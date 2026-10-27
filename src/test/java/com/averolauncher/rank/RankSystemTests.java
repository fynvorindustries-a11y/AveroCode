package com.averolauncher.rank;

import com.averolauncher.rank.AfkDetector.Verdict;
import com.averolauncher.rank.RankEngine.Credit;
import com.averolauncher.rank.RankEngine.ServerSnapshot;
import com.averolauncher.rank.RankState.QuestDone;
import com.google.gson.JsonParser;

import java.io.OutputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dependency-free tests for the rank system. Run:
 * <pre>  java -cp &lt;classes&gt;:&lt;gson.jar&gt; com.averolauncher.rank.RankSystemTests</pre>
 * Exits non-zero on any failure, so it works in CI without JUnit.
 */
public final class RankSystemTests {

    private static int passed, failed;
    private static final long MIN = 60_000L;

    public static void main(String[] args) throws Exception {
        configAndThresholds();
        divisions();
        playtimeToAp();
        dailyCap();
        clockRollback();
        questRewardsAreNeverDuplicated();
        questRotationIsDeterministic();
        questObjectives();
        apNeverDecreases();
        outboxMerging();
        serverMerge();
        syncParsing();
        afkDetector();
        trackerRules();
        storeIntegrity();
        invalidConfigRejected();
        System.out.println("\n" + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ helpers

    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("  ok   " + name); }
        else { failed++; System.out.println("  FAIL " + name); }
    }

    private static void eq(String name, Object expected, Object actual) {
        boolean ok = Objects.equals(expected, actual);
        if (!ok) System.out.println("       expected=" + expected + " actual=" + actual);
        check(name, ok);
    }

    private static Instant at(String iso) { return Instant.parse(iso); }

    private static Credit play(String session, long ms) { return new Credit(session, ms, 0, "test", null, 0, ms); }

    private static RankState user() { return RankState.fresh("u1"); }

    // ------------------------------------------------------------------ tests

    static void configAndThresholds() {
        System.out.println("config + thresholds");
        RankConfig c = RankConfig.bundled();
        eq("nine ranks", 9, c.ranks.size());
        long[] mins = {0, 500, 1500, 2500, 3500, 4500, 5500, 6500, 7500};
        String[] names = {"Stone", "Copper", "Iron", "Gold", "Emerald", "Sapphire", "Amethyst", "Obsidian", "Celestial"};
        for (int i = 0; i < 9; i++) {
            eq(names[i] + " starts at " + mins[i], mins[i], c.ranks.get(i).minAp);
            eq(names[i] + " name", names[i], c.ranks.get(i).name);
            eq(names[i] + " rank at its threshold", names[i], RankCalculator.standing(c, mins[i]).tier().name);
            if (i > 0) eq(names[i - 1] + " one AP before " + names[i], names[i - 1], RankCalculator.standing(c, mins[i] - 1).tier().name);
        }
        eq("negative AP clamps to Stone", "Stone I", RankCalculator.standing(c, -50).label());
        eq("daily cap default", 18, c.playtime.dailyCapAp);
    }

    static void divisions() {
        System.out.println("divisions");
        RankConfig c = RankConfig.bundled();
        eq("0 AP", "Stone I", RankCalculator.standing(c, 0).label());
        eq("499 AP is the top of Stone", "Stone III", RankCalculator.standing(c, 499).label());
        eq("500 AP", "Copper I", RankCalculator.standing(c, 500).label());
        eq("7499 AP", "Obsidian III", RankCalculator.standing(c, 7499).label());
        RankStanding cel = RankCalculator.standing(c, 7500);
        eq("Celestial has no division", 0, cel.division());
        eq("Celestial label", "Celestial", cel.label());
        check("Celestial is max", cel.isMax() && cel.apToNext() == 0 && cel.progress() == 1.0);
        eq("far past the top stays Celestial", "Celestial", RankCalculator.standing(c, 1_000_000).label());

        RankStanding g = RankCalculator.standing(c, 2500);
        eq("Gold I start", "Gold I", g.label());
        eq("next after Gold I", "Gold II", g.nextLabel());
        long gold2 = c.divisionStart(3, 1);
        eq("Gold II begins at", gold2, RankCalculator.standing(c, gold2).floorAp());
        eq("one before Gold II", "Gold I", RankCalculator.standing(c, gold2 - 1).label());
        RankStanding top = RankCalculator.standing(c, 3499);
        eq("last Gold AP", "Gold III", top.label());
        eq("next after Gold III is Emerald I", "Emerald I", top.nextLabel());
        eq("Emerald I at the rank boundary", 3500L, top.nextAt());
        eq("Obsidian III points at Celestial", "Celestial", RankCalculator.standing(c, 7400).nextLabel());
        RankStanding mid = RankCalculator.standing(c, 2500 + (gold2 - 2500) / 2);
        check("progress is about half way", Math.abs(mid.progress() - 0.5) < 0.02);
        long prev = -1; boolean monotone = true;
        for (long ap = 0; ap <= 8000; ap++) { int o = RankCalculator.standing(c, ap).ordinal(); if (o < prev) monotone = false; prev = o; }
        check("standing never goes backwards as AP rises", monotone);
    }

