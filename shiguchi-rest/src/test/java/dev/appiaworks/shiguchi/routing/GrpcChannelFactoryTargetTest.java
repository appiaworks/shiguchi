package dev.appiaworks.shiguchi.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.gateway.GrpcLoggingInterceptor;
import dev.appiaworks.shiguchi.gateway.GrpcMetricsInterceptor;
import io.grpc.ManagedChannel;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Where the factory points a channel: a raw {@code target} wins, a {@code discovery-name} becomes
 * a {@code discovery:///} target when the discovery resolver is available and falls back to the
 * address-resolver SPI when it is not. No network I/O: channels connect lazily.
 */
@DisplayName("GrpcChannelFactory: target, discovery-name and the fallback order")
class GrpcChannelFactoryTargetTest {

    private ManagedChannel channel;

    @AfterEach
    void shutdown() {
        if (channel != null) channel.shutdownNow();
    }

    @Test
    @DisplayName("GIVEN a raw target WHEN built SHOULD use it verbatim even if host/port are also set")
    void given_target_when_built_should_useTarget() {
        ServiceDefinition def = new ServiceDefinition();
        def.setTarget("dns:///orders.shop.svc.cluster.local:9090");
        def.setHost("ignored");
        def.setPort(1);

        channel = factory(null, none()).build(def);

        assertThat(channel.authority()).isEqualTo("orders.shop.svc.cluster.local:9090");
        assertThat(def.isTargetDerived()).isFalse();
    }

    @Test
    @DisplayName("GIVEN discovery-name and an available discovery resolver WHEN built SHOULD target discovery:///name")
    void given_discoveryNameWithResolver_when_built_should_targetDiscoveryScheme() {
        org.springframework.cloud.client.discovery.DiscoveryClient client =
                mock(org.springframework.cloud.client.discovery.DiscoveryClient.class);
        DiscoveryNameResolverProvider resolver = new DiscoveryNameResolverProvider(
                new ObjectProvider<>() {
                    @Override
                    public org.springframework.cloud.client.discovery.DiscoveryClient getObject() {
                        return client;
                    }

                    @Override
                    public org.springframework.cloud.client.discovery.DiscoveryClient getIfAvailable() {
                        return client;
                    }
                },
                java.time.Duration.ofSeconds(30),
                true);
        try {
            ServiceDefinition def = new ServiceDefinition();
            def.setDiscoveryName("orders");

            channel = factory(resolver, none()).build(def);

            // The authority of a discovery:/// channel is the service id: gRPC asks the resolver for it.
            assertThat(channel.authority()).isEqualTo("orders");
            assertThat(def.getTarget())
                    .as("route label stays the discovery name")
                    .isEqualTo("orders");
            assertThat(def.isTargetDerived()).isTrue();
        } finally {
            resolver.destroy();
        }
    }

    @Test
    @DisplayName(
            "GIVEN discovery-name, no DiscoveryClient, and a custom address resolver WHEN built SHOULD use that address")
    void given_discoveryNameWithoutClientButCustomResolver_when_built_should_useResolvedAddress() {
        GrpcServiceAddressResolver custom =
                svc -> Optional.of(new GrpcServiceAddressResolver.ResolvedAddress("resolved.internal", 7443));
        ServiceDefinition def = new ServiceDefinition();
        def.setDiscoveryName("orders");

        channel = factory(null, some(custom)).build(def);

        assertThat(channel.authority()).isEqualTo("resolved.internal:7443");
    }

    @Test
    @DisplayName(
            "GIVEN discovery-name and nothing that can resolve it WHEN built SHOULD fail fast with a pointer to what is missing")
    void given_discoveryNameUnresolvable_when_built_should_failFast() {
        ServiceDefinition def = new ServiceDefinition();
        def.setName("orders-gw");
        def.setDiscoveryName("orders");

        assertThatThrownBy(() -> factory(null, none()).build(def))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("orders")
                .hasMessageContaining("DiscoveryClient");
    }

    @Test
    @DisplayName("GIVEN an inprocess: target WHEN built SHOULD dial the in-process server of that name")
    void given_inProcessTarget_when_built_should_dialInProcessServer() throws Exception {
        String name = "gcf-" + System.nanoTime();
        io.grpc.Server server = io.grpc.inprocess.InProcessServerBuilder.forName(name)
                .addService(io.grpc.protobuf.services.ProtoReflectionServiceV1.newInstance())
                .build()
                .start();
        try {
            ServiceDefinition def = new ServiceDefinition();
            def.setName("hosted");
            def.setTarget(GrpcChannelFactory.IN_PROCESS + name);

            channel = factory(null, none()).build(def);

            // The channel's interceptors are mocks here, so no call goes through; connecting does:
            // an in-process channel to a live server is READY at once, a DNS channel for a
            // target named "inprocess:..." never is.
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            io.grpc.ConnectivityState state = channel.getState(true);
            while (state != io.grpc.ConnectivityState.READY && System.nanoTime() < deadline) {
                Thread.sleep(10);
                state = channel.getState(true);
            }
            assertThat(state).isEqualTo(io.grpc.ConnectivityState.READY);
        } finally {
            server.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- helpers

    private static GrpcChannelFactory factory(
            DiscoveryNameResolverProvider discovery, ObjectProvider<GrpcServiceAddressResolver> resolvers) {
        return new GrpcChannelFactory(
                new GatewayProperties(),
                mock(GrpcMetricsInterceptor.class),
                mock(GrpcLoggingInterceptor.class),
                Optional.empty(),
                new ObjectProvider<GrpcChannelBuilderCustomizer>() {
                    @Override
                    public GrpcChannelBuilderCustomizer getObject() {
                        throw new IllegalStateException();
                    }

                    @Override
                    public java.util.stream.Stream<GrpcChannelBuilderCustomizer> stream() {
                        return java.util.stream.Stream.empty();
                    }
                },
                resolvers,
                new ObjectProvider<DiscoveryNameResolverProvider>() {
                    @Override
                    public DiscoveryNameResolverProvider getObject() {
                        throw new IllegalStateException();
                    }

                    @Override
                    public DiscoveryNameResolverProvider getIfAvailable() {
                        return discovery;
                    }

                    @Override
                    public java.util.stream.Stream<DiscoveryNameResolverProvider> stream() {
                        return discovery == null
                                ? java.util.stream.Stream.empty()
                                : java.util.stream.Stream.of(discovery);
                    }
                });
    }

    private static ObjectProvider<GrpcServiceAddressResolver> none() {
        return new ObjectProvider<>() {
            @Override
            public GrpcServiceAddressResolver getObject() {
                throw new IllegalStateException();
            }

            @Override
            public java.util.stream.Stream<GrpcServiceAddressResolver> stream() {
                return java.util.stream.Stream.empty();
            }
        };
    }

    private static ObjectProvider<GrpcServiceAddressResolver> some(GrpcServiceAddressResolver resolver) {
        return new ObjectProvider<>() {
            @Override
            public GrpcServiceAddressResolver getObject() {
                return resolver;
            }

            @Override
            public java.util.stream.Stream<GrpcServiceAddressResolver> stream() {
                return java.util.stream.Stream.of(resolver);
            }
        };
    }
}
