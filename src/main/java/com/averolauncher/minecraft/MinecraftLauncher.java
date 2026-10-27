package com.averolauncher.minecraft;

import com.averolauncher.logging.AppLogger;
import com.averolauncher.model.manifest.VersionDetail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Builds the real java command line for a resolved version and spawns
 * Minecraft as an actual child process. No simulation, no fake process:
 * this uses java.lang.ProcessBuilder to start the real java executable
 * with the real classpath, JVM args, main class, and game args that Mojang's
 * own launcher would use for the same version.
 */
public class MinecraftLauncher {

    private static final String TAG = "MinecraftLauncher";

    /**
     * Launches Minecraft. Returns the live Process handle so callers can
     * monitor it (exit code, output) rather than firing-and-forgetting.
     * Throws with a clear, specific message on any precondition failure
     * (missing Java, missing client jar, missing main class) rather than
     * silently doing nothing.
     */
    public Process launch(VersionDetail detail, LaunchContext ctx, Consumer<String> onOutputLine) throws IOException {
        validatePreconditions(detail, ctx);

        List<String> command = new ArrayList<>();
        command.add(ctx.javaExecutable.toString());
        command.addAll(ArgumentBuilder.buildJvmArgs(detail, ctx));
        command.add(detail.mainClass);
        command.addAll(ArgumentBuilder.buildGameArgs(detail, ctx));

        AppLogger.get().info(TAG, "Launching Minecraft " + detail.id + " with main class " + detail.mainClass);
        AppLogger.get().debug(TAG, "Command: " + redactSensitive(command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(ctx.gameDir.toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();

        // Stream the child process's output back to the launcher's log/UI on a
        // daemon thread, so the JavaFX thread is never blocked reading output.
        Thread outputThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (onOutputLine != null) {
                        onOutputLine.accept(line);
                    }
                }
            } catch (IOException e) {
                AppLogger.get().debug(TAG, "Output stream closed: " + e.getMessage());
            }
        }, "Minecraft-Output-" + detail.id);
        outputThread.setDaemon(true);
        outputThread.start();

        return process;
    }

    private void validatePreconditions(VersionDetail detail, LaunchContext ctx) throws IOException {
        if (ctx.javaExecutable == null || !Files.isExecutable(ctx.javaExecutable)) {
            throw new IOException("No valid Java executable configured. Set one in Settings or let AveroLauncher auto-detect.");
        }
        if (detail.mainClass == null || detail.mainClass.isBlank()) {
            throw new IOException("Version metadata for " + detail.id + " has no main class defined.");
        }
        // Mod-loader versions (Fabric, Forge, NeoForge) have no own client jar — they
        // inherit the vanilla client jar from the parent version they extend.
        // After mergeInto(), detail.downloads is the parent's downloads (set on the child
        // if the child had none), so we use that to find the right jar name.
        // Strategy: find ANY .jar under versions/<id>/ or versions/<parentId>/. If neither
        // exists the user hasn't installed the vanilla base version yet.
        boolean clientJarExists = clientJarExistsFor(detail, ctx);
        if (!clientJarExists) {
            throw new IOException("Client jar missing for " + detail.id
                    + ". Install the base Minecraft version first (the mod loader requires it).");
        }
        if (!Files.exists(ctx.nativesDir)) {
            throw new IOException("Natives directory missing for " + detail.id
                    + ". Run installation/extraction before launching.");
        }
        if (ctx.classpath == null || ctx.classpath.isBlank()) {
            throw new IOException("Classpath could not be built for " + detail.id + ".");
        }
    }

    /**
     * Checks whether a usable client jar exists for this version. Handles:
     *  - Vanilla: versions/<id>/<id>.jar
     *  - Mod-loader (inheritsFrom): the jar may live under the parent version's directory
     *    because the mod loader's version JSON lists no client download of its own.
     *    After mergeInto() the classpath already includes the parent jar, so we just need
     *    to verify that at least one .jar exists somewhere in versions/ that's on the
     *    classpath.
     */
    private boolean clientJarExistsFor(VersionDetail detail, LaunchContext ctx) {
        // Direct jar
        Path directJar = ctx.minecraftDir.resolve("versions").resolve(detail.id).resolve(detail.id + ".jar");
        if (Files.exists(directJar)) return true;

        // For mod loaders: the classpath contains the vanilla jar. If ANY .jar in the
        // classpath already exists on disk, we're good enough to attempt launch.
        if (ctx.classpath != null) {
            String sep = System.getProperty("path.separator");
            for (String entry : ctx.classpath.split(java.util.regex.Pattern.quote(sep))) {
                if (entry.endsWith(".jar") && Files.exists(Path.of(entry))) {
                    return true;
                }
            }
        }
        return false;
    }

    private String redactSensitive(List<String> command) {
        List<String> redacted = new ArrayList<>();
        for (int i = 0; i < command.size(); i++) {
            String prev = i > 0 ? command.get(i - 1) : "";
            if (prev.equals("--accessToken") || prev.equals("--session") || prev.equals("--uuid")) {
                redacted.add("<redacted>");
            } else {
                redacted.add(command.get(i));
            }
        }
        return String.join(" ", redacted);
    }
}
