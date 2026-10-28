package dev.appiaworks.shiguchi.authz;

import dev.appiaworks.shiguchi.routing.RouteDefinition;
import java.util.Map;
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
 */
public record AuthorizationRequest(
        RouteDefinition route, Authentication authentication, Map<String, Object> arguments, String body) {

    public AuthorizationRequest {
        arguments = arguments != null ? Map.copyOf(arguments) : Map.of();
    }

    /** Convenience factory for surfaces that carry no body (GraphQL). */
    public static AuthorizationRequest of(
            RouteDefinition route, Authentication authentication, Map<String, Object> arguments) {
        return new AuthorizationRequest(route, authentication, arguments, null);
    }
}
