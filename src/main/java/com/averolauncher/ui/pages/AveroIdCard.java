package com.averolauncher.ui.pages;

import com.averolauncher.store.AveroId;
import com.averolauncher.store.StoreApi;
import com.averolauncher.store.StoreApi.AveroSession;
import com.averolauncher.store.StoreApi.AveroUser;
import com.averolauncher.store.StoreApi.Cosmetics;
import com.averolauncher.store.StoreApi.LinkedProfile;
import com.averolauncher.store.StoreApi.SessionInfo;
import com.averolauncher.ui.AppContext;
import com.averolauncher.ui.ValDeco;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

import java.util.List;

/**
 * Avero account panel — modern redesign.
 *
 * Signed out: email/password login, register, forgot-password (request + enter-code), email verification.
 * Signed in: profile header, email-verification banner, change email/password, collapsible sections for
 * cosmetics, sessions and linked player names.
 *
 * The Avero account is independent of Mojang/Microsoft accounts: nothing on this card touches them.
 */
public class AveroIdCard extends VBox {

    private final AppContext appContext;
    private final AveroId avero = AveroId.get();
    private final StoreApi api = avero.api();
    private final Runnable onChange;
    private final Label status = new Label();
    private final Runnable listener = () -> Platform.runLater(this::render);

    private boolean showCosmetics;
    private boolean showSessions;
    private boolean showProfiles;
    private boolean showRegister;
    private boolean showForgot;
    private boolean showReset;
    private boolean showVerify;
    private String pendingEmail = "";

    public AveroIdCard(AppContext appContext, Runnable onChange) {
        super(0);
        this.appContext = appContext;
        this.onChange = onChange != null ? onChange : () -> { };
        getStyleClass().add("avero-auth-card");
        status.getStyleClass().add("play-card-hint");
        status.setWrapText(true);
        status.setVisible(false);
        status.setManaged(false);

        avero.addListener(listener);
        sceneProperty().addListener((o, was, now) -> { if (now == null) avero.removeListener(listener); });
        render();
    }

    /** Re-draws the card (e.g. after a Minecraft account was linked from another page). */
    public void refresh() { Platform.runLater(this::render); }

    // ─────────────────────────────────────────────────────────────── render

    private void render() {
        getChildren().clear();
        if (avero.session() == null) {
            renderSignedOut();
        } else {
            renderSignedIn();
        }
    }

    // ═══════════════════════════════════════════════ SIGNED OUT ════════════

    private void renderSignedOut() {
        if (api.isDemo()) { renderNoServer(); return; }
        String problem = avero.takeSessionProblem();
        if (problem != null) setStatus(problem); // e.g. an admin disabled the account: say so, don't just vanish the sign-in
        if (showReset)     { renderReset();     return; }
        if (showVerify)    { renderVerify();    return; }
        if (showForgot)    { renderForgot();    return; }
        if (showRegister)  { renderRegister();  return; }
        renderLogin();
    }

    /** Shown when no Avero server is configured: there is no account system to log in to. */
    private void renderNoServer() {
        Label title = new Label("Avero server not connected");
        title.getStyleClass().add("section-header");
        title.setStyle("-fx-font-size: 16px;");
        Label blurb = new Label("Avero accounts live on the Avero store server, and none is configured, so you can't create "
                + "or sign in to an account right now. Minecraft accounts above still work.");
        blurb.getStyleClass().add("about-mirror-note");
        blurb.setWrapText(true);
        Label how = new Label("Server owner: start the store backend, then launch with AVERO_STORE_API=https://your-host/api/v1 "
                + "(or -Davero.store.api=...), or set PRODUCTION_URL in StoreApi.java for release builds.");
        how.getStyleClass().add("about-mirror-note");
        how.setWrapText(true);
        getChildren().addAll(title, blurb, how);
    }

    private void renderLogin() {
        // ── header ──
        Label title = new Label("Sign in to Avero");
        title.getStyleClass().add("section-header");
        title.setStyle("-fx-font-size: 16px;");

        String demoNote = "";
        Label blurb = new Label(
                "Unlock capes, manage sessions and link your player names." + demoNote);
        blurb.getStyleClass().add("about-mirror-note");
        blurb.setWrapText(true);

        // ── form ──
        TextField ident = styledField("Email or username", 280);
        PasswordField password = styledPassword("Password", 280);

        Button loginBtn = new Button("Sign In");
        loginBtn.getStyleClass().add("primary-cta-button");
        loginBtn.setPrefWidth(280);
        loginBtn.setPrefHeight(38);
        loginBtn.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        loginBtn.setOnAction(e -> {
            String id = trim(ident.getText());
            String pw = password.getText() == null ? "" : password.getText();
            if (id.isEmpty() || pw.isEmpty()) { setStatus("Enter your email/username and password."); return; }
            loginBtn.setDisable(true);
            setStatus("Signing in\u2026");
            api.login(id, pw, deviceName()).whenComplete((sess, err) -> Platform.runLater(() -> {
                loginBtn.setDisable(false);
                if (err != null) {
                    if ("EMAIL_NOT_VERIFIED".equals(codeOf(err))) {
                        pendingEmail = id.contains("@") ? id : "";
                        showVerify = true; render();
                    }
                    setStatus(msg(err));
                    return;
                }
                avero.signedIn(sess);
                clearStatus();
                onChange.run();
            }));
        });
        password.setOnAction(e -> loginBtn.fire());

        // ── secondary actions ──
        Button registerBtn = new Button("Create Account");
        registerBtn.getStyleClass().add("secondary-button");
        registerBtn.setOnAction(e -> { showRegister = true; clearStatus(); render(); });

        Button forgotBtn = new Button("Forgot Password");
        forgotBtn.getStyleClass().add("button");
        forgotBtn.setStyle("-fx-padding: 6 14 6 14; -fx-font-size: 12px;");
        forgotBtn.setOnAction(e -> { showForgot = true; clearStatus(); render(); });

        VBox form = new VBox(10, ident, password, loginBtn, new HBox(10, registerBtn), forgotBtn);
        form.setPadding(new Insets(16, 0, 4, 0));

        getChildren().addAll(brandMark(), title, blurb, status, form);
    }

