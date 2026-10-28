package dev.appiaworks.shiguchi.cursus.starter;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.cursus.PushMetrics;
import dev.appiaworks.shiguchi.cursus.PushStore;
import dev.appiaworks.shiguchi.cursus.Topic;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
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

/**
 * The POSTGRES engine on the Boot 4 gateway: two gateway instances over one embedded Postgres,
 * connected through {@code spring.r2dbc.url} like an application would be. An event published
 * through one instance reaches a subscriber on the other over {@code LISTEN/NOTIFY}; history
 * written by one is replayed by the other on {@code Last-Event-ID}; an event larger than a
 * notification allows travels by id and is read from the table; the schema is created at startup;
 * an instance that shuts down ends its streams with {@code event: close} / {@code shutdown}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.graphql.enabled=false",
            "shiguchi.cursus.enabled=true",
            "shiguchi.cursus.broker=POSTGRES",
            "shiguchi.cursus.store=POSTGRES",
            "shiguchi.cursus.postgres.table=e2e_push_event",
            "shiguchi.cursus.postgres.channel=e2e_push",
            "shiguchi.cursus.postgres.notify-max-bytes=512",
            "shiguchi.cursus.namespaces.ticker.publish-auth=NONE",
            "shiguchi.cursus.namespaces.orders.qos=AT_LEAST_ONCE",
            "shiguchi.cursus.namespaces.orders.history-size=100",
            "shiguchi.cursus.namespaces.orders.publish-auth=NONE",
            "shiguchi.cursus.archive.retention=1h",
            "shiguchi.cursus.admin.role=PUSH_ADMIN",
            "shiguchi.cursus.admin.payload-visibility=FULL",
            "management.endpoints.web.exposure.include=gateway-push,health",
            "shiguchi.cursus.connection.keepalive=200ms",
            "shiguchi.cursus.connection.max-lifetime=0"
        })
@DisplayName("Push over Postgres, two instances, end to end (Boot 4)")
class PushPostgresEndToEndTest {

    static final String ADMIN_TOKEN = "admin-token";

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {
        /** One fixed token stands in for an issuer, so the archive writes on either instance can be made. */
        @org.springframework.context.annotation.Bean
        org.springframework.security.oauth2.jwt.ReactiveJwtDecoder fixedTokens() {
            return token -> ADMIN_TOKEN.equals(token)
                    ? Mono.just(org.springframework.security.oauth2.jwt.Jwt.withTokenValue(token)
                            .header("alg", "none")
                            .subject("ops")
                            .claim("scope", "PUSH_ADMIN")
                            .issuedAt(java.time.Instant.now())
                            .expiresAt(java.time.Instant.now().plusSeconds(3600))
                            .build())
                    : Mono.error(new org.springframework.security.oauth2.jwt.BadJwtException("unknown token"));
        }
    }

    /** See {@code PushRedisEndToEndTest.OwnResources}: the extra instances get event loops of their own. */
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

    private static EmbeddedPostgres postgres;
    private static String r2dbcUrl;
    private static ConfigurableApplicationContext second;
    private static WebClient secondClient;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> r2dbcUrl);
        registry.add("spring.r2dbc.username", () -> "postgres");
        registry.add("spring.r2dbc.password", () -> "postgres");
    }

    @BeforeAll
    static void startPostgresAndSecondInstance() throws IOException {
        postgres = EmbeddedPostgres.start();
        r2dbcUrl = "r2dbc:postgresql://localhost:" + postgres.getPort() + "/postgres";
        second = instance();
        secondClient = WebClient.create("http://localhost:" + port(second));
    }

    @AfterAll
    static void stop() throws IOException {
        if (second != null) second.close();
        if (postgres != null) postgres.close();
    }

    /** Another gateway instance on the same database, booted the way an application would be. */
    private static ConfigurableApplicationContext instance() {
        return new SpringApplicationBuilder(TestApp.class, OwnResources.class)
                .web(WebApplicationType.REACTIVE)
                .properties(
                        "server.port=0",
                        "spring.main.banner-mode=off",
                        "shiguchi.ui.enabled=false",
                        "shiguchi.openapi.enabled=false",
                        "shiguchi.graphql.enabled=false",
                        "shiguchi.cursus.enabled=true",
                        "shiguchi.cursus.broker=POSTGRES",
                        "shiguchi.cursus.store=POSTGRES",
                        "shiguchi.cursus.postgres.table=e2e_push_event",
                        "shiguchi.cursus.postgres.channel=e2e_push",
                        "shiguchi.cursus.postgres.notify-max-bytes=512",
                        "shiguchi.cursus.namespaces.ticker.publish-auth=NONE",
                        "shiguchi.cursus.namespaces.orders.qos=AT_LEAST_ONCE",
                        "shiguchi.cursus.namespaces.orders.history-size=100",
                        "shiguchi.cursus.namespaces.orders.publish-auth=NONE",
                        "shiguchi.cursus.archive.retention=1h",
                        "shiguchi.cursus.admin.role=PUSH_ADMIN",
                        "shiguchi.cursus.admin.payload-visibility=FULL",
                        "management.endpoints.web.exposure.include=gateway-push,health",
                        "shiguchi.cursus.connection.keepalive=200ms",
                        "shiguchi.cursus.connection.max-lifetime=0")
                // As command-line arguments, which outrank the test classpath's application.properties
                // (its unused URL keeps the other end-to-end tests' contexts starting); builder
                // properties would be defaults and lose to it.
                .run(
                        "--spring.r2dbc.url=" + r2dbcUrl,
                        "--spring.r2dbc.username=postgres",
                        "--spring.r2dbc.password=postgres");
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
    @DisplayName(
            "GIVEN a subscriber on instance A WHEN instance B publishes SHOULD deliver through LISTEN/NOTIFY in order")
    void given_subscriberOnA_when_bPublishes_should_deliverAcrossInstances() {
        try (Subscriber sub = subscribe(client, metrics, "ticker:btc")) {
            awaitListener();
            for (int i = 1; i <= 3; i++) {
                assertThat(publish(secondClient, "ticker:btc", "{\"price\":" + i + "}"))
                        .isEqualTo(200);
            }
            List<ServerSentEvent<String>> events = sub.awaitEvents(3);
            assertThat(events)
                    .extracting(ServerSentEvent::data)
                    .containsExactly("{\"price\":1}", "{\"price\":2}", "{\"price\":3}");
            // ticker keeps no history, but the archive is on: every event gets a row and its table id.
            assertThat(events).extracting(ServerSentEvent::id).allMatch(id -> id.matches("\\d+"));
            assertThat(events).extracting(ServerSentEvent::event).containsOnly("ticker:btc");
        }
    }

    @Test
    @DisplayName(
            "GIVEN history written through instance B WHEN a subscriber resumes on instance A SHOULD replay from the table then stream live")
    void given_historyOnB_when_resumedOnA_should_replayThenLive() {
        String first = publishedId(secondClient, "orders:7", "{\"n\":1}");
        publish(secondClient, "orders:7", "{\"n\":2}");
        publish(secondClient, "orders:7", "{\"n\":3}");
        assertThat(store.historyLength(Topic.parse("orders:7"))).isEqualTo(3);

        try (Subscriber sub = resume(client, metrics, first, "orders:7")) {
            List<ServerSentEvent<String>> replayed = sub.awaitEvents(2);
            assertThat(replayed).extracting(ServerSentEvent::data).containsExactly("{\"n\":2}", "{\"n\":3}");
            awaitListener();
            publish(secondClient, "orders:7", "{\"n\":4}");
            assertThat(sub.awaitEvents(3).get(2).data()).isEqualTo("{\"n\":4}");
        }
    }

    @Test
    @DisplayName(
            "GIVEN an event larger than a notification carries WHEN published on a topic with history SHOULD arrive whole, read from the table by id")
    void given_largeEvent_when_published_should_arriveByReference() {
        String big = "{\"blob\":\"" + "x".repeat(1500) + "\"}";
        try (Subscriber sub = subscribe(client, metrics, "orders:big")) {
            awaitListener();
            assertThat(publish(secondClient, "orders:big", big)).isEqualTo(200);
            assertThat(sub.awaitEvents(1).get(0).data()).isEqualTo(big);
        }
        // Without an archive this is refused (413: no history, no row to read it from); with the
        // archive on every event has a row, so it travels by id like the one above.
        assertThat(publish(secondClient, "ticker:big", big)).isEqualTo(200);
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

    /** The LISTEN is issued a moment after the first subscription is counted. */
    private static void awaitListener() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName(
            "GIVEN an event published through instance B WHEN instance A searches, B republishes and A tombstones SHOULD share one archive: the search sees it, A's subscriber gets B's copy, B sees A's tombstone")
    void given_eventOnB_when_archiveUsedFromBothInstances_should_shareOneArchive() {
        String id = publishedId(secondClient, "orders:shared", "{\"n\":1}");

        com.fasterxml.jackson.databind.JsonNode found = adminGet(client, "/messages?topic=orders:shared");
        assertThat(found.get("messages")).extracting(m -> m.get("id").asText()).containsExactly(id);
        assertThat(found.get("messages").get(0).get("data").asText()).isEqualTo("{\"n\":1}");

        try (Subscriber sub = subscribe(client, metrics, "orders:shared")) {
            awaitListener();
            com.fasterxml.jackson.databind.JsonNode sent = adminPost(
                    secondClient,
                    "/messages/" + id + "/republish",
                    "{\"topic\":\"orders:shared\",\"reason\":\"from B\"}");
            String copy = sent.get("id").asText();
            List<ServerSentEvent<String>> events = sub.awaitEvents(1);
            assertThat(events.get(0).id()).isEqualTo(copy);
            assertThat(events.get(0).data()).isEqualTo("{\"n\":1}");
            com.fasterxml.jackson.databind.JsonNode attrs = adminGet(
                            client, "/messages/" + copy + "?topic=orders:shared")
                    .get("attributes");
            assertThat(attrs.get("origin_id").asText()).isEqualTo(id);
            assertThat(attrs.get("republished_by").asText()).isEqualTo("ops");
        }

        assertThat(adminStatus(client, "DELETE", "/messages/" + id + "?topic=orders:shared"))
                .isEqualTo(200);
        com.fasterxml.jackson.databind.JsonNode viaB =
                adminGet(secondClient, "/messages/" + id + "?topic=orders:shared");
        assertThat(viaB.get("tombstoned").asBoolean()).isTrue();
        assertThat(viaB.get("tombstonedBy").asText()).isEqualTo("ops");
        assertThat(viaB.get("data").asText()).isEqualTo("null");
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper ADMIN_JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static com.fasterxml.jackson.databind.JsonNode adminGet(WebClient via, String path) {
        return adminJson(via.get()
                .uri("/actuator/gateway-push" + path)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(30)));
    }

    private static com.fasterxml.jackson.databind.JsonNode adminPost(WebClient via, String path, String json) {
        return adminJson(via.post()
                .uri("/actuator/gateway-push" + path)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .bodyValue(json)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(30)));
    }

    private static int adminStatus(WebClient via, String method, String path) {
        return via.method(org.springframework.http.HttpMethod.valueOf(method))
                .uri("/actuator/gateway-push" + path)
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .exchangeToMono(r -> Mono.just(r.statusCode().value()))
                .block(Duration.ofSeconds(30));
    }

    private static com.fasterxml.jackson.databind.JsonNode adminJson(String json) {
        try {
            return ADMIN_JSON.readTree(json);
        } catch (java.io.IOException e) {
            throw new AssertionError(json, e);
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
