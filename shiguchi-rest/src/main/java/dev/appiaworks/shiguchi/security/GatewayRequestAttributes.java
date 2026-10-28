package dev.appiaworks.shiguchi.security;

import dev.appiaworks.protobuf.extras.api.ApiStatus;

/** Exchange attributes the gateway's security components set on a request. */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.13.0")
public final class GatewayRequestAttributes {

    /**
     * How the caller was authenticated, when not by an {@code Authorization} header: {@value
     * #COOKIE} for a session cookie. Absent for a bearer or anonymous caller.
     */
    public static final String AUTHENTICATED_BY = "dev.appiaworks.shiguchi.security.authenticatedBy";

    /** The {@link #AUTHENTICATED_BY} value for a caller authenticated by a session cookie. */
    public static final String COOKIE = "cookie";

    private GatewayRequestAttributes() {}
}