    /** Round gradient "A" mark shown above the sign-in form. */
    private static StackPane brandMark() {
        Label a = new Label("A");
        a.getStyleClass().add("avero-brand-letter");
        StackPane m = new StackPane(a);
        m.getStyleClass().add("avero-brand-mark");
        VBox.setMargin(m, new Insets(0, 0, 12, 0));
        return m;
    }

    private void renderRegister() {
        Label title = new Label("Create an Avero Account");
        title.getStyleClass().add("section-header");
        title.setStyle("-fx-font-size: 16px;");

        Label blurb = new Label("One account for your capes, on every device. It is separate from your Minecraft/Microsoft login - we never ask for those.");
        blurb.getStyleClass().add("about-mirror-note");
        blurb.setWrapText(true);

        TextField username = styledField("Username (3-20 chars, A-Z 0-9 _)", 280);
        Label nameHint = new Label();
        nameHint.setWrapText(true);
        nameHint.setMaxWidth(280);
        nameHint.setVisible(false);
        nameHint.setManaged(false);
        TextField email    = styledField("Email address", 280);
        PasswordField pw   = styledPassword("Password (min 8 characters)", 280);

        Button createBtn = new Button("Create Account");
        createBtn.getStyleClass().add("primary-cta-button");
        createBtn.setPrefWidth(280);
        createBtn.setPrefHeight(38);
        createBtn.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        final boolean[] nameBlocked = { false };
        javafx.animation.PauseTransition debounce = new javafx.animation.PauseTransition(javafx.util.Duration.millis(380));
        username.textProperty().addListener((o, was, now) -> {
            nameBlocked[0] = false;
            String n = trim(now);
            if (n.isEmpty()) { showNameHint(nameHint, null, null); return; }
            if (!n.matches("[A-Za-z0-9_]{3,20}")) { showNameHint(nameHint, "bad", "3-20 letters, numbers or underscores."); return; }
            showNameHint(nameHint, "wait", "Checking\u2026");
            debounce.setOnFinished(ev -> api.checkUsername(n).whenComplete((c, ex) -> Platform.runLater(() -> {
                if (!n.equals(trim(username.getText()))) return; // typed on since
                if (c == null || c.available) { showNameHint(nameHint, "ok", "\u2713 " + n + " is available"); return; }
                nameBlocked[0] = true;
                showNameHint(nameHint, "bad", c.reason != null ? c.reason : "That name isn't available.");
            })));
            debounce.playFromStart();
        });
        createBtn.setOnAction(e -> {
            if (nameBlocked[0]) { setStatus("Pick a different username first."); return; }
            if (trim(username.getText()).isEmpty() || trim(email.getText()).isEmpty() || pw.getText() == null || pw.getText().isEmpty()) {
                setStatus("Fill in username, email and password.");
                return;
            }
            createBtn.setDisable(true);
            setStatus("Creating account\u2026");
            api.register(trim(username.getText()), trim(email.getText()), pw.getText(), deviceName())
                    .whenComplete((sess, err) -> Platform.runLater(() -> {
                        createBtn.setDisable(false);
                        if (err != null) { setStatus(msg(err)); return; }
                        showRegister = false;
                        avero.signedIn(sess);
                        clearStatus();
                        onChange.run();
                    }));
        });
        pw.setOnAction(e -> createBtn.fire());

        Button backBtn = new Button("\u2190 Back to Sign In");
        backBtn.getStyleClass().add("button");
        backBtn.setStyle("-fx-padding: 6 14 6 14; -fx-font-size: 12px;");
        backBtn.setOnAction(e -> { showRegister = false; clearStatus(); render(); });

        VBox form = new VBox(10, username, nameHint, email, pw, createBtn, backBtn);
        form.setPadding(new Insets(16, 0, 4, 0));

        getChildren().addAll(title, blurb, status, form);
    }

