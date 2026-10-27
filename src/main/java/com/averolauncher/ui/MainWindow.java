package com.averolauncher.ui;

import com.averolauncher.auth.MicrosoftAuthService;
import com.averolauncher.logging.AppLogger;
import com.averolauncher.model.LauncherSettings;
import com.averolauncher.mods.ModManager;
import com.averolauncher.repository.AccountRepository;
import com.averolauncher.repository.SettingsRepository;
import com.averolauncher.service.LaunchService;
import com.averolauncher.service.ModrinthService;
import com.averolauncher.ui.kit.AvButton;
import com.averolauncher.ui.kit.FocusRing;
import com.averolauncher.ui.kit.LeaderboardOverlay;
import com.averolauncher.ui.kit.RankToast;
import com.averolauncher.ui.kit.RankUpOverlay;
import com.averolauncher.ui.pages.*;
import com.averolauncher.update.UpdateChecker;
import com.averolauncher.update.UpdateDownloader;
import com.averolauncher.update.UpdateInfo;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.util.EnumMap;
import java.util.Map;

/**
 * Builds and owns the main application window. The shell is a stack of layers:
 *
 * <pre>
 *   column   top bar + page area (left inset = rail width, so pages never sit under the rail)
 *     page area   = pages (PageTransition)  +  update bar floating over the bottom
 *   scrim    dims the column while the rail is expanded over it
 *   sidebar  RailSidebar: 72 px icon rail that slides open to 240 px over everything above
 * </pre>
 *
 * Pages are constructed lazily on first visit to keep startup fast and idle memory low.
 */
public class MainWindow {

    private static final String TAG = "MainWindow";
    /** Opacity of the scrim at full strength (it is further multiplied by the -av-scrim token's alpha). */
    private static final double SCRIM_ALPHA = 0.8;

    private final Stage stage;
    private final LauncherSettings settings;
    private final SettingsRepository settingsRepository;
    private final AppContext appContext;

    private final StackPane pageHost = new StackPane();
    private final Map<NavPage, Parent> pageCache = new EnumMap<>(NavPage.class);
    private final Region scrim = new Region();
    private final BorderPane column = new BorderPane();
    private RailSidebar sidebar;
    private PageTransition transition;
    private UpdateBar updateBar;
    private RankToast rankToast;
    private RankUpOverlay rankUpOverlay;
    private LeaderboardOverlay leaderboardOverlay;

    // Top-bar account chip — refreshed on every navigation so it reflects account changes.
    private Label topBarAccountName;
    private Label topBarAccountTag;
    private Label topBarAvatarInitial;
    private AvButton topBarLoginButton;

    public MainWindow(Stage stage, LauncherSettings settings, SettingsRepository settingsRepository, LaunchService launchService,
                       ModrinthService modrinthService, com.averolauncher.service.CurseForgeService curseForgeService,
                       ModManager modManager, AccountRepository accountRepository,
                       MicrosoftAuthService microsoftAuthService, UpdateChecker updateChecker,
                       UpdateDownloader updateDownloader) {
        this.stage = stage;
        this.settings = settings;
        this.settingsRepository = settingsRepository;
        this.appContext = new AppContext(settings, settingsRepository, launchService, modrinthService, curseForgeService,
                modManager, accountRepository, microsoftAuthService, updateChecker, updateDownloader);
        ensureActiveAccount();
        // Lets the Avero ID auto-link every saved Microsoft account (verified with Mojang) as soon as it's signed in.
        com.averolauncher.store.AveroId.get().bind(appContext);
        // Global rank system: tracks verified playtime of games launched from here (needs Avero ID, see RankService).
        com.averolauncher.rank.RankService.get().bind(appContext);
    }

