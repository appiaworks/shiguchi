package dev.appiaworks.shiguchi.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.ServerServiceDefinition;
import io.grpc.reflection.v1.ServerReflectionGrpc;
import io.grpc.reflection.v1.ServerReflectionRequest;
import io.grpc.reflection.v1.ServerReflectionResponse;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * BDD-style unit tests for GrpcProxyServer lifecycle.
 *
 * Strategy:
 * - All collaborators are real instances: GatewayProperties, GrpcProxyHandlerRegistry,
 *   GrpcReflectionAggregator, and GrpcProxyServerInterceptor. This is a lifecycle/wiring
 *   test, not a mocking test — we want to verify the server actually starts and accepts
 *   connections on the configured port.
 * - Port allocation uses ServerSocket(0) to find a free ephemeral port, avoiding
 *   TIME_WAIT flakiness and race conditions across parallel test runs.
 * - To prove start() opens a working server, we issue a real gRPC reflection call and
 *   verify a valid response is returned (not a connection refused or timeout).
 */
class GrpcProxyServerTest {

    private GatewayProperties properties;
    private GrpcProxyHandlerRegistry handlerRegistry;
    private GrpcReflectionAggregator reflectionAggregator;
    private GrpcProxyServerInterceptor serverInterceptor;
    private GrpcProxyServer server;
    private ManagedChannel clientChannel;

    @BeforeEach
    void setUp() {
        // Build a real GatewayProperties instance configured for the test
        properties = new GatewayProperties();
        properties.getGrpc().setEnabled(true);

        // Build real registry and aggregator with no backends (empty registries are fine)
        handlerRegistry = new GrpcProxyHandlerRegistry(new GrpcRouteRegistry(), new GrpcDescriptorCache());
        reflectionAggregator = new GrpcReflectionAggregator(new GrpcRouteRegistry());

        // Build the server interceptor using the same properties instance
        serverInterceptor = new GrpcProxyServerInterceptor(properties);
    }

    @AfterEach
    void tearDown() throws Exception {
        // Close the client channel FIRST: GrpcProxyServer.stop() calls a graceful
        // server.shutdown().awaitTermination(30, SECONDS), which blocks for the full 30s
        // if a client's streaming call (e.g. the reflection bidi-stream) is still open.
        // Tearing down the client before the server keeps every test fast.
        if (clientChannel != null && !clientChannel.isShutdown()) {
            clientChannel.shutdownNow();
            clientChannel.awaitTermination(5, TimeUnit.SECONDS);
        }
        // Stop the server if it was started
        if (server != null) {
            server.stop();
        }
    }

    // =========================================================================
    // Test scenarios
    // =========================================================================

    @Test
    @DisplayName("Given a GrpcProxyServer not yet started, when isRunning() is queried, should return false")
    void GIVEN_serverNotStarted_WHEN_isRunningQueried_SHOULD_returnFalse() {
        // Arrange
        properties.getGrpc().setPort(findFreePort());
        server = new GrpcProxyServer(properties, handlerRegistry, reflectionAggregator, serverInterceptor);

        // Act
        boolean running = server.isRunning();

        // Assert
        assertThat(running).isFalse();
    }

    @Test
    @DisplayName(
            "Given a GrpcProxyServer configured with a specific free port, when start() is called, should set isRunning() to true and accept real gRPC client connections")
    void GIVEN_serverConfiguredWithFreePort_WHEN_start_SHOULD_acceptClientConnections() {
        // Arrange
        int port = findFreePort();
        properties.getGrpc().setPort(port);
        server = new GrpcProxyServer(properties, handlerRegistry, reflectionAggregator, serverInterceptor);

        // Act
        server.start();

        // Assert — server is now marked running
        assertThat(server.isRunning()).isTrue();

        // Assert — a real gRPC client can connect and receive a reflection response
        clientChannel = ManagedChannelBuilder.forAddress("localhost", port)
                .usePlaintext()
                .build();

        ServerReflectionGrpc.ServerReflectionStub stub = ServerReflectionGrpc.newStub(clientChannel);

        // Issue a list services request with a short deadline
        ServerReflectionRequest request = ServerReflectionRequest.newBuilder()
                .setHost("localhost")
                .setListServices("")
                .build();

        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<ServerReflectionResponse> responseHolder =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> errorHolder =
                new java.util.concurrent.atomic.AtomicReference<>();

        stub.serverReflectionInfo(new io.grpc.stub.StreamObserver<ServerReflectionResponse>() {
                    @Override
                    public void onNext(ServerReflectionResponse response) {
                        responseHolder.set(response);
                        latch.countDown();
                    }

                    @Override
                    public void onError(Throwable t) {
                        errorHolder.set(t);
                        latch.countDown();
                    }

                    @Override
                    public void onCompleted() {
                        latch.countDown();
                    }
                })
                .onNext(request);

        // Wait for response with a timeout
        try {
            boolean received = latch.await(30, TimeUnit.SECONDS);
            assertThat(received).isTrue();
            assertThat(errorHolder.get()).isNull();
            assertThat(responseHolder.get()).isNotNull();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Reflection call did not complete within timeout", e);
        }
    }

    @Test
    @DisplayName("Given a running GrpcProxyServer, when stop() is called, should set isRunning() to false")
    void GIVEN_runningServer_WHEN_stop_SHOULD_setRunningToFalse() throws IOException {
        // Arrange
        int port = findFreePort();
        properties.getGrpc().setPort(port);
        server = new GrpcProxyServer(properties, handlerRegistry, reflectionAggregator, serverInterceptor);
        server.start();
        assertThat(server.isRunning()).isTrue();

        // Act
        server.stop();

        // Assert
        assertThat(server.isRunning()).isFalse();
    }

