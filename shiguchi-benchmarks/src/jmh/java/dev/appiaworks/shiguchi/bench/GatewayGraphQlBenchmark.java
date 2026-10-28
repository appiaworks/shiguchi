package dev.appiaworks.shiguchi.bench;

import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import com.google.protobuf.util.JsonFormat;
import dev.appiaworks.shiguchi.bench.proto.Customer;
import dev.appiaworks.shiguchi.bench.proto.Customers;
import dev.appiaworks.shiguchi.bench.proto.GetCustomersRequest;
import dev.appiaworks.shiguchi.bench.proto.GetShopOrderRequest;
import dev.appiaworks.shiguchi.bench.proto.ListShopOrdersRequest;
import dev.appiaworks.shiguchi.bench.proto.ShopItem;
import dev.appiaworks.shiguchi.bench.proto.ShopOrder;
import dev.appiaworks.shiguchi.bench.proto.ShopOrders;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerServiceDefinition;
import io.grpc.protobuf.ProtoFileDescriptorSupplier;
import io.grpc.protobuf.ProtoServiceDescriptorSupplier;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import io.grpc.stub.ServerCalls;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
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
 * The GraphQL surface over HTTP, next to the REST route for the same data: a Boot 3 gateway
 * with DGS on, a reflection-served {@code bench.Shop} backend whose orders point at customers
 * through a DataLoader ({@code customer_id} resolves {@code Customer}; {@code GetCustomers} is
 * its batch loader), or, with {@code backend=http}, a {@code REST_DOWNSTREAM} service in front of
 * a reactor-netty stub serving an OpenAPI 3 document and the same data as JSON.
 * {@link GatewayHttpBenchmark} measures what surrounds a REST request; this
 * one measures what GraphQL adds on top: parse, validate, execute, per-field fetchers, the
 * DataLoader dispatch, and the extra gRPC hop a nested selection costs.
 *
 * <p>{@code backend} picks the transport behind the gateway, the GraphQL surface and the
 * selected fields and row counts being the same: {@code grpc} dispatches over gRPC (the
 * reflection-served {@code bench.Shop}); {@code http} dispatches over HTTP/JSON to a stub whose
 * OpenAPI document describes the same shapes ({@code getOrder}, {@code listOrders},
 * {@code getCustomers}; the response bytes are precomputed so the stub costs little). The
 * difference between the two backends for one leg is the upstream transport and the JSON → Map
 * → synthetic message → projected Map binding.
 *
 * <ul>
 *   <li>{@code rest_*} — the REST route for the same call, on the same gateway, so the GraphQL
 *       excess is read against a REST number taken with the GraphQL surface on. For {@code grpc}
 *       it is the auto-mapped transcoding route; for {@code http} it is the REST passthrough of
 *       the {@code REST_DOWNSTREAM} service (a proxy of the stub's JSON, not a transcode).
 *   <li>{@code graphql_single} — one order: one gRPC hop, the GraphQL execution cost alone.
 *   <li>{@code graphql_list_N} — N orders, no nested selection: one hop, N result objects.
 *   <li>{@code graphql_nested_N} — N orders each selecting its customer: one list hop plus one
 *       batch hop of N keys through the DataLoader. The difference to {@code graphql_list_N} is
 *       the DataLoader round: the fetchers scheduled, the dispatch, the batch call. Not available
 *       with {@code backend=http}: a {@code REST_DOWNSTREAM} type has no relationship field (the
 *       schema links {@code customer_id} to {@code Customer} through the proto
 *       {@code resolves_type} option, which an OpenAPI-derived descriptor cannot carry;
 *       {@code rest-batch-loaders[]} feed Federation {@code _entities} only), so these two throw
 *       {@link UnsupportedOperationException}; run them with {@code -p backend=grpc}.
 * </ul>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 3)
@Fork(1)
public class GatewayGraphQlBenchmark {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class GatewayApp {}

    private static final int CUSTOMERS = 100;

    private static final String GQL_ORDER_FIELDS = "id name active score items { sku qty price }";

    private static final String GRPC_SINGLE = query("{ order(id: \\\"order-1\\\") { " + GQL_ORDER_FIELDS + " } }");
    private static final String GRPC_LIST_10 = query("{ orders(count: 10) { items { " + GQL_ORDER_FIELDS + " } } }");
    private static final String GRPC_LIST_100 = query("{ orders(count: 100) { items { " + GQL_ORDER_FIELDS + " } } }");
    private static final String GRPC_NESTED_10 =
            query("{ orders(count: 10) { items { id name customer { id name tier } } } }");
    private static final String GRPC_NESTED_100 =
            query("{ orders(count: 100) { items { id name customer { id name tier } } } }");

