package com.averolauncher.ui.kit;

import com.averolauncher.ui.Motion;
import com.averolauncher.ui.ValDeco;
import javafx.beans.InvalidationListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

/**
 * Building blocks of the "stage" layout first used by the Play page: full-bleed art under a scrim, a
 * left rail, a chamfered centre card, a right column of summary tiles, a top status strip, corner
 * brackets and a big primary button at the bottom centre. Mods and Store share these pieces so the
 * three pages read as one family. Everything here is static geometry plus styling from pages.css.
 */
public final class StageKit {

    private StageKit() {}

    /** Stage shell: art + scrim + slashes + corner brackets, clipped to its own bounds. Callers add the rest. */
    public static StackPane stage(String artClass) {
        StackPane stage = new StackPane();
        stage.getStyleClass().add("play-stage");

        Region art = new Region();
        art.getStyleClass().add(artClass);
        Region scrim = new Region();
        scrim.getStyleClass().add("play-stage-scrim");

        // The slashes' own polygons would otherwise set the preferred height (a feedback loop).
        Pane slashes = ValDeco.slashes(0.5);
        slashes.setMinSize(0, 0);
        slashes.setPrefSize(0, 0);

        stage.getChildren().addAll(art, scrim, slashes,
                bracket("play-bracket-tl", Pos.TOP_LEFT), bracket("play-bracket-tr", Pos.TOP_RIGHT),
                bracket("play-bracket-bl", Pos.BOTTOM_LEFT), bracket("play-bracket-br", Pos.BOTTOM_RIGHT));

        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(stage.widthProperty());
        clip.heightProperty().bind(stage.heightProperty());
        stage.setClip(clip);
        return stage;
    }

    private static Region bracket(String cls, Pos pos) {
        Region r = new Region();
        r.getStyleClass().addAll("play-bracket", cls);
        r.setMinSize(16, 16);
        r.setPrefSize(16, 16);
        r.setMaxSize(16, 16);
        r.setMouseTransparent(true);
        StackPane.setAlignment(r, pos);
        StackPane.setMargin(r, new Insets(8));
        return r;
    }

    /** Top status strip: dot, brand, status text, optional chips, spring, right-hand label. */
    public static HBox topStrip(Region dot, String brandText, Label status, Label right, Node... chips) {
        dot.getStyleClass().add("play-top-dot");
        Label brand = new Label(brandText);
        brand.getStyleClass().add("play-top-brand");
        status.getStyleClass().add("play-top-text");
        right.getStyleClass().add("play-top-text");
        Region spring = new Region();
        HBox.setHgrow(spring, Priority.ALWAYS);
        HBox top = new HBox(10, dot, brand, status);
        top.getChildren().addAll(chips);
        top.getChildren().addAll(spring, right);
        top.getStyleClass().add("play-top");
        top.setAlignment(Pos.CENTER_LEFT);
        top.setMouseTransparent(true);
        top.setMaxHeight(Region.USE_PREF_SIZE);
        StackPane.setAlignment(top, Pos.TOP_CENTER);
        StackPane.setMargin(top, new Insets(16, 28, 0, 28));
        return top;
    }

    /** Sets the status dot's colour: null/"" = neutral, otherwise ok / ice / err. */
    public static void setDot(Region dot, String kind) {
        dot.getStyleClass().setAll("play-top-dot");
        if (kind != null && !kind.isEmpty()) dot.getStyleClass().add("play-top-dot-" + kind);
    }

    /** Chamfered "V" bottom edge for a card, kept in sync with its size. */
    public static void chamfer(Region card) {
        Polygon cut = new Polygon();
        InvalidationListener recut = o -> {
            double w = card.getWidth(), h = card.getHeight(), c = Math.min(30, h / 4);
            if (w <= 0 || h <= 0) return;
            cut.getPoints().setAll(0.0, 0.0, w, 0.0, w, h - c, w / 2, h, 0.0, h - c);
        };
        card.widthProperty().addListener(recut);
        card.heightProperty().addListener(recut);
        card.setClip(cut);
    }

    /** Centre card shell with the standard width and chamfer. */
    public static VBox card(Node... children) {
        VBox card = new VBox(6, children);
        card.getStyleClass().add("play-card");
        card.setAlignment(Pos.TOP_CENTER);
        card.setMinWidth(260);
        card.setPrefWidth(340);
        card.setMaxWidth(370);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        chamfer(card);
        return card;
    }

    /** Short red rule under a card title. */
    public static Region rule() {
        Region r = new Region();
        r.getStyleClass().add("play-card-rule");
        r.setMaxWidth(44);
        return r;
    }

    /** Eyebrow label (small red tracked caps). */
    public static Label eyebrow(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("play-eyebrow");
        return l;
    }

    /** Big centred card title. */
    public static Label title(Label l) {
        l.getStyleClass().add("play-title");
        l.setTextOverrun(OverrunStyle.ELLIPSIS);
        l.setMaxWidth(Double.MAX_VALUE);
        l.setAlignment(Pos.CENTER);
        return l;
    }

    /** Row of chips, centred. */
    public static HBox chips(Label... chips) {
        for (Label c : chips) c.getStyleClass().add("av-chip");
        HBox row = new HBox(6, chips);
        row.setAlignment(Pos.CENTER);
        return row;
    }

