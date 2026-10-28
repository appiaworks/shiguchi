package dev.appiaworks.shiguchi.cursus.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.appiaworks.shiguchi.cursus.PushMetrics;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
 * Persistent sessions on the Boot 3 gateway over a real port, on the in-memory store: a
 * principal's session on {@code inbox} (SSE, acked over {@code POST /push/ack}) resumes from its
 * cursor without a {@code Last-Event-ID} and receives what was published while it was away; a
 * client-named session on {@code alerts} over WebSocket acks with a frame and, past
 * {@code max-queued}, loses the oldest pending events behind an {@code overflow} frame; the gRPC
 * {@code Ack} is reachable as JSON at {@code /push/v1/ack}; the admin lists and drops sessions.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.graphql.enabled=false",
            "shiguchi.cursus.enabled=true",
            "shiguchi.cursus.admin.role=PUSH_ADMIN",
            "shiguchi.cursus.namespaces.inbox.qos=AT_LEAST_ONCE",
            "shiguchi.cursus.namespaces.inbox.history-size=100",
            "shiguchi.cursus.namespaces.inbox.session-expiry=1h",
            "shiguchi.cursus.namespaces.inbox.auth=AUTHENTICATED",
            "shiguchi.cursus.namespaces.inbox.publish-auth=NONE",
            "shiguchi.cursus.namespaces.alerts.qos=AT_LEAST_ONCE",
            "shiguchi.cursus.namespaces.alerts.history-size=100",
            "shiguchi.cursus.namespaces.alerts.session-expiry=1h",
            "shiguchi.cursus.namespaces.alerts.session-key=CLIENT",
            "shiguchi.cursus.namespaces.alerts.max-queued=3",
            "shiguchi.cursus.namespaces.alerts.publish-auth=NONE",
            "shiguchi.cursus.namespaces.ticker.publish-auth=NONE",
            "shiguchi.cursus.connection.keepalive=200ms",
            "shiguchi.cursus.connection.max-lifetime=0",
            "management.endpoints.web.exposure.include=gateway-push,health"
        })
@DisplayName("Push persistent sessions end to end (Boot 3)")
class PushSessionsEndToEndTest {

