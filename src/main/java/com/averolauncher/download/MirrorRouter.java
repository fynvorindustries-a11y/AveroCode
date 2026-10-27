package com.averolauncher.download;

import com.averolauncher.logging.AppLogger;
import com.averolauncher.util.PathResolver;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Fallback routing for networks where Mojang's hostnames are unreachable
 * (filtered by hostname, blocked route, etc).
 *
 * Mojang's own hosts always come first. The BMCLAPI mirror (by bangbang93,
 * https://bmclapidoc.bangbang93.com) is used only when:
 *   - the request is to a known Mojang host (anything else is never rewritten),
 *   - the "use mirror" setting is on, and
 *   - Mojang's host FAILED at the network level while the mirror WORKED. From then
 *     on the mirror is tried first for a few hours so every launch doesn't first
 *     wait out Mojang's connect timeout.
 *
 * Trust note: file downloads are still verified against the SHA-1 hashes in the version
 * metadata, but when the mirror is in use that metadata comes from the mirror too.
 * That is a genuine trust decision, which is why it can be turned off in Settings.
 *
 * BMCLAPI's terms require programs using it to show its name in the UI; see
 * {@link #CREDIT} and {@link #notice()}.
 */
public final class MirrorRouter {

    private static final String TAG = "MirrorRouter";

    public static final String CREDIT = "BMCLAPI mirror by bangbang93 (bmclapidoc.bangbang93.com)";

    /** Overridable for testing / self-hosting, same idea as HMCL's hmcl.bmclapi.override. */
    private static final String MIRROR_ROOT =
            System.getProperty("avero.mirror.root", "https://bmclapi2.bangbang93.com");

    /** Mojang host -> path prefix on the mirror. */
    private static final Map<String, String> HOST_TO_PREFIX = Map.of(
            "piston-meta.mojang.com", "",
            "piston-data.mojang.com", "",
            "launchermeta.mojang.com", "",
            "launcher.mojang.com", "",
            "libraries.minecraft.net", "/maven",
            "resources.download.minecraft.net", "/assets");

    /** How long a "Mojang is unreachable, prefer the mirror" observation stays valid across restarts. */
    private static final long PREFER_TTL_MS = 6L * 60 * 60 * 1000;

    private static volatile boolean enabled = true;
    private static volatile boolean preferMirror = false;
    private static volatile boolean noticeShown = false;
    private static volatile boolean mirrorUsedThisSession = false;
    private static volatile Consumer<String> noticeListener;

    static {
        loadPersistedPreference();
    }

    private MirrorRouter() {}

    // ─── Configuration ──────────────────────────────────────────────────────

    public static void setEnabled(boolean value) {
        enabled = value;
        if (!value) {
            preferMirror = false;
        } else {
            loadPersistedPreference();
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Receives the one-time "using mirror" notice (called from whichever thread first uses it). */
    public static void setNoticeListener(Consumer<String> listener) {
        noticeListener = listener;
    }

    // ─── URL mapping ────────────────────────────────────────────────────────

    /** Returns the mirror equivalent of {@code url}, or null if disabled / not a Mojang host. */
    public static String mirrorUrl(String url) {
        if (!enabled || url == null) return null;
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) return null;
            String prefix = HOST_TO_PREFIX.get(host.toLowerCase(Locale.ROOT));
            if (prefix == null) return null;
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
            return MIRROR_ROOT + prefix + path + query;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Ordered URLs to try: [original] when no mirror applies, otherwise both in preferred order. */
    public static List<String> candidates(String url) {
        String mirror = mirrorUrl(url);
        if (mirror == null) return List.of(url);
        return preferMirror ? List.of(mirror, url) : List.of(url, mirror);
    }

    public static boolean preferMirror() {
        return enabled && preferMirror;
    }

    // ─── Outcome reporting (called by the network layers) ───────────────────

    /** True for failures that mean "could not reach the host" as opposed to "the host answered with an error". */
    public static boolean isNetworkLevel(Throwable e) {
        Throwable c = e;
        while (c != null) {
            if (c instanceof HttpTimeoutException || c instanceof SocketTimeoutException
                    || c instanceof ConnectException || c instanceof NoRouteToHostException
                    || c instanceof UnknownHostException || c instanceof javax.net.ssl.SSLException) {
                return true;
            }
            c = c.getCause();
        }
        return false;
    }

    /** The mirror worked right after Mojang failed to connect: prefer the mirror from now on. */
    public static void mirrorSucceededAfterPrimaryFailure() {
        if (!preferMirror) {
            preferMirror = true;
            persistPreference();
            AppLogger.get().warn(TAG, "Mojang's servers are unreachable from this network but the mirror works; "
                    + "using the mirror first for the next few hours");
        }
        noteMirrorUse();
    }

    /** A request was served by the mirror. */
    public static void noteMirrorUse() {
        mirrorUsedThisSession = true;
        if (!noticeShown) {
            noticeShown = true;
            Consumer<String> l = noticeListener;
            if (l != null) {
                try { l.accept(notice()); } catch (RuntimeException ignored) {}
            }
        }
    }

    /** Mojang answered again while we were preferring the mirror: go back to Mojang first. */
    public static void reportPrimarySuccess() {
        if (preferMirror) {
            preferMirror = false;
            clearPersistedPreference();
            AppLogger.get().info(TAG, "Mojang's servers are reachable again; no longer preferring the mirror");
        }
    }

    public static boolean isMirrorActive() {
        return enabled && (preferMirror || mirrorUsedThisSession);
    }

    /** User-facing text (also satisfies BMCLAPI's attribution requirement). */
    public static String notice() {
        return "Mojang's servers are unreachable from this network - using the " + CREDIT + ".";
    }

    // ─── Persistence of the "prefer mirror" observation ─────────────────────

    private static Path stateFile() {
        return PathResolver.launcherDataDir().resolve("cache").resolve("mirror-preferred.txt");
    }

    private static void persistPreference() {
        try {
            Path f = stateFile();
            Files.createDirectories(f.getParent());
            Files.writeString(f, Long.toString(System.currentTimeMillis()));
        } catch (IOException e) {
            AppLogger.get().warn(TAG, "Could not persist mirror preference: " + e.getMessage());
        }
    }

    private static void clearPersistedPreference() {
        try {
            Files.deleteIfExists(stateFile());
        } catch (IOException ignored) {}
    }

    private static void loadPersistedPreference() {
        try {
            Path f = stateFile();
            if (!Files.exists(f)) return;
            long ts = Long.parseLong(Files.readString(f).trim());
            if (System.currentTimeMillis() - ts < PREFER_TTL_MS) {
                preferMirror = true;
            } else {
                Files.deleteIfExists(f);
            }
        } catch (Exception ignored) {
            // unreadable/corrupt state file: behave as if there were none
        }
    }
}
