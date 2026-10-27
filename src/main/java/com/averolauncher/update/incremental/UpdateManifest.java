package com.averolauncher.update.incremental;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The signed release manifest. It lists every file of the installed application image with its
 * SHA-256, and where that file's bytes live inside the release's single {@code payload.pack}.
 *
 * <p>The JSON is parsed with unknown fields ignored, so newer generators can add fields without
 * breaking this client; {@link #schema} and {@link #minUpdaterVersion} are the hard compatibility gates.
 * <b>Never call {@link #parse} on bytes whose signature has not been verified.</b>
 */
public final class UpdateManifest {

    public static final int SUPPORTED_SCHEMA = 1;
    /** What this client's update machinery understands; a manifest may demand a newer one. */
    public static final int UPDATER_PROTOCOL = 1;

    public static final long MAX_FILE_BYTES = 1L << 30;       // 1 GiB per file
    public static final long MAX_TOTAL_BYTES = 4L << 30;      // 4 GiB per release
    public static final int MAX_FILES = 20_000;

    private static final Pattern SEMVER = Pattern.compile("^\\d{1,6}\\.\\d{1,6}\\.\\d{1,6}$");
    private static final Pattern HEX64 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern PACK_NAME = Pattern.compile("^[A-Za-z0-9._-]{1,100}$");
    private static final Set<String> RESERVED = Set.of("CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    public int schema;
    public String app;
    public String version;
    public String releaseId;
    public int minUpdaterVersion;
    public String keyId;
    /** Path of the launcher executable, relative to the application image root. */
    public String launcher;
    public Pack pack;
    public List<FileEntry> files;
    /** Files that existed in the previous release and are gone now (the client also diffs its own record). */
    public List<String> deleted;
    public String notes;
    public Rotation rotation;
    /** Reserved for binary-delta patches (not produced or used yet); ignored by this client. */
    public List<Object> patches;

    public static final class Pack {
        public String name;
        public long size;
        public String sha256;
    }

    public static final class FileEntry {
        public String path;
        public long size;
        public String sha256;
        /** Byte offset of this file's blob inside the pack. */
        public long offset;
        public long packedSize;
        /** 0 = stored, 1 = raw deflate. */
        public int method;
    }

    public static final class Rotation {
        public List<Key> newKeys;
    }

    public static final class Key {
        public String id;
        public String publicKey;
    }

    public static final class ManifestException extends Exception {
        public ManifestException(String message) { super(message); }
    }

    // ------------------------------------------------------------------ parse / validate

    public static UpdateManifest parse(byte[] json) throws ManifestException {
        if (json == null || json.length == 0 || json.length > 8 * 1024 * 1024) {
            throw new ManifestException("Manifest is empty or too large");
        }
        UpdateManifest m;
        try {
            m = new Gson().fromJson(new String(json, StandardCharsets.UTF_8), UpdateManifest.class);
        } catch (JsonSyntaxException | IllegalStateException e) {
            throw new ManifestException("Manifest is not valid JSON");
        }
        if (m == null) throw new ManifestException("Manifest is empty");
        m.validate();
        return m;
    }

    public void validate() throws ManifestException {
        if (schema != SUPPORTED_SCHEMA) throw new ManifestException("Unsupported manifest schema " + schema);
        if (minUpdaterVersion > UPDATER_PROTOCOL) {
            throw new ManifestException("This update needs a newer updater; install the latest launcher manually");
        }
        if (app == null || app.isBlank() || app.length() > 64) throw new ManifestException("Bad app name");
        if (version == null || !SEMVER.matcher(version).matches()) throw new ManifestException("Bad version");
        if (!isSafeRelativePath(launcher)) throw new ManifestException("Bad launcher path");
        if (pack == null || pack.name == null || !PACK_NAME.matcher(pack.name).matches()
                || pack.sha256 == null || !HEX64.matcher(pack.sha256).matches()
                || pack.size <= 0 || pack.size > MAX_TOTAL_BYTES) {
            throw new ManifestException("Bad pack description");
        }
        if (files == null || files.isEmpty() || files.size() > MAX_FILES) throw new ManifestException("Bad file list");

        Set<String> seen = new HashSet<>();
        boolean launcherListed = false;
        long totalSize = 0;
        for (FileEntry f : files) {
            if (f == null || !isSafeRelativePath(f.path)) throw new ManifestException("Unsafe file path in manifest");
            if (!seen.add(f.path.toLowerCase(Locale.ROOT))) throw new ManifestException("Duplicate file path: " + f.path);
            if (f.sha256 == null || !HEX64.matcher(f.sha256).matches()) throw new ManifestException("Bad hash for " + f.path);
            if (f.size < 0 || f.size > MAX_FILE_BYTES) throw new ManifestException("Bad size for " + f.path);
            if (f.method != 0 && f.method != 1) throw new ManifestException("Unknown pack method for " + f.path);
            if (f.offset < 0 || f.packedSize < 0 || f.packedSize > MAX_FILE_BYTES) throw new ManifestException("Bad pack range for " + f.path);
            if (f.method == 0 && f.packedSize != f.size) throw new ManifestException("Stored size mismatch for " + f.path);
            if (f.method == 1 && f.packedSize == 0) throw new ManifestException("Empty deflate blob for " + f.path);
            totalSize += f.size;
            if (totalSize > MAX_TOTAL_BYTES) throw new ManifestException("Release is too large");
            if (f.path.equalsIgnoreCase(launcher)) launcherListed = true;
        }
        if (!launcherListed) throw new ManifestException("Launcher executable is not part of the file list");

        // The pack must be exactly the files' blobs back to back: no gaps, overlaps or hidden extras.
        List<FileEntry> byOffset = new ArrayList<>(files);
        byOffset.sort(Comparator.comparingLong(e -> e.offset));
        long cursor = 0;
        for (FileEntry e : byOffset) {
            if (e.offset != cursor) throw new ManifestException("Pack layout is not contiguous");
            cursor += e.packedSize;
        }
        if (cursor != pack.size) throw new ManifestException("Pack size does not match its contents");

        if (deleted != null) {
            for (String d : deleted) {
                if (!isSafeRelativePath(d)) throw new ManifestException("Unsafe deleted path in manifest");
                if (seen.contains(d.toLowerCase(Locale.ROOT))) throw new ManifestException("Path is both kept and deleted: " + d);
            }
        }
        if (rotation != null && rotation.newKeys != null) {
            for (Key k : rotation.newKeys) {
                if (k == null || k.id == null || !k.id.matches("[A-Za-z0-9._-]{1,32}")
                        || k.publicKey == null || k.publicKey.length() > 200) {
                    throw new ManifestException("Bad rotation key");
                }
            }
        }
    }

    public long totalSize() {
        long t = 0;
        for (FileEntry f : files) t += f.size;
        return t;
    }

    // ------------------------------------------------------------------ path safety

    /**
     * A manifest path is always relative, uses '/' only, and is safe on Windows: no drive letters,
     * no "..", no device names, no characters Windows rejects, no trailing dot/space.
     */
    public static boolean isSafeRelativePath(String p) {
        if (p == null || p.isEmpty() || p.length() > 240) return false;
        if (p.startsWith("/") || p.endsWith("/") || p.contains("//") || p.contains("\\")) return false;
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c < 0x20 || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|') return false;
        }
        for (String seg : p.split("/", -1)) {
            if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) return false;
            if (seg.endsWith(".") || seg.endsWith(" ")) return false;
            int dot = seg.indexOf('.');
            String stem = (dot < 0 ? seg : seg.substring(0, dot)).toUpperCase(Locale.ROOT);
            if (RESERVED.contains(stem)) return false;
        }
        return true;
    }
}
