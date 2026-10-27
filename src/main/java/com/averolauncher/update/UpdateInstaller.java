package com.averolauncher.update;

import com.averolauncher.logging.AppLogger;
import com.averolauncher.util.PathResolver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Fully automatic update installation.
 *
 * <p>Takes whatever {@code UpdateDownloader} saved (a .zip, a .exe/.msi installer, or a
 * portable app folder inside a zip), unpacks it, and opens the installer in its own window
 * (like a double-click). No scripts, no PowerShell, no hidden helper: the player just clicks
 * through the installer.
 *
 * <p>Two payload shapes are handled:
 * <ul>
 *   <li><b>Installer</b> — a single .msi, or an .exe whose bytes identify it as Inno
 *       Setup / NSIS / WiX Burn (or whose name contains "setup"/"install"). It is run
 *       with the matching silent flags.</li>
 *   <li><b>Portable / app-image</b> — anything else (a folder with the launcher .exe,
 *       {@code app/}, {@code runtime/} ...). The files are copied over the current
 *       install directory.</li>
 * </ul>
 * If the install directory isn't writable by the current user (e.g. Program Files)
 * the helper elevates once via UAC — that is the only prompt Windows allows.
 */
public final class UpdateInstaller {

    private static final String TAG = "UpdateInstaller";

    public enum Mode { INSTALLER, COPY }

    /** Outcome of {@link #prepareAndLaunch(Path)}. */
    public static final class Result {
        public final boolean started;
        public final String message;
        private Result(boolean started, String message) {
            this.started = started;
            this.message = message;
        }
        static Result ok(String m)   { return new Result(true, m); }
        static Result fail(String m) { return new Result(false, m); }
    }

    private UpdateInstaller() {}

    // ------------------------------------------------------------------ public

    /**
     * Unpacks {@code downloaded} and starts the helper that finishes the install. Blocking
     * (unzipping can take a moment) — call from a background thread. When this returns a
     * result with {@code started == true} the caller must exit the launcher promptly so the
     * helper can replace its files.
     */
    public static Result prepareAndLaunch(Path downloaded) {
        try {
            if (!isWindows()) {
                return Result.fail("Automatic install is only available on Windows.");
            }
            if (downloaded == null || !Files.isRegularFile(downloaded)) {
                return Result.fail("Downloaded update file is missing.");
            }

            // May be null when started via `gradlew run` / an IDE. That is fine: installer
            // payloads don't need to know where the current copy lives.
            Path currentExe = currentLauncherExe();
            Path installDir = currentExe != null ? currentExe.getParent() : null;

            Path updatesRoot = PathResolver.launcherDataDir().resolve("updates");
            Path staging = updatesRoot.resolve("staging");
            deleteRecursively(staging);
            Files.createDirectories(staging);

            String name = downloaded.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.endsWith(".zip")) {
                unzip(downloaded, staging);
            } else {
                Files.copy(downloaded, staging.resolve(downloaded.getFileName()),
                        StandardCopyOption.REPLACE_EXISTING);
            }

            Path payload = descendSingleDirectory(staging);
            Plan plan = planFor(payload, currentExe);
            if (plan.mode == Mode.COPY && installDir == null) {
                return Result.fail("This looks like a portable build, but the launcher is running from "
                        + "an IDE/Gradle so there is no install folder to update. Update saved to "
                        + downloaded);
            }

            if (plan.mode != Mode.INSTALLER) {
                return Result.fail("This update is a portable package, so it can't be installed automatically. "
                        + "It was saved to " + downloaded + " \u2014 extract it over the launcher folder, or download "
                        + "the normal installer.");
            }

            // Open the installer exactly as if the player had double-clicked it: its own window and its own
            // Windows permission prompt. The launcher runs no scripts, searches no folders and reads no registry.
            Path installer = plan.target;
            List<String> cmd = new ArrayList<>();
            if (installer.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".msi")) {
                cmd.add("msiexec.exe");
                cmd.add("/i");
            }
            cmd.add(installer.toString());

            AppLogger.get().info(TAG, "Opening the update installer: " + installer);
            new ProcessBuilder(cmd)
                    .directory(installer.getParent().toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();

            return Result.ok("The update installer is open \u2014 finish it and start the launcher again.");
        } catch (Exception e) {
            AppLogger.get().warn(TAG, "Automatic install failed: " + e.getMessage());
            return Result.fail("Couldn't install automatically: "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    /**
     * Exits the launcher so the helper can replace files. Forces the JVM down shortly after
     * {@code Platform.exit()} in case a non-daemon thread is keeping it alive.
     */
    public static void exitForUpdate() {
        Thread killer = new Thread(() -> {
            try { Thread.sleep(2000); } catch (InterruptedException ignored) { }
            Runtime.getRuntime().halt(0);
        }, "update-exit-watchdog");
        killer.setDaemon(true);
        killer.start();
        javafx.application.Platform.exit();
    }

    // ----------------------------------------------------------------- helpers

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * The installed launcher's own .exe (jpackage app-image launchers run in-process, so the
     * process command IS the launcher). Null when we're running under java/javaw directly
     * (IDE, `gradlew run`) — nothing sensible to replace in that case.
     */
    private static Path currentLauncherExe() {
        String cmd = ProcessHandle.current().info().command().orElse(null);
        if (cmd == null) return null;
        Path p = Path.of(cmd);
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!n.endsWith(".exe") || n.equals("java.exe") || n.equals("javaw.exe")) return null;
        return p;
    }

