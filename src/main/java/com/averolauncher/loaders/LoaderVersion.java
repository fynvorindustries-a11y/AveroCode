package com.averolauncher.loaders;

/**
 * One installable loader build.
 *
 * @param id          what the install step needs (Fabric/Quilt: "0.16.5"; Forge: "1.20.1-47.2.0"; NeoForge: "21.1.77")
 * @param label       what the user sees ("47.2.0")
 * @param recommended true for the build the loader's own project points people to
 */
public record LoaderVersion(String id, String label, boolean recommended) {
    @Override public String toString() { return recommended ? label + "  \u2605 recommended" : label; }
}
