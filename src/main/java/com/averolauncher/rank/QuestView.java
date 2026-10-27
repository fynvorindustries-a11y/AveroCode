package com.averolauncher.rank;

/** What the UI needs to draw one quest. */
public record QuestView(String id, String type, String title, String description, int rewardAp,
                        long progress, long target, boolean timeBased, boolean complete, long endsAtEpochMs) {

    public double fraction() { return target <= 0 ? 1 : Math.min(1.0, progress / (double) target); }

    /** Progress in the units the player thinks in (minutes for time objectives). */
    public long shownProgress() { return timeBased ? progress / 60_000L : progress; }

    public String progressText() {
        long done = complete ? target : Math.min(progress, target);
        if (!timeBased) return done + " / " + target;
        long doneMin = done / 60_000L, tgtMin = target / 60_000L;
        if (tgtMin >= 600) return String.format(java.util.Locale.US, "%.1f / %d h", doneMin / 60.0, tgtMin / 60);
        return doneMin + " / " + tgtMin + " min";
    }
}
