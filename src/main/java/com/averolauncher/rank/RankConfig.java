package com.averolauncher.rank;

import com.averolauncher.logging.AppLogger;
import com.averolauncher.util.PathResolver;
import com.google.gson.Gson;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Every tunable number of the rank system: AP thresholds, the daily playtime cap, AFK limits and
 * quest rewards. Nothing is hard-coded elsewhere; the defaults live in {@code /rank/rank-config.json}.
 *
 * <p>Load order: {@code -Davero.rank.config=<file>} (dev/testing), then
 * {@code <launcher data dir>/rank-config.json} if present, then the bundled resource. A file that
 * fails {@link #validate()} is ignored and the bundled defaults are used. Local overrides only change
 * what the launcher <i>displays and pre-computes</i>; the Avero server re-validates every award.
 */
public final class RankConfig {

    private static final String TAG = "RankConfig";
    private static final Gson GSON = new Gson();

    public int version = 1;
    public Playtime playtime = new Playtime();
    public Activity activity = new Activity();
    public List<Tier> ranks = new ArrayList<>();
    public Quests quests = new Quests();

    public static final class Playtime {
        public int minutesPerAp = 10;
        public int apPerBlock = 1;
        /** Maximum AP that verified playtime can award per UTC day. */
        public int dailyCapAp = 18;
    }

    public static final class Activity {
        public int tickSeconds = 15;
        public int startupGraceSeconds = 90;
        public int idleThresholdSeconds = 300;
        public int backgroundGraceSeconds = 300;
        public int maxTickGapSeconds = 60;
        public int macroWindowMinutes = 120;
        public int macroMinGapSeconds = 10;
        public boolean systemProbeEnabled = false;
        /** One game session earns AP for at most this long (0 = no limit). Restart the game to start a new session. */
        public int sessionLimitMinutes = 120;
        /** What to do when the OS gives no idle/focus info: ALLOW (the daily cap is the backstop) or DENY. */
        public String unknownActivityPolicy = "ALLOW";
    }

    public static final class Tier {
        public String id;
        public String name;
        public long minAp;
        /** 0 = a single, undivided rank (Celestial). */
        public int divisions = 3;
        /** Optional explicit AP at which each division starts (length == divisions, first == minAp). */
        public long[] divisionStarts;
        public String tagline = "";
        public String accent = "#9aa3b0";
    }

    public static final class Quests {
        public int dailyPerDay = 3;
        public int weeklyPerWeek = 3;
        public List<QuestDef> definitions = new ArrayList<>();
    }

    public static final class QuestDef {
        public String id;
        /** DAILY, WEEKLY or SPECIAL. */
        public String type;
        /** PLAY_MINUTES, SESSION_MINUTES, SESSIONS, PLAY_SERVER_MINUTES, LINK_MICROSOFT or SERVER_VERIFIED. */
        public String objective;
        public long target = 1;
        public int rewardAp;
        public int minMinutes = 10;
        public String title;
        public String description = "";
        /** SPECIAL only: optional ISO-8601 instants bounding availability. */
        public String startsAt;
        public String endsAt;
    }

    // ------------------------------------------------------------------ loading

    public static RankConfig load() {
        String prop = System.getProperty("avero.rank.config");
        if (prop != null && !prop.isBlank()) {
            RankConfig c = fromFile(Path.of(prop.trim()));
            if (c != null) return c;
        }
        try {
            RankConfig c = fromFile(PathResolver.launcherDataDir().resolve("rank-config.json"));
            if (c != null) return c;
        } catch (Exception ignored) { }
        return bundled();
    }

    public static RankConfig bundled() {
        try (InputStream in = RankConfig.class.getResourceAsStream("/rank/rank-config.json")) {
            if (in == null) throw new IllegalStateException("rank-config.json is missing from the application resources");
            return parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Bundled rank-config.json is invalid: " + e.getMessage(), e);
        }
    }

    public static RankConfig parse(Reader reader) {
        RankConfig c = GSON.fromJson(reader, RankConfig.class);
        if (c == null) throw new IllegalArgumentException("empty config");
        c.validate();
        return c;
    }

    private static RankConfig fromFile(Path p) {
        try {
            if (!Files.isRegularFile(p)) return null;
            try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                RankConfig c = parse(r);
                AppLogger.get().info(TAG, "Using rank config override: " + p);
                return c;
            }
        } catch (Exception e) {
            AppLogger.get().warn(TAG, "Ignoring invalid rank config " + p + ": " + e.getMessage());
            return null;
        }
    }

    /** Throws {@link IllegalArgumentException} describing the first problem found. */
    public void validate() {
        if (playtime == null || activity == null || quests == null || ranks == null) throw new IllegalArgumentException("missing section");
        if (playtime.minutesPerAp < 1 || playtime.apPerBlock < 1) throw new IllegalArgumentException("playtime.minutesPerAp/apPerBlock must be >= 1");
        if (playtime.dailyCapAp < 0) throw new IllegalArgumentException("playtime.dailyCapAp must be >= 0");
        if (activity.sessionLimitMinutes < 0) throw new IllegalArgumentException("sessionLimitMinutes can't be negative");
        if (activity.tickSeconds < 1 || activity.idleThresholdSeconds < 1) throw new IllegalArgumentException("activity timings must be positive");
        if (ranks.isEmpty()) throw new IllegalArgumentException("at least one rank is required");
        if (ranks.get(0).minAp != 0) throw new IllegalArgumentException("the first rank must start at 0 AP");
        Set<String> ids = new HashSet<>();
        long prev = -1;
        for (Tier t : ranks) {
            if (t.id == null || t.id.isBlank() || t.name == null) throw new IllegalArgumentException("rank needs id and name");
            if (!ids.add(t.id)) throw new IllegalArgumentException("duplicate rank id " + t.id);
            if (t.minAp <= prev) throw new IllegalArgumentException("rank thresholds must strictly increase at " + t.id);
            if (t.divisions < 0 || t.divisions > 9) throw new IllegalArgumentException("divisions out of range for " + t.id);
            prev = t.minAp;
        }
        for (int i = 0; i < ranks.size(); i++) {
            Tier t = ranks.get(i);
            if (t.divisionStarts == null || t.divisions == 0) continue;
            if (t.divisionStarts.length != t.divisions || t.divisionStarts[0] != t.minAp) throw new IllegalArgumentException("bad divisionStarts for " + t.id);
            long end = i + 1 < ranks.size() ? ranks.get(i + 1).minAp : Long.MAX_VALUE;
            for (int d = 1; d < t.divisionStarts.length; d++) {
                if (t.divisionStarts[d] <= t.divisionStarts[d - 1] || t.divisionStarts[d] >= end) throw new IllegalArgumentException("divisionStarts out of order for " + t.id);
            }
        }
        Set<String> qids = new HashSet<>();
        for (QuestDef q : quests.definitions) {
            if (q.id == null || q.type == null || q.objective == null || q.title == null) throw new IllegalArgumentException("quest needs id/type/objective/title");
            if (!qids.add(q.id)) throw new IllegalArgumentException("duplicate quest id " + q.id);
            if (!Set.of("DAILY", "WEEKLY", "SPECIAL").contains(q.type)) throw new IllegalArgumentException("bad quest type " + q.type);
            if (q.target < 1 || q.rewardAp < 0) throw new IllegalArgumentException("bad target/reward on " + q.id);
        }
    }

    public Tier tier(String id) {
        for (Tier t : ranks) if (t.id.equals(id)) return t;
        return null;
    }

    public QuestDef quest(String id) {
        for (QuestDef q : quests.definitions) if (q.id.equals(id)) return q;
        return null;
    }

    /** The AP at which division {@code d} (0-based) of tier {@code index} begins. */
    public long divisionStart(int index, int d) {
        Tier t = ranks.get(index);
        if (t.divisions <= 1 || d <= 0) return t.minAp;
        if (t.divisionStarts != null) return t.divisionStarts[d];
        long end = index + 1 < ranks.size() ? ranks.get(index + 1).minAp : t.minAp;
        return t.minAp + Math.round((end - t.minAp) * (double) d / t.divisions);
    }
}
