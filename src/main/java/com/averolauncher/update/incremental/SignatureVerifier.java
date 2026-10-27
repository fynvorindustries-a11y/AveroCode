package com.averolauncher.update.incremental;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;

/** Ed25519 verification of the detached manifest signature (java.security, JDK 15+). */
public final class SignatureVerifier {

    private SignatureVerifier() {}

    public static PublicKey parsePublicKey(String base64X509) throws Exception {
        byte[] der = Base64.getDecoder().decode(base64X509.trim());
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
    }

    /** The .sig file is the base64 of the raw 64-byte Ed25519 signature over the exact manifest bytes. */
    public static byte[] decodeSignature(byte[] sigFile) {
        try {
            byte[] sig = Base64.getMimeDecoder().decode(new String(sigFile, StandardCharsets.US_ASCII).trim());
            return sig.length == 64 ? sig : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static boolean verify(byte[] data, byte[] signature, PublicKey key) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(key);
            s.update(data);
            return s.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * @return the id of the trusted key that signed {@code manifestBytes}
     * @throws SecurityException if the signature is malformed or no trusted key verifies it
     */
    public static String verifyAgainstAny(byte[] manifestBytes, byte[] sigFile, Map<String, PublicKey> trusted) {
        byte[] sig = decodeSignature(sigFile);
        if (sig == null) throw new SecurityException("Malformed manifest signature");
        for (Map.Entry<String, PublicKey> e : trusted.entrySet()) {
            if (verify(manifestBytes, sig, e.getValue())) return e.getKey();
        }
        throw new SecurityException("Manifest signature is not valid for any trusted key");
    }
}
