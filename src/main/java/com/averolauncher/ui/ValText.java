package com.averolauncher.ui;

import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Labeled;

import java.util.Locale;
import java.util.Set;

/**
 * JavaFX CSS has no {@code text-transform}, but the Valorant-style look wants headings, section
 * labels, buttons and tabs in bold UPPERCASE. This does it in code: it uppercases the text of every
 * {@link Labeled} carrying one of the heading/button style classes, keeps it uppercase if the text
 * is changed later, and also picks up nodes added to the tree afterwards (lists, rebuilt cards...).
 *
 * <p>Only the classes below are touched, so data such as usernames, server names and descriptions
 * is never altered.
 */
public final class ValText {

    private ValText() {}

    private static final String WATCHED = "valtext.watched";
    private static final String UPPER = "valtext.upper";

    private static final Set<String> UPPER_CLASSES = Set.of(
            "page-title", "hero-greeting", "section-header", "card-title", "avero-section-label",
            "nav-button", "brand-label", "brand-sublabel", "footer-title", "hero-eyebrow",
            "button", "primary-cta-button", "secondary-button", "login-button", "play-button",
            "hero-play-button", "hero-setup-button", "hero-choose-version-button", "ms-hero-button",
            "play-tab-button", "type-tab-button", "source-toggle-button", "play-button-label",
            "status-chip-title", "about-stat-title", "title-accent",
            "recommended-badge", "cape-limited-badge", "cape-equipped-badge", "avero-badge",
            "about-tag", "version-type-label", "mod-card-stat", "news-time",
            "av-page-title", "av-empty-title"
    );

    /** Uppercases everything under {@code root} now, and keeps doing so for nodes added later. */
    public static void watch(Node root) {
        if (root == null) return;
        visit(root);
    }

    private static void visit(Node n) {
        if (n instanceof Labeled l && wants(l)) upper(l);
        if (n instanceof javafx.scene.layout.Region r) ValShape.maybe(r);
        if (n instanceof Parent p) {
            if (!p.getProperties().containsKey(WATCHED)) {
                p.getProperties().put(WATCHED, Boolean.TRUE);
                p.getChildrenUnmodifiable().addListener((ListChangeListener<Node>) c -> {
                    while (c.next()) {
                        if (c.wasAdded()) for (Node added : c.getAddedSubList()) visit(added);
                    }
                });
            }
            for (Node child : p.getChildrenUnmodifiable()) visit(child);
        }
    }

    private static boolean wants(Labeled l) {
        for (String c : l.getStyleClass()) if (UPPER_CLASSES.contains(c)) return true;
        return false;
    }

    private static void upper(Labeled l) {
        apply(l);
        if (l.getProperties().containsKey(UPPER)) return;
        l.getProperties().put(UPPER, Boolean.TRUE);
        l.textProperty().addListener((o, was, now) -> apply(l));
        // style classes can be added after creation (e.g. play-page-cta) — nothing to do: "button" is already covered
    }

    private static void apply(Labeled l) {
        String t = l.getText();
        if (t == null || t.isEmpty()) return;
        String up = t.toUpperCase(Locale.ROOT);
        if (!up.equals(t)) l.setText(up); // idempotent, so the listener re-entering ends immediately
    }
}
