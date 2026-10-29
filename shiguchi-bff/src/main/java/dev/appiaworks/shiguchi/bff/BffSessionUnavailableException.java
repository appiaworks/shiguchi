package dev.appiaworks.shiguchi.bff;

import dev.appiaworks.protobuf.extras.api.ApiStatus;

/**
 * A {@link BffSessionAuthenticator} fails with this when the caller has a session but the gateway
 * cannot tell right now whether it is still good: its access token has expired and the issuer
 * could not be reached to refresh it. The gateway answers {@code 503} with {@code Retry-After: 1}.
 * Returning an empty {@code Mono} instead would sign the user out (a {@code 401}) or, on an open
 * route, send the request on as anonymous.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.14.0")
public final class BffSessionUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** @param message why the session could not be used */
    public BffSessionUnavailableException(String message) {
        super(message, null, false, false);
    }
}
