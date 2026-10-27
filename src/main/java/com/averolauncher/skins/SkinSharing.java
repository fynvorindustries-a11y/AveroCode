package com.averolauncher.skins;

import com.averolauncher.logging.AppLogger;
import com.averolauncher.model.Account;
import com.averolauncher.repository.AccountRepository;
import com.averolauncher.store.AveroId;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Shares an offline account's skin with every other Avero player.
 *
 * <p>Nobody outside Mojang can make a skin appear for <i>all</i> Minecraft players: the game only trusts
 * skins signed by Mojang, and an offline name has no Mojang profile. (A real Microsoft account does not need
 * this: {@link SkinService#upload} puts the skin on the Mojang profile and everyone sees it.) What the launcher
 * can do is publish the skin to the Avero server for the Avero-linked name; the Avero cape agent, loaded into
 * every game started by the launcher, then fetches it for any player whose UUID has one. So everyone using
 * Avero sees it. The player's own skin never needs the server: the launcher gives the file straight to the agent.
 *
 * <p>Only names linked to the signed-in Avero ID qualify, because the server has to know who owns the UUID.
 * Every method is best effort and never throws.
 */
public final class SkinSharing {

    private static final String TAG = "SkinSharing";

    public enum Result {
        /** The skin (or its removal) is on the Avero server. */
        SHARED,
        /** This account can't share: it is a Microsoft account (already public) or isn't linked to an Avero ID. */
        NOT_APPLICABLE,
        /** The player isn't signed in to their Avero ID. */
        NOT_SIGNED_IN,
        /** The server couldn't be reached or refused; will be retried at the next launch. */
        FAILED
    }

    private SkinSharing() {}

    /** True for an offline account that mirrors a name linked to the Avero ID (so the server knows its UUID). */
    public static boolean canShare(Account a) {
        return a != null && a.type == Account.Type.OFFLINE && a.averoLinked
                && a.minecraftUuid != null && a.minecraftUuid.replace("-", "").matches("(?i)[0-9a-f]{32}");
    }

    /**
     * Brings the server in line with the account: publishes the worn skin when it changed (or was never
     * published), or removes the shared skin when the account no longer wears one. Never completes exceptionally.
     */
    public static CompletableFuture<Result> sync(AccountRepository accounts, Account account) {
        try {
            if (!canShare(account)) return CompletableFuture.completedFuture(Result.NOT_APPLICABLE);
            if (!AveroId.get().isSignedIn()) return CompletableFuture.completedFuture(Result.NOT_SIGNED_IN);
            final String uuid = account.minecraftUuid.replace("-", "").toLowerCase(Locale.ROOT);
            final String accountId = account.id;
            final String skinId = account.offlineSkinId;
            final String variant = SkinLibrary.SLIM.equals(account.offlineSkinVariant) ? SkinLibrary.SLIM : SkinLibrary.CLASSIC;
            final String sharedHash = account.offlineSkinSharedHash;

            if (skinId == null || skinId.isBlank()) {
                if (sharedHash == null) return CompletableFuture.completedFuture(Result.SHARED); // nothing published, nothing to undo
                return AveroId.get().api().removeSkin(uuid)
                        .thenApply(v -> { remember(accounts, accountId, null); return Result.SHARED; })
                        .exceptionally(ex -> fail("remove", ex));
            }

            Path file = new SkinLibrary().pngPath(skinId);
            if (!Files.isRegularFile(file)) return CompletableFuture.completedFuture(Result.FAILED);
            final byte[] png = Files.readAllBytes(file);
            final String hash = hash(variant, png);
            if (hash.equals(sharedHash)) return CompletableFuture.completedFuture(Result.SHARED);

            return AveroId.get().api().publishSkin(uuid, png, variant)
                    .thenApply(v -> { remember(accounts, accountId, hash); return Result.SHARED; })
                    .exceptionally(ex -> fail("publish", ex));
        } catch (Exception e) {
            AppLogger.get().warn(TAG, "Skin sharing skipped: " + e.getMessage());
            return CompletableFuture.completedFuture(Result.FAILED);
        }
    }

    private static Result fail(String what, Throwable ex) {
        Throwable c = ex;
        while (c.getCause() != null && c instanceof java.util.concurrent.CompletionException) c = c.getCause();
        AppLogger.get().warn(TAG, "Could not " + what + " the shared skin: " + c.getMessage());
        return Result.FAILED;
    }

    /** Stores the published hash on the freshly loaded account (the caller's copy may be stale by now). */
    private static void remember(AccountRepository accounts, String accountId, String hash) {
        try {
            accounts.modify(all -> {
                for (Account a : all) {
                    if (accountId.equals(a.id)) { a.offlineSkinSharedHash = hash; return true; }
                }
                return false;
            });
        } catch (Exception e) {
            AppLogger.get().warn(TAG, "Could not remember the shared skin state: " + e.getMessage());
        }
    }

    private static String hash(String variant, byte[] png) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(variant.getBytes(StandardCharsets.UTF_8));
        md.update((byte) 0);
        return HexFormat.of().formatHex(md.digest(png));
    }
}
