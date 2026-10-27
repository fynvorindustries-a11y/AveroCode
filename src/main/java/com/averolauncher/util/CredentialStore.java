package com.averolauncher.util;

import com.averolauncher.logging.AppLogger;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.LinkedHashSet;

/**
 * Stores sensitive launcher credentials (the Avero ID refresh token, etc.) encrypted on disk
 * using AES-256-GCM.
 *
 * <p><b>Key management (v2).</b> The key is derived from a random per-install secret kept in
 * {@code credentials.key} next to the data file. Earlier builds derived it from the hostname and
 * the <i>first</i> network adapter's MAC address; those change whenever a VPN, Wi-Fi/Ethernet
 * switch, Bluetooth or virtual adapter appears, which made the file undecryptable and silently
 * signed the player out. Existing v1 files are decrypted once with the old derivation (trying
 * every adapter/hostname this machine has) and transparently re-encrypted with the stable key.
 *
 * <p>This is the same trust level as the rest of the launcher's data folder: protection comes
 * from the user's OS profile permissions, not from the cipher.
 *
 * File location: &lt;launcherDataDir&gt;/credentials.dat  (+ credentials.key)
 * Format: [12-byte GCM IV][32-byte salt][encrypted bytes], base64 on a single line.
 */
public final class CredentialStore {

    private static final String TAG = "CredentialStore";
    private static final String ALGORITHM  = "AES/GCM/NoPadding";
    private static final int    GCM_IV_LEN = 12;
    private static final int    GCM_TAG_BITS = 128;
    private static final int    SALT_LEN   = 32;
    private static final int    KEY_BITS   = 256;
    private static final int    PBKDF2_ITERATIONS = 65536;
    private static final String CREDENTIALS_FILE = "credentials.dat";
    private static final String KEY_FILE = "credentials.key";

    /** Serialises every read-modify-write so the restore thread, the UI and sign-out never race. */
    private static final Object LOCK = new Object();
    private static volatile String cachedPassword;

    // Keys stored inside the encrypted blob (simple key=value pairs, newline separated)
    public static final String KEY_MS_CLIENT_ID = "ms_client_id";

    private CredentialStore() {}

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Reads a single credential value by key. Returns null if the file doesn't
     * exist, the key isn't stored, or the file can't be decrypted.
     */
    public static String read(String key) {
        synchronized (LOCK) {
            String plain = loadPlaintext();
            return plain == null ? null : parseKey(plain, key);
        }
    }

    /**
     * Writes or updates a single credential key in the encrypted file.
     * Other keys already in the file are preserved.
     */
    public static void write(String key, String value) {
        synchronized (LOCK) {
            String existing = loadPlaintext();
            String updated = setKey(existing == null ? "" : existing, key, value);
            try {
                saveBlob(updated);
                AppLogger.get().debug(TAG, "Credentials saved (key=" + key + ")");
            } catch (Exception e) {
                AppLogger.get().error(TAG, "Could not save credentials", e);
            }
        }
    }

    /** Deletes a specific key from the credential store. */
    public static void delete(String key) {
        synchronized (LOCK) {
            if (!Files.exists(credentialsFile())) return;
            String existing = loadPlaintext();
            if (existing == null) return;
            try {
                saveBlob(removeKey(existing, key));
            } catch (Exception e) {
                AppLogger.get().warn(TAG, "Could not delete credential key: " + e.getMessage());
            }
        }
    }

    // ── Load / save ─────────────────────────────────────────────────────────

