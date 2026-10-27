package com.averolauncher.update.incremental;

import com.averolauncher.logging.AppLogger;
import com.averolauncher.util.PathResolver;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The public keys the launcher trusts for update manifests.
 *
 * <ul>
 *   <li><b>Built-in</b>: {@code /updater/trusted-keys.json} inside the launcher jar
 *       ({@code {"keys":[{"id":"k1","publicKey":"<base64 X.509>"}]}}). Only public keys live there.</li>
 *   <li><b>Rotated</b>: keys announced by an already-verified manifest ({@code rotation.newKeys}) are saved to
 *       {@code <dataDir>/updater/trusted-keys.json}. Rotation = release N is signed with the old key and
 *       announces the new key; from then on release N+1 can be signed with the new one, and the old key is
 *       dropped from the built-in file in a later build.</li>
 * </ul>
 * If no key is configured, incremental updating reports itself unavailable and the launcher keeps using the
 * installer-based update.
 */
public final class TrustedKeys {

    private static final String TAG = "TrustedKeys";

    private TrustedKeys() {}

    public static Path rotatedKeysFile() {
        return PathResolver.launcherDataDir().resolve("updater").resolve("trusted-keys.json");
    }

    public static Map<String, PublicKey> load() {
        Map<String, PublicKey> out = new LinkedHashMap<>();
        try (InputStream in = TrustedKeys.class.getResourceAsStream("/updater/trusted-keys.json")) {
            if (in != null) readInto(new java.io.InputStreamReader(in, StandardCharsets.UTF_8), out);
        } catch (Exception e) {
            AppLogger.get().warn(TAG, "Built-in trusted keys unreadable: " + e.getMessage());
        }
        Path rotated = rotatedKeysFile();
        if (Files.isRegularFile(rotated)) {
            try (Reader r = Files.newBufferedReader(rotated, StandardCharsets.UTF_8)) {
                readInto(r, out);
            } catch (Exception e) {
                AppLogger.get().warn(TAG, "Rotated trusted keys unreadable: " + e.getMessage());
            }
        }
        return out;
    }

    public static boolean isConfigured() {
        return !load().isEmpty();
    }

    private static void readInto(Reader r, Map<String, PublicKey> out) {
        JsonObject root = new Gson().fromJson(r, JsonObject.class);
        if (root == null || !root.has("keys")) return;
        root.getAsJsonArray("keys").forEach(el -> {
            try {
                JsonObject k = el.getAsJsonObject();
                out.put(k.get("id").getAsString(), SignatureVerifier.parsePublicKey(k.get("publicKey").getAsString()));
            } catch (Exception e) {
                AppLogger.get().warn(TAG, "Ignoring an unusable trusted key entry");
            }
        });
    }

    /** Persists keys announced by a manifest that has ALREADY passed signature verification. */
    public static void saveRotation(UpdateManifest verified) {
        if (verified.rotation == null || verified.rotation.newKeys == null || verified.rotation.newKeys.isEmpty()) return;
        try {
            List<Map<String, String>> keys = new ArrayList<>();
            Path file = rotatedKeysFile();
            Map<String, String> merged = new LinkedHashMap<>();
            if (Files.isRegularFile(file)) {
                JsonObject old = new Gson().fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
                if (old != null && old.has("keys")) {
                    old.getAsJsonArray("keys").forEach(el ->
                            merged.put(el.getAsJsonObject().get("id").getAsString(),
                                    el.getAsJsonObject().get("publicKey").getAsString()));
                }
            }
            for (UpdateManifest.Key k : verified.rotation.newKeys) {
                SignatureVerifier.parsePublicKey(k.publicKey);   // must parse, or it is dropped
                merged.put(k.id, k.publicKey);
            }
            merged.forEach((id, key) -> keys.add(Map.of("id", id, "publicKey", key)));
            Files.createDirectories(file.getParent());
            Files.writeString(file, new Gson().toJson(Map.of("keys", keys)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            AppLogger.get().warn(TAG, "Could not save rotated keys: " + e.getMessage());
        }
    }
}
