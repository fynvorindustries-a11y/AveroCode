package com.averolauncher.auth;

import com.averolauncher.logging.AppLogger;
import com.averolauncher.model.Account;
import com.averolauncher.repository.AccountRepository;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one place that refreshes a Microsoft account's Minecraft session AND saves the result.
 *
 * <p>Microsoft rotates the refresh token on every use. Before this existed, the launch path
 * refreshed the session but never wrote the new tokens back to {@code accounts.json}, and two
 * callers (startup keep-alive and a launch) could refresh the same account at once. Everything now
 * goes through {@link #refreshAndSave}: concurrent requests for one account share a single refresh,
 * and the rotated tokens are persisted before anyone uses them.
 */
public final class AccountSessions {

    private static final String TAG = "AccountSessions";
    private static final Map<String, CompletableFuture<Account>> IN_FLIGHT = new ConcurrentHashMap<>();

    private AccountSessions() {}

    /**
     * Returns {@code account} unchanged when it needs no refresh (offline account or a still-valid
     * Minecraft token). Otherwise refreshes it through Microsoft and persists the new tokens.
     */
    public static CompletableFuture<Account> refreshAndSave(MicrosoftAuthService svc, Account account) {
        if (account == null || account.type != Account.Type.MICROSOFT || account.isMinecraftTokenValid()) {
            return CompletableFuture.completedFuture(account);
        }
        if (svc == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Microsoft sign-in isn't available right now. Restart the launcher and try again."));
        }
        CompletableFuture<Account> mine = new CompletableFuture<>();
        CompletableFuture<Account> running = IN_FLIGHT.putIfAbsent(account.id, mine);
        if (running != null) return running; // someone is already refreshing this exact account

        svc.refresh(account)
                .thenApply(updated -> { save(updated); return updated; })
                .whenComplete((updated, err) -> {
                    IN_FLIGHT.remove(account.id);
                    if (err != null) mine.completeExceptionally(err); else mine.complete(updated);
                });
        return mine;
    }

    /** Replaces the stored copy of {@code updated} (matched by id). Never re-adds a removed account. */
    public static void save(Account updated) {
        if (updated == null || updated.id == null) return;
        // One locked read-modify-write inside the repository (the same lock the UI uses), so a refresh
        // finishing in the background can't clobber an account that was added or removed meanwhile.
        if (!new AccountRepository().replace(updated)) {
            AppLogger.get().debug(TAG, "Refreshed account is no longer saved; not re-adding it.");
        }
    }
}