    /** Key/value grid (2 columns) filled by the caller through the returned arrays. */
    public static VBox facts(Label[] keys, Label[] vals) {
        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(8);
        for (int c = 0; c < 2; c++) {
            ColumnConstraints cc = new ColumnConstraints();
            cc.setPercentWidth(50);
            grid.getColumnConstraints().add(cc);
        }
        for (int i = 0; i < keys.length; i++) {
            keys[i] = new Label();
            keys[i].getStyleClass().add("play-fact-key");
            vals[i] = new Label();
            vals[i].getStyleClass().add("play-fact-val");
            vals[i].setTextOverrun(OverrunStyle.ELLIPSIS);
            vals[i].setMaxWidth(Double.MAX_VALUE);
            VBox cell = new VBox(1, keys[i], vals[i]);
            cell.setAlignment(Pos.CENTER_LEFT);
            grid.add(cell, i % 2, i / 2);
        }
        VBox box = new VBox(grid);
        box.getStyleClass().add("play-facts");
        return box;
    }

    /** Left-rail mode tile: art with a label along its bottom edge. */
    public static StackPane modeTile(Label label, String tileClass) {
        label.getStyleClass().add("play-rail-mode");
        label.setMaxWidth(Double.MAX_VALUE);
        StackPane tile = new StackPane(label);
        tile.getStyleClass().add(tileClass);
        tile.setAlignment(Pos.BOTTOM_LEFT);
        return tile;
    }

    /** Compact rail button class (use on ToggleButton / AvButton). */
    public static void railButton(javafx.scene.control.ButtonBase b, boolean compact) {
        b.getStyleClass().add("play-rail-btn");
        if (compact) b.getStyleClass().add("play-rail-btn-sm");
        b.setMaxWidth(Double.MAX_VALUE);
        b.setAlignment(Pos.CENTER_LEFT);
    }

    /** Small opaque info tile for the left rail. {@code open} may be null (tile is then inert). */
    public static VBox railTile(String title, Label big, Label sub, Runnable open) {
        Label t = new Label(title);
        t.getStyleClass().add("play-fact-key");
        big.getStyleClass().add("play-rail-big");
        big.setTextOverrun(OverrunStyle.ELLIPSIS);
        big.setMaxWidth(Double.MAX_VALUE);
        sub.getStyleClass().add("play-rail-sub");
        sub.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
        sub.setMaxWidth(Double.MAX_VALUE);
        VBox box = new VBox(2, t, big, sub);
        box.getStyleClass().add("play-rail-tile-info");
        wireClick(box, title, open);
        return box;
    }

    /** Right-column summary tile with an optional square badge. {@code open} may be null. */
    public static VBox sideTile(String title, Label big, Label sub, Label badge, Runnable open) {
        Label t = new Label(title);
        t.getStyleClass().add("av-section-title");
        big.getStyleClass().add("play-side-big");
        big.setTextOverrun(OverrunStyle.ELLIPSIS);
        big.setMaxWidth(Double.MAX_VALUE);
        sub.getStyleClass().add("play-side-sub");
        sub.setWrapText(true);
        VBox text = new VBox(2, big, sub);
        HBox.setHgrow(text, Priority.ALWAYS);
        Node body = text;
        if (badge != null) {
            badge.getStyleClass().add("play-side-tile-text");
            StackPane box = new StackPane(badge);
            box.getStyleClass().add("play-side-tile");
            box.setMinSize(40, 40);
            box.setPrefSize(40, 40);
            box.setMaxSize(40, 40);
            HBox row = new HBox(12, box, text);
            row.setAlignment(Pos.CENTER_LEFT);
            body = row;
        }
        VBox tile = new VBox(6, t, body);
        tile.getStyleClass().add("play-side");
        wireClick(tile, title, open);
        return tile;
    }

    private static void wireClick(VBox box, String title, Runnable open) {
        if (open == null) return;
        box.setCursor(Cursor.HAND);
        box.setFocusTraversable(true);
        box.setAccessibleText(title.charAt(0) + title.substring(1).toLowerCase() + ", open");
        box.setOnMouseClicked(e -> open.run());
        box.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) open.run();
        });
    }

    /** The big bottom-centre button with its pulsing glow, positioned for a stage StackPane. */
    public static StackPane primaryAction(AvButton button) {
        button.getStyleClass().add("play-go");
        Region glow = new Region();
        glow.getStyleClass().add("play-go-glow");
        glow.setMouseTransparent(true);
        glow.visibleProperty().bind(button.disabledProperty().not());
        StackPane.setMargin(glow, new Insets(-36, -60, -36, -60));
        Motion.pulse(glow, 0.35, 0.9, Duration.millis(1400));
        StackPane box = new StackPane(glow, button);
        box.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane.setAlignment(box, Pos.BOTTOM_CENTER);
        StackPane.setMargin(box, new Insets(0, 0, 16, 0));
        return box;
    }

    /** Show/hide helper that also removes the node from layout. */
    public static void setShown(Node n, boolean shown) {
        n.setVisible(shown);
        n.setManaged(shown);
    }

    /** Bottom info bar (left + right text) that sits either side of the primary button. */
    public static HBox bottomBar(Label left, Label right) {
        left.getStyleClass().add("play-bottom-text");
        left.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
        left.setMaxWidth(300);
        right.getStyleClass().add("play-bottom-text");
        right.setTextOverrun(OverrunStyle.ELLIPSIS);
        right.setMaxWidth(300);
        Region spring = new Region();
        HBox.setHgrow(spring, Priority.ALWAYS);
        HBox bar = new HBox(10, left, spring, right);
        bar.setAlignment(Pos.BOTTOM_CENTER);
        bar.setMouseTransparent(true);
        bar.setMaxHeight(Region.USE_PREF_SIZE);
        StackPane.setAlignment(bar, Pos.BOTTOM_CENTER);
        StackPane.setMargin(bar, new Insets(0, 28, 20, 28));
        return bar;
    }
}
