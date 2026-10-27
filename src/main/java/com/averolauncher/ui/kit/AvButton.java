package com.averolauncher.ui.kit;

import com.averolauncher.ui.Motion;
import javafx.scene.control.Button;
import javafx.scene.control.Skin;
import javafx.scene.control.skin.ButtonSkin;
import javafx.scene.layout.Pane;
import javafx.scene.shape.Polygon;

/**
 * The launcher's button. A drop-in {@link Button} (same {@code setOnAction}, {@code setDisable}...)
 * that adds, with no inline styling:
 * <ul>
 *   <li>an angled light sheen that sweeps across on hover (primary and play kinds),</li>
 *   <li>a 0.98 press scale,</li>
 *   <li>the shared look from {@code components.css} ({@code av-btn-primary|ghost|play}).</li>
 * </ul>
 * All motion goes through {@link Motion}, so Low-End Mode turns it off live.
 */
public class AvButton extends Button {

    public enum Kind { PRIMARY, GHOST, PLAY }

    private final Kind kind;

    public AvButton(String text) {
        this(text, Kind.GHOST);
    }

    public AvButton(String text, Kind kind) {
        super(text);
        this.kind = kind;
        getStyleClass().add("av-btn");
        getStyleClass().add(switch (kind) {
            case PRIMARY -> "av-btn-primary";
            case PLAY -> "av-btn-play";
            case GHOST -> "av-btn-ghost";
        });
        armedProperty().addListener((o, was, now) -> {
            double s = now ? 0.98 : 1.0;
            Motion.animate(scaleXProperty(), s, Motion.PRESS);
            Motion.animate(scaleYProperty(), s, Motion.PRESS);
        });
    }

    public Kind kind() {
        return kind;
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new SheenSkin(this, kind != Kind.GHOST);
    }

    /** ButtonSkin plus a clipped, mouse-transparent layer holding the sweeping sheen band. */
    private static final class SheenSkin extends ButtonSkin {
        private static final double BAND_W = 34;

        private final Pane layer = new Pane();
        private final Polygon band = new Polygon();
        private final Polygon clip = new Polygon();
        private final boolean enabledKind;
        private double lastW = -1, lastH = -1;

        SheenSkin(AvButton b, boolean enabledKind) {
            super(b);
            this.enabledKind = enabledKind;
            band.getStyleClass().add("av-btn-sheen");
            layer.getChildren().add(band);
            layer.setMouseTransparent(true);
            layer.setManaged(false);
            layer.setClip(clip);
            layer.setVisible(false);
            getChildren().add(layer);

            if (enabledKind) {
                b.hoverProperty().addListener((o, was, now) -> {
                    if (now && !b.isDisabled() && Motion.enabled()) sweep(b);
                    else if (!now) layer.setVisible(false);
                });
            }
        }

        private void sweep(AvButton b) {
            double h = b.getHeight();
            double skew = h * 0.5;
            layer.setVisible(true);
            band.setTranslateX(-BAND_W - skew);
            Motion.animate(band.translateXProperty(), b.getWidth() + skew, javafx.util.Duration.millis(320));
        }

        @Override
        protected void layoutChildren(double x, double y, double w, double h) {
            super.layoutChildren(x, y, w, h);
            if (!enabledKind) return;
            double bw = getSkinnable().getWidth(), bh = getSkinnable().getHeight();
            layer.resizeRelocate(0, 0, bw, bh);
            if (bw == lastW && bh == lastH) return;
            lastW = bw;
            lastH = bh;
            double skew = bh * 0.5;
            band.getPoints().setAll(skew, 0.0, skew + BAND_W, 0.0, BAND_W, bh, 0.0, bh);
            Object cutProp = getSkinnable().getProperties().get("valshape.cut");
            double c = Math.min(cutProp instanceof Double d ? d : 0.0, Math.min(bw, bh) / 2);
            clip.getPoints().setAll(c, 0.0, bw, 0.0, bw, bh - c, bw - c, bh, 0.0, bh, 0.0, c);
        }
    }
}
