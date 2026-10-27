package com.averolauncher.store;

import com.averolauncher.auth.AccountSessions;
import com.averolauncher.logging.AppLogger;
import com.averolauncher.model.Account;
import com.averolauncher.store.StoreApi.AveroSession;
import com.averolauncher.ui.AppContext;
import com.averolauncher.util.CredentialStore;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process-wide Avero account state: one {@link StoreApi}, the signed-in session, and listeners so
 * the Accounts page and the Store page stay in sync.
 *
 * <p>Only the long-lived <b>refresh token</b> is persisted, encrypted in the launcher's credential
 * store — never the password, never the access token. On startup the refresh token is exchanged for
 * a fresh access token, so an expired access token never forces the player to type their password
 * again.
 *
 * <p><b>You stay signed in until you sign out.</b> The session is only ever cleared when the player
 * signs out, or when the server explicitly rejects the refresh token (revoked / expired). A server
 * that is asleep, unreachable or erroring never signs anyone out — the saved session is simply
 * retried in the background.
 */
public final class AveroId implements StoreApi.SessionStore {

    private static final String TAG = "AveroId";
    private static final String KEY = "avero_session"; // refreshToken|userId|email|username|sessionId (no newlines/pipes in fields)
    private static final AveroId INSTANCE = new AveroId();

    public static AveroId get() { return INSTANCE; }

    private final StoreApi api = new StoreApi();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Set<String> linkAttempted = ConcurrentHashMap.newKeySet();
    private volatile AppContext boundCtx;
    private volatile String sessionProblem;
    private AveroSession session;

    private AveroId() {
        api.setSessionStore(this);
        restore();
    }

    public StoreApi api() { return api; }

    /** Gives Avero ID access to the launcher's accounts so it can auto-link Microsoft profiles. Call once at startup. */
    public void bind(AppContext ctx) {
        this.boundCtx = ctx;
        if (isSignedIn()) { autoLinkMicrosoft(ctx); syncProfiles(); }
        keepMicrosoftAlive(ctx);
    }

    /**
     * Makes the Avero ID playable: pulls the linked player names from the server and mirrors them into the
     * launcher's accounts (see {@link AveroProfileSync}). A brand-new Avero ID has no linked name yet, so its
     * own name is linked first (when it is a valid player name that Mojang doesn't own; if the server refuses,
     * the player can still link another name, or sign in with Microsoft).
     */
    public void syncProfiles() {
        final AppContext c = boundCtx;
        if (c == null || !isSignedIn() || api.isDemo()) return;
        api.me().whenComplete((user, err) -> {
            if (err != null || user == null) {
                AppLogger.get().info(TAG, "Could not read the linked names to sync: " + (err == null ? "no data" : rootMessage(err)));
                return;
            }
            if (user.profiles == null || user.profiles.isEmpty()) { linkDefaultName(c, user); return; }
            applyProfiles(c, user.profiles);
        });
    }

    private void applyProfiles(AppContext c, List<StoreApi.LinkedProfile> profiles) {
        if (AveroProfileSync.apply(c, profiles)) fire();
    }

    private void linkDefaultName(AppContext c, StoreApi.AveroUser user) {
        String name = null;
        for (String cand : new String[]{ user.minecraftUsername, user.username,
                session() != null ? session().username : null }) {
            if (cand != null && cand.trim().matches("\\w{3,16}")) { name = cand.trim(); break; }
        }
        if (name == null) { applyProfiles(c, List.of()); return; }
        final String chosen = name;
        // Once per sign-in: a name the server refuses (e.g. it exists at Mojang) must not be retried in a loop.
        if (!linkAttempted.add("default-name:" + (user.id == null ? "" : user.id) + ":" + chosen.toLowerCase())) {
            applyProfiles(c, List.of());
            return;
        }
        api.linkOffline(chosen).whenComplete((list, ex) -> {
            if (ex != null) {
                AppLogger.get().info(TAG, "Default name \u201c" + chosen + "\u201d was not linked: " + rootMessage(ex));
                applyProfiles(c, List.of());
                return;
            }
            AppLogger.get().info(TAG, "Linked \u201c" + chosen + "\u201d to the new Avero ID.");
            applyProfiles(c, list);
        });
    }

    private static String rootMessage(Throwable t) {
        Throwable r = t; while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        return r.getMessage() != null ? r.getMessage() : r.getClass().getSimpleName();
    }

