package dev.appiaworks.shiguchi.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import dev.appiaworks.shiguchi.config.GatewayMode;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.discovery.DiscoveredServiceSynchronizer.Outcome;
import dev.appiaworks.shiguchi.gateway.ScgRouteRegistrar;
import dev.appiaworks.shiguchi.grpc.GrpcDescriptorCache;
import dev.appiaworks.shiguchi.grpc.GrpcProxyHandlerRegistry;
import dev.appiaworks.shiguchi.rest.MergeSpecResolver;
import dev.appiaworks.shiguchi.routing.DynamicRouteLoader;
import dev.appiaworks.shiguchi.routing.GrpcChannelFactory;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.SchemaReloadListener;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import dev.appiaworks.shiguchi.routing.ServiceLoadStatus;
import dev.appiaworks.shiguchi.serde.DynamicProtoSerde;
import dev.appiaworks.shiguchi.serde.DynamicSerializerFactory;
import dev.appiaworks.shiguchi.serde.StaticSerdeDetector;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor.MethodType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;

@DisplayName("DiscoveredServiceSynchronizer")
class DiscoveredServiceSynchronizerTest {

    /** Registry contents are mutable between syncs; getServices can be made to fail. */
    private static class StubDiscoveryClient implements DiscoveryClient {
        final Map<String, List<ServiceInstance>> services = new LinkedHashMap<>();
        boolean failListing;

        @Override
        public String description() {
            return "stub";
        }

        @Override
        public List<ServiceInstance> getInstances(String serviceId) {
            return services.getOrDefault(serviceId, List.of());
        }

        @Override
        public List<String> getServices() {
            if (failListing) throw new IllegalStateException("registry down");
            return new ArrayList<>(services.keySet());
        }

        void put(String id, Map<String, String> metadata) {
            services.put(id, List.of(new DefaultServiceInstance(id + "-1", id, "10.0.0.1", 8080, false, metadata)));
        }
    }

    /** Builds a context per service instead of reading reflection from a backend. */
    private static class FakeLoader extends DynamicRouteLoader {
        final Set<String> failing = new HashSet<>();
        final AtomicInteger builds = new AtomicInteger();
        final List<ServiceDefinition> built = new ArrayList<>();

        FakeLoader(GatewayProperties props, GrpcRouteRegistry registry) {
            super(
                    props,
                    registry,
                    Optional.empty(),
                    new DynamicProtoSerde(new GrpcDescriptorCache()),
                    new StaticSerdeDetector(),
                    new DynamicSerializerFactory(),
                    mock(GrpcChannelFactory.class),
                    mock(MergeSpecResolver.class));
        }

        @Override
        public ServiceEndpointContext buildContext(ServiceDefinition def) throws Exception {
            builds.incrementAndGet();
            if (failing.contains(def.getName())) {
                throw new java.io.IOException("reflection not available on " + def.getName());
            }
            built.add(def);
            ServiceEndpointContext ctx = new ServiceEndpointContext(def, mock(ManagedChannel.class));
            ctx.addRoute(RouteDefinition.builder("demo." + def.getName(), "List", MethodType.UNARY, def.getTarget())
                    .httpBinding("GET", "/" + def.getName())
                    .build());
            ctx.setDescriptorHash(new byte[] {1});
            return ctx;
        }
    }

    private static class Fixture {
        final GatewayProperties props = new GatewayProperties();
        final GrpcRouteRegistry registry = new GrpcRouteRegistry();
        final StubDiscoveryClient client = new StubDiscoveryClient();
        final FakeLoader loader = new FakeLoader(props, registry);
        final ScgRouteRegistrar scg = mock(ScgRouteRegistrar.class);
        final ServiceLoadStatus status = new ServiceLoadStatus();
        final AutoRegisteredServices holder = new AutoRegisteredServices();
        final List<String> reloads = new CopyOnWriteArrayList<>();
        final DiscoveredServiceSynchronizer sync;

        Fixture() {
            props.getDiscovery().getAutoRegister().setEnabled(true);
            DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
            bf.registerSingleton("client", client);
            bf.registerSingleton("listener", (SchemaReloadListener) () -> reloads.add("reload"));
            ObjectProvider<DiscoveryClient> clients = bf.getBeanProvider(DiscoveryClient.class);
            ObjectProvider<SchemaReloadListener> listeners = bf.getBeanProvider(SchemaReloadListener.class);
            ObjectProvider<GrpcProxyHandlerRegistry> proxies = bf.getBeanProvider(GrpcProxyHandlerRegistry.class);
            sync = new DiscoveredServiceSynchronizer(
                    props, clients, loader, registry, Optional.of(scg), status, holder, listeners, proxies);
        }

