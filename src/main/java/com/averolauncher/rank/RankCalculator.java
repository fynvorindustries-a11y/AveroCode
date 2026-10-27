package com.averolauncher.rank;

/** Pure function: AP total -> rank, division and distance to the next step. No state, no I/O. */
public final class RankCalculator {

    private RankCalculator() {}

    public static RankStanding standing(RankConfig cfg, long ap) {
        long a = Math.max(0, ap);
        int ti = 0;
        for (int i = 0; i < cfg.ranks.size(); i++) if (a >= cfg.ranks.get(i).minAp) ti = i;
        RankConfig.Tier t = cfg.ranks.get(ti);
        boolean last = ti == cfg.ranks.size() - 1;

        if (t.divisions <= 0) {
            return new RankStanding(t, ti, 0, a, t.minAp, last ? -1 : cfg.ranks.get(ti + 1).minAp,
                    last ? "" : cfg.ranks.get(ti + 1).name + (cfg.ranks.get(ti + 1).divisions > 0 ? " I" : ""));
        }
        int d = 0;
        for (int i = 0; i < t.divisions; i++) if (a >= cfg.divisionStart(ti, i)) d = i;
        long floor = cfg.divisionStart(ti, d);
        long next;
        String label;
        if (d + 1 < t.divisions) {
            next = cfg.divisionStart(ti, d + 1);
            label = t.name + " " + RankStanding.roman(d + 2);
        } else if (!last) {
            RankConfig.Tier n = cfg.ranks.get(ti + 1);
            next = n.minAp;
            label = n.name + (n.divisions > 0 ? " I" : "");
        } else {
            next = -1;
            label = "";
        }
        return new RankStanding(t, ti, d + 1, a, floor, next, label);
    }

    /**
     * The standing immediately below {@code s} (one division, or one tier at a divisionless/first
     * division boundary). Used where only the arrived-at standing is known — e.g. a rank-up cinematic
     * triggered by opening the Ranks page rather than by watching the promotion happen live — so there
     * is still a reasonable "previous rank" to show without needing the player's actual prior state.
     */
    public static RankStanding previousStep(RankConfig cfg, RankStanding s) {
        return standing(cfg, Math.max(0, s.floorAp() - 1));
    }
}
