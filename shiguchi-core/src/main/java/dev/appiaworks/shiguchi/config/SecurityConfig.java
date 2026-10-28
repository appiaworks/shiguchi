package dev.appiaworks.shiguchi.config;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The gateway's authentication posture: a default mode for every surface, and per-surface
 * overrides for the cases where one surface should differ.
 *
 * <p>The default is {@code PERMIT_ALL} so a first run works without an identity provider. It is
 * not a deployment posture — in particular the web console is an RPC client holding the
 * gateway's credentials, so {@code surfaces.ui.require-auth} exists to lock that one surface
 * down even while the rest stays open.
 */
@Data
@NoArgsConstructor
public class SecurityConfig {
    /**
     * Default security posture of the gateway:
     * <ul>
     *   <li>{@code PERMIT_ALL} — all exchanges are permitted at the transport layer
     *       (the gateway boots and serves with no identity provider). Dangerous
     *       surfaces (the gRPC UI invoke API and the gateway-refresh actuator) are
     *       still individually protected when their own flags require it. A loud WARN
     *       is logged at startup so the open posture is never silent.</li>
     *   <li>{@code AUTHENTICATED} — every exchange requires an authenticated principal,
     *       wired as an OAuth2 JWT resource server. Requires
     *       {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} (or a custom
     *       {@code ReactiveJwtDecoder} bean); the application fails fast at startup if
     *       neither is present, rather than silently returning 401 for every request.</li>
     * </ul>
     * Either way, defining your own {@code SecurityWebFilterChain} bean fully overrides
     * this configuration.
     */
    private Mode mode = Mode.PERMIT_ALL;

    /**
     * Per-surface overrides of the mode's default posture. Lets browser-facing surfaces
     * (UI console, GraphiQL, Swagger) require auth while machine-facing REST stays open
     * behind an authenticating ingress — or the reverse.
     */
    private SurfacesConfig surfaces = new SurfacesConfig();

    /**
     * When the transcoded REST routes are open ({@code rest.require-auth} false), keep them out of
     * Spring Security's filter chain altogether: the chain then matches only the paths it still
     * gates or serves — the always-authenticated actuator endpoints, the browser surfaces, the
     * CORS paths — and a REST request skips its header writer, CORS, context, exception and
     * authorization filters. Measured at ≈55 µs of a ≈420 µs request on loopback. The routes lose
     * Spring Security's default response headers ({@code X-Content-Type-Options},
     * {@code Cache-Control: no-store}, …) and its request firewall; off by default.
     */
    private boolean openRestBypassesChain = false;

    /**
     * How often a gRPC-Web server stream or a GraphQL subscription opened with the BFF session
     * cookie checks that its session is still active: the longest it outlives a logout on another
     * instance (a logout here ends it at once). Push has its own,
     * {@code shiguchi.cursus.session-check-interval}. At least 5 s.
     */
    private java.time.Duration sessionCheckInterval = java.time.Duration.ofSeconds(30);

    /**
     * End a gRPC-Web or REST server stream (NDJSON, SSE, download) or a GraphQL subscription of a
     * bearer (JWT) caller when the token expires, with gRPC status UNAUTHENTICATED "token expired"
     * (an UNAUTHORIZED GraphQL error); a new operation on a bearer GraphQL WebSocket past the expiry
     * is refused likewise. The client reconnects with a fresh token. A stream opened with the BFF
     * session cookie is never ended by this: it follows its session. A token accepted within the JWT
     * clock skew ends its stream at once. {@code false} lets a stream outlive its token, until the
     * client or the backend closes it.
     */
    private boolean endStreamsAtTokenExpiry = true;

    public enum Mode {
        PERMIT_ALL,
        AUTHENTICATED
    }

    @Data
    @NoArgsConstructor
    public static class SurfacesConfig {
        /** gRPC web console + GraphiQL ({@code <ui.path>/**}, {@code /graphiql}). */
        private SurfaceRule ui = new SurfaceRule();
        /** {@code /graphql}. */
        private SurfaceRule graphql = new SurfaceRule();
        /** Swagger UI + {@code /v3/api-docs/**}. */
        private SurfaceRule openapi = new SurfaceRule();
        /** Everything else — the transcoded REST routes. */
        private SurfaceRule rest = new SurfaceRule();
    }

    @Data
    @NoArgsConstructor
    public static class SurfaceRule {
        /** Null = inherit the mode's default (PERMIT_ALL → open, AUTHENTICATED → JWT required). */
        private Boolean requireAuth;

        public boolean effective(boolean modeDefault) {
            return requireAuth != null ? requireAuth : modeDefault;
        }
    }
}