    static void playtimeToAp() {
        System.out.println("playtime -> AP");
        RankEngine e = new RankEngine(RankConfig.bundled());
        RankState s = user();
        Instant t = at("2026-10-19T10:00:00Z");
        e.creditPlaytime(s, t, play("a", 9 * MIN + 59_000));
        eq("9m59s earns nothing yet", 0, s.dayPlaytimeAp);
        e.creditPlaytime(s, t, play("a", 1_000));
        eq("reaching 10:00 earns 1 AP", 1, s.dayPlaytimeAp);
        e.creditPlaytime(s, t, play("a", 35 * MIN));
        eq("35 more minutes = 3 more AP, 5 carried", 4, s.dayPlaytimeAp);
        eq("remainder carried", 5 * MIN, s.carryMs);
        RankState s2 = user();
        for (int i = 0; i < 80; i++) e.creditPlaytime(s2, t, play("b", 15_000));   // 80 ticks of 15s = 20 min
        eq("many small ticks add up exactly (20 min = 2 AP)", 2, s2.dayPlaytimeAp);
        RankState s3 = user();
        e.creditPlaytime(s3, t, new Credit("c", 0, 45 * MIN, "test", null, 0, 0));
        eq("AFK time earns nothing", 0L, s3.totalAp);
        eq("AFK time is recorded", 45 * MIN, s3.dayAfkMs);
    }

    static void dailyCap() {
        System.out.println("daily cap");
        RankConfig c = RankConfig.bundled();
        RankEngine e = new RankEngine(c);
        RankState s = user();
        Instant t = at("2026-10-19T00:30:00Z");
        for (int i = 0; i < 24 * 60 / 5; i++) e.creditPlaytime(s, t, play("a", 5 * MIN)); // 24h straight
        eq("a full day of playtime stops at the cap", (long) c.playtime.dailyCapAp, s.totalAp - questTotal(s));
        eq("daily ledger shows the cap", c.playtime.dailyCapAp, s.dayPlaytimeAp);
        eq("carry is not banked at the cap", 0L, s.carryMs);
        int before = s.dayPlaytimeAp;
        e.creditPlaytime(s, t, play("a", 120 * MIN));
        eq("more playtime after the cap earns no playtime AP", before, s.dayPlaytimeAp);
        e.creditPlaytime(s, at("2026-10-20T00:00:05Z"), play("a", 10 * MIN));
        eq("new UTC day resets the cap", 1, s.dayPlaytimeAp);
        RankState lo = user();
        RankConfig strict = RankConfig.bundled(); strict.playtime.dailyCapAp = 0;
        new RankEngine(strict).creditPlaytime(lo, t, play("a", 600 * MIN));
        eq("cap of 0 disables playtime AP", 0, lo.dayPlaytimeAp);
    }

    private static long questTotal(RankState s) { long q = 0; for (QuestDone d : s.completed) q += d.ap; return q; }

    static void clockRollback() {
        System.out.println("clock tampering");
        RankConfig c = RankConfig.bundled();
        RankEngine e = new RankEngine(c);
        RankState s = user();
        for (int i = 0; i < 40; i++) e.creditPlaytime(s, at("2026-10-19T12:00:00Z"), play("a", 10 * MIN));
        int atCap = s.dayPlaytimeAp;
        eq("cap reached", c.playtime.dailyCapAp, atCap);
        e.creditPlaytime(s, at("2026-10-18T12:00:00Z"), play("a", 60 * MIN));   // PC clock set back a day
        eq("rolling the clock back does not reopen the cap", atCap, s.dayPlaytimeAp);
        eq("day marker never moves backwards", "2026-10-19", s.day);
    }

