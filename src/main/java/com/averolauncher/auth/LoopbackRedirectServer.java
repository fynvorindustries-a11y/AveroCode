package com.averolauncher.auth;

import com.averolauncher.logging.AppLogger;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * A minimal local HTTP server bound to 127.0.0.1 on an OS-assigned free port,
 * used only to receive Microsoft's OAuth2 authorization-code redirect. This
 * is the standard "loopback redirect" pattern for public/native clients
 * (RFC 8252) - no third-party server, no embedded browser: the system's
 * default browser is opened for sign-in, and this server just catches the
 * single redirect back with the authorization code, then shuts itself down.
 */
public class LoopbackRedirectServer implements AutoCloseable {

    private static final String TAG = "LoopbackRedirectServer";

    private final HttpServer server;
    private final int port;
    private final CompletableFuture<String> authorizationCode = new CompletableFuture<>();
    private final String expectedState;

    public LoopbackRedirectServer(String expectedState) throws IOException {
        this.expectedState = expectedState;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.port = server.getAddress().getPort();

        server.createContext("/callback", this::handleCallback);
        server.setExecutor(null); // default executor is fine; this handles exactly one request
        server.start();
        AppLogger.get().debug(TAG, "Loopback redirect server listening on port " + port);
    }

    public int getPort() {
        return port;
    }

    /** Completes once Microsoft redirects back with either a code or an error. */
    public CompletableFuture<String> awaitAuthorizationCode() {
        return authorizationCode;
    }

    private void handleCallback(HttpExchange exchange) throws IOException {
        try {
            URI requestUri = exchange.getRequestURI();
            String query = requestUri.getQuery();
            var params = parseQuery(query);

            String error = params.get("error");
            String code = params.get("code");
            String state = params.get("state");

            String responseHtml;
            if (error != null) {
                String description = params.getOrDefault("error_description", error);
                responseHtml = htmlPage("Sign-in failed", "AveroLauncher could not sign you in: " + description
                        + ". You can close this window and try again.");
                authorizationCode.completeExceptionally(new IOException("Microsoft returned an error: " + description));
            } else if (code == null) {
                responseHtml = htmlPage("Sign-in failed", "No authorization code was received. You can close this window.");
                authorizationCode.completeExceptionally(new IOException("No authorization code in redirect"));
            } else if (expectedState != null && !expectedState.equals(state)) {
                responseHtml = htmlPage("Sign-in failed", "Security check failed (state mismatch). Please try again.");
                authorizationCode.completeExceptionally(new IOException("OAuth state mismatch - possible CSRF, aborting"));
            } else {
                responseHtml = htmlPage("Signed in", "You're signed in to AveroLauncher. You can close this window and return to the launcher.");
                authorizationCode.complete(code);
            }

            byte[] bytes = responseHtml.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } finally {
            // Shut down shortly after responding; the browser tab has already gotten its response.
            Thread shutdownThread = new Thread(() -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                server.stop(0);
            }, "LoopbackRedirectServer-Shutdown");
            shutdownThread.setDaemon(true);
            shutdownThread.start();
        }
    }

    private java.util.Map<String, String> parseQuery(String query) {
        java.util.Map<String, String> result = new java.util.HashMap<>();
        if (query == null || query.isBlank()) return result;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String key = java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            result.put(key, value);
        }
        return result;
    }

    private String htmlPage(String title, String message) {
        return "<!DOCTYPE html><html><head><title>" + title + "</title>"
                + "<style>body{font-family:sans-serif;background:#16171d;color:#eceef5;"
                + "display:flex;align-items:center;justify-content:center;height:100vh;margin:0;}"
                + "div{text-align:center;max-width:400px;padding:24px;}"
                + "h1{color:#ff6a4d;}</style></head><body><div><h1>" + title + "</h1><p>" + message + "</p></div></body></html>";
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
