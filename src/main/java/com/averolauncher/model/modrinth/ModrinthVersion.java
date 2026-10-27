package com.averolauncher.model.modrinth;

import java.util.List;

/** A single version/release of a Modrinth project, from GET /v2/project/{id}/version */
public class ModrinthVersion {
    public String id;
    public String project_id;
    public String name;
    public String version_number;
    public List<String> game_versions;
    public String version_type; // "release", "beta", "alpha"
    public List<String> loaders;
    public boolean featured;
    public String date_published;
    public long downloads;
    public List<ModrinthFile> files;
    public List<Dependency> dependencies;

    public static class ModrinthFile {
        public Hashes hashes;
        public String url;
        public String filename;
        public boolean primary;
        public long size;
    }

    public static class Hashes {
        public String sha1;
        public String sha512;
    }

    public static class Dependency {
        public String version_id;
        public String project_id;
        public String file_name;
        public String dependency_type; // "required", "optional", "incompatible", "embedded"
    }
}