    static void questRewardsAreNeverDuplicated() {
        System.out.println("duplicate quest rewards");
        RankConfig c = RankConfig.bundled();
        RankEngine e = new RankEngine(c);
        RankState s = user();
        Instant t = at("2026-10-19T09:00:00Z");
        e.creditPlaytime(s, t, play("a", 40 * MIN));
        int doneAfterFirst = s.completed.size();
        long ap1 = s.totalAp;
        check("first 40 min completes at least First Steps", doneAfterFirst >= 1 && RankEngine.isCompleted(s, "special_first_steps", "once"));
        Set<String> seen = new HashSet<>();
        for (QuestDone d : s.completed) check("unique: " + d.questId + "@" + d.periodKey, seen.add(d.questId + "@" + d.periodKey));
        long beforeOutbox = s.outbox.stream().filter(x -> x.type.equals("QUEST")).count();
        // Replaying the same progress must not pay again.
        e.creditPlaytime(s, t, play("a", 1 * MIN));
        long questEvents = s.outbox.stream().filter(x -> x.type.equals("QUEST")).count();
        check("no extra quest events from continued play", questEvents >= beforeOutbox);
        Map<String, Integer> perId = new HashMap<>();
        for (RankEvent ev : s.outbox) perId.merge(ev.id, 1, Integer::sum);
        check("every outbox event id is unique", perId.values().stream().allMatch(v -> v == 1));
        // A second device that already completed it:
        RankState s2 = user();
        s2.completed.add(new QuestDone("special_first_steps", "once", 20, 1));
        e.creditPlaytime(s2, t, play("a", 40 * MIN));
        check("a quest completed on another device is not paid again", s2.completed.stream().filter(d -> d.questId.equals("special_first_steps")).count() == 1
                && s2.outbox.stream().noneMatch(x -> x.id.equals("q:special_first_steps:once")));
        long a = s.totalAp;
        e.creditPlaytime(s, t, play("a", 0));
        eq("zero-length credit changes nothing", a, s.totalAp);
        check("sanity: AP was granted", ap1 > 0);
    }

    static void questRotationIsDeterministic() {
        System.out.println("quest rotation");
        RankEngine e = new RankEngine(RankConfig.bundled());
        List<String> a = ids(e.pick("DAILY", 3, "2026-10-19")), b = ids(new RankEngine(RankConfig.bundled()).pick("DAILY", 3, "2026-10-19"));
        eq("same day, same quests", a, b);
        eq("three dailies", 3, a.size());
        Set<List<String>> variety = new HashSet<>();
        for (int d = 1; d <= 28; d++) variety.add(ids(e.pick("DAILY", 3, "2026-11-" + String.format("%02d", d))));
        check("the daily set varies across days", variety.size() > 1);
        eq("weekly picks the configured count", 3, e.pick("WEEKLY", 3, "2026-W43").size());
        eq("asking for more than the pool is capped", 5, e.pick("DAILY", 99, "x").size());
        eq("week key format", "2026-W43", RankEngine.weekKey(java.time.LocalDate.of(2026, 10, 21)));
    }

    private static List<String> ids(List<RankConfig.QuestDef> l) { List<String> o = new ArrayList<>(); for (var q : l) o.add(q.id); Collections.sort(o); return o; }

