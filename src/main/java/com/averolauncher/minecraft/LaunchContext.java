package com.averolauncher.minecraft;

import java.nio.file.Path;

/**
 * All the concrete values needed to substitute into Mojang's argument
 * templates (things like ${auth_player_name}, ${game_directory}, etc) and
 * to build the JVM command line.
 */
public class LaunchContext {
    public Path minecraftDir;
    public Path gameDir;      // usually same as minecraftDir, unless profile overrides it
    public Path javaExecutable;
    public String versionId;
    public String assetsId;
    public Path assetsDir;
    public Path nativesDir;
    public String classpath;

    public String authPlayerName;
    public String authUuid;
    public String authAccessToken;
    public String userType = "legacy"; // overridden to "msa" for Microsoft accounts in LaunchService.buildContext()

    public int minRamMb;
    public int maxRamMb;
    public int windowWidth = 1280;
    public int windowHeight = 720;
    public boolean fullscreen = false;
    public String extraJvmArgs = "";
    /** Extra JVM arguments that must stay ONE argument each (paths with spaces, -javaagent:...). */
    public java.util.List<String> extraJvmArgList = new java.util.ArrayList<>();

    public String quickPlayServerAddress; // null if not auto-joining a server
}