    private void renderForgot() {
        Label title = new Label("Reset Password");
        title.getStyleClass().add("section-header");
        title.setStyle("-fx-font-size: 16px;");

        Label blurb = new Label("Enter your account email. We'll send a link (and a code) to choose a new password.");
        blurb.getStyleClass().add("about-mirror-note");
        blurb.setWrapText(true);

        TextField emailField = styledField("Email address", 280);
        emailField.setText(pendingEmail);
        Button sendBtn = new Button("Send Reset Email");
        sendBtn.getStyleClass().add("primary-cta-button");
        sendBtn.setPrefWidth(280);
        sendBtn.setPrefHeight(38);
        sendBtn.setOnAction(e -> {
            if (trim(emailField.getText()).isEmpty()) { setStatus("Enter your account email."); return; }
            sendBtn.setDisable(true);
            setStatus("Sending\u2026");
            api.forgotPassword(trim(emailField.getText())).whenComplete((sent, err) -> Platform.runLater(() -> {
                sendBtn.setDisable(false);
                if (err != null) { setStatus(msg(err)); return; }
                pendingEmail = trim(emailField.getText());
                setStatus(Boolean.TRUE.equals(sent)
                        ? "Reset email sent \u2014 check your inbox for the link or the code."
                        : "No email was sent. That address may not be registered, or the server has no email service configured \u2014 paste the code if you already have one.");
            }));
        });

        Button haveCode = linkButton("I already have a code");
        haveCode.setOnAction(e -> { showReset = true; clearStatus(); render(); });

        Button backBtn = linkButton("\u2190 Back to Sign In");
        backBtn.setOnAction(e -> { showForgot = false; clearStatus(); render(); });

        VBox form = new VBox(10, emailField, sendBtn, haveCode, backBtn);
        form.setPadding(new Insets(16, 0, 4, 0));

        getChildren().addAll(title, blurb, status, form);
    }

    /** Second step of a password reset: paste the code from the email and choose a new password. */
    private void renderReset() {
        Label title = new Label("Choose a New Password");
        title.getStyleClass().add("section-header");
        title.setStyle("-fx-font-size: 16px;");

        Label blurb = new Label("Paste the code from the reset email (or just use the link in it), then pick a new password.");
        blurb.getStyleClass().add("about-mirror-note");
        blurb.setWrapText(true);

        TextField code = styledField("Code from the email", 280);
        PasswordField pw1 = styledPassword("New password (min 8 characters)", 280);
        PasswordField pw2 = styledPassword("Repeat new password", 280);

        Button go = new Button("Change Password");
        go.getStyleClass().add("primary-cta-button");
        go.setPrefWidth(280);
        go.setPrefHeight(38);
        go.setOnAction(e -> {
            if (trim(code.getText()).isEmpty()) { setStatus("Paste the code from the email."); return; }
            if (pw1.getText() == null || pw1.getText().length() < 8) { setStatus("Password must be at least 8 characters."); return; }
            if (!pw1.getText().equals(pw2.getText())) { setStatus("The passwords do not match."); return; }
            go.setDisable(true);
            setStatus("Changing password\u2026");
            api.resetPassword(trim(code.getText()), pw1.getText()).whenComplete((v, err) -> Platform.runLater(() -> {
                go.setDisable(false);
                if (err != null) { setStatus(msg(err)); return; }
                showReset = false; showForgot = false;
                render();
                setStatus("Password changed. Sign in with your new password.");
            }));
        });
        pw2.setOnAction(e -> go.fire());

        Button backBtn = linkButton("\u2190 Back");
        backBtn.setOnAction(e -> { showReset = false; clearStatus(); render(); });

        VBox form = new VBox(10, code, pw1, pw2, go, backBtn);
        form.setPadding(new Insets(16, 0, 4, 0));
        getChildren().addAll(title, blurb, status, form);
    }

    /** Signed-out email verification (shown when the server requires a verified email before sign-in). */
    private void renderVerify() {
        Label title = new Label("Verify Your Email");
        title.getStyleClass().add("section-header");
        title.setStyle("-fx-font-size: 16px;");

        Label blurb = new Label("Open the link in the verification email, or paste the code from it here.");
        blurb.getStyleClass().add("about-mirror-note");
        blurb.setWrapText(true);

        TextField email = styledField("Email address", 280);
        email.setText(pendingEmail);
        Button resend = new Button("Resend Email");
        resend.getStyleClass().add("secondary-button");
        resend.setOnAction(e -> {
            if (trim(email.getText()).isEmpty()) { setStatus("Enter your account email."); return; }
            resend.setDisable(true);
            api.resendVerification(trim(email.getText())).whenComplete((sent, err) -> Platform.runLater(() -> {
                resend.setDisable(false);
                if (err != null) { setStatus(msg(err)); return; }
                setStatus(Boolean.TRUE.equals(sent)
                        ? "If that address needs verifying, a new email is on its way."
                        : "No email went out \u2014 the address may already be verified, or the server has no email service configured. The code still works if you have one.");
            }));
        });

        TextField code = styledField("Code from the email", 280);
        Button verify = new Button("Verify");
        verify.getStyleClass().add("primary-cta-button");
        verify.setPrefWidth(280);
        verify.setPrefHeight(38);
        verify.setOnAction(e -> {
            if (trim(code.getText()).isEmpty()) { setStatus("Paste the code from the email."); return; }
            verify.setDisable(true);
            api.verifyEmail(trim(code.getText())).whenComplete((v, err) -> Platform.runLater(() -> {
                verify.setDisable(false);
                if (err != null) { setStatus(msg(err)); return; }
                showVerify = false; render();
                setStatus("Email verified. You can sign in now.");
            }));
        });

        Button backBtn = linkButton("\u2190 Back to Sign In");
        backBtn.setOnAction(e -> { showVerify = false; clearStatus(); render(); });

        VBox form = new VBox(10, email, resend, code, verify, backBtn);
        form.setPadding(new Insets(16, 0, 4, 0));
        getChildren().addAll(title, blurb, status, form);
    }

