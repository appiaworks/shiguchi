package dev.appiaworks.shiguchi.gateway;

import dev.appiaworks.shiguchi.authz.AuthorizationRequest;
import dev.appiaworks.shiguchi.authz.GatewayAuthorizer;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import java.util.Map;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Resolves the current caller from the reactive {@link SecurityContext} and hands the decision to
 * the configured {@link GatewayAuthorizer}.
 *
 * <p>This type is the single call site the REST and GraphQL surfaces use; it exists so that
 * reading the security context — the one piece every authorizer needs and none should have to
 * repeat — happens in exactly one place. The policy itself lives in the {@code GatewayAuthorizer}
 * bean, which defaults to {@code ScopeGatewayAuthorizer} and can be replaced to add per-object
 * authorization.
 *
 * <p>A denial surfaces as {@link org.springframework.security.access.AccessDeniedException}, which
 * Spring Security's {@code ServerAccessDeniedHandler} translates to HTTP 403.
 */
@Component
public class GatewayAuthorizationEnforcer {

    /** Sentinel for "no security context present" — isAuthenticated() returns false. */
    private static final Authentication NO_AUTH = new UsernamePasswordAuthenticationToken("anon", null);

    private final GatewayAuthorizer authorizer;

    public GatewayAuthorizationEnforcer(GatewayAuthorizer authorizer) {
        this.authorizer = authorizer;
    }

    /**
     * Authorize a route with no request inputs attached.
     *
     * <p>Sufficient for the default scope check. Per-object authorizers need the inputs, so
     * surfaces should prefer {@link #enforce(RouteDefinition, Map, String)}.
     */
    public Mono<Void> enforce(RouteDefinition route) {
        return enforce(route, Map.of(), null);
    }

    /**
     * Authorize a route together with the request inputs a per-object authorizer needs.
     *
     * @param route the matched route
     * @param arguments normalised request inputs (REST: path vars + query params; GraphQL: field
     *     arguments). May be empty, never null.
     * @param body raw REST request body, or null on surfaces that have none
     */
    public Mono<Void> enforce(RouteDefinition route, Map<String, Object> arguments, String body) {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .defaultIfEmpty(NO_AUTH)
                .flatMap(auth -> authorizer.authorize(new AuthorizationRequest(route, auth, arguments, body)));
    }
}
