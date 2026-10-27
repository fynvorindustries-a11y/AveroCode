package com.averolauncher.store;

import com.averolauncher.logging.AppLogger;
import com.averolauncher.model.Account;
import com.averolauncher.ui.AppContext;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Turns the player names linked to the signed-in Avero ID into real launcher accounts.
 *
 * <p>Why this exists: an Avero ID lives on the Avero server, but the game is started from
 * {@code accounts.json}. With no Minecraft account saved there, nothing told the game who was playing and
 * it ran as "Player". Every <b>unverified</b> (offline) linked name is therefore mirrored here as an
 * {@link Account.Type#OFFLINE} account flagged {@link Account#averoLinked}, carrying the <b>server's UUID</b>
 * for that name so the cape agent ({@code self=<uuid>}) and the server agree on who the player is.
 *
 * <p>Verified names are never mirrored: those belong to a real Mojang account and only play through
 * Microsoft sign-in (which already creates a MICROSOFT account with the same UUID).
 *
 * <p>The mirror is kept exact: names that get unlinked disappear, and everything is removed on sign-out.
 */
public final class AveroProfileSync {

    private static final String TAG = "AveroProfileSync";

    private AveroProfileSync() {}

    /** Mirrors {@code profiles} into accounts.json. Returns true if anything changed (accounts or selection). */
    public static boolean apply(AppContext ctx, List<StoreApi.LinkedProfile> profiles) {
        if (ctx == null || profiles == null) return false;

        Set<String> wanted = new HashSet<>();
        for (StoreApi.LinkedProfile p : profiles) {
            if (p == null || p.verified || p.username == null || norm(p.uuid) == null) continue;
            wanted.add(norm(p.uuid));
        }

        boolean changed = ctx.accountRepository.modify(all -> {
            boolean c = all.removeIf(a -> a.averoLinked && (a.minecraftUuid == null || !wanted.contains(norm(a.minecraftUuid))));

            for (StoreApi.LinkedProfile p : profiles) {
                if (p == null || p.verified || p.username == null) continue;
                String id = norm(p.uuid);
                if (id == null) continue;

                Account existing = null;
                for (Account a : all) {
                    if (a.minecraftUuid != null && id.equals(norm(a.minecraftUuid))) { existing = a; break; }
                }
                if (existing == null) {
                    Account a = new Account(UUID.randomUUID().toString(), Account.Type.OFFLINE, p.username, dash(id));
                    a.averoLinked = true;
                    all.add(a);
                    c = true;
                } else if (existing.averoLinked && !p.username.equals(existing.username)) {
                    existing.username = p.username; // renamed on the server
                    c = true;
                }
                // else: a real saved account already owns this UUID; leave it alone.
            }
            return c;
        });

        boolean selectionFixed = ensureActive(ctx);
        if (changed) AppLogger.get().info(TAG, "Avero ID names synced to launcher accounts.");
        return changed || selectionFixed;
    }

    /** Signed out of the Avero ID: drop every mirrored account. Returns true if anything changed. */
    public static boolean removeAll(AppContext ctx) {
        if (ctx == null) return false;
        boolean changed = ctx.accountRepository.modify(all -> all.removeIf(a -> a.averoLinked));
        boolean selectionFixed = ensureActive(ctx);
        return changed || selectionFixed;
    }

    /** Makes sure the selected account still exists; otherwise picks one (a Microsoft one first), or none. */
    private static boolean ensureActive(AppContext ctx) {
        try {
            List<Account> all = ctx.accountRepository.load();
            String id = ctx.settings.activeAccountId;
            boolean valid = id != null && all.stream().anyMatch(a -> id.equals(a.id));
            if (valid) return false;

            String next = null;
            if (!all.isEmpty()) {
                next = all.stream().filter(a -> a.type == Account.Type.MICROSOFT).findFirst().orElse(all.get(0)).id;
            }
            if (java.util.Objects.equals(id, next)) return false;
            ctx.settings.activeAccountId = next;
            ctx.settingsRepository.save(ctx.settings);
            return true;
        } catch (Exception e) {
            AppLogger.get().warn(TAG, "Could not update the selected account: " + e.getMessage());
            return false;
        }
    }

    /** Lower-case 32-hex form of a UUID, or null when it isn't one. */
    private static String norm(String uuid) {
        if (uuid == null) return null;
        String s = uuid.replace("-", "").toLowerCase(Locale.ROOT);
        return s.matches("[0-9a-f]{32}") ? s : null;
    }

    private static String dash(String hex32) {
        return hex32.substring(0, 8) + "-" + hex32.substring(8, 12) + "-" + hex32.substring(12, 16)
                + "-" + hex32.substring(16, 20) + "-" + hex32.substring(20);
    }
}
