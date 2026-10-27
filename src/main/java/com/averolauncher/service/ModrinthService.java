package com.averolauncher.service;

import com.google.gson.Gson;
import com.averolauncher.logging.AppLogger;
import com.averolauncher.model.modrinth.ModrinthSearchResponse;
import com.averolauncher.model.modrinth.ModrinthVersion;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Talks to Modrinth's real, public, unauthenticated v2 API
 * (https://docs.modrinth.com/api). No API key required for search/browse.
 * Facets follow Modrinth's documented format: a JSON array-of-arrays where
 * each inner array is OR'd together and outer arrays are AND'd, e.g.
 * [["project_type:mod"],["versions:1.20.1"],["categories:fabric"]]
 */
public class ModrinthService {

    private static final String TAG = "ModrinthService";
    private static final String BASE_URL = "https://api.modrinth.com/v2";

    private final HttpClient httpClient;
    private final Gson gson = new Gson();
    private final Executor backgroundExecutor;

    public ModrinthService(Executor backgroundExecutor) {
        this.backgroundExecutor = backgroundExecutor;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public enum ProjectType {
        MOD("mod"), MODPACK("modpack"), RESOURCEPACK("resourcepack"),
        SHADER("shader"), DATAPACK("datapack");

        public final String apiValue;
        ProjectType(String apiValue) { this.apiValue = apiValue; }
    }

    public enum SortIndex {
        RELEVANCE("relevance"), DOWNLOADS("downloads"), FOLLOWS("follows"),
        NEWEST("newest"), UPDATED("updated");

        public final String apiValue;
        SortIndex(String apiValue) { this.apiValue = apiValue; }
    }

    public static class SearchQuery {
        public String text = "";
        public ProjectType projectType = ProjectType.MOD;
        public String gameVersion;      // e.g. "1.20.1", null = no filter
        public String loader;           // e.g. "fabric", null = no filter
        public List<String> categories = new ArrayList<>();
        public SortIndex sort = SortIndex.RELEVANCE;
        public int offset = 0;
        public int limit = 20;
    }

    public CompletableFuture<ModrinthSearchResponse> search(SearchQuery query) {
        return CompletableFuture.supplyAsync(() -> {
            String url = buildSearchUrl(query);
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(15))
                        .header("User-Agent", "AveroLauncher/0.1.0 (contact: launcher@averolauncher.local)")
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new RuntimeException("Modrinth API returned HTTP " + response.statusCode());
                }
                return gson.fromJson(response.body(), ModrinthSearchResponse.class);
            } catch (Exception e) {
                AppLogger.get().error(TAG, "Modrinth search failed: " + url, e);
                throw new RuntimeException("Could not reach Modrinth: " + e.getMessage(), e);
            }
        }, backgroundExecutor);
    }

    /** Fetches all versions of a project, so the launcher can pick the right file for the user's MC version + loader. */
    public CompletableFuture<List<ModrinthVersion>> fetchVersions(String projectId, String gameVersion, String loader) {
        return CompletableFuture.supplyAsync(() -> {
            StringBuilder url = new StringBuilder(BASE_URL + "/project/" + urlEncode(projectId) + "/version");
            List<String> params = new ArrayList<>();
            if (gameVersion != null) {
                params.add("game_versions=" + urlEncode("[\"" + gameVersion + "\"]"));
            }
            if (loader != null) {
                params.add("loaders=" + urlEncode("[\"" + loader + "\"]"));
            }
            if (!params.isEmpty()) {
                url.append("?").append(String.join("&", params));
            }
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url.toString()))
                        .timeout(Duration.ofSeconds(15))
                        .header("User-Agent", "AveroLauncher/0.1.0 (contact: launcher@averolauncher.local)")
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new RuntimeException("Modrinth API returned HTTP " + response.statusCode());
                }
                ModrinthVersion[] versions = gson.fromJson(response.body(), ModrinthVersion[].class);
                return List.of(versions);
            } catch (Exception e) {
                AppLogger.get().error(TAG, "Modrinth version fetch failed for " + projectId, e);
                throw new RuntimeException("Could not fetch mod versions: " + e.getMessage(), e);
            }
        }, backgroundExecutor);
    }

    private String buildSearchUrl(SearchQuery query) {
        StringBuilder url = new StringBuilder(BASE_URL + "/search?query=" + urlEncode(query.text));
        url.append("&offset=").append(query.offset);
        url.append("&limit=").append(query.limit);
        url.append("&index=").append(query.sort.apiValue);

        List<String> facetGroups = new ArrayList<>();
        facetGroups.add("[\"project_type:" + query.projectType.apiValue + "\"]");
        if (query.gameVersion != null) {
            facetGroups.add("[\"versions:" + query.gameVersion + "\"]");
        }
        if (query.loader != null) {
            facetGroups.add("[\"categories:" + query.loader + "\"]");
        }
        for (String category : query.categories) {
            facetGroups.add("[\"categories:" + category + "\"]");
        }
        String facets = "[" + String.join(",", facetGroups) + "]";
        url.append("&facets=").append(urlEncode(facets));

        return url.toString();
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
