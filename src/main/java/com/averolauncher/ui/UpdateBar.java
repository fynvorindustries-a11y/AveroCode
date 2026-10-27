package com.averolauncher.ui;

import com.averolauncher.logging.AppLogger;
import com.averolauncher.model.LauncherSettings;
import com.averolauncher.repository.SettingsRepository;
import com.averolauncher.ui.kit.AvButton;
import com.averolauncher.ui.kit.Slash;
import com.averolauncher.ui.kit.StepProgress;
import com.averolauncher.update.AppVersion;
import com.averolauncher.update.UpdateInfo;
import com.averolauncher.update.incremental.CancelToken;
import com.averolauncher.update.incremental.HelperLauncher;
import com.averolauncher.update.incremental.IncrementalUpdater;
import com.averolauncher.util.PathResolver;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.control.OverrunStyle;

import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The "update available" notice: one slim bar that floats over the bottom of the page (so it never
 * pushes content around) and slides/fades in. Dismissible; "Skip this version" remembers the tag.
 *
 * <p>The download / install flow is exactly the one MainWindow used to have inline (same
 * {@code UpdateDownloader.download} and {@code UpdateInstaller} calls, same skipped-version
 * handling); only the presentation changed. Progress uses the design system's {@link StepProgress}.
 */
public final class UpdateBar extends HBox {

    private static final String TAG = "UpdateBar";
    private static final double ENTER_OFFSET = 16;

    private final AppContext ctx;
    private final LauncherSettings settings;
    private final SettingsRepository settingsRepository;

    private final Label title = new Label();
    private final Label summary = new Label();
    private final Label status = new Label();
    private final StepProgress progress = new StepProgress();
    private final AvButton action = new AvButton("Download & install");
    private final AvButton cancel = new AvButton("Cancel");

    // Incremental ("only what changed") update state. Null/false = classic installer flow.
    private boolean incremental;
    private IncrementalUpdater updater;
    private IncrementalUpdater.Prepared prepared;
    private CancelToken activeToken;
    private int incrementalFailures;
    private long prepareGeneration;

    private UpdateInfo info;
    private boolean releaseOnly; // after a failed download the button falls back to "View release"
    private boolean hiding;

    public UpdateBar(AppContext ctx, LauncherSettings settings, SettingsRepository settingsRepository) {
        this.ctx = ctx;
        this.settings = settings;
        this.settingsRepository = settingsRepository;

        getStyleClass().addAll("av-update-bar", "shadow-2", "gap-16");
        setAlignment(Pos.CENTER_LEFT);
        setMaxHeight(USE_PREF_SIZE);
        setPickOnBounds(false);
        setVisible(false);
        setOpacity(0);

        title.getStyleClass().add("av-update-title");
        summary.getStyleClass().add("av-update-summary");
        summary.setTextOverrun(OverrunStyle.ELLIPSIS);
        summary.managedProperty().bind(summary.visibleProperty());
        VBox text = new VBox(2, title, summary);
        text.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(text, Priority.ALWAYS);
        text.setMinWidth(0);

        status.getStyleClass().add("av-update-status");
        status.managedProperty().bind(status.visibleProperty());
        status.setVisible(false);

        progress.setMinWidth(220);
        progress.setPrefWidth(220);
        progress.setMaxWidth(220);
        progress.managedProperty().bind(progress.visibleProperty());
        progress.setVisible(false);

        action.setOnAction(e -> onAction());
        cancel.managedProperty().bind(cancel.visibleProperty());
        cancel.setVisible(false);
        cancel.setOnAction(e -> {
            CancelToken t = activeToken;
            if (t != null) t.cancel();
        });

        Hyperlink skip = new Hyperlink("Skip this version");
        skip.getStyleClass().add("av-update-skip");
        skip.setOnAction(e -> {
            if (info != null) {
                settings.skippedUpdateVersion = info.tagName;
                settingsRepository.save(settings);
            }
            hide();
        });

        Button dismiss = new Button("\u2715");
        dismiss.getStyleClass().add("av-icon-btn");
        dismiss.setAccessibleText("Dismiss");
        dismiss.setOnAction(e -> hide());

        getChildren().addAll(Slash.mark(8, 22), text, progress, status, cancel, action, skip, dismiss);

        // Fully faded out -> take it out of hit-testing and rendering.
        opacityProperty().addListener((o, was, now) -> {
            if (hiding && now.doubleValue() <= 0.01) setVisible(false);
        });
    }

