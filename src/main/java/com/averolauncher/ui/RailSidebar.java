package com.averolauncher.ui;

import com.averolauncher.update.AppVersion;
import com.averolauncher.util.AppAssets;
import com.averolauncher.util.IconRegistry;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The left navigation: a 72 px icon rail that slides open to 240 px over the page (VALORANT
 * friends-list style).
 *
 * <p><b>How it avoids layout work:</b> the node is always laid out at its full {@link #OPEN_W}
 * width and a clip rectangle reveals only the first {@link #RAIL_W} px. Opening just animates the
 * clip width (and the labels' opacity/translate); nothing is re-laid-out and the page underneath is
 * never resized. The icons sit in a fixed 72 px column so they stay put while it opens. Because
 * clipped areas are not hit-tested, the hidden 168 px never steals clicks from the page.
 *
 * <p>Opening: 80 ms hover-intent delay, then 240 ms ease-out-quint, labels staggered 25 ms apart.
 * Closing: 250 ms grace delay, so a mouse passing by doesn't flicker it. Keyboard focus (when the
 * user is navigating by keyboard) also opens it. Everything goes through {@link Motion}, so
 * Low-End Mode makes it snap.
 */
public final class RailSidebar extends VBox {

    public static final double RAIL_W = 72;
    public static final double OPEN_W = 240;

    private static final double ITEM_H = 48;
    private static final double ITEM_GAP = 4;
    private static final double LABEL_SHIFT = 10;
    private static final double LABEL_STAGGER_MS = 25;
    private static final int LABEL_STAGGER_CAP = 10;
    private static final Duration OPEN_DELAY = Duration.millis(80);
    private static final Duration CLOSE_DELAY = Duration.millis(250);
    private static final PseudoClass KBD = PseudoClass.getPseudoClass("kbd");

    private final Rectangle reveal = new Rectangle(RAIL_W, 0);
    private final Region edge = new Region();
    private final Region indicator = new Region();
    private final VBox nav = new VBox(ITEM_GAP);
    private final ToggleGroup group = new ToggleGroup();
    private final Map<NavPage, ToggleButton> buttons = new EnumMap<>(NavPage.class);
    private final List<ToggleButton> order = new ArrayList<>();
    private final List<NavPage> pages = new ArrayList<>();
    private final List<Node> fadeItems = new ArrayList<>();

    private final ReadOnlyBooleanWrapper expanded = new ReadOnlyBooleanWrapper(false);
    private final PauseTransition openTimer = Motion.after(OPEN_DELAY, this::expand);
    private final PauseTransition closeTimer = Motion.after(CLOSE_DELAY, this::maybeCollapse);
    private final Runnable onEscape;

    private Timeline labelAnim;
    private boolean pinned;
    private boolean indicatorPlaced;

    public RailSidebar(Consumer<NavPage> onNavigate, Runnable onEscape) {
        this.onEscape = onEscape;
        getStyleClass().add("av-rail");
        setFocusTraversable(false);

        // Always laid out at full width; only the clip (below) changes.
        setMinWidth(OPEN_W);
        setPrefWidth(OPEN_W);
        setMaxWidth(OPEN_W);
        setMaxHeight(Double.MAX_VALUE);

        reveal.heightProperty().bind(heightProperty());
        setClip(reveal);

        // 1 px right edge that rides the clip, so the rail keeps its outline while it grows.
        edge.getStyleClass().add("av-rail-edge");
        edge.setManaged(false);
        edge.setMouseTransparent(true);
        edge.layoutXProperty().bind(reveal.widthProperty().subtract(1));

        getChildren().addAll(buildBrand(), buildNav(onNavigate), spring(), buildSettingsItem(onNavigate), buildFooter(), edge);

        hoverProperty().addListener((o, was, now) -> {
            if (now) {
                closeTimer.stop();
                if (!expanded.get()) openTimer.playFromStart();
            } else {
                openTimer.stop();
                closeTimer.playFromStart();
            }
        });

        // Esc anywhere in the rail: close and hand focus back to the page.
        addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                collapseNow();
                if (this.onEscape != null) this.onEscape.run();
                e.consume();
            }
        });
    }

    // ───────────────────────────────────────────────────────────── build

    private Node buildBrand() {
        StackPane markSlot = slot(AppAssets.buildLogoMark("brand-mark", "A", 36), 64);
        Label word = new Label("AVERO");
        word.getStyleClass().add("av-rail-brand");
        Label sub = new Label("LAUNCHER");
        sub.getStyleClass().add("av-rail-brand-sub");
        VBox wordmark = new VBox(0, word, sub);
        wordmark.setAlignment(Pos.CENTER_LEFT);
        fade(wordmark);

        HBox brand = new HBox(markSlot, wordmark);
        brand.getStyleClass().add("av-rail-brand-row");
        brand.setAlignment(Pos.CENTER_LEFT);
        return brand;
    }

    private Node buildNav(Consumer<NavPage> onNavigate) {
        indicator.getStyleClass().add("av-rail-indicator");
        indicator.setManaged(false);
        indicator.setMouseTransparent(true);
        indicator.setOpacity(0);
        indicator.resize(3, ITEM_H);
        nav.getChildren().add(indicator);

        for (NavPage page : NavPage.values()) {
            if (page == NavPage.SETTINGS) continue; // pulled out into its own row — see buildSettingsItem()
            nav.getChildren().add(createItem(page, onNavigate));
        }

        // A selected ToggleButton can be toggled off by clicking it again; navigation never wants that.
        group.selectedToggleProperty().addListener((o, was, now) -> {
            if (now == null && was != null) was.setSelected(true);
        });
        return nav;
    }

    /**
     * Settings, pulled out of the main list and pinned just above the footer (below the spring), with
     * a hairline divider above it so it reads as a separate, lower-priority action from the rest of
     * the nav — not just one more item in the list.
     */
    private Node buildSettingsItem(Consumer<NavPage> onNavigate) {
        ToggleButton b = createItem(NavPage.SETTINGS, onNavigate);

        Region divider = new Region();
        divider.getStyleClass().add("av-rail-divider");
        divider.setMinHeight(1);
        divider.setPrefHeight(1);
        divider.setMaxHeight(1);
        divider.setMinWidth(OPEN_W - 32);
        divider.setPrefWidth(OPEN_W - 32);
        divider.setMaxWidth(OPEN_W - 32);
        HBox dividerRow = new HBox(divider);
        dividerRow.setAlignment(Pos.CENTER);
        VBox.setMargin(dividerRow, new Insets(0, 0, 6, 0));

        VBox box = new VBox(dividerRow, b);
        return box;
    }

    /** Builds one nav button; shared by the main list and the separated Settings row. */
    private ToggleButton createItem(NavPage page, Consumer<NavPage> onNavigate) {
        ToggleButton b = new ToggleButton();
        b.getStyleClass().add("av-rail-item");
        b.setToggleGroup(group);
        b.setMnemonicParsing(false);
        b.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        b.setAlignment(Pos.CENTER_LEFT);
        b.setMinSize(OPEN_W, ITEM_H);
        b.setPrefSize(OPEN_W, ITEM_H);
        b.setMaxSize(OPEN_W, ITEM_H);
        b.setAccessibleText(page.label()); // icon-only when collapsed, so screen readers need this

        StackPane iconSlot = slot(IconRegistry.icon(page), ITEM_H);
        iconSlot.getStyleClass().add("av-rail-icon");
        Label label = new Label(page.label().toUpperCase(Locale.ROOT));
        label.getStyleClass().add("av-rail-label");
        fade(label);
        HBox row = new HBox(iconSlot, label);
        row.setAlignment(Pos.CENTER_LEFT);
        b.setGraphic(row);

        b.setOnAction(e -> onNavigate.accept(page));
        b.focusedProperty().addListener((o, was, now) -> {
            if (now && kbdActive()) {
                closeTimer.stop();
                expand();
            } else if (!now) {
                closeTimer.playFromStart(); // maybeCollapse() checks whether focus moved to a sibling
            }
        });
        b.addEventFilter(KeyEvent.KEY_PRESSED, e -> onKey(e, b));

        buttons.put(page, b);
        order.add(b);
        pages.add(page);
        return b;
    }

    private Node buildFooter() {
        Label ver = new Label("v" + AppVersion.CURRENT);
        ver.getStyleClass().add("av-rail-version");
        StackPane verSlot = new StackPane(ver);
        verSlot.setMinSize(RAIL_W, 28);
        verSlot.setPrefSize(RAIL_W, 28);
        verSlot.setMaxSize(RAIL_W, 28);

        Label name = new Label("AVERO LAUNCHER");
        name.getStyleClass().add("av-rail-foot");
        fade(name);

        HBox footer = new HBox(verSlot, name);
        footer.getStyleClass().add("av-rail-footer");
        footer.setAlignment(Pos.CENTER_LEFT);
        return footer;
    }

    private static Region spring() {
        Region r = new Region();
        VBox.setVgrow(r, Priority.ALWAYS);
        return r;
    }

    /** A RAIL_W-wide cell that centres its content, so icons never move while the rail opens. */
    private static StackPane slot(Node content, double height) {
        StackPane s = new StackPane(content);
        s.setMinSize(RAIL_W, height);
        s.setPrefSize(RAIL_W, height);
        s.setMaxSize(RAIL_W, height);
        return s;
    }

    /** Registers a node that fades/slides in with the stagger when the rail opens. */
    private <T extends Node> T fade(T n) {
        n.setOpacity(0);
        n.setTranslateX(-LABEL_SHIFT);
        n.setMouseTransparent(true);
        fadeItems.add(n);
        return n;
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        edge.resize(1, getHeight());
    }

    // ───────────────────────────────────────────────────────────── public API

    /** Marks {@code page} as current: selects its button and slides the accent bar to it. */
    public void select(NavPage page) {
        ToggleButton b = buttons.get(page);
        if (b == null) return;
        b.setSelected(true);
        if (page == NavPage.SETTINGS) {
            // Settings sits in its own row, outside the sliding accent bar's track; its own
            // :selected background tint (see shell.css) is enough to show it's the active page.
            Motion.animate(indicator.opacityProperty(), 0, Motion.PANEL);
            return;
        }
        double y = pages.indexOf(page) * (ITEM_H + ITEM_GAP);
        if (!indicatorPlaced) {
            indicator.setTranslateY(y);
            indicator.setOpacity(1);
            indicatorPlaced = true;
        } else {
            Motion.animate(indicator.translateYProperty(), y, Motion.PANEL);
            Motion.animate(indicator.opacityProperty(), 1, Motion.PANEL);
        }
    }

    public ReadOnlyBooleanProperty expandedProperty() {
        return expanded.getReadOnlyProperty();
    }

    /** "Pin sidebar open": stays expanded regardless of the pointer. */
    public void setPinned(boolean pin) {
        pinned = pin;
        if (pin) {
            closeTimer.stop();
            expand();
        } else {
            maybeCollapse();
        }
    }

    /** Closes immediately (scrim click, Esc). Ignored while pinned. */
    public void collapseNow() {
        openTimer.stop();
        closeTimer.stop();
        collapse();
    }

    /** Keyboard entry point (Ctrl+B): opens the rail and focuses the current item. */
    public void focusNav() {
        if (getScene() != null && getScene().getRoot() != null) {
            getScene().getRoot().pseudoClassStateChanged(KBD, true); // draw focus styling
        }
        closeTimer.stop();
        expand();
        ToggleButton target = group.getSelectedToggle() instanceof ToggleButton t ? t : order.get(0);
        target.requestFocus();
    }

    // ───────────────────────────────────────────────────────────── open / close

    private void expand() {
        if (expanded.get()) return;
        expanded.set(true);
        Motion.animate(reveal.widthProperty(), OPEN_W, Motion.PANEL);
        playLabels(true);
    }

    private void collapse() {
        if (pinned || !expanded.get()) return;
        expanded.set(false);
        Motion.animate(reveal.widthProperty(), RAIL_W, Motion.PANEL);
        playLabels(false);
    }

    private void maybeCollapse() {
        if (pinned || isHover() || (focusInside() && kbdActive())) return;
        collapse();
    }

    private boolean focusInside() {
        for (ToggleButton b : order) if (b.isFocused()) return true;
        return false;
    }

    /** True after a keyboard navigation key, false after a mouse press (see kit.FocusRing). */
    private boolean kbdActive() {
        return getScene() != null && getScene().getRoot() != null
                && getScene().getRoot().getPseudoClassStates().contains(KBD);
    }

    /**
     * Labels fade/slide in with a short stagger (starting a beat after the panel so they appear as
     * it passes under them); on close they all fade out together, quickly.
     */
    private void playLabels(boolean in) {
        if (labelAnim != null) labelAnim.stop();
        labelAnim = null;
        for (Node n : fadeItems) n.setMouseTransparent(!in);
        if (!Motion.enabled()) {
            for (Node n : fadeItems) {
                n.setOpacity(in ? 1 : 0);
                n.setTranslateX(in ? 0 : -LABEL_SHIFT);
            }
            return;
        }
        List<KeyFrame> frames = new ArrayList<>();
        Duration each = Duration.millis(in ? 200 : 90);
        int i = 0;
        for (Node n : fadeItems) {
            Duration delay = Duration.millis(in ? 40 + Math.min(i, LABEL_STAGGER_CAP) * LABEL_STAGGER_MS : 0);
            // hold the current value until this item's turn, then ease to the target
            frames.add(new KeyFrame(delay,
                    new KeyValue(n.opacityProperty(), n.getOpacity()),
                    new KeyValue(n.translateXProperty(), n.getTranslateX())));
            frames.add(new KeyFrame(delay.add(each),
                    new KeyValue(n.opacityProperty(), in ? 1 : 0, Motion.EASE),
                    new KeyValue(n.translateXProperty(), in ? 0 : -LABEL_SHIFT, Motion.EASE)));
            i++;
        }
        labelAnim = new Timeline(frames.toArray(new KeyFrame[0]));
        labelAnim.play();
    }

    // ───────────────────────────────────────────────────────────── keyboard

    private void onKey(KeyEvent e, ToggleButton b) {
        switch (e.getCode()) {
            case DOWN -> { focusRelative(b, 1); e.consume(); }
            case UP -> { focusRelative(b, -1); e.consume(); }
            case HOME -> { order.get(0).requestFocus(); e.consume(); }
            case END -> { order.get(order.size() - 1).requestFocus(); e.consume(); }
            case ENTER -> { b.fire(); e.consume(); } // Space already fires a ToggleButton
            default -> { }
        }
    }

    private void focusRelative(ToggleButton from, int delta) {
        int i = order.indexOf(from);
        int n = order.size();
        order.get(((i + delta) % n + n) % n).requestFocus();
    }
}
