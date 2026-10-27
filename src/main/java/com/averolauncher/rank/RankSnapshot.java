package com.averolauncher.rank;

import com.averolauncher.rank.RankState.LedgerEntry;

import java.util.List;

/** Immutable view of everything the UI shows. Built under the service lock, safe to use on any thread. */
public record RankSnapshot(
        boolean signedIn,
        long ap,
        RankStanding standing,
        int dailyCapAp,
        int dayPlaytimeAp,
        long dayVerifiedMs,
        long dayAfkMs,
        long dayResetsAtEpochMs,
        List<QuestView> quests,
        PlaytimeTracker.Status tracker,
        SyncStatus sync,
        int pendingEvents,
        List<LedgerEntry> recent,
        RankConfig config) {

    public enum SyncStatus { SIGNED_OUT, SYNCED, PENDING, OFFLINE, LOCAL_ONLY }

    public int dailyRemainingAp() { return Math.max(0, dailyCapAp - dayPlaytimeAp); }

    public boolean capReached() { return dayPlaytimeAp >= dailyCapAp; }

    public static RankSnapshot signedOut(RankConfig cfg) {
        return new RankSnapshot(false, 0, RankCalculator.standing(cfg, 0), cfg.playtime.dailyCapAp, 0, 0, 0, 0,
                List.of(), PlaytimeTracker.Status.idle(), SyncStatus.SIGNED_OUT, 0, List.of(), cfg);
    }
}
