package com.averolauncher.rank;

import com.averolauncher.rank.RankConfig.QuestDef;
import com.averolauncher.rank.RankState.LedgerEntry;
import com.averolauncher.rank.RankState.QuestDone;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/**
 * The economy rules, with no I/O and no threads, so every rule is unit-testable:
 * playtime -> AP (with the daily cap and carry-over), quest rotation, quest progress, and
 * duplicate-proof quest rewards. Mutates the {@link RankState} it is handed; callers hold the lock.
 *
 * <p>Days are UTC and only move forward ({@link #effectiveDay}), so changing the PC clock can neither
 * reset the daily cap nor re-open yesterday's quests.
 */
public final class RankEngine {

    public static final int MAX_OUTBOX = 5000;
    private static final int MAX_LEDGER = 40;
    private static final int MAX_DONE = 1000;

    /** One slice of verified playtime to credit. */
    public record Credit(String sessionId, long verifiedMs, long afkMs, String source, String serverAddress,
                         long sessionVerifiedBeforeMs, long sessionVerifiedAfterMs) { }

    /** What a credit produced. */
    public record Outcome(int playtimeAp, List<QuestDone> quests) {
        public int questAp() { return quests.stream().mapToInt(q -> q.ap).sum(); }
        public int totalAp() { return playtimeAp + questAp(); }
    }

    /** What the server told us (all fields optional). */
    public record ServerSnapshot(Long totalAp, String day, Integer dayPlaytimeAp, List<QuestDone> completed,
                                 Set<String> acceptedIds, Map<String, String> rejected) { }

    private final RankConfig cfg;

    public RankEngine(RankConfig cfg) { this.cfg = cfg; }

    public RankConfig config() { return cfg; }

    // ------------------------------------------------------------------ time

    public static String dayKey(LocalDate d) { return d.toString(); }

