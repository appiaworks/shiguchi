package dev.appiaworks.shiguchi.routing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.config.ServiceDefinition;
import io.grpc.MethodDescriptor.MethodType;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Template-path routing through the prefix-bucketed index: a request path must reach the template
 * that matches it regardless of where the variables sit, the method must be honoured, and the
 * cost must not depend on how many unrelated templates are registered.
 */
@DisplayName("GrpcRouteRegistry template-path lookup")
class GrpcRouteRegistryTemplateTest {

    private static ServiceEndpointContext context(String name, RouteDefinition... routes) {
        var def = new ServiceDefinition();
        def.setName(name);
        var ctx = new ServiceEndpointContext(def, null);
        for (RouteDefinition r : routes) ctx.addRoute(r);
        return ctx;
    }

    private static RouteDefinition route(String method, String path, String rpc) {
        return RouteDefinition.builder("t.Svc", rpc, MethodType.UNARY, "svc")
                .httpBinding(method, path)
                .build();
    }

    @Test
    @DisplayName("GIVEN a template with a trailing variable WHEN a matching path is looked up SHOULD resolve it")
    void given_trailingVariable_when_lookup_should_resolve() {
        var registry = new GrpcRouteRegistry();
        registry.registerContext(context("svc", route("GET", "/v1/orders/{id}", "Get")));

        Optional<RouteDefinition> found = registry.findByHttpKey("GET", "/v1/orders/42");

        assertThat(found).map(RouteDefinition::getMethodName).contains("Get");
    }

    @Test
    @DisplayName(
            "GIVEN a template whose first segment is a variable WHEN looked up SHOULD still resolve (empty prefix bucket)")
    void given_leadingVariable_when_lookup_should_resolve() {
        var registry = new GrpcRouteRegistry();
        registry.registerContext(context("svc", route("GET", "/{tenant}/orders", "ListForTenant")));

        assertThat(registry.findByHttpKey("GET", "/acme/orders"))
                .map(RouteDefinition::getMethodName)
                .contains("ListForTenant");
    }