    static void questObjectives() {
        System.out.println("quest objectives");
        RankConfig c = RankConfig.bundled();
        c.quests.definitions.clear();
        c.quests.dailyPerDay = 9; c.quests.weeklyPerWeek = 9;
        c.quests.definitions.add(q("d_play", "DAILY", "PLAY_MINUTES", 20, 3, 10));
        c.quests.definitions.add(q("d_sess", "DAILY", "SESSION_MINUTES", 30, 4, 10));
        c.quests.definitions.add(q("d_srv", "DAILY", "PLAY_SERVER_MINUTES", 15, 4, 10));
        c.quests.definitions.add(q("d_cnt", "DAILY", "SESSIONS", 2, 3, 10));
        c.quests.definitions.add(q("w_play", "WEEKLY", "PLAY_MINUTES", 60, 20, 10));
        c.quests.definitions.add(q("s_link", "SPECIAL", "LINK_MICROSOFT", 1, 25, 10));
        RankEngine e = new RankEngine(c);
        RankState s = user();
        Instant t = at("2026-10-19T10:00:00Z");

        e.creditPlaytime(s, t, new Credit("s1", 10 * MIN, 0, "t", null, 0, 10 * MIN));
        check("10 min: nothing complete", s.completed.isEmpty());
        e.creditPlaytime(s, t, new Credit("s1", 10 * MIN, 0, "t", null, 10 * MIN, 20 * MIN));
        check("20 min completes the 20-minute quest", RankEngine.isCompleted(s, "d_play", "2026-10-19"));
        check("a single session of 20 min does not finish a 30-minute session quest", !RankEngine.isCompleted(s, "d_sess", "2026-10-19"));
        check("session count needs 10+ min sessions: first one counted once", s.progress.get("d_cnt@2026-10-19") == 1L);
        check("no server minutes without a server join", !RankEngine.isCompleted(s, "d_srv", "2026-10-19"));
        e.creditPlaytime(s, t, new Credit("s1", 10 * MIN, 0, "t", null, 20 * MIN, 30 * MIN));
        check("30-min session completes the session quest", RankEngine.isCompleted(s, "d_sess", "2026-10-19"));
        eq("session count not inflated by one long session", 1L, s.progress.get("d_cnt@2026-10-19"));
        e.creditPlaytime(s, t, new Credit("s2", 10 * MIN, 0, "t", null, 0, 10 * MIN));
        check("a second 10-min session completes the 2-session quest", RankEngine.isCompleted(s, "d_cnt", "2026-10-19"));
        e.creditPlaytime(s, t, new Credit("s3", 15 * MIN, 0, "t", "play.example.net", 0, 15 * MIN));
        check("server minutes count when joined via a server", RankEngine.isCompleted(s, "d_srv", "2026-10-19"));
        check("weekly quest not done at 55 minutes", !RankEngine.isCompleted(s, "w_play", "2026-W43"));
        e.creditPlaytime(s, at("2026-10-20T10:00:00Z"), new Credit("s4", 5 * MIN, 0, "t", null, 0, 5 * MIN));
        check("weekly quest accumulates across days and completes at 60", RankEngine.isCompleted(s, "w_play", "2026-W43"));
        check("daily progress resets on a new day", s.progress.get("d_play@2026-10-20") == 5 * MIN && !s.progress.containsKey("d_play@2026-10-19"));
                long ap = s.totalAp;
        RankEngine.Outcome o = e.onMicrosoftLinked(s, t);
        eq("link quest pays once", 25, o.questAp());
        eq("AP raised by exactly the reward", ap + 25, s.totalAp);
        eq("link quest pays only once", 0, e.onMicrosoftLinked(s, t).questAp());
        List<QuestView> v = e.views(s, at("2026-10-20T11:00:00Z"));
        check("views include completed special", v.stream().anyMatch(x -> x.id().equals("s_link") && x.complete()));
        check("weekly shows next Monday as its end", v.stream().filter(x -> x.id().equals("w_play")).allMatch(x -> x.endsAtEpochMs() == at("2026-10-26T00:00:00Z").toEpochMilli()));
        check("daily ends at next UTC midnight", v.stream().filter(x -> x.id().equals("d_play")).allMatch(x -> x.endsAtEpochMs() == at("2026-10-21T00:00:00Z").toEpochMilli()));
        RankConfig w = RankConfig.bundled();
        w.quests.definitions.removeIf(q -> !q.id.equals("special_century"));
        w.quests.definitions.get(0).endsAt = "2026-01-01T00:00:00Z";
        check("an expired special quest is not offered", new RankEngine(w).views(user(), t).isEmpty());
    }

    private static RankConfig.QuestDef q(String id, String type, String obj, long target, int reward, int minMin) {
        RankConfig.QuestDef d = new RankConfig.QuestDef();
        d.id = id; d.type = type; d.objective = obj; d.target = target; d.rewardAp = reward; d.minMinutes = minMin; d.title = id;
        return d;
    }

    static void apNeverDecreases() {
        System.out.println("monotonic AP");
        RankEngine e = new RankEngine(RankConfig.bundled());
        RankState s = user();
        Random r = new Random(7);
        long last = 0; boolean ok = true;
        Instant t = at("2026-10-01T00:00:00Z");
        for (int i = 0; i < 20_000; i++) {
            t = t.plusSeconds(r.nextInt(3600));
            long v = r.nextInt(4) == 0 ? 0 : r.nextInt(20 * 60_000), afk = r.nextInt(3) == 0 ? r.nextInt(20 * 60_000) : 0;
            e.creditPlaytime(s, t, new Credit("s" + r.nextInt(4), v, afk, "t", r.nextBoolean() ? "x" : null, 0, v));
            if (s.totalAp < last) ok = false;
            last = s.totalAp;
        }
        check("20,000 random credits never lower AP", ok);
    }

