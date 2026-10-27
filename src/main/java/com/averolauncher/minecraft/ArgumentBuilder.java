package com.averolauncher.minecraft;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.averolauncher.model.manifest.VersionDetail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Translates a VersionDetail's "arguments" (or legacy "minecraftArguments")
 * into real, substituted argument lists ready to pass to ProcessBuilder.
 *
 * Handles both:
 *  - Modern format (1.13+): "arguments": { "game": [...], "jvm": [...] },
 *    where each entry is either a plain string or a {rules, value} object.
 *  - Legacy format (pre-1.13): a single "minecraftArguments" string with
 *    ${placeholders}, and no structured jvm args (we supply sensible
 *    defaults: classpath, natives path, main class are still required).
 */
public final class ArgumentBuilder {

    private ArgumentBuilder() {}

    public static List<String> buildJvmArgs(VersionDetail detail, LaunchContext ctx) {
        List<String> args = new ArrayList<>();

        // Always-required flags regardless of version format
        args.add("-Xms" + ctx.minRamMb + "M");
        args.add("-Xmx" + ctx.maxRamMb + "M");
        args.add("-Djava.library.path=" + ctx.nativesDir.toString());
        args.add("-Dminecraft.launcher.brand=AveroLauncher");
        args.add("-Dminecraft.launcher.version=0.1.0");
        args.add("-cp");
        args.add(ctx.classpath);

        if (detail.arguments != null && detail.arguments.jvm != null) {
            for (JsonElement el : detail.arguments.jvm) {
                appendResolvedArgument(args, el, ctx, Map.of());
            }
        }
        // Legacy versions (pre-1.13) have no structured JVM args block.
        // -Djava.library.path was already added unconditionally above, so
        // no extra work is needed here — adding it again would duplicate the flag.

        if (ctx.extraJvmArgs != null && !ctx.extraJvmArgs.isBlank()) {
            for (String extra : ctx.extraJvmArgs.trim().split("\\s+")) {
                args.add(extra);
            }
        }
        if (ctx.extraJvmArgList != null) args.addAll(ctx.extraJvmArgList);

        return args;
    }

    public static List<String> buildGameArgs(VersionDetail detail, LaunchContext ctx) {
        List<String> args = new ArrayList<>();
        Map<String, Boolean> features = Map.of(
                "has_custom_resolution", true,
                "is_demo_user", false
        );

        if (detail.arguments != null && detail.arguments.game != null) {
            for (JsonElement el : detail.arguments.game) {
                appendResolvedArgument(args, el, ctx, features);
            }
        } else if (detail.minecraftArguments != null) {
            for (String token : detail.minecraftArguments.trim().split("\\s+")) {
                args.add(substitute(token, ctx));
            }
        }

        dropUnavailableAuthFlags(args);

        // Ensure resolution flags are present even on legacy versions that
        // didn't have has_custom_resolution in their argument template.
        if (!args.contains("--width")) {
            args.add("--width");
            args.add(String.valueOf(ctx.windowWidth));
        }
        if (!args.contains("--height")) {
            args.add("--height");
            args.add(String.valueOf(ctx.windowHeight));
        }
        if (ctx.fullscreen && !args.contains("--fullscreen")) {
            args.add("--fullscreen");
        }
        if (ctx.quickPlayServerAddress != null && !args.contains("--server")) {
            String[] parts = ctx.quickPlayServerAddress.split(":", 2);
            args.add("--server");
            args.add(parts[0]);
            if (parts.length > 1) {
                args.add("--port");
                args.add(parts[1]);
            }
        }

        return args;
    }

    /**
     * Newer versions list "--clientId ${clientid}" and "--xuid ${auth_xuid}". This launcher has no real value
     * for them, and both flags are optional in the game. Sending them with an empty value is fragile (Windows
     * can drop empty arguments, which would shift the flags), and sending the raw "${...}" text would pass
     * garbage as if it were an id. So the flag AND its value are removed together.
     */
    private static void dropUnavailableAuthFlags(List<String> args) {
        for (int i = 0; i < args.size(); i++) {
            String t = args.get(i);
            if ("--clientId".equals(t) || "--xuid".equals(t)) {
                args.remove(i);
                if (i < args.size() && !args.get(i).startsWith("--")) args.remove(i);
                i--;
            }
        }
    }

    private static void appendResolvedArgument(List<String> out, JsonElement el, LaunchContext ctx, Map<String, Boolean> features) {
        if (el.isJsonPrimitive()) {
            out.add(substitute(el.getAsString(), ctx));
            return;
        }
        if (el.isJsonObject()) {
            JsonObject obj = el.getAsJsonObject();
            if (!obj.has("rules") || !obj.has("value")) {
                return;
            }
            List<VersionDetail.Rule> rules = parseRules(obj.getAsJsonArray("rules"));
            if (!RuleEvaluator.rulesAllow(rules, features)) {
                return;
            }
            JsonElement value = obj.get("value");
            if (value.isJsonArray()) {
                for (JsonElement v : value.getAsJsonArray()) {
                    out.add(substitute(v.getAsString(), ctx));
                }
            } else {
                out.add(substitute(value.getAsString(), ctx));
            }
        }
    }

    private static List<VersionDetail.Rule> parseRules(com.google.gson.JsonArray rulesArray) {
        com.google.gson.Gson gson = new com.google.gson.Gson();
        List<VersionDetail.Rule> rules = new ArrayList<>();
        for (JsonElement el : rulesArray) {
            rules.add(gson.fromJson(el, VersionDetail.Rule.class));
        }
        return rules;
    }

    private static String substitute(String template, LaunchContext ctx) {
        return template
                .replace("${auth_player_name}", nullSafe(ctx.authPlayerName, "Player"))
                .replace("${version_name}", nullSafe(ctx.versionId, "unknown"))
                .replace("${game_directory}", ctx.gameDir.toString())
                .replace("${assets_root}", ctx.assetsDir.toString())
                .replace("${assets_index_name}", nullSafe(ctx.assetsId, "legacy"))
                .replace("${auth_uuid}", nullSafe(ctx.authUuid, "00000000-0000-0000-0000-000000000000"))
                .replace("${auth_access_token}", nullSafe(ctx.authAccessToken, "0"))
                .replace("${auth_session}", nullSafe(ctx.authAccessToken, "0"))
                .replace("${user_type}", nullSafe(ctx.userType, "legacy"))
                .replace("${user_properties}", "{}")
                .replace("${version_type}", "AveroLauncher")
                .replace("${natives_directory}", ctx.nativesDir.toString())
                .replace("${launcher_name}", "AveroLauncher")
                .replace("${launcher_version}", "0.1.0")
                .replace("${classpath}", ctx.classpath)
                // Forge / NeoForge (1.17+) build their module path from these; left unreplaced the game cannot start.
                .replace("${library_directory}", ctx.minecraftDir != null
                        ? ctx.minecraftDir.resolve("libraries").toString() : "libraries")
                .replace("${classpath_separator}", java.io.File.pathSeparator)
                .replace("${resolution_width}", String.valueOf(ctx.windowWidth))
                .replace("${resolution_height}", String.valueOf(ctx.windowHeight));
    }

    private static String nullSafe(String value, String fallback) {
        return value != null ? value : fallback;
    }
}
