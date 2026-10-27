package com.averolauncher.ui.pages;

import com.averolauncher.model.Account;
import com.averolauncher.model.LauncherSettings;
import com.averolauncher.repository.AccountRepository;
import com.averolauncher.repository.SettingsRepository;
import com.averolauncher.skins.SkinLibrary;
import com.averolauncher.skins.SkinService;
import com.averolauncher.store.AveroId;
import com.averolauncher.ui.Motion;
import com.averolauncher.ui.NavPage;
import com.averolauncher.ui.ValDeco;
import com.averolauncher.ui.kit.AvButton;
import com.averolauncher.ui.kit.SkinModel3D;
import com.averolauncher.ui.kit.Card;
import com.averolauncher.ui.kit.ErrorState;
import com.averolauncher.ui.kit.Skeleton;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.DoubleBinding;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.beans.InvalidationListener;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Home. A bold hero ("Ready when you are", the player's name, a big PLAY button with a version
 * selector, and the equipped cape), three quick cards (continue / servers / cape), a compact
 * "what's new" card and the community partners.
 *
 * <p>PLAY here only navigates to the Play page, exactly as the old hero button did; launching stays
 * in PlayPage/LaunchService. The version selector writes {@code defaultVersionId} the same way
 * PlayPage does. All styling is in {@code pages.css}; the only motion is the page transition, the
 * Play glow pulse and a very slow drifting light behind the hero, all through {@link Motion}.
 *
 * <p>Pages are cached, so the content is rebuilt each time Home is shown again. That way a changed
 * Minecraft folder, default version, account or cape is reflected without restarting.
 */
public class HomePage {

    private static final double HERO_H = 400;
    private static final String COPY_IP = "play.pinoy-craft.com";

    private final LauncherSettings settings;
    private final Consumer<NavPage> navigator;
    private final AccountRepository accountRepository;
    private final SettingsRepository settingsRepository;
    private final Runnable openLeaderboard;

    private final VBox root = new VBox();
    private final List<Consumer<CapeState>> capeViews = new ArrayList<>();
    private Label continueTitle;
    private CapeState lastCape;   // last known cape, shown instantly on a revisit
    private int capeTicket;
    private boolean stale;

