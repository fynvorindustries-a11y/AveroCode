package com.averolauncher.ui.kit;

import com.averolauncher.rank.RankConfig;
import javafx.geometry.Pos;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.control.Label;
import javafx.scene.paint.Color;
import javafx.scene.shape.Polygon;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A rank's emblem with its division pips underneath (I / II / III; a single diamond for an undivided
 * rank). Artwork lives in {@code /images/ranks/<id>.png}; if a file is missing a plain accent-coloured
 * hexagon is drawn instead, so a missing asset never breaks the page.
 */
public final class RankBadge extends VBox {

    private static final Map<String, Image> CACHE = new HashMap<>();

    /**
     * @param division 1-based current division, or 0 for an undivided rank
     * @param pips     whether to draw the division pips
     */
    public RankBadge(RankConfig.Tier tier, int division, double height, boolean pips) {
        setAlignment(Pos.CENTER);
        setSpacing(Math.max(4, height * 0.06));
        getChildren().add(emblem(tier, height));
        if (pips) getChildren().add(pipRow(tier, division, Math.max(14, height * 0.14)));
    }

    private static StackPane emblem(RankConfig.Tier tier, double height) {
        StackPane box = new StackPane();
        Image img = load(tier.id);
        if (img != null) {
            ImageView iv = new ImageView(img);
            iv.setPreserveRatio(true);
            iv.setSmooth(true);
            iv.setFitHeight(height);
            box.getChildren().add(iv);
        } else {
            double r = height / 2;
            Polygon hex = new Polygon(0, -r, r * 0.87, -r / 2, r * 0.87, r / 2, 0, r, -r * 0.87, r / 2, -r * 0.87, -r / 2);
            hex.setFill(Color.web(accent(tier)).deriveColor(0, 1, 1, 0.85));
            hex.setStroke(Color.web(accent(tier)));
            box.getChildren().add(hex);
            box.setMinSize(height, height);
        }
        return box;
    }

    private static HBox pipRow(RankConfig.Tier tier, int division, double size) {
        HBox row = new HBox(Math.max(3, size * 0.3));
        row.setAlignment(Pos.CENTER);
        Color acc = Color.web(accent(tier));
        int n = Math.max(1, tier.divisions);
        for (int i = 1; i <= n; i++) {
            boolean reached = tier.divisions == 0 || i <= division;
            double w = size, h = size * 0.86;
            Polygon hex = new Polygon(w * 0.25, 0, w * 0.75, 0, w, h / 2, w * 0.75, h, w * 0.25, h, 0, h / 2);
            hex.setFill(reached ? acc.deriveColor(0, 1, 0.9, 0.9) : Color.web("#0b1118"));
            hex.setStroke(reached ? acc : acc.deriveColor(0, 0.5, 0.8, 0.45));
            StackPane pip = new StackPane(hex);
            if (tier.divisions > 0) {
                Label l = new Label(com.averolauncher.rank.RankStanding.roman(i));
                l.getStyleClass().add("rank-pip-label");
                l.setStyle("-fx-text-fill: " + (reached ? "#0b1118" : "rgba(236,232,225,0.55)") + "; -fx-font-size: " + Math.round(size * 0.42) + "px;");
                pip.getChildren().add(l);
            }
            row.getChildren().add(pip);
        }
        return row;
    }

    public static String accent(RankConfig.Tier tier) {
        try { Color.web(tier.accent); return tier.accent; } catch (Exception e) { return "#9aa3b0"; }
    }

    /** A tier's badge artwork at its cached resolution, for custom layouts (e.g. the rank-up overlay); null if missing. */
    public static Image art(String tierId) {
        return load(tierId);
    }

    private static synchronized Image load(String tierId) {
        String key = tierId.toLowerCase(Locale.ROOT);
        if (CACHE.containsKey(key)) return CACHE.get(key);
        Image img = null;
        try {
            var url = RankBadge.class.getResource("/images/ranks/" + key + ".png");
            if (url != null) img = new Image(url.toExternalForm(), 0, 360, true, true);
            if (img != null && img.isError()) img = null;
        } catch (Exception ignored) { }
        CACHE.put(key, img);
        return img;
    }
}
