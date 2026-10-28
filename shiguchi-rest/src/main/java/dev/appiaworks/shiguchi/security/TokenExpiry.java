package dev.appiaworks.shiguchi.security;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.time.Instant;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * When the bearer token of a caller expires. A long-lived stream of a bearer caller ends then
 * (gRPC-Web and REST server streams, GraphQL subscriptions); a cookie caller's stream follows its BFF session instead
 * ({@link CookieSessionWatch}) and is never bound to the access token it happens to carry.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.13.0")
public final class TokenExpiry {

    /** The reason a stream gives when its token expired. */
    public static final String REASON = "token expired";

    private TokenExpiry() {}

    /**
     * @param auth the caller, may be {@code null}
     * @return when the caller's JWT expires, or {@code null} when it carries no expiry (or is not
     *     a JWT)
     */
    public static Instant expiry(Authentication auth) {
        if (auth == null) return null;
        Object principal = auth.getPrincipal();
        Jwt jwt = principal instanceof Jwt j ? j : auth.getCredentials() instanceof Jwt j2 ? j2 : null;
        return jwt == null ? null : jwt.getExpiresAt();
    }
}
