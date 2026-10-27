package com.averolauncher.rank;

/**
 * Where an AP total sits on the ladder. Immutable. {@code division} is 1-based (I = 1) or 0 for an
 * undivided rank. {@code nextAt} is -1 at the top rank.
 */
public record RankStanding(RankConfig.Tier tier, int tierIndex, int division, long ap,
                           long floorAp, long nextAt, String nextLabel) {

    public boolean isMax() { return nextAt < 0; }

    public long apToNext() { return isMax() ? 0 : Math.max(0, nextAt - ap); }

    /** 0..1 progress through the current division (1.0 at the top rank). */
    public double progress() {
        if (isMax()) return 1.0;
        long span = nextAt - floorAp;
        return span <= 0 ? 1.0 : Math.min(1.0, Math.max(0.0, (ap - floorAp) / (double) span));
    }

    public String label() { return tier.name + (division > 0 ? " " + roman(division) : ""); }

    /** A comparable position: higher means a better standing. */
    public int ordinal() { return tierIndex * 16 + division; }

    public static String roman(int n) {
        return switch (n) { case 1 -> "I"; case 2 -> "II"; case 3 -> "III"; case 4 -> "IV"; case 5 -> "V";
            case 6 -> "VI"; case 7 -> "VII"; case 8 -> "VIII"; case 9 -> "IX"; default -> String.valueOf(n); };
    }
}
