package com.averolauncher.util;

import com.averolauncher.ui.NavPage;
import javafx.scene.shape.SVGPath;

/**
 * Small, consistent line-icon set (24x24 grid, stroke-based) for the sidebar
 * nav and a couple of chrome spots (search field). Replaces the old mix of
 * unicode glyphs (\u2318 for Versions, \u263A for Accounts, \u2699 reused for
 * both Mods and Settings) with one deliberate, on-brand family: the same
 * stroke weight and corner language everywhere, and a couple of shapes —
 * the four-square "blocks" glyph for Mods in particular — chosen to nod at
 * Minecraft itself rather than being generic dashboard icons.
 *
 * Fill/stroke color is left to CSS ({@code .nav-icon} and its
 * {@code :selected}/hover descendant selectors in dark-theme.css) so the
 * same path recolors with the button state instead of being baked in here.
 */
public final class IconRegistry {

    private IconRegistry() {}

    public static SVGPath icon(NavPage page) {
        SVGPath svg = build(pathFor(page));
        if (page == NavPage.PLAY) {
            // The play triangle reads more clearly solid than as a thin outline at
            // sidebar size — every other glyph in the set stays a stroked line icon.
            svg.getStyleClass().add("nav-icon-fill");
        }
        return svg;
    }

    /** Small magnifying-glass glyph used inside the top-bar search field. */
    public static SVGPath search() {
        return build("M11 4 A7 7 0 1 0 11.01 4 Z M16.2 16.2 L21 21");
    }

    private static SVGPath build(String path) {
        SVGPath svg = new SVGPath();
        svg.setContent(path);
        svg.getStyleClass().add("nav-icon");
        return svg;
    }

    private static String pathFor(NavPage page) {
        switch (page) {
            case HOME:
                // Roof + walls + a doorway notch open at the bottom.
                return "M4 12 L12 5 L20 12 M6.5 10.3 V19 H17.5 V10.3 M10 19 V14 H14 V19";
            case PLAY:
                return "M8 5 L19 12 L8 19 Z";
            case MODS:
                // Four blocks in a 2x2 grid — a nod at Minecraft's own building block,
                // and visually distinct from the Settings glyph below (previously both
                // reused the same gear).
                return "M4 4 H10 V10 H4 Z M14 4 H20 V10 H14 Z M4 14 H10 V20 H4 Z M14 14 H20 V20 H14 Z";
            case SERVERS:
                return "M4 5 H20 V8 H4 Z M4 11 H20 V14 H4 Z M4 17 H20 V20 H4 Z";
            case STORE:
                // Shopping bag: body + handle.
                return "M5.5 8 H18.5 L19.5 20 H4.5 Z M9 8 V6.5 A3 3 0 0 1 15 6.5 V8";
            case RANKS:
                // Hexagonal emblem with a diamond inside: the rank badge silhouette.
                return "M12 3 L19.5 7.5 V16.5 L12 21 L4.5 16.5 V7.5 Z M12 8 L15.2 12 L12 16 L8.8 12 Z";
            case ACCOUNTS:
                return "M12 12 A4 4 0 1 0 12.01 12 Z M5 20 C5 15.2 8 13 12 13 C16 13 19 15.2 19 20";
            case SETTINGS:
                // A three-band equalizer/slider glyph, deliberately different from Mods'
                // block grid even though both used to render as the same gear icon.
                return "M4 6 H20 M8 6 A2 2 0 1 0 8.01 6 M4 12 H20 M16 12 A2 2 0 1 0 16.01 12 "
                        + "M4 18 H20 M11 18 A2 2 0 1 0 11.01 18";
            case ABOUT:
                return "M12 21 A9 9 0 1 1 12.01 21 Z M12 11 V16.2 M12 7.6 L12 7.61";
            default:
                return "M4 4 H20 V20 H4 Z";
        }
    }
}