    // ═══════════════════════════════════════════════ SIGNED IN ═════════════

    private void renderSignedIn() {
        AveroSession sess = avero.session();

        // ── Profile header ──
        VBox header = buildProfileHeader(sess);
        getChildren().add(header);

        // ── Email verification banner (only appears while the address is unverified) ──
        getChildren().add(emailBanner());

        // ── Status message ──
        getChildren().add(status);

        // ── Action buttons row ──
        Button editBtn     = new Button("Edit Profile");
        editBtn.getStyleClass().add("secondary-button");
        editBtn.setOnAction(e -> editProfileDialog());

        Button pwBtn       = new Button("Change Password");
        pwBtn.getStyleClass().add("button");
        pwBtn.setOnAction(e -> changePasswordDialog());

        Button signOutBtn  = new Button("Sign Out");
        signOutBtn.getStyleClass().add("secondary-button");
        signOutBtn.setStyle("-fx-border-color: rgba(224,90,80,0.45); -fx-text-fill: #e05a50;");
        signOutBtn.setOnAction(e -> {
            setStatus("Signing out\u2026");
            avero.signOut();
            clearStatus();
            onChange.run();
        });

        Button deleteAcctBtn = new Button("Delete Account");
        deleteAcctBtn.getStyleClass().add("button");
        deleteAcctBtn.setStyle("-fx-padding: 6 14 6 14; -fx-font-size: 12px;");
        deleteAcctBtn.setOnAction(e -> deleteAccountDialog());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(8, editBtn, pwBtn, spacer, signOutBtn);
        actions.setAlignment(Pos.CENTER_LEFT);
        VBox.setMargin(actions, new Insets(14, 0, 4, 0));
        getChildren().add(actions);

        Button emailBtn = new Button("Change Email");
        emailBtn.getStyleClass().add("button");
        emailBtn.setStyle("-fx-padding: 6 14 6 14; -fx-font-size: 12px;");
        emailBtn.setOnAction(e -> changeEmailDialog());
        HBox minor = new HBox(16, emailBtn, deleteAcctBtn);
        minor.setAlignment(Pos.CENTER_LEFT);
        getChildren().add(minor);

        // ── Collapsible sections ──
        getChildren().add(collapsibleSection(
                "Cosmetics \u2014 Capes",
                showCosmetics,
                () -> { showCosmetics = !showCosmetics; render(); },
                cosmeticsSection()));

        getChildren().add(collapsibleSection(
                "Active Sessions",
                showSessions,
                () -> { showSessions = !showSessions; render(); },
                sessionsSection()));

        getChildren().add(collapsibleSection(
                "Linked Player Names",
                showProfiles,
                () -> { showProfiles = !showProfiles; render(); },
                profilesSection()));
    }

    private static final double PROFILE_BANNER_H = 92;

    /**
     * Profile card header: a bold orange-to-red cover banner (bled to the card's outer edge, with
     * the Valorant-style slash accents) and the avatar overlapping its lower edge, the way a social
     * profile card reads &mdash; cover photo on top, identity row below.
     */
    private VBox buildProfileHeader(AveroSession sess) {
        // Banner — bleeds past the card's own 24px padding so it reaches the card's real edge.
        Region bannerFill = new Region();
        bannerFill.getStyleClass().add("avero-profile-banner");
        StackPane banner = new StackPane(bannerFill, ValDeco.slashes(0.75));
        banner.getStyleClass().add("avero-profile-banner-wrap");
        banner.setMinHeight(PROFILE_BANNER_H);
        banner.setPrefHeight(PROFILE_BANNER_H);
        banner.setMaxHeight(PROFILE_BANNER_H);
        VBox.setMargin(banner, new Insets(-24, -24, 0, -24));
        Rectangle bannerClip = new Rectangle();
        bannerClip.widthProperty().bind(banner.widthProperty());
        bannerClip.heightProperty().bind(banner.heightProperty());
        banner.setClip(bannerClip);

        // Avatar circle, overlapping the banner's bottom edge
        StackPane avatar = new StackPane();
        avatar.getStyleClass().addAll("avero-avatar", "avero-avatar-overlap");
        String initial = (sess.username != null && !sess.username.isBlank())
                ? sess.username.substring(0, 1).toUpperCase() : "A";
        Label initLabel = new Label(initial);
        initLabel.getStyleClass().add("avero-avatar-initial");
        avatar.getChildren().add(initLabel);

        // Names
        Label uname = new Label(sess.username != null ? sess.username : "Avero User");
        uname.getStyleClass().add("avero-username");
        boolean sameAsName = sess.email == null || sess.email.isBlank()
                || sess.email.equalsIgnoreCase(sess.username);
        Label emailLbl = new Label(sameAsName ? "" : sess.email);
        emailLbl.getStyleClass().add("avero-email");
        emailLbl.setVisible(!sameAsName);
        emailLbl.setManaged(!sameAsName);

        Label badge = new Label("AVERO ID");
        badge.getStyleClass().add("avero-badge");

        VBox textCol = new VBox(3, uname, emailLbl, badge);
        textCol.setAlignment(Pos.CENTER_LEFT);

        HBox header = new HBox(14, avatar, textCol);
        header.getStyleClass().add("avero-profile-header");
        header.setAlignment(Pos.CENTER_LEFT);
        // Pulls the whole identity row up so the avatar overlaps the banner, like a cover photo.
        VBox.setMargin(header, new Insets(-30, 0, 0, 0));

        VBox wrapper = new VBox(banner, header);
        return wrapper;
    }