    /**
     * Saved accounts but no (or a dangling) active selection — e.g. settings.json was reset or
     * restored from a backup — used to leave the launcher showing "Guest / SIGN IN" even though the
     * player was signed in. Re-select a sensible account (a Microsoft one when there is one).
     */
    private void ensureActiveAccount() {
        try {
            java.util.List<com.averolauncher.model.Account> all = appContext.accountRepository.load();
            if (all.isEmpty()) return;
            String id = settings.activeAccountId;
            boolean valid = id != null && all.stream().anyMatch(a -> id.equals(a.id));
            if (valid) return;
            com.averolauncher.model.Account pick = all.stream()
                    .filter(a -> a.type == com.averolauncher.model.Account.Type.MICROSOFT)
                    .findFirst().orElse(all.get(0));
            settings.activeAccountId = pick.id;
            settingsRepository.save(settings);
            AppLogger.get().info(TAG, "No valid active account was selected; using " + pick.username + ".");
        } catch (Exception e) {
            AppLogger.get().warn(TAG, "Could not restore the active account: " + e.getMessage());
        }
    }

    /**
     * Registers every bundled font weight with JavaFX's font system before any CSS is applied, so
     * "-fx-font-family: 'Barlow'" combined with "-fx-font-weight" in the stylesheet resolves to the
     * correct static weight file instead of a synthetic bold.
     *
     * <p>Typography follows Riot's Valorant client: <b>Barlow</b> (a DIN-style grotesque) for body
     * text and <b>Barlow Condensed</b> (the closest free match to Tungsten) for headings, buttons and
     * labels. Both are SIL OFL 1.1 — see fonts/OFL-LICENSE-Barlow.txt.
     */
    private void loadCustomFonts() {
        // Body (regular/bold) + two display faces derived from Barlow Condensed Bold: "Avero Display"
        // (+2% tracking, titles/buttons/nav) and "Avero Tracked" (+9.5%, small caps labels).
        // JavaFX resolves by plain family name, so only the faces the stylesheet uses are loaded.
        String[] weights = {
                "/fonts/Barlow-Regular.ttf",
                "/fonts/Barlow-Bold.ttf",
                "/fonts/AveroDisplay-Bold.ttf",
                "/fonts/AveroTracked-Bold.ttf",
                // Monocraft (SIL OFL 1.1): the pixel face used for buttons, labels and tags so the
                // Valorant-style layout is dressed in Minecraft's own lettering.
                "/fonts/Monocraft-Regular.ttf",
                "/fonts/Monocraft-Bold.ttf"
        };
        for (String path : weights) {
            try (var stream = getClass().getResourceAsStream(path)) {
                if (stream == null) {
                    AppLogger.get().warn(TAG, "Font resource not found: " + path);
                    continue;
                }
                Font loaded = Font.loadFont(stream, 12);
                if (loaded == null) {
                    AppLogger.get().warn(TAG, "Failed to load font: " + path);
                }
            } catch (Exception e) {
                AppLogger.get().warn(TAG, "Error loading font " + path + ": " + e.getMessage());
            }
        }
    }

