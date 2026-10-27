package com.averolauncher.ui.kit;

import javafx.scene.layout.Region;
import javafx.scene.shape.Polygon;

/** The signature angled slash ("/") used as a small accent mark in headers and cards. */
public final class Slash {

    private Slash() {}

    /** A solid accent parallelogram {@code w} x {@code h} px, leaning right at the top. */
    public static Region mark(double w, double h) {
        Region r = new Region();
        r.getStyleClass().add("av-slash");
        r.setMinSize(w, h);
        r.setPrefSize(w, h);
        r.setMaxSize(w, h);
        double lean = w * 0.6;
        r.setShape(new Polygon(lean, 0, w, 0, w - lean, h, 0, h));
        r.setScaleShape(true);
        r.setMouseTransparent(true);
        return r;
    }
}