    public HomePage(LauncherSettings settings, Consumer<NavPage> navigator,
                    AccountRepository accountRepository, SettingsRepository settingsRepository,
                    Runnable openLeaderboard) {
        this.settings = settings;
        this.navigator = navigator;
        this.accountRepository = accountRepository;
        this.settingsRepository = settingsRepository;
        this.openLeaderboard = openLeaderboard != null ? openLeaderboard : () -> { };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Page
    // ─────────────────────────────────────────────────────────────────────────

    public VBox build() {
        root.getStyleClass().addAll("av-page-body", "gap-24");
        rebuild();
        root.sceneProperty().addListener((o, was, now) -> {
            if (now == null) {
                stale = true;                 // left the screen: refresh next time it is shown
            } else if (stale) {
                stale = false;
                rebuild();
            }
        });
        return root;
    }

    private void rebuild() {
        capeViews.clear();
        root.getChildren().setAll(buildHero(), buildQuickRow(), buildWhatsNew(), buildCommunity());
        loadCape();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Hero
    // ─────────────────────────────────────────────────────────────────────────

    private StackPane buildHero() {
        StackPane hero = new StackPane();
        hero.getStyleClass().add("home-hero");
        hero.setMinHeight(HERO_H);
        hero.setPrefHeight(HERO_H);
        hero.setMaxHeight(HERO_H);

        Region base = new Region();
        base.getStyleClass().add("home-hero-base");
        hero.getChildren().add(base);

        Node photo = buildHeroPhoto(hero);
        if (photo != null) hero.getChildren().add(photo);

        Region light = new Region();           // ambient light: the only looping motion on Home
        light.getStyleClass().add("home-hero-light");
        light.setMouseTransparent(true);
        light.setPrefWidth(640);
        light.setMaxWidth(640);
        StackPane.setAlignment(light, Pos.CENTER_LEFT);
        Motion.drift(light, 140, Duration.seconds(18));

        Region scrim = new Region();
        scrim.getStyleClass().add("home-hero-scrim");
        scrim.setMouseTransparent(true);

        HBox content = new HBox(28);
        content.getStyleClass().add("home-hero-content");
        content.setAlignment(Pos.CENTER_LEFT);
        VBox left = buildHeroLeft();
        HBox.setHgrow(left, Priority.ALWAYS);
        content.getChildren().addAll(left, buildHeroSkin());

        Node leaderboardBtn = buildLeaderboardButton();
        StackPane.setAlignment(leaderboardBtn, Pos.TOP_RIGHT);
        StackPane.setMargin(leaderboardBtn, new Insets(20, 24, 0, 0));

        hero.getChildren().addAll(light, scrim, ValDeco.slashes(1.0), content, leaderboardBtn);

        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(hero.widthProperty());
        clip.heightProperty().bind(hero.heightProperty());
        hero.setClip(clip);
        return hero;
    }

    /** Pinned to the hero's top-right corner; slides the live leaderboard in as a semipage drawer. */
    private Node buildLeaderboardButton() {
        // Simple ascending-bars glyph (ranked standings), same 24-grid stroke style as LeaderboardPanel's icons.
        javafx.scene.shape.SVGPath icon = new javafx.scene.shape.SVGPath();
        icon.setContent("M4 20 V15 M10 20 V11 M16 20 V6 M2 20 H22");
        icon.getStyleClass().add("home-leaderboard-btn-icon");

        Label text = new Label("LEADERBOARD");
        HBox inner = new HBox(8, icon, text);
        inner.setAlignment(Pos.CENTER);

        javafx.scene.control.Button btn = new javafx.scene.control.Button();
        btn.setGraphic(inner);
        btn.getStyleClass().add("home-leaderboard-btn");
        btn.setCursor(javafx.scene.Cursor.HAND);
        btn.setOnAction(e -> openLeaderboard.run());
        btn.setAccessibleText("Open leaderboard");
        return btn;
    }

    private VBox buildHeroLeft() {
        boolean ready = isReady();
        Account active = resolveActiveAccount();
        String version = settings.defaultVersionId;

        Label eyebrow = new Label(ready ? "READY WHEN YOU ARE" : "LET'S GET YOU SET UP");
        eyebrow.getStyleClass().add("home-eyebrow");

        Label name = new Label(active != null ? active.username : "Welcome");
        name.getStyleClass().add("home-hero-name");
        name.setTextOverrun(OverrunStyle.ELLIPSIS);
        name.setMaxWidth(560);

        Label sub = new Label(subText(active, version));
        sub.getStyleClass().add("home-hero-sub");
        sub.setWrapText(true);
        sub.setMaxWidth(500);

        Node actions = buildHeroActions(sub, active);
        VBox.setMargin(actions, new Insets(12, 0, 0, 0));

        // Details: only facts we actually have (version, account kind, memory).
        FlowPane chips = new FlowPane();
        chips.getStyleClass().add("gap-8");
        if (versionSet()) chips.getChildren().add(heroChip("MINECRAFT " + version));
        chips.getChildren().add(heroChip(active == null ? "OFFLINE"
                : active.type == Account.Type.MICROSOFT ? "MICROSOFT"
                : active.averoLinked ? "AVERO ID" : "OFFLINE"));
        chips.getChildren().add(heroChip(settings.maxRamMb % 1024 == 0
                ? (settings.maxRamMb / 1024) + " GB RAM" : settings.maxRamMb + " MB RAM"));
        VBox.setMargin(chips, new Insets(6, 0, 0, 0));

        VBox left = new VBox(6, eyebrow, name, sub, chips, actions);
        left.setAlignment(Pos.CENTER_LEFT);
        return left;
    }

    private static Label heroChip(String text) {
        Label l = new Label(text);
        l.getStyleClass().addAll("av-chip", "home-hero-chip");
        return l;
    }

    private String subText(Account active, String version) {
        if (!mcDirExists()) return "Choose your Minecraft folder in Settings to get started.";
        if (!versionSet()) return "Pick a version and you'll be ready to launch.";
        String base = "Jump straight into Minecraft " + version + ".";
        return active == null ? base + " No account selected, so you'll play offline. Add one in Accounts." : base;
    }

    private Node buildHeroActions(Label sub, Account active) {
        HBox row = new HBox(20);
        row.setAlignment(Pos.CENTER_LEFT);

        if (!mcDirExists()) {
            AvButton open = new AvButton("Open settings", AvButton.Kind.PRIMARY);
            open.getStyleClass().add("home-cta");
            open.setOnAction(e -> navigator.accept(NavPage.SETTINGS));
            row.getChildren().add(open);
            return row;
        }

        if (isReady()) {
            AvButton play = new AvButton("PLAY", AvButton.Kind.PLAY);
            play.getStyleClass().add("home-play");
            play.setOnAction(e -> navigator.accept(NavPage.PLAY));

            // Glow sits behind the button and is the only thing that pulses (opacity only: no effects).
            Region glow = new Region();
            glow.getStyleClass().add("home-play-glow");
            glow.setMouseTransparent(true);
            StackPane.setMargin(glow, new Insets(-40, -56, -40, -56));
            Motion.pulse(glow, 0.35, 0.9, Duration.millis(1400));
            row.getChildren().add(new StackPane(glow, play));
        } else {
            AvButton choose = new AvButton("Choose version", AvButton.Kind.PRIMARY);
            choose.getStyleClass().add("home-cta");
            choose.setOnAction(e -> navigator.accept(NavPage.PLAY));
            row.getChildren().add(choose);
        }

        Node selector = buildVersionSelector(sub, active);
        if (selector != null) row.getChildren().add(selector);
        return row;
    }

    /** Installed versions, newest first. Changing it saves the default version just as PlayPage does. */
    private Node buildVersionSelector(Label sub, Account active) {
        List<String> versions = installedVersions();
        String current = settings.defaultVersionId;
        if (versionSet() && !versions.contains(current)) versions.add(0, current);
        if (versions.isEmpty()) return null;

        ComboBox<String> combo = new ComboBox<>(FXCollections.observableArrayList(versions));
        combo.getStyleClass().add("home-version-select");
        combo.setAccessibleText("Minecraft version");
        if (versionSet()) combo.setValue(current);
        else combo.setPromptText("Select version");

        boolean wasReady = isReady();
        combo.setOnAction(e -> {
            String v = combo.getValue();
            if (v == null || v.equals(settings.defaultVersionId)) return;
            settings.defaultVersionId = v;
            settingsRepository.save(settings);
            if (!wasReady) {
                Platform.runLater(this::rebuild); // hero changes from "choose version" to PLAY
                return;
            }
            sub.setText(subText(active, v));
            if (continueTitle != null) continueTitle.setText("Minecraft " + v);
        });

        Label label = new Label("VERSION");
        label.getStyleClass().add("home-field-label");
        VBox box = new VBox(6, label, combo);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    // ── player skin in 3D (hero, right) ──────────────────────────────────────

    private Node buildHeroSkin() {
        SkinModel3D model = new SkinModel3D(240, 310);
        Label tag = new Label("YOUR SKIN");
        tag.getStyleClass().add("av-section-title");
        Label name = new Label();
        name.getStyleClass().add("home-cape-name");
        name.setTextOverrun(OverrunStyle.ELLIPSIS);
        name.setMaxWidth(240);

        VBox nameBox = new VBox(2, tag, name);
        nameBox.setAlignment(Pos.CENTER);
        tag.setAlignment(Pos.CENTER);
        name.setAlignment(Pos.CENTER);
        VBox col = new VBox(4, model, nameBox);
        col.setAlignment(Pos.TOP_CENTER);
        col.setMinWidth(240);
        col.setMaxWidth(240);

        // Drag turns the model; a plain click (no drag) opens Accounts, where skins are managed.
        col.setOnMouseClicked(e -> { if (e.isStillSincePress()) navigator.accept(NavPage.ACCOUNTS); });
        col.setFocusTraversable(true);
        col.setAccessibleText("Your skin in 3D. Drag to rotate.");
        col.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) navigator.accept(NavPage.ACCOUNTS);
        });

        loadSkin(model, tag, name);

        return col;   // no card: the player stands straight on the hero background
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Quick cards
    // ─────────────────────────────────────────────────────────────────────────

    private HBox buildQuickRow() {
        HBox row = new HBox();
        row.getStyleClass().add("gap-16");
        for (Card c : new Card[] { continueCard(), serversCard(), capeCard(), rankCard() }) {
            HBox.setHgrow(c, Priority.ALWAYS);
            c.setPrefWidth(0);
            c.setMaxWidth(Double.MAX_VALUE);
            row.getChildren().add(c);
        }
        return row;
    }

    private Card continueCard() {
        String kicker, title, hint;
        NavPage target;
        if (!mcDirExists()) {
            kicker = "SET UP"; title = "Choose your Minecraft folder"; hint = "Needed before you can play."; target = NavPage.SETTINGS;
        } else if (!versionSet()) {
            kicker = "ALMOST THERE"; title = "Pick a version"; hint = "Choose what to play first."; target = NavPage.PLAY;
        } else {
            kicker = "CONTINUE"; title = "Minecraft " + settings.defaultVersionId; hint = "Jump back in where you left off."; target = NavPage.PLAY;
        }
        Label t = new Label(title);
        continueTitle = t;
        return quickCard(kicker, t, new Label(hint), () -> navigator.accept(target)).card;
    }

    private Card serversCard() {
        int n = settings.savedServers != null ? settings.savedServers.size() : 0;
        Label title = new Label(n == 0 ? "Find a server" : n + (n == 1 ? " saved server" : " saved servers"));
        Label hint = new Label(n == 0 ? "Browse the directory and save your favourites." : "Browse the directory or jump into a favourite.");
        return quickCard("SERVERS", title, hint, () -> navigator.accept(NavPage.SERVERS)).card;
    }

    /** Current global rank at a glance; opens the Ranks page. Read from a fresh snapshot each time Home is rebuilt. */
    private Card rankCard() {
        com.averolauncher.rank.RankSnapshot snap = com.averolauncher.rank.RankService.get().snapshot();
        Label title = new Label();
        Label hint = new Label();
        if (!snap.signedIn()) {
            title.setText("Earn a global rank");
            hint.setText("Sign in with Avero ID to earn Avero Points from playtime and quests.");
        } else {
            com.averolauncher.rank.RankStanding st = snap.standing();
            title.setText(st.label() + "  \u00B7  " + String.format(java.util.Locale.US, "%,d", snap.ap()) + " AP");
            hint.setText((st.isMax() ? "Highest rank reached." : String.format(java.util.Locale.US, "%,d", st.apToNext()) + " AP to " + st.nextLabel() + ".")
                    + " " + snap.dayPlaytimeAp() + "/" + snap.dailyCapAp() + " playtime AP today.");
        }
        return quickCard("RANK", title, hint, () -> navigator.accept(NavPage.RANKS)).card;
    }

    private Card capeCard() {
        Label title = new Label();
        Label hint = new Label();
        final Runnable[] go = { () -> navigator.accept(NavPage.STORE) };
        QuickCard q = quickCard("CAPE", title, hint, () -> go[0].run());

        Skeleton s1 = Skeleton.line(150);
        Skeleton s2 = Skeleton.line(210);
        s1.setVisible(false);
        s2.setVisible(false);
        s1.managedProperty().bind(s1.visibleProperty());
        s2.managedProperty().bind(s2.visibleProperty());
        q.text.getChildren().addAll(s1, s2);

        capeViews.add(st -> {
            boolean loading = st.kind() == CapeKind.LOADING;
            title.setVisible(!loading); title.setManaged(!loading);
            hint.setVisible(!loading);  hint.setManaged(!loading);
            s1.setVisible(loading);
            s2.setVisible(loading);
            switch (st.kind()) {
                case EQUIPPED -> { title.setText(st.name()); hint.setText("Equipped. Open the Store to change it."); go[0] = () -> navigator.accept(NavPage.STORE); }
                case NONE -> { title.setText("No cape yet"); hint.setText("Browse capes in the Store."); go[0] = () -> navigator.accept(NavPage.STORE); }
                case SIGNED_OUT -> { title.setText("Get a cape"); hint.setText("Sign in with Avero ID to wear one."); go[0] = () -> navigator.accept(NavPage.ACCOUNTS); }
                case ERROR -> { title.setText(st.name()); hint.setText("Click to try again."); go[0] = this::loadCape; }
                default -> { }
            }
        });
        return q.card;
    }

    /** A quick card plus the text column, so callers can add to it (skeleton lines). */
    private record QuickCard(Card card, VBox text) { }

    private QuickCard quickCard(String kicker, Label title, Label hint, Runnable go) {
        Label k = new Label(kicker);
        k.getStyleClass().add("home-quick-kicker");
        title.getStyleClass().add("home-quick-title");
        title.setTextOverrun(OverrunStyle.ELLIPSIS);
        title.setMaxWidth(Double.MAX_VALUE);
        hint.getStyleClass().add("home-quick-hint");
        hint.setWrapText(true);
        hint.setMaxWidth(Double.MAX_VALUE);

        VBox text = new VBox(4, k, title, hint);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);
        Label arrow = new Label("\u2192");
        arrow.getStyleClass().add("home-quick-arrow");

        HBox inner = new HBox(12, text, arrow);
        inner.getStyleClass().add("pad-16");
        inner.setAlignment(Pos.CENTER_LEFT);

        Card card = new Card(inner).interactive();
        card.getStyleClass().add("home-quick");
        card.setCursor(javafx.scene.Cursor.HAND);
        card.setFocusTraversable(true);
        card.setAccessibleText(kicker);
        card.setOnMouseClicked(e -> go.run());
        card.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) go.run();
        });
        return new QuickCard(card, text);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // What's new
    // ─────────────────────────────────────────────────────────────────────────

    private Node buildWhatsNew() {
        VBox body = new VBox();
        ToggleGroup group = new ToggleGroup();
        ToggleButton news = tab("NEWS", group);
        ToggleButton changes = tab("CHANGES", group);
        group.selectedToggleProperty().addListener((o, was, now) -> {
            if (now == null) { if (was != null) was.setSelected(true); return; }
            body.getChildren().setAll(now == news ? newsRows() : changeRows());
        });
        HBox tabs = new HBox(news, changes);
        tabs.getStyleClass().add("av-tabs");
        tabs.setAlignment(javafx.geometry.Pos.CENTER);
        news.setSelected(true);

        VBox inner = new VBox(8, tabs, body);
        inner.getStyleClass().add("pad-16");

        Label title = new Label("WHAT'S NEW");
        title.getStyleClass().add("av-section-title");
        return new VBox(10, title, new Card(inner));
    }

    private ToggleButton tab(String text, ToggleGroup group) {
        ToggleButton t = new ToggleButton(text);
        t.getStyleClass().add("av-tab");
        t.setToggleGroup(group);
        return t;
    }

    private List<Node> newsRows() {
        return List.of(
                row("4.0.0 is out", "AveroLauncher 4.0.0 is now available. Update to get the newest version.", "LATEST"),
                row("3.0.0 is out", "Fixed bugs and added CurseForge API integration.", null),
                row("2.0.0 is out", "Added automatic updates.", null),
                row("1.1.0 is out", "Fixed bugs that remained unresolved in the previous release.", null));
    }

    private List<Node> changeRows() {
        return List.of(
                row("New launcher design", "A darker, sharper look with a slim sidebar that opens when you hover it.", null),
                row("Smoother motion", "Faster page changes and hover effects. Turn them off with Low-End Mode in Settings.", null),
                row("Modpack support", ".mrpack and CurseForge modpacks auto-detect their Minecraft version.", null),
                row("Cross-platform data paths", "Config now lives in the correct OS app-data folder.", null),
                row("Saved settings", "Minecraft directory, RAM, downloads and appearance all save live.", null));
    }

    private Node row(String title, String desc, String chip) {
        Region mark = new Region();
        mark.getStyleClass().add(chip != null ? "home-row-mark" : "home-row-mark-dim");
        mark.setMinWidth(3);
        mark.setPrefWidth(3);
        mark.setMaxWidth(3);

        Label t = new Label(title);
        t.getStyleClass().add("home-row-title");
        HBox titleRow = new HBox(10, t);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        if (chip != null) {
            Label c = new Label(chip);
            c.getStyleClass().addAll("av-chip", "av-chip-accent");
            titleRow.getChildren().add(c);
        }
        Label d = new Label(desc);
        d.getStyleClass().add("home-row-desc");
        d.setWrapText(true);
        d.setMaxWidth(Double.MAX_VALUE);

        VBox text = new VBox(3, titleRow, d);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox r = new HBox(14, mark, text);
        r.getStyleClass().add("home-row");
        return r;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Community
    // ─────────────────────────────────────────────────────────────────────────

    private Node buildCommunity() {
        Label title = new Label("COMMUNITY");
        title.getStyleClass().add("av-section-title");

        HBox row = new HBox();
        row.getStyleClass().add("gap-16");
        // Exactly half the row each, whatever is inside them (a content-sized card made the IP card wider).
        javafx.beans.binding.DoubleBinding half = row.widthProperty().subtract(16).divide(2);
        for (Card c : new Card[] { hostCard(), serverCard() }) {
            c.minWidthProperty().bind(half);
            c.prefWidthProperty().bind(half);
            c.maxWidthProperty().bind(half);
            row.getChildren().add(c);
        }
        return new VBox(10, title, row);
    }

    private static final double BANNER_H = 132;

    /**
     * Partner card layout: a banner of art on top (clipped), a logo tile that overlaps the banner's
     * lower edge, then name / description / chips pinned to the bottom so both cards line up.
     */
    private Card partnerCard(Node bannerArt, Node logo, String accent, String kicker, String name, String desc, List<String> chips, Node action) {
        StackPane art = new StackPane(bannerArt);
        art.setMinHeight(BANNER_H);
        art.setPrefHeight(BANNER_H);
        art.setMaxHeight(BANNER_H);
        Region scrim = new Region();
        scrim.getStyleClass().add("home-banner-scrim");
        scrim.setMouseTransparent(true);
        Region accentLine = new Region();     // thin coloured edge along the top of the card
        accentLine.getStyleClass().add("home-accent-" + accent);
        accentLine.setMinHeight(3);
        accentLine.setPrefHeight(3);
        accentLine.setMaxHeight(3);
        StackPane.setAlignment(accentLine, Pos.TOP_CENTER);
        art.getChildren().addAll(scrim, ValDeco.slashes(0.55), accentLine);
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(art.widthProperty());
        clip.heightProperty().bind(art.heightProperty());
        art.setClip(clip);

        Label tag = new Label("PARTNER");
        tag.getStyleClass().addAll("av-chip", "av-chip-accent");
        StackPane.setAlignment(tag, Pos.TOP_RIGHT);
        StackPane.setMargin(tag, new Insets(12, 12, 0, 0));

        StackPane logoTile = new StackPane(logo);
        logoTile.getStyleClass().addAll("home-logo-tile", "home-logo-" + accent);
        logoTile.setMinSize(64, 64);
        logoTile.setPrefSize(64, 64);
        logoTile.setMaxSize(64, 64);
        logoTile.setTranslateY(32);   // overlaps the banner's lower edge
        StackPane.setAlignment(logoTile, Pos.BOTTOM_LEFT);
        StackPane.setMargin(logoTile, new Insets(0, 0, 0, 20));

        StackPane banner = new StackPane(art, tag, logoTile);

        Label k = new Label(kicker);
        k.getStyleClass().addAll("home-partner-kicker", "home-kicker-" + accent);
        Label n = new Label(name);
        n.getStyleClass().add("home-partner-name");
        Label d = new Label(desc);
        d.getStyleClass().add("home-partner-desc");
        d.setWrapText(true);
        d.setMaxWidth(Double.MAX_VALUE);

        javafx.scene.layout.FlowPane chipRow = new javafx.scene.layout.FlowPane(6, 6);   // wraps instead of truncating
        for (String c : chips) {
            Label l = new Label(c);
            l.getStyleClass().add("av-chip");
            chipRow.getChildren().add(l);
        }

        Region spring = new Region();
        VBox.setVgrow(spring, Priority.ALWAYS);
        VBox body = new VBox(6, k, n, d, spring, chipRow);
        if (action != null) body.getChildren().add(action);
        body.getStyleClass().add("home-partner-body");
        VBox.setVgrow(body, Priority.ALWAYS);

        VBox content = new VBox(banner, body);
        content.setMaxHeight(Double.MAX_VALUE);
        return new Card(content);
    }

    private Card hostCard() {
        // Banner art: icy gradient with a little rack of servers (all shapes, no image needed)
        Region gradient = new Region();
        gradient.getStyleClass().add("home-banner-host");
        VBox racks = new VBox(7);
        racks.setAlignment(Pos.CENTER_RIGHT);
        for (int i = 0; i < 3; i++) {
            HBox rack = new HBox(8);
            rack.getStyleClass().add("home-rack");
            rack.setAlignment(Pos.CENTER_LEFT);
            rack.setMinSize(150, 22);
            rack.setPrefSize(150, 22);
            rack.setMaxSize(150, 22);
            Region led = new Region();
            led.getStyleClass().add(i == 1 ? "home-led-ice" : "home-led");
            led.setMinSize(6, 6);
            led.setMaxSize(6, 6);
            rack.getChildren().add(led);
            for (int k = 0; k < 3; k++) {
                Region slot = new Region();
                slot.getStyleClass().add("home-rack-slot");
                slot.setMinSize(18, 4);
                slot.setMaxSize(18, 4);
                rack.getChildren().add(slot);
            }
            racks.getChildren().add(rack);
        }
        StackPane.setAlignment(racks, Pos.CENTER_RIGHT);
        StackPane.setMargin(racks, new Insets(0, 40, 14, 0));
        StackPane art = new StackPane(gradient, racks);

        Label mono = new Label("A");
        mono.getStyleClass().add("home-logo-mono");
        return partnerCard(art, mono, "ice", "HOSTING", "AveroHost",
                "Fast, reliable Minecraft server hosting, optimised for AveroLauncher players.",
                List.of("INSTANT SETUP", "DDOS PROTECTED", "24/7 SUPPORT"), null);
    }

    private Card serverCard() {
        StackPane art = new StackPane();
        Region gradient = new Region();
        gradient.getStyleClass().add("home-banner-server");
        art.getChildren().add(gradient);
        Image bg = loadClasspathImage("/images/bg.png");
        if (bg != null) {
            ImageView cover = new ImageView(bg);
            cover.setSmooth(true);
            cover.setPreserveRatio(false);
            cover.setMouseTransparent(true);
            double iw = bg.getWidth(), ih = bg.getHeight();
            DoubleBinding scale = Bindings.createDoubleBinding(
                    () -> Math.max(Math.max(art.getWidth(), 1) / iw, BANNER_H / ih), art.widthProperty());
            cover.fitWidthProperty().bind(scale.multiply(iw));
            cover.fitHeightProperty().bind(scale.multiply(ih));
            art.getChildren().add(cover);
        }

        Node logo;
        Image pinoy = loadClasspathImage("/images/pinoy.png");
        if (pinoy != null) {
            ImageView iv = new ImageView(pinoy);
            iv.setFitWidth(52);
            iv.setFitHeight(52);
            iv.setPreserveRatio(true);
            iv.setSmooth(true);
            logo = iv;
        } else {
            Label mono = new Label("P");
            mono.getStyleClass().add("home-logo-mono");
            logo = mono;
        }

        Label ip = new Label(COPY_IP);
        ip.getStyleClass().add("home-partner-ip");
        HBox.setHgrow(ip, Priority.ALWAYS);
        ip.setMaxWidth(Double.MAX_VALUE);
        AvButton copy = new AvButton("Copy IP", AvButton.Kind.GHOST);
        copy.setOnAction(e -> {
            try {
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new java.awt.datatransfer.StringSelection(COPY_IP), null);
            } catch (Exception ignored) { }
            String original = copy.getText();
            copy.setText("Copied!");
            PauseTransition revert = new PauseTransition(Duration.millis(1400));
            revert.setOnFinished(ev -> copy.setText(original));
            revert.play();
        });
        HBox ipRow = new HBox(10, ip, copy);
        ipRow.setAlignment(Pos.CENTER_LEFT);
        VBox.setMargin(ipRow, new Insets(4, 0, 0, 0));

        return partnerCard(art, logo, "red", "SERVER", "PINOYCraft",
                "The community's favourite icy-biome survival server.",
                List.of("SURVIVAL", "SKYBLOCK", "PRISON"), ipRow);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Player skin (3D hero model)
    // ─────────────────────────────────────────────────────────────────────────

    private int skinTicket;

    private Account activeAccount() {
        String id = settings.activeAccountId;
        if (id == null) return null;
        return accountRepository.load().stream()
                .filter(a -> id.equals(a.id))
                .findFirst().orElse(null);
    }

    /**
     * Shows the active account's skin on the 3D model. Source order mirrors the Play page: the library
     * skin an offline account wears, then the skin URL Mojang reported, then (Microsoft accounts only)
     * a lookup by name. Anything missing or unreadable falls back to a short hint on the empty tile.
     */
    private void loadSkin(SkinModel3D model, Label tag, Label name) {
        final int mine = ++skinTicket;
        Account acct = activeAccount();
        if (acct == null) {
            model.showEmpty("No account");
            name.setText("Add an account");
            tag.setText("OPEN ACCOUNTS");
            return;
        }
        name.setText(acct.username != null ? acct.username : "Player");
        tag.setText("YOUR SKIN");

        java.util.concurrent.CompletableFuture<SkinModel3D.Prepared> job;
        boolean offline = acct.type != Account.Type.MICROSOFT;
        Path local = null;
        if (acct.offlineSkinId != null && !acct.offlineSkinId.isBlank()) {
            Path p = new SkinLibrary().pngPath(acct.offlineSkinId);
            if (Files.isRegularFile(p)) local = p;
        }
        final Path localPng = local;
        final SkinService skins = new SkinService();

        if (localPng != null) {
            model.showLoading();
            job = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    long stamp = Files.getLastModifiedTime(localPng).toMillis();
                    return SkinModel3D.prepare(Files.readAllBytes(localPng), acct.offlineSkinVariant, localPng + "|" + stamp);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } else if (acct.skinUrl != null && !acct.skinUrl.isBlank()) {
            model.showLoading();
            job = skins.download(acct.skinUrl).thenApplyAsync(png -> {
                try {
                    return SkinModel3D.prepare(png, acct.skinVariant, acct.skinUrl);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } else if (!offline && acct.username != null && !acct.username.isBlank()) {
            model.showLoading();
            job = skins.fetchByUsername(acct.username).thenApplyAsync(rs -> {
                try {
                    return SkinModel3D.prepare(rs.png, rs.variant, "name:" + acct.username);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } else {
            model.showEmpty("No skin");
            tag.setText("SET ONE IN ACCOUNTS");
            return;
        }

        job.whenComplete((prepared, err) -> Platform.runLater(() -> {
            if (mine != skinTicket) return;   // Home was rebuilt meanwhile
            if (err != null || prepared == null) {
                model.showEmpty("No skin");
                tag.setText("SET ONE IN ACCOUNTS");
                return;
            }
            model.show(prepared);
        }));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cape state (display only: reads what Avero ID already exposes)
    // ─────────────────────────────────────────────────────────────────────────

    private enum CapeKind { SIGNED_OUT, LOADING, NONE, EQUIPPED, ERROR }

    private record CapeState(CapeKind kind, String id, String name, String textureUrl) { }

    private void loadCape() {
        AveroId avero = AveroId.get();
        if (!avero.isSignedIn()) {
            lastCape = null;
            publish(new CapeState(CapeKind.SIGNED_OUT, null, null, null));
            return;
        }
        publish(lastCape != null ? lastCape : new CapeState(CapeKind.LOADING, null, null, null));
        final int mine = ++capeTicket;
        avero.api().cosmetics().whenComplete((c, err) -> Platform.runLater(() -> {
            if (mine != capeTicket) return;   // a newer request superseded this one
            if (err != null) {
                String raw = ErrorState.describe(err);
                String msg = ErrorState.isNetwork(raw) ? "Can't reach Avero right now" : "Couldn't load your cape";
                publish(lastCape != null ? lastCape : new CapeState(CapeKind.ERROR, null, msg, null));
                return;
            }
            if (c == null || c.equippedCape == null) {
                lastCape = new CapeState(CapeKind.NONE, null, null, null);
            } else {
                lastCape = new CapeState(CapeKind.EQUIPPED, c.equippedCape.id, c.equippedCape.name, c.equippedCape.textureUrl);
            }
            publish(lastCape);
        }));
    }

    private void publish(CapeState st) {
        for (Consumer<CapeState> v : new ArrayList<>(capeViews)) v.accept(st);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private boolean mcDirExists() {
        return settings.minecraftDirectory != null && !settings.minecraftDirectory.isBlank()
                && Files.exists(Path.of(settings.minecraftDirectory));
    }

    private boolean versionSet() {
        return settings.defaultVersionId != null && !settings.defaultVersionId.isBlank();
    }

    private boolean isReady() {
        return mcDirExists() && versionSet();
    }

    private Account resolveActiveAccount() {
        if (settings.activeAccountId == null) return null;
        return accountRepository.load().stream()
                .filter(a -> a.id.equals(settings.activeAccountId))
                .findFirst().orElse(null);
    }

    /** Versions with a JSON on disk under versions/, most recently installed first. */
    private List<String> installedVersions() {
        List<String> out = new ArrayList<>();
        if (!mcDirExists()) return out;
        Path dir = Path.of(settings.minecraftDirectory, "versions");
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isDirectory)
                    .filter(d -> Files.exists(jsonOf(d)))
                    .sorted(Comparator.comparingLong((Path d) -> jsonOf(d).toFile().lastModified()).reversed())
                    .limit(80)
                    .forEach(d -> out.add(d.getFileName().toString()));
        } catch (IOException ignored) {
        }
        return out;
    }

    private static Path jsonOf(Path versionDir) {
        String id = versionDir.getFileName().toString();
        return versionDir.resolve(id + ".json");
    }

    /**
     * Cover-fits /images/hero-background.jpg across the hero (scaled by the larger ratio, cropped to the
     * banner). Returns null if the file isn't bundled, in which case the gradient alone shows.
     */
    private Node buildHeroPhoto(StackPane hero) {
        Image image = loadClasspathImage("/images/hero-background.jpg");
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) return null;
        double imgW = image.getWidth(), imgH = image.getHeight();

        ImageView view = new ImageView(image);
        view.getStyleClass().add("home-hero-photo");
        view.setSmooth(true);
        view.setPreserveRatio(false);
        DoubleBinding scale = Bindings.createDoubleBinding(
                () -> Math.max(Math.max(hero.getWidth(), 1) / imgW, HERO_H / imgH), hero.widthProperty());
        view.fitWidthProperty().bind(scale.multiply(imgW));
        view.fitHeightProperty().bind(scale.multiply(imgH));
        StackPane.setAlignment(view, Pos.TOP_CENTER);
        view.setMouseTransparent(true);
        return view;
    }

    private Image loadClasspathImage(String resourcePath) {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) return null;
            Image image = new Image(is);
            return image.isError() ? null : image;
        } catch (Exception e) {
            return null;
        }
    }
}