    /** The http backend's field names are the OpenAPI operationIds; a list operation returns the array itself. */
    private static final String HTTP_SINGLE = query("{ getOrder(id: \\\"order-1\\\") { " + GQL_ORDER_FIELDS + " } }");

    private static final String HTTP_LIST_10 = query("{ listOrders(count: 10) { " + GQL_ORDER_FIELDS + " } }");
    private static final String HTTP_LIST_100 = query("{ listOrders(count: 100) { " + GQL_ORDER_FIELDS + " } }");

    private static final String REST_PREFIX = "/shop";

    /** Same profiles as {@link GatewayHttpBenchmark}, so the two tables read against each other. */
    /**
     * {@code no-field-metrics}: the per-field instrumentation off — DGS's data-fetcher timers and
     * Spring GraphQL's {@code graphql.datafetcher} observation — the request-level metrics kept.
     */
    @Param({"default", "no-otel", "lean", "no-field-metrics"})
    public String profile;

    /**
     * {@code grpc}: a reflection-served gRPC backend. {@code http}: a {@code REST_DOWNSTREAM}
     * service over an OpenAPI document served by a reactor-netty stub. Same data, same queries
     * modulo field names, same row counts.
     */
    @Param({"grpc", "http"})
    public String backend;

    private boolean http;
    private String restSingle;
    private String restList10;
    private String restList100;
    private String gqlSingle;
    private String gqlList10;
    private String gqlList100;
    private String gqlNested10;
    private String gqlNested100;
    private DisposableServer httpStub;
    /** Precomputed stub responses by request shape (order id, count, customer ids). */
    private final java.util.Map<String, byte[]> stubBodies = new ConcurrentHashMap<>();

    private Server grpcBackend;
    private ConfigurableApplicationContext gateway;
    private WebClient client;

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

    private static String query(String graphql) {
        return "{\"query\":\"" + graphql + "\"}";
    }

    static ShopOrder order(int n) {
        ShopOrder.Builder b = ShopOrder.newBuilder()
                .setId("order-" + n)
                .setName("benchmark-order-" + n)
                .setActive(n % 2 == 0)
                .setScore(n * 1.5)
                .setCustomerId("cust-" + (n % CUSTOMERS + 1));
        for (int i = 1; i <= 3; i++) {
            b.addItems(
                    ShopItem.newBuilder().setSku("SKU-" + n + "-" + i).setQty(i).setPrice(9.99 * i));
        }
        return b.build();
    }

    static Customer customer(String id) {
        return Customer.newBuilder()
                .setId(id)
                .setName("Customer " + id)
                .setEmail(id + "@example.com")
                .setTier(id.endsWith("7") ? "gold" : "silver")
                .build();
    }

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        http = "http".equals(backend);
        if (!http && !"grpc".equals(backend)) {
            throw new IllegalArgumentException("backend must be grpc or http: " + backend);
        }
        if (http) {
            startHttpStub();
        } else {
            startGrpcBackend();
        }

