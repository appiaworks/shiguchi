package dev.appiaworks.shiguchi.cursus.starter;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.cursus.PushMetrics;
import dev.appiaworks.shiguchi.cursus.PushStore;
import dev.appiaworks.shiguchi.cursus.Topic;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import redis.embedded.RedisServer;

/**
 * The REDIS engine on the Boot 4 gateway (mirrors {@code PushRedisEndToEndTest} in
 * spring-boot3/shiguchi-cursus-starter; the push module is byte-identical between the lines): two gateway instances over one embedded Redis. An event
 * published through one instance reaches a subscriber on the other; history written by one is
 * replayed by the other on {@code Last-Event-ID}; and an instance that shuts down ends its
 * streams with {@code event: close} / {@code shutdown}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.graphql.enabled=false",
            "shiguchi.cursus.enabled=true",
            "shiguchi.cursus.broker=REDIS",
            "shiguchi.cursus.store=REDIS",
            "shiguchi.cursus.redis.key-prefix=e2e:push:",
            "shiguchi.cursus.namespaces.ticker.publish-auth=NONE",
            "shiguchi.cursus.namespaces.orders.qos=AT_LEAST_ONCE",
            "shiguchi.cursus.namespaces.orders.history-size=100",
            "shiguchi.cursus.namespaces.orders.publish-auth=NONE",
            "shiguchi.cursus.connection.keepalive=200ms",
            "shiguchi.cursus.connection.max-lifetime=0"
        })
@DisplayName("Push over Redis, two instances, end to end (Boot 4)")
class PushRedisEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    /**
     * The extra instances get event loops of their own: Boot's default {@code ReactorResourceFactory}
     * uses reactor-netty's global resources and disposes them when its context closes, which would
     * take the other instances' servers down with it. Not a {@code @Configuration}: the gateway's
     * package scan would register it in every context, this test class's included.
     */
    static class OwnResources {
        @org.springframework.context.annotation.Bean
        org.springframework.http.client.ReactorResourceFactory reactorResourceFactory() {
            var factory = new org.springframework.http.client.ReactorResourceFactory();
            factory.setUseGlobalResources(false);
            return factory;
        }
    }

    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE =
            new ParameterizedTypeReference<>() {};

    private static RedisServer redis;
    private static int redisPort;
    private static ConfigurableApplicationContext second;
    private static WebClient secondClient;

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> redisPort);
    }

    @BeforeAll
    static void startRedisAndSecondInstance() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            redisPort = socket.getLocalPort();
        }
        redis = RedisServer.newRedisServer().port(redisPort).build();
        redis.start();
        second = instance();
        secondClient = WebClient.create("http://localhost:" + port(second));
    }

    @AfterAll
    static void stop() throws IOException {
        if (second != null) second.close();
        if (redis != null) redis.stop();
    }

    /** Another gateway instance on the same Redis, booted the way an application would be. */
    private static ConfigurableApplicationContext instance() {
        return new SpringApplicationBuilder(TestApp.class, OwnResources.class)
                .web(WebApplicationType.REACTIVE)
                .properties(
                        "server.port=0",
                        "spring.main.banner-mode=off",
                        "spring.data.redis.host=localhost",
                        "spring.data.redis.port=" + redisPort,
                        "shiguchi.ui.enabled=false",
                        "shiguchi.openapi.enabled=false",
                        "shiguchi.graphql.enabled=false",
                        "shiguchi.cursus.enabled=true",
                        "shiguchi.cursus.broker=REDIS",
                        "shiguchi.cursus.store=REDIS",
                        "shiguchi.cursus.redis.key-prefix=e2e:push:",
                        "shiguchi.cursus.namespaces.ticker.publish-auth=NONE",
                        "shiguchi.cursus.namespaces.orders.qos=AT_LEAST_ONCE",
                        "shiguchi.cursus.namespaces.orders.history-size=100",
                        "shiguchi.cursus.namespaces.orders.publish-auth=NONE",
                        "shiguchi.cursus.connection.keepalive=200ms",
                        "shiguchi.cursus.connection.max-lifetime=0")
                .run();
    }

    private static int port(ConfigurableApplicationContext ctx) {
        return Integer.parseInt(ctx.getEnvironment().getRequiredProperty("local.server.port"));
    }

    @Autowired
    private Environment environment;

    @Autowired
    private PushMetrics metrics;

    @Autowired
    private PushStore store;

    private WebClient client;

    @BeforeEach
    void client() {
        client = WebClient.create("http://localhost:" + environment.getRequiredProperty("local.server.port"));
    }

    @Test
    @DisplayName("GIVEN a subscriber on instance A WHEN instance B publishes SHOULD deliver through Redis in order")
    void given_subscriberOnA_when_bPublishes_should_deliverAcrossInstances() {
        try (Subscriber sub = subscribe(client, metrics, "ticker:btc")) {
            awaitRedisSubscription();
            for (int i = 1; i <= 3; i++) {
                assertThat(publish(secondClient, "ticker:btc", "{\"price\":" + i + "}"))
                        .isEqualTo(200);
            }
            List<ServerSentEvent<String>> events = sub.awaitEvents(3);
            assertThat(events)
                    .extracting(ServerSentEvent::data)
                    .containsExactly("{\"price\":1}", "{\"price\":2}", "{\"price\":3}");
            assertThat(events).extracting(ServerSentEvent::id).allMatch(id -> id.contains("-"));
            assertThat(events).extracting(ServerSentEvent::event).containsOnly("ticker:btc");
        }
    }

    @Test
    @DisplayName(
            "GIVEN history written through instance B WHEN a subscriber resumes on instance A SHOULD replay from the shared stream then stream live")
    void given_historyOnB_when_resumedOnA_should_replayThenLive() {
        String first = publishedId(secondClient, "orders:7", "{\"n\":1}");
        publish(secondClient, "orders:7", "{\"n\":2}");
        publish(secondClient, "orders:7", "{\"n\":3}");
        assertThat(store.historyLength(Topic.parse("orders:7"))).isEqualTo(3);

        try (Subscriber sub = resume(client, metrics, first, "orders:7")) {
            List<ServerSentEvent<String>> replayed = sub.awaitEvents(2);
            assertThat(replayed).extracting(ServerSentEvent::data).containsExactly("{\"n\":2}", "{\"n\":3}");
            awaitRedisSubscription();
            publish(secondClient, "orders:7", "{\"n\":4}");
            assertThat(sub.awaitEvents(3).get(2).data()).isEqualTo("{\"n\":4}");
        }
    }

    @Test
    @DisplayName(
            "GIVEN an open subscription WHEN its instance shuts down SHOULD end the stream with a close event naming shutdown")
    void given_openSubscription_when_instanceStops_should_closeWithShutdownReason() {
        ConfigurableApplicationContext third = instance();
        WebClient thirdClient = WebClient.create("http://localhost:" + port(third));
        PushMetrics thirdMetrics = third.getBean(PushMetrics.class);
        List<ServerSentEvent<String>> frames = new CopyOnWriteArrayList<>();
        Disposable stream = thirdClient
                .get()
                .uri("/push/subscribe?topic=ticker:bye")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(SSE)
                .filter(e -> e.event() != null)
                .subscribe(frames::add);
        awaitTrue(() -> thirdMetrics.connections() == 1);

        third.close();

        awaitTrue(() -> !frames.isEmpty());
        assertThat(frames.get(frames.size() - 1).event()).isEqualTo("close");
        assertThat(frames.get(frames.size() - 1).data()).isEqualTo("{\"reason\":\"shutdown\"}");
        stream.dispose();
    }

    // ---- helpers ----

    /** A Redis subscription is registered a moment after the SSE connection is counted. */
    private static void awaitRedisSubscription() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int publish(WebClient via, String topic, String dataJson) {
        return via.post()
                .uri("/push/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"topic\":\"" + topic + "\",\"data\":" + dataJson + "}")
                .exchangeToMono(r -> Mono.just(r.statusCode().value()))
                .block(Duration.ofSeconds(30));
    }

    private static String publishedId(WebClient via, String topic, String dataJson) {
        String body = via.post()
                .uri("/push/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"topic\":\"" + topic + "\",\"data\":" + dataJson + "}")
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(30));
        int at = body.indexOf("\"id\":\"") + 6;
        return body.substring(at, body.indexOf('"', at));
    }

    private static Subscriber subscribe(WebClient via, PushMetrics metrics, String... topics) {
        return resume(via, metrics, null, topics);
    }

    private static Subscriber resume(WebClient via, PushMetrics metrics, String lastEventId, String... topics) {
        int before = metrics.connections();
        StringBuilder uri = new StringBuilder("/push/subscribe?");
        for (String t : topics) uri.append("topic=").append(t).append('&');
        Flux<ServerSentEvent<String>> stream = via.get()
                .uri(uri.toString())
                .headers(h -> {
                    if (lastEventId != null) h.add("Last-Event-ID", lastEventId);
                })
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(SSE);
        Subscriber sub = new Subscriber(stream);
        awaitTrue(() -> metrics.connections() == before + 1);
        return sub;
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition not met in 30s");
            Thread.onSpinWait();
        }
    }

    private static final class Subscriber implements AutoCloseable {
        private final List<ServerSentEvent<String>> events = new CopyOnWriteArrayList<>();
        private final Disposable connection;

        Subscriber(Flux<ServerSentEvent<String>> stream) {
            connection = stream.filter(e -> e.event() != null && !"close".equals(e.event()))
                    .subscribe(events::add);
        }

        List<ServerSentEvent<String>> awaitEvents(int count) {
            awaitTrue(() -> events.size() >= count);
            return List.copyOf(events);
        }

        @Override
        public void close() {
            connection.dispose();
        }
    }
}
