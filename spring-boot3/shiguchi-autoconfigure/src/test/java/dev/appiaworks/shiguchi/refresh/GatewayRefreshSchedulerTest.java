package dev.appiaworks.shiguchi.refresh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import dev.appiaworks.shiguchi.config.GatewayMode;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.gateway.ScgRouteRegistrar;
import dev.appiaworks.shiguchi.grpc.GrpcDescriptorCache;
import dev.appiaworks.shiguchi.rest.MergeSpecResolver;
import dev.appiaworks.shiguchi.routing.DynamicRouteLoader;
import dev.appiaworks.shiguchi.routing.GrpcChannelFactory;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.RestDownstreamRouteLoader;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.SchemaReloadListener;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import dev.appiaworks.shiguchi.serde.DynamicProtoSerde;
import dev.appiaworks.shiguchi.serde.DynamicSerializerFactory;
import dev.appiaworks.shiguchi.serde.StaticSerdeDetector;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor.MethodType;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * BDD-style unit tests for {@link GatewayRefreshScheduler}, constructed directly (no Spring
 * context) with hand-built fakes — {@link GatewayRefreshSchedulerWiringTest} already covers bean
 * wiring end to end.
 *
 * <p>{@link DynamicRouteLoader} and {@link RestDownstreamRouteLoader} are not interfaces, but
 * neither class nor its {@code buildContext} method is {@code final}; each test that needs a
 * controllable "reload" result subclasses the loader and overrides {@code buildContext} to hand
 * back a pre-built {@link ServiceEndpointContext} (or throw), mirroring the hand-written-fake
 * style already used for {@code SchemaReloadListener} in {@code RestDownstreamRouteLoaderTest}.
 * {@link ScgRouteRegistrar} is mocked, matching {@code DynamicRouteLoaderTest} and
 * {@code StaticRouteLoaderTest}.
 *
 * <p>Boot 3 / Boot 4: {@code GatewayRefreshScheduler} is byte-for-byte identical between the two
 * module lines (only a comment differs) as of this writing, so this test is mirrored verbatim in
 * {@code spring-boot4/shiguchi-autoconfigure}.
 */
@DisplayName("GatewayRefreshScheduler")
class GatewayRefreshSchedulerTest {

    // -------------------------------------------------------------------------
    // Fakes
    // -------------------------------------------------------------------------

    /** Hands back pre-built contexts (or throws) instead of touching a real backend. */
    private static class FakeDynamicRouteLoader extends DynamicRouteLoader {
        private final java.util.Map<String, ServiceEndpointContext> byName;
        private final java.util.Set<String> failing;
        private final AtomicInteger buildCalls = new AtomicInteger();

        FakeDynamicRouteLoader(
                GatewayProperties properties,
                GrpcRouteRegistry registry,
                java.util.Map<String, ServiceEndpointContext> byName,
                java.util.Set<String> failing) {
            super(
                    properties,
                    registry,
                    Optional.empty(),
                    new DynamicProtoSerde(new GrpcDescriptorCache()),
                    new StaticSerdeDetector(),
                    new DynamicSerializerFactory(),
                    mock(GrpcChannelFactory.class),
                    noopMergeResolver());
            this.byName = byName;
            this.failing = failing;
        }

        @Override
        public ServiceEndpointContext buildContext(ServiceDefinition svcDef) throws Exception {
            buildCalls.incrementAndGet();
            if (failing.contains(svcDef.getName())) {
                throw new java.io.IOException("simulated backend failure for " + svcDef.getName());
            }
            ServiceEndpointContext ctx = byName.get(svcDef.getName());
            if (ctx == null) {
                throw new IllegalStateException("no fake context configured for " + svcDef.getName());
            }
            return ctx;
        }
    }

    /** Same idea for the REST_DOWNSTREAM loader. */
    private static class FakeRestDownstreamRouteLoader extends RestDownstreamRouteLoader {
        private final java.util.Map<String, ServiceEndpointContext> byName;

        FakeRestDownstreamRouteLoader(
                GatewayProperties properties,
                GrpcRouteRegistry registry,
                java.util.Map<String, ServiceEndpointContext> byName) {
            super(properties, registry, Optional.empty(), Optional.of(WebClient.builder()));
            this.byName = byName;
        }