        // The gateway: GraphQL on, auto-mapped REST on, UI/OpenAPI off; the service is DYNAMIC
        // discovery against the gRPC backend, or REST_DOWNSTREAM against the OpenAPI stub.
        List<String> properties = new ArrayList<>(List.of(
                "server.port=0",
                "spring.main.banner-mode=off",
                "logging.level.root=WARN",
                "shiguchi.ui.enabled=false",
                "shiguchi.graphql.enabled=true",
                "shiguchi.openapi.enabled=false",
                "shiguchi.rest.auto-mapping=true",
                "shiguchi.services[0].name=shop"));
        if (http) {
            properties.addAll(List.of(
                    "shiguchi.services[0].mode=REST_DOWNSTREAM",
                    "shiguchi.services[0].path-prefix=" + REST_PREFIX,
                    "shiguchi.services[0].openapi-url=http://localhost:" + httpStub.port() + "/v3/api-docs"));
        } else {
            properties.addAll(List.of(
                    "shiguchi.services[0].mode=DYNAMIC",
                    "shiguchi.services[0].host=localhost",
                    "shiguchi.services[0].port=" + grpcBackend.getPort()));
        }
        if ("lean".equals(profile)) {
            properties.addAll(List.of(
                    "management.observations.enable.spring.security=false",
                    "management.observations.enable.http.server.requests=false",
                    "management.observations.enable.http=false",
                    "management.observations.enable.graphql=false",
                    "management.tracing.enabled=false",
                    "spring.cloud.gateway.metrics.enabled=false",
                    "spring.cloud.gateway.server.webflux.metrics.enabled=false",
                    "spring.cloud.gateway.observability.enabled=false",
                    "spring.cloud.gateway.server.webflux.observability.enabled=false",
                    "spring.reactor.context-propagation=limited"));
        }
        if ("no-field-metrics".equals(profile)) {
            properties.addAll(List.of(
                    "management.metrics.dgs-graphql.instrumentation.enabled=false",
                    "management.observations.enable.graphql.datafetcher=false"));
        }
        if ("no-otel".equals(profile)) {
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
        int port = Integer.parseInt(gateway.getEnvironment().getRequiredProperty("local.server.port"));
        client = WebClient.create("http://localhost:" + port);

        // Warm the connections and verify every leg answers what it should.
        if (http) {
            gqlSingle = HTTP_SINGLE;
            gqlList10 = HTTP_LIST_10;
            gqlList100 = HTTP_LIST_100;
            restSingle = REST_PREFIX + "/orders/order-1";
            restList10 = REST_PREFIX + "/orders?count=10";
            restList100 = REST_PREFIX + "/orders?count=100";
            expect(get(restSingle), "\"benchmark-order-1\"");
            expect(get(restList100), "\"benchmark-order-100\"");
            String single = post("/graphql", gqlSingle);
            expect(single, "\"getOrder\":{\"id\":\"order-1\",\"name\":\"benchmark-order-1\"");
            expect(single, "\"sku\":\"SKU-1-3\"");
            expect(single, "\"score\":1.5");
            String list10 = post("/graphql", gqlList10);
            expect(list10, "\"name\":\"benchmark-order-10\"");
            expectRows(list10, 10);
            String list100 = post("/graphql", gqlList100);
            expect(list100, "\"name\":\"benchmark-order-100\"");
            expectRows(list100, 100);
        } else {
            gqlSingle = GRPC_SINGLE;
            gqlList10 = GRPC_LIST_10;
            gqlList100 = GRPC_LIST_100;
            gqlNested10 = GRPC_NESTED_10;
            gqlNested100 = GRPC_NESTED_100;
            expect(post("/bench.Shop/Get", "{\"id\":\"order-1\"}"), "\"benchmark-order-1\"");
            expect(post("/bench.Shop/List", "{\"count\":100}"), "\"benchmark-order-100\"");
            expect(post("/graphql", gqlSingle), "\"benchmark-order-1\"");
            expect(post("/graphql", gqlList100), "\"benchmark-order-100\"");
            expect(post("/graphql", gqlNested10), "\"customer\":{\"id\":\"cust-10\"");
            expect(post("/graphql", gqlNested100), "\"customer\":{\"id\":\"cust-100\"");
        }
    }

    private void startGrpcBackend() throws Exception {
        // Backend: compiled stubs, reflection on, a real socket. Three hand-bound unary methods.
        FileDescriptor file = ShopOrder.getDescriptor().getFile();
        var get = unary("bench.Shop/Get", GetShopOrderRequest.getDefaultInstance(), ShopOrder.getDefaultInstance());
        var list =
                unary("bench.Shop/List", ListShopOrdersRequest.getDefaultInstance(), ShopOrders.getDefaultInstance());
        var customers = unary(
                "bench.Shop/GetCustomers", GetCustomersRequest.getDefaultInstance(), Customers.getDefaultInstance());
        var serviceDescriptor = io.grpc.ServiceDescriptor.newBuilder("bench.Shop")
                .setSchemaDescriptor(new Schema(file, file.findServiceByName("Shop")))
                .addMethod(get)
                .addMethod(list)
                .addMethod(customers)
                .build();
        ServerServiceDefinition service = ServerServiceDefinition.builder(serviceDescriptor)
                .addMethod(get, ServerCalls.asyncUnaryCall((req, obs) -> {
                    int n = Integer.parseInt(req.getId().substring("order-".length()));
                    obs.onNext(order(n));
                    obs.onCompleted();
                }))
                .addMethod(list, ServerCalls.asyncUnaryCall((req, obs) -> {
                    ShopOrders.Builder b = ShopOrders.newBuilder();
                    for (int n = 1; n <= req.getCount(); n++) b.addItems(order(n));
                    obs.onNext(b.build());
                    obs.onCompleted();
                }))
                .addMethod(customers, ServerCalls.asyncUnaryCall((req, obs) -> {
                    Customers.Builder b = Customers.newBuilder();
                    for (String id : req.getIdsList()) b.addItems(customer(id));
                    obs.onNext(b.build());
                    obs.onCompleted();
                }))
                .build();
        grpcBackend = ServerBuilder.forPort(0)
                .addService(service)
                .addService(ProtoReflectionServiceV1.newInstance())
                .build()
                .start();
    }

