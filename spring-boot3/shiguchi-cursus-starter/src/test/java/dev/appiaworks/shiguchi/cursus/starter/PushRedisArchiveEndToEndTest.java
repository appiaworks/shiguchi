package dev.appiaworks.shiguchi.cursus.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.appiaworks.shiguchi.cursus.PushMetrics;
import java.io.IOException;
import java.net.ServerSocket;
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
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import redis.embedded.RedisServer;

/**
 * The archive half of the {@code gateway-push} endpoint on the Boot 3 gateway over a real port,
 * on the {@code REDIS} engine (embedded Redis) with {@code archive.retention} set: published events are searchable by
 * topic pattern and attribute with their payload under {@code payload-visibility: FULL}; an
 * operator republishes one (dry run first) and a live subscriber receives the copy linked by
 * {@code origin_id}; a tombstone redacts the event and a resume skips it;
 * the writes need the admin role; the export streams a query as NDJSON on the push path.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.graphql.enabled=false",
            "shiguchi.cursus.enabled=true",
            "shiguchi.cursus.store=REDIS",
            "shiguchi.cursus.broker=REDIS",
            "shiguchi.cursus.redis.key-prefix=e2e:archive:",
            "shiguchi.cursus.archive.retention=1h",
            "shiguchi.cursus.admin.role=PUSH_ADMIN",
            "shiguchi.cursus.admin.payload-visibility=FULL",
            "shiguchi.cursus.admin.republish.rate-per-second=1000",
            "shiguchi.cursus.namespaces.ticker.publish-auth=NONE",
            "shiguchi.cursus.namespaces.orders.qos=AT_LEAST_ONCE",
            "shiguchi.cursus.namespaces.orders.history-size=100",
            "shiguchi.cursus.namespaces.orders.publish-auth=NONE",
            "shiguchi.cursus.connection.keepalive=200ms",
            "shiguchi.cursus.connection.max-lifetime=0",
            "management.endpoints.web.exposure.include=gateway-push,health"
        })
@DisplayName("Push archive on Redis: search, republish, tombstone, export end to end (Boot 3)")
class PushRedisArchiveEndToEndTest {

    static final String ADMIN_TOKEN = "admin-token";
    static final String USER_TOKEN = "user-token";

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {

        @Bean
        ReactiveJwtDecoder fixedTokens() {
            return token -> switch (token) {
                case ADMIN_TOKEN -> Mono.just(jwt("ops", "PUSH_ADMIN"));
                case USER_TOKEN -> Mono.just(jwt("alice", "read"));
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

    private static RedisServer redis;
    private static int redisPort;

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> redisPort);
    }

    @BeforeAll
    static void startRedis() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            redisPort = socket.getLocalPort();
        }
        redis = RedisServer.newRedisServer().port(redisPort).build();
        redis.start();
    }

    @AfterAll
    static void stopRedis() throws IOException {
        if (redis != null) redis.stop();
    }

    @Autowired
    private Environment environment;

    @Autowired
    private PushMetrics metrics;

    private WebClient client;

    @BeforeEach
    void client() {
        client = WebClient.create("http://localhost:" + environment.getRequiredProperty("local.server.port"));
    }

    @Test
    @DisplayName(
            "GIVEN events published with attributes WHEN the archive is searched SHOULD list them newest first, filter by topic pattern and attribute, and show the payload under FULL visibility")
    void given_publishedEvents_when_searched_should_filterAndShowPayload() {
        String a = publish("ticker:search/a", "{\"n\":1}", "{\"region\":\"eu\"}");
        String b = publish("ticker:search/b", "{\"n\":2}", "{\"region\":\"us\"}");
        publish("orders:search", "{\"n\":3}", "{\"region\":\"eu\"}");

        JsonNode overview = get(ADMIN, null);
        assertThat(overview.at("/engine/archive/enabled").asBoolean()).isTrue();
        assertThat(overview.at("/engine/archive/retention").asText()).isEqualTo("PT1H");

        JsonNode all = get(ADMIN + "/messages?topic=ticker:search/*", null).get("messages");
        assertThat(all).extracting(m -> m.get("id").asText()).containsExactly(b, a);
        assertThat(all.get(0).get("data").asText()).isEqualTo("{\"n\":2}");
        assertThat(all.get(0).get("attributes").get("region").asText()).isEqualTo("us");

        JsonNode eu = get(ADMIN + "/messages?attr=region:eu&limit=10", null).get("messages");
        assertThat(eu).extracting(m -> m.get("topic").asText()).containsExactly("orders:search", "ticker:search/a");

        JsonNode one = get(ADMIN + "/messages/" + a + "?topic=ticker:search/a", null);
        assertThat(one.get("id").asText()).isEqualTo(a);
        assertThat(one.get("tombstoned").asBoolean()).isFalse();
        assertThat(status("GET", ADMIN + "/messages/nope?topic=ticker:search/a", null))
                .isEqualTo(404);
        assertThat(status("GET", ADMIN + "/messages?from=yesterday", null)).isEqualTo(400);
    }

    @Test
    @DisplayName(
            "GIVEN an archived event WHEN the operator republishes it SHOULD deliver a new event linked by origin_id to a live subscriber, after a dry run that sends nothing, and refuse other callers")
    void given_archivedEvent_when_republished_should_deliverCopy() {
        String id = publish("orders:repub", "{\"n\":7}", "{\"k\":\"v\"}");
        List<ServerSentEvent<String>> frames = new CopyOnWriteArrayList<>();
        Disposable stream = subscribe("orders:repub", frames);
        try {
            String path = ADMIN + "/messages/" + id + "/republish";
            assertThat(post(path, "{\"topic\":\"orders:repub\",\"dryRun\":true}", null)
                            .statusCode()
                            .value())
                    .isEqualTo(401);
            assertThat(post(path, "{\"topic\":\"orders:repub\",\"dryRun\":true}", USER_TOKEN)
                            .statusCode()
                            .value())
                    .isEqualTo(403);
            JsonNode dry = body(post(path, "{\"topic\":\"orders:repub\",\"dryRun\":true}", ADMIN_TOKEN));
            assertThat(dry.get("dryRun").asBoolean()).isTrue();
            assertThat(dry.get("target").asText()).isEqualTo("orders:repub");
            assertThat(dry.has("id")).isFalse();
            assertThat(frames).isEmpty();

            JsonNode sent =
                    body(post(path, "{\"topic\":\"orders:repub\",\"reason\":\"replay after outage\"}", ADMIN_TOKEN));
            assertThat(sent.get("dryRun").asBoolean()).isFalse();
            String copy = sent.get("id").asText();
            assertThat(copy).isNotEqualTo(id);
            awaitTrue(() -> !frames.isEmpty());
            assertThat(frames.get(0).id()).isEqualTo(copy);
            assertThat(frames.get(0).data()).isEqualTo("{\"n\":7}");

            JsonNode archived = get(ADMIN + "/messages/" + copy + "?topic=orders:repub", null);
            JsonNode attrs = archived.get("attributes");
            assertThat(attrs.get("origin_id").asText()).isEqualTo(id);
            assertThat(attrs.get("origin_topic").asText()).isEqualTo("orders:repub");
            assertThat(attrs.get("republished_by").asText()).isEqualTo("ops");
            assertThat(attrs.get("reason").asText()).isEqualTo("replay after outage");
            assertThat(attrs.get("k").asText()).isEqualTo("v");

            assertThat(post(
                                    ADMIN + "/messages/" + id + "/republish",
                                    "{\"topic\":\"orders:repub\",\"target\":\"nope:x\"}",
                                    ADMIN_TOKEN)
                            .statusCode()
                            .value())
                    .isEqualTo(404);
        } finally {
            stream.dispose();
        }
    }

    @Test
    @DisplayName(
            "GIVEN three events in a history WHEN the middle one is tombstoned SHOULD redact it in the archive and skip it on resume")
    void given_history_when_tombstoned_should_redactAndSkipOnResume() {
        String first = publish("orders:tomb", "{\"n\":1}", null);
        String second = publish("orders:tomb", "{\"n\":2}", null);
        publish("orders:tomb", "{\"n\":3}", null);

        assertThat(status("DELETE", ADMIN + "/messages/" + second + "?topic=orders:tomb", USER_TOKEN))
                .isEqualTo(403);
        assertThat(status("DELETE", ADMIN + "/messages/" + second + "?topic=orders:tomb", ADMIN_TOKEN))
                .isEqualTo(200);
        assertThat(status("DELETE", ADMIN + "/messages/" + second + "?topic=orders:tomb", ADMIN_TOKEN))
                .isEqualTo(404);

        JsonNode redacted = get(ADMIN + "/messages/" + second + "?topic=orders:tomb", null);
        assertThat(redacted.get("tombstoned").asBoolean()).isTrue();
        assertThat(redacted.get("tombstonedBy").asText()).isEqualTo("ops");
        assertThat(redacted.get("data").asText()).isEqualTo("null");
        assertThat(post(ADMIN + "/messages/" + second + "/republish", "{\"topic\":\"orders:tomb\"}", ADMIN_TOKEN)
                        .statusCode()
                        .value())
                .isEqualTo(409);

        List<ServerSentEvent<String>> frames = new CopyOnWriteArrayList<>();
        Disposable stream = resume("orders:tomb", first, frames);
        try {
            awaitTrue(() -> frames.size() >= 1);
            assertThat(frames).extracting(ServerSentEvent::data).containsExactly("{\"n\":3}");
        } finally {
            stream.dispose();
        }
    }

    @Test
    @DisplayName("GIVEN archived events WHEN exported SHOULD stream them as NDJSON newest first for the admin only")
    void given_archived_events_when_exported_should_streamNdjson() {
        String first = publish("orders:export", "{\"n\":1}", "{\"batch\":\"x1\"}");
        String second = publish("orders:export", "{\"n\":2}", "{\"batch\":\"x1\"}");

        String export = "/push/export?attr=batch:x1";
        assertThat(status("GET", export, null)).isEqualTo(401);
        assertThat(status("GET", export, USER_TOKEN)).isEqualTo(403);
        ClientResponse exported = client.get()
                .uri(export)
                .headers(h -> h.setBearerAuth(ADMIN_TOKEN))
                .exchangeToMono(r -> r.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(b -> ClientResponse.create(r.statusCode())
                                .headers(h -> h.addAll(r.headers().asHttpHeaders()))
                                .body(b)
                                .build()))
                .block(Duration.ofSeconds(30));
        assertThat(exported.statusCode().value()).isEqualTo(200);
        String[] lines = exported.bodyToMono(String.class)
                .block(Duration.ofSeconds(30))
                .trim()
                .split("\\n");
        assertThat(lines).hasSize(2);
        assertThat(body(lines[0]).get("id").asText()).isEqualTo(second);
        assertThat(body(lines[1]).get("id").asText()).isEqualTo(first);
    }

    // ---- helpers ----

    private String publish(String topic, String dataJson, String attributesJson) {
        String body = "{\"topic\":\"" + topic + "\",\"data\":" + dataJson
                + (attributesJson == null ? "" : ",\"attributes\":" + attributesJson) + "}";
        JsonNode response = body(client.post()
                .uri("/push/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToMono(r -> r.bodyToMono(String.class)
                        .map(b -> new String[] {String.valueOf(r.statusCode().value()), b}))
                .map(pair -> {
                    if (!"200".equals(pair[0])) throw new AssertionError("publish " + pair[0] + ": " + pair[1]);
                    return pair[1];
                })
                .block(Duration.ofSeconds(30)));
        return response.get("id").asText();
    }

    private Disposable subscribe(String topic, List<ServerSentEvent<String>> frames) {
        return resume(topic, null, frames);
    }

    private Disposable resume(String topic, String lastEventId, List<ServerSentEvent<String>> frames) {
        int before = metrics.connections();
        Disposable d = client.get()
                .uri("/push/subscribe?topic=" + topic)
                .headers(h -> {
                    if (lastEventId != null) h.add("Last-Event-ID", lastEventId);
                })
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(SSE)
                .filter(e -> e.event() != null && !"close".equals(e.event()))
                .subscribe(frames::add);
        awaitTrue(() -> metrics.connections() == before + 1);
        // the Redis subscription is registered a moment after the SSE connection is counted
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return d;
    }

    private JsonNode get(String path, String token) {
        return body(client.get()
                .uri(path)
                .headers(h -> {
                    if (token != null) h.setBearerAuth(token);
                })
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(30)));
    }

    private int status(String method, String path, String token) {
        return client.method(HttpMethod.valueOf(method))
                .uri(path)
                .headers(h -> {
                    if (token != null) h.setBearerAuth(token);
                })
                .exchangeToMono(r -> Mono.just(r.statusCode().value()))
                .block(Duration.ofSeconds(30));
    }

    private ClientResponse post(String path, String json, String token) {
        return client.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> {
                    if (token != null) h.setBearerAuth(token);
                })
                .bodyValue(json)
                .exchangeToMono(r -> r.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(b -> ClientResponse.create(r.statusCode())
                                .headers(h -> h.addAll(r.headers().asHttpHeaders()))
                                .body(b)
                                .build()))
                .block(Duration.ofSeconds(30));
    }

    private static JsonNode body(ClientResponse response) {
        assertThat(response.statusCode().value()).as("status of %s", response).isEqualTo(200);
        return body(response.bodyToMono(String.class).block(Duration.ofSeconds(30)));
    }

    private static JsonNode body(String json) {
        try {
            return JSON.readTree(json);
        } catch (java.io.IOException e) {
            throw new AssertionError(json, e);
        }
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition not met in 30s");
            Thread.onSpinWait();
        }
    }
}
