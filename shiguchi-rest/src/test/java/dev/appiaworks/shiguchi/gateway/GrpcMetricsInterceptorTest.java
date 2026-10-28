package dev.appiaworks.shiguchi.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit tests for GrpcMetricsInterceptor verifying client-side gRPC metrics recording.
 *
 * Tests exercise the interceptor on unary, server-streaming, and error scenarios, confirming
 * that call counts, message counts, and status tags are recorded correctly in the
 * MeterRegistry.
 */
@DisplayName("GIVEN GrpcMetricsInterceptor SHOULD record client-side gRPC metrics")
class GrpcMetricsInterceptorTest {

    private static final String SERVICE = "metrics.Items";
    private static final String GET_METHOD = "Get";
    private static final String LIST_METHOD = "List";
    private static final String FAIL_METHOD = "Fail";

    private MeterRegistry registry;
    private GrpcMetricsInterceptor interceptor;
    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void setUp() throws IOException {
        registry = new SimpleMeterRegistry();
        interceptor = new GrpcMetricsInterceptor(new TestObjectProvider<>(registry));
        setupServer();
        setupChannel();
    }

    @AfterEach
    void tearDown() {
        if (channel != null) channel.shutdownNow();
        if (server != null) server.shutdownNow();
    }

    @Test
    @DisplayName("WHEN unary Get is called SHOULD record started, sent, received counts and OK status duration")
    void given_unaryGet_when_called_should_recordMetrics() {
        // Arrange
        byte[] request = new byte[] {1};
        byte[] expectedResponse = new byte[] {42};

        // Act
        byte[] response =
                ClientCalls.blockingUnaryCall(channel, methodDescriptor(GET_METHOD), CallOptions.DEFAULT, request);

        // Assert
        assertThat(response).isEqualTo(expectedResponse);

        // Verify call.started counter
        assertThat(registry.get("grpc.client.call.started")
                        .tags("grpc_service", SERVICE, "grpc_method", GET_METHOD)
                        .counter()
                        .count())
                .isEqualTo(1.0);

        // Verify messages.sent counter
        assertThat(registry.get("grpc.client.call.messages.sent")
                        .tags("grpc_service", SERVICE, "grpc_method", GET_METHOD)
                        .counter()
                        .count())
                .isEqualTo(1.0);

        // Verify messages.received counter
        assertThat(registry.get("grpc.client.call.messages.received")
                        .tags("grpc_service", SERVICE, "grpc_method", GET_METHOD)
                        .counter()
                        .count())
                .isEqualTo(1.0);

        // Verify duration timer with OK status
        assertThat(registry.get("grpc.client.call.duration")
                        .tags("grpc_service", SERVICE, "grpc_method", GET_METHOD, "grpc_status", "OK")
                        .timer()
                        .count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("WHEN server-streaming List is called with 5 messages SHOULD record received count of 5")
    void given_serverStreaming_when_listCalledWithFiveMessages_should_recordFiveReceived() {
        // Arrange: request with first byte = 5 means 5 response messages
        byte[] request = new byte[] {5};

        // Act
        Iterator<byte[]> responses = ClientCalls.blockingServerStreamingCall(
                channel, methodDescriptor(LIST_METHOD), CallOptions.DEFAULT, request);
        List<byte[]> collected = new ArrayList<>();
        responses.forEachRemaining(collected::add);

        // Assert
        assertThat(collected).hasSize(5);

        // Verify call.started counter
        assertThat(registry.get("grpc.client.call.started")
                        .tags("grpc_service", SERVICE, "grpc_method", LIST_METHOD)
                        .counter()
                        .count())
                .isEqualTo(1.0);

        // Verify messages.sent counter
        assertThat(registry.get("grpc.client.call.messages.sent")
                        .tags("grpc_service", SERVICE, "grpc_method", LIST_METHOD)
                        .counter()
                        .count())
                .isEqualTo(1.0);

        // Verify messages.received counter is 5
        assertThat(registry.get("grpc.client.call.messages.received")
                        .tags("grpc_service", SERVICE, "grpc_method", LIST_METHOD)
                        .counter()
                        .count())
                .isEqualTo(5.0);

        // Verify timer was recorded with OK status
        assertThat(registry.get("grpc.client.call.duration")
                        .tags("grpc_service", SERVICE, "grpc_method", LIST_METHOD, "grpc_status", "OK")
                        .timer()
                        .count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("WHEN multiple List calls with 3 and 4 messages SHOULD accumulate received counts per method")
    void given_multipleCalls_when_listCalledTwice_should_accumulateReceivedCounts() {
        // Arrange
        byte[] request3 = new byte[] {3};
        byte[] request4 = new byte[] {4};

        // Act: first call with 3 messages
        Iterator<byte[]> responses1 = ClientCalls.blockingServerStreamingCall(
                channel, methodDescriptor(LIST_METHOD), CallOptions.DEFAULT, request3);
        List<byte[]> collected1 = new ArrayList<>();
        responses1.forEachRemaining(collected1::add);

        // Act: second call with 4 messages
        Iterator<byte[]> responses2 = ClientCalls.blockingServerStreamingCall(
                channel, methodDescriptor(LIST_METHOD), CallOptions.DEFAULT, request4);
        List<byte[]> collected2 = new ArrayList<>();
        responses2.forEachRemaining(collected2::add);

        // Assert
        assertThat(collected1).hasSize(3);
        assertThat(collected2).hasSize(4);

        // Verify started counter incremented to 2
        assertThat(registry.get("grpc.client.call.started")
                        .tags("grpc_service", SERVICE, "grpc_method", LIST_METHOD)
                        .counter()
                        .count())
                .isEqualTo(2.0);

        // Verify received count accumulated to 7
        assertThat(registry.get("grpc.client.call.messages.received")
                        .tags("grpc_service", SERVICE, "grpc_method", LIST_METHOD)
                        .counter()
                        .count())
                .isEqualTo(7.0);
    }

    @Test
    @DisplayName("WHEN Fail method is called SHOULD record NOT_FOUND status and 0 received messages")
    void given_failingCall_when_failMethodInvoked_should_recordNotFoundStatus() {
        // Arrange
        byte[] request = new byte[] {1};

        // Act & Assert
        assertThatThrownBy(() -> ClientCalls.blockingUnaryCall(
                        channel, methodDescriptor(FAIL_METHOD), CallOptions.DEFAULT, request))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting("status")
                .extracting("code")
                .isEqualTo(Status.Code.NOT_FOUND);

        // Verify call.started counter
        assertThat(registry.get("grpc.client.call.started")
                        .tags("grpc_service", SERVICE, "grpc_method", FAIL_METHOD)
                        .counter()
                        .count())
                .isEqualTo(1.0);

        // Verify messages.sent counter
        assertThat(registry.get("grpc.client.call.messages.sent")
                        .tags("grpc_service", SERVICE, "grpc_method", FAIL_METHOD)
                        .counter()
                        .count())
                .isEqualTo(1.0);

        // Verify messages.received counter is 0
        assertThat(registry.get("grpc.client.call.messages.received")
                        .tags("grpc_service", SERVICE, "grpc_method", FAIL_METHOD)
                        .counter()
                        .count())
                .isEqualTo(0.0);

        // Verify timer recorded with NOT_FOUND status
        assertThat(registry.get("grpc.client.call.duration")
                        .tags("grpc_service", SERVICE, "grpc_method", FAIL_METHOD, "grpc_status", "NOT_FOUND")
                        .timer()
                        .count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("WHEN Get and List are called interleaved SHOULD separate counters by grpc_method tag")
    void given_interleaved_when_getAndListCalled_should_separateCountersByMethod() {
        // Arrange
        byte[] getRequest = new byte[] {1};
        byte[] listRequest = new byte[] {2};

        // Act: Get call
        ClientCalls.blockingUnaryCall(channel, methodDescriptor(GET_METHOD), CallOptions.DEFAULT, getRequest);

        // Act: List call
        Iterator<byte[]> responses = ClientCalls.blockingServerStreamingCall(
                channel, methodDescriptor(LIST_METHOD), CallOptions.DEFAULT, listRequest);
        List<byte[]> collected = new ArrayList<>();
        responses.forEachRemaining(collected::add);

        // Act: another Get call
        ClientCalls.blockingUnaryCall(channel, methodDescriptor(GET_METHOD), CallOptions.DEFAULT, getRequest);

        // Assert: Get received count is 2 (one message per unary call)
        assertThat(registry.get("grpc.client.call.messages.received")
                        .tags("grpc_service", SERVICE, "grpc_method", GET_METHOD)
                        .counter()
                        .count())
                .isEqualTo(2.0);

        // Assert: List received count is 2 (from the streaming call)
        assertThat(registry.get("grpc.client.call.messages.received")
                        .tags("grpc_service", SERVICE, "grpc_method", LIST_METHOD)
                        .counter()
                        .count())
                .isEqualTo(2.0);

        // Assert: started counts are separate
        assertThat(registry.get("grpc.client.call.started")
                        .tags("grpc_service", SERVICE, "grpc_method", GET_METHOD)
                        .counter()
                        .count())
                .isEqualTo(2.0);

        assertThat(registry.get("grpc.client.call.started")
                        .tags("grpc_service", SERVICE, "grpc_method", LIST_METHOD)
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    // ==================== Private helpers ====================

    private void setupServer() throws IOException {
        var builder = ServerServiceDefinition.builder(SERVICE);

        // Get: unary method returning one byte[]
        builder.addMethod(methodDescriptor(GET_METHOD), ServerCalls.asyncUnaryCall((request, observer) -> {
            observer.onNext(new byte[] {42});
            observer.onCompleted();
        }));

        // List: server-streaming method returning N messages based on first byte of request
        builder.addMethod(methodDescriptor(LIST_METHOD), ServerCalls.asyncServerStreamingCall((request, observer) -> {
            int count = request[0];
            for (int i = 0; i < count; i++) {
                observer.onNext(new byte[] {(byte) i});
            }
            observer.onCompleted();
        }));

        // Fail: unary method that returns NOT_FOUND status
        builder.addMethod(methodDescriptor(FAIL_METHOD), ServerCalls.asyncUnaryCall((request, observer) -> {
            observer.onError(Status.NOT_FOUND.asException());
        }));

        server = ServerBuilder.forPort(0).addService(builder.build()).build().start();
    }

    private void setupChannel() {
        channel = io.grpc.ManagedChannelBuilder.forAddress("localhost", server.getPort())
                .usePlaintext()
                .intercept(interceptor)
                .build();
    }

    private static MethodDescriptor<byte[], byte[]> methodDescriptor(String methodName) {
        MethodDescriptor.MethodType type = LIST_METHOD.equals(methodName)
                ? MethodDescriptor.MethodType.SERVER_STREAMING
                : MethodDescriptor.MethodType.UNARY;
        return MethodDescriptor.<byte[], byte[]>newBuilder()
                .setType(type)
                .setFullMethodName(SERVICE + "/" + methodName)
                .setRequestMarshaller(new ByteMarshaller())
                .setResponseMarshaller(new ByteMarshaller())
                .build();
    }

    /** Marshaller for byte[] messages. */
    private static final class ByteMarshaller implements MethodDescriptor.Marshaller<byte[]> {
        @Override
        public InputStream stream(byte[] value) {
            return new java.io.ByteArrayInputStream(value);
        }

        @Override
        public byte[] parse(InputStream stream) {
            try {
                return stream.readAllBytes();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** ObjectProvider that returns a fixed MeterRegistry via getIfAvailable(Supplier). */
    private static final class TestObjectProvider<T> implements ObjectProvider<T> {
        private final T value;

        TestObjectProvider(T value) {
            this.value = value;
        }

        @Override
        public T getObject() {
            throw new IllegalStateException("not expected");
        }

        @Override
        public T getObject(Object... args) {
            throw new IllegalStateException("not expected");
        }

        @Override
        public T getIfAvailable() {
            return value;
        }

        @Override
        @SuppressWarnings("unchecked")
        public T getIfAvailable(java.util.function.Supplier<T> defaultSupplier) {
            return value;
        }

        @Override
        public T getIfUnique() {
            return null;
        }

        @Override
        public java.util.stream.Stream<T> stream() {
            return java.util.stream.Stream.of(value);
        }

        @Override
        public java.util.stream.Stream<T> orderedStream() {
            return java.util.stream.Stream.of(value);
        }
    }
}
