package dev.appiaworks.shiguchi.sample.opa;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.authz.AuthorizationRequest;
import dev.appiaworks.shiguchi.authz.ScopeGatewayAuthorizer;
import dev.appiaworks.shiguchi.routing.AuthzRuleDefinition;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import io.grpc.MethodDescriptor;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * BDD-style tests for OpaGatewayAuthorizer and OpaInputBuilder.
 *
 * <p>Tests verify that:
 *
 * <ul>
 *   <li>OPA {@code {"result":true}} allows, {@code {"result":false}} denies
 *   <li>An undefined rule ({@code {}}, no "result" key) is treated as denied, never as permission
 *   <li>Transport failure (HTTP 500) respects the fail-open/fail-closed configuration
 *   <li>The in-process scope gate rejects callers before any network call to OPA is made
 *   <li>{@link OpaInputBuilder#build(AuthorizationRequest)} produces the documented document shape,
 *       including a resolved {@code id} when present and {@code null} when the field is absent
 * </ul>
 */
class OpaGatewayAuthorizerTest {

    private RouteDefinition routeWithAuthzRule(AuthzRuleDefinition rule, String... scopes) {
        return RouteDefinition.builder(
                        "orders.OrderService", "GetOrder", MethodDescriptor.MethodType.UNARY, "localhost:50051")
                .httpBinding("GET", "/v1/orders/{orderId}")
                .authScopes(List.of(scopes))
                .authzRules(rule == null ? List.of() : List.of(rule))
                .build();
    }

    private Authentication authenticatedWith(String... authorities) {
        return new UsernamePasswordAuthenticationToken(
                "alice", null, List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList());
    }

    private OpaAuthzProperties props(boolean failOpen) {
        OpaAuthzProperties props = new OpaAuthzProperties();
        props.setEnabled(true);
        props.setUrl("http://localhost:8181");
        props.setDecisionPath("gateway/authz/allow");
        props.setTimeout(Duration.ofSeconds(2));
        props.setFailOpen(failOpen);
        return props;
    }

    private WebClient.Builder stubReturning(HttpStatus status, String jsonBody) {
        return WebClient.builder()
                .exchangeFunction(req -> Mono.just(ClientResponse.create(status)
                        .header("Content-Type", "application/json")
                        .body(jsonBody)
                        .build()));
    }

    @Test
    @DisplayName("GIVEN OPA returns result true WHEN authorizing SHOULD allow")
    void given_opaReturnsResultTrue_when_authorizing_should_allow() {
        // Arrange
        WebClient.Builder stub = stubReturning(HttpStatus.OK, "{\"result\":true}");
        OpaGatewayAuthorizer authorizer =
                new OpaGatewayAuthorizer(new ScopeGatewayAuthorizer(), stub, props(false));
        RouteDefinition route = routeWithAuthzRule(null, "orders:read");
        Authentication auth = authenticatedWith("SCOPE_orders:read");
        AuthorizationRequest request = AuthorizationRequest.of(route, auth, Map.of());

        // Act & Assert
        StepVerifier.create(authorizer.authorize(request)).expectComplete().verify();
    }

    @Test
    @DisplayName("GIVEN OPA returns result false WHEN authorizing SHOULD deny with AccessDeniedException")
    void given_opaReturnsResultFalse_when_authorizing_should_denyWithAccessDeniedException() {
        // Arrange
        WebClient.Builder stub = stubReturning(HttpStatus.OK, "{\"result\":false}");
        OpaGatewayAuthorizer authorizer =
                new OpaGatewayAuthorizer(new ScopeGatewayAuthorizer(), stub, props(false));
        RouteDefinition route = routeWithAuthzRule(null, "orders:read");
        Authentication auth = authenticatedWith("SCOPE_orders:read");
        AuthorizationRequest request = AuthorizationRequest.of(route, auth, Map.of());

        // Act & Assert
        StepVerifier.create(authorizer.authorize(request))
                .expectErrorMatches(e -> e instanceof AccessDeniedException)
                .verify();
    }

    @Test
    @DisplayName("GIVEN OPA returns empty object with no result key WHEN authorizing SHOULD deny, not allow")
    void given_opaReturnsUndefinedRule_when_authorizing_should_deny() {
        // Arrange: {} means the rule is undefined in OPA — must never read as permission.
        WebClient.Builder stub = stubReturning(HttpStatus.OK, "{}");
        OpaGatewayAuthorizer authorizer =
                new OpaGatewayAuthorizer(new ScopeGatewayAuthorizer(), stub, props(false));
        RouteDefinition route = routeWithAuthzRule(null, "orders:read");
        Authentication auth = authenticatedWith("SCOPE_orders:read");
        AuthorizationRequest request = AuthorizationRequest.of(route, auth, Map.of());

        // Act & Assert
        StepVerifier.create(authorizer.authorize(request))
                .expectErrorMatches(e -> e instanceof AccessDeniedException)
                .verify();
    }

    @Test
    @DisplayName("GIVEN OPA returns HTTP 500 WHEN failOpen is false SHOULD deny")
    void given_opaReturnsServerError_when_failOpenFalse_should_deny() {
        // Arrange
        WebClient.Builder stub = stubReturning(HttpStatus.INTERNAL_SERVER_ERROR, "boom");
        OpaGatewayAuthorizer authorizer =
                new OpaGatewayAuthorizer(new ScopeGatewayAuthorizer(), stub, props(false));
        RouteDefinition route = routeWithAuthzRule(null, "orders:read");
        Authentication auth = authenticatedWith("SCOPE_orders:read");
        AuthorizationRequest request = AuthorizationRequest.of(route, auth, Map.of());

        // Act & Assert
        StepVerifier.create(authorizer.authorize(request))
                .expectErrorMatches(e -> e instanceof AccessDeniedException)
                .verify();
    }

    @Test
    @DisplayName("GIVEN OPA returns HTTP 500 WHEN failOpen is true SHOULD allow")
    void given_opaReturnsServerError_when_failOpenTrue_should_allow() {
        // Arrange
        WebClient.Builder stub = stubReturning(HttpStatus.INTERNAL_SERVER_ERROR, "boom");
        OpaGatewayAuthorizer authorizer =
                new OpaGatewayAuthorizer(new ScopeGatewayAuthorizer(), stub, props(true));
        RouteDefinition route = routeWithAuthzRule(null, "orders:read");
        Authentication auth = authenticatedWith("SCOPE_orders:read");
        AuthorizationRequest request = AuthorizationRequest.of(route, auth, Map.of());

        // Act & Assert
        StepVerifier.create(authorizer.authorize(request)).expectComplete().verify();
    }

    @Test
    @DisplayName("GIVEN caller missing a required auth scope WHEN authorizing SHOULD deny without calling OPA")
    void given_callerMissingRequiredScope_when_authorizing_should_denyWithoutCallingOpa() {
        // Arrange
        AtomicInteger exchangeCount = new AtomicInteger();
        WebClient.Builder stub = WebClient.builder().exchangeFunction(req -> {
            exchangeCount.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body("{\"result\":true}")
                    .build());
        });
        OpaGatewayAuthorizer authorizer =
                new OpaGatewayAuthorizer(new ScopeGatewayAuthorizer(), stub, props(false));
        RouteDefinition route = routeWithAuthzRule(null, "orders:read");
        Authentication auth = authenticatedWith("SCOPE_other:scope"); // Missing orders:read
        AuthorizationRequest request = AuthorizationRequest.of(route, auth, Map.of());

        // Act & Assert
        StepVerifier.create(authorizer.authorize(request))
                .expectErrorMatches(e -> e instanceof AccessDeniedException)
                .verify();
        assertThat(exchangeCount.get()).isZero();
    }

    @Test
    @DisplayName("GIVEN authenticated caller WHEN building OPA input SHOULD carry subject id, authenticated flag and authorities")
    void given_authenticatedCaller_when_buildingInput_should_carrySubjectDetails() {
        // Arrange
        RouteDefinition route = routeWithAuthzRule(null, "orders:read");
        Authentication auth = authenticatedWith("SCOPE_orders:read");
        AuthorizationRequest request = AuthorizationRequest.of(route, auth, Map.of());

        // Act
        Map<String, Object> input = OpaInputBuilder.build(request);

        // Assert
        @SuppressWarnings("unchecked")
        Map<String, Object> subject = (Map<String, Object>) input.get("subject");
        assertThat(subject.get("id")).isEqualTo("alice");
        assertThat(subject.get("authenticated")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<String> authorities = (List<String>) subject.get("authorities");
        assertThat(authorities).containsExactly("SCOPE_orders:read");
    }

    @Test
    @DisplayName("GIVEN a route WHEN building OPA input SHOULD carry action service, method, http method and path")
    void given_route_when_buildingInput_should_carryActionDetails() {
        // Arrange
        RouteDefinition route = routeWithAuthzRule(null, "orders:read");
        Authentication auth = authenticatedWith("SCOPE_orders:read");
        AuthorizationRequest request = AuthorizationRequest.of(route, auth, Map.of());

        // Act
        Map<String, Object> input = OpaInputBuilder.build(request);

        // Assert
        @SuppressWarnings("unchecked")
        Map<String, Object> action = (Map<String, Object>) input.get("action");
        assertThat(action.get("service")).isEqualTo("orders.OrderService");
        assertThat(action.get("method")).isEqualTo("GetOrder");
        assertThat(action.get("http_method")).isEqualTo("GET");
        assertThat(action.get("http_path")).isEqualTo("/v1/orders/{orderId}");
    }

    @Test
    @DisplayName("GIVEN authz rule with matching argument present WHEN building OPA input SHOULD resolve the id")
    void given_authzRuleWithArgumentPresent_when_buildingInput_should_resolveId() {
        // Arrange
        AuthzRuleDefinition rule = new AuthzRuleDefinition("order", "viewer", "order_id");
        RouteDefinition route = routeWithAuthzRule(rule, "orders:read");
        Authentication auth = authenticatedWith("SCOPE_orders:read");
        AuthorizationRequest request = AuthorizationRequest.of(route, auth, Map.of("orderId", "42"));

        // Act
        Map<String, Object> input = OpaInputBuilder.build(request);

        // Assert
        @SuppressWarnings("unchecked")
        Map<String, Object> resource = (Map<String, Object>) input.get("resource");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> authz = (List<Map<String, Object>>) resource.get("authz");
        assertThat(authz).hasSize(1);
        Map<String, Object> entry = authz.get(0);
        assertThat(entry.get("object_type")).isEqualTo("order");
        assertThat(entry.get("relation")).isEqualTo("viewer");
        assertThat(entry.get("id_field")).isEqualTo("order_id");
        assertThat(entry.get("id")).isEqualTo("42");
    }

    @Test
    @DisplayName("GIVEN authz rule whose id field is absent from arguments WHEN building OPA input SHOULD resolve id to null")
    void given_authzRuleWithArgumentAbsent_when_buildingInput_should_resolveIdToNull() {
        // Arrange
        AuthzRuleDefinition rule = new AuthzRuleDefinition("order", "viewer", "order_id");
        RouteDefinition route = routeWithAuthzRule(rule, "orders:read");
        Authentication auth = authenticatedWith("SCOPE_orders:read");
        AuthorizationRequest request = AuthorizationRequest.of(route, auth, Map.of());

        // Act
        Map<String, Object> input = OpaInputBuilder.build(request);

        // Assert
        @SuppressWarnings("unchecked")
        Map<String, Object> resource = (Map<String, Object>) input.get("resource");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> authz = (List<Map<String, Object>>) resource.get("authz");
        assertThat(authz).hasSize(1);
        Map<String, Object> entry = authz.get(0);
        assertThat(entry).containsKey("id");
        assertThat(entry.get("id")).isNull();
    }
}
