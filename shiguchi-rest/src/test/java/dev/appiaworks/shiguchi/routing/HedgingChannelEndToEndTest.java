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
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The hedging configuration as a channel actually behaves, not as a map: a channel built by
 * {@link GrpcChannelFactory} from a service's {@code hedging} block is pointed at a backend that
 * delays its first attempt, and the hedge attempts that land during that delay are counted.
 */
@DisplayName("Hedging policy on a real channel")
class HedgingChannelEndToEndTest {

    private static final String SERVICE = "orders.OrderService";

    private final Map<String, AtomicInteger> callsByMethod = new ConcurrentHashMap<>();
    private final Map<String, Long> sleepUntilMs = new ConcurrentHashMap<>();
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
                int call = callsByMethod
                        .computeIfAbsent(name, k -> new AtomicInteger())
                        .incrementAndGet();
                long sleepUntil = sleepUntilMs.getOrDefault(name, 0L);
                // Only the first call of a method is slow; a hedge (the second call) answers at once.
                // A fixed 2s from arrival, not a deadline set before the channel connected, so
                // connection setup cannot eat into the window the assertions measure.
                if (call == 1 && sleepUntil > 0) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    observer.onNext(new byte[] {1});
                } else {
                    observer.onNext(new byte[] {2});
                }
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

    private ServiceDefinition serviceDefinition(boolean hedgingEnabled) {
        var svc = new ServiceDefinition();
        svc.setName("orders");
        svc.setHost("localhost");
        svc.setPort(backend.getPort());
        svc.setInsecure(true);
        svc.getRetry().setEnabled(false);
        svc.getHedging().setEnabled(hedgingEnabled);
        svc.getHedging().setMaxAttempts(2);
        svc.getHedging().setHedgingDelay(Duration.ofMillis(50));
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
            "GIVEN hedging is enabled WHEN the first attempt is slow SHOULD answer from the hedge (second attempt)")
    void given_hedgingEnabled_when_firstAttemptSlow_should_answerFromHedge() {
        // Sleep 2000ms from now, so the first attempt hits the delay.
        sleepUntilMs.put("GetOrder", System.currentTimeMillis() + 2000);
        channel = factory().build(serviceDefinition(true));

        long startMs = System.currentTimeMillis();
        byte[] reply = ClientCalls.blockingUnaryCall(
                channel, method("GetOrder"), CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS), new byte[] {0
                });
        long elapsedMs = System.currentTimeMillis() - startMs;

        // The hedge is sent after hedgingDelay (50ms), while the first is still sleeping.
        // The hedge should reply with {2} before the first attempt finishes with {1}.
        assertThat(reply).containsExactly(2);
        assertThat(elapsedMs).isLessThan(1500);
        // Both the first and the hedge attempt reach the backend.
        assertThat(callsTo("GetOrder")).isEqualTo(2);
    }

    @Test
    @DisplayName(
            "GIVEN hedging is enabled but the method is excluded WHEN the first attempt is slow SHOULD wait for it")
    void given_hedgingEnabledMethodExcluded_when_firstAttemptSlow_should_waitForIt() {
        sleepUntilMs.put("CreateOrder", System.currentTimeMillis() + 2000);
        channel = factory().build(serviceDefinition(true), Set.of(SERVICE + "/CreateOrder"));

        long startMs = System.currentTimeMillis();
        byte[] reply = ClientCalls.blockingUnaryCall(
                channel, method("CreateOrder"), CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS), new byte[] {
                    0
                });
        long elapsedMs = System.currentTimeMillis() - startMs;

        // Excluded methods are not hedged, so no hedge is sent. The call waits for the slow first attempt.
        assertThat(reply).containsExactly(1);
        assertThat(elapsedMs).isGreaterThanOrEqualTo(1500);
        assertThat(callsTo("CreateOrder")).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "GIVEN hedging is disabled WHEN the first attempt is slow SHOULD wait for it and make exactly one attempt")
    void given_hedgingDisabled_when_firstAttemptSlow_should_waitForIt() {
        sleepUntilMs.put("GetOrder", System.currentTimeMillis() + 1500);
        channel = factory().build(serviceDefinition(false));

        long startMs = System.currentTimeMillis();
        byte[] reply = ClientCalls.blockingUnaryCall(
                channel, method("GetOrder"), CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS), new byte[] {0
                });
        long elapsedMs = System.currentTimeMillis() - startMs;

        // Without hedging, a single slow attempt is made.
        assertThat(reply).containsExactly(1);
        assertThat(elapsedMs).isGreaterThanOrEqualTo(1500);
        assertThat(callsTo("GetOrder")).isEqualTo(1);
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
