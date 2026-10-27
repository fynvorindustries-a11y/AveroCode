package com.averolauncher.ui.pages;

import com.averolauncher.logging.AppLogger;
import com.averolauncher.model.Account;
import com.averolauncher.model.auth.AuthModels;
import com.averolauncher.store.AveroId;
import com.averolauncher.ui.DialogStyler;
import com.averolauncher.ui.AppContext;
import com.averolauncher.ui.NavPage;
import com.averolauncher.util.UiAccents;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

import java.util.Set;
import java.util.HashSet;

import java.util.List;
import java.util.UUID;

/**
 * Minecraft account management: Microsoft sign-in and Avero ID sign-in (whose linked names become playable accounts).
 *
 * The Avero ID panel sits below the Minecraft account list so players can
 * manage both in one place without switching pages.
 */
public class AccountsPage {

    private static final String TAG = "AccountsPage";

    private final AppContext appContext;
    private List<Account> accounts;
    private final VBox accountListBox = new VBox(10);
    private final Label statusLabel   = new Label();
    private AveroIdCard averoCard;
    private SkinsPanel skinsPanel;
    private Dialog<Void> deviceAlert;
    private javafx.animation.Timeline deviceTimer;
    private final AveroId avero = AveroId.get();
    /** Lower-cased names already linked + verified on the signed-in Avero ID (drives the "Linked" badge). */
    private final Set<String> linkedNames = new HashSet<>();
    private final Runnable averoListener = () -> Platform.runLater(() -> { refreshAccountList(); reloadLinked(); });

    public AccountsPage(AppContext appContext) {
        this.appContext = appContext;
        this.accounts   = appContext.accountRepository.load();
    }

    public VBox build() {
        VBox root = new VBox(24);
        root.getStyleClass().add("page");
        root.setPadding(new Insets(32));

        // ── Page header ──
        Label title = new Label("Accounts");
        title.getStyleClass().add("page-title");
        HBox titleRow = new HBox(14, UiAccents.pageBadge(NavPage.ACCOUNTS, 40), title);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Label subtitle = new Label(
                "Minecraft accounts control what name and UUID the game sees. "
                + "Sign in with Microsoft for a genuine Minecraft session, "
                + "or sign in with your Avero ID to play under the name linked to it.");
        subtitle.getStyleClass().add("page-subtitle");
        subtitle.setWrapText(true);

        root.getChildren().addAll(titleRow, UiAccents.pageUnderline(), subtitle);

        // ── Microsoft hero card ──
        Label mcHeader = new Label("MINECRAFT ACCOUNTS");
        mcHeader.getStyleClass().add("card-title");

        Button msBtn = new Button("Sign in with Microsoft");
        msBtn.getStyleClass().add("ms-hero-button");
        msBtn.setOnAction(e -> startMicrosoftSignIn());
        // A Button's minimum width is just an ellipsis, so in a tight row JavaFX squeezed it down to "…".
        // Keep it at its full size and let the explanatory text beside it wrap instead.
        msBtn.setMinWidth(Region.USE_PREF_SIZE);

        Label heroTitle = new Label("Play with your real Minecraft account");
        heroTitle.getStyleClass().add("ms-hero-title");
        heroTitle.setWrapText(true); // wrap instead of cutting off with "..." on a narrow window
        Label heroSub = new Label("One click. Your browser opens, the code is copied for you, and everything else happens by itself "
                + "\u2014 AveroLauncher never sees your password.");
        heroSub.getStyleClass().add("ms-hero-sub");
        heroSub.setWrapText(true);

        // FlowPane (not HBox): on a narrow window the chips wrap onto a second row instead of being squeezed.
        FlowPane chips = new FlowPane(8, 8, autoChip("\u26A1 Browser opens automatically"), autoChip("\u2398 Code copied for you"),
                autoChip("\u2713 Linked to your Avero ID"));
        chips.setAlignment(Pos.CENTER_LEFT);

        VBox heroText = new VBox(8, heroTitle, heroSub, chips);
        HBox.setHgrow(heroText, Priority.ALWAYS);
        HBox heroRow = new HBox(22, microsoftLogo(46), heroText, msBtn);
        heroRow.setAlignment(Pos.CENTER_LEFT);
        VBox hero = new VBox(heroRow);
        hero.getStyleClass().add("ms-hero");

        statusLabel.getStyleClass().add("play-card-hint");
        statusLabel.setWrapText(true);
        statusLabel.setVisible(false);
        statusLabel.setManaged(false);

        refreshAccountList();

        root.getChildren().addAll(mcHeader, hero, statusLabel, accountListBox);

        // Keep the "Linked" badges in sync with the Avero ID, and kick off the automatic Microsoft -> Avero link.
        avero.addListener(averoListener);
        root.sceneProperty().addListener((o, was, now) -> { if (now == null) avero.removeListener(averoListener); });
        reloadLinked();
        avero.autoLinkMicrosoft(appContext);

        // ── Avero ID section ──
        Label averoHeader = new Label("AVERO ID");
        averoHeader.getStyleClass().add("card-title");
        VBox.setMargin(averoHeader, new Insets(8, 0, 0, 0));

        Label averoDesc = new Label(
                "Sign in (or create an Avero ID) to play under a name linked to it \u2014 it appears in the list above and "
                + "your Avero cape follows it. Sign in with Microsoft above and your real Minecraft name is verified and "
                + "linked here automatically.");
        averoDesc.getStyleClass().add("page-subtitle");
        averoDesc.setWrapText(true);

        averoCard = new AveroIdCard(appContext, null);
        root.getChildren().addAll(averoHeader, averoDesc, averoCard);

        // ── Skins section ──
        Label skinsHeader = new Label("SKINS");
        skinsHeader.getStyleClass().add("card-title");
        VBox.setMargin(skinsHeader, new Insets(8, 0, 0, 0));
        Label skinsDesc = new Label(
                "Add skins from a file, a player\u2019s name or a link, then apply one to your Microsoft account. "
                + "It\u2019s set on your Minecraft profile, so it loads in-game everywhere.");
        skinsDesc.getStyleClass().add("page-subtitle");
        skinsDesc.setWrapText(true);
        skinsPanel = new SkinsPanel(appContext, this::rebuildAccountRows);
        root.getChildren().addAll(skinsHeader, skinsDesc, skinsPanel);

        return root;
    }