    /** Decrypts the file (migrating a v1 file if needed). Null when absent or unrecoverable. */
    private static String loadPlaintext() {
        Path file = credentialsFile();
        if (!Files.exists(file)) return null;
        String blob;
        try {
            blob = Files.readString(file).trim();
        } catch (IOException e) {
            AppLogger.get().warn(TAG, "Could not read credentials file (will retry later): " + e.getMessage());
            return null; // transient I/O problem — never treat as "unreadable", never quarantine
        }
        if (blob.isEmpty()) return null;

        String password;
        try {
            password = currentPassword();
        } catch (IOException e) {
            // The key file couldn't be read right now (antivirus / indexer lock). That says nothing about the
            // saved sign-ins, so report "nothing yet" and leave credentials.dat exactly as it is.
            AppLogger.get().warn(TAG, "Could not read the credentials key (will retry later): " + e.getMessage());
            return null;
        }
        try {
            return decrypt(blob, password);
        } catch (Exception ignored) { /* not a v2 file — try the old machine-bound key */ }

        String legacy = decryptLegacy(blob);
        if (legacy != null) {
            AppLogger.get().info(TAG, "Migrated credentials to the stable per-install key.");
            try { saveBlob(legacy); } catch (Exception e) {
                AppLogger.get().warn(TAG, "Migration re-save failed (will retry next start): " + e.getMessage());
            }
            return legacy;
        }

        // Genuinely unreadable (corrupt, or created on another machine). Move it aside ONCE so we
        // don't burn time retrying on every start and so the next write starts a clean file.
        try {
            Path aside = file.resolveSibling(CREDENTIALS_FILE + ".unreadable-" + System.currentTimeMillis());
            Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
            AppLogger.get().warn(TAG, "Credentials file could not be decrypted; moved to " + aside.getFileName());
        } catch (Exception e) {
            AppLogger.get().warn(TAG, "Credentials file could not be decrypted: " + e.getMessage());
        }
        return null;
    }