    public static String weekKey(LocalDate d) {
        return d.get(IsoFields.WEEK_BASED_YEAR) + "-W" + String.format("%02d", d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    /** The later of the real UTC date and the last date we have seen. */
    public static LocalDate effectiveDay(RankState s, Instant now) {
        LocalDate real = LocalDate.ofInstant(now, ZoneOffset.UTC);
        if (s.day == null) return real;
        LocalDate seen = LocalDate.parse(s.day);
        return real.isAfter(seen) ? real : seen;
    }

    /** Rolls the daily ledger forward if a new day has started and drops expired quest progress. */
    public void rollover(RankState s, Instant now) {
        LocalDate eff = effectiveDay(s, now);
        String today = dayKey(eff);
        if (!today.equals(s.day)) {
            s.day = today;
            s.dayPlaytimeAp = 0;
            s.carryMs = 0;
            s.dayVerifiedMs = 0;
            s.dayAfkMs = 0;
        }
        String week = weekKey(eff);
        s.progress.keySet().removeIf(key -> {
            int at = key.indexOf('@');
            if (at < 0) return true;
            QuestDef q = cfg.quest(key.substring(0, at));
            if (q == null) return true;
            String period = key.substring(at + 1);
            return switch (q.type) {
                case "DAILY" -> !period.equals(today);
                case "WEEKLY" -> !period.equals(week);
                default -> false;
            };
        });
    }

    // ------------------------------------------------------------------ playtime

    public Outcome creditPlaytime(RankState s, Instant now, Credit c) {
        rollover(s, now);
        long nowMs = now.toEpochMilli();
        int ap = 0;

        if (c.verifiedMs() > 0) {
            s.dayVerifiedMs += c.verifiedMs();
            s.lifetimeVerifiedMs += c.verifiedMs();
            s.carryMs += c.verifiedMs();
            long block = cfg.playtime.minutesPerAp * 60_000L;
            while (s.carryMs >= block) {
                s.carryMs -= block;
                int room = Math.max(0, cfg.playtime.dailyCapAp - s.dayPlaytimeAp);
                int give = Math.min(cfg.playtime.apPerBlock, room);
                s.dayPlaytimeAp += give;
                ap += give;
            }
            // At the cap, partial progress toward the next block must not be banked for tomorrow.
            if (s.dayPlaytimeAp >= cfg.playtime.dailyCapAp) s.carryMs = 0;
        }
        if (c.afkMs() > 0) {
            s.dayAfkMs += c.afkMs();
            s.lifetimeAfkMs += c.afkMs();
        }
        if (ap > 0) {
            s.totalAp += ap;
            addLedger(s, new LedgerEntry(nowMs, "PLAYTIME", ap, "Verified playtime"));
        }
        if (c.verifiedMs() > 0 || c.afkMs() > 0) recordPlaytimeEvent(s, now, c, ap);

        List<QuestDone> done = new ArrayList<>();
        if (c.verifiedMs() > 0) advanceQuests(s, now, c, done);
        return new Outcome(ap, done);
    }

    private void recordPlaytimeEvent(RankState s, Instant now, Credit c, int ap) {
        long nowMs = now.toEpochMilli();
        // Merge into the newest PLAYTIME event (quest events may sit after it), so one session stays one event.
        RankEvent last = null;
        for (int i = s.outbox.size() - 1; i >= 0; i--) {
            if ("PLAYTIME".equals(s.outbox.get(i).type)) { last = s.outbox.get(i); break; }
        }
        long vSec = Math.round(c.verifiedMs() / 1000.0), aSec = Math.round(c.afkMs() / 1000.0);
        if (last != null && "PLAYTIME".equals(last.type) && !last.inFlight
                && Objects.equals(last.sessionId, c.sessionId()) && Objects.equals(last.day, s.day)
                && Objects.equals(last.serverAddress, c.serverAddress())) {
            last.verifiedSeconds += vSec;
            last.afkSeconds += aSec;
            last.ap += ap;
            last.at = nowMs;
            return;
        }
        RankEvent e = new RankEvent();
        long stamp = nowMs;
        Set<String> ids = new HashSet<>();
        for (RankEvent x : s.outbox) ids.add(x.id);
        while (ids.contains("pt:" + c.sessionId() + ":" + stamp)) stamp++;
        e.id = "pt:" + c.sessionId() + ":" + stamp;
        e.type = "PLAYTIME";
        e.at = nowMs;
        e.ap = ap;
        e.day = s.day;
        e.sessionId = c.sessionId();
        e.verifiedSeconds = vSec;
        e.afkSeconds = aSec;
        e.source = c.source();
        e.serverAddress = c.serverAddress();
        s.outbox.add(e);
        while (s.outbox.size() > MAX_OUTBOX) s.outbox.remove(0);
    }

    // ------------------------------------------------------------------ quests

    /** Quests available right now for this state, in display order (daily, weekly, special). */
    public List<QuestView> views(RankState s, Instant now) {
        LocalDate eff = effectiveDay(s, now);
        List<QuestView> out = new ArrayList<>();
        for (QuestDef q : activeQuests(eff, now)) {
            String period = periodKey(q, eff);
            boolean done = isCompleted(s, q.id, period);
            long prog = s.progress.getOrDefault(q.id + "@" + period, 0L);
            boolean timed = isTimeBased(q);
            long target = timed ? q.target * 60_000L : q.target;
            out.add(new QuestView(q.id, q.type, q.title, q.description, q.rewardAp,
                    done ? target : Math.min(prog, target), target, timed, done, endsAt(q, eff)));
        }
        return out;
    }

    public List<QuestDef> activeQuests(LocalDate eff, Instant now) {
        List<QuestDef> out = new ArrayList<>();
        out.addAll(pick("DAILY", cfg.quests.dailyPerDay, dayKey(eff)));
        out.addAll(pick("WEEKLY", cfg.quests.weeklyPerWeek, weekKey(eff)));
        for (QuestDef q : cfg.quests.definitions) {
            if ("SPECIAL".equals(q.type) && specialOpen(q, now)) out.add(q);
        }
        return out;
    }

    /** Deterministic rotation: the same period yields the same quests for every player and for the server. */
    public List<QuestDef> pick(String type, int count, String periodKey) {
        List<QuestDef> pool = new ArrayList<>();
        for (QuestDef q : cfg.quests.definitions) if (type.equals(q.type)) pool.add(q);
        pool.sort(Comparator.comparing((QuestDef q) -> sha(periodKey + ":" + q.id)));
        return new ArrayList<>(pool.subList(0, Math.min(Math.max(0, count), pool.size())));
    }

    private static String sha(String in) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(in.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static boolean specialOpen(QuestDef q, Instant now) {
        try {
            if (q.startsAt != null && now.isBefore(Instant.parse(q.startsAt))) return false;
            if (q.endsAt != null && now.isAfter(Instant.parse(q.endsAt))) return false;
        } catch (Exception ignored) { }
        return true;
    }

    private static String periodKey(QuestDef q, LocalDate eff) {
        return switch (q.type) { case "DAILY" -> dayKey(eff); case "WEEKLY" -> weekKey(eff); default -> "once"; };
    }

    private static long endsAt(QuestDef q, LocalDate eff) {
        return switch (q.type) {
            case "DAILY" -> eff.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
            case "WEEKLY" -> eff.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
            default -> { try { yield q.endsAt == null ? 0 : Instant.parse(q.endsAt).toEpochMilli(); } catch (Exception e) { yield 0; } }
        };
    }

    private static boolean isTimeBased(QuestDef q) {
        return q.objective.equals("PLAY_MINUTES") || q.objective.equals("PLAY_SERVER_MINUTES") || q.objective.equals("SESSION_MINUTES");
    }

    public static boolean isCompleted(RankState s, String questId, String periodKey) {
        for (QuestDone d : s.completed) if (d.questId.equals(questId) && d.periodKey.equals(periodKey)) return true;
        return false;
    }

    private void advanceQuests(RankState s, Instant now, Credit c, List<QuestDone> done) {
        LocalDate eff = effectiveDay(s, now);
        for (QuestDef q : activeQuests(eff, now)) {
            String period = periodKey(q, eff);
            if (isCompleted(s, q.id, period)) continue;
            String key = q.id + "@" + period;
            long cur = s.progress.getOrDefault(key, 0L);
            long next = cur;
            switch (q.objective) {
                case "PLAY_MINUTES" -> next = cur + c.verifiedMs();
                case "PLAY_SERVER_MINUTES" -> { if (c.serverAddress() != null) next = cur + c.verifiedMs(); }
                case "SESSION_MINUTES" -> next = Math.max(cur, c.sessionVerifiedAfterMs());
                case "SESSIONS" -> {
                    long need = q.minMinutes * 60_000L;
                    if (c.sessionVerifiedBeforeMs() < need && c.sessionVerifiedAfterMs() >= need) next = cur + 1;
                }
                default -> { }
            }
            if (next == cur) continue;
            s.progress.put(key, next);
            long target = isTimeBased(q) ? q.target * 60_000L : q.target;
            if (next >= target) done.add(award(s, q, period, now));
        }
    }

    /** Completes the one-time "link a Microsoft account" quest if it is open. */
    public Outcome onMicrosoftLinked(RankState s, Instant now) {
        rollover(s, now);
        List<QuestDone> done = new ArrayList<>();
        LocalDate eff = effectiveDay(s, now);
        for (QuestDef q : activeQuests(eff, now)) {
            if (!"LINK_MICROSOFT".equals(q.objective)) continue;
            String period = periodKey(q, eff);
            if (isCompleted(s, q.id, period)) continue;
            s.progress.put(q.id + "@" + period, 1L);
            done.add(award(s, q, period, now));
        }
        return new Outcome(0, done);
    }

    /** Grants a quest reward exactly once per (quest, period). Callers have already checked {@link #isCompleted}. */
    private QuestDone award(RankState s, QuestDef q, String period, Instant now) {
        long nowMs = now.toEpochMilli();
        QuestDone d = new QuestDone(q.id, period, q.rewardAp, nowMs);
        s.completed.add(d);
        while (s.completed.size() > MAX_DONE) s.completed.remove(0);
        s.totalAp += q.rewardAp;
        addLedger(s, new LedgerEntry(nowMs, "QUEST", q.rewardAp, q.title));
        RankEvent e = new RankEvent();
        e.id = "q:" + q.id + ":" + period;
        e.type = "QUEST";
        e.at = nowMs;
        e.ap = q.rewardAp;
        e.day = s.day;
        e.questId = q.id;
        e.periodKey = period;
        boolean dupe = false;
        for (RankEvent x : s.outbox) if (x.id.equals(e.id)) { dupe = true; break; }
        if (!dupe) s.outbox.add(e);
        return d;
    }

    private static void addLedger(RankState s, LedgerEntry e) {
        s.ledger.add(e);
        while (s.ledger.size() > MAX_LEDGER) s.ledger.remove(0);
    }

    // ------------------------------------------------------------------ server reconcile

    /** Folds an authoritative server answer into local state. */
    public void mergeServer(RankState s, ServerSnapshot snap, Instant now) {
        if (snap.acceptedIds() != null || snap.rejected() != null) {
            s.outbox.removeIf(e -> (snap.acceptedIds() != null && snap.acceptedIds().contains(e.id))
                    || (snap.rejected() != null && snap.rejected().containsKey(e.id)));
        }
        for (RankEvent e : s.outbox) e.inFlight = false;
        rollover(s, now);
        if (snap.day() != null && snap.dayPlaytimeAp() != null && snap.day().equals(s.day)) {
            s.dayPlaytimeAp = Math.max(s.dayPlaytimeAp, snap.dayPlaytimeAp());
        }
        if (snap.completed() != null) {
            for (QuestDone d : snap.completed()) {
                if (d.questId != null && d.periodKey != null && !isCompleted(s, d.questId, d.periodKey)) s.completed.add(d);
            }
        }
        if (snap.totalAp() != null) {
            s.serverAp = snap.totalAp();
            long pending = 0;
            for (RankEvent e : s.outbox) pending += e.ap;
            s.totalAp = s.serverAp + pending;
        }
    }
}
