package com.averolauncher.ui;

import com.averolauncher.model.LauncherSettings;
import com.averolauncher.repository.SettingsRepository;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

/**
 * Window-shell preferences that must take effect live. Today that is only "Pin sidebar open".
 * Settings calls {@link #setPinned}; the shell listens to {@link #pinnedProperty()}. Same static
 * pattern as {@link Motion}, so SettingsPage needs no reference to MainWindow.
 */
public final class ShellState {

    private ShellState() {}

    private static LauncherSettings settings;
    private static SettingsRepository repository;
    private static final BooleanProperty PINNED = new SimpleBooleanProperty(false);

    /** Called once at startup (MainWindow.createScene). */
    public static void bind(LauncherSettings s, SettingsRepository r) {
        settings = s;
        repository = r;
        PINNED.set(s != null && s.pinSidebar);
    }

    public static ReadOnlyBooleanProperty pinnedProperty() {
        return PINNED;
    }

    public static boolean isPinned() {
        return PINNED.get();
    }

    /** Persists the choice and applies it immediately. */
    public static void setPinned(boolean pinned) {
        if (settings != null && settings.pinSidebar != pinned) {
            settings.pinSidebar = pinned;
            if (repository != null) repository.save(settings);
        }
        PINNED.set(pinned);
    }
}