    /** Safe to call repeatedly; ignores a version the player already chose to skip. */
    public void show(UpdateInfo i) {
        if (i == null) return;
        if (i.tagName != null && i.tagName.equals(settings.skippedUpdateVersion)) return;
        info = i;
        releaseOnly = !i.hasDownloadableAsset();
        hiding = false;

        title.setText(i.displayName + " is out \u2014 you're on v" + AppVersion.CURRENT);
        String s = i.shortSummary();
        summary.setText(s == null ? "" : s);
        summary.setVisible(s != null && !s.isBlank());
        status.setVisible(false);
        progress.setVisible(false);
        action.setText(releaseOnly ? "View release" : "Download & install");
        action.setDisable(false);
        cancel.setVisible(false);
        incremental = false;
        prepared = null;
        incrementalFailures = 0;
        if (i.hasIncrementalManifest() && IncrementalUpdater.isAvailableHere()) {
            beginIncrementalCheck(i);
        }

        setVisible(true);
        setOpacity(0);
        setTranslateY(ENTER_OFFSET);
        Motion.animate(opacityProperty(), 1.0, Motion.PANEL);
        Motion.animate(translateYProperty(), 0.0, Motion.PANEL);
    }

    public void hide() {
        hiding = true;
        Motion.animate(translateYProperty(), ENTER_OFFSET, Motion.PANEL);
        Motion.animate(opacityProperty(), 0.0, Motion.PANEL);
    }

    // ───────────────────────────────────────────────────────────── download / install

    private void onAction() {
        if (info == null) return;
        final UpdateInfo i = info;
        if (releaseOnly) {
            openInBrowser(i.htmlUrl);
            return;
        }
        if (incremental && prepared != null) {
            startIncrementalInstall(i);
            return;
        }
        action.setDisable(true);
        status.setVisible(false);
        progress.setStep("Downloading update").setProgress(0);
        progress.setVisible(true);
        ctx.updateDownloader.download(i, bytes ->
                Platform.runLater(() -> {
                    if (i.assetSize > 0) {
                        progress.setProgress(Math.min(1.0, bytes / (double) i.assetSize));
                    } else {
                        progress.setIndeterminate(true);
                    }
                })
        ).whenComplete((path, err) -> Platform.runLater(() -> {
            if (err != null) {
                AppLogger.get().warn(TAG, "Update download failed: " + err.getMessage());
                progress.setVisible(false);
                say("Download failed \u2014 try \u201cView release\u201d instead");
                releaseOnly = true;
                action.setText("View release");
                action.setDisable(false);
                return;
            }
            install(path);
        }));
    }

    /**
     * Fully automatic install: unpack, install silently, restart the launcher. If that isn't
     * possible (not Windows / running from an IDE / unpack error) it reveals the downloaded file
     * and shows the reason.
     */
    private void install(Path downloaded) {
        progress.setStep("Installing update\u2026").setIndeterminate(true);
        new Thread(() -> {
            com.averolauncher.update.UpdateInstaller.Result r =
                    com.averolauncher.update.UpdateInstaller.prepareAndLaunch(downloaded);
            Platform.runLater(() -> {
                if (r.started) {
                    progress.setStep(r.message);
                    com.averolauncher.update.UpdateInstaller.exitForUpdate();
                } else {
                    progress.setVisible(false);
                    say(r.message);
                    action.setDisable(false);
                    openInFileManager(downloaded);
                }
            });
        }, "update-installer").start();
    }

    // ───────────────────────────────────────────────────────── incremental (changed files only)

