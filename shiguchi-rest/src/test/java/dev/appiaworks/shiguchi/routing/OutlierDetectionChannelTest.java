package dev.appiaworks.shiguchi.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.gateway.GrpcLoggingInterceptor;
import dev.appiaworks.shiguchi.gateway.GrpcMetricsInterceptor;
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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
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
 * {@code services[].outlier-detection} on a real channel: two instances registered under one
 * discovery name, one of which fails every call. With outlier detection the failing instance is
 * ejected after an interval and calls stop failing; without it, roughly half keep failing.
 */
@DisplayName("Outlier detection on a discovery channel")
class OutlierDetectionChannelTest {

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

    private static final MethodDescriptor<byte[], byte[]> GET = MethodDescriptor.<byte[], byte[]>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("test.Echo/Get")
            .setRequestMarshaller(BYTES)
            .setResponseMarshaller(BYTES)
            .build();

    private Server healthy;
    private Server broken;
    private DiscoveryNameResolverProvider resolver;
    private ManagedChannel channel;

    @BeforeEach
    void start() throws IOException {
        healthy = server(Status.OK);
        broken = server(Status.UNAVAILABLE);
        DiscoveryClient client = mock(DiscoveryClient.class);
        List<ServiceInstance> instances = List.of(
                new DefaultServiceInstance("a", "orders", "localhost", healthy.getPort(), false),
                new DefaultServiceInstance("b", "orders", "localhost", broken.getPort(), false));
        when(client.getInstances("orders")).thenReturn(instances);
        resolver = new DiscoveryNameResolverProvider(provider(client), Duration.ofSeconds(30), true);
    }

    @AfterEach
    void stop() throws InterruptedException {
        if (channel != null) channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        resolver.destroy();
        healthy.shutdownNow();
        broken.shutdownNow();
    }

    private static Server server(Status answer) throws IOException {
        ServerServiceDefinition service = ServerServiceDefinition.builder("test.Echo")
                .addMethod(GET, ServerCalls.asyncUnaryCall((request, observer) -> {
                    if (answer.isOk()) {
                        observer.onNext(request);
                        observer.onCompleted();
                    } else {
                        observer.onError(answer.asRuntimeException());
                    }
                }))
                .build();
        return ServerBuilder.forPort(0).addService(service).build().start();
    }

    private ServiceDefinition orders(boolean outlierDetection) {
        ServiceDefinition def = new ServiceDefinition();
        def.setName("orders");
        def.setDiscoveryName("orders");
        var od = def.getOutlierDetection();
        od.setEnabled(outlierDetection);
        od.setInterval(Duration.ofMillis(500));
        od.setBaseEjectionTime(Duration.ofMinutes(1));
        od.getFailurePercentage().setRequestVolume(5);
        return def;
    }

    /** Failures among {@code calls} sequential calls. */
    private int failures(int calls) {
        int failed = 0;
        for (int i = 0; i < calls; i++) {
            try {
                ClientCalls.blockingUnaryCall(
                        channel, GET, CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS), new byte[] {1});
            } catch (StatusRuntimeException e) {
                failed++;
            }
        }
        return failed;
    }

    /** Warms both instances past the request volume, then lets at least one interval pass. */
    private void warmUpAndWaitAnInterval() throws InterruptedException {
        failures(30);
        Thread.sleep(1_200);
    }

    @Test
    @DisplayName(
            "GIVEN outlier detection and one instance failing every call WHEN an interval has passed SHOULD eject it so calls stop failing")
    void given_outlierDetection_when_intervalPassed_should_ejectFailingInstance() throws InterruptedException {
        channel = factory().build(orders(true));

        warmUpAndWaitAnInterval();

        assertThat(failures(40)).isZero();
    }

    @Test
    @DisplayName(
            "GIVEN no outlier detection and one instance failing every call WHEN calls continue SHOULD keep sending it about half of them")
    void given_noOutlierDetection_when_callsContinue_should_keepFailing() throws InterruptedException {
        channel = factory().build(orders(false));

        warmUpAndWaitAnInterval();

        // round_robin over two instances, one broken: every other call fails.
        assertThat(failures(40)).isBetween(15, 25);
    }

    // ---------------------------------------------------------------- helpers

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public java.util.stream.Stream<T> stream() {
                return value == null ? java.util.stream.Stream.empty() : java.util.stream.Stream.of(value);
            }
        };
    }

    /** A mock interceptor that hands every call straight to the next channel. */
    private static <T extends io.grpc.ClientInterceptor> T passThrough(Class<T> type) {
        return mock(type, invocation -> {
            if (invocation.getMethod().getName().equals("interceptCall")) {
                MethodDescriptor<?, ?> method = invocation.getArgument(0);
                CallOptions options = invocation.getArgument(1);
                io.grpc.Channel next = invocation.getArgument(2);
                return next.newCall(method, options);
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
    }

    private GrpcChannelFactory factory() {
        return new GrpcChannelFactory(
                new GatewayProperties(),
                passThrough(GrpcMetricsInterceptor.class),
                passThrough(GrpcLoggingInterceptor.class),
                Optional.empty(),
                provider((GrpcChannelBuilderCustomizer) null),
                provider((GrpcServiceAddressResolver) null),
                provider(resolver));
    }
}
