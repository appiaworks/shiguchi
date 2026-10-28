package dev.appiaworks.shiguchi.cursus.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.appiaworks.shiguchi.cursus.PushMetrics;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Consumer groups on the Boot 3 gateway over a real port, on the in-memory store: two WebSocket
 * members of one group split a topic's events between them and ack with frames; a member that
 * never acks sees its events redelivered to the other after {@code ack-deadline}, then
 * dead-lettered past {@code max-deliveries}; an SSE member joins with {@code ?group=&member=} and
 * acks over {@code POST /push/ack}; the admin lists and drops the group.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.graphql.enabled=false",
            "shiguchi.cursus.enabled=true",
            "shiguchi.cursus.admin.role=PUSH_ADMIN",
            "shiguchi.cursus.namespaces.jobs.qos=AT_LEAST_ONCE",
            "shiguchi.cursus.namespaces.jobs.history-size=100",
            "shiguchi.cursus.namespaces.jobs.session-expiry=1h",
            "shiguchi.cursus.namespaces.jobs.session-key=GROUP",
            "shiguchi.cursus.namespaces.jobs.ack-deadline=700ms",
            "shiguchi.cursus.namespaces.jobs.max-deliveries=2",
            "shiguchi.cursus.namespaces.jobs.max-inflight=2",
            "shiguchi.cursus.namespaces.jobs.publish-auth=NONE",
            "shiguchi.cursus.namespaces.ticker.publish-auth=NONE",
            "shiguchi.cursus.connection.keepalive=200ms",
            "shiguchi.cursus.connection.max-lifetime=0",
            "management.endpoints.web.exposure.include=gateway-push,health"
        })
@DisplayName("Push consumer groups end to end (Boot 3)")
class PushGroupsEndToEndTest {

