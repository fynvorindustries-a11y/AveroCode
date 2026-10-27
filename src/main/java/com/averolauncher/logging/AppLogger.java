package com.averolauncher.logging;

import com.averolauncher.util.PathResolver;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * A minimal, low-overhead file+console logger.
 *
 * Design goals (per low-end-hardware requirements):
 *  - No third-party heavy logging framework required at runtime for basic operation
 *    (slf4j/logback are present for library compatibility, but AveroLauncher's own
 *    logging uses this lightweight class to avoid extra overhead).
 *  - Single background thread drains a queue and writes to disk, so callers never
 *    block on file I/O.
 *  - No continuous polling; the writer thread parks on the queue.
 */
public final class AppLogger {

    public enum Level { DEBUG, INFO, WARN, ERROR }

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static volatile AppLogger instance;

    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private final Thread writerThread;
    private volatile PrintWriter writer;
    private volatile boolean running = true;

    private AppLogger() {
        try {
            Path logDir = PathResolver.launcherDataDir().resolve("logs");
            Files.createDirectories(logDir);
            Path logFile = logDir.resolve("launcher.log");
            writer = new PrintWriter(Files.newBufferedWriter(logFile,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND), false);
        } catch (IOException e) {
            writer = null;
            System.err.println("Failed to initialize log file: " + e.getMessage());
        }

        writerThread = new Thread(this::drainLoop, "AveroLauncher-Logger");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    public static AppLogger get() {
        AppLogger local = instance;
        if (local == null) {
            synchronized (AppLogger.class) {
                if (instance == null) {
                    instance = new AppLogger();
                }
                local = instance;
            }
        }
        return local;
    }

    private void drainLoop() {
        while (running || !queue.isEmpty()) {
            try {
                String line = queue.take();
                if (writer != null) {
                    writer.println(line);
                    writer.flush();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void enqueue(Level level, String tag, String message, Throwable t) {
        String ts = LocalDateTime.now().format(TS);
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(ts).append(']')
          .append(" [").append(level).append(']')
          .append(" [").append(tag).append("] ")
          .append(message);
        if (t != null) {
            sb.append(" | ").append(t.getClass().getName()).append(": ").append(t.getMessage());
        }
        String line = sb.toString();
        queue.offer(line);
        if (level == Level.ERROR || level == Level.WARN) {
            System.err.println(line);
        }
    }

    public void debug(String tag, String message) { enqueue(Level.DEBUG, tag, message, null); }
    public void info(String tag, String message) { enqueue(Level.INFO, tag, message, null); }
    public void warn(String tag, String message) { enqueue(Level.WARN, tag, message, null); }
    public void error(String tag, String message, Throwable t) { enqueue(Level.ERROR, tag, message, t); }
    public void error(String tag, String message) { enqueue(Level.ERROR, tag, message, null); }

    public void shutdown() {
        running = false;
        queue.offer("__shutdown__");
        try {
            writerThread.join(1000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        if (writer != null) {
            writer.flush();
            writer.close();
        }
    }
}
