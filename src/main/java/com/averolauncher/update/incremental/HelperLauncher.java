package com.averolauncher.update.incremental;

import com.averolauncher.logging.AppLogger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Starts {@code apply-update.ps1} from a copy that lives OUTSIDE the install folder (so it can never be
 * overwritten by, or locked together with, the files it replaces). The script receives only the path of a
 * plan file the launcher wrote; it takes no commands from the manifest.
 *
 * <p>PowerShell is launched with a process-scoped {@code -ExecutionPolicy Bypass} so the locally created
 * script can run on machines whose default policy is "Restricted". That setting applies to this one process
 * only; no system or user policy is changed and no security feature is disabled.
 */
public final class HelperLauncher {

    private static final String TAG = "HelperLauncher";

    private HelperLauncher() {}

    /** Test hook: {@code -Davero.update.powershell=/path/to/pwsh} replaces the Windows PowerShell location. */
    private static String powershellExe() {
        String override = System.getProperty("avero.update.powershell");
        if (override != null && !override.isBlank()) return override;
        String root = System.getenv("SystemRoot");
        if (root == null || root.isBlank()) root = "C:\\Windows";
        return root + "\\System32\\WindowsPowerShell\\v1.0\\powershell.exe";
    }

    public static Process start(Path planFile, Path dataDir) throws IOException {
        Path helperDir = dataDir.resolve("updater").resolve("helper");
        Files.createDirectories(helperDir);
        pruneOldScripts(helperDir);
        Path script = helperDir.resolve("apply-update-" + UUID.randomUUID() + ".ps1");
        try (InputStream in = HelperLauncher.class.getResourceAsStream("/updater/apply-update.ps1")) {
            if (in == null) throw new IOException("Updater helper script is missing from this build");
            Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(powershellExe());
        cmd.add("-NoProfile");
        cmd.add("-NonInteractive");
        cmd.add("-ExecutionPolicy");
        cmd.add("Bypass");
        if (System.getProperty("avero.update.powershell") == null) {
            cmd.add("-WindowStyle");
            cmd.add("Hidden");
        }
        cmd.add("-File");
        cmd.add(script.toString());
        cmd.add("-PlanFile");
        cmd.add(planFile.toString());

        AppLogger.get().info(TAG, "Starting update helper");
        Process p = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            Thread.sleep(400);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!p.isAlive() && p.exitValue() != 0) {
            throw new IOException("The update helper stopped immediately (exit " + p.exitValue() + ")");
        }
        return p;
    }

    private static void pruneOldScripts(Path dir) {
        try (var s = Files.list(dir)) {
            for (Path f : s.toList()) {
                if (f.getFileName().toString().startsWith("apply-update-")
                        && Files.getLastModifiedTime(f).toMillis() < System.currentTimeMillis() - 24L * 3600 * 1000) {
                    Files.deleteIfExists(f);
                }
            }
        } catch (IOException ignored) { }
    }
}