    static final String ADMIN_TOKEN = "admin-token";
    static final String ALICE_TOKEN = "alice-token";

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {
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
            "GIVEN a principal's session WHEN it goes away, events are published and it comes back without a position SHOULD resume from its cursor, and after an ack over HTTP SHOULD resume after the acked event")
    void given_principalSession_when_awayAndBack_should_resumeFromCursor() {
        String topic = "inbox:alice";
        publish(topic, "{\"n\":0}");
        List<ServerSentEvent<String>> first = new CopyOnWriteArrayList<>();
        subscribe(topic, ALICE_TOKEN, null, first).dispose();
        awaitTrue(() -> metrics.connections("sse") == 0);
        assertThat(first).as("a new session starts at the head").isEmpty();

        String a = publish(topic, "{\"n\":1}");
        String b = publish(topic, "{\"n\":2}");
        String c = publish(topic, "{\"n\":3}");

        List<ServerSentEvent<String>> second = new CopyOnWriteArrayList<>();
        Disposable s2 = subscribe(topic, ALICE_TOKEN, null, second);
        awaitTrue(() -> second.size() >= 3);
        assertThat(second).extracting(ServerSentEvent::id).containsExactly(a, b, c);
        s2.dispose();
        awaitTrue(() -> metrics.connections("sse") == 0);

        assertThat(status("POST", "/push/ack", "{\"topic\":\"" + topic + "\",\"id\":\"" + b + "\"}", null))
                .as("an ack needs the caller's session")
                .isEqualTo(401);
        JsonNode acked =
                body(exchange("POST", "/push/ack", "{\"topic\":\"" + topic + "\",\"id\":\"" + b + "\"}", ALICE_TOKEN));
        assertThat(acked.get("cursor").asText()).isEqualTo(b);
        assertThat(status("POST", "/push/ack", "{\"topic\":\"" + topic + "\",\"id\":\"" + a + "\"}", ALICE_TOKEN))
                .as("moving back is accepted and ignored")
                .isEqualTo(200);

        List<ServerSentEvent<String>> third = new CopyOnWriteArrayList<>();
        Disposable s3 = subscribe(topic, ALICE_TOKEN, null, third);
        awaitTrue(() -> !third.isEmpty());
        assertThat(third).extracting(ServerSentEvent::id).containsExactly(c);
        s3.dispose();
        awaitTrue(() -> metrics.connections("sse") == 0);

        // Last-Event-ID is an implicit ack: a reconnect naming c resumes after it even without a POST.
        List<ServerSentEvent<String>> fourth = new CopyOnWriteArrayList<>();
        Disposable s4 = subscribe(topic, ALICE_TOKEN, c, fourth);
        String d = publish(topic, "{\"n\":4}");
        awaitTrue(() -> !fourth.isEmpty());
        assertThat(fourth).extracting(ServerSentEvent::id).containsExactly(d);
        s4.dispose();
        awaitTrue(() -> metrics.connections("sse") == 0);
        List<ServerSentEvent<String>> fifth = new CopyOnWriteArrayList<>();
        Disposable s5 = subscribe(topic, ALICE_TOKEN, null, fifth);
        awaitTrue(() -> !fifth.isEmpty());
        assertThat(fifth).extracting(ServerSentEvent::id).containsExactly(d);
        s5.dispose();

        JsonNode sessions = get(ADMIN + "/sessions?q=alice", null).get("sessions");
        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).get("session").asText()).isEqualTo("alice");
        assertThat(sessions.get(0).get("topic").asText()).isEqualTo(topic);
        assertThat(sessions.get(0).get("cursor").asText()).isEqualTo(c);
        assertThat(get(ADMIN, null).at("/engine/sessions/namespaces"))
                .extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("inbox", "alerts");
        assertThat(get(ADMIN, null).at("/engine/sessions/supported").asBoolean())
                .isTrue();
    }

    @Test
    @DisplayName(
            "GIVEN a client-named session over WebSocket WHEN it acks with a frame, leaves, and more than max-queued events arrive SHOULD get an overflow frame with the dropped count and the newest events on resume")
    void given_clientSession_when_overflowed_should_announceDroppedAndResume() {
        String topic = "alerts:dev";
        try (Socket first = socket("dev-7")) {
            first.send("{\"type\":\"sub\",\"topics\":[\"" + topic + "\"]}");
            first.await("subscribed");
            String e1 = publish(topic, "{\"n\":1}");
            JsonNode event = first.await("event");
            assertThat(event.get("id").asText()).isEqualTo(e1);
            first.send("{\"type\":\"ack\",\"topic\":\"" + topic + "\",\"id\":\"" + e1 + "\"}");
            JsonNode acked = first.await("acked");
            assertThat(acked.get("cursor").asText()).isEqualTo(e1);
            first.send("{\"type\":\"ack\",\"topic\":\"ticker:x\",\"id\":\"" + e1 + "\"}");
            assertThat(first.await("error").get("code").asText()).isEqualTo("bad_request");
        }
        awaitTrue(() -> metrics.connections("ws") == 0);

        String[] ids = new String[6];
        for (int i = 0; i < 6; i++) ids[i] = publish(topic, "{\"n\":" + (i + 2) + "}");

        try (Socket second = socket("dev-7")) {
            second.send("{\"type\":\"sub\",\"topics\":[\"" + topic + "\"]}");
            second.await("subscribed");
            JsonNode overflow = second.await("overflow");
            assertThat(overflow.get("reason").asText()).isEqualTo("session_overflow");
            assertThat(overflow.get("dropped").asInt()).isEqualTo(3);
            second.await("event", 3);
            assertThat(second.frames.stream()
                            .filter(f -> "event".equals(f.path("type").asText()))
                            .map(f -> f.get("id").asText())
                            .toList())
                    .containsExactly(ids[3], ids[4], ids[5]);
        }
        awaitTrue(() -> metrics.connections("ws") == 0);

        // The same client id under another principal is another session: alice's "dev-7" starts fresh.
        try (Socket other = socket("dev-7", ALICE_TOKEN)) {
            other.send("{\"type\":\"sub\",\"topics\":[\"" + topic + "\"]}");
            other.await("subscribed");
            sleep(300);
            assertThat(other.frames.stream()
                            .noneMatch(f -> "event".equals(f.path("type").asText())))
                    .isTrue();
        }
        JsonNode sessions = get(ADMIN + "/sessions?q=dev-7", null).get("sessions");
        assertThat(sessions)
                .extracting(s -> s.get("session").asText())
                .containsExactlyInAnyOrder("dev-7@-", "dev-7@alice");
    }

    @Test
    @DisplayName(
            "GIVEN the gRPC Ack through the JSON transcoding and the admin WHEN a cursor is acked and the session dropped SHOULD move the cursor, and a reconnect SHOULD start at the head again")
    void given_grpcAckAndAdminDrop_when_used_should_moveThenForget() {
        String topic = "alerts:grpc";
        try (Socket s = socket("cli-1")) {
            s.send("{\"type\":\"sub\",\"topics\":[\"" + topic + "\"]}");
            s.await("subscribed");
        }
        awaitTrue(() -> metrics.connections("ws") == 0);
        String e1 = publish(topic, "{\"n\":1}");
        String e2 = publish(topic, "{\"n\":2}");

        JsonNode acked = body(exchange(
                "POST",
                "/push/v1/ack",
                "{\"topic\":\"" + topic + "\",\"id\":\"" + e1 + "\",\"session\":\"cli-1\"}",
                null));
        assertThat(acked.get("cursor").asText()).isEqualTo(e1);
        assertThat(get(ADMIN + "/sessions?q=cli-1", null)
                        .get("sessions")
                        .get(0)
                        .get("cursor")
                        .asText())
                .isEqualTo(e1);

        assertThat(status("DELETE", ADMIN + "/sessions/cli-1@-", null, null)).isEqualTo(401);
        JsonNode dropped = body(exchange("DELETE", ADMIN + "/sessions/cli-1@-", null, ADMIN_TOKEN));
        assertThat(dropped.get("dropped").asLong()).isEqualTo(1);
        assertThat(status("DELETE", ADMIN + "/sessions/cli-1@-", null, ADMIN_TOKEN))
                .isEqualTo(404);
        assertThat(get(ADMIN + "/audit", null).get("audit"))
                .extracting(a -> a.get("action").asText())
                .contains("drop_session");

        try (Socket again = socket("cli-1")) {
            again.send("{\"type\":\"sub\",\"topics\":[\"" + topic + "\"]}");
            again.await("subscribed");
            sleep(300);
            assertThat(again.frames.stream()
                            .noneMatch(f -> "event".equals(f.path("type").asText())))
                    .as("a forgotten session starts at the head: " + e2 + " is not replayed")
                    .isTrue();
        }
    }

    // ---- helpers ----

    private String publish(String topic, String dataJson) {
        JsonNode response = body(
                exchange("POST", "/push/publish", "{\"topic\":\"" + topic + "\",\"data\":" + dataJson + "}", null));
        return response.get("id").asText();
    }

    private Disposable subscribe(String topic, String token, String lastEventId, List<ServerSentEvent<String>> frames) {
        int before = metrics.connections("sse");
        Disposable d = client.get()
                .uri("/push/subscribe?topic=" + topic)
                .headers(h -> {
                    if (token != null) h.setBearerAuth(token);
                    if (lastEventId != null) h.add("Last-Event-ID", lastEventId);
                })
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

    private Socket socket(String session) {
        return socket(session, null);
    }

    private Socket socket(String session, String token) {
        int before = metrics.connections("ws");
        Socket socket = new Socket(wsUri, token);
        socket.send("{\"type\":\"connect\",\"session\":\"" + session + "\""
                + (token == null ? "" : ",\"token\":\"" + token + "\"") + "}");
        socket.await("connected");
        awaitTrue(() -> metrics.connections("ws") == before + 1);
        return socket;
    }

    private static final class Socket implements AutoCloseable {
        private final List<JsonNode> frames = new CopyOnWriteArrayList<>();
        private final Sinks.Many<String> outbound = Sinks.many().unicast().onBackpressureBuffer();
        private final Disposable connection;

        Socket(URI uri, String token) {
            HttpHeaders headers = new HttpHeaders();
            if (token != null) headers.setBearerAuth(token);
            connection = new ReactorNettyWebSocketClient()
                    .execute(uri, headers, session -> {
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

        JsonNode await(String type) {
            return await(type, 1);
        }

        JsonNode await(String type, int nth) {
            awaitTrue(() -> frames.stream()
                            .filter(f -> type.equals(f.path("type").asText()))
                            .count()
                    >= nth);
            return frames.stream()
                    .filter(f -> type.equals(f.path("type").asText()))
                    .skip(nth - 1)
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

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitTrue(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition not met in 30s");
            Thread.onSpinWait();
        }
    }
}
