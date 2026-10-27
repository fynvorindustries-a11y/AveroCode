package com.averolauncher.rank;

import com.averolauncher.store.AveroId;
import com.averolauncher.store.StoreApi;
import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/**
 * End to end: the real StoreApi + RankService + RankSync talking HTTP to an in-process fake Avero server.
 * The fake server is also a compact reference for the real backend's rules (see RANK_SYSTEM.md):
 * it ignores the client's AP, recomputes from verified seconds, enforces the daily cap, dedupes by event
 * id and rejects impossible claims.
 */
public final class RankE2ETest {

    private static int passed, failed;
    private static final Gson G = new Gson();

    // ---- fake server state
    static volatile boolean rankEndpoints = false;
    static final Map<String, Long> daySeconds = new HashMap<>();     // day -> accepted verified seconds
    static final Map<String, Integer> dayAp = new HashMap<>();
    static final Set<String> seen = new HashSet<>();
    static final Set<String> questKeys = new LinkedHashSet<>();
    static long serverTotal = 0;
    static final Map<String, Integer> QUEST_AP = Map.of("special_first_steps", 20, "daily_warmup", 3, "daily_hour", 4, "daily_server", 4, "daily_session", 4, "daily_sessions", 3);

    static void check(String n, boolean ok) { if (ok) passed++; else failed++; System.out.println((ok ? "  ok   " : "  FAIL ") + n); }

