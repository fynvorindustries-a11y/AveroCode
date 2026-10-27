package com.averolauncher.update.incremental.tool;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

/**
 * Generates an Ed25519 signing key pair for update manifests. JDK-only, so it runs without building anything:
 * <pre>  java KeyTool.java generate k1 ./keys</pre>
 * Writes {@code ./keys/k1.private} (base64 PKCS#8 — goes into the GitHub secret UPDATE_SIGNING_KEY, never into the
 * repo or the launcher) and prints the public half as a JSON entry for {@code trusted-keys.json}.
 */
public final class KeyTool {

    private KeyTool() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !args[0].equals("generate") || !args[1].matches("[A-Za-z0-9._-]{1,32}")) {
            System.err.println("usage: java KeyTool.java generate <key-id> <output-dir>");
            System.exit(2);
        }
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String priv = Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
        String pub = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
        Path dir = Path.of(args[2]);
        Files.createDirectories(dir);
        Path privFile = dir.resolve(args[1] + ".private");
        if (Files.exists(privFile)) throw new IllegalStateException(privFile + " already exists; refusing to overwrite");
        Files.writeString(privFile, priv + "\n", StandardCharsets.US_ASCII);
        System.out.println("Private key written to " + privFile.toAbsolutePath());
        System.out.println("  -> store its contents in the GitHub secret UPDATE_SIGNING_KEY, then delete/secure the file.");
        System.out.println();
        System.out.println("Public key entry for src/main/resources/updater/trusted-keys.json:");
        System.out.println("{\"keys\":[{\"id\":\"" + args[1] + "\",\"publicKey\":\"" + pub + "\"}]}");
    }
}
