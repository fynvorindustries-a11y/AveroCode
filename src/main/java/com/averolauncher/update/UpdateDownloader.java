package com.averolauncher.update;

import com.averolauncher.logging.AppLogger;
import com.averolauncher.util.PathResolver;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.LongConsumer;

/**
 * Downloads an update asset into AveroLauncher's own data directory. This
 * does not run or install anything itself: AveroLauncher isn't packaged with
 * a native self-updating installer yet (see README's "what's intentionally
 * not built yet"), so the honest, safe behavior is to fetch the file and
 * hand the person off to it in their file manager — they run it themselves,
 * the same as installing any other update.
 */
public class UpdateDownloader {

    private static final String TAG = "UpdateDownloader";
    private static final int BUFFER_SIZE = 64 * 1024;

    private final HttpClient httpClient;
    private final Executor backgroundExecutor;

    public UpdateDownloader(Executor backgroundExecutor) {
        this.backgroundExecutor = backgroundExecutor;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** Directory update downloads land in: {@code <launcherDataDir>/updates}. */
    public static Path updatesDir() {
        return PathResolver.launcherDataDir().resolve("updates");
    }

    /**
     * Downloads {@code info}'s asset, reporting bytes-received-so-far to
     * {@code onProgress} (called from the background thread, not the FX
     * thread — callers must hop back with Platform.runLater themselves).
     */
    public CompletableFuture<Path> download(UpdateInfo info, LongConsumer onProgress) {
        return CompletableFuture.supplyAsync(() -> downloadNow(info, onProgress), backgroundExecutor);
    }

    private Path downloadNow(UpdateInfo info, LongConsumer onProgress) {
        if (!info.hasDownloadableAsset()) {
            throw new IllegalStateException("This release has no downloadable asset for this platform");
        }
        try {
            Path dir = updatesDir();
            Files.createDirectories(dir);
            Path target = dir.resolve(info.assetName);
            Path tmp = target.resolveSibling(target.getFileName() + ".part");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(info.assetUrl))
                    .timeout(Duration.ofMinutes(10))
                    .header("User-Agent", "AveroLauncher/" + AppVersion.CURRENT + " (update-downloader)")
                    .GET()
                    .build();

            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " downloading " + info.assetUrl);
            }

            long total = 0;
            try (InputStream in = response.body();
                 var out = Files.newOutputStream(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    total += read;
                    if (onProgress != null) {
                        onProgress.accept(total);
                    }
                }
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            AppLogger.get().info(TAG, "Downloaded update to " + target);
            return target;
        } catch (Exception e) {
            AppLogger.get().error(TAG, "Update download failed", e);
            throw new RuntimeException(e);
        }
    }
}
