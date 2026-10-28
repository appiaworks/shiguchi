package dev.appiaworks.shiguchi.routing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.config.ServiceDefinition;
import io.grpc.MethodDescriptor;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD tests for duplicate route bindings: two services claiming the same REST path or the same
 * GraphQL field name.
 *
 * <p>The registry keeps the first claim, resolves it the same way on every run, and never throws —
 * contexts register one at a time, so failing here would abort startup based on how far through
 * registration the gateway had got.
 */
class GrpcRouteRegistryDuplicateTest {

    private static ServiceEndpointContext context(String serviceName, RouteDefinition... routes) {
        ServiceDefinition config = new ServiceDefinition();
        config.setName(serviceName);
        ServiceEndpointContext ctx = new ServiceEndpointContext(config, null);
        for (RouteDefinition route : routes) {
            ctx.addRoute(route);
        }
        return ctx;
    }

    private static RouteDefinition restRoute(String protoService, String method, String httpMethod, String path) {
        return RouteDefinition.builder(protoService, method, MethodDescriptor.MethodType.UNARY, "localhost:1")
                .httpBinding(httpMethod, path)
                .build();
    }

    private static RouteDefinition graphQlRoute(String protoService, String method, String fieldName) {
        return RouteDefinition.builder(protoService, method, MethodDescriptor.MethodType.UNARY, "localhost:1")
                .graphQl(fieldName, RouteDefinition.GraphQlOperationType.QUERY)
                .build();
    }

    // ===== REST =====

    @Test
    @DisplayName("GIVEN two services binding the same REST path WHEN resolving SHOULD keep exactly one route")
    void given_duplicateRestPath_when_resolving_should_keepOneRoute() {
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        registry.registerContext(
                context("alliance-svc", restRoute("AllianceService", "getByIds", "GET", "/v1/alliances")));
        registry.registerContext(
                context("org-svc", restRoute("EveAllianceService", "getByIds", "GET", "/v1/alliances")));

        assertThat(registry.findByHttpKey("GET", "/v1/alliances")).isPresent();
    }

    @Test
    @DisplayName("GIVEN a duplicate REST path WHEN resolving SHOULD keep the alphabetically first service, "
            + "regardless of registration order")
    void given_duplicateRestPath_when_resolving_should_notDependOnRegistrationOrder() {
        GrpcRouteRegistry first = new GrpcRouteRegistry();
        first.registerContext(
                context("alliance-svc", restRoute("AllianceService", "getByIds", "GET", "/v1/alliances")));
        first.registerContext(context("org-svc", restRoute("EveAllianceService", "getByIds", "GET", "/v1/alliances")));

        GrpcRouteRegistry reversed = new GrpcRouteRegistry();
        reversed.registerContext(
                context("org-svc", restRoute("EveAllianceService", "getByIds", "GET", "/v1/alliances")));
        reversed.registerContext(
                context("alliance-svc", restRoute("AllianceService", "getByIds", "GET", "/v1/alliances")));

        String winnerA =
                first.findByHttpKey("GET", "/v1/alliances").orElseThrow().getFullServiceName();
        String winnerB =
                reversed.findByHttpKey("GET", "/v1/alliances").orElseThrow().getFullServiceName();

        assertThat(winnerA)
                .as("the same config must route the same way whichever order contexts registered in")
                .isEqualTo(winnerB)
                .isEqualTo("AllianceService");
    }

    @Test
    @DisplayName("GIVEN a duplicate templated REST path WHEN resolving SHOULD not register the losing pattern twice")
    void given_duplicateTemplatePath_when_resolving_should_registerOnePattern() {
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        registry.registerContext(
                context("alliance-svc", restRoute("AllianceService", "getById", "GET", "/v1/alliances/{id}")));
        registry.registerContext(
                context("org-svc", restRoute("EveAllianceService", "getById", "GET", "/v1/alliances/{id}")));

        // A losing route must not linger in the template list, where it could win the pattern scan
        // that runs when the exact-key lookup misses.
        assertThat(registry.findByHttpKey("GET", "/v1/alliances/42")
                        .orElseThrow()
                        .getFullServiceName())
                .isEqualTo("AllianceService");
    }

