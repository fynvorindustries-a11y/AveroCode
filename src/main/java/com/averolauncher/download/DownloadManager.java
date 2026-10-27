package com.averolauncher.download;

import com.averolauncher.logging.AppLogger;
import com.averolauncher.model.DownloadTask;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Real streaming download manager used for the client jar, libraries, and
 * assets. Never loads a full file into memory: uses a small fixed buffer and
 * writes straight to disk. Verifies SHA-1 when Mojang's metadata provides
 * one, and skips re-downloading files that already exist and pass
 * verification. Runs downloads on a bounded thread pool sized from launcher
 * settings (concurrentDownloads) so it never floods a low-end machine's
 * network or disk I/O.
 */
public class DownloadManager {

    private static final String TAG = "DownloadManager";
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int MAX_RETRIES = 3;

    private final HttpClient httpClient;
    private ExecutorService pool;
    private volatile boolean cancelled = false;

    public DownloadManager() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    private volatile Consumer<DownloadTask> globalTaskListener = null;

    /** Sets a global listener that receives every DownloadTask created across all downloadAll() calls. */
    public void setGlobalTaskListener(Consumer<DownloadTask> listener) {
        this.globalTaskListener = listener;
    }

    /**
     * Downloads a batch of files concurrently (bounded by concurrency).
     * Individual per-file progress is reported through each DownloadTask's
     * JavaFX properties, safe to bind directly to UI. Returns a future that
     * completes when the whole batch is done (successfully or not); check
     * each task's status afterward for failures.
     */
    public CompletableFuture<Void> downloadAll(List<PendingDownload> downloads, int concurrency,
                                                Consumer<DownloadTask> onTaskCreated) {
        cancelled = false;
        pool = Executors.newFixedThreadPool(Math.max(1, concurrency));
        List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();

        for (PendingDownload pending : downloads) {
            DownloadTask task = new DownloadTask(pending.displayName(), pending.url(), pending.expectedSize());
            if (onTaskCreated != null) {
                onTaskCreated.accept(task);
            }
            if (globalTaskListener != null) {
                globalTaskListener.accept(task);
            }
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> downloadOne(pending, task), pool);
            futures.add(future);
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .whenComplete((v, ex) -> shutdownPool());
    }

    public void cancel() {
        cancelled = true;
        shutdownPool();
    }

    private void shutdownPool() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    private void downloadOne(PendingDownload pending, DownloadTask task) {
        Path target = pending.targetPath();

        // Skip re-download if the file already exists and passes integrity check.
        if (Files.exists(target) && pending.expectedSha1() != null) {
            try {
                String actualHash = sha1Of(target);
                if (actualHash.equalsIgnoreCase(pending.expectedSha1())) {
                    task.addBytes(pending.expectedSize());
                    task.setStatus(DownloadTask.Status.COMPLETE);
                    AppLogger.get().debug(TAG, "Skipping (already valid): " + pending.displayName());
                    return;
                } else {
                    AppLogger.get().warn(TAG, "Existing file failed hash check, redownloading: " + pending.displayName());
                }
            } catch (Exception e) {
                AppLogger.get().warn(TAG, "Could not verify existing file, redownloading: " + pending.displayName());
            }
        } else if (Files.exists(target) && pending.expectedSha1() == null) {
            // No hash to check against; trust existing file to avoid unnecessary network use.
            task.addBytes(pending.expectedSize());
            task.setStatus(DownloadTask.Status.COMPLETE);
            return;
        }

        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            if (cancelled) {
                task.setStatus(DownloadTask.Status.CANCELLED);
                return;
            }
            try {
                task.setStatus(DownloadTask.Status.DOWNLOADING);
                streamDownload(pending.url(), target, task);

                if (pending.expectedSha1() != null) {
                    task.setStatus(DownloadTask.Status.VERIFYING);
                    String actualHash = sha1Of(target);
                    if (!actualHash.equalsIgnoreCase(pending.expectedSha1())) {
                        throw new IOException("SHA-1 mismatch for " + pending.displayName()
                                + " (expected " + pending.expectedSha1() + ", got " + actualHash + ")");
                    }
                }
                task.setStatus(DownloadTask.Status.COMPLETE);
                return;
            } catch (Exception e) {
                lastError = e;
                AppLogger.get().warn(TAG, "Attempt " + attempt + " failed for " + pending.displayName() + ": " + e.getMessage());
                try {
                    Files.deleteIfExists(target);
                } catch (IOException ignored) {}
            }
        }

        task.setError(lastError != null ? lastError.getMessage() : "Unknown download error");
        AppLogger.get().error(TAG, "Giving up on " + pending.displayName() + " after " + MAX_RETRIES + " attempts", lastError);
    }

    /**
     * Downloads {@code url}, falling back to the mirror (see {@link MirrorRouter}) if the
     * Mojang host can't be reached. Non-Mojang URLs (Fabric/Forge maven, etc) are never rewritten.
     * Integrity is checked by the caller against the SHA-1 from the version metadata.
     */
    private void streamDownload(String url, Path target, DownloadTask task) throws IOException, InterruptedException {
        java.util.List<String> candidates = MirrorRouter.candidates(url);
        if (candidates.size() == 1) {
            streamOne(url, target, task);
            return;
        }
        boolean mirrorFirst = !candidates.get(0).equals(url);
        IOException last = null;
        boolean primaryNetworkFailure = false;
        for (String candidate : candidates) {
            boolean isMirror = !candidate.equals(url);
            try {
                streamOne(candidate, target, task);
                if (isMirror) {
                    if (!mirrorFirst && primaryNetworkFailure) MirrorRouter.mirrorSucceededAfterPrimaryFailure();
                    else MirrorRouter.noteMirrorUse();
                } else if (mirrorFirst) {
                    MirrorRouter.reportPrimarySuccess();
                }
                return;
            } catch (IOException e) {
                last = e;
                if (cancelled) throw e;
                if (!isMirror) primaryNetworkFailure = MirrorRouter.isNetworkLevel(e);
                AppLogger.get().warn(TAG, (isMirror ? "Mirror" : "Mojang") + " download failed for "
                        + candidate + ": " + e.getMessage());
            }
        }
        throw last;
    }

    private void streamOne(String url, Path target, DownloadTask task) throws IOException, InterruptedException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " for " + url);
        }

        try (InputStream in = response.body();
             var out = Files.newOutputStream(tmp,
                     java.nio.file.StandardOpenOption.CREATE,
                     java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (cancelled) {
                    throw new IOException("Download cancelled");
                }
                out.write(buffer, 0, read);
                task.addBytes(read);
            }
        }

        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    public static String sha1Of(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        byte[] hashBytes = digest.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : hashBytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public record PendingDownload(String displayName, String url, Path targetPath, String expectedSha1, long expectedSize) {}
}