    // ─── Collapsible section helper ──────────────────────────────────────

    private VBox collapsibleSection(String title, boolean expanded, Runnable toggle, Node content) {
        Button toggleBtn = new Button((expanded ? "\u25BE  " : "\u25B8  ") + title);
        toggleBtn.getStyleClass().add("button");
        toggleBtn.setStyle("-fx-padding: 6 14 6 14; -fx-font-size: 12px;");
        toggleBtn.setMaxWidth(Double.MAX_VALUE);
        toggleBtn.setAlignment(Pos.CENTER_LEFT);
        toggleBtn.setOnAction(e -> toggle.run());

        VBox box = new VBox(0, toggleBtn);
        if (expanded && content != null) {
            VBox.setMargin(content, new Insets(0, 0, 6, 0));
            box.getChildren().add(content);
        }
        return box;
    }

    // ─── Cosmetics ───────────────────────────────────────────────────────

    private VBox cosmeticsSection() {
        VBox box = new VBox(6);
        box.getStyleClass().add("avero-section");

        Label loading = note("Loading capes\u2026");
        box.getChildren().add(loading);

        api.cosmetics().whenComplete((cos, err) -> Platform.runLater(() -> {
            box.getChildren().remove(loading);
            if (err != null) {
                box.getChildren().add(errNote("Couldn't load cosmetics: " + msg(err)));
                return;
            }
            fillCosmetics(box, cos);
        }));
        return box;
    }

    private void fillCosmetics(VBox box, Cosmetics cos) {
        Label header = new Label("EQUIPPED CAPE");
        header.getStyleClass().add("avero-section-label");
        box.getChildren().add(header);

        if (cos.equippedCape != null) {
            box.getChildren().add(buildCapeRow(cos.equippedCape, true));
        } else {
            box.getChildren().add(note("No cape equipped."));
        }

        if (!cos.ownedCapes.isEmpty()) {
            Label ownedLabel = new Label("YOUR CAPES");
            ownedLabel.getStyleClass().add("avero-section-label");
            VBox.setMargin(ownedLabel, new Insets(10, 0, 0, 0));
            box.getChildren().add(ownedLabel);

            for (StoreApi.Cape c : cos.ownedCapes) {
                boolean equipped = cos.equippedCape != null && cos.equippedCape.id.equals(c.id);
                box.getChildren().add(buildCapeRow(c, equipped));
            }
        } else {
            box.getChildren().add(note("No capes yet — visit the Store page to get one."));
        }
    }

    private HBox buildCapeRow(StoreApi.Cape cape, boolean equipped) {
        // Colour swatch
        StackPane swatch = new StackPane();
        swatch.getStyleClass().add("cape-preview-swatch");
        int h = Math.abs((cape.id != null ? cape.id : "x").hashCode()) % 4;
        String[] colours = {"#ff4655","#3d8ab8","#6a48b0","#2e7d52"};
        swatch.setStyle("-fx-background-color: linear-gradient(to bottom, " + colours[h] + ", #0f1923); -fx-background-radius: 0;"
                + " -fx-min-width: 28; -fx-min-height: 28; -fx-max-width: 28; -fx-max-height: 28;");

        Label name = new Label(cape.name != null && !cape.name.isBlank() ? cape.name : StoreApi.prettyName(cape.id));
        name.getStyleClass().add("footer-title");
        HBox.setHgrow(name, Priority.ALWAYS);

        HBox row = new HBox(10, swatch, name);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().addAll("cape-row", equipped ? "cape-row-equipped" : "");

        if (!equipped) {
            Button equipBtn = new Button("Equip");
            equipBtn.getStyleClass().add("primary-cta-button");
            equipBtn.setStyle("-fx-padding: 5 12 5 12; -fx-font-size: 11px;");
            equipBtn.setOnAction(e -> api.equip(cape.id).whenComplete((w, err) -> Platform.runLater(() -> {
                if (err != null) setStatus(msg(err));
                else { clearStatus(); render(); onChange.run(); }
            })));
            row.getChildren().add(equipBtn);
        } else {
            Label equippedBadge = new Label("EQUIPPED");
            equippedBadge.getStyleClass().add("cape-equipped-badge");
            Button unequipBtn = new Button("Remove");
            unequipBtn.getStyleClass().add("secondary-button");
            unequipBtn.setStyle("-fx-padding: 4 10 4 10; -fx-font-size: 11px;");
            unequipBtn.setOnAction(e -> api.equip(null).whenComplete((w, err) -> Platform.runLater(() -> {
                if (err != null) setStatus(msg(err));
                else { clearStatus(); render(); onChange.run(); }
            })));
            row.getChildren().addAll(equippedBadge, unequipBtn);
        }
        return row;
    }

