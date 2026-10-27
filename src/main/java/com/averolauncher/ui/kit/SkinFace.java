package com.averolauncher.ui.kit;

import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Draws a player's face (the 8x8 head with the hat layer on top) from a skin PNG, enlarged with
 * nearest-neighbour so every skin pixel stays a sharp square at any size or screen scaling.
 * The old avatar kept the face at 8x8 and let JavaFX stretch it to 76px, which looked blurry and uneven.
 */
public final class SkinFace {

    /** Each skin pixel becomes a 20x20 block: 8px face -> 160x160 image (crisp when shown at 80px, also on HiDPI). */
    public static final int SCALE = 20;
    private static final Map<String, Image> CACHE = new ConcurrentHashMap<>();

    private SkinFace() {}

    /** Loads {@code url} in the background and hands back the enlarged face on the FX thread, or null when unusable. */
    public static void load(String url, Consumer<Image> done) {
        Image cached = CACHE.get(url);
        if (cached != null) { done.accept(cached); return; }
        try {
            Image skin = new Image(url, true);
            final boolean[] finished = {false};
            Runnable finish = () -> {
                if (finished[0]) return;
                finished[0] = true;
                Image face = (skin.isError() || skin.getWidth() < 64) ? null : build(skin);
                if (face != null) CACHE.put(url, face);
                Platform.runLater(() -> done.accept(face));
            };
            if (skin.getProgress() >= 1.0) finish.run();
            else skin.progressProperty().addListener((o, was, now) -> { if (now.doubleValue() >= 1.0) finish.run(); });
            skin.errorProperty().addListener((o, was, err) -> {
                if (err && !finished[0]) { finished[0] = true; Platform.runLater(() -> done.accept(null)); }
            });
        } catch (Exception ex) {
            done.accept(null);
        }
    }

    /** Public so a skin that is already loaded can be drawn without another download. */
    public static Image build(Image skin) {
        PixelReader pr = skin.getPixelReader();
        if (pr == null) return null;
        // Old 64x32 and new 64x64 skins keep the head and hat at the same place.
        int n = 8 * SCALE;
        WritableImage out = new WritableImage(n, n);
        var pw = out.getPixelWriter();
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int base = pr.getArgb(8 + x, 8 + y) | 0xFF000000;
                int hat = pr.getArgb(40 + x, 8 + y);
                int a = (hat >>> 24) & 0xFF;
                int px = base;
                if (a == 255) px = hat;
                else if (a > 0) px = blend(hat, base);
                for (int dy = 0; dy < SCALE; dy++) for (int dx = 0; dx < SCALE; dx++) pw.setArgb(x * SCALE + dx, y * SCALE + dy, px);
            }
        }
        return out;
    }

    private static int blend(int over, int under) {
        float a = ((over >>> 24) & 0xFF) / 255f;
        int r = (int) (((over >> 16) & 0xFF) * a + ((under >> 16) & 0xFF) * (1 - a));
        int g = (int) (((over >> 8) & 0xFF) * a + ((under >> 8) & 0xFF) * (1 - a));
        int b = (int) ((over & 0xFF) * a + (under & 0xFF) * (1 - a));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
