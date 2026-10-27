package com.averolauncher.ui;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;

import java.util.ArrayList;
import java.util.List;

/**
 * Swaps the page shown in a host pane with the launcher's page motion:
 * the outgoing page fades out while drifting up 12 px (90 ms), then the incoming page's top-level
 * sections fade in while sliding up from 12 px below, staggered 35 ms apart.
 *
 * <p>Only opacity and translate are animated. Rapid navigation is safe: a new request cancels
 * whatever is running and puts the affected nodes back in their resting state first (pages are
 * cached and re-shown, so leaving one half-faded would corrupt it). With animations off (Low-End
 * Mode) the swap is instant.
 */
final class PageTransition {

    private final StackPane host;
    private Node shown;    // what is actually in the host right now
    private Node target;   // what the latest request asked for
    private Node fadingOut;
    private Timeline outAnim;
    private Timeline inAnim;
    private List<Node> revealing = List.of();

    PageTransition(StackPane host) {
        this.host = host;
    }

    void show(Node next) {
        if (next == target) return;
        target = next;
        cancel();

        Node out = shown;
        if (out == null || out == next || !Motion.enabled()) {
            place(next);
            return;
        }

        fadingOut = out;
        out.setMouseTransparent(true); // no clicks on a page that is leaving
        outAnim = new Timeline(new KeyFrame(Motion.PRESS,
                new KeyValue(out.opacityProperty(), 0, Motion.EASE),
                new KeyValue(out.translateYProperty(), -Motion.SLIDE, Motion.EASE)));
        outAnim.setOnFinished(e -> {
            rest(fadingOut);
            fadingOut = null;
            outAnim = null;
            place(next);
        });
        outAnim.play();
    }

    private void place(Node next) {
        host.getChildren().setAll(next);
        shown = next;
        rest(next);
        revealing = sections(next);
        inAnim = Motion.staggerIn(revealing, Motion.SLIDE, Motion.PANEL);
    }

    /** Stops whatever is running and restores every touched node to its resting state. */
    private void cancel() {
        if (outAnim != null) { outAnim.stop(); outAnim = null; }
        if (fadingOut != null) { rest(fadingOut); fadingOut = null; }
        if (inAnim != null) { inAnim.stop(); inAnim = null; }
        Motion.settle(revealing);
        revealing = List.of();
    }

    private static void rest(Node n) {
        if (n == null) return;
        n.setOpacity(1);
        n.setTranslateY(0);
        n.setMouseTransparent(false);
    }

    /**
     * The nodes to stagger: the page's top-level sections (header, cards...). Pages are a ScrollPane
     * around a VBox, sometimes wrapped in one or two single-child containers, so unwrap those first.
     * If there is nothing sensible to stagger, the whole page fades in as one.
     */
    private static List<Node> sections(Node page) {
        Node n = page;
        if (n instanceof ScrollPane sp && sp.getContent() != null) n = sp.getContent();
        for (int depth = 0; depth < 2; depth++) {
            if (n instanceof Parent single && single.getChildrenUnmodifiable().size() == 1) {
                n = single.getChildrenUnmodifiable().get(0);
            } else {
                break;
            }
        }
        List<Node> out = new ArrayList<>();
        if (n instanceof Parent multi) {
            for (Node c : multi.getChildrenUnmodifiable()) {
                if (c.isManaged() && c.isVisible()) out.add(c);
            }
        }
        return out.size() > 1 ? out : List.of(page);
    }
}
