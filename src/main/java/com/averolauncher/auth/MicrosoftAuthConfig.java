package com.averolauncher.auth;

/**
 * Holds the Azure AD application (client) ID that Minecraft's Microsoft
 * OAuth flow requires. This is NOT a secret - public clients (like desktop
 * launchers) use the "public client" / native-app flow with PKCE, which
 * does not require a client secret.
 *
 * DEFAULT_CLIENT_ID below is AveroLauncher's own registered Azure AD
 * application, so end users never need to configure anything themselves.
 * It can still be overridden per-build via the AVEROLAUNCHER_MS_CLIENT_ID
 * environment variable or the averolauncher.ms.clientId system property,
 * e.g. for a fork that wants to use its own Azure app registration instead.
 */
public final class MicrosoftAuthConfig {

    private MicrosoftAuthConfig() {}

    /** AveroLauncher's registered Azure AD (Entra ID) application (client) ID. */
    public static final String DEFAULT_CLIENT_ID = "92307c7a-e9f6-48fc-b412-c897dd803aa5";

    /** The loopback port used for the local redirect listener. 0 = pick a free port dynamically. */
    public static final int PREFERRED_LOOPBACK_PORT = 0;

    public static final String AUTHORIZE_ENDPOINT = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize";
    public static final String TOKEN_ENDPOINT = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    public static final String XBOX_LIVE_AUTH_ENDPOINT = "https://user.auth.xboxlive.com/user/authenticate";
    public static final String XSTS_AUTH_ENDPOINT = "https://xsts.auth.xboxlive.com/xsts/authorize";
    public static final String MINECRAFT_LOGIN_ENDPOINT = "https://api.minecraftservices.com/authentication/login_with_xbox";
    public static final String MINECRAFT_PROFILE_ENDPOINT = "https://api.minecraftservices.com/minecraft/profile";
    public static final String MINECRAFT_ENTITLEMENTS_ENDPOINT = "https://api.minecraftservices.com/entitlements/mcstore";

    public static final String SCOPE = "XboxLive.signin offline_access";

    public static String clientId() {
        String fromEnv = System.getenv("AVEROLAUNCHER_MS_CLIENT_ID");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        String fromProperty = System.getProperty("averolauncher.ms.clientId");
        if (fromProperty != null && !fromProperty.isBlank()) {
            return fromProperty;
        }
        return DEFAULT_CLIENT_ID;
    }

    public static boolean isConfigured() {
        return clientId() != null && !clientId().isBlank();
    }

    public static String redirectUri(int actualPort) {
        return "http://localhost:" + actualPort + "/callback";
    }
}
