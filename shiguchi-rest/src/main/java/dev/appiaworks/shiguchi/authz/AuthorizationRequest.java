package dev.appiaworks.shiguchi.authz;

import dev.appiaworks.shiguchi.routing.RouteDefinition;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.security.core.Authentication;

/**
 * Everything a {@link GatewayAuthorizer} may need to reach a decision, assembled by the
 * calling surface (REST or GraphQL) before the backend RPC is dispatched.
 *
 * <p>The scope-based default authorizer only reads {@link #route()} and {@link #authentication()}.
 * The remaining members exist for per-object authorizers (e.g. OpenFGA/Zanzibar-style ReBAC),
 * which need to resolve a concrete object id out of the request before they can ask
 * "may this user perform this relation on <em>that</em> object?".
 *
 * <p>{@code arguments} is the surface-normalised view of the request inputs:
 * <ul>
 *   <li>REST — path variables merged with query parameters (values are {@code String}).</li>
 *   <li>GraphQL — the resolved field arguments (values may be nested maps/lists).</li>
 * </ul>
 * Keys are the names as they appear on that surface; no proto field mapping is applied.
 *
 * <p>{@code body} is the raw request payload and is only populated for REST. It is
 * {@code null} on the GraphQL surface, where the equivalent data is already in
 * {@code arguments}. Authorizers that need it must null-check.
 *
 * @param route the matched route being authorized
 * @param authentication the caller, never null — an unauthenticated caller is represented
 *     by a token whose {@code isAuthenticated()} returns false rather than by null
 * @param arguments normalised request inputs, never null (empty when there are none)
 * @param body raw REST request body, or null on non-REST surfaces
 * @param headers the request headers, case-insensitive and read-only, with {@code authorization}
 *     and {@code cookie} removed; empty when the surface supplies none
 * @param surface the surface the request arrived on, or null when the caller did not say
 */
public record AuthorizationRequest(
        RouteDefinition route,
        Authentication authentication,
        Map<String, Object> arguments,
        String body,
        Map<String, List<String>> headers,
        Surface surface) {

    /** The surface a request arrived on. */
    public enum Surface {
        REST,
        GRPC_WEB,
        GRAPHQL,
        GRPC
    }

    /** Headers never exposed to authorizers or policies. */
    private static final Set<String> REDACTED = Set.of("authorization", "cookie");

    public AuthorizationRequest {
        arguments = arguments != null ? Map.copyOf(arguments) : Map.of();
        headers = redact(headers);
    }

    /** Compatibility constructor: no headers, surface unknown ({@code null}). */
    public AuthorizationRequest(
            RouteDefinition route, Authentication authentication, Map<String, Object> arguments, String body) {
        this(route, authentication, arguments, body, Map.of(), null);
    }

    /**
     * A read-only, case-insensitive copy of {@code headers} without {@code authorization} and
     * {@code cookie}.
     */
    private static Map<String, List<String>> redact(Map<String, List<String>> headers) {
        if (headers == null || headers.isEmpty()) return Collections.emptyMap();
        Map<String, List<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.forEach((name, values) -> {
            if (name != null && !REDACTED.contains(name.toLowerCase(Locale.ROOT))) {
                copy.put(name, values == null ? List.of() : List.copyOf(values));
            }
        });
        return Collections.unmodifiableMap(copy);
    }

    /** Convenience factory for surfaces that carry no body (GraphQL). */
    public static AuthorizationRequest of(
            RouteDefinition route, Authentication authentication, Map<String, Object> arguments) {
        return new AuthorizationRequest(route, authentication, arguments, null);
    }
}
