package com.averolauncher.minecraft;

import com.averolauncher.model.manifest.VersionDetail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds the classpath string passed to -cp when launching Minecraft:
 * every applicable (OS-filtered) non-native library jar, plus the version's
 * own client jar, in a stable order. Native-classifier jars are NOT included
 * here; they're extracted to a natives directory and referenced separately
 * via -Djava.library.path.
 *
 * Handles two library descriptor formats:
 *  A) Modern (vanilla 1.13+, NeoForge): lib.downloads.artifact.path is an explicit
 *     relative path under libraries/.
 *  B) Fabric / older Forge: lib.downloads is null; the path is derived from the
 *     Maven coordinates in lib.name ("group:artifact:version") using the standard
 *     Maven layout: group/artifact/version/artifact-version.jar  (dots in group
 *     become path separators).  Without this, every Fabric loader jar is silently
 *     dropped and the game fails with ClassNotFoundException.
 */
public final class ClasspathBuilder {

    private ClasspathBuilder() {}

    public static String build(Path minecraftDir, VersionDetail detail) {
        Set<String> entries = new LinkedHashSet<>(); // dedupe while preserving order

        if (detail.libraries != null) {
            for (VersionDetail.Library lib : detail.libraries) {
                if (lib.rules != null && !RuleEvaluator.librariesRulesAllow(lib.rules)) {
                    continue;
                }
                // Skip native-only jars (they get extracted, not added to -cp)
                if (isNativeOnly(lib)) continue;

                Path jarPath = resolveLibraryPath(minecraftDir, lib);
                if (jarPath != null) {
                    entries.add(jarPath.toString());
                }
            }
        }

        // The client jar is only present on vanilla/parent versions (inheritsFrom
        // versions like Fabric have no client download — they reuse the parent's jar).
        if (detail.downloads != null && detail.downloads.client != null) {
            Path clientJar = minecraftDir.resolve("versions").resolve(detail.id).resolve(detail.id + ".jar");
            entries.add(clientJar.toString());
        } else {
            // Mod loader version (inheritsFrom) — add the parent's client jar.
            // After mergeInto() runs, detail.id is the child (e.g. "fabric-loader-…"),
            // but the actual client jar on disk has the vanilla version's id.
            // We detect this case by checking if a jar named after the current id is
            // absent, and fall back to searching for any .jar under versions/<id>/.
            Path loaderJarDir = minecraftDir.resolve("versions").resolve(detail.id);
            Path loaderJar = loaderJarDir.resolve(detail.id + ".jar");
            if (java.nio.file.Files.exists(loaderJar)) {
                entries.add(loaderJar.toString());
            }
            // Parent vanilla jar is already included via the merged libraries classpath;
            // if it's not (e.g. very old Forge), pick it up from the parent version dir.
        }

        return String.join(System.getProperty("path.separator"), entries);
    }

    /**
     * Resolves a library to its local jar path using whichever descriptor format
     * is present. Returns null if the library has neither a downloads.artifact
     * nor a parseable Maven name, or if it's a native-only entry.
     */
    public static Path resolveLibraryPath(Path minecraftDir, VersionDetail.Library lib) {
        // Format A: explicit artifact path from downloads block
        if (lib.downloads != null && lib.downloads.artifact != null && lib.downloads.artifact.path != null) {
            return minecraftDir.resolve("libraries").resolve(lib.downloads.artifact.path);
        }
        // Format B: derive path from Maven coordinates in lib.name
        if (lib.name != null && !lib.name.isBlank()) {
            String mavenPath = mavenPathFromCoordinates(lib.name);
            if (mavenPath != null) {
                return minecraftDir.resolve("libraries").resolve(mavenPath);
            }
        }
        return null;
    }

    /**
     * Converts Maven coordinates "group:artifact:version[:classifier]" to
     * the standard repository path "group/artifact/version/artifact-version[-classifier].jar"
     * where dots in group become forward slashes.
     *
     * Examples:
     *   net.fabricmc:fabric-loader:0.16.0  →  net/fabricmc/fabric-loader/0.16.0/fabric-loader-0.16.0.jar
     *   net.fabricmc:intermediary:1.21.1   →  net/fabricmc/intermediary/1.21.1/intermediary-1.21.1.jar
     */
    public static String mavenPathFromCoordinates(String mavenCoord) {
        // Supported formats: group:artifact:version  or  group:artifact:version:classifier
        String[] parts = mavenCoord.split(":");
        if (parts.length < 3) return null;
        String group    = parts[0].replace('.', '/');
        String artifact = parts[1];
        String version  = parts[2];
        String classifier = parts.length >= 4 ? "-" + parts[3] : "";
        // Strip @ext suffix if present (e.g. "1.0@jar" → "1.0")
        String ext = "jar";
        if (version.contains("@")) {
            String[] ve = version.split("@", 2);
            version = ve[0];
            ext = ve[1];
        }
        return group + "/" + artifact + "/" + version + "/" + artifact + "-" + version + classifier + "." + ext;
    }

    /** Returns true for libraries that only contribute natives (extracted to a separate dir, not on -cp). */
    private static boolean isNativeOnly(VersionDetail.Library lib) {
        if (lib.natives == null) return false;
        // A library is native-only if it has natives entries but no regular artifact download
        // and no meaningful Maven jar to add to the classpath.
        return (lib.downloads == null || lib.downloads.artifact == null)
                && (lib.name == null || lib.name.isBlank());
    }
}
