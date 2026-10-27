package com.averolauncher.ui.pages;

import com.averolauncher.loaders.LoaderService;
import com.averolauncher.loaders.LoaderVersion;
import com.averolauncher.loaders.ModLoader;
import com.averolauncher.model.LauncherSettings;
import com.averolauncher.ui.DialogStyler;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * "Install mod loader" dialog for the Play page: pick Fabric, Quilt, NeoForge or Forge, a Minecraft
 * version and a loader build, then install. The finished version appears in the Play page's list.
 */
final class LoaderInstallDialog {

    private static final int MAX_LOG_CHARS = 60_000;

    private final LoaderService service;
    private final LauncherSettings settings;
    private final Consumer<String> onInstalled;

    private final ToggleGroup loaderGroup = new ToggleGroup();
    private final ComboBox<String> mcBox = new ComboBox<>();
    private final ComboBox<LoaderVersion> buildBox = new ComboBox<>();
    private final Label note = new Label();
    private final Label status = new Label();
    private final ProgressBar bar = new ProgressBar();
    private final TextArea logArea = new TextArea();
    private final FlowPane loaderRow = new FlowPane(8, 8);

    private Button installButton;
    private Button closeButton;
    private boolean installing;
    private boolean installed;
    private int ticket;

    private LoaderInstallDialog(LoaderService service, LauncherSettings settings, Consumer<String> onInstalled) {
        this.service = service;
        this.settings = settings;
        this.onInstalled = onInstalled;
    }

    /**
     * @param releases      Minecraft release ids, newest first
     * @param preferredMc   pre-selected Minecraft version (usually the one open on the Play page), may be null
     * @param onInstalled   called on the FX thread with the new version id once an install succeeds
     */
    static void show(Window owner, LoaderService service, LauncherSettings settings,
                     List<String> releases, String preferredMc, Consumer<String> onInstalled) {
        new LoaderInstallDialog(service, settings, onInstalled).open(owner, releases, preferredMc);
    }

    private void open(Window owner, List<String> releases, String preferredMc) {
        Dialog<Void> dialog = new Dialog<>();
        DialogStyler.style(dialog);
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle("Install mod loader");
        dialog.setHeaderText("Install a mod loader");

        ButtonType installType = new ButtonType("Install", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(installType, ButtonType.CLOSE);
        installButton = (Button) dialog.getDialogPane().lookupButton(installType);
        closeButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.CLOSE);
        installButton.setDisable(true);
        // Install keeps the dialog open so progress stays visible.
        installButton.addEventFilter(ActionEvent.ACTION, e -> {
            e.consume();
            if (installed) dialog.close(); else startInstall();
        });
        dialog.setOnCloseRequest(e -> { if (installing) e.consume(); });

        for (ModLoader l : ModLoader.values()) {
            ToggleButton t = new ToggleButton(l.displayName());
            t.getStyleClass().add("play-filter");
            t.setUserData(l);
            t.setToggleGroup(loaderGroup);
            loaderRow.getChildren().add(t);
        }
        loaderGroup.getToggles().get(0).setSelected(true);
        // A toggle group allows deselecting everything; keep one always on.
        loaderGroup.selectedToggleProperty().addListener((o, was, now) -> {
            if (now == null) was.setSelected(true); else reloadBuilds();
        });

        mcBox.getStyleClass().add("play-select");
        mcBox.setMaxWidth(Double.MAX_VALUE);
        mcBox.getItems().setAll(releases);
        String pick = preferredMc != null && releases.contains(preferredMc) ? preferredMc : (releases.isEmpty() ? null : releases.get(0));
        mcBox.setValue(pick);
        mcBox.valueProperty().addListener((o, was, now) -> reloadBuilds());

        buildBox.getStyleClass().add("play-select");
        buildBox.setMaxWidth(Double.MAX_VALUE);
        buildBox.setPromptText("Loader version");
        buildBox.valueProperty().addListener((o, was, now) -> refreshButtons());

        for (Label l : new Label[] { note, status }) {
            l.setWrapText(true);
            l.setMaxWidth(Double.MAX_VALUE);
            l.setMinHeight(Region.USE_PREF_SIZE);   // let wrapped text grow instead of ending in "..."
            l.getStyleClass().add("play-hint");
        }
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setVisible(false);
        bar.setManaged(false);
        logArea.setEditable(false);
        logArea.setPrefRowCount(8);
        logArea.setWrapText(false);
        logArea.setVisible(false);
        logArea.setManaged(false);

        VBox content = new VBox(8,
                heading("MOD LOADER"), loaderRow,
                heading("MINECRAFT VERSION"), mcBox,
                heading("LOADER VERSION"), buildBox,
                note, bar, status, logArea);
        content.setPadding(new Insets(4, 0, 0, 0));
        content.setPrefWidth(440);
        dialog.getDialogPane().setContent(content);

        reloadBuilds();
        dialog.show();   // non-blocking: the launcher stays usable and the download list keeps updating
    }

