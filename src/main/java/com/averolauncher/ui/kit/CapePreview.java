package com.averolauncher.ui.kit;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelReader;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Polygon;

/**
 * A cape shown large on an opaque, chamfered "display" tile. Used for the equipped cape on Home and
 * the featured preview in the Store.
 *
 * <p>The cape is drawn as a cape rather than a picture in a frame: it fills most of the tile, has a
 * tapered shoulder line, a darker collar band, a light rim on the left and shading toward the hem,
 * and casts a soft shadow. Behind it sits a glow tinted with the cape's own average colour.
 *
 * <p>Given a texture URL it loads the image in the background and crops out the cape's outer face
 * (Minecraft cape textures are 64x32 or 22x17 with the 10x16 face at 1,1), scaled with nearest-
 * neighbour so pixels stay crisp; any other image is shown whole. While loading it shows a
 * {@link Skeleton}; with no texture (or on failure) it shows a tinted tile with the cape's initial.
 * All shading is static CSS/fills: nothing here animates. Display only; no cape logic.
 */
public final class CapePreview extends StackPane {

    private static final Color[] TONES = {
            Color.web("#ff4655"), Color.web("#8fd0ff"), Color.web("#9672eb"), Color.web("#5ec97a")
    };

    private final Region glow = new Region();
    private final Label glyph = new Label();
    private final ImageView face = new ImageView();
    private final StackPane capeBox = new StackPane();   // clipped to the cape silhouette
    private final StackPane capeStage = new StackPane(); // carries the drop shadow (follows the silhouette)
    private final Polygon silhouette = new Polygon();
    private final Skeleton skeleton;
    private final double tileH;
    private int ticket;

    public CapePreview(double w, double h) {
        this.tileH = h;
        getStyleClass().add("av-cape-preview");
        setMinSize(w, h);
        setPrefSize(w, h);
        setMaxSize(w, h);
        setAlignment(Pos.CENTER);

        glow.setMouseTransparent(true);
        glyph.getStyleClass().add("av-cape-glyph");

        face.setPreserveRatio(true);
        face.setSmooth(false); // crisp pixel art
        face.setFitHeight(h * 0.80);

        Region shade = new Region();
        shade.getStyleClass().add("av-cape-shade");
        shade.setMouseTransparent(true);
        Region collar = new Region();
        collar.getStyleClass().add("av-cape-collar");
        collar.setMouseTransparent(true);
        collar.prefHeightProperty().bind(capeBox.heightProperty().multiply(0.07));
        collar.maxHeightProperty().bind(capeBox.heightProperty().multiply(0.07));
        StackPane.setAlignment(collar, Pos.TOP_CENTER);

        capeBox.getChildren().addAll(face, shade, collar);
        capeBox.setClip(silhouette);
        capeBox.layoutBoundsProperty().addListener((o, was, b) -> {
            double bw = b.getWidth(), bh = b.getHeight(), t = bw * 0.12;
            silhouette.getPoints().setAll(
                    t, 0.0, bw - t, 0.0, bw, t * 1.1, bw, bh, 0.0, bh, 0.0, t * 1.1);
        });

        capeStage.getStyleClass().add("av-cape-stage");
        capeStage.getChildren().add(capeBox);
        capeStage.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        capeStage.setVisible(false);

        skeleton = new Skeleton(w, h);
        skeleton.setVisible(false);

        setGlow(Color.web("#8fd0ff"), 0.14);
        getChildren().addAll(glow, glyph, capeStage, skeleton);
    }

    public void showLoading() {
        ticket++;
        capeStage.setVisible(false);
        glyph.setVisible(false);
        skeleton.setVisible(true);
    }

    /** No cape: a quiet tile with a short hint instead of a picture. */
    public void showEmpty(String hint) {
        ticket++;
        skeleton.setVisible(false);
        capeStage.setVisible(false);
        setGlow(Color.web("#8fd0ff"), 0.14);
        glyph.getStyleClass().remove("av-cape-glyph-initial");
        glyph.setText(hint == null ? "" : hint.toUpperCase(java.util.Locale.ROOT));
        glyph.setVisible(true);
    }

