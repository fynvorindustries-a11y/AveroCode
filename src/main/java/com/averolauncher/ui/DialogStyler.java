package com.averolauncher.ui;

import javafx.scene.control.Dialog;

/**
 * Dialogs (Alert, TextInputDialog, etc) open in their own Scene and do not
 * automatically inherit the main window's stylesheet. Without this, dialogs
 * render with JavaFX's default light-ish chrome, which visually clashes with
 * the app and can look like it "overlaps" or shows through against the dark
 * background behind it. Call this on every Alert/TextInputDialog/Dialog
 * before showing it.
 */
public final class DialogStyler {

    private DialogStyler() {}

    public static void style(Dialog<?> dialog) {
        for (String name : new String[] {"dark-theme", "tokens", "components", "shell", "pages", "mc-theme"}) {
            var css = DialogStyler.class.getResource("/css/" + name + ".css");
            if (css != null) {
                dialog.getDialogPane().getStylesheets().add(css.toExternalForm());
            }
        }
    }
}
