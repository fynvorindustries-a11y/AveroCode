package com.averolauncher.util;

import com.averolauncher.ui.NavPage;
import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.DoubleBinding;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import javafx.scene.effect.Bloom;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

/**
 * Shared gradient styling for page headers and primary action buttons.
 *
 * Before this, HomePage was the only screen with any gradient/imagery in it —
 * every other page (Play, Versions, Mods, Servers, Accounts, Downloads,
 * Settings, About) was flat single-color panels with no imagery at all. This
 * class carries HomePage's own palette (ACCENT_BLUE / ACCENT_BLUE_D) out to
 * the rest of the app so it reads as one brand:
 *   - {@link #pageBadge} — a small gradient tile with the page's own nav glyph
 *     (reusing {@link IconRegistry}), meant to sit beside every page-title.
 *   - {@link #underline} — the thin gradient accent line HomePage already uses
 *     under its section headers, made reusable.
 *   - {@link #applyPrimaryGradient} — a gradient fill for a page's main action
 *     button (Play, Install, Save, Connect, ...), independent of whatever the
 *     external stylesheet does with that button's style class.
 *   - {@link #heroBanner} — an optional bundled photo/illustration behind a
 *     page header, with the same "never crash, just fall back to the gradient"
 *     contract AppAssets already uses for the logo and HomePage's own banners.
 */
public final class UiAccents {

    public static final String ACCENT_BLUE    = "#ff4655";
    public static final String ACCENT_BLUE_D  = "#bd3944";
    public static final String ACCENT_INK     = "#f5f1ea";
    public static final String ACCENT_ORANGE   = "#ff4655";
    public static final String ACCENT_ORANGE_L = "#ff5d6b";

    private UiAccents() {
    }

    /** Small square accent tile carrying a page's own nav glyph, for use beside a page-title. */
    public static StackPane pageBadge(NavPage page, double size) {
        StackPane badge = new StackPane();
        badge.setPrefSize(size, size);
        badge.setMinSize(size, size);
        badge.setMaxSize(size, size);
        badge.setStyle(
                "-fx-background-color: linear-gradient(to bottom right, " + ACCENT_BLUE_D + ", " + ACCENT_BLUE + ");" +
                "-fx-background-radius: 0;"
        );

        SVGPath icon = IconRegistry.icon(page);
        // IconRegistry's paths are drawn on a 24x24 grid; scale that up/down to fit the badge
        // with a little breathing room, and force the ink color so it reads on the light
        // gradient regardless of whatever ".nav-icon" resolves to in the sidebar's own CSS.
        double scale = (size * 0.52) / 24.0;
        icon.setScaleX(scale);
        icon.setScaleY(scale);
        icon.setStyle("-fx-fill: transparent; -fx-stroke: " + ACCENT_INK + "; -fx-stroke-width: 1.9; -fx-stroke-line-cap: round; -fx-stroke-line-join: round;");
        badge.getChildren().add(icon);
        return badge;
    }

    /** Thin gradient accent line, same treatment as HomePage's own section underlines. */
    public static Region underline(double width) {
        Region line = new Region();
        line.setMaxSize(width, 3);
        line.setMinSize(width, 3);
        line.setStyle(
                "-fx-background-color: linear-gradient(to right, " + ACCENT_BLUE_D + ", " + ACCENT_BLUE + ", #ece8e1);" +
                "-fx-background-radius: 0;"
        );
        return line;
    }

    /**
     * Page-header rule: a short slanted red segment (the Valorant accent) followed by a hairline that
     * runs the full width of the page.
     */
    public static Region pageUnderline() {
        Region red = new Region();
        red.setMinSize(96, 4);
        red.setPrefSize(96, 4);
        red.setMaxSize(96, 4);
        red.setStyle("-fx-background-color: #ff4655;");
        red.setShape(new javafx.scene.shape.Polygon(0, 0, 96, 0, 88, 4, 0, 4));
        red.setScaleShape(false);
        red.setCenterShape(false);

        Region hair = new Region();
        hair.setMinHeight(1);
        hair.setPrefHeight(1);
        hair.setMaxHeight(1);
        hair.setMaxWidth(Double.MAX_VALUE);
        hair.setStyle("-fx-background-color: rgba(236,232,225,0.16);");
        HBox.setHgrow(hair, javafx.scene.layout.Priority.ALWAYS);

        HBox rule = new HBox(red, hair);
        rule.setAlignment(javafx.geometry.Pos.BOTTOM_LEFT);
        rule.setMinHeight(4);
        rule.setMaxWidth(Double.MAX_VALUE);
        return rule;
    }

