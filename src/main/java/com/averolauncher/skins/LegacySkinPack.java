package com.averolauncher.skins;

import com.averolauncher.logging.AppLogger;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Clean-up for older launcher versions, which made offline skins show by writing a resource pack
 * ({@code resourcepacks/AveroSkin.zip}) and switching it on in {@code options.txt}.
 *
 * <p>Skins no longer use a resource pack: the Avero agent loaded into the game supplies the skin itself
 * (see {@code CapeSupport}). This removes the leftover pack and its entry in {@code options.txt} so it can
 * not keep forcing the old skin over the new system. Does nothing when there is nothing left over.
 */
public final class LegacySkinPack {

    private static final String TAG = "LegacySkinPack";
    private static final String PACK_FILE = "AveroSkin.zip";
    private static final String PACK_ENTRY = "file/" + PACK_FILE;

    private LegacySkinPack() {}

    /** Removes the old pack file and its options.txt entries. Never throws: a failure only logs. */
    public static void remove(Path gameDir) {
        try {
            if (gameDir == null) return;
            Path pack = gameDir.resolve("resourcepacks").resolve(PACK_FILE);
            Path options = gameDir.resolve("options.txt");
            boolean listed = Files.isRegularFile(options)
                    && Files.readString(options, StandardCharsets.UTF_8).contains(PACK_FILE);
            if (!listed && !Files.exists(pack)) return;           // nothing left over

            if (listed) dropFromOptions(options);
            Files.deleteIfExists(pack);
            AppLogger.get().info(TAG, "Removed the old Avero skin resource pack from " + gameDir);
        } catch (Exception e) {
            AppLogger.get().warn(TAG, "Could not remove the old skin resource pack: " + e.getMessage());
        }
    }

    /** Takes our pack out of the {@code resourcePacks:[...]} and {@code incompatibleResourcePacks:[...]} lines. */
    private static void dropFromOptions(Path options) throws java.io.IOException {
        List<String> lines = new ArrayList<>(Files.readAllLines(options, StandardCharsets.UTF_8));
        boolean changed = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            for (String key : new String[] { "resourcePacks:", "incompatibleResourcePacks:" }) {
                if (!line.startsWith(key)) continue;
                try {
                    JsonElement e = JsonParser.parseString(line.substring(key.length()));
                    if (!e.isJsonArray()) continue;
                    JsonArray kept = new JsonArray();
                    boolean removed = false;
                    for (JsonElement x : e.getAsJsonArray()) {
                        if (PACK_ENTRY.equals(x.getAsString())) removed = true; else kept.add(x);
                    }
                    if (removed) { lines.set(i, key + kept); changed = true; }
                } catch (Exception ignored) {
                    // A line we can't parse is left exactly as it was.
                }
            }
        }
        if (changed) Files.write(options, lines, StandardCharsets.UTF_8);
    }
}
