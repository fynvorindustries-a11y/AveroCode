package com.averolauncher.ui;

public enum NavPage {
    HOME("Home", "\u2302"),
    PLAY("Play", "\u25B6"),
    MODS("Mods", "\u2699"),
    SERVERS("Servers", "\u2261"),
    STORE("Store", "\u2605"),
    RANKS("Ranks", "\u2666"),
    ACCOUNTS("Accounts", "\u263A"),
    SETTINGS("Settings", "\u2699"),
    ABOUT("About", "\u2139");

    private final String label;
    private final String icon;

    NavPage(String label, String icon) {
        this.label = label;
        this.icon = icon;
    }

    public String label() {
        return label;
    }

    public String icon() {
        return icon;
    }
}
