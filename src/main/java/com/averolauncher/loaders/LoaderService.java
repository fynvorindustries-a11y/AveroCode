package com.averolauncher.loaders;

import com.averolauncher.download.DownloadManager;
import com.averolauncher.logging.AppLogger;
import com.averolauncher.model.DownloadTask;
import com.averolauncher.model.JavaInstallation;
import com.averolauncher.model.LauncherSettings;
import com.averolauncher.service.LaunchService;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Installs mod loaders into the launcher's Minecraft directory so they show up on the Play page.
 *
 * <ul>
 *   <li><b>Fabric / Quilt</b>: the project's metadata server hands out the finished version profile
 *       (a small JSON file). We save it to {@code versions/<id>/<id>.json}; libraries are fetched
 *       the first time the profile is launched.</li>
 *   <li><b>Forge / NeoForge</b>: the official installer jar is downloaded and run headless
 *       ({@code --installClient}), exactly what the project's own installer would do. It patches the
 *       game and downloads its libraries, which can take a few minutes.</li>
 * </ul>
 * Every loader needs the vanilla version first; that is installed here too.
 */
public class LoaderService {

    private static final String TAG = "LoaderService";

    /** Where each project publishes. Overridable so tests can point at a local server. */
    public record Endpoints(String fabricMeta, String quiltMeta, String forgeMaven, String neoForgeMaven, String forgePromos) {
        public static Endpoints defaults() {
            return new Endpoints(
                    "https://meta.fabricmc.net/v2/",
                    "https://meta.quiltmc.org/v3/",
                    "https://maven.minecraftforge.net/",
                    "https://maven.neoforged.net/releases/",
                    "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json");
        }
    }

