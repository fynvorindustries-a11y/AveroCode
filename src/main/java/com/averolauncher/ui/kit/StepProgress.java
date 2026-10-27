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
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Scale;
import javafx.util.Duration;

/**
 * Progress bar with a clear current-step label ("Downloading libraries", "Verifying files"...).
 * The fill is a full-width region scaled on X (no relayout per frame) and eases to each new value,
 * so jumpy download callbacks look smooth. Indeterminate mode is a sliding segment. With animations
 * off the fill snaps and the indeterminate segment rests in the middle.
 */
public final class StepProgress extends VBox {

    private final Label step = new Label();
    private final Label pct = new Label();
    private final StackPane track = new StackPane();
    private final Region fill = new Region();
    private final Region sweep = new Region();
    private final Scale fillScale = new Scale(0, 1, 0, 0);
    private final DoubleProperty phase = new SimpleDoubleProperty(0.5);
    private final Timeline loop = new Timeline(
            new KeyFrame(Duration.ZERO, new KeyValue(phase, 0)),
            new KeyFrame(Duration.millis(1100), new KeyValue(phase, 1, Interpolator.LINEAR)));
    private final ChangeListener<Boolean> sync = (o, a, b) -> sync();
    private boolean indeterminate;

    public StepProgress() {
        getStyleClass().add("gap-8");

        step.getStyleClass().add("av-progress-step");
        pct.getStyleClass().add("av-progress-pct");
        Region spring = new Region();
        HBox.setHgrow(spring, Priority.ALWAYS);
        HBox row = new HBox(8, step, spring, pct);
        row.setAlignment(Pos.BASELINE_LEFT);

        track.getStyleClass().add("av-progress-track");
        track.setAlignment(Pos.CENTER_LEFT);
        fill.getStyleClass().add("av-progress-fill");
        fill.getTransforms().add(fillScale);
        sweep.getStyleClass().add("av-progress-fill");
        sweep.setVisible(false);
        track.getChildren().addAll(fill, sweep);

        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(track.widthProperty());
        clip.heightProperty().bind(track.heightProperty());
        track.setClip(clip);

        track.widthProperty().addListener((o, a, b) -> {
            double sw = Math.max(1, b.doubleValue() * 0.3);
            sweep.setMinWidth(sw);
            sweep.setPrefWidth(sw);
            sweep.setMaxWidth(sw);
            place();
        });
        phase.addListener((o, a, b) -> place());
        loop.setCycleCount(Timeline.INDEFINITE);
        sceneProperty().addListener((o, was, now) -> {
            if (now != null) Motion.enabledProperty().addListener(sync);
            else Motion.enabledProperty().removeListener(sync);
            sync();
        });

        getChildren().addAll(row, track);
    }

    private void place() {
        double sw = sweep.getPrefWidth();
        sweep.setTranslateX(-sw + phase.get() * (track.getWidth() + sw));
    }

    private void sync() {
        boolean run = indeterminate && Motion.enabled() && getScene() != null;
        sweep.setVisible(indeterminate);
        if (run) {
            loop.play();
        } else {
            loop.stop();
            phase.set(0.5);
        }
    }

    public StepProgress setStep(String text) {
        step.setText(text == null ? "" : text);
        return this;
    }

    /** @param p 0..1 */
    public StepProgress setProgress(double p) {
        double v = Math.max(0, Math.min(1, p));
        indeterminate = false;
        sync();
        Motion.animate(fillScale.xProperty(), v, Motion.PANEL);
        pct.setText(Math.round(v * 100) + "%");
        return this;
    }

    public StepProgress setIndeterminate(boolean on) {
        indeterminate = on;
        if (on) {
            Motion.animate(fillScale.xProperty(), 0.0, Motion.HOVER);
            pct.setText("");
        }
        sync();
        return this;
    }
}
