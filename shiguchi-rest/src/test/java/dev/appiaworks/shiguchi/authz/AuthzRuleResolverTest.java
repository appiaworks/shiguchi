package dev.appiaworks.shiguchi.authz;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.routing.AuthzRuleDefinition;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import io.grpc.MethodDescriptor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * BDD-style tests for AuthzRuleResolver.
 *
 * Tests verify that:
 * - Routes with no authz rules produce empty resolved/unresolved lists
 * - Rules with id_field matching an argument key are resolved to ResolvedCheck objects
 * - Snake_case id_field resolves against camelCase argument keys
 * - Rules with missing id_field land in unresolved(), not checks()
 * - Blank/empty id_field values are treated as missing
 * - Dotted id_field paths walk nested Map structures correctly
 * - Dotted id_field with missing intermediate segments are unresolved
 * - Multiple rules on one route all resolve, preserving order
 * - Mixed resolvable and unresolvable rules are handled correctly
 */
class AuthzRuleResolverTest {

    private RouteDefinition routeWithAuthzRules(List<AuthzRuleDefinition> rules) {
        return RouteDefinition.builder(
                        "svc.pkg.Service", "Method", MethodDescriptor.MethodType.UNARY, "localhost:50051")
                .httpBinding("GET", "/v1/test")
                .authzRules(rules)
                .build();
    }

    private RouteDefinition routeWithoutAuthzRules() {
        return RouteDefinition.builder(
                        "svc.pkg.Service", "Method", MethodDescriptor.MethodType.UNARY, "localhost:50051")
                .httpBinding("GET", "/v1/test")
                .build();
    }

    private Authentication unauthenticated() {
        return new UsernamePasswordAuthenticationToken("anon", null);
    }

