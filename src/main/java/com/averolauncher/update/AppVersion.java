package com.averolauncher.update;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The application's own version, read from {@code version.properties} on the
 * classpath. That file is a template ({@code app.version=${version}}) that
 * Gradle's {@code processResources} task expands from {@code project.version}
 * in build.gradle at build time — so bumping the version for a release means
 * changing it in exactly one place (build.gradle), and this always matches
 * whatever was actually built, rather than a second hardcoded constant that
 * can drift out of sync.
 */
public final class AppVersion {

    private AppVersion() {}

    /** owner/repo on GitHub that {@link UpdateChecker} polls and release links point to. */
    public static final String GITHUB_REPO = "fynvorindustries-a11y/AveroLauncher";

    public static final String CURRENT = load();

    private static String load() {
        Properties props = new Properties();
        try (InputStream in = AppVersion.class.getResourceAsStream("/version.properties")) {
            if (in != null) {
                props.load(in);
                String v = props.getProperty("app.version");
                // If this is ever run straight from source without going through Gradle's
                // resource-filtering step, the placeholder is left unexpanded — fall through
                // to the dev default below rather than reporting "${version}" as the version.
                if (v != null && !v.isBlank() && !v.contains("${")) {
                    return v.trim();
                }
            }
        } catch (IOException ignored) {
            // Falls through to the dev default below.
        }
        return "0.0.0-dev";
    }
}