    /**
     * Refreshes the active Microsoft account's Minecraft token in the background at startup (and saves
     * the rotated tokens). This keeps the sign-in alive without the player doing anything, and means
     * pressing Play never has to wait on a refresh. A failure here is only logged: the saved account
     * stays signed in and is retried at launch.
     */
    private void keepMicrosoftAlive(AppContext c) {
        try {
            String activeId = c.settings.activeAccountId;
            if (activeId == null) return;
            Account active = c.accountRepository.load().stream()
                    .filter(a -> activeId.equals(a.id)).findFirst().orElse(null);
            if (active == null || active.type != Account.Type.MICROSOFT
                    || active.msRefreshToken == null || active.isMinecraftTokenValid()) return;
            AccountSessions.refreshAndSave(c.microsoftAuthService, active).whenComplete((fresh, ex) -> {
                if (ex != null) {
                    Throwable r = ex; while (r.getCause() != null && r.getCause() != r) r = r.getCause();
                    AppLogger.get().info(TAG, "Microsoft session for " + active.username
                            + " not refreshed at startup (kept; will retry at launch): " + r.getMessage());
                } else {
                    AppLogger.get().info(TAG, "Microsoft session for " + active.username + " refreshed at startup.");
                    fire();
                }
            });
        } catch (Exception e) {
            AppLogger.get().warn(TAG, "Microsoft keep-alive skipped: " + e.getMessage());
        }
    }

    /**
     * Fully automatic: every Microsoft account saved in the launcher is verified with Mojang through
     * the Avero server and linked to the signed-in Avero ID. Runs after Avero sign-in, after a Microsoft
     * sign-in, and whenever the account page opens; each account is only attempted once per session
     * unless the attempt failed for a transient reason.
     */
    public void autoLinkMicrosoft(AppContext ctx) {
        AppContext c = ctx != null ? ctx : boundCtx;
        AveroSession s = session();
        if (c == null || s == null || api.isDemo() || !s.hasRefresh()) return;
        String owner = s.userId == null ? "" : s.userId;
        List<Account> all;
        try { all = c.accountRepository.load(); } catch (Exception e) { return; }
        for (Account a : all) {
            if (a.type != Account.Type.MICROSOFT || a.minecraftUuid == null) continue;
            String key = owner + ":" + a.minecraftUuid;
            if (!linkAttempted.add(key)) continue;
            freshAccount(c, a)
                    .thenCompose(fa -> api.linkMicrosoft(fa.minecraftAccessToken))
                    .whenComplete((list, ex) -> {
                        if (ex != null) {
                            Throwable r = ex; while (r.getCause() != null && r.getCause() != r) r = r.getCause();
                            AppLogger.get().info(TAG, "Auto-link of " + a.username + " skipped: " + r.getMessage());
                            // Permanent answers (already linked elsewhere, no Java licence) aren't retried;
                            // anything the server might answer differently next time is.
                            if (r instanceof StoreApi.StoreException se && (se.isTransient() || "TOKEN_EXPIRED".equals(se.code))) linkAttempted.remove(key);
                        } else {
                            AppLogger.get().info(TAG, "Auto-linked " + a.username + " to the Avero ID.");
                            fire();
                        }
                    });
        }
    }

    /** Loads the persisted refresh token (if any) and refreshes it into a live access token. */
    private void restore() {
        try {
            String raw = CredentialStore.read(KEY);
            if (raw == null) return;
            String[] p = raw.split("\\|", 5);
            if (p.length < 1 || p[0].isBlank()) return;
            if (api.isDemo() || "demo".equals(p[0])) { CredentialStore.delete(KEY); return; } // fake session left by the old demo mode
            AveroSession s = new AveroSession();
            s.refreshToken = p[0];
            if (p.length > 1) s.userId = p[1];
            if (p.length > 2) s.email = p[2];
            if (p.length > 3) s.username = p[3];
            if (p.length > 4) s.sessionId = p[4];
            session = s; // access token is null; the first call (or restoreAsync) refreshes it
        } catch (Exception e) {
            AppLogger.get().warn(TAG, "Could not restore Avero session: " + e.getMessage());
        }
        // Exchange the refresh token for an access token in the background so the UI is ready fast.
        if (session != null && !api.isDemo()) {
            Thread t = new Thread(this::refreshSavedSession, "avero-session-restore");
            t.setDaemon(true);
            t.start();
        }
    }

