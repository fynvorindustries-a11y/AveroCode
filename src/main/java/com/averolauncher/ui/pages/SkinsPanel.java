package com.averolauncher.ui.pages;

import com.averolauncher.auth.AccountSessions;
import com.averolauncher.logging.AppLogger;
import com.averolauncher.model.Account;
import com.averolauncher.skins.SkinImage;
import com.averolauncher.skins.SkinLibrary;
import com.averolauncher.skins.SkinLibrary.SavedSkin;
import com.averolauncher.skins.SkinService;
import com.averolauncher.skins.SkinSharing;
import com.averolauncher.ui.AppContext;
import com.averolauncher.ui.DialogStyler;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * The skins section of the Accounts page: a library of skins the player has added (from a file, a
 * player's name, or a link) and one click to wear any of them.
 *
 * <p>Applying uploads the skin to the active Microsoft account's Minecraft profile — the only way a
 * custom skin loads in-game, because the game fetches skins from Mojang by profile. Offline profiles
 * have no Mojang profile, so for those the panel says so instead of pretending.
 */
public class SkinsPanel extends VBox {

    private static final String TAG = "SkinsPanel";
    private static final int PREVIEW_SCALE = 4; // 16x32 front view -> 64x128

    private final AppContext ctx;
    private final Runnable onAccountChanged;
    private final SkinLibrary library = new SkinLibrary();
    private final SkinService service = new SkinService();
    private final Map<String, Image> previewCache = new HashMap<>();

    private final Label targetLabel = new Label();
    private final Label statusLabel = new Label();
    private final VBox currentBox = new VBox();
    private final FlowPane grid = new FlowPane(14, 14);
    private final FlowPane toolbar = new FlowPane(10, 10); // wraps on a narrow window instead of squeezing buttons
    private boolean busy;

    public SkinsPanel(AppContext ctx, Runnable onAccountChanged) {
        super(14);
        this.ctx = ctx;
        this.onAccountChanged = onAccountChanged;

        Button fromFile = new Button("\uFF0B  Add from file");
        fromFile.getStyleClass().add("primary-cta-button");
        fromFile.setOnAction(e -> addFromFile());
        Button fromName = new Button("From a player\u2019s name");
        fromName.getStyleClass().add("secondary-button");
        fromName.setOnAction(e -> addFromUsername());
        Button fromLink = new Button("From a link");
        fromLink.getStyleClass().add("secondary-button");
        fromLink.setOnAction(e -> addFromLink());
        toolbar.getChildren().addAll(fromFile, fromName, fromLink);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        targetLabel.getStyleClass().add("play-card-hint");
        targetLabel.setWrapText(true);
        statusLabel.getStyleClass().add("play-card-hint");
        statusLabel.setWrapText(true);
        setStatus(null);

        grid.setPrefWrapLength(0);
        getChildren().addAll(toolbar, targetLabel, statusLabel, currentBox, grid);
        refresh();
    }

    // ------------------------------------------------------------------ rebuild

    /** Re-reads the active account and the library. Safe to call whenever accounts change. */
    public void refresh() {
        Account active = active();
        if (active == null) {
            targetLabel.setText("Add or select an account above to wear a skin.");
        } else if (active.type == Account.Type.MICROSOFT) {
            targetLabel.setText("Skins you apply are set on " + active.username + "\u2019s Minecraft profile, "
                    + "so they show in-game everywhere \u2014 singleplayer, servers, and to other players.");
        } else {
            targetLabel.setText("\u201C" + active.username + "\u201D is an offline profile. Skins you apply are loaded by the launcher "
                    + "straight into the game, with no resource pack, so you see them in singleplayer, on LAN and on "
                    + "offline-mode servers. " + shareHint(active));
        }
        rebuildCurrent(active);
        rebuildGrid();
    }

    private void rebuildGrid() {
        grid.getChildren().clear();
        var skins = library.list();
        if (skins.isEmpty()) {
            Label empty = new Label("No saved skins yet. Add one from a file, a player\u2019s name, or a link.");
            empty.getStyleClass().add("activity-empty");
            empty.setWrapText(true);
            grid.getChildren().add(empty);
            return;
        }
        for (SavedSkin s : skins) grid.getChildren().add(skinCard(s));
    }

    private VBox skinCard(SavedSkin s) {
        Image img = previewFor(s);
        Node previewNode = img != null ? previewBox(img) : new Label("?");
        Label name = new Label(s.name);
        name.getStyleClass().add("profile-name");
        name.setStyle("-fx-font-size: 12.5px;");
        name.setMaxWidth(130);
        Label model = new Label(s.isSlim() ? "Slim" : "Classic");
        model.getStyleClass().add("status-chip");

        Button apply = new Button("Apply");
        apply.getStyleClass().add("primary-cta-button");
        apply.setStyle("-fx-padding: 6 16 6 16; -fx-font-size: 12px;");
        apply.setOnAction(e -> apply(s));
        Button remove = new Button("\u2715");
        remove.getStyleClass().add("button");
        remove.setStyle("-fx-padding: 5 10 5 10; -fx-font-size: 12px;");
        remove.setTooltip(new Tooltip("Delete from library"));
        remove.setOnAction(e -> confirmDelete(s));
        HBox actions = new HBox(6, apply, remove);
        actions.setAlignment(Pos.CENTER);

        VBox card = new VBox(8, previewNode, name, model, actions);
        card.setAlignment(Pos.TOP_CENTER);
        card.getStyleClass().add("settings-section");
        card.setPadding(new Insets(12));
        card.setPrefWidth(156);
        card.setMinWidth(156);
        return card;
    }

    private static StackPane previewBox(Image img) {
        ImageView iv = new ImageView(img);
        iv.setSmooth(false);                       // keep pixels crisp
        iv.setFitWidth(img.getWidth());
        iv.setFitHeight(img.getHeight());
        StackPane box = new StackPane(iv);
        box.setPadding(new Insets(8));
        box.setStyle("-fx-background-color: rgba(0,0,0,0.28); -fx-background-radius: 0;");
        return box;
    }

    private Image previewFor(SavedSkin s) {
        Image cached = previewCache.get(s.id);
        if (cached != null) return cached;
        try {
            BufferedImage skin = SkinImage.load(library.readPng(s.id));
            Image img = toFx(SkinImage.scale(SkinImage.renderFront(skin, s.isSlim()), PREVIEW_SCALE));
            previewCache.put(s.id, img);
            return img;
        } catch (Exception e) {
            AppLogger.get().warn(TAG, "Could not render preview for " + s.name + ": " + e.getMessage());
            return null;
        }
    }

    private static Image toFx(BufferedImage bi) {
        WritableImage out = new WritableImage(bi.getWidth(), bi.getHeight());
        PixelWriter pw = out.getPixelWriter();
        for (int y = 0; y < bi.getHeight(); y++) {
            for (int x = 0; x < bi.getWidth(); x++) pw.setArgb(x, y, bi.getRGB(x, y));
        }
        return out;
    }

    // ------------------------------------------------------------------ "current skin" card (Microsoft accounts)

    private void rebuildCurrent(Account active) {
        currentBox.getChildren().clear();
        if (active != null && active.type != Account.Type.MICROSOFT) {
            if (active.offlineSkinId == null) return;
            String shown = library.list().stream().filter(x -> x.id.equals(active.offlineSkinId))
                    .map(x -> x.name).findFirst().orElse("a saved skin");
            Label t = new Label("WORN BY " + active.username.toUpperCase());
            t.getStyleClass().add("card-title");
            Label l = new Label("\u201C" + shown + "\u201D \u2014 applied the next time you press Play.");
            l.getStyleClass().add("play-card-hint");
            l.setWrapText(true);
            Button off = new Button("Remove skin");
            off.getStyleClass().add("secondary-button");
            off.setOnAction(e -> {
                active.offlineSkinId = null;
                active.offlineSkinVariant = null;
                ctx.accountRepository.replace(active);
                setStatus("Skin removed. The default skin is used from the next launch.");
                refresh();
                if (onAccountChanged != null) onAccountChanged.run();
                shareWithAvero(active, null);
            });
            VBox c = new VBox(10, t, l, off);
            c.getStyleClass().add("settings-section");
            c.setPadding(new Insets(14, 16, 14, 16));
            currentBox.getChildren().add(c);
            return;
        }
        if (active == null) return;

        Label title = new Label("CURRENT SKIN ON " + active.username.toUpperCase());
        title.getStyleClass().add("card-title");
        Label loading = new Label("Loading\u2026");
        loading.getStyleClass().add("play-card-hint");
        VBox card = new VBox(10, title, loading);
        card.getStyleClass().add("settings-section");
        card.setPadding(new Insets(14, 16, 14, 16));
        currentBox.getChildren().add(card);

        // Ask Mojang for the truth (the stored URL can be stale after changing skins elsewhere), then draw it.
        AccountSessions.refreshAndSave(ctx.microsoftAuthService, active)
                .thenCompose(fresh -> service.currentProfile(fresh.minecraftAccessToken).thenApply(ps -> {
                    if (ps.skinUrl != null) { fresh.skinUrl = ps.skinUrl; }
                    if (ps.variant != null) { fresh.skinVariant = ps.variant; }
                    AccountSessions.save(fresh);
                    return fresh;
                }))
                .exceptionally(ex -> { // offline / refused: fall back to what we stored at sign-in
                    AppLogger.get().info(TAG, "Current skin lookup skipped: " + rootMsg(ex));
                    return active;
                })
                .thenCompose(acc -> {
                    if (acc.skinUrl == null) return java.util.concurrent.CompletableFuture.<byte[]>completedFuture(null);
                    return service.download(acc.skinUrl).exceptionally(ex -> null);
                })
                .whenComplete((bytes, ex) -> Platform.runLater(() -> {
                    // The player may have switched account while this was loading.
                    Account now = active();
                    if (now == null || !now.id.equals(active.id)) return;
                    Account stored = ctx.accountRepository.load().stream().filter(a -> a.id.equals(active.id)).findFirst().orElse(active);
                    fillCurrentCard(card, loading, stored, bytes);
                }));
    }

    private void fillCurrentCard(VBox card, Label loading, Account acc, byte[] png) {
        card.getChildren().remove(loading);
        BufferedImage skin = null;
        if (png != null) {
            try { skin = SkinImage.load(png); } catch (Exception e) { skin = null; }
        }
        if (skin == null) {
            Label none = new Label("Using the default skin (or it couldn\u2019t be loaded right now).");
            none.getStyleClass().add("play-card-hint");
            none.setWrapText(true);
            card.getChildren().add(none);
        } else {
            boolean slim = acc.skinVariant != null ? "slim".equalsIgnoreCase(acc.skinVariant) : SkinImage.looksSlim(skin);
            Image img = toFx(SkinImage.scale(SkinImage.renderFront(skin, slim), PREVIEW_SCALE));
            final BufferedImage fSkin = skin;
            final boolean fSlim = slim;
            Button save = new Button("Save to library");
            save.getStyleClass().add("secondary-button");
            save.setOnAction(e -> {
                try {
                    library.add(acc.username + "\u2019s skin", SkinImage.toPng(fSkin),
                            fSlim ? SkinLibrary.SLIM : SkinLibrary.CLASSIC, "current");
                    setStatus("Saved your current skin to the library.");
                    rebuildGrid();
                } catch (Exception ex) {
                    setStatus("Couldn\u2019t save it: " + rootMsg(ex));
                }
            });
            HBox row = new HBox(16, previewBox(img), new VBox(8, save));
            row.setAlignment(Pos.CENTER_LEFT);
            card.getChildren().add(row);
        }
        Button reset = new Button("Reset to default skin");
        reset.getStyleClass().add("secondary-button");
        reset.setOnAction(e -> resetSkin());
        card.getChildren().add(reset);
    }

    // ------------------------------------------------------------------ adding skins

    private void addFromFile() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Choose a Minecraft skin");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Minecraft skin (PNG)", "*.png"));
        File f = fc.showOpenDialog(owner());
        if (f == null) return;
        try {
            if (f.length() > SkinImage.MAX_BYTES) { setStatus("That file is too large to be a skin."); return; }
            byte[] data = Files.readAllBytes(f.toPath());
            promptAndSave(f.getName().replaceFirst("(?i)\\.png$", ""), data, null, "file");
        } catch (IOException e) {
            setStatus("Couldn\u2019t read that file: " + e.getMessage());
        }
    }

    private void addFromUsername() {
        TextInputDialog d = new TextInputDialog();
        d.setTitle("Add a skin from a player");
        d.setHeaderText("Copy any Java Edition player\u2019s current skin");
        d.setContentText("Minecraft name:");
        styleDialog(d);
        d.showAndWait().ifPresent(name -> {
            if (name.isBlank()) return;
            setBusy(true);
            setStatus("Looking up " + name.trim() + "\u2026");
            service.fetchByUsername(name).whenComplete((rs, ex) -> Platform.runLater(() -> {
                setBusy(false);
                if (ex != null) { setStatus(rootMsg(ex)); return; }
                setStatus(null);
                promptAndSave(rs.name, rs.png, rs.variant, "username:" + rs.name);
            }));
        });
    }

    private void addFromLink() {
        TextInputDialog d = new TextInputDialog();
        d.setTitle("Add a skin from a link");
        d.setHeaderText("Paste a direct link to a skin PNG");
        d.setContentText("Link:");
        styleDialog(d);
        d.showAndWait().ifPresent(url -> {
            if (url.isBlank()) return;
            setBusy(true);
            setStatus("Downloading\u2026");
            service.download(url).whenComplete((bytes, ex) -> Platform.runLater(() -> {
                setBusy(false);
                if (ex != null) { setStatus(rootMsg(ex)); return; }
                setStatus(null);
                String last = url.trim();
                int q = last.indexOf('?'); if (q >= 0) last = last.substring(0, q);
                last = last.substring(last.lastIndexOf('/') + 1).replaceFirst("(?i)\\.png$", "");
                promptAndSave(last.isBlank() ? "Skin" : last, bytes, null, "url");
            }));
        });
    }

    /** Validates the image, lets the player name it and confirm the arm model (with a live preview), then saves it. */
    private void promptAndSave(String suggestedName, byte[] data, String variantHint, String source) {
        BufferedImage skin;
        try {
            skin = SkinImage.load(data);
        } catch (SkinImage.InvalidSkinException e) {
            setStatus(e.getMessage());
            return;
        }
        boolean slimGuess = variantHint != null ? SkinLibrary.SLIM.equals(variantHint) : SkinImage.looksSlim(skin);

        TextField nameField = new TextField(suggestedName == null || suggestedName.isBlank() ? "My skin" : suggestedName);
        ToggleGroup group = new ToggleGroup();
        RadioButton classic = new RadioButton("Classic \u2014 4px arms (Steve)");
        RadioButton slim = new RadioButton("Slim \u2014 3px arms (Alex)");
        classic.setToggleGroup(group);
        slim.setToggleGroup(group);
        (slimGuess ? slim : classic).setSelected(true);

        ImageView iv = new ImageView(toFx(SkinImage.scale(SkinImage.renderFront(skin, slimGuess), PREVIEW_SCALE)));
        iv.setSmooth(false);
        StackPane previewHolder = new StackPane(iv);
        previewHolder.setPadding(new Insets(8));
        previewHolder.setStyle("-fx-background-color: rgba(0,0,0,0.28); -fx-background-radius: 0;");
        final BufferedImage fSkin = skin;
        group.selectedToggleProperty().addListener((o, was, now) ->
                iv.setImage(toFx(SkinImage.scale(SkinImage.renderFront(fSkin, now == slim), PREVIEW_SCALE))));

        Label nameLabel = new Label("Name");
        nameLabel.getStyleClass().add("card-title");
        Label modelLabel = new Label("Arm model");
        modelLabel.getStyleClass().add("card-title");
        Label hint = new Label("Wrong arms? Pick the other model \u2014 the preview shows the difference.");
        hint.getStyleClass().add("play-card-hint");
        hint.setWrapText(true);
        hint.setMaxWidth(260);
        VBox form = new VBox(8, nameLabel, nameField, modelLabel, classic, slim, hint);
        HBox content = new HBox(20, previewHolder, form);
        content.setPadding(new Insets(6, 4, 2, 4));

        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Add skin");
        dlg.setHeaderText(null);
        styleDialog(dlg);
        dlg.getDialogPane().setContent(content);
        dlg.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        ((Button) dlg.getDialogPane().lookupButton(ButtonType.OK)).setText("Save to library");
        Platform.runLater(nameField::requestFocus);

        Optional<ButtonType> res = dlg.showAndWait();
        if (res.isEmpty() || res.get() != ButtonType.OK) return;
        try {
            SavedSkin saved = library.add(nameField.getText(), data, slim.isSelected() ? SkinLibrary.SLIM : SkinLibrary.CLASSIC, source);
            setStatus("Added \u201C" + saved.name + "\u201D. Press Apply to wear it.");
            rebuildGrid();
        } catch (Exception e) {
            setStatus("Couldn\u2019t save the skin: " + rootMsg(e));
        }
    }

    // ------------------------------------------------------------------ applying

    private void apply(SavedSkin s) {
        Account acc = active();
        if (acc == null) { setStatus("Add or select an account first."); return; }
        if (acc.type != Account.Type.MICROSOFT) {
            acc.offlineSkinId = s.id;
            acc.offlineSkinVariant = s.variant;
            ctx.accountRepository.replace(acc);
            setStatus("\u2713 \u201C" + s.name + "\u201D will be worn by " + acc.username + " the next time you press Play. "
                    + shareHint(acc));
            refresh();
            if (onAccountChanged != null) onAccountChanged.run();
            shareWithAvero(acc, s.name);
            return;
        }
        if (busy) return;
        final byte[] png;
        try { png = library.readPng(s.id); } catch (IOException e) { setStatus("That skin\u2019s file is missing."); return; }

        setBusy(true);
        setStatus("Applying \u201C" + s.name + "\u201D to " + acc.username + "\u2026");
        AccountSessions.refreshAndSave(ctx.microsoftAuthService, acc)
                .thenCompose(fresh -> service.upload(fresh.minecraftAccessToken, png, s.variant).thenApply(ps -> {
                    if (ps.skinUrl != null) fresh.skinUrl = ps.skinUrl;
                    fresh.skinVariant = ps.variant != null ? ps.variant : s.variant;
                    AccountSessions.save(fresh);
                    return ps;
                }))
                .whenComplete((ps, ex) -> Platform.runLater(() -> {
                    setBusy(false);
                    if (ex != null) {
                        AppLogger.get().warn(TAG, "Applying skin failed: " + rootMsg(ex));
                        setStatus(rootMsg(ex));
                        return;
                    }
                    setStatus("\u2713 \u201C" + s.name + "\u201D is now " + acc.username + "\u2019s skin. It shows the next time you join "
                            + "a world or server (Mojang can take up to a minute to update).");
                    refresh();
                    if (onAccountChanged != null) onAccountChanged.run();
                }));
    }

    private void resetSkin() {
        Account acc = active();
        if (acc == null || acc.type != Account.Type.MICROSOFT || busy) return;
        setBusy(true);
        setStatus("Resetting " + acc.username + "\u2019s skin\u2026");
        AccountSessions.refreshAndSave(ctx.microsoftAuthService, acc)
                .thenCompose(fresh -> service.reset(fresh.minecraftAccessToken).thenApply(ps -> {
                    fresh.skinUrl = ps.skinUrl;
                    fresh.skinVariant = ps.variant;
                    AccountSessions.save(fresh);
                    return ps;
                }))
                .whenComplete((ps, ex) -> Platform.runLater(() -> {
                    setBusy(false);
                    if (ex != null) { setStatus(rootMsg(ex)); return; }
                    setStatus("\u2713 Skin reset to the default.");
                    refresh();
                    if (onAccountChanged != null) onAccountChanged.run();
                }));
    }

    private void confirmDelete(SavedSkin s) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION);
        a.setTitle("Delete skin");
        a.setHeaderText("Delete \u201C" + s.name + "\u201D from your library?");
        a.setContentText("This only removes it from the launcher. A skin you already applied stays on your Minecraft account.");
        styleDialog(a);
        a.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) return;
            library.delete(s.id);
            previewCache.remove(s.id);
            rebuildGrid();
        });
    }

    // ------------------------------------------------------------------ sharing with other Avero players

    /** Honest one-liner about who sees an offline skin. Minecraft only trusts Mojang-signed skins, so non-Avero players can't. */
    private static String shareHint(Account acc) {
        if (SkinSharing.canShare(acc)) {
            return "It is also shared with everyone using Avero, so they see it on you in-game "
                    + "(players without Avero can’t — Minecraft only accepts skins from Mojang).";
        }
        return "Only you see it: link this name to your Avero ID (Avero account) to show it to everyone using Avero. "
                + "To show it to every Minecraft player, use a Microsoft account.";
    }

    /** Publishes (or, with null, removes) the offline skin on the Avero server and reports the outcome in the status line. */
    private void shareWithAvero(Account acc, String skinName) {
        if (!SkinSharing.canShare(acc)) return;
        SkinSharing.sync(ctx.accountRepository, acc).thenAccept(r -> Platform.runLater(() -> {
            switch (r) {
                case SHARED -> setStatus(skinName == null
                        ? "Skin removed, and no longer shared with Avero players."
                        : "✓ “" + skinName + "” is shared — other Avero players see it on " + acc.username + ".");
                case NOT_SIGNED_IN -> setStatus("Saved for you. Sign in to your Avero ID to share this skin with other Avero players.");
                case FAILED -> setStatus("Saved for you, but couldn’t reach the Avero server to share it. It will retry the next time you press Play.");
                default -> { }
            }
        }));
    }

    // ------------------------------------------------------------------ helpers

    private Account active() {
        String id = ctx.settings.activeAccountId;
        if (id == null) return null;
        return ctx.accountRepository.load().stream().filter(a -> id.equals(a.id)).findFirst().orElse(null);
    }

    private void setBusy(boolean b) {
        busy = b;
        toolbar.setDisable(b);
        grid.setDisable(b);
    }

    private void setStatus(String text) {
        boolean show = text != null && !text.isEmpty();
        statusLabel.setText(show ? text : "");
        statusLabel.setVisible(show);
        statusLabel.setManaged(show);
    }

    private javafx.stage.Window owner() {
        return getScene() != null ? getScene().getWindow() : null;
    }

    private void styleDialog(Dialog<?> d) {
        DialogStyler.style(d);
        javafx.stage.Window w = owner();
        if (w != null) d.initOwner(w);
    }

    private static String rootMsg(Throwable ex) {
        Throwable cause = ex;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        while (cause.getCause() != null && !(cause instanceof SkinService.SkinException)) cause = cause.getCause();
        String m = cause.getMessage() != null ? cause.getMessage() : ex.getMessage();
        return m != null && !m.isBlank() ? m : "Something went wrong. Please try again.";
    }
}
