package com.averolauncher.auth;

import com.google.gson.Gson;
import com.averolauncher.logging.AppLogger;
import com.averolauncher.model.Account;
import com.averolauncher.model.auth.AuthModels.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Implements the real, documented Microsoft -> Xbox Live -> XSTS -> Minecraft
 * authentication chain used by every legitimate Minecraft: Java Edition
 * launcher (this is the same public flow Mojang documents and the official
 * launcher itself uses). No password fields, no fake tokens: this opens the
 * user's real Microsoft device-code login in their system browser, then
 * exchanges the resulting tokens hop by hop for a genuine Minecraft access
 * token and profile.
 *
 * Requires a real Azure AD application (client ID) registered as a "public
 * client" with no client secret, since desktop apps cannot safely embed a
 * secret. This class never stores or logs a client secret because none
 * should ever exist for this flow.
 */
public class MicrosoftAuthService {

    private static final String TAG = "MicrosoftAuthService";

    // Real, documented Microsoft/Xbox/Minecraft endpoints. Nothing here is invented.
    private static final String DEVICE_CODE_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
    private static final String TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    private static final String XBL_AUTH_URL = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_AUTH_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MC_LOGIN_URL = "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String MC_PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";
    private static final String MC_ENTITLEMENTS_URL = "https://api.minecraftservices.com/entitlements/mcstore";

    private static final String SCOPE = "XboxLive.signin offline_access";

    private final HttpClient http;
    private final Gson gson = new Gson();
    private final Executor backgroundExecutor;
    private final String clientId;

    public MicrosoftAuthService(String clientId, Executor backgroundExecutor) {
        this.clientId = clientId;
        this.backgroundExecutor = backgroundExecutor;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public static class AuthException extends RuntimeException {
        public AuthException(String message) { super(message); }
        public AuthException(String message, Throwable cause) { super(message, cause); }
    }

    /**
     * Starts the device-code flow: requests a code from Microsoft, hands the
     * human-readable instructions (URL + short code) to onCodeReady so the
     * UI can display them, then polls until the user finishes signing in in
     * their browser. Returns a fully-populated MICROSOFT Account on success.
     */
    public CompletableFuture<Account> signIn(Consumer<DeviceCodeResponse> onCodeReady) {
        if (clientId == null || clientId.isBlank()) {
            CompletableFuture<Account> failed = new CompletableFuture<>();
            failed.completeExceptionally(new AuthException(
                    "No Microsoft Azure client ID configured. Add one in Settings > Advanced before signing in."));
            return failed;
        }

        return requestDeviceCode()
                .thenCompose(deviceCode -> {
                    onCodeReady.accept(deviceCode);
                    return pollForToken(deviceCode);
                })
                .thenCompose(this::completeChain);
    }

    /** Uses a stored refresh token to get a fresh Minecraft session without any user interaction. */
    public CompletableFuture<Account> refresh(Account existing) {
        if (existing.msRefreshToken == null) {
            CompletableFuture<Account> failed = new CompletableFuture<>();
            failed.completeExceptionally(new AuthException("No refresh token stored for this account. Sign in again."));
            return failed;
        }
        return refreshMsToken(existing.msRefreshToken)
                .thenCompose(this::completeChain)
                .thenApply(updated -> {
                    updated.id = existing.id; // same launcher entry, not a brand-new account
                    // Microsoft normally rotates the refresh token; if a response ever omits it, keep the old
                    // one rather than overwriting it with null (that would force a full re-login).
                    if (updated.msRefreshToken == null || updated.msRefreshToken.isBlank()) {
                        updated.msRefreshToken = existing.msRefreshToken;
                    }
                    if (updated.skinUrl == null) updated.skinUrl = existing.skinUrl;
                    if (updated.skinVariant == null) updated.skinVariant = existing.skinVariant;
                    return updated;
                });
    }

    private CompletableFuture<DeviceCodeResponse> requestDeviceCode() {
        return CompletableFuture.supplyAsync(() -> {
            String body = "client_id=" + urlEncode(clientId) + "&scope=" + urlEncode(SCOPE);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(DEVICE_CODE_URL))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = send(request);

            if (response.statusCode() != 200) {
                String detail = extractMsErrorDescription(response.body());
                AppLogger.get().error(TAG, "Device code request failed with HTTP " + response.statusCode()
                        + ": " + response.body());
                throw new AuthException("Microsoft rejected the device code request (HTTP " + response.statusCode()
                        + "): " + detail);
            }

            DeviceCodeResponse code = gson.fromJson(response.body(), DeviceCodeResponse.class);
            if (code == null || code.device_code == null) {
                AppLogger.get().error(TAG, "Device code response had no device_code. Raw body: " + response.body());
                throw new AuthException("Microsoft returned an unexpected response (no device code). "
                        + "Double-check the client ID is correct and 'Allow public client flows' is enabled in Azure.");
            }
            AppLogger.get().info(TAG, "Device code requested, expires in " + code.expires_in + "s");
            return code;
        }, backgroundExecutor);
    }