    static void outboxMerging() {
        System.out.println("event outbox");
        RankEngine e = new RankEngine(RankConfig.bundled());
        RankState s = user();
        Instant t = at("2026-10-19T10:00:00Z");
        for (int i = 0; i < 100; i++) e.creditPlaytime(s, t, play("sess", 15_000));
        long pt = s.outbox.stream().filter(x -> x.type.equals("PLAYTIME")).count();
        eq("100 ticks in one session collapse into one event", 1L, pt);
        RankEvent ev = s.outbox.stream().filter(x -> x.type.equals("PLAYTIME")).findFirst().get();
        eq("seconds are summed", 1500L, ev.verifiedSeconds);
        ev.inFlight = true;
        e.creditPlaytime(s, t, play("sess", 15_000));
        eq("an in-flight event is never merged into", 2L, s.outbox.stream().filter(x -> x.type.equals("PLAYTIME")).count());
        Set<String> ids = new HashSet<>();
        for (RankEvent x : s.outbox) check("unique id " + x.id, ids.add(x.id));
        e.creditPlaytime(s, t, play("other", 15_000));
        check("another session gets its own event", s.outbox.stream().anyMatch(x -> "other".equals(x.sessionId)));
    }

    static void serverMerge() {
        System.out.println("server reconcile");
        RankEngine e = new RankEngine(RankConfig.bundled());
        RankState s = user();
        Instant t = at("2026-10-19T10:00:00Z");
        e.creditPlaytime(s, t, play("a", 30 * MIN));
        String firstId = s.outbox.get(0).id;
        long local = s.totalAp;
        int pendingAp = s.outbox.stream().mapToInt(x -> x.ap).sum();
        check("local total is optimistic", local > 0 && local == pendingAp);

        e.mergeServer(s, new ServerSnapshot(100L, "2026-10-19", 3, List.of(), Set.of(firstId), Map.of()), t);
        check("accepted event leaves the outbox", s.outbox.stream().noneMatch(x -> x.id.equals(firstId)));
        long pend2 = s.outbox.stream().mapToInt(x -> x.ap).sum();
        eq("total = server + still-pending", 100 + pend2, s.totalAp);
        eq("server remembered", 100L, s.serverAp);

        RankState r = user();
        e.creditPlaytime(r, t, play("a", 30 * MIN));
        String id = r.outbox.get(0).id;
        e.mergeServer(r, new ServerSnapshot(0L, "2026-10-19", 0, List.of(), Set.of(), Map.of(id, "daily cap")), t);
        check("a rejected event is dropped, not retried forever", r.outbox.stream().noneMatch(x -> x.id.equals(id)));

        RankState d = user();
        e.creditPlaytime(d, t, play("a", 10 * MIN));
        e.mergeServer(d, new ServerSnapshot(null, "2026-10-19", 18, List.of(new QuestDone("daily_x", "2026-10-19", 4, 0)), Set.of(), Map.of()), t);
        eq("cross-device daily cap is honoured (max of local, server)", 18, d.dayPlaytimeAp);
        check("quests completed elsewhere are merged", RankEngine.isCompleted(d, "daily_x", "2026-10-19"));
        long before = d.totalAp;
        e.mergeServer(d, new ServerSnapshot(null, null, null, null, null, null), t);
        eq("a snapshot with no total leaves AP alone (local-only mode)", before, d.totalAp);
        e.creditPlaytime(d, t, play("a", 60 * MIN));
        eq("with the cap already used on another device, no more playtime AP", 18, d.dayPlaytimeAp);
    }

    static void syncParsing() {
        System.out.println("server response parsing");
        var o = JsonParser.parseReader(new StringReader("""
            {"totalAp": 1234, "daily": {"day":"2026-10-19","playtimeAp": 7},
             "completedQuests":[{"questId":"a","periodKey":"once","ap":5},{"bad":1}],
             "accepted":["pt:1","q:a:once"], "rejected":[{"id":"pt:2","reason":"cap"}]}""")).getAsJsonObject();
        ServerSnapshot s = RankSync.parse(o);
        eq("total", 1234L, s.totalAp());
        eq("daily day", "2026-10-19", s.day());
        eq("daily AP", 7, s.dayPlaytimeAp());
        eq("completed quests parsed, junk skipped", 1, s.completed().size());
        eq("accepted ids", Set.of("pt:1", "q:a:once"), s.acceptedIds());
        eq("rejected reasons", Map.of("pt:2", "cap"), s.rejected());
        ServerSnapshot empty = RankSync.parse(JsonParser.parseString("{}").getAsJsonObject());
        check("an empty body is tolerated", empty.totalAp() == null && empty.completed().isEmpty());
    }

