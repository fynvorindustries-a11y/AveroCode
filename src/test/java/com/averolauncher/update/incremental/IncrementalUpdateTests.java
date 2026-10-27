package com.averolauncher.update.incremental;

import com.averolauncher.update.incremental.tool.ReleaseGenerator;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Dependency-free tests for the incremental updater (same style as RankSystemTests). Run from the project root:
 * <pre>  java -cp &lt;classes&gt;:&lt;gson.jar&gt; com.averolauncher.update.incremental.IncrementalUpdateTests</pre>
 * Exits non-zero on any failure. The PowerShell-helper tests need PowerShell 7 and are reported as SKIPPED
 * (never as passed) unless the PWSH environment variable points at it.
 */
public final class IncrementalUpdateTests {

    private static int passed, failed, skipped;
    private static final Random RND = new Random(42);
    private static Path root;
    private static KeyPair keys;
    private static Path keyFile;

    interface Body { void run() throws Exception; }

    static void test(String name, Body b) {
        try { b.run(); passed++; System.out.println("PASS  " + name); }
        catch (Throwable t) { failed++; System.out.println("FAIL  " + name + " -> " + t); t.printStackTrace(System.out); }
    }

    static void check(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); }

    static void expectThrows(Body b, String contains) throws Exception {
        try { b.run(); } catch (Throwable t) {
            String m = String.valueOf(t.getMessage()) + (t.getCause() != null ? " / " + t.getCause().getMessage() : "");
            if (contains == null || m.toLowerCase(Locale.ROOT).contains(contains.toLowerCase(Locale.ROOT))) return;
            throw new AssertionError("wrong error: " + m);
        }
        throw new AssertionError("expected an exception containing '" + contains + "'");
    }

    // ------------------------------------------------------------------ fixtures

    static byte[] random(int n) { byte[] b = new byte[n]; RND.nextBytes(b); return b; }
    static byte[] text(int n, String seed) { StringBuilder sb = new StringBuilder(); while (sb.length() < n) sb.append(seed).append(sb.length()).append('\n'); return sb.substring(0, n).getBytes(StandardCharsets.UTF_8); }

    static Map<String, byte[]> baseImage(String version) {
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put("run.sh", "#!/bin/sh\nDIR=\"$(cd \"$(dirname \"$0\")\" && pwd)\"\nV=\"$(cat \"$DIR/app/version.txt\")\"\nif [ -n \"$AVERO_UPDATE_HEALTH_FILE\" ] && [ \"$V\" != \"BAD\" ]; then mkdir -p \"$(dirname \"$AVERO_UPDATE_HEALTH_FILE\")\"; echo \"{\\\"version\\\":\\\"$V\\\"}\" > \"$AVERO_UPDATE_HEALTH_FILE\"; fi\necho started >> \"$DIR/started.log\"\nsleep 1\n".getBytes(StandardCharsets.UTF_8));
        m.put("runtime/lib/modules", FIXED_MODULES);
        m.put("runtime/bin/java.dll", FIXED_DLL);
        m.put("app/version.txt", version.getBytes(StandardCharsets.UTF_8));
        m.put("app/app.jar", text(120_000, "jar-v" + version + "-"));
        m.put("app/app.cfg", ("version=" + version + "\n").getBytes(StandardCharsets.UTF_8));
        return m;
    }
    static final byte[] FIXED_MODULES = random(300_000), FIXED_DLL = random(60_000), FIXED_EXTRA = random(30_000);

    static Path writeImage(String name, Map<String, byte[]> files) throws IOException {
        Path dir = root.resolve(name);
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            Path f = dir.resolve(e.getKey());
            Files.createDirectories(f.getParent());
            Files.write(f, e.getValue());
        }
        Path run = dir.resolve("run.sh");
        if (Files.exists(run)) run.toFile().setExecutable(true);
        return dir;
    }

    static Map<String, byte[]> v1() { return baseImage("1.0.0"); }
    static Map<String, byte[]> v2() {
        Map<String, byte[]> m = baseImage("2.0.0");
        m.put("app/new.jar", text(50_000, "new-"));
        m.put("old/removed.txt", null);      // placeholder removed below
        m.remove("old/removed.txt");
        return m;
    }
    static Map<String, byte[]> v1WithOld() { Map<String, byte[]> m = v1(); m.put("old/removed.txt", "gone soon".getBytes(StandardCharsets.UTF_8)); return m; }
    static Map<String, byte[]> v3() { Map<String, byte[]> m = baseImage("3.0.0"); m.put("app/new.jar", text(50_000, "new3-")); m.put("app/extra.bin", FIXED_EXTRA); return m; }

    static Path generate(String tag, Path image, String version, Path prevManifest) throws Exception {
        Path out = root.resolve("rel-" + tag);
        List<String> a = new ArrayList<>(List.of("--image", image.toString(), "--version", version, "--launcher", "run.sh",
                "--out", out.toString(), "--key-file", keyFile.toString(), "--key-id", "t1"));
        if (prevManifest != null) { a.add("--previous-manifest"); a.add(prevManifest.toString()); }
        ReleaseGenerator.main(a.toArray(new String[0]));
        return out;
    }

    static Map<String, PublicKey> trust() { return Map.of("t1", keys.getPublic()); }

    static void signManifest(Path dir, byte[] manifest) throws Exception {
        Files.write(dir.resolve("update-manifest.json"), manifest);
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(keys.getPrivate());
        s.update(manifest);
        Files.writeString(dir.resolve("update-manifest.json.sig"), Base64.getEncoder().encodeToString(s.sign()));
    }

    static String sha(byte[] b) { return Hashes.sha256Hex(b); }

    // ------------------------------------------------------------------ test server

    static final class Server implements AutoCloseable {
        final HttpServer srv;
        final Path dir;
        volatile boolean ignoreRange;
        volatile long corruptPackAt = -1;
        final AtomicInteger dropFirst = new AtomicInteger(0);
        final AtomicLong served = new AtomicLong();
        volatile String redirectPackTo;

        Server(Path dir) throws IOException {
            this.dir = dir;
            srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            srv.createContext("/", this::handle);
            srv.start();
        }
        URI uri(String name) { return URI.create("http://127.0.0.1:" + srv.getAddress().getPort() + "/" + name); }
        public void close() { srv.stop(0); }

        void handle(HttpExchange ex) throws IOException {
            String name = ex.getRequestURI().getPath().substring(1);
            Path f = dir.resolve(name);
            if (name.equals("payload.pack") && redirectPackTo != null) {
                ex.getResponseHeaders().add("Location", redirectPackTo);
                ex.sendResponseHeaders(302, -1); ex.close(); return;
            }
            if (!Files.isRegularFile(f)) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
            byte[] all = Files.readAllBytes(f);
            if (name.equals("payload.pack") && corruptPackAt >= 0 && corruptPackAt < all.length) { all = all.clone(); all[(int) corruptPackAt] ^= 0x55; }
            String range = ex.getRequestHeaders().getFirst("Range");
            int from = 0, to = all.length - 1, code = 200;
            if (range != null && !ignoreRange && range.startsWith("bytes=")) {
                String[] p = range.substring(6).split("-");
                from = Integer.parseInt(p[0]); to = Math.min(Integer.parseInt(p[1]), all.length - 1); code = 206;
                ex.getResponseHeaders().add("Content-Range", "bytes " + from + "-" + to + "/" + all.length);
            }
            int len = to - from + 1;
            boolean drop = name.equals("payload.pack") && dropFirst.getAndUpdate(v -> v > 0 ? v - 1 : 0) > 0;
            ex.sendResponseHeaders(code, len);
            try (OutputStream os = ex.getResponseBody()) {
                int n = drop ? len / 2 : len;
                os.write(all, from, n);
                served.addAndGet(n);
                if (drop) { os.flush(); throw new IOException("simulated connection drop"); }
            } catch (IOException ignored) { }
        }
    }

    static IncrementalUpdater updater(Path install, Path data, String current, Server s) {
        IncrementalUpdater u = new IncrementalUpdater(install, data, current, trust(), new SecureHttp(s.uri("update-manifest.json"), true), false);
        u.setRetryBackoffMillis(20);
        return u;
    }

    static IncrementalUpdater.Prepared prep(IncrementalUpdater u, Server s, String ver) throws Exception {
        try (CancelToken t = new CancelToken()) {
            return u.prepare(s.uri("update-manifest.json"), s.uri("update-manifest.json.sig"), ver, t, null);
        }
    }

    static Set<String> paths(List<UpdateManifest.FileEntry> l) { Set<String> s = new TreeSet<>(); for (UpdateManifest.FileEntry e : l) s.add(e.path); return s; }

    static Map<String, String> snapshot(Path dir) throws IOException {
        Map<String, String> m = new TreeMap<>();
        try (Stream<Path> w = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) w.filter(Files::isRegularFile)::iterator) {
                String rel = dir.relativize(p).toString().replace('\\', '/');
                if (rel.equals("started.log")) continue;
                m.put(rel, Hashes.sha256Hex(p));
            }
        }
        return m;
    }

    static Map<String, String> hashesOf(Map<String, byte[]> img) { Map<String, String> m = new TreeMap<>(); img.forEach((k, v) -> m.put(k, sha(v))); return m; }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("avero-update-tests");
        keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        keyFile = root.resolve("test.private");
        Files.writeString(keyFile, Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded()));

        // releases: 1.0.0 (with an extra old file), 2.0.0 (changes + removal), 3.0.0 (skipped-version case)
        Path img1 = writeImage("img1", v1WithOld());
        Path rel1 = generate("1", img1, "1.0.0", null);
        Path img2 = writeImage("img2", v2());
        Path rel2 = generate("2", img2, "2.0.0", rel1.resolve("update-manifest.json"));
        Path img3 = writeImage("img3", v3());
        Path rel3 = generate("3", img3, "3.0.0", rel2.resolve("update-manifest.json"));

        pathTests();
        manifestTests(rel2);
        signatureTests(rel2, img1);
        comparisonTests(rel1, rel2);
        endToEndTests(rel1, rel2, rel3, img1);
        failureTests(rel2, img1);
        securityTests(rel2, img1);
        helperTests(rel1, rel2, img1);

        System.out.println("\n" + passed + " passed, " + failed + " failed, " + skipped + " skipped");
        System.exit(failed == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ groups

    static void pathTests() {
        test("paths: good paths accepted", () -> {
            for (String p : List.of("a.txt", "app/lib/x.jar", "runtime/bin/java.exe", "a b/c.txt"))
                check(UpdateManifest.isSafeRelativePath(p), p);
        });
        test("paths: traversal/absolute/windows-hostile rejected", () -> {
            for (String p : List.of("../x", "a/../x", "/etc/passwd", "C:/x", "C:\\x", "a\\b", "a//b", "a/", "", "./x", "a/./b",
                    "CON", "con.txt", "a/NUL.dat", "COM1", "x.", "x /y", "a|b", "a?b", "a*b", "a\u0001b"))
                check(!UpdateManifest.isSafeRelativePath(p), "should reject: " + p);
        });
    }

    static byte[] mutate(Path rel, java.util.function.Consumer<JsonObject> f) throws IOException {
        JsonObject o = new Gson().fromJson(Files.readString(rel.resolve("update-manifest.json")), JsonObject.class);
        f.accept(o);
        return new Gson().toJson(o).getBytes(StandardCharsets.UTF_8);
    }

    static void manifestTests(Path rel2) {
        byte[] good = readUnchecked(rel2.resolve("update-manifest.json"));
        test("manifest: valid manifest parses", () -> { UpdateManifest m = UpdateManifest.parse(good); check(m.version.equals("2.0.0"), "version"); check(m.files.size() == 7 - 0 || m.files.size() > 3, "files"); });
        test("manifest: unsupported schema rejected", () -> expectThrows(() -> UpdateManifest.parse(mutate(rel2, o -> o.addProperty("schema", 2))), "schema"));
        test("manifest: updater too old rejected", () -> expectThrows(() -> UpdateManifest.parse(mutate(rel2, o -> o.addProperty("minUpdaterVersion", 99))), "newer updater"));
        test("manifest: unknown extra fields tolerated", () -> UpdateManifest.parse(mutate(rel2, o -> o.addProperty("futureField", "x"))));
        test("manifest: not JSON / empty rejected", () -> { expectThrows(() -> UpdateManifest.parse("nope".getBytes()), "json"); expectThrows(() -> UpdateManifest.parse(new byte[0]), null); });
        test("manifest: traversal path rejected", () -> expectThrows(() -> UpdateManifest.parse(mutate(rel2, o -> o.getAsJsonArray("files").get(2).getAsJsonObject().addProperty("path", "../evil.dll"))), "unsafe"));
        test("manifest: duplicate path (case-insensitive) rejected", () -> expectThrows(() -> UpdateManifest.parse(mutate(rel2, o -> {
            JsonArray fs = o.getAsJsonArray("files");
            fs.get(1).getAsJsonObject().addProperty("path", fs.get(2).getAsJsonObject().get("path").getAsString().toUpperCase());
        })), "duplicate"));
        test("manifest: bad hash rejected", () -> expectThrows(() -> UpdateManifest.parse(mutate(rel2, o -> o.getAsJsonArray("files").get(0).getAsJsonObject().addProperty("sha256", "xyz"))), "hash"));
        test("manifest: gap in pack layout rejected", () -> expectThrows(() -> UpdateManifest.parse(mutate(rel2, o -> {
            JsonObject f = o.getAsJsonArray("files").get(1).getAsJsonObject(); f.addProperty("offset", f.get("offset").getAsLong() + 1);
        })), "contiguous"));
        test("manifest: launcher not in file list rejected", () -> expectThrows(() -> UpdateManifest.parse(mutate(rel2, o -> o.addProperty("launcher", "missing.exe"))), "launcher"));
        test("manifest: path both kept and deleted rejected", () -> expectThrows(() -> UpdateManifest.parse(mutate(rel2, o -> {
            JsonArray d = new JsonArray(); d.add(o.getAsJsonArray("files").get(0).getAsJsonObject().get("path").getAsString()); o.add("deleted", d);
        })), "both"));
    }

    static byte[] readUnchecked(Path p) { try { return Files.readAllBytes(p); } catch (IOException e) { throw new RuntimeException(e); } }

    static void signatureTests(Path rel2, Path img1) throws Exception {
        try (Server s = new Server(rel2)) {
            Path inst = copyTree(img1, "inst-sig"), data = root.resolve("data-sig");
            test("signature: valid manifest verifies and prepares", () -> check(prep(updater(inst, data, "1.0.0", s), s, "2.0.0").manifest.version.equals("2.0.0"), "version"));
            test("signature: tampered manifest rejected", () -> {
                Path bad = root.resolve("rel-tampered"); Files.createDirectories(bad);
                Files.copy(rel2.resolve("update-manifest.json.sig"), bad.resolve("update-manifest.json.sig"));
                Files.write(bad.resolve("update-manifest.json"), new String(Files.readAllBytes(rel2.resolve("update-manifest.json")), StandardCharsets.UTF_8).replace("2.0.0", "2.0.1").getBytes(StandardCharsets.UTF_8));
                try (Server s2 = new Server(bad)) { expectThrows(() -> prep(updater(inst, data, "1.0.0", s2), s2, null), "signature"); }
            });
            test("signature: wrong key rejected", () -> {
                KeyPair other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
                IncrementalUpdater u = new IncrementalUpdater(inst, data, "1.0.0", Map.of("x", other.getPublic()), new SecureHttp(s.uri("update-manifest.json"), true), false);
                expectThrows(() -> prep(u, s, null), "signature");
            });
            test("signature: malformed signature rejected", () -> {
                Path bad = root.resolve("rel-badsig"); Files.createDirectories(bad);
                Files.copy(rel2.resolve("update-manifest.json"), bad.resolve("update-manifest.json"));
                Files.writeString(bad.resolve("update-manifest.json.sig"), "not-a-signature!!");
                try (Server s2 = new Server(bad)) { expectThrows(() -> prep(updater(inst, data, "1.0.0", s2), s2, null), "signature"); }
            });
        }
    }

    static void comparisonTests(Path rel1, Path rel2) {
        test("generator: report lists new/modified/unchanged/deleted vs previous release", () -> {
            String rep = Files.readString(rel2.resolve("release-report.txt"));
            check(rep.contains("1 new, 3 modified"), rep);       // new.jar; run.sh? no: version.txt, app.jar, app.cfg
            check(rep.contains("deleted: old/removed.txt"), rep);
            UpdateManifest m = UpdateManifest.parse(Files.readAllBytes(rel2.resolve("update-manifest.json")));
            check(m.deleted.equals(List.of("old/removed.txt")), "deleted list " + m.deleted);
        });
        test("generator: runtime files sit before app files (one contiguous tail for typical updates)", () -> {
            UpdateManifest m = UpdateManifest.parse(Files.readAllBytes(rel2.resolve("update-manifest.json")));
            long runtimeEnd = 0, appStart = Long.MAX_VALUE;
            for (UpdateManifest.FileEntry f : m.files) { if (f.path.startsWith("runtime/")) runtimeEnd = Math.max(runtimeEnd, f.offset + f.packedSize); else appStart = Math.min(appStart, f.offset); }
            check(runtimeEnd <= appStart, "layout");
        });
    }

    static Path copyTree(Path src, String name) throws IOException {
        Path dst = root.resolve(name);
        try (Stream<Path> w = Files.walk(src)) {
            for (Path p : (Iterable<Path>) w::iterator) {
                Path t = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t); else { Files.createDirectories(t.getParent()); Files.copy(p, t); }
            }
        }
        if (Files.exists(dst.resolve("run.sh"))) dst.resolve("run.sh").toFile().setExecutable(true);
        return dst;
    }

    static void endToEndTests(Path rel1, Path rel2, Path rel3, Path img1) throws Exception {
        test("e2e: only changed files are downloaded, unchanged are not (1.0.0 -> 2.0.0)", () -> {
            Path inst = copyTree(img1, "inst-e2e"), data = root.resolve("data-e2e");
            Files.createDirectories(data.resolve("updater"));
            Files.copy(rel1.resolve("update-manifest.json"), data.resolve("updater/installed-manifest.json"));
            Files.copy(rel1.resolve("update-manifest.json.sig"), data.resolve("updater/installed-manifest.json.sig"));
            try (Server s = new Server(rel2); CancelToken t = new CancelToken()) {
                IncrementalUpdater u = updater(inst, data, "1.0.0", s);
                IncrementalUpdater.Prepared p = prep(u, s, "2.0.0");
                check(paths(p.needed).equals(new TreeSet<>(List.of("app/app.cfg", "app/app.jar", "app/new.jar", "app/version.txt"))), "needed " + paths(p.needed));
                check(p.deletions.equals(List.of("old/removed.txt")), "deletions " + p.deletions);
                long pack = Files.size(rel2.resolve("payload.pack"));
                check(p.downloadBytes < pack / 2, "download " + p.downloadBytes + " vs pack " + pack);
                Map<String, String> before = snapshot(inst);
                Path plan = u.stage(p, t, null);
                check(s.served.get() <= p.downloadBytes + 4096L, "served " + s.served.get() + " > estimate " + p.downloadBytes);   // headers excluded
                check(!Files.exists(u.stagedFile("2.0.0", "runtime/lib/modules")), "unchanged runtime file must not be staged");
                for (String r : paths(p.needed)) check(Hashes.sha256Hex(u.stagedFile("2.0.0", r)).equals(hashesOf(v2()).get(r)), "staged content " + r);
                check(before.equals(snapshot(inst)), "install dir must be untouched by staging");
                JsonObject pj = new Gson().fromJson(Files.readString(plan), JsonObject.class);
                check(pj.get("version").getAsString().equals("2.0.0") && pj.getAsJsonArray("files").size() == 4, "plan content");
                check(pj.getAsJsonArray("delete").size() == 1, "plan deletions");
            }
        });
        test("e2e: user who skipped releases (1.0.0 -> 3.0.0) gets the union of changes", () -> {
            Path inst = copyTree(img1, "inst-skip"), data = root.resolve("data-skip");
            try (Server s = new Server(rel3); CancelToken t = new CancelToken()) {
                IncrementalUpdater u = updater(inst, data, "1.0.0", s);
                IncrementalUpdater.Prepared p = prep(u, s, "3.0.0");
                check(paths(p.needed).equals(new TreeSet<>(List.of("app/app.cfg", "app/app.jar", "app/new.jar", "app/version.txt", "app/extra.bin"))), "needed " + paths(p.needed));
                u.stage(p, t, null);
                for (String r : paths(p.needed)) check(Hashes.sha256Hex(u.stagedFile("3.0.0", r)).equals(hashesOf(v3()).get(r)), r);
                check(p.deletions.contains("old/removed.txt"), "manifest.deleted covers files removed in skipped releases? " + p.deletions);
            }
        });
        test("e2e: damaged install is repaired (missing + corrupted file detected)", () -> {
            Path inst = writeImage("inst-repair", v2());
            Files.delete(inst.resolve("app/new.jar"));
            Files.write(inst.resolve("app/app.jar"), "corrupted".getBytes());
            try (Server s = new Server(rel2); CancelToken t = new CancelToken()) {
                IncrementalUpdater u = updater(inst, root.resolve("data-repair"), "2.0.0", s);
                IncrementalUpdater.Prepared p = prep(u, s, "2.0.0");
                check(!p.upToDate && paths(p.needed).equals(new TreeSet<>(List.of("app/app.jar", "app/new.jar"))), "needed " + paths(p.needed));
                u.stage(p, t, null);
            }
        });
        test("e2e: healthy same-version install reports up to date, nothing to download", () -> {
            Path inst = writeImage("inst-ok", v2());
            try (Server s = new Server(rel2)) { IncrementalUpdater.Prepared p = prep(updater(inst, root.resolve("data-ok"), "2.0.0", s), s, "2.0.0"); check(p.upToDate && p.needed.isEmpty() && p.downloadBytes == 0, "up to date"); }
        });
        test("e2e: server ignoring Range falls back to streaming the whole pack and still verifies", () -> {
            Path inst = copyTree(img1, "inst-norange");
            try (Server s = new Server(rel2); CancelToken t = new CancelToken()) {
                s.ignoreRange = true;
                IncrementalUpdater u = updater(inst, root.resolve("data-norange"), "1.0.0", s);
                IncrementalUpdater.Prepared p = prep(u, s, "2.0.0");
                u.stage(p, t, null);
                check(s.served.get() >= Files.size(rel2.resolve("payload.pack")), "whole pack streamed");
                check(Hashes.sha256Hex(u.stagedFile("2.0.0", "app/new.jar")).equals(sha(v2().get("app/new.jar"))), "content");
            }
        });
        test("e2e: interrupted download is retried and completes", () -> {
            Path inst = copyTree(img1, "inst-drop");
            try (Server s = new Server(rel2); CancelToken t = new CancelToken()) {
                s.dropFirst.set(1);
                IncrementalUpdater u = updater(inst, root.resolve("data-drop"), "1.0.0", s);
                u.stage(prep(u, s, "2.0.0"), t, null);
                check(Files.exists(u.stagedFile("2.0.0", "app/app.jar")), "staged after retry");
            }
        });
        test("e2e: resumes — files already staged and valid are not downloaded again", () -> {
            Path inst = copyTree(img1, "inst-resume");
            try (Server s = new Server(rel2); CancelToken t = new CancelToken()) {
                IncrementalUpdater u = updater(inst, root.resolve("data-resume"), "1.0.0", s);
                IncrementalUpdater.Prepared p = prep(u, s, "2.0.0");
                u.stage(p, t, null);
                long first = s.served.get();
                u.stage(p, t, null);
                check(s.served.get() == first, "second stage must download nothing");
            }
        });
    }

    static void failureTests(Path rel2, Path img1) throws Exception {
        test("failure: corrupted download is rejected, nothing bad is staged, install untouched", () -> {
            Path inst = copyTree(img1, "inst-corrupt"), data = root.resolve("data-corrupt");
            try (Server s = new Server(rel2); CancelToken t = new CancelToken()) {
                UpdateManifest m = UpdateManifest.parse(Files.readAllBytes(rel2.resolve("update-manifest.json")));
                UpdateManifest.FileEntry jar = m.files.stream().filter(f -> f.path.equals("app/app.jar")).findFirst().get();
                s.corruptPackAt = jar.offset + jar.packedSize / 2;
                Map<String, String> before = snapshot(inst);
                IncrementalUpdater u = updater(inst, data, "1.0.0", s);
                expectThrows(() -> u.stage(prep(u, s, "2.0.0"), t, null), null);
                check(!Files.exists(u.stagedFile("2.0.0", "app/app.jar")), "corrupt file must not be staged");
                try (Stream<Path> w = Files.exists(data) ? Files.walk(data) : Stream.empty()) { check(w.noneMatch(p -> p.toString().endsWith(".part")), "no .part leftovers"); }
                check(before.equals(snapshot(inst)), "install untouched");
            }
        });
        test("failure: downgrade / replayed old manifest refused", () -> {
            Path inst = copyTree(img1, "inst-down");
            try (Server s = new Server(rel2)) { expectThrows(() -> prep(updater(inst, root.resolve("data-down"), "3.0.0", s), s, null), "not newer"); }
        });
        test("failure: manifest for a different version than the release tag refused", () -> {
            Path inst = copyTree(img1, "inst-ver");
            try (Server s = new Server(rel2)) { expectThrows(() -> prep(updater(inst, root.resolve("data-ver"), "1.0.0", s), s, "9.9.9"), "expected"); }
        });
        test("failure: install folder that doesn't match the update refused", () -> {
            Path inst = Files.createDirectories(root.resolve("inst-wrong"));
            try (Server s = new Server(rel2)) { expectThrows(() -> prep(updater(inst, root.resolve("data-wrong"), "1.0.0", s), s, "2.0.0"), "doesn't match"); }
        });
        test("cancel: cancelling mid-download removes staging and leaves install untouched", () -> {
            Path inst = copyTree(img1, "inst-cancel"), data = root.resolve("data-cancel");
            try (Server s = new Server(rel2); CancelToken t = new CancelToken()) {
                IncrementalUpdater u = updater(inst, data, "1.0.0", s);
                IncrementalUpdater.Prepared p = prep(u, s, "2.0.0");
                Map<String, String> before = snapshot(inst);
                expectThrows(() -> u.stage(p, t, new IncrementalUpdater.Listener() {
                    public void onState(IncrementalUpdater.State st, String m) { }
                    public void onProgress(long d, long tot) { t.cancel(); }
                }), "cancel");
                check(!Files.exists(u.stagingDir("2.0.0")), "staging removed");
                check(before.equals(snapshot(inst)), "install untouched");
            }
        });
    }

    /** Hand-built signed release with arbitrary content, for hostile-input tests. */
    static Path craft(String name, String version, String path, byte[] packedBlob, int method, long declaredSize, String declaredSha) throws Exception {
        Path dir = Files.createDirectories(root.resolve("craft-" + name));
        byte[] runSh = "#!/bin/sh\n".getBytes();
        Files.write(dir.resolve("payload.pack"), concat(runSh, packedBlob));
        JsonObject o = new JsonObject();
        o.addProperty("schema", 1); o.addProperty("app", "AveroLauncher"); o.addProperty("version", version); o.addProperty("releaseId", version);
        o.addProperty("minUpdaterVersion", 1); o.addProperty("keyId", "t1"); o.addProperty("launcher", "run.sh");
        JsonObject pk = new JsonObject(); pk.addProperty("name", "payload.pack"); pk.addProperty("size", runSh.length + packedBlob.length);
        pk.addProperty("sha256", sha(Files.readAllBytes(dir.resolve("payload.pack")))); o.add("pack", pk);
        JsonArray fs = new JsonArray();
        JsonObject a = new JsonObject(); a.addProperty("path", "run.sh"); a.addProperty("size", runSh.length); a.addProperty("sha256", sha(runSh)); a.addProperty("offset", 0); a.addProperty("packedSize", runSh.length); a.addProperty("method", 0); fs.add(a);
        JsonObject b = new JsonObject(); b.addProperty("path", path); b.addProperty("size", declaredSize); b.addProperty("sha256", declaredSha); b.addProperty("offset", runSh.length); b.addProperty("packedSize", packedBlob.length); b.addProperty("method", method); fs.add(b);
        o.add("files", fs); o.add("deleted", new JsonArray());
        signManifest(dir, new Gson().toJson(o).getBytes(StandardCharsets.UTF_8));
        return dir;
    }
    static byte[] concat(byte[] a, byte[] b) { byte[] r = Arrays.copyOf(a, a.length + b.length); System.arraycopy(b, 0, r, a.length, b.length); return r; }
    static byte[] rawDeflate(byte[] d) throws IOException { Deflater df = new Deflater(9, true); ByteArrayOutputStream bo = new ByteArrayOutputStream(); try (DeflaterOutputStream o = new DeflaterOutputStream(bo, df)) { o.write(d); } df.end(); return bo.toByteArray(); }

    static void securityTests(Path rel2, Path img1) throws Exception {
        test("security: decompression bomb (inflates past the signed size) is stopped", () -> {
            byte[] zeros = new byte[20_000_000];                        // 20 MB of zeros -> a few KB deflated
            byte[] blob = rawDeflate(zeros);
            Path rel = craft("bomb", "2.5.0", "app/bomb.bin", blob, 1, 1000, sha(new byte[1000]));
            Path inst = writeImage("inst-bomb", Map.of("run.sh", "#!/bin/sh\n".getBytes()));
            try (Server s = new Server(rel); CancelToken t = new CancelToken()) {
                IncrementalUpdater u = updater(inst, root.resolve("data-bomb"), "1.0.0", s);
                IncrementalUpdater.Prepared p = prep(u, s, "2.5.0");
                expectThrows(() -> u.stage(p, t, null), null);
                check(!Files.exists(u.stagedFile("2.5.0", "app/bomb.bin")), "bomb must not be staged");
            }
        });
        test("security: file shorter/different than signed hash is rejected", () -> {
            byte[] real = "hello world".getBytes();
            Path rel = craft("badhash", "2.5.1", "app/x.txt", real, 0, real.length, sha("something else".getBytes()));
            Path inst = writeImage("inst-badhash", Map.of("run.sh", "#!/bin/sh\n".getBytes()));
            try (Server s = new Server(rel); CancelToken t = new CancelToken()) {
                IncrementalUpdater u = updater(inst, root.resolve("data-badhash"), "1.0.0", s);
                expectThrows(() -> u.stage(prep(u, s, "2.5.1"), t, null), null);
            }
        });
        test("security: redirect to an untrusted host is blocked", () -> {
            Path inst = copyTree(img1, "inst-redir");
            try (Server s = new Server(rel2); CancelToken t = new CancelToken()) {
                s.redirectPackTo = "http://evil.example/payload.pack";
                IncrementalUpdater u = updater(inst, root.resolve("data-redir"), "1.0.0", s);
                expectThrows(() -> u.stage(prep(u, s, "2.0.0"), t, null), "untrusted");
            }
        });
        test("security: only https from allowed hosts; plain http and foreign hosts refused", () -> {
            SecureHttp h = new SecureHttp(URI.create("https://github.com/o/r/releases/download/1.0.0/update-manifest.json"), false);
            check(h.allowed(URI.create("https://github.com/o/r/x")), "github ok");
            check(h.allowed(URI.create("https://release-assets.githubusercontent.com/x")), "cdn ok");
            check(!h.allowed(URI.create("http://github.com/x")), "http refused");
            check(!h.allowed(URI.create("https://evil.com/x")), "foreign refused");
            check(!h.allowed(URI.create("https://github.com.evil.com/x")), "lookalike refused");
            check(!h.allowed(URI.create("http://127.0.0.1/x")), "loopback http refused in production mode");
            check(!h.allowed(URI.create("https://user:pw@github.com/x")), "userinfo refused");
        });
        test("security: pack name cannot point elsewhere (no path separators)", () -> expectThrows(() -> UpdateManifest.parse(mutate(rel2, o -> o.getAsJsonObject("pack").addProperty("name", "../evil.pack"))), "pack"));
        test("security: updater is unavailable without a trusted key (falls back to installer flow)", () -> {
            Path inst = copyTree(img1, "inst-nokey");
            try (Server s = new Server(rel2)) { IncrementalUpdater u = new IncrementalUpdater(inst, root.resolve("data-nokey"), "1.0.0", Map.of(), new SecureHttp(s.uri("update-manifest.json"), true), false); expectThrows(() -> prep(u, s, null), "trusted"); }
        });
    }

    // ------------------------------------------------------------------ PowerShell helper (needs pwsh)

    static final class HelperRun { int exit; String log; }

    static HelperRun runHelper(Path planFile, Path home) throws Exception {
        String pwsh = System.getenv("PWSH");
        String script = System.getProperty("helper.script", "src/main/resources/updater/apply-update.ps1");
        ProcessBuilder pb = new ProcessBuilder(pwsh, "-NoProfile", "-NonInteractive", "-File", script, "-PlanFile", planFile.toString());
        pb.environment().put("USERPROFILE", home.toString());
        pb.environment().put("HOME", home.toString());
        pb.environment().put("DOTNET_SYSTEM_GLOBALIZATION_INVARIANT", "1");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        HelperRun r = new HelperRun();
        r.exit = p.waitFor(); r.log = out;
        return r;
    }

    /** Stages a real update with the Java updater, then tweaks the plan so the helper doesn't wait for this JVM. */
    static final class Scenario {
        Path home, install, data, plan; IncrementalUpdater u;
        Map<String, String> beforeInstall;
    }

    static Scenario scenario(String name, Path relToApply, Path relPrev, Map<String, byte[]> installedImage, String fromVer, String toVer, Path img) throws Exception {
        Scenario sc = new Scenario();
        sc.home = Files.createDirectories(root.resolve("home-" + name));
        sc.install = sc.home.resolve("AppData/Programs/Avero");
        sc.data = sc.home.resolve("AppData/Roaming/.averolauncher");
        Files.createDirectories(sc.install);
        for (Map.Entry<String, byte[]> e : installedImage.entrySet()) { Path f = sc.install.resolve(e.getKey()); Files.createDirectories(f.getParent()); Files.write(f, e.getValue()); }
        sc.install.resolve("run.sh").toFile().setExecutable(true);
        Files.createDirectories(sc.data.resolve("updater"));
        Files.copy(relPrev.resolve("update-manifest.json"), sc.data.resolve("updater/installed-manifest.json"));
        Files.copy(relPrev.resolve("update-manifest.json.sig"), sc.data.resolve("updater/installed-manifest.json.sig"));
        try (Server s = new Server(relToApply); CancelToken t = new CancelToken()) {
            sc.u = updater(sc.install, sc.data, fromVer, s);
            sc.plan = sc.u.stage(prep(sc.u, s, toVer), t, null);
        }
        JsonObject pj = new Gson().fromJson(Files.readString(sc.plan), JsonObject.class);
        pj.addProperty("pid", 2_000_000_000L);        // not a running process: don't wait for the test JVM
        pj.addProperty("healthTimeoutSec", 6); pj.addProperty("exitGraceSec", 3);
        Files.writeString(sc.plan, new GsonBuilder().setPrettyPrinting().create().toJson(pj));
        sc.beforeInstall = snapshot(sc.install);
        return sc;
    }

    static String lastStatus(Scenario sc) throws IOException {
        Path f = sc.data.resolve("updater/last-update.json");
        return Files.exists(f) ? new Gson().fromJson(Files.readString(f), JsonObject.class).get("status").getAsString() : "none";
    }

    static int startedCount(Scenario sc) throws Exception {
        Path f = sc.install.resolve("started.log");
        for (int i = 0; i < 30 && !Files.exists(f); i++) Thread.sleep(200);
        Thread.sleep(500);
        return Files.exists(f) ? Files.readAllLines(f).size() : 0;
    }

    static void helperTests(Path rel1, Path rel2, Path img1) throws Exception {
        if (System.getenv("PWSH") == null) {
            skipped += 5;
            System.out.println("SKIP  helper tests (5): set PWSH to a PowerShell 7 executable to run them");
            return;
        }
        test("helper: successful update replaces, deletes, commits and restarts the new version", () -> {
            Scenario sc = scenario("ok", rel2, rel1, v1WithOld(), "1.0.0", "2.0.0", img1);
            HelperRun r = runHelper(sc.plan, sc.home);
            check(r.exit == 0, "exit " + r.exit + "\n" + r.log + log(sc));
            Map<String, String> now = snapshot(sc.install);
            Map<String, String> expect = hashesOf(v2());
            for (Map.Entry<String, String> e : expect.entrySet()) check(e.getValue().equals(now.get(e.getKey())), "installed " + e.getKey());
            check(!Files.exists(sc.install.resolve("old/removed.txt")), "removed file deleted");
            check(!Files.exists(sc.install.resolve("old")), "empty dir pruned");
            check("updated".equals(lastStatus(sc)), "status " + lastStatus(sc));
            check(!Files.exists(sc.data.resolve("updater/backup/2.0.0")), "backup dropped on commit");
            check(Files.exists(sc.data.resolve("updater/installed-manifest.json")) && Files.readString(sc.data.resolve("updater/installed-manifest.json")).contains("\"2.0.0\""), "installed manifest recorded");
            check(startedCount(sc) >= 1, "new launcher started");
        });
        test("helper: new version never signals startup -> ROLLED BACK and previous version restarted", () -> {
            // 2.0.0 whose version.txt says BAD: files install fine, launcher refuses to confirm startup.
            Map<String, byte[]> bad = v2(); bad.put("app/version.txt", "BAD".getBytes());
            Path imgBad = writeImage("img-bad", bad);
            Path relBad = generate("bad", imgBad, "2.0.0", rel1.resolve("update-manifest.json"));
            Scenario sc = scenario("rollback", relBad, rel1, v1WithOld(), "1.0.0", "2.0.0", img1);
            HelperRun r = runHelper(sc.plan, sc.home);
            check(r.exit == 1, "exit " + r.exit + "\n" + r.log + log(sc));
            Map<String, String> now = snapshot(sc.install);
            check(now.equals(sc.beforeInstall), "install restored byte-for-byte\nbefore=" + sc.beforeInstall + "\nnow=" + now);
            check("rolled_back".equals(lastStatus(sc)), "status " + lastStatus(sc));
            check(startedCount(sc) >= 2, "previous version restarted after rollback (started " + startedCount(sc) + "x)");
        });
        test("helper: staged file corrupted after verification -> aborts BEFORE touching the install", () -> {
            Scenario sc = scenario("corrupt", rel2, rel1, v1WithOld(), "1.0.0", "2.0.0", img1);
            Path staged = sc.u.stagedFile("2.0.0", "app/app.jar");
            byte[] b = Files.readAllBytes(staged); b[10] ^= 1; Files.write(staged, b);
            HelperRun r = runHelper(sc.plan, sc.home);
            check(r.exit == 1, "exit " + r.exit);
            check(sc.beforeInstall.equals(snapshot(sc.install)), "install untouched");
            check("failed".equals(lastStatus(sc)), "status " + lastStatus(sc));
        });
        test("helper: plan with a path-traversal entry is rejected, nothing written outside", () -> {
            Scenario sc = scenario("traversal", rel2, rel1, v1WithOld(), "1.0.0", "2.0.0", img1);
            JsonObject pj = new Gson().fromJson(Files.readString(sc.plan), JsonObject.class);
            pj.getAsJsonArray("files").get(0).getAsJsonObject().addProperty("path", "../../escaped.txt");
            Files.writeString(sc.plan, new Gson().toJson(pj));
            HelperRun r = runHelper(sc.plan, sc.home);
            check(r.exit == 1, "exit " + r.exit);
            check(!Files.exists(sc.home.resolve("AppData/escaped.txt")) && sc.beforeInstall.equals(snapshot(sc.install)), "nothing escaped / changed");
        });
        test("helper: install folder outside the user profile is refused", () -> {
            Scenario sc = scenario("outside", rel2, rel1, v1WithOld(), "1.0.0", "2.0.0", img1);
            HelperRun r = runHelper(sc.plan, root.resolve("some-other-home"));   // USERPROFILE no longer contains the install dir
            check(r.exit == 1, "exit " + r.exit);
            check(sc.beforeInstall.equals(snapshot(sc.install)), "install untouched");
        });
    }

    static String log(Scenario sc) {
        try { Path l = sc.data.resolve("updater/logs/update-2.0.0.log"); return Files.exists(l) ? "\n--- helper log ---\n" + Files.readString(l) : ""; } catch (IOException e) { return ""; }
    }
}