    // ── Account list ───────────────────────────────────────────────────────

    /** Rebuilds the account rows AND the skins panel (the active account decides where skins are applied). */
    private void refreshAccountList() {
        rebuildAccountRows();
        if (skinsPanel != null) skinsPanel.refresh();
    }

    private void rebuildAccountRows() {
        // Always re-read: tokens are refreshed and saved in the background, and this page must never
        // write an out-of-date copy of them back to disk.
        accounts = appContext.accountRepository.load();
        accountListBox.getChildren().clear();
        if (accounts.isEmpty()) {
            Label empty = new Label("No Minecraft accounts yet. Sign in with Microsoft, or sign in with your Avero ID below.");
            empty.getStyleClass().add("activity-empty");
            empty.setWrapText(true);
            accountListBox.getChildren().add(empty);
            return;
        }
        for (Account account : accounts) {
            accountListBox.getChildren().add(buildAccountRow(account));
        }
    }

    private HBox buildAccountRow(Account account) {
        boolean isActive = account.id.equals(appContext.settings.activeAccountId);
        boolean ms = account.type == Account.Type.MICROSOFT;

        // ── Avatar: real skin head for Microsoft accounts (falls back to an initial) ──
        StackPane avatar = buildAvatar(account);

        // ── Name + chips ──
        Label name = new Label(account.username);
        name.getStyleClass().add("profile-name");
        name.setStyle("-fx-font-size: 14px;");

        Label tag = new Label(ms ? "Microsoft account" : account.averoLinked ? "Avero account" : "Offline profile");
        tag.getStyleClass().addAll("profile-tag", ms ? "account-type-ms" : "account-type-offline");
        HBox tagRow = new HBox(8, tag);
        tagRow.setAlignment(Pos.CENTER_LEFT);
        if (account.averoLinked) {
            Label linked = new Label("\u2713 Avero ID");
            linked.getStyleClass().add("auto-chip");
            tagRow.getChildren().add(linked);
        } else if (ms && account.username != null && linkedNames.contains(account.username.toLowerCase())) {
            Label linked = new Label("\u2713 Linked to Avero ID");
            linked.getStyleClass().add("auto-chip");
            tagRow.getChildren().add(linked);
        }

        VBox names = new VBox(3, name, tagRow);
        HBox.setHgrow(names, Priority.ALWAYS);

        HBox row = new HBox(14, avatar, names);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("settings-section");
        if (isActive) row.getStyleClass().add("account-row-active");
        row.setPadding(new Insets(12, 16, 12, 16));

        if (isActive) {
            Label activeBadge = new Label("ACTIVE");
            activeBadge.getStyleClass().addAll("status-chip", "status-ok");
            activeBadge.setStyle("-fx-font-size: 9.5px; -fx-font-weight: bold;");
            row.getChildren().add(activeBadge);
        } else {
            Button useBtn = new Button("Use");
            useBtn.getStyleClass().add("secondary-button");
            useBtn.setStyle("-fx-padding: 5 14 5 14;");
            useBtn.setOnAction(e -> {
                appContext.settings.activeAccountId = account.id;
                appContext.settingsRepository.save(appContext.settings);
                refreshAccountList();
            });
            row.getChildren().add(useBtn);
        }

        if (account.averoLinked) {
            // Managed by the Avero ID: unlink the name in the Avero ID panel (or sign out) to remove it.
            return row;
        }
        Button removeBtn = new Button("\u2715");
        removeBtn.getStyleClass().add("button");
        removeBtn.setStyle("-fx-padding: 5 10 5 10; -fx-font-size: 12px;");
        removeBtn.setTooltip(new Tooltip("Remove this account"));
        removeBtn.setOnAction(e -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            DialogStyler.style(confirm);
            confirm.setTitle("Remove Account");
            confirm.setHeaderText("Remove \u201c" + account.username + "\u201d?");
            confirm.setContentText("This removes the saved session from this launcher. "
                    + "You can always sign back in.");
            confirm.showAndWait().ifPresent(bt -> {
                if (bt != ButtonType.OK) return;
                accounts = appContext.accountRepository.load();
                accounts.removeIf(a -> a.id.equals(account.id));
                appContext.accountRepository.save(accounts);
                if (account.id.equals(appContext.settings.activeAccountId)) {
                    appContext.settings.activeAccountId = accounts.isEmpty() ? null : accounts.get(0).id;
                    appContext.settingsRepository.save(appContext.settings);
                }
                refreshAccountList();
            });
        });
        row.getChildren().add(removeBtn);
        return row;
    }

    /** 42px rounded avatar. Microsoft accounts load their skin face; everything falls back to a coloured initial. */
    private static StackPane buildAvatar(Account account) {
        boolean ms = account.type == Account.Type.MICROSOFT;
        StackPane avatar = new StackPane();
        avatar.setMinSize(42, 42); avatar.setMaxSize(42, 42);
        avatar.setStyle(ms
                ? "-fx-background-color: linear-gradient(to bottom right, #2a6a9c, #14304f); -fx-background-radius: 0;"
                : "-fx-background-color: linear-gradient(to bottom right, #8a6a1f, #3d2d0a); -fx-background-radius: 0;");
        Label initial = new Label(account.username != null && !account.username.isBlank()
                ? account.username.substring(0, 1).toUpperCase() : "?");
        initial.setStyle("-fx-text-fill: " + (ms ? "#ece8e1" : "#f0c060") + "; -fx-font-size: 17px; -fx-font-weight: bold;");
        avatar.getChildren().add(initial);

        if (!ms && account.offlineSkinId != null && !account.offlineSkinId.isBlank()) {
            // Offline account wearing a library skin: draw its real face from the saved PNG.
            java.nio.file.Path png = new com.averolauncher.skins.SkinLibrary().pngPath(account.offlineSkinId);
            if (java.nio.file.Files.isRegularFile(png)) {
                com.averolauncher.ui.kit.SkinFace.load(png.toUri().toString(), face -> {
                    if (face == null) return;
                    ImageView fv = new ImageView(face);
                    fv.setFitWidth(42); fv.setFitHeight(42); fv.setSmooth(true);
                    avatar.getChildren().setAll(fv);
                });
            }
        }
        if (ms && account.minecraftUuid != null && !account.minecraftUuid.isBlank()) {
            try {
                String id = account.minecraftUuid.replace("-", "");
                // 4x the displayed size, shown smoothed down: crisp on any screen scaling.
                Image img = new Image("https://mc-heads.net/avatar/" + id + "/168", 168, 168, true, true, true);
                ImageView iv = new ImageView(img);
                iv.setSmooth(true);
                iv.setFitWidth(42); iv.setFitHeight(42);
                Rectangle clip = new Rectangle(42, 42);
                clip.setArcWidth(0); clip.setArcHeight(0);
                iv.setClip(clip);
                img.progressProperty().addListener((o, was, now) -> {
                    if (now.doubleValue() >= 1.0 && !img.isError() && !avatar.getChildren().contains(iv)) {
                        avatar.getChildren().setAll(iv);
                    }
                });
            } catch (Exception ignored) { /* keep the initial */ }
        }
        return avatar;
    }

    private static Label autoChip(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("auto-chip");
        l.setMinWidth(Region.USE_PREF_SIZE); // never collapse to "..."
        return l;
    }

    /** Four-square Microsoft mark, drawn so no image asset is needed. */
    private static Node microsoftLogo(double size) {
        double g = size * 0.06, c = (size - g) / 2;
        Rectangle r1 = new Rectangle(c, c, Color.web("#f25022"));
        Rectangle r2 = new Rectangle(c, c, Color.web("#7fba00"));
        Rectangle r3 = new Rectangle(c, c, Color.web("#00a4ef"));
        Rectangle r4 = new Rectangle(c, c, Color.web("#ffb900"));
        GridPane grid = new GridPane();
        grid.setHgap(g); grid.setVgap(g);
        grid.add(r1, 0, 0); grid.add(r2, 1, 0); grid.add(r3, 0, 1); grid.add(r4, 1, 1);
        StackPane box = new StackPane(grid);
        box.setMinSize(size + 18, size + 18); box.setMaxSize(size + 18, size + 18);
        box.setStyle("-fx-background-color: rgba(255,255,255,0.07); -fx-background-radius: 0;");
        return box;
    }

    /** Pulls the verified names of the signed-in Avero ID so account rows can show a "Linked" badge. */
    private void reloadLinked() {
        if (!avero.isSignedIn()) {
            if (!linkedNames.isEmpty()) { linkedNames.clear(); refreshAccountList(); }
            return;
        }
        avero.api().me().whenComplete((user, err) -> Platform.runLater(() -> {
            if (err != null || user == null) return;
            Set<String> now = new HashSet<>();
            if (user.profiles != null) user.profiles.forEach(pr -> { if (pr.verified && pr.username != null) now.add(pr.username.toLowerCase()); });
            if (!now.equals(linkedNames)) { linkedNames.clear(); linkedNames.addAll(now); refreshAccountList(); }
        }));
    }

    // ── Microsoft sign-in ──────────────────────────────────────────────────

    private void startMicrosoftSignIn() {
        setStatus("Requesting sign-in code from Microsoft\u2026");
        appContext.microsoftAuthService.signIn(this::showDeviceCode)
                .whenComplete((account, ex) -> Platform.runLater(() -> {
                    closeDeviceAlert();
                    if (ex != null) {
                        setStatus("Sign-in failed: " + rootMsg(ex));
                        AppLogger.get().error(TAG, "Microsoft sign-in failed", ex);
                    } else {
                        accounts = appContext.accountRepository.load();
                        accounts.removeIf(a -> a.minecraftUuid != null
                                && a.minecraftUuid.equals(account.minecraftUuid));
                        accounts.add(account);
                        appContext.accountRepository.save(accounts);
                        appContext.settings.activeAccountId = account.id;
                        appContext.settingsRepository.save(appContext.settings);
                        refreshAccountList();
                        if (avero.isSignedIn()) {
                            setStatus("Signed in as " + account.username + ". Linking it to your Avero ID\u2026");
                            avero.autoLinkMicrosoft(appContext);
                            // the listener refreshes the badge once the server has verified the name
                            javafx.animation.PauseTransition t = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(3));
                            t.setOnFinished(ev -> { reloadLinked(); setStatus("Signed in as " + account.username + "."); });
                            t.play();
                        } else {
                            setStatus("Signed in as " + account.username + ". Sign in to your Avero ID below and this name links itself.");
                        }
                    }
                }));
    }

    private void showDeviceCode(AuthModels.DeviceCodeResponse code) {
        Platform.runLater(() -> {
            setStatus("Go to " + code.verification_uri + " and enter code: " + code.user_code);
            copyToClipboard(code.user_code);   // already on the clipboard; the dialog lets you copy again
            final String pageUrl = prefilledUrl(code); // opens with the code already typed in where Microsoft supports it
            openBrowser(pageUrl);

            closeDeviceAlert();
            Dialog<Void> dlg = new Dialog<>();
            dlg.setTitle("Sign in with Microsoft");
            dlg.setHeaderText(null);
            try {
                javafx.scene.Scene owner = statusLabel.getScene();
                if (owner != null) {
                    dlg.initOwner(owner.getWindow());
                    dlg.getDialogPane().getStylesheets().setAll(owner.getStylesheets());
                }
            } catch (Exception ignored) { }
            DialogPane pane = dlg.getDialogPane();
            pane.getStyleClass().add("device-dialog");

            Label heading = new Label("Sign in with Microsoft");
            heading.getStyleClass().add("device-title");
            Label step = new Label("Your browser was opened. Paste this code on the Microsoft page:");
            step.getStyleClass().add("device-step");
            step.setWrapText(true);

            // Read-only but selectable, so the code can be copied by hand too (Ctrl+C / right-click).
            TextField codeField = new TextField(code.user_code);
            codeField.setEditable(false);
            codeField.getStyleClass().add("device-code-field");
            codeField.setAlignment(Pos.CENTER);
            codeField.setOnMouseClicked(e -> codeField.selectAll());

            Button copyBtn = new Button("Copy code");
            copyBtn.getStyleClass().add("primary-cta-button");
            copyBtn.setDefaultButton(false);
            copyBtn.setOnAction(e -> {
                copyToClipboard(code.user_code);
                codeField.selectAll();
                copyBtn.setText("Copied \u2713");
                javafx.animation.PauseTransition t = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(1.6));
                t.setOnFinished(ev -> copyBtn.setText("Copy code"));
                t.play();
            });
            Button openBtn = new Button("Open Microsoft page");
            openBtn.getStyleClass().add("secondary-button");
            openBtn.setOnAction(e -> openBrowser(pageUrl));
            HBox buttons = new HBox(10, copyBtn, openBtn);
            buttons.setAlignment(Pos.CENTER);

            Label uri = new Label(code.verification_uri);
            uri.getStyleClass().add("device-uri");

            ProgressIndicator spin = new ProgressIndicator();
            spin.setPrefSize(16, 16);
            spin.setMaxSize(16, 16);
            Label timer = new Label();
            timer.getStyleClass().add("device-timer");
            startDeviceTimer(timer, code.expires_in);
            Label waiting = new Label("Waiting for you to finish \u2014 this window closes automatically.");
            waiting.getStyleClass().add("device-waiting");
            waiting.setWrapText(true);
            HBox wait = new HBox(10, spin, waiting);
            wait.setAlignment(Pos.CENTER_LEFT);

            VBox box = new VBox(14, heading, step, codeField, buttons, uri, wait, timer);
            box.setAlignment(Pos.CENTER);
            box.setPadding(new Insets(6, 10, 4, 10));
            box.setPrefWidth(400);
            pane.setContent(box);
            pane.getButtonTypes().setAll(ButtonType.CLOSE);

            dlg.show();
            deviceAlert = dlg;
            Platform.runLater(() -> { codeField.requestFocus(); codeField.selectAll(); });
        });
    }

    private static void copyToClipboard(String text) {
        try {
            javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
            cc.putString(text);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
        } catch (Exception e) { AppLogger.get().warn(TAG, "Clipboard unavailable: " + e.getMessage()); }
    }

    private static void openBrowser(String url) {
        try {
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
            }
        } catch (Exception e) { AppLogger.get().warn(TAG, "Could not open browser: " + e.getMessage()); }
    }

    private void closeDeviceAlert() {
        if (deviceTimer != null) { deviceTimer.stop(); deviceTimer = null; }
        if (deviceAlert != null) { deviceAlert.hide(); deviceAlert = null; }
    }

    /** Microsoft's /link page accepts ?otc=CODE to pre-fill the code; if it ignores it, the code is still on the clipboard. */
    private static String prefilledUrl(AuthModels.DeviceCodeResponse code) {
        String base = code.verification_uri;
        if (base == null || code.user_code == null) return base;
        if (base.contains("?")) return base;
        return base + "?otc=" + java.net.URLEncoder.encode(code.user_code, java.nio.charset.StandardCharsets.UTF_8);
    }

    private void startDeviceTimer(Label label, int seconds) {
        if (deviceTimer != null) deviceTimer.stop();
        final int[] left = { Math.max(seconds, 0) };
        Runnable paint = () -> label.setText(left[0] > 0
                ? String.format("Code expires in %d:%02d", left[0] / 60, left[0] % 60) : "Code expired \u2014 close this and try again.");
        paint.run();
        deviceTimer = new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(1), ev -> {
            if (left[0] > 0) left[0]--;
            paint.run();
        }));
        deviceTimer.setCycleCount(javafx.animation.Animation.INDEFINITE);
        deviceTimer.play();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private void setStatus(String text) {
        statusLabel.setText(text);
        boolean show = text != null && !text.isEmpty();
        statusLabel.setVisible(show);
        statusLabel.setManaged(show);
    }

    private static String rootMsg(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null) cause = cause.getCause();
        String m = cause.getMessage() != null ? cause.getMessage() : ex.getMessage();
        return m != null && !m.isBlank() ? m : "Something went wrong. Please try again.";
    }
}
