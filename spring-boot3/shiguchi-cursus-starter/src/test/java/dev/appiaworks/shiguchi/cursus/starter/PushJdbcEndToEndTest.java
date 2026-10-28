package dev.appiaworks.shiguchi.cursus.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.appiaworks.shiguchi.cursus.PushMetrics;
import dev.appiaworks.shiguchi.cursus.PushStore;
import dev.appiaworks.shiguchi.cursus.Topic;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.context.annotation.Bean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The JDBC engine on the Boot 3 gateway: two gateway instances over one in-memory H2 database
 * (no Docker, no server), each with a {@code DataSource} bean, with {@code broker: JDBC} and {@code store: JDBC}. An event published through one
 * instance reaches a subscriber on the other within the poll interval; history written by one is
 * replayed by the other on {@code Last-Event-ID}; a persistent session resumes from its acked
 * cursor; a consumer-group member acks its claim over HTTP; the archive is searched, tombstoned
 * and exported.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.graphql.enabled=false",
            "shiguchi.cursus.enabled=true",
            "shiguchi.cursus.broker=JDBC",
            "shiguchi.cursus.store=JDBC",
            "shiguchi.cursus.jdbc.table=e2e_push_event",
            "shiguchi.cursus.jdbc.poll-interval=100ms",
            "shiguchi.cursus.namespaces.ticker.publish-auth=NONE",
            "shiguchi.cursus.namespaces.orders.qos=AT_LEAST_ONCE",
            "shiguchi.cursus.namespaces.orders.history-size=100",
            "shiguchi.cursus.namespaces.orders.publish-auth=NONE",
            "shiguchi.cursus.namespaces.inbox.qos=AT_LEAST_ONCE",
            "shiguchi.cursus.namespaces.inbox.history-size=100",
            "shiguchi.cursus.namespaces.inbox.session-expiry=1h",
            "shiguchi.cursus.namespaces.inbox.auth=AUTHENTICATED",
            "shiguchi.cursus.namespaces.inbox.publish-auth=NONE",
            "shiguchi.cursus.namespaces.jobs.qos=AT_LEAST_ONCE",
            "shiguchi.cursus.namespaces.jobs.history-size=100",
            "shiguchi.cursus.namespaces.jobs.session-expiry=1h",
            "shiguchi.cursus.namespaces.jobs.session-key=GROUP",
            "shiguchi.cursus.namespaces.jobs.ack-deadline=5s",
            "shiguchi.cursus.namespaces.jobs.publish-auth=NONE",
            "shiguchi.cursus.archive.retention=1h",
            "shiguchi.cursus.admin.role=PUSH_ADMIN",
            "shiguchi.cursus.admin.payload-visibility=FULL",
            "management.endpoints.web.exposure.include=gateway-push,health",
            "shiguchi.cursus.connection.keepalive=200ms",
            "shiguchi.cursus.connection.max-lifetime=0"
        })
@DisplayName("Push over JDBC (H2), two instances, end to end (Boot 3)")
class PushJdbcEndToEndTest {

