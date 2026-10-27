package com.averolauncher.update.incremental;

import java.io.Closeable;
import java.io.IOException;

/**
 * Cooperative cancellation plus a stall watchdog. {@link #cancel()} closes whatever stream is currently
 * being read so a blocked socket read ends immediately; the same mechanism cuts a transfer that has
 * delivered no bytes for {@code stallMillis}.
 */
public final class CancelToken implements AutoCloseable {

    public static final class CancelledException extends IOException {
        public CancelledException() { super("Update cancelled"); }
    }

    private volatile boolean cancelled;
    private volatile boolean stopped;
    private volatile Closeable current;
    private volatile long lastActivity = System.nanoTime();
    private final long stallNanos;

    public CancelToken() { this(30_000); }

    public CancelToken(long stallMillis) {
        this.stallNanos = stallMillis * 1_000_000L;
        Thread watchdog = new Thread(() -> {
            while (!stopped) {
                try { Thread.sleep(Math.max(100, Math.min(2000, stallMillis / 4))); } catch (InterruptedException e) { return; }
                Closeable c = current;
                if (c != null && System.nanoTime() - lastActivity > stallNanos) closeQuietly(c);
            }
        }, "update-stall-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    public void cancel() {
        cancelled = true;
        closeQuietly(current);
    }

    public boolean isCancelled() { return cancelled; }

    public void check() throws CancelledException {
        if (cancelled) throw new CancelledException();
    }

    void attach(Closeable c) { current = c; touch(); }
    void detach() { current = null; }
    void touch() { lastActivity = System.nanoTime(); }

    @Override public void close() { stopped = true; current = null; }

    private static void closeQuietly(Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (IOException ignored) { }
    }
}
