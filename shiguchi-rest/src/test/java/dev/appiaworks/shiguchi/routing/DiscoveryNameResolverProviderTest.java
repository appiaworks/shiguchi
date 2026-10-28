package dev.appiaworks.shiguchi.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.CallOptions;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;

/**
 * A {@code discovery:///} channel in front of two real gRPC servers and a scripted
 * {@link DiscoveryClient}: calls spread across every registered instance, an instance that
 * leaves the registry stops receiving calls at the next refresh, {@code grpc-port} metadata wins
 * over the registry port, and an empty registry fails calls with UNAVAILABLE instead of hanging.
 */
@DisplayName("DiscoveryNameResolverProvider: discovery:/// channels balance across instances")
class DiscoveryNameResolverProviderTest {

    private static final MethodDescriptor.Marshaller<byte[]> BYTES = new MethodDescriptor.Marshaller<>() {
        @Override
        public InputStream stream(byte[] value) {
            return new ByteArrayInputStream(value);
        }

        @Override
        public byte[] parse(InputStream stream) {
            try {
                return stream.readAllBytes();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    };

    private static final MethodDescriptor<byte[], byte[]> WHO = MethodDescriptor.<byte[], byte[]>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("lb.Echo/Who")
            .setRequestMarshaller(BYTES)
            .setResponseMarshaller(BYTES)
            .build();

    private Server a;
    private Server b;
    private DiscoveryClient discovery;
    private DiscoveryNameResolverProvider provider;
    private ManagedChannel channel;

    @BeforeEach
    void start() throws IOException {
        a = server("A");
        b = server("B");
        discovery = mock(DiscoveryClient.class);
        provider = new DiscoveryNameResolverProvider(provider(discovery), Duration.ofMillis(200), true);
    }

    @AfterEach
    void stop() throws InterruptedException {
        if (channel != null) channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        provider.destroy();
        a.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        b.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("GIVEN two registered instances WHEN ten calls are made SHOULD reach both of them")
    void given_twoInstances_when_called_should_reachBoth() {
        when(discovery.getInstances("echo")).thenReturn(List.of(instance(a.getPort()), instance(b.getPort())));
        channel = channel();

        Set<String> responders = new HashSet<>();
        for (int i = 0; i < 10; i++) responders.add(who());

        assertThat(responders).containsExactlyInAnyOrder("A", "B");
    }

    @Test
    @DisplayName("GIVEN an instance leaves the registry WHEN the list is refreshed SHOULD stop sending calls to it")
    void given_instanceLeaves_when_refreshed_should_stopCallingIt() {
        when(discovery.getInstances("echo")).thenReturn(List.of(instance(a.getPort()), instance(b.getPort())));
        channel = channel();
        Set<String> warm = new HashSet<>();
        for (int i = 0; i < 6; i++) warm.add(who());
        assertThat(warm).containsExactlyInAnyOrder("A", "B");

        when(discovery.getInstances("echo")).thenReturn(List.of(instance(b.getPort())));

        // The resolver re-reads on its own cadence, so the change lands whenever it lands: poll
        // for the condition instead of sleeping a guessed interval. Several calls per attempt,
        // because round_robin means one "B" proves nothing on its own.
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    Set<String> responders = new HashSet<>();
                    for (int i = 0; i < 6; i++) responders.add(who());
                    assertThat(responders)
                            .as("only B receives calls once A left the registry")
                            .containsExactly("B");
                });
    }

    @Test
    @DisplayName("GIVEN grpc-port metadata WHEN resolved SHOULD connect to that port, not the registry port")
    void given_grpcPortMetadata_when_resolved_should_useIt() {
        ServiceInstance http = new DefaultServiceInstance(
                "a-http", "echo", "localhost", 1, false, Map.of("grpc-port", Integer.toString(a.getPort())));
        when(discovery.getInstances("echo")).thenReturn(List.of(http));
        channel = channel();

        assertThat(who()).isEqualTo("A");
    }

    @Test
    @DisplayName("GIVEN a registry with no instances WHEN called SHOULD fail with UNAVAILABLE rather than hang")
    void given_noInstances_when_called_should_failUnavailable() {
        when(discovery.getInstances("echo")).thenReturn(List.of());
        channel = channel();

        assertThatThrownBy(this::who)
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(t -> ((StatusRuntimeException) t).getStatus().getCode())
                .isEqualTo(Status.Code.UNAVAILABLE);
    }

    @Test
    @DisplayName(
            "GIVEN the provider is registered WHEN a plain host:port channel is built SHOULD still resolve through DNS, not discovery")
    void given_providerRegistered_when_hostPortChannelBuilt_should_stillUseDns() {
        when(discovery.getInstances("localhost")).thenReturn(List.of());
        channel = Grpc.newChannelBuilderForAddress("localhost", a.getPort(), InsecureChannelCredentials.create())
                .build();

        assertThat(who()).isEqualTo("A");
    }

    @Test
    @DisplayName("GIVEN a target with another scheme WHEN a resolver is requested SHOULD decline")
    void given_otherScheme_when_resolverRequested_should_decline() {
        assertThat(provider.newNameResolver(
                        java.net.URI.create("dns:///host:1"), mock(io.grpc.NameResolver.Args.class)))
                .isNull();
        // Each provider has a scheme of its own (discovery, discovery-2, ...), so tests and
        // multi-context applications never resolve through another context's registry.
        assertThat(provider.getDefaultScheme()).startsWith("discovery");
        assertThat(provider.targetFor("echo")).isEqualTo(provider.getDefaultScheme() + ":///echo");
    }

    @Test
    @DisplayName(
            "GIVEN two providers alive at once WHEN each resolves its target SHOULD use its own scheme, never the other's")
    void given_twoProviders_when_resolving_should_keepSchemesApart() {
        DiscoveryClient other = mock(DiscoveryClient.class);
        DiscoveryNameResolverProvider second = new DiscoveryNameResolverProvider(
                new org.springframework.beans.factory.ObjectProvider<>() {
                    @Override
                    public DiscoveryClient getObject() {
                        return other;
                    }

                    @Override
                    public DiscoveryClient getIfAvailable() {
                        return other;
                    }
                },
                java.time.Duration.ofSeconds(30),
                true);
        try {
            assertThat(second.getDefaultScheme()).isNotEqualTo(provider.getDefaultScheme());
            assertThat(provider.newNameResolver(
                            java.net.URI.create(second.targetFor("echo")), mock(io.grpc.NameResolver.Args.class)))
                    .isNull();
        } finally {
            second.destroy();
        }
    }

    // ---------------------------------------------------------------- helpers

    private ManagedChannel channel() {
        return Grpc.newChannelBuilder(provider.targetFor("echo"), InsecureChannelCredentials.create())
                .defaultLoadBalancingPolicy("round_robin")
                .build();
    }

    private String who() {
        byte[] reply = ClientCalls.blockingUnaryCall(
                channel, WHO, CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS), new byte[0]);
        return new String(reply, StandardCharsets.UTF_8);
    }

    private static ServiceInstance instance(int port) {
        return new DefaultServiceInstance("echo-" + port, "echo", "localhost", port, false);
    }

    private static Server server(String name) throws IOException {
        ServerServiceDefinition service = ServerServiceDefinition.builder("lb.Echo")
                .addMethod(WHO, ServerCalls.asyncUnaryCall((req, obs) -> {
                    obs.onNext(name.getBytes(StandardCharsets.UTF_8));
                    obs.onCompleted();
                }))
                .build();
        return ServerBuilder.forPort(0).addService(service).build().start();
    }

    private static ObjectProvider<DiscoveryClient> provider(DiscoveryClient client) {
        return new ObjectProvider<>() {
            @Override
            public DiscoveryClient getObject() {
                return client;
            }

            @Override
            public DiscoveryClient getIfAvailable() {
                return client;
            }
        };
    }
}