    /** Serves the OpenAPI document and the shop's JSON; response bytes are built once per request shape. */
    private void startHttpStub() {
        byte[] openApi = OPENAPI.getBytes(StandardCharsets.UTF_8);
        httpStub = HttpServer.create()
                .port(0)
                .route(r -> r.get(
                                "/v3/api-docs",
                                (req, res) -> res.header("Content-Type", "application/json")
                                        .sendByteArray(Mono.just(openApi)))
                        .get("/orders/{id}", (req, res) -> {
                            String id = req.param("id");
                            byte[] body = stubBodies.computeIfAbsent(
                                    "order:" + id, k -> orderJson(Integer.parseInt(id.substring("order-".length()))));
                            return res.header("Content-Type", "application/json")
                                    .sendByteArray(Mono.just(body));
                        })
                        .get("/orders", (req, res) -> {
                            String count = queryParam(req.uri(), "count");
                            byte[] body = stubBodies.computeIfAbsent(
                                    "orders:" + count, k -> ordersJson(Integer.parseInt(count)));
                            return res.header("Content-Type", "application/json")
                                    .sendByteArray(Mono.just(body));
                        })
                        .get("/customers", (req, res) -> {
                            String ids = queryParam(req.uri(), "ids");
                            byte[] body = stubBodies.computeIfAbsent("customers:" + ids, k -> customersJson(ids));
                            return res.header("Content-Type", "application/json")
                                    .sendByteArray(Mono.just(body));
                        }))
                .bindNow();
    }

    private static final JsonFormat.Printer JSON = JsonFormat.printer()
            .omittingInsignificantWhitespace()
            .preservingProtoFieldNames()
            .alwaysPrintFieldsWithNoPresence();

