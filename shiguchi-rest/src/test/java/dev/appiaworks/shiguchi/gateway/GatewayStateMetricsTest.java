package dev.appiaworks.shiguchi.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.grpc.GrpcDescriptorCache;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import dev.appiaworks.shiguchi.routing.ServiceLoadStatus;
import io.grpc.MethodDescriptor.MethodType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

@DisplayName("GatewayStateMetrics")
class GatewayStateMetricsTest {

    private final MeterRegistry meters = new SimpleMeterRegistry();
    private final GatewayProperties properties = new GatewayProperties();
    private final GrpcRouteRegistry registry = new GrpcRouteRegistry();
    private final ServiceLoadStatus loadStatus = new ServiceLoadStatus();

    private GatewayStateMetrics metrics;

    @BeforeEach
    void wire() {
        metrics = new GatewayStateMetrics(
                provider(meters), properties, registry, provider(loadStatus), provider(new GrpcDescriptorCache()));
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

    private double gauge(String name) {
        return meters.get(name).gauge().value();
    }

    private void configureAndLoad(String name) {
        var svc = new ServiceDefinition();
        svc.setName(name);
        svc.setHost("localhost");
        svc.setPort(9090);
        properties.getServices().add(svc);
        var ctx = new ServiceEndpointContext(svc, null);
        ctx.addRoute(RouteDefinition.builder("shop.Catalog", "GetItem", MethodType.UNARY, "")
                .httpBinding("GET", "/v1/items/{id}")
                .build());
        registry.registerContext(ctx);
    }

    @Test
    @DisplayName(
            "GIVEN services load after startup WHEN the gauges are read SHOULD reflect the state as it is now, not as it was when registered")
    void given_servicesLoadLater_when_reading_should_reflectCurrentState() {
        assertThat(gauge(GatewayStateMetrics.SERVICES_CONFIGURED)).isZero();
        assertThat(gauge(GatewayStateMetrics.ROUTES)).isZero();

        configureAndLoad("catalog");

        // The gauges read live state, so a refresh that changes the route count needs nobody to
        // remember to update a number.
        assertThat(gauge(GatewayStateMetrics.SERVICES_CONFIGURED)).isEqualTo(1);
        assertThat(gauge(GatewayStateMetrics.SERVICES_LOADED)).isEqualTo(1);
        assertThat(gauge(GatewayStateMetrics.ROUTES)).isEqualTo(1);
    }

    @Test
    @DisplayName("GIVEN a load attempt WHEN it succeeds or fails SHOULD count it by service, phase and outcome")
    void given_loadAttempts_when_recorded_should_countByOutcome() {
        loadStatus.recordLoaded("catalog");
        loadStatus.recordFailure("orders", "refresh", new IllegalStateException("nope"));

        assertThat(meters.get(GatewayStateMetrics.SERVICE_LOADS)
                        .tag("service", "catalog")
                        .tag("phase", "initial")
                        .tag("outcome", "loaded")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(meters.get(GatewayStateMetrics.SERVICE_LOADS)
                        .tag("service", "orders")
                        .tag("phase", "refresh")
                        .tag("outcome", "failed")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName(
            "GIVEN a service loads twice WHEN counted SHOULD tell a reload apart from the first load, since a reload loop is what you are looking for")
    void given_reload_when_counted_should_distinguishItFromInitial() {
        loadStatus.recordLoaded("catalog");
        loadStatus.recordLoaded("catalog");
        loadStatus.recordLoaded("catalog");

        assertThat(meters.get(GatewayStateMetrics.SERVICE_LOADS)
                        .tag("service", "catalog")
                        .tag("phase", "initial")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(meters.get(GatewayStateMetrics.SERVICE_LOADS)
                        .tag("service", "catalog")
                        .tag("phase", "reload")
                        .counter()
                        .count())
                .isEqualTo(2);
    }

    @Test
    @DisplayName(
            "GIVEN a discovery service WHEN its instance count changes SHOULD track the latest value, including a drop to zero")
    void given_discoveryInstances_when_changing_should_trackLatest() {
        metrics.recordDiscoveryInstances("orders", 3);

        assertThat(meters.get(GatewayStateMetrics.DISCOVERY_INSTANCES)
                        .tag("service", "orders")
                        .gauge()
                        .value())
                .isEqualTo(3);

        // The case a dashboard exists for.
        metrics.recordDiscoveryInstances("orders", 0);

        assertThat(meters.get(GatewayStateMetrics.DISCOVERY_INSTANCES)
                        .tag("service", "orders")
                        .gauge()
                        .value())
                .isZero();
    }

    @Test
    @DisplayName("GIVEN two discovery services WHEN both resolve SHOULD keep a separate gauge per service")
    void given_twoDiscoveryServices_when_resolving_should_keepThemApart() {
        metrics.recordDiscoveryInstances("orders", 2);
        metrics.recordDiscoveryInstances("catalog", 5);

        assertThat(meters.get(GatewayStateMetrics.DISCOVERY_INSTANCES)
                        .tag("service", "orders")
                        .gauge()
                        .value())
                .isEqualTo(2);
        assertThat(meters.get(GatewayStateMetrics.DISCOVERY_INSTANCES)
                        .tag("service", "catalog")
                        .gauge()
                        .value())
                .isEqualTo(5);
    }

    @Test
    @DisplayName(
            "GIVEN a listener that throws WHEN an outcome is recorded SHOULD still record it, because counting must not break loading")
    void given_failingListener_when_recording_should_notBreakLoading() {
        loadStatus.addOutcomeListener((service, phase, loaded) -> {
            throw new IllegalStateException("listener is broken");
        });

        loadStatus.recordLoaded("catalog");

        assertThat(loadStatus.failure("catalog")).isEmpty();
        assertThat(meters.get(GatewayStateMetrics.SERVICE_LOADS)
                        .tag("service", "catalog")
                        .counter()
                        .count())
                .isEqualTo(1);
    }
}
