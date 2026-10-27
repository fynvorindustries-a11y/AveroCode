package com.averolauncher.update.incremental;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTPS-only fetching for update assets.
 * <ul>
 *   <li>Every hop (including each redirect) must be https and on an allowed host: the manifest's own host,
 *       github.com, or GitHub's asset CDN ({@code *.githubusercontent.com}). Redirects are followed by hand
 *       so nothing is trusted just because a server said "go here".</li>
 *   <li>Plain http is refused. The single exception is loopback http, enabled only by the test-only
 *       constructor flag, never by production code.</li>
 *   <li>Range requests must be answered with a matching {@code Content-Range}; otherwise the caller is told
 *       the server ignored the range and can fall back to a full download.</li>
 * </ul>
 */
final class SecureHttp {

    static final class Response implements AutoCloseable {
        final int status;
        final InputStream body;
        final long contentLength;
        final boolean rangeHonoured;
        Response(int status, InputStream body, long contentLength, boolean rangeHonoured) {
            this.status = status; this.body = body; this.contentLength = contentLength; this.rangeHonoured = rangeHonoured;
        }
        @Override public void close() throws IOException { body.close(); }
    }

    private static final Pattern CONTENT_RANGE = Pattern.compile("^bytes (\\d+)-(\\d+)/(\\d+|\\*)$");
    private static final int MAX_REDIRECTS = 5;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final String originHost;
    private final boolean allowLoopbackHttp;
    private final URI origin;

    SecureHttp(URI origin, boolean allowLoopbackHttpForTests) {
        this.origin = origin;
        this.originHost = origin.getHost() == null ? "" : origin.getHost().toLowerCase(Locale.ROOT);
        this.allowLoopbackHttp = allowLoopbackHttpForTests;
    }

    /** The asset named {@code name} in the same directory as the manifest (names are validated to contain no '/'). */
    URI siblingOfManifest(String name) {
        return origin.resolve(name);
    }

    boolean allowed(URI u) {
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        if (host.isEmpty() || u.getUserInfo() != null) return false;
        if (scheme.equals("http")) {
            return allowLoopbackHttp && (host.equals("localhost") || host.equals("127.0.0.1"));
        }
        if (!scheme.equals("https")) return false;
        return host.equals(originHost) || host.equals("github.com") || host.endsWith(".github.com")
                || host.endsWith(".githubusercontent.com");
    }

    byte[] getBytes(URI uri, long maxBytes, CancelToken token) throws IOException {
        try (Response r = open(uri, -1, -1, token)) {
            if (r.status != 200) throw new IOException("HTTP " + r.status + " fetching " + uri.getPath());
            if (r.contentLength > maxBytes) throw new IOException("Response too large");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = r.body.read(buf)) != -1) {
                token.touch();
                out.write(buf, 0, n);
                if (out.size() > maxBytes) throw new IOException("Response too large");
            }
            return out.toByteArray();
        } finally {
            token.detach();
        }
    }

    /** Opens {@code uri}; {@code rangeStart < 0} means a whole-file GET. Caller closes the response. */
    Response open(URI uri, long rangeStart, long rangeEndInclusive, CancelToken token) throws IOException {
        URI current = uri;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            token.check();
            if (!allowed(current)) throw new IOException("Blocked download from untrusted location");
            HttpRequest.Builder rb = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofSeconds(60))
                    .header("User-Agent", "AveroLauncher-Updater")
                    .GET();
            if (rangeStart >= 0) rb.header("Range", "bytes=" + rangeStart + "-" + rangeEndInclusive);
            HttpResponse<InputStream> resp;
            try {
                resp = client.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted");
            }
            int code = resp.statusCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = resp.headers().firstValue("Location").orElse(null);
                resp.body().close();
                if (loc == null) throw new IOException("Redirect without a target");
                current = current.resolve(loc);
                continue;
            }
            token.attach(resp.body());
            long len = resp.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (rangeStart < 0) return new Response(code, resp.body(), len, false);
            if (code == 206) {
                Matcher m = CONTENT_RANGE.matcher(resp.headers().firstValue("Content-Range").orElse(""));
                if (!m.matches() || Long.parseLong(m.group(1)) != rangeStart || Long.parseLong(m.group(2)) != rangeEndInclusive) {
                    resp.body().close();
                    throw new IOException("Server returned a different range than requested");
                }
                return new Response(206, resp.body(), len, true);
            }
            return new Response(code, resp.body(), len, false);   // 200 = range ignored; others = caller decides
        }
        throw new IOException("Too many redirects");
    }
}