    // ─── Sessions ────────────────────────────────────────────────────────

    private VBox sessionsSection() {
        VBox box = new VBox(6);
        box.getStyleClass().add("avero-section");

        Label loading = note("Loading sessions\u2026");
        box.getChildren().add(loading);

        api.sessions().whenComplete((list, err) -> Platform.runLater(() -> {
            box.getChildren().remove(loading);
            if (err != null) { box.getChildren().add(errNote("Couldn't load sessions: " + msg(err))); return; }
            fillSessions(box, list);
        }));
        return box;
    }

    private void fillSessions(VBox box, List<SessionInfo> list) {
        if (list == null || list.isEmpty()) {
            box.getChildren().add(note("No active sessions."));
            return;
        }
        for (SessionInfo si : list) {
            Label device = new Label(si.deviceName + (si.current ? "  \u2022  This device" : ""));
            device.getStyleClass().add("footer-title");
            if (si.current) device.setStyle("-fx-text-fill: #5da8d0;");

            Label used = new Label("Last used " + shortDate(si.lastUsedAt));
            used.getStyleClass().add("profile-tag");

            VBox names = new VBox(2, device, used);
            HBox.setHgrow(names, Priority.ALWAYS);

            HBox row = new HBox(10, names);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("avero-section");
            row.setStyle("-fx-padding: 8 10 8 10; -fx-margin: 0 0 4 0;");

            if (!si.current) {
                Button revokeBtn = new Button("Revoke");
                revokeBtn.getStyleClass().add("secondary-button");
                revokeBtn.setStyle("-fx-padding: 4 10 4 10; -fx-font-size: 11px; "
                        + "-fx-border-color: rgba(224,90,80,0.40); -fx-text-fill: #e05a50;");
                revokeBtn.setOnAction(e -> api.revokeSession(si.id).whenComplete((l, err) -> Platform.runLater(() -> {
                    if (err != null) setStatus(msg(err));
                    else { setStatus("Session revoked."); render(); }
                })));
                row.getChildren().add(revokeBtn);
            }
            box.getChildren().add(row);
        }

        Button logoutAll = new Button("Sign out all other devices");
        logoutAll.getStyleClass().add("secondary-button");
        logoutAll.setStyle("-fx-border-color: rgba(224,90,80,0.40); -fx-text-fill: #e05a50; -fx-margin-top: 6px;");
        VBox.setMargin(logoutAll, new Insets(6, 0, 0, 0));
        logoutAll.setOnAction(e -> api.logoutAllOtherDevices().whenComplete((l, err) -> Platform.runLater(() -> {
            if (err != null) setStatus(msg(err));
            else { setStatus("All other sessions signed out."); render(); }
        })));
        box.getChildren().add(logoutAll);
    }

    // ─── Linked Minecraft Profiles ───────────────────────────────────────

    private VBox profilesSection() {
        VBox box = new VBox(6);
        box.getStyleClass().add("avero-section");

        Label blurb = note("Your cape follows every player name you link here. Real Minecraft names link by themselves "
                + "when you sign in with Microsoft (\u201cVerified\u201d). Names that exist at Mojang can't be claimed by hand.");
        blurb.setWrapText(true);
        box.getChildren().add(blurb);

        api.me().whenComplete((user, err) -> Platform.runLater(() -> {
            if (err != null) { box.getChildren().add(errNote("Couldn't load linked names: " + msg(err))); return; }
            fillProfiles(box, user);
        }));
        return box;
    }

    private void fillProfiles(VBox box, AveroUser user) {
        if (user.profiles == null || user.profiles.isEmpty()) {
            box.getChildren().add(note("No player names linked yet."));
        } else {
            for (LinkedProfile p : user.profiles) {
                Label name = new Label(p.username);
                name.getStyleClass().add("footer-title");
                Label badge = new Label(p.verified ? "\u2713 Verified" : "Unverified");
                badge.getStyleClass().addAll("status-chip", p.verified ? "status-ok" : "status-warn");
                badge.setStyle("-fx-font-size: 9.5px; -fx-font-weight: bold;");
                Region grow = spacer();

                Button remove = new Button("Remove");
                remove.getStyleClass().add("secondary-button");
                remove.setStyle("-fx-padding: 4 10 4 10; -fx-font-size: 11px; "
                        + "-fx-border-color: rgba(224,90,80,0.40); -fx-text-fill: #e05a50;");
                remove.setOnAction(e -> {
                    remove.setDisable(true);
                    api.unlink(p.uuid).whenComplete((list, ex) -> Platform.runLater(() -> {
                        if (ex != null) { remove.setDisable(false); setStatus(msg(ex)); return; }
                        setStatus(p.username + " unlinked.");
                        avero.syncProfiles();
                        render(); onChange.run();
                    }));
                });

                HBox row = new HBox(10, name, badge, grow, remove);
                row.setAlignment(Pos.CENTER_LEFT);
                row.getStyleClass().add("avero-section");
                row.setStyle("-fx-padding: 7 10 7 10;");
                box.getChildren().add(row);
            }
        }

        Label linkHead = new Label("LINK A CUSTOM NAME");
        linkHead.getStyleClass().add("avero-section-label");
        VBox.setMargin(linkHead, new Insets(10, 0, 4, 0));
        TextField nameField = styledField("Custom / offline name (3-16 letters, numbers, _)", 280);
        Button linkBtn = new Button("Link Name");
        linkBtn.getStyleClass().add("secondary-button");
        linkBtn.setMaxWidth(Double.MAX_VALUE);
        linkBtn.setOnAction(e -> {
            String n = trim(nameField.getText());
            if (!n.matches("\\w{3,16}")) { setStatus("Names are 3-16 letters, numbers or underscores."); return; }
            linkBtn.setDisable(true);
            setStatus("Linking \u201c" + n + "\u201d\u2026");
            api.linkOffline(n).whenComplete((list, ex) -> Platform.runLater(() -> {
                linkBtn.setDisable(false);
                if (ex != null) { setStatus(msg(ex)); return; }
                setStatus(n + " linked.");
                avero.syncProfiles();
                render(); onChange.run();
            }));
        });
        nameField.setOnAction(e -> linkBtn.fire());
        box.getChildren().addAll(linkHead, nameField, linkBtn);
    }