    @Test
    @DisplayName("GIVEN route with no authz rules WHEN resolved SHOULD have empty checks and unresolved")
    void given_routeWithNoAuthzRules_when_resolved_should_haveEmptyChecksAndUnresolved() {
        // Arrange
        RouteDefinition route = routeWithoutAuthzRules();
        AuthorizationRequest request = AuthorizationRequest.of(route, unauthenticated(), Map.of("order_id", "42"));

        // Act
        AuthzRuleResolver.Resolved resolved = AuthzRuleResolver.resolve(request);

        // Assert
        assertThat(resolved.isEmpty()).isTrue();
        assertThat(resolved.checks()).isEmpty();
        assertThat(resolved.unresolved()).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN rule with id_field matching argument key exactly WHEN resolved SHOULD create ResolvedCheck with objectRef")
    void given_ruleWithMatchingIdField_when_resolved_should_createResolvedCheck() {
        // Arrange
        AuthzRuleDefinition rule = new AuthzRuleDefinition("order", "viewer", "order_id");
        RouteDefinition route = routeWithAuthzRules(List.of(rule));
        AuthorizationRequest request = AuthorizationRequest.of(route, unauthenticated(), Map.of("order_id", "42"));

        // Act
        AuthzRuleResolver.Resolved resolved = AuthzRuleResolver.resolve(request);

        // Assert
        assertThat(resolved.checks()).hasSize(1);
        assertThat(resolved.checks().get(0).objectRef()).isEqualTo("order:42");
        assertThat(resolved.checks().get(0).relation()).isEqualTo("viewer");
        assertThat(resolved.unresolved()).isEmpty();
    }

    @Test
    @DisplayName("GIVEN snake_case id_field WHEN argument is camelCase SHOULD resolve correctly")
    void given_snakeCaseIdField_when_camelCaseArgument_should_resolveCorrectly() {
        // Arrange
        AuthzRuleDefinition rule = new AuthzRuleDefinition("order", "viewer", "order_id");
        RouteDefinition route = routeWithAuthzRules(List.of(rule));
        AuthorizationRequest request = AuthorizationRequest.of(route, unauthenticated(), Map.of("orderId", "99"));

        // Act
        AuthzRuleResolver.Resolved resolved = AuthzRuleResolver.resolve(request);

        // Assert
        assertThat(resolved.checks()).hasSize(1);
        assertThat(resolved.checks().get(0).objectRef()).isEqualTo("order:99");
        assertThat(resolved.unresolved()).isEmpty();
    }

    @Test
    @DisplayName("GIVEN rule whose id_field is absent from arguments WHEN resolved SHOULD land in unresolved")
    void given_missingIdField_when_resolved_should_landInUnresolved() {
        // Arrange
        AuthzRuleDefinition rule = new AuthzRuleDefinition("order", "viewer", "order_id");
        RouteDefinition route = routeWithAuthzRules(List.of(rule));
        AuthorizationRequest request = AuthorizationRequest.of(route, unauthenticated(), Map.of("customer_id", "100"));

        // Act
        AuthzRuleResolver.Resolved resolved = AuthzRuleResolver.resolve(request);

        // Assert
        assertThat(resolved.checks()).isEmpty();
        assertThat(resolved.unresolved()).hasSize(1);
        assertThat(resolved.unresolved().get(0)).isEqualTo(rule);
    }

    @Test
    @DisplayName("GIVEN id_field present but blank WHEN resolved SHOULD land in unresolved")
    void given_blankIdField_when_resolved_should_landInUnresolved() {
        // Arrange
        AuthzRuleDefinition rule = new AuthzRuleDefinition("order", "viewer", "order_id");
        RouteDefinition route = routeWithAuthzRules(List.of(rule));
        AuthorizationRequest request = AuthorizationRequest.of(route, unauthenticated(), Map.of("order_id", ""));

        // Act
        AuthzRuleResolver.Resolved resolved = AuthzRuleResolver.resolve(request);

        // Assert
        assertThat(resolved.checks()).isEmpty();
        assertThat(resolved.unresolved()).hasSize(1);
        assertThat(resolved.unresolved().get(0)).isEqualTo(rule);
    }

    @Test
    @DisplayName("GIVEN dotted id_field WHEN nested Map argument exists SHOULD walk path and resolve")
    void given_dottedIdField_when_nestedMap_should_walkPathAndResolve() {
        // Arrange
        AuthzRuleDefinition rule = new AuthzRuleDefinition("order", "viewer", "order.id");
        RouteDefinition route = routeWithAuthzRules(List.of(rule));
        Map<String, Object> arguments = Map.of("order", Map.of("id", "42"));
        AuthorizationRequest request = AuthorizationRequest.of(route, unauthenticated(), arguments);

        // Act
        AuthzRuleResolver.Resolved resolved = AuthzRuleResolver.resolve(request);

        // Assert
        assertThat(resolved.checks()).hasSize(1);
        assertThat(resolved.checks().get(0).objectRef()).isEqualTo("order:42");
        assertThat(resolved.unresolved()).isEmpty();
    }

    @Test
    @DisplayName("GIVEN dotted id_field WHEN intermediate segment missing WHEN resolved SHOULD land in unresolved")
    void given_dottedIdFieldMissingIntermediate_when_resolved_should_landInUnresolved() {
        // Arrange
        AuthzRuleDefinition rule = new AuthzRuleDefinition("order", "viewer", "order.items.id");
        RouteDefinition route = routeWithAuthzRules(List.of(rule));
        Map<String, Object> arguments = Map.of("order", Map.of("metadata", "value")); // items key missing
        AuthorizationRequest request = AuthorizationRequest.of(route, unauthenticated(), arguments);

        // Act
        AuthzRuleResolver.Resolved resolved = AuthzRuleResolver.resolve(request);

        // Assert
        assertThat(resolved.checks()).isEmpty();
        assertThat(resolved.unresolved()).hasSize(1);
        assertThat(resolved.unresolved().get(0)).isEqualTo(rule);
    }

    @Test
    @DisplayName("GIVEN multiple rules on one route WHEN resolved SHOULD all resolve into checks preserving order")
    void given_multipleRules_when_resolved_should_resolveAllInOrder() {
        // Arrange
        AuthzRuleDefinition rule1 = new AuthzRuleDefinition("order", "viewer", "order_id");
        AuthzRuleDefinition rule2 = new AuthzRuleDefinition("customer", "admin", "customer_id");
        AuthzRuleDefinition rule3 = new AuthzRuleDefinition("invoice", "editor", "invoice_id");
        RouteDefinition route = routeWithAuthzRules(List.of(rule1, rule2, rule3));
        Map<String, Object> arguments = Map.of(
                "orderId", "10",
                "customerId", "20",
                "invoiceId", "30");
        AuthorizationRequest request = AuthorizationRequest.of(route, unauthenticated(), arguments);

        // Act
        AuthzRuleResolver.Resolved resolved = AuthzRuleResolver.resolve(request);

        // Assert
        assertThat(resolved.checks()).hasSize(3);
        assertThat(resolved.checks().get(0).objectRef()).isEqualTo("order:10");
        assertThat(resolved.checks().get(0).relation()).isEqualTo("viewer");
        assertThat(resolved.checks().get(1).objectRef()).isEqualTo("customer:20");
        assertThat(resolved.checks().get(1).relation()).isEqualTo("admin");
        assertThat(resolved.checks().get(2).objectRef()).isEqualTo("invoice:30");
        assertThat(resolved.checks().get(2).relation()).isEqualTo("editor");
        assertThat(resolved.unresolved()).isEmpty();
    }

    @Test
    @DisplayName("GIVEN mixed resolvable and unresolvable rules WHEN resolved SHOULD preserve both in correct lists")
    void given_mixedResolvableAndUnresolvable_when_resolved_should_preserveBoth() {
        // Arrange
        AuthzRuleDefinition resolvable = new AuthzRuleDefinition("order", "viewer", "order_id");
        AuthzRuleDefinition unresolvable = new AuthzRuleDefinition("invoice", "admin", "invoice_id");
        RouteDefinition route = routeWithAuthzRules(List.of(resolvable, unresolvable));
        AuthorizationRequest request = AuthorizationRequest.of(route, unauthenticated(), Map.of("order_id", "42"));

        // Act
        AuthzRuleResolver.Resolved resolved = AuthzRuleResolver.resolve(request);

        // Assert
        assertThat(resolved.checks()).hasSize(1);
        assertThat(resolved.checks().get(0).objectRef()).isEqualTo("order:42");
        assertThat(resolved.checks().get(0).rule()).isEqualTo(resolvable);
        assertThat(resolved.unresolved()).hasSize(1);
        assertThat(resolved.unresolved().get(0)).isEqualTo(unresolvable);
    }
}