    static final String ADMIN_TOKEN = "admin-token";

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {
        @Bean
        ReactiveJwtDecoder fixedTokens() {
            return token -> ADMIN_TOKEN.equals(token)
                    ? Mono.just(Jwt.withTokenValue(token)
                            .header("alg", "none")
                            .subject("ops")
                            .claim("scope", "PUSH_ADMIN")
                            .issuedAt(Instant.now())
                            .expiresAt(Instant.now().plusSeconds(3600))
                            .build())
                    : Mono.error(new BadJwtException("unknown token"));
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE =
            new ParameterizedTypeReference<>() {};
    private static final String ADMIN = "/actuator/gateway-push";

    @Autowired
    private Environment environment;

    @Autowired
    private PushMetrics metrics;

    private WebClient client;
    private URI wsUri;

    @BeforeEach
    void client() {
        String port = environment.getRequiredProperty("local.server.port");
        client = WebClient.create("http://localhost:" + port);
        wsUri = URI.create("ws://localhost:" + port + "/push/ws");
    }

    @Test
    @DisplayName(
            "GIVEN two WebSocket members of a group WHEN events are published SHOULD hand each event to exactly one member, hold at most max-inflight per member until acked, and show the group to the admin")
    void given_twoMembers_when_published_should_splitEventsExactlyOnce() {
        String topic = "jobs:split";
        try (Socket a = socket();
                Socket b = socket()) {
            a.send("{\"type\":\"sub\",\"topics\":[\"" + topic + "\"],\"group\":\"workers\",\"member\":\"a\"}");
            b.send("{\"type\":\"sub\",\"topics\":[\"" + topic + "\"],\"group\":\"workers\",\"member\":\"b\"}");
            a.await("subscribed");
            b.await("subscribed");
            assertThat(status("GET", "/push/subscribe?topic=" + topic, null, null))
                    .as("a group namespace needs a group")
                    .isEqualTo(400);

            List<String> published = new ArrayList<>();
            for (int i = 0; i < 6; i++) published.add(publish(topic, "{\"n\":" + i + "}"));

            // Each member holds at most two unacknowledged events: ack what arrives to keep the flow.
            awaitTrue(() -> {
                ackAll(a, topic);
                ackAll(b, topic);
                return a.events().size() + b.events().size() >= 6;
            });
            Set<String> seen = new TreeSet<>();
            seen.addAll(a.events());
            seen.addAll(b.events());
            assertThat(a.events().size() + b.events().size())
                    .as("no duplicates")
                    .isEqualTo(6);
            assertThat(seen).containsExactlyInAnyOrderElementsOf(published);
            assertThat(a.events()).isNotEmpty();
            assertThat(b.events()).isNotEmpty();
            awaitTrue(() -> a.frames.stream()
                                    .filter(f -> "acked".equals(f.path("type").asText()))
                                    .count()
                            + b.frames.stream()
                                    .filter(f -> "acked".equals(f.path("type").asText()))
                                    .count()
                    >= 6);

            JsonNode overview = get(ADMIN, null);
            assertThat(overview.at("/engine/groups/supported").asBoolean()).isTrue();
            assertThat(overview.at("/engine/groups/namespaces").get(0).asText()).isEqualTo("jobs");
            assertThat(overview.at("/engine/groups/members").asInt()).isEqualTo(2);
            JsonNode groups = get(ADMIN + "/groups?q=work", null).get("groups");
            assertThat(groups).hasSize(1);
            assertThat(groups.get(0).get("group").asText()).isEqualTo("workers");
            assertThat(groups.get(0).get("topic").asText()).isEqualTo(topic);
            assertThat(groups.get(0).get("pending").asLong()).isZero();
        }
        awaitTrue(() -> metrics.connections("ws") == 0);
    }

    @Test
    @DisplayName(
            "GIVEN a member that never acks WHEN the ack deadline passes SHOULD redeliver its events to another member with the delivery count, and dead-letter them past max-deliveries")
    void given_silentMember_when_deadlinePasses_should_redeliverThenDeadLetter() {
        String topic = "jobs:redeliver";
        try (Socket silent = socket()) {
            silent.send("{\"type\":\"sub\",\"topics\":[\"" + topic + "\"],\"group\":\"g2\",\"member\":\"silent\"}");
            silent.await("subscribed");
            String e1 = publish(topic, "{\"n\":1}");
            awaitTrue(() -> silent.events().contains(e1));
            try (Socket other = socket()) {
                other.send("{\"type\":\"sub\",\"topics\":[\"" + topic + "\"],\"group\":\"g2\",\"member\":\"other\"}");
                other.await("subscribed");
                awaitTrue(() -> other.events().contains(e1));
                JsonNode redelivered = other.frames.stream()
                        .filter(f -> "event".equals(f.path("type").asText())
                                && e1.equals(f.path("id").asText()))
                        .findFirst()
                        .orElseThrow();
                assertThat(redelivered.at("/attributes/deliveries").asText()).isEqualTo("2");
                // Nobody acks: the third claim exceeds max-deliveries 2 and the event is dead-lettered.
                awaitTrue(() -> get(ADMIN + "/dlq?q=group:g2", null).get("dlq").size() >= 1);
                JsonNode entry = get(ADMIN + "/dlq?q=group:g2", null).get("dlq").get(0);
                assertThat(entry.get("source").asText()).isEqualTo("group:g2");
                assertThat(entry.get("reason").asText()).contains("max-deliveries 2");
                assertThat(entry.get("topic").asText()).isEqualTo(topic);
                assertThat(status("POST", ADMIN + "/dlq/" + entry.get("id").asText() + "/retry", "{}", ADMIN_TOKEN))
                        .as("a dead-lettered event can be retried into the group's topic")
                        .isEqualTo(200);
            }
        }
        awaitTrue(() -> metrics.connections("ws") == 0);
    }

    @Test
    @DisplayName(
            "GIVEN an SSE member named with ?group=&member= WHEN it receives an event SHOULD ack it over POST /push/ack with the group and member, and the admin SHOULD drop the group")
    void given_sseMember_when_acking_should_useHttpAck() {
        String topic = "jobs:sse";
        List<ServerSentEvent<String>> frames = new CopyOnWriteArrayList<>();
        assertThat(status("GET", "/push/subscribe?topic=" + topic + "&group=g3", null, null))
                .as("a member name is required with a group")
                .isEqualTo(400);
        Disposable stream = subscribe(topic + "&group=g3&member=sse-1", frames);
        try {
            String e1 = publish(topic, "{\"n\":1}");
            awaitTrue(() -> frames.stream().anyMatch(f -> e1.equals(f.id())));
            assertThat(status(
                            "POST",
                            "/push/ack",
                            "{\"topic\":\"" + topic + "\",\"id\":\"" + e1
                                    + "\",\"group\":\"g3\",\"member\":\"nobody\"}",
                            null))
                    .as("another member holds no such claim")
                    .isEqualTo(404);
            JsonNode acked = body(exchange(
                    "POST",
                    "/push/ack",
                    "{\"topic\":\"" + topic + "\",\"id\":\"" + e1 + "\",\"group\":\"g3\",\"member\":\"sse-1\"}",
                    null));
            assertThat(acked.get("cursor").asText()).isEqualTo(e1);
            JsonNode groups = get(ADMIN + "/groups?q=g3", null).get("groups");
            assertThat(groups.get(0).get("pending").asLong()).isZero();
            assertThat(groups.get(0).get("members").asLong()).isZero();
            assertThat(status("DELETE", ADMIN + "/groups/g3", null, null)).isEqualTo(401);
            assertThat(body(exchange("DELETE", ADMIN + "/groups/g3", null, ADMIN_TOKEN))
                            .get("dropped")
                            .asLong())
                    .isGreaterThanOrEqualTo(1);
            assertThat(get(ADMIN + "/audit", null).get("audit"))
                    .extracting(a -> a.get("action").asText())
                    .contains("drop_group");
        } finally {
            stream.dispose();
        }
    }

    // ---- helpers ----

    /** Acks every event frame of {@code s} on {@code topic} not acked yet. */
    private static void ackAll(Socket s, String topic) {
        for (String id : s.events()) {
            if (s.acked.add(id)) s.send("{\"type\":\"ack\",\"topic\":\"" + topic + "\",\"id\":\"" + id + "\"}");
        }
    }

    private String publish(String topic, String dataJson) {
        return body(exchange("POST", "/push/publish", "{\"topic\":\"" + topic + "\",\"data\":" + dataJson + "}", null))
                .get("id")
                .asText();
    }

    private Disposable subscribe(String topicAndParams, List<ServerSentEvent<String>> frames) {
        int before = metrics.connections("sse");
        Disposable d = client.get()
                .uri("/push/subscribe?topic=" + topicAndParams)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(SSE)
                .filter(e -> e.event() != null && !"close".equals(e.event()) && !"overflow".equals(e.event()))
                .subscribe(frames::add);
        awaitTrue(() -> metrics.connections("sse") == before + 1);
        return d;
    }

    private JsonNode get(String path, String token) {
        return body(exchange("GET", path, null, token));
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

    private Socket socket() {
        int before = metrics.connections("ws");
        Socket socket = new Socket(wsUri);
        socket.send("{\"type\":\"connect\"}");
        socket.await("connected");
        awaitTrue(() -> metrics.connections("ws") == before + 1);
        return socket;
    }

    private static final class Socket implements AutoCloseable {
        private final List<JsonNode> frames = new CopyOnWriteArrayList<>();
        private final Set<String> acked = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private final Sinks.Many<String> outbound = Sinks.many().unicast().onBackpressureBuffer();
        private final Disposable connection;

        Socket(URI uri) {
            connection = new ReactorNettyWebSocketClient()
                    .execute(uri, new HttpHeaders(), session -> {
                        Mono<Void> send = session.send(outbound.asFlux().map(session::textMessage));
                        Mono<Void> receive = session.receive()
                                .map(WebSocketMessage::getPayloadAsText)
                                .doOnNext(t -> frames.add(parse(t)))
                                .then();
                        return Mono.zip(send, receive).then();
                    })
                    .subscribe();
        }

        void send(String frame) {
            outbound.tryEmitNext(frame);
        }

        /** The ids of the event frames received so far, in order. */
        List<String> events() {
            return frames.stream()
                    .filter(f -> "event".equals(f.path("type").asText()))
                    .map(f -> f.path("id").asText())
                    .toList();
        }

        JsonNode await(String type) {
            awaitTrue(() ->
                    frames.stream().anyMatch(f -> type.equals(f.path("type").asText())));
            return frames.stream()
                    .filter(f -> type.equals(f.path("type").asText()))
                    .findFirst()
                    .orElseThrow();
        }

        @Override
        public void close() {
            outbound.tryEmitComplete();
            connection.dispose();
        }
    }

    private static JsonNode body(String[] response) {
        assertThat(response[0]).as("status: %s", response[1]).isEqualTo("200");
        return parse(response[1]);
    }

    private static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (java.io.IOException e) {
            throw new AssertionError(json, e);
        }
    }

    private static void awaitTrue(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition not met in 30s");
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
