package com.averolauncher.model.auth;

import java.util.List;

/** Real response shapes for each hop of the Microsoft -> Xbox -> XSTS -> Minecraft auth chain. */
public class AuthModels {

    /** POST https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode */
    public static class DeviceCodeResponse {
        public String device_code;
        public String user_code;
        public String verification_uri;
        public int expires_in;
        public int interval;
        public String message;
    }

    /** POST https://login.microsoftonline.com/consumers/oauth2/v2.0/token (device_code or refresh_token grant) */
    public static class MsTokenResponse {
        public String token_type;
        public int expires_in;
        public String scope;
        public String access_token;
        public String refresh_token;
        public String error;
        public String error_description;
    }

    /** POST https://user.auth.xboxlive.com/user/authenticate */
    public static class XblAuthResponse {
        public String IssueInstant;
        public String NotAfter;
        public String Token;
        public DisplayClaims DisplayClaims;

        public static class DisplayClaims {
            public List<Xui> xui;
        }
        public static class Xui {
            public String uhs; // user hash, needed for XSTS and Minecraft login
        }
    }

    /** POST https://xsts.auth.xboxlive.com/xsts/authorize (same response shape as XBL auth) */
    public static class XstsAuthResponse extends XblAuthResponse {
        public long XErr;        // present on error responses (e.g. no Xbox account, child account restrictions)
        public String Message;
        public String Redirect;
    }

    /** POST https://api.minecraftservices.com/authentication/login_with_xbox */
    public static class MinecraftAuthResponse {
        public String username; // this is actually the Minecraft-service UUID, not the player name
        public String access_token;
        public String token_type;
        public int expires_in;
    }

    /** GET https://api.minecraftservices.com/minecraft/profile */
    public static class MinecraftProfileResponse {
        public String id;   // player UUID, no dashes
        public String name; // player username
        public List<Skin> skins;
        public String error;
        public String errorMessage;

        public static class Skin {
            public String id;
            public String state;
            public String url;
            public String variant;
        }
    }
}