    /** Background: fetch + verify the signed manifest and work out how little needs downloading. */
    private void beginIncrementalCheck(UpdateInfo i) {
        final long generation = ++prepareGeneration;
        action.setText("Checking\u2026");
        action.setDisable(true);
        new Thread(() -> {
            try (CancelToken token = new CancelToken()) {
                IncrementalUpdater u = new IncrementalUpdater(IncrementalUpdater.detectInstallDir(), URI.create(i.manifestUrl));
                IncrementalUpdater.Prepared p = u.prepare(URI.create(i.manifestUrl), URI.create(i.manifestSigUrl),
                        i.tagName, token, null);
                Platform.runLater(() -> {
                    if (generation != prepareGeneration || info != i) return;
                    if (p.upToDate) { fallToInstaller("Nothing to download"); return; }
                    updater = u;
                    prepared = p;
                    incremental = true;
                    String size = IncrementalUpdater.formatBytes(p.downloadBytes);
                    String base = i.shortSummary();
                    summary.setText((base == null || base.isBlank() ? "" : base + "  \u2022  ") + "Update size: about " + size);
                    summary.setVisible(true);
                    action.setText("Update now (" + size + ")");
                    action.setDisable(false);
                });
            } catch (Exception e) {
                AppLogger.get().warn(TAG, "Incremental update unavailable, using the installer: " + e.getMessage());
                Platform.runLater(() -> { if (generation == prepareGeneration && info == i) fallToInstaller(null); });
            }
        }, "update-prepare").start();
    }

    private void fallToInstaller(String note) {
        incremental = false;
        prepared = null;
        action.setText(releaseOnly ? "View release" : "Download & install");
        action.setDisable(false);
        if (note != null) say(note);
    }

    private void startIncrementalInstall(UpdateInfo i) {
        final IncrementalUpdater u = updater;
        final IncrementalUpdater.Prepared p = prepared;
        final CancelToken token = new CancelToken();
        activeToken = token;
        action.setDisable(true);
        status.setVisible(false);
        cancel.setVisible(true);
        progress.setStep("Downloading\u2026").setProgress(0);
        progress.setVisible(true);

        new Thread(() -> {
            try {
                java.nio.file.Path plan = u.stage(p, token, new IncrementalUpdater.Listener() {
                    @Override public void onState(IncrementalUpdater.State s, String message) {
                        Platform.runLater(() -> {
                            progress.setStep(stateLabel(s, message));
                            // Past this point the files are being swapped in by the helper: no more cancelling.
                            if (s == IncrementalUpdater.State.INSTALLING) cancel.setVisible(false);
                            if (s == IncrementalUpdater.State.VERIFYING) progress.setIndeterminate(true);
                        });
                    }
                    @Override public void onProgress(long done, long total) {
                        Platform.runLater(() -> progress.setProgress(Math.min(1.0, done / (double) Math.max(1, total))));
                    }
                });
                Platform.runLater(() -> {
                    cancel.setVisible(false);
                    progress.setStep("Restarting\u2026").setIndeterminate(true);
                });
                HelperLauncher.start(plan, PathResolver.launcherDataDir());
                Platform.runLater(com.averolauncher.update.UpdateInstaller::exitForUpdate);
            } catch (CancelToken.CancelledException ce) {
                Platform.runLater(() -> {
                    progress.setVisible(false);
                    cancel.setVisible(false);
                    say("Update cancelled \u2014 nothing was changed");
                    action.setDisable(false);
                });
            } catch (Exception e) {
                AppLogger.get().warn(TAG, "Incremental update failed: " + e.getMessage());
                Platform.runLater(() -> {
                    progress.setVisible(false);
                    cancel.setVisible(false);
                    incrementalFailures++;
                    say(e.getMessage() != null ? e.getMessage() : "Update failed");
                    if (incrementalFailures >= 2) {
                        fallToInstaller(null);           // keep working: offer the classic installer download
                        action.setText("Download installer");
                    } else {
                        action.setText("Retry");          // resumable: already-downloaded files are kept
                        action.setDisable(false);
                    }
                });
            } finally {
                token.close();
                activeToken = null;
            }
        }, "update-incremental").start();
    }

    private static String stateLabel(IncrementalUpdater.State s, String message) {
        return switch (s) {
            case CHECKING -> "Checking\u2026";
            case UPDATE_AVAILABLE -> "Update available";
            case DOWNLOADING -> "Downloading\u2026";
            case VERIFYING -> "Verifying\u2026";
            case INSTALLING -> "Preparing install\u2026";
            case RESTARTING -> "Restarting\u2026";
            case COMPLETE -> "Done";
        };
    }

    private void say(String message) {
        status.setText(message);
        status.setVisible(true);
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

    private void openInFileManager(Path path) {
        try {
            java.io.File target = (Files.isDirectory(path) ? path : path.getParent()).toFile();
            if (target != null && target.exists() && Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(target);
            }
        } catch (Exception ignored) {
        }
    }
}
