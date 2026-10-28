package dev.appiaworks.shiguchi.bff.session;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.time.Instant;

/**
 * A sign-in in flight, from the redirect to the issuer until the callback. Single use: the
 * callback takes it with {@link BffSessionStore#takeLogin}.
 *
 * @param state the {@code state} sent to the issuer; also the store key and the login cookie value
 * @param codeVerifier the PKCE verifier
 * @param nonce the OIDC nonce
 * @param provider the gateway provider id
 * @param application the application resolved at login start (the callback has no {@code Origin})
 * @param returnTo where to send the browser when sign-in ends
 * @param redirectUri the exact {@code redirect_uri} sent to the issuer, reused at the callback
 * @param created when the login started
 * @param maxAge the {@code max_age} (seconds) sent to the issuer, or {@code null}: the callback then checks {@code auth_time}
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.13.0")
public record BffLogin(
        String state,
        String codeVerifier,
        String nonce,
        String provider,
        String application,
        String returnTo,
        String redirectUri,
        Instant created,
        Long maxAge) {

    /** A login that sent no {@code max_age}. */
    public BffLogin(
            String state,
            String codeVerifier,
            String nonce,
            String provider,
            String application,
            String returnTo,
            String redirectUri,
            Instant created) {
        this(state, codeVerifier, nonce, provider, application, returnTo, redirectUri, created, null);
    }
}
