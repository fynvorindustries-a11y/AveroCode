package com.averolauncher.minecraft;

import com.google.gson.Gson;
import com.averolauncher.download.MirrorRouter;
import com.averolauncher.logging.AppLogger;
import com.averolauncher.model.manifest.AssetIndex;
import com.averolauncher.model.manifest.VersionDetail;
import com.averolauncher.model.manifest.VersionManifest;
import com.averolauncher.util.PathResolver;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Fetches Minecraft version metadata from Mojang's public endpoints and
 * caches it locally so the launcher can work offline once metadata has been
 * seen at least once, and doesn't hammer the network for unchanged data.
 *
 * Real endpoints used (these are Mojang's actual public launcher-metadata
 * URLs, the same ones the official launcher uses):
 *   - https://piston-meta.mojang.com/mc/game/version_manifest_v2.json
 *   - the per-version "url" field inside that manifest
 *   - the asset index "url" field inside each version's JSON
 */
public class VersionManifestService {

    private static final String TAG = "VersionManifestService";
    private static final String MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    private final HttpClient httpClient;
    private final HttpClient httpClientTls12Fallback;
    private final Gson gson = new Gson();
    private final Path cacheDir;
    private final Executor backgroundExecutor;

    /** Shared by concurrent callers (Play + Versions pages) so a dead network isn't probed N times in parallel. */
    private CompletableFuture<VersionManifest> manifestInFlight;