    private static boolean isDirWritable(Path dir) {
        try {
            Path probe = dir.resolve(".averolauncher-write-test-" + System.nanoTime());
            Files.createFile(probe);
            Files.delete(probe);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void unzip(Path zip, Path dest) throws IOException {
        Path destReal = dest.toAbsolutePath().normalize();
        try (InputStream in = Files.newInputStream(zip);
             ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                Path out = destReal.resolve(e.getName()).normalize();
                if (!out.startsWith(destReal)) {
                    throw new IOException("Blocked unsafe path in update archive: " + e.getName());
                }
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** If staging holds exactly one directory and nothing else, descend into it. */
    private static Path descendSingleDirectory(Path dir) throws IOException {
        Path cur = dir;
        for (int i = 0; i < 4; i++) {
            List<Path> kids;
            try (Stream<Path> s = Files.list(cur)) { kids = s.toList(); }
            if (kids.size() == 1 && Files.isDirectory(kids.get(0))) {
                cur = kids.get(0);
            } else {
                break;
            }
        }
        return cur;
    }

    private static final class Plan {
        final Mode mode; final Path target; final String silentArgs;
        Plan(Mode mode, Path target, String silentArgs) {
            this.mode = mode; this.target = target; this.silentArgs = silentArgs;
        }
    }

    /**
     * Decides what to do with the unpacked payload:
     * an app-image (launcher exe next to app/ + runtime/) is copied over the install dir;
     * an .msi or an installer .exe is run silently; a lone unknown .exe is treated as an
     * installer too (the "zip containing an .exe" case) unless it has the same file name as
     * the running launcher, in which case it is a portable replacement.
     */
    private static Plan planFor(Path payload, Path currentExe) throws IOException {
        List<Path> all;
        try (Stream<Path> s = Files.walk(payload, 3)) {
            all = s.filter(Files::isRegularFile).toList();
        }
        boolean appImage;
        try (Stream<Path> s = Files.list(payload)) {
            appImage = s.anyMatch(p -> Files.isDirectory(p) && (
                    p.getFileName().toString().equalsIgnoreCase("app")
                 || p.getFileName().toString().equalsIgnoreCase("runtime")));
        }
        if (appImage) return new Plan(Mode.COPY, payload, "");

        for (Path f : all) {
            if (f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".msi")) {
                return new Plan(Mode.INSTALLER, f, "");
            }
        }

        List<Path> exes = all.stream()
                .filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".exe"))
                .toList();
        if (exes.isEmpty()) return new Plan(Mode.COPY, payload, "");

        for (Path exe : exes) {
            String n = exe.getFileName().toString().toLowerCase(Locale.ROOT);
            if (n.contains("setup") || n.contains("install") || detectInstallerKind(exe) != null) {
                return new Plan(Mode.INSTALLER, exe, silentFlagsFor(exe));
            }
        }
        if (exes.size() == 1) {
            Path only = exes.get(0);
            boolean sameAsCurrent = currentExe != null
                    && only.getFileName().toString().equalsIgnoreCase(currentExe.getFileName().toString());
            if (sameAsCurrent) return new Plan(Mode.COPY, only.getParent(), "");
            return new Plan(Mode.INSTALLER, only, silentFlagsFor(only));
        }
        return new Plan(Mode.COPY, payload, "");
    }

    private static String detectInstallerKind(Path exe) {
        try {
            byte[] bytes = Files.readAllBytes(exe);
            String text = new String(bytes, StandardCharsets.ISO_8859_1);
            if (text.contains("Inno Setup")) return "INNO";
            if (text.contains("Nullsoft"))   return "NSIS";
            if (text.contains(".wixburn"))   return "BURN";
        } catch (IOException ignored) { }
        return null;
    }

    private static String silentFlagsFor(Path exe) {
        String kind = detectInstallerKind(exe);
        if (kind == null) return "/VERYSILENT /SUPPRESSMSGBOXES /NORESTART /SP-";
        return switch (kind) {
            case "INNO" -> "/VERYSILENT /SUPPRESSMSGBOXES /NORESTART /SP- /CLOSEAPPLICATIONS";
            case "NSIS" -> "/S";
            case "BURN" -> "/quiet /norestart";
            default     -> "/VERYSILENT /SUPPRESSMSGBOXES /NORESTART /SP-";
        };
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }
}
