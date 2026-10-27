package com.averolauncher.skins;

import com.averolauncher.logging.AppLogger;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Talks to Mojang about skins.
 *
 * <p><b>Why this is how skins reach the game.</b> Minecraft never reads a skin from the launcher: the
 * client asks Mojang's session server for the textures of the account's profile. So the only way a
 * custom skin reliably shows up in-game (singleplayer, every server, other players' screens) is to
 * set it on the Minecraft profile itself — this class does exactly that through the same
 * {@code /minecraft/profile/skins} endpoint the official launcher uses. Offline profiles have no
 * Mojang profile, so there is nothing to upload to.
 */
public final class SkinService {

    private static final String TAG = "SkinService";
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");

    public static class SkinException extends RuntimeException {
        /** True when the Microsoft/Minecraft token was refused — the account needs a fresh sign-in. */
        public final boolean sessionProblem;
        public SkinException(String message) { this(message, false, null); }
        public SkinException(String message, boolean sessionProblem, Throwable cause) {
            super(message, cause); this.sessionProblem = sessionProblem;
        }
    }

    /** A skin fetched from somewhere (Mojang by username, or a URL). */
    public static class RemoteSkin {
        public byte[] png;
        public String variant;   // "classic" | "slim" | null (unknown — auto-detect)
        public String name;
    }

    /** What Mojang says the profile's active skin currently is. */
    public static class ProfileSkin {
        public String skinUrl;
        public String variant;   // "classic" | "slim"
        public String profileName;
    }

    private final String api;       // https://api.minecraftservices.com
    private final String lookup;    // https://api.mojang.com
    private final String session;   // https://sessionserver.mojang.com
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "skin-service"); t.setDaemon(true); return t;
    });

    public SkinService() {
        this("https://api.minecraftservices.com", "https://api.mojang.com", "https://sessionserver.mojang.com");
    }

    /** Injectable endpoints (used by tests). */
    public SkinService(String api, String lookup, String session) {
        this.api = trim(api); this.lookup = trim(lookup); this.session = trim(session);
    }

    // ------------------------------------------------------------------ fetching skins

    /** Looks a Java Edition player up by name and returns their current skin. */
    public CompletableFuture<RemoteSkin> fetchByUsername(String username) {
        return CompletableFuture.supplyAsync(() -> {
            String name = username == null ? "" : username.trim();
            if (!USERNAME.matcher(name).matches()) {
                throw new SkinException("Minecraft names are 1\u201316 letters, numbers or underscores.");
            }
            HttpResponse<String> r = send(get(lookup + "/users/profiles/minecraft/" + URLEncoder.encode(name, StandardCharsets.UTF_8)), "Mojang");
            if (r.statusCode() == 204 || r.statusCode() == 404) throw new SkinException("No Minecraft: Java Edition player is named \u201c" + name + "\u201d.");
            if (r.statusCode() == 429) throw tooManyRequests();
            if (r.statusCode() / 100 != 2) throw new SkinException("Mojang couldn't look that name up (HTTP " + r.statusCode() + ").");
            JsonObject who = parseObject(r.body());
            if (who == null || !who.has("id")) throw new SkinException("Mojang sent an unexpected answer for that name.");
            String id = who.get("id").getAsString();
            String exact = who.has("name") ? who.get("name").getAsString() : name;

            HttpResponse<String> p = send(get(session + "/session/minecraft/profile/" + id), "Mojang");
            if (p.statusCode() == 429) throw tooManyRequests();
            if (p.statusCode() / 100 != 2 || p.body() == null || p.body().isBlank()) {
                throw new SkinException("Couldn't read " + exact + "\u2019s profile from Mojang (HTTP " + p.statusCode() + ").");
            }
            JsonObject prof = parseObject(p.body());
            String[] tex = prof == null ? null : textureSkin(prof);
            if (tex == null) throw new SkinException(exact + " is using a default skin \u2014 there's no custom skin to copy.");
            RemoteSkin out = new RemoteSkin();
            out.png = fetchBytes(tex[0]);
            out.variant = tex[1];
            out.name = exact;
            return out;
        }, POOL);
    }

    /** Downloads a PNG from a direct http(s) link (capped at {@link SkinImage#MAX_BYTES}). */
    public CompletableFuture<byte[]> download(String url) {
        return CompletableFuture.supplyAsync(() -> fetchBytes(url), POOL);
    }

    // ------------------------------------------------------------------ changing the account's skin

    /** Uploads {@code png} as the active skin of the profile that owns {@code mcAccessToken}. */
    public CompletableFuture<ProfileSkin> upload(String mcAccessToken, byte[] png, String variant) {
        return CompletableFuture.supplyAsync(() -> {
            String v = SkinLibrary.SLIM.equals(variant) ? "slim" : "classic";
            String boundary = "----AveroLauncher" + UUID.randomUUID().toString().replace("-", "");
            byte[] body = multipart(boundary, v, png);
            HttpRequest req = HttpRequest.newBuilder(URI.create(api + "/minecraft/profile/skins"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + mcAccessToken)
                    .header("Accept", "application/json")
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> r = send(req, "Mojang");
            checkProfileResponse(r, "change your skin");
            AppLogger.get().info(TAG, "Skin uploaded (" + v + ").");
            return parseProfile(r.body());
        }, POOL);
    }

    /** Resets the profile's skin to the default (Steve/Alex). */
    public CompletableFuture<ProfileSkin> reset(String mcAccessToken) {
        return CompletableFuture.supplyAsync(() -> {
            HttpRequest req = HttpRequest.newBuilder(URI.create(api + "/minecraft/profile/skins/active"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + mcAccessToken)
                    .header("Accept", "application/json")
                    .DELETE().build();
            HttpResponse<String> r = send(req, "Mojang");
            checkProfileResponse(r, "reset your skin");
            return parseProfile(r.body());
        }, POOL);
    }

    /** Reads the profile's currently active skin. */
    public CompletableFuture<ProfileSkin> currentProfile(String mcAccessToken) {
        return CompletableFuture.supplyAsync(() -> {
            HttpRequest req = HttpRequest.newBuilder(URI.create(api + "/minecraft/profile"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + mcAccessToken)
                    .header("Accept", "application/json")
                    .GET().build();
            HttpResponse<String> r = send(req, "Mojang");
            checkProfileResponse(r, "read your profile");
            return parseProfile(r.body());
        }, POOL);
    }

    // ------------------------------------------------------------------ internals

    private static void checkProfileResponse(HttpResponse<String> r, String what) {
        int c = r.statusCode();
        if (c / 100 == 2) return;
        if (c == 401) throw new SkinException("Your Microsoft session was refused. Remove the account and sign in again.", true, null);
        if (c == 403) throw new SkinException("Mojang refused to " + what + ". Make sure this account owns Minecraft: Java Edition.");
        if (c == 400) throw new SkinException("Mojang rejected that skin image. Check it's a valid 64\u00D764 PNG.");
        if (c == 429) throw tooManyRequests();
        throw new SkinException("Mojang's skin service returned HTTP " + c + ". Try again in a moment.");
    }

    private static SkinException tooManyRequests() {
        return new SkinException("Mojang is limiting requests right now. Wait a minute and try again.");
    }

    private static ProfileSkin parseProfile(String json) {
        ProfileSkin out = new ProfileSkin();
        JsonObject o = parseObject(json);
        if (o == null) return out;
        if (o.has("name") && !o.get("name").isJsonNull()) out.profileName = o.get("name").getAsString();
        if (o.has("skins") && o.get("skins").isJsonArray()) {
            JsonArray skins = o.getAsJsonArray("skins");
            JsonObject pick = null;
            for (JsonElement e : skins) {
                if (!e.isJsonObject()) continue;
                JsonObject s = e.getAsJsonObject();
                if (pick == null) pick = s;
                if (s.has("state") && "ACTIVE".equalsIgnoreCase(s.get("state").getAsString())) { pick = s; break; }
            }
            if (pick != null) {
                if (pick.has("url") && !pick.get("url").isJsonNull()) out.skinUrl = https(pick.get("url").getAsString());
                if (pick.has("variant") && !pick.get("variant").isJsonNull()) out.variant = pick.get("variant").getAsString().toLowerCase();
            }
        }
        return out;
    }

    /** {url, variant|null} of the SKIN texture inside a session-server profile, or null when none. */
    private static String[] textureSkin(JsonObject profile) {
        if (!profile.has("properties") || !profile.get("properties").isJsonArray()) return null;
        for (JsonElement e : profile.getAsJsonArray("properties")) {
            if (!e.isJsonObject()) continue;
            JsonObject p = e.getAsJsonObject();
            if (!p.has("name") || !"textures".equals(p.get("name").getAsString()) || !p.has("value")) continue;
            try {
                String decoded = new String(Base64.getDecoder().decode(p.get("value").getAsString()), StandardCharsets.UTF_8);
                JsonObject t = parseObject(decoded);
                if (t == null || !t.has("textures")) return null;
                JsonObject textures = t.getAsJsonObject("textures");
                if (!textures.has("SKIN")) return null;
                JsonObject skin = textures.getAsJsonObject("SKIN");
                if (!skin.has("url")) return null;
                String model = null;
                if (skin.has("metadata") && skin.get("metadata").isJsonObject()) {
                    JsonObject md = skin.getAsJsonObject("metadata");
                    if (md.has("model")) model = md.get("model").getAsString();
                }
                return new String[]{https(skin.get("url").getAsString()), "slim".equalsIgnoreCase(model) ? "slim" : "classic"};
            } catch (Exception ex) {
                return null;
            }
        }
        return null;
    }

    private byte[] fetchBytes(String url) {
        URI uri;
        try { uri = URI.create(url == null ? "" : url.trim()); } catch (IllegalArgumentException e) {
            throw new SkinException("That doesn't look like a valid link.");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")) || uri.getHost() == null) {
            throw new SkinException("Paste a direct link starting with http:// or https://.");
        }
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build();
        try {
            HttpResponse<InputStream> r = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (r.statusCode() / 100 != 2) {
                r.body().close();
                throw new SkinException("The link returned HTTP " + r.statusCode() + ".");
            }
            try (InputStream in = r.body()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > SkinImage.MAX_BYTES) throw new SkinException("That file is too large to be a skin.");
                }
                return out.toByteArray();
            }
        } catch (SkinException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SkinException("Cancelled.");
        } catch (IOException e) {
            throw new SkinException("Couldn't download that link. Check your internet connection.", false, e);
        }
    }

    private static byte[] multipart(String boundary, String variant, byte[] png) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            String crlf = "\r\n";
            out.write(("--" + boundary + crlf
                    + "Content-Disposition: form-data; name=\"variant\"" + crlf + crlf
                    + variant + crlf).getBytes(StandardCharsets.UTF_8));
            out.write(("--" + boundary + crlf
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"skin.png\"" + crlf
                    + "Content-Type: image/png" + crlf + crlf).getBytes(StandardCharsets.UTF_8));
            out.write(png);
            out.write((crlf + "--" + boundary + "--" + crlf).getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private HttpRequest get(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json").GET().build();
    }

    private HttpResponse<String> send(HttpRequest req, String who) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SkinException("Cancelled.");
        } catch (IOException e) {
            throw new SkinException("Couldn't reach " + who + ". Check your internet connection.", false, e);
        }
    }

    private static JsonObject parseObject(String json) {
        try {
            if (json == null || json.isBlank()) return null;
            JsonElement e = JsonParser.parseString(json);
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Mojang's texture links are plain http in the profile data; the host serves https too. */
    private static String https(String url) {
        String prefix = "http://textures.minecraft.net";
        return url != null && url.startsWith(prefix) ? "https://" + url.substring("http://".length()) : url;
    }

    private static String trim(String s) {
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