    /** Applies the brand gradient fill to a page's primary action button (Play/Install/Save/...). */
    public static void applyPrimaryGradient(Button button) {
        String existing = button.getStyle();
        button.setStyle(
                (existing == null ? "" : existing) +
                ";-fx-background-color: linear-gradient(to bottom right, " + ACCENT_ORANGE + ", " + ACCENT_ORANGE_L + ");" +
                "-fx-text-fill: #ffffff;" +
                "-fx-font-weight: bold;" +
                "-fx-background-radius: 0;"
        );
    }

    /** Same treatment, for any other Labeled control (e.g. a status pill) that should pop. */
    public static void applyPrimaryGradient(Labeled labeled) {
        String existing = labeled.getStyle();
        labeled.setStyle(
                (existing == null ? "" : existing) +
                ";-fx-background-color: linear-gradient(to bottom right, " + ACCENT_ORANGE + ", " + ACCENT_ORANGE_L + ");" +
                "-fx-text-fill: #ffffff;" +
                "-fx-font-weight: bold;" +
                "-fx-background-radius: 0;"
        );
    }

    /**
     * Optional bundled banner behind a page header — same graceful-fallback contract as
     * AppAssets.loadLogo(): if {@code /images/<name>} isn't bundled yet, this returns a
     * plain dark gradient panel of the requested height (still on-brand, never blank/broken),
     * and the exact same call will start showing the real artwork the moment the image is
     * dropped into src/main/resources/images/ and the app is rebuilt.
     *
     * The whole banner is clipped to exactly {@code height} px, and any bundled photo is
     * "cover"-fit into it (scaled up just enough to fill the box in both directions,
     * keeping its aspect ratio, then cropped to size) — never stretched out of shape and
     * never allowed to spill past its box onto whatever comes after it on the page.
     */
    public static StackPane heroBanner(String classpathImagePath, double height) {
        StackPane hero = new StackPane();
        hero.setPrefHeight(height);
        hero.setMinHeight(height);
        hero.setMaxHeight(height);
        hero.setStyle(
                "-fx-background-color: linear-gradient(to bottom right, #0f1923 0%, #17232f 40%, #1b2a38 70%, #0f1923 100%);" +
                "-fx-background-radius: 0;"
        );

        // Clip the WHOLE banner — not just the image — to its own rounded bounds. This is
        // what actually stops a bundled photo (whatever its native resolution) from ever
        // rendering past this box and over the cards/sections that follow it on the page.
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(hero.widthProperty());
        clip.heightProperty().bind(hero.heightProperty());
        clip.setArcWidth(0);
        clip.setArcHeight(0);
        hero.setClip(clip);

        Image image = AppAssets.loadImage(classpathImagePath);
        if (image != null && !image.isError() && image.getWidth() > 0 && image.getHeight() > 0) {
            double imgW = image.getWidth();
            double imgH = image.getHeight();

            ImageView view = new ImageView(image);
            view.setSmooth(true);
            // Ratio is preserved manually below via one shared scale factor, so JavaFX's
            // own preserveRatio flag would just fight the binding — leave it off.
            view.setPreserveRatio(false);

            // "Cover" fit, same idea as CSS background-size: cover — scale up just enough
            // to fill both the banner's live width and its fixed height, so the image is
            // never squashed/stretched and never leaves a letterboxed gap. Whichever axis
            // ends up with excess gets cropped by the clip above instead of overflowing.
            DoubleBinding scale = Bindings.createDoubleBinding(
                    () -> Math.max(Math.max(hero.getWidth(), 1) / imgW, height / imgH),
                    hero.widthProperty()
            );
            view.fitWidthProperty().bind(scale.multiply(imgW));
            view.fitHeightProperty().bind(scale.multiply(imgH));
            StackPane.setAlignment(view, Pos.CENTER);

            // Left-to-right scrim so header text overlaid on the banner stays legible
            // regardless of what the source photo looks like.
            Region scrim = new Region();
            scrim.setStyle(
                    "-fx-background-color: linear-gradient(to right, rgba(8,10,14,0.80) 0%, rgba(8,10,14,0.35) 55%, rgba(8,10,14,0.10) 100%);"
            );
            scrim.prefWidthProperty().bind(hero.widthProperty());
            scrim.setPrefHeight(height);

            // Bottom fade so the banner melts into the page instead of ending on a hard edge.
            Region fade = new Region();
            fade.setMouseTransparent(true);
            fade.setStyle("-fx-background-color: linear-gradient(to bottom, rgba(15,25,35,0.0) 0%, rgba(15,25,35,0.0) 40%, rgba(15,25,35,0.92) 100%);");
            fade.prefWidthProperty().bind(hero.widthProperty());
            fade.setPrefHeight(height);

            hero.getChildren().addAll(view, scrim, fade, com.averolauncher.ui.ValDeco.slashes(0.8));
        }
        return hero;
    }
}

