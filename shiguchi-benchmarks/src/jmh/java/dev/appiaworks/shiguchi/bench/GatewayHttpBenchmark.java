package dev.appiaworks.shiguchi.bench;

import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import com.google.protobuf.util.JsonFormat;
import dev.appiaworks.shiguchi.bench.proto.GetOrderRequest;
import dev.appiaworks.shiguchi.bench.proto.OrderResponse;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerServiceDefinition;
import io.grpc.protobuf.ProtoFileDescriptorSupplier;
import io.grpc.protobuf.ProtoServiceDescriptorSupplier;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * The whole gateway over HTTP: a Boot 3 gateway on a port, a reflection-served {@code
 * bench.Orders} backend on a socket, one {@code POST /bench.Orders/Get} per operation through a
 * pooled reactor-netty client. The serde benchmarks measure a few microseconds of gateway code;
 * this one shows what surrounds them — the HTTP server, Spring Cloud Gateway's filter chain,
 * the gateway's own routing and dispatch, and a real gRPC hop — so the next optimisation
 * target is chosen on the whole request, not the part that is easy to measure.
 *
 * <ul>
 *   <li>{@code http_floor} — the same POST to a bare reactor-netty server answering the same
 *       JSON: the HTTP client + server cost with no gateway code at all.
 *   <li>{@code grpc_hop} — the backend call alone over the socket channel the gateway would use.
 *   <li>{@code gateway_post} — the gateway end to end. Its excess over the two above is the
 *       gateway's own cost: Spring WebFlux + Cloud Gateway + routing + request encoding + Tier 0.
 * </ul>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 3)
@Fork(1)
public class GatewayHttpBenchmark {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class GatewayApp {}

    private static final String REQUEST_BODY = "{\"id\":\"order-1\"}";

    /**
     * {@code default}: the gateway as it boots. {@code lean}: observations and the gateway
     * framework's metrics off, Reactor's automatic context propagation off — what those cost.
     */
    /**
     * Not in the default list, passed with {@code -p profile=...}: {@code no-security}
     * ({@code shiguchi.security.open-rest-bypasses-chain}) and {@code no-security-observations}
     * (Spring Security's per-filter observations off).
     */
    @Param({"default", "no-otel", "lean"})
    public String profile;

    /** The channel the gateway built for the service: its interceptors and settings, no HTTP. */
    private io.grpc.Channel gatewayChannel;

    private Server backend;
    private ConfigurableApplicationContext gateway;
    private DisposableServer floor;
    private ManagedChannel channel;
    private io.grpc.MethodDescriptor<GetOrderRequest, OrderResponse> getMethod;
    private WebClient gatewayClient;
    private WebClient floorClient;
    private String gatewayPath;

    /** The proto schema server reflection serves for the dynamic service. */
    private record Schema(FileDescriptor file, ServiceDescriptor service)
            implements ProtoFileDescriptorSupplier, ProtoServiceDescriptorSupplier {
        @Override
        public FileDescriptor getFileDescriptor() {
            return file;
        }

        @Override
        public ServiceDescriptor getServiceDescriptor() {
            return service;
        }
    }

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        OrderResponse order = BenchOrders.order(1, 3);
        String json = JsonFormat.printer().omittingInsignificantWhitespace().print(order);

        // Backend: compiled stubs, reflection on, a real socket.
        FileDescriptor file = OrderResponse.getDescriptor().getFile();
        getMethod = io.grpc.MethodDescriptor.<GetOrderRequest, OrderResponse>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("bench.Orders/Get")
                .setRequestMarshaller(ProtoUtils.marshaller(GetOrderRequest.getDefaultInstance()))
                .setResponseMarshaller(ProtoUtils.marshaller(OrderResponse.getDefaultInstance()))
                .build();
        var serviceDescriptor = io.grpc.ServiceDescriptor.newBuilder("bench.Orders")
                .setSchemaDescriptor(new Schema(file, file.findServiceByName("Orders")))
                .addMethod(getMethod)
                .build();
        ServerServiceDefinition service = ServerServiceDefinition.builder(serviceDescriptor)
                .addMethod(getMethod, ServerCalls.asyncUnaryCall((req, obs) -> {
                    obs.onNext(order);
                    obs.onCompleted();
                }))
                .build();
        backend = ServerBuilder.forPort(0)
                .addService(service)
                .addService(ProtoReflectionServiceV1.newInstance())
                .build()
                .start();
        channel = ManagedChannelBuilder.forAddress("localhost", backend.getPort())
                .usePlaintext()
                .build();

