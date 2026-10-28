package dev.appiaworks.shiguchi.cursus.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.appiaworks.shiguchi.cursus.PushMetrics;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
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
 * Consumer-group ordering on the Boot 4 gateway over a real port, on the in-memory store (mirrors {@code PushOrderingEndToEndTest} in spring-boot3/shiguchi-cursus-starter; the push module is byte-identical between the lines):
 * a {@code STRICT} group has one active member and hands over when it leaves; a {@code KEY} group
 * never splits a key across members and keeps ids of one key in order; an admin seek moves a
 * group back so it redelivers.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.graphql.enabled=false",
            "shiguchi.cursus.enabled=true",
            "shiguchi.cursus.admin.role=PUSH_ADMIN",
            "shiguchi.cursus.namespaces.strict.qos=AT_LEAST_ONCE",
            "shiguchi.cursus.namespaces.strict.history-size=100",
            "shiguchi.cursus.namespaces.strict.session-expiry=1h",
            "shiguchi.cursus.namespaces.strict.session-key=GROUP",
            "shiguchi.cursus.namespaces.strict.ordering=STRICT",
            "shiguchi.cursus.namespaces.strict.ack-deadline=700ms",
            "shiguchi.cursus.namespaces.strict.max-inflight=10",
            "shiguchi.cursus.namespaces.strict.publish-auth=NONE",
            "shiguchi.cursus.namespaces.keyed.qos=AT_LEAST_ONCE",
            "shiguchi.cursus.namespaces.keyed.history-size=100",
            "shiguchi.cursus.namespaces.keyed.session-expiry=1h",
            "shiguchi.cursus.namespaces.keyed.session-key=GROUP",
            "shiguchi.cursus.namespaces.keyed.ordering=KEY",
            "shiguchi.cursus.namespaces.keyed.ack-deadline=30s",
            "shiguchi.cursus.namespaces.keyed.max-inflight=1",
            "shiguchi.cursus.namespaces.keyed.publish-auth=NONE",
            "shiguchi.cursus.namespaces.jobs.qos=AT_LEAST_ONCE",
            "shiguchi.cursus.namespaces.jobs.history-size=100",
            "shiguchi.cursus.namespaces.jobs.session-expiry=1h",
            "shiguchi.cursus.namespaces.jobs.session-key=GROUP",
            "shiguchi.cursus.namespaces.jobs.ack-deadline=700ms",
            "shiguchi.cursus.namespaces.jobs.max-deliveries=10",
            "shiguchi.cursus.namespaces.jobs.max-inflight=10",
            "shiguchi.cursus.namespaces.jobs.publish-auth=NONE",
            "shiguchi.cursus.connection.keepalive=200ms",
            "shiguchi.cursus.connection.max-lifetime=0",
            "management.endpoints.web.exposure.include=gateway-push,health"
        })
