package com.averolauncher.repository;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.averolauncher.logging.AppLogger;
import com.averolauncher.model.LauncherSettings;
import com.averolauncher.util.PathResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Loads and saves LauncherSettings as JSON in the OS-appropriate app-data directory.
 * Corrupted config files are handled gracefully: on parse failure we back up the
 * bad file and fall back to defaults rather than crashing the launcher.
 */
public final class SettingsRepository {

    private static final String TAG = "SettingsRepository";
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path settingsFile;

    public SettingsRepository() {
        Path dir = PathResolver.launcherDataDir();
        this.settingsFile = dir.resolve("settings.json");
    }

    public LauncherSettings load() {
        try {
            Files.createDirectories(settingsFile.getParent());
        } catch (IOException e) {
            AppLogger.get().error(TAG, "Could not create data directory", e);
        }

        if (!Files.exists(settingsFile)) {
            LauncherSettings defaults = createDefaults();
            save(defaults);
            return defaults;
        }

        try {
            String json = Files.readString(settingsFile);

            // ── Migration: if an old settings.json still has msClientId in plain text,
            //    move it into the encrypted CredentialStore and re-save without it. ──
            com.google.gson.JsonObject raw = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            if (raw.has("msClientId") && !raw.get("msClientId").isJsonNull()) {
                String legacyId = raw.get("msClientId").getAsString();
                if (legacyId != null && !legacyId.isBlank()
                        && com.averolauncher.util.CredentialStore.read(
                                com.averolauncher.util.CredentialStore.KEY_MS_CLIENT_ID) == null) {
                    com.averolauncher.util.CredentialStore.write(
                            com.averolauncher.util.CredentialStore.KEY_MS_CLIENT_ID, legacyId);
                    AppLogger.get().info(TAG, "Migrated msClientId from settings.json to encrypted credential store");
                }
                // Strip it from the JSON before parsing into LauncherSettings
                raw.remove("msClientId");
                json = raw.toString();
            }

            LauncherSettings settings = gson.fromJson(json, LauncherSettings.class);
            if (settings == null) {
                throw new IOException("Parsed settings were null");
            }
            fillMissingDefaults(settings);
            return settings;
        } catch (Exception e) {
            AppLogger.get().error(TAG, "Settings file corrupted, backing up and using defaults", e);
            backupCorruptedFile();
            LauncherSettings defaults = createDefaults();
            save(defaults);
            return defaults;
        }
    }

    public void save(LauncherSettings settings) {
        try {
            Files.createDirectories(settingsFile.getParent());
            String json = gson.toJson(settings);
            Path tmp = settingsFile.resolveSibling(settingsFile.getFileName() + ".tmp");
            Files.writeString(tmp, json);
            Files.move(tmp, settingsFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            AppLogger.get().debug(TAG, "Settings saved");
        } catch (IOException e) {
            AppLogger.get().error(TAG, "Failed to save settings", e);
        }
    }

    private void backupCorruptedFile() {
        try {
            Path backup = settingsFile.resolveSibling("settings.corrupted." + System.currentTimeMillis() + ".json");
            Files.move(settingsFile, backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            AppLogger.get().error(TAG, "Failed to back up corrupted settings file", e);
        }
    }

    private LauncherSettings createDefaults() {
        LauncherSettings s = new LauncherSettings();
        s.minecraftDirectory = PathResolver.defaultMinecraftDir().toString();
        s.downloadDirectory = s.minecraftDirectory;
        // Size the default to this machine's actual RAM instead of a fixed 2 GB.
        s.maxRamMb = com.averolauncher.util.MemoryAdvisor.recommendedMaxMb();
        return s;
    }

    private void fillMissingDefaults(LauncherSettings s) {
        if (s.minecraftDirectory == null || s.minecraftDirectory.isBlank()) {
            s.minecraftDirectory = PathResolver.defaultMinecraftDir().toString();
        }
        if (s.downloadDirectory == null || s.downloadDirectory.isBlank()) {
            s.downloadDirectory = s.minecraftDirectory;
        }
        if (s.theme == null) s.theme = "DARK";
        if (s.closeBehavior == null) s.closeBehavior = "EXIT";
        if (s.logLevel == null) s.logLevel = "INFO";
    }
}
