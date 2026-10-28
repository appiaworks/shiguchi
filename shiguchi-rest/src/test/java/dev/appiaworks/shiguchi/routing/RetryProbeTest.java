package dev.appiaworks.shiguchi.routing;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins down the gRPC retry behaviour the gateway's design depends on, against a real client and
 * server: that a wildcard {@code methodConfig} retry policy actually retries, how many attempts it
 * makes, and whether a channel-level interceptor sees one call or one per attempt. If a gRPC
 * upgrade changes any of this, this test says so directly instead of a feature quietly misbehaving.
 */
@DisplayName("gRPC retry semantics the gateway relies on")
class RetryProbeTest {

    private static final MethodDescriptor<byte[], byte[]> METHOD = MethodDescriptor.<byte[], byte[]>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("probe.Service/Call")
            .setRequestMarshaller(new ByteMarshaller())
            .setResponseMarshaller(new ByteMarshaller())
            .build();

    /** Fails the first {@code failures} calls with UNAVAILABLE, then succeeds. */
    private static Server startServer(AtomicInteger calls, int failures) throws IOException {
        ServerServiceDefinition service = ServerServiceDefinition.builder("probe.Service")
                .addMethod(METHOD, ServerCalls.asyncUnaryCall((request, observer) -> {
                    if (calls.incrementAndGet() <= failures) {
                        observer.onError(
                                Status.UNAVAILABLE.withDescription("try again").asRuntimeException());
                        return;
                    }
                    observer.onNext(new byte[] {1});
                    observer.onCompleted();
                }))
                .build();
        return ServerBuilder.forPort(0).addService(service).build().start();
    }

    private static Map<String, Object> retryConfig(double maxAttempts) {
        return Map.of(
                "methodConfig",
                List.of(Map.of(
                        "name",
                        List.of(Map.of()),
                        "retryPolicy",
                        Map.of(
                                "maxAttempts",
                                maxAttempts,
                                "initialBackoff",
                                "0.01s",
                                "maxBackoff",
                                "0.02s",
                                "backoffMultiplier",
                                2.0,
                                "retryableStatusCodes",
                                List.of("UNAVAILABLE")))));
    }

    @Test
    @DisplayName(
            "GIVEN a wildcard retry policy WHEN the backend fails twice then succeeds SHOULD retry and return the success")
    void given_wildcardPolicy_when_transientFailures_should_retry() throws Exception {
        AtomicInteger backendCalls = new AtomicInteger();
        Server server = startServer(backendCalls, 2);
        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", server.getPort())
                .usePlaintext()
                .disableServiceConfigLookUp()
                .defaultServiceConfig(retryConfig(3))
                .enableRetry()
                .build();
        try {
            byte[] reply = ClientCalls.blockingUnaryCall(channel, METHOD, CallOptions.DEFAULT, new byte[] {0});

            assertThat(reply).containsExactly(1);
            // The wildcard name really did match, and maxAttempts counts the first try.
            assertThat(backendCalls.get()).isEqualTo(3);
        } finally {
            channel.shutdownNow();
            server.shutdownNow();
        }
    }

    @Test
    @DisplayName("GIVEN maxAttempts is exhausted WHEN the backend keeps failing SHOULD surface the last status")
    void given_attemptsExhausted_when_stillFailing_should_fail() throws Exception {
        AtomicInteger backendCalls = new AtomicInteger();
        Server server = startServer(backendCalls, 99);
        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", server.getPort())
                .usePlaintext()
                .disableServiceConfigLookUp()
                .defaultServiceConfig(retryConfig(2))
                .enableRetry()
                .build();
        try {
            assertThatUnavailable(channel);
            assertThat(backendCalls.get()).isEqualTo(2);
        } finally {
            channel.shutdownNow();
            server.shutdownNow();
        }
    }

    @Test
    @DisplayName(
            "GIVEN a channel-level interceptor WHEN a call is retried SHOULD see one logical call, not one per attempt")
    void given_interceptor_when_retried_should_seeOneLogicalCall() throws Exception {
        AtomicInteger backendCalls = new AtomicInteger();
        AtomicInteger interceptedCalls = new AtomicInteger();
        Server server = startServer(backendCalls, 2);
        ClientInterceptor counting = new ClientInterceptor() {
            @Override
            public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                    MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                interceptedCalls.incrementAndGet();
                return next.newCall(method, callOptions);
            }
        };
        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", server.getPort())
                .usePlaintext()
                .intercept(counting)
                .disableServiceConfigLookUp()
                .defaultServiceConfig(retryConfig(3))
                .enableRetry()
                .build();
        try {
            ClientCalls.blockingUnaryCall(channel, METHOD, CallOptions.DEFAULT, new byte[] {0});

            assertThat(backendCalls.get()).isEqualTo(3);
            // This is what lets the circuit breaker count logical calls: retries happen below it.
            assertThat(interceptedCalls.get()).isEqualTo(1);
        } finally {
            channel.shutdownNow();
            server.shutdownNow();
        }
    }

    @Test
    @DisplayName("PROBE: does a per-method service config entry suppress retry for that method only?")
    void probe_perMethodConfig() throws Exception {
        AtomicInteger backendCalls = new AtomicInteger();
        Server server = startServer(backendCalls, 99);
        // Wildcard retries, but an explicit entry for probe.Service/Call with no retryPolicy.
        Map<String, Object> config = Map.of(
                "methodConfig",
                List.of(
                        // No retryPolicy at all: the documented way to say "do not retry this one".
                        Map.of("name", List.of(Map.of("service", "probe.Service", "method", "Call"))),
                        Map.of(
                                "name",
                                List.of(Map.of()),
                                "retryPolicy",
                                Map.of(
                                        "maxAttempts",
                                        3.0,
                                        "initialBackoff",
                                        "0.01s",
                                        "maxBackoff",
                                        "0.02s",
                                        "backoffMultiplier",
                                        2.0,
                                        "retryableStatusCodes",
                                        List.of("UNAVAILABLE")))));
        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", server.getPort())
                .usePlaintext()
                .disableServiceConfigLookUp()
                .defaultServiceConfig(config)
                .enableRetry()
                .build();
        try {
            assertThatUnavailable(channel);
            System.out.println("PROBE per-method attempts=" + backendCalls.get());
        } finally {
            channel.shutdownNow();
            server.shutdownNow();
        }
    }

    private static void assertThatUnavailable(ManagedChannel channel) {
        try {
            ClientCalls.blockingUnaryCall(channel, METHOD, CallOptions.DEFAULT, new byte[] {0});
            throw new AssertionError("expected the call to fail");
        } catch (StatusRuntimeException e) {
            assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
        }
    }

    private static final class ByteMarshaller implements MethodDescriptor.Marshaller<byte[]> {
        @Override
        public java.io.InputStream stream(byte[] value) {
            return new java.io.ByteArrayInputStream(value);
        }

        @Override
        public byte[] parse(java.io.InputStream stream) {
            try {
                return stream.readAllBytes();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
