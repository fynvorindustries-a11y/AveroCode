package com.averolauncher.rank;

/**
 * One idempotent claim sent to the Avero server. The {@link #id} is the dedupe key: a retry, a second
 * device or a replay after a crash can never award the same thing twice.
 *
 * <ul>
 *   <li>PLAYTIME: id {@code pt:<sessionId>:<seq>}; verified/afk seconds since the previous event.</li>
 *   <li>QUEST: id {@code q:<questId>:<periodKey>}.</li>
 * </ul>
 * {@link #ap} is what the launcher granted locally; the server recomputes it and answers with its own total.
 */
public final class RankEvent {
    public String id;
    public String type;          // PLAYTIME | QUEST
    public long at;              // epoch ms of the last update
    public int ap;
    public String day;           // UTC day (PLAYTIME)
    public String sessionId;
    public long verifiedSeconds;
    public long afkSeconds;
    public String source;        // activity probe used, e.g. "windows-ps", "none"
    public String serverAddress; // PLAYTIME on a server joined from the Servers page, else null
    public String questId;
    public String periodKey;
    /** True once handed to the network; an in-flight event is never merged into. */
    public transient boolean inFlight;
}
