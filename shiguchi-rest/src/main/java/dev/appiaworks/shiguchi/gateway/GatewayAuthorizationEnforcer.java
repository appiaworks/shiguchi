package dev.appiaworks.shiguchi.gateway;

import dev.appiaworks.shiguchi.authz.AuthorizationRequest;
import dev.appiaworks.shiguchi.authz.GatewayAuthorizer;
import dev.appiaworks.shiguchi.authz.GatewayPolicy;
import dev.appiaworks.shiguchi.authz.ScopeGatewayAuthorizer;
import dev.appiaworks.shiguchi.authz.ThrottledException;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
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

    private static final Logger log = LoggerFactory.getLogger(GatewayAuthorizationEnforcer.class);

    private final GatewayAuthorizer authorizer;
    private final ObjectProvider<GatewayPolicy> policies;

    public GatewayAuthorizationEnforcer(GatewayAuthorizer authorizer) {
        this(authorizer, null);
    }

    @Autowired
    public GatewayAuthorizationEnforcer(GatewayAuthorizer authorizer, ObjectProvider<GatewayPolicy> policies) {
        this.authorizer = authorizer;
        this.policies = policies;
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
        return enforce(route, arguments, body, Map.of(), null);
    }

    /**
     * Authorize a route, also handing the authorizer and the {@link GatewayPolicy} chain the request
     * headers ({@code authorization} and {@code cookie} are removed) and the surface.
     *
     * <p>The authorizer decides first; when it allows, every policy runs in order and all must pass.
     * A policy that errors with anything other than {@link AccessDeniedException} or
     * {@link ThrottledException}, or returns null, denies.
     */
    public Mono<Void> enforce(
            RouteDefinition route,
            Map<String, Object> arguments,
            String body,
            Map<String, List<String>> headers,
            AuthorizationRequest.Surface surface) {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .defaultIfEmpty(NO_AUTH)
                .flatMap(auth -> {
                    AuthorizationRequest request =
                            new AuthorizationRequest(route, auth, arguments, body, headers, surface);
                    return authorizer.authorize(request).then(Mono.defer(() -> runPolicies(request)));
                });
    }

    /**
     * Whether the decision to serve a request can depend on who asks beyond the route's own scopes
     * and rules: a {@code GatewayAuthorizer} other than the default {@link ScopeGatewayAuthorizer}
     * is in place, or at least one {@link GatewayPolicy} bean exists. A cache that answers before
     * {@link #enforce} runs must then not share an entry between callers.
     */
    public boolean decidesPerCaller() {
        if (authorizer != null && authorizer.getClass() != ScopeGatewayAuthorizer.class) return true;
        return policies != null && policies.stream().findAny().isPresent();
    }

    private Mono<Void> runPolicies(AuthorizationRequest request) {
        if (policies == null) return Mono.empty();
        List<GatewayPolicy> chain = policies.orderedStream().toList();
        if (chain.isEmpty()) return Mono.empty();
        return Flux.fromIterable(chain)
                .concatMap(policy -> check(policy, request))
                .then();
    }

    private static Mono<Void> check(GatewayPolicy policy, AuthorizationRequest request) {
        Mono<Void> result;
        try {
            result = policy.check(request);
        } catch (RuntimeException e) {
            return failClosed(policy, e);
        }
        if (result == null) {
            return failClosed(policy, new NullPointerException("check returned null"));
        }
        return result.onErrorResume(e -> {
            if (e instanceof AccessDeniedException || e instanceof ThrottledException) {
                return Mono.error(e);
            }
            return failClosed(policy, e);
        });
    }

    private static Mono<Void> failClosed(GatewayPolicy policy, Throwable cause) {
        if (cause instanceof AccessDeniedException || cause instanceof ThrottledException) {
            return Mono.error(cause);
        }
        log.warn(
                "GatewayPolicy {} failed; denying the request",
                policy.getClass().getName(),
                cause);
        return Mono.error(new AccessDeniedException("Denied: policy check failed"));
    }
}
