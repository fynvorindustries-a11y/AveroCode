package com.averolauncher.loaders;

/** The mod loaders the launcher can install by itself. */
public enum ModLoader {
    FABRIC("Fabric", false),
    QUILT("Quilt", false),
    NEOFORGE("NeoForge", true),
    FORGE("Forge", true);

    private final String displayName;
    private final boolean usesInstallerJar;

    ModLoader(String displayName, boolean usesInstallerJar) {
        this.displayName = displayName;
        this.usesInstallerJar = usesInstallerJar;
    }

    public String displayName() { return displayName; }

    /** Forge and NeoForge are installed by running their official installer; Fabric and Quilt just need a profile file. */
    public boolean usesInstallerJar() { return usesInstallerJar; }

    @Override public String toString() { return displayName; }
}
