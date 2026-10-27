package com.averolauncher.ui.kit;

import com.averolauncher.ui.Motion;
import com.averolauncher.ui.NavPage;
import com.averolauncher.ui.pages.LeaderboardPanel;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.function.Consumer;

/**
 * The live leaderboard as a "semipage": a half-width drawer that slides in from the right over
 * whatever page is showing, with a scrim behind it. Built once (like {@link RankUpOverlay}) and
 * reused for every open; {@link #open()} rebuilds the panel fresh so standings are always current.
 * The scrim, the close button or Escape all dismiss it.
 */
public final class LeaderboardOverlay extends StackPane {

    private static final double PANEL_W = 420;

    private final Region scrim = new Region();
    private final VBox panel = new VBox();
    private final Consumer<NavPage> navigator;
    private boolean openNow;

    public LeaderboardOverlay(Consumer<NavPage> navigator) {
        this.navigator = navigator;
        getStyleClass().add("lb-overlay");
        setVisible(false);
        setManaged(false);
        setPickOnBounds(false);
        setFocusTraversable(true);

        scrim.getStyleClass().add("av-scrim");
        scrim.setOpacity(0);
        scrim.setMouseTransparent(true);
        scrim.setOnMousePressed(e -> close());

        panel.getStyleClass().add("lb-overlay-panel");
        panel.setMinWidth(PANEL_W);
        panel.setPrefWidth(PANEL_W);
        panel.setMaxWidth(PANEL_W);
        panel.setPickOnBounds(true);
        StackPane.setAlignment(panel, Pos.CENTER_RIGHT);
        panel.setTranslateX(PANEL_W + 40);

        getChildren().addAll(scrim, panel);
        setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) { close(); e.consume(); }
        });
    }

    public boolean isOpenNow() {
        return openNow;
    }

    /** Slides the drawer in, rebuilding the leaderboard list so it always shows current standings. */
    public void open() {
        if (openNow) return;
        openNow = true;
        setVisible(true);
        setManaged(true);
        setPickOnBounds(true);
        scrim.setMouseTransparent(false);
        rebuild();
        requestFocus();
        boolean animated = Motion.enabled();
        Motion.animate(scrim.opacityProperty(), 1.0, Duration.millis(animated ? 200 : 0));
        Motion.animate(panel.translateXProperty(), 0.0, Duration.millis(animated ? 260 : 0));
    }

    /** Slides the drawer back out and discards its content (so it stops polling the server). */
    public void close() {
        if (!openNow) return;
        openNow = false;
        boolean animated = Motion.enabled();
        scrim.setMouseTransparent(true);
        Motion.animate(scrim.opacityProperty(), 0.0, Duration.millis(animated ? 180 : 0));
        Motion.animate(panel.translateXProperty(), PANEL_W + 40, Duration.millis(animated ? 200 : 0));
        Motion.after(Duration.millis(animated ? 210 : 0), () -> {
            setVisible(false);
            setManaged(false);
            panel.getChildren().clear();
        }).play();
    }

    public void toggle() {
        if (openNow) close(); else open();
    }

    private void rebuild() {
        panel.getChildren().clear();

        Label title = new Label("LEADERBOARD");
        title.getStyleClass().add("av-section-title");
        Region spring = new Region();
        HBox.setHgrow(spring, Priority.ALWAYS);
        Button closeBtn = new Button("\u2715");
        closeBtn.getStyleClass().add("lb-overlay-close");
        closeBtn.setOnAction(e -> close());
        HBox header = new HBox(10, title, spring, closeBtn);
        header.getStyleClass().add("lb-overlay-header");
        header.setAlignment(Pos.CENTER_LEFT);

        LeaderboardPanel live = new LeaderboardPanel(navigator, 0);
        VBox.setVgrow(live, Priority.ALWAYS);
        VBox.setMargin(live, new Insets(4, 0, 0, 0));

        panel.getChildren().addAll(header, live);
    }
}
