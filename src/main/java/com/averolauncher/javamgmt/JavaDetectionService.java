package com.averolauncher.javamgmt;

import com.averolauncher.logging.AppLogger;
import com.averolauncher.model.JavaInstallation;
import com.averolauncher.util.PathResolver;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Detects installed Java runtimes dynamically rather than assuming any
 * hardcoded path. Searches: the JAVA_HOME environment variable, PATH,
 * common OS-specific install directories (only as search roots to scan,
 * never assumed to exist), and the JVM AveroLauncher itself is running on
 * (always a valid fallback).
 */
public class JavaDetectionService {

    private static final String TAG = "JavaDetectionService";
    private static final Pattern VERSION_LINE = Pattern.compile("version \"([\\d.]+)(?:_\\d+)?\"");
    private static final Pattern VERSION_LINE_MODERN = Pattern.compile("version \"(\\d+)(?:\\.\\d+)*\"");

    /** Scans known locations for Java installations. Never assumes a fixed path exists. */
    public List<JavaInstallation> detectAll() {
        Set<Path> candidates = new LinkedHashSet<>();

        // 1. The JVM currently running this launcher is always a guaranteed-valid candidate.
        String currentJavaHome = System.getProperty("java.home");
        if (currentJavaHome != null) {
            candidates.add(Path.of(currentJavaHome, "bin", executableName()));
        }

        // 2. JAVA_HOME
        String javaHomeEnv = System.getenv("JAVA_HOME");
        if (javaHomeEnv != null && !javaHomeEnv.isBlank()) {
            candidates.add(Path.of(javaHomeEnv, "bin", executableName()));
        }

        // 3. Search PATH
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
                Path candidate = Path.of(dir, executableName());
                if (Files.isExecutable(candidate)) {
                    candidates.add(candidate);
                }
            }
        }

        // 4. Scan common OS-specific install roots (roots only; never assume a specific version path)
        for (Path root : commonInstallRoots()) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> children = Files.list(root)) {
                children.filter(Files::isDirectory).forEach(dir -> {
                    Path exe = dir.resolve("bin").resolve(executableName());
                    if (Files.isExecutable(exe)) {
                        candidates.add(exe);
                    }
                });
            } catch (Exception e) {
                AppLogger.get().debug(TAG, "Could not scan " + root + ": " + e.getMessage());
            }
        }

        List<JavaInstallation> results = new ArrayList<>();
        for (Path candidate : candidates) {
            if (!Files.isExecutable(candidate)) continue;
            JavaInstallation info = probe(candidate);
            if (info != null) {
                results.add(info);
            }
        }
        AppLogger.get().info(TAG, "Detected " + results.size() + " Java installation(s)");
        return results;
    }

    /** Picks the best available Java for a required major version (e.g. 21 for modern MC, 8 for old versions). */
    public JavaInstallation selectBestFor(int requiredMajorVersion, List<JavaInstallation> available) {
        return available.stream()
                .filter(j -> j.majorVersion == requiredMajorVersion)
                .findFirst()
                .orElseGet(() -> available.stream()
                        .filter(j -> j.majorVersion >= requiredMajorVersion)
                        .min((a, b) -> Integer.compare(a.majorVersion, b.majorVersion))
                        .orElse(available.isEmpty() ? null : available.get(0)));
    }

    private List<Path> commonInstallRoots() {
        List<Path> roots = new ArrayList<>();
        if (PathResolver.isWindows()) {
            roots.add(Path.of("C:\\Program Files\\Java"));
            roots.add(Path.of("C:\\Program Files\\Eclipse Adoptium"));
            roots.add(Path.of("C:\\Program Files (x86)\\Java"));
        } else if (PathResolver.isMac()) {
            roots.add(Path.of("/Library/Java/JavaVirtualMachines"));
            roots.add(Path.of(System.getProperty("user.home"), "Library/Java/JavaVirtualMachines"));
        } else {
            roots.add(Path.of("/usr/lib/jvm"));
            roots.add(Path.of("/opt/java"));
        }
        return roots;
    }

    private String executableName() {
        return PathResolver.isWindows() ? "java.exe" : "java";
    }

    private JavaInstallation probe(Path executable) {
        try {
            ProcessBuilder pb = new ProcessBuilder(executable.toString(), "-version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            boolean finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }

            String text = output.toString();
            String versionString = null;
            int major = -1;

            Matcher m1 = VERSION_LINE.matcher(text);
            if (m1.find()) {
                versionString = m1.group(1);
                String[] parts = versionString.split("\\.");
                major = parts[0].equals("1") && parts.length > 1 ? Integer.parseInt(parts[1]) : Integer.parseInt(parts[0]);
            } else {
                Matcher m2 = VERSION_LINE_MODERN.matcher(text);
                if (m2.find()) {
                    versionString = m2.group(1);
                    major = Integer.parseInt(versionString.split("\\.")[0]);
                }
            }

            if (versionString == null) {
                return null;
            }

            String vendor = text.toLowerCase().contains("openjdk") ? "OpenJDK"
                    : text.toLowerCase().contains("temurin") ? "Eclipse Temurin"
                    : text.toLowerCase().contains("corretto") ? "Amazon Corretto"
                    : "Unknown";

            String arch = text.contains("64-Bit") ? "x64" : text.contains("32-Bit") ? "x86" : "unknown";

            return new JavaInstallation(executable.toString(), versionString, major, vendor, arch);
        } catch (Exception e) {
            AppLogger.get().debug(TAG, "Failed to probe " + executable + ": " + e.getMessage());
            return null;
        }
    }
}