        @Override
        public ServiceEndpointContext buildContext(ServiceDefinition svcDef) {
            ServiceEndpointContext ctx = byName.get(svcDef.getName());
            if (ctx == null) {
                throw new IllegalStateException("no fake context configured for " + svcDef.getName());
            }
            return ctx;
        }
    }

    private static MergeSpecResolver noopMergeResolver() {
        MergeSpecResolver resolver = mock(MergeSpecResolver.class);
        return resolver;
    }

    // -------------------------------------------------------------------------
    // Context / route building helpers
    // -------------------------------------------------------------------------

    private static ServiceDefinition dynamicServiceDef(String name) {
        ServiceDefinition def = new ServiceDefinition();
        def.setName(name);
        def.setMode(GatewayMode.DYNAMIC);
        def.setHost("localhost");
        def.setPort(9090);
        return def;
    }

    private static GatewayProperties propsWith(ServiceDefinition... services) {
        GatewayProperties props = new GatewayProperties();
        props.setServices(List.of(services));
        return props;
    }

    /** Builds a minimal context with one HTTP-bound route and a given descriptor-hash byte. */
    private static ServiceEndpointContext contextWithHash(ServiceDefinition svcDef, byte hashByte) {
        ServiceEndpointContext ctx = new ServiceEndpointContext(svcDef, mock(ManagedChannel.class));
        RouteDefinition route = RouteDefinition.builder(
                        "demo." + svcDef.getName(), "List", MethodType.UNARY, svcDef.getTarget())
                .httpBinding("GET", "/" + svcDef.getName())
                .build();
        ctx.addRoute(route);
        ctx.setDescriptorHash(new byte[] {hashByte});
        return ctx;
    }

    private GatewayRefreshScheduler buildScheduler(
            GatewayProperties props,
            GrpcRouteRegistry registry,
            DynamicRouteLoader dynamicLoader,
            RestDownstreamRouteLoader downstreamLoader,
            ScgRouteRegistrar scgRegistrar,
            List<SchemaReloadListener> listeners) {
        return new GatewayRefreshScheduler(props, registry, dynamicLoader, downstreamLoader, scgRegistrar, listeners);
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GIVEN refreshAll re-fetches a service whose descriptor hash changed "
            + "WHEN refreshAll runs SHOULD swap the context and report the service as changed")
    void given_descriptorHashChanged_when_refreshAll_should_swapContextAndReportChanged() {
        // Arrange
        ServiceDefinition svcDef = dynamicServiceDef("orders");
        GatewayProperties props = propsWith(svcDef);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        ServiceEndpointContext oldCtx = contextWithHash(svcDef, (byte) 1);
        registry.registerContext(oldCtx);
        ServiceEndpointContext newCtx = contextWithHash(svcDef, (byte) 2);

        DynamicRouteLoader dynamicLoader =
                new FakeDynamicRouteLoader(props, registry, java.util.Map.of("orders", newCtx), java.util.Set.of());
        RestDownstreamRouteLoader downstreamLoader =
                new FakeRestDownstreamRouteLoader(props, registry, java.util.Map.of());
        ScgRouteRegistrar scgRegistrar = mock(ScgRouteRegistrar.class);
        GatewayRefreshScheduler scheduler =
                buildScheduler(props, registry, dynamicLoader, downstreamLoader, scgRegistrar, List.of());

        // Act
        List<String> changed = scheduler.refreshAll();

        // Assert
        assertThat(changed).containsExactly("orders");
        assertThat(registry.contextByServiceName("orders")).contains(newCtx);
    }

    @Test
    @DisplayName("GIVEN refreshAll re-fetches a service whose descriptor hash is unchanged "
            + "WHEN refreshAll runs SHOULD keep the previous context in place and report no changes")
    void given_descriptorHashUnchanged_when_refreshAll_should_keepPreviousContext() {
        // Arrange
        ServiceDefinition svcDef = dynamicServiceDef("orders");
        GatewayProperties props = propsWith(svcDef);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        ServiceEndpointContext oldCtx = contextWithHash(svcDef, (byte) 7);
        registry.registerContext(oldCtx);
        // A freshly-built context with the SAME hash bytes — simulates an unchanged backend.
        ServiceEndpointContext sameHashCtx = contextWithHash(svcDef, (byte) 7);

        DynamicRouteLoader dynamicLoader = new FakeDynamicRouteLoader(
                props, registry, java.util.Map.of("orders", sameHashCtx), java.util.Set.of());
        RestDownstreamRouteLoader downstreamLoader =
                new FakeRestDownstreamRouteLoader(props, registry, java.util.Map.of());
        ScgRouteRegistrar scgRegistrar = mock(ScgRouteRegistrar.class);
        GatewayRefreshScheduler scheduler =
                buildScheduler(props, registry, dynamicLoader, downstreamLoader, scgRegistrar, List.of());

        // Act
        List<String> changed = scheduler.refreshAll();

        // Assert — the ORIGINAL context instance is still the one registered
        assertThat(changed).isEmpty();
        assertThat(registry.contextByServiceName("orders")).contains(oldCtx);
        verify(scgRegistrar, never()).register(any());
        verify(scgRegistrar, never()).deregister(any());
    }

