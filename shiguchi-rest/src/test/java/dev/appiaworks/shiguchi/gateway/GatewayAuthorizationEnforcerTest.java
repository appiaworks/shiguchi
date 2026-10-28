package dev.appiaworks.shiguchi.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.authz.AuthorizationRequest;
import dev.appiaworks.shiguchi.authz.GatewayAuthorizer;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import io.grpc.MethodDescriptor;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.test.StepVerifier;

/**
 * BDD-style tests for GatewayAuthorizationEnforcer.
 *
 * Tests verify that:
 * - The enforcer delegates to the configured GatewayAuthorizer
 * - A successful authorization from the authorizer completes the Mono successfully
 * - A denial (AccessDeniedException) from the authorizer is propagated
 * - When no security context is present, the authorizer receives an Authentication
 *   whose isAuthenticated() returns false
 * - When a security context is present, the authorizer receives that exact Authentication
 * - Request arguments are passed through to the authorizer
 * - Request body is passed through to the authorizer
 * - The single-arg enforce(route) overload uses empty arguments and null body
 */
class GatewayAuthorizationEnforcerTest {

    private RouteDefinition testRoute() {
        return RouteDefinition.builder(
                        "svc.pkg.Service", "Method", MethodDescriptor.MethodType.UNARY, "localhost:50051")
                .httpBinding("GET", "/v1/test")
                .build();
    }

    /**
     * A hand-written capturing fake that records the last AuthorizationRequest
     * and returns a configurable Mono.
     */
    private static class CapturingFakeAuthorizer implements GatewayAuthorizer {
        private AtomicReference<AuthorizationRequest> lastRequest = new AtomicReference<>();
        private RuntimeException errorToReturn = null;

        void setErrorToReturn(RuntimeException error) {
            this.errorToReturn = error;
        }

        AuthorizationRequest getLastRequest() {
            return lastRequest.get();
        }

        @Override
        public reactor.core.publisher.Mono<Void> authorize(AuthorizationRequest request) {
            lastRequest.set(request);
            if (errorToReturn != null) {
                return reactor.core.publisher.Mono.error(errorToReturn);
            }
            return reactor.core.publisher.Mono.empty();
        }
    }

    @Test
    @DisplayName("GIVEN configured GatewayAuthorizer WHEN authorize called SHOULD delegate to authorizer")
    void given_configuredAuthorizer_when_authorize_should_delegate() {
        // Arrange
        CapturingFakeAuthorizer fakeAuthorizer = new CapturingFakeAuthorizer();
        GatewayAuthorizationEnforcer enforcer = new GatewayAuthorizationEnforcer(fakeAuthorizer);
        RouteDefinition route = testRoute();

        // Act & Assert
        StepVerifier.create(enforcer.enforce(route)).expectComplete().verify();

        // Verify that authorizer was called
        assertThat(fakeAuthorizer.getLastRequest()).isNotNull();
        assertThat(fakeAuthorizer.getLastRequest().route()).isEqualTo(route);
    }

    @Test
    @DisplayName("GIVEN authorizer allows WHEN enforce called SHOULD complete successfully")
    void given_authorizerAllows_when_enforce_should_completeSuccessfully() {
        // Arrange
        CapturingFakeAuthorizer fakeAuthorizer = new CapturingFakeAuthorizer();
        GatewayAuthorizationEnforcer enforcer = new GatewayAuthorizationEnforcer(fakeAuthorizer);
        RouteDefinition route = testRoute();

        // Act & Assert
        StepVerifier.create(enforcer.enforce(route)).expectComplete().verify();
    }

    @Test
    @DisplayName("GIVEN authorizer denies WHEN enforce called SHOULD propagate AccessDeniedException")
    void given_authorizerDenies_when_enforce_should_propagateException() {
        // Arrange
        CapturingFakeAuthorizer fakeAuthorizer = new CapturingFakeAuthorizer();
        fakeAuthorizer.setErrorToReturn(new AccessDeniedException("Not allowed"));
        GatewayAuthorizationEnforcer enforcer = new GatewayAuthorizationEnforcer(fakeAuthorizer);
        RouteDefinition route = testRoute();

        // Act & Assert
        StepVerifier.create(enforcer.enforce(route))
                .expectErrorMatches(e ->
                        e instanceof AccessDeniedException && e.getMessage().equals("Not allowed"))
                .verify();
    }

