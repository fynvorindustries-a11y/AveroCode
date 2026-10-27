package com.averolauncher.update;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loose version comparison for GitHub release tags (used for the running app's own version, which can
 * be something like "0.0.0-dev"). Tolerates a leading "v" (v1.2.3) and a trailing pre-release/build
 * suffix (1.2.3-beta, 1.2.3+build4). Candidate release tags are filtered separately with the strict
 * {@link #isPlainReleaseInRange} check.
 *
 * A tag that doesn't contain a usable leading numeric version (a typo'd tag,
 * a codename-only tag, etc) parses to {@code null} rather than throwing, and
 * {@link #isNewer} treats "can't tell" as "not newer" — a malformed tag on
 * the release feed should never cause a false "update available" nag.
 */
public final class VersionComparator {

    private static final Pattern DIGITS = Pattern.compile("\\d+");

    /** Exactly MAJOR.MINOR.PATCH: no "v" prefix, no suffix, no extra segments (so "v1.123", "1.2", "1.2.3-beta" all fail). */
    private static final Pattern STRICT = Pattern.compile("^(\\d{1,6})\\.(\\d{1,6})\\.(\\d{1,6})$");

    private VersionComparator() {}

    /** True only if {@code candidate} parses as a version strictly newer than {@code current}. */
    public static boolean isNewer(String current, String candidate) {
        int[] a = parse(current);
        int[] b = parse(candidate);
        if (a == null || b == null) {
            return false;
        }
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int ai = i < a.length ? a[i] : 0;
            int bi = i < b.length ? b[i] : 0;
            if (bi != ai) {
                return bi > ai;
            }
        }
        return false;
    }

    /**
     * True only if {@code tag} is a plain {@code MAJOR.MINOR.PATCH} version (no leading "v") that lies in
     * the inclusive range {@code [min, max]}. Used to pick launcher releases out of a repo that also
     * carries other tags (e.g. agent builds tagged "v1.123").
     */
    public static boolean isPlainReleaseInRange(String tag, String min, String max) {
        int[] t = parseStrict(tag);
        int[] lo = parseStrict(min);
        int[] hi = parseStrict(max);
        if (t == null || lo == null || hi == null) {
            return false;
        }
        return compare(t, lo) >= 0 && compare(t, hi) <= 0;
    }

    /**
     * True only if {@code tag} is a plain {@code MAJOR.MINOR.PATCH} version (no leading "v", no suffix)
     * that is at least {@code min}. There is deliberately no upper bound, so new releases (4.0.0, 5.0.0 ...)
     * are picked up without touching the code.
     */
    public static boolean isPlainReleaseAtLeast(String tag, String min) {
        int[] t = parseStrict(tag);
        int[] lo = parseStrict(min);
        return t != null && lo != null && compare(t, lo) >= 0;
    }

    private static int[] parseStrict(String version) {
        if (version == null) {
            return null;
        }
        Matcher m = STRICT.matcher(version);   // deliberately no trim(): " 1.2.3" or "1.2.3\n" are not clean tags
        if (!m.matches()) {
            return null;
        }
        return new int[] { Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)) };
    }

    private static int compare(int[] a, int[] b) {
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int ai = i < a.length ? a[i] : 0;
            int bi = i < b.length ? b[i] : 0;
            if (ai != bi) return Integer.compare(ai, bi);
        }
        return 0;
    }

    /** True if {@code a} is strictly newer than {@code b}, both already known to be plain release tags. */
    public static boolean isNewerPlain(String a, String b) {
        int[] x = parseStrict(a), y = parseStrict(b);
        return x != null && y != null && compare(x, y) > 0;
    }

    private static int[] parse(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }
        String cleaned = version.trim();
        if (cleaned.startsWith("v") || cleaned.startsWith("V")) {
            cleaned = cleaned.substring(1);
        }
        // Keep only the leading run of digits/dots, so "1.2.3-beta.1" -> "1.2.3"
        // and a tag that doesn't start with a digit (e.g. the typo'd "!.0.0") -> "".
        StringBuilder core = new StringBuilder();
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (Character.isDigit(c) || c == '.') {
                core.append(c);
            } else {
                break;
            }
        }
        if (core.length() == 0) {
            return null;
        }
        String[] parts = core.toString().split("\\.");
        int[] nums = new int[parts.length];
        int count = 0;
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            Matcher m = DIGITS.matcher(part);
            if (!m.find()) {
                break;
            }
            try {
                nums[count++] = Integer.parseInt(m.group());
            } catch (NumberFormatException e) {
                break;
            }
        }
        return count > 0 ? Arrays.copyOf(nums, count) : null;
    }
}
