package dev.appiaworks.shiguchi.authz;

import dev.appiaworks.shiguchi.routing.RouteDefinition;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import reactor.core.publisher.Mono;

/**
 * Default {@link GatewayAuthorizer}: enforces the {@code auth_scopes} declared on a
 * {@code (shiguchi.api.method)} option.
 *
 * <p>When a route declares one or more required scopes, this authorizer verifies that the
 * authenticated principal holds every listed scope. Spring Security represents JWT scopes as
 * granted authorities with a {@code SCOPE_} prefix; the prefix is stripped before comparison so
 * that the annotation values match the raw scope names (e.g. {@code "read:books"}).
 *
 * <p>The check is a no-op when {@code route.getAuthScopes()} is empty, so services that have not
 * configured scope requirements are unaffected.
 *
 * <p>This is a coarse, route-level RBAC check: it answers "may this caller call this method?",
 * not "may this caller act on <em>this object</em>?". For per-object decisions supply your own
 * {@link GatewayAuthorizer} bean, optionally delegating here first for the scope gate.
 */
public class ScopeGatewayAuthorizer implements GatewayAuthorizer {

    private static final Logger log = LoggerFactory.getLogger(ScopeGatewayAuthorizer.class);

    private static final String SCOPE_PREFIX = "SCOPE_";

    @Override
    public Mono<Void> authorize(AuthorizationRequest request) {
        RouteDefinition route = request.route();
        List<String> required = route.getAuthScopes();
        if (required.isEmpty()) return Mono.empty();

        Authentication auth = request.authentication();
        Set<String> granted = (auth != null && auth.isAuthenticated())
                ? auth.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        // Strip "SCOPE_" prefix added by Spring Security OAuth2 resource server
                        .map(a -> a.startsWith(SCOPE_PREFIX) ? a.substring(SCOPE_PREFIX.length()) : a)
                        .collect(Collectors.toSet())
                : Set.of();

        List<String> missing =
                required.stream().filter(s -> !granted.contains(s)).toList();

        if (missing.isEmpty()) return Mono.empty();

        log.debug(
                "Access denied for '{}': missing scopes {}",
                route.getGraphQlName() != null ? route.getGraphQlName() : route.getHttpPath(),
                missing);
        return Mono.error(new AccessDeniedException("Missing required scopes: " + String.join(", ", missing)));
    }
}
