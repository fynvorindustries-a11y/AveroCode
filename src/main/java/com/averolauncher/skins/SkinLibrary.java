package com.averolauncher.skins;

import com.averolauncher.logging.AppLogger;
import com.averolauncher.util.PathResolver;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The player's saved skins: {@code <launcherDataDir>/skins/<id>.png} plus an index
 * ({@code skins.json}). Every skin is stored already normalised (64x64 PNG) so applying one never
 * has to re-validate anything. Safe to call from any thread.
 */
public final class SkinLibrary {

    private static final String TAG = "SkinLibrary";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<SavedSkin>>() {}.getType();
    private static final Object LOCK = new Object();
    public static final int MAX_SKINS = 60;

    public static final String CLASSIC = "classic";
    public static final String SLIM = "slim";

    public static class SavedSkin {
        public String id;
        public String name;
        public String variant = CLASSIC;   // "classic" (Steve arms) or "slim" (Alex arms)
        public String source;              // "file", "username:Notch", "url", "current"
        public long addedAt;

        public boolean isSlim() { return SLIM.equals(variant); }
    }

    public Path dir() { return PathResolver.launcherDataDir().resolve("skins"); }
    private Path index() { return dir().resolve("skins.json"); }
    public Path pngPath(String id) { return dir().resolve(id + ".png"); }

    /** Newest first. Skins whose PNG has gone missing are skipped. */
    public List<SavedSkin> list() {
        synchronized (LOCK) {
            List<SavedSkin> all = readIndex();
            List<SavedSkin> out = new ArrayList<>();
            for (SavedSkin s : all) if (s != null && s.id != null && Files.isRegularFile(pngPath(s.id))) out.add(s);
            out.sort((a, b) -> Long.compare(b.addedAt, a.addedAt));
            return out;
        }
    }

    public byte[] readPng(String id) throws IOException {
        return Files.readAllBytes(pngPath(id));
    }

    /**
     * Validates {@code rawPng} (must be a real 64x64 / 64x32 skin) and saves it.
     *
     * @param variant {@link #CLASSIC}, {@link #SLIM}, or null to auto-detect from the pixels
     * @throws SkinImage.InvalidSkinException when the image isn't a usable skin
     */
    public SavedSkin add(String name, byte[] rawPng, String variant, String source)
            throws SkinImage.InvalidSkinException, IOException {
        java.awt.image.BufferedImage img = SkinImage.load(rawPng);
        String v = SLIM.equals(variant) ? SLIM : CLASSIC.equals(variant) ? CLASSIC
                : (SkinImage.looksSlim(img) ? SLIM : CLASSIC);
        synchronized (LOCK) {
            List<SavedSkin> all = readIndex();
            if (all.size() >= MAX_SKINS) throw new IOException("Skin library is full (" + MAX_SKINS + "). Delete one first.");
            SavedSkin s = new SavedSkin();
            s.id = UUID.randomUUID().toString();
            s.name = cleanName(name);
            s.variant = v;
            s.source = source;
            s.addedAt = System.currentTimeMillis();
            Files.createDirectories(dir());
            Files.write(pngPath(s.id), SkinImage.toPng(img));
            all.add(s);
            writeIndex(all);
            return s;
        }
    }

    public void delete(String id) {
        synchronized (LOCK) {
            List<SavedSkin> all = readIndex();
            all.removeIf(s -> s != null && id.equals(s.id));
            try { Files.deleteIfExists(pngPath(id)); } catch (IOException e) {
                AppLogger.get().warn(TAG, "Could not delete skin file: " + e.getMessage());
            }
            writeIndex(all);
        }
    }

    public void setVariant(String id, String variant) {
        synchronized (LOCK) {
            List<SavedSkin> all = readIndex();
            for (SavedSkin s : all) if (s != null && id.equals(s.id)) s.variant = SLIM.equals(variant) ? SLIM : CLASSIC;
            writeIndex(all);
        }
    }

    // ------------------------------------------------------------------ internals

    private static String cleanName(String name) {
        String n = name == null ? "" : name.replaceAll("[\\p{Cntrl}]", "").trim();
        if (n.isEmpty()) n = "My skin";
        return n.length() > 32 ? n.substring(0, 32) : n;
    }

    private List<SavedSkin> readIndex() {
        Path f = index();
        if (!Files.exists(f)) return new ArrayList<>();
        try {
            String json = Files.readString(f, StandardCharsets.UTF_8).trim();
            if (json.isEmpty()) return new ArrayList<>();
            List<SavedSkin> list = GSON.fromJson(json, LIST_TYPE);
            return list != null ? new ArrayList<>(list) : new ArrayList<>();
        } catch (Exception e) {
            AppLogger.get().warn(TAG, "skins.json unreadable, keeping a copy: " + e.getMessage());
            try { Files.copy(f, f.resolveSibling("skins.corrupted." + System.currentTimeMillis() + ".json")); } catch (IOException ignored) { }
            return new ArrayList<>();
        }
    }

    private void writeIndex(List<SavedSkin> all) {
        try {
            Files.createDirectories(dir());
            Path tmp = index().resolveSibling("skins.json.tmp");
            Files.writeString(tmp, GSON.toJson(all), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, index(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, index(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            AppLogger.get().error(TAG, "Could not save skins.json", e);
        }
    }
}
