package com.averolauncher.update.incremental.tool;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.DigestOutputStream;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Builds the update assets for one release from the jpackage application image. JDK-only on purpose, so CI can
 * run it as {@code java ReleaseGenerator.java ...} with no dependencies:
 *
 * <pre>
 *   --image DIR            jpackage app-image root (the folder that contains the launcher .exe)
 *   --version X.Y.Z        release version (must equal the Gradle project version)
 *   --launcher PATH        launcher exe relative to --image (e.g. AveroLauncher.exe)
 *   --out DIR              where payload.pack, update-manifest.json(.sig) and release-report.txt go
 *   --key-file FILE | --key-env NAME   base64 PKCS#8 Ed25519 private key
 *   --key-id ID            id of that key (informational, goes in the manifest)
 *   [--previous-manifest FILE]   previous release's update-manifest.json, to report changes and list deletions
 *   [--notes FILE]  [--release-id ID]  [--app NAME]
 *   [--rotation-key id=BASE64_X509 ...]  announce a new public key (key rotation)
 * </pre>
 * Every file of the image is compared by SHA-256 with the previous manifest; nothing relies on Git history.
 */
public final class ReleaseGenerator {

    private static final Pattern HEX64 = Pattern.compile("^[0-9a-f]{64}$");

    private ReleaseGenerator() {}

    private record Entry(String path, long size, String sha256, long offset, long packedSize, int method) {}

    public static void main(String[] argv) throws Exception {
        Map<String, List<String>> a = parseArgs(argv);
        Path image = Path.of(req(a, "image"));
        String version = req(a, "version");
        String launcher = req(a, "launcher");
        Path out = Path.of(req(a, "out"));
        String keyId = req(a, "key-id");
        String app = a.containsKey("app") ? a.get("app").get(0) : "AveroLauncher";
        if (!version.matches("\\d{1,6}\\.\\d{1,6}\\.\\d{1,6}")) throw new IllegalArgumentException("version must be MAJOR.MINOR.PATCH (no 'v')");
        if (!Files.isRegularFile(image.resolve(launcher))) throw new IllegalArgumentException("launcher not found in image: " + launcher);

        PrivateKey key = loadKey(a);
        Files.createDirectories(out);
        Path packFile = out.resolve("payload.pack");

        // Stable order, runtime first: the frequently changing app/ + launcher files end up in one contiguous tail,
        // so a typical update is a single small Range request.
        List<String> paths = new ArrayList<>();
        try (Stream<Path> s = Files.walk(image)) {
            for (Path p : (Iterable<Path>) s.filter(f -> Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS))::iterator) {
                paths.add(image.relativize(p).toString().replace('\\', '/'));
            }
        }
        paths.sort(Comparator.<String>comparingInt(p -> p.startsWith("runtime/") ? 0 : 1).thenComparing(Comparator.naturalOrder()));

        List<Entry> entries = new ArrayList<>();
        MessageDigest packDigest = MessageDigest.getInstance("SHA-256");
        long offset = 0;
        try (OutputStream raw = new BufferedOutputStream(Files.newOutputStream(packFile));
             DigestOutputStream pack = new DigestOutputStream(raw, packDigest)) {
            for (String rel : paths) {
                Path f = image.resolve(rel);
                byte[] data = Files.readAllBytes(f);
                String sha = hex(MessageDigest.getInstance("SHA-256").digest(data));
                byte[] packed = deflate(data);
                int method = 1;
                if (packed.length >= data.length * 0.98 || data.length == 0) { packed = data; method = 0; }
                pack.write(packed);
                entries.add(new Entry(rel, data.length, sha, offset, packed.length, method));
                offset += packed.length;
            }
        }
        String packSha = hex(packDigest.digest());

        // Compare with the previous release (by content hash, not by Git).
        Map<String, String> prev = new LinkedHashMap<>();
        if (a.containsKey("previous-manifest")) prev = readPrevious(Path.of(a.get("previous-manifest").get(0)));
        List<String> deleted = new ArrayList<>();
        Set<String> now = new HashSet<>();
        for (Entry e : entries) now.add(e.path);
        for (String p : prev.keySet()) if (!now.contains(p)) deleted.add(p);
        // Carry earlier removals forward so users who skipped releases (and have no install record) still get them
        // cleaned up; anything that is shipped again in this release is dropped from the list.
        if (a.containsKey("previous-manifest")) {
            for (String p : readPreviousDeleted(Path.of(a.get("previous-manifest").get(0)))) {
                if (!now.contains(p) && !deleted.contains(p)) deleted.add(p);
            }
        }
        Collections.sort(deleted);
        if (deleted.size() > 2000) deleted = new ArrayList<>(deleted.subList(deleted.size() - 2000, deleted.size()));

        String notes = a.containsKey("notes") ? Files.readString(Path.of(a.get("notes").get(0)), StandardCharsets.UTF_8) : "";
        String releaseId = a.containsKey("release-id") ? a.get("release-id").get(0) : version;

