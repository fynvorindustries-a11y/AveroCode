package com.averolauncher.ui.kit;

import com.averolauncher.ui.Motion;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Pos;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

/**
 * Shimmering placeholder that replaces "Loading..." text. The shimmer is a gradient band moved by
 * {@code translateX} (no layout, no effects). It only runs while the skeleton is on screen and
 * animations are enabled; otherwise it is a plain flat tint.
 */
public final class Skeleton extends StackPane {

    private final Region band = new Region();
    private final DoubleProperty phase = new SimpleDoubleProperty();
    private final Timeline loop = new Timeline(
            new KeyFrame(Duration.ZERO, new KeyValue(phase, 0)),
            new KeyFrame(Duration.millis(1250), new KeyValue(phase, 1, Interpolator.LINEAR)));
    private final ChangeListener<Boolean> sync = (o, a, b) -> sync();

    /** @param w fixed width, or {@code <= 0} to fill the available width */
    public Skeleton(double w, double h) {
        getStyleClass().add("av-skeleton");
        setAlignment(Pos.CENTER_LEFT);
        if (w > 0) { setMinWidth(w); setPrefWidth(w); setMaxWidth(w); }
        setMinHeight(h);
        setPrefHeight(h);
        setMaxHeight(h);

        band.getStyleClass().add("av-skeleton-band");
        band.setMouseTransparent(true);
        getChildren().add(band);

        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);

        loop.setCycleCount(Timeline.INDEFINITE);
        widthProperty().addListener((o, a, b) -> {
            double bw = Math.max(1, b.doubleValue() * 0.5);
            band.setMinWidth(bw);
            band.setPrefWidth(bw);
            band.setMaxWidth(bw);
            place();
        });
        phase.addListener((o, a, b) -> place());
        sceneProperty().addListener((o, was, now) -> {
            if (now != null) Motion.enabledProperty().addListener(sync);
            else Motion.enabledProperty().removeListener(sync);
            sync();
        });
        visibleProperty().addListener(sync);
    }

    private void place() {
        double bw = band.getPrefWidth();
        band.setTranslateX(-bw + phase.get() * (getWidth() + bw));
    }

    private void sync() {
        boolean run = Motion.enabled() && getScene() != null && isVisible();
        band.setVisible(run);
        if (run) loop.play(); else loop.stop();
    }

    public static Skeleton line(double w) {
        return new Skeleton(w, 12);
    }

    public static Skeleton block(double w, double h) {
        return new Skeleton(w, h);
    }

    /** A stack of text-like lines of varied length, for lists and paragraphs. */
    public static VBox lines(int rows) {
        VBox box = new VBox();
        box.getStyleClass().add("gap-8");
        double[] widths = {320, 260, 360, 220, 300};
        for (int i = 0; i < rows; i++) box.getChildren().add(line(widths[i % widths.length]));
        return box;
    }
}
