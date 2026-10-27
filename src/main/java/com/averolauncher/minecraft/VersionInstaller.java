package com.averolauncher.minecraft;

import com.averolauncher.download.DownloadManager;
import com.averolauncher.logging.AppLogger;
import com.averolauncher.model.DownloadTask;
import com.averolauncher.model.manifest.AssetIndex;
import com.averolauncher.model.manifest.VersionDetail;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Given a fully-resolved VersionDetail and asset index, determines every file
 * that must exist locally (client jar, libraries, natives, loose assets) and
 * hands the concrete list to DownloadManager. Writes files under the
 * standard Minecraft directory layout so this installation is compatible
 * with other launchers and with the game itself, which expects:
 *
 *   <mcDir>/versions/<id>/<id>.jar
 *   <mcDir>/versions/<id>/<id>.json
 *   <mcDir>/libraries/<maven-path>
 *   <mcDir>/assets/indexes/<assetIndexId>.json
 *   <mcDir>/assets/objects/<xx>/<hash>
 */
public class VersionInstaller {

    private static final String TAG = "VersionInstaller";

    private final DownloadManager downloadManager;
    private final Executor backgroundExecutor;

    public VersionInstaller(DownloadManager downloadManager, Executor backgroundExecutor) {
        this.downloadManager = downloadManager;
        this.backgroundExecutor = backgroundExecutor;
    }

    /**
     * Installs (or verifies-and-skips-if-already-present) everything needed
     * to launch the given version. Progress for each individual file is
     * exposed via onTaskCreated, so a Downloads page can bind to per-file
     * progress bars in real time.
     *
     * Also writes versions/{id}/{id}.json to disk: Minecraft itself reads this
     * file at startup (for logging config, compliance level, etc) and some mod
     * loaders also require it. Without it, even a fully-downloaded version
     * can fail to start.
     */
    public CompletableFuture<Void> install(Path minecraftDir, VersionDetail detail, AssetIndex assetIndex,
                                            int concurrency, Consumer<DownloadTask> onTaskCreated) {
        return CompletableFuture.supplyAsync(() -> {
            // Write the resolved version JSON before we start downloading, so that
            // even a partially-completed install has a consistent metadata file.
            writeVersionJson(minecraftDir, detail);
            return buildPendingDownloads(minecraftDir, detail, assetIndex);
        }, backgroundExecutor)
                .thenCompose(pending -> {
                    AppLogger.get().info(TAG, "Installing " + detail.id + ": " + pending.size() + " files to verify/download");
                    return downloadManager.downloadAll(pending, concurrency, onTaskCreated);
                });
    }

    /** Writes versions/{id}/{id}.json so Minecraft and mod loaders can read version metadata at runtime. */
    private void writeVersionJson(Path minecraftDir, VersionDetail detail) {
        Path versionDir = minecraftDir.resolve("versions").resolve(detail.id);
        Path versionJson = versionDir.resolve(detail.id + ".json");
        try {
            Files.createDirectories(versionDir);
            if (!Files.exists(versionJson)) {
                // Serialize the fully-merged VersionDetail back to JSON.
                String json = new Gson().toJson(detail);
                Files.writeString(versionJson, json);
                AppLogger.get().info(TAG, "Wrote version JSON for " + detail.id);
            }
        } catch (IOException e) {
            // Non-fatal: Minecraft may still launch if the game doesn't strictly need it.
            AppLogger.get().warn(TAG, "Could not write version JSON for " + detail.id + ": " + e.getMessage());
        }
    }

    private static final String MAVEN_CENTRAL = "https://repo1.maven.org/maven2/";
    private static final String FABRIC_MAVEN   = "https://maven.fabricmc.net/";
    private static final String NEOFORGE_MAVEN = "https://maven.neoforged.net/releases/";
    private static final String FORGE_MAVEN    = "https://maven.minecraftforge.net/";

