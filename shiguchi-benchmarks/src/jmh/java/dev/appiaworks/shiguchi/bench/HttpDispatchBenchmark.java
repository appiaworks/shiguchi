package dev.appiaworks.shiguchi.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.JsonFormat;
import dev.appiaworks.protobuf.extras.serde.WireJsonTranscoder;
import dev.appiaworks.shiguchi.bench.proto.GetOrderRequest;
import dev.appiaworks.shiguchi.bench.proto.Item;
import dev.appiaworks.shiguchi.bench.proto.OrderResponse;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.grpc.GrpcDescriptorCache;
import dev.appiaworks.shiguchi.rest.GrpcRequestBuilder;
import dev.appiaworks.shiguchi.rest.HttpUpstreamDispatcher;
import dev.appiaworks.shiguchi.rest.ReactiveGrpcDispatcher;
import dev.appiaworks.shiguchi.routing.HttpOperationBinding;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import dev.appiaworks.shiguchi.serde.DynamicProtoSerde;
import dev.appiaworks.shiguchi.serde.DynamicSerializerFactory;
import dev.appiaworks.shiguchi.serde.SerdeSelector;
import dev.appiaworks.shiguchi.serde.StaticSerdeDetector;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ServerCalls;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * The GraphQL surface's two transports for one field, measured through the dispatchers the data
 * fetcher actually calls: {@link ReactiveGrpcDispatcher#dispatchToMap} against an in-process
 * gRPC backend, and {@link HttpUpstreamDispatcher#dispatchToMap} against a reactor-netty stub
 * answering the same order as JSON — the {@code REST_DOWNSTREAM} path. Both end in the same
 * projected {@code Map}, so the gap is the transport (a real loopback socket for HTTP, an
 * in-process channel for gRPC) plus the decode: wire → message → Map on one side, JSON → Map →
 * synthetic message → Map on the other.
 *
 * <p>{@code http_webClientOnly} is the HTTP floor — the same request with no gateway code after the
 * body arrives — so the HTTP dispatcher's own cost is {@code http_dispatchToMap} minus it.
 *
 * <p>{@code http_bindOnly} isolates the bind step inside {@code http_dispatchToMap}: the already
 * decoded response {@code Map} turned into the projected {@code Map} (the dispatcher's
 * {@code bind()}: {@code SerdeSelector.deserializeFromMap} into the synthetic message, then
 * {@code serializeToMapProjected}), with no socket, no request building and no JSON parsing.
 *
 * <p>Payload: the end-to-end fixture (6 scalars, 3 nested items, 3-entry map, Timestamp), as a
 * compiled stub on the gRPC side and as its JSON rendering on the HTTP side.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class HttpDispatchBenchmark {

    private Server grpcServer;
    private ManagedChannel grpcChannel;
    private DisposableServer httpStub;

    private ReactiveGrpcDispatcher grpcDispatcher;
    private HttpUpstreamDispatcher httpDispatcher;
    private WebClient rawClient;
    private RouteDefinition grpcRoute;
    private RouteDefinition httpRoute;
    private ServiceEndpointContext grpcCtx;
    private ServiceEndpointContext httpCtx;
    private Map<String, Object> args;
    private SerdeSelector serde;
    private Descriptor outputType;
    private Map<String, Object> decodedBody;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        OrderResponse order = order();
        String json = JsonFormat.printer().omittingInsignificantWhitespace().print(order);

        // gRPC backend: the compiled stub answers the same order every time.
        var method = io.grpc.MethodDescriptor.<GetOrderRequest, OrderResponse>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("bench.Orders/Get")
                .setRequestMarshaller(ProtoUtils.marshaller(GetOrderRequest.getDefaultInstance()))
                .setResponseMarshaller(ProtoUtils.marshaller(OrderResponse.getDefaultInstance()))
                .build();
        var service = ServerServiceDefinition.builder("bench.Orders")
                .addMethod(method, ServerCalls.asyncUnaryCall((req, obs) -> {
                    obs.onNext(order);
                    obs.onCompleted();
                }))
                .build();
        String name = InProcessServerBuilder.generateName();
        grpcServer = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(service)
                .build()
                .start();
        grpcChannel = InProcessChannelBuilder.forName(name).directExecutor().build();

        // HTTP backend: the same order as JSON, over a real loopback socket.
        httpStub = HttpServer.create()
                .port(0)
                .route(r -> r.get(
                        "/orders/{id}",
                        (req, res) ->
                                res.header("Content-Type", "application/json").sendString(Mono.just(json))))
                .bindNow();
        String baseUrl = "http://localhost:" + httpStub.port();

        var props = new GatewayProperties();
        props.getClient().setDefaultDeadline(java.time.Duration.ZERO);
        serde = new SerdeSelector(new StaticSerdeDetector(), new DynamicProtoSerde(new GrpcDescriptorCache()));
        grpcDispatcher = new ReactiveGrpcDispatcher(
                new GrpcRequestBuilder(), serde, new GrpcDescriptorCache(), new WireJsonTranscoder(), props);
        httpDispatcher = new HttpUpstreamDispatcher(serde, props, Optional.empty(), List.of());
        rawClient = WebClient.builder().baseUrl(baseUrl).build();

        // Both contexts carry the fixture file rebuilt dynamically, as a reflection-served
        // descriptor would be; the HTTP one is what RestDownstreamRouteLoader builds from an
        // OpenAPI document whose Order schema matches.
        FileDescriptor file = FileDescriptor.buildFrom(
                OrderResponse.getDescriptor().getFile().toProto(),
                new FileDescriptor[] {Timestamp.getDescriptor().getFile()});
        grpcCtx = context("orders-grpc", file, grpcChannel);
        grpcRoute = RouteDefinition.builder("bench.Orders", "Get", MethodType.UNARY, "orders-grpc")
                .httpBinding("GET", "/grpc/v1/orders/{id}")
                .build();
        grpcCtx.addRoute(grpcRoute);

        httpCtx = context("orders-http", file, null);
        httpCtx.putHttpBinding(
                "Get",
                new HttpOperationBinding(
                        "GET",
                        "/orders/{id}",
                        List.of("id"),
                        List.of(),
                        false,
                        false,
                        false,
                        List.of(),
                        List.of("application/json"),
                        null,
                        "OrderResponse"));
        httpRoute = RouteDefinition.builder("bench.Orders", "Get", MethodType.UNARY, baseUrl)
                .httpUpstream(baseUrl)
                .httpBinding("GET", "/http/v1/orders/{id}")
                .build();
        httpCtx.addRoute(httpRoute);

        args = Map.of("id", "order-1");

        // The bind step's input: the response body parsed to a Map, as the dispatcher hands it to bind().
        outputType = file.findMessageTypeByName("OrderResponse");
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = new ObjectMapper().readValue(json, Map.class);
        decodedBody = parsed;

        // Both legs must agree before anything is measured.
        Map<String, Object> viaGrpc = grpc_dispatchToMap();
        Map<String, Object> viaHttp = http_dispatchToMap();
        if (!viaGrpc.equals(viaHttp)) {
            throw new IllegalStateException("transports disagree: grpc=" + viaGrpc + " http=" + viaHttp);
        }
        if (!http_bindOnly().equals(viaHttp)) {
            throw new IllegalStateException("bind step disagrees with the dispatcher: " + http_bindOnly());
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        grpcChannel.shutdownNow();
        grpcServer.shutdownNow();
        httpStub.disposeNow();
    }

    /** gRPC transport: request Map → DynamicMessage → in-process call → message → projected Map. */
    @Benchmark
    public Map<String, Object> grpc_dispatchToMap() {
        return grpcDispatcher.dispatchToMap(grpcRoute, grpcCtx, args, null).block();
    }

    /** HTTP transport: request Map → URI → loopback GET → JSON → Map → synthetic message → Map. */
    @Benchmark
    public Map<String, Object> http_dispatchToMap() {
        return httpDispatcher
                .dispatchToMap(httpRoute, httpCtx, args, null, null)
                .block();
    }

    /**
     * The bind step alone: decoded response Map → synthetic message → projected Map (what the
     * dispatcher's private {@code bind()} does, called here through the same two SerdeSelector
     * methods with the same descriptor, context and projection).
     */
    @Benchmark
    public Map<String, Object> http_bindOnly() throws Exception {
        DynamicMessage message = serde.deserializeFromMap(decodedBody, outputType, httpCtx);
        return serde.serializeToMapProjected(message, outputType, httpCtx, null);
    }

    /** The HTTP floor: the same GET through a bare WebClient, body read and discarded. */
    @Benchmark
    public byte[] http_webClientOnly() {
        return rawClient
                .get()
                .uri("/orders/{id}", "order-1")
                .retrieve()
                .bodyToMono(byte[].class)
                .block();
    }

    // -------------------------------------------------------------------------

    private static ServiceEndpointContext context(String name, FileDescriptor file, ManagedChannel channel)
            throws Exception {
        var config = new dev.appiaworks.shiguchi.config.ServiceDefinition();
        config.setName(name);
        var ctx = new ServiceEndpointContext(config, channel);
        ctx.setFileDescriptors(Map.of(file.getName(), file));
        var service = file.findServiceByName("Orders");
        ctx.setServiceByFqn(Map.of(service.getFullName(), service));
        ctx.setMethodByKey(Map.of(service.getFullName() + "/Get", service.findMethodByName("Get")));
        JsonFormat.TypeRegistry.Builder types = JsonFormat.TypeRegistry.newBuilder();
        var factory = new DynamicSerializerFactory();
        for (Descriptor d : file.getMessageTypes()) {
            types.add(d);
            ctx.putSerializer(d, factory.compileDescriptor(d));
        }
        ctx.setTypeRegistry(types.build());
        return ctx;
    }

    private static OrderResponse order() {
        OrderResponse.Builder b = OrderResponse.newBuilder()
                .setId(42_000_000_123L)
                .setName("benchmark-order-name")
                .setActive(true)
                .setScore(12.75d)
                .setCount(314)
                .setNote("a moderately sized note field for realism")
                .setCreatedAt(Timestamp.newBuilder().setSeconds(1_700_000_000L).setNanos(123_456_789));
        for (int i = 0; i < 3; i++) {
            b.addItems(Item.newBuilder()
                    .setSku("sku-" + i)
                    .setQty(i + 1)
                    .setPrice(9.99d * (i + 1))
                    .setNote("item note " + i)
                    .setFlag(i % 2 == 0));
            b.putLabels("k" + i, "v" + i);
        }
        return b.build();
    }
}
