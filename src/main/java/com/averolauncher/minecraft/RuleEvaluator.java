package com.averolauncher.minecraft;

import com.averolauncher.model.manifest.VersionDetail;
import com.averolauncher.util.PathResolver;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Evaluates Mojang's "rules" arrays (used on libraries and structured
 * arguments) to decide whether an item applies to the current OS/arch and
 * requested features (demo mode, custom resolution, etc).
 */
public final class RuleEvaluator {

    private RuleEvaluator() {}

    public static boolean librariesRulesAllow(List<VersionDetail.Rule> rules) {
        return rulesAllow(rules, Map.of());
    }

    /**
     * Evaluates a rules list the way Mojang's launcher does: default action
     * is "disallow" if any rule exists, then each rule in order can flip the
     * decision; the last matching rule wins.
     */
    public static boolean rulesAllow(List<VersionDetail.Rule> rules, Map<String, Boolean> activeFeatures) {
        if (rules == null || rules.isEmpty()) {
            return true;
        }
        boolean allowed = false;
        for (VersionDetail.Rule rule : rules) {
            if (ruleMatches(rule, activeFeatures)) {
                allowed = "allow".equals(rule.action);
            }
        }
        return allowed;
    }

    private static boolean ruleMatches(VersionDetail.Rule rule, Map<String, Boolean> activeFeatures) {
        if (rule.os != null) {
            if (rule.os.name != null && !osNameMatches(rule.os.name)) {
                return false;
            }
            if (rule.os.arch != null && !archMatches(rule.os.arch)) {
                return false;
            }
            if (rule.os.version != null) {
                String osVersion = System.getProperty("os.version", "");
                try {
                    if (!Pattern.compile(rule.os.version).matcher(osVersion).find()) {
                        return false;
                    }
                } catch (Exception ignored) {
                    // malformed regex in metadata; ignore this constraint rather than crash
                }
            }
        }
        if (rule.features != null) {
            for (Map.Entry<String, Boolean> entry : rule.features.entrySet()) {
                boolean active = activeFeatures.getOrDefault(entry.getKey(), false);
                boolean required = entry.getValue() != null && entry.getValue();
                if (active != required) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean osNameMatches(String name) {
        return switch (name) {
            case "windows" -> PathResolver.isWindows();
            case "osx" -> PathResolver.isMac();
            case "linux" -> PathResolver.isLinux();
            default -> false;
        };
    }

    private static boolean archMatches(String arch) {
        String realArch = System.getProperty("os.arch", "").toLowerCase();
        if ("x86".equals(arch)) {
            return realArch.contains("32") || realArch.equals("x86");
        }
        return realArch.contains(arch.toLowerCase());
    }

    /** Returns the current OS's native-classifier key as used in library "natives" maps. */
    public static String currentNativesClassifierKey() {
        if (PathResolver.isWindows()) return "natives-windows";
        if (PathResolver.isMac()) return "natives-osx";
        return "natives-linux";
    }

    public static String currentOsKeyword() {
        if (PathResolver.isWindows()) return "windows";
        if (PathResolver.isMac()) return "osx";
        return "linux";
    }
}
