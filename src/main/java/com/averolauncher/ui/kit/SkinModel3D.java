package com.averolauncher.ui.kit;

import com.averolauncher.skins.SkinImage;
import com.averolauncher.ui.Motion;
import javafx.animation.AnimationTimer;
import javafx.application.ConditionalFeature;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.AmbientLight;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.PerspectiveCamera;
import javafx.scene.PointLight;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Translate;

import java.awt.image.BufferedImage;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A 3D Minecraft player built from a skin PNG: head, body, both arms and both legs, each with its
 * second (hat / jacket / sleeve / pants) layer. Drag to turn it * while it walks on the spot (arms and legs swing, body bobs). With animations off in Settings it just stands.
 *
 * <p>Usage: call {@link #prepare} off the FX thread (it decodes and upscales the skin), then
 * {@link #show} on the FX thread. {@link #showLoading()} and {@link #showEmpty(String)} cover the
 * other states. Frameless: it sits directly on the hero background. Display only.
 *
 * <p>Geometry is in skin pixels: the figure is 32 units tall, centred on the origin, facing the camera
 * (-Z), +Y pointing down as everywhere in JavaFX.
 */
public final class SkinModel3D extends StackPane {

    /** Texture upscale (nearest-neighbour) so the 64x64 skin stays crisp when magnified. */
    private static final int TEX_SCALE = 8;
    private static final double START_YAW = -32;
    private static final double STRIDE_HZ = 0.9;      // full walk cycles per second
    private static final double ARM_SWING = 34;       // degrees
    private static final double LEG_SWING = 30;

    /** Decoded skin textures, ready to hand to {@link #show}. Safe to build on any thread. */
    public record Prepared(Image base, Image overlay, boolean slim) { }

    private static final Map<String, Prepared> CACHE = new ConcurrentHashMap<>();

    private final Label hint = new Label();
    private final Skeleton skeleton;
    private final Group modelHolder = new Group();
    private final Rotate yaw = new Rotate(START_YAW, Rotate.Y_AXIS);
    private final Translate bob = new Translate();
    // Shoulder / hip joints; each is shared by a limb's base and overlay meshes so they swing together.
    private final Rotate rightArm = new Rotate(0, Rotate.X_AXIS);
    private final Rotate leftArm = new Rotate(0, Rotate.X_AXIS);
    private final Rotate rightLeg = new Rotate(0, Rotate.X_AXIS);
    private final Rotate leftLeg = new Rotate(0, Rotate.X_AXIS);
    private double phase;
    private final SubScene scene3d;
    private final AnimationTimer spinner;
    private final boolean supported;

    private boolean dragging;
    private double lastX;
    private long lastNanos;

    public SkinModel3D(double w, double h) {
        setMinSize(w, h);
        setPrefSize(w, h);
        setMaxSize(w, h);
        setAlignment(Pos.CENTER);

        supported = Platform.isSupported(ConditionalFeature.SCENE3D);

        hint.getStyleClass().add("av-cape-glyph");
        hint.setVisible(false);

        skeleton = new Skeleton(w, h);
        skeleton.setVisible(false);

        // 3D scene: transparent, so the hero background shows straight through behind the player.
        Group root = new Group();
        scene3d = new SubScene(root, w, h, true,
                Motion.enabled() ? SceneAntialiasing.BALANCED : SceneAntialiasing.DISABLED);
        scene3d.setFill(Color.TRANSPARENT);

        PerspectiveCamera camera = new PerspectiveCamera(true);
        camera.setFieldOfView(28);
        camera.setNearClip(1);
        camera.setFarClip(1000);
        // Orbit slightly above the player, looking down at the centre: rotate first, then pull back.
        camera.getTransforms().addAll(new Rotate(-9, Rotate.X_AXIS), new Translate(0, 0, -80));
        scene3d.setCamera(camera);

        AmbientLight ambient = new AmbientLight(Color.gray(0.74));
        PointLight key = new PointLight(Color.gray(0.52));
        key.setTranslateX(-70);
        key.setTranslateY(-90);
        key.setTranslateZ(-130);

        modelHolder.getTransforms().addAll(yaw, bob);
        root.getChildren().addAll(modelHolder, ambient, key);
        scene3d.setVisible(false);
        scene3d.setMouseTransparent(false);

        if (supported) {
            getChildren().addAll(hint, scene3d, skeleton);
            setCursor(Cursor.OPEN_HAND);
            installDrag();
        } else {
            getChildren().addAll(hint, skeleton);
        }

        spinner = new AnimationTimer() {
            @Override public void handle(long now) {
                if (lastNanos != 0 && Motion.enabled()) {
                    phase += (now - lastNanos) / 1e9 * STRIDE_HZ * 2 * Math.PI;
                    walkPose(phase);
                }
                lastNanos = now;
            }
        };
        // Spin only while on screen and showing a model: no idle cost on other pages.
        sceneProperty().addListener((o, was, now) -> syncSpinner());
        scene3d.visibleProperty().addListener((o, was, now) -> syncSpinner());
        Motion.enabledProperty().addListener((o, was, now) -> syncSpinner());
    }

    private void syncSpinner() {
        if (getScene() != null && scene3d.isVisible() && Motion.enabled()) {
            lastNanos = 0;
            spinner.start();
        } else {
            spinner.stop();
            walkPose(0);   // animations off (or hidden): stand still
        }
    }

    /** Opposite arm and leg swing together; the body bobs up at the midpoint of each step. */
    private void walkPose(double ph) {
        double s = Math.sin(ph);
        rightArm.setAngle(s * ARM_SWING);
        leftArm.setAngle(-s * ARM_SWING);
        rightLeg.setAngle(-s * LEG_SWING);
        leftLeg.setAngle(s * LEG_SWING);
        bob.setY(-Math.abs(Math.cos(ph)) * 0.7 + 0.35);
    }

    private void installDrag() {
        setOnMousePressed(e -> {
            dragging = true;
            lastX = e.getSceneX();
            setCursor(Cursor.CLOSED_HAND);
        });
        setOnMouseDragged(e -> {
            double dx = e.getSceneX() - lastX;
            lastX = e.getSceneX();
            yaw.setAngle(yaw.getAngle() + dx * 0.9);
        });
        setOnMouseReleased(e -> {
            dragging = false;
            setCursor(Cursor.OPEN_HAND);
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // States
    // ─────────────────────────────────────────────────────────────────────────

    public void showLoading() {
        scene3d.setVisible(false);
        hint.setVisible(false);
        skeleton.setVisible(true);
    }

    public void showEmpty(String text) {
        scene3d.setVisible(false);
        skeleton.setVisible(false);
        String t = supported ? text : "3D unavailable";
        hint.setText(t == null ? "" : t.toUpperCase(Locale.ROOT));
        hint.setVisible(true);
    }

    /** FX thread. Rebuilds the model from already-decoded textures. */
    public void show(Prepared p) {
        if (!supported) { showEmpty("3D unavailable"); return; }
        modelHolder.getChildren().setAll(buildPlayer(p));
        skeleton.setVisible(false);
        hint.setVisible(false);
        scene3d.setVisible(true);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Skin -> textures (any thread)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Decodes a skin PNG (64x64 or legacy 64x32) into two upscaled textures: an opaque one for the base
     * layer (the game ignores transparency there) and one that keeps its alpha for the overlay layer.
     *
     * @param variant "slim", "classic" or null to detect from the image
     * @param cacheKey optional; identical keys return the earlier result without decoding again
     */
    public static Prepared prepare(byte[] png, String variant, String cacheKey) throws SkinImage.InvalidSkinException {
        if (cacheKey != null) {
            Prepared hit = CACHE.get(cacheKey);
            if (hit != null) return hit;
        }
        BufferedImage skin = SkinImage.load(png);   // always 64x64 ARGB, legacy skins converted
        boolean slim = variant != null
                ? "slim".equalsIgnoreCase(variant)
                : SkinImage.looksSlim(skin);

        int n = 64 * TEX_SCALE;
        WritableImage base = new WritableImage(n, n);
        WritableImage overlay = new WritableImage(n, n);
        var bw = base.getPixelWriter();
        var ow = overlay.getPixelWriter();
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int argb = skin.getRGB(x / TEX_SCALE, y / TEX_SCALE);
                bw.setArgb(x, y, argb | 0xFF000000);
                ow.setArgb(x, y, argb);
            }
        }
        Prepared out = new Prepared(base, overlay, slim);
        if (cacheKey != null) {
            if (CACHE.size() >= 6) CACHE.clear();   // tiny cache: a handful of skins at most
            CACHE.put(cacheKey, out);
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Model
    // ─────────────────────────────────────────────────────────────────────────

    private Group buildPlayer(Prepared p) {
        PhongMaterial baseMat = material(p.base());
        PhongMaterial overMat = material(p.overlay());
        double aw = p.slim() ? 3 : 4;

        // All base layers first, then all overlays: a transparent overlay pixel still writes depth, so
        // drawing overlays last keeps them from hiding any base layer behind them.
        Group bases = new Group();
        Group overlays = new Group();
        Group g = new Group(bases, overlays);
        // part: size (w,h,d), centre (x,y,z), base UV origin, overlay UV origin, overlay inflation
        part(bases, overlays, baseMat, overMat, 8, 8, 8,   0, -12, 0,   0, 0,    32, 0,   0.5,  null);          // head / hat
        part(bases, overlays, baseMat, overMat, 8, 12, 4,  0, -2, 0,    16, 16,  16, 32,  0.25, null);         // body / jacket
        double armX = 4 + aw / 2;
        part(bases, overlays, baseMat, overMat, aw, 12, 4, -armX, -2, 0, 40, 16, 40, 32, 0.25, joint(rightArm, -armX, -8)); // right arm / sleeve
        part(bases, overlays, baseMat, overMat, aw, 12, 4,  armX, -2, 0, 32, 48, 48, 48, 0.25, joint(leftArm, armX, -8));   // left arm / sleeve
        part(bases, overlays, baseMat, overMat, 4, 12, 4, -2, 10, 0,    0, 16,   0, 32,  0.25, joint(rightLeg, -2, 4));      // right leg / pants
        part(bases, overlays, baseMat, overMat, 4, 12, 4,  2, 10, 0,    16, 48,  0, 48,  0.25, joint(leftLeg, 2, 4));        // left leg / pants
        return g;
    }

    /** Sets the pivot of a limb joint (top of the limb) and returns it. */
    private static Rotate joint(Rotate r, double x, double y) {
        r.setPivotX(x);
        r.setPivotY(y);
        r.setPivotZ(0);
        return r;
    }

    private static PhongMaterial material(Image tex) {
        PhongMaterial m = new PhongMaterial(Color.WHITE);
        m.setDiffuseMap(tex);
        m.setSpecularColor(null);   // skins are matte
        return m;
    }

    private static void part(Group bases, Group overlays, PhongMaterial baseMat, PhongMaterial overMat,
                             double w, double h, double d, double cx, double cy, double cz,
                             int bu, int bv, int ou, int ov, double grow, Rotate joint) {
        MeshView base = box(w, h, d, cx, cy, cz, bu, bv, 0);
        base.setMaterial(baseMat);
        MeshView over = box(w, h, d, cx, cy, cz, ou, ov, grow);
        over.setMaterial(overMat);
        if (joint != null) {
            base.getTransforms().add(joint);
            over.getTransforms().add(joint);
        }
        bases.getChildren().add(base);
        overlays.getChildren().add(over);
    }

    /**
     * One textured cuboid. Texture layout follows the skin format: with origin (u,v) and size W x H x D
     * the strip is [right | front | left | back] along v+D, with top and bottom above it. Every face has
     * its own four vertices, so the faces are flat shaded.
     *
     * <p>"Right" and "left" are the player's: the player faces the camera (-Z), so their right side is -X.
     */
    private static MeshView box(double w, double h, double d, double cx, double cy, double cz,
                                int u, int v, double grow) {
        double hw = w / 2 + grow, hh = h / 2 + grow, hd = d / 2 + grow;
        double x0 = cx - hw, x1 = cx + hw, y0 = cy - hh, y1 = cy + hh, z0 = cz - hd, z1 = cz + hd;
        int W = (int) w, H = (int) h, D = (int) d;

        // Corners per face in the order top-left, top-right, bottom-right, bottom-left as seen from outside.
        float[][] pos = {
                // front (-Z)
                { (float) x0, (float) y0, (float) z0,  (float) x1, (float) y0, (float) z0,  (float) x1, (float) y1, (float) z0,  (float) x0, (float) y1, (float) z0 },
                // back (+Z)
                { (float) x1, (float) y0, (float) z1,  (float) x0, (float) y0, (float) z1,  (float) x0, (float) y1, (float) z1,  (float) x1, (float) y1, (float) z1 },
                // player's right (-X)
                { (float) x0, (float) y0, (float) z1,  (float) x0, (float) y0, (float) z0,  (float) x0, (float) y1, (float) z0,  (float) x0, (float) y1, (float) z1 },
                // player's left (+X)
                { (float) x1, (float) y0, (float) z0,  (float) x1, (float) y0, (float) z1,  (float) x1, (float) y1, (float) z1,  (float) x1, (float) y1, (float) z0 },
                // top (-Y): image-up is the back of the head
                { (float) x0, (float) y0, (float) z1,  (float) x1, (float) y0, (float) z1,  (float) x1, (float) y0, (float) z0,  (float) x0, (float) y0, (float) z0 },
                // bottom (+Y)
                { (float) x0, (float) y1, (float) z0,  (float) x1, (float) y1, (float) z0,  (float) x1, (float) y1, (float) z1,  (float) x0, (float) y1, (float) z1 },
        };
        // Matching texture rectangles (x, y, width, height) in skin pixels.
        int[][] uv = {
                { u + D,         v + D, W, H },   // front
                { u + 2 * D + W, v + D, W, H },   // back
                { u,             v + D, D, H },   // right
                { u + D + W,     v + D, D, H },   // left
                { u + D,         v,     W, D },   // top
                { u + D + W,     v,     W, D },   // bottom
        };

        TriangleMesh mesh = new TriangleMesh();
        float[] points = new float[6 * 4 * 3];
        float[] tex = new float[6 * 4 * 2];
        int[] faces = new int[6 * 2 * 6];
        int fi = 0;
        for (int f = 0; f < 6; f++) {
            System.arraycopy(pos[f], 0, points, f * 12, 12);
            float tu0 = uv[f][0] / 64f, tv0 = uv[f][1] / 64f;
            float tu1 = (uv[f][0] + uv[f][2]) / 64f, tv1 = (uv[f][1] + uv[f][3]) / 64f;
            float[] t = { tu0, tv0,  tu1, tv0,  tu1, tv1,  tu0, tv1 };   // TL, TR, BR, BL
            System.arraycopy(t, 0, tex, f * 8, 8);
            int a = f * 4, b = a + 1, c = a + 2, e = a + 3;   // TL, TR, BR, BL (point index == texcoord index)
            // Counter-clockwise from outside: (TL, BL, BR) and (TL, BR, TR).
            int[] tri = { a, a, e, e, c, c,   a, a, c, c, b, b };
            System.arraycopy(tri, 0, faces, fi, 12);
            fi += 12;
        }
        mesh.getPoints().setAll(points);
        mesh.getTexCoords().setAll(tex);
        mesh.getFaces().setAll(faces);

        MeshView view = new MeshView(mesh);
        view.setCullFace(CullFace.NONE);   // robust to winding; the depth buffer still hides back faces
        return view;
    }
}
