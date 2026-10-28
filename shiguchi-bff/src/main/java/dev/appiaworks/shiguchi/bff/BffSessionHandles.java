package dev.appiaworks.shiguchi.bff;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The opaque handle the session listing gives a browser for one of its user's sessions:
 * {@code Base64url(HMAC-SHA256(k, store key))}. The store key is itself only a hash of the cookie, but
 * it is what push, the admin endpoint and the store index by, so it is never shown. {@code k} is derived
 * from the active session-encryption key (label {@code bff-session-handle}), so handles are stable across
 * restarts and instances that share the key; without encryption keys (the {@code MEMORY} store, or
 * encryption disabled) it is random per boot and handles change on restart. Rotating the active
 * key changes the handles too: a handle is good for the listing it came from, not for storing.
 */
final class BffSessionHandles {

    static final String LABEL = "bff-session-handle";

    private final byte[] key;

    BffSessionHandles(byte[] key) {
        this.key = key.clone();
    }

    /** Handles keyed from the active encryption key of these settings, or random when there are none. */
    static BffSessionHandles from(BffProperties.Session session) {
        BffTokenCipher cipher = BffTokenCipher.from(session);
        if (cipher != null) return new BffSessionHandles(cipher.derive(LABEL));
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        return new BffSessionHandles(random);
    }

    String handle(String storeKey) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(mac.doFinal(storeKey.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("could not compute a session handle", e);
        }
    }

    boolean matches(String handle, String storeKey) {
        return handle != null
                && MessageDigest.isEqual(
                        handle.getBytes(StandardCharsets.UTF_8),
                        handle(storeKey).getBytes(StandardCharsets.UTF_8));
    }
}
