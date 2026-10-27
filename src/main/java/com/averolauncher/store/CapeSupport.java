package com.averolauncher.store;

import com.averolauncher.logging.AppLogger;
import com.averolauncher.model.Account;
import com.averolauncher.model.LauncherSettings;
import com.averolauncher.util.PathResolver;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Makes Avero capes <b>and offline skins</b> show up in-game <b>without any mod or resource pack</b>, on every
 * Minecraft version and every loader (vanilla, Fabric, Forge, NeoForge, Quilt...).
 *
 * <p>It works through the Avero cape <i>agent</i>: one small jar that the game loads through the JVM
 * argument {@code -javaagent:...}. The agent adds the player's Avero cape, and the skin of an offline account,
 * to the textures Minecraft asks Mojang's authlib for (an offline skin is served to the game from a tiny
 * loopback server inside the agent, the way TLauncher serves its skins). Nothing is put into the {@code mods}
 * or {@code resourcepacks} folder, and options.txt is left alone.
 *
 * <p>A copy of the agent ships inside the launcher, so skins work on the very first launch and with no
 * internet. The server may publish a newer one, but never an older one than the bundled build.
 *
 * <ul>
 *   <li>{@link #prepare} downloads/updates the agent (SHA-256 verified) into
 *       {@code <launcher data>/agent/}. Best effort, short timeouts, never breaks a launch.</li>
 *   <li>{@link #agentArgument} gives the {@code -javaagent:} argument to add to the game's JVM arguments,
 *       or null when capes are switched off / no agent has been published or downloaded yet.</li>
 * </ul>
 */
public final class CapeSupport {

    private static final String TAG = "CapeSupport";
    private static final String JAR_PREFIX = "avero-capes-agent-";
    private static final String POINTER_FILE = "current.txt";
    private static final Pattern SAFE_JAR_NAME = Pattern.compile("^avero-capes-agent-[A-Za-z0-9._-]+\\.jar$");
    private static final Pattern HEX32 = Pattern.compile("[0-9a-f]{32}");
    private static final long MAX_JAR_BYTES = 5L * 1024 * 1024;
    private static final long RECHECK_MS = 60_000L;
    /** The agent build that ships inside the launcher. 1.2.0 is the first one that can show an offline skin. */
    static final String BUNDLED_VERSION = "1.2.0";
    static final String BUNDLED_SHA256 = "36eaf91009d894fbec08a882d3788c4a940ee9b2a89424c84698b803e8406ac3";
    private static final String BUNDLED_RESOURCE = "/agent/" + JAR_PREFIX + BUNDLED_VERSION + ".jar";
    /** With capes switched off the agent still loads (for the skin) but is pointed at a port nothing listens on. */
    static final String NO_SERVER_API = "http://127.0.0.1:9";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();

    private static volatile long lastAttemptMs;

    private CapeSupport() {}

    /**
     * Completes (never exceptionally) once the agent is ready or has been skipped.
     * {@code versionId} is only sent to the server for logging; the agent works for every version.
     */
    public static CompletableFuture<Void> prepare(LauncherSettings settings, String versionId) {
        try {
            ensureAgent();                                                    // bundled copy: works offline, first launch included
            if (settings == null || !settings.averoCapesEnabled) return CompletableFuture.completedFuture(null);
            String base = AveroId.get().api().baseUrl();
            if (base == null) return CompletableFuture.completedFuture(null); // demo mode: no server
            long now = System.currentTimeMillis();
            if (now - lastAttemptMs < RECHECK_MS) {
                return CompletableFuture.completedFuture(null);               // already checked a moment ago
            }
            lastAttemptMs = now;                                              // counts even if it fails: never retry-stack
            return CompletableFuture.runAsync(() -> install(base, versionId))
                    .orTimeout(8, TimeUnit.SECONDS)
                    .exceptionally(e -> {
                        AppLogger.get().warn(TAG, "Cape agent not updated (using the installed one, if any): " + e.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            AppLogger.get().warn(TAG, "Cape agent check failed: " + e.getMessage());
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Same as {@link #prepare(LauncherSettings, String)}; kept so callers that also pass the active account
     * still compile. The account is not needed here: it is added to the agent argument at launch time
     * (see {@link #agentArgument}).
     */
    public static CompletableFuture<Void> prepare(LauncherSettings settings, String versionId, Account account) {
        return prepare(settings, versionId);
    }

    /**
     * The complete JVM argument that loads the agent, e.g.
     * {@code -javaagent:C:\...\avero-capes-agent-1.2.0.jar=api=https://.../api/v1,self=<uuid>,skinfile=C:\...\skin.png};
     * or null when the agent has nothing to do for this launch.
     *
     * <p>The agent loads when capes are on (and a server is configured) <i>or</i> when the account wears an offline
     * skin: a skin must show whatever the cape switch says. With capes off the agent is given an unreachable server
     * address, so it can show the skin but never fetches a cape or anyone's shared skin.
     */
    public static String agentArgument(LauncherSettings settings, Account account) {
        try {
            if (settings == null) return null;
            Path skinPng = ownSkinFile(account);
            boolean capes = settings.averoCapesEnabled;
            String base = capes ? AveroId.get().api().baseUrl() : null;
            if (base == null && skinPng == null) return null;      // no server and no skin to show: nothing to do

            Path jar = ensureAgent();
            if (jar == null) return null;
            String path = jar.toAbsolutePath().toString();
            if (path.indexOf('=') >= 0) { // '=' would split the agent's option string in the wrong place
                AppLogger.get().warn(TAG, "The launcher data folder path contains '=', so the Avero agent can't be used.");
                return null;
            }
            StringBuilder sb = new StringBuilder("-javaagent:").append(path).append("=api=").append(base != null ? base : NO_SERVER_API);
            if (base != null) {
                // Shared skins of offline names (looked up by player name). Commas would split the option string.
                String skins = AveroId.get().api().skinsUrl();
                if (skins != null && skins.indexOf(',') < 0) sb.append(",skins=").append(skins);
            }
            if (account != null && account.username != null && account.username.matches("[A-Za-z0-9_]{1,16}")) {
                sb.append(",name=").append(account.username);
            }
            // The skin this offline account wears: the agent shows it on the player's own character straight from this
            // PC (like TLauncher does), so it works without a resource pack, without options.txt and without a server.
            if (skinPng != null) {
                sb.append(",skinfile=").append(skinPng.toAbsolutePath()).append(",skinmodel=")
                  .append("slim".equals(account.offlineSkinVariant) ? "slim" : "classic");
            }
            if (account != null && account.minecraftUuid != null) {
                String self = account.minecraftUuid.replace("-", "").toLowerCase(Locale.ROOT);
                if (HEX32.matcher(self).matches()) sb.append(",self=").append(self);
            }
            return sb.toString();
        } catch (Exception e) {
            AppLogger.get().warn(TAG, "Could not build the Avero agent argument: " + e.getMessage());
            return null;
        }
    }

    /** The PNG of the library skin an OFFLINE account wears, or null (no skin, other account type, file gone, unusable path). */
    static Path ownSkinFile(Account account) {
        if (account == null || account.type != Account.Type.OFFLINE
                || account.offlineSkinId == null || account.offlineSkinId.isBlank()) return null;
        Path png = new com.averolauncher.skins.SkinLibrary().pngPath(account.offlineSkinId);
        if (!Files.isRegularFile(png)) return null;
        if (png.toAbsolutePath().toString().indexOf(',') >= 0) {   // a comma would split the agent's option string
            AppLogger.get().warn(TAG, "The skin file path contains a comma, so the agent can't show it: " + png);
            return null;
        }
        return png;
    }

    /**
     * The agent jar to load: the installed one, unless it is older than the bundled build (or missing), in which
     * case the bundled build is unpacked and becomes the installed one. Null only if the bundled copy is unreadable.
     */
    static Path ensureAgent() {
        Path installed = installedAgent();
        if (installed != null && !olderThanBundled(installed)) return installed;
        try (java.io.InputStream in = CapeSupport.class.getResourceAsStream(BUNDLED_RESOURCE)) {
            if (in == null) {
                AppLogger.get().warn(TAG, "The bundled Avero agent is missing from the launcher.");
                return installed;
            }
            byte[] bytes = in.readAllBytes();
            if (!BUNDLED_SHA256.equals(sha256(bytes))) {
                AppLogger.get().warn(TAG, "The bundled Avero agent failed its SHA-256 check; not using it.");
                return installed;
            }
            Path dir = agentDir();
            Files.createDirectories(dir);
            Path target = dir.resolve(JAR_PREFIX + BUNDLED_VERSION + ".jar");
            if (!(Files.isRegularFile(target) && BUNDLED_SHA256.equals(sha256(Files.readAllBytes(target))))) {
                Path tmp = dir.resolve(target.getFileName() + ".part");
                Files.write(tmp, bytes);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                AppLogger.get().info(TAG, "Unpacked the bundled Avero agent " + BUNDLED_VERSION);
            }
            Files.writeString(dir.resolve(POINTER_FILE), target.getFileName().toString(), StandardCharsets.UTF_8);
            removeOtherBuilds(dir, target.getFileName().toString());
            return target;
        } catch (Exception e) {
            AppLogger.get().warn(TAG, "Could not unpack the bundled Avero agent: " + e.getMessage());
            return installed;
        }
    }

    /** True if the installed jar's version (from its file name) is older than the build shipped with the launcher. */
    private static boolean olderThanBundled(Path jar) {
        String n = jar.getFileName().toString();
        if (!n.startsWith(JAR_PREFIX) || !n.endsWith(".jar")) return true;
        String v = n.substring(JAR_PREFIX.length(), n.length() - ".jar".length());
        return com.averolauncher.update.VersionComparator.isNewer(v, BUNDLED_VERSION);
    }

    // ------------------------------------------------------------------ internals

    private static Path agentDir() {
        return PathResolver.launcherDataDir().resolve("agent");
    }

    /** The agent jar recorded by the last successful install, or null. */
    private static Path installedAgent() {
        try {
            Path pointer = agentDir().resolve(POINTER_FILE);
            if (!Files.isRegularFile(pointer)) return null;
            String name = Files.readString(pointer, StandardCharsets.UTF_8).trim();
            if (!SAFE_JAR_NAME.matcher(name).matches()) return null;
            Path jar = agentDir().resolve(name);
            return Files.isRegularFile(jar) ? jar : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void install(String base, String versionId) {
        try {
            String mc = versionId == null ? "*" : versionId;
            JsonObject manifest = getJson(base + "/mod/avero-capes?mc=" + java.net.URLEncoder.encode(mc, StandardCharsets.UTF_8));
            if (manifest == null) { // nothing published (yet): keep using what is installed, but leave a trace
                AppLogger.get().info(TAG, "The server has no Avero cape agent published"
                        + (installedAgent() != null ? "; using the installed one." : "; Avero capes won't show in-game."));
                return;
            }
            String version = manifest.get("version").getAsString().replaceAll("[^A-Za-z0-9._-]", "_");
            if (com.averolauncher.update.VersionComparator.isNewer(version, BUNDLED_VERSION)) {
                // e.g. a server still listing 1.1.0, which cannot show an offline skin: keep the bundled build.
                AppLogger.get().info(TAG, "The server lists Avero agent " + version + ", older than the bundled "
                        + BUNDLED_VERSION + "; keeping the bundled one.");
                return;
            }
            String url = manifest.get("url").getAsString();
            String sha = manifest.get("sha256").getAsString().toLowerCase(Locale.ROOT);

            Path dir = agentDir();
            Files.createDirectories(dir);
            Path target = dir.resolve(JAR_PREFIX + version + ".jar");

            if (!(Files.isRegularFile(target) && sha.equals(sha256(Files.readAllBytes(target))))) {
                byte[] bytes = download(url);
                if (bytes == null || !sha.equals(sha256(bytes))) {
                    AppLogger.get().warn(TAG, "Cape agent download failed its SHA-256 check; not installing.");
                    return;
                }
                Path tmp = dir.resolve(target.getFileName() + ".part");
                Files.write(tmp, bytes);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                AppLogger.get().info(TAG, "Installed Avero cape agent " + version);
            }
            Files.writeString(dir.resolve(POINTER_FILE), target.getFileName().toString(), StandardCharsets.UTF_8);
            removeOtherBuilds(dir, target.getFileName().toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void removeOtherBuilds(Path dir, String keepName) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, JAR_PREFIX + "*.jar")) {
            for (Path p : ds) {
                if (!p.getFileName().toString().equals(keepName)) Files.deleteIfExists(p);
            }
        } catch (Exception e) {
            AppLogger.get().warn(TAG, "Could not clean old cape agent builds: " + e.getMessage());
        }
    }

    private static JsonObject getJson(String url) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(4))
                .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 404) return null;
        if (r.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + r.statusCode());
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private static byte[] download(String url) throws Exception {
        HttpResponse<byte[]> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(12)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() != 200 || r.body().length == 0 || r.body().length > MAX_JAR_BYTES) return null;
        return r.body();
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
}