    private List<DownloadManager.PendingDownload> buildPendingDownloads(Path minecraftDir, VersionDetail detail, AssetIndex assetIndex) {
        List<DownloadManager.PendingDownload> pending = new ArrayList<>();

        // 1. Client jar — only present on vanilla versions; mod-loader inheritsFrom versions skip this.
        if (detail.downloads != null && detail.downloads.client != null) {
            Path clientJar = minecraftDir.resolve("versions").resolve(detail.id).resolve(detail.id + ".jar");
            pending.add(new DownloadManager.PendingDownload(
                    "Minecraft " + detail.id + " client",
                    detail.downloads.client.url,
                    clientJar,
                    detail.downloads.client.sha1,
                    detail.downloads.client.size));
        }

        // 2. Libraries (including native-classifier jars for the current OS)
        if (detail.libraries != null) {
            for (VersionDetail.Library lib : detail.libraries) {
                if (lib.rules != null && !RuleEvaluator.librariesRulesAllow(lib.rules)) {
                    continue; // library doesn't apply to this OS
                }

                // 2a. Regular (non-native) artifact
                if (lib.downloads != null && lib.downloads.artifact != null && lib.downloads.artifact.path != null) {
                    // Format A: explicit path + URL in the downloads block (vanilla/NeoForge modern)
                    Path libPath = minecraftDir.resolve("libraries").resolve(lib.downloads.artifact.path);
                    pending.add(new DownloadManager.PendingDownload(
                            "Library " + lib.name,
                            lib.downloads.artifact.url,
                            libPath,
                            lib.downloads.artifact.sha1,
                            lib.downloads.artifact.size));
                } else if ((lib.downloads == null || lib.downloads.artifact == null) && lib.name != null) {
                    // Format B: Fabric/older-Forge — no explicit downloads block; derive path and URL
                    // from Maven coordinates. We try Fabric Maven first (for net.fabricmc), then
                    // NeoForge Maven (for net.neoforged), then Forge Maven, then Maven Central.
                    String mavenPath = ClasspathBuilder.mavenPathFromCoordinates(lib.name);
                    if (mavenPath != null) {
                        Path libPath = minecraftDir.resolve("libraries").resolve(mavenPath);
                        // Prefer the repository the loader itself names (Fabric, Quilt, old Forge); guess by group otherwise.
                        String url = (lib.url != null && lib.url.startsWith("http"))
                                ? (lib.url.endsWith("/") ? lib.url : lib.url + "/") + mavenPath
                                : resolveLibraryUrl(lib.name, mavenPath);
                        // sha1 and size are unknown for these; DownloadManager will skip re-download
                        // if the file already exists (no-hash = trust existing behaviour).
                        pending.add(new DownloadManager.PendingDownload(
                                "Library " + lib.name,
                                url,
                                libPath,
                                null,   // no hash in metadata
                                0L));
                    }
                }

                // 2b. Native-classifier jar for this OS
                if (lib.natives != null && lib.downloads != null && lib.downloads.classifiers != null) {
                    String classifierKey = lib.natives.get(RuleEvaluator.currentOsKeyword());
                    if (classifierKey != null) {
                        VersionDetail.DownloadArtifact nativeArtifact = lib.downloads.classifiers.get(classifierKey);
                        if (nativeArtifact != null && nativeArtifact.path != null) {
                            Path nativePath = minecraftDir.resolve("libraries").resolve(nativeArtifact.path);
                            pending.add(new DownloadManager.PendingDownload(
                                    "Native " + lib.name,
                                    nativeArtifact.url,
                                    nativePath,
                                    nativeArtifact.sha1,
                                    nativeArtifact.size));
                        }
                    }
                }
            }
        }

        // 3. Asset index file itself
        if (detail.assetIndex != null) {
            Path assetIndexPath = minecraftDir.resolve("assets").resolve("indexes").resolve(detail.assetIndex.id + ".json");
            pending.add(new DownloadManager.PendingDownload(
                    "Asset index " + detail.assetIndex.id,
                    detail.assetIndex.url,
                    assetIndexPath,
                    detail.assetIndex.sha1,
                    detail.assetIndex.size));
        }

        // 4. Individual asset objects (sounds, textures, lang files, etc)
        if (assetIndex != null && assetIndex.objects != null) {
            for (AssetIndex.AssetObject obj : assetIndex.objects.values()) {
                Path assetPath = minecraftDir.resolve("assets").resolve("objects").resolve(obj.storagePath());
                String url = "https://resources.download.minecraft.net/" + obj.storagePath();
                pending.add(new DownloadManager.PendingDownload(
                        "Asset " + obj.hash.substring(0, 8),
                        url,
                        assetPath,
                        obj.hash,
                        obj.size));
            }
        }

        return pending;
    }

    /**
     * Picks the best Maven repository URL for a library based on its group ID.
     * Order of preference: known group prefixes → specific repos; fallback to Maven Central.
     */
    private static String resolveLibraryUrl(String mavenCoord, String mavenPath) {
        String group = mavenCoord.split(":")[0];
        if (group.startsWith("net.fabricmc") || group.startsWith("com.github.ornithemc")) {
            return FABRIC_MAVEN + mavenPath;
        }
        if (group.startsWith("net.neoforged")) {
            return NEOFORGE_MAVEN + mavenPath;
        }
        if (group.startsWith("net.minecraftforge")) {
            return FORGE_MAVEN + mavenPath;
        }
        return MAVEN_CENTRAL + mavenPath;
    }

    /**
     * Extracts native library jars (LWJGL natives, etc) into the version's
     * natives directory, honoring each library's "extract.exclude" rules
     * (typically META-INF is excluded). Must run after libraries are
     * downloaded and before launch.
     */
    public void extractNatives(Path minecraftDir, VersionDetail detail) throws Exception {
        Path nativesDir = minecraftDir.resolve("versions").resolve(detail.id).resolve(detail.id + "-natives");
        java.nio.file.Files.createDirectories(nativesDir);

        if (detail.libraries == null) return;
        String nativesKey = RuleEvaluator.currentNativesClassifierKey();

        for (VersionDetail.Library lib : detail.libraries) {
            if (lib.rules != null && !RuleEvaluator.librariesRulesAllow(lib.rules)) continue;
            if (lib.natives == null || lib.downloads == null || lib.downloads.classifiers == null) continue;

            String classifierKey = lib.natives.get(RuleEvaluator.currentOsKeyword());
            if (classifierKey == null) continue;
            VersionDetail.DownloadArtifact nativeArtifact = lib.downloads.classifiers.get(classifierKey);
            if (nativeArtifact == null || nativeArtifact.path == null) continue;

            Path jarPath = minecraftDir.resolve("libraries").resolve(nativeArtifact.path);
            if (!java.nio.file.Files.exists(jarPath)) continue;

            List<String> excludes = (lib.extract != null && lib.extract.exclude != null)
                    ? lib.extract.exclude : List.of("META-INF/");

            try (var zip = new java.util.zip.ZipFile(jarPath.toFile())) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    boolean excluded = excludes.stream().anyMatch(entry.getName()::startsWith);
                    if (excluded) continue;

                    Path outPath = nativesDir.resolve(entry.getName()).normalize();
                    if (!outPath.startsWith(nativesDir)) {
                        continue; // guard against zip-slip
                    }
                    java.nio.file.Files.createDirectories(outPath.getParent());
                    try (var in = zip.getInputStream(entry)) {
                        java.nio.file.Files.copy(in, outPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
        AppLogger.get().info(TAG, "Extracted natives for " + detail.id + " to " + nativesDir);
    }
}
