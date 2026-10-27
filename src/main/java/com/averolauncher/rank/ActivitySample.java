package com.averolauncher.rank;

/**
 * One reading of the player's presence. Either field may be unknown ({@code null}) when the OS can't say.
 *
 * @param idleMs      time since the last keyboard/mouse input anywhere on the system
 * @param gameFocused whether the game's window is the foreground window
 * @param source      short label of the probe that produced it ("windows-ps", "macos-ioreg", "linux-x11", "none")
 */
public record ActivitySample(Long idleMs, Boolean gameFocused, String source) {
    public static ActivitySample unknown() { return new ActivitySample(null, null, "none"); }
    public boolean hasInfo() { return idleMs != null || gameFocused != null; }
}