    private static void saveBlob(String plaintext) throws Exception {
        Path file = credentialsFile();
        if (plaintext == null || plaintext.isBlank()) {
            Files.deleteIfExists(file);
            return;
        }
        String blob = encrypt(plaintext, currentPassword());
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(CREDENTIALS_FILE + ".tmp");
        Files.writeString(tmp, blob);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            // Windows can refuse an atomic replace while antivirus/indexers hold the file; a plain replace works.
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ── Encryption / Decryption ─────────────────────────────────────────────

    private static String encrypt(String plaintext, String password) throws Exception {
        byte[] salt = new byte[SALT_LEN];
        new SecureRandom().nextBytes(salt);
        SecretKey key = deriveKey(salt, password);

        byte[] iv = new byte[GCM_IV_LEN];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        ByteBuffer buf = ByteBuffer.allocate(GCM_IV_LEN + SALT_LEN + encrypted.length);
        buf.put(iv);
        buf.put(salt);
        buf.put(encrypted);
        return Base64.getEncoder().encodeToString(buf.array());
    }

    private static String decrypt(String base64, String password) throws Exception {
        byte[] data = Base64.getDecoder().decode(base64);
        ByteBuffer buf = ByteBuffer.wrap(data);

        byte[] iv   = new byte[GCM_IV_LEN];
        byte[] salt = new byte[SALT_LEN];
        buf.get(iv);
        buf.get(salt);
        byte[] encrypted = new byte[buf.remaining()];
        buf.get(encrypted);

        SecretKey key = deriveKey(salt, password);
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }

    private static SecretKey deriveKey(byte[] salt, String password) throws Exception {
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
    }

    // ── Stable (v2) key ─────────────────────────────────────────────────────

    /** Password derived from the random per-install secret in credentials.key (created on first use). */
    private static String currentPassword() throws IOException {
        String cached = cachedPassword;
        if (cached != null) return cached;
        Path keyFile = credentialsFile().resolveSibling(KEY_FILE);
        String secret = null;
        if (Files.exists(keyFile)) {
            secret = Files.readString(keyFile).trim();
        }
        if (secret == null || secret.length() < 32) {
            if (Files.exists(keyFile)) {
                // A damaged key file: keep a copy before replacing it so nothing is lost silently.
                try {
                    Files.copy(keyFile, keyFile.resolveSibling(KEY_FILE + ".bad-" + System.currentTimeMillis()),
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) { }
            }
            byte[] raw = new byte[32];
            new SecureRandom().nextBytes(raw);
            secret = HexFormat.of().formatHex(raw);
            Files.createDirectories(keyFile.getParent());
            Path tmpKey = keyFile.resolveSibling(KEY_FILE + ".tmp");
            Files.writeString(tmpKey, secret);
            try {
                Files.move(tmpKey, keyFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmpKey, keyFile, StandardCopyOption.REPLACE_EXISTING);
            }
            try { // best effort: owner-only
                java.io.File f = keyFile.toFile();
                f.setReadable(false, false); f.setReadable(true, true);
                f.setWritable(false, false); f.setWritable(true, true);
            } catch (Exception ignored) { }
        }
        cachedPassword = sha256Hex("averolauncher:v2:" + secret);
        return cachedPassword;
    }

    // ── Legacy (v1) machine-bound key, used only to migrate old files ───────

    /**
     * v1 derived the password from user name + hostname + the first non-loopback adapter's MAC.
     * Which adapter was "first" (and sometimes the hostname) varied between runs, so we try every
     * adapter and hostname spelling this machine has. Only runs for an old-format file.
     */
    private static String decryptLegacy(String blob) {
        try {
            String user = System.getProperty("user.name", "user");

            LinkedHashSet<String> hosts = new LinkedHashSet<>();
            try { hosts.add(java.net.InetAddress.getLocalHost().getHostName()); } catch (Exception ignored) { }
            hosts.add("localhost");
            for (String env : new String[]{"COMPUTERNAME", "HOSTNAME"}) {
                String v = System.getenv(env);
                if (v != null && !v.isBlank()) { hosts.add(v); hosts.add(v.toLowerCase()); }
            }

            LinkedHashSet<String> macs = new LinkedHashSet<>();
            try {
                Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
                if (ifaces != null) {
                    for (NetworkInterface iface : Collections.list(ifaces)) {
                        if (iface.isLoopback() || iface.isVirtual()) continue;
                        byte[] hw = iface.getHardwareAddress();
                        if (hw != null && hw.length == 6) {
                            StringBuilder sb = new StringBuilder();
                            for (byte b : hw) sb.append(String.format("%02x", b));
                            macs.add(sb.toString());
                        }
                    }
                }
            } catch (Exception ignored) { }
            macs.add("00:00:00:00:00:00"); // v1's "no adapter found" value

            int tried = 0;
            for (String h : hosts) {
                for (String m : macs) {
                    if (++tried > 80) return null; // safety cap; each try is one PBKDF2 run
                    String pw = sha256Hex("averolauncher:" + user + ":" + h + ":" + m);
                    try { return decrypt(blob, pw); } catch (Exception ignored) { }
                }
            }
        } catch (Exception e) {
            AppLogger.get().warn(TAG, "Legacy credential migration failed: " + e.getMessage());
        }
        return null;
    }

    private static String sha256Hex(String s) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── Key-value store helpers ─────────────────────────────────────────────

    /** Parses "key=value\n..." formatted plaintext and returns the value for `key`, or null. */
    private static String parseKey(String plaintext, String key) {
        for (String line : plaintext.split("\n")) {
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String k = line.substring(0, eq).trim();
            if (k.equals(key)) return line.substring(eq + 1);
        }
        return null;
    }

    /** Sets a key in "key=value\n..." formatted plaintext. Replaces existing or appends. */
    private static String setKey(String plaintext, String key, String value) {
        StringBuilder sb = new StringBuilder();
        boolean found = false;
        for (String line : plaintext.split("\n")) {
            int eq = line.indexOf('=');
            if (eq >= 0 && line.substring(0, eq).trim().equals(key)) {
                sb.append(key).append('=').append(value).append('\n');
                found = true;
            } else if (!line.isBlank()) {
                sb.append(line).append('\n');
            }
        }
        if (!found) sb.append(key).append('=').append(value).append('\n');
        return sb.toString();
    }

    /** Removes a key from "key=value\n..." formatted plaintext. */
    private static String removeKey(String plaintext, String key) {
        StringBuilder sb = new StringBuilder();
        for (String line : plaintext.split("\n")) {
            int eq = line.indexOf('=');
            if (eq >= 0 && line.substring(0, eq).trim().equals(key)) continue;
            if (!line.isBlank()) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static Path credentialsFile() {
        return PathResolver.launcherDataDir().resolve(CREDENTIALS_FILE);
    }
}