    public Scene createScene() {
        loadCustomFonts();
        Motion.bind(settings);                      // one live switch for every animation (Low-End Mode)
        ShellState.bind(settings, settingsRepository); // "Pin sidebar open"

        sidebar = new RailSidebar(this::navigateTo, this::focusPage);
        updateBar = new UpdateBar(appContext, settings, settingsRepository);
        transition = new PageTransition(pageHost);

        // Page area: pages, with the update bar floating over the bottom edge (no reflow).
        pageHost.getStyleClass().add("av-page-host");
        rankToast = new RankToast();
        // Leaderboard "semipage": slides in over the page area (not the rail/top bar) from the right.
        leaderboardOverlay = new LeaderboardOverlay(this::navigateTo);
        StackPane pageLayer = new StackPane(pageHost, updateBar, rankToast, leaderboardOverlay);
        // Halloween season: pumpkins in the bottom-right corner, under the toasts so alerts stay readable.
        javafx.scene.image.ImageView pumpkins = com.averolauncher.ui.kit.HalloweenDecor.create();
        if (pumpkins != null) {
            pageLayer.getChildren().add(1, pumpkins);
            StackPane.setAlignment(pumpkins, Pos.BOTTOM_RIGHT);
            StackPane.setMargin(pumpkins, new Insets(0, 8, 4, 0));
        }
        StackPane.setAlignment(updateBar, Pos.BOTTOM_CENTER);
        StackPane.setMargin(updateBar, new Insets(0, 24, 16, 24));
        StackPane.setAlignment(rankToast, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(rankToast, new Insets(0, 24, 24, 24));
        installRankToasts();

        column.setTop(buildTopBar());
        column.setCenter(pageLayer);
        applyColumnInset(ShellState.isPinned());

        // Scrim: dims the page while the rail is open over it; a click on it closes the rail.
        scrim.getStyleClass().add("av-scrim");
        scrim.setOpacity(0);
        scrim.setVisible(false);
        scrim.setMouseTransparent(true);
        scrim.opacityProperty().addListener((o, was, now) -> scrim.setVisible(now.doubleValue() > 0.001));
        scrim.setOnMousePressed(e -> sidebar.collapseNow());

        sidebar.expandedProperty().addListener((o, was, now) -> syncScrim());
        ShellState.pinnedProperty().addListener((o, was, pinned) -> {
            applyColumnInset(pinned);   // pinned is a deliberate choice, so the page makes room for it
            sidebar.setPinned(pinned);
            syncScrim();
        });

        // Full-screen cinematic rank-up: sits above everything (sidebar included), built once and
        // reused for every promotion so it reads as one modal moment regardless of the current page.
        rankUpOverlay = new RankUpOverlay();

        StackPane shell = new StackPane(column, scrim, sidebar, rankUpOverlay);
        shell.getStyleClass().add("av-shell");
        StackPane.setAlignment(sidebar, Pos.TOP_LEFT);
        sidebar.setPinned(ShellState.isPinned());
        ValText.watch(shell); // sidebar + top bar (pages are watched as they are shown)

        Scene scene = new Scene(shell, 1280, 720);
        // Belt-and-suspenders against the Scene's own default WHITE fill showing through at the edges.
        scene.setFill(javafx.scene.paint.Color.web("#0f1923"));
        // Order matters: legacy sheet first, then the design system on top of it, then shell and per-page styles.
        for (String name : new String[] {"dark-theme", "tokens", "components", "shell", "pages", "rank", "rankup", "mc-theme"}) {
            var css = getClass().getResource("/css/" + name + ".css");
            if (css != null) {
                scene.getStylesheets().add(css.toExternalForm());
            } else {
                AppLogger.get().warn(TAG, "Stylesheet not found: " + name + ".css");
            }
        }
        FocusRing.install(scene);
        installShortcuts(scene);

        navigateTo(NavPage.HOME);
        return scene;
    }

    /** Ctrl+1..9 jump to a page; Ctrl+B moves keyboard focus into the sidebar (Esc returns to the page). */
    private void installShortcuts(Scene scene) {
        // Fixed order so Ctrl+1..8 keep their original meaning; pages added later take the remaining numbers.
        NavPage[] pages = {NavPage.HOME, NavPage.PLAY, NavPage.MODS, NavPage.SERVERS, NavPage.STORE,
                NavPage.ACCOUNTS, NavPage.SETTINGS, NavPage.ABOUT, NavPage.RANKS};
        for (int i = 0; i < pages.length && i < 9; i++) {
            NavPage p = pages[i];
            scene.getAccelerators().put(
                    new KeyCodeCombination(KeyCode.valueOf("DIGIT" + (i + 1)), KeyCombination.CONTROL_DOWN),
                    () -> navigateTo(p));
        }
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.B, KeyCombination.CONTROL_DOWN), () -> sidebar.focusNav());
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.R, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN), this::replayRankUp);
    }

    /** The page area keeps a fixed left inset equal to the rail, so an overlaying rail never reflows it. */
    private void applyColumnInset(boolean pinned) {
        column.setPadding(new Insets(0, 0, 0, pinned ? RailSidebar.OPEN_W : RailSidebar.RAIL_W));
    }

    private void syncScrim() {
        boolean dim = sidebar.expandedProperty().get() && !ShellState.isPinned();
        scrim.setMouseTransparent(!dim);
        Motion.animate(scrim.opacityProperty(), dim ? SCRIM_ALPHA : 0.0, Motion.PANEL);
    }

    /** Esc in the rail: give focus back to the page area. */
    private void focusPage() {
        // A plain pane only accepts programmatic focus while it is focus-traversable; make it so just
        // for this call so it doesn't become an extra Tab stop.
        column.setFocusTraversable(true);
        column.requestFocus();
        column.setFocusTraversable(false);
    }

    private void navigateTo(NavPage page) {
        Parent panel = pageCache.computeIfAbsent(page, this::wrapScrollable);
        ValText.watch(panel);
        sidebar.select(page);
        refreshTopBarAccount();
        transition.show(panel);
        AppLogger.get().debug(TAG, "Navigated to " + page);
        // Checked on Home too (the page the launcher opens on), so a promotion earned while the launcher was
        // closed or in the background is celebrated as soon as the player sees the launcher again.
        if (page == NavPage.RANKS || page == NavPage.HOME) {
            Platform.runLater(() -> celebrateIfNew(com.averolauncher.rank.RankService.get().snapshot().standing()));
        }
    }

    /**
     * Plays the rank-up cinematic for {@code to} if (and only if) it's higher than the last standing
     * already celebrated, then persists {@code to} as celebrated so it never replays. This is the
     * primary trigger (checked every time the Ranks page opens) because the live {@code onPromotion}
     * callback alone can be missed — the launcher window may not be focused when AP is credited during
     * play, or a sync/catch-up can grant a large batch of AP in one step. The live callback below still
     * fires it immediately when the window happens to be in front; whichever path runs first wins, and
     * {@code rankCelebratedKey} keeps the other from firing twice for the same standing.
     */
    private void celebrateIfNew(com.averolauncher.rank.RankStanding to) {
        if (to == null || to.tier() == null || rankUpOverlay == null) return;
        String key = to.tier().id + ":" + to.division();
        if (key.equals(settings.rankCelebratedKey)) return;
        if (to.ap() <= 0) {
            // Brand-new standing with no AP earned yet: nothing to celebrate, just set the baseline
            // so the first real promotion (not this starting point) is what triggers the cinematic.
            settings.rankCelebratedKey = key;
            settingsRepository.save(settings);
            return;
        }
        com.averolauncher.rank.RankConfig cfg = com.averolauncher.rank.RankService.get().config();
        com.averolauncher.rank.RankStanding from = com.averolauncher.rank.RankCalculator.previousStep(cfg, to);
        // Only remember this standing as celebrated once the cinematic really started, so a failure
        // never silently swallows the promotion.
        if (playRankUp(cfg, from, to)) {
            settings.rankCelebratedKey = key;
            settingsRepository.save(settings);
        }
    }

    /** Plays the overlay; logs (instead of throwing into the FX thread) if anything in it fails. */
    private boolean playRankUp(com.averolauncher.rank.RankConfig cfg, com.averolauncher.rank.RankStanding from,
                               com.averolauncher.rank.RankStanding to) {
        try {
            rankUpOverlay.play(cfg, from, to, null);
            return true;
        } catch (Throwable t) {
            AppLogger.get().error(TAG, "Rank-up animation failed: " + t, t);
            return false;
        }
    }

    /** The Home page's leaderboard button: slides the live leaderboard in as a semipage drawer. */
    private void openLeaderboard() {
        if (leaderboardOverlay != null) leaderboardOverlay.open();
    }

    /** Ctrl+Shift+R / the Ranks page "Replay" button: plays the cinematic for the current rank on demand. */
    private void replayRankUp() {
        com.averolauncher.rank.RankStanding to = com.averolauncher.rank.RankService.get().snapshot().standing();
        if (to == null || to.tier() == null) return;
        com.averolauncher.rank.RankConfig cfg = com.averolauncher.rank.RankService.get().config();
        playRankUp(cfg, com.averolauncher.rank.RankCalculator.previousStep(cfg, to), to);
    }

    /**
     * Refreshes the top-bar account chip. It reflects BOTH sign-ins: the active Minecraft account and
     * the Avero ID. "Guest / SIGN IN" only shows when the player really isn't signed in to anything.
     */
    private void refreshTopBarAccount() {
        if (topBarAccountName == null) return; // not built yet
        java.util.List<com.averolauncher.model.Account> allAccounts = appContext.accountRepository.load();
        com.averolauncher.model.Account active = appContext.settings.activeAccountId != null
                ? allAccounts.stream().filter(a -> a.id.equals(appContext.settings.activeAccountId)).findFirst().orElse(null)
                : null;
        com.averolauncher.store.StoreApi.AveroSession avero = com.averolauncher.store.AveroId.get().session();
        boolean averoIn = avero != null;

        String name, tag;
        boolean showSignIn;
        if (active != null) {
            name = active.username;
            tag = active.type == com.averolauncher.model.Account.Type.MICROSOFT ? "Microsoft" : active.averoLinked ? "Avero account" : "Offline profile";
            if (averoIn) tag += " \u00B7 Avero ID";
            showSignIn = false;
        } else if (averoIn) {
            name = avero.username != null && !avero.username.isBlank() ? avero.username : "Avero ID";
            tag = "Avero ID";
            showSignIn = false;
        } else {
            name = "Guest";
            tag = "Offline";
            showSignIn = true;
        }
        topBarAccountName.setText(name);
        topBarAccountTag.setText(tag);
        String initial = (name != null && !name.isBlank()) ? name.substring(0, 1).toUpperCase() : "G";
        topBarAvatarInitial.setText(initial);
        topBarLoginButton.setVisible(showSignIn);
        topBarLoginButton.setManaged(showSignIn);
    }

    /** Compact (~56 px) top bar: search on the left, account chip on the right. */
    private HBox buildTopBar() {
        HBox bar = new HBox();
        bar.getStyleClass().addAll("av-topbar", "gap-16");
        bar.setAlignment(Pos.CENTER_LEFT);

        TextField search = new TextField();
        search.setPromptText("Search anything...");
        search.getStyleClass().add("search-field-input");
        HBox.setHgrow(search, Priority.ALWAYS);

        StackPane searchIconBox = new StackPane(com.averolauncher.util.IconRegistry.search());
        searchIconBox.getStyleClass().add("search-field-icon");
        searchIconBox.setMinSize(15, 15);
        searchIconBox.setMaxSize(15, 15);

        HBox searchWrap = new HBox(9, searchIconBox, search);
        searchWrap.getStyleClass().add("search-field");
        searchWrap.setAlignment(Pos.CENTER_LEFT);
        searchWrap.setPrefWidth(340);
        searchWrap.setMaxWidth(340);

        // Focus state is drawn by the stylesheet (red border) — no blurred glow effect, which is flatter and cheaper.
        PseudoClass focusWithin = PseudoClass.getPseudoClass("focus-within");
        search.focusedProperty().addListener((obs, was, isNow) -> searchWrap.pseudoClassStateChanged(focusWithin, isNow));

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        topBarAccountName = new Label();
        topBarAccountName.getStyleClass().add("account-name");
        topBarAccountTag = new Label();
        topBarAccountTag.getStyleClass().add("account-tag");
        VBox accountText = new VBox(1, topBarAccountName, topBarAccountTag);

        StackPane avatar = avatarCircle();
        topBarAvatarInitial = (Label) avatar.getChildren().get(0); // avatarCircle's sole child

        HBox accountChip = new HBox(10, avatar, accountText);
        accountChip.getStyleClass().add("account-chip");
        accountChip.setAlignment(Pos.CENTER_LEFT);
        accountChip.setFocusTraversable(true);
        accountChip.setAccessibleText("Account");
        accountChip.setOnMouseClicked(e -> navigateTo(NavPage.ACCOUNTS));
        accountChip.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) navigateTo(NavPage.ACCOUNTS);
        });

        // Only shown when there is no account at all; otherwise the chip is the shortcut to Accounts.
        topBarLoginButton = new AvButton("Sign in", AvButton.Kind.GHOST);
        topBarLoginButton.setOnAction(e -> navigateTo(NavPage.ACCOUNTS));

        bar.getChildren().addAll(searchWrap, spacer, accountChip, topBarLoginButton);
        refreshTopBarAccount();
        // Sign-in / sign-out / session restore finishing in the background all update the chip immediately.
        com.averolauncher.store.AveroId.get().addListener(() -> Platform.runLater(this::refreshTopBarAccount));
        return bar;
    }

    private StackPane avatarCircle() {
        StackPane circle = new StackPane();
        circle.getStyleClass().add("avatar-circle");
        Label initial = new Label("G");
        initial.getStyleClass().add("avatar-initial");
        circle.getChildren().add(initial);
        return circle;
    }

    private Parent wrapScrollable(NavPage page) {
        Parent content = buildPage(page);
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        // Home's layout uses VGrow so it naturally fills a normal-height window without scrolling.
        // It deliberately does NOT force fitToHeight: nested regions (the tabbed activity card's
        // internal scroll lists in particular) report a small minimum height, so forcing the page to
        // the viewport height on a small window squashed them instead of simply making the page scroll.
        // Transparent background comes from .av-page-scroll (shell.css); "page-scroll" keeps legacy scrollbar styling.
        scroll.getStyleClass().addAll("page-scroll", "av-page-scroll");
        return scroll;
    }

    private Parent buildPage(NavPage page) {
        switch (page) {
            case HOME:
                return new HomePage(settings, this::navigateTo, appContext.accountRepository, settingsRepository,
                        this::openLeaderboard).build();
            case PLAY:
                return new PlayPage(appContext, this::navigateTo).build();
            case MODS:
                return new ModsPage(appContext).build();
            case SERVERS:
                return new ServersPage(appContext).build();
            case STORE:
                return new StorePage(appContext, this::navigateTo).build();
            case RANKS:
                return new RanksPage(this::navigateTo, this::replayRankUp).build();
            case ACCOUNTS:
                return new AccountsPage(appContext).build();
            case SETTINGS:
                return new SettingsPage(settings, settingsRepository, appContext).build();
            case ABOUT:
                return new AboutPage(appContext).build();
            default:
                throw new IllegalStateException("Unhandled page: " + page);
        }
    }

    // ─────────────────────────────────────────────────────── rank toasts

    /**
     * Promotion and quest-completed notices from the rank system, shown over the page area.
     * onPromotion is the fast path (fires immediately if the launcher window happens to be in front
     * when it happens) but isn't relied on alone — see {@link #celebrateIfNew} and the check that runs
     * every time the Ranks page opens, which is what actually guarantees the cinematic is seen.
     */
    private void installRankToasts() {
        com.averolauncher.rank.RankService.get().addListener(new com.averolauncher.rank.RankListener() {
            @Override public void onPromotion(com.averolauncher.rank.RankStanding to) {
                Platform.runLater(() -> celebrateIfNew(to));
            }
            @Override public void onQuestCompleted(com.averolauncher.rank.QuestView q) {
                Platform.runLater(() -> rankToast.show("Quest complete: " + q.title(), "+" + q.rewardAp() + " AP", "#5ec97a"));
            }
        });
    }

    // ─────────────────────────────────────────────────────── update bar

    /**
     * Shows the slim update bar over the page area. Safe to call from any thread; hops to the FX
     * thread itself. Ignores a version the player already chose to skip.
     */
    public void showUpdateAvailable(UpdateInfo info) {
        if (info == null) return;
        Platform.runLater(() -> updateBar.show(info));
    }
}