    @Test
    @DisplayName("GIVEN a backend fails mid-refresh WHEN refreshAll runs "
            + "SHOULD leave the previous context in place for that service and not report it as changed")
    void given_backendFailsMidRefresh_when_refreshAll_should_leavePreviousContextInPlace() {
        // Arrange
        ServiceDefinition healthy = dynamicServiceDef("healthy");
        ServiceDefinition flaky = dynamicServiceDef("flaky");
        GatewayProperties props = propsWith(healthy, flaky);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        ServiceEndpointContext healthyOld = contextWithHash(healthy, (byte) 1);
        ServiceEndpointContext flakyOld = contextWithHash(flaky, (byte) 1);
        registry.registerContext(healthyOld);
        registry.registerContext(flakyOld);

        ServiceEndpointContext healthyNew = contextWithHash(healthy, (byte) 2);
        DynamicRouteLoader dynamicLoader = new FakeDynamicRouteLoader(
                props, registry, java.util.Map.of("healthy", healthyNew), java.util.Set.of("flaky"));
        RestDownstreamRouteLoader downstreamLoader =
                new FakeRestDownstreamRouteLoader(props, registry, java.util.Map.of());
        ScgRouteRegistrar scgRegistrar = mock(ScgRouteRegistrar.class);
        GatewayRefreshScheduler scheduler =
                buildScheduler(props, registry, dynamicLoader, downstreamLoader, scgRegistrar, List.of());

        // Act
        List<String> changed = scheduler.refreshAll();

        // Assert
        assertThat(changed).containsExactly("healthy");
        assertThat(registry.contextByServiceName("healthy")).contains(healthyNew);
        assertThat(registry.contextByServiceName("flaky")).contains(flakyOld);
    }

    @Test
    @DisplayName("GIVEN one service actually changed and one did not WHEN refreshAll runs "
            + "SHOULD notify every reload listener exactly once, not once per changed service")
    void given_oneServiceChanged_when_refreshAll_should_notifyListenersExactlyOnce() {
        // Arrange
        ServiceDefinition changedSvc = dynamicServiceDef("changed-svc");
        ServiceDefinition unchangedSvc = dynamicServiceDef("unchanged-svc");
        GatewayProperties props = propsWith(changedSvc, unchangedSvc);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        registry.registerContext(contextWithHash(changedSvc, (byte) 1));
        ServiceEndpointContext unchangedOld = contextWithHash(unchangedSvc, (byte) 9);
        registry.registerContext(unchangedOld);

        ServiceEndpointContext changedNew = contextWithHash(changedSvc, (byte) 2);
        ServiceEndpointContext unchangedSame = contextWithHash(unchangedSvc, (byte) 9);
        DynamicRouteLoader dynamicLoader = new FakeDynamicRouteLoader(
                props,
                registry,
                java.util.Map.of("changed-svc", changedNew, "unchanged-svc", unchangedSame),
                java.util.Set.of());
        RestDownstreamRouteLoader downstreamLoader =
                new FakeRestDownstreamRouteLoader(props, registry, java.util.Map.of());
        ScgRouteRegistrar scgRegistrar = mock(ScgRouteRegistrar.class);
        AtomicInteger notifications = new AtomicInteger();
        SchemaReloadListener listener = notifications::incrementAndGet;
        GatewayRefreshScheduler scheduler =
                buildScheduler(props, registry, dynamicLoader, downstreamLoader, scgRegistrar, List.of(listener));

        // Act
        scheduler.refreshAll();

        // Assert
        assertThat(notifications.get()).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "GIVEN nothing changed across any service WHEN refreshAll runs " + "SHOULD not notify any reload listener")
    void given_nothingChanged_when_refreshAll_should_notNotifyListeners() {
        // Arrange
        ServiceDefinition svcDef = dynamicServiceDef("stable");
        GatewayProperties props = propsWith(svcDef);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        registry.registerContext(contextWithHash(svcDef, (byte) 5));
        ServiceEndpointContext same = contextWithHash(svcDef, (byte) 5);

        DynamicRouteLoader dynamicLoader =
                new FakeDynamicRouteLoader(props, registry, java.util.Map.of("stable", same), java.util.Set.of());
        RestDownstreamRouteLoader downstreamLoader =
                new FakeRestDownstreamRouteLoader(props, registry, java.util.Map.of());
        ScgRouteRegistrar scgRegistrar = mock(ScgRouteRegistrar.class);
        AtomicInteger notifications = new AtomicInteger();
        SchemaReloadListener listener = notifications::incrementAndGet;
        GatewayRefreshScheduler scheduler =
                buildScheduler(props, registry, dynamicLoader, downstreamLoader, scgRegistrar, List.of(listener));

        // Act
        scheduler.refreshAll();

        // Assert
        assertThat(notifications.get()).isZero();
    }

