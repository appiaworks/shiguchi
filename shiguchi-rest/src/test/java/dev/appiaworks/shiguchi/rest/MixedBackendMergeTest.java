package dev.appiaworks.shiguchi.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import dev.appiaworks.protobuf.extras.serde.WireJsonTranscoder;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.grpc.GrpcDescriptorCache;
import dev.appiaworks.shiguchi.merge.MergeSource;
import dev.appiaworks.shiguchi.merge.MergeSpec;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * A merge route fed by one gRPC backend and one {@code REST_DOWNSTREAM} backend, through the real
 * {@link MergeDispatcher}, {@link ReactiveGrpcDispatcher} and {@link HttpUpstreamDispatcher}. The
 * gRPC side is an in-process server streaming pre-sorted rows; the HTTP side is a reactor-netty
 * stub answering a JSON array. Each serves its own descriptor instance — the row types are
 * different {@code Order} messages that happen to share field names, which is exactly the shape a
 * mixed deployment has.
 *
 * <p>Proves that rows from an HTTP source merge, sort and page together with gRPC rows; that an
 * HTTP source is read whole whatever the spec says about ordering; that the row type comes from
 * the HTTP list's element type when the HTTP source is first; that paging by cursor across the
 * mix neither repeats nor drops rows; and that the failure policy applies to an HTTP source.
 */
@DisplayName("Merge across a gRPC source and an HTTP source")
class MixedBackendMergeTest {

    private static final int PAGE = 4;

    /** rank, id per row; both backends hold ranks that interleave and tie across them. */
    private static final long[][] GRPC_ROWS = {{1, 100}, {3, 101}, {5, 102}, {7, 103}, {9, 104}};

    private static final long[][] HTTP_ROWS = {{8, 200}, {2, 201}, {6, 202}, {3, 203}, {4, 204}};

    private static Server grpcServer;
    private static ManagedChannel grpcChannel;
    private static DisposableServer httpStub;
    private static final AtomicInteger HTTP_STATUS = new AtomicInteger(200);
    private static final List<String> HTTP_CALLS = new CopyOnWriteArrayList<>();

    private GrpcRouteRegistry registry;
    private MergeDispatcher merge;
    private RouteDefinition grpcRoute;
    private RouteDefinition httpRoute;

