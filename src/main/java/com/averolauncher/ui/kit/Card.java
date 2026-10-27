package com.averolauncher.ui.kit;

import com.averolauncher.ui.Motion;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Polygon;

/**
 * The shared card. Two layers on purpose: an invisible <i>host</i> that owns hover detection, and
 * the visible <i>face</i> (chamfered, outlined) that lifts. Lifting the face rather than the host
 * keeps the hit area still, so the card can't flicker when the cursor sits on its bottom edge.
 *
 * <p>Interactive cards ({@link #interactive()}) lift 4 px, brighten their border and show a small
 * accent corner notch on hover. Static cards are just surfaces.
 */
public final class Card extends StackPane {

    private static final double NOTCH = 16;

    private final Region notch = new Region();
    private final StackPane face = new StackPane() {
        @Override
        protected void layoutChildren() {
            super.layoutChildren();
            notch.resizeRelocate(getWidth() - NOTCH, 0, NOTCH, NOTCH);
        }
    };
    private boolean interactive;

    public Card() {
        getStyleClass().add("av-card-host");
        face.getStyleClass().add("av-card");
        face.setAlignment(Pos.TOP_LEFT);

        notch.getStyleClass().add("av-card-notch");
        notch.setShape(new Polygon(0, 0, NOTCH, 0, NOTCH, NOTCH));
        notch.setScaleShape(true);
        notch.setManaged(false);
        notch.setMouseTransparent(true);
        notch.setOpacity(0);
        face.getChildren().add(notch);

        getChildren().add(face);
    }

    public Card(Node content) {
        this();
        setContent(content);
    }

    public Card setContent(Node content) {
        face.getChildren().removeIf(c -> c != notch);
        if (content instanceof Region r) r.setMaxWidth(Double.MAX_VALUE);
        face.getChildren().add(0, content);
        return this;
    }

    /** Hover lift + notch + brighter border. Idempotent. */
    public Card interactive() {
        if (interactive) return this;
        interactive = true;
        face.getStyleClass().add("av-card-hot");
        hoverProperty().addListener((o, was, hovering) -> {
            Motion.animate(face.translateYProperty(), hovering ? -Motion.LIFT : 0.0, Motion.HOVER);
            Motion.animate(notch.opacityProperty(), hovering ? 1.0 : 0.0, Motion.HOVER);
        });
        return this;
    }

    /** Marks the card as the current/selected one (accent outline). */
    public Card setActive(boolean active) {
        if (active) {
            if (!face.getStyleClass().contains("av-card-active")) face.getStyleClass().add("av-card-active");
        } else {
            face.getStyleClass().remove("av-card-active");
        }
        return this;
    }

    public StackPane getFace() {
        return face;
    }
}