    @Test
    @DisplayName("GIVEN the actuator endpoint refreshes all services "
            + "WHEN refresh(null) is called SHOULD trigger exactly one refreshAll and summarize the changed services")
    void given_refreshEndpointCalledWithNoName_when_refresh_should_triggerOneRefreshAllAndSummarize() {
        // Arrange
        ServiceDefinition svcDef = dynamicServiceDef("catalog");
        GatewayProperties props = propsWith(svcDef);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        registry.registerContext(contextWithHash(svcDef, (byte) 1));
        ServiceEndpointContext newCtx = contextWithHash(svcDef, (byte) 2);

        DynamicRouteLoader dynamicLoader =
                new FakeDynamicRouteLoader(props, registry, java.util.Map.of("catalog", newCtx), java.util.Set.of());
        RestDownstreamRouteLoader downstreamLoader =
                new FakeRestDownstreamRouteLoader(props, registry, java.util.Map.of());
        ScgRouteRegistrar scgRegistrar = mock(ScgRouteRegistrar.class);
        GatewayRefreshScheduler scheduler =
                buildScheduler(props, registry, dynamicLoader, downstreamLoader, scgRegistrar, List.of());
        GatewayRefreshController controller = new GatewayRefreshController(scheduler);

        // Act
        var result = controller.refresh(null);

        // Assert
        assertThat(result).containsEntry("count", 1);
        @SuppressWarnings("unchecked")
        List<String> changedList = (List<String>) result.get("changed");
        assertThat(changedList).containsExactly("catalog");
        assertThat(((FakeDynamicRouteLoader) dynamicLoader).buildCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("GIVEN the actuator endpoint refreshes a named service "
            + "WHEN refresh(name) is called SHOULD refresh only that service and report its outcome")
    void given_refreshEndpointCalledWithName_when_refresh_should_refreshOnlyThatService() {
        // Arrange
        ServiceDefinition target = dynamicServiceDef("target-svc");
        ServiceDefinition other = dynamicServiceDef("other-svc");
        GatewayProperties props = propsWith(target, other);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        registry.registerContext(contextWithHash(target, (byte) 1));
        registry.registerContext(contextWithHash(other, (byte) 1));
        ServiceEndpointContext targetNew = contextWithHash(target, (byte) 2);

        DynamicRouteLoader dynamicLoader = new FakeDynamicRouteLoader(
                props, registry, java.util.Map.of("target-svc", targetNew), java.util.Set.of());
        RestDownstreamRouteLoader downstreamLoader =
                new FakeRestDownstreamRouteLoader(props, registry, java.util.Map.of());
        ScgRouteRegistrar scgRegistrar = mock(ScgRouteRegistrar.class);
        GatewayRefreshScheduler scheduler =
                buildScheduler(props, registry, dynamicLoader, downstreamLoader, scgRegistrar, List.of());
        GatewayRefreshController controller = new GatewayRefreshController(scheduler);

        // Act
        var result = controller.refresh("target-svc");

        // Assert
        assertThat(result).containsEntry("service", "target-svc").containsEntry("refreshed", true);
        assertThat(registry.contextByServiceName("target-svc")).contains(targetNew);
        // "other-svc" was never asked for by name, so buildContext was called exactly once overall.
        assertThat(((FakeDynamicRouteLoader) dynamicLoader).buildCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("GIVEN refreshService is asked for a service name not present in configuration "
            + "WHEN refreshService is called SHOULD throw IllegalArgumentException naming it")
    void given_unknownServiceName_when_refreshService_should_throwIllegalArgumentException() {
        // Arrange
        GatewayProperties props = propsWith();
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        DynamicRouteLoader dynamicLoader =
                new FakeDynamicRouteLoader(props, registry, java.util.Map.of(), java.util.Set.of());
        RestDownstreamRouteLoader downstreamLoader =
                new FakeRestDownstreamRouteLoader(props, registry, java.util.Map.of());
        ScgRouteRegistrar scgRegistrar = mock(ScgRouteRegistrar.class);
        GatewayRefreshScheduler scheduler =
                buildScheduler(props, registry, dynamicLoader, downstreamLoader, scgRegistrar, List.of());

        // Act & Assert
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> scheduler.refreshService("ghost"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    @DisplayName("GIVEN a STATIC-mode service alongside a DYNAMIC one WHEN refreshAll runs "
            + "SHOULD skip the STATIC service entirely (not refreshable) and only refresh the DYNAMIC one")
    void given_staticServiceAlongsideDynamic_when_refreshAll_should_skipStaticService() {
        // Arrange
        ServiceDefinition dynamicSvc = dynamicServiceDef("dyn-svc");
        ServiceDefinition staticSvc = new ServiceDefinition();
        staticSvc.setName("static-svc");
        staticSvc.setMode(GatewayMode.STATIC);
        GatewayProperties props = propsWith(dynamicSvc, staticSvc);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        registry.registerContext(contextWithHash(dynamicSvc, (byte) 1));
        registry.registerContext(contextWithHash(staticSvc, (byte) 1));
        ServiceEndpointContext dynamicNew = contextWithHash(dynamicSvc, (byte) 2);

        DynamicRouteLoader dynamicLoader = new FakeDynamicRouteLoader(
                props, registry, java.util.Map.of("dyn-svc", dynamicNew), java.util.Set.of());
        RestDownstreamRouteLoader downstreamLoader =
                new FakeRestDownstreamRouteLoader(props, registry, java.util.Map.of());
        ScgRouteRegistrar scgRegistrar = mock(ScgRouteRegistrar.class);
        GatewayRefreshScheduler scheduler =
                buildScheduler(props, registry, dynamicLoader, downstreamLoader, scgRegistrar, List.of());

        // Act
        List<String> changed = scheduler.refreshAll();

        // Assert
        assertThat(changed).containsExactly("dyn-svc");
        assertThat(((FakeDynamicRouteLoader) dynamicLoader).buildCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("GIVEN a context is replaced during refresh WHEN applyReload runs "
            + "SHOULD deregister the old context's SCG routes before registering the new one")
    void given_contextReplaced_when_refresh_should_deregisterOldThenRegisterNewOnScgRegistrar() {
        // Arrange
        ServiceDefinition svcDef = dynamicServiceDef("scg-svc");
        GatewayProperties props = propsWith(svcDef);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        ServiceEndpointContext oldCtx = contextWithHash(svcDef, (byte) 1);
        registry.registerContext(oldCtx);
        ServiceEndpointContext newCtx = contextWithHash(svcDef, (byte) 2);

        DynamicRouteLoader dynamicLoader =
                new FakeDynamicRouteLoader(props, registry, java.util.Map.of("scg-svc", newCtx), java.util.Set.of());
        RestDownstreamRouteLoader downstreamLoader =
                new FakeRestDownstreamRouteLoader(props, registry, java.util.Map.of());
        ScgRouteRegistrar scgRegistrar = mock(ScgRouteRegistrar.class);
        GatewayRefreshScheduler scheduler =
                buildScheduler(props, registry, dynamicLoader, downstreamLoader, scgRegistrar, List.of());

        // Act
        scheduler.refreshAll();

        // Assert
        var inOrder = org.mockito.Mockito.inOrder(scgRegistrar);
        inOrder.verify(scgRegistrar, times(1)).deregister(oldCtx);
        inOrder.verify(scgRegistrar, times(1)).register(newCtx);
    }
}
