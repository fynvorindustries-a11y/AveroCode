package com.averolauncher.rank;

import com.averolauncher.logging.AppLogger;
import com.averolauncher.rank.RankEngine.Credit;
import com.averolauncher.rank.RankEngine.Outcome;
import com.averolauncher.rank.RankState.QuestDone;
import com.averolauncher.store.AveroId;
import com.averolauncher.store.StoreApi;
import com.averolauncher.ui.AppContext;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Facade for the global rank system. Owns the current account's {@link RankState}, feeds verified
 * playtime from {@link PlaytimeTracker} into {@link RankEngine}, persists through {@link RankStore} and
 * reconciles with the Avero server through {@link RankSync}.
 *
 * <p>Wiring: {@link #bind(AppContext)} once at startup; {@code LaunchService} calls
 * {@link #onGameLaunched}; the UI calls {@link #snapshot()} and registers a {@link RankListener}.
 * Nothing here can interrupt a launch: every entry point swallows its own failures.
 */
public final class RankService implements PlaytimeTracker.Sink {

    private static final String TAG = "RankService";
    private static final RankService INSTANCE = new RankService();
    public static RankService get() { return INSTANCE; }

    private static final long SYNC_PERIOD_S = 60;
    private static final long PULL_EVERY_MS = 10 * 60_000L;
    private static final long NOT_SUPPORTED_RETRY_MS = 30 * 60_000L;
    private static final int PUSH_BATCH = 100;

    private final Object lock = new Object();
    private final List<RankListener> listeners = new CopyOnWriteArrayList<>();

    private RankConfig cfg;
    private RankEngine engine;
    private RankStore store;
    private RankSync sync;
    private PlaytimeTracker tracker;
    private ScheduledExecutorService exec;
    private boolean bound;

    private RankState state;            // null while signed out
    private boolean syncing;
    private long lastPullAt;
    private long retryNotBefore;
    private boolean serverMissing;      // endpoints answered 404/501: local-only until the retry window passes
    private boolean offline;
    private int backoffS;
    private long linkCheckedAt;

    private RankService() { }

    // ------------------------------------------------------------------ lifecycle

    /** Call once at startup, after {@code AveroId.get().bind(ctx)}. Safe to call repeatedly. */
    public void bind(AppContext ctx) {
        synchronized (lock) {
            if (bound) return;
            bound = true;
            try {
                cfg = RankConfig.load();
                engine = new RankEngine(cfg);
                store = new RankStore();
                sync = new RankSync(AveroId.get().api());
                tracker = new PlaytimeTracker(cfg, this);
                exec = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "avero-rank-sync");
                    t.setDaemon(true);
                    return t;
                });
                exec.scheduleWithFixedDelay(this::syncTick, 5, SYNC_PERIOD_S, TimeUnit.SECONDS);
                AveroId.get().addListener(this::onAccountChanged);
            } catch (Throwable t) {
                bound = false;
                AppLogger.get().error(TAG, "Rank system failed to start; the launcher continues without it.", t);
                return;
            }
        }
        onAccountChanged();
    }

    public boolean isReady() { synchronized (lock) { return bound && cfg != null; } }

    public RankConfig config() { synchronized (lock) { return cfg != null ? cfg : RankConfig.bundled(); } }

    public void addListener(RankListener l) { listeners.add(l); }
    public void removeListener(RankListener l) { listeners.remove(l); }

    /** Flushes to disk and a last best-effort upload. Call on application exit. */
    public void shutdown() {
        RankState s;
        synchronized (lock) {
            if (!bound) return;
            if (tracker != null) tracker.shutdown();
            s = state;
            if (s != null) store.save(s);
        }
        if (s != null) {
            // Best-effort final upload, but closing the launcher must never wait on a slow network.
            Thread t = new Thread(() -> { try { syncOnce(true); } catch (Throwable ignored) { } }, "avero-rank-final-sync");
            t.setDaemon(true);
            t.start();
            try { t.join(3000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        synchronized (lock) { if (exec != null) exec.shutdownNow(); }
    }

    // ------------------------------------------------------------------ account

    private void onAccountChanged() {
        try {
            String uid = null;
            StoreApi.AveroSession sess = AveroId.get().session();
            if (sess != null) uid = sess.userId;
            boolean changed;
            synchronized (lock) {
                String cur = state == null ? null : state.userId;
                changed = !Objects.equals(cur, uid);
                if (changed) {
                    if (state != null) store.save(state);
                    if (uid == null) {
                        state = null;
                    } else {
                        RankStore.Loaded l = store.load(uid);
                        state = l.state();
                        engine.rollover(state, Instant.now());
                        recomputeTotal(state);
                        state.announcedOrdinal = RankCalculator.standing(cfg, state.totalAp).ordinal();
                        if (l.tampered()) { state.serverAp = -1; }
                        lastPullAt = 0;
                    }
                }
            }
            if (changed) {
                fireChanged();
                if (uid != null) kick();
            }
        } catch (Throwable t) {
            AppLogger.get().warn(TAG, "account change handling failed: " + t);
        }
    }

    private void recomputeTotal(RankState s) {
        long pending = 0;
        for (RankEvent e : s.outbox) pending += e.ap;
        if (s.serverAp >= 0) s.totalAp = Math.max(s.totalAp, s.serverAp + pending);
    }

    // ------------------------------------------------------------------ game hook

    /**
     * Called by {@code LaunchService} right after the game process starts.
     *
     * @param serverAddress the server joined via the Servers page, or null
     */
    public void onGameLaunched(Process process, String versionId, String serverAddress) {
        try {
            PlaytimeTracker t;
            synchronized (lock) { t = tracker; }
            if (t != null && process != null) t.register(process, versionId, serverAddress);
        } catch (Throwable ex) {
            AppLogger.get().warn(TAG, "Could not start playtime tracking: " + ex);
        }
    }

    // ------------------------------------------------------------------ PlaytimeTracker.Sink

    @Override
    public boolean canEarn() {
        // Deliberately does not ask AveroId: AveroId.signedIn() holds its own lock while notifying us, so
        // calling back into it from under our lock could deadlock. `state` is set exactly while signed in.
        synchronized (lock) { return state != null; }
    }

    @Override
    public void credit(PlaytimeTracker.Session s, AfkDetector.Tick tick, long before) {
        List<QuestView> completedViews = new ArrayList<>();
        boolean promoted;
        RankStanding to = null;
        synchronized (lock) {
            if (state == null) return;
            String src = s.lastSource == null ? "none" : s.lastSource;
            Outcome o = engine.creditPlaytime(state, Instant.now(),
                    new Credit(s.id, tick.verifiedMs(), tick.afkMs(), src, s.serverAddress, before, s.verifiedMs));
            collectCompleted(o.quests(), completedViews);
            to = checkPromotion();
            promoted = to != null;
            store.save(state);
        }
        for (QuestView q : completedViews) for (RankListener l : listeners) safe(() -> l.onQuestCompleted(q));
        if (promoted) { RankStanding t = to; for (RankListener l : listeners) safe(() -> l.onPromotion(t)); }
        fireChanged();
        // Push soon after AP lands so other devices see it, without hammering the server every 15s.
        if (completedViews.size() > 0 || promoted) kick();
    }

    @Override
    public void sessionEnded(PlaytimeTracker.Session s) {
        synchronized (lock) {
            if (state != null) { state.lifetimeSessions++; store.save(state); }
        }
        fireChanged();
        kick();
    }

    private void collectCompleted(List<QuestDone> done, List<QuestView> out) {
        for (QuestDone d : done) {
            RankConfig.QuestDef q = cfg.quest(d.questId);
            if (q != null) out.add(new QuestView(q.id, q.type, q.title, q.description, q.rewardAp, q.target, q.target, false, true, 0));
        }
    }

    /** Returns the new standing if the player just reached a higher one than was last announced. */
    private RankStanding checkPromotion() {
        RankStanding now = RankCalculator.standing(cfg, state.totalAp);
        if (state.announcedOrdinal < 0) { state.announcedOrdinal = now.ordinal(); return null; }
        if (now.ordinal() > state.announcedOrdinal) { state.announcedOrdinal = now.ordinal(); return now; }
        return null;
    }

    // ------------------------------------------------------------------ views

    public RankSnapshot snapshot() {
        synchronized (lock) {
            if (!bound || cfg == null) return RankSnapshot.signedOut(RankConfig.bundled());
            if (state == null) return RankSnapshot.signedOut(cfg);
            Instant now = Instant.now();
            engine.rollover(state, now);
            LocalDate eff = RankEngine.effectiveDay(state, now);
            long resets = eff.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
            RankSnapshot.SyncStatus st;
            if (serverMissing || AveroId.get().api().isDemo()) st = RankSnapshot.SyncStatus.LOCAL_ONLY;
            else if (offline) st = RankSnapshot.SyncStatus.OFFLINE;
            else if (!state.outbox.isEmpty()) st = RankSnapshot.SyncStatus.PENDING;
            else st = RankSnapshot.SyncStatus.SYNCED;
            List<RankState.LedgerEntry> recent = new ArrayList<>(state.ledger);
            Collections.reverse(recent);
            return new RankSnapshot(true, state.totalAp, RankCalculator.standing(cfg, state.totalAp),
                    cfg.playtime.dailyCapAp, state.dayPlaytimeAp, state.dayVerifiedMs, state.dayAfkMs, resets,
                    engine.views(state, now), tracker.status(), st, state.outbox.size(),
                    recent.size() > 8 ? recent.subList(0, 8) : recent, cfg);
        }
    }

    private void fireChanged() {
        if (listeners.isEmpty()) return;
        RankSnapshot snap = snapshot();
        for (RankListener l : listeners) safe(() -> l.onChanged(snap));
    }

    private static void safe(Runnable r) { try { r.run(); } catch (Throwable ignored) { } }

    /** Asks for a fresh server read (used when the Ranks page opens). Returns immediately. */
    public void refresh() {
        // An explicit user action (opening the Ranks page) overrides any back-off, so a freshly deployed server is noticed at once.
        synchronized (lock) { if (!bound || state == null) return; lastPullAt = 0; retryNotBefore = 0; }
        kick();
    }

    // ------------------------------------------------------------------ sync

    private void kick() {
        try { ScheduledExecutorService e = exec; if (e != null) e.execute(this::syncTick); } catch (Throwable ignored) { }
    }

    private void syncTick() {
        try { syncOnce(false); } catch (Throwable t) { AppLogger.get().warn(TAG, "sync failed: " + t); }
    }

    private void syncOnce(boolean force) {
        List<RankEvent> batch;
        boolean pull;
        String uid;
        synchronized (lock) {
            if (state == null || syncing) return;
            long now = System.currentTimeMillis();
            if (!force && now < retryNotBefore) return;
            if (AveroId.get().api().isDemo()) { serverMissing = true; return; }
            syncing = true;
            uid = state.userId;
            batch = new ArrayList<>();
            for (RankEvent e : state.outbox) {
                if (batch.size() >= PUSH_BATCH) break;
                e.inFlight = true;
                batch.add(copy(e));
            }
            pull = batch.isEmpty() && (now - lastPullAt > PULL_EVERY_MS || lastPullAt == 0);
            if (batch.isEmpty() && !pull) { syncing = false; return; }
        }

        RankSync.Result r = batch.isEmpty() ? sync.pull() : sync.push(batch, cfg.version);
        List<QuestView> newlyDone = new ArrayList<>();
        RankStanding promo = null;
        boolean linkCheck = false;
        synchronized (lock) {
            syncing = false;
            if (state == null || !Objects.equals(state.userId, uid)) return;
            for (RankEvent e : state.outbox) e.inFlight = false;
            long now = System.currentTimeMillis();
            if (r.ok()) {
                offline = false; serverMissing = false; backoffS = 0; retryNotBefore = 0;
                if (batch.isEmpty()) lastPullAt = now;
                if (r.snapshot().rejected() != null) {
                    for (Map.Entry<String, String> en : r.snapshot().rejected().entrySet()) {
                        AppLogger.get().info(TAG, "Server declined " + en.getKey() + ": " + en.getValue());
                    }
                }
                engine.mergeServer(state, r.snapshot(), Instant.now());
                promo = checkPromotion();
                store.save(state);
                linkCheck = now - linkCheckedAt > PULL_EVERY_MS;
            } else if (r.notSupported()) {
                serverMissing = true;
                retryNotBefore = now + NOT_SUPPORTED_RETRY_MS;
                AppLogger.get().info(TAG, "Avero server has no rank endpoints yet; running local-only.");
            } else {
                offline = true;
                backoffS = Math.min(300, Math.max(30, backoffS * 2));
                retryNotBefore = now + backoffS * 1000L;
            }
        }
        if (promo != null) { RankStanding t = promo; for (RankListener l : listeners) safe(() -> l.onPromotion(t)); }
        fireChanged();
        if (linkCheck) checkMicrosoftLink();
        // More queued than one batch: keep draining without waiting a full period.
        synchronized (lock) {
            if (r.ok() && state != null && state.outbox.size() > 0 && batch.size() == PUSH_BATCH) kick();
        }
    }

    private static RankEvent copy(RankEvent e) {
        RankEvent c = new RankEvent();
        c.id = e.id; c.type = e.type; c.at = e.at; c.ap = e.ap; c.day = e.day; c.sessionId = e.sessionId;
        c.verifiedSeconds = e.verifiedSeconds; c.afkSeconds = e.afkSeconds; c.source = e.source;
        c.serverAddress = e.serverAddress; c.questId = e.questId; c.periodKey = e.periodKey;
        return c;
    }

    /** One-time "Verified Player" quest: any Avero-linked player name the server has verified with Mojang. */
    private void checkMicrosoftLink() {
        try {
            synchronized (lock) { linkCheckedAt = System.currentTimeMillis(); }
            AveroId.get().api().me().whenComplete((user, err) -> {
                if (err != null || user == null || user.profiles == null) return;
                boolean verified = user.profiles.stream().anyMatch(p -> p.verified);
                if (!verified) return;
                List<QuestView> done = new ArrayList<>();
                RankStanding promo;
                synchronized (lock) {
                    if (state == null) return;
                    Outcome o = engine.onMicrosoftLinked(state, Instant.now());
                    if (o.quests().isEmpty()) return;
                    collectCompleted(o.quests(), done);
                    promo = checkPromotion();
                    store.save(state);
                }
                for (QuestView q : done) for (RankListener l : listeners) safe(() -> l.onQuestCompleted(q));
                if (promo != null) { RankStanding t = promo; for (RankListener l : listeners) safe(() -> l.onPromotion(t)); }
                fireChanged();
                kick();
            });
        } catch (Throwable t) {
            AppLogger.get().warn(TAG, "link check failed: " + t);
        }
    }
}