    // ─── Email verification banner ───────────────────────────────────────

    private VBox emailBanner() {
        VBox box = new VBox(6);
        AveroSession sess = avero.session();
        if (sess != null && sess.emailVerified) return box; // already verified this sign-in: no round trip needed
        api.me().whenComplete((u, err) -> Platform.runLater(() -> {
            if (err != null || u == null || u.emailVerified) return;
            Label l = new Label("Verify your email" + (u.email == null || u.email.isBlank() ? "" : " (" + u.email + ")")
                    + " so you can recover your account if you forget your password.");
            l.getStyleClass().add("about-mirror-note");
            l.setWrapText(true);

            Button resend = new Button("Resend email");
            resend.getStyleClass().add("secondary-button");
            resend.setOnAction(e -> {
                resend.setDisable(true);
                api.resendVerification(u.email).whenComplete((sent, ex) -> Platform.runLater(() -> {
                    resend.setDisable(false);
                    if (ex != null) { setStatus(msg(ex)); return; }
                    setStatus(Boolean.TRUE.equals(sent)
                            ? "Verification email sent \u2014 check your inbox."
                            : "No email went out \u2014 the server has no email service configured, or this address is already verified.");
                }));
            });
            Button enter = new Button("I have a code");
            enter.getStyleClass().add("secondary-button");
            enter.setOnAction(e -> verifyCodeDialog());

            box.getChildren().addAll(l, new HBox(8, resend, enter));
            box.setStyle("-fx-padding: 10 12 10 12; -fx-background-color: rgba(224,170,60,0.10); -fx-background-radius: 0;");
            VBox.setMargin(box, new Insets(10, 0, 0, 0));
        }));
        return box;
    }

    private void verifyCodeDialog() {
        javafx.scene.control.TextInputDialog d = new javafx.scene.control.TextInputDialog();
        d.setTitle("Verify Email");
        d.setHeaderText("Paste the code from the verification email");
        d.setContentText("Code:");
        d.showAndWait().ifPresent(code -> {
            if (code.isBlank()) return;
            setStatus("Verifying\u2026");
            api.verifyEmail(code.trim()).whenComplete((v, err) -> Platform.runLater(() -> {
                if (err != null) { setStatus(msg(err)); return; }
                AveroSession s = avero.session();
                if (s != null) s.emailVerified = true;
                setStatus("Email verified.");
                render();
            }));
        });
    }

