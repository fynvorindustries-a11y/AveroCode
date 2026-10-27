package com.averolauncher.service;

import com.averolauncher.model.Account;
import com.averolauncher.model.LauncherSettings;
import com.averolauncher.repository.AccountRepository;
import com.averolauncher.repository.SettingsRepository;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Everything about offline ("cracked") accounts that is not user interface: the rules for a valid nickname, the
 * UUID Minecraft itself derives from it, and creating + selecting the account.
 *
 * <p>An offline account behaves like one in TLauncher or any other offline launcher: a nickname, no Microsoft
 * sign-in, works in singleplayer, on LAN and on servers running in offline mode. The UUID is the one a vanilla
 * offline-mode server computes for that name ({@code UUID.nameUUIDFromBytes("OfflinePlayer:" + name)}), so the
 * player keeps the same inventory and permissions on every such server.
 */
public final class OfflineAccounts {

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private OfflineAccounts() {}

    /** Minecraft's own rule: 3-16 letters, digits or underscores. Anything else is kicked by most servers. */
    public static boolean isValidName(String name) {
        return name != null && VALID.matcher(name).matches();
    }

    /** The UUID an offline-mode server (and the game itself) derives from a nickname. */
    public static String uuidFor(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** A valid, unlikely-to-collide nickname such as "Player4821" (never the same fixed name for everyone). */
    public static String suggestName() {
        return "Player" + (1000 + RANDOM.nextInt(9000));
    }

    /**
     * Creates an offline account with this nickname, saves it and makes it the selected account.
     * If an account with that nickname already exists it is selected instead of creating a duplicate.
     * The caller must have checked {@link #isValidName}; an invalid name is rejected.
     */
    public static Account createAndSelect(AccountRepository accounts, LauncherSettings settings,
                                          SettingsRepository settingsRepo, String nickname) {
        if (!isValidName(nickname)) throw new IllegalArgumentException("Invalid nickname: " + nickname);
        List<Account> all = accounts.load();
        Account chosen = null;
        for (Account a : all) {
            if (a.username != null && a.username.equalsIgnoreCase(nickname)) { chosen = a; break; }
        }
        if (chosen == null) {
            chosen = new Account(UUID.randomUUID().toString(), Account.Type.OFFLINE, nickname, uuidFor(nickname));
            all.add(chosen);
            accounts.save(all);
        }
        settings.activeAccountId = chosen.id;
        settingsRepo.save(settings);
        return chosen;
    }
}
