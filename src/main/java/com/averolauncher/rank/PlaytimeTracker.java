package com.averolauncher.rank;

import com.averolauncher.logging.AppLogger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Watches the game processes this launcher started and turns their running time into <i>verified</i>
 * playtime. A process only earns time while it is alive, past its startup grace, and the player is
 * actually there ({@link AfkDetector}). Only one instance accrues at a time, so opening several
 * clients never stacks time. Time while the launcher itself is closed is never counted.
 */
public final class PlaytimeTracker {

    private static final String TAG = "PlaytimeTracker";

    /** Receives accounting results. */
    public interface Sink {
        /** Called for every tick that produced verified or AFK time. Session totals already include this tick. */
        void credit(Session session, AfkDetector.Tick tick, long sessionVerifiedBeforeMs);
        void sessionEnded(Session session);
        boolean canEarn();
    }

    /** What the UI shows about the live session. */
    public record Status(boolean playing, AfkDetector.Verdict verdict, String source, int instances) {
        public static Status idle() { return new Status(false, null, "none", 0); }
    }

    public static final class Session {
        public final String id = UUID.randomUUID().toString();
        public final String versionId;
        public final String serverAddress;
        public final long pid;
        public final Instant startedAt = Instant.now();
        final Process process;
        final AfkDetector detector;
        public long verifiedMs;
        public long afkMs;
        volatile AfkDetector.Verdict lastVerdict = AfkDetector.Verdict.STARTING;
        volatile String lastSource = "none";

        Session(Process p, String versionId, String serverAddress, AfkDetector d) {
            this.process = p; this.versionId = versionId; this.serverAddress = serverAddress;
            this.pid = p.pid(); this.detector = d;
        }
    }

    private final RankConfig cfg;
    private final Sink sink;
    private final LongSupplier nanoClock;
    private final java.util.function.Supplier<ActivityProbe> probeFactory;
    private final List<Session> sessions = new ArrayList<>();
    private ScheduledExecutorService exec;
    private ActivityProbe probe;

    public PlaytimeTracker(RankConfig cfg, Sink sink) {
        this(cfg, sink, System::nanoTime,
                () -> p -> ActivitySample.unknown()); // no keyboard/mouse/window probing: the launcher never looks at the PC
    }

    /** Test seam: inject the clock and probe. */
    public PlaytimeTracker(RankConfig cfg, Sink sink, LongSupplier nanoClock, java.util.function.Supplier<ActivityProbe> probeFactory) {
        this.cfg = cfg; this.sink = sink; this.nanoClock = nanoClock; this.probeFactory = probeFactory;
    }

    public synchronized Session register(Process p, String versionId, String serverAddress) {
        Session s = new Session(p, versionId, serverAddress, new AfkDetector(cfg.activity, nanoClock.getAsLong()));
        sessions.add(s);
        if (probe == null) probe = probeFactory.get();
        if (exec == null) {
            exec = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "avero-rank-playtime");
                t.setDaemon(true);
                return t;
            });
            exec.scheduleWithFixedDelay(this::safeTick, cfg.activity.tickSeconds, cfg.activity.tickSeconds, TimeUnit.SECONDS);
        }
        AppLogger.get().info(TAG, "Tracking game session " + s.id + " (pid " + s.pid + ")");
        return s;
    }

    public synchronized Status status() {
        if (sessions.isEmpty()) return Status.idle();
        Session lead = sessions.get(0);
        return new Status(true, lead.lastVerdict, lead.lastSource, sessions.size());
    }

    private void safeTick() {
        try { tick(); } catch (Throwable t) { AppLogger.get().warn(TAG, "tick failed: " + t); }
    }

    /** One accounting pass over every live session. Public so tests can drive it without a scheduler. */
    public void tick() {
        List<Session> snapshot;
        synchronized (this) { snapshot = new ArrayList<>(sessions); }
        long now = nanoClock.getAsLong();
        boolean earner = sink.canEarn();
        boolean creditedOne = false;
        List<Session> ended = new ArrayList<>();

        for (Session s : snapshot) {
            boolean alive = s.process.isAlive();
            ActivitySample sample = alive && probe != null ? probe.sample(s.pid) : null;
            AfkDetector.Tick t = s.detector.step(now, alive, sample);
            s.lastSource = sample == null ? "none" : sample.source();
            if (!alive) { ended.add(s); s.lastVerdict = AfkDetector.Verdict.ENDED; continue; }

            // Signed-out players and extra instances still advance their detectors but earn nothing.
            if (t.verdict().counts && (!earner || creditedOne)) {
                t = new AfkDetector.Tick(earner ? AfkDetector.Verdict.DUPLICATE_INSTANCE : t.verdict(), 0, 0);
            }
            s.lastVerdict = t.verdict();
            if (t.verifiedMs() > 0) creditedOne = true;
            if (t.verifiedMs() > 0 || t.afkMs() > 0) {
                long before = s.verifiedMs;
                s.verifiedMs += t.verifiedMs();
                s.afkMs += t.afkMs();
                if (earner) sink.credit(s, t, before);
            }
        }
        if (!ended.isEmpty()) {
            for (Session s : ended) {
                try { sink.sessionEnded(s); } catch (Throwable ex) { AppLogger.get().warn(TAG, "sessionEnded failed: " + ex); }
            }
            synchronized (this) {
                sessions.removeAll(ended);
                if (sessions.isEmpty()) {
                    if (probe != null) { probe.close(); probe = null; }
                    if (exec != null) { exec.shutdown(); exec = null; }
                }
            }
        }
    }

    public synchronized void shutdown() {
        if (exec != null) { exec.shutdownNow(); exec = null; }
        if (probe != null) { probe.close(); probe = null; }
        sessions.clear();
    }
}
