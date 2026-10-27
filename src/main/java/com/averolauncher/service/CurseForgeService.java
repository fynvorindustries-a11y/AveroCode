package com.averolauncher.service;

import com.averolauncher.model.curseforge.CurseForgeFileResponse;
import com.averolauncher.model.curseforge.CurseForgeSearchResponse;
import com.google.gson.Gson;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Async client for the CurseForge Core v1 API.
 * Docs: https://docs.curseforge.com/
 *
 * The API key is sent as the "x-api-key" header on every request.
 * Game ID 432 = Minecraft.
 *
 * Class IDs:
 *   6     = Mods
 *   4471  = Modpacks
 *   12    = Resource Packs
 *   6552  = Shaders
 *   6945  = Data Packs
 *   5     = Plugins (Bukkit/Spigot)
 *   4559  = Worlds
 */
public class CurseForgeService {

    private static final String BASE = "https://api.curseforge.com";
    private static final int GAME_ID = 432;

    public static final int CLASS_MOD          = 6;
    public static final int CLASS_MODPACK      = 4471;
    public static final int CLASS_RESOURCEPACK = 12;
    public static final int CLASS_SHADER       = 6552;
    public static final int CLASS_DATAPACK     = 6945;
    public static final int CLASS_PLUGIN       = 5;
    public static final int CLASS_WORLD        = 4559;

    // ModLoader type IDs used by the file index filter
    public static final int LOADER_ANY     = 0;
    public static final int LOADER_FORGE   = 1;
    public static final int LOADER_CAULDRON= 2;
    public static final int LOADER_LITELOADER=3;
    public static final int LOADER_FABRIC  = 4;
    public static final int LOADER_QUILT   = 5;
    public static final int LOADER_NEOFORGE= 6;

    // Sort fields (sortField query param)
    public enum SortField {
        FEATURED(1), POPULARITY(2), LAST_UPDATED(3), NAME(4),
        AUTHOR(5), TOTAL_DOWNLOADS(6), CATEGORY(7), GAME_VERSION(8),
        EARLY_ACCESS(9), FEATURED_RELEASED(10), RELEASE_DATE(11), CREATED_DATE(12);

        public final int id;
        SortField(int id) { this.id = id; }

        @Override public String toString() {
            return switch (this) {
                case FEATURED          -> "Featured";
                case POPULARITY        -> "Popular";
                case LAST_UPDATED      -> "Recently Updated";
                case NAME              -> "Name";
                case AUTHOR            -> "Author";
                case TOTAL_DOWNLOADS   -> "Most Downloaded";
                case CATEGORY          -> "Category";
                case GAME_VERSION      -> "Game Version";
                case EARLY_ACCESS      -> "Early Access";
                case FEATURED_RELEASED -> "Featured Released";
                case RELEASE_DATE      -> "Release Date";
                case CREATED_DATE      -> "Newest";
            };
        }
    }

    public static class SearchQuery {
        public int classId = CLASS_MOD;
        public String searchFilter = "";
        public String gameVersion;           // e.g. "1.20.1" — null = any
        public Integer modLoaderType;        // null = any; use LOADER_* constants
        public SortField sortField = SortField.POPULARITY;
        public int pageSize = 20;
        public int index    = 0;            // offset (0-based)
    }

    private final String apiKey;
    private final HttpClient http;
    private final Gson gson = new Gson();

