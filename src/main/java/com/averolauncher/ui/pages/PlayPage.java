package com.averolauncher.ui.pages;

import com.averolauncher.loaders.LoaderService;
import com.averolauncher.logging.AppLogger;
import com.averolauncher.model.Account;
import com.averolauncher.model.DownloadTask;
import com.averolauncher.model.manifest.VersionManifest;
import com.averolauncher.ui.AppContext;
import com.averolauncher.ui.Motion;
import com.averolauncher.ui.NavPage;
import com.averolauncher.ui.kit.AvButton;
import com.averolauncher.ui.kit.Card;
import com.averolauncher.ui.kit.EmptyState;
import com.averolauncher.ui.kit.ErrorState;
import com.averolauncher.rank.RankListener;
import com.averolauncher.rank.RankService;
import com.averolauncher.rank.RankSnapshot;
import com.averolauncher.rank.RankStanding;
import com.averolauncher.ui.kit.PageHeader;
import com.averolauncher.ui.kit.RankBadge;
import com.averolauncher.ui.kit.RankBar;
import com.averolauncher.ui.kit.Skeleton;
import com.averolauncher.ui.kit.StepProgress;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Scale;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Play. A launch panel (version picker or the selected modpack, a big PLAY button with a pulsing
 * glow, launch progress and status) above four underline tabs:
 * <ol>
 *   <li><b>Versions</b> – pick any release/snapshot/local modded version and launch it.</li>
 *   <li><b>Modpacks</b> – modpacks found in modpacks/ (from the Mods browser); the Minecraft
 *       version is detected automatically.</li>
 *   <li><b>All versions</b> – full browser with a per-row Install / Play button.</li>
 *   <li><b>Downloads</b> – live per-file download monitor.</li>
 * </ol>
 *
 * <p>This is a visual redesign only: {@code launchVersion}, {@code launchModpack},
 * {@code onPlayClicked}, version/modpack detection and the download/launch callbacks are the same
 * code as before; what changed is how their state is presented. The page is cached by MainWindow,
 * so it refreshes itself in place when shown again (default version changed on Home, new local
 * versions, new modpacks) without losing a launch that is in progress.
 */
public class PlayPage {

    private static final String TAG = "PlayPage";

    // ── shared services ──────────────────────────────────────────────────────
    private final AppContext appContext;
    /** Used by empty states ("Open settings", "Browse mods"). May be null: those buttons are then left out. */
    private final Consumer<NavPage> navigator;

    // ── version-tab controls ─────────────────────────────────────────────────
    private final ComboBox<VersionManifest.VersionEntry> versionBox = new ComboBox<>();
    private final ToggleButton showSnapshotsBox = filterToggle("SNAPSHOTS");
    private final ToggleButton showModdedBox = filterToggle("MODDED (LOCAL)");
    private final AvButton retryVersionsButton = new AvButton("Retry", AvButton.Kind.GHOST);
    private final AvButton installLoaderButton = new AvButton("+  INSTALL MOD LOADER", AvButton.Kind.GHOST);
    private LoaderService loaderService;   // created on first use
    private final List<VersionManifest.VersionEntry> allVersions = new ArrayList<>();
    private boolean versionsLoading = true;
    private boolean versionsFailed = false;

    // ── modpack-tab controls ─────────────────────────────────────────────────
    private final FlowPane modpackGrid = new FlowPane();
    private final VBox modpackBody = new VBox();
    private final Label modpackStatusLabel = new Label();
    // Detected MC version for the selected modpack (read-only, auto-filled — no manual picker)
    private final Label modpackVersionLabel = new Label("—");
    private final List<Card> modpackCards = new ArrayList<>();
    private InstalledModpack selectedModpack = null;
    private VersionManifest.VersionEntry selectedModpackVersion = null;
    private int modpackScanTicket;

    // ── version-browser tab controls ─────────────────────────────────────────
    private final ListView<VersionManifest.VersionEntry> browserList = new ListView<>();
    private final ToggleButton browserReleases = filterToggle("RELEASES");
    private final ToggleButton browserSnapshots = filterToggle("SNAPSHOTS");
    private final ToggleButton browserModded = filterToggle("MODDED (LOCAL)");
    private final Label browserStatusLabel = new Label();

    // ── downloads tab controls ───────────────────────────────────────────────
    /** Max rows kept in the Downloads tab — a vanilla install queues thousands of asset files. */
    private static final int MAX_DOWNLOAD_ROWS = 200;
    private final VBox downloadList = new VBox();
    private final Label downloadStatusLabel = new Label();
    private final List<DownloadTask> trackedDownloads = new ArrayList<>();
    private final Map<DownloadTask, HBox> downloadRowMap = new java.util.LinkedHashMap<>();
    private ScrollPane downloadScroll;
    private EmptyState downloadsEmpty;

    // ── shared play controls (reused for the launch tabs) ─────────────────────
    private final AvButton playButton = new AvButton("PLAY", AvButton.Kind.PLAY);
    private final Label statusLabel = new Label();
    private final TextArea outputArea = new TextArea();
    private final StepProgress progress = new StepProgress();
    private final Label launchFilesLabel = new Label();
    private final Label phaseChip = new Label();
    private final AvButton retryLaunchButton = new AvButton("Try again", AvButton.Kind.GHOST);

    /** What the launch panel is showing. (Loading the version list is tracked by {@link #versionsLoading}.) */
    private enum Phase { READY, LAUNCHING, RUNNING }
    private Phase phase = Phase.READY;
    private boolean launchFailed;
    private boolean logShown;
    private int launchFiles;

    // ── tab tracking ─────────────────────────────────────────────────────────
    private enum Tab { VERSIONS, MODPACKS, BROWSER, DOWNLOADS }
    private Tab activeTab = Tab.VERSIONS;
    private final Map<Tab, ToggleButton> tabButtons = new EnumMap<>(Tab.class);

    // ── panel pieces refreshed by refreshChrome() ────────────────────────────
    private VBox launchPanel;
    private Label eyebrow;
    private Label titleLabel;
    private Skeleton titleSkeleton;
    private Label hintLabel;
    private VBox versionBlock;
    private StackPane pickerRow;
    private Skeleton versionSkeleton;
    private HBox noticeRow;
    private Label noticeText;
    private VBox errorHolder;
    private VBox packBlock;
    private VBox progressBox;
    private HBox statusRow;
    private VBox logBox;
    // left rail / right column (Valorant-style stage)
    private Label railModeLabel, railSubLabel;
    private FlowPane chipsRow;
    private Label typeChip, installChip;
    private AvButton railRefresh;
    private VBox sideColumn;
    private Label sideModpackBig, sideModpackSub, sideDownloadBig, sideDownloadSub;
    private final List<String> modpackNames = new ArrayList<>();
    // player card (left) and rank card (right)
    private StackPane avatarBox;
    private Label avatarName, avatarKind;
    private String avatarKey = "";
    private static final Map<String, Image> HEAD_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private VBox rankCard;
    private final RankListener rankListener = new RankListener() {
        @Override public void onChanged(RankSnapshot snap) { Platform.runLater(() -> renderRank(snap)); }
    };
    private VBox factsBox;
    private static final int FACTS = 6;
    private final Label[] factKey = new Label[FACTS];
    private final Label[] factVal = new Label[FACTS];
    /** What we can read about an installed version without the network (cached; read off the FX thread). */
    private record VersionInfo(int javaMajor, long jarBytes, String assets) {}
    private final java.util.Map<String, VersionInfo> infoCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<String> infoPending = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile int modsCount = -1;

    private VBox modpacksPane;
    private VBox browserPane;
    private VBox downloadsPane;
    private final StackPane tabContent = new StackPane();

    private boolean stale;

    public PlayPage(AppContext appContext) {
        this(appContext, null);
    }

