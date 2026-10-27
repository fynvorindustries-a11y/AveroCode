package com.averolauncher.ui;

import javafx.beans.value.ChangeListener;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Polygon;

/**
 * Static decorative geometry in the Valorant manner: slanted light-slashes that cut across banners.
 * Everything here is created once and only re-laid-out when the host is resized — no animation, no
 * effects — so it costs essentially nothing at runtime.
 */
public final class ValDeco {

    private ValDeco() {}

    private static final Color RED = Color.web("#ff4655");

    /**
     * A transparent overlay (mouse-transparent) holding three red parallelograms leaning like "/".
     * Add it as the top child of a StackPane banner. Positions are fractions of the banner width, the
     * lean is a fixed 45&deg; of the banner height so it looks identical on short and tall banners.
     *
     * @param intensity 1.0 = normal; use ~0.6 over busy photos
     */
    public static Pane slashes(double intensity) {
        Pane pane = new Pane();
        pane.setMouseTransparent(true);
        pane.setPickOnBounds(false);

        Polygon wide  = poly(0.07 * intensity);   // big faint wedge
        Polygon mid   = poly(0.20 * intensity);   // medium translucent band
        Polygon sharp = poly(0.95 * intensity);   // thin hot line
        pane.getChildren().addAll(wide, mid, sharp);

        ChangeListener<Number> relayout = (o, a, b) -> {
            double w = pane.getWidth(), h = pane.getHeight();
            if (w <= 0 || h <= 0) return;
            double lean = h;            // 45 degrees
            set(wide,  w * 0.915, 170, h, lean);
            set(mid,   w * 0.845,  64, h, lean);
            set(sharp, w * 0.805,  10, h, lean);
        };
        pane.widthProperty().addListener(relayout);
        pane.heightProperty().addListener(relayout);
        return pane;
    }

    private static Polygon poly(double alpha) {
        Polygon p = new Polygon();
        p.setFill(new Color(RED.getRed(), RED.getGreen(), RED.getBlue(), Math.min(1, alpha)));
        p.setStroke(null);
        p.setMouseTransparent(true);
        return p;
    }

    /** Parallelogram centred on x (at mid-height), {@code width} wide, leaning right at the top. */
    private static void set(Polygon p, double x, double width, double h, double lean) {
        double topL = x - width / 2 + lean / 2, topR = x + width / 2 + lean / 2;
        double botL = x - width / 2 - lean / 2, botR = x + width / 2 - lean / 2;
        p.getPoints().setAll(topL, 0.0, topR, 0.0, botR, h, botL, h);
    }
}
