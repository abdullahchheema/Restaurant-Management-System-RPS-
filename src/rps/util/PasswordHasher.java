package rps.util;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Salted PBKDF2-HMAC-SHA256 password hashing, using only what ships with the JDK — the
 * same reason B2Client hand-rolls its REST calls instead of pulling in an SDK.
 *
 * <p>Staff passwords used to be stored and compared as plaintext. That is a materially
 * different risk here than in a typical local-only app, because the nightly database dump
 * is uploaded to Backblaze B2 — plaintext credentials would leave the premises on every
 * backup, and staff commonly reuse passwords elsewhere. Hashing means a leaked dump no
 * longer discloses anything usable.
 *
 * <p>Stored format is self-describing so the iteration count can be raised later without
 * invalidating existing hashes: {@code pbkdf2$<iterations>$<salt-b64>$<hash-b64>}.
 */
public final class PasswordHasher {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String PREFIX = "pbkdf2$";
    private static final int ITERATIONS = 210_000;   // OWASP-recommended range for PBKDF2-SHA256
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {}

    /** True if the stored value is already hashed (vs. a legacy plaintext password). */
    public static boolean isHashed(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    public static String hash(String plaintext) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] key = derive(plaintext, salt, ITERATIONS);
        Base64.Encoder enc = Base64.getEncoder().withoutPadding();
        return PREFIX + ITERATIONS + "$" + enc.encodeToString(salt) + "$" + enc.encodeToString(key);
    }

    /**
     * Verifies a candidate password. Accepts a legacy plaintext row so an existing install
     * keeps working if the rehash migration hasn't run for some reason, but that path is
     * only a fallback — migration 12 rewrites every row on startup.
     */
    public static boolean verify(String candidate, String stored) {
        if (candidate == null || stored == null) return false;
        if (!isHashed(stored)) {
            // Legacy plaintext row: still constant-time, still correct.
            return MessageDigest.isEqual(candidate.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                stored.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4) return false;
        int iterations;
        try {
            iterations = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return false;
        }
        Base64.Decoder dec = Base64.getDecoder();
        byte[] salt;
        byte[] expected;
        try {
            salt = dec.decode(parts[2]);
            expected = dec.decode(parts[3]);
        } catch (IllegalArgumentException e) {
            return false;
        }
        byte[] actual = derive(candidate, salt, iterations);
        return MessageDigest.isEqual(expected, actual);
    }

    private static byte[] derive(String plaintext, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(plaintext.toCharArray(), salt, iterations, KEY_BITS);
            try {
                return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            // Both indicate a broken JRE rather than bad input -- failing loudly is correct,
            // because silently returning a constant here would make every password match.
            throw new IllegalStateException("Password hashing unavailable: " + e.getMessage(), e);
        }
    }
}
