package com.averolauncher.model;

import java.util.Objects;

/**
 * Represents a launcher account: either a real, authenticated Microsoft/Minecraft
 * account, or a locally-generated offline profile. Offline accounts are always
 * clearly flagged via {@link #type} so the UI can never misrepresent one as
 * a genuine Microsoft account.
 */
public class Account {

    public enum Type { MICROSOFT, OFFLINE }

    public String id;                  // internal stable id (UUID string)
    public Type type;
    public String username;
    public String minecraftUuid;       // Mojang/Minecraft profile UUID (dashless or dashed)
    public String skinUrl;             // may be null
    public String skinVariant;         // "classic" | "slim" | null (unknown) — last skin Mojang reported

    /**
     * True for an OFFLINE account that mirrors a player name linked to the signed-in Avero ID. These are
     * created and removed automatically by {@code AveroProfileSync}; they are how an Avero account gets a
     * real name + UUID into the game (the same job TLauncher / CMClient profiles do).
     */
    public boolean averoLinked;

    /**
     * OFFLINE accounts only: id of the library skin this account wears in-game, or null. Minecraft only loads
     * real skins from Mojang, so for offline accounts the launcher hands the file to the Avero agent at
     * launch, which shows it on the player (see {@code CapeSupport.agentArgument}). No resource pack is used.
     */
    public String offlineSkinId;
    public String offlineSkinVariant;   // "classic" | "slim"

    /**
     * SHA-256 of the skin (plus arm model) last published to the Avero server for this account, or null when
     * nothing is shared. Lets the launcher re-publish only when the skin actually changed or the server missed it.
     */
    public String offlineSkinSharedHash;

    // Only populated for MICROSOFT accounts. Never logged, never rendered in UI.
    public String msRefreshToken;
    public String minecraftAccessToken;
    public long minecraftAccessTokenExpiresAtEpochMs;

    public Account() {}

    public Account(String id, Type type, String username, String minecraftUuid) {
        this.id = id;
        this.type = type;
        this.username = username;
        this.minecraftUuid = minecraftUuid;
    }

    public boolean isMinecraftTokenValid() {
        return type == Type.MICROSOFT
                && minecraftAccessToken != null
                && System.currentTimeMillis() < minecraftAccessTokenExpiresAtEpochMs;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Account)) return false;
        Account account = (Account) o;
        return Objects.equals(id, account.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
