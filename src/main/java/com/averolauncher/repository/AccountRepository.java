package com.averolauncher.repository;

import com.averolauncher.model.Account;
import com.averolauncher.util.PathResolver;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists the list of launcher accounts to disk as JSON.
 *
 * File location: {@code <launcherDataDir>/accounts.json}
 *
 * Uses an atomic write (write to a .tmp file, then move) so a crash during
 * save never leaves a half-written or empty accounts file.
 */
public class AccountRepository {

    private static final String ACCOUNTS_FILE = "accounts.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<Account>>() {}.getType();

    /** Loads all persisted accounts. Returns an empty (mutable) list if the file does not exist or is unreadable. */
    /** Shared by every instance: the UI, the launch path and background refreshes all touch accounts.json. */
    private static final Object LOCK = new Object();

    public List<Account> load() {
        synchronized (LOCK) { return loadLocked(); }
    }

    private List<Account> loadLocked() {
        Path file = accountsFile();
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (json.isEmpty()) return new ArrayList<>();
            List<Account> accounts = GSON.fromJson(json, LIST_TYPE);
            return accounts != null ? accounts : new ArrayList<>();
        } catch (Exception e) {
            // Corrupted file — keep a copy (the next save would otherwise overwrite every saved
            // sign-in with an empty list) and return empty rather than crashing the launcher.
            System.err.println("[AccountRepository] Failed to load accounts.json: " + e.getMessage());
            try {
                Files.copy(file, file.resolveSibling("accounts.corrupted." + System.currentTimeMillis() + ".json"),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) { }
            return new ArrayList<>();
        }
    }

    /**
     * Replaces the stored copy of {@code updated} (matched by id) in one locked read-modify-write, so a
     * background token refresh can never overwrite an account that was added/removed at the same moment.
     * Returns false (and writes nothing) when that account is no longer saved.
     */
    public boolean replace(Account updated) {
        if (updated == null || updated.id == null) return false;
        synchronized (LOCK) {
            List<Account> all = loadLocked();
            for (int i = 0; i < all.size(); i++) {
                if (updated.id.equals(all.get(i).id)) {
                    all.set(i, updated);
                    saveLocked(all);
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * One locked read-modify-write. {@code mutator} edits the freshly loaded list and returns true when it
     * changed something (only then is the file written). Returns that same flag.
     */
    public boolean modify(java.util.function.Predicate<List<Account>> mutator) {
        synchronized (LOCK) {
            List<Account> all = loadLocked();
            if (!mutator.test(all)) return false;
            saveLocked(all);
            return true;
        }
    }

    /**
     * Saves the given account list to disk atomically.
     * Passing an empty list writes an empty JSON array (not a deletion) so the
     * file is always well-formed on the next {@link #load()}.
     */
    public void save(List<Account> accounts) {
        synchronized (LOCK) { saveLocked(accounts); }
    }

    private void saveLocked(List<Account> accounts) {
        Path file = accountsFile();
        try {
            Files.createDirectories(file.getParent());
            String json = GSON.toJson(accounts == null ? new ArrayList<>() : accounts);
            Path tmp = file.resolveSibling(ACCOUNTS_FILE + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                // Windows can refuse an atomic replace while antivirus/indexers hold the file open.
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("[AccountRepository] Failed to save accounts.json: " + e.getMessage());
        }
    }

    private Path accountsFile() {
        return PathResolver.launcherDataDir().resolve(ACCOUNTS_FILE);
    }
}