    public PlayPage(AppContext appContext, Consumer<NavPage> navigator) {
        this.appContext = appContext;
        this.navigator = navigator;
        // Register as the global download listener so any install pipeline (Play, Mods, the
        // version browser) feeds the Downloads tab.
        appContext.launchService.getDownloadManager().setGlobalTaskListener(this::onGlobalDownloadTask);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Build
    // ─────────────────────────────────────────────────────────────────────────

    public VBox build() {
        // Direct children are the page's sections so PageTransition can stagger them in.
        VBox root = new VBox();
        root.getStyleClass().addAll("av-page-body", "gap-16");

        PageHeader header = new PageHeader("Play", "Pick a version or a modpack, then launch.");
        HBox tabBar = buildTabBar();
        launchPanel = buildLaunchPanel();

        modpacksPane  = buildModpacksPane();
        browserPane   = buildBrowserPane();
        downloadsPane = buildDownloadsPane();

        root.getChildren().addAll(header, tabBar, launchPanel, tabContent);

        // Cached page: refresh in place when it is shown again.
        root.sceneProperty().addListener((o, was, now) -> {
            if (now == null) {
                RankService.get().removeListener(rankListener);
            } else {
                RankService.get().addListener(rankListener);
                renderRank(RankService.get().snapshot());
                refreshPlayer();
            }
        });
        root.sceneProperty().addListener((o, was, now) -> {
            if (now == null) {
                stale = true;
            } else if (stale) {
                stale = false;
                onReattach();
            }
        });

        refreshChrome();
        renderRank(RankService.get().snapshot());
        loadVersions();
        loadModpacks();
        return root;
    }

    /**
     * Shown again after leaving the page. Home may have changed the default version, the player may
     * have installed a modded version or a modpack elsewhere, or the Minecraft folder may have
     * changed. A launch that is in progress is never disturbed.
     */
    private void onReattach() {
        if (phase == Phase.LAUNCHING) return;
        infoCache.clear();
        if (versionsFailed) {
            loadVersions();                       // try the manifest again
        } else if (!versionsLoading) {
            allVersions.removeIf(v -> "local-modded".equals(v.type));
            scanLocalModdedVersions();
            applyVersionFilter();                 // re-selects settings.defaultVersionId
            applyBrowserFilter();
        }
        loadModpacks();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tabs
    // ─────────────────────────────────────────────────────────────────────────

    private HBox buildTabBar() {
        HBox bar = new HBox();
        bar.getStyleClass().add("av-tabs");
        bar.setAlignment(Pos.CENTER);
        ToggleGroup group = new ToggleGroup();
        for (Object[] def : new Object[][]{
                {Tab.VERSIONS, "VERSIONS"}, {Tab.MODPACKS, "MODPACKS"},
                {Tab.BROWSER, "ALL VERSIONS"}, {Tab.DOWNLOADS, "DOWNLOADS"}}) {
            Tab tab = (Tab) def[0];
            ToggleButton b = new ToggleButton((String) def[1]);
            b.getStyleClass().add("av-tab");
            b.setToggleGroup(group);
            b.setOnAction(e -> switchTab(tab));
            // A ToggleGroup lets the selected button be clicked off; keep one tab always selected.
            b.selectedProperty().addListener((o, was, now) -> {
                if (!now && group.getSelectedToggle() == null) b.setSelected(true);
            });
            tabButtons.put(tab, b);
            bar.getChildren().add(b);
        }
        tabButtons.get(Tab.VERSIONS).setSelected(true);
        return bar;
    }

    private void selectTab(Tab tab) {
        tabButtons.get(tab).setSelected(true);
        switchTab(tab);
    }

    private void switchTab(Tab tab) {
        activeTab = tab;
        Pane pane = switch (tab) {
            case VERSIONS  -> null;
            case MODPACKS  -> modpacksPane;
            case BROWSER   -> browserPane;
            case DOWNLOADS -> downloadsPane;
        };
        if (pane != null) {
            tabContent.getChildren().setAll(pane);
            pane.setOpacity(0);
            Motion.animate(pane.opacityProperty(), 1.0, Motion.PANEL);
        } else {
            tabContent.getChildren().clear();
        }
        statusLabel.setText("");
        refreshChrome();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Launch panel
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The launch "stage": full-bleed art with a left rail (mode tile + filters), a tall centre card that
     * holds the selected version or modpack, a right column of live summaries, and the PLAY button at the
     * bottom centre. Composition is inspired by the Riot client's play screen; the art, shapes and every
     * slot are ours and show only real launcher data. The game log sits below the stage.
     */
    private VBox buildLaunchPanel() {
        StackPane stage = new StackPane();
        stage.getStyleClass().add("play-stage");

        Region art = new Region();
        art.getStyleClass().add("play-stage-art");
        Region scrim = new Region();
        scrim.getStyleClass().add("play-stage-scrim");

        // ── centre card ──
        eyebrow = new Label("MINECRAFT VERSION");
        eyebrow.getStyleClass().add("play-eyebrow");

        titleLabel = new Label();
        titleLabel.getStyleClass().add("play-title");
        titleLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        titleLabel.setMaxWidth(Double.MAX_VALUE);
        titleLabel.setAlignment(Pos.CENTER);
        titleSkeleton = Skeleton.block(220, 30);
        StackPane titleSlot = new StackPane(titleLabel, titleSkeleton);
        titleSlot.setAlignment(Pos.CENTER);

        hintLabel = new Label();
        hintLabel.getStyleClass().add("play-hint");
        hintLabel.setWrapText(true);
        hintLabel.setAlignment(Pos.CENTER);
        hintLabel.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        hintLabel.setMaxWidth(Double.MAX_VALUE);

        versionBlock = buildVersionBlock();
        VBox.setMargin(versionBlock, new Insets(8, 0, 0, 0));
        packBlock = buildPackBlock();
        VBox.setMargin(packBlock, new Insets(8, 0, 0, 0));
        typeChip = new Label();
        typeChip.getStyleClass().add("av-chip");
        installChip = new Label();
        installChip.getStyleClass().add("av-chip");
        chipsRow = new FlowPane(6, 6, typeChip, installChip);
        chipsRow.setAlignment(Pos.CENTER);
        factsBox = buildFacts();
        VBox.setMargin(factsBox, new Insets(10, 0, 0, 0));
        progressBox = buildProgressBox();
        VBox.setMargin(progressBox, new Insets(8, 0, 0, 0));
        statusRow = buildStatusRow();
        VBox.setMargin(statusRow, new Insets(4, 0, 0, 0));

        VBox card = new VBox(6, eyebrow, titleSlot, chipsRow, hintLabel, versionBlock, packBlock, factsBox, progressBox, statusRow);
        card.getStyleClass().add("play-card");
        card.setAlignment(Pos.TOP_CENTER);
        card.setMinWidth(260);
        card.setPrefWidth(350);
        card.setMaxWidth(380);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        // Chamfered "V" bottom edge, kept in sync with the card size (static: no animation).
        Polygon cut = new Polygon();
        InvalidationListener recut = o -> {
            double w = card.getWidth(), h = card.getHeight(), c = Math.min(30, h / 4);
            if (w <= 0 || h <= 0) return;
            cut.getPoints().setAll(0.0, 0.0, w, 0.0, w, h - c, w / 2, h, 0.0, h - c);
        };
        card.widthProperty().addListener(recut);
        card.heightProperty().addListener(recut);
        card.setClip(cut);

        // ── left rail ──
        railModeLabel = new Label("VERSIONS");
        railModeLabel.getStyleClass().add("play-rail-mode");
        railSubLabel = new Label();
        railSubLabel.getStyleClass().add("play-rail-sub");
        railSubLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        VBox tileText = new VBox(0, railModeLabel, railSubLabel);
        tileText.getStyleClass().add("play-rail-strip");
        tileText.setMaxHeight(Region.USE_PREF_SIZE);
        // Player card: the active account's head, name and account kind, with the current mode underneath.
        avatarBox = new StackPane();
        avatarBox.getStyleClass().add("play-avatar");
        avatarBox.setMinSize(88, 88);
        avatarBox.setPrefSize(88, 88);
        avatarBox.setMaxSize(88, 88);
        avatarName = new Label();
        avatarName.getStyleClass().add("play-avatar-name");
        avatarName.setTextOverrun(OverrunStyle.ELLIPSIS);
        avatarName.setMaxWidth(Double.MAX_VALUE);
        avatarName.setAlignment(Pos.CENTER);
        avatarKind = new Label();
        avatarKind.getStyleClass().add("play-avatar-kind");
        avatarKind.setTextOverrun(OverrunStyle.ELLIPSIS);
        avatarKind.setMaxWidth(Double.MAX_VALUE);
        avatarKind.setAlignment(Pos.CENTER);
        VBox who = new VBox(8, avatarBox, avatarName, avatarKind);
        who.setAlignment(Pos.CENTER);
        who.setPadding(new Insets(18, 12, 14, 12));
        tileText.setAlignment(Pos.CENTER_LEFT);
        VBox modeTile = new VBox(who, tileText);
        modeTile.getStyleClass().add("play-rail-tile");
        modeTile.setCursor(javafx.scene.Cursor.HAND);
        modeTile.setFocusTraversable(true);
        modeTile.setAccessibleText("Playing account, open accounts");
        modeTile.setOnMouseClicked(e -> { if (navigator != null) navigator.accept(NavPage.ACCOUNTS); });
        modeTile.setOnKeyPressed(e -> {
            if ((e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) && navigator != null) navigator.accept(NavPage.ACCOUNTS);
        });

        for (ToggleButton t : new ToggleButton[]{showSnapshotsBox, showModdedBox}) {
            t.getStyleClass().add("play-rail-btn");
            t.setMaxWidth(Double.MAX_VALUE);
            t.setAlignment(Pos.CENTER_LEFT);
        }
        railRefresh = new AvButton("Refresh versions", AvButton.Kind.GHOST);
        railRefresh.getStyleClass().add("play-rail-btn");
        railRefresh.setMaxWidth(Double.MAX_VALUE);
        railRefresh.setAlignment(Pos.CENTER_LEFT);
        railRefresh.setOnAction(e -> { if (activeTab == Tab.MODPACKS) loadModpacks(); else loadVersions(); });
        AvButton railDownloads = new AvButton("Downloads", AvButton.Kind.GHOST);
        railDownloads.getStyleClass().add("play-rail-btn");
        railDownloads.setMaxWidth(Double.MAX_VALUE);
        railDownloads.setAlignment(Pos.CENTER_LEFT);
        railDownloads.setOnAction(e -> selectTab(Tab.DOWNLOADS));

        VBox rail = new VBox(8, modeTile, showSnapshotsBox, showModdedBox, railRefresh, railDownloads);
        rail.setMinWidth(210);
        rail.setPrefWidth(210);
        rail.setMaxWidth(210);

        // ── right column: real summaries only ──
        sideModpackBig = new Label();
        sideModpackSub = new Label();
        sideDownloadBig = new Label();
        sideDownloadSub = new Label();
        rankCard = new VBox(6);
        rankCard.getStyleClass().add("play-rank");
        rankCard.setAlignment(Pos.TOP_CENTER);
        rankCard.setCursor(javafx.scene.Cursor.HAND);
        rankCard.setFocusTraversable(true);
        rankCard.setAccessibleText("Global rank, open ranks");
        rankCard.setOnMouseClicked(e -> { if (navigator != null) navigator.accept(NavPage.RANKS); });
        rankCard.setOnKeyPressed(e -> {
            if ((e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) && navigator != null) navigator.accept(NavPage.RANKS);
        });
        sideColumn = new VBox(8,
                rankCard,
                sideCard("MODPACKS", sideModpackBig, sideModpackSub, () -> selectTab(Tab.MODPACKS)),
                sideCard("DOWNLOADS", sideDownloadBig, sideDownloadSub, () -> selectTab(Tab.DOWNLOADS)));
        sideColumn.setMinWidth(210);
        sideColumn.setPrefWidth(210);
        sideColumn.setMaxWidth(210);

        BorderPane content = new BorderPane();
        content.getStyleClass().add("play-stage-content");
        content.setLeft(rail);
        // PLAY lives in the same column as the card, so it is always exactly centred under it
        // (its glow is a separate node pulsed by Motion.pulse).
        playButton.getStyleClass().add("play-go");
        playButton.setAccessibleText("Play Minecraft");
        playButton.setDisable(true);
        playButton.setOnAction(e -> onPlayClicked());
        Region glow = new Region();
        glow.getStyleClass().add("play-go-glow");
        glow.setMouseTransparent(true);
        glow.visibleProperty().bind(playButton.disabledProperty().not());
        StackPane.setMargin(glow, new Insets(-36, -60, -36, -60));
        Motion.pulse(glow, 0.35, 0.9, Duration.millis(1400));
        StackPane playBox = new StackPane(glow, playButton);
        playBox.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        VBox centreCol = new VBox(card, playBox);
        centreCol.setAlignment(Pos.TOP_CENTER);
        centreCol.setPickOnBounds(false);
        VBox.setMargin(playBox, new Insets(-30, 0, 0, 0));   // the card's V tip dips behind the button

        Region spot = new Region();            // soft warm spotlight behind the card
        spot.getStyleClass().add("play-spot");
        spot.setMouseTransparent(true);
        spot.setMaxSize(560, 560);
        StackPane centre = new StackPane(spot, centreCol);
        StackPane.setAlignment(spot, Pos.TOP_CENTER);
        StackPane.setAlignment(centreCol, Pos.TOP_CENTER);
        centre.setPickOnBounds(false);
        content.setCenter(centre);
        BorderPane.setAlignment(centre, Pos.TOP_CENTER);
        content.setRight(sideColumn);
        BorderPane.setAlignment(rail, Pos.TOP_LEFT);
        BorderPane.setAlignment(sideColumn, Pos.TOP_RIGHT);
        // Narrow window: drop the right column so the card keeps its room.
        stage.widthProperty().addListener((o, was, w) -> {
            double x = w.doubleValue();
            setShown(sideColumn, x >= 900);
        });

        stage.getChildren().addAll(art, scrim, content);

        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(stage.widthProperty());
        clip.heightProperty().bind(stage.heightProperty());
        stage.setClip(clip);

        // ── game log (shown once Minecraft is running; below the stage) ──
        Label logTitle = new Label("GAME LOG");
        logTitle.getStyleClass().add("av-section-title");
        outputArea.getStyleClass().add("play-log");
        outputArea.setEditable(false);
        outputArea.setPrefRowCount(9);
        outputArea.setAccessibleText("Game log");
        logBox = new VBox(8, logTitle, outputArea);
        setShown(logBox, false);

        VBox panel = new VBox(16, stage, logBox);
        return panel;
    }

    /** Six key/value cells (3x2) under the picker, filled from real state in refreshChrome. */
    private VBox buildFacts() {
        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        for (int c = 0; c < 3; c++) {
            javafx.scene.layout.ColumnConstraints cc = new javafx.scene.layout.ColumnConstraints();
            cc.setPercentWidth(100.0 / 3);
            grid.getColumnConstraints().add(cc);
        }
        for (int i = 0; i < FACTS; i++) {
            factKey[i] = new Label();
            factKey[i].getStyleClass().add("play-fact-key");
            factVal[i] = new Label();
            factVal[i].getStyleClass().add("play-fact-val");
            factVal[i].setTextOverrun(OverrunStyle.ELLIPSIS);
            factVal[i].setMaxWidth(Double.MAX_VALUE);
            VBox cell = new VBox(1, factKey[i], factVal[i]);
            cell.getStyleClass().add("play-fact-cell");
            cell.setAlignment(Pos.CENTER_LEFT);
            grid.add(cell, i % 3, i / 3);
        }
        VBox box = new VBox(grid);
        box.getStyleClass().add("play-facts");
        return box;
    }

    // ── local version info (Java requirement, jar size, mods folder), read in the background ──

    private void requestInfo(String id) {
        if (id == null || infoCache.containsKey(id) || !infoPending.add(id)) return;
        appContext.launchService.getBackgroundExecutor().execute(() -> {
            VersionInfo info = readVersionInfo(id);
            int mods = countMods();
            infoCache.put(id, info);
            infoPending.remove(id);
            modsCount = mods;
            Platform.runLater(this::refreshChrome);
        });
    }

    private VersionInfo readVersionInfo(String id) {
        String mc = appContext.settings.minecraftDirectory;
        if (mc == null || mc.isBlank()) return new VersionInfo(0, -1, null);
        int java = 0;
        long jar = -1;
        String assets = null;
        try {
            Path dir = Path.of(mc, "versions", id);
            Path jarPath = dir.resolve(id + ".jar");
            if (Files.exists(jarPath)) jar = Files.size(jarPath);
            com.google.gson.JsonObject o = com.google.gson.JsonParser
                    .parseString(Files.readString(dir.resolve(id + ".json"))).getAsJsonObject();
            java = javaMajorOf(o);
            if (o.has("assets")) assets = o.get("assets").getAsString();
            // Mod-loader versions inherit from a vanilla version: borrow what they do not define.
            if (o.has("inheritsFrom")) {
                String parent = o.get("inheritsFrom").getAsString();
                Path pdir = Path.of(mc, "versions", parent);
                if (jar < 0 && Files.exists(pdir.resolve(parent + ".jar"))) jar = Files.size(pdir.resolve(parent + ".jar"));
                if (java == 0 || assets == null) {
                    com.google.gson.JsonObject po = com.google.gson.JsonParser
                            .parseString(Files.readString(pdir.resolve(parent + ".json"))).getAsJsonObject();
                    if (java == 0) java = javaMajorOf(po);
                    if (assets == null && po.has("assets")) assets = po.get("assets").getAsString();
                }
            }
        } catch (Exception ignored) {
            // not installed yet (or unreadable): the page just shows dashes
        }
        return new VersionInfo(java, jar, assets);
    }

    private static int javaMajorOf(com.google.gson.JsonObject o) {
        return o.has("javaVersion") && o.get("javaVersion").isJsonObject()
                && o.getAsJsonObject("javaVersion").has("majorVersion")
                ? o.getAsJsonObject("javaVersion").get("majorVersion").getAsInt() : 0;
    }

    private int countMods() {
        String mc = appContext.settings.minecraftDirectory;
        if (mc == null || mc.isBlank()) return -1;
        Path mods = Path.of(mc, "mods");
        if (!Files.isDirectory(mods)) return 0;
        try (Stream<Path> files = Files.list(mods)) {
            return (int) files.filter(f -> f.getFileName().toString().endsWith(".jar")).count();
        } catch (IOException e) {
            return -1;
        }
    }

    private static String sizeText(long bytes) {
        if (bytes < 0) return "—";
        return bytes >= 1024 * 1024 ? String.format("%.1f MB", bytes / 1048576.0) : (bytes / 1024) + " KB";
    }

    private static String accountKind(Account a) {
        if (a == null) return "Offline";
        if (a.type == Account.Type.MICROSOFT) return "Microsoft";
        return a.averoLinked ? "Avero ID" : "Offline";
    }

    private static String memoryText(int mb) {
        return mb % 1024 == 0 ? (mb / 1024) + " GB" : mb + " MB";
    }

    // ── player card (left) ───────────────────────────────────────────────────

    /** Fills the left card from the active account: head image (or a coloured initial), name and kind. */
    private void refreshPlayer() {
        if (avatarBox == null) return;
        Account acct = resolveActiveAccount();
        String name = acct != null && acct.username != null ? acct.username : "No account";
        avatarName.setText(name);
        avatarKind.setText(acct != null ? accountKind(acct).toUpperCase() + " ACCOUNT" : "PLAYING OFFLINE");
        String skinSrc = null;
        if (acct != null && acct.offlineSkinId != null && !acct.offlineSkinId.isBlank()) {
            java.nio.file.Path local = new com.averolauncher.skins.SkinLibrary().pngPath(acct.offlineSkinId);
            if (Files.isRegularFile(local)) skinSrc = local.toUri().toString();
        }
        if (skinSrc == null && acct != null && acct.skinUrl != null && !acct.skinUrl.isBlank()) skinSrc = acct.skinUrl;
        final String src = skinSrc;
        String key = acct == null ? "" : acct.id + "|" + src + "|" + name;
        if (key.equals(avatarKey) && !avatarBox.getChildren().isEmpty()) return;
        avatarKey = key;
        avatarBox.getChildren().setAll(initialTile(name));
        if (src != null) {
            com.averolauncher.ui.kit.SkinFace.load(src, head -> {
                if (!key.equals(avatarKey) || head == null) return;
                ImageView iv = new ImageView(head);   // 160px face drawn at 80px: sharp blocks, also on HiDPI screens
                iv.setFitWidth(80);
                iv.setFitHeight(80);
                iv.setSmooth(true);
                avatarBox.getChildren().setAll(iv);
            });
        }
    }

    /** Fallback avatar: the first letter on a colour derived from the name. */
    private static Label initialTile(String name) {
        String letter = name == null || name.isBlank() ? "?" : name.substring(0, 1).toUpperCase();
        Label l = new Label(letter);
        l.getStyleClass().add("play-avatar-initial");
        return l;
    }

    /** Loads a skin PNG in the background and hands back its 8x8 face (hat layer composited on top). */
    private static void loadHead(String url, Consumer<Image> done) {
        Image cached = HEAD_CACHE.get(url);
        if (cached != null) { done.accept(cached); return; }
        try {
            Image skin = new Image(url, true);
            Runnable finish = () -> {
                if (skin.isError() || skin.getWidth() < 64) { done.accept(null); return; }
                PixelReader pr = skin.getPixelReader();
                if (pr == null) { done.accept(null); return; }
                WritableImage head = new WritableImage(8, 8);
                var pw = head.getPixelWriter();
                for (int y = 0; y < 8; y++) {
                    for (int x = 0; x < 8; x++) {
                        int base = pr.getArgb(8 + x, 8 + y);
                        int hat = pr.getArgb(40 + x, 8 + y);
                        pw.setArgb(x, y, ((hat >>> 24) & 0xFF) > 0 ? hat : base | 0xFF000000);
                    }
                }
                HEAD_CACHE.put(url, head);
                done.accept(head);
            };
            if (skin.getProgress() >= 1.0) finish.run();
            else skin.progressProperty().addListener((o, was, now) -> { if (now.doubleValue() >= 1.0) finish.run(); });
            skin.errorProperty().addListener((o, was, err) -> { if (err) done.accept(null); });
        } catch (Exception ex) {
            done.accept(null);
        }
    }

    // ── rank card (right) ────────────────────────────────────────────────────

    /** Rebuilds the right-hand rank card: badge image, rank name, AP and progress to the next rank. */
    private void renderRank(RankSnapshot snap) {
        if (rankCard == null) return;
        Label kicker = new Label("GLOBAL RANK");
        kicker.getStyleClass().add("av-section-title");
        kicker.setMaxWidth(Double.MAX_VALUE);
        kicker.setAlignment(Pos.CENTER_LEFT);

        RankStanding st = snap.standing();
        RankBadge badge = new RankBadge(st.tier(), st.division(), 92, true);
        if (!snap.signedIn()) badge.setOpacity(0.4);
        String accent = RankBadge.accent(st.tier());

        Label name = new Label(snap.signedIn() ? st.label().toUpperCase() : "UNRANKED");
        name.getStyleClass().add("play-rank-name");
        if (snap.signedIn()) name.setStyle("-fx-text-fill: " + accent + ";");
        name.setMaxWidth(Double.MAX_VALUE);
        name.setAlignment(Pos.CENTER);

        Label detail = new Label();
        detail.getStyleClass().add("play-side-sub");
        detail.setWrapText(true);
        detail.setMaxWidth(Double.MAX_VALUE);
        detail.setAlignment(Pos.CENTER);
        detail.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);

        rankCard.getChildren().setAll(kicker, badge, name);
        if (!snap.signedIn()) {
            detail.setText("Sign in with Avero ID to earn Avero Points from playtime.");
            rankCard.getChildren().add(detail);
        } else {
            Label ap = new Label(String.format(java.util.Locale.US, "%,d AP", snap.ap()));
            ap.getStyleClass().add("play-rank-ap");
            ap.setMaxWidth(Double.MAX_VALUE);
            ap.setAlignment(Pos.CENTER);
            RankBar bar = new RankBar(st.progress(), accent, true);
            VBox.setMargin(bar, new Insets(4, 0, 0, 0));
            detail.setText(st.isMax() ? "Highest rank reached."
                    : String.format(java.util.Locale.US, "%,d AP to %s", st.apToNext(), st.nextLabel()));
            rankCard.getChildren().addAll(ap, bar, detail);
        }
    }

    /** Right-column summary tile: opaque, keyboard-reachable, jumps to the tab it summarises. */
    private VBox sideCard(String title, Label big, Label sub, Runnable open) {
        Label t = new Label(title);
        t.getStyleClass().add("av-section-title");
        big.getStyleClass().add("play-side-big");
        sub.getStyleClass().add("play-side-sub");
        sub.setWrapText(true);
        VBox box = new VBox(4, t, big, sub);
        box.getStyleClass().add("play-side");
        box.setCursor(javafx.scene.Cursor.HAND);
        box.setFocusTraversable(true);
        box.setAccessibleText(title.charAt(0) + title.substring(1).toLowerCase() + " summary, open tab");
        box.setOnMouseClicked(e -> open.run());
        box.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) open.run();
        });
        return box;
    }

    /** Fills the right-column tiles from real state (installed modpacks, download queue). */
    private void refreshSide() {
        if (sideModpackBig == null) return;
        refreshPlayer();
        int n = modpackNames.size();
        sideModpackBig.setText(n == 0 ? "None yet" : n + " installed");
        sideModpackSub.setText(n == 0 ? "Download one from the Mods page."
                : String.join(", ", modpackNames.subList(0, Math.min(3, n))) + (n > 3 ? " +" + (n - 3) : ""));
        long active = trackedDownloads.stream()
                .filter(t -> t.getStatus() == DownloadTask.Status.DOWNLOADING
                        || t.getStatus() == DownloadTask.Status.VERIFYING).count();
        long done = trackedDownloads.stream()
                .filter(t -> t.getStatus() == DownloadTask.Status.COMPLETE).count();
        sideDownloadBig.setText(active > 0 ? active + " active" : "Idle");
        sideDownloadSub.setText(trackedDownloads.isEmpty() ? "Nothing downloaded yet."
                : done + " of " + trackedDownloads.size() + " files complete");
    }

    private VBox buildVersionBlock() {
        versionBox.getStyleClass().add("play-select");
        versionBox.setAccessibleText("Minecraft version");
        versionBox.setPromptText("Select version");
        versionBox.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(VersionManifest.VersionEntry v) {
                return v == null ? "" : v.id + "  (" + v.type + ")";
            }
            @Override public VersionManifest.VersionEntry fromString(String s) { return null; }
        });
        versionBox.valueProperty().addListener((o, was, now) -> refreshChrome());

        showSnapshotsBox.setSelected(false);
        showSnapshotsBox.setOnAction(e -> applyVersionFilter());
        showModdedBox.setSelected(true);
        showModdedBox.setOnAction(e -> applyVersionFilter());

        versionBox.setMaxWidth(Double.MAX_VALUE);
        versionSkeleton = Skeleton.block(0, 44);
        pickerRow = new StackPane(versionBox, versionSkeleton);
        pickerRow.setAlignment(Pos.CENTER);

        // Shown when Mojang's list could not be fetched but local versions can still be played.
        noticeText = new Label();
        noticeText.getStyleClass().add("play-notice-text");
        noticeText.setWrapText(true);
        HBox.setHgrow(noticeText, Priority.ALWAYS);
        retryVersionsButton.getStyleClass().add("play-row-btn");
        retryVersionsButton.setMinWidth(Region.USE_PREF_SIZE);
        retryVersionsButton.setOnAction(e -> loadVersions());
        noticeRow = new HBox(12, noticeText, retryVersionsButton);
        noticeRow.getStyleClass().add("play-notice");
        noticeRow.setAlignment(Pos.CENTER_LEFT);
        noticeRow.setMaxWidth(Double.MAX_VALUE);

        errorHolder = new VBox();
        errorHolder.setAlignment(Pos.CENTER);

        installLoaderButton.getStyleClass().add("play-row-btn");
        installLoaderButton.setMaxWidth(Double.MAX_VALUE);
        installLoaderButton.setAccessibleText("Install Fabric, Quilt, NeoForge or Forge");
        installLoaderButton.setTooltip(new Tooltip("Install Fabric, Quilt, NeoForge or Forge for a Minecraft version"));
        installLoaderButton.setOnAction(e -> openLoaderInstaller());

        return new VBox(12, pickerRow, installLoaderButton, noticeRow, errorHolder);
    }

    /** Opens the built-in mod loader installer. */
    private void openLoaderInstaller() {
        if (loaderService == null) loaderService = new LoaderService(appContext.launchService);
        List<String> releases = allVersions.stream()
                .filter(v -> "release".equals(v.type))
                .sorted((a, b) -> String.valueOf(b.releaseTime).compareTo(String.valueOf(a.releaseTime)))
                .map(v -> v.id)
                .toList();
        VersionManifest.VersionEntry cur = versionBox.getValue();
        String preferred = cur != null && "release".equals(cur.type) ? cur.id : null;
        LoaderInstallDialog.show(versionBox.getScene() != null ? versionBox.getScene().getWindow() : null,
                loaderService, appContext.settings, releases, preferred, this::onLoaderInstalled);
    }

    /** A loader finished installing: refresh the lists and select it so Play launches it. */
    private void onLoaderInstalled(String id) {
        showModdedBox.setSelected(true);
        scanLocalModdedVersions();
        applyVersionFilter();
        applyBrowserFilter();
        for (VersionManifest.VersionEntry v : versionBox.getItems()) {
            if (v.id.equals(id)) { versionBox.setValue(v); break; }
        }
    }

    private VBox buildPackBlock() {
        Label mc = new Label("MINECRAFT");
        mc.getStyleClass().add("av-chip");
        modpackVersionLabel.getStyleClass().add("av-chip");
        HBox row = new HBox(8, mc, modpackVersionLabel);
        row.setAlignment(Pos.CENTER);
        return new VBox(row);
    }

    private VBox buildProgressBox() {
        launchFilesLabel.getStyleClass().add("play-count");
        AvButton viewDownloads = new AvButton("View downloads", AvButton.Kind.GHOST);
        viewDownloads.getStyleClass().add("play-row-btn");
        viewDownloads.setOnAction(e -> selectTab(Tab.DOWNLOADS));
        HBox actions = new HBox(12, viewDownloads, launchFilesLabel);
        actions.setAlignment(Pos.CENTER);
        VBox box = new VBox(10, progress, actions);
        return box;
    }

    private HBox buildStatusRow() {
        statusLabel.getStyleClass().add("play-status");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(statusLabel, Priority.ALWAYS);
        // While launching, status messages become the progress step label ("Checking files for 1.21").
        statusLabel.textProperty().addListener((o, was, now) -> {
            if (phase == Phase.LAUNCHING && now != null && !now.isBlank()) progress.setStep(now);
            refreshChrome();
        });
        phaseChip.getStyleClass().add("av-chip");
        retryLaunchButton.getStyleClass().add("play-row-btn");
        retryLaunchButton.setOnAction(e -> onPlayClicked());
        HBox row = new HBox(10, phaseChip, statusLabel, retryLaunchButton);
        row.setAlignment(Pos.CENTER);
        return row;
    }

    /** Derives everything the panel shows from the current state. Cheap; call after any state change. */
    private void refreshChrome() {
        if (launchPanel == null) return;
        boolean launching = phase == Phase.LAUNCHING;
        boolean packTab = activeTab == Tab.MODPACKS;
        boolean launchTab = activeTab == Tab.VERSIONS || packTab;
        boolean noVersions = versionsFailed && allVersions.isEmpty();

        setShown(launchPanel, launchTab);
        setShown(tabContent, activeTab != Tab.VERSIONS);

        // ── title / hint ──
        eyebrow.setText(packTab ? "MODPACK" : "MINECRAFT VERSION");
        boolean titleLoading = !packTab && versionsLoading;
        String title;
        if (packTab) {
            title = selectedModpack != null ? selectedModpack.displayName : "Select a modpack";
        } else if (titleLoading) {
            title = "";
        } else if (noVersions) {
            title = "No versions available";
        } else {
            VersionManifest.VersionEntry v = versionBox.getValue();
            title = v == null ? "Select a version" : ("local-modded".equals(v.type) ? v.id : "Minecraft " + v.id);
        }
        titleLabel.setText(title);
        titleLabel.setVisible(!titleLoading);
        titleSkeleton.setVisible(titleLoading);
        hintLabel.setText("Modpacks you download from the Mods page appear below. Pick one, then press Play.");
        setShown(hintLabel, packTab && selectedModpack == null);

        // ── picker ──
        setShown(versionBlock, !packTab);
        setShown(packBlock, packTab && selectedModpack != null);
        boolean pickerVisible = !noVersions;
        setShown(pickerRow, pickerVisible);
        versionSkeleton.setVisible(versionsLoading);
        versionBox.setVisible(!versionsLoading);
        versionBox.setDisable(launching);
        installLoaderButton.setDisable(launching);
        setShown(installLoaderButton, pickerVisible && !versionsLoading);
        showSnapshotsBox.setDisable(launching || versionsLoading);
        showModdedBox.setDisable(launching || versionsLoading);
        setShown(noticeRow, versionsFailed && !allVersions.isEmpty() && !versionsLoading);
        setShown(errorHolder, noVersions && !versionsLoading);

        // ── PLAY ──
        boolean canPlay = !launching && (activeTab == Tab.VERSIONS
                ? versionBox.getValue() != null
                : packTab && selectedModpack != null && selectedModpackVersion != null);
        playButton.setDisable(!canPlay);

        // ── progress + status ──
        setShown(progressBox, launching);
        boolean hasStatus = statusLabel.getText() != null && !statusLabel.getText().isBlank() && !launching;
        statusLabel.setVisible(hasStatus);
        statusLabel.setManaged(hasStatus);
        boolean showRetry = launchFailed && !launching;
        setShown(retryLaunchButton, showRetry);

        String chip = null;
        String chipClass = null;
        if (launching)                      { chip = "LAUNCHING"; chipClass = "play-chip-ice"; }
        else if (phase == Phase.RUNNING)    { chip = "RUNNING";   chipClass = "play-chip-ok"; }
        else if (launchFailed)              { chip = "FAILED";    chipClass = "play-chip-err"; }
        phaseChip.getStyleClass().setAll("av-chip");
        if (chipClass != null) phaseChip.getStyleClass().add(chipClass);
        if (chip != null) phaseChip.setText(chip);
        setShown(phaseChip, chip != null);
        setShown(statusRow, chip != null || hasStatus);

        setShown(logBox, logShown);

        // rail: mode tile label, filters (versions tab only), refresh target
        VersionManifest.VersionEntry cur = versionBox.getValue();
        railModeLabel.setText(packTab
                ? (selectedModpack != null ? selectedModpack.type.toUpperCase() : "MODPACK")
                : (cur == null ? "VERSIONS" : ("local-modded".equals(cur.type) ? "MODDED" : cur.type.toUpperCase())));
        setShown(showSnapshotsBox, !packTab);
        setShown(showModdedBox, !packTab);
        railRefresh.setText(packTab ? "Refresh modpacks" : "Refresh versions");
        refreshSide();

        // chips + facts under the picker (real data only)
        String infoId = packTab ? (selectedModpackVersion != null ? selectedModpackVersion.id : null)
                : (cur != null ? cur.id : null);
        requestInfo(infoId);
        VersionInfo info = infoId != null ? infoCache.get(infoId) : null;
        boolean installed = infoId != null && isVersionInstalled(infoId);

        String type = packTab ? (selectedModpack != null ? selectedModpack.type.toUpperCase() : null)
                : (cur == null ? null : ("local-modded".equals(cur.type) ? "MODDED" : cur.type.toUpperCase()));
        typeChip.setText(type == null ? "" : type);
        installChip.setText(infoId == null ? "" : installed ? "INSTALLED" : "NEEDS DOWNLOAD");
        installChip.getStyleClass().setAll("av-chip", installed ? "play-chip-ok" : "play-chip-warn");
        setShown(typeChip, type != null);
        setShown(installChip, infoId != null);
        setShown(chipsRow, !noVersions && !versionsLoading && (type != null || infoId != null));

        String javaText = info != null && info.javaMajor() > 0 ? "Java " + info.javaMajor()
                : (appContext.settings.javaPath == null || appContext.settings.javaPath.isBlank() ? "Auto" : "Custom");
        String sizeText = info != null ? sizeText(info.jarBytes()) : "—";
        String modsText = modsCount < 0 ? "—" : String.valueOf(modsCount);
        String memory = memoryText(appContext.settings.maxRamMb);
        String parallel = appContext.settings.concurrentDownloads + " at once";
        String[][] facts;
        if (packTab) {
            long packBytes = -1;
            try {
                if (selectedModpack != null && Files.isRegularFile(selectedModpack.path)) packBytes = Files.size(selectedModpack.path);
            } catch (IOException ignored) { }
            facts = new String[][]{
                    {"MINECRAFT", selectedModpackVersion != null ? selectedModpackVersion.id : "—"},
                    {"JAVA", javaText}, {"PACK SIZE", sizeText(packBytes)},
                    {"MEMORY", memory}, {"MODS FOLDER", modsText}, {"DOWNLOADS", parallel}};
        } else {
            String released = "—";
            if (cur != null) {
                released = (cur.releaseTime != null && cur.releaseTime.length() >= 10)
                        ? cur.releaseTime.substring(0, 10) : "Local install";
            }
            facts = new String[][]{
                    {"RELEASED", released}, {"JAVA", javaText}, {"JAR SIZE", sizeText},
                    {"MEMORY", memory}, {"MODS FOLDER", modsText}, {"DOWNLOADS", parallel}};
        }
        for (int i = 0; i < FACTS; i++) { factKey[i].setText(facts[i][0]); factVal[i].setText(facts[i][1]); }
        railSubLabel.setText(packTab ? (selectedModpack != null ? selectedModpack.displayName : "")
                : (cur != null ? cur.id : ""));
        setShown(factsBox, !noVersions && !versionsLoading && !launching);
    }

    private static void setShown(Node n, boolean shown) {
        n.setVisible(shown);
        n.setManaged(shown);
    }

    private static ToggleButton filterToggle(String text) {
        ToggleButton t = new ToggleButton(text);
        t.getStyleClass().add("play-filter");
        t.setAccessibleText(text.charAt(0) + text.substring(1).toLowerCase() + " filter");
        return t;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Modpacks pane
    // ─────────────────────────────────────────────────────────────────────────

    private VBox buildModpacksPane() {
        modpackStatusLabel.getStyleClass().add("play-count");
        modpackStatusLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(modpackStatusLabel, Priority.ALWAYS);

        AvButton refreshBtn = new AvButton("Refresh", AvButton.Kind.GHOST);
        refreshBtn.getStyleClass().add("play-row-btn");
        refreshBtn.setAccessibleText("Refresh modpacks");
        refreshBtn.setOnAction(e -> loadModpacks());

        HBox toolRow = new HBox(10, modpackStatusLabel, refreshBtn);
        toolRow.setAlignment(Pos.CENTER_LEFT);

        modpackGrid.getStyleClass().add("gap-16");
        modpackBody.getStyleClass().add("gap-16");

        VBox pane = new VBox(toolRow, modpackBody);
        pane.getStyleClass().add("gap-12");
        return pane;
    }

    private VBox buildBrowserPane() {
        browserReleases.setSelected(true);
        browserSnapshots.setSelected(false);
        browserModded.setSelected(true);
        browserReleases.selectedProperty().addListener((o, was, now) -> applyBrowserFilter());
        browserSnapshots.selectedProperty().addListener((o, was, now) -> applyBrowserFilter());
        browserModded.selectedProperty().addListener((o, was, now) -> applyBrowserFilter());

        FlowPane filters = new FlowPane(browserReleases, browserSnapshots, browserModded);
        filters.getStyleClass().add("gap-8");
        filters.setAlignment(Pos.CENTER_LEFT);

        browserStatusLabel.getStyleClass().add("play-count");
        browserStatusLabel.setWrapText(true);
        browserStatusLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(browserStatusLabel, Priority.ALWAYS);

        AvButton refresh = new AvButton("Refresh", AvButton.Kind.GHOST);
        refresh.getStyleClass().add("play-row-btn");
        refresh.setAccessibleText("Refresh version list");
        refresh.setMinWidth(Region.USE_PREF_SIZE);
        refresh.setOnAction(e -> loadVersions());

        Region spring = new Region();
        HBox.setHgrow(spring, Priority.SOMETIMES);
        HBox filterRow = new HBox(16, filters, browserStatusLabel, refresh);
        filterRow.setAlignment(Pos.CENTER_LEFT);

        browserList.getStyleClass().add("play-list");
        browserList.setCellFactory(lv -> new BrowserVersionCell());
        browserList.setPlaceholder(skeletonRows());
        browserList.setPrefHeight(420);
        browserList.setAccessibleText("Minecraft versions");

        VBox pane = new VBox(filterRow, browserList);
        pane.getStyleClass().add("gap-12");
        return pane;
    }

    /** Skeleton stand-in for a list that is still loading. */
    private static VBox skeletonRows() {
        VBox box = new VBox();
        box.getStyleClass().addAll("gap-12", "pad-16");
        for (int i = 0; i < 6; i++) box.getChildren().add(Skeleton.block(0, 40));
        return box;
    }

        private void applyBrowserFilter() {
        List<VersionManifest.VersionEntry> filtered = allVersions.stream()
                .filter(v -> (browserReleases.isSelected() && "release".equals(v.type))
                        || (browserSnapshots.isSelected() && "snapshot".equals(v.type))
                        || (browserModded.isSelected() && "local-modded".equals(v.type)))
                .sorted((a, b) -> {
                    if ("local-modded".equals(a.type) && !"local-modded".equals(b.type)) return -1;
                    if (!"local-modded".equals(a.type) && "local-modded".equals(b.type)) return 1;
                    String ta = a.releaseTime != null ? a.releaseTime : "";
                    String tb = b.releaseTime != null ? b.releaseTime : "";
                    return tb.compareTo(ta);
                })
                .toList();
        browserList.setItems(FXCollections.observableArrayList(filtered));
    }

        private boolean isVersionInstalled(String versionId) {
        String mcDir = appContext.settings.minecraftDirectory;
        if (mcDir == null) return false;
        Path jar = Path.of(mcDir, "versions", versionId, versionId + ".jar");
        // mod-loader versions have no own jar — check for the version JSON instead
        Path json = Path.of(mcDir, "versions", versionId, versionId + ".json");
        return Files.exists(jar) || Files.exists(json);
    }

    private class BrowserVersionCell extends ListCell<VersionManifest.VersionEntry> {
        @Override
        protected void updateItem(VersionManifest.VersionEntry entry, boolean empty) {
            super.updateItem(entry, empty);
            if (empty || entry == null) { setGraphic(null); setText(null); return; }

            boolean isModded = "local-modded".equals(entry.type);
            boolean installed = isVersionInstalled(entry.id);

            Label idLabel = new Label(entry.id);
            idLabel.getStyleClass().add("play-row-id");
            idLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
            idLabel.setMinWidth(120);
            idLabel.setPrefWidth(220);

            Label typeLabel = new Label(isModded ? "MODDED" : entry.type.toUpperCase());
            typeLabel.getStyleClass().add("av-chip");

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            Label statusTag = new Label();
            setInstalledChip(statusTag, installed);

            AvButton actionBtn;
            if (installed) {
                actionBtn = new AvButton("Play", AvButton.Kind.GHOST);
                actionBtn.setOnAction(e -> launchFromBrowser(entry));
            } else if (isModded) {
                actionBtn = new AvButton("Not installable here", AvButton.Kind.GHOST);
                actionBtn.setDisable(true);
                Tooltip.install(actionBtn, new Tooltip("Run the mod loader's own installer (Fabric Installer, Forge Installer, etc) to install this version. Then come back and launch it here."));
            } else {
                actionBtn = new AvButton("Install", AvButton.Kind.GHOST);
                actionBtn.setOnAction(e -> installFromBrowser(entry, actionBtn, statusTag));
            }
            actionBtn.getStyleClass().add("play-row-btn");
            actionBtn.setAccessibleText((installed ? "Play " : "Install ") + entry.id);

            HBox row = new HBox(12, idLabel, typeLabel, spacer, statusTag, actionBtn);
            row.getStyleClass().add("play-row");
            row.setAlignment(Pos.CENTER_LEFT);
            setText(null);
            setGraphic(row);
        }
    }

    private static void setInstalledChip(Label chip, boolean installed) {
        chip.setText(installed ? "INSTALLED" : "NOT INSTALLED");
        chip.getStyleClass().setAll("av-chip");
        if (installed) chip.getStyleClass().add("play-chip-ok");
    }

    private void installFromBrowser(VersionManifest.VersionEntry entry, AvButton btn, Label statusTag) {
        if (!guardBrowserMinecraftDir()) return;
        btn.setDisable(true);
        btn.setText("Installing…");
        browserStatusLabel.setText("Installing " + entry.id + "…");

        appContext.launchService.installAndLaunch(
                entry, appContext.settings, null,
                resolveActiveAccount(), appContext.microsoftAuthService,
                task -> {}, line -> {}, msg -> Platform.runLater(() -> browserStatusLabel.setText(msg))
        ).whenComplete((proc, ex) -> Platform.runLater(() -> {
            if (ex != null) {
                btn.setDisable(false);
                btn.setText("Install");
                browserStatusLabel.setText("Install failed: " + rootMessage(ex));
                AppLogger.get().error(TAG, "Install failed for " + entry.id, ex);
            } else {
                // Kill the game immediately — user just wanted to install, not play
                proc.destroyForcibly();
                btn.setText("Play");
                btn.setDisable(false);
                setInstalledChip(statusTag, true);
                browserStatusLabel.setText(entry.id + " installed.");
                btn.setOnAction(e2 -> launchFromBrowser(entry));
            }
        }));
    }

        private void launchFromBrowser(VersionManifest.VersionEntry entry) {
        if (!guardBrowserMinecraftDir()) return;
        browserStatusLabel.setText("Launching " + entry.id + "…");
        appContext.settings.defaultVersionId = entry.id;
        appContext.settingsRepository.save(appContext.settings);

        CompletableFuture<Process> launch;
        if ("local-modded".equals(entry.type)) {
            launch = com.averolauncher.store.CapeSupport.prepare(appContext.settings, entry.id, resolveActiveAccount())
                    .thenCompose(ignored -> appContext.launchService.installAndLaunchLocal(
                            entry.id, appContext.settings, null,
                            resolveActiveAccount(), appContext.microsoftAuthService,
                            task -> {}, line -> {}, msg -> Platform.runLater(() -> browserStatusLabel.setText(msg))));
        } else {
            launch = appContext.launchService.installAndLaunch(
                    entry, appContext.settings, null,
                    resolveActiveAccount(), appContext.microsoftAuthService,
                    task -> {}, line -> {}, msg -> Platform.runLater(() -> browserStatusLabel.setText(msg)));
        }
        launch.whenComplete((proc, ex) -> Platform.runLater(() -> {
            if (ex != null) browserStatusLabel.setText("Launch failed: " + rootMessage(ex));
            else browserStatusLabel.setText(entry.id + " running (PID " + proc.pid() + ")");
        }));
    }

        private boolean guardBrowserMinecraftDir() {
        if (appContext.settings.minecraftDirectory == null || appContext.settings.minecraftDirectory.isBlank()) {
            browserStatusLabel.setText("Set a Minecraft directory in Settings first.");
            return false;
        }
        return true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Downloads pane
    // ─────────────────────────────────────────────────────────────────────────

    private VBox buildDownloadsPane() {
        downloadStatusLabel.getStyleClass().add("play-count");
        downloadStatusLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(downloadStatusLabel, Priority.ALWAYS);

        AvButton clearBtn = new AvButton("Clear completed", AvButton.Kind.GHOST);
        clearBtn.getStyleClass().add("play-row-btn");
        clearBtn.setMinWidth(Region.USE_PREF_SIZE);
        clearBtn.setOnAction(e -> clearCompletedDownloads());

        HBox toolbar = new HBox(12, downloadStatusLabel, clearBtn);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        downloadList.getStyleClass().add("gap-8");
        downloadScroll = new ScrollPane(downloadList);
        downloadScroll.setFitToWidth(true);
        downloadScroll.setPrefHeight(340);
        downloadScroll.setMinHeight(200);
        downloadScroll.getStyleClass().addAll("page-scroll", "play-dl-scroll");

        downloadsEmpty = new EmptyState("No downloads yet",
                "Files show up here while a version or modpack installs. Downloads keep going if you leave this page.");

        StackPane body = new StackPane(downloadScroll, downloadsEmpty);
        VBox pane = new VBox(toolbar, body);
        pane.getStyleClass().add("gap-12");
        updateDownloadStatus();
        return pane;
    }

    private void onGlobalDownloadTask(DownloadTask task) {
        Platform.runLater(() -> {
            trackedDownloads.add(task);
            HBox row = buildDownloadRow(task);
            downloadRowMap.put(task, row);
            downloadList.getChildren().add(row);

            // A vanilla install queues thousands of asset files; keep only the newest rows
            // so the monitor (which now lives on a page that is always built) stays light.
            while (trackedDownloads.size() > MAX_DOWNLOAD_ROWS) {
                DownloadTask oldest = trackedDownloads.remove(0);
                HBox oldRow = downloadRowMap.remove(oldest);
                if (oldRow != null) downloadList.getChildren().remove(oldRow);
            }
            updateDownloadStatus();
        });
    }

    private HBox buildDownloadRow(DownloadTask task) {
        HBox row = new HBox(12);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("play-dl-row");

        Label name = new Label(task.getDisplayName());
        name.getStyleClass().add("play-dl-name");
        name.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
        name.setMinWidth(120);
        name.setPrefWidth(320);
        name.setMaxWidth(380);

        MiniBar bar = new MiniBar();
        HBox.setHgrow(bar, Priority.ALWAYS);

        Label pct = new Label("0%");
        pct.getStyleClass().add("play-dl-pct");
        pct.setMinWidth(40);

        Label statusLbl = new Label(task.statusTextProperty().get());
        statusLbl.getStyleClass().add("play-dl-status");
        statusLbl.setMinWidth(80);

        task.bytesDownloadedProperty().addListener((o, ov, nv) -> Platform.runLater(() -> {
            double frac = task.progressFraction();
            bar.set(frac);
            pct.setText(frac < 0 ? "?" : String.format("%.0f%%", frac * 100));
            updateDownloadStatus();
        }));
        // Status changes (verifying / complete / failed) don't always move the byte counter.
        task.statusTextProperty().addListener((o, ov, nv) -> Platform.runLater(() -> {
            statusLbl.setText(nv);
            if (task.getStatus() == DownloadTask.Status.COMPLETE) {
                bar.set(1.0);
                pct.setText("100%");
            }
            updateDownloadStatus();
        }));

        row.getChildren().addAll(name, bar, pct, statusLbl);
        return row;
    }

    private void clearCompletedDownloads() {
        List<DownloadTask> done = trackedDownloads.stream()
                .filter(t -> t.getStatus() == DownloadTask.Status.COMPLETE
                        || t.getStatus() == DownloadTask.Status.CANCELLED
                        || t.getStatus() == DownloadTask.Status.FAILED)
                .toList();
        for (DownloadTask t : done) {
            HBox row = downloadRowMap.remove(t);
            if (row != null) downloadList.getChildren().remove(row);
        }
        trackedDownloads.removeAll(done);
        updateDownloadStatus();
    }

    private void updateDownloadStatus() {
        long active = trackedDownloads.stream()
                .filter(t -> t.getStatus() == DownloadTask.Status.DOWNLOADING
                        || t.getStatus() == DownloadTask.Status.VERIFYING)
                .count();
        long done = trackedDownloads.stream()
                .filter(t -> t.getStatus() == DownloadTask.Status.COMPLETE).count();
        boolean none = trackedDownloads.isEmpty();
        downloadStatusLabel.setText(none ? "" : active + " active  ·  " + done + "/" + trackedDownloads.size() + " complete");
        refreshSide();
        if (downloadScroll != null) setShown(downloadScroll, !none);
        if (downloadsEmpty != null) setShown(downloadsEmpty, none);
    }

    /** Slim progress bar for download rows. Updates the scale directly (no Timeline per row: there can be 200 rows). */
    private static final class MiniBar extends StackPane {
        private final Region fill = new Region();
        private final Scale scale = new Scale(0, 1, 0, 0);

        MiniBar() {
            getStyleClass().add("play-dl-track");
            setAlignment(Pos.CENTER_LEFT);
            fill.getStyleClass().add("play-dl-fill");
            fill.getTransforms().add(scale);
            getChildren().add(fill);
        }

        /** @param frac 0..1, or negative when the total size is unknown */
        void set(double frac) {
            boolean unknown = frac < 0;
            fill.getStyleClass().remove("play-dl-fill-wait");
            if (unknown) fill.getStyleClass().add("play-dl-fill-wait");
            scale.setX(unknown ? 1.0 : Math.max(0, Math.min(1, frac)));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Data loading
    // ─────────────────────────────────────────────────────────────────────────

    private void loadVersions() {
        // BMCLAPI's terms require showing its name wherever it is used: surface the one-time notice here.
        com.averolauncher.download.MirrorRouter.setNoticeListener(msg -> Platform.runLater(() -> statusLabel.setText(msg)));
        versionsLoading = true;
        versionsFailed = false;
        statusLabel.setText("");
        browserStatusLabel.setText("Loading...");
        browserList.setPlaceholder(skeletonRows());
        refreshChrome();

        appContext.launchService.getManifestService().fetchManifest(true)
                .thenAccept(manifest -> Platform.runLater(() -> {
                    allVersions.clear();
                    allVersions.addAll(manifest.versions);

                    // Also add locally-installed mod-loader versions (Fabric, Forge, NeoForge, OptiFine)
                    // that exist in versions/<id>/<id>.json but are NOT in Mojang's manifest.
                    // These are added as synthetic VersionEntry objects with type "local-modded".
                    scanLocalModdedVersions();

                    versionsLoading = false;
                    browserList.setPlaceholder(new EmptyState("No versions match",
                            "Turn on Releases, Snapshots or Modded above to see more."));
                    applyVersionFilter();
                    applyBrowserFilter();
                    browserStatusLabel.setText(allVersions.size() + " versions"
                            + (com.averolauncher.download.MirrorRouter.isMirrorActive()
                                ? "  |  via " + com.averolauncher.download.MirrorRouter.CREDIT : ""));

                    // If a modpack card is already selected, re-resolve its version now that
                    // the manifest is loaded (covers the case where cards were built first).
                    if (selectedModpack != null) resolveModpackVersion(selectedModpack);

                    statusLabel.setText(com.averolauncher.download.MirrorRouter.isMirrorActive()
                            ? com.averolauncher.download.MirrorRouter.notice() : "");
                }))
                .exceptionally(ex -> {
                    AppLogger.get().error(TAG, "Failed to load versions", ex);
                    Platform.runLater(() -> {
                        // Don't leave the picker stuck loading forever: even without the
                        // Mojang manifest, locally-installed (Fabric/Forge/NeoForge/OptiFine) versions
                        // can still be scanned from disk and played offline.
                        allVersions.clear();
                        scanLocalModdedVersions();
                        versionsLoading = false;
                        versionsFailed = true;
                        applyVersionFilter();
                        applyBrowserFilter();

                                        boolean network = ErrorState.isNetwork(ErrorState.describe(ex));
                        browserStatusLabel.setText(network ? "Can't reach Mojang right now." : "The version list is unavailable.");
                        noticeText.setText((network ? "Can't reach Mojang right now." : "Couldn't load the version list.")
                                + " Showing local versions only.");
                        errorHolder.getChildren().setAll(ErrorState.of(ex, "Mojang", this::loadVersions));
                        browserList.setPlaceholder(allVersions.isEmpty()
                                ? ErrorState.of(ex, "Mojang", this::loadVersions)
                                : new EmptyState("No versions match", "Turn on a filter above to see more."));
                        refreshChrome();
                    });
                    return null;
                });
    }

        /** Scans versions/ on disk and adds any locally-installed mod-loader versions not already in allVersions. */
    private void scanLocalModdedVersions() {
        String mcDir = appContext.settings.minecraftDirectory;
        if (mcDir == null || mcDir.isBlank()) return;
        java.nio.file.Path versionsDir = java.nio.file.Path.of(mcDir, "versions");
        if (!java.nio.file.Files.isDirectory(versionsDir)) return;

        java.util.Set<String> knownIds = allVersions.stream()
                .map(v -> v.id).collect(java.util.stream.Collectors.toSet());

        try (Stream<java.nio.file.Path> dirs = java.nio.file.Files.list(versionsDir)) {
            dirs.filter(java.nio.file.Files::isDirectory).forEach(dir -> {
                String id = dir.getFileName().toString();
                if (knownIds.contains(id)) return; // vanilla — already in list

                java.nio.file.Path jsonFile = dir.resolve(id + ".json");
                if (!java.nio.file.Files.exists(jsonFile)) return;

                // Create a synthetic VersionEntry so it shows up in the version list
                VersionManifest.VersionEntry entry = new VersionManifest.VersionEntry();
                entry.id = id;
                entry.type = "local-modded";
                entry.url = jsonFile.toUri().toString(); // used as a hint; launch goes via fetchVersionDetailLocal
                entry.releaseTime = ""; // unknown; sort to bottom
                allVersions.add(entry);
                AppLogger.get().info(TAG, "Found local modded version: " + id);
            });
        } catch (java.io.IOException e) {
            AppLogger.get().warn(TAG, "Could not scan versions dir for local versions: " + e.getMessage());
        }
    }

    private void applyVersionFilter() {
        boolean snapshots = showSnapshotsBox.isSelected();
        boolean modded    = showModdedBox.isSelected();
        List<VersionManifest.VersionEntry> filtered = allVersions.stream()
                .filter(v -> "release".equals(v.type)
                        || (snapshots && "snapshot".equals(v.type))
                        || (modded   && "local-modded".equals(v.type)))
                .sorted((a, b) -> {
                    // local-modded entries go to the top (no releaseTime); releases sorted newest-first
                    if ("local-modded".equals(a.type) && !"local-modded".equals(b.type)) return -1;
                    if (!"local-modded".equals(a.type) && "local-modded".equals(b.type)) return 1;
                    String ta = a.releaseTime != null ? a.releaseTime : "";
                    String tb = b.releaseTime != null ? b.releaseTime : "";
                    return tb.compareTo(ta);
                })
                .toList();
        ObservableList<VersionManifest.VersionEntry> items = FXCollections.observableArrayList(filtered);
        versionBox.setItems(items);

        // Always select the *current* default version (Home's selector may have changed it).
        String defaultId = appContext.settings.defaultVersionId;
        VersionManifest.VersionEntry pre = defaultId != null
                ? filtered.stream().filter(v -> v.id.equals(defaultId)).findFirst().orElse(null) : null;
        versionBox.setValue(pre != null ? pre : (filtered.isEmpty() ? null : filtered.get(0)));
        refreshChrome();
    }

    private void loadModpacks() {
        final int ticket = ++modpackScanTicket;
        modpackGrid.getChildren().clear();
        modpackCards.clear();
        modpackNames.clear();
        selectedModpack = null;
        selectedModpackVersion = null;
        modpackVersionLabel.setText("—");
        modpackStatusLabel.setText("Scanning modpacks folder...");
        modpackBody.getChildren().setAll(skeletonGrid());
        refreshChrome();

        String mcDir = appContext.settings.minecraftDirectory;
        if (mcDir == null || mcDir.isBlank()) {
            modpackStatusLabel.setText("Set a Minecraft directory in Settings first.");
            EmptyState es = new EmptyState("Choose your Minecraft folder",
                    "Modpacks are read from your Minecraft folder. Set it in Settings first.");
            if (navigator != null) es.action("Open settings", () -> navigator.accept(NavPage.SETTINGS));
            modpackBody.getChildren().setAll(es);
            return;
        }

        appContext.launchService.getBackgroundExecutor().execute(() -> {
            List<InstalledModpack> packs = scanModpacks(mcDir);
            Platform.runLater(() -> {
                if (ticket != modpackScanTicket) return;   // a newer scan superseded this one
                if (packs.isEmpty()) {
                    modpackStatusLabel.setText("");
                    EmptyState es = new EmptyState("No modpacks yet",
                            "Modpacks you download from the Mods page show up here, ready to play.");
                    if (navigator != null) es.action("Browse mods", () -> navigator.accept(NavPage.MODS));
                    modpackBody.getChildren().setAll(es);
                } else {
                    modpackStatusLabel.setText(packs.size() + " modpack" + (packs.size() == 1 ? "" : "s") + " installed");
                    for (InstalledModpack pack : packs) {
                        modpackNames.add(pack.displayName);
                        Card card = buildModpackCard(pack);
                        modpackCards.add(card);
                        modpackGrid.getChildren().add(card);
                    }
                    modpackBody.getChildren().setAll(modpackGrid);
                }
                refreshSide();
            });
        });
    }

    private Node skeletonGrid() {
        FlowPane grid = new FlowPane();
        grid.getStyleClass().add("gap-16");
        for (int i = 0; i < 3; i++) grid.getChildren().add(Skeleton.block(240, 132));
        return grid;
    }

        /** Scans .averolauncher/modpacks/ for .mrpack files and subdirectories (extracted packs). */
    private List<InstalledModpack> scanModpacks(String mcDir) {
        List<InstalledModpack> result = new ArrayList<>();
        Path modpacksDir = Path.of(mcDir, "modpacks");
        if (!Files.isDirectory(modpacksDir)) return result;
        try (Stream<Path> entries = Files.list(modpacksDir)) {
            entries.sorted().forEach(p -> {
                String name = p.getFileName().toString();
                if (name.endsWith(".mrpack")) {
                    result.add(new InstalledModpack(name.substring(0, name.length() - 7), p, "mrpack"));
                } else if (name.endsWith(".zip")) {
                    result.add(new InstalledModpack(name.substring(0, name.length() - 4), p, "zip"));
                } else if (Files.isDirectory(p)) {
                    result.add(new InstalledModpack(name, p, "folder"));
                }
            });
        } catch (IOException e) {
            AppLogger.get().error(TAG, "Could not scan modpacks folder", e);
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Modpack card
    // ─────────────────────────────────────────────────────────────────────────

    private static final double PACK_CARD_W = 240;

    private Card buildModpackCard(InstalledModpack pack) {
        Label glyph = new Label(pack.displayName.substring(0, 1).toUpperCase());
        glyph.getStyleClass().add("play-pack-glyph");
        StackPane icon = new StackPane(glyph);
        icon.getStyleClass().add("play-pack-icon");

        Label name = new Label(pack.displayName);
        name.getStyleClass().add("play-pack-name");
        name.setWrapText(true);
        name.setMaxWidth(Double.MAX_VALUE);

        Label type = new Label(pack.type.toUpperCase());
        type.getStyleClass().add("av-chip");

        Label file = new Label(pack.path.getFileName().toString());
        file.getStyleClass().add("play-pack-meta");
        file.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
        file.setMaxWidth(Double.MAX_VALUE);

        VBox body = new VBox(10, icon, name, new HBox(8, type), file);
        body.getStyleClass().add("pad-16");

        Card card = new Card(body).interactive();
        card.getStyleClass().add("play-pack");
        card.setMinWidth(PACK_CARD_W);
        card.setPrefWidth(PACK_CARD_W);
        card.setMaxWidth(PACK_CARD_W);
        card.setCursor(javafx.scene.Cursor.HAND);
        card.setFocusTraversable(true);
        card.setAccessibleText("Modpack " + pack.displayName);

        Runnable pick = () -> {
            modpackCards.forEach(c -> c.setActive(false));
            card.setActive(true);
            selectedModpack = pack;
            statusLabel.setText("");
            resolveModpackVersion(pack);
        };
        card.setOnMouseClicked(e -> pick.run());
        card.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) pick.run();
        });
        return card;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Modpack version auto-detection (no manual picker — a missed selection
    // must never silently fall through to launching vanilla)
    // ─────────────────────────────────────────────────────────────────────────

    /** Detects the modpack's own Minecraft version and stores it in selectedModpackVersion. */
    private void resolveModpackVersion(InstalledModpack pack) {
        modpackVersionLabel.setText("Detecting…");
        selectedModpackVersion = null;
        refreshChrome();

        appContext.launchService.getBackgroundExecutor().execute(() -> {
            String detectedId = detectModpackMcVersion(pack);
            Platform.runLater(() -> {
                if (selectedModpack != pack) return; // user picked a different card meanwhile

                if (detectedId == null) {
                    modpackVersionLabel.setText("Unknown — cannot launch");
                    statusLabel.setText("Could not detect a Minecraft version for " + pack.displayName + ".");
                    selectedModpackVersion = null;
                    refreshChrome();
                    return;
                }

                VersionManifest.VersionEntry match = allVersions.stream()
                        .filter(v -> v.id.equals(detectedId)).findFirst().orElse(null);
                if (match == null) {
                    // Not in the manifest (e.g. loader version not yet installed locally) —
                    // still record it; installAndLaunch resolves loader/local versions by id.
                    match = new VersionManifest.VersionEntry();
                    match.id = detectedId;
                    match.type = "release";
                }
                selectedModpackVersion = match;
                modpackVersionLabel.setText(detectedId);
                refreshChrome();
            });
        });
    }

        /** Best-effort detection of a modpack's target MC version from its own files. */
    private String detectModpackMcVersion(InstalledModpack pack) {
        try {
            if ("mrpack".equals(pack.type) || ("zip".equals(pack.type) && isMrpackZip(pack.path))) {
                return readMcVersionFromMrpack(pack.path);
            } else if ("folder".equals(pack.type)) {
                return readMcVersionFromFolder(pack.path);
            }
        } catch (Exception e) {
            AppLogger.get().warn(TAG, "Modpack version detection failed for " + pack.displayName + ": " + e.getMessage());
        }
        return null;
    }

        private boolean isMrpackZip(Path zipPath) {
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zipPath.toFile())) {
            return zf.getEntry("modrinth.index.json") != null;
        } catch (IOException e) {
            return false;
        }
    }

        /** Reads dependencies.minecraft from modrinth.index.json inside a .mrpack (or .zip built the same way). */
    private String readMcVersionFromMrpack(Path packPath) throws IOException {
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(packPath.toFile())) {
            java.util.zip.ZipEntry entry = zf.getEntry("modrinth.index.json");
            if (entry == null) return null;
            try (java.io.InputStream is = zf.getInputStream(entry)) {
                String json = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
                com.google.gson.JsonObject deps = root.getAsJsonObject("dependencies");
                if (deps != null && deps.has("minecraft")) {
                    return deps.get("minecraft").getAsString();
                }
            }
        }
        return null;
    }

        /**
     * Reads a version hint from an extracted modpack folder. Looks for a manifest.json
     * (CurseForge-style: minecraft.version) or falls back to scanning for a
     * modrinth.index.json placed at the folder root.
     */
    private String readMcVersionFromFolder(Path folder) throws IOException {
        Path manifestJson = folder.resolve("manifest.json");
        if (Files.exists(manifestJson)) {
            String json = Files.readString(manifestJson, java.nio.charset.StandardCharsets.UTF_8);
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            com.google.gson.JsonObject mc = root.getAsJsonObject("minecraft");
            if (mc != null && mc.has("version")) {
                return mc.get("version").getAsString();
            }
        }
        Path modrinthIndex = folder.resolve("modrinth.index.json");
        if (Files.exists(modrinthIndex)) {
            String json = Files.readString(modrinthIndex, java.nio.charset.StandardCharsets.UTF_8);
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            com.google.gson.JsonObject deps = root.getAsJsonObject("dependencies");
            if (deps != null && deps.has("minecraft")) {
                return deps.get("minecraft").getAsString();
            }
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Launch
    // ─────────────────────────────────────────────────────────────────────────

        private void onPlayClicked() {
        if (activeTab == Tab.VERSIONS) {
            launchVersion();
        } else {
            launchModpack();
        }
    }

        private void launchVersion() {
        VersionManifest.VersionEntry selected = versionBox.getValue();
        if (selected == null) {
            statusLabel.setText("Select a version first.");
            return;
        }
        if (!guardMinecraftDir()) return;

        lockUI();
        appContext.settings.defaultVersionId = selected.id;
        appContext.settingsRepository.save(appContext.settings);

        CompletableFuture<Process> launch;
        if ("local-modded".equals(selected.type)) {
            // Fabric, Forge, NeoForge, OptiFine — version JSON is on disk, not in Mojang manifest
            // Fabric: make sure the Avero cape mod is in place first (best-effort, never blocks launch).
            launch = com.averolauncher.store.CapeSupport.prepare(appContext.settings, selected.id, resolveActiveAccount())
                    .thenCompose(ignored -> appContext.launchService.installAndLaunchLocal(
                    selected.id,
                    appContext.settings,
                    null,
                    resolveActiveAccount(),
                    appContext.microsoftAuthService,
                    this::onDownloadTask,
                    this::onGameOutput,
                    this::onStatusUpdate));
        } else {
            // Vanilla / snapshot — use Mojang manifest entry
            launch = appContext.launchService.installAndLaunch(
                    selected,
                    appContext.settings,
                    null,
                    resolveActiveAccount(),
                    appContext.microsoftAuthService,
                    this::onDownloadTask,
                    this::onGameOutput,
                    this::onStatusUpdate);
        }
        launch.whenComplete((process, ex) -> Platform.runLater(() -> handleLaunchResult(process, ex)));
    }

        private void launchModpack() {
        if (selectedModpack == null) {
            statusLabel.setText("Select a modpack card first.");
            return;
        }
        VersionManifest.VersionEntry mcVersion = selectedModpackVersion;
        if (mcVersion == null) {
            // Never fall through to a vanilla launch — refuse instead of guessing.
            statusLabel.setText("Could not detect this modpack's Minecraft version — cannot launch.");
            return;
        }
        if (!guardMinecraftDir()) return;

        lockUI();
        statusLabel.setText("Preparing modpack: " + selectedModpack.displayName + " on MC " + mcVersion.id);
        AppLogger.get().info(TAG, "Launching modpack " + selectedModpack.displayName
                + " (" + selectedModpack.type + ") with MC " + mcVersion.id);

        // For .mrpack / zip packs the game directory is set to the pack's own folder so
        // per-pack saves, configs and mods stay separate from vanilla.  For folder-based
        // packs the folder itself becomes the game directory.
        String gameDir = appContext.settings.minecraftDirectory;
        com.averolauncher.model.LaunchProfile profile = new com.averolauncher.model.LaunchProfile();
        profile.id = "modpack-" + selectedModpack.displayName;
        profile.name = selectedModpack.displayName;
        profile.versionId = mcVersion.id;
        profile.gameDirectoryOverride = gameDir;  // modpack mods live alongside MC data for now

        appContext.settings.defaultVersionId = mcVersion.id;
        appContext.settingsRepository.save(appContext.settings);

        appContext.launchService.installAndLaunch(
                mcVersion,
                appContext.settings,
                profile,
                resolveActiveAccount(),
                appContext.microsoftAuthService,
                this::onDownloadTask,
                this::onGameOutput,
                this::onStatusUpdate
        ).whenComplete((process, ex) -> Platform.runLater(() -> handleLaunchResult(process, ex)));
    }

        private boolean guardMinecraftDir() {
        if (appContext.settings.minecraftDirectory == null || appContext.settings.minecraftDirectory.isBlank()) {
            statusLabel.setText("Set a Minecraft directory in Settings first.");
            return false;
        }
        return true;
    }

    /** Presentation only: switches the panel to its "launching" state with an indeterminate progress bar. */
    private void lockUI() {
        phase = Phase.LAUNCHING;
        launchFailed = false;
        launchFiles = 0;
        launchFilesLabel.setText("");
        progress.setStep("Preparing…");
        progress.setIndeterminate(true);
        outputArea.clear();
        logShown = false;
        refreshChrome();
    }

    private void handleLaunchResult(Process process, Throwable ex) {
        progress.setIndeterminate(false);
        if (ex != null) {
            phase = Phase.READY;
            launchFailed = true;
            String raw = ErrorState.describe(ex);
            statusLabel.setText(ErrorState.isNetwork(raw)
                    ? "Can't reach Minecraft's servers right now. Check your connection and try again."
                    : "Launch failed: " + rootMessage(ex));
            AppLogger.get().error(TAG, "Install/launch pipeline failed", ex);
            refreshChrome();
        } else {
            infoCache.clear();      // the launch may have just installed this version
            phase = Phase.RUNNING;
            launchFailed = false;
            logShown = true;
            statusLabel.setText("Minecraft is running (PID " + process.pid() + ")");
            refreshChrome();
            process.onExit().thenAccept(finished -> Platform.runLater(() -> {
                if (phase == Phase.RUNNING) phase = Phase.READY;
                statusLabel.setText("Minecraft exited with code " + finished.exitValue());
                refreshChrome();
            }));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

        private Account resolveActiveAccount() {
        if (appContext.settings.activeAccountId == null) return null;
        return appContext.accountRepository.load().stream()
                .filter(a -> a.id.equals(appContext.settings.activeAccountId))
                .findFirst().orElse(null);
    }

    /** Presentation only: counts the files queued by this launch (the Downloads tab lists them). */
    private void onDownloadTask(DownloadTask task) {
        Platform.runLater(() -> {
            launchFiles++;
            launchFilesLabel.setText(launchFiles + (launchFiles == 1 ? " file" : " files") + " queued");
        });
    }

        private void onGameOutput(String line) {
        Platform.runLater(() -> outputArea.appendText(line + "\n"));
    }

        private void onStatusUpdate(String message) {
        Platform.runLater(() -> statusLabel.setText(message));
    }

        private String rootMessage(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() != null ? cause.getMessage() : ex.getMessage();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal data model
    // ─────────────────────────────────────────────────────────────────────────

        /** A modpack file or folder found in the modpacks/ directory. */
    private static class InstalledModpack {
        final String displayName;
        final Path   path;
        final String type; // "mrpack", "zip", "folder"

        InstalledModpack(String displayName, Path path, String type) {
            this.displayName = displayName;
            this.path = path;
            this.type = type;
        }
    }
}
