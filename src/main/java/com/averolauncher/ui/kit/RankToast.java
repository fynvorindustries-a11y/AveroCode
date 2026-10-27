package com.averolauncher.ui.kit;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/** Small transient notice (promotion, quest completed). Never intercepts the mouse; one at a time. */
public final class RankToast extends HBox {

    private final Region bar = new Region();
    private final Label title = new Label();
    private final Label sub = new Label();
    private SequentialTransition running;

    public RankToast() {
        getStyleClass().add("rank-toast");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(14);
        setMouseTransparent(true);
        setPickOnBounds(false);
        setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);
        bar.setMinWidth(4);
        bar.setPrefWidth(4);
        title.getStyleClass().add("rank-toast-title");
        sub.getStyleClass().add("rank-toast-sub");
        getChildren().addAll(bar, new VBox(2, title, sub));
        setOpacity(0);
        setVisible(false);
    }

    /** FX thread only. */
    public void show(String heading, String detail, String accentHex) {
        title.setText(heading);
        sub.setText(detail);
        sub.setManaged(detail != null && !detail.isBlank());
        sub.setVisible(detail != null && !detail.isBlank());
        bar.setStyle("-fx-background-color: " + accentHex + ";");
        if (running != null) running.stop();
        setVisible(true);
        FadeTransition in = new FadeTransition(Duration.millis(180), this);
        in.setToValue(1);
        PauseTransition hold = new PauseTransition(Duration.seconds(5));
        FadeTransition out = new FadeTransition(Duration.millis(320), this);
        out.setToValue(0);
        running = new SequentialTransition(in, hold, out);
        running.setOnFinished(e -> setVisible(false));
        running.play();
    }
}