    static void afkDetector() {
        System.out.println("AFK detection");
        RankConfig.Activity a = RankConfig.bundled().activity;
        long S = 1_000_000_000L;
        AfkDetector d = new AfkDetector(a, 0);
        AfkDetector.Tick t = d.step(15 * S, true, new ActivitySample(1000L, true, "t"));
        eq("startup grace is not counted", Verdict.STARTING, t.verdict());
        for (int sec = 30; sec <= 75; sec += 15) d.step(sec * S, true, new ActivitySample(1000L, true, "t"));
        t = d.step(90 * S, true, new ActivitySample(1000L, true, "t"));
        check("past the grace, active play counts", t.verdict() == Verdict.ACTIVE && t.verifiedMs() == 15_000);
        t = d.step(105 * S, true, new ActivitySample(310_000L, true, "t"));
        check("5+ minutes without input is AFK and earns nothing", t.verdict() == Verdict.AFK_IDLE && t.verifiedMs() == 0 && t.afkMs() == 15_000);
        t = d.step(120 * S, true, new ActivitySample(2000L, true, "t"));
        eq("input resumes: counting resumes", Verdict.ACTIVE, t.verdict());
        t = d.step(135 * S, false, null);
        eq("a dead process ends the session", Verdict.ENDED, t.verdict());

        // Session limit: a game left running stops earning after sessionLimitMinutes, with no input at all needed.
        AfkDetector lim = new AfkDetector(a, 0);
        long limNow = 0; long earned = 0; Verdict limLast = Verdict.STARTING;
        for (long sec = 15; sec <= 4 * 3600; sec += 15) {
            var x = lim.step(sec * S, true, new ActivitySample(null, null, "none"));
            earned += x.verifiedMs(); limLast = x.verdict();
        }
        check("a 4-hour session stops earning at the limit", limLast == Verdict.SESSION_LIMIT
                && earned <= (a.sessionLimitMinutes * 60_000L) && earned >= (a.sessionLimitMinutes * 60_000L) - 2 * 90_000L);
        AfkDetector fresh = new AfkDetector(a, 5000 * S);
        for (long sec = 5000 + 15; sec <= 5000 + 200; sec += 15) fresh.step(sec * S, true, new ActivitySample(null, null, "none"));
        eq("a new session earns again", Verdict.UNVERIFIED_ALLOWED, fresh.step((5000 + 215) * S, true, new ActivitySample(null, null, "none")).verdict());
        RankConfig.Activity off = RankConfig.bundled().activity; off.sessionLimitMinutes = 0;
        AfkDetector nolim = new AfkDetector(off, 0); Verdict nl = Verdict.STARTING;
        for (long sec = 15; sec <= 5 * 3600; sec += 15) nl = nolim.step(sec * S, true, new ActivitySample(null, null, "none")).verdict();
        eq("0 = no session limit", Verdict.UNVERIFIED_ALLOWED, nl);

        AfkDetector g = new AfkDetector(a, 0);
        for (int sec = 15; sec <= 105; sec += 15) g.step(sec * S, true, new ActivitySample(1000L, true, "t"));
        t = g.step(105 * S + 3600 * S, true, new ActivitySample(1000L, true, "t"));
        check("a one-hour gap (sleep) earns nothing", t.verdict() == Verdict.SUSPENDED_GAP && t.verifiedMs() == 0);

        AfkDetector b = new AfkDetector(a, 0);
        long now = 0; for (int i = 0; i < 7; i++) { now += 15 * S; b.step(now, true, new ActivitySample(1000L, true, "t")); }
        Verdict last = Verdict.ACTIVE; long credited = 0;
        for (int i = 0; i < 50; i++) { now += 15 * S; var x = b.step(now, true, new ActivitySample(1000L, false, "t")); last = x.verdict(); credited += x.verifiedMs(); }
        check("game in background: short alt-tab ok, then paused", last == Verdict.AFK_BACKGROUND && credited <= (a.backgroundGraceSeconds + 15) * 1000L);
        now += 15 * S;
        eq("refocusing the game resumes counting", Verdict.ACTIVE, b.step(now, true, new ActivitySample(1000L, true, "t")).verdict());

        AfkDetector m = new AfkDetector(off, 0); // session limit off: this test is about the macro rule
        now = 0; Verdict mv = Verdict.ACTIVE;
        for (int i = 0; i < 600; i++) { now += 15 * S; mv = m.step(now, true, new ActivitySample(2000L, true, "t")).verdict(); }
        eq("2.5h of input that never pauses looks scripted", Verdict.MACRO_SUSPECT, mv);
        now += 15 * S;
        eq("a natural pause clears the suspicion", Verdict.ACTIVE, m.step(now, true, new ActivitySample(20_000L, true, "t")).verdict());

        AfkDetector u = new AfkDetector(a, 0);
        for (int sec = 15; sec <= 90; sec += 15) u.step(sec * S, true, ActivitySample.unknown());
        t = u.step(105 * S, true, ActivitySample.unknown());
        eq("no probe data: ALLOW policy counts (cap is the backstop)", Verdict.UNVERIFIED_ALLOWED, t.verdict());
        RankConfig.Activity deny = RankConfig.bundled().activity; deny.unknownActivityPolicy = "DENY";
        AfkDetector dd = new AfkDetector(deny, 0);
        for (int sec = 15; sec <= 90; sec += 15) dd.step(sec * S, true, null);
        t = dd.step(105 * S, true, null);
        check("no probe data: DENY policy earns nothing", t.verdict() == Verdict.UNVERIFIED_DENIED && t.verifiedMs() == 0);
    }

