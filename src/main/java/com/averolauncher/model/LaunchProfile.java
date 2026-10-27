package com.averolauncher.model;

/**
 * A launcher profile: a saved combination of version, Java, JVM args,
 * game directory, resolution, RAM, and account selection.
 */
public class LaunchProfile {

    public String id;
    public String name;
    public String versionId;           // e.g. "1.21.1"
    public String javaPathOverride;    // null = use settings default
    public String jvmArgsExtra = "";   // extra args appended after required ones
    public String gameDirectoryOverride; // null = use global minecraft directory
    public int windowWidth = 1280;
    public int windowHeight = 720;
    public boolean fullscreen = false;
    public Integer minRamMbOverride;   // null = use global settings
    public Integer maxRamMbOverride;   // null = use global settings
    public String accountId;           // null = use currently active account
    public String serverAddressAutoJoin; // null = don't auto-join

    public LaunchProfile() {}

    public LaunchProfile(String id, String name, String versionId) {
        this.id = id;
        this.name = name;
        this.versionId = versionId;
    }
}
