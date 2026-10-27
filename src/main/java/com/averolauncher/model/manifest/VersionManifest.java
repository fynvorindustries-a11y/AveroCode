package com.averolauncher.model.manifest;

import java.util.List;

/** Root of Mojang's version_manifest_v2.json */
public class VersionManifest {
    public Latest latest;
    public List<VersionEntry> versions;

    public static class Latest {
        public String release;
        public String snapshot;
    }

    public static class VersionEntry {
        public String id;
        public String type;
        public String url;
        public String time;
        public String releaseTime;
        public String sha1;
        public int complianceLevel;
    }
}
