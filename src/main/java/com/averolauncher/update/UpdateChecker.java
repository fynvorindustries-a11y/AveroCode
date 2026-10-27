package com.averolauncher.update;

import com.averolauncher.logging.AppLogger;
import com.averolauncher.util.PathResolver;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Checks GitHub Releases (real, public, unauthenticated REST API — no token
 * needed) for a build newer than {@link AppVersion#CURRENT}. Only releases tagged as a plain
 * {@code MAJOR.MINOR.PATCH} (no "v" prefix) at or above {@link #MIN_TAG} are
 * considered, so unrelated tags such as agent builds ("v1.123") never trigger an update. Draft and
 * pre-release entries are ignored; the highest matching version wins.
 *
 * Failures (no network, rate-limited, repo has no releases yet) are logged
 * and swallowed rather than surfaced — a missed update check should never
 * interrupt the person trying to play the game.
 */
public class UpdateChecker {

    private static final String TAG = "UpdateChecker";
    /** Only plain MAJOR.MINOR.PATCH tags (no "v" prefix) at or above this count as launcher releases. No upper cap. */
    static final String MIN_TAG = "1.1.0";

    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 3;
    private static final String RELEASES_URL =
            "https://api.github.com/repos/" + AppVersion.GITHUB_REPO + "/releases?per_page=" + PAGE_SIZE + "&page=";

    private final HttpClient httpClient;
    private final Executor backgroundExecutor;

    public UpdateChecker(Executor backgroundExecutor) {
        this.backgroundExecutor = backgroundExecutor;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Returns the latest release if it is genuinely newer than the running
     * version, or {@link Optional#empty()} if already up to date, the tag
     * couldn't be parsed as a version, or the check itself failed.
     */
    public CompletableFuture<Optional<UpdateInfo>> checkForUpdate() {
        return CompletableFuture.supplyAsync(this::checkNow, backgroundExecutor);
    }

    private Optional<UpdateInfo> checkNow() {
        try {
            JsonObject release = findNewestLauncherRelease();
            if (release == null) {
                AppLogger.get().info(TAG, "No launcher release >= " + MIN_TAG + " found");
                return Optional.empty();
            }

            String tag = asString(release, "tag_name");
            if (!VersionComparator.isNewer(AppVersion.CURRENT, tag)) {
                AppLogger.get().info(TAG, "Up to date (current " + AppVersion.CURRENT + ", latest " + tag + ")");
                return Optional.empty();
            }

            String name = asString(release, "name");
            String htmlUrl = asString(release, "html_url");
            String body = asString(release, "body");
            Asset asset = pickAssetForThisPlatform(release);

            AppLogger.get().info(TAG, "Update available: " + tag + " (current " + AppVersion.CURRENT + ")");
            return Optional.of(new UpdateInfo(
                    tag,
                    (name != null && !name.isBlank()) ? name : ("AveroLauncher " + tag),
                    htmlUrl,
                    body,
                    asset != null ? asset.url() : null,
                    asset != null ? asset.name() : null,
                    asset != null ? asset.size() : 0,
                    findAssetUrl(release, "update-manifest.json"),
                    findAssetUrl(release, "update-manifest.json.sig")));
        } catch (Exception e) {
            AppLogger.get().warn(TAG, "Update check failed: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Walks the repo's release list and returns the highest-versioned published release whose tag is a
     * plain {@code MAJOR.MINOR.PATCH} at or above {@link #MIN_TAG}. Anything else on the
     * feed (agent builds tagged "v1.123", drafts, pre-releases, odd tags) is skipped. Returns null if
     * there is none or the request failed.
     */
    private JsonObject findNewestLauncherRelease() throws Exception {
        JsonObject best = null;
        String bestTag = null;

        for (int page = 1; page <= MAX_PAGES; page++) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(RELEASES_URL + page))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "AveroLauncher/" + AppVersion.CURRENT + " (update-checker)")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 404) {
                AppLogger.get().info(TAG, "No releases published yet for " + AppVersion.GITHUB_REPO);
                return null;
            }
            if (response.statusCode() != 200) {
                AppLogger.get().warn(TAG, "Update check failed: HTTP " + response.statusCode()
                        + " - " + truncate(response.body()));
                return best;
            }

            JsonArray releases = JsonParser.parseString(response.body()).getAsJsonArray();
            for (JsonElement el : releases) {
                if (!el.isJsonObject()) continue;
                JsonObject release = el.getAsJsonObject();
                if (asBoolean(release, "draft") || asBoolean(release, "prerelease")) continue;

                String tag = asString(release, "tag_name");
                if (!VersionComparator.isPlainReleaseAtLeast(tag, MIN_TAG)) continue;

                if (best == null || VersionComparator.isNewerPlain(tag, bestTag)) {
                    best = release;
                    bestTag = tag;
                }
            }

            if (releases.size() < PAGE_SIZE) break;   // last page
        }
        return best;
    }

    /**
     * Picks the release asset that matches the OS we're running on. Falls back to
     * {@code null} (no direct download offered, just the release page link) if the
     * release has no assets at all or none look like they're for this platform —
     * safer than guessing wrong and handing someone an installer for another OS.
     */
    private Asset pickAssetForThisPlatform(JsonObject release) {
        JsonElement assetsEl = release.get("assets");
        if (assetsEl == null || !assetsEl.isJsonArray()) {
            return null;
        }
        JsonArray assets = assetsEl.getAsJsonArray();
        for (JsonElement el : assets) {
            JsonObject assetObj = el.getAsJsonObject();
            String fileName = asString(assetObj, "name");
            if (fileName == null) continue;
            if (matchesPlatform(fileName.toLowerCase(Locale.ROOT))) {
                String url = asString(assetObj, "browser_download_url");
                long size = assetObj.has("size") && !assetObj.get("size").isJsonNull()
                        ? assetObj.get("size").getAsLong() : 0;
                return new Asset(url, fileName, size);
            }
        }
        return null;
    }

    /** browser_download_url of the release asset with exactly this file name, or null. */
    private String findAssetUrl(JsonObject release, String exactName) {
        JsonElement assetsEl = release.get("assets");
        if (assetsEl == null || !assetsEl.isJsonArray()) return null;
        for (JsonElement el : assetsEl.getAsJsonArray()) {
            JsonObject a = el.getAsJsonObject();
            if (exactName.equals(asString(a, "name"))) return asString(a, "browser_download_url");
        }
        return null;
    }

    private boolean matchesPlatform(String lowerFileName) {
        if (PathResolver.isWindows()) {
            return lowerFileName.endsWith(".exe") || lowerFileName.endsWith(".msi")
                    || lowerFileName.endsWith(".msix")
                    || (lowerFileName.endsWith(".zip") && (lowerFileName.contains("win") || lowerFileName.contains("windows")));
        } else if (PathResolver.isMac()) {
            return lowerFileName.endsWith(".dmg") || lowerFileName.endsWith(".pkg")
                    || (lowerFileName.endsWith(".zip") && (lowerFileName.contains("mac") || lowerFileName.contains("osx") || lowerFileName.contains("darwin")));
        } else {
            return lowerFileName.endsWith(".appimage") || lowerFileName.endsWith(".deb") || lowerFileName.endsWith(".rpm")
                    || lowerFileName.endsWith(".tar.gz")
                    || (lowerFileName.endsWith(".zip") && lowerFileName.contains("linux"));
        }
    }

    private static String asString(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return (el != null && !el.isJsonNull()) ? el.getAsString() : null;
    }

    private static boolean asBoolean(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return el != null && !el.isJsonNull() && el.getAsBoolean();
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    private record Asset(String url, String name, long size) {}
}
