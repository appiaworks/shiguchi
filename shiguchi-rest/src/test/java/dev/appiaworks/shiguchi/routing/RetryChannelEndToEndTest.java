package dev.appiaworks.shiguchi.routing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
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
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The retry configuration as a channel actually behaves, not as a map: a channel built by
 * {@link GrpcChannelFactory} from a service's {@code retry} block is pointed at a backend that
 * fails a set number of times, and the attempts that reach it are counted.
 */
@DisplayName("Retry policy on a real channel")
class RetryChannelEndToEndTest {

    private static final String SERVICE = "orders.OrderService";

    private final Map<String, AtomicInteger> callsByMethod = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> failuresLeft = new ConcurrentHashMap<>();
    private Server backend;
    private ManagedChannel channel;

    private static MethodDescriptor<byte[], byte[]> method(String name) {
        return MethodDescriptor.<byte[], byte[]>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(SERVICE + "/" + name)
                .setRequestMarshaller(new ByteMarshaller())
                .setResponseMarshaller(new ByteMarshaller())
                .build();
    }

    @BeforeEach
    void startBackend() throws IOException {
        var builder = ServerServiceDefinition.builder(SERVICE);
        for (String name : new String[] {"GetOrder", "CreateOrder"}) {
            MethodDescriptor<byte[], byte[]> md = method(name);
            builder.addMethod(md, ServerCalls.asyncUnaryCall((request, observer) -> {
                callsByMethod.computeIfAbsent(name, k -> new AtomicInteger()).incrementAndGet();
                AtomicInteger remaining = failuresLeft.computeIfAbsent(name, k -> new AtomicInteger());
                if (remaining.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                    observer.onError(
                            Status.UNAVAILABLE.withDescription("not today").asRuntimeException());
                    return;
                }
                observer.onNext(new byte[] {1});
                observer.onCompleted();
            }));
        }
        backend = ServerBuilder.forPort(0).addService(builder.build()).build().start();
    }

    @AfterEach
    void stop() {
        if (channel != null) channel.shutdownNow();
        if (backend != null) backend.shutdownNow();
    }

    private ServiceDefinition serviceDefinition(boolean retryEnabled) {
        var svc = new ServiceDefinition();
        svc.setName("orders");
        svc.setHost("localhost");
        svc.setPort(backend.getPort());
        svc.setInsecure(true);
        svc.getRetry().setEnabled(retryEnabled);
        svc.getRetry().setMaxAttempts(3);
        svc.getRetry().setInitialBackoff(Duration.ofMillis(10));
        svc.getRetry().setMaxBackoff(Duration.ofMillis(20));
        return svc;
    }

    private GrpcChannelFactory factory() {
        var properties = new GatewayProperties();
        return new GrpcChannelFactory(
                properties,
                new dev.appiaworks.shiguchi.gateway.GrpcMetricsInterceptor(new EmptyProvider<>()),
                new dev.appiaworks.shiguchi.gateway.GrpcLoggingInterceptor(),
                java.util.Optional.empty(),
                new EmptyProvider<>(),
                new EmptyProvider<>());
    }

    private int callsTo(String method) {
        return callsByMethod.getOrDefault(method, new AtomicInteger()).get();
    }

    @Test
    @DisplayName(
            "GIVEN retry is enabled with default throttling WHEN the backend fails twice then succeeds SHOULD retry until it works")
    void given_retryEnabled_when_transientFailure_should_retry() {
        // This test runs with the default retry budget (maxTokens=10, tokenRatio=0.1): the budget
        // is large enough that transient failures are retried normally.
        failuresLeft.put("GetOrder", new AtomicInteger(2));
        channel = factory().build(serviceDefinition(true));

        byte[] reply = ClientCalls.blockingUnaryCall(channel, method("GetOrder"), CallOptions.DEFAULT, new byte[] {0});

        assertThat(reply).containsExactly(1);
        assertThat(callsTo("GetOrder")).isEqualTo(3);
    }

