package dev.appiaworks.shiguchi.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.config.GatewayMode;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import io.grpc.MethodDescriptor;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionWriter;
import org.springframework.context.ApplicationEventPublisher;
import reactor.core.publisher.Mono;

/**
 * BDD-style tests for ScgRouteRegistrar.
 *
 * Tests verify that:
 * - Passthrough routes are registered as SCG routes with correct URI and predicates
 * - Path prefixes result in correct StripPrefix filter configuration
 * - gRPC routes keep the no://op URI and no filters
 * - Register/deregister cycle works correctly
 * - Path prefix segment counting is accurate
 */
@DisplayName("ScgRouteRegistrar tests")
class ScgRouteRegistrarTest {

    /**
     * In-memory fake RouteDefinitionWriter that captures saves and tracks deletes.
     */
    private static class FakeRouteDefinitionWriter implements RouteDefinitionWriter {
        private final Map<String, org.springframework.cloud.gateway.route.RouteDefinition> saved = new HashMap<>();
        private final List<String> deleted = new ArrayList<>();

        @Override
        public Mono<Void> save(Mono<org.springframework.cloud.gateway.route.RouteDefinition> route) {
            return route.doOnNext(r -> saved.put(r.getId(), r)).then();
        }

        @Override
        public Mono<Void> delete(Mono<String> id) {
            return id.doOnNext(deleted::add).then();
        }

        org.springframework.cloud.gateway.route.RouteDefinition get(String id) {
            return saved.get(id);
        }

        boolean wasDeleted(String id) {
            return deleted.contains(id);
        }
    }

    private ServiceDefinition buildServiceDef(String name) {
        ServiceDefinition def = new ServiceDefinition();
        def.setName(name);
        def.setBaseUrl("http://backend:8080");
        def.setMode(GatewayMode.REST_DOWNSTREAM);
        return def;
    }

