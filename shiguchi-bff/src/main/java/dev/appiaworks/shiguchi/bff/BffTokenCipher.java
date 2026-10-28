package dev.appiaworks.shiguchi.bff;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts one stored token: AES-256-GCM, a random 12-byte IV per value, and the session key plus
 * the field name as additional authenticated data, so a ciphertext cannot be moved to another row
 * or field. The stored form is {@code v1.<kid>.<base64url-no-padding(iv | ciphertext | tag)>}.
 * {@code null} and the empty string are not encrypted.
 */
final class BffTokenCipher {

    static final String PREFIX = "v1.";

    static final Pattern KID = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    /** Why a value did not decrypt. Never carries the value. */
    enum Failure {
        NOT_ENCRYPTED("the value has no v1 prefix (a plaintext or older row)"),
        MALFORMED("the value is not a well-formed v1 ciphertext"),
        UNKNOWN_KEY("the value was written with a key id that is not configured"),
        REJECTED("authentication failed (wrong key, tampered value, or moved to another row or field)");

        private final String text;

        Failure(String text) {
            this.text = text;
        }

        String text() {
            return text;
        }
    }

    /** A value that does not decrypt. The message is the reason and, where known, the key id. */
    static final class DecryptionException extends RuntimeException {

        private final transient Failure failure;

        DecryptionException(Failure failure, String kid) {
            super(failure.text() + (kid == null ? "" : " (key id " + kid + ")"), null, false, false);
            this.failure = failure;
        }

        Failure failure() {
            return failure;
        }
    }

    private final Map<String, SecretKeySpec> keys;
    private final String activeKid;
    private final SecretKeySpec active;
    private final SecureRandom random = new SecureRandom();

    BffTokenCipher(Map<String, byte[]> rawKeys, String activeKid) {
        Map<String, SecretKeySpec> built = new LinkedHashMap<>();
        rawKeys.forEach((kid, bytes) -> built.put(kid, new SecretKeySpec(bytes, "AES")));
        this.keys = Map.copyOf(built);
        this.activeKid = activeKid;
        this.active = keys.get(activeKid);
        if (active == null) throw new IllegalArgumentException("active key id is not among the keys");
    }

    /**
     * The cipher the session settings call for, or {@code null} when the store is not encrypted:
     * {@code MEMORY}, or {@code disabled}.
     *
     * @throws IllegalStateException when the settings are invalid
     */
    static BffTokenCipher from(BffProperties.Session session) {
        if (!enabled(session)) return null;
        List<String> problems = problems(session);
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "Invalid shiguchi.bff.session.encryption:\n  - " + String.join("\n  - ", problems));
        }
        return new BffTokenCipher(decodeKeys(session.getEncryption().getKeys()), activeKid(session));
    }

    /** Whether tokens are encrypted for these settings. */
    static boolean enabled(BffProperties.Session session) {
        return session.getStore() != BffProperties.SessionStore.MEMORY
                && !session.getEncryption().isDisabled();
    }

    /** One line per problem; empty when the settings are fine or encryption does not apply. Never names a key value. */
    static List<String> problems(BffProperties.Session session) {
        List<String> problems = new ArrayList<>();
        if (!enabled(session)) return problems;
        BffProperties.Encryption encryption = session.getEncryption();
        Map<String, String> keys = encryption.getKeys();
        if (keys == null || keys.isEmpty()) {
            problems.add("session.store is " + session.getStore() + ": set session.encryption.keys and"
                    + " session.encryption.active, or session.encryption.disabled: true to accept plaintext tokens");
            return problems;
        }
        keys.forEach((kid, value) -> {
            if (kid == null || !KID.matcher(kid).matches()) {
                problems.add("session.encryption.keys: key id '" + kid + "' must match [A-Za-z0-9_-]{1,32}");
                return;
            }
            byte[] bytes = decode(value);
            if (bytes == null || bytes.length != KEY_BYTES) {
                problems.add("session.encryption.keys." + kid + " must be Base64 of exactly " + KEY_BYTES + " bytes");
            }
        });
        String activeKid = activeKid(session);
        if (activeKid.isEmpty()) {
            problems.add("session.encryption.active is required: name the key id that encrypts new writes");
        } else if (!keys.containsKey(activeKid)) {
            problems.add("session.encryption.active '" + activeKid + "' is not one of session.encryption.keys");
        }
        return problems;
    }

    private static String activeKid(BffProperties.Session session) {
        String active = session.getEncryption().getActive();
        return active == null ? "" : active.trim();
    }

    private static Map<String, byte[]> decodeKeys(Map<String, String> keys) {
        Map<String, byte[]> decoded = new LinkedHashMap<>();
        keys.forEach((kid, value) -> decoded.put(kid, decode(value)));
        return decoded;
    }

    /** Standard or URL-safe Base64, with or without padding; {@code null} when it is neither. */
    private static byte[] decode(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        try {
            return Base64.getDecoder().decode(trimmed);
        } catch (IllegalArgumentException standard) {
            try {
                return Base64.getUrlDecoder().decode(trimmed);
            } catch (IllegalArgumentException urlSafe) {
                return null;
            }
        }
    }

    String encrypt(String sessionKey, String field, String plain) {
        if (plain == null || plain.isEmpty()) return plain;
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, active, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(sessionKey, field));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV_BYTES + sealed.length];
            System.arraycopy(iv, 0, out, 0, IV_BYTES);
            System.arraycopy(sealed, 0, out, IV_BYTES, sealed.length);
            return PREFIX + activeKid + "."
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("could not encrypt a session token", e);
        }
    }

    /**
     * @throws DecryptionException when the value is not a ciphertext this cipher can open for that
     *     session key and field
     */
    String decrypt(String sessionKey, String field, String stored) {
        if (stored == null || stored.isEmpty()) return stored;
        if (!stored.startsWith(PREFIX)) throw new DecryptionException(Failure.NOT_ENCRYPTED, null);
        int dot = stored.indexOf('.', PREFIX.length());
        if (dot < 0) throw new DecryptionException(Failure.MALFORMED, null);
        String kid = stored.substring(PREFIX.length(), dot);
        if (!KID.matcher(kid).matches()) throw new DecryptionException(Failure.MALFORMED, null);
        SecretKeySpec key = keys.get(kid);
        if (key == null) throw new DecryptionException(Failure.UNKNOWN_KEY, kid);
        byte[] raw;
        try {
            raw = Base64.getUrlDecoder().decode(stored.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            throw new DecryptionException(Failure.MALFORMED, kid);
        }
        if (raw.length < IV_BYTES + TAG_BITS / 8) throw new DecryptionException(Failure.MALFORMED, kid);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES));
            cipher.updateAAD(aad(sessionKey, field));
            return new String(cipher.doFinal(raw, IV_BYTES, raw.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new DecryptionException(Failure.REJECTED, kid);
        }
    }

    /**
     * A 32-byte key for one purpose, derived from the active key with HKDF-SHA256 (RFC 5869, no salt, the label as
     * {@code info}), so the purpose never sees the encryption key itself and no two labels share a key.
     */
    byte[] derive(String label) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
            byte[] prk = mac.doFinal(active.getEncoded());
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            mac.update(label.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 1);
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("could not derive a key", e);
        }
    }

    private static byte[] aad(String sessionKey, String field) {
        return (sessionKey + "\0" + field).getBytes(StandardCharsets.UTF_8);
    }
}