    static final String URL = "jdbc:h2:mem:push;DB_CLOSE_DELAY=-1";
    static final String ADMIN_TOKEN = "admin-token";
    static final String ALICE_TOKEN = "alice-token";

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {
        /**
         * Boot's DataSource auto-configuration backs off when an R2DBC ConnectionFactory is
         * present (this module's test classpath has one for the Postgres test), so the database
         * is a bean here, as it would be in an application that has both.
         */
        @Bean
        javax.sql.DataSource dataSource() {
            org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
            ds.setURL(URL);
            return ds;
        }

        /** Fixed tokens stand in for an issuer, so admin writes and a principal's session can be made. */
        @Bean
        ReactiveJwtDecoder fixedTokens() {
            return token -> switch (token) {
                case ADMIN_TOKEN -> Mono.just(jwt("ops", "PUSH_ADMIN"));
                case ALICE_TOKEN -> Mono.just(jwt("alice", "read"));
                default -> Mono.error(new BadJwtException("unknown token"));
            };
        }

        private static Jwt jwt(String subject, String scope) {
            return Jwt.withTokenValue(subject)
                    .header("alg", "none")
                    .subject(subject)
                    .claim("scope", scope)
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .build();
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE =
            new ParameterizedTypeReference<>() {};
    private static final String ADMIN = "/actuator/gateway-push";

    private static ConfigurableApplicationContext second;
    private static WebClient secondClient;

    @BeforeAll
    static void startSecondInstance() {
        second = new SpringApplicationBuilder(TestApp.class)
                .web(WebApplicationType.REACTIVE)
                .properties(
                        "server.port=0",
                        "spring.main.banner-mode=off",
                        "shiguchi.ui.enabled=false",
                        "shiguchi.openapi.enabled=false",
                        "shiguchi.graphql.enabled=false",
                        "shiguchi.cursus.enabled=true",
                        "shiguchi.cursus.broker=JDBC",
                        "shiguchi.cursus.store=JDBC",
                        "shiguchi.cursus.jdbc.table=e2e_push_event",
                        "shiguchi.cursus.jdbc.poll-interval=100ms",
                        "shiguchi.cursus.namespaces.ticker.publish-auth=NONE",
                        "shiguchi.cursus.namespaces.orders.qos=AT_LEAST_ONCE",
                        "shiguchi.cursus.namespaces.orders.history-size=100",
                        "shiguchi.cursus.namespaces.orders.publish-auth=NONE",
                        "shiguchi.cursus.namespaces.inbox.qos=AT_LEAST_ONCE",
                        "shiguchi.cursus.namespaces.inbox.history-size=100",
                        "shiguchi.cursus.namespaces.inbox.session-expiry=1h",
                        "shiguchi.cursus.namespaces.inbox.auth=AUTHENTICATED",
                        "shiguchi.cursus.namespaces.inbox.publish-auth=NONE",
                        "shiguchi.cursus.namespaces.jobs.qos=AT_LEAST_ONCE",
                        "shiguchi.cursus.namespaces.jobs.history-size=100",
                        "shiguchi.cursus.namespaces.jobs.session-expiry=1h",
                        "shiguchi.cursus.namespaces.jobs.session-key=GROUP",
                        "shiguchi.cursus.namespaces.jobs.ack-deadline=5s",
                        "shiguchi.cursus.namespaces.jobs.publish-auth=NONE",
                        "shiguchi.cursus.archive.retention=1h",
                        "shiguchi.cursus.admin.role=PUSH_ADMIN",
                        "shiguchi.cursus.admin.payload-visibility=FULL",
                        "management.endpoints.web.exposure.include=gateway-push,health",
                        "shiguchi.cursus.connection.keepalive=200ms",
                        "shiguchi.cursus.connection.max-lifetime=0")
                .run();
        secondClient = WebClient.create("http://localhost:" + port(second));
    }

    @AfterAll
    static void stop() {
        if (second != null) second.close();
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
            "GIVEN a subscriber on instance A WHEN instance B publishes SHOULD deliver through the poller in order")
    void given_subscriberOnA_when_bPublishes_should_deliverAcrossInstances() {
        try (Subscriber sub = subscribe(client, metrics, null, "ticker:btc")) {
            for (int i = 1; i <= 3; i++) {
                assertThat(publish(secondClient, "ticker:btc", "{\"price\":" + i + "}", null))
                        .isNotBlank();
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
        String first = publish(secondClient, "orders:7", "{\"n\":1}", null);
        publish(secondClient, "orders:7", "{\"n\":2}", null);
        publish(secondClient, "orders:7", "{\"n\":3}", null);
        assertThat(store.historyLength(Topic.parse("orders:7"))).isEqualTo(3);

        try (Subscriber sub = subscribe(client, metrics, first, "orders:7")) {
            List<ServerSentEvent<String>> replayed = sub.awaitEvents(2);
            assertThat(replayed).extracting(ServerSentEvent::data).containsExactly("{\"n\":2}", "{\"n\":3}");
            publish(secondClient, "orders:7", "{\"n\":4}", null);
            assertThat(sub.awaitEvents(3).get(2).data()).isEqualTo("{\"n\":4}");
        }
    }

    @Test
    @DisplayName(
            "GIVEN a principal's session WHEN it goes away, events are published and it acks SHOULD resume from its cursor, then after the acked event")
    void given_principalSession_when_awayAndBack_should_resumeFromCursor() {
        String topic = "inbox:alice";
        publish(client, topic, "{\"n\":0}", null);
        subscribe(client, metrics, null, ALICE_TOKEN, topic).close();
        awaitTrue(() -> metrics.connections("sse") == 0);

        String a = publish(secondClient, topic, "{\"n\":1}", null);
        String b = publish(secondClient, topic, "{\"n\":2}", null);
        String c = publish(secondClient, topic, "{\"n\":3}", null);

        try (Subscriber s2 = subscribe(client, metrics, null, ALICE_TOKEN, topic)) {
            assertThat(s2.awaitEvents(3)).extracting(ServerSentEvent::id).containsExactly(a, b, c);
        }
        awaitTrue(() -> metrics.connections("sse") == 0);

        assertThat(status("POST", "/push/ack", "{\"topic\":\"" + topic + "\",\"id\":\"" + b + "\"}", null))
                .as("an ack needs the caller's session")
                .isEqualTo(401);
        JsonNode acked = body(
                exchange("POST", "/push/ack", "{\"topic\":\"" + topic + "\",\"id\":\"" + b + "\"}", ALICE_TOKEN)[1]);
        assertThat(acked.get("cursor").asText()).isEqualTo(b);

        try (Subscriber s3 = subscribe(client, metrics, null, ALICE_TOKEN, topic)) {
            assertThat(s3.awaitEvents(1)).extracting(ServerSentEvent::id).containsExactly(c);
        }
    }

    @Test
    @DisplayName(
            "GIVEN an SSE member of a consumer group WHEN it receives an event SHOULD ack the claim over POST /push/ack and leave nothing pending")
    void given_sseGroupMember_when_acking_should_clearTheClaim() {
        String topic = "jobs:sse";
        try (Subscriber sub = subscribe(client, metrics, null, topic + "&group=g1&member=m1")) {
            String e1 = publish(secondClient, topic, "{\"n\":1}", null);
            assertThat(sub.awaitEvents(1)).extracting(ServerSentEvent::id).containsExactly(e1);

            assertThat(status(
                            "POST",
                            "/push/ack",
                            "{\"topic\":\"" + topic + "\",\"id\":\"" + e1
                                    + "\",\"group\":\"g1\",\"member\":\"nobody\"}",
                            null))
                    .as("another member holds no such claim")
                    .isEqualTo(404);
            JsonNode acked = body(exchange(
                    "POST",
                    "/push/ack",
                    "{\"topic\":\"" + topic + "\",\"id\":\"" + e1 + "\",\"group\":\"g1\",\"member\":\"m1\"}",
                    null)[1]);
            assertThat(acked.get("cursor").asText()).isEqualTo(e1);
            JsonNode groups =
                    body(exchange("GET", ADMIN + "/groups?q=g1", null, null)[1]).get("groups");
            assertThat(groups).hasSize(1);
            assertThat(groups.get(0).get("pending").asLong()).isZero();
        }
    }

    @Test
    @DisplayName(
            "GIVEN events on instance B WHEN instance A searches, tombstones one and exports SHOULD share one archive: the search sees them, the tombstone redacts on both, the export streams all three newest first")
    void given_eventsOnB_when_archiveUsedFromA_should_searchTombstoneAndExport() {
        String first = publish(secondClient, "orders:arch", "{\"n\":1}", "{\"batch\":\"e1\"}");
        String middle = publish(secondClient, "orders:arch", "{\"n\":2}", "{\"batch\":\"e1\"}");
        String last = publish(secondClient, "orders:arch", "{\"n\":3}", "{\"batch\":\"e1\"}");

        JsonNode found = body(exchange("GET", ADMIN + "/messages?topic=orders:arch", null, null)[1]);
        assertThat(found.get("messages"))
                .extracting(m -> m.get("id").asText())
                .containsExactlyInAnyOrder(first, middle, last);
        assertThat(found.get("messages").get(0).get("data").asText()).startsWith("{\"n\":");

        assertThat(status("DELETE", ADMIN + "/messages/" + middle + "?topic=orders:arch", null))
                .isEqualTo(401);
        assertThat(status("DELETE", ADMIN + "/messages/" + middle + "?topic=orders:arch", null, ADMIN_TOKEN))
                .isEqualTo(200);
        for (WebClient via : List.of(client, secondClient)) {
            JsonNode redacted = body(via.get()
                    .uri(ADMIN + "/messages/" + middle + "?topic=orders:arch")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(30)));
            assertThat(redacted.get("tombstoned").asBoolean()).isTrue();
            assertThat(redacted.get("tombstonedBy").asText()).isEqualTo("ops");
            assertThat(redacted.get("data").asText()).isEqualTo("null");
        }

        try (Subscriber resumed = subscribe(client, metrics, first, "orders:arch")) {
            assertThat(resumed.awaitEvents(1)).extracting(ServerSentEvent::data).containsExactly("{\"n\":3}");
        }

        String[] exported = exchange("GET", "/push/export?attr=batch:e1", null, ADMIN_TOKEN);
        assertThat(exported[0]).isEqualTo("200");
        String[] lines = exported[1].trim().split("\n");
        assertThat(lines).hasSize(3);
        assertThat(body(lines[0]).get("id").asText()).isEqualTo(last);
        assertThat(body(lines[0]).get("tombstoned").asBoolean()).isFalse();
        assertThat(body(lines[1]).get("id").asText()).isEqualTo(middle);
        assertThat(body(lines[1]).get("tombstoned").asBoolean())
                .as("a tombstone stays in the export")
                .isTrue();
        assertThat(body(lines[2]).get("id").asText()).isEqualTo(first);
        assertThat(status("GET", "/push/export?attr=batch:e1", null)).isEqualTo(401);
    }

    // ---- helpers ----

    private static String publish(WebClient via, String topic, String dataJson, String attributesJson) {
        String request = "{\"topic\":\"" + topic + "\",\"data\":" + dataJson
                + (attributesJson == null ? "" : ",\"attributes\":" + attributesJson) + "}";
        String response = via.post()
                .uri("/push/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchangeToMono(r -> r.bodyToMono(String.class)
                        .map(b -> new String[] {String.valueOf(r.statusCode().value()), b}))
                .map(pair -> {
                    if (!"200".equals(pair[0])) throw new AssertionError("publish " + pair[0] + ": " + pair[1]);
                    return pair[1];
                })
                .block(Duration.ofSeconds(30));
        return body(response).get("id").asText();
    }

    private int status(String method, String path, String json) {
        return status(method, path, json, null);
    }

    private int status(String method, String path, String json, String token) {
        return Integer.parseInt(exchange(method, path, json, token)[0]);
    }

    private String[] exchange(String method, String path, String json, String token) {
        WebClient.RequestBodySpec spec = client.method(HttpMethod.valueOf(method))
                .uri(path)
                .headers(h -> {
                    if (token != null) h.setBearerAuth(token);
                });
        return (json == null
                        ? spec
                        : spec.contentType(MediaType.APPLICATION_JSON).bodyValue(json))
                .exchangeToMono(r -> r.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(b -> new String[] {String.valueOf(r.statusCode().value()), b}))
                .block(Duration.ofSeconds(30));
    }

    private static JsonNode body(String json) {
        try {
            return JSON.readTree(json);
        } catch (java.io.IOException e) {
            throw new AssertionError(json, e);
        }
    }

    private static Subscriber subscribe(WebClient via, PushMetrics metrics, String lastEventId, String topics) {
        return subscribe(via, metrics, lastEventId, null, topics);
    }

    private static Subscriber subscribe(
            WebClient via, PushMetrics metrics, String lastEventId, String token, String topics) {
        int before = metrics.connections();
        Flux<ServerSentEvent<String>> stream = via.get()
                .uri("/push/subscribe?topic=" + topics)
                .headers(h -> {
                    if (lastEventId != null) h.add("Last-Event-ID", lastEventId);
                    if (token != null) h.setBearerAuth(token);
                })
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(SSE);
        Subscriber sub = new Subscriber(stream);
        awaitTrue(() -> metrics.connections() == before + 1);
        // The poller drops what is appended while nobody listens on this instance: let the sink attach.
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
