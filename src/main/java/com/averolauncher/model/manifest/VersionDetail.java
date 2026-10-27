package com.averolauncher.model.manifest;

import com.google.gson.JsonElement;

import java.util.List;
import java.util.Map;

/**
 * Models a single version's JSON file (e.g. 1.21.1.json), as served by the
 * URL in a VersionManifest.VersionEntry. Mirrors Mojang's real schema,
 * including the newer "arguments" structure (post-1.13) used alongside the
 * legacy "minecraftArguments" string (pre-1.13) for backward compatibility.
 */
public class VersionDetail {
    public String id;
    public String type;
    public String mainClass;
    public String minecraftArguments;      // legacy (pre-1.13) game args string
    public Arguments arguments;             // modern (1.13+) structured args
    public AssetIndexRef assetIndex;
    public String assets;
    public int complianceLevel;
    public Downloads downloads;
    public List<Library> libraries;
    public JavaVersionRef javaVersion;
    public Map<String, LoggingConfig> logging;
    public int minimumLauncherVersion;
    public String releaseTime;
    public String time;
    public String inheritsFrom;             // used by Forge/Fabric-style versions

    public static class Arguments {
        public List<JsonElement> game;   // mix of plain strings and conditional-rule objects
        public List<JsonElement> jvm;    // same
    }

    public static class AssetIndexRef {
        public String id;
        public String sha1;
        public long size;
        public long totalSize;
        public String url;
    }

    public static class Downloads {
        public DownloadArtifact client;
        public DownloadArtifact server;
        public DownloadArtifact client_mappings;
        public DownloadArtifact server_mappings;
    }

    public static class DownloadArtifact {
        public String sha1;
        public long size;
        public String url;
        public String path; // only present on library artifacts
    }

    public static class Library {
        public String name; // groupId:artifactId:version
        /** Maven repository base URL; Fabric, Quilt and older Forge list this instead of a downloads block. */
        public String url;
        public LibraryDownloads downloads;
        public List<Rule> rules;              // OS-conditional inclusion
        public Map<String, String> natives;   // os-name -> classifier, for native libs
        public ExtractRule extract;
    }

    public static class LibraryDownloads {
        public DownloadArtifact artifact;
        public Map<String, DownloadArtifact> classifiers; // native jars per-OS
    }

    public static class Rule {
        public String action; // "allow" or "disallow"
        public OsRule os;
        public Map<String, Boolean> features; // e.g. is_demo_user, has_custom_resolution
    }

    public static class OsRule {
        public String name;    // "windows", "osx", "linux"
        public String version; // regex, rarely used
        public String arch;    // "x86", etc, rarely used
    }

    public static class ExtractRule {
        public List<String> exclude;
    }

    public static class JavaVersionRef {
        public String component;
        public int majorVersion;
    }

    public static class LoggingConfig {
        public String argument;
        public LoggingFile file;
        public String type;
    }

    public static class LoggingFile {
        public String id;
        public String sha1;
        public long size;
        public String url;
    }
}
