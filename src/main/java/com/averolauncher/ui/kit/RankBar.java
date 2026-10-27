package com.averolauncher.ui.kit;

import javafx.geometry.Pos;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

/** A flat progress bar tinted with a rank accent (the shared progress bar is fixed to the launcher's red). */
public final class RankBar extends StackPane {

    private final Region fill = new Region();

    public RankBar(double fraction, String accentHex, boolean thin) {
        getStyleClass().add("rank-bar-track");
        if (thin) getStyleClass().add("rank-bar-track-thin");
        setAlignment(Pos.CENTER_LEFT);
        setMaxWidth(Double.MAX_VALUE);
        double f = Math.max(0, Math.min(1, fraction));
        fill.setStyle("-fx-background-color: linear-gradient(to right, " + accentHex + "aa, " + accentHex + ");");
        fill.setMaxHeight(Double.MAX_VALUE);
        fill.setManaged(false);
        getChildren().add(fill);
        widthProperty().addListener((o, a, w) -> layoutFill(f));
        heightProperty().addListener((o, a, h) -> layoutFill(f));
        layoutFill(f);
    }

    private void layoutFill(double f) {
        fill.resizeRelocate(0, 0, getWidth() * f, getHeight());
    }
}