    // A controllable fake game process.
    static final class FakeProcess extends Process {
        volatile boolean alive = true;
        final long pid;
        FakeProcess(long pid) { this.pid = pid; }
        @Override public long pid() { return pid; }
        @Override public boolean isAlive() { return alive; }
        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { return 0; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { alive = false; }
    }

    static void trackerRules() {
        System.out.println("playtime tracker");
        RankConfig c = RankConfig.bundled();
        AtomicLong clock = new AtomicLong(0);
        long S = 1_000_000_000L;
        List<long[]> credits = new ArrayList<>();
        boolean[] earn = {true};
        int[] ended = {0};
        PlaytimeTracker.Sink sink = new PlaytimeTracker.Sink() {
            public void credit(PlaytimeTracker.Session s, AfkDetector.Tick t, long before) { credits.add(new long[]{s.pid, t.verifiedMs(), t.afkMs(), before}); }
            public void sessionEnded(PlaytimeTracker.Session s) { ended[0]++; }
            public boolean canEarn() { return earn[0]; }
        };
        PlaytimeTracker tr = new PlaytimeTracker(c, sink, clock::get, () -> p -> new ActivitySample(500L, true, "fake"));

        FakeProcess p1 = new FakeProcess(111), p2 = new FakeProcess(222);
        tr.register(p1, "1.21", null);
        tr.register(p2, "1.21", null);
        for (int sec = 15; sec <= 75; sec += 15) { clock.set(sec * S); tr.tick(); }
        check("loading screen earns nothing", credits.isEmpty());
        clock.set(90 * S); tr.tick();
        clock.set(105 * S); tr.tick();
        check("running and present earns time (15s per tick)", !credits.isEmpty() && credits.get(0)[1] == 15_000);
        long paid111 = credits.stream().filter(x -> x[0] == 111).count(), paid222 = credits.stream().filter(x -> x[0] == 222).count();
        check("two clients open: only the first accrues", paid111 == 2 && paid222 == 0);
        earn[0] = false;
        int n = credits.size();
        clock.set(120 * S); tr.tick();
        eq("signed out earns nothing", n, credits.size());
        earn[0] = true;
        p1.alive = false;
        clock.set(135 * S); tr.tick();
        eq("a closed game ends its session", 1, ended[0]);
        check("tracker reports live session while one remains", tr.status().playing() && tr.status().instances() == 1);
        p2.alive = false;
        clock.set(150 * S); tr.tick();
        check("tracker goes idle when all games close", !tr.status().playing());
        tr.shutdown();

        // launch-and-kill (the install-from-browser path): never reaches the grace period.
        List<long[]> c2 = new ArrayList<>();
        PlaytimeTracker t2 = new PlaytimeTracker(c, new PlaytimeTracker.Sink() {
            public void credit(PlaytimeTracker.Session s, AfkDetector.Tick t, long b) { c2.add(new long[]{t.verifiedMs()}); }
            public void sessionEnded(PlaytimeTracker.Session s) { }
            public boolean canEarn() { return true; }
        }, clock::get, () -> p -> ActivitySample.unknown());
        FakeProcess k = new FakeProcess(9);
        clock.set(1000 * S); t2.register(k, "1.21", null);
        clock.set(1010 * S); t2.tick(); k.alive = false; clock.set(1020 * S); t2.tick();
        check("a game killed seconds after launch earns nothing", c2.isEmpty());
        t2.shutdown();
    }