    private static Label heading(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("play-eyebrow");
        return l;
    }

    private ModLoader selectedLoader() {
        Object u = loaderGroup.getSelectedToggle() == null ? null : loaderGroup.getSelectedToggle().getUserData();
        return u instanceof ModLoader l ? l : ModLoader.FABRIC;
    }

    private void reloadBuilds() {
        if (installing || installed) return;
        final int mine = ++ticket;
        ModLoader loader = selectedLoader();
        String mc = mcBox.getValue();
        buildBox.getItems().clear();
        buildBox.setValue(null);
        status.setText("");
        if (mc == null) { note.setText("Pick a Minecraft version."); refreshButtons(); return; }
        note.setText("Looking up " + loader.displayName() + " builds for Minecraft " + mc + "\u2026");
        refreshButtons();
        service.versions(loader, mc).whenComplete((list, err) -> Platform.runLater(() -> {
            if (mine != ticket) return;   // the choice changed while this was loading
            if (err != null) {
                note.setText("Could not load builds: " + message(err));
            } else if (list.isEmpty()) {
                note.setText(loader.displayName() + " has no build for Minecraft " + mc + ". Try another version.");
            } else {
                buildBox.getItems().setAll(list);
                buildBox.setValue(list.stream().filter(LoaderVersion::recommended).findFirst().orElse(list.get(0)));
                note.setText(loader.usesInstallerJar()
                        ? loader.displayName() + " is set up with its official installer. It needs Java 17+ and can take a few minutes."
                        : loader.displayName() + " installs instantly; its libraries download the first time you launch it.");
            }
            refreshButtons();
        }));
    }

    private void refreshButtons() {
        installButton.setDisable(installing || (!installed && buildBox.getValue() == null));
        installButton.setText(installed ? "Done" : "Install");
        closeButton.setDisable(installing);
        loaderRow.setDisable(installing || installed);
        mcBox.setDisable(installing || installed);
        buildBox.setDisable(installing || installed);
    }

    private void startInstall() {
        ModLoader loader = selectedLoader();
        String mc = mcBox.getValue();
        LoaderVersion lv = buildBox.getValue();
        if (mc == null || lv == null) return;

        installing = true;
        bar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        bar.setVisible(true);
        bar.setManaged(true);
        if (loader.usesInstallerJar()) { logArea.clear(); logArea.setVisible(true); logArea.setManaged(true); }
        note.setText("");
        status.setText("Starting\u2026");
        refreshButtons();

        service.install(loader, mc, lv, settings,
                        msg -> Platform.runLater(() -> status.setText(msg)),
                        line -> Platform.runLater(() -> appendLog(line)),
                        null)   // file downloads already reach the Downloads tab through the shared listener
                .whenComplete((id, err) -> Platform.runLater(() -> {
                    installing = false;
                    bar.setVisible(false);
                    bar.setManaged(false);
                    if (err != null) {
                        status.setText("Install failed: " + message(err));
                        refreshButtons();
                        return;
                    }
                    installed = true;
                    status.setText("Installed " + id + ". It is now in your version list.");
                    refreshButtons();
                    onInstalled.accept(id);
                }));
    }

    private void appendLog(String line) {
        logArea.appendText(line + "\n");
        if (logArea.getLength() > MAX_LOG_CHARS) logArea.deleteText(0, logArea.getLength() - MAX_LOG_CHARS / 2);
    }

    private static String message(Throwable t) {
        Throwable c = t;
        while (c instanceof CompletionException && c.getCause() != null) c = c.getCause();
        return c.getMessage() != null ? c.getMessage() : c.toString();
    }
}