        StringBuilder j = new StringBuilder();
        j.append("{\n");
        j.append("  \"schema\": 1,\n  \"app\": ").append(q(app)).append(",\n  \"version\": ").append(q(version)).append(",\n");
        j.append("  \"releaseId\": ").append(q(releaseId)).append(",\n  \"minUpdaterVersion\": 1,\n");
        j.append("  \"keyId\": ").append(q(keyId)).append(",\n  \"launcher\": ").append(q(launcher)).append(",\n");
        j.append("  \"pack\": {\"name\": \"payload.pack\", \"size\": ").append(offset).append(", \"sha256\": ").append(q(packSha)).append("},\n");
        j.append("  \"notes\": ").append(q(notes)).append(",\n");
        if (a.containsKey("rotation-key")) {
            j.append("  \"rotation\": {\"newKeys\": [");
            List<String> ks = a.get("rotation-key");
            for (int i = 0; i < ks.size(); i++) {
                String[] kv = ks.get(i).split("=", 2);
                if (i > 0) j.append(", ");
                j.append("{\"id\": ").append(q(kv[0])).append(", \"publicKey\": ").append(q(kv[1])).append("}");
            }
            j.append("]},\n");
        }
        j.append("  \"deleted\": [");
        for (int i = 0; i < deleted.size(); i++) j.append(i > 0 ? ", " : "").append(q(deleted.get(i)));
        j.append("],\n  \"files\": [\n");
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            j.append("    {\"path\": ").append(q(e.path)).append(", \"size\": ").append(e.size).append(", \"sha256\": ").append(q(e.sha256))
             .append(", \"offset\": ").append(e.offset).append(", \"packedSize\": ").append(e.packedSize)
             .append(", \"method\": ").append(e.method).append("}").append(i + 1 < entries.size() ? ",\n" : "\n");
        }
        j.append("  ]\n}\n");
        byte[] manifest = j.toString().getBytes(StandardCharsets.UTF_8);
        Files.write(out.resolve("update-manifest.json"), manifest);

        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(key);
        sig.update(manifest);
        Files.writeString(out.resolve("update-manifest.json.sig"), Base64.getEncoder().encodeToString(sig.sign()) + "\n", StandardCharsets.US_ASCII);

        writeReport(out.resolve("release-report.txt"), version, entries, prev, deleted, offset);
        System.out.println(Files.readString(out.resolve("release-report.txt")));
    }

    // ---------------------------------------------------------------- helpers

    private static void writeReport(Path file, String version, List<Entry> entries, Map<String, String> prev,
                                    List<String> deleted, long packSize) throws IOException {
        int added = 0, modified = 0, same = 0;
        long changedPacked = 0, changedRaw = 0;
        for (Entry e : entries) {
            String old = prev.get(e.path);
            if (old == null) { added++; changedPacked += e.packedSize; changedRaw += e.size; }
            else if (!old.equals(e.sha256)) { modified++; changedPacked += e.packedSize; changedRaw += e.size; }
            else same++;
        }
        StringBuilder r = new StringBuilder();
        r.append("Release ").append(version).append(" — ").append(entries.size()).append(" files, pack ").append(packSize).append(" bytes\n");
        if (prev.isEmpty()) {
            r.append("No previous manifest supplied: change comparison skipped.\n");
        } else {
            r.append("vs previous release: ").append(added).append(" new, ").append(modified).append(" modified, ")
             .append(same).append(" unchanged, ").append(deleted.size()).append(" deleted\n");
            r.append("An up-to-date previous version downloads about ").append(changedPacked).append(" bytes (").append(changedRaw).append(" unpacked).\n");
            for (String d : deleted) r.append("  deleted: ").append(d).append('\n');
        }
        Files.writeString(file, r.toString(), StandardCharsets.UTF_8);
    }

    /** Tiny extractor for the previous manifest's path/sha256 pairs (the generator stays dependency-free). */
    private static Map<String, String> readPrevious(Path f) throws IOException {
        String s = Files.readString(f, StandardCharsets.UTF_8);
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = Pattern.compile("\\{\\s*\"path\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*,\\s*\"size\"\\s*:\\s*\\d+\\s*,\\s*\"sha256\"\\s*:\\s*\"([0-9a-f]{64})\"").matcher(s);
        while (m.find()) out.put(unescape(m.group(1)), m.group(2));
        return out;
    }

    private static List<String> readPreviousDeleted(Path f) throws IOException {
        String s = Files.readString(f, StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>();
        Matcher arr = Pattern.compile("\"deleted\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL).matcher(s);
        if (arr.find()) {
            Matcher item = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(arr.group(1));
            while (item.find()) out.add(unescape(item.group(1)));
        }
        return out;
    }

    private static String unescape(String s) { return s.replace("\\\"", "\"").replace("\\\\", "\\"); }

    private static byte[] deflate(byte[] data) throws IOException {
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION, true);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (DeflaterOutputStream out = new DeflaterOutputStream(bo, d)) { out.write(data); }
            return bo.toByteArray();
        } finally {
            d.end();
        }
    }

    private static PrivateKey loadKey(Map<String, List<String>> a) throws Exception {
        String b64;
        if (a.containsKey("key-file")) b64 = Files.readString(Path.of(a.get("key-file").get(0))).trim();
        else if (a.containsKey("key-env")) {
            b64 = System.getenv(a.get("key-env").get(0));
            if (b64 == null || b64.isBlank()) throw new IllegalArgumentException("environment variable " + a.get("key-env").get(0) + " is empty");
            b64 = b64.trim();
        } else throw new IllegalArgumentException("need --key-file or --key-env");
        return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(b64)));
    }

    private static Map<String, List<String>> parseArgs(String[] argv) {
        Map<String, List<String>> m = new HashMap<>();
        for (int i = 0; i < argv.length; i += 2) {
            if (!argv[i].startsWith("--") || i + 1 >= argv.length) throw new IllegalArgumentException("bad arguments near " + argv[i]);
            m.computeIfAbsent(argv[i].substring(2), k -> new ArrayList<>()).add(argv[i + 1]);
        }
        return m;
    }

    private static String req(Map<String, List<String>> a, String k) {
        if (!a.containsKey(k)) throw new IllegalArgumentException("missing --" + k);
        return a.get(k).get(0);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static String q(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        return sb.append('"').toString();
    }
}
