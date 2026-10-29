package dev.appiaworks.shiguchi.security;

import dev.appiaworks.protobuf.extras.api.ApiStatus;

/**
 * A Spring application event published on this instance when a browser session ends (logout, a
 * refused token refresh, an expired session deleted). Push closes the local connections bound to
 * the session at once; other instances learn from {@link CookieSessions#active(String)}.
 *
 * @param handle the {@link CookieAuthenticated#handle() session handle} of the ended session
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.14.0")
public record CookieSessionEnded(String handle) {}
