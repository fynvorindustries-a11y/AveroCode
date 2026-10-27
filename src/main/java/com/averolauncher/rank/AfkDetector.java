package com.averolauncher.rank;

/**
 * Per-session activity judge. Pure logic over {@link ActivitySample}s, driven by a monotonic clock, so it
 * is deterministic in tests. One instance per game session.
 *
 * <p>Rules, in order:
 * <ol>
 *   <li>The first {@code startupGraceSeconds} (loading screens, instant kills) never count.</li>
 *   <li>One game session earns for at most {@code sessionLimitMinutes} (0 = no limit): a game left running
 *       overnight stops counting; quitting and starting the game again begins a new session.</li>
 *   <li>A tick gap longer than {@code maxTickGapSeconds} (sleep, hibernate, debugger pause) is discarded.</li>
 *   <li>No input for {@code idleThresholdSeconds} = AFK.</li>
 *   <li>Game window in the background for longer than {@code backgroundGraceSeconds} = AFK
 *       (alt-tabbing to read a wiki is fine; leaving the game running behind other windows is not).</li>
 *   <li>Input that never pauses for {@code macroWindowMinutes} (no gap of {@code macroMinGapSeconds}) looks
 *       scripted: credit stops until the player naturally pauses.</li>
 *   <li>No presence info at all: {@code unknownActivityPolicy} decides (ALLOW leans on the daily cap).</li>
 * </ol>
 */
public final class AfkDetector {

    public enum Verdict {
        STARTING(false, false), ACTIVE(true, false), UNVERIFIED_ALLOWED(true, false),
        AFK_IDLE(false, true), AFK_BACKGROUND(false, true), MACRO_SUSPECT(false, true), SESSION_LIMIT(false, false),
        SUSPENDED_GAP(false, false), UNVERIFIED_DENIED(false, false), DUPLICATE_INSTANCE(false, false), ENDED(false, false);

        public final boolean counts;
        public final boolean afk;
        Verdict(boolean counts, boolean afk) { this.counts = counts; this.afk = afk; }
    }

    /** Result of one tick: how much to credit as verified, and how much to record as AFK. */
    public record Tick(Verdict verdict, long verifiedMs, long afkMs) { }

    private final RankConfig.Activity cfg;
    private final long startNano;
    private long lastNano;
    private long unfocusedMs;
    private long lastPauseNano;

    public AfkDetector(RankConfig.Activity cfg, long startNano) {
        this.cfg = cfg;
        this.startNano = startNano;
        this.lastNano = startNano;
        this.lastPauseNano = startNano;
    }

    public Tick step(long nowNano, boolean alive, ActivitySample sample) {
        long elapsedMs = Math.max(0, (nowNano - lastNano) / 1_000_000L);
        lastNano = nowNano;
        if (!alive) return new Tick(Verdict.ENDED, 0, 0);
        if (nowNano - startNano < cfg.startupGraceSeconds * 1_000_000_000L) {
            lastPauseNano = nowNano;
            return new Tick(Verdict.STARTING, 0, 0);
        }
        if (elapsedMs > cfg.maxTickGapSeconds * 1000L) {
            lastPauseNano = nowNano;
            return new Tick(Verdict.SUSPENDED_GAP, 0, 0);
        }
        if (cfg.sessionLimitMinutes > 0 && nowNano - startNano > cfg.sessionLimitMinutes * 60_000_000_000L) {
            return new Tick(Verdict.SESSION_LIMIT, 0, 0);
        }
        ActivitySample s = sample == null ? ActivitySample.unknown() : sample;

        if (!s.hasInfo()) {
            boolean allow = !"DENY".equalsIgnoreCase(cfg.unknownActivityPolicy);
            return allow ? new Tick(Verdict.UNVERIFIED_ALLOWED, elapsedMs, 0) : new Tick(Verdict.UNVERIFIED_DENIED, 0, 0);
        }

        if (s.idleMs() != null) {
            if (s.idleMs() >= cfg.macroMinGapSeconds * 1000L) lastPauseNano = nowNano;
            if (s.idleMs() >= cfg.idleThresholdSeconds * 1000L) return new Tick(Verdict.AFK_IDLE, 0, elapsedMs);
        }
        if (Boolean.FALSE.equals(s.gameFocused())) {
            unfocusedMs += elapsedMs;
            if (unfocusedMs > cfg.backgroundGraceSeconds * 1000L) return new Tick(Verdict.AFK_BACKGROUND, 0, elapsedMs);
        } else if (Boolean.TRUE.equals(s.gameFocused())) {
            unfocusedMs = 0;
        }
        if (s.idleMs() != null && nowNano - lastPauseNano > cfg.macroWindowMinutes * 60_000_000_000L) {
            return new Tick(Verdict.MACRO_SUSPECT, 0, elapsedMs);
        }
        return new Tick(Verdict.ACTIVE, elapsedMs, 0);
    }
}
