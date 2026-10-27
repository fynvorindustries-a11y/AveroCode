package com.averolauncher.rank;

import java.time.*;
import java.util.*;

public final class PacingReport {
    record Arch(String name, int minPerDay, int daysPerWeek, int sessions, double serverShare) {}
    public static void main(String[] a) {
        RankConfig cfg = RankConfig.bundled();
        Arch[] archs = {
            new Arch("Casual (1 h, 4 days/wk)", 60, 4, 1, 0.0),
            new Arch("Regular (2 h, 6 days/wk)", 120, 6, 2, 0.3),
            new Arch("Dedicated (3 h, daily)", 180, 7, 2, 0.5),
            new Arch("Heavy (6 h, daily)", 360, 7, 3, 0.5),
            new Arch("AFK farm, undetected (24 h, daily)", 1440, 7, 1, 0.0),
        };
        System.out.println("| Player | Stone→Copper | →Gold | →Sapphire | →Obsidian | →Celestial | AP/day (avg) |");
        System.out.println("|---|---|---|---|---|---|---|");
        for (Arch ar : archs) {
            RankEngine e = new RankEngine(cfg);
            RankState s = RankState.fresh("u");
            Instant d0 = Instant.parse("2026-10-19T08:00:00Z");
            int[] hit = new int[cfg.ranks.size()];
            Arrays.fill(hit, -1);
            int day = 0;
            for (; day < 3000 && hit[8] < 0; day++) {
                Instant now = d0.plus(Duration.ofDays(day));
                if (day % 7 < ar.daysPerWeek()) {
                    long per = ar.minPerDay() * 60_000L / ar.sessions();
                    for (int i = 0; i < ar.sessions(); i++) {
                        String addr = (i < Math.round(ar.sessions() * ar.serverShare()) || (ar.sessions()==1 && ar.serverShare()>0)) ? "srv" : null;
                        long done = 0;
                        while (done < per) {
                            long chunk = Math.min(10 * 60_000L, per - done);
                            e.creditPlaytime(s, now, new RankEngine.Credit("d" + day + "s" + i, chunk, 0, "t", addr, done, done + chunk));
                            done += chunk;
                        }
                    }
                }
                int idx = RankCalculator.standing(cfg, s.totalAp).tierIndex();
                for (int k = 0; k <= idx; k++) if (hit[k] < 0) hit[k] = day + 1;
            }
            System.out.printf("| %s | %s | %s | %s | %s | %s | %.1f |%n", ar.name(),
                hit[1] < 0 ? "never" : hit[1] + " d", hit[3] < 0 ? "never" : hit[3] + " d", hit[5] < 0 ? "never" : hit[5] + " d",
                hit[7] < 0 ? "never" : hit[7] + " d", hit[8] < 0 ? "never" : hit[8] + " d (" + String.format("%.1f", hit[8]/30.4) + " mo)",
                s.totalAp / (double) Math.max(1, day));
        }
    }
}
