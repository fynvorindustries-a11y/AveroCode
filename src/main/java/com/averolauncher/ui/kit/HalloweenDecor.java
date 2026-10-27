package com.averolauncher.ui.kit;

import com.averolauncher.logging.AppLogger;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import java.time.LocalDate;
import java.time.Month;

/**
 * Seasonal decoration: the pumpkin trio that sits in the bottom-right corner of the launcher around
 * Halloween. It only appears inside {@link #isSeason}, so it disappears on its own after the holiday.
 * Purely visual: it never takes clicks and never moves.
 */
public final class HalloweenDecor {

    private static final String TAG = "HalloweenDecor";
    private static final String RESOURCE = "/images/halloween-pumpkins.png";
    /** Width on screen in logical pixels (the file itself is about twice that for sharp high-DPI screens). */
    private static final double WIDTH = 240;

    private HalloweenDecor() { }

    /** October 15 through November 2. */
    public static boolean isSeason(LocalDate day) {
        if (day == null) return false;
        Month m = day.getMonth();
        return (m == Month.OCTOBER && day.getDayOfMonth() >= 15) || (m == Month.NOVEMBER && day.getDayOfMonth() <= 2);
    }

    /** The decoration, or null outside the season (or if the image is missing). */
    public static ImageView create() {
        return create(LocalDate.now());
    }

    static ImageView create(LocalDate today) {
        if (!isSeason(today)) return null;
        var url = HalloweenDecor.class.getResource(RESOURCE);
        if (url == null) {
            AppLogger.get().warn(TAG, "Halloween image not found: " + RESOURCE);
            return null;
        }
        ImageView view = new ImageView(new Image(url.toExternalForm(), WIDTH * 2, 0, true, true));
        view.setFitWidth(WIDTH);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        view.setMouseTransparent(true);   // never blocks a button underneath it
        view.setFocusTraversable(false);
        view.getStyleClass().add("av-halloween-decor");
        return view;
    }
}
