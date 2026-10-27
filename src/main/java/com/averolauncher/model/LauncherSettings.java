package com.averolauncher.model;

/**
 * Persisted launcher-wide settings. Plain mutable POJO serialized with Gson.
 */
public class LauncherSettings {

    // General
    public String closeBehavior = "EXIT"; // EXIT or KEEP_OPEN
    public boolean launchOnStartup = false;

    // Minecraft
    public String minecraftDirectory; // absolute path, set at first run
    public String defaultVersionId;
    public String defaultProfileId;
    /** Max game memory (-Xmx). The starting heap (-Xms) is derived automatically — see MemoryAdvisor. */
    public int maxRamMb = 2048;
    /** Memory the user had before Low-End Mode lowered it (0 = Low-End Mode hasn't changed it). */
    public int ramBeforeLowEndMb = 0;
    /** The value Low-End Mode set; the old value is only restored if memory is still at this value. */
    public int lowEndRamMb = 0;
    public String javaPath; // null = auto-detect best match

    // Downloads
    public String downloadDirectory; // defaults to minecraftDirectory
    public int concurrentDownloads = 4;
    public boolean useCache = true;
    /** If Mojang's servers can't be reached, fall back to the BMCLAPI mirror (see MirrorRouter). */
    public boolean useMirrorFallback = true;

    // Appearance
    public String theme = "DARK";
    public boolean animationsEnabled = true;
    public boolean compactMode = false;
    /** Keep the sidebar expanded (pushing the page) instead of a hover-to-open icon rail. */
    public boolean pinSidebar = false;

    // Advanced
    public boolean lowEndMode = false;
    public String logLevel = "INFO";

    // Updates
    /** Whether to silently check GitHub Releases for a newer build shortly after startup. */
    public boolean checkForUpdatesOnStartup = true;
    /** Install/load the Avero cape mod when launching Fabric profiles (Store page toggle). */
    public boolean averoCapesEnabled = true;
    /** Tag name of a release the user dismissed with "Skip this version" — not re-nagged. */
    public String skippedUpdateVersion;
    // NOTE: msClientId is NOT stored here. It is stored encrypted in credentials.dat
    // via CredentialStore. Use CredentialStore.read(KEY_MS_CLIENT_ID) to access it.
    public String activeAccountId; // id of the Account currently selected for launching, null = offline
    /**
     * "<tierId>:<division>" of the highest rank standing the cinematic rank-up screen has already
     * been shown for. Checked whenever the Ranks page opens (and on a live promotion) so a promotion
     * earned while the launcher wasn't focused, or AP credited in a single large batch, still gets its
     * celebration the next time the player actually looks — see MainWindow#celebrateIfNew.
     */
    public String rankCelebratedKey;
    public java.util.List<com.averolauncher.model.ServerEntry> savedServers = new java.util.ArrayList<>();
    /** Server addresses (host:port, lowercase) starred on the Servers page. Shown first, in the Favorites tab. */
    public java.util.Set<String> favoriteServerAddresses = new java.util.LinkedHashSet<>();

    public LauncherSettings() {
        // Defaults resolved lazily by SettingsService on first load, since
        // static initializers here would run before PathResolver context matters.
    }
}
