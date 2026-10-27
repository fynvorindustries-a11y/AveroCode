package com.averolauncher.ui;

import com.averolauncher.model.LauncherSettings;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WritableValue;
import javafx.scene.Node;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The launcher's single motion language. Every animation in the UI goes through here so that
 * (a) they all share one easing and one set of durations, and (b) Low-End Mode / "animations off"
 * is honoured in exactly one place, <b>live</b> (no restart needed).
 *
 * <p>Rules of thumb: animate translate / opacity / scale / clip only (never layout), one easing
 * ({@link #EASE}, ease-out-quint), no bounce.
 */
public final class Motion {

    private Motion() {}

    /** ease-out-quint — the one easing used for almost everything. */
    public static final Interpolator EASE = Interpolator.SPLINE(0.22, 1, 0.36, 1);

    public static final Duration PRESS   = Duration.millis(90);
    public static final Duration HOVER   = Duration.millis(140);
    public static final Duration PANEL   = Duration.millis(240);
    public static final Duration PAGE    = Duration.millis(300);

    /** Vertical travel of page transitions, px. */
    public static final double SLIDE = 12;
    /** Card hover lift, px. */
    public static final double LIFT = 4;
    /** Delay between staggered children, ms. */
    public static final double STAGGER_MS = 35;
    private static final int STAGGER_CAP = 12;

    private static LauncherSettings settings;
    private static final BooleanProperty ENABLED = new SimpleBooleanProperty(true);
    private static final Map<WritableValue<?>, Timeline> RUNNING = new WeakHashMap<>();

    /** Called once at startup. */
    public static void bind(LauncherSettings s) {
        settings = s;
        refresh();
    }

    /** Call after the animation / Low-End settings change so running loops stop or start at once. */
    public static void refresh() {
        ENABLED.set(settings == null || (settings.animationsEnabled && !settings.lowEndMode));
    }

    public static boolean enabled() {
        return ENABLED.get();
    }

    public static ReadOnlyBooleanProperty enabledProperty() {
        return ENABLED;
    }

    /**
     * Animates {@code prop} to {@code to} with {@link #EASE}. Cancels any animation already running
     * on the same property (so rapid hover in/out never fights itself). With animations off the
     * value is simply set.
     */
    public static <T> void animate(WritableValue<T> prop, T to, Duration d) {
        Timeline old = RUNNING.remove(prop);
        if (old != null) old.stop();
        if (!enabled() || d.lessThanOrEqualTo(Duration.ZERO)) {
            prop.setValue(to);
            return;
        }
        Timeline t = new Timeline(new KeyFrame(d, new KeyValue(prop, to, EASE)));
        RUNNING.put(prop, t);
        t.setOnFinished(e -> RUNNING.remove(prop, t));
        t.play();
    }

    /**
     * Fade + slide each node in, one after another ({@link #STAGGER_MS} apart, first 12 only).
     * Returns the running timeline so a caller can cancel it (then call {@link #settle}), or
     * {@code null} when animations are off and everything was simply put in its final state.
     */
    public static Timeline staggerIn(List<? extends Node> nodes, double fromY, Duration each) {
        if (!enabled() || nodes.isEmpty()) {
            settle(nodes);
            return null;
        }
        List<KeyFrame> frames = new ArrayList<>();
        int i = 0;
        for (Node n : nodes) {
            if (i >= STAGGER_CAP) { n.setOpacity(1); n.setTranslateY(0); continue; }
            Duration delay = Duration.millis(i * STAGGER_MS);
            n.setOpacity(0);
            n.setTranslateY(fromY);
            frames.add(new KeyFrame(delay,
                    new KeyValue(n.opacityProperty(), 0),
                    new KeyValue(n.translateYProperty(), fromY)));
            frames.add(new KeyFrame(delay.add(each),
                    new KeyValue(n.opacityProperty(), 1, EASE),
                    new KeyValue(n.translateYProperty(), 0, EASE)));
            i++;
        }
        Timeline t = new Timeline(frames.toArray(new KeyFrame[0]));
        t.play();
        return t;
    }

    /** Puts nodes in their resting state (fully visible, no offset). */
    public static void settle(List<? extends Node> nodes) {
        for (Node n : nodes) { n.setOpacity(1); n.setTranslateY(0); }
    }

    /** Runs {@code r} after {@code d} unless the returned timer is stopped first (hover-intent). */
    public static PauseTransition after(Duration d, Runnable r) {
        PauseTransition p = new PauseTransition(d);
        p.setOnFinished(e -> r.run());
        return p;
    }

    /**
     * Slow opacity breathing, used for the Play button's glow. Runs only while the node is in a
     * scene, visible and animations are on; otherwise the node rests at {@code hi}.
     */
    public static void pulse(Node n, double lo, double hi, Duration half) {
        Timeline t = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(n.opacityProperty(), lo, Interpolator.EASE_BOTH)),
                new KeyFrame(half, new KeyValue(n.opacityProperty(), hi, Interpolator.EASE_BOTH)));
        t.setAutoReverse(true);
        t.setCycleCount(Timeline.INDEFINITE);
        ChangeListener<Boolean> sync = (o, a, b) -> {
            boolean run = enabled() && n.getScene() != null && n.isVisible();
            if (run) { t.play(); } else { t.stop(); n.setOpacity(hi); }
        };
        n.sceneProperty().addListener((o, was, now) -> {
            if (now != null) ENABLED.addListener(sync); else ENABLED.removeListener(sync);
            sync.changed(null, null, null);
        });
        n.visibleProperty().addListener(sync);
        sync.changed(null, null, null);
    }

    /**
     * Very slow back-and-forth drift on X, for ambient light behind the Home hero. Same lifecycle
     * rules as {@link #pulse}: runs only while the node is on screen and animations are on,
     * otherwise it rests at its origin.
     */
    public static void drift(Node n, double dx, Duration half) {
        Timeline t = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(n.translateXProperty(), 0, Interpolator.EASE_BOTH)),
                new KeyFrame(half, new KeyValue(n.translateXProperty(), dx, Interpolator.EASE_BOTH)));
        t.setAutoReverse(true);
        t.setCycleCount(Timeline.INDEFINITE);
        ChangeListener<Boolean> sync = (o, a, b) -> {
            boolean run = enabled() && n.getScene() != null && n.isVisible();
            if (run) { t.play(); } else { t.stop(); n.setTranslateX(0); }
        };
        n.sceneProperty().addListener((o, was, now) -> {
            if (now != null) ENABLED.addListener(sync); else ENABLED.removeListener(sync);
            sync.changed(null, null, null);
        });
        n.visibleProperty().addListener(sync);
        sync.changed(null, null, null);
    }
}
