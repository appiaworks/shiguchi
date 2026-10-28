package dev.appiaworks.shiguchi.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.util.JsonFormat;
import dev.appiaworks.protobuf.extras.serde.WireJsonTranscoder;
import dev.appiaworks.shiguchi.bench.proto.BatchResponse;
import dev.appiaworks.shiguchi.bench.proto.GetBatchRequest;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.graphql.GrpcBatchLoader;
import dev.appiaworks.shiguchi.graphql.OpenApiBatchLoader;
import dev.appiaworks.shiguchi.grpc.GrpcDescriptorCache;
import dev.appiaworks.shiguchi.rest.GrpcRequestBuilder;
import dev.appiaworks.shiguchi.rest.ReactiveGrpcDispatcher;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import dev.appiaworks.shiguchi.serde.DynamicProtoSerde;
import dev.appiaworks.shiguchi.serde.SerdeSelector;
import dev.appiaworks.shiguchi.serde.StaticSerdeDetector;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ServerCalls;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * The federation batch loaders — what a GraphQL {@code _entities} request or a DataLoader-batched
 * relationship costs per batch, keyed by entity id:
 *
 * <ul>
 *   <li>{@code grpcBatch} — {@link GrpcBatchLoader} over a unary {@code GetBatch(ids) →
 *       {items}} on an in-process channel, through {@code ReactiveGrpcDispatcher.dispatchToMap}.
 *   <li>{@code restBatch} — {@link OpenApiBatchLoader} in batch mode, one {@code GET
 *       /orders?ids=a,b,c} against a loopback reactor-netty stub answering a JSON array.
 * </ul>
 *
 * <p>{@code keys} is the batch size; the backend returns one fixture order per key, so the
 * numbers scale with the response as well as with the key list. As in {@code
 * HttpDispatchBenchmark}, the REST side pays a real socket where the gRPC side pays none — read
 * the gap as an upper bound on the transport difference, not as the gateway's own cost.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class FederationBatchLoaderBenchmark {

    @Param({"1", "10", "100"})
    public int keys;

    private Server server;
    private ManagedChannel channel;
    private DisposableServer httpStub;
    private GrpcBatchLoader grpcLoader;
    private OpenApiBatchLoader restLoader;
    private List<String> ids;
    private final Metadata noHeaders = new Metadata();

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        ids = new ArrayList<>(keys);
        for (int i = 0; i < keys; i++) ids.add(String.valueOf(1000 + i));

        // gRPC backend: one order per requested id, in a single response.
        var method = io.grpc.MethodDescriptor.<GetBatchRequest, BatchResponse>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName(BenchOrders.SERVICE + "/GetBatch")
                .setRequestMarshaller(ProtoUtils.marshaller(GetBatchRequest.getDefaultInstance()))
                .setResponseMarshaller(ProtoUtils.marshaller(BatchResponse.getDefaultInstance()))
                .build();
        var service = ServerServiceDefinition.builder(BenchOrders.SERVICE)
                .addMethod(method, ServerCalls.asyncUnaryCall((req, obs) -> {
                    BatchResponse.Builder b = BatchResponse.newBuilder();
                    for (String id : req.getIdsList()) b.addItems(BenchOrders.order(Long.parseLong(id), 3));
                    obs.onNext(b.build());
                    obs.onCompleted();
                }))
                .build();
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(service)
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();

        var props = new GatewayProperties();
        props.getClient().setDefaultDeadline(java.time.Duration.ZERO);
        var serde = new SerdeSelector(new StaticSerdeDetector(), new DynamicProtoSerde(new GrpcDescriptorCache()));
        var dispatcher = new ReactiveGrpcDispatcher(
                new GrpcRequestBuilder(), serde, new GrpcDescriptorCache(), new WireJsonTranscoder(), props);
        FileDescriptor file = BenchOrders.dynamicFile();
        ServiceEndpointContext ctx = BenchOrders.context("orders", file, channel);
        RouteDefinition route = RouteDefinition.builder(BenchOrders.SERVICE, "GetBatch", MethodType.UNARY, "orders")
                .graphQl("ordersBatch", RouteDefinition.GraphQlOperationType.QUERY)
                .build();
        ctx.addRoute(route);
        grpcLoader = new GrpcBatchLoader(route, ctx, dispatcher, "ids", "items", "id");

        // REST backend: the same orders as a JSON array for GET /orders?ids=...
        JsonFormat.Printer printer = JsonFormat.printer().omittingInsignificantWhitespace();
        httpStub = HttpServer.create()
                .port(0)
                .route(r -> r.get("/orders", (req, res) -> {
                    String[] wanted =
                            req.uri().substring(req.uri().indexOf("ids=") + 4).split(",");
                    StringBuilder json = new StringBuilder("[");
                    try {
                        for (int i = 0; i < wanted.length; i++) {
                            if (i > 0) json.append(',');
                            json.append(printer.print(BenchOrders.order(Long.parseLong(wanted[i]), 3)));
                        }
                    } catch (Exception e) {
                        return res.status(500).sendString(Mono.just(e.toString()));
                    }
                    return res.header("Content-Type", "application/json")
                            .sendString(Mono.just(json.append(']').toString()));
                }))
                .bindNow();
        var config = new dev.appiaworks.shiguchi.config.RestBatchLoaderConfig();
        config.setType("Order");
        config.setEndpoint("/orders");
        config.setIdsParam("ids");
        config.setKeyField("id");
        config.setBatchEndpoint(true);
        restLoader = new OpenApiBatchLoader(
                WebClient.builder()
                        .baseUrl("http://localhost:" + httpStub.port())
                        .build(),
                config,
                new ObjectMapper());

        // Both loaders must find every key before anything is measured.
        Map<String, Map<String, Object>> viaGrpc = grpcBatch();
        Map<String, Map<String, Object>> viaRest = restBatch();
        if (viaGrpc.size() != keys || viaRest.size() != keys) {
            throw new IllegalStateException(
                    "loaders returned " + viaGrpc.size() + " / " + viaRest.size() + " of " + keys);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
        httpStub.disposeNow();
    }

    @Benchmark
    public Map<String, Map<String, Object>> grpcBatch() {
        return grpcLoader.loadMapped(ids, noHeaders).block();
    }

    @Benchmark
    public Map<String, Map<String, Object>> restBatch() {
        return restLoader.loadMapped(ids, noHeaders).block();
    }
}