    @Test
    @DisplayName("GIVEN distinct REST paths WHEN resolving SHOULD register every route")
    void given_distinctPaths_when_resolving_should_keepAll() {
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        registry.registerContext(
                context("svc", restRoute("A", "list", "GET", "/v1/alpha"), restRoute("B", "list", "GET", "/v1/beta")));

        assertThat(registry.findByHttpKey("GET", "/v1/alpha")).isPresent();
        assertThat(registry.findByHttpKey("GET", "/v1/beta")).isPresent();
    }

    @Test
    @DisplayName("GIVEN the same path on different HTTP methods WHEN resolving SHOULD treat them as distinct routes")
    void given_samePathDifferentMethods_when_resolving_should_keepBoth() {
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        registry.registerContext(context(
                "svc", restRoute("A", "list", "GET", "/v1/things"), restRoute("A", "create", "POST", "/v1/things")));

        assertThat(registry.findByHttpKey("GET", "/v1/things").orElseThrow().getMethodName())
                .isEqualTo("list");
        assertThat(registry.findByHttpKey("POST", "/v1/things").orElseThrow().getMethodName())
                .isEqualTo("create");
    }

    // ===== GraphQL =====

    @Test
    @DisplayName("GIVEN two services declaring the same GraphQL field WHEN resolving SHOULD expose only one route")
    void given_duplicateGraphQlField_when_resolving_should_exposeOneRoute() {
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        registry.registerContext(context("alliance-svc", graphQlRoute("AllianceService", "getByIds", "alliances")));
        registry.registerContext(context("org-svc", graphQlRoute("EveAllianceService", "getByIds", "alliances")));

        // More than one would make the SDL emit two fields of the same name, which fails to parse.
        assertThat(registry.findByGraphQlName("alliances")).hasSize(1);
    }

    @Test
    @DisplayName("GIVEN a duplicate GraphQL field WHEN generating schema input SHOULD list it exactly once")
    void given_duplicateGraphQlField_when_listingForSchema_should_appearOnce() {
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        registry.registerContext(context("alliance-svc", graphQlRoute("AllianceService", "getByIds", "alliances")));
        registry.registerContext(context("org-svc", graphQlRoute("EveAllianceService", "getByIds", "alliances")));

        assertThat(registry.graphQlRoutesWithContext()).hasSize(1);
    }

    @Test
    @DisplayName("GIVEN distinct GraphQL fields WHEN generating schema input SHOULD pair each route with its context")
    void given_distinctGraphQlFields_when_listingForSchema_should_pairWithOwningContext() {
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        registry.registerContext(context("svc-a", graphQlRoute("A", "list", "alpha")));
        registry.registerContext(context("svc-b", graphQlRoute("B", "list", "beta")));

        List<String> owners = registry.graphQlRoutesWithContext().stream()
                .map(e -> e.getValue().getConfig().getName())
                .sorted()
                .toList();

        assertThat(registry.graphQlRoutesWithContext()).hasSize(2);
        assertThat(owners).containsExactly("svc-a", "svc-b");
    }

    @Test
    @DisplayName("GIVEN a duplicate binding WHEN registering SHOULD not throw, so startup and refresh survive it")
    void given_duplicateBinding_when_registering_should_notThrow() {
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        registry.registerContext(context("alliance-svc", restRoute("AllianceService", "getByIds", "GET", "/v1/x")));

        // replaceContext runs on the refresh scheduler's thread; throwing there would kill it.
        registry.registerContext(context("org-svc", restRoute("EveAllianceService", "getByIds", "GET", "/v1/x")));
        registry.replaceContext(
                "org-svc", context("org-svc", restRoute("EveAllianceService", "getByIds", "GET", "/v1/x")));

        assertThat(registry.findByHttpKey("GET", "/v1/x")).isPresent();
    }

    @Test
    @DisplayName("GIVEN a losing route WHEN looking up its context SHOULD still resolve, since it stays a known route")
    void given_losingRoute_when_lookingUpContext_should_stillResolve() {
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        RouteDefinition loser = restRoute("EveAllianceService", "getByIds", "GET", "/v1/alliances");
        registry.registerContext(
                context("alliance-svc", restRoute("AllianceService", "getByIds", "GET", "/v1/alliances")));
        registry.registerContext(context("org-svc", loser));

        // The route is unreachable over REST but still belongs to a context — other surfaces (the
        // gRPC proxy, the UI) enumerate routes independently and must not hit a null context.
        assertThat(registry.contextFor(loser)).isNotNull();
    }
}