        // The gateway: DYNAMIC discovery against the backend, auto-mapped REST routes, nothing else.
        java.util.List<String> properties = new java.util.ArrayList<>(java.util.List.of(
                "server.port=0",
                "spring.main.banner-mode=off",
                "logging.level.root=WARN",
                "shiguchi.ui.enabled=false",
                "shiguchi.graphql.enabled=false",
                "shiguchi.openapi.enabled=false",
                "shiguchi.rest.auto-mapping=true",
                "shiguchi.services[0].name=orders",
                "shiguchi.services[0].mode=DYNAMIC",
                "shiguchi.services[0].host=localhost",
                "shiguchi.services[0].port=" + backend.getPort()));
        if ("lean".equals(profile)) {
            // Observations and the gateway framework's metrics off, Reactor context propagation off.
            properties.addAll(java.util.List.of(
                    "management.observations.enable.spring.security=false",
                    "management.observations.enable.http.server.requests=false",
                    "management.observations.enable.http=false",
                    "management.tracing.enabled=false",
                    "spring.cloud.gateway.metrics.enabled=false",
                    "spring.cloud.gateway.server.webflux.metrics.enabled=false",
                    "spring.cloud.gateway.observability.enabled=false",
                    "spring.cloud.gateway.server.webflux.observability.enabled=false",
                    "spring.reactor.context-propagation=limited"));
        }
        if ("no-security-observations".equals(profile)) {
            properties.add("management.observations.enable.spring.security=false");
        }
        if ("no-security".equals(profile)) {
            properties.add("shiguchi.security.open-rest-bypasses-chain=true");
        }
        if ("no-otel".equals(profile)) {
            // No OpenTelemetry bean, so no GrpcOpenTelemetry plugin on the channel and no tracer.
            properties.add(
                    "spring.autoconfigure.exclude="
                            + "org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration,"
                            + "org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryTracingAutoConfiguration,"
                            + "org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration,"
                            + "org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryAutoConfiguration,"
                            + "org.springframework.boot.actuate.autoconfigure.tracing.NoopTracerAutoConfiguration,"
                            + "org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpTracingAutoConfiguration,"
                            + "org.springframework.boot.actuate.autoconfigure.logging.OpenTelemetryLoggingAutoConfiguration,"
                            + "org.springframework.boot.actuate.autoconfigure.logging.otlp.OtlpLoggingAutoConfiguration,"
                            + "org.springframework.boot.actuate.autoconfigure.metrics.export.otlp.OtlpMetricsExportAutoConfiguration");
        }
        gateway = new SpringApplicationBuilder(GatewayApp.class)
                .web(WebApplicationType.REACTIVE)
                .properties(properties.toArray(String[]::new))
                .run();
        int gatewayPort = Integer.parseInt(gateway.getEnvironment().getRequiredProperty("local.server.port"));
        gatewayChannel = gateway.getBean(dev.appiaworks.shiguchi.routing.GrpcRouteRegistry.class)
                .contextByServiceName("orders")
                .orElseThrow()
                .getChannel();
        gatewayPath = "/bench.Orders/Get";
        gatewayClient = WebClient.create("http://localhost:" + gatewayPort);

        // The HTTP floor: a bare server answering the same JSON.
        floor = HttpServer.create()
                .port(0)
                .route(r -> r.post(
                        "/floor",
                        (req, res) -> res.header("Content-Type", "application/json")
                                .sendString(req.receive().aggregate().then(Mono.just(json)))))
                .bindNow();
        floorClient = WebClient.create("http://localhost:" + floor.port());