    /**
     * Microsoft's OAuth error responses look like {"error":"...", "error_description":"..."}.
     * Extracts a human-readable reason from that shape without assuming it's always present.
     */
    private String extractMsErrorDescription(String rawBody) {
        try {
            MsTokenResponse errorShape = gson.fromJson(rawBody, MsTokenResponse.class);
            if (errorShape != null && errorShape.error_description != null) {
                // Microsoft's error_description is a long multi-line string with a doc link;
                // keep just the first line for a readable UI message.
                return errorShape.error_description.split("\\r?\\n")[0];
            }
            if (errorShape != null && errorShape.error != null) {
                return errorShape.error;
            }
        } catch (Exception ignored) {
            // response wasn't the expected JSON error shape; fall through to raw body
        }
        return rawBody != null && !rawBody.isBlank() ? rawBody : "no additional details";
    }

    private CompletableFuture<MsTokenResponse> pollForToken(DeviceCodeResponse deviceCode) {
        return CompletableFuture.supplyAsync(() -> {
            long deadline = System.currentTimeMillis() + deviceCode.expires_in * 1000L;
            int intervalMs = Math.max(deviceCode.interval, 5) * 1000;

            while (System.currentTimeMillis() < deadline) {
                sleep(intervalMs);
                String body = "grant_type=urn:ietf:params:oauth:grant-type:device_code"
                        + "&client_id=" + urlEncode(clientId)
                        + "&device_code=" + urlEncode(deviceCode.device_code);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(TOKEN_URL))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> response = send(request);
                MsTokenResponse token = gson.fromJson(response.body(), MsTokenResponse.class);
                if (token == null) {
                    throw new AuthException("Microsoft sent an empty answer while signing in (HTTP "
                            + response.statusCode() + "). Please try again.");
                }

                if (token.access_token != null) {
                    AppLogger.get().info(TAG, "Microsoft sign-in completed");
                    return token;
                }
                if ("authorization_pending".equals(token.error)) {
                    continue; // user hasn't finished signing in yet, keep polling
                }
                if ("slow_down".equals(token.error)) {
                    intervalMs += 5000;
                    continue;
                }
                if ("expired_token".equals(token.error) || "authorization_declined".equals(token.error)) {
                    throw new AuthException("Sign-in was not completed: " + token.error);
                }
                throw new AuthException("Microsoft sign-in failed: "
                        + (token.error_description != null ? token.error_description : token.error));
            }
            throw new AuthException("Sign-in code expired before completion.");
        }, backgroundExecutor);
    }

    private CompletableFuture<MsTokenResponse> refreshMsToken(String refreshToken) {
        return CompletableFuture.supplyAsync(() -> {
            String body = "grant_type=refresh_token"
                    + "&client_id=" + urlEncode(clientId)
                    + "&refresh_token=" + urlEncode(refreshToken)
                    + "&scope=" + urlEncode(SCOPE);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TOKEN_URL))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = send(request);
            MsTokenResponse token = gson.fromJson(response.body(), MsTokenResponse.class);
            if (token == null || token.access_token == null) {
                String why = token == null ? "empty answer from Microsoft (HTTP " + response.statusCode() + ")"
                        : token.error_description != null ? token.error_description.split("\\r?\\n")[0]
                        : token.error != null ? token.error : "unknown error";
                throw new AuthException("Refresh failed: " + why + ". Sign in again.");
            }
            return token;
        }, backgroundExecutor);
    }

    /** Runs the Xbox Live -> XSTS -> Minecraft hops, common to both fresh sign-in and refresh. */
    private CompletableFuture<Account> completeChain(MsTokenResponse msToken) {
        return authenticateXbl(msToken.access_token)
                .thenCompose(this::authenticateXsts)
                .thenCompose(xsts -> loginMinecraft(xsts).thenApply(mcAuth -> new Object[]{xsts, mcAuth}))
                .thenCompose(pair -> {
                    MinecraftAuthResponse mcAuth = (MinecraftAuthResponse) pair[1];
                    return fetchProfile(mcAuth.access_token).thenApply(profile -> new Object[]{mcAuth, profile});
                })
                .thenApply(pair -> {
                    MinecraftAuthResponse mcAuth = (MinecraftAuthResponse) pair[0];
                    MinecraftProfileResponse profile = (MinecraftProfileResponse) pair[1];
                    if (profile == null || profile.error != null || profile.id == null) {
                        throw new AuthException(profile != null && profile.errorMessage != null
                                ? profile.errorMessage
                                : "This Microsoft account does not own a copy of Minecraft: Java Edition.");
                    }
                    Account account = new Account(UUID.randomUUID().toString(), Account.Type.MICROSOFT,
                            profile.name, profile.id);
                    account.minecraftAccessToken = mcAuth.access_token;
                    account.minecraftAccessTokenExpiresAtEpochMs =
                            System.currentTimeMillis() + (mcAuth.expires_in * 1000L);
                    account.msRefreshToken = msToken.refresh_token;
                    if (profile.skins != null && !profile.skins.isEmpty()) {
                        // Mojang lists every skin the profile has had; the one that's worn is state ACTIVE.
                        MinecraftProfileResponse.Skin worn = profile.skins.get(0);
                        for (MinecraftProfileResponse.Skin sk : profile.skins) {
                            if (sk != null && "ACTIVE".equalsIgnoreCase(sk.state)) { worn = sk; break; }
                        }
                        if (worn != null) {
                            account.skinUrl = worn.url;
                            if (worn.variant != null) account.skinVariant = worn.variant.toLowerCase();
                        }
                    }
                    AppLogger.get().info(TAG, "Signed in as " + profile.name + " (Minecraft profile resolved)");
                    return account;
                });
    }

    private CompletableFuture<XblAuthResponse> authenticateXbl(String msAccessToken) {
        return CompletableFuture.supplyAsync(() -> {
            String json = "{"
                    + "\"Properties\":{\"AuthMethod\":\"RPS\",\"SiteName\":\"user.auth.xboxlive.com\","
                    + "\"RpsTicket\":\"d=" + jsonEscape(msAccessToken) + "\"},"
                    + "\"RelyingParty\":\"http://auth.xboxlive.com\",\"TokenType\":\"JWT\"}";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(XBL_AUTH_URL))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> response = send(request);
            XblAuthResponse xbl = gson.fromJson(response.body(), XblAuthResponse.class);
            if (xbl == null || xbl.Token == null) {
                throw new AuthException("Xbox Live authentication failed.");
            }
            return xbl;
        }, backgroundExecutor);
    }

    private CompletableFuture<XstsAuthResponse> authenticateXsts(XblAuthResponse xbl) {
        return CompletableFuture.supplyAsync(() -> {
            String json = "{"
                    + "\"Properties\":{\"SandboxId\":\"RETAIL\",\"UserTokens\":[\"" + jsonEscape(xbl.Token) + "\"]},"
                    + "\"RelyingParty\":\"rp://api.minecraftservices.com/\",\"TokenType\":\"JWT\"}";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(XSTS_AUTH_URL))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> response = send(request);
            XstsAuthResponse xsts = gson.fromJson(response.body(), XstsAuthResponse.class);
            if (xsts == null || xsts.Token == null) {
                String detail = describeXstsError(xsts);
                throw new AuthException("Xbox authorization failed: " + detail);
            }
            return xsts;
        }, backgroundExecutor);
    }

    private String describeXstsError(XstsAuthResponse xsts) {
        if (xsts == null) return "unknown error";
        long code = xsts.XErr;
        if (code == 2148916233L) return "This Microsoft account has no Xbox Live profile. Create one at xbox.com, then try again.";
        if (code == 2148916235L) return "Xbox Live is not available in this account's region.";
        if (code == 2148916236L || code == 2148916237L) return "This account needs adult verification on the Xbox website.";
        if (code == 2148916238L) return "This is a child account. An adult must add it to a Microsoft Family group first.";
        return "error code " + code;
    }

    private CompletableFuture<MinecraftAuthResponse> loginMinecraft(XstsAuthResponse xsts) {
        return CompletableFuture.supplyAsync(() -> {
            if (xsts.DisplayClaims == null || xsts.DisplayClaims.xui == null || xsts.DisplayClaims.xui.isEmpty()
                    || xsts.DisplayClaims.xui.get(0) == null || xsts.DisplayClaims.xui.get(0).uhs == null) {
                throw new AuthException("Xbox token did not contain the expected user hash (uhs). "
                        + "The account may not have a valid Xbox Live profile.");
            }
            String uhs = xsts.DisplayClaims.xui.get(0).uhs;
            String identityToken = "XBL3.0 x=" + uhs + ";" + xsts.Token;
            String json = "{\"identityToken\":\"" + jsonEscape(identityToken) + "\"}";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(MC_LOGIN_URL))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> response = send(request);
            MinecraftAuthResponse mcAuth = gson.fromJson(response.body(), MinecraftAuthResponse.class);
            if (mcAuth == null || mcAuth.access_token == null) {
                throw new AuthException("Minecraft services login failed.");
            }
            return mcAuth;
        }, backgroundExecutor);
    }

    private CompletableFuture<MinecraftProfileResponse> fetchProfile(String minecraftAccessToken) {
        return CompletableFuture.supplyAsync(() -> {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(MC_PROFILE_URL))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + minecraftAccessToken)
                    .GET()
                    .build();
            HttpResponse<String> response = send(request);
            return gson.fromJson(response.body(), MinecraftProfileResponse.class);
        }, backgroundExecutor);
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new AuthException("Network error contacting authentication service: " + e.getMessage(), e);
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuthException("Sign-in was interrupted.");
        }
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
