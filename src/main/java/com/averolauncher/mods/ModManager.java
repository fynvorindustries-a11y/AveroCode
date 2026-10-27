package com.averolauncher.mods;

import com.averolauncher.download.DownloadManager;
import com.averolauncher.logging.AppLogger;
import com.averolauncher.model.InstalledMod;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

/**
 * Real local mod management: scans the actual mods/ folder inside the
 * configured Minecraft directory, toggles mods on/off by renaming to/from
 * ".disabled" (Fabric/Forge/NeoForge/Quilt all honor this convention so the
 * game simply won't load a disabled jar), removes mod files, and downloads
 * new mods from Modrinth using the same verified streaming DownloadManager
 * used for Minecraft's own files.
 */
public class ModManager {

    private static final String TAG = "ModManager";

    private final DownloadManager downloadManager;
    private final Executor backgroundExecutor;

    public ModManager(DownloadManager downloadManager, Executor backgroundExecutor) {
        this.downloadManager = downloadManager;
        this.backgroundExecutor = backgroundExecutor;
    }

    public Path modsFolder(String minecraftDirectory) {
        return Path.of(minecraftDirectory, "mods");
    }

    /** Real filesystem scan. Only invoked when the user opens/refreshes the Mods page, never on a timer. */
    public CompletableFuture<List<InstalledMod>> scanInstalledMods(String minecraftDirectory) {
        return CompletableFuture.supplyAsync(() -> {
            Path modsDir = modsFolder(minecraftDirectory);
            List<InstalledMod> results = new ArrayList<>();
            if (!Files.isDirectory(modsDir)) {
                return results;
            }
            try (Stream<Path> entries = Files.list(modsDir)) {
                entries.filter(Files::isRegularFile)
                        .filter(p -> {
                            String n = p.getFileName().toString();
                            return n.endsWith(".jar") || n.endsWith(".jar.disabled");
                        })
                        .forEach(p -> {
                            String fileName = p.getFileName().toString();
                            boolean enabled = !fileName.endsWith(".disabled");
                            long size;
                            try {
                                size = Files.size(p);
                            } catch (IOException e) {
                                size = 0;
                            }
                            results.add(new InstalledMod(fileName, p, enabled, size));
                        });
            } catch (IOException e) {
                AppLogger.get().error(TAG, "Failed to scan mods folder", e);
            }
            results.sort((a, b) -> a.displayName().compareToIgnoreCase(b.displayName()));
            return results;
        }, backgroundExecutor);
    }

    public CompletableFuture<Void> setEnabled(InstalledMod mod, boolean enabled) {
        return CompletableFuture.runAsync(() -> {
            try {
                if (enabled && !mod.enabled) {
                    Path target = stripDisabledSuffix(mod.filePath);
                    Files.move(mod.filePath, target, StandardCopyOption.REPLACE_EXISTING);
                    mod.filePath = target;
                    mod.fileName = target.getFileName().toString();
                    mod.enabled = true;
                } else if (!enabled && mod.enabled) {
                    Path target = mod.filePath.resolveSibling(mod.filePath.getFileName() + ".disabled");
                    Files.move(mod.filePath, target, StandardCopyOption.REPLACE_EXISTING);
                    mod.filePath = target;
                    mod.fileName = target.getFileName().toString();
                    mod.enabled = false;
                }
            } catch (IOException e) {
                AppLogger.get().error(TAG, "Failed to toggle mod " + mod.fileName, e);
                throw new RuntimeException("Could not toggle " + mod.displayName() + ": " + e.getMessage(), e);
            }
        }, backgroundExecutor);
    }

    public CompletableFuture<Void> remove(InstalledMod mod) {
        return CompletableFuture.runAsync(() -> {
            try {
                Files.deleteIfExists(mod.filePath);
                AppLogger.get().info(TAG, "Removed mod " + mod.fileName);
            } catch (IOException e) {
                AppLogger.get().error(TAG, "Failed to remove mod " + mod.fileName, e);
                throw new RuntimeException("Could not remove " + mod.displayName() + ": " + e.getMessage(), e);
            }
        }, backgroundExecutor);
    }

    /** Imports an external .jar the user picked via file chooser into the mods folder. */
    public CompletableFuture<Void> importLocalFile(String minecraftDirectory, Path sourceJar) {
        return CompletableFuture.runAsync(() -> {
            Path modsDir = modsFolder(minecraftDirectory);
            try {
                Files.createDirectories(modsDir);
                Path target = modsDir.resolve(sourceJar.getFileName());
                Files.copy(sourceJar, target, StandardCopyOption.REPLACE_EXISTING);
                AppLogger.get().info(TAG, "Imported mod " + sourceJar.getFileName());
            } catch (IOException e) {
                AppLogger.get().error(TAG, "Failed to import " + sourceJar, e);
                throw new RuntimeException("Could not import file: " + e.getMessage(), e);
            }
        }, backgroundExecutor);
    }

    /**
     * Downloads a specific Modrinth file (already resolved by the caller) into
     * the given subfolder under the configured Minecraft directory.
     * <p>
     * Subfolder examples: "mods", "resourcepacks", "shaderpacks", "modpacks", "datapacks".
     * The folder is created if it doesn't already exist.
     */
    public CompletableFuture<Void> installFromModrinth(String minecraftDirectory, String downloadUrl,
                                                        String fileName, String sha1, long size,
                                                        String subfolder) {
        Path destDir = Path.of(minecraftDirectory, subfolder);
        try {
            Files.createDirectories(destDir);
        } catch (IOException e) {
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(new RuntimeException("Could not create " + subfolder + " folder: " + e.getMessage(), e));
            return failed;
        }
        Path target = destDir.resolve(fileName);
        DownloadManager.PendingDownload pending = new DownloadManager.PendingDownload(
                fileName, downloadUrl, target, sha1, size);
        return downloadManager.downloadAll(List.of(pending), 1, task -> {});
    }

    /**
     * Backwards-compatible overload — installs into the "mods" subfolder.
     * Kept so existing call sites that pass 5 args still compile.
     */
    public CompletableFuture<Void> installFromModrinth(String minecraftDirectory, String downloadUrl,
                                                        String fileName, String sha1, long size) {
        return installFromModrinth(minecraftDirectory, downloadUrl, fileName, sha1, size, "mods");
    }

    /**
     * Detects which mod loader (if any) a given installed Minecraft version
     * is using, by checking for loader-signature libraries/version-id
     * patterns Mojang/Fabric/Forge/NeoForge use. Returns null if vanilla.
     */
    public String detectLoader(String minecraftDirectory, String versionId) {
        Path versionJson = Path.of(minecraftDirectory, "versions", versionId, versionId + ".json");
        if (!Files.exists(versionJson)) {
            return null;
        }
        try {
            String content = Files.readString(versionJson);
            if (content.contains("net.fabricmc")) return "fabric";
            if (content.contains("net.neoforged")) return "neoforge";
            if (content.contains("net.minecraftforge")) return "forge";
            if (content.contains("org.quiltmc")) return "quilt";
        } catch (IOException e) {
            AppLogger.get().debug(TAG, "Could not read version json for loader detection: " + e.getMessage());
        }
        return null;
    }

    private Path stripDisabledSuffix(Path disabledPath) {
        String name = disabledPath.getFileName().toString();
        String stripped = name.substring(0, name.length() - ".disabled".length());
        return disabledPath.resolveSibling(stripped);
    }
}
