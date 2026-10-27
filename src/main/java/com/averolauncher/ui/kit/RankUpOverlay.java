package com.averolauncher.ui.kit;

import com.averolauncher.rank.RankCalculator;
import com.averolauncher.rank.RankConfig;
import com.averolauncher.rank.RankStanding;
import com.averolauncher.ui.Motion;
import com.averolauncher.ui.ValText;
import javafx.animation.Animation;
import javafx.animation.AnimationTimer;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.RotateTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.value.WritableValue;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.effect.BlendMode;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Ellipse;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.transform.Rotate;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Full-screen cinematic "rank promoted" sequence. One instance is created at startup and reused for
 * every promotion ({@link #play}); it lives above the whole shell so it reads as a single modal moment
 * regardless of which page the player is on.
 *
 * <p>Timeline (all offsets approximate, see {@link #scheduleCinematic}):
 * <ol>
 *   <li><b>Build-up</b> &mdash; drifting aurora + faint hex lattice fade in, the emblem rises through its
 *       rings, a tick dial and orbiting sparks while a bright arc <i>charges</i> clockwise around it.</li>
 *   <li><b>Impact</b> &mdash; white flash, three shockwave rings, a vertical light pillar and horizontal
 *       lens streak, a short camera shake, a screen pulse and a burst of sparks/streaks/stars; the
 *       division pips pop in and the rank ladder lights up.</li>
 *   <li><b>Reveal</b> &mdash; chevrons, then the rank name letter-by-letter with a shine sweep, then
 *       Previous / AP gained (counting up) / Next cards, the AP bar filling with a travelling spark
 *       (ticks light as it passes), and finally Continue.</li>
 * </ol>
 *
 * <p>Every eased transition goes through {@link Motion#animate} or {@link #tween}, so Low-End Mode /
 * "animations off" (Settings) collapses the whole sequence to its final state instantly &mdash; the
 * reduced-motion path ({@link #settleAll}). A visible "Skip animation" control (and Esc) does the same
 * thing on demand without closing the window; Continue / the close button / Enter once at rest dismiss
 * it. Nothing here blocks on the network or disk: {@link #play} only ever reads the
 * {@link RankStanding} values it's handed.
 */
public final class RankUpOverlay extends StackPane {

    /** Overshooting ease-out ("back"): used for pops so things land with a little life. */
    private static final Interpolator BACK = new Interpolator() {
        @Override
        protected double curve(double t) {
            double c1 = 1.70158, c3 = c1 + 1, u = t - 1;
            return 1 + c3 * u * u * u + c1 * u * u;
        }
    };

    private final Random rnd = new Random();
    private final List<Particle> ambient = new ArrayList<>();
    private final List<Particle> burst = new ArrayList<>();
    private final List<PauseTransition> pending = new ArrayList<>();
    /** Never-ending loops (ring spins, breathing, drift...). */
    private final List<Animation> idle = new ArrayList<>();
    /** Finite tweens in flight, so skip/dismiss can stop them. */
    private final List<Timeline> tweens = new ArrayList<>();

    private Canvas canvas;
    private GraphicsContext gc;
    private AnimationTimer timer;
    private Runnable onDismiss;
    private Runnable fit;
    private boolean atRest;
    private boolean idleStarted;
    private boolean auroraStarted;
    private double targetProgress;
    private double clock;
    private Color accentCol = Color.web("#a855f7");
    private Color hotCol = Color.WHITE;

    // Node groups touched by the staged reveal and by settleAll()/skip().
    private Node ringA, ringB, ringC, glowCircle, emblemGroup, flash, raysGroup, dial, orbitA, orbitB, floorGlow;
    private Node beam, flare, screenFlash, auroraLayer, gridLayer, frameNode, emblemAnchor, bobNode;
    private Arc chargeArc;
    private final List<Node> shockwaves = new ArrayList<>();
    private final List<Node> chevrons = new ArrayList<>();
    private final List<Node> letters = new ArrayList<>();
    private final List<StackPane> pips = new ArrayList<>();
    private final List<Boolean> pipReached = new ArrayList<>();
    private final List<Node> ladderBadges = new ArrayList<>();
    private final List<Region> ladderLinks = new ArrayList<>();
    private final List<String> ladderAccents = new ArrayList<>();
    private int ladderCur;
    private VBox titleGroup, statsGroup, continueGroup;
    private HBox prevCard, nextCard;
    private VBox apCard;
    private Region footerBar;
    private VBox centerBox;
    private DoubleProperty barProgress;
    private DoubleProperty apCount;
    private long apGained;
    private Rectangle shimmer;

    public RankUpOverlay() {
        getStyleClass().add("rankup-overlay");
        setPickOnBounds(true);
        setVisible(false);
        setManaged(false);
        setOpacity(0);
        setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                if (!atRest) skipAnimation(); else dismiss();
                e.consume();
            } else if ((e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) && atRest) {
                dismiss();
                e.consume();
            }
        });
        widthProperty().addListener((o, a, b) -> { if (fit != null) fit.run(); });
        heightProperty().addListener((o, a, b) -> { if (fit != null) fit.run(); });
    }

    /**
     * FX thread only. Builds and plays the full sequence for a promotion from {@code from} to
     * {@code to}. {@code onDismiss} runs once the player closes/continues past the overlay (may be
     * {@code null}).
     */
    public void play(RankConfig cfg, RankStanding from, RankStanding to, Runnable onDismiss) {
        if (cfg == null || from == null || to == null || to.tier() == null) return;
        stopAll();
        getChildren().clear();
        ambient.clear();
        burst.clear();
        shockwaves.clear();
        chevrons.clear();
        letters.clear();
        pips.clear();
        pipReached.clear();
        ladderBadges.clear();
        ladderLinks.clear();
        ladderAccents.clear();
        atRest = false;
        idleStarted = false;
        auroraStarted = false;
        clock = 0;
        this.onDismiss = onDismiss;
        this.targetProgress = to.progress();
        this.apGained = Math.max(0, to.ap() - from.ap());

        boolean celestial = "CELESTIAL".equalsIgnoreCase(to.tier().id);
        String accentHex = RankBadge.accent(to.tier());
        Color accentColor = safeColor(accentHex);
        accentCol = accentColor;
        hotCol = accentColor.interpolate(Color.WHITE, 0.62);

        try {
            buildUI(cfg, from, to, accentHex, accentColor, celestial);
        } catch (RuntimeException ex) {
            // Never leave a half-built, input-blocking overlay on screen.
            stopAll();
            getChildren().clear();
            setVisible(false);
            setManaged(false);
            throw ex;
        }

        setVisible(true);
        setManaged(true);
        setMouseTransparent(false);
        setFocusTraversable(true);
        ValText.watch(this);
        toFront();
        requestFocus();
        Platform.runLater(() -> { if (fit != null) fit.run(); });

        Motion.animate(opacityProperty(), 1.0, Duration.millis(260));

        if (!Motion.enabled()) {
            settleAll();
            return;
        }
        try {
            startParticles();
            scheduleCinematic(celestial);
        } catch (RuntimeException ex) {
            // Fall back to the finished state rather than a frozen half-reveal.
            stopAll();
            try { settleAll(); } catch (RuntimeException ignored) { }
            throw ex;
        }
    }

    // ------------------------------------------------------------------ build

    private void buildUI(RankConfig cfg, RankStanding from, RankStanding to, String accentHex, Color accentColor, boolean celestial) {
        Region bg = new Region();
        bg.getStyleClass().add("rankup-bg");

        Pane aurora = buildAurora(accentColor, celestial);
        aurora.setOpacity(0);
        auroraLayer = aurora;

        Pane grid = buildHexGrid(accentColor);
        grid.setOpacity(0);
        gridLayer = grid;

        Pane shards = buildShards(accentColor, celestial);

        Region vignette = new Region();
        vignette.getStyleClass().add("rankup-vignette");
        vignette.setMouseTransparent(true);

        canvas = new Canvas();
        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());
        canvas.setMouseTransparent(true);
        canvas.setBlendMode(BlendMode.ADD);
        gc = canvas.getGraphicsContext2D();
        gc.setLineCap(StrokeLineCap.ROUND);

        Region pulse = new Region();
        pulse.setStyle("-fx-background-color: white;");
        pulse.setMouseTransparent(true);
        pulse.setOpacity(0);
        screenFlash = pulse;

        getChildren().addAll(bg, aurora, grid, shards, canvas, vignette,
                buildTopBar(), buildCenter(cfg, from, to, accentHex, accentColor, celestial), buildFooter(), pulse);
    }

    /** Two huge, soft colour blobs (tier accent + a hue-shifted partner) that drift slowly behind everything. */
    private Pane buildAurora(Color accent, boolean celestial) {
        Pane pane = new Pane();
        pane.setMouseTransparent(true);
        pane.setPickOnBounds(false);
        Color partner = accent.deriveColor(celestial ? 140 : 38, 0.9, 1.0, 1.0);
        Circle a = new Circle(430);
        a.setFill(radial(accent, celestial ? 0.26 : 0.19));
        Circle b = new Circle(380);
        b.setFill(radial(partner, celestial ? 0.20 : 0.13));
        a.setBlendMode(BlendMode.SCREEN);
        b.setBlendMode(BlendMode.SCREEN);
        pane.getChildren().addAll(a, b);

        Runnable relayout = () -> {
            double w = pane.getWidth(), h = pane.getHeight();
            if (w <= 0 || h <= 0) return;
            a.setCenterX(w * 0.28);
            a.setCenterY(h * 0.30);
            b.setCenterX(w * 0.76);
            b.setCenterY(h * 0.74);
        };
        pane.widthProperty().addListener((o, x, y) -> relayout.run());
        pane.heightProperty().addListener((o, x, y) -> relayout.run());
        // Drift is started with the other idle loops (see startIdle); stash the blobs for it.
        pane.getProperties().put("blobA", a);
        pane.getProperties().put("blobB", b);
        return pane;
    }

    /** A very faint pointy-top hexagon lattice in the tier accent: texture only, redrawn on resize. */
    private Pane buildHexGrid(Color accent) {
        Pane pane = new Pane();
        pane.setMouseTransparent(true);
        Canvas c = new Canvas();
        c.setMouseTransparent(true);
        c.widthProperty().bind(pane.widthProperty());
        c.heightProperty().bind(pane.heightProperty());
        pane.getChildren().add(c);
        Runnable draw = () -> {
            double w = c.getWidth(), h = c.getHeight();
            GraphicsContext g = c.getGraphicsContext2D();
            g.clearRect(0, 0, w, h);
            if (w <= 0 || h <= 0) return;
            double s = 34, hw = Math.sqrt(3) * s, vs = 1.5 * s;
            g.setLineWidth(1);
            g.setStroke(Color.color(accent.getRed(), accent.getGreen(), accent.getBlue(), 0.05));
            double[] xs = new double[6], ys = new double[6];
            int rows = (int) (h / vs) + 3, cols = (int) (w / hw) + 3;
            for (int r = -1; r < rows; r++) {
                for (int q = -1; q < cols; q++) {
                    double cx = q * hw + ((r & 1) == 0 ? 0 : hw / 2), cy = r * vs;
                    for (int i = 0; i < 6; i++) {
                        double ang = Math.PI / 3 * i - Math.PI / 2;
                        xs[i] = cx + s * Math.cos(ang);
                        ys[i] = cy + s * Math.sin(ang);
                    }
                    g.strokePolygon(xs, ys, 6);
                }
            }
        };
        c.widthProperty().addListener((o, a, b) -> draw.run());
        c.heightProperty().addListener((o, a, b) -> draw.run());
        return pane;
    }

    /**
     * A handful of large, very faint diagonal wedges along the left/right edges &mdash; the same
     * leaning-parallelogram language as the rest of the app's banners, but tinted to this promotion's
     * tier accent instead of brand red, and kept subtle enough to read as texture, not noise.
     */
    private Pane buildShards(Color accentColor, boolean celestial) {
        Pane pane = new Pane();
        pane.setMouseTransparent(true);
        pane.setPickOnBounds(false);

        double baseAlpha = celestial ? 0.10 : 0.07;
        List<Polygon> shapes = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Polygon p = new Polygon();
            double alpha = baseAlpha * (1.0 - i * 0.22);
            p.setFill(Color.color(accentColor.getRed(), accentColor.getGreen(), accentColor.getBlue(), Math.max(0.01, alpha)));
            p.setStroke(null);
            shapes.add(p);
        }
        pane.getChildren().addAll(shapes);

        Runnable relayout = () -> {
            double w = pane.getWidth(), h = pane.getHeight();
            if (w <= 0 || h <= 0) return;
            double lean = h * 0.55;
            shard(shapes.get(0), w * -0.02, 220, h, lean);
            shard(shapes.get(1), w * 0.09, 90, h, lean);
            shard(shapes.get(2), w * 1.02, 220, h, lean);
            shard(shapes.get(3), w * 0.91, 90, h, lean);
        };
        pane.widthProperty().addListener((o, a, b) -> relayout.run());
        pane.heightProperty().addListener((o, a, b) -> relayout.run());
        return pane;
    }

    private static void shard(Polygon p, double x, double width, double h, double lean) {
        double topL = x - width / 2 + lean / 2, topR = x + width / 2 + lean / 2;
        double botL = x - width / 2 - lean / 2, botR = x + width / 2 - lean / 2;
        p.getPoints().setAll(topL, 0.0, topR, 0.0, botR, h, botL, h);
    }

    private HBox buildTopBar() {
        ImageView logo = new ImageView();
        try {
            var url = getClass().getResource("/icons/app-icon.png");
            if (url != null) logo.setImage(new Image(url.toExternalForm(), 0, 28, true, true));
        } catch (Exception ignored) { }
        logo.setFitHeight(24);
        logo.setPreserveRatio(true);
        logo.setSmooth(true);
        Label wordmark = new Label("Avero");
        wordmark.getStyleClass().add("rankup-wordmark");
        HBox brand = new HBox(10, logo, wordmark);
        brand.setAlignment(Pos.CENTER_LEFT);

        Button skip = new Button("Skip animation");
        skip.getStyleClass().add("rankup-skip");
        skip.setOnAction(e -> skipAnimation());

        SVGPath closeIcon = new SVGPath();
        closeIcon.setContent("M5 5 L19 19 M19 5 L5 19");
        closeIcon.getStyleClass().add("rankup-close-icon");
        closeIcon.setFill(null);
        Button close = new Button();
        close.setGraphic(closeIcon);
        close.getStyleClass().add("rankup-close");
        close.setAccessibleText("Close");
        close.setOnAction(e -> dismiss());

        Region spring = new Region();
        HBox.setHgrow(spring, Priority.ALWAYS);
        HBox topBar = new HBox(18, brand, spring, skip, close);
        topBar.getStyleClass().add("rankup-topbar");
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setMaxWidth(Double.MAX_VALUE);
        StackPane.setAlignment(topBar, Pos.TOP_CENTER);
        return topBar;
    }

    private Node buildCenter(RankConfig cfg, RankStanding from, RankStanding to, String accentHex, Color accentColor, boolean celestial) {
        // ── header (static chrome, not part of the staged reveal) + rank ladder ────────
        Label kicker = new Label("Rank Up");
        kicker.getStyleClass().add("rankup-kicker");
        HBox kickerRow = new HBox(14, rule(), kicker, rule());
        kickerRow.setAlignment(Pos.CENTER);
        Label kickerSub = new Label("Avero Global Ranks");
        kickerSub.getStyleClass().add("rankup-kicker-sub");
        VBox header = new VBox(6, kickerRow, kickerSub, buildLadder(cfg, to));
        header.setAlignment(Pos.CENTER);

        // ── emblem ──────────────────────────────────────────────────────────────
        double emblemHeight = celestial ? 236 : 214;

        Group rays = buildRays(accentColor, celestial ? 16 : 12, celestial ? 215 : 190);
        rays.setOpacity(0);
        rays.setScaleX(0.8);
        rays.setScaleY(0.8);
        raysGroup = rays;

        Circle glow = new Circle(150);
        glow.setFill(radial(accentColor, 0.55));
        glow.setOpacity(0);
        glowCircle = glow;

        Polygon ring1 = hex(celestial ? 122 : 112);
        ring1.setStroke(accentColor.deriveColor(0, 1, 1, 0.55));
        ring1.setStrokeWidth(1.4);
        ring1.setOpacity(0);
        ring1.setScaleX(0.6);
        ring1.setScaleY(0.6);
        ringA = ring1;

        Polygon ring2 = hex(celestial ? 150 : 138);
        ring2.setStroke(accentColor.deriveColor(0, 1, 1, 0.32));
        ring2.setStrokeWidth(1.0);
        ring2.setOpacity(0);
        ring2.setScaleX(0.6);
        ring2.setScaleY(0.6);
        ringB = ring2;

        Polygon ring3 = hex(emblemHeight * 0.5 + 10);
        ring3.setStroke(accentColor.deriveColor(0, 1, 1, 0.7));
        ring3.setStrokeWidth(0.8);
        ring3.setOpacity(0);
        ring3.setScaleX(0.6);
        ring3.setScaleY(0.6);
        ringC = ring3;

        // Fine tick dial (like a bezel) just outside the hex rings.
        SVGPath dialPath = new SVGPath();
        dialPath.setContent(dialContent(emblemHeight * 0.5 + 34, 72));
        dialPath.setFill(null);
        dialPath.setStroke(accentColor.deriveColor(0, 1, 1, 0.38));
        dialPath.setStrokeWidth(1.0);
        dialPath.setMouseTransparent(true);
        dialPath.setOpacity(0);
        dial = dialPath;

        // The "charging" arc: drawn clockwise during the build-up, then settles into a faint full ring.
        double arcR = emblemHeight * 0.5 + 22;
        Arc arc = new Arc(arcR + 6, arcR + 6, arcR, arcR, 90, 0);
        arc.setType(ArcType.OPEN);
        arc.setFill(null);
        arc.setStroke(hotCol);
        arc.setStrokeWidth(2.6);
        arc.setStrokeLineCap(StrokeLineCap.ROUND);
        arc.setEffect(new DropShadow(14, accentColor));
        chargeArc = arc;
        Pane arcHolder = new Pane(arc);
        arcHolder.setMinSize(arcR * 2 + 12, arcR * 2 + 12);
        arcHolder.setPrefSize(arcR * 2 + 12, arcR * 2 + 12);
        arcHolder.setMaxSize(arcR * 2 + 12, arcR * 2 + 12);
        arcHolder.setMouseTransparent(true);

        Group orbit1 = orbiters(accentColor, emblemHeight * 0.5 + 48, 5.5, new double[] {0, 180});
        Group orbit2 = orbiters(accentColor, emblemHeight * 0.5 + 62, 4.0, new double[] {90, 270});
        orbit1.setOpacity(0);
        orbit2.setOpacity(0);
        orbitA = orbit1;
        orbitB = orbit2;

        Image art = RankBadge.art(to.tier().id);
        Node badgeVisual;
        if (art != null) {
            ImageView iv = new ImageView(art);
            iv.setPreserveRatio(true);
            iv.setSmooth(true);
            iv.setFitHeight(emblemHeight);
            badgeVisual = iv;
        } else {
            Polygon fallback = hex(emblemHeight / 2);
            fallback.setFill(accentColor.deriveColor(0, 1, 1, 0.85));
            fallback.setStroke(accentColor);
            badgeVisual = fallback;
        }
        badgeVisual.setEffect(new DropShadow(22, Color.color(accentColor.getRed(), accentColor.getGreen(), accentColor.getBlue(), 0.55)));
        bobNode = badgeVisual;
        emblemAnchor = badgeVisual;

        StackPane emblem = new StackPane(rays, dialPath, glow, ring2, ring1, ring3, arcHolder, orbit1, orbit2, badgeVisual);
        emblem.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        emblem.setOpacity(0);
        emblem.setScaleX(0.72);
        emblem.setScaleY(0.72);
        emblemGroup = emblem;

        // Soft pool of light under the emblem, as if it hovers above a polished floor.
        Ellipse floor = new Ellipse(emblemHeight * 0.52, 15);
        floor.setFill(new RadialGradient(0, 0, 0.5, 0.5, 0.5, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.color(accentColor.getRed(), accentColor.getGreen(), accentColor.getBlue(), 0.55)),
                new Stop(1, Color.color(accentColor.getRed(), accentColor.getGreen(), accentColor.getBlue(), 0))));
        floor.setTranslateY(emblemHeight * 0.5 + 18);
        floor.setOpacity(0.85);
        floor.setScaleY(0.01); // grows in on impact; opacity is left to the idle "breathing" loop
        floor.setMouseTransparent(true);
        floorGlow = floor;

        Circle flashCircle = new Circle(emblemHeight * 0.62);
        flashCircle.setFill(Color.WHITE);
        flashCircle.setOpacity(0);
        flashCircle.setMouseTransparent(true);
        flash = flashCircle;

        // Impact effects: light pillar, lens streak, three expanding shockwave rings.
        Rectangle pillar = new Rectangle(170, 1100);
        pillar.setFill(new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.color(accentColor.getRed(), accentColor.getGreen(), accentColor.getBlue(), 0)),
                new Stop(0.42, Color.color(accentColor.getRed(), accentColor.getGreen(), accentColor.getBlue(), 0.45)),
                new Stop(0.5, Color.color(1, 1, 1, 0.85)),
                new Stop(0.58, Color.color(accentColor.getRed(), accentColor.getGreen(), accentColor.getBlue(), 0.45)),
                new Stop(1, Color.color(accentColor.getRed(), accentColor.getGreen(), accentColor.getBlue(), 0))));
        pillar.setBlendMode(BlendMode.SCREEN);
        pillar.setMouseTransparent(true);
        pillar.setOpacity(0);
        beam = pillar;

        Rectangle streak = new Rectangle(1000, 3);
        streak.setFill(new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.color(1, 1, 1, 0)),
                new Stop(0.5, Color.color(1, 1, 1, 0.95)),
                new Stop(1, Color.color(1, 1, 1, 0))));
        streak.setBlendMode(BlendMode.SCREEN);
        streak.setEffect(new DropShadow(10, accentColor));
        streak.setMouseTransparent(true);
        streak.setOpacity(0);
        flare = streak;

        StackPane emblemLayer = new StackPane(floor, pillar, emblem, streak);
        for (int i = 0; i < 3; i++) {
            Circle w = new Circle(96);
            w.setFill(null);
            w.setStroke(i == 0 ? Color.WHITE : accentColor.interpolate(Color.WHITE, 0.3 - i * 0.12));
            w.setStrokeWidth(3.0 - i * 0.8);
            w.setOpacity(0);
            w.setMouseTransparent(true);
            shockwaves.add(w);
            emblemLayer.getChildren().add(w);
        }
        emblemLayer.getChildren().add(flashCircle);
        emblemLayer.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        emblemLayer.setTranslateY(-14);
        // The layer is far larger than the emblem (pillar / lens streak) and purely visual: never let it eat clicks.
        emblemLayer.setMouseTransparent(true);

        // Division pips (I / II / III...) under the emblem; the newly reached ones pop in on impact.
        HBox pipRow = buildPips(to, accentColor);
        StackPane emblemStack = new StackPane(emblemLayer, pipRow);
        StackPane.setAlignment(pipRow, Pos.BOTTOM_CENTER);
        double area = emblemHeight + 44;
        emblemStack.setMinHeight(area);
        emblemStack.setPrefHeight(area);
        emblemStack.setMaxHeight(area);

        // ── title: chevrons, "RANK PROMOTED", rank name letter by letter, tagline, caption ──
        VBox chevronBox = new VBox(-4);
        chevronBox.setAlignment(Pos.CENTER);
        for (int i = 0; i < 3; i++) {
            SVGPath c = new SVGPath();
            c.setContent("M0 9 L11 0 L22 9");
            c.setFill(null);
            c.setStroke(accentColor.interpolate(Color.WHITE, 0.25));
            c.setStrokeWidth(2.4);
            c.setStrokeLineCap(StrokeLineCap.ROUND);
            c.setStrokeLineJoin(javafx.scene.shape.StrokeLineJoin.ROUND);
            c.setOpacity(0.25);
            chevrons.add(c);
            chevronBox.getChildren().add(c);
        }

        Label promoted = new Label("RANK PROMOTED");
        promoted.getStyleClass().add("rankup-promoted");

        HBox nameRow = new HBox(0);
        nameRow.setAlignment(Pos.CENTER);
        nameRow.setStyle("-fx-effect: dropshadow(gaussian, " + accentHex + ", 24, 0.5, 0, 0);");
        for (char ch : to.label().toCharArray()) {
            Label l = new Label(ch == ' ' ? " " : String.valueOf(ch));
            l.getStyleClass().add("rankup-rankname");
            l.setStyle("-fx-text-fill: " + accentHex + ";");
            l.setOpacity(0);
            l.setTranslateY(22);
            letters.add(l);
            nameRow.getChildren().add(l);
        }

        Rectangle sweep = new Rectangle(46, 220);
        sweep.setFill(new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.color(1, 1, 1, 0)),
                new Stop(0.5, Color.color(1, 1, 1, 0.65)),
                new Stop(1, Color.color(1, 1, 1, 0))));
        sweep.setRotate(22);
        sweep.setMouseTransparent(true);
        sweep.setBlendMode(BlendMode.SCREEN);
        sweep.setOpacity(0);
        sweep.setTranslateX(-260);
        shimmer = sweep;

        StackPane nameFrame = new StackPane(nameRow, sweep);
        nameFrame.setAlignment(Pos.CENTER);
        nameFrame.setPadding(new Insets(4, 26, 4, 26));
        Rectangle nameClip = new Rectangle();
        nameFrame.widthProperty().addListener((o, a, b) -> nameClip.setWidth(nameFrame.getWidth()));
        nameFrame.heightProperty().addListener((o, a, b) -> nameClip.setHeight(nameFrame.getHeight()));
        nameFrame.setClip(nameClip);

        VBox taglineBlock = new VBox(2);
        taglineBlock.setAlignment(Pos.CENTER);
        if (celestial) {
            Label ult = new Label("The Ultimate Rank");
            ult.getStyleClass().add("rankup-tagline");
            ult.setStyle("-fx-text-fill: " + accentHex + ";");
            taglineBlock.getChildren().add(ult);
        } else if (to.tier().tagline != null && !to.tier().tagline.isBlank()) {
            Label tag = new Label(to.tier().tagline);
            tag.getStyleClass().add("rankup-tagline");
            taglineBlock.getChildren().add(tag);
        }

        Label caption = new Label("You've reached a new rank");
        caption.getStyleClass().add("rankup-caption");
        HBox captionRow = new HBox(12, rule(), caption, rule());
        captionRow.setAlignment(Pos.CENTER);

        VBox title = new VBox(6, chevronBox, promoted, nameFrame, taglineBlock, captionRow);
        title.setAlignment(Pos.CENTER);
        title.setOpacity(0);
        title.setTranslateY(16);
        titleGroup = title;

        // ── previous / AP gained / next cards, plus the AP progress bar ──────────────
        RankStanding nextApprox = to.isMax() ? null : RankCalculator.standing(cfg, to.nextAt());
        RankConfig.Tier nextTier = nextApprox != null ? nextApprox.tier() : to.tier();
        int nextDivision = nextApprox != null ? nextApprox.division() : to.division();
        String nextValue = to.isMax() ? "Top Rank" : to.nextLabel();

        HBox prev = statBlock(from.tier(), from.division(), "Previous Rank", from.label());
        prev.getStyleClass().add("rankup-card");
        HBox.setHgrow(prev, Priority.ALWAYS);
        prev.setMaxWidth(Double.MAX_VALUE);
        prevCard = prev;

        Label apValue = new Label("+0 AP");
        apValue.getStyleClass().add("rankup-ap-value");
        apValue.setStyle("-fx-text-fill: " + accentHex + ";");
        apValue.setEffect(new DropShadow(12, Color.color(accentColor.getRed(), accentColor.getGreen(), accentColor.getBlue(), 0.5)));
        apCount = new SimpleDoubleProperty(0);
        apCount.addListener((o, a, b) -> apValue.setText("+" + fmt(Math.round(b.doubleValue())) + " AP"));
        Label apCaption = new Label("AP GAINED");
        apCaption.getStyleClass().add("rankup-ap-caption");
        VBox ap = new VBox(4, apValue, apCaption);
        ap.setAlignment(Pos.CENTER);
        ap.getStyleClass().addAll("rankup-card", "rankup-card-ap");
        ap.setStyle("-fx-border-color: " + accentHex + " transparent transparent transparent; -fx-border-width: 2 0 0 0;");
        apCard = ap;

        HBox next = statBlock(nextTier, nextDivision, "Next Rank", nextValue);
        next.getStyleClass().add("rankup-card");
        HBox.setHgrow(next, Priority.ALWAYS);
        next.setMaxWidth(Double.MAX_VALUE);
        nextCard = next;

        for (Node n : new Node[] {prev, ap, next}) n.setOpacity(0);
        prev.setTranslateX(-28);
        next.setTranslateX(28);
        ap.setTranslateY(14);
        ap.setScaleX(0.85);
        ap.setScaleY(0.85);

        HBox stats = new HBox(10, prev, arrowIcon(), ap, arrowIcon(), next);
        stats.setAlignment(Pos.CENTER);
        stats.setFillHeight(true);

        barProgress = new SimpleDoubleProperty(0);
        StackPane bar = buildBar(accentHex, accentColor, to.tier().divisions);
        String barText = fmt(to.ap()) + " / " + (to.isMax() ? fmt(to.ap()) + " AP · Max Rank" : fmt(to.nextAt()) + " AP");
        Label barCaption = new Label(barText);
        barCaption.getStyleClass().add("rankup-bartext");
        VBox barWrap = new VBox(8, bar, barCaption);
        barWrap.getStyleClass().add("rankup-barwrap");
        barWrap.setAlignment(Pos.CENTER);

        VBox statsSection = new VBox(16, stats, barWrap);
        statsSection.setAlignment(Pos.CENTER);
        statsGroup = statsSection;
        // statsSection itself stays opaque; the individual cards and the bar wrap are staged.
        barWrap.setOpacity(0);

        // ── Continue ─────────────────────────────────────────────────────────
        SVGPath arrow = new SVGPath();
        arrow.setContent("M4 10 H15 M10 5 L15 10 L10 15");
        arrow.getStyleClass().add("rankup-arrow-icon");
        arrow.setFill(null);
        AvButton continueBtn = new AvButton("Continue", AvButton.Kind.PRIMARY);
        continueBtn.setGraphic(arrow);
        continueBtn.setContentDisplay(ContentDisplay.RIGHT);
        continueBtn.setGraphicTextGap(10);
        continueBtn.getStyleClass().add("rankup-continue");
        continueBtn.setOnAction(e -> dismiss());
        VBox continueWrap = new VBox(continueBtn);
        continueWrap.setAlignment(Pos.CENTER);
        continueWrap.setOpacity(0);
        continueWrap.setTranslateY(16);
        continueWrap.setDisable(true);
        continueGroup = continueWrap;

        VBox center = new VBox(18, header, emblemStack, title, statsSection, continueWrap);
        center.setAlignment(Pos.CENTER);
        center.setMaxWidth(660);
        center.setPadding(new Insets(52, 24, 22, 24));
        centerBox = center;

        // Endless rotations (only when motion is on; reduced motion shows the rings still).
        if (Motion.enabled()) {
            spin(ring1, celestial ? 22 : 26, 360);
            spin(ring2, celestial ? 30 : 36, -360);
            spin(ring3, celestial ? 16 : 19, 360);
            spin(rays, celestial ? 48 : 58, -360);
            spin(dialPath, celestial ? 70 : 90, -360);
            spin(orbit1, celestial ? 7 : 9, 360);
            spin(orbit2, celestial ? 11 : 14, -360);
        }

        StackPane frame = new StackPane(center,
                frameCorner(Pos.TOP_LEFT), frameCorner(Pos.TOP_RIGHT),
                frameCorner(Pos.BOTTOM_LEFT), frameCorner(Pos.BOTTOM_RIGHT));
        frame.setMaxWidth(660);
        frameNode = frame;

        // Shrink the whole composition to fit short/narrow windows instead of overflowing it.
        fit = () -> {
            double h = getHeight(), w = getWidth();
            if (h <= 0 || w <= 0 || centerBox == null || frameNode == null) return;
            double need = centerBox.prefHeight(660);
            double s = need <= 0 ? 1.0 : Math.min(1.0, (h - 96) / need);
            s = Math.min(s, (w - 24) / 660.0);
            s = Math.max(0.5, s);
            frameNode.setScaleX(s);
            frameNode.setScaleY(s);
        };
        return frame;
    }

    /** A small L-shaped bracket pinned to one corner of the content card — a quiet HUD-frame touch. */
    private static Node frameCorner(Pos pos) {
        double s = 22;
        SVGPath p = new SVGPath();
        p.setContent("M0 " + s + " L0 0 L" + s + " 0");
        p.getStyleClass().add("rankup-frame-corner");
        p.setMouseTransparent(true);
        if (pos == Pos.TOP_RIGHT) p.setRotate(90);
        else if (pos == Pos.BOTTOM_RIGHT) p.setRotate(180);
        else if (pos == Pos.BOTTOM_LEFT) p.setRotate(270);
        StackPane.setAlignment(p, pos);
        StackPane.setMargin(p, new Insets(10));
        return p;
    }

    private HBox buildFooter() {
        Label left = new Label("Play / Explore / Achieve");
        left.getStyleClass().add("rankup-footer-text");
        Region spring = new Region();
        HBox.setHgrow(spring, Priority.ALWAYS);
        Label right = new Label("Avero Launcher");
        right.getStyleClass().add("rankup-footer-text");
        HBox footer = new HBox(left, spring, right);
        footer.getStyleClass().add("rankup-footer");
        footer.setMaxWidth(Double.MAX_VALUE);
        footer.setOpacity(0);
        StackPane.setAlignment(footer, Pos.BOTTOM_CENTER);
        footerBar = footer;
        return footer;
    }

    private HBox statBlock(RankConfig.Tier tier, int division, String eyebrow, String value) {
        RankBadge badge = new RankBadge(tier, division, 40, false);
        Label e = new Label(eyebrow.toUpperCase(Locale.ROOT));
        e.getStyleClass().add("rankup-stat-label");
        Label v = new Label(value);
        v.getStyleClass().add("rankup-stat-value");
        VBox text = new VBox(2, e, v);
        HBox row = new HBox(10, badge, text);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static Node arrowIcon() {
        SVGPath p = new SVGPath();
        p.setContent("M3 2 L9 8 L3 14");
        p.getStyleClass().add("rankup-chev-icon");
        p.setFill(null);
        p.setMouseTransparent(true);
        return p;
    }

    private Region rule() {
        Region r = new Region();
        r.getStyleClass().add("rankup-rule");
        return r;
    }

    /**
     * The whole rank ladder as a strip of tiny badges joined by links: everything up to the new tier
     * lights up in sequence (each in its own tier colour) and the new tier pops with a glow.
     */
    private HBox buildLadder(RankConfig cfg, RankStanding to) {
        HBox row = new HBox(0);
        row.setAlignment(Pos.CENTER);
        row.getStyleClass().add("rankup-ladder");
        int n = cfg.ranks.size();
        if (n == 0) return row;
        ladderCur = 0;
        for (int i = 0; i < n; i++) if (cfg.ranks.get(i).id.equals(to.tier().id)) ladderCur = i;
        double itemH = n <= 8 ? 30 : n <= 12 ? 24 : 18;
        double link = Math.max(4, Math.min(18, (560 - n * itemH) / Math.max(1, n - 1)));
        for (int i = 0; i < n; i++) {
            RankConfig.Tier t = cfg.ranks.get(i);
            StackPane holder = new StackPane(new RankBadge(t, 0, itemH, false));
            holder.setOpacity(i <= ladderCur ? 0.35 : 0.2);
            ladderBadges.add(holder);
            ladderAccents.add(RankBadge.accent(t));
            row.getChildren().add(holder);
            if (i < n - 1) {
                Region l = new Region();
                l.getStyleClass().add("rankup-ladder-link");
                l.setMinSize(link, 2);
                l.setPrefSize(link, 2);
                l.setMaxSize(link, 2);
                ladderLinks.add(l);
                row.getChildren().add(l);
            }
        }
        row.setPadding(new Insets(10, 0, 0, 0));
        return row;
    }

    private void lightLadderStep(int i, boolean animated) {
        if (i < 0 || i >= ladderBadges.size()) return;
        Node b = ladderBadges.get(i);
        if (animated) tween(b.opacityProperty(), 1.0, 260, Interpolator.EASE_OUT);
        else b.setOpacity(1.0);
        if (i > 0 && i - 1 < ladderLinks.size()) {
            String a = ladderAccents.get(i);
            ladderLinks.get(i - 1).setStyle("-fx-background-color: linear-gradient(to right, "
                    + ladderAccents.get(i - 1) + ", " + a + ");");
        }
        if (i == ladderCur) {
            b.setEffect(new DropShadow(16, safeColor(ladderAccents.get(i))));
            if (animated) {
                tween(b.scaleXProperty(), 1.4, 420, BACK);
                tween(b.scaleYProperty(), 1.4, 420, BACK);
            } else {
                b.setScaleX(1.4);
                b.setScaleY(1.4);
            }
        }
    }

    private HBox buildPips(RankStanding to, Color accent) {
        HBox row = new HBox(7);
        row.setAlignment(Pos.CENTER);
        row.setMouseTransparent(true);
        int n = Math.max(0, to.tier().divisions);
        double size = 22;
        for (int i = 1; i <= n; i++) {
            boolean reached = i <= to.division();
            double w = size, h = size * 0.86;
            Polygon hex = new Polygon(w * 0.25, 0, w * 0.75, 0, w, h / 2, w * 0.75, h, w * 0.25, h, 0, h / 2);
            hex.setFill(reached ? accent.deriveColor(0, 1, 0.95, 0.95) : Color.web("#0b1118"));
            hex.setStroke(reached ? hotCol : accent.deriveColor(0, 0.5, 0.8, 0.45));
            hex.setStrokeWidth(reached ? 1.4 : 1.0);
            StackPane pip = new StackPane(hex);
            Label l = new Label(RankStanding.roman(i));
            l.getStyleClass().add("rank-pip-label");
            l.setStyle("-fx-text-fill: " + (reached ? "#0b1118" : "rgba(236,232,225,0.55)")
                    + "; -fx-font-size: " + Math.round(size * 0.42) + "px;");
            pip.getChildren().add(l);
            if (reached) pip.setEffect(new DropShadow(10, accent));
            pip.setOpacity(0);
            pip.setScaleX(0.01);
            pip.setScaleY(0.01);
            pips.add(pip);
            pipReached.add(reached);
            row.getChildren().add(pip);
        }
        return row;
    }

    /** The AP bar: a glossy tier-accent fill, a travelling spark at its tip, and a tick at every division boundary. */
    private StackPane buildBar(String accentHex, Color accent, int divisions) {
        StackPane bar = new StackPane();
        bar.getStyleClass().add("rank-bar-track");
        bar.setMaxWidth(Double.MAX_VALUE);

        Region fill = new Region();
        fill.setStyle("-fx-background-color: linear-gradient(to right, " + accentHex + "70, " + accentHex + "), "
                + "linear-gradient(to bottom, rgba(255,255,255,0.38) 0%, rgba(255,255,255,0) 45%);"
                + "-fx-effect: dropshadow(gaussian, " + accentHex + "99, 10, 0.3, 0, 0);");
        fill.setManaged(false);
        bar.getChildren().add(fill);

        List<Region> ticks = new ArrayList<>();
        for (int i = 1; i < Math.max(1, divisions); i++) {
            Region t = new Region();
            t.getStyleClass().add("rankup-bar-tick");
            t.setManaged(false);
            ticks.add(t);
            bar.getChildren().add(t);
        }

        Circle spark = new Circle(5.5);
        spark.setFill(new RadialGradient(0, 0, 0.5, 0.5, 0.5, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.WHITE), new Stop(0.45, hotCol), new Stop(1, Color.color(accent.getRed(), accent.getGreen(), accent.getBlue(), 0))));
        spark.setEffect(new DropShadow(12, accent));
        spark.setManaged(false);
        spark.setMouseTransparent(true);
        spark.setVisible(false);
        bar.getChildren().add(spark);

        boolean[] lit = new boolean[ticks.size()];
        Runnable relayout = () -> {
            double w = bar.getWidth(), h = bar.getHeight();
            double p = clamp01(barProgress.get());
            fill.resizeRelocate(0, 0, w * p, h);
            for (int i = 0; i < ticks.size(); i++) {
                double frac = (i + 1.0) / divisions;
                Region t = ticks.get(i);
                t.resizeRelocate(w * frac - 0.5, 0, 1, h);
                boolean passed = p >= frac;
                if (passed != lit[i]) {
                    lit[i] = passed;
                    t.setStyle(passed ? "-fx-background-color: rgba(255,255,255,0.85);" : null);
                }
            }
            spark.setVisible(p > 0.012);
            spark.setCenterX(w * p);
            spark.setCenterY(h / 2);
        };
        bar.widthProperty().addListener((o, a, b) -> relayout.run());
        bar.heightProperty().addListener((o, a, b) -> relayout.run());
        barProgress.addListener((o, a, b) -> relayout.run());
        return bar;
    }

    /** A fan of thin tapered rays radiating from a shared center point, for dramatic light behind the emblem. */
    private static Group buildRays(Color accentColor, int count, double length) {
        Group g = new Group();
        for (int i = 0; i < count; i++) {
            // Alternate long/short rays for a more detailed, star-like silhouette.
            double len = (i % 2 == 0) ? length : length * 0.72;
            Rectangle ray = new Rectangle(-1.1, -len, 2.2, len);
            ray.setFill(new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
                    new Stop(0, Color.color(accentColor.getRed(), accentColor.getGreen(), accentColor.getBlue(), 0)),
                    new Stop(0.65, Color.color(accentColor.getRed(), accentColor.getGreen(), accentColor.getBlue(), 0.20)),
                    new Stop(1, Color.color(accentColor.getRed(), accentColor.getGreen(), accentColor.getBlue(), 0))));
            ray.getTransforms().add(new Rotate(360.0 / count * i, 0, 0));
            g.getChildren().add(ray);
        }
        // Invisible symmetric anchor so the group's rotation pivot stays on the emblem centre.
        Circle anchor = new Circle(length);
        anchor.setFill(Color.TRANSPARENT);
        g.getChildren().add(anchor);
        g.setMouseTransparent(true);
        g.setBlendMode(BlendMode.SCREEN);
        return g;
    }

    /** Small glowing diamonds placed symmetrically (so the group rotates about the emblem centre). */
    private Group orbiters(Color accent, double radius, double size, double[] anglesDeg) {
        Group g = new Group();
        for (double a : anglesDeg) {
            double rad = Math.toRadians(a);
            Rectangle d = new Rectangle(-size / 2, -size / 2, size, size);
            d.setFill(hotCol);
            d.setRotate(45);
            d.setTranslateX(Math.cos(rad) * radius);
            d.setTranslateY(Math.sin(rad) * radius);
            d.setEffect(new DropShadow(9, accent));
            g.getChildren().add(d);
        }
        g.setMouseTransparent(true);
        return g;
    }

    private static String dialContent(double radius, int ticks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ticks; i++) {
            double ang = Math.PI * 2 * i / ticks - Math.PI / 2;
            double r2 = radius + (i % 6 == 0 ? 9 : 4);
            sb.append(String.format(Locale.US, "M%.2f %.2f L%.2f %.2f ",
                    radius * Math.cos(ang), radius * Math.sin(ang), r2 * Math.cos(ang), r2 * Math.sin(ang)));
        }
        return sb.toString();
    }

    private static Polygon hex(double r) {
        Polygon p = new Polygon();
        for (int i = 0; i < 6; i++) {
            double ang = Math.PI / 3 * i - Math.PI / 2;
            p.getPoints().addAll(r * Math.cos(ang), r * Math.sin(ang));
        }
        p.setFill(null);
        return p;
    }

    private static RadialGradient radial(Color c, double a0) {
        return new RadialGradient(0, 0, 0.5, 0.5, 0.5, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.color(c.getRed(), c.getGreen(), c.getBlue(), a0)),
                new Stop(1, Color.color(c.getRed(), c.getGreen(), c.getBlue(), 0)));
    }

    private void spin(Node n, double seconds, double angle) {
        RotateTransition r = new RotateTransition(Duration.seconds(seconds), n);
        r.setByAngle(angle);
        r.setCycleCount(Animation.INDEFINITE);
        r.setInterpolator(Interpolator.LINEAR);
        idle.add(r);
        r.play();
    }

    // ------------------------------------------------------------------ cinematic schedule

    private void scheduleCinematic(boolean celestial) {
        int flashAt = celestial ? 1100 : 900;
        int titleAt = flashAt + 220;
        int statsAt = titleAt + 700;
        int barAt = statsAt + 520;
        int continueAt = barAt + 800;

        // Background atmosphere.
        tween(auroraLayer.opacityProperty(), 1.0, 1400, Interpolator.EASE_OUT);
        tween(gridLayer.opacityProperty(), 1.0, 1800, Interpolator.EASE_OUT);
        startAuroraDrift();

        // Build-up: emblem rises, rings open, the arc charges.
        at(Duration.millis(120), () -> {
            tween(raysGroup.opacityProperty(), 1.0, 760, Interpolator.EASE_OUT);
            tween(raysGroup.scaleXProperty(), 1.0, 900, Interpolator.EASE_OUT);
            tween(raysGroup.scaleYProperty(), 1.0, 900, Interpolator.EASE_OUT);
            tween(ringA.opacityProperty(), 1.0, 520, Interpolator.EASE_OUT);
            tween(ringA.scaleXProperty(), 1.0, 620, BACK);
            tween(ringA.scaleYProperty(), 1.0, 620, BACK);
            tween(ringB.opacityProperty(), 1.0, 680, Interpolator.EASE_OUT);
            tween(ringB.scaleXProperty(), 1.0, 780, BACK);
            tween(ringB.scaleYProperty(), 1.0, 780, BACK);
            tween(ringC.opacityProperty(), 1.0, 440, Interpolator.EASE_OUT);
            tween(ringC.scaleXProperty(), 1.0, 520, BACK);
            tween(ringC.scaleYProperty(), 1.0, 520, BACK);
            tween(dial.opacityProperty(), 1.0, 900, Interpolator.EASE_OUT);
            tween(glowCircle.opacityProperty(), 0.9, 700, Interpolator.EASE_OUT);
            tween(emblemGroup.opacityProperty(), 1.0, 620, Interpolator.EASE_OUT);
            tween(emblemGroup.scaleXProperty(), 1.0, 760, BACK);
            tween(emblemGroup.scaleYProperty(), 1.0, 760, BACK);
            tween(orbitA.opacityProperty(), 1.0, 700, Interpolator.EASE_OUT);
            tween(orbitB.opacityProperty(), 1.0, 900, Interpolator.EASE_OUT);
            tween(chargeArc.lengthProperty(), -360.0, Math.max(300, flashAt - 180), Interpolator.EASE_BOTH);
        });

        // Impact.
        at(Duration.millis(flashAt), () -> {
            tween(flash.opacityProperty(), celestial ? 0.95 : 0.85, 70, Interpolator.LINEAR);
            at(Duration.millis(80), () -> tween(flash.opacityProperty(), 0.0, 460, Interpolator.EASE_OUT));
            tween(screenFlash.opacityProperty(), celestial ? 0.22 : 0.15, 60, Interpolator.LINEAR);
            at(Duration.millis(70), () -> tween(screenFlash.opacityProperty(), 0.0, 520, Interpolator.EASE_OUT));
            tween(chargeArc.opacityProperty(), 0.35, 700, Interpolator.EASE_OUT);
            tween(floorGlow.scaleYProperty(), 1.0, 900, Interpolator.EASE_OUT);

            for (int i = 0; i < shockwaves.size(); i++) {
                Node w = shockwaves.get(i);
                int idx = i;
                at(Duration.millis(i * 120), () -> expand(w, 0.35, 3.1 + idx * 0.5, 950 + idx * 150, 0.95 - idx * 0.15));
            }
            expand(beam, 0.04, 1.0, 760, 0.95, true);
            expand(flare, 0.1, 1.0, 640, 1.0, true);
            shake(celestial ? 7 : 5);
            spawnBurst(celestial);
            at(Duration.millis(190), () -> spawnBurst2(celestial));
            startIdle();

            // Pips pop in, in order; unreached ones just fade up dimly.
            for (int i = 0; i < pips.size(); i++) {
                StackPane p = pips.get(i);
                boolean reached = pipReached.get(i);
                at(Duration.millis(260 + i * 120), () -> {
                    tween(p.opacityProperty(), 1.0, 220, Interpolator.EASE_OUT);
                    tween(p.scaleXProperty(), 1.0, reached ? 460 : 240, reached ? BACK : Interpolator.EASE_OUT);
                    tween(p.scaleYProperty(), 1.0, reached ? 460 : 240, reached ? BACK : Interpolator.EASE_OUT);
                });
            }

            // Ladder lights up tier by tier.
            for (int i = 0; i <= ladderCur && i < ladderBadges.size(); i++) {
                int step = i;
                at(Duration.millis(160 + i * 65), () -> lightLadderStep(step, true));
            }
        });

        // Title: chevrons, label, then the name letter by letter, then the shine.
        at(Duration.millis(titleAt), () -> {
            tween(titleGroup.opacityProperty(), 1.0, 380, Interpolator.EASE_OUT);
            tween(titleGroup.translateYProperty(), 0.0, 420, Interpolator.EASE_OUT);
            startChevrons();
            for (int i = 0; i < letters.size(); i++) {
                Node l = letters.get(i);
                at(Duration.millis(140 + i * 48), () -> {
                    tween(l.opacityProperty(), 1.0, 260, Interpolator.EASE_OUT);
                    tween(l.translateYProperty(), 0.0, 420, BACK);
                });
            }
            at(Duration.millis(140 + letters.size() * 48 + 120), this::runShimmer);
        });

        // Stat cards: slide/pop in one after another, AP counts up.
        at(Duration.millis(statsAt), () -> {
            stageCard(prevCard, 0);
            stageCard(apCard, 120);
            stageCard(nextCard, 240);
            at(Duration.millis(260), () -> tween(apCount, apGained, 1100, Interpolator.EASE_OUT));
        });

        at(Duration.millis(barAt), () -> {
            tween(statsGroup.getChildren().get(1).opacityProperty(), 1.0, 300, Interpolator.EASE_OUT);
            tween(barProgress, targetProgress, 950, Interpolator.EASE_BOTH);
        });

        at(Duration.millis(continueAt), () -> {
            tween(continueGroup.opacityProperty(), 1.0, 380, Interpolator.EASE_OUT);
            tween(continueGroup.translateYProperty(), 0.0, 380, Interpolator.EASE_OUT);
            tween(footerBar.opacityProperty(), 1.0, 380, Interpolator.EASE_OUT);
            continueGroup.setDisable(false);
            atRest = true;
            scheduleAutoClose();
        });
    }

    private void stageCard(Node card, int delayMs) {
        at(Duration.millis(delayMs), () -> {
            tween(card.opacityProperty(), 1.0, 360, Interpolator.EASE_OUT);
            tween(card.translateXProperty(), 0.0, 520, BACK);
            tween(card.translateYProperty(), 0.0, 520, BACK);
            tween(card.scaleXProperty(), 1.0, 520, BACK);
            tween(card.scaleYProperty(), 1.0, 520, BACK);
        });
    }

    /** Scale-up + fade-out burst for shockwaves / beam / lens streak. {@code xOnly} scales just the width. */
    private void expand(Node n, double from, double to, double ms, double startOpacity) {
        expand(n, from, to, ms, startOpacity, false);
    }

    private void expand(Node n, double from, double to, double ms, double startOpacity, boolean xOnly) {
        n.setScaleX(from);
        if (!xOnly) n.setScaleY(from);
        n.setOpacity(startOpacity);
        List<KeyValue> kv = new ArrayList<>();
        kv.add(new KeyValue(n.scaleXProperty(), to, Interpolator.EASE_OUT));
        if (!xOnly) kv.add(new KeyValue(n.scaleYProperty(), to, Interpolator.EASE_OUT));
        kv.add(new KeyValue(n.opacityProperty(), 0.0, Interpolator.EASE_IN));
        track(new Timeline(new KeyFrame(Duration.millis(ms), kv.toArray(new KeyValue[0]))));
    }

    /** A short, damped camera shake of the whole content frame on impact. */
    private void shake(double amp) {
        if (frameNode == null) return;
        DoubleProperty x = frameNode.translateXProperty(), y = frameNode.translateYProperty();
        track(new Timeline(
                new KeyFrame(Duration.ZERO, kv(x, 0), kv(y, 0)),
                new KeyFrame(Duration.millis(45), kv(x, -amp), kv(y, amp * 0.6)),
                new KeyFrame(Duration.millis(95), kv(x, amp * 0.8), kv(y, -amp * 0.5)),
                new KeyFrame(Duration.millis(150), kv(x, -amp * 0.5), kv(y, amp * 0.3)),
                new KeyFrame(Duration.millis(210), kv(x, amp * 0.25), kv(y, -amp * 0.15)),
                new KeyFrame(Duration.millis(280), kv(x, 0), kv(y, 0))));
    }

    private static KeyValue kv(WritableValue<Number> p, double v) {
        return new KeyValue(p, v);
    }

    /** The three chevrons above the title pulse upward in sequence, forever. */
    private void startChevrons() {
        if (!Motion.enabled()) return;
        Timeline t = new Timeline();
        double period = 1.5;
        for (int i = 0; i < chevrons.size(); i++) {
            // bottom chevron (last) lights first
            double on = 0.18 * (chevrons.size() - 1 - i);
            Node c = chevrons.get(i);
            t.getKeyFrames().addAll(
                    new KeyFrame(Duration.seconds(on), new KeyValue(c.opacityProperty(), 0.2, Interpolator.EASE_OUT)),
                    new KeyFrame(Duration.seconds(on + 0.22), new KeyValue(c.opacityProperty(), 1.0, Interpolator.EASE_OUT)),
                    new KeyFrame(Duration.seconds(on + 0.65), new KeyValue(c.opacityProperty(), 0.2, Interpolator.EASE_IN)));
        }
        t.getKeyFrames().add(new KeyFrame(Duration.seconds(period)));
        t.setCycleCount(Animation.INDEFINITE);
        idle.add(t);
        t.play();
    }

    private void startAuroraDrift() {
        if (auroraStarted || !Motion.enabled() || !(auroraLayer instanceof Pane)) return;
        auroraStarted = true;
        Object a = ((Pane) auroraLayer).getProperties().get("blobA");
        Object b = ((Pane) auroraLayer).getProperties().get("blobB");
        if (a instanceof Node) {
            drift((Node) a, 60, 36, 11);
        }
        if (b instanceof Node) {
            drift((Node) b, -70, -30, 14);
        }
    }

    private void drift(Node n, double dx, double dy, double seconds) {
        Timeline t = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(n.translateXProperty(), 0.0, Interpolator.EASE_BOTH),
                        new KeyValue(n.translateYProperty(), 0.0, Interpolator.EASE_BOTH)),
                new KeyFrame(Duration.seconds(seconds), new KeyValue(n.translateXProperty(), dx, Interpolator.EASE_BOTH),
                        new KeyValue(n.translateYProperty(), dy, Interpolator.EASE_BOTH)));
        t.setAutoReverse(true);
        t.setCycleCount(Animation.INDEFINITE);
        idle.add(t);
        t.play();
    }

    /** Gentle "alive" loops once the emblem has landed: glow breathes, badge bobs, floor pulses. */
    private void startIdle() {
        if (idleStarted || !Motion.enabled()) return;
        idleStarted = true;
        breathe(glowCircle.scaleXProperty(), 0.93, 1.07, 2.3);
        breathe(glowCircle.scaleYProperty(), 0.93, 1.07, 2.3);
        breathe(bobNode.translateYProperty(), -4.5, 4.5, 2.8);
        breathe(floorGlow.scaleXProperty(), 0.88, 1.08, 2.8);
        breathe(floorGlow.opacityProperty(), 0.65, 1.0, 2.8);
        breathe(raysGroup.opacityProperty(), 0.7, 1.0, 3.2);
    }

    private void breathe(DoubleProperty p, double a, double b, double seconds) {
        Timeline t = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(p, a, Interpolator.EASE_BOTH)),
                new KeyFrame(Duration.seconds(seconds), new KeyValue(p, b, Interpolator.EASE_BOTH)));
        t.setAutoReverse(true);
        t.setCycleCount(Animation.INDEFINITE);
        idle.add(t);
        t.play();
    }

    /** Auto-dismisses 8s after the player has had the full reveal to read — cancelled by any manual dismiss. */
    private void scheduleAutoClose() {
        PauseTransition close = Motion.after(Duration.seconds(8), this::dismiss);
        pending.add(close);
        close.play();
    }

    /** One-shot diagonal shine sweeping across the rank name, like a freshly-minted medal catching light. */
    private void runShimmer() {
        if (shimmer == null) return;
        shimmer.setTranslateX(-260);
        shimmer.setOpacity(1.0);
        tween(shimmer.translateXProperty(), 260.0, 760, Interpolator.EASE_BOTH);
        at(Duration.millis(780), () -> shimmer.setOpacity(0));
    }

    private void at(Duration delay, Runnable action) {
        PauseTransition p = Motion.after(delay, action);
        pending.add(p);
        p.play();
    }

    /** Eases {@code p} to {@code to}; instant when animations are off. Tracked so skip/dismiss can stop it. */
    private void tween(DoubleProperty p, double to, double ms, Interpolator ip) {
        if (!Motion.enabled() || ms <= 0) {
            p.set(to);
            return;
        }
        track(new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(p, p.get())),
                new KeyFrame(Duration.millis(ms), new KeyValue(p, to, ip))));
    }

    private void track(Timeline t) {
        tweens.add(t);
        t.setOnFinished(e -> tweens.remove(t));
        t.play();
    }

    /** Jumps straight to the fully-revealed resting state; used by "Skip animation", Esc, and reduced motion. */
    private void skipAnimation() {
        if (atRest) return;
        settleAll();
    }

    private void settleAll() {
        for (PauseTransition p : pending) p.stop();
        pending.clear();
        for (Timeline t : new ArrayList<>(tweens)) t.stop();
        tweens.clear();

        auroraLayer.setOpacity(1.0);
        gridLayer.setOpacity(1.0);
        raysGroup.setOpacity(1.0);
        raysGroup.setScaleX(1.0);
        raysGroup.setScaleY(1.0);
        for (Node r : new Node[] {ringA, ringB, ringC}) {
            r.setOpacity(1.0);
            r.setScaleX(1.0);
            r.setScaleY(1.0);
        }
        dial.setOpacity(1.0);
        orbitA.setOpacity(1.0);
        orbitB.setOpacity(1.0);
        chargeArc.setLength(-360);
        chargeArc.setOpacity(0.35);
        glowCircle.setOpacity(0.9);
        floorGlow.setOpacity(1.0);
        floorGlow.setScaleY(1.0);
        emblemGroup.setOpacity(1.0);
        emblemGroup.setScaleX(1.0);
        emblemGroup.setScaleY(1.0);
        for (Node n : new Node[] {flash, beam, flare, screenFlash}) n.setOpacity(0);
        for (Node n : shockwaves) n.setOpacity(0);
        frameNode.setTranslateX(0);
        frameNode.setTranslateY(0);
        for (StackPane p : pips) {
            p.setOpacity(1.0);
            p.setScaleX(1.0);
            p.setScaleY(1.0);
        }
        for (int i = 0; i <= ladderCur && i < ladderBadges.size(); i++) lightLadderStep(i, false);
        titleGroup.setOpacity(1.0);
        titleGroup.setTranslateY(0);
        for (Node c : chevrons) c.setOpacity(1.0);
        for (Node l : letters) {
            l.setOpacity(1.0);
            l.setTranslateY(0);
        }
        if (shimmer != null) shimmer.setOpacity(0);
        for (Node c : new Node[] {prevCard, apCard, nextCard}) {
            c.setOpacity(1.0);
            c.setTranslateX(0);
            c.setTranslateY(0);
            c.setScaleX(1.0);
            c.setScaleY(1.0);
        }
        if (apCount != null) apCount.set(apGained);
        if (statsGroup != null && statsGroup.getChildren().size() > 1) statsGroup.getChildren().get(1).setOpacity(1.0);
        if (barProgress != null) barProgress.set(targetProgress);
        continueGroup.setOpacity(1.0);
        continueGroup.setTranslateY(0);
        continueGroup.setDisable(false);
        footerBar.setOpacity(1.0);
        atRest = true;
        startIdle();
        startAuroraDrift();
        scheduleAutoClose();
    }

    // ------------------------------------------------------------------ particles

    private void startParticles() {
        for (int i = 0; i < 56; i++) ambient.add(spawnAmbient());
        timer = new AnimationTimer() {
            private long last = -1;

            @Override
            public void handle(long now) {
                if (last < 0) {
                    last = now;
                    return;
                }
                double dt = Math.min(0.05, (now - last) / 1_000_000_000.0);
                last = now;
                step(dt);
            }
        };
        timer.start();
    }

    private Particle spawnAmbient() {
        Particle p = new Particle();
        double w = Math.max(getWidth(), 960), h = Math.max(getHeight(), 640);
        p.x = rnd.nextDouble() * w;
        p.y = rnd.nextDouble() * h;
        p.vx = (rnd.nextDouble() - 0.5) * 6;
        p.vy = -6 - rnd.nextDouble() * 14;
        boolean star = rnd.nextDouble() < 0.28;
        p.kind = star ? Particle.STAR : Particle.DOT;
        p.size = star ? 2.2 + rnd.nextDouble() * 2.6 : 1 + rnd.nextDouble() * 2.2;
        p.alpha = star ? 0.25 + rnd.nextDouble() * 0.3 : 0.08 + rnd.nextDouble() * 0.17;
        p.phase = rnd.nextDouble() * Math.PI * 2;
        p.twinkle = 1.2 + rnd.nextDouble() * 2.4;
        p.hot = rnd.nextDouble() < 0.35;
        p.maxLife = 0; // ambient never expires
        return p;
    }

    /** Impact centre in canvas coordinates: the real emblem centre, falling back to a sane guess. */
    private Point2D emblemCenter() {
        try {
            if (emblemAnchor != null && emblemAnchor.getScene() != null && canvas != null) {
                Bounds b = emblemAnchor.localToScene(emblemAnchor.getBoundsInLocal());
                return canvas.sceneToLocal((b.getMinX() + b.getMaxX()) / 2, (b.getMinY() + b.getMaxY()) / 2);
            }
        } catch (Exception ignored) { }
        return new Point2D(Math.max(1, getWidth()) / 2.0, Math.max(260, getHeight() * 0.40));
    }

    private void spawnBurst(boolean celestial) {
        if (canvas == null) return;
        Point2D c = emblemCenter();
        int n = celestial ? 150 : 86;
        for (int i = 0; i < n; i++) {
            Particle p = new Particle();
            double ang = rnd.nextDouble() * Math.PI * 2;
            double speed = 70 + rnd.nextDouble() * (celestial ? 380 : 300);
            p.x = c.getX();
            p.y = c.getY();
            p.vx = Math.cos(ang) * speed;
            p.vy = Math.sin(ang) * speed;
            double k = rnd.nextDouble();
            p.kind = k < 0.55 ? Particle.STREAK : k < 0.85 ? Particle.DOT : Particle.STAR;
            p.size = p.kind == Particle.STAR ? 3 + rnd.nextDouble() * 4 : 1.4 + rnd.nextDouble() * 2.4;
            p.alpha = 0.6 + rnd.nextDouble() * 0.4;
            p.gravity = p.kind == Particle.STREAK ? 130 : 40;
            p.life = 0;
            p.maxLife = 0.9 + rnd.nextDouble() * 0.8;
            p.phase = rnd.nextDouble() * Math.PI * 2;
            p.twinkle = 8 + rnd.nextDouble() * 8;
            p.hot = rnd.nextDouble() < 0.5;
            burst.add(p);
        }
    }

    /** A second, softer wave a beat after impact: slow glowing motes that float outward and fade. */
    private void spawnBurst2(boolean celestial) {
        if (canvas == null) return;
        Point2D c = emblemCenter();
        int n = celestial ? 60 : 34;
        for (int i = 0; i < n; i++) {
            Particle p = new Particle();
            double ang = rnd.nextDouble() * Math.PI * 2;
            double speed = 20 + rnd.nextDouble() * 90;
            p.x = c.getX() + Math.cos(ang) * 40;
            p.y = c.getY() + Math.sin(ang) * 40;
            p.vx = Math.cos(ang) * speed;
            p.vy = Math.sin(ang) * speed - 18;
            p.kind = rnd.nextDouble() < 0.4 ? Particle.STAR : Particle.DOT;
            p.size = 2 + rnd.nextDouble() * 3.2;
            p.alpha = 0.35 + rnd.nextDouble() * 0.35;
            p.gravity = -6;
            p.life = 0;
            p.maxLife = 1.6 + rnd.nextDouble() * 1.4;
            p.phase = rnd.nextDouble() * Math.PI * 2;
            p.twinkle = 3 + rnd.nextDouble() * 4;
            p.hot = rnd.nextDouble() < 0.5;
            burst.add(p);
        }
    }

    private void step(double dt) {
        if (gc == null) return;
        clock += dt;
        double w = canvas.getWidth(), h = canvas.getHeight();
        gc.setGlobalAlpha(1.0);
        gc.clearRect(0, 0, w, h);

        for (Particle p : ambient) {
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            if (p.y < -10) {
                p.y = h + 10;
                p.x = rnd.nextDouble() * Math.max(1, w);
            }
            if (p.x < -10) p.x = w + 10;
            else if (p.x > w + 10) p.x = -10;
            double tw = 0.65 + 0.35 * Math.sin(clock * p.twinkle + p.phase);
            draw(p, p.alpha * tw, 1.0);
        }

        Iterator<Particle> it = burst.iterator();
        while (it.hasNext()) {
            Particle p = it.next();
            p.life += dt;
            if (p.life >= p.maxLife) {
                it.remove();
                continue;
            }
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            double drag = Math.min(1, 1.4 * dt);
            p.vx *= (1 - drag);
            p.vy = p.vy * (1 - drag) + p.gravity * dt;
            double fade = Math.max(0, 1 - (p.life / p.maxLife));
            double flicker = p.kind == Particle.STAR ? 0.7 + 0.3 * Math.sin(clock * p.twinkle + p.phase) : 1.0;
            draw(p, Math.min(1, p.alpha * fade * fade * 1.15) * flicker, fade);
        }
        gc.setGlobalAlpha(1.0);
    }

    private void draw(Particle p, double alpha, double lifeScale) {
        if (alpha <= 0.003) return;
        Color col = p.hot ? hotCol : accentCol;
        gc.setGlobalAlpha(Math.min(1, alpha));
        gc.setFill(col);
        gc.setStroke(col);
        switch (p.kind) {
            case Particle.STAR: {
                double s = p.size * (0.6 + 0.4 * lifeScale);
                gc.setLineWidth(1.1);
                gc.strokeLine(p.x - s, p.y, p.x + s, p.y);
                gc.strokeLine(p.x, p.y - s, p.x, p.y + s);
                gc.fillOval(p.x - s * 0.22, p.y - s * 0.22, s * 0.44, s * 0.44);
                break;
            }
            case Particle.STREAK: {
                gc.setLineWidth(Math.max(0.8, p.size * 0.75 * (0.4 + 0.6 * lifeScale)));
                gc.strokeLine(p.x, p.y, p.x - p.vx * 0.05, p.y - p.vy * 0.05);
                gc.setGlobalAlpha(Math.min(1, alpha * 1.2));
                gc.setFill(Color.WHITE);
                gc.fillOval(p.x - p.size * 0.35, p.y - p.size * 0.35, p.size * 0.7, p.size * 0.7);
                break;
            }
            default: {
                // soft halo + bright core
                gc.setGlobalAlpha(Math.min(1, alpha * 0.25));
                gc.fillOval(p.x - p.size, p.y - p.size, p.size * 3, p.size * 3);
                gc.setGlobalAlpha(Math.min(1, alpha));
                gc.fillOval(p.x, p.y, p.size, p.size);
            }
        }
    }

    private static final class Particle {
        static final int DOT = 0, STREAK = 1, STAR = 2;
        int kind = DOT;
        boolean hot;
        double x, y, vx, vy, size, alpha, life, maxLife, gravity, phase, twinkle;
    }

    // ------------------------------------------------------------------ lifecycle / dismiss

    private void stopAll() {
        if (timer != null) {
            timer.stop();
            timer = null;
        }
        for (PauseTransition p : pending) p.stop();
        pending.clear();
        for (Timeline t : new ArrayList<>(tweens)) t.stop();
        tweens.clear();
        for (Animation a : idle) a.stop();
        idle.clear();
        fit = null;
    }

    private void dismiss() {
        boolean animated = Motion.enabled();
        stopAll();
        Runnable cb = onDismiss;
        Motion.animate(opacityProperty(), 0.0, Duration.millis(animated ? 220 : 0));
        PauseTransition hide = Motion.after(Duration.millis(animated ? 230 : 0), () -> {
            setVisible(false);
            setManaged(false);
            getChildren().clear();
            if (cb != null) cb.run();
        });
        pending.add(hide);
        hide.play();
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static String fmt(long v) {
        return String.format(Locale.US, "%,d", v);
    }

    private static Color safeColor(String hex) {
        try {
            return Color.web(hex);
        } catch (Exception e) {
            return Color.web("#a855f7");
        }
    }
}
