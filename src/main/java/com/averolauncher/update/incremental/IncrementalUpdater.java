package com.averolauncher.update.incremental;

import com.averolauncher.logging.AppLogger;
import com.averolauncher.update.AppVersion;
import com.averolauncher.update.VersionComparator;
import com.averolauncher.util.PathResolver;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The incremental updater: fetch + verify the signed manifest, work out which files of the installed
 * application image differ from the target release, download only those, verify them, and stage them for
 * the external helper ({@code apply-update.ps1}) which swaps them in after the launcher exits.
 *
 * <p>Nothing in the install directory is touched here. Everything lands in
 * {@code <dataDir>/updater/staging/<version>/} first.
 *
 * <p>How "only what changed" works for any starting version (including users who skipped releases): the
 * manifest lists every file of the target release with its hash, so the client simply hashes its own files
 * and downloads whatever differs or is missing. Each file's compressed bytes sit at a signed offset in the
 * release's single pack, so they are fetched with HTTP Range requests (nearby files are merged into one
 * request). If the server ignores ranges, or most of the pack is needed anyway, the whole pack is streamed
 * and only the needed files are extracted. The same path repairs damaged installs.
 */
public final class IncrementalUpdater {

    private static final String TAG = "IncrementalUpdater";
    private static final long GAP_MERGE_BYTES = 64 * 1024;
    private static final int MAX_RANGES = 60;
    private static final double FULL_PACK_RATIO = 0.75;
    private static final long DISK_MARGIN = 64L * 1024 * 1024;
    private static final int MAX_ATTEMPTS = 3;

    public enum State { CHECKING, UPDATE_AVAILABLE, DOWNLOADING, VERIFYING, INSTALLING, RESTARTING, COMPLETE }

    public interface Listener {
        void onState(State state, String message);
        void onProgress(long done, long total);
    }

    public static final class UpdateException extends Exception {
        public UpdateException(String m) { super(m); }
        public UpdateException(String m, Throwable cause) { super(m, cause); }
    }

    /** Result of {@link #prepare}: what the update would do. Nothing has been downloaded yet. */
    public static final class Prepared {
        public final UpdateManifest manifest;
        final byte[] manifestBytes;
        final byte[] signatureBytes;
        public final List<UpdateManifest.FileEntry> needed;
        public final List<String> deletions;
        /** Bytes that will actually cross the network (compressed, including merged gaps). */
        public final long downloadBytes;
        /** Bytes the needed files occupy once unpacked. */
        public final long neededBytes;
        public final boolean upToDate;
        final String signedBy;

        Prepared(UpdateManifest m, byte[] mb, byte[] sb, List<UpdateManifest.FileEntry> needed, List<String> deletions,
                 long downloadBytes, long neededBytes, boolean upToDate, String signedBy) {
            this.manifest = m; this.manifestBytes = mb; this.signatureBytes = sb; this.needed = needed;
            this.deletions = deletions; this.downloadBytes = downloadBytes; this.neededBytes = neededBytes;
            this.upToDate = upToDate; this.signedBy = signedBy;
        }
    }

    private static final class Range {
        final long start;      // inclusive
        long end;              // exclusive
        final List<UpdateManifest.FileEntry> entries = new ArrayList<>();   // every pack entry inside, in order
        Range(long start, long end) { this.start = start; this.end = end; }
    }

    private final Path installDir;
    private final Path dataDir;
    private final String currentVersion;
    private final Map<String, PublicKey> trusted;
    private final SecureHttp http;
    private final boolean persistKeyRotation;
    private long retryBackoffMillis = 1000;

    /** Production constructor. */
    public IncrementalUpdater(Path installDir, URI manifestOrigin) {
        this(installDir, PathResolver.launcherDataDir(), AppVersion.CURRENT, TrustedKeys.load(),
                new SecureHttp(manifestOrigin, false), true);
    }

    /** Full-control constructor (tests inject keys, directories and a loopback-capable HTTP client). */
    IncrementalUpdater(Path installDir, Path dataDir, String currentVersion, Map<String, PublicKey> trusted,
                       SecureHttp http, boolean persistKeyRotation) {
        this.installDir = installDir.toAbsolutePath().normalize();
        this.dataDir = dataDir.toAbsolutePath().normalize();
        this.currentVersion = currentVersion;
        this.trusted = trusted;
        this.http = http;
        this.persistKeyRotation = persistKeyRotation;
    }

    void setRetryBackoffMillis(long ms) { this.retryBackoffMillis = ms; }

    // ------------------------------------------------------------------ availability