    public CurseForgeService(String apiKey) {
        this.apiKey = apiKey;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    // ── Search ────────────────────────────────────────────────────────────

    public CompletableFuture<CurseForgeSearchResponse> search(SearchQuery q) {
        StringBuilder url = new StringBuilder(BASE)
                .append("/v1/mods/search")
                .append("?gameId=").append(GAME_ID)
                .append("&classId=").append(q.classId)
                .append("&pageSize=").append(q.pageSize)
                .append("&index=").append(q.index)
                .append("&sortField=").append(q.sortField.id)
                .append("&sortOrder=desc");

        if (q.searchFilter != null && !q.searchFilter.isBlank())
            url.append("&searchFilter=").append(encode(q.searchFilter));
        if (q.gameVersion != null && !q.gameVersion.isBlank())
            url.append("&gameVersion=").append(encode(q.gameVersion));
        if (q.modLoaderType != null && q.modLoaderType != LOADER_ANY)
            url.append("&modLoaderType=").append(q.modLoaderType);

        return get(url.toString())
                .thenApply(body -> gson.fromJson(body, CurseForgeSearchResponse.class));
    }

    // ── Mod details ───────────────────────────────────────────────────────

    /** Returns a single mod's full detail wrapped in a one-field container. */
    public CompletableFuture<CurseForgeSearchResponse.CurseForgeMod> getMod(int modId) {
        return get(BASE + "/v1/mods/" + modId)
                .thenApply(body -> {
                    record Wrapper(CurseForgeSearchResponse.CurseForgeMod data) {}
                    return gson.fromJson(body, Wrapper.class).data();
                });
    }

    // ── Files / versions ──────────────────────────────────────────────────

    /**
     * Returns the list of files for a mod, optionally filtered by game version
     * and mod-loader type. The list is sorted newest-first by the API.
     */
    public CompletableFuture<CurseForgeFileResponse> getFiles(int modId,
                                                               String gameVersion,
                                                               Integer modLoaderType) {
        StringBuilder url = new StringBuilder(BASE)
                .append("/v1/mods/").append(modId).append("/files")
                .append("?pageSize=50");

        if (gameVersion != null && !gameVersion.isBlank())
            url.append("&gameVersion=").append(encode(gameVersion));
        if (modLoaderType != null && modLoaderType != LOADER_ANY)
            url.append("&modLoaderType=").append(modLoaderType);

        return get(url.toString())
                .thenApply(body -> gson.fromJson(body, CurseForgeFileResponse.class));
    }

    /** Fetches the download URL for a specific file (needed when downloadUrl is null). */
    public CompletableFuture<String> getFileDownloadUrl(int modId, int fileId) {
        return get(BASE + "/v1/mods/" + modId + "/files/" + fileId + "/download-url")
                .thenApply(body -> {
                    // Response: { "data": "https://..." }
                    record Wrapper(String data) {}
                    return gson.fromJson(body, Wrapper.class).data();
                });
    }

    // ── Categories ────────────────────────────────────────────────────────

    public CompletableFuture<List<CurseForgeSearchResponse.Category>> getCategories(int classId) {
        String url = BASE + "/v1/categories?gameId=" + GAME_ID + "&classId=" + classId;
        return get(url).thenApply(body -> {
            record Wrapper(List<CurseForgeSearchResponse.Category> data) {}
            return gson.fromJson(body, Wrapper.class).data();
        });
    }

    // ── Featured mods ─────────────────────────────────────────────────────

    public CompletableFuture<List<CurseForgeSearchResponse.CurseForgeMod>> getFeatured(int classId) {
        String body = String.format(
                "{\"gameId\":%d,\"excludedModIds\":[],\"gameVersionTypeId\":null}", GAME_ID);
        return post(BASE + "/v1/mods/featured", body)
                .thenApply(resp -> {
                    record Featured(
                            List<CurseForgeSearchResponse.CurseForgeMod> featured,
                            List<CurseForgeSearchResponse.CurseForgeMod> popular,
                            List<CurseForgeSearchResponse.CurseForgeMod> recentlyUpdated) {}
                    record Wrapper(Featured data) {}
                    var w = gson.fromJson(resp, Wrapper.class);
                    if (w == null || w.data() == null) return List.of();
                    var all = new java.util.ArrayList<CurseForgeSearchResponse.CurseForgeMod>();
                    if (w.data().featured() != null) all.addAll(w.data().featured());
                    return all.stream()
                            .filter(m -> m.classId == classId || classId == 0)
                            .collect(Collectors.toList());
                });
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────

    private CompletableFuture<String> get(String url) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("x-api-key", apiKey)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() < 200 || resp.statusCode() >= 300)
                        throw new RuntimeException("CurseForge API error " + resp.statusCode()
                                + " for " + url + ": " + resp.body());
                    return resp.body();
                });
    }

    private CompletableFuture<String> post(String url, String jsonBody) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("x-api-key", apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() < 200 || resp.statusCode() >= 300)
                        throw new RuntimeException("CurseForge API error " + resp.statusCode()
                                + ": " + resp.body());
                    return resp.body();
                });
    }

    private static String encode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