    @Test
    @DisplayName(
            "Given start() is called with a port that is already bound, when start() runs, should throw IllegalStateException with port in message")
    void GIVEN_portAlreadyBound_WHEN_start_SHOULD_throwIllegalStateException() throws IOException {
        // Arrange — bind a ServerSocket to reserve a port
        ServerSocket reserved = new ServerSocket(0);
        int boundPort = reserved.getLocalPort();

        try {
            properties.getGrpc().setPort(boundPort);
            server = new GrpcProxyServer(properties, handlerRegistry, reflectionAggregator, serverInterceptor);

            // Act & Assert
            assertThatThrownBy(() -> server.start())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Failed to start gRPC proxy server on port")
                    .hasMessageContaining(String.valueOf(boundPort));
        } finally {
            reserved.close();
        }
    }

    @Test
    @DisplayName("Given a GrpcProxyServer instance, when getPhase() is queried, should return Integer.MAX_VALUE")
    void GIVEN_server_WHEN_getPhase_SHOULD_returnMaxInteger() {
        // Arrange
        properties.getGrpc().setPort(findFreePort());
        server = new GrpcProxyServer(properties, handlerRegistry, reflectionAggregator, serverInterceptor);

        // Act
        int phase = server.getPhase();

        // Assert
        assertThat(phase).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("Given stop() is called on a server that was never started, should not throw")
    void GIVEN_serverNeverStarted_WHEN_stop_SHOULD_notThrow() {
        // Arrange
        properties.getGrpc().setPort(findFreePort());
        server = new GrpcProxyServer(properties, handlerRegistry, reflectionAggregator, serverInterceptor);

        // Act & Assert — should not throw
        assertThat(server.isRunning()).isFalse();
        server.stop(); // No-op, server is null
        assertThat(server.isRunning()).isFalse();
    }

    @Test
    @DisplayName(
            "Given a GatewayHostedService, when the server starts, should serve its method in place instead of proxying it")
    void GIVEN_gatewayHostedService_WHEN_serverStarts_SHOULD_serveItInPlace() throws Exception {
        // Arrange
        int port = findFreePort();
        properties.getGrpc().setPort(port);
        GatewayHostedService hosted = echoHostedService();
        server = new GrpcProxyServer(
                properties, handlerRegistry, reflectionAggregator, serverInterceptor, List.of(hosted));
        server.start();
        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", port)
                .usePlaintext()
                .build();

        try {
            // Act
            String response = ClientCalls.blockingUnaryCall(
                    channel, ECHO, CallOptions.DEFAULT.withDeadlineAfter(30, TimeUnit.SECONDS), "ping");

            // Assert
            assertThat(response).isEqualTo("ping");
        } finally {
            // Client first, then server, so the graceful shutdown does not wait on open calls
            channel.shutdownNow();
            channel.awaitTermination(5, TimeUnit.SECONDS);
            server.stop();
        }
    }

    @Test
    @DisplayName(
            "Given an ObjectProvider with no GatewayHostedService beans, when the server starts, should start and be running as before")
    void GIVEN_objectProviderWithNoHostedServices_WHEN_serverStarts_SHOULD_startAsBefore() {
        // Arrange
        properties.getGrpc().setPort(findFreePort());
        ObjectProvider<GatewayHostedService> noHostedServices = emptyHostedServiceProvider();
        server = new GrpcProxyServer(
                properties, handlerRegistry, reflectionAggregator, serverInterceptor, noHostedServices);

        // Act
        server.start();

        // Assert
        assertThat(server.isRunning()).isTrue();
    }

    // =========================================================================
    // Test fixtures
    // =========================================================================

    private static final MethodDescriptor.Marshaller<String> STRING = new MethodDescriptor.Marshaller<>() {
        @Override
        public InputStream stream(String value) {
            return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String parse(InputStream stream) {
            try {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    };

    private static final MethodDescriptor<String, String> ECHO = MethodDescriptor.newBuilder(STRING, STRING)
            .setFullMethodName("bench.Echo/Echo")
            .setType(MethodType.UNARY)
            .build();

    /** A hosted service that answers the ECHO method by returning the request unchanged. */
    private static GatewayHostedService echoHostedService() {
        return new GatewayHostedService() {
            @Override
            public ServerServiceDefinition bindService() {
                return ServerServiceDefinition.builder("bench.Echo")
                        .addMethod(ECHO, ServerCalls.asyncUnaryCall((req, observer) -> {
                            observer.onNext(req);
                            observer.onCompleted();
                        }))
                        .build();
            }
        };
    }

    /** An ObjectProvider that reports no beans, as Spring does when no GatewayHostedService is defined. */
    private static ObjectProvider<GatewayHostedService> emptyHostedServiceProvider() {
        return new ObjectProvider<>() {
            @Override
            public GatewayHostedService getObject() {
                throw new UnsupportedOperationException("No GatewayHostedService beans are defined");
            }

            @Override
            public Stream<GatewayHostedService> stream() {
                return Stream.empty();
            }

            @Override
            public Stream<GatewayHostedService> orderedStream() {
                return Stream.empty();
            }
        };
    }

    // =========================================================================
    // Test helpers
    // =========================================================================

    /**
     * Finds a free ephemeral port by creating and immediately closing a ServerSocket.
     * This returns a port that was free at the time of the call, reducing race conditions.
     */
    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("Could not find a free port", e);
        }
    }
}