    /** The folder holding the running jpackage launcher exe, or null when run from an IDE / `gradlew run`. */
    public static Path detectInstallDir() {
        String cmd = ProcessHandle.current().info().command().orElse(null);
        if (cmd == null) return null;
        Path p = Path.of(cmd);
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!n.endsWith(".exe") || n.equals("java.exe") || n.equals("javaw.exe")) return null;
        return p.getParent();
    }

    /** True when this launcher can update itself incrementally here (Windows, real install, signing key present). */
    public static boolean isAvailableHere() {
        if (!PathResolver.isWindows()) return false;
        Path dir = detectInstallDir();
        if (dir == null || !isUnderUserProfile(dir) || !isWritable(dir)) return false;
        return TrustedKeys.isConfigured();
    }

    private static boolean isUnderUserProfile(Path dir) {
        String profile = System.getenv("USERPROFILE");
        if (profile == null || profile.isBlank()) profile = System.getProperty("user.home");
        return dir.toAbsolutePath().normalize().startsWith(Path.of(profile).toAbsolutePath().normalize());
    }

    private static boolean isWritable(Path dir) {
        try {
            Path probe = dir.resolve(".avero-write-test-" + System.nanoTime());
            Files.createFile(probe);
            Files.delete(probe);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ paths

    private Path updaterDir() { return dataDir.resolve("updater"); }
    Path stagingDir(String version) { return updaterDir().resolve("staging").resolve(version); }
    Path stagedFile(String version, String rel) { return stagingDir(version).resolve("files").resolve(rel); }
    private Path installedManifest() { return updaterDir().resolve("installed-manifest.json"); }
    private Path installedManifestSig() { return updaterDir().resolve("installed-manifest.json.sig"); }
    public static Path healthFile(Path dataDir) { return dataDir.resolve("updater").resolve("health").resolve("startup-ok.json"); }

    // ------------------------------------------------------------------ 1. prepare

    /**
     * Fetches and verifies the manifest, compares it with the installed files and works out the smallest
     * download. Blocking; call from a background thread. Downloads no application files.
     */
    public Prepared prepare(URI manifestUrl, URI signatureUrl, String expectedVersion, CancelToken token,
                            Listener listener) throws UpdateException, CancelToken.CancelledException {
        say(listener, State.CHECKING, "Checking for updates\u2026");
        if (trusted.isEmpty()) throw new UpdateException("Incremental updates are not set up (no trusted signing key)");
        try {
            byte[] manifestBytes = http.getBytes(manifestUrl, 8L * 1024 * 1024, token);
            byte[] sigBytes = http.getBytes(signatureUrl, 4096, token);
            String signedBy;
            try {
                signedBy = SignatureVerifier.verifyAgainstAny(manifestBytes, sigBytes, trusted);
            } catch (SecurityException se) {
                throw new UpdateException("The update's signature could not be verified. It was not installed.", se);
            }
            UpdateManifest m;
            try {
                m = UpdateManifest.parse(manifestBytes);          // only after the signature checked out
            } catch (UpdateManifest.ManifestException me) {
                throw new UpdateException("The update manifest is invalid: " + me.getMessage(), me);
            }
            if (expectedVersion != null && !expectedVersion.equals(m.version)) {
                throw new UpdateException("The manifest is for version " + m.version + ", expected " + expectedVersion);
            }
            boolean sameVersion = m.version.equals(currentVersion);
            if (!sameVersion && !VersionComparator.isNewer(currentVersion, m.version)) {
                throw new UpdateException("Refusing to install " + m.version + " over " + currentVersion + " (not newer)");
            }
            if (!Files.isRegularFile(installDir.resolve(m.launcher))) {
                throw new UpdateException("This install folder doesn't match the update (launcher executable not found)");
            }

            List<UpdateManifest.FileEntry> needed = new ArrayList<>();
            for (UpdateManifest.FileEntry f : m.files) {
                token.check();
                if (!matchesLocal(f)) needed.add(f);
            }
            needed.sort(Comparator.comparingLong(f -> f.offset));
            List<String> deletions = computeDeletions(m);

            boolean upToDate = sameVersion && needed.isEmpty();
            long neededBytes = needed.stream().mapToLong(f -> f.size).sum();
            long download = planRanges(m, needed).stream().mapToLong(r -> r.end - r.start).sum();
            AppLogger.get().info(TAG, "Plan: " + currentVersion + " -> " + m.version + ", " + needed.size() + "/" + m.files.size()
                    + " files differ, download ~" + download + " bytes, " + deletions.size() + " deletions, signedBy=" + signedBy);
            if (persistKeyRotation) TrustedKeys.saveRotation(m);
            say(listener, State.UPDATE_AVAILABLE, "Version " + m.version + " is ready (" + needed.size() + " files)");
            return new Prepared(m, manifestBytes, sigBytes, needed, deletions, download, neededBytes, upToDate, signedBy);
        } catch (CancelToken.CancelledException ce) {
            throw ce;
        } catch (IOException e) {
            throw new UpdateException("Could not reach the update server: " + e.getMessage(), e);
        }
    }

    private boolean matchesLocal(UpdateManifest.FileEntry f) {
        Path p = installDir.resolve(f.path).normalize();
        if (!p.startsWith(installDir)) return false;
        try {
            if (!Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS) || Files.size(p) != f.size) return false;
            return Hashes.sha256Hex(p).equals(f.sha256);
        } catch (IOException e) {
            return false;
        }
    }

    /** Files to remove: those the previous installed manifest owned (or the new one lists as deleted) minus anything still shipped. */
    private List<String> computeDeletions(UpdateManifest m) {
        Set<String> keep = new HashSet<>();
        for (UpdateManifest.FileEntry f : m.files) keep.add(f.path.toLowerCase(Locale.ROOT));
        Set<String> candidates = new java.util.LinkedHashSet<>();
        UpdateManifest prev = loadInstalledManifest();
        if (prev != null) for (UpdateManifest.FileEntry f : prev.files) candidates.add(f.path);
        if (m.deleted != null) candidates.addAll(m.deleted);
        List<String> out = new ArrayList<>();
        for (String c : candidates) {
            if (keep.contains(c.toLowerCase(Locale.ROOT)) || c.equalsIgnoreCase(m.launcher)) continue;
            Path p = installDir.resolve(c).normalize();
            if (p.startsWith(installDir) && Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) out.add(c);
        }
        return out;
    }

    private UpdateManifest loadInstalledManifest() {
        try {
            if (!Files.isRegularFile(installedManifest()) || !Files.isRegularFile(installedManifestSig())) return null;
            byte[] mb = Files.readAllBytes(installedManifest());
            SignatureVerifier.verifyAgainstAny(mb, Files.readAllBytes(installedManifestSig()), trusted);
            return UpdateManifest.parse(mb);
        } catch (Exception e) {
            return null;     // unreadable/unsigned record: be conservative and delete nothing based on it
        }
    }

    /** Groups the needed files' blobs into as few Range requests as sensible (or one whole-pack request). */
    private List<Range> planRanges(UpdateManifest m, List<UpdateManifest.FileEntry> needed) {
        List<Range> ranges = new ArrayList<>();
        if (needed.isEmpty()) return ranges;
        List<UpdateManifest.FileEntry> all = new ArrayList<>(m.files);
        all.sort(Comparator.comparingLong(f -> f.offset));
        Set<String> neededPaths = new HashSet<>();
        for (UpdateManifest.FileEntry f : needed) neededPaths.add(f.path);

        Range cur = null;
        for (UpdateManifest.FileEntry f : needed) {
            long start = f.offset, end = f.offset + f.packedSize;
            if (cur != null && start - cur.end <= GAP_MERGE_BYTES) {
                cur.end = end;
            } else {
                cur = new Range(start, end);
                ranges.add(cur);
            }
        }
        for (Range r : ranges) {
            for (UpdateManifest.FileEntry f : all) {
                if (f.offset >= r.start && f.offset + f.packedSize <= r.end) r.entries.add(f);
            }
        }
        long total = ranges.stream().mapToLong(r -> r.end - r.start).sum();
        if (ranges.size() > MAX_RANGES || total >= FULL_PACK_RATIO * m.pack.size) return List.of(wholePack(m, all));
        return ranges;
    }

    private static Range wholePack(UpdateManifest m, List<UpdateManifest.FileEntry> all) {
        Range r = new Range(0, m.pack.size);
        r.entries.addAll(all);
        return r;
    }

    // ------------------------------------------------------------------ 2. stage

    /**
     * Downloads and verifies the needed files into the staging folder and writes the plan for the helper.
     * Resumable: files already staged and valid are kept. Cancelling deletes the staging folder; any other
     * failure keeps verified files so a retry only fetches the rest.
     *
     * @return the plan file to hand to the helper
     */
    public Path stage(Prepared p, CancelToken token, Listener listener) throws UpdateException, CancelToken.CancelledException {
        UpdateManifest m = p.manifest;
        Path staging = stagingDir(m.version);
        try (FileLock lock = acquireLock()) {
            if (lock == null) throw new UpdateException("Another update is already in progress");
            pruneOldStaging(m.version);
            checkDiskSpace(p);
            Files.createDirectories(staging.resolve("files"));

            say(listener, State.DOWNLOADING, "Downloading " + formatBytes(p.downloadBytes) + "\u2026");
            List<UpdateManifest.FileEntry> pending = new ArrayList<>();
            for (UpdateManifest.FileEntry f : p.needed) if (!stagedOk(m.version, f)) pending.add(f);
            long[] done = {0};
            long total = Math.max(1, p.downloadBytes);
            downloadPending(m, pending, token, bytes -> {
                done[0] += bytes;
                if (listener != null) listener.onProgress(Math.min(done[0], total), total);
            });

            say(listener, State.VERIFYING, "Verifying files\u2026");
            for (UpdateManifest.FileEntry f : p.needed) {
                token.check();
                if (!stagedOk(m.version, f)) throw new UpdateException("Verification failed for " + f.path);
            }

            say(listener, State.INSTALLING, "Preparing installation\u2026");
            Files.write(staging.resolve("installed-manifest.json"), p.manifestBytes);
            Files.write(staging.resolve("installed-manifest.json.sig"), p.signatureBytes);
            Path plan = writePlan(p);
            AppLogger.get().info(TAG, "Staged " + p.needed.size() + " files for " + m.version);
            return plan;
        } catch (CancelToken.CancelledException ce) {
            deleteRecursively(staging);       // cancelled: leave nothing behind, install untouched
            throw ce;
        } catch (IOException e) {
            throw new UpdateException(e.getMessage() == null ? "Update failed" : e.getMessage(), e);
        }
    }

    private void downloadPending(UpdateManifest m, List<UpdateManifest.FileEntry> pending, CancelToken token,
                                 java.util.function.LongConsumer onBytes) throws IOException, UpdateException {
        if (pending.isEmpty()) return;
        URI packUri = packUri(m);
        boolean rangesOk = true;
        for (int round = 0; round < 2; round++) {
            List<Range> ranges = rangesOk ? planRanges(m, pending) : List.of(wholePack(m, sortedAll(m)));
            boolean restart = false;
            for (Range r : ranges) {
                boolean ok = runWithRetry(m, packUri, r, pending, token, onBytes);
                if (!ok) { rangesOk = false; restart = true; break; }       // server ignored Range: stream the whole pack
            }
            if (!restart) return;
            pending = pending.stream().filter(f -> !stagedOk(m.version, f)).toList();
            if (pending.isEmpty()) return;
        }
        throw new UpdateException("Could not download the update");
    }

    private List<UpdateManifest.FileEntry> sortedAll(UpdateManifest m) {
        List<UpdateManifest.FileEntry> all = new ArrayList<>(m.files);
        all.sort(Comparator.comparingLong(f -> f.offset));
        return all;
    }

    /** @return false when the server ignored the Range header (caller switches to a whole-pack stream). */
    private boolean runWithRetry(UpdateManifest m, URI packUri, Range r, List<UpdateManifest.FileEntry> pending,
                                 CancelToken token, java.util.function.LongConsumer onBytes) throws IOException, UpdateException {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            token.check();
            try {
                return runRange(m, packUri, r, pending, token, onBytes);
            } catch (CancelToken.CancelledException ce) {
                throw ce;
            } catch (IOException e) {
                last = e;
                if (token.isCancelled()) throw new CancelToken.CancelledException();
                AppLogger.get().warn(TAG, "Download attempt " + attempt + " failed: " + e.getMessage());
                try { Thread.sleep(retryBackoffMillis * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
            }
        }
        throw new UpdateException("Download failed: " + (last == null ? "unknown error" : last.getMessage()), last);
    }

    private boolean runRange(UpdateManifest m, URI packUri, Range r, List<UpdateManifest.FileEntry> pending,
                             CancelToken token, java.util.function.LongConsumer onBytes) throws IOException {
        Set<String> want = new HashSet<>();
        for (UpdateManifest.FileEntry f : pending) if (!stagedOk(m.version, f)) want.add(f.path);
        if (want.isEmpty()) return true;
        boolean whole = r.start == 0 && r.end == m.pack.size;

        try (SecureHttp.Response resp = http.open(packUri, r.start, r.end - 1, token)) {
            long offset;
            if (resp.status == 206) {
                offset = r.start;
            } else if (resp.status == 200) {
                if (!whole) return false;                       // range ignored -> caller streams the whole pack
                offset = 0;
            } else {
                throw new IOException("HTTP " + resp.status + " downloading the update");
            }
            DigestInputStream digest = whole ? new DigestInputStream(resp.body, Hashes.sha256()) : null;
            InputStream in = digest != null ? digest : resp.body;
            for (UpdateManifest.FileEntry e : r.entries) {
                token.check();
                if (e.offset < offset) continue;
                if (e.offset > offset) { PackExtractor.skipFully(in, e.offset - offset, token, onBytes); offset = e.offset; }
                if (want.contains(e.path)) {
                    PackExtractor.extract(in, e, stagedFile(m.version, e.path), token, onBytes);
                } else {
                    PackExtractor.skipFully(in, e.packedSize, token, onBytes);
                }
                offset += e.packedSize;
            }
            if (whole && offset == m.pack.size) {
                // Whole pack streamed: its own hash must match the signed one too.
                if (in.read() != -1) throw new PackExtractor.IntegrityException("Pack is longer than the manifest says");
                if (!Hashes.hex(digest.getMessageDigest().digest()).equals(m.pack.sha256)) {
                    throw new PackExtractor.IntegrityException("Downloaded pack does not match its signed hash");
                }
            }
            return true;
        } finally {
            token.detach();
        }
    }

    private URI packUri(UpdateManifest m) {
        // The pack always lives next to the manifest in the same release; the manifest only names the file.
        return http.siblingOfManifest(m.pack.name);
    }

    private boolean stagedOk(String version, UpdateManifest.FileEntry f) {
        Path p = stagedFile(version, f.path);
        try {
            return Files.isRegularFile(p) && Files.size(p) == f.size && Hashes.sha256Hex(p).equals(f.sha256);
        } catch (IOException e) {
            return false;
        }
    }

    private void checkDiskSpace(Prepared p) throws UpdateException, IOException {
        long need = p.neededBytes;
        Files.createDirectories(updaterDir());
        long freeData = Files.getFileStore(updaterDir()).getUsableSpace();
        long freeInstall = Files.getFileStore(installDir).getUsableSpace();
        if (freeData < need + DISK_MARGIN || freeInstall < 2 * need + DISK_MARGIN) {
            throw new UpdateException("Not enough free disk space for this update (needs about "
                    + formatBytes(2 * need + DISK_MARGIN) + ")");
        }
    }

    private Path writePlan(Prepared p) throws IOException {
        UpdateManifest m = p.manifest;
        Path staging = stagingDir(m.version);
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("protocol", 1);
        plan.put("version", m.version);
        plan.put("previousVersion", currentVersion);
        plan.put("pid", ProcessHandle.current().pid());
        plan.put("installDir", installDir.toString());
        plan.put("launcher", m.launcher);
        plan.put("stagingDir", staging.resolve("files").toString());
        plan.put("backupDir", updaterDir().resolve("backup").resolve(m.version).toString());
        plan.put("updaterDir", updaterDir().toString());
        plan.put("recordFiles", List.of(staging.resolve("installed-manifest.json").toString(),
                staging.resolve("installed-manifest.json.sig").toString()));
        plan.put("healthFile", healthFile(dataDir).toString());
        plan.put("logFile", updaterDir().resolve("logs").resolve("update-" + m.version + ".log").toString());
        List<Map<String, Object>> files = new ArrayList<>();
        for (UpdateManifest.FileEntry f : p.needed) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("path", f.path); e.put("sha256", f.sha256); e.put("size", f.size);
            files.add(e);
        }
        plan.put("files", files);
        plan.put("delete", p.deletions);
        Path file = staging.resolve("plan.json");
        Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(plan), StandardCharsets.UTF_8);
        return file;
    }

    // ------------------------------------------------------------------ helpers

    private FileLock acquireLock() throws IOException {
        Files.createDirectories(updaterDir());
        FileChannel ch = FileChannel.open(updaterDir().resolve("update.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock l = ch.tryLock();
            if (l == null) ch.close();
            return l;
        } catch (java.nio.channels.OverlappingFileLockException e) {
            ch.close();
            return null;
        }
    }

    private void pruneOldStaging(String keepVersion) {
        Path root = updaterDir().resolve("staging");
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> s = Files.list(root)) {
            for (Path d : s.toList()) if (!d.getFileName().toString().equals(keepVersion)) deleteRecursively(d);
        } catch (IOException ignored) { }
    }

    static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } });
        } catch (IOException ignored) { }
    }

    private static void say(Listener l, State s, String msg) {
        if (l != null) l.onState(s, msg);
    }

    public static String formatBytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024L * 1024) return String.format(Locale.ROOT, "%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", b / (1024.0 * 1024));
        return String.format(Locale.ROOT, "%.2f GB", b / (1024.0 * 1024 * 1024));
    }
}