        Outcome outcome(String id) {
            return sync.seen().get(id).outcome();
        }
    }

    private static Map<String, String> optedIn() {
        return Map.of("shiguchi.expose", "true", "grpc-port", "9090");
    }

    @Test
    @DisplayName("GIVEN unmarked and expose=false services WHEN syncing THEN SHOULD ignore them and say why")
    void GIVEN_unmarked_services_WHEN_sync_SHOULD_ignore_them() {
        Fixture f = new Fixture();
        f.client.put("plain", Map.of("grpc-port", "9090"));
        f.client.put("off", Map.of("shiguchi.expose", "false", "grpc-port", "9090"));

        f.sync.sync();

        assertThat(f.registry.contextByServiceName("plain")).isEmpty();
        assertThat(f.registry.contextByServiceName("off")).isEmpty();
        assertThat(f.loader.builds.get()).isZero();
        assertThat(f.outcome("plain")).isEqualTo(Outcome.NOT_OPTED_IN);
        assertThat(f.outcome("off")).isEqualTo(Outcome.NOT_OPTED_IN);
    }

    @Test
    @DisplayName("GIVEN an opted-in grpc service WHEN syncing THEN SHOULD register a DYNAMIC definition under /<id>")
    void GIVEN_opted_in_grpc_service_WHEN_sync_SHOULD_register_it() {
        Fixture f = new Fixture();
        f.client.put("orders", optedIn());

        f.sync.sync();

        assertThat(f.registry.contextByServiceName("orders")).isPresent();
        ServiceDefinition def = f.holder.definitions().get(0);
        assertThat(def.getMode()).isEqualTo(GatewayMode.DYNAMIC);
        assertThat(def.getDiscoveryName()).isEqualTo("orders");
        assertThat(def.getPathPrefix()).isEqualTo("/orders");
        assertThat(f.props.getServices())
                .as("never leaks into shiguchi.services")
                .isEmpty();
        assertThat(f.outcome("orders")).isEqualTo(Outcome.REGISTERED);
        verify(f.scg).register(any());
        assertThat(f.reloads).hasSize(1);
    }

    @Test
    @DisplayName("GIVEN a path-prefix metadata WHEN syncing THEN SHOULD normalise and use it")
    void GIVEN_path_prefix_metadata_WHEN_sync_SHOULD_use_it() {
        Fixture f = new Fixture();
        f.client.put(
                "orders", Map.of("shiguchi.expose", "true", "grpc-port", "9090", "shiguchi.path-prefix", "api/o/"));

        f.sync.sync();

        assertThat(f.holder.definitions().get(0).getPathPrefix()).isEqualTo("/api/o");
    }

    @Test
    @DisplayName("GIVEN an opted-in service without grpc-port WHEN syncing THEN SHOULD skip it as REST-only")
    void GIVEN_no_grpc_port_WHEN_sync_SHOULD_skip() {
        Fixture f = new Fixture();
        f.client.put("web", Map.of("shiguchi.expose", "true"));

        f.sync.sync();

        assertThat(f.registry.contextByServiceName("web")).isEmpty();
        assertThat(f.outcome("web")).isEqualTo(Outcome.NO_GRPC_PORT);
    }

    @Test
    @DisplayName("GIVEN only one of two instances opted in WHEN syncing THEN SHOULD still register the service")
    void GIVEN_any_instance_opted_in_WHEN_sync_SHOULD_register() {
        Fixture f = new Fixture();
        f.client.services.put(
                "orders",
                List.of(
                        new DefaultServiceInstance("o1", "orders", "10.0.0.1", 8080, false, Map.of()),
                        new DefaultServiceInstance("o2", "orders", "10.0.0.2", 8080, false, optedIn())));

        f.sync.sync();

        assertThat(f.registry.contextByServiceName("orders")).isPresent();
    }

    @Test
    @DisplayName("GIVEN an explicit shiguchi.services entry WHEN syncing THEN SHOULD NOT auto-register that service")
    void GIVEN_explicit_config_WHEN_sync_SHOULD_win() {
        Fixture f = new Fixture();
        ServiceDefinition explicit = new ServiceDefinition();
        explicit.setName("billing-explicit");
        explicit.setDiscoveryName("billing");
        explicit.setMode(GatewayMode.DYNAMIC);
        f.props.setServices(List.of(explicit));
        f.client.put("billing", optedIn());

        f.sync.sync();

        assertThat(f.loader.builds.get()).isZero();
        assertThat(f.outcome("billing")).isEqualTo(Outcome.EXPLICIT_CONFIG_WINS);
    }

