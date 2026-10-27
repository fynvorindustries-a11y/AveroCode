package com.averolauncher.ui.pages;

import com.averolauncher.rank.*;
import com.averolauncher.ui.NavPage;
import com.averolauncher.ui.kit.*;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.*;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The global rank page: badge, AP, next-rank requirement, progress bar, daily AP cap, live earning
 * status, quests and the full ladder. Purely a view of {@link RankSnapshot}; it re-renders whenever
 * the {@link RankService} reports a change while the page is on screen.
 */
public final class RanksPage {

    private static final NumberFormat NUM = NumberFormat.getIntegerInstance(Locale.US);

    private final Consumer<NavPage> navigator;
    private final Runnable replayRankUp;
    private final VBox content = new VBox(22);
    private final RankListener listener = new RankListener() {
        @Override public void onChanged(RankSnapshot s) { Platform.runLater(() -> render(s)); }
    };

    public RanksPage(Consumer<NavPage> navigator, Runnable replayRankUp) {
        this.navigator = navigator;
        this.replayRankUp = replayRankUp;
    }

    public VBox build() {
        VBox root = new VBox(22);
        root.getStyleClass().add("page");
        root.setPadding(new Insets(32));
        PageHeader header = new PageHeader("Ranks", "One launcher. All servers. Your progression.");
        if (replayRankUp != null) {
            AvButton replay = new AvButton("Replay rank-up", AvButton.Kind.GHOST);
            replay.setTooltip(new javafx.scene.control.Tooltip("Play the rank-up animation again (Ctrl+Shift+R)"));
            replay.setOnAction(e -> replayRankUp.run());
            header.actions(replay);
        }
        root.getChildren().addAll(header, content);

        // Live while visible: listener for changes, a slow tick for the reset countdown.
        Timeline clock = new Timeline(new KeyFrame(javafx.util.Duration.seconds(30), e -> render(RankService.get().snapshot())));
        clock.setCycleCount(Animation.INDEFINITE);
        root.sceneProperty().addListener((o, was, scene) -> {
            if (scene == null) {
                clock.stop();
                RankService.get().removeListener(listener);
            } else {
                RankService.get().addListener(listener);
                RankService.get().refresh();
                render(RankService.get().snapshot());
                clock.play();
            }
        });
        render(RankService.get().snapshot());
        return root;
    }

    // ------------------------------------------------------------------ render

    private void render(RankSnapshot s) {
        content.getChildren().clear();
        if (!s.signedIn()) {
            content.getChildren().addAll(signedOutCard(s), ladder(s, true));
            return;
        }
        content.getChildren().addAll(hero(s), dailyCard(s), questsSection(s), ladder(s, false), footer(s));
    }

    private Node signedOutCard(RankSnapshot s) {
        EmptyState es = new EmptyState("Earn a global rank",
                "Sign in with your Avero ID to earn Avero Points (AP) from verified Minecraft playtime and Avero quests. "
                        + "Your rank follows you across every server and every device.");
        AvButton go = new AvButton("Sign in with Avero ID", AvButton.Kind.PRIMARY);
        go.setOnAction(e -> navigator.accept(NavPage.ACCOUNTS));
        es.getChildren().add(go);
        return new Card(es);
    }