    private static final Pattern MC_VERSION = Pattern.compile("^\\d+\\.\\d+(\\.\\d+)?$");
    private static final Pattern VERSION_TAG = Pattern.compile("<version>([^<]+)</version>");
    /** A version id becomes a folder name, so only plain characters are allowed (remote JSON must not escape versions/). */
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._+\\-]{0,127}$");
    private static final long METADATA_TTL_MS = TimeUnit.MINUTES.toMillis(15);
    private static final int INSTALLER_TIMEOUT_MIN = 25;

    private record CachedList(long at, List<String> versions) { }
    private static final Map<String, CachedList> METADATA_CACHE = new ConcurrentHashMap<>();

    private final LaunchService launch;
    private final Endpoints endpoints;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public LoaderService(LaunchService launch) {
        this(launch, Endpoints.defaults());
    }

    public LoaderService(LaunchService launch, Endpoints endpoints) {
        this.launch = launch;
        this.endpoints = endpoints;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Which builds exist
    // ─────────────────────────────────────────────────────────────────────────

    /** Loader builds available for one Minecraft version, newest first. Empty if the loader has none for it. */
    public CompletableFuture<List<LoaderVersion>> versions(ModLoader loader, String mcVersion) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return switch (loader) {
                    case FABRIC -> metaVersions(endpoints.fabricMeta(), mcVersion, true);
                    case QUILT -> metaVersions(endpoints.quiltMeta(), mcVersion, false);
                    case FORGE -> forgeVersions(mcVersion);
                    case NEOFORGE -> neoForgeVersions(mcVersion);
                };
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new RuntimeException("Could not load " + loader.displayName() + " versions: " + e.getMessage(), e);
            }
        }, launch.getBackgroundExecutor());
    }

    private List<LoaderVersion> metaVersions(String base, String mc, boolean hasStableFlag) throws IOException, InterruptedException {
        JsonArray arr = JsonParser.parseString(get(base + "versions/loader/" + enc(mc))).getAsJsonArray();
        List<LoaderVersion> out = new ArrayList<>();
        boolean markedRecommended = false;
        for (JsonElement el : arr) {
            JsonObject l = el.getAsJsonObject().getAsJsonObject("loader");
            if (l == null || !l.has("version")) continue;
            String v = l.get("version").getAsString();
            boolean stable = hasStableFlag && l.has("stable") ? l.get("stable").getAsBoolean() : !v.contains("-");
            boolean rec = stable && !markedRecommended;
            if (rec) markedRecommended = true;
            out.add(new LoaderVersion(v, v, rec));
        }
        return out;   // the servers already return newest first
    }

    private List<LoaderVersion> forgeVersions(String mc) throws IOException, InterruptedException {
        List<String> all = mavenVersions(endpoints.forgeMaven() + "net/minecraftforge/forge/maven-metadata.xml");
        String recommended = null;
        try {
            JsonObject promos = JsonParser.parseString(get(endpoints.forgePromos())).getAsJsonObject().getAsJsonObject("promos");
            if (promos != null && promos.has(mc + "-recommended")) recommended = promos.get(mc + "-recommended").getAsString();
        } catch (Exception ignored) {
            // The promotions file is a nicety; the list works without it.
        }
        List<LoaderVersion> out = new ArrayList<>();
        for (String full : all) {
            if (!full.startsWith(mc + "-")) continue;
            String label = full.substring(mc.length() + 1);
            if (label.endsWith("-" + mc)) label = label.substring(0, label.length() - mc.length() - 1);   // 1.7.10 style
            out.add(new LoaderVersion(full, label, label.equals(recommended)));
        }
        Collections.reverse(out);   // metadata is oldest-first
        return out;
    }

    private List<LoaderVersion> neoForgeVersions(String mc) throws IOException, InterruptedException {
        List<String> all = mavenVersions(endpoints.neoForgeMaven() + "net/neoforged/neoforge/maven-metadata.xml");
        List<LoaderVersion> out = new ArrayList<>();
        boolean markedRecommended = false;
        List<String> forMc = new ArrayList<>();
        for (String v : all) if (mc.equals(neoForgeMinecraftVersion(v))) forMc.add(v);
        Collections.reverse(forMc);
        for (String v : forMc) {
            boolean stable = !v.contains("-");
            boolean rec = stable && !markedRecommended;
            if (rec) markedRecommended = true;
            out.add(new LoaderVersion(v, v, rec));
        }
        return out;
    }

    /**
     * NeoForge numbers its builds after the game: 21.1.77 is for Minecraft 1.21.1 and 20.4.x for 1.20.4
     * (a ".0" minor means plain 1.21). Builds for the newer year-based Minecraft numbering (26.x) follow
     * the same "first two numbers" rule here, which I could not check against a real list.
     */
    static String neoForgeMinecraftVersion(String neoVersion) {
        String core = neoVersion.split("-")[0];
        String[] p = core.split("\\.");
        if (p.length < 2) return null;
        try {
            int major = Integer.parseInt(p[0]);
            int minor = Integer.parseInt(p[1]);
            if (major >= 26) return major + "." + minor;
            return "1." + major + (minor == 0 ? "" : "." + minor);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private List<String> mavenVersions(String metadataUrl) throws IOException, InterruptedException {
        CachedList hit = METADATA_CACHE.get(metadataUrl);
        if (hit != null && System.currentTimeMillis() - hit.at() < METADATA_TTL_MS) return hit.versions();
        List<String> out = parseMavenVersions(get(metadataUrl));
        METADATA_CACHE.put(metadataUrl, new CachedList(System.currentTimeMillis(), out));
        return out;
    }

    static List<String> parseMavenVersions(String xml) {
        List<String> out = new ArrayList<>();
        Matcher m = VERSION_TAG.matcher(xml);
        while (m.find()) out.add(m.group(1).trim());
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Install
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Installs {@code loaderVersion} of {@code loader} for {@code mcVersion} and returns the new version id
     * (the name it will have on the Play page).
     *
     * @param status receives short human-readable progress lines
     * @param log    receives raw installer output (Forge/NeoForge only)
     * @param onTask receives each file download so the Downloads tab can show it
     */
    public CompletableFuture<String> install(ModLoader loader, String mcVersion, LoaderVersion loaderVersion,
                                             LauncherSettings settings, Consumer<String> status,
                                             Consumer<String> log, Consumer<DownloadTask> onTask) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!MC_VERSION.matcher(mcVersion).matches()) throw new IOException("Unsupported Minecraft version: " + mcVersion);
                if (settings.minecraftDirectory == null || settings.minecraftDirectory.isBlank())
                    throw new IOException("No Minecraft directory is set in Settings.");
                Path mcDir = Path.of(settings.minecraftDirectory);
                Files.createDirectories(mcDir);

                say(status, "Installing Minecraft " + mcVersion + " (needed by " + loader.displayName() + ")");
                launch.installVanillaOnly(mcVersion, settings, onTask).join();

                String id = switch (loader) {
                    case FABRIC -> installProfile(endpoints.fabricMeta(), "Fabric", mcVersion, loaderVersion, mcDir, status);
                    case QUILT -> installProfile(endpoints.quiltMeta(), "Quilt", mcVersion, loaderVersion, mcDir, status);
                    case FORGE, NEOFORGE -> installWithInstaller(loader, mcVersion, loaderVersion, settings, mcDir, status, log, onTask);
                };
                say(status, "Installed " + id);
                AppLogger.get().info(TAG, "Installed " + loader + " " + loaderVersion.id() + " for " + mcVersion + " as " + id);
                return id;
            } catch (java.util.concurrent.CompletionException e) {
                throw new RuntimeException(rootMessage(e), e);
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new RuntimeException(e.getMessage(), e);
            }
        }, launch.getBackgroundExecutor());
    }

    private String installProfile(String base, String name, String mc, LoaderVersion lv, Path mcDir,
                                  Consumer<String> status) throws IOException, InterruptedException {
        say(status, "Fetching " + name + " " + lv.id() + " profile");
        String body = get(base + "versions/loader/" + enc(mc) + "/" + enc(lv.id()) + "/profile/json");
        JsonObject profile = JsonParser.parseString(body).getAsJsonObject();
        String id = profile.has("id") ? profile.get("id").getAsString() : null;
        if (id == null || !SAFE_ID.matcher(id).matches() || !profile.has("mainClass") || !profile.has("libraries")) {
            throw new IOException(name + " returned an unexpected profile for " + mc + " / " + lv.id() + ".");
        }
        Path dir = mcDir.resolve("versions").resolve(id);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(id + ".json"), body, StandardCharsets.UTF_8);
        return id;
    }

    private String installWithInstaller(ModLoader loader, String mc, LoaderVersion lv, LauncherSettings settings,
                                        Path mcDir, Consumer<String> status, Consumer<String> log,
                                        Consumer<DownloadTask> onTask) throws IOException, InterruptedException {
        int needJava = (loader == ModLoader.FORGE && isOldMinecraft(mc)) ? 8 : 17;
        JavaInstallation java = launch.findJava(settings, needJava);
        if (java == null) {
            throw new IOException("The " + loader.displayName() + " installer needs Java " + needJava
                    + " or newer, and none was found. Install one (or set its path in Settings) and try again.");
        }

        String url = installerUrl(loader, lv);
        Path work = Files.createTempDirectory("avero-" + loader.name().toLowerCase() + "-");
        try {
            Path jar = work.resolve(installerFileName(loader, lv));
            say(status, "Downloading the " + loader.displayName() + " installer");
            DownloadManager.PendingDownload pd = new DownloadManager.PendingDownload(
                    loader.displayName() + " installer " + lv.label(), url, jar, null, 0L);
            List<DownloadTask> tasks = new java.util.concurrent.CopyOnWriteArrayList<>();
            launch.getDownloadManager().downloadAll(List.of(pd), 1, t -> { tasks.add(t); if (onTask != null) onTask.accept(t); }).join();
            for (DownloadTask t : tasks) {
                if (t.getStatus() == DownloadTask.Status.FAILED) {
                    throw new IOException("Could not download the installer: " + t.getErrorMessage());
                }
            }
            if (!Files.isRegularFile(jar) || Files.size(jar) < 1024) throw new IOException("The downloaded installer is missing or empty.");

            String id = readInstallerVersionId(jar);
            ensureLauncherProfiles(mcDir);

            say(status, "Running the " + loader.displayName() + " installer (this can take a few minutes)");
            runInstaller(Path.of(java.path), jar, mcDir, work, log);

            if (id == null || !SAFE_ID.matcher(id).matches() || !Files.isRegularFile(mcDir.resolve("versions").resolve(id).resolve(id + ".json"))) {
                id = findInstalledId(mcDir, loader, lv);
            }
            if (id == null) {
                throw new IOException("The installer finished, but no " + loader.displayName() + " version appeared in "
                        + mcDir.resolve("versions") + ".");
            }
            return id;
        } finally {
            deleteQuietly(work);
        }
    }

    static boolean isOldMinecraft(String mc) {
        String[] p = mc.split("\\.");
        try {
            return p.length >= 2 && Integer.parseInt(p[0]) == 1 && Integer.parseInt(p[1]) <= 16;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String installerUrl(ModLoader loader, LoaderVersion lv) {
        String file = installerFileName(loader, lv);
        return loader == ModLoader.FORGE
                ? endpoints.forgeMaven() + "net/minecraftforge/forge/" + lv.id() + "/" + file
                : endpoints.neoForgeMaven() + "net/neoforged/neoforge/" + lv.id() + "/" + file;
    }

    private static String installerFileName(ModLoader loader, LoaderVersion lv) {
        return (loader == ModLoader.FORGE ? "forge-" : "neoforge-") + lv.id() + "-installer.jar";
    }

    /** The installers refuse to run unless the folder looks like a launcher directory. */
    private static void ensureLauncherProfiles(Path mcDir) throws IOException {
        Path f = mcDir.resolve("launcher_profiles.json");
        if (!Files.exists(f)) {
            Files.writeString(f, "{\"profiles\":{},\"settings\":{},\"version\":3}\n", StandardCharsets.UTF_8);
        }
    }

    /** The version id an installer will create, read from its own install_profile.json. */
    static String readInstallerVersionId(Path installerJar) {
        try (ZipFile zip = new ZipFile(installerJar.toFile())) {
            ZipEntry e = zip.getEntry("install_profile.json");
            if (e == null) return null;
            String text = new String(zip.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
            JsonObject o = JsonParser.parseString(text).getAsJsonObject();
            if (o.has("version") && o.get("version").isJsonPrimitive()) return o.get("version").getAsString();   // Forge 1.13+, NeoForge
            if (o.has("versionInfo") && o.get("versionInfo").isJsonObject()
                    && o.getAsJsonObject("versionInfo").has("id")) return o.getAsJsonObject("versionInfo").get("id").getAsString();   // old Forge
            if (o.has("install") && o.get("install").isJsonObject()
                    && o.getAsJsonObject("install").has("target")) return o.getAsJsonObject("install").get("target").getAsString();
        } catch (Exception ignored) {
            // Falls back to scanning versions/ after the install.
        }
        return null;
    }

    /** Fallback: the newest versions/ entry that looks like this loader build. */
    private static String findInstalledId(Path mcDir, ModLoader loader, LoaderVersion lv) throws IOException {
        Path versions = mcDir.resolve("versions");
        if (!Files.isDirectory(versions)) return null;
        String needle = loader == ModLoader.FORGE ? lv.label() : lv.id();
        String family = loader == ModLoader.FORGE ? "forge" : "neoforge";
        try (var dirs = Files.list(versions)) {
            return dirs.filter(Files::isDirectory)
                    .filter(d -> {
                        String n = d.getFileName().toString();
                        String low = n.toLowerCase();
                        boolean familyOk = loader == ModLoader.FORGE ? low.contains("forge") && !low.contains("neoforge") : low.contains(family);
                        return familyOk && n.contains(needle) && SAFE_ID.matcher(n).matches()
                                && Files.isRegularFile(d.resolve(n + ".json"));
                    })
                    .max(Comparator.comparingLong(d -> d.toFile().lastModified()))
                    .map(d -> d.getFileName().toString())
                    .orElse(null);
        }
    }

    private void runInstaller(Path javaExe, Path jar, Path mcDir, Path workDir, Consumer<String> log)
            throws IOException, InterruptedException {
        String[] flags = { "--installClient", "--install-client" };
        IOException last = null;
        for (String flag : flags) {
            List<String> cmd = List.of(javaExe.toString(), "-Djava.awt.headless=true", "-jar", jar.toString(), flag, mcDir.toString());
            AppLogger.get().info(TAG, "Running installer: " + String.join(" ", cmd));
            Process p = new ProcessBuilder(cmd).directory(workDir.toFile()).redirectErrorStream(true).start();
            ArrayDeque<String> tail = new ArrayDeque<>();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (tail) { tail.addLast(line); if (tail.size() > 40) tail.removeFirst(); }
                        if (log != null) log.accept(line);
                    }
                } catch (IOException ignored) { }
            }, "Avero-LoaderInstaller-Output");
            reader.setDaemon(true);
            reader.start();

            if (!p.waitFor(INSTALLER_TIMEOUT_MIN, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new IOException("The installer did not finish within " + INSTALLER_TIMEOUT_MIN + " minutes.");
            }
            reader.join(2000);
            if (p.exitValue() == 0) return;

            String output;
            synchronized (tail) { output = String.join("\n", tail); }
            String low = output.toLowerCase();
            last = new IOException("The installer failed (exit code " + p.exitValue() + ").\n" + lastLines(output, 12));
            boolean flagRejected = low.contains("not a recognized option") || low.contains("unrecognized option")
                    || low.contains("unknown option") || low.contains("unrecognized argument");
            if (!flagRejected) throw last;   // a real failure: do not retry with a different flag
        }
        throw last != null ? last : new IOException("The installer failed.");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String get(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "AveroLauncher (mod loader installer)")
                .GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() == 404) throw new IOException("Not found: " + url);
        if (res.statusCode() != 200) throw new IOException("HTTP " + res.statusCode() + " from " + url);
        return res.body();
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static void say(Consumer<String> status, String msg) {
        AppLogger.get().info(TAG, msg);
        if (status != null) status.accept(msg);
    }

    private static String lastLines(String s, int n) {
        String[] lines = s.split("\n");
        int from = Math.max(0, lines.length - n);
        return String.join("\n", java.util.Arrays.copyOfRange(lines, from, lines.length));
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        return c.getMessage() != null ? c.getMessage() : c.toString();
    }

    private static void deleteQuietly(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } });
        } catch (IOException ignored) { }
    }
}
