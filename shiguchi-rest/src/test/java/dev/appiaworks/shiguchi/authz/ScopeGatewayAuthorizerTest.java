package dev.appiaworks.shiguchi.authz;

import dev.appiaworks.shiguchi.routing.RouteDefinition;
import io.grpc.MethodDescriptor;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import reactor.test.StepVerifier;

/**
 * BDD-style tests for ScopeGatewayAuthorizer.
 *
 * Tests verify that:
 * - Routes with no required scopes are allowed regardless of authentication
 * - Authenticated callers with all required scopes are allowed
 * - Authenticated callers missing one or more scopes are denied
 * - SCOPE_ prefix is stripped when matching authorities to required scopes
 * - Authorities without SCOPE_ prefix are also matched correctly
 * - Unauthenticated callers (isAuthenticated() == false) are denied when scopes are required
 * - All required scopes must be present; partial matches are denied
 */
class ScopeGatewayAuthorizerTest {

    private final ScopeGatewayAuthorizer authorizer = new ScopeGatewayAuthorizer();

    private RouteDefinition routeWithAuthScopes(String... scopes) {
        return RouteDefinition.builder(
                        "svc.pkg.Service", "Method", MethodDescriptor.MethodType.UNARY, "localhost:50051")
                .httpBinding("GET", "/v1/test")
                .authScopes(List.of(scopes))
                .build();
    }

    private RouteDefinition routeWithoutAuthScopes() {
        return RouteDefinition.builder(
                        "svc.pkg.Service", "Method", MethodDescriptor.MethodType.UNARY, "localhost:50051")
                .httpBinding("GET", "/v1/test")
                .build();
    }

    private Authentication authenticatedWith(String... authorities) {
        return new UsernamePasswordAuthenticationToken(
                "user",
                null,
                List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList());
    }

    private Authentication unauthenticated() {
        return new UsernamePasswordAuthenticationToken("anon", null);
    }

    @Test
    @DisplayName("GIVEN route with no required scopes WHEN unauthenticated caller SHOULD allow")
    void given_routeWithNoScopes_when_unauthenticatedCaller_should_allow() {
        // Arrange
        RouteDefinition route = routeWithoutAuthScopes();
        Authentication auth = unauthenticated();
        AuthorizationRequest request = new AuthorizationRequest(route, auth, null, null);

        // Act & Assert
        StepVerifier.create(authorizer.authorize(request)).expectComplete().verify();
    }

    @Test
    @DisplayName("GIVEN route with no required scopes WHEN authenticated caller SHOULD allow")
    void given_routeWithNoScopes_when_authenticatedCaller_should_allow() {
        // Arrange
        RouteDefinition route = routeWithoutAuthScopes();
        Authentication auth = authenticatedWith("SCOPE_read:books");
        AuthorizationRequest request = new AuthorizationRequest(route, auth, null, null);

        // Act & Assert
        StepVerifier.create(authorizer.authorize(request)).expectComplete().verify();
    }

    @Test
    @DisplayName("GIVEN route requiring scopes WHEN caller holds all required scopes with SCOPE_ prefix SHOULD allow")
    void given_routeRequiringScopes_when_callerHoldsAllScopesWithPrefix_should_allow() {
        // Arrange
        RouteDefinition route = routeWithAuthScopes("read:books", "write:orders");
        Authentication auth = authenticatedWith("SCOPE_read:books", "SCOPE_write:orders", "SCOPE_admin");
        AuthorizationRequest request = new AuthorizationRequest(route, auth, null, null);

        // Act & Assert
        StepVerifier.create(authorizer.authorize(request)).expectComplete().verify();
    }

    @Test
    @DisplayName(
            "GIVEN route requiring scopes WHEN caller holds all required scopes without SCOPE_ prefix SHOULD allow")
    void given_routeRequiringScopes_when_callerHoldsAllScopesWithoutPrefix_should_allow() {
        // Arrange
        RouteDefinition route = routeWithAuthScopes("read:books", "write:orders");
        Authentication auth = authenticatedWith("read:books", "write:orders", "admin");
        AuthorizationRequest request = new AuthorizationRequest(route, auth, null, null);

        // Act & Assert
        StepVerifier.create(authorizer.authorize(request)).expectComplete().verify();
    }

    @Test
    @DisplayName(
            "GIVEN route requiring scopes WHEN caller holds mix of prefixed and unprefixed scopes matching requirements SHOULD allow")
    void given_routeRequiringScopes_when_callerHoldsScopesMixed_should_allow() {
        // Arrange
        RouteDefinition route = routeWithAuthScopes("read:books", "write:orders");
        Authentication auth = authenticatedWith("SCOPE_read:books", "write:orders");
        AuthorizationRequest request = new AuthorizationRequest(route, auth, null, null);

        // Act & Assert
        StepVerifier.create(authorizer.authorize(request)).expectComplete().verify();
    }

    @Test
    @DisplayName(
            "GIVEN route requiring scopes WHEN caller missing at least one required scope SHOULD deny with AccessDeniedException")
    void given_routeRequiringScopes_when_callerMissingScope_should_denyWithException() {
        // Arrange
        RouteDefinition route = routeWithAuthScopes("read:books", "write:orders");
        Authentication auth = authenticatedWith("SCOPE_read:books"); // Missing write:orders
        AuthorizationRequest request = new AuthorizationRequest(route, auth, null, null);

        // Act & Assert
        StepVerifier.create(authorizer.authorize(request))
                .expectErrorMatches(e ->
                        e instanceof AccessDeniedException && e.getMessage().contains("Missing required scopes"))
                .verify();
    }

    @Test
    @DisplayName("GIVEN route requiring scopes WHEN unauthenticated caller SHOULD deny with AccessDeniedException")
    void given_routeRequiringScopes_when_unauthenticatedCaller_should_denyWithException() {
        // Arrange
        RouteDefinition route = routeWithAuthScopes("read:books");
        Authentication auth = unauthenticated(); // isAuthenticated() == false
        AuthorizationRequest request = new AuthorizationRequest(route, auth, null, null);

        // Act & Assert
        StepVerifier.create(authorizer.authorize(request))
                .expectErrorMatches(e ->
                        e instanceof AccessDeniedException && e.getMessage().contains("Missing required scopes"))
                .verify();
    }

    @Test
    @DisplayName("GIVEN route requiring multiple scopes WHEN caller holds only partial match SHOULD deny")
    void given_routeRequiringMultipleScopes_when_callerHoldsPartial_should_deny() {
        // Arrange
        RouteDefinition route = routeWithAuthScopes("read:books", "write:orders", "delete:catalog");
        Authentication auth = authenticatedWith("SCOPE_read:books", "SCOPE_write:orders"); // Missing delete:catalog
        AuthorizationRequest request = new AuthorizationRequest(route, auth, null, null);

        // Act & Assert
        StepVerifier.create(authorizer.authorize(request))
                .expectErrorMatches(e -> e instanceof AccessDeniedException)
                .verify();
    }
}
