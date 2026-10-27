package com.averolauncher.service;

import com.averolauncher.auth.MicrosoftAuthService;
import com.averolauncher.download.DownloadManager;
import com.averolauncher.javamgmt.JavaDetectionService;
import com.averolauncher.logging.AppLogger;
import com.averolauncher.minecraft.*;
import com.averolauncher.model.Account;
import com.averolauncher.model.DownloadTask;
import com.averolauncher.model.JavaInstallation;
import com.averolauncher.model.LaunchProfile;
import com.averolauncher.model.LauncherSettings;
import com.averolauncher.model.manifest.AssetIndex;
import com.averolauncher.model.manifest.VersionDetail;
import com.averolauncher.model.manifest.VersionManifest;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * High-level orchestration: given a version id, ensures it's installed
 * (downloading whatever's missing or corrupted), resolves Java, builds the
 * classpath and arguments, and starts the real Minecraft process.
 *
 * This is the class the Play button actually calls. Every step here does
 * real work; there is no simulated or fake path through this class.
 */
public class LaunchService {

    private static final String TAG = "LaunchService";

    /** Shown when Play is pressed with no account selected (instead of silently starting as "Player"). */
    public static final String NO_ACCOUNT_MESSAGE = "No Minecraft account selected. Sign in with Microsoft or your Avero ID "
            + "on the Accounts page, then press Play again.";

    private final ExecutorService backgroundExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "AveroLauncher-Background");
        t.setDaemon(true);
        return t;
    });

    private final VersionManifestService manifestService = new VersionManifestService(backgroundExecutor);
    private final DownloadManager downloadManager = new DownloadManager();
    private final VersionInstaller installer = new VersionInstaller(downloadManager, backgroundExecutor);
    private final JavaDetectionService javaDetectionService = new JavaDetectionService();
    private final MinecraftLauncher minecraftLauncher = new MinecraftLauncher();

    public ExecutorService getBackgroundExecutor() {
        return backgroundExecutor;
    }

    public DownloadManager getDownloadManager() {
        return downloadManager;
    }

    public VersionManifestService getManifestService() {
        return manifestService;
    }

    /**
     * Installs just the vanilla files of one Minecraft version (json, client jar, libraries) without
     * starting it. Mod loaders need this in place. Fails if any file could not be downloaded.
     */
    public CompletableFuture<Void> installVanillaOnly(String mcVersion, LauncherSettings settings, Consumer<DownloadTask> onTask) {
        Path minecraftDir = Path.of(settings.minecraftDirectory);
        List<DownloadTask> tasks = new java.util.concurrent.CopyOnWriteArrayList<>();
        return manifestService.fetchManifest(true)
                .thenCompose(manifest -> {
                    VersionManifest.VersionEntry entry = manifest.versions.stream()
                            .filter(v -> v.id.equals(mcVersion)).findFirst()
                            .orElseThrow(() -> new RuntimeException("Minecraft " + mcVersion + " is not in Mojang's version list."));
                    return manifestService.fetchVersionDetail(entry);
                })
                .thenCompose(detail -> installer.install(minecraftDir, detail, null, settings.concurrentDownloads, t -> {
                    tasks.add(t);
                    if (onTask != null) onTask.accept(t);
                }))
                .thenAccept(v -> {
                    for (DownloadTask t : tasks) {
                        if (t.getStatus() == DownloadTask.Status.FAILED) {
                            throw new RuntimeException("Could not download " + t.getDisplayName() + ": " + t.getErrorMessage());
                        }
                    }
                });
    }

    /**
     * A Java of at least {@code requiredMajor}: the one chosen in Settings if it qualifies, otherwise the
     * closest detected install that does. Null when nothing suitable is installed.
     */
    public JavaInstallation findJava(LauncherSettings settings, int requiredMajor) {
        List<JavaInstallation> all = javaDetectionService.detectAll();
        if (settings.javaPath != null && !settings.javaPath.isBlank()) {
            for (JavaInstallation j : all) {
                if (j.path.equals(settings.javaPath) && j.majorVersion >= requiredMajor) return j;
            }
        }
        JavaInstallation best = javaDetectionService.selectBestFor(requiredMajor, all);
        return best != null && best.majorVersion >= requiredMajor ? best : null;
    }

    /**
     * Full pipeline: fetch version detail -> fetch asset index -> install
     * missing/corrupted files -> extract natives -> resolve Java -> launch.
     * Reports per-file download progress via onDownloadTask and returns the
     * live Process once Minecraft has actually started.
     */
    public CompletableFuture<Process> installAndLaunch(
            VersionManifest.VersionEntry versionEntry,
            LauncherSettings settings,
            LaunchProfile profile,
            Account account,
            Consumer<DownloadTask> onDownloadTask,
            Consumer<String> onGameOutput,
            Consumer<String> onStatusUpdate) {
        return installAndLaunch(versionEntry, settings, profile, account, null,
                onDownloadTask, onGameOutput, onStatusUpdate);
    }

    /**
     * Same as above, but accepts a MicrosoftAuthService so an expired
     * Minecraft access token on a MICROSOFT account can be silently
     * refreshed via the account's stored refresh token before launch,
     * instead of launching with a stale token that would fail once the
     * game itself tries to use it.
     */
    public CompletableFuture<Process> installAndLaunch(
            VersionManifest.VersionEntry versionEntry,
            LauncherSettings settings,
            LaunchProfile profile,
            Account account,
            MicrosoftAuthService authService,
            Consumer<DownloadTask> onDownloadTask,
            Consumer<String> onGameOutput,
            Consumer<String> onStatusUpdate) {

        if (account == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(NO_ACCOUNT_MESSAGE));
        }
        Path minecraftDir = Path.of(settings.minecraftDirectory);

        CompletableFuture<Account> readyAccount = ensureFreshToken(account, authService, onStatusUpdate)
                .thenCompose(a -> com.averolauncher.store.CapeSupport.prepare(settings, null).thenApply(ignored -> a));

        return readyAccount.thenCompose(resolvedAccount ->
                manifestService.fetchVersionDetail(versionEntry)
                .thenCompose(detail -> {
                    updateStatus(onStatusUpdate, "Fetching asset index for " + detail.id);
                    return manifestService.fetchAssetIndex(detail.assetIndex)
                            .thenApply(assetIndex -> new Object[]{detail, assetIndex});
                })
                .thenCompose(pair -> {
                    VersionDetail detail = (VersionDetail) pair[0];
                    AssetIndex assetIndex = (AssetIndex) pair[1];
                    updateStatus(onStatusUpdate, "Checking files for " + detail.id);
                    return installer.install(minecraftDir, detail, assetIndex, settings.concurrentDownloads, onDownloadTask)
                            .thenApply(v -> detail);
                })
                .thenCompose(detail -> CompletableFuture.supplyAsync(() -> {
                    try {
                        updateStatus(onStatusUpdate, "Extracting native libraries");
                        installer.extractNatives(minecraftDir, detail);
                        return detail;
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to extract native libraries: " + e.getMessage(), e);
                    }
                }, backgroundExecutor))
                .thenCompose(detail -> CompletableFuture.supplyAsync(() -> {
                    updateStatus(onStatusUpdate, "Resolving Java runtime");
                    JavaInstallation javaInstall = resolveJava(settings, detail);
                    if (javaInstall == null) {
                        throw new RuntimeException("No suitable Java installation found. "
                                + "Install a JDK/JRE or set a Java path in Settings.");
                    }
                    return new Object[]{detail, javaInstall};
                }, backgroundExecutor))
                .thenCompose(pair -> CompletableFuture.supplyAsync(() -> {
                    VersionDetail detail = (VersionDetail) pair[0];
                    JavaInstallation javaInstall = (JavaInstallation) pair[1];

                    LaunchContext ctx = buildContext(minecraftDir, detail, javaInstall, settings, profile, resolvedAccount);

                    updateStatus(onStatusUpdate, "Starting Minecraft " + detail.id);
                    try {
                        return trackPlaytime(minecraftLauncher.launch(detail, ctx, onGameOutput), detail.id, ctx);
                    } catch (Exception e) {
                        throw new RuntimeException("Launch failed: " + e.getMessage(), e);
                    }
                }, backgroundExecutor)));
    }

    /**
     * If the given account is a MICROSOFT account whose Minecraft token has
     * expired, silently refreshes it via the stored Microsoft refresh token
     * before launch. Offline accounts and already-valid tokens pass through
     * unchanged. If authService is null (no client ID configured) the
     * existing account is used as-is; MinecraftLauncher will simply fail
     * with a clear auth error if the token turns out to be invalid.
     */
    private CompletableFuture<Account> ensureFreshToken(Account account, MicrosoftAuthService authService,
                                                          Consumer<String> onStatusUpdate) {
        if (account == null || account.type != Account.Type.MICROSOFT || account.isMinecraftTokenValid()) {
            return CompletableFuture.completedFuture(account);
        }
        if (authService == null) {
            AppLogger.get().warn(TAG, "Microsoft token expired but no auth service available to refresh it");
            return CompletableFuture.completedFuture(account);
        }
        updateStatus(onStatusUpdate, "Refreshing Microsoft sign-in...");
        // Saves the rotated tokens too, so the next launch/start doesn't need a fresh browser login.
        return com.averolauncher.auth.AccountSessions.refreshAndSave(authService, account);
    }

    private JavaInstallation resolveJava(LauncherSettings settings, VersionDetail detail) {
        if (settings.javaPath != null && !settings.javaPath.isBlank()) {
            List<JavaInstallation> all = javaDetectionService.detectAll();
            return all.stream()
                    .filter(j -> j.path.equals(settings.javaPath))
                    .findFirst()
                    .orElseGet(() -> {
                        AppLogger.get().warn(TAG, "Configured Java path not found among detected installs, auto-selecting instead");
                        return autoSelect(detail);
                    });
        }
        return autoSelect(detail);
    }

    private JavaInstallation autoSelect(VersionDetail detail) {
        List<JavaInstallation> all = javaDetectionService.detectAll();
        if (all.isEmpty()) return null;
        int required = (detail.javaVersion != null && detail.javaVersion.majorVersion > 0)
                ? detail.javaVersion.majorVersion : 8;
        return javaDetectionService.selectBestFor(required, all);
    }

    private LaunchContext buildContext(Path minecraftDir, VersionDetail detail, JavaInstallation javaInstall,
                                        LauncherSettings settings, LaunchProfile profile, Account account) {
        LaunchContext ctx = new LaunchContext();
        ctx.minecraftDir = minecraftDir;
        ctx.gameDir = (profile != null && profile.gameDirectoryOverride != null)
                ? Path.of(profile.gameDirectoryOverride) : minecraftDir;
        ctx.javaExecutable = Path.of(javaInstall.path);
        ctx.versionId = detail.id;
        ctx.assetsId = detail.assets != null ? detail.assets : (detail.assetIndex != null ? detail.assetIndex.id : "legacy");
        ctx.assetsDir = minecraftDir.resolve("assets");
        ctx.nativesDir = minecraftDir.resolve("versions").resolve(detail.id).resolve(detail.id + "-natives");
        ctx.classpath = ClasspathBuilder.build(minecraftDir, detail);

        if (account == null || account.username == null || account.username.isBlank()) {
            throw new IllegalStateException(NO_ACCOUNT_MESSAGE);
        }
        ctx.authPlayerName = account.username;
        // An offline-type account always has the standard offline UUID for its name if none was stored.
        ctx.authUuid = (account.minecraftUuid != null && !account.minecraftUuid.isBlank())
                ? account.minecraftUuid
                : java.util.UUID.nameUUIDFromBytes(("OfflinePlayer:" + account.username)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        ctx.authAccessToken = account.type == Account.Type.MICROSOFT ? account.minecraftAccessToken : "0";
        ctx.userType = account.type == Account.Type.MICROSOFT ? "msa" : "legacy";

        ctx.maxRamMb = (profile != null && profile.maxRamMbOverride != null) ? profile.maxRamMbOverride : settings.maxRamMb;
        // Starting heap is derived from the max (no separate user setting); a profile may still override it.
        int autoMin = com.averolauncher.util.MemoryAdvisor.autoMinMb(ctx.maxRamMb);
        ctx.minRamMb = (profile != null && profile.minRamMbOverride != null)
                ? Math.min(profile.minRamMbOverride, ctx.maxRamMb) : autoMin;
        ctx.windowWidth = profile != null ? profile.windowWidth : 1280;
        ctx.windowHeight = profile != null ? profile.windowHeight : 720;
        ctx.fullscreen = profile != null && profile.fullscreen;
        ctx.extraJvmArgs = profile != null ? profile.jvmArgsExtra : "";
        ctx.quickPlayServerAddress = profile != null ? profile.serverAddressAutoJoin : null;

        // Avero capes without a mod: the agent jar is loaded by the JVM itself (works on every loader/version).
        String capeAgent = com.averolauncher.store.CapeSupport.agentArgument(settings, account);
        if (capeAgent != null) ctx.extraJvmArgList.add(capeAgent);

        // Offline accounts wear their skin through the agent above (no resource pack, no options.txt edits).
        // Older launcher versions did use a pack, so take any leftover one out of the way.
        com.averolauncher.skins.LegacySkinPack.remove(ctx.gameDir);
        if (capeAgent == null && account != null && account.type == Account.Type.OFFLINE
                && account.offlineSkinId != null && !account.offlineSkinId.isBlank()) {
            AppLogger.get().warn(TAG, "The skin agent could not be loaded, so " + account.username
                    + " will show a default skin in-game this time.");
        }

        // ...and make sure other Avero players get the same skin (re-publishes only if it changed or the server missed it).
        // Fire and forget: a slow or unreachable server must never delay or break the launch.
        try {
            com.averolauncher.skins.SkinSharing.sync(new com.averolauncher.repository.AccountRepository(), account);
        } catch (Exception ignored) { }

        return ctx;
    }

    /**
     * Launches a locally-installed version by its version ID (e.g. a Fabric or
     * Forge profile whose JSON lives under versions/<id>/<id>.json). This is the
     * correct entry point for mod-loader versions — they are not in Mojang's
     * version manifest and must be read from disk first.
     *
     * Internally reads the version JSON from disk, resolves inheritsFrom, and
     * then runs the same install+launch pipeline as installAndLaunch().
     */
    public CompletableFuture<Process> installAndLaunchLocal(
            String versionId,
            LauncherSettings settings,
            LaunchProfile profile,
            Account account,
            MicrosoftAuthService authService,
            Consumer<DownloadTask> onDownloadTask,
            Consumer<String> onGameOutput,
            Consumer<String> onStatusUpdate) {

        if (account == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(NO_ACCOUNT_MESSAGE));
        }
        Path minecraftDir = Path.of(settings.minecraftDirectory);

        CompletableFuture<Account> readyAccount = ensureFreshToken(account, authService, onStatusUpdate)
                .thenCompose(a -> com.averolauncher.store.CapeSupport.prepare(settings, null).thenApply(ignored -> a));

        return readyAccount.thenCompose(resolvedAccount ->
                manifestService.fetchVersionDetailLocal(versionId, minecraftDir)
                .thenCompose(detail -> {
                    updateStatus(onStatusUpdate, "Fetching asset index for " + detail.id);
                    if (detail.assetIndex == null) {
                        throw new RuntimeException("Version " + detail.id + " has no asset index — "
                                + "make sure the base Minecraft version is installed first.");
                    }
                    return manifestService.fetchAssetIndex(detail.assetIndex)
                            .thenApply(assetIndex -> new Object[]{detail, assetIndex});
                })
                .thenCompose(pair -> {
                    VersionDetail detail = (VersionDetail) pair[0];
                    com.averolauncher.model.manifest.AssetIndex assetIndex =
                            (com.averolauncher.model.manifest.AssetIndex) pair[1];
                    updateStatus(onStatusUpdate, "Checking files for " + detail.id);
                    return installer.install(minecraftDir, detail, assetIndex, settings.concurrentDownloads, onDownloadTask)
                            .thenApply(v -> detail);
                })
                .thenCompose(detail -> CompletableFuture.supplyAsync(() -> {
                    try {
                        updateStatus(onStatusUpdate, "Extracting native libraries");
                        installer.extractNatives(minecraftDir, detail);
                        return detail;
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to extract native libraries: " + e.getMessage(), e);
                    }
                }, backgroundExecutor))
                .thenCompose(detail -> CompletableFuture.supplyAsync(() -> {
                    updateStatus(onStatusUpdate, "Resolving Java runtime");
                    JavaInstallation javaInstall = resolveJava(settings, detail);
                    if (javaInstall == null) {
                        throw new RuntimeException("No suitable Java installation found. "
                                + "Install a JDK/JRE or set a Java path in Settings.");
                    }
                    return new Object[]{detail, javaInstall};
                }, backgroundExecutor))
                .thenCompose(pair -> CompletableFuture.supplyAsync(() -> {
                    VersionDetail detail = (VersionDetail) pair[0];
                    JavaInstallation javaInstall = (JavaInstallation) pair[1];
                    LaunchContext ctx = buildContext(minecraftDir, detail, javaInstall, settings, profile, resolvedAccount);
                    updateStatus(onStatusUpdate, "Starting Minecraft " + detail.id);
                    try {
                        return trackPlaytime(minecraftLauncher.launch(detail, ctx, onGameOutput), detail.id, ctx);
                    } catch (Exception e) {
                        throw new RuntimeException("Launch failed: " + e.getMessage(), e);
                    }
                }, backgroundExecutor)));
    }

    /**
     * Hands the freshly started game process to the rank system so verified playtime can earn Avero Points.
     * Observational only: a failure here is swallowed and never affects the launch.
     */
    private Process trackPlaytime(Process process, String versionId, LaunchContext ctx) {
        try {
            com.averolauncher.rank.RankService.get().onGameLaunched(process, versionId, ctx != null ? ctx.quickPlayServerAddress : null);
        } catch (Throwable t) {
            AppLogger.get().warn(TAG, "Playtime tracking not started: " + t);
        }
        return process;
    }

    private void updateStatus(Consumer<String> onStatusUpdate, String message) {
        AppLogger.get().info(TAG, message);
        if (onStatusUpdate != null) {
            onStatusUpdate.accept(message);
        }
    }

    public void shutdown() {
        backgroundExecutor.shutdown();
    }
}
