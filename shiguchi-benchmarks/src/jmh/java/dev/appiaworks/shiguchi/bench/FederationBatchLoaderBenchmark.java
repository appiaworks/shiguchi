package dev.appiaworks.shiguchi.bench;

import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.util.JsonFormat;
import dev.appiaworks.protobuf.extras.serde.WireJsonTranscoder;
import dev.appiaworks.shiguchi.bench.proto.BatchResponse;
import dev.appiaworks.shiguchi.bench.proto.GetBatchRequest;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.graphql.FederationBench;
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
import java.util.concurrent.atomic.AtomicLong;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * The federation batch loaders — what a GraphQL {@code _entities} request or a DataLoader-batched
 * relationship costs per batch, keyed by entity id:
 *
 * <ul>
 *   <li>{@code grpcBatch} — {@link GrpcBatchLoader} over a unary {@code GetBatch(ids) →
 *       {items}} on an in-process channel, through {@code ReactiveGrpcDispatcher.dispatchToMap}.
 *   <li>{@code restBatch} — {@link OpenApiBatchLoader} against a loopback reactor-netty stub:
 *       {@code restStrategy=batch} sends {@code GET /orders?ids=a,b,c} (split by {@code
 *       maxBatchSize}); {@code restStrategy=parallel} sends one {@code GET /orders/{id}} per id,
 *       {@code maxConcurrency} in flight.
 *   <li>{@code fetchEntitiesGrpc} / {@code fetchEntitiesRest} — the same loaders behind {@code
 *       FederationDataFetcher.fetchEntities}, so the representations are grouped, de-duplicated
 *       and chunked by the shared pipeline. {@code dupRatio} is the share of the {@code keys}
 *       representations that repeat an id.
 * </ul>
 *
 * <p>{@code keys} is the batch size (the number of representations on the {@code fetchEntities}
 * legs, the number of distinct ids on the plain loader legs); the backend returns one fixture order
 * per key, so the numbers scale with the response as well as with the key list. As in {@code
 * HttpDispatchBenchmark}, the REST side pays a real socket where the gRPC side pays none — read
 * the gap as an upper bound on the transport difference, not as the gateway's own cost.
 *
 * <p>Both stubs count their calls and {@link #setUp()} asserts the count of every leg: the plain
 * REST batch leg and the {@code fetchEntities} legs send ceil(distinct ids / {@code maxBatchSize})
 * calls ({@code maxBatchSize=0}: one), the parallel strategy one call per distinct id, the plain
 * gRPC loader one call (it neither chunks nor de-duplicates, so it is fed the distinct ids).
 * The defaults reproduce the earlier runs ({@code keys=1,10,100}, batch strategy, no duplicates, no
 * chunking, {@code maxConcurrency=16}); sweep the rest with {@code -p}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class FederationBatchLoaderBenchmark {

    @Param({"1", "10", "100", "1000", "5000"})
    public int keys;

    /** Share of the representations that repeat an id (only the {@code fetchEntities} legs see duplicates). */
    @Param({"0.0"})
    public double dupRatio;

    /** {@code batch}: one GET with the ids; {@code parallel}: one GET per id. */
    @Param({"batch"})
    public String restStrategy;

    /** Most ids per upstream call; {@code 0} = one call for everything. */
    @Param({"0"})
    public int maxBatchSize;

    /** Most REST calls in flight (the loader's own limit) and most chunks in flight (the pipeline's); {@code 0} = unbounded. */
    @Param({"16"})
    public int maxConcurrency;

    private Server server;
    private ManagedChannel channel;
    private DisposableServer httpStub;
    private GrpcBatchLoader grpcLoader;
    private OpenApiBatchLoader restLoader;
    private FederationBench.Fetcher grpcFetcher;
    private FederationBench.Fetcher restFetcher;
    /** The distinct ids, for the plain loader legs. */
    private List<String> ids;
    /** {@code keys} representations, {@code dupRatio} of them repeating an id, for the fetchEntities legs. */
    private List<Map<String, Object>> representations;

    private int distinct;
    private final AtomicLong grpcCalls = new AtomicLong();
    private final AtomicLong restCalls = new AtomicLong();
    private final Metadata noHeaders = new Metadata();

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        boolean parallel = "parallel".equals(restStrategy);
        if (!parallel && !"batch".equals(restStrategy)) {
            throw new IllegalArgumentException("restStrategy must be batch or parallel, not " + restStrategy);
        }
        distinct = Math.max(1, (int) Math.round(keys * (1 - dupRatio)));
        ids = new ArrayList<>(distinct);
        for (int i = 0; i < distinct; i++) ids.add(String.valueOf(1000 + i));
        representations = new ArrayList<>(keys);
        for (int i = 0; i < keys; i++) {
            representations.add(Map.of("__typename", "Order", "id", ids.get(i % distinct)));
        }

        // gRPC backend: one order per requested id, in a single response.
        var method = io.grpc.MethodDescriptor.<GetBatchRequest, BatchResponse>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName(BenchOrders.SERVICE + "/GetBatch")
                .setRequestMarshaller(ProtoUtils.marshaller(GetBatchRequest.getDefaultInstance()))
                .setResponseMarshaller(ProtoUtils.marshaller(BatchResponse.getDefaultInstance()))
                .build();
        var service = ServerServiceDefinition.builder(BenchOrders.SERVICE)
                .addMethod(method, ServerCalls.asyncUnaryCall((req, obs) -> {
                    grpcCalls.incrementAndGet();
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

        // REST backend: the same orders, as a JSON array for GET /orders?ids=... and one object for GET /orders/{id}.
        JsonFormat.Printer printer = JsonFormat.printer().omittingInsignificantWhitespace();
        httpStub = HttpServer.create()
                .port(0)
                .route(r -> r.get("/orders", (req, res) -> {
                            restCalls.incrementAndGet();
                            String[] wanted = req.uri()
                                    .substring(req.uri().indexOf("ids=") + 4)
                                    .split(",");
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
                        })
                        .get("/orders/{id}", (req, res) -> {
                            restCalls.incrementAndGet();
                            try {
                                return res.header("Content-Type", "application/json")
                                        .sendString(Mono.just(
                                                printer.print(BenchOrders.order(Long.parseLong(req.param("id")), 3))));
                            } catch (Exception e) {
                                return res.status(500).sendString(Mono.just(e.toString()));
                            }
                        }))
                .bindNow();
        var config = new dev.appiaworks.shiguchi.config.RestBatchLoaderConfig();
        config.setType("Order");
        config.setEndpoint(parallel ? "/orders/{id}" : "/orders");
        config.setIdsParam("ids");
        config.setKeyField("id");
        config.setBatchEndpoint(!parallel);
        config.setMaxBatchSize(maxBatchSize);
        config.setMaxConcurrency(maxConcurrency);
        restLoader = new OpenApiBatchLoader(
                WebClient.builder()
                        .baseUrl("http://localhost:" + httpStub.port())
                        .build(),
                config,
                JsonMapper.builder().build());

        grpcFetcher = FederationBench.fetcher("Order", "id", grpcLoader, maxBatchSize, maxConcurrency);
        restFetcher = FederationBench.fetcher("Order", "id", restLoader, maxBatchSize, maxConcurrency);

        // Every leg must find every key, with the upstream call count it should have, before anything is measured.
        long chunks = maxBatchSize <= 0 ? 1 : (distinct + maxBatchSize - 1) / maxBatchSize;
        long restCallsExpected = parallel ? distinct : chunks;
        check("grpcBatch", grpcBatch().size(), distinct, grpcCalls, 1);
        check("restBatch", restBatch().size(), distinct, restCalls, restCallsExpected);
        check("fetchEntitiesGrpc", fetchEntitiesGrpc().size(), keys, grpcCalls, chunks);
        check("fetchEntitiesRest", fetchEntitiesRest().size(), keys, restCalls, restCallsExpected);
    }

    /** Fails when {@code leg} resolved the wrong number of entries or sent the wrong number of upstream calls. */
    private void check(String leg, int size, int expectedSize, AtomicLong counter, long expectedCalls) {
        long calls = counter.getAndSet(0);
        if (size != expectedSize || calls != expectedCalls) {
            throw new IllegalStateException(leg + ": " + size + " of " + expectedSize + " entities, " + calls
                    + " upstream calls, expected " + expectedCalls);
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

    /** {@code _entities} over the gRPC loader: grouped, de-duplicated and chunked by the pipeline. */
    @Benchmark
    public List<Object> fetchEntitiesGrpc() {
        return grpcFetcher.fetch(representations);
    }

    /** {@code _entities} over the REST loader (the strategy in {@code restStrategy}). */
    @Benchmark
    public List<Object> fetchEntitiesRest() {
        return restFetcher.fetch(representations);
    }
}