    @Test
    @DisplayName("GIVEN retry is disabled WHEN the backend fails SHOULD make exactly one attempt")
    void given_retryDisabled_when_failing_should_notRetry() {
        failuresLeft.put("GetOrder", new AtomicInteger(2));
        channel = factory().build(serviceDefinition(false));

        assertThatFails("GetOrder");

        assertThat(callsTo("GetOrder")).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "GIVEN a method excluded as non-idempotent WHEN it fails SHOULD not be retried, while another method still is")
    void given_excludedMethod_when_failing_should_notRetryThatOne() {
        failuresLeft.put("CreateOrder", new AtomicInteger(5));
        failuresLeft.put("GetOrder", new AtomicInteger(2));
        channel = factory().build(serviceDefinition(true), Set.of(SERVICE + "/CreateOrder"));

        assertThatFails("CreateOrder");
        ClientCalls.blockingUnaryCall(channel, method("GetOrder"), CallOptions.DEFAULT, new byte[] {0});

        // The named entry beats the wildcard for CreateOrder, and only for it.
        assertThat(callsTo("CreateOrder")).isEqualTo(1);
        assertThat(callsTo("GetOrder")).isEqualTo(3);
    }

    @Test
    @DisplayName(
            "GIVEN a status outside retryable-status-codes WHEN it is returned SHOULD surface immediately without retrying")
    void given_nonRetryableStatus_when_returned_should_notRetry() {
        var svc = serviceDefinition(true);
        svc.getRetry().setRetryableStatusCodes(new java.util.ArrayList<>(java.util.List.of("DEADLINE_EXCEEDED")));
        failuresLeft.put("GetOrder", new AtomicInteger(2));
        channel = factory().build(svc);

        assertThatFails("GetOrder");

        assertThat(callsTo("GetOrder")).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "GIVEN throttling enabled with max-tokens 2 WHEN a call keeps failing SHOULD not retry at all because the budget exhausts")
    void given_throttlingWithLowMaxTokens_when_failing_should_notRetry() {
        var svc = serviceDefinition(true);
        svc.getRetry().getThrottling().setMaxTokens(2);
        // With maxTokens=2, the first failure costs 1 token, leaving 1 token (50% of 2).
        // gRPC stops retrying when tokens drop to or below half the max, so no retry is sent.
        failuresLeft.put("GetOrder", new AtomicInteger(10));
        channel = factory().build(svc);

        assertThatFails("GetOrder");

        assertThat(callsTo("GetOrder")).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "GIVEN throttling disabled WHEN a call keeps failing SHOULD use every attempt without budget constraints")
    void given_throttlingDisabled_when_failing_should_useEveryAttempt() {
        var svc = serviceDefinition(true);
        svc.getRetry().getThrottling().setEnabled(false);
        // Without throttling, every failure is retried up to maxAttempts (3), regardless of the budget.
        failuresLeft.put("GetOrder", new AtomicInteger(10));
        channel = factory().build(svc);

        assertThatFails("GetOrder");

        assertThat(callsTo("GetOrder")).isEqualTo(3);
    }

    private void assertThatFails(String methodName) {
        try {
            ClientCalls.blockingUnaryCall(channel, method(methodName), CallOptions.DEFAULT, new byte[] {0});
            throw new AssertionError("expected " + methodName + " to fail");
        } catch (StatusRuntimeException e) {
            assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
        }
    }

    /** An ObjectProvider with nothing in it; the factory only streams over these. */
    private static final class EmptyProvider<T> implements org.springframework.beans.factory.ObjectProvider<T> {
        @Override
        public T getObject() {
            throw new IllegalStateException("empty");
        }

        @Override
        public T getObject(Object... args) {
            throw new IllegalStateException("empty");
        }

        @Override
        public T getIfAvailable() {
            return null;
        }

        @Override
        public T getIfUnique() {
            return null;
        }

        @Override
        public java.util.stream.Stream<T> stream() {
            return java.util.stream.Stream.empty();
        }

        @Override
        public java.util.stream.Stream<T> orderedStream() {
            return java.util.stream.Stream.empty();
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