    private Node hero(RankSnapshot s) {
        RankStanding st = s.standing();
        String accent = RankBadge.accent(st.tier());

        RankBadge badge = new RankBadge(st.tier(), st.division(), 170, true);
        badge.setMinWidth(210);

        Label kicker = new Label("GLOBAL RANK");
        kicker.getStyleClass().add("rank-kicker");
        Label name = new Label(st.label());
        name.getStyleClass().add("rank-hero-name");
        name.setStyle("-fx-text-fill: " + accent + ";");
        Label tag = new Label(st.tier().tagline);
        tag.getStyleClass().add("rank-hero-tag");

        Label ap = new Label(NUM.format(s.ap()));
        ap.getStyleClass().add("rank-ap");
        Label unit = new Label("AVERO POINTS");
        unit.getStyleClass().add("rank-ap-unit");
        HBox apRow = new HBox(8, ap, unit);
        apRow.setAlignment(Pos.BASELINE_LEFT);

        Label left = new Label(st.isMax() ? "Highest rank reached" : NUM.format(st.apToNext()) + " AP to " + st.nextLabel());
        left.getStyleClass().add("rank-caption-strong");
        Label right = new Label(st.isMax() ? "" : "Next: " + st.nextLabel() + " at " + NUM.format(st.nextAt()) + " AP");
        right.getStyleClass().add("rank-caption");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox captions = new HBox(left, sp, right);

        VBox info = new VBox(8, kicker, name, tag, apRow, new RankBar(st.progress(), accent, false), captions, trackerStatus(s));
        info.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(info, Priority.ALWAYS);

        HBox row = new HBox(28, badge, info);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(22, 28, 22, 24));
        return new Card(row);
    }

    private Node trackerStatus(RankSnapshot s) {
        String text;
        String kind = "";
        PlaytimeTracker.Status t = s.tracker();
        if (s.capReached() && t.playing()) { text = "DAILY CAP REACHED, PLAYTIME NO LONGER EARNS AP TODAY"; kind = "warn"; }
        else if (!t.playing()) text = "NOT IN GAME";
        else switch (t.verdict()) {
            case ACTIVE -> { text = "EARNING: VERIFIED PLAYTIME"; kind = "ok"; }
            case UNVERIFIED_ALLOWED -> { text = "EARNING"; kind = "ok"; }
            case STARTING -> text = "STARTING UP, NOT COUNTED YET";
            case AFK_IDLE -> { text = "AFK DETECTED, PAUSED"; kind = "warn"; }
            case AFK_BACKGROUND -> { text = "GAME IN BACKGROUND, PAUSED"; kind = "warn"; }
            case MACRO_SUSPECT -> { text = "WAITING FOR NATURAL ACTIVITY, PAUSED"; kind = "warn"; }
            case SESSION_LIMIT -> { text = "SESSION LIMIT REACHED, RESTART THE GAME TO KEEP EARNING"; kind = "warn"; }
            case SUSPENDED_GAP -> text = "RESUMING";
            case DUPLICATE_INSTANCE -> text = "ANOTHER INSTANCE IS ALREADY EARNING";
            case UNVERIFIED_DENIED -> { text = "CAN'T VERIFY ACTIVITY ON THIS SYSTEM"; kind = "warn"; }
            default -> text = "NOT IN GAME";
        }
        Label chip = new Label(text);
        chip.getStyleClass().add("rank-status");
        if (!kind.isEmpty()) chip.getStyleClass().add("rank-status-" + kind);
        HBox box = new HBox(chip);
        box.setPadding(new Insets(4, 0, 0, 0));
        return box;
    }

    private Node dailyCard(RankSnapshot s) {
        RankConfig cfg = s.config();
        String accent = RankBadge.accent(s.standing().tier());

        Label title = new Label("DAILY PLAYTIME AP");
        title.getStyleClass().add("av-section-title");
        Label value = new Label(s.dayPlaytimeAp() + " / " + s.dailyCapAp() + " AP");
        value.getStyleClass().add("rank-ap");
        Label resets = new Label("Resets in " + countdown(s.dayResetsAtEpochMs() - System.currentTimeMillis()) + " (00:00 UTC)");
        resets.getStyleClass().add("rank-caption");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox top = new HBox(12, value, sp, resets);
        top.setAlignment(Pos.BASELINE_LEFT);

        double pct = s.dailyCapAp() <= 0 ? 1 : s.dayPlaytimeAp() / (double) s.dailyCapAp();
        long verifiedMin = s.dayVerifiedMs() / 60_000L, afkMin = s.dayAfkMs() / 60_000L;
        Label rule = new Label("You earn " + cfg.playtime.apPerBlock + " AP for every " + cfg.playtime.minutesPerAp
                + " minutes of verified playtime, up to " + s.dailyCapAp() + " AP a day. Quests are on top of that. "
                + "Today: " + verifiedMin + " min verified" + (afkMin > 0 ? ", " + afkMin + " min AFK (not counted)." : "."));
        rule.getStyleClass().add("rank-note");
        rule.setWrapText(true);

        VBox box = new VBox(10, title, top, new RankBar(pct, accent, false), rule);
        box.setPadding(new Insets(18, 24, 18, 24));
        return new Card(box);
    }

    private Node questsSection(RankSnapshot s) {
        VBox sec = new VBox(14);
        sec.getChildren().add(sectionTitle("QUESTS"));
        for (String type : new String[]{"DAILY", "WEEKLY", "SPECIAL"}) {
            List<QuestView> qs = s.quests().stream().filter(q -> q.type().equals(type)).toList();
            if (qs.isEmpty()) continue;
            String head = switch (type) { case "DAILY" -> "Daily"; case "WEEKLY" -> "Weekly"; default -> "Special"; };
            long ends = qs.stream().mapToLong(QuestView::endsAtEpochMs).filter(v -> v > 0).min().orElse(0);
            Label h = new Label(head + (ends > 0 ? "  \u00B7  resets in " + countdown(ends - System.currentTimeMillis()) : ""));
            h.getStyleClass().add("rank-caption-strong");
            VBox group = new VBox(8, h);
            for (QuestView q : qs) group.getChildren().add(questRow(q, RankBadge.accent(s.standing().tier())));
            sec.getChildren().add(group);
        }
        return sec;
    }

    private Node questRow(QuestView q, String accent) {
        Label t = new Label(q.title());
        t.getStyleClass().add("rank-quest-title");
        Label d = new Label(q.description());
        d.getStyleClass().add("rank-quest-desc");
        d.setWrapText(true);
        VBox text = new VBox(2, t, d);
        HBox.setHgrow(text, Priority.ALWAYS);

        Label prog = new Label(q.complete() ? "Completed" : q.progressText());
        prog.getStyleClass().add("rank-quest-prog");
        VBox bar = new VBox(4, new RankBar(q.fraction(), accent, true), prog);
        bar.setMinWidth(200);
        bar.setPrefWidth(220);
        bar.setMaxWidth(220);
        bar.setAlignment(Pos.CENTER_LEFT);

        Label reward = new Label((q.complete() ? "\u2713 " : "+") + q.rewardAp() + " AP");
        reward.getStyleClass().add("rank-reward");
        if (q.complete()) reward.getStyleClass().add("rank-reward-done");
        reward.setMinWidth(84);
        reward.setPrefWidth(84);
        reward.setAlignment(Pos.CENTER);

        HBox row = new HBox(18, text, bar, reward);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("rank-quest");
        if (q.complete()) row.getStyleClass().add("rank-quest-done");
        return row;
    }

    private Node ladder(RankSnapshot s, boolean showTitle) {
        VBox sec = new VBox(12);
        sec.getChildren().add(sectionTitle("RANK LADDER"));
        // Laid out like the rank chart: a row of five, then the rest, every cell the same width.
        List<RankConfig.Tier> tiers = s.config().ranks;
        VBox grid = new VBox(6);
        HBox rowBox = null;
        for (int i = 0; i < tiers.size(); i++) {
            if (i % 5 == 0) { rowBox = new HBox(6); grid.getChildren().add(rowBox); }
            RankConfig.Tier t = tiers.get(i);
            boolean reached = s.signedIn() && i <= s.standing().tierIndex();
            boolean current = s.signedIn() && i == s.standing().tierIndex();
            RankBadge b = new RankBadge(t, current ? s.standing().division() : (reached ? Math.max(1, t.divisions) : 0), 84, true);
            Label name = new Label(t.name.toUpperCase(Locale.ROOT));
            name.getStyleClass().add("rank-ladder-name");
            if (current) name.setStyle("-fx-text-fill: " + RankBadge.accent(t) + ";");
            long hi = i + 1 < tiers.size() ? tiers.get(i + 1).minAp - 1 : -1;
            Label range = new Label(hi < 0 ? NUM.format(t.minAp) + "+ AP" : NUM.format(t.minAp) + " \u2013 " + NUM.format(hi) + " AP");
            range.getStyleClass().add("rank-ladder-range");
            VBox cell = new VBox(5, b, name, range);
            cell.setAlignment(Pos.TOP_CENTER);
            cell.getStyleClass().add("rank-ladder-cell");
            cell.setPrefWidth(0);
            cell.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(cell, Priority.ALWAYS);
            if (s.signedIn() && !reached) cell.getStyleClass().add("rank-ladder-locked");
            rowBox.getChildren().add(cell);
        }
        Node flow = grid;
        sec.getChildren().add(new Card(wrap(flow)));
        return sec;
    }

    private Node footer(RankSnapshot s) {
        String text = switch (s.sync()) {
            case SYNCED -> "Synced to your Avero account.";
            case PENDING -> s.pendingEvents() + (s.pendingEvents() == 1 ? " update is" : " updates are") + " waiting to sync to your Avero account.";
            case OFFLINE -> "Offline. Your progress is saved here and will sync automatically.";
            case LOCAL_ONLY -> "Progress is saved on this device. Account-wide sync isn't available yet, and will catch up automatically.";
            case SIGNED_OUT -> "";
        };
        Label l = new Label(text + "  AP never decreases from normal play.");
        l.getStyleClass().add("rank-note");
        l.setWrapText(true);
        return l;
    }

    // ------------------------------------------------------------------ helpers

    private static Label sectionTitle(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("av-section-title");
        return l;
    }

    private static Node wrap(Node n) {
        StackPane p = new StackPane(n);
        p.setPadding(new Insets(12, 16, 12, 16));
        return p;
    }

    static String countdown(long ms) {
        long m = Math.max(0, ms) / 60_000L;
        long d = m / (60 * 24), h = (m / 60) % 24, mm = m % 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + mm + "m";
        return Math.max(1, mm) + "m";
    }
}
