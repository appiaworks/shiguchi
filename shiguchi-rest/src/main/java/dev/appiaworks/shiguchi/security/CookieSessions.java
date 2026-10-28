package dev.appiaworks.shiguchi.security;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import reactor.core.publisher.Mono;

/**
 * Lets code that does not know the BFF module ask whether a browser session is still alive: push
 * (G4) polls it for every connection opened with the session cookie, so a socket does not outlive
 * a logout on another instance. The BFF module provides the bean.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.13.0")
public interface CookieSessions {

    /**
     * Is that session still active? Cheap: one store read, no token refresh, no activity touch. A
     * session past its absolute or idle timeout is not active. An error means the store could not be
     * asked, which is not the same as the session having ended.
     *
     * @param handle {@link CookieAuthenticated#handle() the session handle} of a cookie caller
     * @return {@code true} while the session exists and has not expired
     */
    Mono<Boolean> active(String handle);
}
