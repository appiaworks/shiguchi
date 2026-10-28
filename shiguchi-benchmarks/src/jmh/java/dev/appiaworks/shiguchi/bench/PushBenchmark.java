package dev.appiaworks.shiguchi.bench;

import dev.appiaworks.shiguchi.cursus.MemoryPushBroker;
import dev.appiaworks.shiguchi.cursus.MemoryPushStore;
import dev.appiaworks.shiguchi.cursus.PushEvent;
import dev.appiaworks.shiguchi.cursus.PushMetrics;
import dev.appiaworks.shiguchi.cursus.PushProperties;
import dev.appiaworks.shiguchi.cursus.PushPublisher;
import dev.appiaworks.shiguchi.cursus.Qos;
import dev.appiaworks.shiguchi.cursus.Topic;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
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
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;

/**
 * The push surface: what one publish costs in the engine alone and what it costs end to end over
 * HTTP with subscribers connected.
 *
 * <ul>
 *   <li>{@code engine_fanout}: {@code MemoryPushBroker.publish} with {@code subscribers} live
 *       subscriptions on the topic, each draining with unbounded demand. The per-subscriber cost
 *       of the sink.
 *   <li>{@code engine_publish}: {@code PushPublisher.publish}, JSON validation, id, history of
 *       100, fan-out to {@code subscribers}. What a publish costs before HTTP.
 *   <li>{@code http_publish_delivery}: a Boot 3 gateway with push on (the {@code engine}:
 *       in-memory, or Redis Pub/Sub + Streams on an embedded Redis), {@code subscribers} SSE
 *       connections on one topic, one {@code POST /push/publish} per operation, and the operation
 *       ends when the first subscriber has the event. Publish-to-delivery latency through the
 *       whole stack.
 *   <li>{@code httpv1_publish_delivery}: the same, through {@code POST /push/v1/publish}: the
 *       gateway's JSON transcoding into the hosted {@code PushService} over the in-process
 *       channel, against the hand-written handler above.
 *   <li>{@code grpc_publish_delivery}: {@code subscribers} gRPC {@code Subscribe} streams on a
 *       third topic through the native gRPC port, one connection per hundred streams, one
 *       {@code Publish} over the same port per operation, done when the first stream has the
 *       event.
 *   <li>{@code ws_publish_delivery}: the same with {@code subscribers} WebSocket connections on
 *       a second topic, done when the first socket has the event frame.
 * </ul>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(1)
public class PushBenchmark {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class GatewayApp {
        /**
         * The JDBC engine's database, only when {@code bench.jdbc.url} is set. Boot's DataSource
         * auto-configuration backs off here because an R2DBC ConnectionFactory is on the classpath.
         */
        @org.springframework.context.annotation.Bean
        @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "bench.jdbc.url")
        javax.sql.DataSource benchDataSource(
                @org.springframework.beans.factory.annotation.Value("${bench.jdbc.url}") String url) {
            org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
            ds.setURL(url);
            return ds;
        }
    }

    @Param({"1", "100", "1000"})
    public int subscribers;

    /**
     * {@code MEMORY}; {@code REDIS} over an embedded Redis; {@code POSTGRES} over an embedded
     * Postgres; {@code JDBC} over an in-memory H2 (broker polling every 50 ms, the floor), each
     * started for the trial.
     */
    @Param({"MEMORY", "REDIS", "POSTGRES", "JDBC"})
    public String engine;

    /**
     * {@code true}: {@code archive.retention=1h}, so on POSTGRES the three search indexes are on
     * and every event (a no-history topic's too) gets a row; on MEMORY a per-instance ring. Not
     * in the default matrix: {@code -p archive=true -p engine=POSTGRES} measures its cost.
     */
    @Param({"false"})
    public String archive;

    private redis.embedded.RedisServer redisServer;
    private io.zonky.test.db.postgres.embedded.EmbeddedPostgres postgres;
    private dev.appiaworks.shiguchi.cursus.postgres.PostgresPushStore postgresStore;
    private dev.appiaworks.shiguchi.cursus.jdbc.JdbcPushStore jdbcStore;
    private org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory redisConnections;

    private static final Topic TOPIC = Topic.parse("bench:topic");
    private static final byte[] PAYLOAD =
            "{\"orderId\":\"order-1\",\"status\":\"SHIPPED\",\"items\":3}".getBytes(StandardCharsets.UTF_8);

    private dev.appiaworks.shiguchi.cursus.PushBroker broker;
    private dev.appiaworks.shiguchi.cursus.PushStore store;
    private PushPublisher publisher;
    private final List<Disposable> engineSubscriptions = new ArrayList<>();
    private final AtomicLong engineDelivered = new AtomicLong();

    private ConfigurableApplicationContext gateway;
    private WebClient client;
    private final List<Disposable> httpSubscriptions = new ArrayList<>();
    private volatile CountDownLatch delivered;
    private volatile CountDownLatch wsDelivered;
    private final List<reactor.core.Disposable> wsSubscriptions = new ArrayList<>();
    private String publishBody;
    private String wsPublishBody;
    private String v1PublishBody;
    private io.grpc.ManagedChannel grpcChannel;
    /** The subscribers' channels: real clients do not share one HTTP/2 connection, so neither do these. */
    private final List<io.grpc.ManagedChannel> grpcSubscriberChannels = new ArrayList<>();

    private dev.appiaworks.shiguchi.proto.cursus.PushServiceGrpc.PushServiceBlockingStub grpcStub;
    private dev.appiaworks.shiguchi.proto.cursus.PublishRequest grpcPublish;
    private volatile CountDownLatch grpcDelivered;
    private final List<io.grpc.Context.CancellableContext> grpcSubscriptions = new ArrayList<>();

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        // Engine legs.
        int redisPort = 0;
        if ("REDIS".equals(engine)) {
            try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
                redisPort = socket.getLocalPort();
            }
            redisServer =
                    redis.embedded.RedisServer.newRedisServer().port(redisPort).build();
            redisServer.start();
            redisConnections = new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(
                    "localhost", redisPort);
            redisConnections.afterPropertiesSet();
            var template = new org.springframework.data.redis.core.ReactiveStringRedisTemplate(redisConnections);
            broker = new dev.appiaworks.shiguchi.cursus.redis.RedisPushBroker(template, "bench:push:");
            store = new dev.appiaworks.shiguchi.cursus.redis.RedisPushStore(template, "bench:push:");
        } else if ("POSTGRES".equals(engine)) {
            postgres = io.zonky.test.db.postgres.embedded.EmbeddedPostgres.start();
            var factory = io.r2dbc.spi.ConnectionFactories.get(
                    "r2dbc:pool:postgresql://postgres:postgres@localhost:" + postgres.getPort() + "/postgres");
            var client = org.springframework.r2dbc.core.DatabaseClient.create(factory);
            PushProperties.Postgres pg = new PushProperties.Postgres();
            pg.setTable("bench_push_event");
            pg.setChannel("bench_push");
            PushProperties.Archive archiveSettings = new PushProperties.Archive();
            if ("true".equals(archive)) archiveSettings.setRetention(Duration.ofHours(1));
            postgresStore = new dev.appiaworks.shiguchi.cursus.postgres.PostgresPushStore(client, pg, archiveSettings);
            postgresStore.afterPropertiesSet();
            store = postgresStore;
            broker = new dev.appiaworks.shiguchi.cursus.postgres.PostgresPushBroker(
                    factory, client, pg, () -> postgresStore);
        } else if ("JDBC".equals(engine)) {
            org.h2.jdbcx.JdbcDataSource h2 = new org.h2.jdbcx.JdbcDataSource();
            h2.setURL("jdbc:h2:mem:bench_push;DB_CLOSE_DELAY=-1");
            PushProperties.Jdbc jdbc = new PushProperties.Jdbc();
            jdbc.setTable("bench_push_event");
            jdbc.setPollInterval(Duration.ofMillis(50));
            PushProperties.Archive archiveSettings = new PushProperties.Archive();
            if ("true".equals(archive)) archiveSettings.setRetention(Duration.ofHours(1));
            jdbcStore = new dev.appiaworks.shiguchi.cursus.jdbc.JdbcPushStore(h2, jdbc, archiveSettings);
            jdbcStore.afterPropertiesSet();
            store = jdbcStore;
            var jdbcBroker = new dev.appiaworks.shiguchi.cursus.jdbc.JdbcPushBroker(h2, jdbc, null);
            jdbcBroker.start();
            broker = jdbcBroker;
        } else {
            broker = new MemoryPushBroker();
            store = new MemoryPushStore();
        }
        PushProperties properties = new PushProperties();
        PushProperties.Namespace ns = new PushProperties.Namespace();
        ns.setQos(Qos.AT_LEAST_ONCE);
        ns.setHistorySize(100);
        properties.getNamespaces().put("bench", ns);
        // The JDBC broker reads rows the store wrote, so its publisher must write to the real store.
        publisher = new PushPublisher(
                properties,
                jdbcStore != null ? jdbcStore : new MemoryPushStore(),
                broker,
                new PushMetrics(new SimpleMeterRegistry()));
        for (int i = 0; i < subscribers; i++) {
            engineSubscriptions.add(broker.subscribe(TOPIC).subscribe(e -> engineDelivered.incrementAndGet()));
        }
        if (broker instanceof dev.appiaworks.shiguchi.cursus.postgres.PostgresPushBroker pgBroker) {
            pgBroker.listening().block(Duration.ofSeconds(30));
        }

        // HTTP leg: the gateway with only push on, plus the native gRPC port for the gRPC leg.
        int grpcPort;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            grpcPort = socket.getLocalPort();
        }
        java.util.List<String> props = new java.util.ArrayList<>(java.util.List.of(
                "server.port=0",
                "spring.main.banner-mode=off",
                "logging.level.root=WARN",
                "shiguchi.ui.enabled=false",
                "shiguchi.graphql.enabled=false",
                "shiguchi.openapi.enabled=false",
                "shiguchi.cursus.enabled=true",
                "shiguchi.cursus.namespaces.bench.qos=AT_LEAST_ONCE",
                "shiguchi.cursus.namespaces.bench.history-size=100",
                "shiguchi.cursus.namespaces.bench.publish-auth=NONE",
                "shiguchi.cursus.connection.max-lifetime=0",
                "shiguchi.cursus.connection.keepalive=0",
                "shiguchi.grpc.enabled=true",
                "shiguchi.grpc.port=" + grpcPort));
        if ("true".equals(archive)) props.add("shiguchi.cursus.archive.retention=1h");
        if ("REDIS".equals(engine)) {
            props.addAll(java.util.List.of(
                    "shiguchi.cursus.broker=REDIS",
                    "shiguchi.cursus.store=REDIS",
                    "shiguchi.cursus.redis.key-prefix=bench:push:gw:",
                    "spring.data.redis.host=localhost",
                    "spring.data.redis.port=" + redisPort));
        } else if ("JDBC".equals(engine)) {
            props.addAll(java.util.List.of(
                    "shiguchi.cursus.broker=JDBC",
                    "shiguchi.cursus.store=JDBC",
                    "shiguchi.cursus.jdbc.table=bench_push_event_gw",
                    "shiguchi.cursus.jdbc.poll-interval=50ms",
                    "bench.jdbc.url=jdbc:h2:mem:bench_push_gw;DB_CLOSE_DELAY=-1"));
        } else if ("POSTGRES".equals(engine)) {
            props.addAll(java.util.List.of(
                    "shiguchi.cursus.broker=POSTGRES",
                    "shiguchi.cursus.store=POSTGRES",
                    "shiguchi.cursus.postgres.table=bench_push_event_gw",
                    "shiguchi.cursus.postgres.channel=bench_push_gw",
                    "spring.r2dbc.url=r2dbc:postgresql://localhost:" + postgres.getPort() + "/postgres",
                    "spring.r2dbc.username=postgres",
                    "spring.r2dbc.password=postgres"));
        }
        // As command-line arguments: builder properties are defaults and lose to the JMH classpath's
        // application.properties (whose placeholder R2DBC URL keeps the other benchmarks' contexts starting).
        gateway = new SpringApplicationBuilder(GatewayApp.class)
                .web(WebApplicationType.REACTIVE)
                .run(props.stream().map(p -> "--" + p).toArray(String[]::new));
        int port = Integer.parseInt(gateway.getEnvironment().getRequiredProperty("local.server.port"));
        // The default pool holds 500 connections per host; the subscribers are long-lived streams,
        // so they get their own unbounded pool and the publisher its own small one.
        var subscriberPool = reactor.netty.resources.ConnectionProvider.builder("push-subscribers")
                .maxConnections(Math.max(subscribers, 16) + 16)
                .pendingAcquireMaxCount(-1)
                .build();
        WebClient subscriberClient = WebClient.builder()
                .baseUrl("http://localhost:" + port)
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(
                        reactor.netty.http.client.HttpClient.create(subscriberPool)))
                .build();
        client = WebClient.create("http://localhost:" + port);
        publishBody = "{\"topic\":\"bench:topic\",\"data\":" + new String(PAYLOAD, StandardCharsets.UTF_8) + "}";
        PushMetrics metrics = gateway.getBean(PushMetrics.class);
        // Connected in batches: a thousand simultaneous connects overflow the listen backlog, which
        // Windows answers with "connection refused" rather than a SYN drop.
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        for (int i = 0; i < subscribers; i++) {
            final boolean first = i == 0;
            httpSubscriptions.add(subscriberClient
                    .get()
                    .uri("/push/subscribe?topic=bench:topic")
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .retrieve()
                    .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                    .subscribe(e -> {
                        if (first && e.event() != null) {
                            CountDownLatch latch = delivered;
                            if (latch != null) latch.countDown();
                        }
                    }));
            if ((i + 1) % 50 == 0 || i + 1 == subscribers) {
                while (metrics.connections() < i + 1) {
                    if (System.nanoTime() > deadline) throw new IllegalStateException("subscribers did not connect");
                    Thread.sleep(5);
                }
            }
        }
        if (!"MEMORY".equals(engine)) Thread.sleep(500);
        // The same number of WebSocket subscribers on a second topic, so the two transports are
        // measured on the same gateway without feeding each other's subscribers.
        java.net.URI wsUri = java.net.URI.create("ws://localhost:" + port + "/push/ws");
        // Its own pool too: the default one is capped at 500 connections and shared with the publisher.
        var wsPool = reactor.netty.resources.ConnectionProvider.builder("push-ws-subscribers")
                .maxConnections(Math.max(subscribers, 16) + 16)
                .pendingAcquireMaxCount(-1)
                .build();
        var wsClient = new org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient(
                reactor.netty.http.client.HttpClient.create(wsPool));
        java.util.concurrent.atomic.AtomicInteger wsSubscribed = new java.util.concurrent.atomic.AtomicInteger();
        for (int i = 0; i < subscribers; i++) {
            final boolean first = i == 0;
            wsSubscriptions.add(wsClient.execute(wsUri, session -> {
                        var send = session.send(reactor.core.publisher.Mono.just(
                                session.textMessage("{\"type\":\"sub\",\"topics\":[\"bench:ws\"]}")));
                        return send.then(session.receive()
                                .map(org.springframework.web.reactive.socket.WebSocketMessage::getPayloadAsText)
                                .doOnNext(t -> {
                                    if (t.contains("\"subscribed\"")) wsSubscribed.incrementAndGet();
                                    if (first && t.startsWith("{\"type\":\"event\"")) {
                                        CountDownLatch latch = wsDelivered;
                                        if (latch != null) latch.countDown();
                                    }
                                })
                                .then());
                    })
                    .subscribe());
            if ((i + 1) % 50 == 0 || i + 1 == subscribers) {
                while (wsSubscribed.get() < i + 1) {
                    if (System.nanoTime() > deadline)
                        throw new IllegalStateException("ws subscribers did not subscribe");
                    Thread.sleep(5);
                }
            }
        }
        wsPublishBody = "{\"topic\":\"bench:ws\",\"data\":" + new String(PAYLOAD, StandardCharsets.UTF_8) + "}";
        v1PublishBody = "{\"topics\":[\"bench:topic\"],\"json\":" + new String(PAYLOAD, StandardCharsets.UTF_8) + "}";
        // The same number of gRPC subscribers on a third topic, through the native gRPC port: the
        // proxy's handler registry, the in-process channel, the hosted PushService.
        grpcChannel = io.grpc.ManagedChannelBuilder.forAddress("localhost", grpcPort)
                .usePlaintext()
                .build();
        grpcStub = dev.appiaworks.shiguchi.proto.cursus.PushServiceGrpc.newBlockingStub(grpcChannel);
        // One connection per 100 streams (gRPC's default concurrent-streams ceiling is in that
        // range), as a fleet of clients would present rather than one process multiplexing them all.
        int channels = Math.max(1, subscribers / 100);
        for (int i = 0; i < channels; i++) {
            grpcSubscriberChannels.add(io.grpc.ManagedChannelBuilder.forAddress("localhost", grpcPort)
                    .usePlaintext()
                    .build());
        }
        var subscribeRequest = dev.appiaworks.shiguchi.proto.cursus.SubscribeRequest.newBuilder()
                .addTopics("bench:grpc")
                .build();
        int grpcBefore = metrics.connections("grpc");
        for (int i = 0; i < subscribers; i++) {
            final boolean first = i == 0;
            var grpcAsync = dev.appiaworks.shiguchi.proto.cursus.PushServiceGrpc.newStub(
                    grpcSubscriberChannels.get(i % channels));
            io.grpc.Context.CancellableContext ctx = io.grpc.Context.current().withCancellation();
            grpcSubscriptions.add(ctx);
            ctx.run(() -> grpcAsync.subscribe(subscribeRequest, new io.grpc.stub.StreamObserver<>() {
                @Override
                public void onNext(dev.appiaworks.shiguchi.proto.cursus.PushEvent value) {
                    if (first) {
                        CountDownLatch latch = grpcDelivered;
                        if (latch != null) latch.countDown();
                    }
                }

                @Override
                public void onError(Throwable t) {}

                @Override
                public void onCompleted() {}
            }));
            if ((i + 1) % 50 == 0 || i + 1 == subscribers) {
                while (metrics.connections("grpc") < grpcBefore + i + 1) {
                    if (System.nanoTime() > deadline)
                        throw new IllegalStateException("grpc subscribers did not connect");
                    Thread.sleep(5);
                }
            }
        }
        grpcPublish = dev.appiaworks.shiguchi.proto.cursus.PublishRequest.newBuilder()
                .addTopics("bench:grpc")
                .setJson(com.google.protobuf.Value.newBuilder()
                        .setStructValue(com.google.protobuf.Struct.newBuilder()
                                .putFields(
                                        "orderId",
                                        com.google.protobuf.Value.newBuilder()
                                                .setStringValue("order-1")
                                                .build())
                                .putFields(
                                        "status",
                                        com.google.protobuf.Value.newBuilder()
                                                .setStringValue("SHIPPED")
                                                .build())
                                .putFields(
                                        "items",
                                        com.google.protobuf.Value.newBuilder()
                                                .setNumberValue(3)
                                                .build())))
                .build();
        if (!"MEMORY".equals(engine)) Thread.sleep(500);
        ws_publish_delivery();
        grpc_publish_delivery();
        httpv1_publish_delivery();
        // Warm the publish path once and verify delivery.
        http_publish_delivery();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        grpcSubscriptions.forEach(c -> c.cancel(null));
        grpcSubscriberChannels.forEach(io.grpc.ManagedChannel::shutdownNow);
        if (grpcChannel != null) grpcChannel.shutdownNow();
        wsSubscriptions.forEach(Disposable::dispose);
        httpSubscriptions.forEach(Disposable::dispose);
        engineSubscriptions.forEach(Disposable::dispose);
        if (gateway != null) gateway.close();
        if (broker instanceof dev.appiaworks.shiguchi.cursus.redis.RedisPushBroker r) r.destroy();
        if (broker instanceof dev.appiaworks.shiguchi.cursus.postgres.PostgresPushBroker p) p.destroy();
        if (broker instanceof dev.appiaworks.shiguchi.cursus.jdbc.JdbcPushBroker j) j.destroy();
        if (jdbcStore != null) jdbcStore.destroy();
        if (postgresStore != null) postgresStore.destroy();
        if (postgres != null) {
            try {
                postgres.close();
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
        if (redisConnections != null) redisConnections.destroy();
        if (redisServer != null) {
            try {
                redisServer.stop();
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Benchmark
    public void engine_fanout() {
        PushEvent event = new PushEvent(TOPIC, "1", PAYLOAD, Map.of(), null, null);
        if (jdbcStore != null) {
            // The JDBC broker fans out the rows the store wrote: the write is part of the path.
            jdbcStore.append(event, 100, Duration.ZERO).flatMap(broker::publish).block();
            return;
        }
        broker.publish(event).block();
    }

    @Benchmark
    public PushEvent engine_publish() {
        return publisher.publish(TOPIC, PAYLOAD, Map.of(), null).block();
    }

    @Benchmark
    public void ws_publish_delivery() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        wsDelivered = latch;
        client.post()
                .uri("/push/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(wsPublishBody)
                .retrieve()
                .toBodilessEntity()
                .block();
        if (!latch.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("event not delivered over ws");
    }

    @Benchmark
    public void grpc_publish_delivery() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        grpcDelivered = latch;
        grpcStub.publish(grpcPublish);
        if (!latch.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("event not delivered over grpc");
    }

    /** {@code POST /push/v1/publish}: the same event through the gateway's transcoding into the hosted PushService. */
    @Benchmark
    public void httpv1_publish_delivery() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        delivered = latch;
        client.post()
                .uri("/push/v1/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(v1PublishBody)
                .retrieve()
                .toBodilessEntity()
                .block();
        if (!latch.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("event not delivered via v1");
    }

    @Benchmark
    public void http_publish_delivery() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        delivered = latch;
        client.post()
                .uri("/push/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(publishBody)
                .retrieve()
                .toBodilessEntity()
                .block();
        if (!latch.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("event not delivered");
    }
}