    @Test
    @DisplayName(
            "GIVEN variables in the middle of the path WHEN looked up SHOULD resolve via the static prefix before them")
    void given_variableInMiddle_when_lookup_should_resolve() {
        var registry = new GrpcRouteRegistry();
        registry.registerContext(context("svc", route("GET", "/v1/orders/{id}/items/{sku}", "GetItem")));

        assertThat(registry.findByHttpKey("GET", "/v1/orders/42/items/sku-1"))
                .map(RouteDefinition::getMethodName)
                .contains("GetItem");
        assertThat(registry.findByHttpKey("GET", "/v1/orders/42/items")).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN a catch-all and a more specific template WHEN both match SHOULD prefer the longer static prefix")
    void given_catchAllAndSpecific_when_bothMatch_should_preferSpecific() {
        var registry = new GrpcRouteRegistry();
        registry.registerContext(
                context("svc", route("GET", "/{*rest}", "CatchAll"), route("GET", "/v1/orders/{id}", "Get")));

        assertThat(registry.findByHttpKey("GET", "/v1/orders/42"))
                .map(RouteDefinition::getMethodName)
                .contains("Get");
        assertThat(registry.findByHttpKey("GET", "/anything/else"))
                .map(RouteDefinition::getMethodName)
                .contains("CatchAll");
    }

    @Test
    @DisplayName(
            "GIVEN the same template on two methods WHEN looked up SHOULD honour the HTTP method, case-insensitively")
    void given_sameTemplateTwoMethods_when_lookup_should_honourMethod() {
        var registry = new GrpcRouteRegistry();
        registry.registerContext(
                context("svc", route("GET", "/v1/orders/{id}", "Get"), route("DELETE", "/v1/orders/{id}", "Delete")));

        assertThat(registry.findByHttpKey("delete", "/v1/orders/42"))
                .map(RouteDefinition::getMethodName)
                .contains("Delete");
        assertThat(registry.findByHttpKey("PATCH", "/v1/orders/42")).isEmpty();
    }

    @Test
    @DisplayName("GIVEN an exact route and a template covering the same path WHEN looked up SHOULD take the exact one")
    void given_exactAndTemplate_when_lookup_should_preferExact() {
        var registry = new GrpcRouteRegistry();
        registry.registerContext(
                context("svc", route("GET", "/v1/orders/{id}", "Get"), route("GET", "/v1/orders/latest", "Latest")));

        assertThat(registry.findByHttpKey("GET", "/v1/orders/latest"))
                .map(RouteDefinition::getMethodName)
                .contains("Latest");
    }

    @Test
    @DisplayName("GIVEN thousands of unrelated templates WHEN one is looked up SHOULD resolve without scanning them")
    void given_manyTemplates_when_lookup_should_resolve() {
        var registry = new GrpcRouteRegistry();
        RouteDefinition[] routes = new RouteDefinition[5000];
        for (int i = 0; i < routes.length; i++) {
            routes[i] = RouteDefinition.builder("t.Svc" + i, "Get", MethodType.UNARY, "svc")
                    .httpBinding("GET", "/v1/svc" + i + "/{id}")
                    .build();
        }
        registry.registerContext(context("svc", routes));

        assertThat(registry.findByHttpKey("GET", "/v1/svc4999/7"))
                .map(RouteDefinition::getFullServiceName)
                .contains("t.Svc4999");
        assertThat(registry.findByHttpKey("GET", "/v1/nope/7")).isEmpty();
    }

    @Test
    @DisplayName("GIVEN templates WHEN the static prefix is derived SHOULD stop at the first variable or wildcard")
    void given_templates_when_staticPrefixDerived_should_stopAtFirstVariable() {
        assertThat(GrpcRouteRegistry.staticPrefix("/v1/orders/{id}")).isEqualTo("/v1/orders");
        assertThat(GrpcRouteRegistry.staticPrefix("/v1/orders/{id}/items/{sku}"))
                .isEqualTo("/v1/orders");
        assertThat(GrpcRouteRegistry.staticPrefix("/{tenant}/orders")).isEmpty();
        assertThat(GrpcRouteRegistry.staticPrefix("/files/{*path}")).isEqualTo("/files");
        assertThat(GrpcRouteRegistry.staticPrefix("/a/b/**")).isEqualTo("/a/b");
        assertThat(List.of(GrpcRouteRegistry.staticPrefix("v1/x/{id}"))).containsExactly("/v1/x");
    }

    @Test
    @DisplayName(
            "GIVEN resource-name templates with patterns and a verb WHEN paths are looked up SHOULD route each to its method")
    void given_resourceNameTemplates_when_lookup_should_resolve() {
        var registry = new GrpcRouteRegistry();
        registry.registerContext(context(
                "svc",
                route("GET", "/v1/{name=shelves/*/books/*}", "GetBook"),
                route("POST", "/v1/{name=shelves/*/books/*}:publish", "PublishBook"),
                route("GET", "/v1/files/{path=**}", "ReadFile")));

        assertThat(registry.findByHttpKey("GET", "/v1/shelves/s1/books/b2"))
                .get()
                .extracting(RouteDefinition::getMethodName)
                .isEqualTo("GetBook");
        assertThat(registry.findByHttpKey("POST", "/v1/shelves/s1/books/b2:publish"))
                .get()
                .extracting(RouteDefinition::getMethodName)
                .isEqualTo("PublishBook");
        assertThat(registry.findByHttpKey("GET", "/v1/files/a/b/c.txt"))
                .get()
                .extracting(RouteDefinition::getMethodName)
                .isEqualTo("ReadFile");
        assertThat(registry.findByHttpKey("GET", "/v1/shelves/s1")).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN one route with a malformed template WHEN the context registers SHOULD skip that route and keep the others")
    void given_malformedTemplate_when_registered_should_skipOnlyThatRoute() {
        var registry = new GrpcRouteRegistry();
        registry.registerContext(
                context("svc", route("GET", "/v1/{name=**}/tail", "Broken"), route("GET", "/v1/orders/{id}", "Get")));

        assertThat(registry.findByHttpKey("GET", "/v1/orders/42"))
                .get()
                .extracting(RouteDefinition::getMethodName)
                .isEqualTo("Get");
        assertThat(registry.findByHttpKey("GET", "/v1/x/tail")).isEmpty();
    }
}