    /**
     * Turns the saved refresh token into a live access token. Free hosts can take a minute to wake and
     * the player may be offline, so transient failures are retried with back-off and NEVER sign the
     * player out. Only an explicit rejection of the refresh token ends the session.
     */
    private void refreshSavedSession() {
        long[] delaysMs = {0, 8_000, 30_000, 90_000, 300_000, 900_000};
        for (long delay : delaysMs) {
            if (delay > 0) {
                try { Thread.sleep(delay); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
            if (current() == null) return; // the player signed out in the meantime
            try {
                api.refreshBlocking();
                fire();
                autoLinkMicrosoft(null);
                syncProfiles();
                return;
            } catch (StoreApi.StoreException se) {
                if (se.isSessionRejected()) {
                    // The server said no. Tell the player why instead of quietly dropping them on the
                    // sign-in form — a disabled account looks exactly like a normal sign-out otherwise.
                    sessionProblem = "ACCOUNT_DISABLED".equals(se.code)
                            ? "Your Avero account was disabled by an administrator, so you have been signed out."
                            : "The Avero server no longer accepts your saved session, so you have been signed out.";
                    AppLogger.get().info(TAG, "The server rejected the saved session (" + se.code + "); signed out.");
                    clear();
                    fire();
                    return;
                }
                AppLogger.get().info(TAG, "Avero server gave no clear verdict (" + se.code + "/" + se.status
                        + "); keeping the saved sign-in and retrying.");
            } catch (Exception e) {
                // Anything unexpected (bad JSON, I/O) says nothing about the session — keep it.
                AppLogger.get().info(TAG, "Session refresh hit a snag; keeping the saved sign-in: " + e.getMessage());
            }
        }
        AppLogger.get().info(TAG, "Avero server still unreachable; staying signed in and refreshing on next use.");
    }

    // ---------------------------------------------------------- SessionStore

    @Override
    public synchronized AveroSession current() { return session; }

    @Override
    public synchronized void persist(AveroSession s) {
        this.session = s;
        if (s == null || !s.hasRefresh()) { CredentialStore.delete(KEY); return; }
        CredentialStore.write(KEY, String.join("|",
                nz(s.refreshToken), nz(s.userId), nz(s.email), nz(s.username), nz(s.sessionId)));
    }

    @Override
    public synchronized void clear() {
        this.session = null;
        linkAttempted.clear();
        CredentialStore.delete(KEY);
        // The names that came from this Avero ID go with it; saved Microsoft accounts stay.
        try { AveroProfileSync.removeAll(boundCtx); } catch (Exception e) {
            AppLogger.get().warn(TAG, "Could not remove Avero-linked accounts: " + e.getMessage());
        }
    }

    private static String nz(String s) { return s == null ? "" : s.replace("|", "").replace("\n", ""); }

    // -------------------------------------------------------------- lifecycle

    /** The current session, or null when signed out. */
    public synchronized AveroSession session() {
        if (session != null && !session.hasRefresh()) { session = null; }
        return session;
    }

    public boolean isSignedIn() { return session() != null; }

    /** Called after a successful login/register: adopt + persist the session. */
    public synchronized void signedIn(AveroSession s) {
        sessionProblem = null;
        persist(s);
        fire();
        autoLinkMicrosoft(null);
        syncProfiles();
    }

    /** Revokes the server-side session, clears local tokens, and notifies listeners. */
    public void signOut() {
        sessionProblem = null;
        api.logout().whenComplete((v, e) -> { clear(); fire(); });
    }

    /**
     * Why the saved sign-in ended by itself, or null. The UI shows this once — a player who finds the
     * login form again without explanation reasonably assumes the launcher lost their password.
     */
    public String takeSessionProblem() {
        String p = sessionProblem;
        sessionProblem = null;
        return p;
    }

    public void addListener(Runnable r) { listeners.add(r); }
    public void removeListener(Runnable r) { listeners.remove(r); }
    private void fire() { for (Runnable r : listeners) { try { r.run(); } catch (Exception ignored) { } } }

    /**
     * Returns {@code account} with a valid Minecraft token, refreshing through Microsoft and
     * persisting the new tokens if needed. Offline accounts pass straight through.
     */
    public CompletableFuture<Account> freshAccount(AppContext ctx, Account account) {
        return AccountSessions.refreshAndSave(ctx == null ? null : ctx.microsoftAuthService, account);
    }
}
