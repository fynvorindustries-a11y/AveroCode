package com.averolauncher.model.modrinth;

import java.util.List;

/**
 * Models Modrinth's real, documented v2 API response shapes
 * (https://docs.modrinth.com/api/operations/searchprojects/). Field names
 * match the API exactly (snake_case preserved via Gson's default mapping
 * where the JSON key equals the Java field name).
 */
public class ModrinthSearchResponse {
    public List<ModrinthHit> hits;
    public int offset;
    public int limit;
    public int total_hits;

    public static class ModrinthHit {
        public String project_id;
        public String project_type;   // "mod", "modpack", "resourcepack", "shader", "datapack"
        public String slug;
        public String author;
        public String title;
        public String description;
        public List<String> categories;
        public List<String> display_categories;
        public List<String> versions; // game versions supported, e.g. "1.20.1"
        public long downloads;
        public String follows;
        public String icon_url;
        public String date_created;
        public String date_modified;
        public String latest_version;
        public String license;
        public List<String> gallery;
        public String featured_gallery;
        public List<String> loaders; // "fabric", "forge", "neoforge", "quilt", etc.
    }
}
