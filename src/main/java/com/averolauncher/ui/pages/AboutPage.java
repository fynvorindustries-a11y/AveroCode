package com.averolauncher.ui.pages;

import com.averolauncher.download.MirrorRouter;
import com.averolauncher.logging.AppLogger;
import com.averolauncher.ui.AppContext;
import com.averolauncher.update.AppVersion;
import com.averolauncher.update.UpdateInfo;
import com.averolauncher.util.AppAssets;
import com.averolauncher.util.PathResolver;
import com.averolauncher.util.UiAccents;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.awt.Desktop;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public class AboutPage {

    private static final String TAG = "AboutPage";
    private static final String APP_VERSION = AppVersion.CURRENT;

    private final AppContext appContext;

    // Live-updating stat labels (memory + uptime used to be frozen at page-build time).
    private Label memValueLabel;
    private Label uptimeValueLabel;

    public AboutPage(AppContext appContext) {
        this.appContext = appContext;
    }

    public VBox build() {
        VBox root = new VBox(22);
        root.getStyleClass().add("page");
        root.setPadding(new Insets(32));

        root.getChildren().addAll(
                buildHero(),
                buildStatGrid(),
                buildUpdatesCard(),
                buildPathsCard(),
                buildMirrorCard(),
                buildBuiltWith(),
                buildLegal()
        );

        // Refresh memory + uptime every second while this page is on screen; stop as soon as
        // it is removed from the scene so a rebuilt page never leaves a timer running behind it.
        Timeline live = new Timeline(new KeyFrame(javafx.util.Duration.seconds(1), e -> refreshLiveStats()));
        live.setCycleCount(Animation.INDEFINITE);
        root.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) live.stop(); else live.play();
        });
        return root;
    }

    private void refreshLiveStats() {
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMb = rt.maxMemory() / (1024 * 1024);
        if (memValueLabel != null) memValueLabel.setText(usedMb + " MB / " + maxMb + " MB");
        if (uptimeValueLabel != null) {
            uptimeValueLabel.setText(formatUptime(ManagementFactory.getRuntimeMXBean().getUptime()));
        }
    }

    // ---------------------------------------------------------------- hero

    private StackPane buildHero() {
        StackPane hero = new StackPane();
        hero.getStyleClass().add("about-hero");
        hero.setPrefHeight(150);
        hero.setMinHeight(140);
        // Inline gradient background so the hero reads on-brand even without the
        // external stylesheet's ".about-hero" rule; shows /images/about-hero.png
        // once bundled, otherwise falls back to this same gradient panel.
        hero.getChildren().add(UiAccents.heroBanner("/images/about-hero.png", 150));

        javafx.scene.Node mark = AppAssets.buildLogoMark("about-mark", "A", 72);

        Label name = new Label("AveroLauncher");
        name.getStyleClass().add("about-app-name");

        Label badge = new Label("v" + APP_VERSION);
        badge.getStyleClass().add("about-badge");
        UiAccents.applyPrimaryGradient(badge);

        HBox nameRow = new HBox(12, name, badge);
        nameRow.setAlignment(Pos.CENTER_LEFT);

        Label tagline = new Label("Independent, open-source Minecraft launcher");
        tagline.getStyleClass().add("about-tagline");

        VBox textCol = new VBox(8, nameRow, tagline, UiAccents.underline(64));
        textCol.setAlignment(Pos.CENTER_LEFT);

        HBox content = new HBox(20, mark, textCol);
        content.setAlignment(Pos.CENTER_LEFT);
        content.setPadding(new Insets(6, 36, 0, 36));

        hero.getChildren().add(content);
        return hero;
    }

    // ---------------------------------------------------------- stat grid

    private FlowPane buildStatGrid() {
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMb = rt.maxMemory() / (1024 * 1024);
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();

        FlowPane grid = new FlowPane(14, 14);
        grid.setMinWidth(0);

        grid.getChildren().addAll(
                statCard("\u2615", "JAVA RUNTIME",
                        "Java " + System.getProperty("java.version"),
                        System.getProperty("java.vendor")),
                statCard("\u2B21", "JAVAFX",
                        javafxVersion(),
                        "UI toolkit"),
                statCard("\u25A3", "OPERATING SYSTEM",
                        System.getProperty("os.name"),
                        System.getProperty("os.version") + " \u00b7 " + System.getProperty("os.arch")),
                statCard("\u2699", "PROCESSORS",
                        rt.availableProcessors() + " cores",
                        "available to the JVM"),
                statCard("\u25C8", "MEMORY",
                        usedMb + " MB / " + maxMb + " MB",
                        "used of max heap"),
                statCard("\u23F1", "SESSION UPTIME",
                        formatUptime(uptimeMs),
                        "since launcher started")
        );
        // grid children: 4 = memory, 5 = uptime (see order above)
        memValueLabel = (Label) grid.getChildren().get(4).lookup(".about-stat-value");
        uptimeValueLabel = (Label) grid.getChildren().get(5).lookup(".about-stat-value");
        return grid;
    }

    private VBox statCard(String icon, String title, String value, String subvalue) {
        Label iconGlyph = new Label(icon);
        iconGlyph.setStyle("-fx-text-fill: #ffffff; -fx-font-size: 14px;");
        StackPane iconBadge = new StackPane(iconGlyph);
        iconBadge.setMinSize(30, 30);
        iconBadge.setPrefSize(30, 30);
        iconBadge.setMaxSize(30, 30);
        iconBadge.setStyle(
                "-fx-background-color: linear-gradient(to bottom right, " + UiAccents.ACCENT_ORANGE + ", " + UiAccents.ACCENT_ORANGE_L + ");" +
                "-fx-background-radius: 0;"
        );

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("about-stat-title");

        HBox header = new HBox(8, iconBadge, titleLabel);
        header.setAlignment(Pos.CENTER_LEFT);

        Label valueLabel = new Label(value);
        valueLabel.getStyleClass().add("about-stat-value");
        valueLabel.setWrapText(true);

        Label subLabel = new Label(subvalue);
        subLabel.getStyleClass().add("about-stat-subvalue");
        subLabel.setWrapText(true);

        VBox card = new VBox(8, header, valueLabel, subLabel);
        card.getStyleClass().add("about-stat-card");
        card.setMinWidth(170);
        card.setPrefWidth(190);
        return card;
    }

    private String formatUptime(long ms) {
        Duration d = Duration.ofMillis(ms);
        long h = d.toHours();
        long m = d.toMinutesPart();
        long s = d.toSecondsPart();
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }

    // ------------------------------------------------------- updates card

    private VBox buildUpdatesCard() {
        Label header = new Label("Updates");
        header.getStyleClass().add("section-header");

        Label statusLabel = new Label("Checks " + AppVersion.GITHUB_REPO + " on GitHub for new releases.");
        statusLabel.getStyleClass().add("about-mirror-note");
        statusLabel.setWrapText(true);

        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setVisible(false);
        progressBar.setManaged(false);
        progressBar.setPrefWidth(140);

        Button checkBtn = new Button("Check for updates");
        checkBtn.getStyleClass().add("primary-cta-button");
        // Also set directly, inline, as a Java object property — not just a CSS
        // class name. This makes the button's look independent of the external
        // stylesheet loading/parsing/caching correctly at all: even if
        // dark-theme.css were stale, missing, or failed to apply for any
        // reason, this still paints a visible gradient with readable text.
        checkBtn.setStyle(
            "-fx-background-color: linear-gradient(to bottom, rgba(255,255,255,0.22) 0%, rgba(255,255,255,0.0) 45%), linear-gradient(to bottom right, #ff4655 0%, #ff5d6b 100%);" +
            "-fx-text-fill: #ffffff;" +
            "-fx-font-weight: bold;" +
            "-fx-background-radius: 0;" +
            "-fx-padding: 9 20 9 20;" +
            "-fx-cursor: hand;"
        );

        HBox actionRow = new HBox(10, checkBtn, progressBar);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(14, header, statusLabel, actionRow);
        card.getStyleClass().addAll("settings-section", "settings-panel");

        checkBtn.setOnAction(e -> {
            checkBtn.setDisable(true);
            statusLabel.setText("Checking for updates\u2026");
            appContext.updateChecker.checkForUpdate().whenComplete((maybeUpdate, err) ->
                    Platform.runLater(() -> {
                        checkBtn.setDisable(false);
                        if (err != null) {
                            Throwable root = err;
                            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
                            statusLabel.setText("Couldn't check for updates: "
                                    + (root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName()));
                            return;
                        }
                        if (maybeUpdate.isEmpty()) {
                            statusLabel.setText("You're up to date (v" + APP_VERSION + ").");
                            return;
                        }
                        UpdateInfo info = maybeUpdate.get();
                        statusLabel.setText("You can check again at any time.");
                        card.getChildren().setAll(header, buildFoundUpdateRow(info), statusLabel, actionRow);
                    }));
        });

        return card;
    }

    private VBox buildFoundUpdateRow(UpdateInfo info) {
        Label pill = new Label("UPDATE AVAILABLE");
        pill.getStyleClass().addAll("about-status-pill", "about-status-active");

        Label title = new Label(info.displayName);
        title.getStyleClass().add("about-stat-value");

        HBox titleRow = new HBox(10, pill, title);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Label summary = new Label(info.shortSummary());
        summary.getStyleClass().add("about-mirror-note");
        summary.setWrapText(true);

        Label progressLabel = new Label();
        progressLabel.getStyleClass().add("about-path-label");
        progressLabel.setVisible(false);
        progressLabel.setManaged(false);

        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setVisible(false);
        progressBar.setManaged(false);
        progressBar.setPrefWidth(140);

        Button actionBtn = new Button(info.hasDownloadableAsset() ? "Download & install" : "View release");
        actionBtn.getStyleClass().add("secondary-button");
        actionBtn.setOnAction(e -> {
            if (info.hasDownloadableAsset()) {
                actionBtn.setDisable(true);
                progressBar.setVisible(true);
                progressBar.setManaged(true);
                progressLabel.setVisible(true);
                progressLabel.setManaged(true);
                progressLabel.setText("Downloading\u2026");
                appContext.updateDownloader.download(info, bytes ->
                        Platform.runLater(() -> {
                            if (info.assetSize > 0) {
                                progressBar.setProgress(Math.min(1.0, bytes / (double) info.assetSize));
                            } else {
                                progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                            }
                        })
                ).whenComplete((path, err) -> Platform.runLater(() -> {
                    if (err != null) {
                        AppLogger.get().warn(TAG, "Update download failed: "
                                + (err.getMessage() != null ? err.getMessage() : err.getClass().getSimpleName()));
                        progressLabel.setText("Download failed \u2014 try \u201cView release\u201d instead");
                        actionBtn.setDisable(false);
                        return;
                    }
                    installDownloadedUpdate(path, progressLabel, progressBar, actionBtn);
                }));
            } else {
                openInBrowser(info.htmlUrl);
            }
        });

        Hyperlink releaseLink = new Hyperlink("View on GitHub");
        releaseLink.setOnAction(e -> openInBrowser(info.htmlUrl));

        HBox actionRow = new HBox(10, actionBtn, progressBar, progressLabel, releaseLink);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        return new VBox(10, titleRow, summary, actionRow);
    }

    /**
     * Fully automatic install: unpack, install silently, restart the launcher. If that
     * isn't possible (not Windows / running from an IDE / unpack error) it falls back to
     * revealing the downloaded file like before, with the reason shown.
     */
    private void installDownloadedUpdate(Path downloaded, Label progressLabel,
                                         ProgressBar progressBar, Button actionBtn) {
        progressLabel.setText("Installing update\u2026");
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        new Thread(() -> {
            com.averolauncher.update.UpdateInstaller.Result r =
                    com.averolauncher.update.UpdateInstaller.prepareAndLaunch(downloaded);
            Platform.runLater(() -> {
                progressLabel.setText(r.message);
                if (r.started) {
                    com.averolauncher.update.UpdateInstaller.exitForUpdate();
                } else {
                    progressBar.setProgress(0);
                    actionBtn.setDisable(false);
                    openInFileManager(downloaded);
                }
            });
        }, "update-installer").start();
    }

    private void openInBrowser(String url) {
        if (url == null || url.isBlank()) return;
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception e) {
            AppLogger.get().warn(TAG, "Could not open browser for " + url + ": " + e.getMessage());
        }
    }

    // -------------------------------------------------------- paths card

    private VBox buildPathsCard() {
        Label header = new Label("Where things live");
        header.getStyleClass().add("section-header");

        Path dataDir = PathResolver.launcherDataDir();
        Path logFile = dataDir.resolve("logs").resolve("launcher.log");

        VBox rows = new VBox(10,
                pathRow("DATA DIRECTORY", dataDir),
                pathRow("LOG FILE", logFile)
        );

        VBox card = new VBox(14, header, rows);
        card.getStyleClass().addAll("settings-section", "settings-panel");
        return card;
    }

    private HBox pathRow(String label, Path path) {
        Label labelNode = new Label(label);
        labelNode.getStyleClass().add("about-path-label");

        Label valueNode = new Label(path.toString());
        valueNode.getStyleClass().add("about-path-value");
        valueNode.setWrapText(true);

        valueNode.setMinWidth(0);
        valueNode.setMaxWidth(Double.MAX_VALUE);
        VBox textCol = new VBox(4, labelNode, valueNode);
        HBox.setHgrow(textCol, Priority.ALWAYS);
        // minWidth 0 lets the path column shrink/wrap instead of forcing the row wider
        // than the window and pushing Copy / Open folder off the right edge.
        textCol.setMinWidth(0);
        textCol.setMaxWidth(Double.MAX_VALUE);

        Label feedback = new Label();
        feedback.getStyleClass().add("about-copied");
        feedback.setVisible(false);

        Button copyBtn = new Button("Copy");
        copyBtn.getStyleClass().add("secondary-button");
        copyBtn.setMinWidth(Region.USE_PREF_SIZE);
        copyBtn.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(path.toString());
            Clipboard.getSystemClipboard().setContent(content);
            feedback.setText("Copied!");
            feedback.setVisible(true);
            javafx.animation.PauseTransition hide =
                    new javafx.animation.PauseTransition(javafx.util.Duration.seconds(1.5));
            hide.setOnFinished(ev -> feedback.setVisible(false));
            hide.play();
        });

        Button openBtn = new Button("Open folder");
        openBtn.getStyleClass().add("secondary-button");
        openBtn.setMinWidth(Region.USE_PREF_SIZE);
        openBtn.setOnAction(e -> openInFileManager(path));
        boolean canOpen = Desktop.isDesktopSupported()
                && Desktop.getDesktop().isSupported(Desktop.Action.OPEN);
        openBtn.setDisable(!canOpen);

        HBox row = new HBox(10, textCol, feedback, copyBtn, openBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("about-path-row");
        row.setMinWidth(0);
        HBox.setHgrow(row, Priority.ALWAYS);
        return row;
    }

    /** Best-effort convenience only: opens the containing folder in the OS file
     * manager (revealing the file itself if it's a file, e.g. the log). Silently
     * does nothing if the platform has no registered file manager to hand off to —
     * never worth surfacing an error dialog for a "nice to have" shortcut. */
    private void openInFileManager(Path path) {
        try {
            File target = (Files.isDirectory(path) ? path : path.getParent()).toFile();
            if (target != null && target.exists()) {
                Desktop.getDesktop().open(target);
            }
        } catch (Exception ignored) {
        }
    }

    // -------------------------------------------------------- mirror card

    /**
     * BMCLAPI's terms require any program using their mirror to show its name in the
     * UI (see MirrorRouter.CREDIT / notice()) — this card is that attribution, made
     * into an actual feature panel instead of a buried sentence: it shows live
     * whether the fallback is on, and whether it's actually being used right now.
     */
    private VBox buildMirrorCard() {
        Label header = new Label("Network fallback");
        header.getStyleClass().add("section-header");

        boolean enabled = MirrorRouter.isEnabled();
        boolean activeNow = enabled && MirrorRouter.isMirrorActive();

        Label pill = new Label(!enabled ? "Disabled" : activeNow ? "Active now" : "Standby");
        pill.getStyleClass().addAll("about-status-pill",
                !enabled ? "about-status-off" : activeNow ? "about-status-active" : "about-status-standby");

        Label title = new Label("BMCLAPI Mirror");
        title.getStyleClass().add("about-stat-value");

        HBox titleRow = new HBox(10, title, pill);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Label note = new Label(
                "If Mojang's servers are unreachable, AveroLauncher automatically falls back to the "
                        + MirrorRouter.CREDIT + " for version metadata and game files. "
                        + "File downloads are still verified against Mojang's checksums when available. "
                        + "Turn this off in Settings \u2192 Downloads.");
        note.setWrapText(true);
        note.getStyleClass().add("about-mirror-note");

        VBox card = new VBox(10, header, titleRow, note);
        card.getStyleClass().addAll("settings-section", "settings-panel");
        return card;
    }

    // -------------------------------------------------------------- tags

    private VBox buildBuiltWith() {
        Label header = new Label("Built with");
        header.getStyleClass().add("section-header");

        FlowPane tags = new FlowPane(8, 8);
        for (String dep : new String[]{
                "Gson 2.11", "SLF4J", "Logback", "JavaFX " + javafxMajor(),
                "Mojang Launcher API", "Modrinth API", "BMCLAPI Mirror"
        }) {
            Label tag = new Label(dep);
            tag.getStyleClass().add("about-tag");
            tags.getChildren().add(tag);
        }

        return new VBox(12, header, tags);
    }

    // ------------------------------------------------------------- legal

    private VBox buildLegal() {
        Label legal = new Label(
                "AveroLauncher is an independent, open-source Minecraft launcher.\n" +
                "Not affiliated with Mojang Studios, Microsoft, or the Minecraft brand.\n" +
                "Minecraft is a trademark of Mojang Studios.");
        legal.setWrapText(true);
        legal.getStyleClass().add("about-legal");

        VBox box = new VBox(legal);
        box.setPadding(new Insets(8, 0, 0, 0));
        return box;
    }

    // ---------------------------------------------------------------- misc

    /** Reads the actual running JavaFX version instead of a hardcoded guess, so this
     * stays correct if the bundled JavaFX version is ever bumped in build.gradle. */
    private String javafxVersion() {
        String v = System.getProperty("javafx.runtime.version");
        return "JavaFX " + ((v != null && !v.isBlank()) ? v : "21");
    }

    private String javafxMajor() {
        String v = System.getProperty("javafx.runtime.version");
        if (v != null && v.contains(".")) {
            return v.substring(0, v.indexOf('.'));
        }
        return "21";
    }
}