    public static void main(String[] a) throws Exception {
        Path home = Files.createTempDirectory("avero-e2e-home");
        System.setProperty("user.home", home.toString());
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/", RankE2ETest::handle);
        srv.start();
        System.setProperty("avero.store.api", "http://127.0.0.1:" + srv.getAddress().getPort());

        List<String> promos = new CopyOnWriteArrayList<>(), quests = new CopyOnWriteArrayList<>();
        RankService rs = RankService.get();
        rs.bind(null);
        rs.addListener(new RankListener() {
            @Override public void onPromotion(RankStanding to) { promos.add(to.label()); }
            @Override public void onQuestCompleted(QuestView q) { quests.add(q.id()); }
        });

        System.out.println("signed out");
        check("signed-out snapshot", !rs.snapshot().signedIn());
        check("playtime cannot be credited while signed out", !rs.canEarn());

        System.out.println("phase A: server has no rank endpoints yet");
        StoreApi.AveroSession sess = AveroId.get().api().login("e2e", "pw", "test").get();
        AveroId.get().signedIn(sess);
        waitFor(() -> rs.snapshot().signedIn(), 5000);
        check("signed in: snapshot reflects the account", rs.snapshot().signedIn());
        check("playtime can be credited once signed in", rs.canEarn());

        FakeGame g = new FakeGame();
        PlaytimeTracker.Session s1 = g.session();
        rs.credit(s1, new AfkDetector.Tick(AfkDetector.Verdict.ACTIVE, 35 * 60_000L, 0), 0);
        s1.verifiedMs = 35 * 60_000L;
        RankSnapshot snap = rs.snapshot();
        check("AP is credited immediately (optimistic, offline)", snap.ap() > 0);
        check("35 min = 3 playtime AP", snap.dayPlaytimeAp() == 3);
        check("quests fired a completion", quests.contains("special_first_steps"));
        long localAp = snap.ap();
        waitFor(() -> rs.snapshot().sync() == RankSnapshot.SyncStatus.LOCAL_ONLY, 8000);
        check("404 from the server means LOCAL_ONLY, not an error", rs.snapshot().sync() == RankSnapshot.SyncStatus.LOCAL_ONLY);
        check("events are retained for later", rs.snapshot().pendingEvents() > 0);
        check("AP unchanged by the failed sync", rs.snapshot().ap() == localAp);

        System.out.println("phase B: server comes online and catches up");
        rankEndpoints = true;
        rs.refresh();
        waitFor(() -> rs.snapshot().pendingEvents() == 0, 10000);
        snap = rs.snapshot();
        check("queued events were replayed and acknowledged", snap.pendingEvents() == 0);
        check("status is SYNCED", snap.sync() == RankSnapshot.SyncStatus.SYNCED);
        check("client total equals the server's total (" + serverTotal + ")", snap.ap() == serverTotal);
        check("server computed the same playtime AP (3)", dayAp.values().stream().mapToInt(i -> i).sum() == 3);

        System.out.println("duplicate and cheating protection (server side)");
        RankSync direct = new RankSync(AveroId.get().api());
        RankEvent dup = new RankEvent();
        dup.id = "pt:" + s1.id + ":replay"; dup.type = "PLAYTIME"; dup.day = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        dup.verifiedSeconds = 600; dup.ap = 1; dup.sessionId = s1.id;
        long t0 = serverTotal;
        direct.push(List.of(dup), 1);
        long afterFirst = serverTotal;
        direct.push(List.of(dup), 1);
        check("a new playtime event is credited once", afterFirst >= t0);
        check("replaying the same event id never pays twice", serverTotal == afterFirst);

        RankEvent inflated = new RankEvent();
        inflated.id = "pt:cheat:1"; inflated.type = "PLAYTIME"; inflated.day = dup.day; inflated.verifiedSeconds = 60; inflated.ap = 9999; inflated.sessionId = "cheat";
        long b = serverTotal;
        RankSync.Result r = direct.push(List.of(inflated), 1);
        check("a claimed AP amount is ignored; the server recomputes", serverTotal - b <= 1 && r.ok());

        RankEvent impossible = new RankEvent();
        impossible.id = "pt:cheat:2"; impossible.type = "PLAYTIME"; impossible.day = dup.day; impossible.verifiedSeconds = 3L * 86_400; impossible.sessionId = "cheat";
        b = serverTotal;
        r = direct.push(List.of(impossible), 1);
        check("more verified time than a day contains is rejected", r.ok() && r.snapshot().rejected().containsKey("pt:cheat:2") && serverTotal == b);

        RankEvent fakeQuest = new RankEvent();
        fakeQuest.id = "q:made_up:once"; fakeQuest.type = "QUEST"; fakeQuest.questId = "made_up"; fakeQuest.periodKey = "once"; fakeQuest.ap = 500;
        r = direct.push(List.of(fakeQuest), 1);
        check("an unknown quest is rejected", r.snapshot().rejected().containsKey("q:made_up:once"));

        RankEvent bigDay = new RankEvent();
        bigDay.id = "pt:cap:1"; bigDay.type = "PLAYTIME"; bigDay.day = dup.day; bigDay.verifiedSeconds = 20 * 3600; bigDay.sessionId = "cap";
        direct.push(List.of(bigDay), 1);
        check("server enforces the daily cap (" + dayAp.get(dup.day) + " <= 18)", dayAp.get(dup.day) <= 18);

        System.out.println("persistence across sessions");
        String uid = sess.userId;
        rs.shutdown();   // flush
        RankStore.Loaded loaded = new RankStore().load(uid);
        check("state file survives and verifies", !loaded.tampered() && loaded.state().totalAp > 0);
        check("daily ledger persisted", loaded.state().dayPlaytimeAp >= 3);
        check("quest history persisted", loaded.state().completed.stream().anyMatch(d -> d.questId.equals("special_first_steps")));

        System.out.println("\n" + passed + " passed, " + failed + " failed");
        srv.stop(0);
        System.exit(failed > 0 ? 1 : 0);
    }