    private static byte[] orderJson(int n) {
        return print(order(n)).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] ordersJson(int count) {
        StringBuilder sb = new StringBuilder("[");
        for (int n = 1; n <= count; n++) {
            if (n > 1) sb.append(',');
            sb.append(print(order(n)));
        }
        return sb.append(']').toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] customersJson(String ids) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String id : ids.split(",")) {
            if (!first) sb.append(',');
            first = false;
            sb.append(print(customer(id)));
        }
        return sb.append(']').toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String print(com.google.protobuf.MessageOrBuilder message) {
        try {
            return JSON.print(message);
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String queryParam(String uri, String name) {
        int q = uri.indexOf('?');
        if (q >= 0) {
            for (String pair : uri.substring(q + 1).split("&")) {
                if (pair.startsWith(name + "=")) {
                    return java.net.URLDecoder.decode(pair.substring(name.length() + 1), StandardCharsets.UTF_8);
                }
            }
        }
        return "";
    }

    /** The same shapes as {@code bench.Shop}; the property names are the proto field names. */
    private static final String OPENAPI = """
            {
              "openapi": "3.0.3",
              "info": { "title": "Shop", "version": "1.0" },
              "paths": {
                "/orders/{id}": {
                  "get": {
                    "operationId": "getOrder",
                    "parameters": [ { "name": "id", "in": "path", "required": true, "schema": { "type": "string" } } ],
                    "responses": { "200": { "description": "OK", "content": { "application/json": {
                      "schema": { "$ref": "#/components/schemas/Order" } } } } }
                  }
                },
                "/orders": {
                  "get": {
                    "operationId": "listOrders",
                    "parameters": [ { "name": "count", "in": "query", "schema": { "type": "integer", "format": "int32" } } ],
                    "responses": { "200": { "description": "OK", "content": { "application/json": {
                      "schema": { "type": "array", "items": { "$ref": "#/components/schemas/Order" } } } } } }
                  }
                },
                "/customers": {
                  "get": {
                    "operationId": "getCustomers",
                    "parameters": [ { "name": "ids", "in": "query", "schema": { "type": "string" } } ],
                    "responses": { "200": { "description": "OK", "content": { "application/json": {
                      "schema": { "type": "array", "items": { "$ref": "#/components/schemas/Customer" } } } } } }
                  }
                }
              },
              "components": {
                "schemas": {
                  "Order": {
                    "type": "object",
                    "properties": {
                      "id": { "type": "string" },
                      "name": { "type": "string" },
                      "active": { "type": "boolean" },
                      "score": { "type": "number", "format": "double" },
                      "customer_id": { "type": "string" },
                      "items": { "type": "array", "items": { "$ref": "#/components/schemas/OrderItem" } }
                    }
                  },
                  "OrderItem": {
                    "type": "object",
                    "properties": {
                      "sku": { "type": "string" },
                      "qty": { "type": "integer", "format": "int32" },
                      "price": { "type": "number", "format": "double" }
                    }
                  },
                  "Customer": {
                    "type": "object",
                    "properties": {
                      "id": { "type": "string" },
                      "name": { "type": "string" },
                      "email": { "type": "string" },
                      "tier": { "type": "string" }
                    }
                  }
                }
              }
            }
            """;

    private static <Q extends com.google.protobuf.Message, R extends com.google.protobuf.Message>
            MethodDescriptor<Q, R> unary(String name, Q request, R response) {
        return MethodDescriptor.<Q, R>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName(name)
                .setRequestMarshaller(ProtoUtils.marshaller(request))
                .setResponseMarshaller(ProtoUtils.marshaller(response))
                .build();
    }

    private static void expect(String body, String fragment) {
        if (body == null || !body.contains(fragment) || body.contains("\"errors\"")) {
            throw new IllegalStateException("expected " + fragment + " in: " + body);
        }
    }

    /** The list leg returns exactly {@code rows} orders: counts the "benchmark-order-" names. */
    private static void expectRows(String body, int rows) {
        String needle = "\"name\":\"benchmark-order-";
        int found = 0;
        for (int i = body.indexOf(needle); i >= 0; i = body.indexOf(needle, i + 1)) {
            found++;
        }
        if (found != rows) {
            throw new IllegalStateException("expected " + rows + " orders, got " + found);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (gateway != null) {
            var registry = gateway.getBean(io.micrometer.core.instrument.MeterRegistry.class);
            for (String name : List.of("http.server.requests", "graphql.request", "grpc.client.call.duration")) {
                for (var timer : registry.find(name).timers()) {
                    System.err.printf(
                            "TIMER %s %s count=%d mean=%.1fus max=%.1fus%n",
                            name,
                            timer.getId().getTags(),
                            timer.count(),
                            timer.mean(TimeUnit.MICROSECONDS),
                            timer.max(TimeUnit.MICROSECONDS));
                }
            }
            gateway.close();
        }
        if (grpcBackend != null) grpcBackend.shutdownNow();
        if (httpStub != null) httpStub.disposeNow();
    }

    private String post(String path, String body) {
        return client.post()
                .uri(path)
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(30));
    }

    private String get(String path) {
        return client.get().uri(path).retrieve().bodyToMono(String.class).block(Duration.ofSeconds(30));
    }

    /** The REST leg: a transcoded POST for gRPC, the passthrough GET of the REST_DOWNSTREAM service for http. */
    private String rest(String httpPath, String grpcMethod, String grpcBody) {
        return http ? get(httpPath) : post(grpcMethod, grpcBody);
    }

    @Benchmark
    public String rest_single() {
        return rest(restSingle, "/bench.Shop/Get", "{\"id\":\"order-1\"}");
    }

    @Benchmark
    public String rest_list_10() {
        return rest(restList10, "/bench.Shop/List", "{\"count\":10}");
    }

    @Benchmark
    public String rest_list_100() {
        return rest(restList100, "/bench.Shop/List", "{\"count\":100}");
    }

    @Benchmark
    public String graphql_single() {
        return post("/graphql", gqlSingle);
    }

    @Benchmark
    public String graphql_list_10() {
        return post("/graphql", gqlList10);
    }

    @Benchmark
    public String graphql_list_100() {
        return post("/graphql", gqlList100);
    }

    @Benchmark
    public String graphql_nested_10() {
        requireNested();
        return post("/graphql", gqlNested10);
    }

    @Benchmark
    public String graphql_nested_100() {
        requireNested();
        return post("/graphql", gqlNested100);
    }

    private void requireNested() {
        if (http) {
            throw new UnsupportedOperationException(
                    "a REST_DOWNSTREAM type has no relationship field; run graphql_nested_* with -p backend=grpc");
        }
    }
}
