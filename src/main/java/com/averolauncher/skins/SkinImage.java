package com.averolauncher.skins;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Everything the launcher needs to know about a Minecraft skin PNG: validate it, bring old 64x32
 * skins up to the modern 64x64 layout (so Mojang and every game version accept them), guess
 * classic vs slim arms, and draw a small front-view preview. Pure java.desktop — no JavaFX — so it
 * can be tested on its own.
 */
public final class SkinImage {

    private SkinImage() {}

    /** Largest file we'll even try to decode. Real skins are well under 20 KB. */
    public static final int MAX_BYTES = 1_000_000;

    public static class InvalidSkinException extends Exception {
        public InvalidSkinException(String message) { super(message); }
    }

    /**
     * Decodes and validates a skin. Accepts 64x64 and legacy 64x32 PNGs and always returns a 64x64
     * ARGB image. Anything else (wrong size, not an image) is rejected with a readable reason.
     */
    public static BufferedImage load(byte[] data) throws InvalidSkinException {
        if (data == null || data.length == 0) throw new InvalidSkinException("The file is empty.");
        if (data.length > MAX_BYTES) throw new InvalidSkinException("That file is too large to be a skin.");
        BufferedImage src;
        try {
            src = ImageIO.read(new ByteArrayInputStream(data));
        } catch (IOException e) {
            throw new InvalidSkinException("That file couldn't be read as an image.");
        }
        if (src == null) throw new InvalidSkinException("That file isn't a PNG image.");
        int w = src.getWidth(), h = src.getHeight();
        if (w != 64 || (h != 64 && h != 32)) {
            throw new InvalidSkinException("Skins must be 64\u00D764 (or the older 64\u00D732) pixels \u2014 this one is "
                    + w + "\u00D7" + h + ".");
        }
        BufferedImage out = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < 64; x++) out.setRGB(x, y, src.getRGB(x, y));
        if (h == 32) convertLegacy(out);
        return out;
    }

    /** Encodes as PNG (what Mojang's skin endpoint expects). */
    public static byte[] toPng(BufferedImage img) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (!ImageIO.write(img, "png", bos)) throw new IOException("No PNG writer available");
        return bos.toByteArray();
    }

    /**
     * Slim ("Alex", 3px arms) skins leave the last column of the right arm's back face empty; classic
     * ("Steve", 4px arms) skins paint it. Both columns x=54..55 over y=20..31 fully transparent => slim.
     */
    public static boolean looksSlim(BufferedImage skin64) {
        for (int y = 20; y < 32; y++) {
            for (int x = 54; x < 56; x++) {
                if (((skin64.getRGB(x, y) >>> 24) & 0xFF) != 0) return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ legacy 64x32 -> 64x64

    /**
     * Same conversion Minecraft itself applies to old skins: the left arm and left leg didn't exist,
     * the game mirrored the right ones. Rows 32..63 must be blank before calling.
     */
    private static void convertLegacy(BufferedImage img) {
        // (x, y, xOffset, yOffset, w, h) — copy then mirror horizontally
        int[][] r = {
                {4, 16, 16, 32, 4, 4}, {8, 16, 16, 32, 4, 4},
                {0, 20, 24, 32, 4, 12}, {4, 20, 16, 32, 4, 12}, {8, 20, 8, 32, 4, 12}, {12, 20, 16, 32, 4, 12},
                {44, 16, -8, 32, 4, 4}, {48, 16, -8, 32, 4, 4},
                {40, 20, 0, 32, 4, 12}, {44, 20, -8, 32, 4, 12}, {48, 20, -16, 32, 4, 12}, {52, 20, -8, 32, 4, 12},
        };
        for (int[] a : r) copyMirrored(img, a[0], a[1], a[2], a[3], a[4], a[5]);
    }

    private static void copyMirrored(BufferedImage img, int x, int y, int xOff, int yOff, int w, int h) {
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int sx = x + i, sy = y + j;
                int dx = x + xOff + (w - 1 - i), dy = y + yOff + j;
                if (dx >= 0 && dx < 64 && dy >= 0 && dy < 64) img.setRGB(dx, dy, img.getRGB(sx, sy));
            }
        }
    }

    // ------------------------------------------------------------------ preview

    /** Draws a 16x32 front view (base layer + hat/jacket/sleeves/pants overlays) on a transparent canvas. */
    public static BufferedImage renderFront(BufferedImage skin, boolean slim) {
        BufferedImage out = new BufferedImage(16, 32, BufferedImage.TYPE_INT_ARGB);
        int aw = slim ? 3 : 4;
        int rightArmX = slim ? 1 : 0;
        // base layer, then overlay layer on top of each part
        part(out, skin, 8, 8, 8, 8, 4, 0);   part(out, skin, 40, 8, 8, 8, 4, 0);        // head, hat
        part(out, skin, 20, 20, 8, 12, 4, 8); part(out, skin, 20, 36, 8, 12, 4, 8);     // body, jacket
        part(out, skin, 4, 20, 4, 12, 4, 20); part(out, skin, 4, 36, 4, 12, 4, 20);     // right leg, pants
        part(out, skin, 20, 52, 4, 12, 8, 20); part(out, skin, 4, 52, 4, 12, 8, 20);    // left leg, pants
        part(out, skin, 44, 20, aw, 12, rightArmX, 8); part(out, skin, 44, 36, aw, 12, rightArmX, 8); // right arm, sleeve
        part(out, skin, 36, 52, aw, 12, 12, 8); part(out, skin, 52, 52, aw, 12, 12, 8); // left arm, sleeve
        return out;
    }

    /** Nearest-neighbour upscale so the pixels stay crisp. */
    public static BufferedImage scale(BufferedImage src, int factor) {
        BufferedImage out = new BufferedImage(src.getWidth() * factor, src.getHeight() * factor, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < out.getHeight(); y++) {
            for (int x = 0; x < out.getWidth(); x++) out.setRGB(x, y, src.getRGB(x / factor, y / factor));
        }
        return out;
    }

    private static void part(BufferedImage dst, BufferedImage skin, int sx, int sy, int w, int h, int dx, int dy) {
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int px = skin.getRGB(sx + i, sy + j);
                int a = (px >>> 24) & 0xFF;
                if (a == 0) continue;
                int X = dx + i, Y = dy + j;
                dst.setRGB(X, Y, a == 255 ? px : over(px, dst.getRGB(X, Y)));
            }
        }
    }

    /** Standard source-over alpha compositing of two ARGB pixels. */
    private static int over(int src, int dst) {
        float sa = ((src >>> 24) & 0xFF) / 255f, da = ((dst >>> 24) & 0xFF) / 255f;
        float oa = sa + da * (1 - sa);
        if (oa <= 0) return 0;
        int r = (int) ((((src >> 16) & 0xFF) * sa + ((dst >> 16) & 0xFF) * da * (1 - sa)) / oa);
        int g = (int) ((((src >> 8) & 0xFF) * sa + ((dst >> 8) & 0xFF) * da * (1 - sa)) / oa);
        int b = (int) (((src & 0xFF) * sa + (dst & 0xFF) * da * (1 - sa)) / oa);
        return ((int) (oa * 255) << 24) | (r << 16) | (g << 8) | b;
    }
}
