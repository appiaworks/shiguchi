package dev.appiaworks.shiguchi.bff.session;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A signed-in browser session, stored under {@code key} (never the cookie value: see
 * {@link BffSessionKeys#key}). Tokens are stored as the issuer sent them.
 *
 * @param key the store key, Base64url(SHA-256(cookie value))
 * @param subject the issuer's {@code sub}
 * @param provider the gateway provider id the session was created through
 * @param application the application id recorded at sign-in
 * @param sid the issuer's session id from the ID token, or {@code null}
 * @param name display name claim, or {@code null}
 * @param email email claim, or {@code null}
 * @param picture picture claim, or {@code null}
 * @param accessToken the access token sent upstream
 * @param accessTokenExpiresAt when the access token expires
 * @param refreshToken the refresh token, or {@code null}
 * @param idToken the ID token, kept for the end-session {@code id_token_hint}
 * @param created when the session was created; the absolute timeout counts from here
 * @param lastSeen when the session was last used; the idle timeout counts from here
 * @param version bumped by writers that replace the session (compare-and-set in later slices)
 * @param acr the authentication context class the issuer reported ({@code acr}), or {@code null}
 * @param amr the authentication methods the issuer reported ({@code amr}); empty when none
 * @param authTime when the user last authenticated at the issuer ({@code auth_time}), or {@code null}
 * @param claims the configured ID-token claims beyond name, email and picture (JSON-like values: strings,
 *     numbers, booleans, lists, flat maps); empty when none
 * @param userAgent the browser's {@code User-Agent} at sign-in, truncated, or {@code null}
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.14.0")
public record BffSession(
        String key,
        String subject,
        String provider,
        String application,
        String sid,
        String name,
        String email,
        String picture,
        String accessToken,
        Instant accessTokenExpiresAt,
        String refreshToken,
        String idToken,
        Instant created,
        Instant lastSeen,
        long version,
        String acr,
        List<String> amr,
        Instant authTime,
        Map<String, Object> claims,
        String userAgent) {

    public BffSession {
        amr = amr == null ? List.of() : List.copyOf(amr);
        claims = claims == null || claims.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(claims));
    }

    /** A session without session claims and user agent. */
    public BffSession(
            String key,
            String subject,
            String provider,
            String application,
            String sid,
            String name,
            String email,
            String picture,
            String accessToken,
            Instant accessTokenExpiresAt,
            String refreshToken,
            String idToken,
            Instant created,
            Instant lastSeen,
            long version,
            String acr,
            List<String> amr,
            Instant authTime) {
        this(
                key,
                subject,
                provider,
                application,
                sid,
                name,
                email,
                picture,
                accessToken,
                accessTokenExpiresAt,
                refreshToken,
                idToken,
                created,
                lastSeen,
                version,
                acr,
                amr,
                authTime,
                Map.of(),
                null);
    }

    /** A session without authentication context (no {@code acr}, {@code amr} or {@code auth_time}). */
    public BffSession(
            String key,
            String subject,
            String provider,
            String application,
            String sid,
            String name,
            String email,
            String picture,
            String accessToken,
            Instant accessTokenExpiresAt,
            String refreshToken,
            String idToken,
            Instant created,
            Instant lastSeen,
            long version) {
        this(
                key,
                subject,
                provider,
                application,
                sid,
                name,
                email,
                picture,
                accessToken,
                accessTokenExpiresAt,
                refreshToken,
                idToken,
                created,
                lastSeen,
                version,
                null,
                List.of(),
                null,
                Map.of(),
                null);
    }

    /**
     * @param lastSeen the new last-seen instant
     * @return a copy with {@code lastSeen} replaced
     */
    public BffSession withLastSeen(Instant lastSeen) {
        return new BffSession(
                key,
                subject,
                provider,
                application,
                sid,
                name,
                email,
                picture,
                accessToken,
                accessTokenExpiresAt,
                refreshToken,
                idToken,
                created,
                lastSeen,
                version,
                acr,
                amr,
                authTime,
                claims,
                userAgent);
    }
}