    public VersionManifestService(Executor backgroundExecutor) {
        this.backgroundExecutor = backgroundExecutor;
        this.httpClient = buildHttpClient(false);
        this.httpClientTls12Fallback = buildHttpClient(true);
        this.cacheDir = PathResolver.launcherDataDir().resolve("cache").resolve("metadata");
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            AppLogger.get().error(TAG, "Failed to create metadata cache dir", e);
        }
    }

    /**
     * Builds an HttpClient for talking to Mojang's endpoints.
     * <p>
     * {@code forceHttp1AndTls12} selects the "safe mode" fallback client:
     * HTTP/1.1 (skips ALPN/HTTP-2 negotiation) pinned to TLS 1.2. This is
     * used only as a retry when the default client (HTTP/2 negotiated down
     * to 1.1 as needed, default TLS) either gets a handshake failure or
     * times out — some antivirus HTTPS scanners / corporate TLS-inspecting
     * proxies handle one of the two negotiation styles far worse than the
     * other, and which one is worse varies per machine, so we don't force
     * either style up front — we try the normal path first and only fall
     * back if it actually fails.
     * <p>
     * The default client follows the system proxy (see {@code java.net.useSystemProxies}
     * in AveroLauncherApplication). The fallback client connects DIRECTLY, so a stale or
     * dead system proxy entry can't make both attempts fail the same way.
     */
    private static HttpClient buildHttpClient(boolean forceHttp1AndTls12) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(forceHttp1AndTls12 ? 8 : 10))
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (forceHttp1AndTls12) {
            builder.proxy(ProxySelector.of(null)); // null address = never use a proxy
            builder.version(HttpClient.Version.HTTP_1_1);
            try {
                SSLContext ctx = SSLContext.getInstance("TLSv1.2");
                ctx.init(null, null, null);
                builder.sslContext(ctx);
            } catch (Exception e) {
                AppLogger.get().warn(TAG, "Could not build TLSv1.2 fallback SSLContext: " + e.getMessage());
            }
        }
        return builder.build();
    }

    /**
     * Sends a GET, trying Mojang's host and (when it is unreachable) the mirror; see
     * {@link MirrorRouter}. Hosts with no mirror mapping behave exactly as before.
     * A non-200 answer from one candidate also moves on to the next; if every candidate
     * answered non-200 the last response is returned so callers report the status as usual.
     */
    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler)
            throws IOException, InterruptedException {
        String original = request.uri().toString();
        String mirror = MirrorRouter.mirrorUrl(original);
        if (mirror == null) {
            return sendOnce(request, bodyHandler);
        }
        boolean mirrorFirst = MirrorRouter.preferMirror();
        IOException lastError = null;
        HttpResponse<T> lastResponse = null;
        boolean primaryNetworkFailure = false;
        for (int i = 0; i < 2; i++) {
            boolean useMirror = (i == 0) == mirrorFirst;
            HttpRequest attempt = useMirror
                    ? HttpRequest.newBuilder(URI.create(mirror)).timeout(request.timeout().orElse(Duration.ofSeconds(15))).GET().build()
                    : request;
            try {
                HttpResponse<T> response = sendOnce(attempt, bodyHandler);
                if (response.statusCode() == 200) {
                    if (useMirror) {
                        if (!mirrorFirst && primaryNetworkFailure) MirrorRouter.mirrorSucceededAfterPrimaryFailure();
                        else MirrorRouter.noteMirrorUse();
                    } else if (mirrorFirst) {
                        MirrorRouter.reportPrimarySuccess();
                    }
                    return response;
                }
                lastResponse = response;
                AppLogger.get().warn(TAG, (useMirror ? "Mirror" : "Mojang") + " answered HTTP "
                        + response.statusCode() + " for " + attempt.uri());
            } catch (IOException e) {
                lastError = e;
                if (!useMirror) primaryNetworkFailure = MirrorRouter.isNetworkLevel(e);
                AppLogger.get().warn(TAG, (useMirror ? "Mirror" : "Mojang") + " request failed for "
                        + attempt.uri() + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        if (lastResponse != null) return lastResponse;
        throw lastError;
    }

    /** One host: default client first, then the direct HTTP/1.1 + TLS 1.2 fallback client. */
    private <T> HttpResponse<T> sendOnce(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler)
            throws IOException, InterruptedException {
        try {
            return httpClient.send(request, bodyHandler);
        } catch (IOException e) {
            AppLogger.get().warn(TAG, "Request to " + request.uri() + " failed on default settings ("
                    + e.getClass().getSimpleName() + ": " + e.getMessage() + "), retrying direct (no proxy) on HTTP/1.1 + TLS 1.2");
            return httpClientTls12Fallback.send(request, bodyHandler);
        }
    }

    private static boolean isHandshakeFailure(Throwable e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof SSLHandshakeException) return true;
            String msg = current.getMessage();
            if (msg != null && msg.toLowerCase(Locale.ROOT).contains("handshake_failure")) return true;
            current = current.getCause();
        }
        return false;
    }

    private static boolean isTimeout(Throwable e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof java.net.http.HttpTimeoutException) return true;
            if (current instanceof java.net.http.HttpConnectTimeoutException) return true;
            if (current instanceof java.net.SocketTimeoutException) return true;
            current = current.getCause();
        }
        return false;
    }

    /**
     * Turns a network failure into a RuntimeException whose message is
     * actually useful in the UI. The JDK's raw messages for these cases
     * ("Received fatal alert: handshake_failure", generic timeouts) tell
     * the user nothing actionable, so we replace them with guidance.
     */
    private static RuntimeException networkFailure(String context, Exception e) {
        if (isHandshakeFailure(e)) {
            AppLogger.get().error(TAG, context + " (TLS handshake failure, both attempts)", e);
            return new RuntimeException(context + ": TLS handshake failed. This is usually caused by "
                    + "antivirus HTTPS/SSL scanning (Kaspersky, ESET, Avast, etc.), a VPN or corporate proxy, "
                    + "or an incorrect system clock. Try temporarily disabling HTTPS scanning in your antivirus, "
                    + "disconnecting your VPN, or checking your system date and time, then retry.");
        }
        if (isTimeout(e)) {
            AppLogger.get().error(TAG, context + " (timed out, both attempts)", e);
            return new RuntimeException(context + ": connection timed out. Mojang's servers may be unreachable "
                    + "from this network. Check your internet connection, proxy, VPN, firewall or antivirus, then retry. "
                    + "Details were written to " + PathResolver.launcherDataDir().resolve("logs").resolve("launcher.log")
                    + " (look for [net-diag] lines).");
        }
        return new RuntimeException(context + ": " + e.getMessage(), e);
    }

    /**
     * Fetches the version manifest asynchronously. Falls back to the cached
     * copy on network failure so the launcher stays usable offline. Throws
     * only if there is neither a live fetch nor any usable cache.
     * <p>
     * A single timeout is retried a couple of times with a short backoff
     * before giving up: a transient dropped-packet blip (flaky wifi, a
     * momentarily busy firewall) shouldn't force the person to notice the
     * error and manually hit Retry — only a genuinely dead connection should
     * surface as an error.
     */
    public synchronized CompletableFuture<VersionManifest> fetchManifest(boolean allowCache) {
        if (allowCache && manifestInFlight != null && !manifestInFlight.isDone()) {
            return manifestInFlight;
        }
        CompletableFuture<VersionManifest> future = CompletableFuture.supplyAsync(() -> {
            Path cacheFile = cacheDir.resolve("version_manifest_v2.json");
            final int maxAttempts = 3;

            Exception lastError = null;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(MANIFEST_URL))
                            .timeout(Duration.ofSeconds(15))
                            .GET()
                            .build();
                    HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() != 200) {
                        throw new IOException("Unexpected status code " + response.statusCode());
                    }
                    String body = response.body();
                    Files.writeString(cacheFile, body);
                    AppLogger.get().info(TAG, "Fetched live version manifest" + (attempt > 1 ? " (attempt " + attempt + ")" : ""));
                    return gson.fromJson(body, VersionManifest.class);
                } catch (Exception e) {
                    lastError = e;
                    AppLogger.get().warn(TAG, "Manifest fetch attempt " + attempt + "/" + maxAttempts + " failed: " + e.getMessage());
                    // Only retry timeouts automatically — a definite rejection (handshake failure,
                    // 4xx/5xx) won't fix itself by waiting, so fail fast for those instead.
                    if (attempt < maxAttempts && isTimeout(e)) {
                        try {
                            Thread.sleep(1500L * attempt);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        continue;
                    }
                    break;
                }
            }

            AppLogger.get().warn(TAG, "Live manifest fetch failed after retries: " + lastError.getMessage());
            if (allowCache && Files.exists(cacheFile)) {
                try {
                    String cached = Files.readString(cacheFile);
                    AppLogger.get().info(TAG, "Using cached version manifest");
                    return gson.fromJson(cached, VersionManifest.class);
                } catch (IOException cacheError) {
                    AppLogger.get().error(TAG, "Cached manifest unreadable", cacheError);
                }
            }
            logNetworkDiagnostics(MANIFEST_URL);
            throw networkFailure("Could not load version manifest: no network and no usable cache", lastError);
        }, backgroundExecutor);
        if (allowCache) manifestInFlight = future;
        return future;
    }

    /**
     * Runs after a total manifest failure, on a background thread (so it never delays the
     * error shown in the UI), purely to record WHY the connection failed in launcher.log:
     * which proxy Java chose, what the host resolves to, and whether a raw TCP connection
     * to each address (and to an unrelated control host) succeeds. "HTTP connect timed out"
     * alone can't distinguish a firewall, a proxy, DNS, or a blocked route.
     */
    private void logNetworkDiagnostics(String url) {
        backgroundExecutor.execute(() -> {
            try {
                URI uri = URI.create(url);
                String host = uri.getHost();
                AppLogger.get().info(TAG, "[net-diag] proxy chosen by Java for " + uri + ": "
                        + ProxySelector.getDefault().select(uri)
                        + " (useSystemProxies=" + System.getProperty("java.net.useSystemProxies")
                        + ", preferIPv4Stack=" + System.getProperty("java.net.preferIPv4Stack") + ")");

                InetAddress[] addresses;
                try {
                    addresses = InetAddress.getAllByName(host);
                } catch (Exception dnsError) {
                    AppLogger.get().warn(TAG, "[net-diag] RESULT: DNS lookup for " + host + " failed (" + dnsError
                            + "). Check DNS settings, VPN, or a hosts-file entry.");
                    return;
                }

                boolean anyMojangOk = false;
                for (int i = 0; i < Math.min(addresses.length, 4); i++) {
                    String r = probeTcp(addresses[i], 443);
                    anyMojangOk |= r.startsWith("connected");
                    AppLogger.get().info(TAG, "[net-diag] TCP " + host + " " + addresses[i].getHostAddress() + ":443 -> " + r);
                }
                String control = probeTcp(InetAddress.getByName("1.1.1.1"), 443);
                boolean controlOk = control.startsWith("connected");
                AppLogger.get().info(TAG, "[net-diag] TCP control 1.1.1.1:443 -> " + control);

                String verdict;
                if (anyMojangOk) {
                    verdict = "TCP to Mojang works from Java, so the failure is above TCP: a TLS-inspecting "
                            + "antivirus/proxy, or the system proxy setting above.";
                } else if (controlOk) {
                    verdict = "Internet works from Java but Mojang's addresses are unreachable: something between "
                            + "this PC and Mojang (ISP route/block, VPN, firewall rule) is dropping them. "
                            + "Try the URL in a browser, a different network, or a VPN.";
                } else {
                    verdict = "Java cannot open ANY outbound connection: a firewall/antivirus is blocking java/javaw, "
                            + "or this network requires a proxy Java doesn't know about.";
                }
                AppLogger.get().warn(TAG, "[net-diag] RESULT: " + verdict);
            } catch (Exception e) {
                AppLogger.get().warn(TAG, "[net-diag] diagnostics failed: " + e);
            }
        });
    }

    private static String probeTcp(InetAddress address, int port) {
        long start = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), 4000);
            return "connected in " + (System.nanoTime() - start) / 1_000_000 + " ms";
        } catch (Exception e) {
            return "FAILED after " + (System.nanoTime() - start) / 1_000_000 + " ms (" + e.getClass().getSimpleName() + ")";
        }
    }

    /**
     * Fetches version detail from Mojang's manifest entry (vanilla versions).
     * Mod-loader versions installed locally should use fetchVersionDetailLocal() instead.
     */
    public CompletableFuture<VersionDetail> fetchVersionDetail(VersionManifest.VersionEntry entry) {
        return CompletableFuture.supplyAsync(() -> fetchVersionDetailSync(entry.id, entry.url), backgroundExecutor)
                .thenCompose(this::resolveInheritsFrom);
    }

    /**
     * Fetches version detail for a locally-installed version id (Fabric, Forge,
     * NeoForge, OptiFine) whose JSON lives at:
     *   <minecraftDir>/versions/<id>/<id>.json
     * Falls back to network if the file isn't on disk (unusual but possible if the
     * user points AveroLauncher at a shared .minecraft directory).
     */
    public CompletableFuture<VersionDetail> fetchVersionDetailLocal(String versionId, java.nio.file.Path minecraftDir) {
        return CompletableFuture.supplyAsync(() -> {
            // 1. Try the installed version JSON first (mod loaders write their JSON here)
            java.nio.file.Path localJson = minecraftDir.resolve("versions").resolve(versionId).resolve(versionId + ".json");
            if (java.nio.file.Files.exists(localJson)) {
                try {
                    String content = java.nio.file.Files.readString(localJson);
                    AppLogger.get().info(TAG, "Loaded local version detail for " + versionId);
                    return gson.fromJson(content, VersionDetail.class);
                } catch (java.io.IOException e) {
                    AppLogger.get().warn(TAG, "Could not read local version JSON for " + versionId + ": " + e.getMessage());
                }
            }
            // 2. Try the metadata cache (may have been fetched before)
            java.nio.file.Path cacheFile = cacheDir.resolve(versionId + ".json");
            if (java.nio.file.Files.exists(cacheFile)) {
                try {
                    String cached = java.nio.file.Files.readString(cacheFile);
                    AppLogger.get().info(TAG, "Using cached version detail for " + versionId);
                    return gson.fromJson(cached, VersionDetail.class);
                } catch (java.io.IOException e) {
                    AppLogger.get().warn(TAG, "Could not read cached version JSON for " + versionId);
                }
            }
            throw new RuntimeException("Version '" + versionId + "' not found locally. "
                    + "Install it with the mod loader's own installer first, then launch from AveroLauncher.");
        }, backgroundExecutor).thenCompose(this::resolveInheritsFrom);
    }

    /**
     * Resolves {@code inheritsFrom} recursively (max 4 hops to guard against
     * malformed metadata). The child always wins on single-value fields;
     * list fields (libraries, game args, jvm args) are merged parent-first.
     */
    private CompletableFuture<VersionDetail> resolveInheritsFrom(VersionDetail child) {
        return resolveInheritsFrom(child, 0);
    }

    private CompletableFuture<VersionDetail> resolveInheritsFrom(VersionDetail child, int depth) {
        if (child.inheritsFrom == null || child.inheritsFrom.isBlank() || depth >= 4) {
            return CompletableFuture.completedFuture(child);
        }
        AppLogger.get().info(TAG, child.id + " inheritsFrom " + child.inheritsFrom + " — fetching parent");
        // Check local metadata cache first so we don't go to the network when offline
        Path parentCache = cacheDir.resolve(child.inheritsFrom + ".json");
        if (Files.exists(parentCache)) {
            try {
                String cached = Files.readString(parentCache);
                VersionDetail parent = gson.fromJson(cached, VersionDetail.class);
                AppLogger.get().info(TAG, "Resolved parent " + child.inheritsFrom + " from cache");
                return resolveInheritsFrom(parent, depth + 1)
                        .thenApply(resolvedParent -> mergeInto(child, resolvedParent));
            } catch (IOException e) {
                AppLogger.get().warn(TAG, "Could not read cached parent " + child.inheritsFrom + ": " + e.getMessage());
            }
        }
        // Look up the parent entry in the manifest so we have a URL for it.
        return fetchManifest(true).thenCompose(manifest -> {
            VersionManifest.VersionEntry parentEntry = manifest.versions.stream()
                    .filter(v -> v.id.equals(child.inheritsFrom))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException(
                            "Parent version '" + child.inheritsFrom + "' for '" + child.id + "' not found in manifest"));
            return CompletableFuture.supplyAsync(
                    () -> fetchVersionDetailSync(parentEntry.id, parentEntry.url), backgroundExecutor);
        }).thenCompose(parent -> resolveInheritsFrom(parent, depth + 1))
          .thenApply(resolvedParent -> mergeInto(child, resolvedParent));
    }

    /** "group:artifact[:classifier]" for a Maven coordinate (version and @ext dropped), or null if unparseable. */
    static String libraryKey(String name) {
        if (name == null) return null;
        String[] p = name.split(":");
        if (p.length < 3) return null;
        String classifier = p.length >= 4 ? p[3].split("@", 2)[0] : "";
        return p[0] + ":" + p[1] + (classifier.isEmpty() ? "" : ":" + classifier);
    }

    /**
     * Merges resolved parent into child. Child fields win; list fields are
     * parent-first so the game sees the correct library/argument ordering.
     */
    private VersionDetail mergeInto(VersionDetail child, VersionDetail parent) {
        // Single-value fields: child wins (already set)
        if (child.mainClass == null)   child.mainClass   = parent.mainClass;
        if (child.assetIndex == null)  child.assetIndex  = parent.assetIndex;
        if (child.assets == null)      child.assets      = parent.assets;
        if (child.javaVersion == null) child.javaVersion = parent.javaVersion;
        if (child.downloads == null)   child.downloads   = parent.downloads;

        // Libraries: parent first, then the child's. A child library REPLACES a parent one with the same
        // group:artifact[:classifier] even when the version differs (Forge/NeoForge ship newer guava, asm,
        // log4j... than vanilla); keeping both would put two versions of one jar on the classpath.
        if (parent.libraries != null) {
            java.util.Set<String> childKeys = new java.util.HashSet<>();
            if (child.libraries != null) {
                for (var lib : child.libraries) {
                    String k = libraryKey(lib.name);
                    if (k != null) childKeys.add(k);
                }
            }
            java.util.List<com.averolauncher.model.manifest.VersionDetail.Library> merged = new java.util.ArrayList<>();
            for (var lib : parent.libraries) {
                String k = libraryKey(lib.name);
                if (k == null || !childKeys.contains(k)) merged.add(lib);
            }
            if (child.libraries != null) merged.addAll(child.libraries);
            child.libraries = merged;
        }

        // Arguments: parent first, child appended (so child args take effect last)
        if (parent.arguments != null) {
            if (child.arguments == null) {
                child.arguments = parent.arguments;
            } else {
                if (child.arguments.game == null) child.arguments.game = parent.arguments.game;
                else if (parent.arguments.game != null) {
                    java.util.List<com.google.gson.JsonElement> gameArgs = new java.util.ArrayList<>(parent.arguments.game);
                    gameArgs.addAll(child.arguments.game);
                    child.arguments.game = gameArgs;
                }
                if (child.arguments.jvm == null) child.arguments.jvm = parent.arguments.jvm;
                else if (parent.arguments.jvm != null) {
                    java.util.List<com.google.gson.JsonElement> jvmArgs = new java.util.ArrayList<>(parent.arguments.jvm);
                    jvmArgs.addAll(child.arguments.jvm);
                    child.arguments.jvm = jvmArgs;
                }
            }
        }
        if (child.minecraftArguments == null && parent.minecraftArguments != null) {
            child.minecraftArguments = parent.minecraftArguments;
        }

        child.inheritsFrom = null; // mark as resolved
        return child;
    }

    /** Synchronous fetch+cache for a single version JSON by id and URL. */
    private VersionDetail fetchVersionDetailSync(String versionId, String url) {
        Path cacheFile = cacheDir.resolve(versionId + ".json");
        // file:// URIs (local-modded versions) must be read from disk, not via HTTP
        if (url != null && url.startsWith("file:")) {
            try {
                Path localPath = Path.of(URI.create(url));
                String content = Files.readString(localPath);
                return gson.fromJson(content, VersionDetail.class);
            } catch (Exception e) {
                AppLogger.get().warn(TAG, "Could not read local version file for " + versionId + ": " + e.getMessage());
                if (Files.exists(cacheFile)) {
                    try { return gson.fromJson(Files.readString(cacheFile), VersionDetail.class); } catch (IOException ignored) {}
                }
                throw new RuntimeException("Could not load local version detail for " + versionId, e);
            }
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("Unexpected status code " + response.statusCode());
            }
            String body = response.body();
            Files.writeString(cacheFile, body);
            return gson.fromJson(body, VersionDetail.class);
        } catch (Exception e) {
            AppLogger.get().warn(TAG, "Live version detail fetch failed for " + versionId + ": " + e.getMessage());
            if (Files.exists(cacheFile)) {
                try {
                    String cached = Files.readString(cacheFile);
                    AppLogger.get().info(TAG, "Using cached version detail for " + versionId);
                    return gson.fromJson(cached, VersionDetail.class);
                } catch (IOException cacheError) {
                    AppLogger.get().error(TAG, "Cached version detail unreadable", cacheError);
                }
            }
            throw networkFailure("Could not load version detail for " + versionId, e);
        }
    }

    /**
     * Fetches the asset index (list of resource files + hashes) referenced by
     * a version's assetIndex.url. Cached by asset index id (e.g. "17"), since
     * many versions share the same asset index.
     */
    public CompletableFuture<AssetIndex> fetchAssetIndex(VersionDetail.AssetIndexRef ref) {
        return CompletableFuture.supplyAsync(() -> {
            Path assetCacheDir = PathResolver.launcherDataDir().resolve("cache").resolve("asset-indexes");
            Path cacheFile = assetCacheDir.resolve(ref.id + ".json");
            try {
                Files.createDirectories(assetCacheDir);
            } catch (IOException e) {
                AppLogger.get().error(TAG, "Failed to create asset index cache dir", e);
            }
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(ref.url))
                        .timeout(Duration.ofSeconds(20))
                        .GET()
                        .build();
                HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IOException("Unexpected status code " + response.statusCode());
                }
                String body = response.body();
                Files.writeString(cacheFile, body);
                return gson.fromJson(body, AssetIndex.class);
            } catch (Exception e) {
                AppLogger.get().warn(TAG, "Live asset index fetch failed for " + ref.id + ": " + e.getMessage());
                if (Files.exists(cacheFile)) {
                    try {
                        String cached = Files.readString(cacheFile);
                        return gson.fromJson(cached, AssetIndex.class);
                    } catch (IOException cacheError) {
                        AppLogger.get().error(TAG, "Cached asset index unreadable", cacheError);
                    }
                }
                throw networkFailure("Could not load asset index " + ref.id, e);
            }
        }, backgroundExecutor);
    }
}
