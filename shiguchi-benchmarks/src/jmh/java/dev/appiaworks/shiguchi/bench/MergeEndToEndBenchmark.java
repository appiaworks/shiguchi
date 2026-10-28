package dev.appiaworks.shiguchi.bench;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.util.JsonFormat;
import dev.appiaworks.protobuf.extras.serde.WireJsonTranscoder;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.grpc.GrpcDescriptorCache;
import dev.appiaworks.shiguchi.merge.MergeSource;
import dev.appiaworks.shiguchi.merge.MergeSpec;
import dev.appiaworks.shiguchi.rest.GrpcRequestBuilder;
import dev.appiaworks.shiguchi.rest.MergeDispatcher;
import dev.appiaworks.shiguchi.rest.ReactiveGrpcDispatcher;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
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

/**
 * A merge route end to end: real gRPC backends, real fan-out, real page assembly.
 *
 * <p>{@code MergeBenchmark} measures the merge algorithm over in-memory lists. This measures what a
 * caller actually pays — N concurrent gRPC calls over in-process channels, responses decoded to
 * messages, merged, sorted, and cut into a page by {@link MergeDispatcher}. The gap between the two
 * is the transport and decode cost the algorithm benchmark cannot see.
 *
 * <p>Worth having because the merge feature's two worst bugs — a route-id collision that made a
 * merged path 404, and a comparator that threw across backends — were both invisible to unit tests
 * and only appeared when something drove the whole path. A benchmark that exercises it end to end
 * is a standing check that the path still works, not only that it is fast.
 *
 * <p>Each backend runs on its own in-process server so the fan-out is genuinely N calls rather than
 * one server answering N times, and each serves its own descriptor instance — the cross-descriptor
 * case that a single-backend benchmark would never produce.
 *
 * <ul>
 *   <li>{@code mergePage} — the whole path: fan-out, decode, merge, page.
 *   <li>{@code twoSourcePage} — always the same two backends, whatever the source parameter says,
 *       so its flatness is the harness checking itself: a moving number there would mean the
 *       benchmark is drifting rather than the fan-out widening.
 *   <li>{@code fanOutOnly} — the N calls with no merge, isolating transport from assembly.
 *   <li>{@code mixedPage} — {@code sources - 1} gRPC backends plus one {@code REST_DOWNSTREAM}
 *       backend: a reactor-netty stub answering a JSON array, called over {@code WebClient},
 *       split into rows and merged with the gRPC records. The gap to {@code mergePage} is what
 *       an HTTP source costs: a real socket, JSON parsing, and a source that is always read
 *       whole because it cannot be handed the merge's sort or window.
 * </ul>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class MergeEndToEndBenchmark {

    /** Rows each backend holds. The page is far smaller; a merge only reads what it needs. */
    private static final int ROWS_PER_SOURCE = 100;

    private static final int PAGE_SIZE = 25;

    @Param({"2", "4", "8"})
    public int sources;

    private final List<Server> servers = new ArrayList<>();
    private final List<ManagedChannel> channels = new ArrayList<>();

    private GrpcRouteRegistry registry;
    private MergeDispatcher mergeDispatcher;
    private ReactiveGrpcDispatcher reactiveDispatcher;
    private RouteDefinition mergeRoute;
    private RouteDefinition singleRoute;
    private RouteDefinition mixedRoute;
    private reactor.netty.DisposableServer httpStub;
    private List<RouteDefinition> sourceRoutes;
    private List<ServiceEndpointContext> contexts;
    private Map<String, Object> requestArgs;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        registry = new GrpcRouteRegistry();
        var serde = new SerdeSelector(new StaticSerdeDetector(), new DynamicProtoSerde(new GrpcDescriptorCache()));
        reactiveDispatcher = new ReactiveGrpcDispatcher(
                new GrpcRequestBuilder(), serde, new GrpcDescriptorCache(), new WireJsonTranscoder(), properties());
        var httpDispatcher = new dev.appiaworks.shiguchi.rest.HttpUpstreamDispatcher(
                serde, properties(), java.util.Optional.empty(), List.of());
        mergeDispatcher = new MergeDispatcher(registry, reactiveDispatcher, httpDispatcher, serde);

        sourceRoutes = new ArrayList<>(sources);
        contexts = new ArrayList<>(sources);
        Random random = new Random(20260908L);

        for (int s = 0; s < sources; s++) {
            String endpoint = "orders-" + s;
            // Its own descriptor instance, as a separately deployed backend would have.
            FileDescriptor file = buildFile("bench.merge.e2e" + s);
            Descriptor order = file.findMessageTypeByName("Order");
            Descriptor request = file.findMessageTypeByName("ListRequest");

            ManagedChannel channel = startBackend(file, order, request, rowsFor(order, random));
            ServiceEndpointContext ctx = contextFor(endpoint, file, order, request, channel);
            contexts.add(ctx);

            RouteDefinition route = RouteDefinition.builder(
                            file.getPackage() + ".OrderService", "list", MethodType.SERVER_STREAMING, endpoint)
                    .httpBinding("GET", "/" + endpoint + "/v1/orders")
                    .build();
            ctx.addRoute(route);
            sourceRoutes.add(route);
            registry.registerContext(ctx);
        }

        List<MergeSource> specSources = new ArrayList<>(sources);
        for (int s = 0; s < sources; s++) {
            // Pre-sorted: the backends below return rows already ordered, so this is the shape a
            // well-behaved deployment has.
            specSources.add(new MergeSource("orders-" + s, sourceRoutes.get(s).getFullServiceName(), "list", true));
        }

        mergeRoute = RouteDefinition.builder(
                        sourceRoutes.get(0).getFullServiceName(), "list", MethodType.SERVER_STREAMING, "orders-0")
                .httpBinding("GET", "/v1/orders")
                .mergeSpec(new MergeSpec(specSources, "id", null, null, 0))
                .build();

        singleRoute = RouteDefinition.builder(
                        sourceRoutes.get(0).getFullServiceName(), "list", MethodType.SERVER_STREAMING, "orders-0")
                .httpBinding("GET", "/v1/orders-single")
                // Always sources 0 and 1: the narrowest merge, as a floor for what wider fan-out
                // adds. A spec needs two distinct sources, so this cannot just reuse source 0.
                .mergeSpec(new MergeSpec(List.of(specSources.get(0), specSources.get(1)), "id", null, null, 0))
                .build();

        // One HTTP source in place of the last gRPC one: the same rows, served as a JSON array by
        // a reactor-netty stub and registered the way RestDownstreamRouteLoader would.
        FileDescriptor httpFile = buildHttpFile("bench.merge.http");
        String httpJson = jsonRows(rowsFor(httpFile.findMessageTypeByName("Order"), new Random(20260908L)));
        httpStub = reactor.netty.http.server.HttpServer.create()
                .port(0)
                .route(r -> r.get(
                        "/orders",
                        (req, res) -> res.header("Content-Type", "application/json")
                                .sendString(reactor.core.publisher.Mono.just(httpJson))))
                .bindNow();
        String baseUrl = "http://localhost:" + httpStub.port();
        ServiceEndpointContext httpCtx = contextFor(
                "orders-http",
                httpFile,
                httpFile.findMessageTypeByName("Order"),
                httpFile.findMessageTypeByName("ListRequest"),
                null);
        httpCtx.putHttpBinding(
                "list",
                new dev.appiaworks.shiguchi.routing.HttpOperationBinding(
                        "GET",
                        "/orders",
                        List.of(),
                        List.of(),
                        false,
                        true,
                        false,
                        List.of(),
                        List.of("application/json"),
                        null,
                        "Order"));
        httpCtx.addRoute(
                RouteDefinition.builder(httpFile.getPackage() + ".OrderService", "list", MethodType.UNARY, baseUrl)
                        .httpUpstream(baseUrl)
                        .httpBinding("GET", "/orders-http/v1/orders")
                        .build());
        registry.registerContext(httpCtx);

        List<MergeSource> mixedSources = new ArrayList<>(specSources.subList(0, sources - 1));
        mixedSources.add(new MergeSource("orders-http", httpFile.getPackage() + ".OrderService", "list", false));
        mixedRoute = RouteDefinition.builder(
                        sourceRoutes.get(0).getFullServiceName(), "list", MethodType.SERVER_STREAMING, "orders-0")
                .httpBinding("GET", "/v1/orders-mixed")
                .mergeSpec(new MergeSpec(mixedSources, "id", null, null, 0))
                .build();

        requestArgs = Map.of(
                "sortFields", List.of(Map.of("field", "rank", "sortOrder", "SORT_ORDER_ASC")),
                "pageRequest", Map.of("pageSize", PAGE_SIZE));
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        channels.forEach(ManagedChannel::shutdownNow);
        servers.forEach(Server::shutdownNow);
        if (httpStub != null) httpStub.disposeNow();
    }

    /** The whole merge route: fan-out, decode, merge, page. */
    @Benchmark
    public int mergePage() {
        return mergeDispatcher
                .dispatch(mergeRoute, requestArgs, null)
                .block()
                .records()
                .size();
    }

    /** Two sources through the same dispatcher, as a floor for what wider fan-out adds. */
    @Benchmark
    public int twoSourcePage() {
        return mergeDispatcher
                .dispatch(singleRoute, requestArgs, null)
                .block()
                .records()
                .size();
    }

    /** {@code sources - 1} gRPC backends and one HTTP backend through the same merge. */
    @Benchmark
    public int mixedPage() {
        return mergeDispatcher
                .dispatch(mixedRoute, requestArgs, null)
                .block()
                .records()
                .size();
    }

    /** The N backend calls with no merging, isolating transport and decode from assembly. */
    @Benchmark
    public int fanOutOnly() {
        int total = 0;
        List<reactor.core.publisher.Mono<List<com.google.protobuf.Message>>> calls = new ArrayList<>(sources);
        for (int s = 0; s < sources; s++) {
            calls.add(reactiveDispatcher
                    .dispatchStreamingMessages(sourceRoutes.get(s), contexts.get(s), Map.of(), null)
                    .collectList());
        }
        for (var records : reactor.core.publisher.Mono.zip(calls, r -> r).block()) {
            total += ((List<?>) records).size();
        }
        return total;
    }

    // -------------------------------------------------------------------------

    private static GatewayProperties properties() {
        var props = new GatewayProperties();
        // No deadline: a benchmark should measure the path, not race a timer.
        props.getClient().setDefaultDeadline(java.time.Duration.ZERO);
        return props;
    }

    private ManagedChannel startBackend(
            FileDescriptor file, Descriptor order, Descriptor request, List<DynamicMessage> rows) throws Exception {

        var method = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.SERVER_STREAMING)
                .setFullMethodName(file.getPackage() + ".OrderService/list")
                .setRequestMarshaller(
                        ProtoUtils.marshaller(DynamicMessage.newBuilder(request).build()))
                .setResponseMarshaller(
                        ProtoUtils.marshaller(DynamicMessage.newBuilder(order).build()))
                .build();

        var service = ServerServiceDefinition.builder(file.getPackage() + ".OrderService")
                .addMethod(method, ServerCalls.asyncServerStreamingCall((req, obs) -> {
                    // Rows are pre-sorted at setup, so this is a backend that honours the sort.
                    for (DynamicMessage row : rows) {
                        obs.onNext(row);
                    }
                    obs.onCompleted();
                }))
                .build();

        String name = InProcessServerBuilder.generateName();
        servers.add(InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(service)
                .build()
                .start());
        ManagedChannel channel =
                InProcessChannelBuilder.forName(name).directExecutor().build();
        channels.add(channel);
        return channel;
    }

    private ServiceEndpointContext contextFor(
            String name, FileDescriptor file, Descriptor order, Descriptor request, ManagedChannel channel)
            throws Exception {

        var config = new dev.appiaworks.shiguchi.config.ServiceDefinition();
        config.setName(name);
        var ctx = new ServiceEndpointContext(config, channel);
        ctx.setFileDescriptors(Map.of(file.getName(), file));
        ctx.setServiceByFqn(Map.of(file.getPackage() + ".OrderService", file.findServiceByName("OrderService")));
        ctx.setMethodByKey(Map.of(
                file.getPackage() + ".OrderService/list",
                file.findServiceByName("OrderService").findMethodByName("list")));
        JsonFormat.TypeRegistry.Builder types = JsonFormat.TypeRegistry.newBuilder();
        var factory = new DynamicSerializerFactory();
        for (Descriptor d : file.getMessageTypes()) {
            types.add(d);
            ctx.putSerializer(d, factory.compileDescriptor(d));
        }
        ctx.setTypeRegistry(types.build());
        return ctx;
    }

    /** Rows ordered by the merge key, since these backends claim to be pre-sorted. */
    private static List<DynamicMessage> rowsFor(Descriptor order, Random random) {
        var rankFd = order.findFieldByName("rank");
        var idFd = order.findFieldByName("id");
        var nameFd = order.findFieldByName("name");

        List<DynamicMessage> rows = new ArrayList<>(ROWS_PER_SOURCE);
        for (int i = 0; i < ROWS_PER_SOURCE; i++) {
            rows.add(DynamicMessage.newBuilder(order)
                    .setField(rankFd, (long) random.nextInt(ROWS_PER_SOURCE / 4 + 1)) // ties are common
                    .setField(idFd, (long) random.nextInt(1_000_000))
                    .setField(nameFd, "order-" + i)
                    .build());
        }
        Comparator<DynamicMessage> byRank = Comparator.comparingLong(m -> (Long) m.getField(rankFd));
        rows.sort(byRank.thenComparingLong(m -> (Long) m.getField(idFd)));
        return rows;
    }

    private static String jsonRows(List<DynamicMessage> rows) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            DynamicMessage m = rows.get(i);
            Descriptor d = m.getDescriptorForType();
            if (i > 0) sb.append(',');
            sb.append("{\"id\":")
                    .append(m.getField(d.findFieldByName("id")))
                    .append(",\"rank\":")
                    .append(m.getField(d.findFieldByName("rank")))
                    .append(",\"name\":\"")
                    .append(m.getField(d.findFieldByName("name")))
                    .append("\"}");
        }
        return sb.append(']').toString();
    }

    /**
     * The HTTP side's descriptor, shaped as {@code OpenApiToDescriptor} shapes an array response:
     * a unary {@code list} returning {@code ListResponse{repeated Order items}}.
     */
    private static FileDescriptor buildHttpFile(String pkg) throws Exception {
        FileDescriptorProto grpcShaped = buildFile(pkg).toProto();
        DescriptorProto listResponse = DescriptorProto.newBuilder()
                .setName("ListResponse")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName(dev.appiaworks.shiguchi.routing.HttpOperationBinding.ARRAY_FIELD)
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName("." + pkg + ".Order")
                        .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED))
                .build();
        ServiceDescriptorProto service = ServiceDescriptorProto.newBuilder()
                .setName("OrderService")
                .addMethod(MethodDescriptorProto.newBuilder()
                        .setName("list")
                        .setInputType("." + pkg + ".ListRequest")
                        .setOutputType("." + pkg + ".ListResponse"))
                .build();
        FileDescriptorProto file = grpcShaped.toBuilder()
                .addMessageType(listResponse)
                .clearService()
                .addService(service)
                .build();
        return FileDescriptor.buildFrom(file, new FileDescriptor[0]);
    }

    private static FileDescriptor buildFile(String pkg) throws Exception {
        DescriptorProto order = DescriptorProto.newBuilder()
                .setName("Order")
                .addField(scalar("id", 1, FieldDescriptorProto.Type.TYPE_INT64))
                .addField(scalar("rank", 2, FieldDescriptorProto.Type.TYPE_INT64))
                .addField(scalar("name", 3, FieldDescriptorProto.Type.TYPE_STRING))
                .build();

        DescriptorProto pageRequest = DescriptorProto.newBuilder()
                .setName("PageRequest")
                .addField(scalar("page_size", 1, FieldDescriptorProto.Type.TYPE_INT32))
                .addField(scalar("page_token", 2, FieldDescriptorProto.Type.TYPE_STRING))
                .build();

        DescriptorProto sortField = DescriptorProto.newBuilder()
                .setName("SortField")
                .addField(scalar("field", 1, FieldDescriptorProto.Type.TYPE_STRING))
                .addField(scalar("sort_order", 2, FieldDescriptorProto.Type.TYPE_STRING))
                .build();

        DescriptorProto listRequest = DescriptorProto.newBuilder()
                .setName("ListRequest")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("page_request")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName("." + pkg + ".PageRequest")
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("sort_fields")
                        .setNumber(2)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName("." + pkg + ".SortField")
                        .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED))
                .build();

        ServiceDescriptorProto service = ServiceDescriptorProto.newBuilder()
                .setName("OrderService")
                .addMethod(MethodDescriptorProto.newBuilder()
                        .setName("list")
                        .setInputType("." + pkg + ".ListRequest")
                        .setOutputType("." + pkg + ".Order")
                        .setServerStreaming(true))
                .build();

        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName(pkg.replace('.', '_') + ".proto")
                .setPackage(pkg)
                .setSyntax("proto3")
                .addMessageType(order)
                .addMessageType(pageRequest)
                .addMessageType(sortField)
                .addMessageType(listRequest)
                .addService(service)
                .build();

        return FileDescriptor.buildFrom(file, new FileDescriptor[0]);
    }

    private static FieldDescriptorProto scalar(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .build();
    }
}