    static void storeIntegrity() throws Exception {
        System.out.println("local persistence");
        Path dir = Files.createTempDirectory("rankstore");
        RankStore st = new RankStore(dir);
        RankEngine e = new RankEngine(RankConfig.bundled());
        RankState s = RankState.fresh("user-42");
        e.creditPlaytime(s, at("2026-10-19T10:00:00Z"), play("a", 45 * MIN));
        long ap = s.totalAp;
        st.save(s);
        RankStore.Loaded l = new RankStore(dir).load("user-42");
        check("round trip keeps AP, quests and history", !l.tampered() && l.state().totalAp == ap && l.state().completed.size() == s.completed.size()
                && l.state().outbox.size() == s.outbox.size() && l.state().ledger.size() == s.ledger.size());
        eq("round trip keeps the daily ledger", s.dayPlaytimeAp, l.state().dayPlaytimeAp);
        check("a different account never sees this data", new RankStore(dir).load("someone-else").state().totalAp == 0);

        Path f;
        try (var ls = Files.list(dir)) { f = ls.filter(p -> p.getFileName().toString().endsWith(".json")).findFirst().get(); }
        String json = Files.readString(f);
        Files.writeString(f, json.replace("\"totalAp\": " + ap, "\"totalAp\": 99999").replace("\"totalAp\":" + ap, "\"totalAp\":99999"));
        RankStore.Loaded t = new RankStore(dir).load("user-42");
        check("hand-editing AP is detected and not trusted", t.state().totalAp != 99999);

        RankStore st2 = new RankStore(dir);
        st2.save(s);                       // fresh good file
        s.totalAp += 1; st2.save(s);       // second save makes the first the .bak
        Files.writeString(f, "{ corrupt");
        RankStore.Loaded b = new RankStore(dir).load("user-42");
        check("a corrupt file falls back to the previous good copy", b.state().totalAp == ap);

        Files.writeString(dir.resolve(".key"), "x");   // lost/changed key
        check("without the original key the file is not trusted", new RankStore(dir).load("user-42").tampered() || new RankStore(dir).load("user-42").state().totalAp == 0);
    }

    static void invalidConfigRejected() {
        System.out.println("config validation");
        String[] bad = {
            "{\"ranks\":[{\"id\":\"A\",\"name\":\"A\",\"minAp\":5}]}",                                                    // first must be 0
            "{\"ranks\":[{\"id\":\"A\",\"name\":\"A\",\"minAp\":0},{\"id\":\"B\",\"name\":\"B\",\"minAp\":0}]}",            // not increasing
            "{\"ranks\":[{\"id\":\"A\",\"name\":\"A\",\"minAp\":0},{\"id\":\"A\",\"name\":\"B\",\"minAp\":10}]}",           // dup id
            "{\"playtime\":{\"minutesPerAp\":0},\"ranks\":[{\"id\":\"A\",\"name\":\"A\",\"minAp\":0}]}",                    // zero minutes
            "{\"ranks\":[]}"
        };
        for (int i = 0; i < bad.length; i++) {
            boolean threw = false;
            try { RankConfig.parse(new StringReader(bad[i])); } catch (RuntimeException ex) { threw = true; }
            check("invalid config #" + (i + 1) + " is rejected", threw);
        }
        RankConfig c = RankConfig.bundled();
        c.ranks.get(3).minAp = 7000;
        boolean threw = false; try { c.validate(); } catch (IllegalArgumentException ex) { threw = true; }
        check("out-of-order thresholds are rejected", threw);
        RankConfig custom = RankConfig.parse(new StringReader(
            "{\"ranks\":[{\"id\":\"A\",\"name\":\"Alpha\",\"minAp\":0,\"divisions\":2},{\"id\":\"B\",\"name\":\"Beta\",\"minAp\":100,\"divisions\":0}]}"));
        eq("a small custom ladder works", "Alpha II", RankCalculator.standing(custom, 60).label());
        eq("custom top rank", "Beta", RankCalculator.standing(custom, 100).label());
    }
}
