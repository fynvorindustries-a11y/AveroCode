package com.averolauncher.store;

import com.averolauncher.logging.AppLogger;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Client for the Avero account + cape store backend (see STORE_GUIDE.md and store-backend/).
 *
 * <p><b>Avero account</b> is a real email/username + password account. The backend is the source of
 * truth for authentication, profiles, sessions, entitlements and cosmetics. This client:
 * <ul>
 *   <li>keeps the short-lived <b>access token</b> in memory and the long-lived <b>refresh token</b>
 *       in the launcher's encrypted credential store (via {@link AveroId});</li>
 *   <li>transparently <b>refreshes</b> an expired/near-expiry access token and retries once, so an
 *       expired access token never forces a re-login;</li>
 *   <li>never stores or transits the password after login.</li>
 * </ul>
 *
 * <p>The Avero account is its own login, separate from Mojang/Microsoft. Two things connect them:
 * <ul>
 *   <li>names that already exist at Mojang can't be claimed as an Avero username or linked by hand
 *       (the server rejects them with code {@code MOJANG_NAME});</li>
 *   <li>signing in with Microsoft lets the launcher hand the server a Minecraft token <i>once</i>
 *       ({@link #linkMicrosoft}) so the server can verify you really own that name and link it
 *       automatically. The server forwards it to Mojang and never stores it.</li>
 * </ul>
 *
 * <p>If no backend URL is configured there is NO account system: register/login fail with a clear
 * message (the store catalog still shows demo capes, but nothing can be signed in or owned). Configure with the environment variable {@code AVERO_STORE_API}
 * or the JVM property {@code -Davero.store.api=https://host/api/v1}.
 */
public class StoreApi {

    private static final String TAG = "StoreApi";

    /**
     * Your live store server, baked into release builds so players don't configure anything,
     * e.g. {@code "https://store.yourdomain.com/api/v1"}. Leave empty while developing: the
     * launcher then runs in demo mode (or uses AVERO_STORE_API / -Davero.store.api if set).
     */
    private static final String PRODUCTION_URL = "https://rzgojdzvscarhytixszk.supabase.co/functions/v1/avero/api/v1";

    /** Refresh the access token this many ms before it actually expires. */
    private static final long REFRESH_MARGIN_MS = 60_000;

    // ------------------------------------------------------------------ models

    public static class CapeItem {
        public String id;
        public String name;
        public String description;
        public int priceCents;
        public String currency = "USD";
        public String previewUrl;   // optional; a placeholder tile is drawn when absent
        public boolean limited;

        public String priceLabel() {
            if (priceCents <= 0) return "Free";
            return String.format("%s %.2f", currency, priceCents / 100.0);
        }
    }

    public static class Wardrobe {
        public List<String> owned = new ArrayList<>();
        public String equipped; // cape id or null
    }

    /** A cape reference returned by the cosmetics endpoints. */
    public static class Cape {
        public String id;
        public String name;
        public String textureUrl;
    }

    /** Full cosmetics payload: the equipped cape plus every owned cape. */
    public static class Cosmetics {
        public Cape equippedCape;                       // null when nothing is equipped
        public List<Cape> ownedCapes = new ArrayList<>();
    }

    /** A Minecraft player name linked to the Avero account. {@code verified} = proven via Microsoft sign-in. */
    public static class LinkedProfile {
        public String uuid;
        public String username;
        public boolean verified;
    }

    /** Answer of the live "is this Avero username free?" check. */
    public static class UsernameCheck {
        public boolean available;
        public String code;     // INVALID | USERNAME_TAKEN | MOJANG_NAME | null
        public String reason;   // human readable, null when available
    }

    public static class AveroUser {
        public String id;
        public String username;
        public String email;
        public boolean emailVerified;
        /** Set by an admin; such an account can neither sign in nor refresh. */
        public boolean disabled;
        /** Only set by the responses that trigger an email: false means no email is coming. */
        public boolean mailSent;
        public String createdAt;
        public String lastLoginAt;
        public String displayName;
        public String avatar;
        public String minecraftUsername;
        public List<LinkedProfile> profiles = new ArrayList<>();
    }

    /** One active login session (device) on the account. */
    public static class SessionInfo {
        public String id;
        public String deviceName;
        public String createdAt;
        public String lastUsedAt;
        public String expiresAt;
        public boolean current;
    }

    /** A short-lived, scoped token handed to the Minecraft cosmetics adapter. */
    public static class LaunchSession {
        public String token;
        public String sessionId;
        public String userId;
        public String expiresAt;
        public List<String> scope = new ArrayList<>();
    }

    /**
     * The signed-in Avero session. The access token is short-lived and lives in memory only; the
     * refresh token is long-lived and is what {@link AveroId} persists (encrypted). A session is
     * "valid" as long as it holds a refresh token — an expired access token is refreshed, never
     * turned into a re-login prompt.
     */
    public static class AveroSession {
        public String accessToken;      // memory only
        public String refreshToken;     // persisted (encrypted) by AveroId
        public long accessExpiresAtMs;  // memory only
        public String userId;
        public String sessionId;
        public String email;
        public String username;
        /** Whether the account's email is confirmed, as the server last told us. */
        public boolean emailVerified;
        /** True when the last register/resend actually put an email in the outgoing queue. Memory only. */
        public boolean mailSent;

        public boolean hasRefresh() { return refreshToken != null && !refreshToken.isBlank(); }
        public boolean accessValid() { return accessToken != null && System.currentTimeMillis() < accessExpiresAtMs - REFRESH_MARGIN_MS; }
    }

    public static class StoreException extends RuntimeException {
        public final String code;
        /** HTTP status when the server answered, or 0 for client-side / connection failures. */
        public final int status;
        public StoreException(String msg) { this(msg, "INVALID_REQUEST", null, 0); }
        public StoreException(String msg, String code) { this(msg, code, null, 0); }
        public StoreException(String msg, String code, Throwable cause) { this(msg, code, cause, 0); }
        public StoreException(String msg, String code, Throwable cause, int status) {
            super(msg, cause); this.code = code; this.status = status;
        }

        /**
         * True when the failure says nothing about whether the session is still valid: the server
         * was unreachable, asleep (free hosts return 502/503 while waking), overloaded or rate
         * limiting. Such a failure must NEVER sign the player out — the saved session is retried.
         */
        public boolean isTransient() {
            return "NETWORK".equals(code) || status >= 500 || status == 429 || status == 408;
        }

        /**
         * True only when the Avero server itself answered with a structured error that says the saved
         * session is no good (revoked / expired / account disabled...). A bare 400/401/403/404 with no
         * Avero error code is what a gateway, a redeploying function or a captive portal returns; that
         * says nothing about the session and must never sign the player out.
         */
        public boolean isSessionRejected() {
            if (isTransient() || status < 400 || status >= 500) return false;
            return code != null && !"INVALID_REQUEST".equals(code) && !"NO_SERVER".equals(code) && !"TLS".equals(code);
        }
    }

    /** Lets {@link AveroId} supply the current session and persist rotated tokens. */
    public interface SessionStore {
        AveroSession current();
        void persist(AveroSession s);
        void clear();
    }

    // ------------------------------------------------------------------- state

    private static final Gson GSON = new Gson();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    private final String baseUrl;
    private volatile SessionStore sessionStore;

    // Demo-mode state (in memory only, never persisted, never pretends to be real).
    private final Set<String> demoOwned = new LinkedHashSet<>();
    private String demoEquipped;
    private final List<LinkedProfile> demoProfiles = new ArrayList<>();

    public StoreApi() {
        String url = System.getProperty("avero.store.api");
        if (url == null || url.isBlank()) url = System.getenv("AVERO_STORE_API");
        if (url == null || url.isBlank()) url = PRODUCTION_URL; // what real players use
        if (url != null) url = url.trim();
        while (url != null && url.endsWith("/")) url = url.substring(0, url.length() - 1);
        this.baseUrl = (url == null || url.isBlank()) ? null : url;
    }

    public void setSessionStore(SessionStore store) { this.sessionStore = store; }
    public boolean isDemo() { return baseUrl == null; }

    /** Server URL without trailing slash, or null in demo mode. */
    public String baseUrl() { return baseUrl; }

    /**
     * Base URL of the Avero skins service (shared skins of offline names), or null in demo mode. Defaults to the
     * {@code avero-skins} function next to the main {@code avero} function; override with
     * {@code -Davero.skins.api=} / {@code AVERO_SKINS_API}.
     */
    public String skinsUrl() {
        String url = System.getProperty("avero.skins.api");
        if (url == null || url.isBlank()) url = System.getenv("AVERO_SKINS_API");
        if (url == null || url.isBlank()) {
            if (baseUrl == null) return null;
            url = baseUrl.endsWith("/avero/api/v1")
                    ? baseUrl.substring(0, baseUrl.length() - "/avero/api/v1".length()) + "/avero-skins"
                    : null;
        }
        if (url == null) return null;
        url = url.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url.isEmpty() ? null : url;
    }

    // ---------------------------------------------------------------- catalog

    public CompletableFuture<List<CapeItem>> catalog() {
        if (isDemo()) return CompletableFuture.completedFuture(demoCatalog());
        return CompletableFuture.supplyAsync(() -> {
            JsonObject o = call("GET", "/capes", null, false);
            List<CapeItem> list = GSON.fromJson(o.get("capes"), new TypeToken<List<CapeItem>>() {}.getType());
            return list != null ? list : new ArrayList<CapeItem>();
        });
    }

    // -------------------------------------------------------- server directory

    /**
     * The centrally-maintained list of public servers shown on the Servers page
     * (see ADMIN.md -> "Server directory"). Falls back to a small curated list in
     * demo mode, same as {@link #catalog()}.
     */
    public CompletableFuture<List<com.averolauncher.model.DirectoryServerEntry>> fetchServerDirectory() {
        if (isDemo()) return CompletableFuture.completedFuture(demoDirectory());
        return CompletableFuture.supplyAsync(() -> {
            JsonObject o = call("GET", "/servers/directory", null, false);
            List<com.averolauncher.model.DirectoryServerEntry> list = GSON.fromJson(o.get("servers"),
                    new TypeToken<List<com.averolauncher.model.DirectoryServerEntry>>() {}.getType());
            return list != null ? list : new ArrayList<com.averolauncher.model.DirectoryServerEntry>();
        });
    }

    private List<com.averolauncher.model.DirectoryServerEntry> demoDirectory() {
        List<com.averolauncher.model.DirectoryServerEntry> list = new ArrayList<>();
        list.add(dirServer("hypixel", "Hypixel", "mc.hypixel.net",
                "The biggest Minecraft network — Bedwars, SkyWars, Murder Mystery and dozens more minigames.", "Minigames", true));
        list.add(dirServer("cubecraft", "CubeCraft Games", "play.cubecraft.net",
                "Polished minigames network known for Eggwars, SkyWars and Lucky Islands.", "Minigames", true));
        list.add(dirServer("hive", "The Hive", "play.hivemc.com",
                "One of the original minigame networks — DeathRun, Survival Games, SkyWars and more.", "Minigames", true));
        list.add(dirServer("wynncraft", "Wynncraft", "play.wynncraft.com",
                "A sprawling custom MMORPG built entirely on vanilla Minecraft, no mods required.", "MMORPG", true));
        list.add(dirServer("complex", "Complex Gaming", "hub.mc-complex.com",
                "Pixelmon, survival and PvP servers with a large, active modded community.", "Modded", true));
        return list;
    }

    private com.averolauncher.model.DirectoryServerEntry dirServer(String id, String name, String address, String desc, String category, boolean requiresPremium) {
        com.averolauncher.model.DirectoryServerEntry d = new com.averolauncher.model.DirectoryServerEntry();
        d.id = id; d.name = name; d.address = address; d.description = desc; d.category = category; d.requiresPremium = requiresPremium;
        return d;
    }

    // ------------------------------------------------------------------- auth

    /** Creates an account and signs straight in. */
    public CompletableFuture<AveroSession> register(String username, String email, String password, String deviceName) {
        String bad = validateRegistration(username, email, password);
        if (bad != null) return CompletableFuture.failedFuture(new StoreException(bad));
        if (isDemo()) return CompletableFuture.failedFuture(new StoreException("The Avero server isn't set up, so accounts can't be created or signed in to. "
                + "Run the store backend and set AVERO_STORE_API (or -Davero.store.api=https://host/api/v1), or set PRODUCTION_URL in StoreApi.", "NO_SERVER"));
        return CompletableFuture.supplyAsync(() -> {
            JsonObject b = new JsonObject();
            b.addProperty("username", username.trim());
            b.addProperty("email", email.trim());
            b.addProperty("password", password);
            b.addProperty("deviceName", deviceName);
            return adopt(call("POST", "/auth/register", b, false), email.trim().toLowerCase(), username.trim());
        });
    }

    /** Mirrors the server's rules so mistakes are caught instantly with a clear message. */
    private static String validateRegistration(String username, String email, String password) {
        String u = username == null ? "" : username.trim();
        String e = email == null ? "" : email.trim();
        if (!u.matches("[A-Za-z0-9_]{3,20}")) return "Username must be 3-20 letters, numbers or underscores.";
        if (!e.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) return "Enter a valid email address.";
        if (password == null || password.length() < 8) return "Password must be at least 8 characters.";
        return null;
    }

    public CompletableFuture<AveroSession> login(String emailOrUsername, String password, String deviceName) {
        if (emailOrUsername == null || emailOrUsername.isBlank() || password == null || password.isEmpty()) {
            return CompletableFuture.failedFuture(new StoreException("Enter your email/username and password."));
        }
        if (isDemo()) return CompletableFuture.failedFuture(new StoreException("The Avero server isn't set up, so accounts can't be created or signed in to. "
                + "Run the store backend and set AVERO_STORE_API (or -Davero.store.api=https://host/api/v1), or set PRODUCTION_URL in StoreApi.", "NO_SERVER"));
        return CompletableFuture.supplyAsync(() -> {
            JsonObject b = new JsonObject();
            String ident = emailOrUsername.trim();
            if (ident.contains("@")) b.addProperty("email", ident); else b.addProperty("username", ident);
            b.addProperty("password", password);
            b.addProperty("deviceName", deviceName);
            return adopt(call("POST", "/auth/login", b, false), ident, ident);
        });
    }

    /**
     * Exchanges the refresh token for a fresh access + refresh token pair (rotation). Updates the
     * session store. Throws {@link StoreException} if the refresh token is invalid/revoked/expired.
     */
    public synchronized AveroSession refreshBlocking() {
        AveroSession cur = sessionStore != null ? sessionStore.current() : null;
        if (isDemo()) {
            if (cur != null) { cur.accessExpiresAtMs = System.currentTimeMillis() + 30L * 86_400_000L; }
            return cur;
        }
        if (cur == null || !cur.hasRefresh()) throw new StoreException("Not signed in.", "TOKEN_REVOKED");
        JsonObject b = new JsonObject();
        b.addProperty("refreshToken", cur.refreshToken);
        JsonObject o = call("POST", "/auth/refresh", b, false);
        if (!o.has("accessToken") || !o.has("refreshToken") || o.get("accessToken").isJsonNull() || o.get("refreshToken").isJsonNull()) {
            // A 2xx without tokens is a server hiccup, not a verdict on the session — don't sign out over it.
            throw new StoreException("The Avero server sent an unexpected refresh response.", "NETWORK");
        }
        cur.accessToken = o.get("accessToken").getAsString();
        cur.refreshToken = o.get("refreshToken").getAsString();
        long ttl = o.has("accessExpiresIn") ? o.get("accessExpiresIn").getAsLong() : 900L;
        cur.accessExpiresAtMs = System.currentTimeMillis() + ttl * 1000;
        if (o.has("sessionId")) cur.sessionId = o.get("sessionId").getAsString();
        if (o.has("userId") && !o.get("userId").isJsonNull()) cur.userId = o.get("userId").getAsString();
        if (o.has("username") && !o.get("username").isJsonNull()) cur.username = o.get("username").getAsString();
        if (o.has("email") && !o.get("email").isJsonNull()) cur.email = o.get("email").getAsString();
        if (o.has("emailVerified")) cur.emailVerified = o.get("emailVerified").getAsBoolean();
        if (sessionStore != null) sessionStore.persist(cur);
        return cur;
    }

    public CompletableFuture<AveroSession> refresh() {
        return CompletableFuture.supplyAsync(this::refreshBlocking);
    }

    /** Revokes the current server-side session and clears local tokens. */
    public CompletableFuture<Void> logout() {
        if (isDemo()) { if (sessionStore != null) sessionStore.clear(); return CompletableFuture.completedFuture(null); }
        return CompletableFuture.runAsync(() -> {
            AveroSession cur = sessionStore != null ? sessionStore.current() : null;
            try {
                if (cur != null && cur.hasRefresh()) {
                    JsonObject b = new JsonObject();
                    b.addProperty("refreshToken", cur.refreshToken);
                    call("POST", "/auth/logout", b, false);
                }
            } catch (Exception e) {
                AppLogger.get().warn(TAG, "Logout call failed (clearing locally anyway): " + e.getMessage());
            } finally {
                if (sessionStore != null) sessionStore.clear();
            }
        });
    }

    /**
     * Completes a password reset. {@code codeOrToken} is either the short code from the email body
     * ("K7QF-2MBX", typed into the launcher) or the 64-character token from the link someone opened
     * in a browser; the server tells the two apart. Each one works once.
     */
    public CompletableFuture<Void> resetPassword(String codeOrToken, String newPassword) {
        if (isDemo()) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> {
            JsonObject b = new JsonObject();
            b.addProperty("code", codeOrToken.trim());
            b.addProperty("password", newPassword);
            call("POST", "/auth/reset-password", b, false);
        });
    }

    /** Verifies an email address with the code from the email (or the token from its link). */
    public CompletableFuture<Void> verifyEmail(String codeOrToken) {
        if (isDemo()) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> {
            JsonObject b = new JsonObject();
            b.addProperty("code", codeOrToken.trim());
            call("POST", "/auth/verify-email", b, false);
        });
    }

    /**
     * Sends a fresh verification email. The returned {@code mailSent} flag matters: when no mail
     * provider is configured on the server nothing was sent, and the UI must not tell the player to
     * go and check their inbox.
     */
    public CompletableFuture<Boolean> resendVerification(String email) {
        if (isDemo()) return CompletableFuture.completedFuture(false);
        return CompletableFuture.supplyAsync(() -> {
            JsonObject b = new JsonObject();
            b.addProperty("email", email.trim());
            JsonObject o = call("POST", "/auth/resend-verification", b, false);
            return o.has("mailSent") && o.get("mailSent").getAsBoolean();
        });
    }

    /** Requests a password-reset email. Always succeeds for a valid email shape (no enumeration). */
    public CompletableFuture<Boolean> forgotPassword(String email) {
        if (isDemo()) return CompletableFuture.completedFuture(false);
        return CompletableFuture.supplyAsync(() -> {
            JsonObject b = new JsonObject();
            b.addProperty("email", email.trim());
            JsonObject o = call("POST", "/auth/forgot-password", b, false);
            return o.has("mailSent") && o.get("mailSent").getAsBoolean();
        });
    }

    /**
     * Authenticated JSON call for feature modules that own their own endpoints (e.g. the rank system).
     * Reuses the normal token refresh and error mapping. Fails with a {@link StoreException} in demo mode.
     */
    public CompletableFuture<JsonObject> authedJson(String method, String path, JsonObject body) {
        if (isDemo()) return CompletableFuture.failedFuture(new StoreException("The Avero server isn't set up.", "NO_SERVER"));
        return CompletableFuture.supplyAsync(() -> call(method, path, body, true));
    }

    // ------------------------------------------------------------- leaderboard

    /** One player on the leaderboard: an Avero ID ranked by total AP. */
    public static class LeaderboardEntry {
        public int rank;
        public String userId;
        public String username;
        /** Verified linked Minecraft name, used only to draw the head icon; may be null. */
        public String minecraftUsername;
        public long totalAp;
    }

    public static class Leaderboard {
        public List<LeaderboardEntry> entries = new ArrayList<>();
        /** The signed-in player's own place (even when outside the top list); null when signed out. */
        public LeaderboardEntry me;
        public String updatedAt;
    }

    /**
     * Live top players by total AP, straight from the server (GET /leaderboard?limit=N). Public data
     * (Avero username + AP only); when signed in the answer also carries the caller's own place.
     * There is no demo data: without a server this fails with NO_SERVER.
     */
    public CompletableFuture<Leaderboard> leaderboard(int limit) {
        if (isDemo()) return CompletableFuture.failedFuture(new StoreException("The Avero server isn't set up.", "NO_SERVER"));
        int n = Math.max(1, Math.min(limit, 100));
        return CompletableFuture.supplyAsync(() -> {
            AveroSession s = sessionStore != null ? sessionStore.current() : null;
            JsonObject o = call("GET", "/leaderboard?limit=" + n, null, s != null && s.hasRefresh());
            Leaderboard lb = GSON.fromJson(o, Leaderboard.class);
            if (lb == null) lb = new Leaderboard();
            if (lb.entries == null) lb.entries = new ArrayList<>();
            return lb;
        });
    }

    // -------------------------------------------------------------------- me

    public CompletableFuture<AveroUser> me() {
        if (isDemo()) {
            AveroSession s = sessionStore != null ? sessionStore.current() : null;
            AveroUser u = new AveroUser();
            u.id = "demo"; u.email = s != null ? s.email : ""; u.username = s != null ? s.username : "";
            u.displayName = u.username; u.emailVerified = true; u.profiles = new ArrayList<>(demoProfiles);
            return CompletableFuture.completedFuture(u);
        }
        return CompletableFuture.supplyAsync(() -> parseUser(call("GET", "/me", null, true)));
    }

    /** Updates display name / avatar / minecraft username. Pass null for fields you don't change. */
    public CompletableFuture<AveroUser> patchMe(String displayName, String minecraftUsername) {
        if (isDemo()) return me();
        return CompletableFuture.supplyAsync(() -> {
            JsonObject b = new JsonObject();
            if (displayName != null) b.addProperty("displayName", displayName);
            if (minecraftUsername != null) b.addProperty("minecraftUsername", minecraftUsername);
            return parseUser(call("PATCH", "/me", b, true));
        });
    }

    /** Changes the account email (password required). The new address must be verified again. */
    public CompletableFuture<AveroUser> changeEmail(String password, String newEmail) {
        if (newEmail == null || !newEmail.trim().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            return CompletableFuture.failedFuture(new StoreException("Enter a valid email address."));
        }
        if (isDemo()) return me();
        return CompletableFuture.supplyAsync(() -> {
            JsonObject b = new JsonObject();
            b.addProperty("password", password);
            b.addProperty("email", newEmail.trim());
            JsonObject o = call("POST", "/me/change-email", b, true);
            AveroSession cur = sessionStore != null ? sessionStore.current() : null;
            if (cur != null && o.has("email") && !o.get("email").isJsonNull()) {
                cur.email = o.get("email").getAsString();
                sessionStore.persist(cur);
            }
            return parseUser(o);
        });
    }

    public CompletableFuture<Void> changePassword(String currentPassword, String newPassword) {
        if (isDemo()) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> {
            JsonObject b = new JsonObject();
            b.addProperty("currentPassword", currentPassword);
            b.addProperty("password", newPassword);
            // Hit the dedicated endpoint (also falls back gracefully to the PATCH /me handler)
            call("POST", "/me/change-password", b, true);
        });
    }

    /**
     * Permanently deletes the Avero account. The password must be confirmed by the caller before
     * passing it here. On success the caller must clear the local session.
     */
    public CompletableFuture<Void> deleteAccount(String password) {
        if (isDemo()) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> {
            JsonObject b = new JsonObject();
            b.addProperty("password", password);
            // POST rather than DELETE-with-body: some proxies silently drop a DELETE body, and the
            // password confirmation in it is not optional.
            call("POST", "/me/delete", b, true);
        });
    }

    // --------------------------------------------------------------- sessions

    public CompletableFuture<List<SessionInfo>> sessions() {
        if (isDemo()) {
            SessionInfo s = new SessionInfo();
            s.id = "demo"; s.deviceName = "This device"; s.current = true;
            return CompletableFuture.completedFuture(new ArrayList<>(List.of(s)));
        }
        return CompletableFuture.supplyAsync(() -> {
            JsonObject o = call("GET", "/sessions", null, true);
            return GSON.fromJson(o.get("sessions"), new TypeToken<List<SessionInfo>>() {}.getType());
        });
    }

    public CompletableFuture<List<SessionInfo>> revokeSession(String id) {
        if (isDemo()) return sessions();
        return CompletableFuture.supplyAsync(() -> {
            JsonObject o = call("DELETE", "/sessions/" + id, null, true);
            return GSON.fromJson(o.get("sessions"), new TypeToken<List<SessionInfo>>() {}.getType());
        });
    }

    public CompletableFuture<List<SessionInfo>> logoutAllOtherDevices() {
        if (isDemo()) return sessions();
        return CompletableFuture.supplyAsync(() -> {
            JsonObject o = call("POST", "/sessions/logout-all", null, true);
            return GSON.fromJson(o.get("sessions"), new TypeToken<List<SessionInfo>>() {}.getType());
        });
    }

    // ----------------------------------------------------------- profiles/link

    /**
     * Links a free-form player name (first come first served). Names that exist at Mojang are rejected by
     * the server with code {@code MOJANG_NAME}: those can only be linked with {@link #linkMicrosoft}.
     */
    public CompletableFuture<List<LinkedProfile>> linkOffline(String username) {
        if (isDemo()) return CompletableFuture.completedFuture(demoLink(username, username));
        return CompletableFuture.supplyAsync(() -> {
            JsonObject b = new JsonObject();
            b.addProperty("username", username);
            return parseProfiles(call("POST", "/me/profiles/offline", b, true).get("profiles"));
        });
    }

    /**
     * Links the Minecraft profile that owns {@code minecraftAccessToken} and marks it verified. The server
     * checks the token against Mojang, uses it once and discards it.
     */
    public CompletableFuture<List<LinkedProfile>> linkMicrosoft(String minecraftAccessToken) {
        if (isDemo()) return CompletableFuture.completedFuture(new ArrayList<>(demoProfiles));
        return CompletableFuture.supplyAsync(() -> {
            JsonObject b = new JsonObject();
            b.addProperty("minecraftAccessToken", minecraftAccessToken);
            return parseProfiles(call("POST", "/me/profiles/microsoft", b, true).get("profiles"));
        });
    }

    /** Live availability check for the registration form (no sign-in needed). Never fails: errors read as "available". */
    public CompletableFuture<UsernameCheck> checkUsername(String name) {
        UsernameCheck ok = new UsernameCheck(); ok.available = true;
        if (isDemo() || name == null || name.isBlank()) return CompletableFuture.completedFuture(ok);
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonObject o = call("GET", "/auth/username-check?name=" + java.net.URLEncoder.encode(name.trim(), java.nio.charset.StandardCharsets.UTF_8), null, false);
                UsernameCheck c = new UsernameCheck();
                c.available = o.has("available") && o.get("available").getAsBoolean();
                if (o.has("code") && !o.get("code").isJsonNull()) c.code = o.get("code").getAsString();
                if (o.has("reason") && !o.get("reason").isJsonNull()) c.reason = o.get("reason").getAsString();
                return c;
            } catch (Exception e) { return ok; }
        });
    }

    public CompletableFuture<List<LinkedProfile>> unlink(String uuid) {
        if (isDemo()) {
            demoProfiles.removeIf(p -> p.uuid.equals(undash(uuid)));
            return CompletableFuture.completedFuture(new ArrayList<>(demoProfiles));
        }
        return CompletableFuture.supplyAsync(() ->
                parseProfiles(call("DELETE", "/me/profiles/" + undash(uuid), null, true).get("profiles")));
    }

    // --------------------------------------------------------------- wardrobe

    public CompletableFuture<Wardrobe> wardrobe() {
        if (isDemo()) return CompletableFuture.completedFuture(demoWardrobe());
        return CompletableFuture.supplyAsync(() -> parseWardrobe(call("GET", "/me/capes", null, true)));
    }

    /** Full cosmetics payload (equipped cape + owned capes with names/textures). */
    public CompletableFuture<Cosmetics> cosmetics() {
        if (isDemo()) {
            Cosmetics c = new Cosmetics();
            for (String id : demoOwned) { Cape cp = new Cape(); cp.id = id; cp.name = demoName(id); c.ownedCapes.add(cp); }
            if (demoEquipped != null) { Cape cp = new Cape(); cp.id = demoEquipped; cp.name = demoName(demoEquipped); c.equippedCape = cp; }
            return CompletableFuture.completedFuture(c);
        }
        return CompletableFuture.supplyAsync(() -> {
            Cosmetics c = GSON.fromJson(call("GET", "/cosmetics", null, true), Cosmetics.class);
            if (c == null) c = new Cosmetics();
            if (c.ownedCapes == null) c.ownedCapes = new ArrayList<>();
            if (c.equippedCape != null && (c.equippedCape.name == null || c.equippedCape.name.isBlank())) c.equippedCape.name = prettyName(c.equippedCape.id);
            for (Cape cp : c.ownedCapes) if (cp.name == null || cp.name.isBlank()) cp.name = prettyName(cp.id);
            return c;
        });
    }

    /** Pass {@code null} to take the cape off. */
    public CompletableFuture<Wardrobe> equip(String capeId) {
        if (isDemo()) {
            demoEquipped = capeId;
            return CompletableFuture.completedFuture(demoWardrobe());
        }
        return CompletableFuture.supplyAsync(() -> {
            JsonObject body = new JsonObject();
            JsonObject o;
            if (capeId == null) {
                o = call("POST", "/capes/unequip", body, true);
            } else {
                body.addProperty("capeId", capeId);
                o = call("POST", "/capes/equip", body, true);
            }
            return parseWardrobe(o);
        });
    }

    public CompletableFuture<List<String>> entitlements() {
        if (isDemo()) return CompletableFuture.completedFuture(new ArrayList<>());
        return CompletableFuture.supplyAsync(() -> {
            JsonObject o = call("GET", "/entitlements", null, true);
            return GSON.fromJson(o.get("entitlements"), new TypeToken<List<String>>() {}.getType());
        });
    }

    /** Returns a hosted-checkout URL to open in the browser. */
    public CompletableFuture<String> checkoutUrl(String capeId) {
        if (isDemo()) {
            demoOwned.add(capeId); // demo only: "buying" just unlocks it locally
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.supplyAsync(() -> {
            JsonObject body = new JsonObject();
            body.addProperty("capeId", capeId);
            return call("POST", "/me/checkout", body, true).get("url").getAsString();
        });
    }

    /** Redeems a gift/promo code into the signed-in account's wardrobe. */
    public CompletableFuture<Wardrobe> redeem(String code) {
        if (isDemo()) return CompletableFuture.failedFuture(new StoreException("Codes need a store server (demo mode)."));
        return CompletableFuture.supplyAsync(() -> {
            JsonObject body = new JsonObject();
            body.addProperty("code", code.trim());
            return parseWardrobe(call("POST", "/me/redeem", body, true));
        });
    }

    // -------------------------------------------------------- launch session

    /**
     * Requests a short-lived, scoped launch token for the in-game Avero cape agent. The token grants
     * {@code cosmetics:read} only — never a refresh token, never account or admin access.
     *
     * @param ttlSeconds how long the token should stay valid; the server clamps it to 1 minute..7 days.
     *                   Ask for long enough to cover the whole gaming session, because the agent only
     *                   fetches cosmetics when it starts.
     */
    public CompletableFuture<LaunchSession> launcherSession(long ttlSeconds) {
        if (isDemo()) return CompletableFuture.failedFuture(new StoreException("Launch sessions need a store server (demo mode)."));
        return CompletableFuture.supplyAsync(() -> {
            JsonObject body = new JsonObject();
            JsonArray scope = new JsonArray();
            scope.add("cosmetics:read");
            body.add("scope", scope);
            if (ttlSeconds > 0) body.addProperty("ttlSeconds", ttlSeconds);
            LaunchSession s = GSON.fromJson(call("POST", "/launcher/session", body, true), LaunchSession.class);
            if (s == null || s.token == null) throw new StoreException("The Avero server did not issue a launch session.", "NETWORK");
            return s;
        });
    }

    public CompletableFuture<LaunchSession> launcherSession() {
        return launcherSession(0);
    }

    // -------------------------------------------------------- shared skins (offline names)

    /**
     * Publishes the skin worn by one of the signed-in Avero ID's linked offline names, so every player running
     * the Avero cape agent sees it. The server must check that {@code uuid} is linked to the caller.
     * See AVERO_SKIN_SHARING.md for the server and agent side of this contract.
     *
     * @param variant "classic" or "slim"
     */
    public CompletableFuture<Void> publishSkin(String uuid, byte[] png, String variant) {
        if (skinsUrl() == null) return CompletableFuture.failedFuture(new StoreException("Sharing a skin needs the Avero server (demo mode).", "NETWORK"));
        return CompletableFuture.supplyAsync(() -> {
            JsonObject b = new JsonObject();
            b.addProperty("variant", "slim".equals(variant) ? "slim" : "classic");
            b.addProperty("png", java.util.Base64.getEncoder().encodeToString(png));
            call(skinsUrl(), "PUT", "/me/profiles/" + undash(uuid) + "/skin", b, true);
            return null;
        });
    }

    /** Takes the shared skin of a linked name away again (everyone sees the default skin once their cache expires). */
    public CompletableFuture<Void> removeSkin(String uuid) {
        if (skinsUrl() == null) return CompletableFuture.completedFuture(null);
        return CompletableFuture.supplyAsync(() -> {
            call(skinsUrl(), "DELETE", "/me/profiles/" + undash(uuid) + "/skin", null, true);
            return null;
        });
    }

    // ---------------------------------------------------------------- internals

    /** Builds/updates the in-memory session from an auth response and persists it. */
    private AveroSession adopt(JsonObject o, String email, String username) {
        AveroSession s = new AveroSession();
        s.accessToken = o.get("accessToken").getAsString();
        s.refreshToken = o.get("refreshToken").getAsString();
        long ttl = o.has("accessExpiresIn") ? o.get("accessExpiresIn").getAsLong() : 900L;
        s.accessExpiresAtMs = System.currentTimeMillis() + ttl * 1000;
        if (o.has("userId")) s.userId = o.get("userId").getAsString();
        if (o.has("sessionId")) s.sessionId = o.get("sessionId").getAsString();
        s.email = email;
        s.username = username;
        // Prefer the server's canonical identity so the username and email are never the same typed value.
        if (o.has("username") && !o.get("username").isJsonNull()) s.username = o.get("username").getAsString();
        if (o.has("email") && !o.get("email").isJsonNull()) s.email = o.get("email").getAsString();
        s.emailVerified = o.has("emailVerified") && o.get("emailVerified").getAsBoolean();
        s.mailSent = o.has("mailSent") && o.get("mailSent").getAsBoolean();
        if (sessionStore != null) sessionStore.persist(s);
        if (s.username == null || s.username.contains("@")) completeIdentity(s); // older servers: ask /me
        return s;
    }

    /** Fills in the real username/email from /me (used when the auth response didn't include them). */
    private void completeIdentity(AveroSession s) {
        try {
            AveroUser u = parseUser(call("GET", "/me", null, true));
            if (u.username != null && !u.username.isBlank()) s.username = u.username;
            if (u.email != null && !u.email.isBlank()) s.email = u.email;
            if (sessionStore != null) sessionStore.persist(s);
        } catch (Exception e) {
            AppLogger.get().warn(TAG, "Could not load account identity: " + e.getMessage());
            if (s.username != null && s.username.contains("@")) s.username = s.username.substring(0, s.username.indexOf('@'));
        }
    }

    private AveroUser parseUser(JsonObject o) {
        AveroUser u = GSON.fromJson(o, AveroUser.class);
        if (u == null) u = new AveroUser();
        if (u.profiles == null) u.profiles = new ArrayList<>();
        return u;
    }

    private List<LinkedProfile> parseProfiles(JsonElement e) {
        List<LinkedProfile> list = new ArrayList<>();
        if (e != null && e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) {
                LinkedProfile p = GSON.fromJson(x, LinkedProfile.class);
                if (p != null) list.add(p);
            }
        }
        return list;
    }

    private List<LinkedProfile> demoLink(String uuid, String username) {
        demoProfiles.removeIf(p -> p.uuid.equals(uuid));
        LinkedProfile p = new LinkedProfile();
        p.uuid = uuid; p.username = username;
        demoProfiles.add(p);
        return new ArrayList<>(demoProfiles);
    }

    private Wardrobe parseWardrobe(JsonObject o) {
        Wardrobe w = new Wardrobe();
        if (o.has("owned") && o.get("owned").isJsonArray()) {
            o.getAsJsonArray("owned").forEach(e -> w.owned.add(e.getAsString()));
        }
        if (o.has("equipped") && !o.get("equipped").isJsonNull()) w.equipped = o.get("equipped").getAsString();
        return w;
    }

    /** Ensures the in-memory access token is fresh, refreshing (rotating) if needed. */
    private synchronized AveroSession ensureFreshAccess() {
        AveroSession s = sessionStore != null ? sessionStore.current() : null;
        if (s == null || !s.hasRefresh()) throw new StoreException("Sign in to your Avero account first.", "TOKEN_REVOKED");
        if (!s.accessValid()) return refreshBlocking();
        return s;
    }

    /**
     * Performs an API call. When {@code authed} is true, attaches the access token, refreshing it
     * first if it is near expiry, and on a 401 refreshes once and retries. Never logs tokens.
     */
    private JsonObject call(String method, String path, JsonObject body, boolean authed) {
        return call(baseUrl, method, path, body, authed);
    }

    /** Same, against another service of the Avero backend (e.g. the skins function) that accepts the same sign-in token. */
    private JsonObject call(String base, String method, String path, JsonObject body, boolean authed) {
        String token = null;
        if (authed) token = ensureFreshAccess().accessToken;
        try {
            HttpResponse<String> r = raw(base, method, path, body, token);
            if (authed && r.statusCode() == 401) {
                // Access token may have just expired server-side: rotate once and retry.
                try {
                    token = refreshBlocking().accessToken;
                    r = raw(base, method, path, body, token);
                } catch (StoreException refreshFailed) {
                    if (refreshFailed.isTransient()) {
                        // Server asleep / offline: keep the saved sign-in and let the caller retry later.
                        throw new StoreException("Couldn't reach the Avero server to refresh your session. "
                                + "You're still signed in \u2014 try again in a moment.", "NETWORK", refreshFailed, refreshFailed.status);
                    }
                    if (!refreshFailed.isSessionRejected()) {
                        // Unclear answer (gateway / unexpected status): keep the saved sign-in and retry later.
                        throw new StoreException("The Avero server gave an unexpected answer. You're still signed in "
                                + "\u2014 try again in a moment.", "NETWORK", refreshFailed, refreshFailed.status);
                    }
                    if (sessionStore != null) sessionStore.clear();
                    throw new StoreException("Your session expired. Sign in again.", "TOKEN_EXPIRED", refreshFailed);
                }
            }
            return parse(method, path, r);
        } catch (StoreException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            throw new StoreException("The Avero server took too long to answer. Try again in a moment.", "NETWORK", e);
        } catch (java.net.ConnectException | java.nio.channels.UnresolvedAddressException e) {
            throw new StoreException("Couldn't reach the Avero server. Check your internet connection.", "NETWORK", e);
        } catch (Exception e) {
            if (isTlsFailure(e)) {
                // No cause attached on purpose: the UI shows the root cause, which would hide this explanation.
                AppLogger.get().warn(TAG, "TLS handshake failed: " + e + " | " + tlsEnvironment());
                throw new StoreException("Secure connection to the Avero server failed (TLS handshake). "
                        + "This usually means this build's bundled Java runtime lacks the crypto module "
                        + "(jdk.crypto.ec) or a proxy/antivirus is intercepting HTTPS. Reinstall the latest launcher "
                        + "or temporarily disable HTTPS scanning.", "TLS");
            }
            String m = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw new StoreException("Couldn't reach the Avero server: " + m, "NETWORK", e);
        }
    }

    private static boolean isTlsFailure(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof javax.net.ssl.SSLException) return true;
            String m = c.getMessage();
            if (m != null && m.contains("handshake_failure")) return true;
            if (c.getCause() == c) break;
        }
        return false;
    }

    /** One-line description of the JVM's TLS capabilities, for the log when a handshake fails. */
    private static String tlsEnvironment() {
        try {
            javax.net.ssl.SSLParameters p = javax.net.ssl.SSLContext.getDefault().getSupportedSSLParameters();
            long ec = java.util.Arrays.stream(p.getCipherSuites()).filter(c -> c.contains("ECDHE")).count();
            boolean ecProvider = java.security.Security.getProvider("SunEC") != null;
            return "java=" + System.getProperty("java.version") + " protocols=" + java.util.Arrays.toString(p.getProtocols())
                    + " ecdheSuites=" + ec + " SunEC=" + ecProvider
                    + " jdk.tls.client.protocols=" + System.getProperty("jdk.tls.client.protocols")
                    + " https.protocols=" + System.getProperty("https.protocols");
        } catch (Exception ex) {
            return "tls environment unavailable: " + ex;
        }
    }

    private HttpResponse<String> raw(String base, String method, String path, JsonObject body, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(75)) // free hosts sleep when idle; the first request can take ~1 minute to wake them
                .header("Accept", "application/json");
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonObject parse(String method, String path, HttpResponse<String> r) {
        JsonObject json = null;
        try {
            if (r.body() != null && !r.body().isBlank()) json = JsonParser.parseString(r.body()).getAsJsonObject();
        } catch (Exception ignored) { }
        if (r.statusCode() / 100 != 2) {
            String code = "INVALID_REQUEST";
            String msg = "Store server returned HTTP " + r.statusCode();
            if (json != null && json.has("error") && json.get("error").isJsonObject()) {
                JsonObject err = json.getAsJsonObject("error");
                if (err.has("code")) code = err.get("code").getAsString();
                if (err.has("message")) msg = err.get("message").getAsString();
            } else if (json != null && json.has("error") && json.get("error").isJsonPrimitive()) {
                msg = json.get("error").getAsString(); // tolerate the legacy {error:"msg"} shape
            }
            AppLogger.get().warn(TAG, method + " " + path + " -> " + r.statusCode() + " [" + code + "]");
            throw new StoreException(msg, code, null, r.statusCode());
        }
        return json != null ? json : new JsonObject();
    }

    private static String undash(String uuid) {
        return uuid == null ? "" : uuid.replace("-", "").toLowerCase();
    }

    // ------------------------------------------------------------------- demo

    private List<CapeItem> demoCatalog() {
        List<CapeItem> list = new ArrayList<>();
        list.add(cape("shadow", "Shadow", "Dark as the space between torches.", 299, false));
        list.add(cape("crimson", "Crimson", "Deep red with a royal trim.", 299, false));
        list.add(cape("moonveil", "Moonveil", "A pale veil woven from moonlight.", 399, false));
        list.add(cape("storm", "Storm", "Thunderclouds with a lightning edge.", 399, false));
        list.add(cape("frostfire", "Frostfire", "Ice and flame in one cape.", 499, true));
        list.add(cape("nightfall", "Nightfall", "The sky just after sunset.", 299, false));
        list.add(cape("silverwing", "Silverwing", "Polished silver feather pattern.", 399, false));
        list.add(cape("ember", "Ember", "Burning orange gradient, warm to the touch.", 299, false));
        list.add(cape("mystic", "Mystic", "Arcane runes on deep violet.", 399, false));
        list.add(cape("thunder", "Thunder", "Crackling gold on storm grey.", 399, false));
        list.add(cape("darkstar", "Darkstar", "A black sky full of tiny stars.", 499, true));
        list.add(cape("aurora", "Aurora", "Northern lights rippling down.", 499, false));
        list.add(cape("phantom", "Phantom", "Ghostly teal, barely there.", 399, false));
        list.add(cape("sunflare", "Sunflare", "A burst of solar light.", 299, false));
        list.add(cape("raven", "Raven", "Black feathers with a violet sheen.", 299, false));
        list.add(cape("celestial", "Celestial", "Gold and sky blue, heaven-bound.", 499, true));
        list.add(cape("bloodmoon", "Bloodmoon", "A red moon over a black night.", 499, true));
        list.add(cape("wildwind", "Wildwind", "Swirling green gusts.", 299, false));
        list.add(cape("starborn", "Starborn", "Born from a falling star.", 499, false));
        list.add(cape("eternal", "Eternal", "Gold and royal purple, timeless.", 599, true));
        list.add(cape("nebula", "Nebula", "Avero cosmetic cape", 499, false));
        list.add(cape("dragon_rider", "Dragon Rider", "Avero cosmetic cape", 499, false));
        list.add(cape("zenin", "Zenin", "Avero cosmetic cape", 499, false));
        list.add(cape("frozen", "Frozen", "Avero cosmetic cape", 499, false));
        list.add(cape("elder", "Elder", "Avero cosmetic cape", 499, false));
        list.add(cape("emeraldguardian", "Emerald Guardian", "Avero cosmetic cape", 499, false));
        list.add(cape("home", "Home", "Avero cosmetic cape", 499, false));
        list.add(cape("avero", "Avero", "Avero cosmetic cape", 499, false));
        return list;
    }

    /** Display name for a cape id (falls back to a prettified id). */
    private String demoName(String id) {
        for (CapeItem c : demoCatalog()) if (c.id.equals(id)) return c.name;
        return prettyName(id);
    }

    /** "dragon_rider" -> "Dragon Rider". */
    public static String prettyName(String id) {
        if (id == null || id.isBlank()) return "Cape";
        StringBuilder sb = new StringBuilder();
        for (String w : id.split("[_\\-]+")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    private CapeItem cape(String id, String name, String desc, int cents, boolean limited) {
        CapeItem c = new CapeItem();
        c.id = id; c.name = name; c.description = desc; c.priceCents = cents; c.limited = limited;
        return c;
    }

    private Wardrobe demoWardrobe() {
        Wardrobe w = new Wardrobe();
        w.owned = new ArrayList<>(demoOwned);
        w.equipped = demoEquipped;
        return w;
    }
}