package com.averolauncher.util;

import com.averolauncher.logging.AppLogger;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * Central place for loading the app's own logo, shared by every screen that shows it
 * (sidebar header, sidebar footer, About page). Every other bundled image (profile
 * banner, news thumbnails) is per-page because only one page ever shows them; the logo
 * is different — it appears in several places, so it gets one shared, cached loader
 * instead of three copies of the same lookup.
 *
 * Two locations are checked, in order:
 *   1. The bundled classpath resource /images/logo.png — the normal path once the file
 *      has been dropped into src/main/resources/images and the app (re)built. This is
 *      what a packaged/shipped build will use.
 *   2. A plain "logo.png" file sitting next to the project (the working directory the
 *      app was launched from, or src/main/resources/images/ directly), so the logo
 *      shows up immediately while developing — even from an IDE run configuration or
 *      "gradle run" — without needing a full rebuild first.
 *
 * Like the rest of the app's image handling, this never throws and never crashes on a
 * missing or invalid file: if neither location has a usable image, callers get null back
 * and are expected to fall back to their existing styled placeholder (the same contract
 * HomePage's loadClasspathImage/applyBannerImage already follow).
 */
public final class AppAssets {

    private static final String TAG = "AppAssets";
    private static final String CLASSPATH_LOGO = "/images/logo.png";
    // Bundled app icon (the icy "AL"). Used when no dedicated logo.png has been dropped in,
    // so the sidebar / footer / About page show the real brand mark instead of a lettered tile.
    private static final String CLASSPATH_ICON_FALLBACK = "/icons/app-icon.png";
    private static final String[] ROOT_LOGO_CANDIDATES = {
            "logo.png",
            "./logo.png",
            "src/main/resources/images/logo.png",
            "../logo.png"
    };

    // Loaded once and reused everywhere it's needed — the sidebar, footer and About
    // page all ask for this during the same startup, so there's no reason to hit the
    // filesystem/classpath three separate times for the same file.
    private static Image cachedLogo;
    private static boolean cacheLoaded = false;

    private AppAssets() {
    }

    /** Returns the app logo, or null if logo.png isn't available anywhere yet. */
    public static synchronized Image loadLogo() {
        if (cacheLoaded) {
            return cachedLogo;
        }
        cacheLoaded = true;

        cachedLogo = loadFromClasspath(CLASSPATH_LOGO);
        if (cachedLogo == null) {
            cachedLogo = loadFromProjectRoot();
        }
        if (cachedLogo == null) {
            // The icon is 1254x1254; the largest spot that shows it is ~72px, so decode it
            // down to 256px once (smooth, ratio kept) instead of holding the full bitmap.
            cachedLogo = loadScaledFromClasspath(CLASSPATH_ICON_FALLBACK, 256);
        }
        if (cachedLogo == null) {
            AppLogger.get().debug(TAG, "No logo.png found (checked classpath:/images/logo.png and project root) — using styled placeholder marks");
        }
        return cachedLogo;
    }

    /**
     * Generic loader for any bundled page banner/illustration under classpath
     * {@code /images/...} (news thumbnails, page hero art, etc). Unlike
     * {@link #loadLogo()} there is no project-root fallback and nothing is cached —
     * each page owns its own image and only asks for it once while building.
     * Never throws: a missing or invalid file simply returns null so the caller
     * falls back to its existing gradient panel, exactly like HomePage's banners do.
     */
    public static Image loadImage(String classpathResourcePath) {
        return loadFromClasspath(classpathResourcePath);
    }

    private static Image loadScaledFromClasspath(String resourcePath, double maxSize) {
        try (InputStream is = AppAssets.class.getResourceAsStream(resourcePath)) {
            if (is == null) return null;
            Image image = new Image(is, maxSize, maxSize, true, true);
            return image.isError() ? null : image;
        } catch (Exception e) {
            return null;
        }
    }

    private static Image loadFromClasspath(String resourcePath) {
        try (InputStream is = AppAssets.class.getResourceAsStream(resourcePath)) {
            if (is == null) return null;
            Image image = new Image(is);
            return image.isError() ? null : image;
        } catch (Exception e) {
            return null;
        }
    }

    private static Image loadFromProjectRoot() {
        for (String candidate : ROOT_LOGO_CANDIDATES) {
            File file = new File(candidate);
            if (!file.isFile()) continue;
            try (InputStream is = new FileInputStream(file)) {
                Image image = new Image(is);
                if (!image.isError()) {
                    return image;
                }
            } catch (Exception ignored) {
                // try the next candidate
            }
        }
        return null;
    }

    /**
     * Builds a "brand mark" node for a spot that currently shows a lettered placeholder
     * tile (the sidebar's top mark, its footer tile, the About page hero mark, or any
     * future spot like them): the real logo when logo.png is available, or the existing
     * styled placeholder — completely unchanged — when it isn't.
     *
     * @param styleClass    the CSS class the placeholder Label already uses (e.g.
     *                      "footer-mark"). Reused on the image container too, so any
     *                      fixed sizing/radius/background the class defines still
     *                      applies — only the gradient fill and glow are dropped, since
     *                      those were dressing for the letter, not a real image.
     * @param fallbackGlyph the letter/symbol shown when no logo image is found.
     * @param size          the image's rendered width and height, in pixels.
     */
    public static Node buildLogoMark(String styleClass, String fallbackGlyph, double size) {
        Image logo = loadLogo();
        if (logo == null) {
            Label fallback = new Label(fallbackGlyph);
            fallback.getStyleClass().add(styleClass);
            return fallback;
        }

        StackPane box = new StackPane();
        box.getStyleClass().add(styleClass);
        box.setStyle("-fx-background-color: transparent;");

        ImageView view = new ImageView(logo);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        view.setFitWidth(size);
        view.setFitHeight(size);

        Rectangle clip = new Rectangle(size, size);
        double arc = 0;
        clip.setArcWidth(arc);
        clip.setArcHeight(arc);
        view.setClip(clip);

        box.getChildren().add(view);
        return box;
    }
}