    @Test
    @DisplayName(
            "GIVEN no security context WHEN enforce called SHOULD pass unauthenticated Authentication to authorizer")
    void given_noSecurityContext_when_enforce_should_passUnauthenticatedAuth() {
        // Arrange
        CapturingFakeAuthorizer fakeAuthorizer = new CapturingFakeAuthorizer();
        GatewayAuthorizationEnforcer enforcer = new GatewayAuthorizationEnforcer(fakeAuthorizer);
        RouteDefinition route = testRoute();

        // Act & Assert
        StepVerifier.create(enforcer.enforce(route)).expectComplete().verify();

        // Verify that an unauthenticated Authentication was passed
        AuthorizationRequest request = fakeAuthorizer.getLastRequest();
        assertThat(request.authentication()).isNotNull();
        assertThat(request.authentication().isAuthenticated()).isFalse();
    }

    @Test
    @DisplayName(
            "GIVEN security context with Authentication WHEN enforce called SHOULD pass that Authentication to authorizer")
    void given_securityContextPresent_when_enforce_should_passExactAuthentication() {
        // Arrange
        CapturingFakeAuthorizer fakeAuthorizer = new CapturingFakeAuthorizer();
        GatewayAuthorizationEnforcer enforcer = new GatewayAuthorizationEnforcer(fakeAuthorizer);
        RouteDefinition route = testRoute();
        Authentication expectedAuth = new UsernamePasswordAuthenticationToken(
                "testuser", "password", List.of(new SimpleGrantedAuthority("SCOPE_read:books")));

        // Act & Assert
        StepVerifier.create(enforcer.enforce(route)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(expectedAuth)))
                .expectComplete()
                .verify();

        // Verify that the exact Authentication was passed
        AuthorizationRequest request = fakeAuthorizer.getLastRequest();
        assertThat(request.authentication()).isEqualTo(expectedAuth);
        assertThat(request.authentication().isAuthenticated()).isTrue();
    }

    @Test
    @DisplayName("GIVEN arguments passed to enforce WHEN authorizer called SHOULD receive arguments in request")
    void given_argumentsPassed_when_authorize_should_receiveArguments() {
        // Arrange
        CapturingFakeAuthorizer fakeAuthorizer = new CapturingFakeAuthorizer();
        GatewayAuthorizationEnforcer enforcer = new GatewayAuthorizationEnforcer(fakeAuthorizer);
        RouteDefinition route = testRoute();
        Map<String, Object> arguments = Map.of("id", "123", "name", "test-item");

        // Act & Assert
        StepVerifier.create(enforcer.enforce(route, arguments, null))
                .expectComplete()
                .verify();

        // Verify that arguments were passed
        AuthorizationRequest request = fakeAuthorizer.getLastRequest();
        assertThat(request.arguments()).isEqualTo(arguments);
    }

    @Test
    @DisplayName("GIVEN body passed to enforce WHEN authorizer called SHOULD receive body in request")
    void given_bodyPassed_when_authorize_should_receiveBody() {
        // Arrange
        CapturingFakeAuthorizer fakeAuthorizer = new CapturingFakeAuthorizer();
        GatewayAuthorizationEnforcer enforcer = new GatewayAuthorizationEnforcer(fakeAuthorizer);
        RouteDefinition route = testRoute();
        String body = "{\"field\": \"value\"}";

        // Act & Assert
        StepVerifier.create(enforcer.enforce(route, Map.of(), body))
                .expectComplete()
                .verify();

        // Verify that body was passed
        AuthorizationRequest request = fakeAuthorizer.getLastRequest();
        assertThat(request.body()).isEqualTo(body);
    }

    @Test
    @DisplayName(
            "GIVEN single-arg enforce(route) called WHEN authorizer called SHOULD receive empty arguments and null body")
    void given_singleArgEnforce_when_authorize_should_receiveEmptyArgumentsAndNullBody() {
        // Arrange
        CapturingFakeAuthorizer fakeAuthorizer = new CapturingFakeAuthorizer();
        GatewayAuthorizationEnforcer enforcer = new GatewayAuthorizationEnforcer(fakeAuthorizer);
        RouteDefinition route = testRoute();

        // Act & Assert
        StepVerifier.create(enforcer.enforce(route)).expectComplete().verify();

        // Verify that empty arguments and null body were passed
        AuthorizationRequest request = fakeAuthorizer.getLastRequest();
        assertThat(request.arguments()).isEmpty();
        assertThat(request.body()).isNull();
    }
}
