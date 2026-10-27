package com.averolauncher.util;

import java.lang.management.ManagementFactory;

/**
 * Sizes the game's memory allocation from the machine's real RAM, so users don't have to
 * tune two sliders: they pick one "max memory" value and the starting heap (-Xms) is derived.
 */
public final class MemoryAdvisor {

    private static final int STEP_MB = 256;
    private static final int FALLBACK_TOTAL_MB = 8192; // used if the OS won't report its RAM
    private static volatile int cachedTotalMb = -1;

    private MemoryAdvisor() {}

    /** Total physical RAM in MB (cached). */
    public static int totalSystemMb() {
        int c = cachedTotalMb;
        if (c > 0) return c;
        int mb = FALLBACK_TOTAL_MB;
        try {
            java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
                long bytes = sun.getTotalMemorySize();
                if (bytes > 0) mb = (int) Math.min(Integer.MAX_VALUE, bytes / (1024L * 1024L));
            }
        } catch (Throwable ignored) { /* keep fallback */ }
        cachedTotalMb = mb;
        return mb;
    }

    /** Upper bound for the slider: leave ~2 GB for Windows and other apps, never above 32 GB. */
    public static int sliderMaxMb() {
        int usable = totalSystemMb() - 2048;
        return Math.max(2048, Math.min(32768, roundDown(usable)));
    }

    /** A sensible default: a quarter of system RAM, between 2 and 8 GB. */
    public static int recommendedMaxMb() {
        int quarter = roundDown(totalSystemMb() / 4);
        return Math.max(2048, Math.min(8192, Math.min(quarter < 2048 ? 2048 : quarter, sliderMaxMb())));
    }

    /** Above this the game can starve the OS and run slower, not faster. */
    public static int highThresholdMb() {
        return (int) (totalSystemMb() * 0.75);
    }

    /**
     * Starting heap derived from the max: a quarter of it, clamped to 512 MB..2 GB (and never above
     * the max). Big enough to avoid constant heap resizing at startup, small enough not to grab
     * memory a light game never uses.
     */
    public static int autoMinMb(int maxMb) {
        int quarter = roundDown(maxMb / 4);
        return Math.max(256, Math.min(maxMb, Math.max(512, Math.min(2048, quarter))));
    }

    /** Low-End Mode never goes below this: Minecraft with a few mods needs roughly 1.5 GB to stay stable. */
    public static final int LOW_END_FLOOR_MB = 1536;

    /** Memory to use in Low-End Mode: about 40% less than {@code currentMb}, but not below the floor. */
    public static int lowEndMb(int currentMb) {
        int reduced = Math.max(LOW_END_FLOOR_MB, roundDown((int) (currentMb * 0.6)));
        return Math.min(currentMb, reduced);
    }

    public static int roundDown(int mb) {
        return (mb / STEP_MB) * STEP_MB;
    }

    public static String format(int mb) {
        if (mb % 1024 == 0) return (mb / 1024) + " GB";
        return String.format("%.2f GB", mb / 1024.0).replaceAll("0+ GB$", " GB").replace(". GB", " GB");
    }
}
