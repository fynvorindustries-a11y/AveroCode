package com.averolauncher.ui.kit;

import javafx.css.PseudoClass;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;

/**
 * JavaFX has no {@code :focus-visible}, so a clicked button would keep a keyboard focus ring.
 * This toggles a {@code :kbd} pseudo-class on the scene root: on after a navigation key, off after
 * a mouse press. The stylesheet draws focus rings only under {@code .root:kbd}.
 */
public final class FocusRing {

    private FocusRing() {}

    private static final PseudoClass KBD = PseudoClass.getPseudoClass("kbd");

    public static void install(Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            KeyCode c = e.getCode();
            if (c == KeyCode.TAB || c == KeyCode.UP || c == KeyCode.DOWN || c == KeyCode.LEFT
                    || c == KeyCode.RIGHT || c == KeyCode.SPACE || c == KeyCode.ENTER) {
                set(scene, true);
            }
        });
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> set(scene, false));
    }

    private static void set(Scene scene, boolean on) {
        if (scene.getRoot() != null) scene.getRoot().pseudoClassStateChanged(KBD, on);
    }
}