    public void showCape(String id, String name, String textureUrl) {
        final int mine = ++ticket;
        skeleton.setVisible(false);
        setGlow(TONES[id == null ? 0 : Math.abs(id.hashCode()) % TONES.length], 0.34);
        String initial = (name == null || name.isBlank()) ? "?" : name.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
        glyph.setText(initial);
        if (!glyph.getStyleClass().contains("av-cape-glyph-initial")) glyph.getStyleClass().add("av-cape-glyph-initial");
        glyph.setVisible(true);
        capeStage.setVisible(false);

        if (textureUrl == null || textureUrl.isBlank()) return;
        try {
            Image img = new Image(textureUrl, true); // background loading
            Runnable apply = () -> {
                if (mine != ticket || img.isError() || img.getWidth() <= 0) return;
                applyTexture(img);
            };
            if (img.getProgress() >= 1.0) apply.run();
            else img.progressProperty().addListener((o, was, now) -> { if (now.doubleValue() >= 1.0) apply.run(); });
        } catch (Exception ignored) {
            // keep the tinted-initial fallback
        }
    }

    private void applyTexture(Image img) {
        double w = img.getWidth(), ih = img.getHeight();
        double ratio = w / ih;
        Rectangle2D view = null;
        if (Math.abs(ratio - 2.0) < 0.05) {                 // 64x32 (or HD multiples)
            double s = w / 64.0;
            view = new Rectangle2D(1 * s, 1 * s, 10 * s, 16 * s);
        } else if (Math.abs(ratio - 22.0 / 17.0) < 0.05) { // legacy 22x17
            double s = w / 22.0;
            view = new Rectangle2D(1 * s, 1 * s, 10 * s, 16 * s);
        }
        face.setImage(img);
        face.setViewport(view);
        Color avg = average(img, view);
        if (avg != null) setGlow(avg, 0.46);
        capeStage.setVisible(true);
        glyph.setVisible(false);
    }

    /** Glow behind the cape: a soft radial wash of {@code c}, fading to nothing at the edges. */
    private void setGlow(Color c, double alpha) {
        glow.setBackground(new Background(new BackgroundFill(
                new RadialGradient(0, 0, 0.5, 0.42, 0.78, true, CycleMethod.NO_CYCLE,
                        new Stop(0, Color.color(c.getRed(), c.getGreen(), c.getBlue(), alpha)),
                        new Stop(1, Color.TRANSPARENT)),
                CornerRadii.EMPTY, Insets.EMPTY)));
    }

    /** Average colour of the visible cape, weighted toward saturated pixels so the glow isn't muddy grey. */
    private static Color average(Image img, Rectangle2D view) {
        PixelReader pr = img.getPixelReader();
        if (pr == null) return null;
        double x0 = view != null ? view.getMinX() : 0, y0 = view != null ? view.getMinY() : 0;
        double w = view != null ? view.getWidth() : img.getWidth(), h = view != null ? view.getHeight() : img.getHeight();
        double r = 0, g = 0, b = 0, sum = 0;
        for (int i = 0; i < 12; i++) {
            for (int j = 0; j < 12; j++) {
                int x = (int) Math.min(img.getWidth() - 1, x0 + (i + 0.5) * w / 12);
                int y = (int) Math.min(img.getHeight() - 1, y0 + (j + 0.5) * h / 12);
                Color p = pr.getColor(x, y);
                if (p.getOpacity() < 0.5) continue;
                double wt = 0.2 + p.getSaturation();
                r += p.getRed() * wt; g += p.getGreen() * wt; b += p.getBlue() * wt; sum += wt;
            }
        }
        if (sum == 0) return null;
        Color avg = Color.color(r / sum, g / sum, b / sum);
        return Color.hsb(avg.getHue(), Math.min(1, avg.getSaturation() * 1.15), Math.max(avg.getBrightness(), 0.75));
    }
}
