package com.averolauncher.model;

/**
 * Corresponds to an entry in Mojang's version_manifest_v2.json "versions" array.
 */
public class MinecraftVersionSummary {
    public String id;
    public String type;   // "release" or "snapshot" or "old_beta" / "old_alpha"
    public String url;    // URL to the full version JSON
    public String time;
    public String releaseTime;
    public String sha1;
    public boolean installed; // computed locally, not part of Mojang's JSON

    public boolean isRelease() {
        return "release".equals(type);
    }

    public boolean isSnapshot() {
        return "snapshot".equals(type);
    }
}
