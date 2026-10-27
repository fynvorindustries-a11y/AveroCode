package com.averolauncher.rank;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the launcher remembers about one Avero account's progression. Plain mutable POJO
 * (Gson). Only {@link RankEngine} mutates it, and only under {@link RankService}'s lock.
 *
 * <p>{@code totalAp} is what the UI shows: the server-confirmed total plus AP still waiting in the
 * {@link #outbox}. AP is monotonic: nothing here ever subtracts from ordinary gameplay.
 */
public final class RankState {

    public int schema = 1;
    public String userId;

    public long totalAp;
    /** Last total the server confirmed, or -1 if it never has. */
    public long serverAp = -1;

    // Daily ledger (UTC day). The day only ever moves forward, so rolling the clock back can't reset the cap.
    public String day;
    public int dayPlaytimeAp;
    public long carryMs;
    public long dayVerifiedMs;
    public long dayAfkMs;

    public long lifetimeVerifiedMs;
    public long lifetimeAfkMs;
    public long lifetimeSessions;

    /** key "questId@periodKey" -> progress (milliseconds for time objectives, otherwise a count). */
    public Map<String, Long> progress = new HashMap<>();
    public List<QuestDone> completed = new ArrayList<>();
    public List<LedgerEntry> ledger = new ArrayList<>();
    public List<RankEvent> outbox = new ArrayList<>();

    /** Highest standing already announced to the player (so a restart doesn't replay a promotion toast). */
    public int announcedOrdinal = -1;
    public long updatedAt;
    public String sig;

    public static final class QuestDone {
        public String questId;
        public String periodKey;
        public int ap;
        public long at;
        public QuestDone() {}
        public QuestDone(String q, String p, int ap, long at) { questId = q; periodKey = p; this.ap = ap; this.at = at; }
    }

    public static final class LedgerEntry {
        public long at;
        public String kind;   // PLAYTIME | QUEST
        public int ap;
        public String note;
        public LedgerEntry() {}
        public LedgerEntry(long at, String kind, int ap, String note) { this.at = at; this.kind = kind; this.ap = ap; this.note = note; }
    }

    public static RankState fresh(String userId) {
        RankState s = new RankState();
        s.userId = userId;
        return s;
    }
}
