package com.averolauncher.ui.pages;

import com.averolauncher.javamgmt.JavaDetectionService;
import com.averolauncher.logging.AppLogger;
import com.averolauncher.model.JavaInstallation;
import com.averolauncher.model.LauncherSettings;
import com.averolauncher.repository.SettingsRepository;
import com.averolauncher.ui.NavPage;
import com.averolauncher.util.PathResolver;
import com.averolauncher.util.UiAccents;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class SettingsPage {

    private static final String TAG = "SettingsPage";
    private final LauncherSettings settings;
    private final SettingsRepository repository;
    private final com.averolauncher.ui.AppContext appContext; // for rebuilding MicrosoftAuthService
    // Memory controls, shared so Low-End Mode (Advanced card) can adjust them live.
    private Slider maxRamSlider;
    private Label maxRamLabelRef;
    private Runnable ramHintRefresh;
    private final Executor bg = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "settings-bg"); t.setDaemon(true); return t;
    });

    public SettingsPage(LauncherSettings settings, SettingsRepository repository,
                        com.averolauncher.ui.AppContext appContext) {
        this.settings = settings;
        this.repository = repository;
        this.appContext = appContext;
    }

    public VBox build() {
        VBox root = new VBox(24);
        root.getStyleClass().add("page");
        root.setPadding(new Insets(32));

        Label title = new Label("Settings");
        title.getStyleClass().add("page-title");

        HBox titleRow = new HBox(14, UiAccents.pageBadge(NavPage.SETTINGS, 40), title);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        // First-run banner if directory is not set
        VBox firstRunBanner = buildFirstRunBanner();

        root.getChildren().addAll(titleRow, UiAccents.pageUnderline(), firstRunBanner,
                buildMinecraftSection(),
                buildAdvancedSection(),
                buildJavaSection(),
                buildDownloadsSection(),
                buildAppearanceSection(),
                buildUpdatesSection());
        return root;
    }

    // ─── Updates section ────────────────────────────────────────────────────

    private VBox buildUpdatesSection() {
        VBox section = sectionBox("Updates");
        GridPane grid = new GridPane();
        grid.setHgap(12); grid.setVgap(12);

        CheckBox autoCheck = new CheckBox("Check for updates on startup");
        autoCheck.setSelected(settings.checkForUpdatesOnStartup);
        autoCheck.selectedProperty().addListener((obs, ov, nv) -> { settings.checkForUpdatesOnStartup = nv; persist(); });

        Label version = new Label("Current version: v" + com.averolauncher.update.AppVersion.CURRENT);
        version.getStyleClass().add("about-path-label");

        Label statusLabel = new Label();
        statusLabel.getStyleClass().add("about-mirror-note");
        statusLabel.setWrapText(true);

        Button checkNow = new Button("Check for updates now");
        checkNow.getStyleClass().add("secondary-button");
        checkNow.setOnAction(e -> {
            checkNow.setDisable(true);
            statusLabel.setText("Checking\u2026");
            appContext.updateChecker.checkForUpdate().whenComplete((maybeUpdate, err) ->
                    Platform.runLater(() -> {
                        checkNow.setDisable(false);
                        if (err != null) {
                            statusLabel.setText("Couldn't check for updates: " + err.getMessage());
                        } else if (maybeUpdate.isEmpty()) {
                            statusLabel.setText("You're up to date.");
                        } else {
                            statusLabel.setText(maybeUpdate.get().displayName
                                    + " is available \u2014 see the About page to download it.");
                        }
                    }));
        });

        if (settings.skippedUpdateVersion != null && !settings.skippedUpdateVersion.isBlank()) {
            Button forgetSkip = new Button("Clear skipped version (" + settings.skippedUpdateVersion + ")");
            forgetSkip.getStyleClass().add("secondary-button");
            forgetSkip.setOnAction(e -> {
                settings.skippedUpdateVersion = null;
                persist();
                forgetSkip.setDisable(true);
                forgetSkip.setText("Cleared");
            });
            grid.add(forgetSkip, 0, 3, 2, 1);
        }

        grid.add(autoCheck, 0, 0, 2, 1);
        grid.add(version, 0, 1, 2, 1);
        grid.add(new HBox(10, checkNow, statusLabel), 0, 2, 2, 1);
        section.getChildren().add(grid);
        return section;
    }

    // ─── First-run banner ───────────────────────────────────────────────────

    private VBox buildFirstRunBanner() {
        boolean needsSetup = settings.minecraftDirectory == null || settings.minecraftDirectory.isBlank();
        VBox banner = new VBox(10);
        banner.setPadding(new Insets(16));
        banner.setStyle("-fx-background-color: rgba(91,95,240,0.15); -fx-background-radius: 0; -fx-border-color: rgba(91,95,240,0.35); -fx-border-radius: 0; -fx-border-width: 1;");

        if (!needsSetup) {
            banner.setVisible(false);
            banner.setManaged(false);
            return banner;
        }

        Label heading = new Label("Welcome to AveroLauncher — let's get set up");
        heading.getStyleClass().add("section-header");

        Label info = new Label(
                "1. Set your Minecraft directory below (or click 'Use Default' to use .averolauncher in your AppData).\n" +
                "2. Sign in on the Accounts page with Microsoft or your Avero ID.\n" +
                "3. Go to Play, pick a Minecraft version, and hit Play.");
        info.setWrapText(true);
        info.getStyleClass().add("page-subtitle");

        Button useDefault = new Button("✓  Use Default (.averolauncher)");
        useDefault.getStyleClass().add("play-button");
        useDefault.setStyle("-fx-padding: 8 20 8 20;");
        useDefault.setOnAction(e -> {
            String defaultDir = PathResolver.defaultMinecraftDir().toString();
            settings.minecraftDirectory = defaultDir;
            persist();
            try { Files.createDirectories(Path.of(defaultDir)); } catch (IOException ignored) {}
            banner.setVisible(false);
            banner.setManaged(false);
        });

        banner.getChildren().addAll(heading, info, useDefault);
        return banner;
    }

    // ─── Minecraft section ──────────────────────────────────────────────────

    private VBox buildMinecraftSection() {
        VBox section = sectionBox("Minecraft");
        GridPane grid = new GridPane();
        grid.setHgap(12); grid.setVgap(12);

        TextField mcDirField = new TextField(settings.minecraftDirectory != null ? settings.minecraftDirectory : "");
        mcDirField.setPrefWidth(380);
        mcDirField.setPromptText("e.g. C:\\Users\\You\\AppData\\Roaming\\.averolauncher");
        mcDirField.focusedProperty().addListener((obs, was, isNow) -> {
            if (!isNow && !mcDirField.getText().equals(settings.minecraftDirectory)) {
                settings.minecraftDirectory = mcDirField.getText().trim();
                persist();
            }
        });

        Button browseMc = new Button("Browse…");
        browseMc.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Select Minecraft / AveroLauncher Directory");
            String cur = mcDirField.getText();
            File curFile = cur.isBlank() ? null : new File(cur);
            if (curFile != null && curFile.isDirectory()) chooser.setInitialDirectory(curFile);
            File chosen = chooser.showDialog(mcDirField.getScene().getWindow());
            if (chosen != null) {
                mcDirField.setText(chosen.getAbsolutePath());
                settings.minecraftDirectory = chosen.getAbsolutePath();
                persist();
            }
        });

        Button useDefault = new Button("Use Default");
        useDefault.setOnAction(e -> {
            String def = PathResolver.defaultMinecraftDir().toString();
            mcDirField.setText(def);
            settings.minecraftDirectory = def;
            persist();
            try { Files.createDirectories(Path.of(def)); } catch (IOException ex) {
                AppLogger.get().warn(TAG, "Could not create default MC dir: " + ex.getMessage());
            }
        });

        // ── Memory: one slider, sized to this PC. The starting heap is derived automatically. ──
        final int sysMb = com.averolauncher.util.MemoryAdvisor.totalSystemMb();
        final int sliderMax = com.averolauncher.util.MemoryAdvisor.sliderMaxMb();
        final int recommended = com.averolauncher.util.MemoryAdvisor.recommendedMaxMb();
        final int highMb = com.averolauncher.util.MemoryAdvisor.highThresholdMb();

        Slider maxRam = new Slider(1024, sliderMax, Math.max(1024, Math.min(settings.maxRamMb, sliderMax)));
        maxRam.setMajorTickUnit(1024);
        maxRam.setMinorTickCount(3);       // a tick every 256 MB
        maxRam.setSnapToTicks(true);
        maxRam.setBlockIncrement(256);
        maxRam.setPrefWidth(320);

        Label maxRamLabel = new Label(com.averolauncher.util.MemoryAdvisor.format(settings.maxRamMb));
        maxRamLabel.setMinWidth(70);
        Label ramHint = new Label();
        ramHint.setWrapText(true);
        ramHint.getStyleClass().add("play-card-hint");

        Runnable refreshHint = () -> {
            int mb = settings.maxRamMb;
            String base = "Your PC has " + com.averolauncher.util.MemoryAdvisor.format(sysMb)
                    + ". Recommended: " + com.averolauncher.util.MemoryAdvisor.format(recommended)
                    + " (4\u20136 GB for most modpacks). The starting memory is set automatically.";
            if (mb > highMb) {
                ramHint.setText("\u26A0 That's more than 75% of your RAM \u2014 Windows and other apps may slow down. " + base);
            } else if (mb < 2048) {
                ramHint.setText("\u26A0 Under 2 GB can cause crashes or lag with mods. " + base);
            } else {
                ramHint.setText(base);
            }
        };
        refreshHint.run();

        maxRam.valueProperty().addListener((obs, ov, nv) -> {
            int mb = com.averolauncher.util.MemoryAdvisor.roundDown(nv.intValue());
            if (mb == settings.maxRamMb) return;
            maxRamLabel.setText(com.averolauncher.util.MemoryAdvisor.format(mb));
            settings.maxRamMb = mb;
            refreshHint.run();
            persist();
        });

        maxRamSlider = maxRam;
        maxRamLabelRef = maxRamLabel;
        ramHintRefresh = refreshHint;

        Button recommendedBtn = new Button("Use recommended");
        recommendedBtn.getStyleClass().add("secondary-button");
        recommendedBtn.setOnAction(e -> maxRam.setValue(recommended));

        HBox mcDirRow = new HBox(8, mcDirField, browseMc, useDefault);
        HBox.setHgrow(mcDirField, Priority.ALWAYS);

        grid.add(new Label("Game directory:"), 0, 0); grid.add(mcDirRow, 1, 0);
        grid.add(new Label("Memory (RAM):"), 0, 1);
        grid.add(new HBox(12, maxRam, maxRamLabel, recommendedBtn), 1, 1);
        grid.add(ramHint, 1, 2);
        ramHint.setMaxWidth(520);

        section.getChildren().add(grid);
        return section;
    }

    // ─── Java section ───────────────────────────────────────────────────────

    private VBox buildJavaSection() {
        VBox section = sectionBox("Java Runtime");

        Label hint = new Label("AveroLauncher can auto-detect installed Java versions. You can also browse to a specific java executable.");
        hint.setWrapText(true); hint.getStyleClass().add("page-subtitle");

        TextField javaPathField = new TextField(settings.javaPath != null ? settings.javaPath : "");
        javaPathField.setPromptText("Auto-detect (recommended)");
        javaPathField.setPrefWidth(380);
        javaPathField.focusedProperty().addListener((obs, was, isNow) -> {
            if (!isNow) { settings.javaPath = javaPathField.getText().trim().isBlank() ? null : javaPathField.getText().trim(); persist(); }
        });

        Button browseJava = new Button("Browse…");
        browseJava.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Select java executable");
            if (PathResolver.isWindows()) fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Java executable", "java.exe"));
            File chosen = fc.showOpenDialog(javaPathField.getScene().getWindow());
            if (chosen != null) {
                javaPathField.setText(chosen.getAbsolutePath());
                settings.javaPath = chosen.getAbsolutePath();
                persist();
            }
        });

        Label detectedLabel = new Label("Click 'Auto-Detect' to scan your system.");
        detectedLabel.getStyleClass().add("play-card-hint");
        detectedLabel.setWrapText(true);

        Button autoDetect = new Button("↺  Auto-Detect Java");
        autoDetect.getStyleClass().add("secondary-button");
        autoDetect.setOnAction(e -> {
            autoDetect.setDisable(true);
            detectedLabel.setText("Scanning…");
            CompletableFuture.supplyAsync(() -> new JavaDetectionService().detectAll(), bg)
                    .thenAccept(found -> Platform.runLater(() -> {
                        autoDetect.setDisable(false);
                        if (found.isEmpty()) {
                            detectedLabel.setText("No Java installations found. Install a JDK 21 (recommended) or set the path manually.");
                        } else {
                            StringBuilder sb = new StringBuilder("Found " + found.size() + " installation(s):\n");
                            for (JavaInstallation j : found) {
                                sb.append("  • Java ").append(j.majorVersion).append(" (").append(j.vendor).append(") — ").append(j.path).append("\n");
                            }
                            detectedLabel.setText(sb.toString());
                            // Auto-fill if user hasn't set a path
                            if (settings.javaPath == null || settings.javaPath.isBlank()) {
                                JavaInstallation best = found.stream()
                                        .filter(j -> j.majorVersion >= 17)
                                        .findFirst()
                                        .orElse(found.get(0));
                                javaPathField.setText(best.path);
                                settings.javaPath = best.path;
                                persist();
                            }
                        }
                    }));
        });

        Button clearJava = new Button("Clear (use auto)");
        clearJava.setOnAction(e -> {
            javaPathField.clear();
            settings.javaPath = null;
            persist();
            detectedLabel.setText("Java path cleared. The launcher will auto-detect on next launch.");
        });

        HBox javaRow = new HBox(8, javaPathField, browseJava, clearJava);
        HBox.setHgrow(javaPathField, Priority.ALWAYS);

        section.getChildren().addAll(hint, javaRow, autoDetect, detectedLabel);
        return section;
    }

    // ─── Downloads section ──────────────────────────────────────────────────

    private VBox buildDownloadsSection() {
        VBox section = sectionBox("Downloads");
        GridPane grid = new GridPane();
        grid.setHgap(12); grid.setVgap(12);

        Spinner<Integer> concurrent = new Spinner<>(1, 32, settings.concurrentDownloads);
        concurrent.setEditable(true);
        concurrent.valueProperty().addListener((obs, ov, nv) -> { settings.concurrentDownloads = nv; persist(); });

        CheckBox cacheBox = new CheckBox("Use local metadata cache (faster startup)");
        cacheBox.setSelected(settings.useCache);
        cacheBox.selectedProperty().addListener((obs, ov, nv) -> { settings.useCache = nv; persist(); });

        CheckBox mirrorBox = new CheckBox("If Mojang's servers are unreachable, use the BMCLAPI mirror (by bangbang93)");
        mirrorBox.setSelected(settings.useMirrorFallback);
        mirrorBox.setTooltip(new javafx.scene.control.Tooltip(
                "Only used when Mojang's servers fail to connect. Downloaded files are still checked against SHA-1 hashes,\n"
              + "but the version metadata that lists those hashes then comes from the mirror too, so this is a trust choice."));
        mirrorBox.selectedProperty().addListener((obs, ov, nv) -> {
            settings.useMirrorFallback = nv;
            com.averolauncher.download.MirrorRouter.setEnabled(nv);
            persist();
        });

        grid.add(new Label("Concurrent downloads:"), 0, 0); grid.add(concurrent, 1, 0);
        grid.add(cacheBox, 0, 1, 2, 1);
        grid.add(mirrorBox, 0, 2, 2, 1);
        section.getChildren().add(grid);
        return section;
    }

    // ─── Appearance section ─────────────────────────────────────────────────

    private VBox buildAppearanceSection() {
        VBox section = sectionBox("Appearance");
        GridPane grid = new GridPane();
        grid.setHgap(12); grid.setVgap(12);

        CheckBox animations = new CheckBox("Enable animations");
        animations.setSelected(settings.animationsEnabled);
        animations.selectedProperty().addListener((obs, ov, nv) -> { settings.animationsEnabled = nv; persist(); });

        CheckBox compact = new CheckBox("Compact mode");
        compact.setSelected(settings.compactMode);
        compact.selectedProperty().addListener((obs, ov, nv) -> { settings.compactMode = nv; persist(); });

        // Sidebar: slim icon rail that expands on hover, or pinned open. ShellState saves and applies it live.
        CheckBox pinSidebar = new CheckBox("Pin sidebar open");
        pinSidebar.setSelected(com.averolauncher.ui.ShellState.isPinned());
        pinSidebar.selectedProperty().addListener((obs, ov, nv) -> com.averolauncher.ui.ShellState.setPinned(nv));
        Label pinHint = new Label("Keep the full menu open instead of the slim icon rail.");
        pinHint.getStyleClass().add("play-card-hint");

        grid.add(animations, 0, 0); grid.add(compact, 0, 1);
        grid.add(pinSidebar, 0, 2); grid.add(pinHint, 0, 3);
        section.getChildren().add(grid);
        return section;
    }

    // ─── Advanced section ───────────────────────────────────────────────────

    private VBox buildAdvancedSection() {
        VBox section = sectionBox("Advanced");
        GridPane grid = new GridPane();
        grid.setHgap(12); grid.setVgap(12);

        CheckBox lowEnd = new CheckBox("Low-End Mode (reduces RAM/CPU usage, disables animations)");
        lowEnd.setSelected(settings.lowEndMode);

        Label lowEndInfo = new Label();
        lowEndInfo.setWrapText(true);
        lowEndInfo.setMaxWidth(520);
        lowEndInfo.getStyleClass().add("play-card-hint");
        showLowEndSummary(lowEndInfo);

        lowEnd.selectedProperty().addListener((obs, ov, nv) -> {
            settings.lowEndMode = nv;
            if (nv) {
                settings.animationsEnabled = false;
                int before = Math.min(settings.maxRamMb, com.averolauncher.util.MemoryAdvisor.sliderMaxMb());
                int after = com.averolauncher.util.MemoryAdvisor.lowEndMb(before);
                if (after < before) {
                    settings.ramBeforeLowEndMb = before;
                    settings.lowEndRamMb = after;
                    setMaxRam(after);
                } else {
                    settings.ramBeforeLowEndMb = 0;
                    settings.lowEndRamMb = 0;
                }
            } else {
                // Give the memory back — but only if the user hasn't changed it since.
                if (settings.ramBeforeLowEndMb > 0 && settings.maxRamMb == settings.lowEndRamMb) {
                    int restored = settings.ramBeforeLowEndMb;
                    settings.ramBeforeLowEndMb = 0;
                    settings.lowEndRamMb = 0;
                    setMaxRam(restored);
                    lowEndInfo.setText("Memory restored to " + com.averolauncher.util.MemoryAdvisor.format(restored) + ".");
                    persist();
                    return;
                }
                settings.ramBeforeLowEndMb = 0;
                settings.lowEndRamMb = 0;
            }
            persist();
            showLowEndSummary(lowEndInfo);
        });

        ComboBox<String> logLevel = new ComboBox<>();
        logLevel.getItems().addAll("DEBUG", "INFO", "WARN", "ERROR");
        logLevel.setValue(settings.logLevel != null ? settings.logLevel : "INFO");
        logLevel.valueProperty().addListener((obs, ov, nv) -> { settings.logLevel = nv; persist(); });

        // Reset
        Button reset = new Button("Reset All Settings to Defaults");
        reset.setStyle("-fx-background-color: rgba(200,50,50,0.15); -fx-border-color: rgba(200,50,50,0.4); -fx-border-radius: 0; -fx-background-radius: 0;");
        reset.setOnAction(e -> {
            new Alert(Alert.AlertType.CONFIRMATION, "Reset all settings to defaults? Your Minecraft directory and accounts are kept.")
                    .showAndWait().ifPresent(bt -> {
                if (bt == ButtonType.OK) {
                    String keepDir = settings.minecraftDirectory;
                    String keepAccountId = settings.activeAccountId;
                    LauncherSettings fresh = new LauncherSettings();
                    fresh.maxRamMb = com.averolauncher.util.MemoryAdvisor.recommendedMaxMb();
                    fresh.minecraftDirectory = keepDir;
                    fresh.activeAccountId = keepAccountId;
                    copyInto(fresh, settings);
                    repository.save(settings);
                    AppLogger.get().info(TAG, "Settings reset to defaults");
                }
            });
        });

        // Log file path
        Path logFile = PathResolver.launcherDataDir().resolve("logs").resolve("launcher.log");
        Label logPathLabel = new Label("Log file: " + logFile);
        logPathLabel.getStyleClass().add("play-card-hint");
        logPathLabel.setWrapText(true);

        grid.add(lowEnd, 0, 0, 2, 1);
        grid.add(lowEndInfo, 0, 1, 2, 1);
        grid.add(new Label("Log level:"), 0, 2); grid.add(logLevel, 1, 2);
        grid.add(logPathLabel, 0, 3, 2, 1);
        grid.add(reset, 0, 4);

        section.getChildren().add(grid);
        return section;
    }

    /** Moves the memory slider/label/hint and the saved setting to {@code mb}. */
    private void setMaxRam(int mb) {
        if (maxRamSlider != null) maxRamSlider.setValue(mb);
        settings.maxRamMb = mb; // also covers a saved value outside the slider's range
        if (maxRamLabelRef != null) maxRamLabelRef.setText(com.averolauncher.util.MemoryAdvisor.format(mb));
        if (ramHintRefresh != null) ramHintRefresh.run();
        persist();
    }

    /** Explains how much memory Low-End Mode is saving (or why it isn't saving any). */
    private void showLowEndSummary(Label info) {
        if (!settings.lowEndMode) {
            info.setText("");
        } else if (settings.ramBeforeLowEndMb > 0 && settings.lowEndRamMb > 0) {
            int before = settings.ramBeforeLowEndMb, after = settings.lowEndRamMb;
            info.setText("Low-End Mode is saving " + com.averolauncher.util.MemoryAdvisor.format(before - after)
                    + " of RAM \u2014 game memory lowered from " + com.averolauncher.util.MemoryAdvisor.format(before)
                    + " to " + com.averolauncher.util.MemoryAdvisor.format(after) + ". Turn it off to get it back.");
        } else {
            info.setText("Game memory is already at the low-end minimum ("
                    + com.averolauncher.util.MemoryAdvisor.format(settings.maxRamMb) + ") \u2014 nothing more to save.");
        }
    }

    private void copyInto(LauncherSettings from, LauncherSettings to) {
        to.closeBehavior = from.closeBehavior;
        to.launchOnStartup = from.launchOnStartup;
        to.defaultVersionId = from.defaultVersionId;
        to.defaultProfileId = from.defaultProfileId;
        to.maxRamMb = from.maxRamMb;
        to.ramBeforeLowEndMb = from.ramBeforeLowEndMb;
        to.lowEndRamMb = from.lowEndRamMb;
        to.javaPath = from.javaPath;
        to.downloadDirectory = from.downloadDirectory;
        to.concurrentDownloads = from.concurrentDownloads;
        to.useCache = from.useCache;
        to.useMirrorFallback = from.useMirrorFallback;
        to.theme = from.theme;
        to.animationsEnabled = from.animationsEnabled;
        to.compactMode = from.compactMode;
        to.lowEndMode = from.lowEndMode;
        to.logLevel = from.logLevel;
        to.checkForUpdatesOnStartup = from.checkForUpdatesOnStartup;
        to.skippedUpdateVersion = from.skippedUpdateVersion;
        to.activeAccountId = from.activeAccountId;
        // minecraftDirectory is intentionally not copied — it is preserved across resets.
        // msClientId lives in CredentialStore — never copied here.
    }

    private VBox sectionBox(String heading) {
        VBox box = new VBox(12);
        box.getStyleClass().addAll("settings-section", "settings-panel");
        Label header = new Label(heading);
        header.getStyleClass().add("section-header");
        box.getChildren().add(header);
        return box;
    }

    /** Saves, then lets the motion system re-read Animations / Low-End Mode so they take effect live. */
    private void persist() {
        repository.save(settings);
        com.averolauncher.ui.Motion.refresh();
    }
}
