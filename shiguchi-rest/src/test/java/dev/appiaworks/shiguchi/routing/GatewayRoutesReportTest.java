package dev.appiaworks.shiguchi.routing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import io.grpc.MethodDescriptor.MethodType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * What the {@code gateway-routes} endpoint reports. The interesting cases are the ones someone
 * actually hits it for: a service that did not load, and a route that is there but not where the
 * caller thought.
 */
@DisplayName("GatewayRoutesReport")
class GatewayRoutesReportTest {

    private final GatewayProperties properties = new GatewayProperties();
    private final GrpcRouteRegistry registry = new GrpcRouteRegistry();
    private final ServiceLoadStatus loadStatus = new ServiceLoadStatus();

    private GatewayRoutesReport report;

    @BeforeEach
    void wire() {
        report = new GatewayRoutesReport(properties, registry, provider(loadStatus));
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return value;
            }

            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }
        };
    }

    private ServiceDefinition configure(String name) {
        var svc = new ServiceDefinition();
        svc.setName(name);
        svc.setHost("localhost");
        svc.setPort(9090);
        properties.getServices().add(svc);
        return svc;
    }

    /** Registers a loaded context with the given routes. */
    private void load(ServiceDefinition svc, RouteDefinition... routes) {
        var ctx = new ServiceEndpointContext(svc, null);
        for (RouteDefinition route : routes) ctx.addRoute(route);
        registry.registerContext(ctx);
        loadStatus.recordLoaded(svc.getName());
    }

    private static RouteDefinition restRoute(String method, String httpMethod, String path) {
        return RouteDefinition.builder("shop.Catalog", method, MethodType.UNARY, "")
                .httpBinding(httpMethod, path)
                .build();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> services(Map<String, Object> report) {
        return (List<Map<String, Object>>) report.get("services");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> routes(Map<String, Object> service) {
        return (List<Map<String, Object>>) service.get("routes");
    }

    @Test
    @DisplayName("GIVEN a loaded service WHEN reporting SHOULD list it with its routes and where each one goes")
    void given_loadedService_when_reporting_should_listRoutes() {
        var svc = configure("catalog");
        load(svc, restRoute("GetItem", "GET", "/v1/items/{id}"));

        Map<String, Object> result = report.report();

        assertThat(result)
                .containsEntry("configured", 1)
                .containsEntry("loaded", 1)
                .containsEntry("routeCount", 1);
        Map<String, Object> service = services(result).get(0);
        assertThat(service).containsEntry("name", "catalog").containsEntry("loaded", true);
        assertThat(routes(service).get(0))
                .containsEntry("method", "shop.Catalog/GetItem")
                .containsEntry("http", "GET /v1/items/{id}")
                .containsEntry("type", "UNARY");
    }

    @Test
    @DisplayName(
            "GIVEN a service that failed to load WHEN reporting SHOULD say so and carry the error, which is why anyone reads this")
    void given_failedService_when_reporting_should_carryTheError() {
        configure("orders");
        loadStatus.recordFailure("orders", "startup", new IllegalStateException("connection refused"));

        Map<String, Object> service = services(report.report()).get(0);

        assertThat(service)
                .containsEntry("name", "orders")
                .containsEntry("loaded", false)
                .containsEntry("lastError", "connection refused")
                .containsEntry("attempts", 1)
                .containsEntry("routeCount", 0);
    }

    @Test
    @DisplayName("GIVEN several routes WHEN reporting SHOULD sort them, so two calls give the same answer")
    void given_severalRoutes_when_reporting_should_sortThem() {
        var svc = configure("catalog");
        load(
                svc,
                restRoute("ListItems", "GET", "/v1/items"),
                restRoute("DeleteItem", "DELETE", "/v1/items/{id}"),
                restRoute("AddItem", "POST", "/v1/items"));

        List<Map<String, Object>> reported = routes(services(report.report()).get(0));

        assertThat(reported)
                .extracting(r -> r.get("method"))
                .containsExactly("shop.Catalog/AddItem", "shop.Catalog/DeleteItem", "shop.Catalog/ListItems");
    }

    @Test
    @DisplayName(
            "GIVEN a named service WHEN asked for it SHOULD return just that one, and null for a name that does not exist")
    void given_serviceName_when_asked_should_returnOnlyThatOne() {
        var catalog = configure("catalog");
        load(catalog, restRoute("GetItem", "GET", "/v1/items/{id}"));
        configure("orders");

        assertThat(report.service("catalog")).containsEntry("name", "catalog");
        assertThat(report.service("orders")).containsEntry("name", "orders").containsEntry("loaded", false);
        assertThat(report.service("nonexistent")).isNull();
    }

    @Test
    @DisplayName("GIVEN a route with scopes and a declared idempotency WHEN reporting SHOULD include them")
    void given_annotatedRoute_when_reporting_should_includeAnnotations() {
        var svc = configure("catalog");
        var route = RouteDefinition.builder("shop.Catalog", "GetItem", MethodType.UNARY, "")
                .httpBinding("GET", "/v1/items/{id}")
                .authScopes(List.of("catalog.read"))
                .idempotencyLevel(RouteDefinition.IdempotencyLevel.NO_SIDE_EFFECTS)
                .build();
        load(svc, route);

        assertThat(routes(services(report.report()).get(0)).get(0))
                .containsEntry("scopes", List.of("catalog.read"))
                .containsEntry("idempotency", "NO_SIDE_EFFECTS");
    }

    @Test
    @DisplayName(
            "GIVEN a route whose proto declared no idempotency WHEN reporting SHOULD omit the field rather than print UNKNOWN on every row")
    void given_undeclaredIdempotency_when_reporting_should_omitIt() {
        var svc = configure("catalog");
        load(svc, restRoute("GetItem", "GET", "/v1/items/{id}"));

        assertThat(routes(services(report.report()).get(0)).get(0)).doesNotContainKey("idempotency");
    }

    @Test
    @DisplayName("GIVEN nothing configured WHEN reporting SHOULD answer with empty counts rather than fail")
    void given_nothingConfigured_when_reporting_should_answerEmpty() {
        Map<String, Object> result = report.report();

        assertThat(result).containsEntry("configured", 0).containsEntry("routeCount", 0);
        assertThat(services(result)).isEmpty();
    }
}
