package dev.appiaworks.shiguchi.bff.session;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Random values and derivations for sign-in: the cookie value, the store key derived from it, and
 * the {@code state}, nonce and PKCE pair. All random values are 32 bytes from {@link SecureRandom}
 * in unpadded Base64url.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.13.0")
public final class BffSessionKeys {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private BffSessionKeys() {}

    /** @return a fresh session cookie value */
    public static String newCookieValue() {
        return random();
    }

    /**
     * @param cookieValue the session cookie value
     * @return the store key, Base64url(SHA-256(value)): a leaked store does not yield usable cookies
     */
    public static String key(String cookieValue) {
        return B64.encodeToString(sha256(cookieValue));
    }

    /** @return a fresh OAuth {@code state} */
    public static String newState() {
        return random();
    }

    /** @return a fresh OIDC nonce */
    public static String newNonce() {
        return random();
    }

    /** @return a fresh PKCE code verifier (43 characters) */
    public static String newCodeVerifier() {
        return random();
    }

    /**
     * @param verifier the PKCE code verifier
     * @return the S256 code challenge, Base64url(SHA-256(verifier))
     */
    public static String challenge(String verifier) {
        return B64.encodeToString(sha256(verifier));
    }

    private static String random() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return B64.encodeToString(bytes);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
