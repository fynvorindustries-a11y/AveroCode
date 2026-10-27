package com.averolauncher.rank;

import com.averolauncher.rank.RankState.QuestDone;
import com.averolauncher.store.StoreApi;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Talks to the Avero server's rank endpoints (see RANK_SYSTEM.md):
 * <pre>
 *   GET  /me/rank          -> { totalAp, daily:{day,playtimeAp}, completedQuests:[{questId,periodKey}] }
 *   POST /me/rank/events   -> { events:[...] }  =>  same fields + accepted:[ids] + rejected:[{id,reason}]
 * </pre>
 * The server is authoritative. A 404/405/501 means "not deployed yet": the launcher keeps working in
 * local-only mode and replays the queued events once the endpoints exist.
 */
public final class RankSync {

    public record Result(RankEngine.ServerSnapshot snapshot, boolean notSupported, boolean transientFailure, String error) {
        public boolean ok() { return snapshot != null; }
    }

    private static final Gson GSON = new Gson();
    private static final long TIMEOUT_S = 25;
    private final StoreApi api;

    public RankSync(StoreApi api) { this.api = api; }

    public Result pull() {
        return exec("GET", "/me/rank", null);
    }

    public Result push(List<RankEvent> events, int configVersion) {
        JsonObject body = new JsonObject();
        body.add("events", GSON.toJsonTree(events));
        JsonObject client = new JsonObject();
        client.addProperty("configVersion", configVersion);
        client.addProperty("os", System.getProperty("os.name", ""));
        body.add("client", client);
        return exec("POST", "/me/rank/events", body);
    }

    private Result exec(String method, String path, JsonObject body) {
        try {
            JsonObject o = api.authedJson(method, path, body).get(TIMEOUT_S, TimeUnit.SECONDS);
            return new Result(parse(o), false, false, null);
        } catch (Exception e) {
            Throwable r = e;
            while (r.getCause() != null && r.getCause() != r) r = r.getCause();
            if (r instanceof StoreApi.StoreException se) {
                if (se.status == 404 || se.status == 405 || se.status == 501 || "NO_SERVER".equals(se.code)) {
                    return new Result(null, true, false, se.getMessage());
                }
                return new Result(null, false, se.isTransient(), se.getMessage());
            }
            return new Result(null, false, true, r.getMessage());
        }
    }

    static RankEngine.ServerSnapshot parse(JsonObject o) {
        Long total = o.has("totalAp") && !o.get("totalAp").isJsonNull() ? o.get("totalAp").getAsLong() : null;
        String day = null;
        Integer dayAp = null;
        if (o.has("daily") && o.get("daily").isJsonObject()) {
            JsonObject d = o.getAsJsonObject("daily");
            if (d.has("day") && !d.get("day").isJsonNull()) day = d.get("day").getAsString();
            if (d.has("playtimeAp") && !d.get("playtimeAp").isJsonNull()) dayAp = d.get("playtimeAp").getAsInt();
        }
        List<QuestDone> done = new ArrayList<>();
        if (o.has("completedQuests") && o.get("completedQuests").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("completedQuests")) {
                if (!e.isJsonObject()) continue;
                JsonObject q = e.getAsJsonObject();
                if (!q.has("questId") || !q.has("periodKey")) continue;
                done.add(new QuestDone(q.get("questId").getAsString(), q.get("periodKey").getAsString(),
                        q.has("ap") ? q.get("ap").getAsInt() : 0, 0));
            }
        }
        Set<String> accepted = new HashSet<>();
        if (o.has("accepted") && o.get("accepted").isJsonArray()) for (JsonElement e : o.getAsJsonArray("accepted")) accepted.add(e.getAsString());
        Map<String, String> rejected = new HashMap<>();
        if (o.has("rejected") && o.get("rejected").isJsonArray()) {
            JsonArray a = o.getAsJsonArray("rejected");
            for (JsonElement e : a) {
                if (!e.isJsonObject() || !e.getAsJsonObject().has("id")) continue;
                JsonObject r = e.getAsJsonObject();
                rejected.put(r.get("id").getAsString(), r.has("reason") ? r.get("reason").getAsString() : "rejected");
            }
        }
        return new RankEngine.ServerSnapshot(total, day, dayAp, done, accepted, rejected);
    }
}