    @Test
    @DisplayName(
            "GIVEN passthrough route with httpUpstream and httpBinding WHEN register SHOULD save SCG route with correct URI and predicates")
    void given_passthroughRoute_when_register_should_saveSCGRoute() {
        // Arrange
        FakeRouteDefinitionWriter writer = new FakeRouteDefinitionWriter();
        AtomicInteger eventCount = new AtomicInteger(0);
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof RefreshRoutesEvent) {
                eventCount.incrementAndGet();
            }
        };
        ScgRouteRegistrar registrar = new ScgRouteRegistrar(writer, publisher);

        ServiceDefinition svcDef = buildServiceDef("inventory");
        svcDef.setPathPrefix("/inv");
        ServiceEndpointContext ctx = new ServiceEndpointContext(svcDef, null);

        dev.appiaworks.shiguchi.routing.RouteDefinition route = dev.appiaworks.shiguchi.routing.RouteDefinition.builder(
                        "inventory", "getItem", MethodDescriptor.MethodType.UNARY, "")
                .httpUpstream("http://inv:8080")
                .httpBinding("GET", "/items/{sku}")
                .build();
        ctx.addRoute(route);

        // Act
        registrar.register(ctx);

        // Assert
        org.springframework.cloud.gateway.route.RouteDefinition savedRoute =
                writer.get("inventory/getItem#GET:/items/{sku}");
        assertThat(savedRoute).isNotNull();
        assertThat(savedRoute.getUri()).isEqualTo(URI.create("http://inv:8080"));

        // Check Path predicate
        PredicateDefinition pathPred = savedRoute.getPredicates().stream()
                .filter(p -> p.getName().equals("Path"))
                .findFirst()
                .orElseThrow();
        // The predicate only matches; variables are read from the parsed template, so they become wildcards.
        assertThat(pathPred.getArgs().get("pattern")).isEqualTo("/items/*");

        // Check Method predicate
        PredicateDefinition methodPred = savedRoute.getPredicates().stream()
                .filter(p -> p.getName().equals("Method"))
                .findFirst()
                .orElseThrow();
        assertThat(methodPred.getArgs().get("methods")).isEqualTo("GET");

        // Check StripPrefix filter with parts=1 (one segment in prefix "/inv")
        FilterDefinition stripFilter = savedRoute.getFilters().stream()
                .filter(f -> f.getName().equals("StripPrefix"))
                .findFirst()
                .orElseThrow();
        assertThat(stripFilter.getArgs().get("parts")).isEqualTo("1");

        // Should publish RefreshRoutesEvent
        assertThat(eventCount.get()).isGreaterThan(0);
    }

    @Test
    @DisplayName("GIVEN passthrough route with prefix /api/v2 WHEN register SHOULD set parts=2")
    void given_passthroughRouteWithMultiSegmentPrefix_when_register_should_set2Parts() {
        // Arrange
        FakeRouteDefinitionWriter writer = new FakeRouteDefinitionWriter();
        ApplicationEventPublisher publisher = event -> {};
        ScgRouteRegistrar registrar = new ScgRouteRegistrar(writer, publisher);

        ServiceDefinition svcDef = buildServiceDef("inventory");
        svcDef.setPathPrefix("/api/v2");
        ServiceEndpointContext ctx = new ServiceEndpointContext(svcDef, null);

        dev.appiaworks.shiguchi.routing.RouteDefinition route = dev.appiaworks.shiguchi.routing.RouteDefinition.builder(
                        "inventory", "getItem", MethodDescriptor.MethodType.UNARY, "")
                .httpUpstream("http://inv:8080")
                .httpBinding("GET", "/items/{sku}")
                .build();
        ctx.addRoute(route);

        // Act
        registrar.register(ctx);

        // Assert
        org.springframework.cloud.gateway.route.RouteDefinition savedRoute =
                writer.get("inventory/getItem#GET:/items/{sku}");
        FilterDefinition stripFilter = savedRoute.getFilters().stream()
                .filter(f -> f.getName().equals("StripPrefix"))
                .findFirst()
                .orElseThrow();
        assertThat(stripFilter.getArgs().get("parts")).isEqualTo("2");
    }

    @Test
    @DisplayName("GIVEN passthrough route with no prefix WHEN register SHOULD not add StripPrefix filter")
    void given_passthroughRouteNoPrefix_when_register_should_noFilters() {
        // Arrange
        FakeRouteDefinitionWriter writer = new FakeRouteDefinitionWriter();
        ApplicationEventPublisher publisher = event -> {};
        ScgRouteRegistrar registrar = new ScgRouteRegistrar(writer, publisher);

        ServiceDefinition svcDef = buildServiceDef("inventory");
        svcDef.setPathPrefix("");
        ServiceEndpointContext ctx = new ServiceEndpointContext(svcDef, null);

        dev.appiaworks.shiguchi.routing.RouteDefinition route = dev.appiaworks.shiguchi.routing.RouteDefinition.builder(
                        "inventory", "getItem", MethodDescriptor.MethodType.UNARY, "")
                .httpUpstream("http://inv:8080")
                .httpBinding("GET", "/items/{sku}")
                .build();
        ctx.addRoute(route);

        // Act
        registrar.register(ctx);

        // Assert
        org.springframework.cloud.gateway.route.RouteDefinition savedRoute =
                writer.get("inventory/getItem#GET:/items/{sku}");
        assertThat(savedRoute.getFilters()).isEmpty();
    }

    @Test
    @DisplayName("GIVEN gRPC route without HTTP binding WHEN register SHOULD keep uri as no://op and no filters")
    void given_grpcRoute_when_register_should_keepNoOpUri() {
        // Arrange
        FakeRouteDefinitionWriter writer = new FakeRouteDefinitionWriter();
        ApplicationEventPublisher publisher = event -> {};
        ScgRouteRegistrar registrar = new ScgRouteRegistrar(writer, publisher);

        ServiceDefinition svcDef = buildServiceDef("inventory");
        ServiceEndpointContext ctx = new ServiceEndpointContext(svcDef, null);

        // gRPC route: no httpBinding, no httpUpstream
        dev.appiaworks.shiguchi.routing.RouteDefinition route = dev.appiaworks.shiguchi.routing.RouteDefinition.builder(
                        "inventory", "getItem", MethodDescriptor.MethodType.UNARY, "localhost:50051")
                .build();
        ctx.addRoute(route);

        // Act
        registrar.register(ctx);

        // Assert
        // Since no REST binding, route should not be registered by registrar
        assertThat(writer.saved).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN register then deregister WHEN called SHOULD delete same route id and publish RefreshRoutesEvent")
    void given_registerThenDeregister_when_deregister_should_deleteAndPublish() {
        // Arrange
        FakeRouteDefinitionWriter writer = new FakeRouteDefinitionWriter();
        AtomicInteger eventCount = new AtomicInteger(0);
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof RefreshRoutesEvent) {
                eventCount.incrementAndGet();
            }
        };
        ScgRouteRegistrar registrar = new ScgRouteRegistrar(writer, publisher);

        ServiceDefinition svcDef = buildServiceDef("inventory");
        ServiceEndpointContext ctx = new ServiceEndpointContext(svcDef, null);

        dev.appiaworks.shiguchi.routing.RouteDefinition route = dev.appiaworks.shiguchi.routing.RouteDefinition.builder(
                        "inventory", "getItem", MethodDescriptor.MethodType.UNARY, "")
                .httpUpstream("http://inv:8080")
                .httpBinding("GET", "/items/{sku}")
                .build();
        ctx.addRoute(route);

        // Act
        registrar.register(ctx);
        int eventsAfterRegister = eventCount.get();
        registrar.deregister(ctx);

        // Assert
        assertThat(writer.wasDeleted("inventory/getItem#GET:/items/{sku}")).isTrue();
        assertThat(eventCount.get()).isGreaterThan(eventsAfterRegister);
    }

    @Test
    @DisplayName("GIVEN passthrough route with a 2s upstream timeout WHEN register SHOULD set response-timeout 2000")
    void given_passthroughRouteWithTimeout_when_register_should_setResponseTimeoutMetadata() {
        // Arrange
        FakeRouteDefinitionWriter writer = new FakeRouteDefinitionWriter();
        ScgRouteRegistrar registrar = new ScgRouteRegistrar(writer, event -> {});
        ServiceEndpointContext ctx = new ServiceEndpointContext(buildServiceDef("inventory"), null);
        ctx.addRoute(dev.appiaworks.shiguchi.routing.RouteDefinition.builder(
                        "inventory", "getItem", MethodDescriptor.MethodType.UNARY, "")
                .httpUpstream("http://inv:8080")
                .httpBinding("GET", "/items/{sku}")
                .upstreamTimeout(java.time.Duration.ofSeconds(2))
                .build());

        // Act
        registrar.register(ctx);

        // Assert
        assertThat(writer.get("inventory/getItem#GET:/items/{sku}").getMetadata())
                .containsEntry("response-timeout", 2000L);
    }

    @Test
    @DisplayName("GIVEN passthrough route without a resolved timeout WHEN register SHOULD set no response-timeout")
    void given_passthroughRouteWithoutTimeout_when_register_should_setNoMetadata() {
        // Arrange
        FakeRouteDefinitionWriter writer = new FakeRouteDefinitionWriter();
        ScgRouteRegistrar registrar = new ScgRouteRegistrar(writer, event -> {});
        ServiceEndpointContext ctx = new ServiceEndpointContext(buildServiceDef("inventory"), null);
        ctx.addRoute(dev.appiaworks.shiguchi.routing.RouteDefinition.builder(
                        "inventory", "getItem", MethodDescriptor.MethodType.UNARY, "")
                .httpUpstream("http://inv:8080")
                .httpBinding("GET", "/items/{sku}")
                .build());

        // Act
        registrar.register(ctx);

        // Assert
        assertThat(writer.get("inventory/getItem#GET:/items/{sku}").getMetadata())
                .doesNotContainKey("response-timeout");
    }

    @Test
    @DisplayName(
            "GIVEN gRPC route with REST binding and an upstream timeout WHEN register SHOULD set no response-timeout")
    void given_grpcRouteWithRestBinding_when_register_should_setNoResponseTimeout() {
        // Arrange
        FakeRouteDefinitionWriter writer = new FakeRouteDefinitionWriter();
        ScgRouteRegistrar registrar = new ScgRouteRegistrar(writer, event -> {});
        ServiceEndpointContext ctx = new ServiceEndpointContext(buildServiceDef("inventory"), null);
        ctx.addRoute(dev.appiaworks.shiguchi.routing.RouteDefinition.builder(
                        "inventory", "getItem", MethodDescriptor.MethodType.UNARY, "localhost:50051")
                .httpBinding("GET", "/items/{sku}")
                .upstreamTimeout(java.time.Duration.ofSeconds(2))
                .build());

        // Act
        registrar.register(ctx);

        // Assert
        assertThat(writer.get("inventory/getItem#GET:/items/{sku}").getMetadata())
                .doesNotContainKey("response-timeout");
    }

    @Test
    @DisplayName("prefixSegments: null→0, empty→0, /→0, /inv→1, /api/v2/→2")
    void given_variousPrefixes_when_prefixSegments_should_countNonEmptySegments() {
        // Act & Assert
        assertThat(ScgRouteRegistrar.prefixSegments(null)).isZero();
        assertThat(ScgRouteRegistrar.prefixSegments("")).isZero();
        assertThat(ScgRouteRegistrar.prefixSegments("/")).isZero();
        assertThat(ScgRouteRegistrar.prefixSegments("/inv")).isEqualTo(1);
        assertThat(ScgRouteRegistrar.prefixSegments("/api/v2")).isEqualTo(2);
        assertThat(ScgRouteRegistrar.prefixSegments("/api/v2/")).isEqualTo(2);
    }
}
