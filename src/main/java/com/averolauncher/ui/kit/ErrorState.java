package com.averolauncher.ui.kit;

import java.util.Locale;

/**
 * Friendly errors. Network failures ("Connection reset", timeouts, DNS) become
 * "Can't reach Avero right now" with a Retry button; anything else gets a short, plain message.
 * UI-layer only: it reads the error text, it never changes how errors are produced or handled.
 */
public final class ErrorState {

    private ErrorState() {}

    private static final String[] NETWORK_HINTS = {
            "connection reset", "connection refused", "connection closed", "timed out", "timeout",
            "unknownhost", "unknown host", "unable to resolve", "nodename nor servname", "no route to host",
            "network is unreachable", "unreachable", "failed to connect", "could not connect",
            "sslhandshake", "premature eof", "broken pipe", "no such host"
    };

    public static boolean isNetwork(String raw) {
        if (raw == null) return false;
        String s = raw.toLowerCase(Locale.ROOT);
        for (String h : NETWORK_HINTS) if (s.contains(h)) return true;
        return false;
    }

    /** Joins a throwable's message and its causes' messages, so "wrapped" network errors still match. */
    public static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null && sb.length() < 600; c = c.getCause() == c ? null : c.getCause()) {
            sb.append(c.getClass().getSimpleName()).append(' ');
            if (c.getMessage() != null) sb.append(c.getMessage());
            sb.append(" | ");
        }
        return sb.toString();
    }

    public static EmptyState of(Throwable t, String service, Runnable retry) {
        return of(describe(t), service, retry);
    }

    /**
     * @param raw     the error text (from {@link #describe} or a message already shown to the user)
     * @param service what the user was trying to reach, e.g. "Avero" or "Modrinth"
     * @param retry   what the Retry button does; pass {@code null} to omit the button
     */
    public static EmptyState of(String raw, String service, Runnable retry) {
        String svc = (service == null || service.isBlank()) ? "Avero" : service;
        EmptyState s = isNetwork(raw)
                ? new EmptyState("Can't reach " + svc + " right now",
                        "Check your internet connection, then try again.")
                : new EmptyState("Something went wrong",
                        "Try again in a moment. If it keeps happening, check the launcher log in Settings.");
        if (retry != null) s.action("Retry", retry);
        return s;
    }
}