@DisplayName("Push consumer-group ordering and seek end to end (Boot 4)")
class PushOrderingEndToEndTest {

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
            "GIVEN a STRICT group with two members WHEN events are published and the active member leaves SHOULD deliver only to the active one, then to the other")
    void given_strictGroup_when_activeMemberLeaves_should_handOver() {
        String topic = "strict:t1";
        try (Socket b = socket()) {
            try (Socket a = socket()) {
                a.send("{\"type\":\"sub\",\"topics\":[\"" + topic + "\"],\"group\":\"sg\",\"member\":\"a\"}");
                a.await("subscribed");
                String e0 = publish(topic, "{\"n\":0}");
                awaitTrue(() -> a.events().contains(e0));
                ackAll(a, topic);

                b.send("{\"type\":\"sub\",\"topics\":[\"" + topic + "\"],\"group\":\"sg\",\"member\":\"b\"}");
                b.await("subscribed");
                String e1 = publish(topic, "{\"n\":1}");
                String e2 = publish(topic, "{\"n\":2}");
                awaitTrue(() -> a.events().containsAll(List.of(e1, e2)));
                sleep(Duration.ofMillis(800));
                assertThat(b.events()).as("only the active member receives").isEmpty();
                ackAll(a, topic);
            }
            String e3 = publish(topic, "{\"n\":3}");
            awaitTrue(() -> b.events().contains(e3));
        }
        awaitTrue(() -> metrics.connections("ws") == 0);
    }

    @Test
    @DisplayName(
            "GIVEN a KEY group with two members at max-inflight 1 WHEN events of two keys are published SHOULD keep each key at one member, in order, until it acks")
    void given_keyGroup_when_eventsOfTwoKeysPublished_should_keepEachKeyAtOneMemberInOrder() {
        String topic = "keyed:t1";
        try (Socket a = socket();
                Socket b = socket()) {
            a.send("{\"type\":\"sub\",\"topics\":[\"" + topic + "\"],\"group\":\"kg\",\"member\":\"a\"}");
            b.send("{\"type\":\"sub\",\"topics\":[\"" + topic + "\"],\"group\":\"kg\",\"member\":\"b\"}");
            a.await("subscribed");
            b.await("subscribed");

            String a1 = publishKeyed(topic, "a", "{\"n\":1}");
            String b1 = publishKeyed(topic, "b", "{\"n\":2}");
            String a2 = publishKeyed(topic, "a", "{\"n\":3}");
            String b2 = publishKeyed(topic, "b", "{\"n\":4}");
            awaitTrue(() -> a.events().size() + b.events().size() >= 2);
            sleep(Duration.ofMillis(500));

            Socket ownerA = a.events().contains(a1) ? a : b;
            Socket ownerB = ownerA == a ? b : a;
            assertThat(ownerA.events())
                    .as("key a at one member, first event first")
                    .containsExactly(a1);
            assertThat(ownerB.events()).as("key b at the other member").containsExactly(b1);

            ownerA.send("{\"type\":\"ack\",\"topic\":\"" + topic + "\",\"id\":\"" + a1 + "\"}");
            awaitTrue(() -> ownerA.events().contains(a2));
            ownerB.send("{\"type\":\"ack\",\"topic\":\"" + topic + "\",\"id\":\"" + b1 + "\"}");
            awaitTrue(() -> ownerB.events().contains(b2));
            assertThat(ownerA.events()).containsExactly(a1, a2);
            assertThat(ownerB.events()).containsExactly(b1, b2);
        }
        awaitTrue(() -> metrics.connections("ws") == 0);
    }

    @Test
    @DisplayName(
            "GIVEN a group that acked an event WHEN an admin seeks it to first SHOULD redeliver the event, refuse an anonymous seek, answer 404 for an unknown group and audit the seek")
    void given_group_when_adminSeeksToFirst_should_redeliver() {
        String topic = "jobs:seek";
        try (Socket m = socket()) {
            m.send("{\"type\":\"sub\",\"topics\":[\"" + topic + "\"],\"group\":\"sk\",\"member\":\"m\"}");
            m.await("subscribed");
            String e1 = publish(topic, "{\"n\":1}");
            awaitTrue(() -> m.events().contains(e1));
            ackAll(m, topic);
            awaitTrue(() -> get(ADMIN + "/groups?q=sk", null)
                            .get("groups")
                            .get(0)
                            .get("pending")
                            .asLong()
                    == 0);

            String seek = "{\"topic\":\"" + topic + "\",\"to\":\"first\"}";
            assertThat(status("POST", ADMIN + "/groups/sk/seek", seek, null)).isEqualTo(401);
            assertThat(status("POST", ADMIN + "/groups/nobody/seek", seek, ADMIN_TOKEN))
                    .isEqualTo(404);
            assertThat(status("POST", ADMIN + "/groups/sk/seek", "{\"topic\":\"" + topic + "\"}", ADMIN_TOKEN))
                    .isEqualTo(400);
            assertThat(body(exchange("POST", ADMIN + "/groups/sk/seek", seek, ADMIN_TOKEN))
                            .get("seeked")
                            .asBoolean())
                    .isTrue();

            awaitTrue(() -> m.events().stream().filter(e1::equals).count() >= 2);
            assertThat(get(ADMIN + "/audit", null).get("audit"))
                    .extracting(a -> a.get("action").asText())
                    .contains("seek_group");
        }
        awaitTrue(() -> metrics.connections("ws") == 0);
    }

    // ---- helpers ----

    private String publishKeyed(String topic, String key, String dataJson) {
        return body(exchange(
                        "POST",
                        "/push/publish",
                        "{\"topic\":\"" + topic + "\",\"key\":\"" + key + "\",\"data\":" + dataJson + "}",
                        null))
                .get("id")
                .asText();
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

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
