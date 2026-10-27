package com.averolauncher.util;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Resolves OS-appropriate application-data and default Minecraft directories
 * at runtime. Never hard-codes a single path.
 */
public final class PathResolver {

    private PathResolver() {}

    private static String os() {
        return System.getProperty("os.name", "").toLowerCase();
    }

    public static boolean isWindows() {
        return os().contains("win");
    }

    public static boolean isMac() {
        return os().contains("mac");
    }

    public static boolean isLinux() {
        return os().contains("nux") || os().contains("nix");
    }

    /**
     * Directory where AveroLauncher stores its own configuration, cache and logs.
     * Uses a dot-prefixed hidden folder (like .tlauncher, .minecraft) so it is
     * consistent and discoverable by the user.
     *
     * Windows: %APPDATA%\.averolauncher
     * macOS:   ~/Library/Application Support/.averolauncher
     * Linux:   ~/.averolauncher
     *
     * IMPORTANT: this path is intentionally identical to defaultMinecraftDir() so
     * that settings.json, the metadata cache, and all game files (versions/,
     * libraries/, assets/, mods/, etc) live under the same single root directory.
     * Previously this returned "AveroLauncher" (no dot) which caused a path mismatch
     * with defaultMinecraftDir() (".averolauncher"), so settings were saved to one
     * folder and never found on restart.
     */
    public static Path launcherDataDir() {
        String home = System.getProperty("user.home", ".");
        if (isWindows()) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) {
                return Paths.get(appData, ".averolauncher");
            }
            return Paths.get(home, "AppData", "Roaming", ".averolauncher");
        } else if (isMac()) {
            return Paths.get(home, "Library", "Application Support", ".averolauncher");
        } else {
            return Paths.get(home, ".averolauncher");
        }
    }

    /**
     * The default game-files directory AveroLauncher uses for its own,
     * isolated Minecraft installation (versions/, libraries/, assets/, mods/,
     * saves/, etc). Deliberately separate from the shared .minecraft folder
     * so AveroLauncher never reads, writes, or corrupts files belonging to
     * the official launcher or any other launcher. The user may still
     * override this in Settings to point at a shared .minecraft folder if
     * they explicitly choose to.
     *
     * Windows: %APPDATA%\.averolauncher
     * macOS:   ~/Library/Application Support/.averolauncher
     * Linux:   ~/.averolauncher
     */
    public static Path defaultMinecraftDir() {
        String home = System.getProperty("user.home", ".");
        if (isWindows()) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) {
                return Paths.get(appData, ".averolauncher");
            }
            return Paths.get(home, "AppData", "Roaming", ".averolauncher");
        } else if (isMac()) {
            return Paths.get(home, "Library", "Application Support", ".averolauncher");
        } else {
            return Paths.get(home, ".averolauncher");
        }
    }
}
