package dev.appiaworks.shiguchi.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.grpc.GrpcDescriptorCache;
import dev.appiaworks.shiguchi.grpc.GrpcProxyHandlerRegistry;
import dev.appiaworks.shiguchi.rest.MergeSpecResolver;
import dev.appiaworks.shiguchi.routing.DynamicRouteLoader;
import dev.appiaworks.shiguchi.routing.GrpcChannelFactory;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import dev.appiaworks.shiguchi.routing.ServiceLoadStatus;
import dev.appiaworks.shiguchi.serde.DynamicProtoSerde;
import dev.appiaworks.shiguchi.serde.DynamicSerializerFactory;
import dev.appiaworks.shiguchi.serde.StaticSerdeDetector;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;

/**
 * Drives {@link DiscoveredServiceSynchronizer} with the real {@link DynamicRouteLoader} and real
 * server reflection against an in-process gRPC server (the standard health service). The
 * {@code discovery:///} channel cannot reach an in-process server, so {@link GrpcChannelFactory}
 * is mocked to hand back the in-process channel, the same way {@code DynamicRouteLoaderTest} does.
 */
@DisplayName("DiscoveredServiceSynchronizer end to end")
class DiscoveredServiceSynchronizerEndToEndTest {

    private static final String SERVICE = "grpc.health.v1.Health";

    private final List<Server> servers = new ArrayList<>();
    private final List<ManagedChannel> channels = new ArrayList<>();

    @AfterEach
    void tearDown() {
        channels.forEach(ManagedChannel::shutdownNow);
        servers.forEach(Server::shutdownNow);
    }

    private static class MutableClient implements DiscoveryClient {
        final Map<String, List<ServiceInstance>> services = new LinkedHashMap<>();

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
            return new ArrayList<>(services.keySet());
        }
    }

    @Test
    @DisplayName("GIVEN a reflecting in-process server WHEN synced, then dropped from the registry for 3 syncs"
            + " THEN SHOULD register its routes, then remove context and routes")
    void GIVEN_reflecting_backend_WHEN_registered_then_dropped_SHOULD_add_then_remove_routes() throws Exception {
        String serverName = InProcessServerBuilder.generateName();
        Server server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new HealthStatusManager().getHealthService())
                .addService(ProtoReflectionServiceV1.newInstance())
                .build()
                .start();
        servers.add(server);
        ManagedChannel channel =
                InProcessChannelBuilder.forName(serverName).directExecutor().build();
        channels.add(channel);

        GatewayProperties props = new GatewayProperties();
        props.getDiscovery().getAutoRegister().setEnabled(true);
        props.getDiscovery().getAutoRegister().setRemovalGraceSyncs(3);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
        when(channelFactory.build(any())).thenReturn(channel);
        MergeSpecResolver merge = mock(MergeSpecResolver.class);
        when(merge.resolve(any(), any())).thenReturn(Optional.empty());
        DynamicRouteLoader loader = new DynamicRouteLoader(
                props,
                registry,
                Optional.empty(),
                new DynamicProtoSerde(new GrpcDescriptorCache()),
                new StaticSerdeDetector(),
                new DynamicSerializerFactory(),
                channelFactory,
                merge);

        MutableClient client = new MutableClient();
        client.services.put(
                "health",
                List.of(new DefaultServiceInstance(
                        "health-1",
                        "health",
                        "10.0.0.1",
                        8080,
                        false,
                        Map.of("shiguchi.expose", "true", "grpc-port", "9090"))));
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerSingleton("client", client);
        DiscoveredServiceSynchronizer sync = new DiscoveredServiceSynchronizer(
                props,
                bf.getBeanProvider(DiscoveryClient.class),
                loader,
                bf.getBeanProvider(dev.appiaworks.shiguchi.routing.RestDownstreamRouteLoader.class),
                registry,
                Optional.empty(),
                new ServiceLoadStatus(),
                new AutoRegisteredServices(),
                bf.getBeanProvider(dev.appiaworks.shiguchi.routing.SchemaReloadListener.class),
                bf.getBeanProvider(GrpcProxyHandlerRegistry.class));

        sync.sync();

        ServiceEndpointContext ctx = registry.contextByServiceName("health").orElseThrow();
        assertThat(sync.seen().get("health").outcome()).isEqualTo(DiscoveredServiceSynchronizer.Outcome.REGISTERED);
        assertThat(ctx.getRoutes()).extracting(r -> r.getMethodName()).contains("Check", "Watch");
        assertThat(registry.allRoutes()).isNotEmpty();

        client.services.clear();
        sync.sync();
        sync.sync();
        assertThat(registry.contextByServiceName("health"))
                .as("still within the grace period")
                .isPresent();
        sync.sync();

        assertThat(registry.contextByServiceName("health")).isEmpty();
        assertThat(registry.allRoutes()).isEmpty();
    }
}
