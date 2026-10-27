package com.averolauncher.ui.pages;

import com.averolauncher.logging.AppLogger;
import com.averolauncher.rank.RankCalculator;
import com.averolauncher.rank.RankService;
import com.averolauncher.rank.RankStanding;
import com.averolauncher.store.AveroId;
import com.averolauncher.store.StoreApi;
import com.averolauncher.store.StoreApi.Leaderboard;
import com.averolauncher.store.StoreApi.LeaderboardEntry;
import com.averolauncher.ui.Motion;
import com.averolauncher.ui.NavPage;
import com.averolauncher.ui.kit.RankBadge;
import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.RotateTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.shape.SVGPath;
import javafx.scene.layout.StackPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Live player leaderboard: real Avero IDs ranked by total AP, fetched from the server (never demo data).
 * It refreshes itself every {@link #REFRESH_SECONDS} seconds while it is on screen and stops as soon as the
 * page is closed, so the Home leaderboard always shows the current standings. Header icons: refresh now,
 * jump to my place, Top 10 / Top 50, and open the Ranks page.
 */
public final class LeaderboardPanel extends VBox {

    private static final String TAG = "Leaderboard";
    /** Always fetch the full board; the Top 10 / Top 50 toggle only changes how much of it is shown. */
    private static final int TOP = 50;
    private static final int SHORT_TOP = 10;
    private static final int REFRESH_SECONDS = 10;

    private final StoreApi api = AveroId.get().api();
    private final VBox rows = new VBox(0);
    private final ScrollPane scroll = new ScrollPane(rows);
    private final Label live = new Label("Connecting…");
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private final Timeline poll = new Timeline(new KeyFrame(Duration.seconds(REFRESH_SECONDS), e -> refresh()));
    private final Consumer<NavPage> navigator;
    private final Button refreshBtn = iconButton("M20 11 A8 8 0 1 0 18.3 16 M20 4 V11 H13", "Refresh now");
    private final Button limitBtn = new Button("TOP 50");
    private RotateTransition spin;
    private Leaderboard last;
    private HBox myRow;
    private boolean showAll = true;
    private boolean loadedOnce;

    /** @param navigator opens other pages (the "open Ranks" icon); may be null to hide that icon */
    public LeaderboardPanel(Consumer<NavPage> navigator, double listHeight) {
        super(10);
        this.navigator = navigator;
        Label title = new Label("LEADERBOARD");
        title.getStyleClass().add("av-section-title");
        live.getStyleClass().add("av-lb-live");
        Region spring = new Region();
        HBox.setHgrow(spring, Priority.ALWAYS);

        refreshBtn.setOnAction(e -> manualRefresh());

        limitBtn.getStyleClass().add("av-lb-limit-btn");
        limitBtn.setTooltip(new Tooltip("Show the top 10 or the top 50 players"));
        limitBtn.setOnAction(e -> {
            showAll = !showAll;
            limitBtn.setText(showAll ? "TOP 50" : "TOP 10");
            if (last != null) render(last);
        });

        Button findMe = iconButton("M12 8 A4 4 0 1 0 12.01 8 Z M12 2 V5 M12 19 V22 M2 12 H5 M19 12 H22", "Jump to my place");
        findMe.setOnAction(e -> scrollToMe());

        HBox head = new HBox(8, title, live, spring, limitBtn, findMe, refreshBtn);
        if (navigator != null) {
            Button open = iconButton("M14 4 H20 V10 M20 4 L11 13 M18 14 V19 H5 V6 H10", "Open my rank & quests");
            open.setOnAction(e -> navigator.accept(NavPage.RANKS));
            head.getChildren().add(open);
        }
        head.setAlignment(Pos.CENTER_LEFT);

        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("page-scroll");
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        if (listHeight > 0) {
            scroll.setMinHeight(listHeight);
            scroll.setPrefHeight(listHeight);
            scroll.setMaxHeight(listHeight);
        }

        VBox.setVgrow(scroll, Priority.ALWAYS);   // fills remaining height when no fixed listHeight is given
        getChildren().addAll(head, scroll);
        poll.setCycleCount(Timeline.INDEFINITE);
        showNote("Loading leaderboard…");

        // Poll only while visible: start when attached to a scene, stop (and drop the timer) when removed.
        sceneProperty().addListener((o, was, now) -> {
            if (now != null) { refresh(); poll.play(); } else { poll.stop(); stopSpin(); }
        });
        // A new AP total / sign-in change should show up without waiting for the next tick.
        Runnable onAvero = () -> Platform.runLater(this::refresh);
        AveroId.get().addListener(onAvero);
        sceneProperty().addListener((o, was, now) -> { if (now == null) AveroId.get().removeListener(onAvero); });
    }

    /** A small outlined icon button (24-grid stroke path), recoloured by CSS on hover. */
    private static Button iconButton(String path, String tip) {
        SVGPath svg = new SVGPath();
        svg.setContent(path);
        svg.getStyleClass().add("av-lb-icon");
        svg.setScaleX(0.72);
        svg.setScaleY(0.72);
        StackPane box = new StackPane(svg);
        box.setMinSize(18, 18);
        box.setPrefSize(18, 18);
        box.setMaxSize(18, 18);
        Button b = new Button();
        b.setGraphic(box);
        b.getStyleClass().add("av-lb-icon-btn");
        b.setTooltip(new Tooltip(tip));
        b.setAccessibleText(tip);
        return b;
    }

    private void manualRefresh() {
        live.setText("Refreshing…");
        startSpin();
        refresh();
        poll.playFromStart();
    }

    private void startSpin() {
        if (!Motion.enabled() || refreshBtn.getGraphic() == null) return;
        stopSpin();
        spin = new RotateTransition(Duration.millis(700), refreshBtn.getGraphic());
        spin.setByAngle(360);
        spin.setInterpolator(Interpolator.LINEAR);
        spin.setCycleCount(Animation.INDEFINITE);
        spin.play();
    }

    private void stopSpin() {
        if (spin != null) spin.stop();
        spin = null;
        if (refreshBtn.getGraphic() != null) refreshBtn.getGraphic().setRotate(0);
    }

    /** Scrolls the list so the signed-in player's own row is in view (briefly says so when there is none). */
    private void scrollToMe() {
        if (myRow == null) {
            live.setText(AveroId.get().session() == null ? "Sign in to see your place" : "You're not on the board yet");
            return;
        }
        rows.applyCss();
        rows.layout();
        double content = rows.getHeight(), view = scroll.getViewportBounds().getHeight();
        double y = myRow.getBoundsInParent().getMinY() - (view - myRow.getHeight()) / 2;
        scroll.setVvalue(content <= view ? 0 : Math.max(0, Math.min(1, y / (content - view))));
    }

    private void refresh() {
        if (getScene() == null || !inFlight.compareAndSet(false, true)) return;
        api.leaderboard(TOP).whenComplete((lb, err) -> Platform.runLater(() -> {
            inFlight.set(false);
            stopSpin();
            if (getScene() == null) return;
            if (err != null) { onError(err); return; }
            last = lb;
            render(lb);
        }));
    }

    private void onError(Throwable err) {
        Throwable r = err;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        if (r instanceof StoreApi.StoreException se && (se.status == 404 || se.status == 405 || se.status == 501 || "NO_SERVER".equals(se.code))) {
            live.setText("Unavailable");
            showNote("The leaderboard isn't available on the Avero server yet.");
            poll.stop();
            return;
        }
        AppLogger.get().warn(TAG, "refresh failed: " + r);
        live.setText("Offline — retrying");
        // Keep the last good list on screen; only say something when there is nothing to show.
        if (!loadedOnce) showNote("Couldn't load the leaderboard: " + (r.getMessage() != null ? r.getMessage() : "unknown error"));
    }

    private void showNote(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("activity-empty");
        l.setWrapText(true);
        rows.getChildren().setAll(l);
    }

    private void render(Leaderboard lb) {
        loadedOnce = true;
        live.setText("● LIVE");
        if (lb.entries.isEmpty()) { showNote("No ranked players yet. Play to earn AP and be the first on the board."); return; }

        StoreApi.AveroSession me = AveroId.get().session();
        String myId = me != null ? me.userId : null;
        rows.getChildren().clear();
        myRow = null;
        boolean meListed = false;
        int shown = 0;
        for (LeaderboardEntry e : lb.entries) {
            boolean mine = myId != null && myId.equals(e.userId);
            meListed |= mine;
            // Top 10 view: still keep the player's own row even when it is further down.
            if (!showAll && shown >= SHORT_TOP && !mine) continue;
            shown++;
            HBox r = row(e, mine);
            if (mine) myRow = r;
            rows.getChildren().add(r);
        }
        // The caller outside the top list still sees where they stand.
        if (!meListed && lb.me != null && lb.me.rank > 0) {
            Label gap = new Label("⋯");
            gap.getStyleClass().add("av-lb-sub");
            gap.setPadding(new Insets(4, 0, 4, 14));
            myRow = row(lb.me, true);
            rows.getChildren().addAll(gap, myRow);
        }
    }

    private HBox row(LeaderboardEntry e, boolean mine) {
        HBox row = new HBox(12);
        row.getStyleClass().add("av-lb-row");
        if (mine) row.getStyleClass().add("av-lb-row-me");
        row.setAlignment(Pos.CENTER_LEFT);

        Label place = new Label("#" + e.rank);
        place.getStyleClass().add("av-lb-place");
        if (e.rank > 0 && e.rank <= 3) {
            place.getStyleClass().addAll("av-lb-place-top", e.rank == 1 ? "av-lb-place-gold" : e.rank == 2 ? "av-lb-place-silver" : "av-lb-place-bronze");
        }

        HBox who = new HBox(10);
        who.setAlignment(Pos.CENTER_LEFT);
        if (e.minecraftUsername != null && !e.minecraftUsername.isBlank()) {
            ImageView head = new ImageView(new Image("https://mc-heads.net/avatar/"
                    + java.net.URLEncoder.encode(e.minecraftUsername, java.nio.charset.StandardCharsets.UTF_8) + "/32", 28, 28, true, false, true));
            head.setFitWidth(28);
            head.setFitHeight(28);
            who.getChildren().add(head);
        }
        Label name = new Label((e.username != null ? e.username : "Unknown") + (mine ? "  (you)" : ""));
        name.getStyleClass().add("av-lb-name");
        who.getChildren().add(name);

        RankStanding st = RankCalculator.standing(RankService.get().config(), e.totalAp);
        Label rankName = new Label(st.label());
        rankName.getStyleClass().add("av-lb-sub");
        HBox rank = new HBox(8, new RankBadge(st.tier(), st.division(), 24, false), rankName);
        rank.setAlignment(Pos.CENTER_LEFT);

        Region spring = new Region();
        HBox.setHgrow(spring, Priority.ALWAYS);
        Label ap = new Label(NumberFormat.getIntegerInstance(Locale.getDefault()).format(e.totalAp) + " AP");
        ap.getStyleClass().add("av-lb-ap");

        row.getChildren().addAll(place, who, spring, rank, ap);
        return row;
    }
}
