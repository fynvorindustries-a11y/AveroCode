package com.averolauncher.rank;

import com.averolauncher.logging.AppLogger;
import com.averolauncher.util.PathResolver;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Per-account progression file: {@code <data dir>/rank/<userId>.json}, written atomically with a
 * {@code .bak} of the previous version and an HMAC so casual hand-edits are detected. This is a speed
 * bump, not a vault: the Avero server is the authority for AP, and a rejected local file is simply
 * re-hydrated from it.
 */
public final class RankStore {

    private static final String TAG = "RankStore";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** {@code state} is never null; {@code tampered} is true when a file existed but failed its integrity check. */
    public record Loaded(RankState state, boolean tampered) { }

    private final Path dir;
    private byte[] key;

    public RankStore() { this(PathResolver.launcherDataDir().resolve("rank")); }

    public RankStore(Path dir) { this.dir = dir; }

    public synchronized Loaded load(String userId) {
        Path main = file(userId);
        boolean sawFile = false;
        for (Path p : new Path[]{main, Path.of(main + ".bak")}) {
            if (!Files.isRegularFile(p)) continue;
            sawFile = true;
            try {
                RankState s = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), RankState.class);
                if (s != null && userId.equals(s.userId) && verify(s)) {
                    if (s.progress == null) s.progress = new java.util.HashMap<>();
                    if (s.completed == null) s.completed = new java.util.ArrayList<>();
                    if (s.ledger == null) s.ledger = new java.util.ArrayList<>();
                    if (s.outbox == null) s.outbox = new java.util.ArrayList<>();
                    return new Loaded(s, false);
                }
            } catch (Exception e) {
                AppLogger.get().warn(TAG, "Could not read " + p.getFileName() + ": " + e.getMessage());
            }
        }
        if (sawFile) AppLogger.get().warn(TAG, "Local rank data failed its integrity check; it will be restored from the Avero server.");
        return new Loaded(RankState.fresh(userId), sawFile);
    }

    public synchronized void save(RankState s) {
        try {
            Files.createDirectories(dir);
            s.updatedAt = System.currentTimeMillis();
            s.sig = null;
            s.sig = sign(GSON.toJson(s));
            Path main = file(s.userId);
            Path tmp = Path.of(main + ".tmp");
            Files.writeString(tmp, GSON.toJson(s), StandardCharsets.UTF_8);
            if (Files.exists(main)) Files.copy(main, Path.of(main + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(tmp, main, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, main, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            AppLogger.get().warn(TAG, "Could not save rank data: " + e.getMessage());
        }
    }

    private Path file(String userId) {
        // User ids come from the server; hash them so the file name is always safe.
        return dir.resolve(HexFormat.of().formatHex(sha(userId)).substring(0, 32) + ".json");
    }

    private static byte[] sha(String s) {
        try { return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private boolean verify(RankState s) {
        String claimed = s.sig;
        if (claimed == null) return false;
        s.sig = null;
        String expect = sign(GSON.toJson(s));
        s.sig = claimed;
        return MessageDigest.isEqual(expect.getBytes(StandardCharsets.UTF_8), claimed.getBytes(StandardCharsets.UTF_8));
    }

    private String sign(String json) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key(), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(json.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private byte[] key() throws Exception {
        if (key != null) return key;
        Files.createDirectories(dir);
        Path kf = dir.resolve(".key");
        if (Files.isRegularFile(kf) && Files.size(kf) == 32) {
            key = Files.readAllBytes(kf);
        } else {
            key = new byte[32];
            new SecureRandom().nextBytes(key);
            Files.write(kf, key);
        }
        return key;
    }
}