    @Test
    @DisplayName("GIVEN a matching include and exclude WHEN syncing THEN SHOULD let exclude win")
    void GIVEN_exclude_pattern_WHEN_sync_SHOULD_win_over_metadata() {
        Fixture f = new Fixture();
        f.props.getDiscovery().getAutoRegister().setInclude(List.of("team-*"));
        f.props.getDiscovery().getAutoRegister().setExclude(List.of("team-secret*"));
        f.client.put("team-a", optedIn());
        f.client.put("team-secret-x", optedIn());
        f.client.put("other", optedIn());

        f.sync.sync();

        assertThat(f.registry.contextByServiceName("team-a")).isPresent();
        assertThat(f.registry.contextByServiceName("team-secret-x")).isEmpty();
        assertThat(f.registry.contextByServiceName("other")).isEmpty();
        assertThat(f.outcome("team-secret-x")).isEqualTo(Outcome.EXCLUDED_BY_PATTERN);
        assertThat(f.outcome("other")).isEqualTo(Outcome.EXCLUDED_BY_PATTERN);
    }

    @Test
    @DisplayName(
            "GIVEN a service that left the registry WHEN 3 syncs pass THEN SHOULD remove its routes only on the 3rd")
    void GIVEN_service_gone_WHEN_three_syncs_SHOULD_remove() {
        Fixture f = new Fixture();
        f.client.put("orders", optedIn());
        f.sync.sync();
        f.client.services.clear();

        f.sync.sync();
        f.sync.sync();
        assertThat(f.registry.contextByServiceName("orders"))
                .as("grace: still routed")
                .isPresent();

        f.sync.sync();

        assertThat(f.registry.contextByServiceName("orders")).isEmpty();
        assertThat(f.holder.definitions()).isEmpty();
        verify(f.scg).deregister(any());
        assertThat(f.reloads).hasSize(2);
    }

    @Test
    @DisplayName(
            "GIVEN a service that flaps WHEN it reappears within the grace THEN SHOULD keep its routes and reset the count")
    void GIVEN_flapping_service_WHEN_reappears_SHOULD_keep_routes() {
        Fixture f = new Fixture();
        f.client.put("orders", optedIn());
        f.sync.sync();
        Map<String, String> meta = optedIn();

        for (int i = 0; i < 4; i++) {
            f.client.services.clear();
            f.sync.sync();
            f.sync.sync();
            f.client.put("orders", meta);
            f.sync.sync();
        }

        assertThat(f.registry.contextByServiceName("orders")).isPresent();
        assertThat(f.loader.builds.get()).as("loaded once, never rebuilt").isEqualTo(1);
        verify(f.scg, times(0)).deregister(any());
    }

    @Test
    @DisplayName("GIVEN the registry listing fails WHEN syncing THEN SHOULD keep every route and count no miss")
    void GIVEN_registry_outage_WHEN_sync_SHOULD_keep_routes() {
        Fixture f = new Fixture();
        f.client.put("orders", optedIn());
        f.sync.sync();
        f.client.failListing = true;

        for (int i = 0; i < 5; i++) f.sync.sync();

        assertThat(f.registry.contextByServiceName("orders")).isPresent();
    }

    @Test
    @DisplayName(
            "GIVEN a backend without reflection WHEN syncing THEN SHOULD record the failure, not throw, and retry next sync")
    void GIVEN_probe_failure_WHEN_sync_SHOULD_be_non_fatal_and_retried() {
        Fixture f = new Fixture();
        f.loader.failing.add("orders");
        f.client.put("orders", optedIn());

        f.sync.sync();

        assertThat(f.registry.contextByServiceName("orders")).isEmpty();
        assertThat(f.holder.definitions()).isEmpty();
        assertThat(f.outcome("orders")).isEqualTo(Outcome.PROBE_FAILED);
        assertThat(f.status.failure("orders")).isPresent();
        assertThat(f.status.failure("orders").get().phase()).isEqualTo("discovery");

        f.loader.failing.clear();
        f.sync.sync();

        assertThat(f.registry.contextByServiceName("orders")).isPresent();
        assertThat(f.status.failure("orders")).isEmpty();
        assertThat(f.loader.builds.get()).isEqualTo(2);
    }
}