        // Warm the connections and verify the gateway answers the order.
        var entity = gatewayClient
                .post()
                .uri(gatewayPath)
                .header("Content-Type", "application/json")
                .bodyValue(REQUEST_BODY)
                .retrieve()
                .toEntity(String.class)
                .block(Duration.ofSeconds(30));
        String viaGateway = entity.getBody();
        if (viaGateway == null || !viaGateway.contains("\"benchmark-order-1\"")) {
            throw new IllegalStateException("gateway did not serve the order: " + viaGateway);
        }
        post(floorClient, "/floor");
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (gateway != null) {
            // The gateway's own timers: where the request's time went inside it.
            var registry = gateway.getBean(io.micrometer.core.instrument.MeterRegistry.class);
            for (var timer : registry.find("http.server.requests").timers()) {
                System.err.printf(
                        "TIMER http.server.requests count=%d mean=%.1fus max=%.1fus%n",
                        timer.count(), timer.mean(TimeUnit.MICROSECONDS), timer.max(TimeUnit.MICROSECONDS));
            }
            for (var timer : registry.find("grpc.client.call.duration").timers()) {
                System.err.printf(
                        "TIMER grpc.client.call.duration count=%d mean=%.1fus max=%.1fus%n",
                        timer.count(), timer.mean(TimeUnit.MICROSECONDS), timer.max(TimeUnit.MICROSECONDS));
            }
            gateway.close();
        }
        if (floor != null) floor.disposeNow();
        if (channel != null) channel.shutdownNow();
        if (backend != null) backend.shutdownNow();
    }

    private static String post(WebClient client, String path) {
        return client.post()
                .uri(path)
                .header("Content-Type", "application/json")
                .bodyValue(REQUEST_BODY)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(30));
    }

    @Benchmark
    public String http_floor() {
        return post(floorClient, "/floor");
    }

    @Benchmark
    public OrderResponse grpc_hop() {
        return ClientCalls.blockingUnaryCall(
                channel,
                getMethod,
                CallOptions.DEFAULT,
                GetOrderRequest.newBuilder().setId("order-1").build());
    }

    /** The backend hop through the gateway's channel: what its interceptors and settings add to {@link #grpc_hop}. */
    @Benchmark
    public OrderResponse grpc_hop_gatewayChannel() {
        return ClientCalls.blockingUnaryCall(
                gatewayChannel,
                getMethod,
                CallOptions.DEFAULT,
                GetOrderRequest.newBuilder().setId("order-1").build());
    }

    /** The same call issued asynchronously (as the dispatcher does), awaited on a future. */
    @Benchmark
    public OrderResponse grpc_hop_async_gatewayChannel() throws Exception {
        var done = new java.util.concurrent.CompletableFuture<OrderResponse>();
        ClientCalls.asyncUnaryCall(
                gatewayChannel.newCall(getMethod, CallOptions.DEFAULT),
                GetOrderRequest.newBuilder().setId("order-1").build(),
                new io.grpc.stub.StreamObserver<OrderResponse>() {
                    @Override
                    public void onNext(OrderResponse value) {
                        done.complete(value);
                    }

                    @Override
                    public void onError(Throwable t) {
                        done.completeExceptionally(t);
                    }

                    @Override
                    public void onCompleted() {}
                });
        return done.get(30, TimeUnit.SECONDS);
    }

    /** The async call started from another thread (a scheduler worker), as a request thread would. */
    @Benchmark
    public OrderResponse grpc_hop_async_fromOtherThread() throws Exception {
        var done = new java.util.concurrent.CompletableFuture<OrderResponse>();
        reactor.core.scheduler.Schedulers.parallel()
                .schedule(() -> ClientCalls.asyncUnaryCall(
                        gatewayChannel.newCall(getMethod, CallOptions.DEFAULT),
                        GetOrderRequest.newBuilder().setId("order-1").build(),
                        new io.grpc.stub.StreamObserver<OrderResponse>() {
                            @Override
                            public void onNext(OrderResponse value) {
                                done.complete(value);
                            }

                            @Override
                            public void onError(Throwable t) {
                                done.completeExceptionally(t);
                            }

                            @Override
                            public void onCompleted() {}
                        }));
        return done.get(30, TimeUnit.SECONDS);
    }

    @Benchmark
    public String gateway_post() {
        return post(gatewayClient, gatewayPath);
    }
}
