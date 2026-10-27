package com.averolauncher;

import com.averolauncher.auth.MicrosoftAuthService;
import com.averolauncher.logging.AppLogger;
import com.averolauncher.model.LauncherSettings;
import com.averolauncher.mods.ModManager;
import com.averolauncher.repository.AccountRepository;
import com.averolauncher.repository.SettingsRepository;
import com.averolauncher.service.LaunchService;
import com.averolauncher.service.CurseForgeService;
import com.averolauncher.service.ModrinthService;
import com.averolauncher.ui.MainWindow;
import com.averolauncher.update.UpdateChecker;
import com.averolauncher.update.UpdateDownloader;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * Entry point. Keeps startup work minimal so the launcher opens quickly even
 * on low-end hardware: settings are loaded synchronously (small JSON file),
 * but all network-bound work (version metadata, downloads) is deferred to
 * background threads started after the window is already visible.
 */
public class AveroLauncherApplication extends Application {

    private static final String TAG = "Application";
    private SettingsRepository settingsRepository;
    private LauncherSettings settings;
    private LaunchService launchService;
    private ModrinthService modrinthService;
    private CurseForgeService curseForgeService;
    private ModManager modManager;
    private AccountRepository accountRepository;
    private MicrosoftAuthService microsoftAuthService;
    private UpdateChecker updateChecker;
    private UpdateDownloader updateDownloader;

    @Override
    public void init() {
        AppLogger.get().info(TAG, "AveroLauncher starting up");
        settingsRepository = new SettingsRepository();
        settings = settingsRepository.load();

        // First-run: auto-set the Minecraft directory to the canonical .averolauncher path
        // so the user can hit Play immediately without visiting Settings first.
        if (settings.minecraftDirectory == null || settings.minecraftDirectory.isBlank()) {
            settings.minecraftDirectory = com.averolauncher.util.PathResolver.defaultMinecraftDir().toString();
            try {
                java.nio.file.Files.createDirectories(java.nio.file.Path.of(settings.minecraftDirectory));
                AppLogger.get().info(TAG, "Created default Minecraft directory: " + settings.minecraftDirectory);
            } catch (java.io.IOException e) {
                AppLogger.get().warn(TAG, "Could not create default MC directory: " + e.getMessage());
            }
            settingsRepository.save(settings);
        }

        com.averolauncher.download.MirrorRouter.setEnabled(settings.useMirrorFallback);
        launchService = new LaunchService();
        modrinthService = new ModrinthService(launchService.getBackgroundExecutor());
        curseForgeService = new CurseForgeService(
                "$2a$10$.fDBpr22q6GAakMCVDe/ZO7WRj7QihzeMRz4WwODCI0ar5s514hnO");
        modManager = new ModManager(launchService.getDownloadManager(), launchService.getBackgroundExecutor());
        accountRepository = new AccountRepository();
        // Always use AveroLauncher's built-in Azure AD app registration — no per-machine
        // setup, no manual client ID entry, sign-in just works on every install.
        microsoftAuthService = new MicrosoftAuthService(
                com.averolauncher.auth.MicrosoftAuthConfig.DEFAULT_CLIENT_ID,
                launchService.getBackgroundExecutor());
        updateChecker = new UpdateChecker(launchService.getBackgroundExecutor());
        updateDownloader = new UpdateDownloader(launchService.getBackgroundExecutor());
        AppLogger.get().info(TAG, "Settings loaded. Minecraft dir: " + settings.minecraftDirectory);
    }

    @Override
    public void start(Stage primaryStage) {
        MainWindow mainWindow = new MainWindow(primaryStage, settings, settingsRepository, launchService,
                modrinthService, curseForgeService, modManager, accountRepository, microsoftAuthService,
                updateChecker, updateDownloader);
        Scene scene = mainWindow.createScene();

        primaryStage.setTitle("AveroLauncher");
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(1000);
        primaryStage.setMinHeight(650);
        primaryStage.setWidth(1280);
        primaryStage.setHeight(720);

        try (var iconStream = getClass().getResourceAsStream("/icons/app-icon.png")) {
            if (iconStream != null) {
                primaryStage.getIcons().add(new javafx.scene.image.Image(iconStream));
            }
        } catch (Exception e) {
            AppLogger.get().warn(TAG, "Could not load application icon: " + e.getMessage());
        }

        primaryStage.setOnCloseRequest(event -> {
            AppLogger.get().info(TAG, "Close requested, behavior=" + settings.closeBehavior);
            Platform.exit();
        });

        primaryStage.show();
        AppLogger.get().info(TAG, "Main window shown");
        // If the update helper just relaunched us, tell it we came up fine (it rolls back otherwise).
        com.averolauncher.update.incremental.StartupHealth.signalIfRequested();

        if (settings.checkForUpdatesOnStartup) {
            // Deferred rather than checked in init(): this is a network call, and startup
            // work stays off the critical path so the window appears quickly (see the
            // class-level javadoc). Failures are logged and swallowed inside UpdateChecker
            // itself, so there's nothing to handle here beyond showing a result if found.
            updateChecker.checkForUpdate().thenAccept(maybeUpdate ->
                    maybeUpdate.ifPresent(mainWindow::showUpdateAvailable));
        }
    }

    @Override
    public void stop() {
        AppLogger.get().info(TAG, "AveroLauncher shutting down");
        try {
            com.averolauncher.rank.RankService.get().shutdown();   // flush progression before the logger closes
        } catch (Throwable t) {
            AppLogger.get().warn(TAG, "Rank shutdown skipped: " + t);
        }
        if (launchService != null) {
            launchService.shutdown();
        }
        AppLogger.get().shutdown();
    }

    public static void main(String[] args) {
        // Must be set before any networking happens. Some Windows setups have a broken
        // or half-configured IPv6 route (common with certain ISPs, VPNs, and antivirus
        // network filters) that causes HTTPS calls to intermittently hang until timeout
        // or fail the TLS handshake outright, depending on which address the JVM tries
        // first. Preferring IPv4 avoids that flakiness for a dual-stack host like Mojang's.
        System.setProperty("java.net.preferIPv4Stack", "true");
        // Java ignores the OS proxy settings unless told otherwise. On networks where the browser
        // reaches the internet through a system/corporate/ISP proxy, every direct connection from
        // the JVM just times out ("HTTP connect timed out"). This makes ProxySelector.getDefault()
        // (which HttpClient uses) follow the Windows / macOS / GNOME proxy configuration. It also
        // must be set before the first network call, which is why it lives here.
        System.setProperty("java.net.useSystemProxies", "true");
        launch(args);
    }
}