    static final class FakeGame {
        PlaytimeTracker.Session session() {
            RankSystemTests.FakeProcess p = new RankSystemTests.FakeProcess(4242);
            try {
                var c = PlaytimeTracker.Session.class.getDeclaredConstructors()[0];
                c.setAccessible(true);
                return (PlaytimeTracker.Session) c.newInstance(p, "1.21", null, new AfkDetector(RankConfig.bundled().activity, 0));
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    static void waitFor(BooleanSupplier c, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) { if (c.getAsBoolean()) return; Thread.sleep(50); }
    }

    // ------------------------------------------------------------------ fake Avero server

    static void handle(com.sun.net.httpserver.HttpExchange ex) {
        try {
            String path = ex.getRequestURI().getPath(), method = ex.getRequestMethod();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int code = 200; JsonObject out = new JsonObject();
            if (path.equals("/auth/login")) {
                out.addProperty("accessToken", "tok"); out.addProperty("refreshToken", "ref"); out.addProperty("accessExpiresIn", 900);
                out.addProperty("userId", "u-e2e"); out.addProperty("username", "e2e"); out.addProperty("email", "e2e@example.com"); out.addProperty("emailVerified", true);
            } else if (path.equals("/auth/logout")) {
            } else if (path.equals("/me/rank") && rankEndpoints && method.equals("GET")) {
                synchronized (RankE2ETest.class) { snapshotInto(out); }
            } else if (path.equals("/me/rank/events") && rankEndpoints && method.equals("POST")) {
                synchronized (RankE2ETest.class) { applyEvents(JsonParser.parseString(body).getAsJsonObject(), out); }
            } else { code = 404; out.add("error", G.toJsonTree(Map.of("code", "NOT_FOUND", "message", "no such route"))); }
            byte[] bytes = out.toString().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        } catch (Exception e) { e.printStackTrace(); ex.close(); }
    }

    static void snapshotInto(JsonObject out) {
        out.addProperty("totalAp", serverTotal);
        String today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        JsonObject d = new JsonObject(); d.addProperty("day", today); d.addProperty("playtimeAp", dayAp.getOrDefault(today, 0));
        out.add("daily", d);
        JsonArray cq = new JsonArray();
        for (String k : questKeys) { JsonObject q = new JsonObject(); q.addProperty("questId", k.split("@")[0]); q.addProperty("periodKey", k.split("@")[1]); cq.add(q); }
        out.add("completedQuests", cq);
    }

    /** The rules a real backend must enforce. */
    static void applyEvents(JsonObject req, JsonObject out) {
        JsonArray accepted = new JsonArray(), rejected = new JsonArray();
        for (JsonElement el : req.getAsJsonArray("events")) {
            JsonObject e = el.getAsJsonObject();
            String id = e.get("id").getAsString();
            if (seen.contains(id)) { accepted.add(id); continue; }                       // idempotent replay
            String type = e.get("type").getAsString();
            if (type.equals("PLAYTIME")) {
                String day = e.get("day").getAsString();
                long sec = e.get("verifiedSeconds").getAsLong();
                long total = daySeconds.getOrDefault(day, 0L) + sec;
                if (sec < 0 || total > 86_400) { rejectEv(rejected, id, "IMPOSSIBLE_PLAYTIME"); continue; }
                daySeconds.put(day, total);
                int apNow = (int) Math.min(18, total / 600);                              // server's own AP rule + cap
                int delta = apNow - dayAp.getOrDefault(day, 0);
                dayAp.put(day, apNow);
                serverTotal += delta;
            } else if (type.equals("QUEST")) {
                String qid = e.get("questId").getAsString(), key = qid + "@" + e.get("periodKey").getAsString();
                Integer reward = QUEST_AP.get(qid);
                if (reward == null) { rejectEv(rejected, id, "UNKNOWN_QUEST"); continue; }
                if (questKeys.add(key)) serverTotal += reward;                             // once per (quest, period)
            } else { rejectEv(rejected, id, "UNKNOWN_TYPE"); continue; }
            seen.add(id); accepted.add(id);
        }
        snapshotInto(out);
        out.add("accepted", accepted); out.add("rejected", rejected);
    }

    static void rejectEv(JsonArray rejected, String id, String why) {
        JsonObject r = new JsonObject(); r.addProperty("id", id); r.addProperty("reason", why); rejected.add(r);
    }
}
