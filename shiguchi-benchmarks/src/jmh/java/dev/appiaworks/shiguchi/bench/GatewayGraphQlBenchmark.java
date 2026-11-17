package dev.appiaworks.shiguchi.bench;

import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import com.google.protobuf.util.JsonFormat;
import dev.appiaworks.shiguchi.bench.proto.BooksByAuthorsRequest;
import dev.appiaworks.shiguchi.bench.proto.BooksOfRequest;
import dev.appiaworks.shiguchi.bench.proto.Customer;
import dev.appiaworks.shiguchi.bench.proto.Customers;
import dev.appiaworks.shiguchi.bench.proto.GetCustomersRequest;
import dev.appiaworks.shiguchi.bench.proto.GetRegionsRequest;
import dev.appiaworks.shiguchi.bench.proto.GetShopOrderRequest;
import dev.appiaworks.shiguchi.bench.proto.ListShopAuthorsRequest;
import dev.appiaworks.shiguchi.bench.proto.ListShopOrdersRequest;
import dev.appiaworks.shiguchi.bench.proto.Region;
import dev.appiaworks.shiguchi.bench.proto.Regions;
import dev.appiaworks.shiguchi.bench.proto.ShopAuthor;
import dev.appiaworks.shiguchi.bench.proto.ShopAuthors;
import dev.appiaworks.shiguchi.bench.proto.ShopBook;
import dev.appiaworks.shiguchi.bench.proto.ShopBooks;
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
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
 * The GraphQL surface over HTTP, next to the REST route for the same data: a gateway
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
 *   <li>{@code graphql_nested_1000}, {@code graphql_nested_10000} — the same shape at the sizes the
 *       batching work is judged on. {@code distinctCustomers} sets how many different customers
 *       the orders point at (default 100, as in the other nested legs); {@code maxBatchSize}
 *       splits a type's batch into calls of at most that many ids (0 = no split, the baseline);
 *       {@code entityCache=on} turns the opt-in cross-request {@code Customer}/{@code Region}
 *       cache on.
 *   <li>{@code graphql_two_level_100}, {@code graphql_two_level_1000} — N orders, each selecting
 *       its customer and the customer's region (10 regions): a list hop plus one batch hop per
 *       level.
 *   <li>{@code graphql_has_many_AxB} — A authors each selecting their B books through a grouped
 *       loader ({@code group_by}): one list hop plus one batch hop of A keys, whatever A is.
 *   <li>{@code graphql_per_parent_AxB} — the same data with one upstream call per author: the
 *       authors, then one {@code booksOf(authorId)} field per author in the same document (a
 *       resolver never reads its parent, so the per-parent calls are aliased root fields). The
 *       difference to {@code graphql_has_many_AxB} is what the grouped loader saves. Only with
 *       {@code backend=grpc}.
 * </ul>
 *
 * <p>The gRPC backend counts its calls and the ids they carried per method, and setup checks the
 * batching legs against them: with the entity cache off, upstream calls must equal
 * {@code ceil(distinct keys / maxBatchSize)} per (level, type) and the ids carried the distinct
 * keys, so a leg that silently stops batching or de-duplicating fails before it is measured.
 * {@code upstreamLatencyMicros} delays the answers of the loader and has-many stubs (not the list
 * and get stubs), so the grouped versus per-parent comparison reads like a backend that is not on
 * the same host. The defaults of the added params reproduce the original legs.
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

    /** Regions the customers are spread over, for the two-level legs. */
    private static final int REGIONS = 10;

    /** Books per author in the one-to-many legs. */
    private static final int BOOKS_PER_AUTHOR = 10;

    private static final String GQL_ORDER_FIELDS = "id name active score items { sku qty price }";

    private static final String GRPC_SINGLE = query("{ order(id: \\\"order-1\\\") { " + GQL_ORDER_FIELDS + " } }");
    private static final String GRPC_LIST_10 = query("{ orders(count: 10) { items { " + GQL_ORDER_FIELDS + " } } }");
    private static final String GRPC_LIST_100 = query("{ orders(count: 100) { items { " + GQL_ORDER_FIELDS + " } } }");
    private static final String GRPC_NESTED_10 =
            query("{ orders(count: 10) { items { id name customer { id name tier } } } }");
    private static final String GRPC_NESTED_100 =
            query("{ orders(count: 100) { items { id name customer { id name tier } } } }");
    private static final String NESTED_FIELDS = "{ items { id name customer { id name tier } } }";
    private static final String TWO_LEVEL_FIELDS = "{ items { id name customer { id name region { id name } } } }";

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

    /**
     * Most ids of one type per upstream call ({@code data-loader.max-batch-size}); 0 = unlimited,
     * the baseline. Sweep with {@code -p maxBatchSize=0,100,1000}.
     */
    @Param({"0"})
    public int maxBatchSize;

    /**
     * {@code off}: no cross-request cache, the baseline. {@code on}: {@code Customer} and
     * {@code Region} cached for an hour, so a warm leg measures the cache-hit path (the call-count
     * check then only bounds the calls from above).
     */
    @Param({"off"})
    public String entityCache;

    /** How many different customers the orders point at; sweep with {@code -p distinctCustomers=1,100,1000,10000}. */
    @Param({"100"})
    public int distinctCustomers;

    /** Delay before the loader and has-many stubs answer; 0 = none, 1000 = the 1 ms acceptance comparison. */
    @Param({"0"})
    public int upstreamLatencyMicros;

    private boolean http;
    private String restSingle;
    private String restList10;
    private String restList100;
    private String gqlSingle;
    private String gqlList10;
    private String gqlList100;
    private String gqlNested10;
    private String gqlNested100;
    private String gqlNested1000;
    private String gqlNested10000;
    private String gqlTwoLevel100;
    private String gqlTwoLevel1000;
    private String gqlHasMany100;
    private String gqlHasMany1000;
    private String gqlPerParent100;
    private String gqlPerParent1000;
    private DisposableServer httpStub;
    /** Precomputed stub responses by request shape (order id, count, customer ids). */
    private final java.util.Map<String, byte[]> stubBodies = new ConcurrentHashMap<>();

    private Server grpcBackend;
    private ScheduledExecutorService delayer;
    private final Calls customerCalls = new Calls();
    private final Calls regionCalls = new Calls();
    private final Calls booksByAuthorsCalls = new Calls();
    private final Calls booksOfCalls = new Calls();
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

    /** Upstream calls of one stub method, the ids they carried and the most in one call. */
    private static final class Calls {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicLong keys = new AtomicLong();
        final AtomicInteger maxKeys = new AtomicInteger();

        void hit(int carried) {
            calls.incrementAndGet();
            keys.addAndGet(carried);
            maxKeys.accumulateAndGet(carried, Math::max);
        }

        void reset() {
            calls.set(0);
            keys.set(0);
            maxKeys.set(0);
        }

        @Override
        public String toString() {
            return "calls=" + calls + " keys=" + keys + " maxKeys=" + maxKeys;
        }
    }

    private static String query(String graphql) {
        return "{\"query\":\"" + graphql + "\"}";
    }

    ShopOrder order(int n) {
        ShopOrder.Builder b = ShopOrder.newBuilder()
                .setId("order-" + n)
                .setName("benchmark-order-" + n)
                .setActive(n % 2 == 0)
                .setScore(n * 1.5)
                .setCustomerId("cust-" + (n % distinctCustomers + 1));
        for (int i = 1; i <= 3; i++) {
            b.addItems(
                    ShopItem.newBuilder().setSku("SKU-" + n + "-" + i).setQty(i).setPrice(9.99 * i));
        }
        return b.build();
    }

    static ShopBook book(String authorId, int k) {
        return ShopBook.newBuilder()
                .setId("book-" + authorId + "-" + k)
                .setAuthorId(authorId)
                .setTitle("Title " + authorId + "-" + k)
                .setPages(100 + k)
                .build();
    }

    /** The has-many query: authors with their books through the grouped loader. */
    static String hasManyQuery(int authors) {
        return query("{ authors(count: " + authors + ") { items { id name books { id title pages } } } }");
    }

    /** The same data with one upstream books call per author: aliased booksOf root fields. */
    static String perParentQuery(int authors) {
        StringBuilder sb = new StringBuilder("{ authors(count: " + authors + ") { items { id name } }");
        for (int n = 1; n <= authors; n++) {
            sb.append(" b")
                    .append(n)
                    .append(": booksOf(authorId: \\\"author-")
                    .append(n)
                    .append("\\\") { items { id title pages } }");
        }
        return query(sb.append(" }").toString());
    }

    static Customer customer(String id) {
        return Customer.newBuilder()
                .setId(id)
                .setName("Customer " + id)
                .setEmail(id + "@example.com")
                .setTier(id.endsWith("7") ? "gold" : "silver")
                .build();
    }

    /** The gRPC backend's customer: the same, pointing at one of {@link #REGIONS} regions. */
    static Customer grpcCustomer(String id) {
        int k = Integer.parseInt(id.substring("cust-".length()));
        return customer(id).toBuilder()
                .setRegionId("region-" + (k % REGIONS + 1))
                .build();
    }

    static Region region(String id) {
        return Region.newBuilder().setId(id).setName("Region " + id).build();
    }

    /** The orders query for a leg: {@code orders(count: n)} selecting {@code fields}. */
    static String ordersQuery(int n, String fields) {
        return query("{ orders(count: " + n + ") " + fields + " }");
    }

    int distinctCustomersOf(int orders) {
        return Math.min(distinctCustomers, orders);
    }

    /** Distinct regions the first {@code orders} orders' customers point at. */
    int distinctRegionsOf(int orders) {
        Set<Integer> regions = new HashSet<>();
        for (int n = 1; n <= orders; n++) regions.add((n % distinctCustomers + 1) % REGIONS + 1);
        return regions.size();
    }

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        if (distinctCustomers < 1) throw new IllegalArgumentException("distinctCustomers must be >= 1");
        if (!"off".equals(entityCache) && !"on".equals(entityCache)) {
            throw new IllegalArgumentException("entityCache must be off or on: " + entityCache);
        }
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
        if (maxBatchSize > 0) {
            properties.add("shiguchi.graphql.data-loader.max-batch-size=" + maxBatchSize);
        }
        if ("on".equals(entityCache)) {
            properties.addAll(List.of(
                    "shiguchi.graphql.data-loader.cache.Customer.ttl=1h",
                    "shiguchi.graphql.data-loader.cache.Customer.max-size=100000",
                    "shiguchi.graphql.data-loader.cache.Region.ttl=1h"));
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
            gqlNested1000 = ordersQuery(1000, NESTED_FIELDS);
            gqlNested10000 = ordersQuery(10000, NESTED_FIELDS);
            gqlTwoLevel100 = ordersQuery(100, TWO_LEVEL_FIELDS);
            gqlTwoLevel1000 = ordersQuery(1000, TWO_LEVEL_FIELDS);
            gqlHasMany100 = hasManyQuery(100);
            gqlHasMany1000 = hasManyQuery(1000);
            gqlPerParent100 = perParentQuery(100);
            gqlPerParent1000 = perParentQuery(1000);
            expect(post("/graphql", gqlHasMany100), "\"title\":\"Title author-100-" + BOOKS_PER_AUTHOR + "\"");
            expect(post("/graphql", gqlPerParent100), "\"title\":\"Title author-100-" + BOOKS_PER_AUTHOR + "\"");
            verifyUpstreamCalls();
        }
    }

    /**
     * Runs each batching leg once and compares the stub's counters with
     * {@code ceil(distinct keys / maxBatchSize)} per (level, type). With the cache on the calls are
     * only bounded from above, since an earlier check may have warmed the same ids.
     */
    private void verifyUpstreamCalls() {
        for (int orders : new int[] {1000, 10000}) {
            resetCalls();
            String body = post("/graphql", orders == 1000 ? gqlNested1000 : gqlNested10000);
            expect(body, "\"customer\":{\"id\":\"cust-" + (orders % distinctCustomers + 1) + "\"");
            checkCalls("nested_" + orders + " Customer", customerCalls, distinctCustomersOf(orders));
        }
        for (int orders : new int[] {100, 1000}) {
            resetCalls();
            String body = post("/graphql", orders == 100 ? gqlTwoLevel100 : gqlTwoLevel1000);
            expect(body, "\"region\":{\"id\":\"region-");
            checkCalls("two_level_" + orders + " Customer", customerCalls, distinctCustomersOf(orders));
            checkCalls("two_level_" + orders + " Region", regionCalls, distinctRegionsOf(orders));
        }
        for (int authors : new int[] {100, 1000}) {
            resetCalls();
            expect(post("/graphql", authors == 100 ? gqlHasMany100 : gqlHasMany1000), "\"books\":[{");
            checkCalls("has_many_" + authors + " books", booksByAuthorsCalls, authors);
            if (booksOfCalls.calls.get() != 0) {
                throw new IllegalStateException("has_many_" + authors + ": per-parent booksOf was called");
            }
            resetCalls();
            expect(post("/graphql", authors == 100 ? gqlPerParent100 : gqlPerParent1000), "\"b1\":{");
            if (booksOfCalls.calls.get() != authors || booksByAuthorsCalls.calls.get() != 0) {
                throw new IllegalStateException(
                        "per_parent_" + authors + ": expected " + authors + " booksOf calls, got " + booksOfCalls);
            }
        }
        resetCalls();
    }

    private void checkCalls(String leg, Calls actual, int distinctKeys) {
        int expectedCalls = maxBatchSize > 0 ? (distinctKeys + maxBatchSize - 1) / maxBatchSize : 1;
        boolean cached = "on".equals(entityCache) && !leg.contains("books");
        boolean ok = cached
                ? actual.calls.get() <= expectedCalls && actual.keys.get() <= distinctKeys
                : actual.calls.get() == expectedCalls && actual.keys.get() == distinctKeys;
        if (maxBatchSize > 0 && actual.maxKeys.get() > maxBatchSize) ok = false;
        if (!ok) {
            throw new IllegalStateException(leg + ": expected " + expectedCalls + " upstream call(s) carrying "
                    + distinctKeys + " distinct id(s) (maxBatchSize=" + maxBatchSize + ", entityCache="
                    + entityCache + "), got " + actual);
        }
    }

    private void resetCalls() {
        for (Calls c : List.of(customerCalls, regionCalls, booksByAuthorsCalls, booksOfCalls)) c.reset();
    }

    /** Answers {@code response}, after {@code upstreamLatencyMicros} when {@code delayed} (a loader or has-many stub). */
    private <T> void reply(StreamObserver<T> obs, T response, boolean delayed) {
        if (delayed && upstreamLatencyMicros > 0) {
            delayer.schedule(
                    () -> {
                        obs.onNext(response);
                        obs.onCompleted();
                    },
                    upstreamLatencyMicros,
                    TimeUnit.MICROSECONDS);
        } else {
            obs.onNext(response);
            obs.onCompleted();
        }
    }

    private void startGrpcBackend() throws Exception {
        // Backend: compiled stubs, reflection on, a real socket. Hand-bound unary methods.
        delayer = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "stub-delay");
            t.setDaemon(true);
            return t;
        });
        FileDescriptor file = ShopOrder.getDescriptor().getFile();
        var get = unary("bench.Shop/Get", GetShopOrderRequest.getDefaultInstance(), ShopOrder.getDefaultInstance());
        var list =
                unary("bench.Shop/List", ListShopOrdersRequest.getDefaultInstance(), ShopOrders.getDefaultInstance());
        var customers = unary(
                "bench.Shop/GetCustomers", GetCustomersRequest.getDefaultInstance(), Customers.getDefaultInstance());
        var regions =
                unary("bench.Shop/GetRegions", GetRegionsRequest.getDefaultInstance(), Regions.getDefaultInstance());
        var listAuthors = unary(
                "bench.Shop/ListAuthors",
                ListShopAuthorsRequest.getDefaultInstance(),
                ShopAuthors.getDefaultInstance());
        var booksByAuthors = unary(
                "bench.Shop/BooksByAuthors",
                BooksByAuthorsRequest.getDefaultInstance(),
                ShopBooks.getDefaultInstance());
        var booksOf = unary("bench.Shop/BooksOf", BooksOfRequest.getDefaultInstance(), ShopBooks.getDefaultInstance());
        var serviceDescriptor = io.grpc.ServiceDescriptor.newBuilder("bench.Shop")
                .setSchemaDescriptor(new Schema(file, file.findServiceByName("Shop")))
                .addMethod(get)
                .addMethod(list)
                .addMethod(customers)
                .addMethod(regions)
                .addMethod(listAuthors)
                .addMethod(booksByAuthors)
                .addMethod(booksOf)
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
                    for (String id : req.getIdsList()) b.addItems(grpcCustomer(id));
                    customerCalls.hit(req.getIdsCount());
                    reply(obs, b.build(), true);
                }))
                .addMethod(regions, ServerCalls.asyncUnaryCall((req, obs) -> {
                    Regions.Builder b = Regions.newBuilder();
                    for (String id : req.getIdsList()) b.addItems(region(id));
                    regionCalls.hit(req.getIdsCount());
                    reply(obs, b.build(), true);
                }))
                .addMethod(listAuthors, ServerCalls.asyncUnaryCall((req, obs) -> {
                    ShopAuthors.Builder b = ShopAuthors.newBuilder();
                    for (int n = 1; n <= req.getCount(); n++) {
                        b.addItems(ShopAuthor.newBuilder().setId("author-" + n).setName("Author " + n));
                    }
                    obs.onNext(b.build());
                    obs.onCompleted();
                }))
                .addMethod(booksByAuthors, ServerCalls.asyncUnaryCall((req, obs) -> {
                    ShopBooks.Builder b = ShopBooks.newBuilder();
                    for (String id : req.getAuthorIdsList()) {
                        for (int k = 1; k <= BOOKS_PER_AUTHOR; k++) b.addItems(book(id, k));
                    }
                    booksByAuthorsCalls.hit(req.getAuthorIdsCount());
                    reply(obs, b.build(), true);
                }))
                .addMethod(booksOf, ServerCalls.asyncUnaryCall((req, obs) -> {
                    ShopBooks.Builder b = ShopBooks.newBuilder();
                    for (int k = 1; k <= BOOKS_PER_AUTHOR; k++) b.addItems(book(req.getAuthorId(), k));
                    booksOfCalls.hit(1);
                    reply(obs, b.build(), true);
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

    private byte[] orderJson(int n) {
        return print(order(n)).getBytes(StandardCharsets.UTF_8);
    }

    private byte[] ordersJson(int count) {
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
        if (delayer != null) delayer.shutdownNow();
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

    @Benchmark
    public String graphql_nested_1000() {
        requireNested();
        return post("/graphql", gqlNested1000);
    }

    @Benchmark
    public String graphql_nested_10000() {
        requireNested();
        return post("/graphql", gqlNested10000);
    }

    @Benchmark
    public String graphql_two_level_100() {
        requireNested();
        return post("/graphql", gqlTwoLevel100);
    }

    @Benchmark
    public String graphql_two_level_1000() {
        requireNested();
        return post("/graphql", gqlTwoLevel1000);
    }

    @Benchmark
    public String graphql_has_many_100x10() {
        requireNested();
        return post("/graphql", gqlHasMany100);
    }

    @Benchmark
    public String graphql_has_many_1000x10() {
        requireNested();
        return post("/graphql", gqlHasMany1000);
    }

    @Benchmark
    public String graphql_per_parent_100x10() {
        requireNested();
        return post("/graphql", gqlPerParent100);
    }

    @Benchmark
    public String graphql_per_parent_1000x10() {
        requireNested();
        return post("/graphql", gqlPerParent1000);
    }

    private void requireNested() {
        if (http) {
            throw new UnsupportedOperationException(
                    "a REST_DOWNSTREAM type has no relationship field; run the nested and has-many legs with -p backend=grpc");
        }
    }
}