    private void changeEmailDialog() {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Change Email");
        d.setHeaderText("Use a different email address");
        TextField email = new TextField(); email.setPromptText("New email address");
        PasswordField pw = new PasswordField(); pw.setPromptText("Your password");
        GridPane g = new GridPane();
        g.setHgap(12); g.setVgap(10);
        g.addRow(0, new Label("New email"), email);
        g.addRow(1, new Label("Password"), pw);
        d.getDialogPane().setContent(g);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) return;
            if (trim(email.getText()).isEmpty() || pw.getText().isEmpty()) { setStatus("Enter the new email and your password."); return; }
            setStatus("Changing email\u2026");
            api.changeEmail(pw.getText(), trim(email.getText())).whenComplete((u, err) -> Platform.runLater(() -> {
                if (err != null) { setStatus(msg(err)); return; }
                AveroSession s = avero.session();
                if (s != null) { s.email = u.email; s.emailVerified = false; }
                setStatus(u.mailSent
                        ? "Email changed \u2014 check your new inbox to verify it."
                        : "Email changed, but no verification email went out (the server has no email service configured).");
                render(); onChange.run();
            }));
        });
    }

    // ─── Dialogs ─────────────────────────────────────────────────────────

    private void editProfileDialog() {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Edit Profile");
        d.setHeaderText("Update your Avero profile");
        TextField display = new TextField();
        display.setPromptText("Display name");
        TextField mc = new TextField();
        mc.setPromptText("Minecraft username (optional)");

        GridPane g = new GridPane();
        g.setHgap(12); g.setVgap(10);
        g.addRow(0, new Label("Display name"), display);
        g.addRow(1, new Label("Minecraft username"), mc);
        d.getDialogPane().setContent(g);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        api.me().whenComplete((u, err) -> Platform.runLater(() -> {
            if (u != null) {
                if (u.displayName != null) display.setText(u.displayName);
                if (u.minecraftUsername != null) mc.setText(u.minecraftUsername);
            }
        }));

        d.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) return;
            String dn  = display.getText() == null || display.getText().isBlank() ? null : display.getText().trim();
            String mcu = mc.getText() == null || mc.getText().isBlank() ? null : mc.getText().trim();
            setStatus("Saving profile\u2026");
            api.patchMe(dn, mcu).whenComplete((u, err) -> Platform.runLater(() -> {
                setStatus(err != null ? msg(err) : "Profile updated.");
                render(); onChange.run();
            }));
        });
    }

    private void changePasswordDialog() {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Change Password");
        d.setHeaderText("Set a new password");
        PasswordField cur = new PasswordField(); cur.setPromptText("Current password");
        PasswordField nw  = new PasswordField(); nw.setPromptText("New password (min 8 characters)");

        GridPane g = new GridPane();
        g.setHgap(12); g.setVgap(10);
        g.addRow(0, new Label("Current password"), cur);
        g.addRow(1, new Label("New password"), nw);
        d.getDialogPane().setContent(g);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        d.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) return;
            if (cur.getText().isBlank() || nw.getText().isBlank()) {
                setStatus("Both password fields are required.");
                return;
            }
            setStatus("Changing password\u2026");
            api.changePassword(cur.getText(), nw.getText()).whenComplete((v, err) -> Platform.runLater(() ->
                    setStatus(err != null ? msg(err) : "Password changed. Other devices were signed out.")));
        });
    }

    private void deleteAccountDialog() {
        javafx.scene.control.Alert confirm = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.WARNING);
        confirm.setTitle("Delete Account");
        confirm.setHeaderText("Permanently delete your Avero account?");
        confirm.setContentText(
                "This is irreversible. Your capes, sessions and linked profiles will be deleted.\n\n"
                + "Enter your password to confirm.");
        PasswordField pw = new PasswordField();
        pw.setPromptText("Your password");
        confirm.getDialogPane().setExpandableContent(pw);
        confirm.getDialogPane().setExpanded(true);
        confirm.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);

        confirm.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) return;
            if (pw.getText() == null || pw.getText().isBlank()) {
                setStatus("Password is required to delete your account.");
                return;
            }
            setStatus("Deleting account\u2026");
            api.deleteAccount(pw.getText()).whenComplete((v, err) -> Platform.runLater(() -> {
                if (err != null) { setStatus(msg(err)); return; }
                avero.signOut();
                clearStatus();
                onChange.run();
            }));
        });
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private static TextField styledField(String prompt, double width) {
        TextField f = new TextField();
        f.setPromptText(prompt);
        f.setPrefWidth(width);
        f.setMaxWidth(Double.MAX_VALUE);
        return f;
    }

    private static PasswordField styledPassword(String prompt, double width) {
        PasswordField f = new PasswordField();
        f.setPromptText(prompt);
        f.setPrefWidth(width);
        f.setMaxWidth(Double.MAX_VALUE);
        return f;
    }

    private static Region spacer() {
        Region g = new Region();
        HBox.setHgrow(g, Priority.ALWAYS);
        return g;
    }

    /** Small inline validation line under a field. kind: ok | bad | wait | null (hides it). */
    private static void showNameHint(Label l, String kind, String text) {
        boolean show = kind != null && text != null;
        l.setVisible(show); l.setManaged(show);
        if (!show) return;
        l.setText(text);
        l.getStyleClass().removeAll("field-hint-ok", "field-hint-bad", "field-hint-wait");
        l.getStyleClass().addAll("field-hint", "field-hint-" + kind);
    }

    private static Label note(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("about-mirror-note");
        l.setWrapText(true);
        return l;
    }

    private static Label errNote(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #e05a50; -fx-font-size: 11.5px;");
        l.setWrapText(true);
        return l;
    }

    private static String shortDate(String iso) {
        if (iso == null || iso.length() < 10) return "\u2014";
        return iso.substring(0, 10);
    }

    private String deviceName() {
        String os = System.getProperty("os.name", "PC");
        String host = "this device";
        try { host = java.net.InetAddress.getLocalHost().getHostName(); } catch (Exception ignored) { }
        return host + " (" + os + ")";
    }

    private static String trim(String s) { return s == null ? "" : s.trim(); }

    /** The server's error code (e.g. EMAIL_NOT_VERIFIED) hidden inside a CompletionException chain. */
    private static String codeOf(Throwable t) {
        for (Throwable r = t; r != null; r = r.getCause()) {
            if (r instanceof StoreApi.StoreException se) return se.code;
            if (r.getCause() == r) break;
        }
        return "";
    }

    /** Small grey text-only button used for secondary navigation. */
    private static Button linkButton(String text) {
        Button b = new Button(text);
        b.getStyleClass().add("button");
        b.setStyle("-fx-padding: 6 14 6 14; -fx-font-size: 12px;");
        return b;
    }

    private void setStatus(String t) {
        status.setText(t == null ? "" : t);
        boolean show = t != null && !t.isEmpty();
        status.setVisible(show);
        status.setManaged(show);
    }

    private void clearStatus() { setStatus(""); }

    private static String msg(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        return r.getMessage() != null && !r.getMessage().isBlank() ? r.getMessage() : "Something went wrong. Please try again.";
    }
}