    @BeforeAll
    static void startBackends() throws Exception {
        FileDescriptor grpcFile = orderFile("test.merge.grpcside", true);
        Descriptor order = grpcFile.findMessageTypeByName("Order");
        Descriptor request = grpcFile.findMessageTypeByName("ListRequest");
        List<DynamicMessage> rows = new ArrayList<>();
        for (long[] r : GRPC_ROWS) rows.add(row(order, r[0], r[1], "grpc-" + r[1]));

        var method = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.SERVER_STREAMING)
                .setFullMethodName("test.merge.grpcside.OrderService/list")
                .setRequestMarshaller(
                        ProtoUtils.marshaller(DynamicMessage.newBuilder(request).build()))
                .setResponseMarshaller(
                        ProtoUtils.marshaller(DynamicMessage.newBuilder(order).build()))
                .build();
        var service = ServerServiceDefinition.builder("test.merge.grpcside.OrderService")
                .addMethod(method, ServerCalls.asyncServerStreamingCall((req, obs) -> {
                    rows.forEach(obs::onNext);
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

        httpStub = HttpServer.create()
                .port(0)
                .route(routes -> routes.get("/orders", (req, res) -> {
                    HTTP_CALLS.add(req.uri());
                    int status = HTTP_STATUS.get();
                    if (status != 200) return res.status(status).sendString(Mono.just("boom"));
                    StringBuilder json = new StringBuilder("[");
                    for (int i = 0; i < HTTP_ROWS.length; i++) {
                        if (i > 0) json.append(',');
                        // placed_at: the backend's own snake_case property, which the synthetic
                        // message names the same way; it must survive the binding as placedAt.
                        json.append("{\"id\":")
                                .append(HTTP_ROWS[i][1])
                                .append(",\"rank\":")
                                .append(HTTP_ROWS[i][0])
                                .append(",\"placed_at\":")
                                .append(1_700_000_000L + HTTP_ROWS[i][1])
                                .append(",\"name\":\"http-")
                                .append(HTTP_ROWS[i][1])
                                .append("\"}");
                    }
                    json.append(']');
                    return res.header("Content-Type", "application/json").sendString(Mono.just(json.toString()));
                }))
                .bindNow();
    }

    @AfterAll
    static void stopBackends() {
        grpcChannel.shutdownNow();
        grpcServer.shutdownNow();
        httpStub.disposeNow();
    }

    @BeforeEach
    void wireGateway() throws Exception {
        HTTP_STATUS.set(200);
        HTTP_CALLS.clear();
        registry = new GrpcRouteRegistry();
        var serde = new SerdeSelector(new StaticSerdeDetector(), new DynamicProtoSerde(new GrpcDescriptorCache()));
        var props = new GatewayProperties();
        props.getClient().setDefaultDeadline(Duration.ofSeconds(10));
        var grpcDispatcher = new ReactiveGrpcDispatcher(
                new GrpcRequestBuilder(), serde, new GrpcDescriptorCache(), new WireJsonTranscoder(), props);
        var httpDispatcher = new HttpUpstreamDispatcher(serde, props, Optional.empty(), List.of());
        merge = new MergeDispatcher(registry, grpcDispatcher, httpDispatcher, serde);

        // gRPC side, as DynamicRouteLoader would register it.
        FileDescriptor grpcFile = orderFile("test.merge.grpcside", true);
        ServiceEndpointContext grpcCtx = context("orders-grpc", grpcFile, grpcChannel);
        grpcRoute = RouteDefinition.builder(
                        "test.merge.grpcside.OrderService", "list", MethodType.SERVER_STREAMING, "orders-grpc")
                .httpBinding("GET", "/grpc/v1/orders")
                .build();
        grpcCtx.addRoute(grpcRoute);
        registry.registerContext(grpcCtx);

        // HTTP side, as RestDownstreamRouteLoader would register it: a passthrough route with a
        // synthetic unary method whose response wraps the array in `items`.
        String baseUrl = "http://localhost:" + httpStub.port();
        FileDescriptor httpFile = orderFile("test.merge.httpside", false);
        ServiceEndpointContext httpCtx = context("orders-http", httpFile, null);
        httpCtx.putHttpBinding(
                "list",
                new HttpOperationBinding(
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
        httpRoute = RouteDefinition.builder("test.merge.httpside.OrderService", "list", MethodType.UNARY, baseUrl)
                .httpUpstream(baseUrl)
                .httpBinding("GET", "/http/v1/orders")
                .build();
        httpCtx.addRoute(httpRoute);
        registry.registerContext(httpCtx);
    }

    @Test
    @DisplayName("GIVEN a gRPC source and an HTTP source WHEN merging a page SHOULD interleave rows from both by rank")
    void given_mixedSources_when_merging_should_interleaveByRank() {
        RouteDefinition route =
                mergeRoute(List.of(grpcSource(true), httpSource(false)), MergeSpec.FailurePolicy.FAIL_FAST);

        MergeDispatcher.MergePage page =
                merge.dispatch(route, args(PAGE, null), null).block();

        assertThat(page.records()).hasSize(PAGE);
        assertThat(ranks(page.records())).containsExactly(1L, 2L, 3L, 3L);
        assertThat(names(page.records())).containsExactly("grpc-100", "http-201", "grpc-101", "http-203");
        assertThat(page.nextPageToken()).as("more rows remain").isNotNull();
        Message httpRow = page.records().get(1);
        assertThat(httpRow.getField(httpRow.getDescriptorForType().findFieldByName("placed_at")))
                .as("a snake_case backend property binds to the synthetic field, not its default")
                .isEqualTo(1_700_000_201L);
    }

    @Test
    @DisplayName("GIVEN the HTTP source declared presorted WHEN merging SHOULD still read it whole and order it")
    void given_httpSourcePresorted_when_merging_should_readWholeAndSort() {
        // The stub answers its rows unsorted; if the flag were honoured the merge would trust that
        // order and interleave wrongly (or take only a window of it).
        RouteDefinition route =
                mergeRoute(List.of(grpcSource(true), httpSource(true)), MergeSpec.FailurePolicy.FAIL_FAST);

        MergeDispatcher.MergePage page =
                merge.dispatch(route, args(PAGE, null), null).block();

        assertThat(ranks(page.records())).containsExactly(1L, 2L, 3L, 3L);
        assertThat(HTTP_CALLS).hasSize(1);
        assertThat(HTTP_CALLS.get(0)).as("no paging pushed to the HTTP backend").isEqualTo("/orders");
    }

    @Test
    @DisplayName("GIVEN the HTTP source first in the spec WHEN merging SHOULD sort by the HTTP row type's fields")
    void given_httpSourceFirst_when_merging_should_useItsRowType() {
        RouteDefinition route =
                mergeRoute(List.of(httpSource(false), grpcSource(true)), MergeSpec.FailurePolicy.FAIL_FAST);

        MergeDispatcher.MergePage page =
                merge.dispatch(route, args(PAGE, null), null).block();

        // Row type is the `items` element (Order), not the ListResponse wrapper, or `rank` would not
        // resolve and the comparator would fail to build.
        assertThat(ranks(page.records())).containsExactly(1L, 2L, 3L, 3L);
        assertThat(page.records().get(0).getDescriptorForType().getName()).isEqualTo("Order");
    }

    @Test
    @DisplayName("GIVEN a cursor from the first page WHEN fetching the rest SHOULD neither repeat nor drop any row")
    void given_cursor_when_pagingThrough_should_coverEveryRowOnce() {
        RouteDefinition route =
                mergeRoute(List.of(grpcSource(true), httpSource(false)), MergeSpec.FailurePolicy.FAIL_FAST);
        List<Long> seen = new ArrayList<>();

        String token = null;
        do {
            MergeDispatcher.MergePage page =
                    merge.dispatch(route, args(PAGE, token), null).block();
            seen.addAll(ids(page.records()));
            token = page.nextPageToken();
        } while (token != null);

        assertThat(seen).hasSize(GRPC_ROWS.length + HTTP_ROWS.length).doesNotHaveDuplicates();
        assertThat(seen).containsExactly(100L, 201L, 101L, 203L, 204L, 102L, 202L, 103L, 200L, 104L);
    }

    @Test
    @DisplayName("GIVEN the HTTP backend answers 500 WHEN the policy is FAIL_FAST SHOULD fail the whole page")
    void given_httpFailure_when_failPolicy_should_failPage() {
        HTTP_STATUS.set(500);
        RouteDefinition route =
                mergeRoute(List.of(grpcSource(true), httpSource(false)), MergeSpec.FailurePolicy.FAIL_FAST);

        assertThatThrownBy(() -> merge.dispatch(route, args(PAGE, null), null).block())
                .hasMessageContaining("merge source failed")
                .hasMessageContaining("orders-http");
    }

    @Test
    @DisplayName("GIVEN the HTTP backend answers 500 WHEN the policy is BEST_EFFORT SHOULD serve the gRPC rows alone")
    void given_httpFailure_when_bestEffort_should_serveRemainingSource() {
        HTTP_STATUS.set(500);
        RouteDefinition route =
                mergeRoute(List.of(grpcSource(true), httpSource(false)), MergeSpec.FailurePolicy.BEST_EFFORT);

        MergeDispatcher.MergePage page =
                merge.dispatch(route, args(PAGE, null), null).block();

        assertThat(names(page.records())).allMatch(n -> n.startsWith("grpc-"));
        assertThat(page.records()).hasSize(PAGE);
    }

    @Test
    @DisplayName(
            "GIVEN a merge wired without an HTTP dispatcher WHEN a source is HTTP SHOULD fail with a clear message")
    void given_noHttpDispatcher_when_httpSource_should_failClearly() throws Exception {
        var serde = new SerdeSelector(new StaticSerdeDetector(), new DynamicProtoSerde(new GrpcDescriptorCache()));
        var grpcOnly = new MergeDispatcher(
                registry,
                new ReactiveGrpcDispatcher(
                        new GrpcRequestBuilder(),
                        serde,
                        new GrpcDescriptorCache(),
                        new WireJsonTranscoder(),
                        new GatewayProperties()),
                serde);
        RouteDefinition route =
                mergeRoute(List.of(grpcSource(true), httpSource(false)), MergeSpec.FailurePolicy.FAIL_FAST);

        assertThatThrownBy(
                        () -> grpcOnly.dispatch(route, args(PAGE, null), null).block())
                .hasMessageContaining("merge source failed")
                .rootCause()
                .hasMessageContaining("no HttpUpstreamDispatcher is wired");
    }

    // -------------------------------------------------------------------------

    private RouteDefinition mergeRoute(List<MergeSource> sources, MergeSpec.FailurePolicy policy) {
        return RouteDefinition.builder(
                        grpcRoute.getFullServiceName(), "list", MethodType.SERVER_STREAMING, "orders-grpc")
                .httpBinding("GET", "/v1/orders")
                .mergeSpec(new MergeSpec(sources, "id", null, policy, 0))
                .build();
    }

    private static MergeSource grpcSource(boolean presorted) {
        return new MergeSource("orders-grpc", "test.merge.grpcside.OrderService", "list", presorted);
    }

    private static MergeSource httpSource(boolean presorted) {
        return new MergeSource("orders-http", "test.merge.httpside.OrderService", "list", presorted);
    }

    private static Map<String, Object> args(int pageSize, String token) {
        Map<String, Object> pageRequest =
                token == null ? Map.of("pageSize", pageSize) : Map.of("pageSize", pageSize, "pageToken", token);
        return Map.of(
                "sortFields",
                List.of(Map.of("field", "rank", "sortOrder", "SORT_ORDER_ASC")),
                "pageRequest",
                pageRequest);
    }

    private static List<Long> ranks(List<Message> records) {
        return records.stream()
                .map(m -> (Long) m.getField(m.getDescriptorForType().findFieldByName("rank")))
                .toList();
    }

    private static List<Long> ids(List<Message> records) {
        return records.stream()
                .map(m -> (Long) m.getField(m.getDescriptorForType().findFieldByName("id")))
                .toList();
    }

    private static List<String> names(List<Message> records) {
        return records.stream()
                .map(m -> (String) m.getField(m.getDescriptorForType().findFieldByName("name")))
                .toList();
    }

    private static DynamicMessage row(Descriptor order, long rank, long id, String name) {
        return DynamicMessage.newBuilder(order)
                .setField(order.findFieldByName("rank"), rank)
                .setField(order.findFieldByName("id"), id)
                .setField(order.findFieldByName("name"), name)
                .build();
    }

    private static ServiceEndpointContext context(String name, FileDescriptor file, ManagedChannel channel)
            throws Exception {
        var config = new ServiceDefinition();
        config.setName(name);
        var ctx = new ServiceEndpointContext(config, channel);
        ctx.setFileDescriptors(Map.of(file.getName(), file));
        var service = file.findServiceByName("OrderService");
        ctx.setServiceByFqn(Map.of(service.getFullName(), service));
        ctx.setMethodByKey(Map.of(service.getFullName() + "/list", service.findMethodByName("list")));
        JsonFormat.TypeRegistry.Builder types = JsonFormat.TypeRegistry.newBuilder();
        var factory = new DynamicSerializerFactory();
        for (Descriptor d : file.getMessageTypes()) {
            types.add(d);
            ctx.putSerializer(d, factory.compileDescriptor(d));
        }
        ctx.setTypeRegistry(types.build());
        return ctx;
    }

    /**
     * {@code Order{id,rank,name}} plus a list method: server-streaming {@code Order} for the gRPC
     * side, unary {@code ListResponse{repeated Order items}} for the HTTP side (what {@code
     * OpenApiToDescriptor} builds for an array response).
     */
    private static FileDescriptor orderFile(String pkg, boolean streaming) throws Exception {
        DescriptorProto order = DescriptorProto.newBuilder()
                .setName("Order")
                .addField(scalar("id", 1, FieldDescriptorProto.Type.TYPE_INT64))
                .addField(scalar("rank", 2, FieldDescriptorProto.Type.TYPE_INT64))
                .addField(scalar("name", 3, FieldDescriptorProto.Type.TYPE_STRING))
                .addField(scalar("placed_at", 4, FieldDescriptorProto.Type.TYPE_INT64))
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
        DescriptorProto listResponse = DescriptorProto.newBuilder()
                .setName("ListResponse")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName(HttpOperationBinding.ARRAY_FIELD)
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
                        .setOutputType(streaming ? "." + pkg + ".Order" : "." + pkg + ".ListResponse")
                        .setServerStreaming(streaming))
                .build();
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName(pkg.replace('.', '_') + ".proto")
                .setPackage(pkg)
                .setSyntax("proto3")
                .addMessageType(order)
                .addMessageType(pageRequest)
                .addMessageType(sortField)
                .addMessageType(listRequest)
                .addMessageType(listResponse)
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
