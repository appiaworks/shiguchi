package dev.appiaworks.shiguchi.security;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import org.springframework.security.core.Authentication;

/**
 * Details of a caller authenticated by BFF session cookie, wrapping whatever details the
 * authenticator set. It lives in -rest so code that sees the caller but not the BFF module can
 * read it: push (G4) binds sockets to the {@link #handle() session handle}, and the CSRF check
 * (G3) matches the request's {@code Origin} against the {@link #application() application}.
 *
 * @param details the authenticator's own details, or {@code null}
 * @param handle the session store key, or {@code null} when a third-party authenticator produced
 *     the caller and the repository wrapped it
 * @param application the application id the session was created for, or {@code null} as for
 *     {@code handle}
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.13.0")
public record CookieAuthenticated(Object details, String handle, String application) {

    /**
     * Whether the caller was authenticated by session cookie.
     *
     * @param auth the caller, may be {@code null}
     * @return {@code true} for a cookie-authenticated caller
     */
    public static boolean isCookieAuthenticated(Authentication auth) {
        return auth != null && auth.getDetails() instanceof CookieAuthenticated;
    }

    /**
     * @param auth the caller, may be {@code null}
     * @return the session store key, or {@code null} when not cookie-authenticated or unknown
     */
    public static String handle(Authentication auth) {
        return auth != null && auth.getDetails() instanceof CookieAuthenticated c ? c.handle() : null;
    }

    /**
     * @param auth the caller, may be {@code null}
     * @return the application id, or {@code null} when not cookie-authenticated or unknown
     */
    public static String application(Authentication auth) {
        return auth != null && auth.getDetails() instanceof CookieAuthenticated c ? c.application() : null;
    }
}
