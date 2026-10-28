package dev.appiaworks.shiguchi.cursus.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.appiaworks.shiguchi.cursus.PushMetrics;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * The WebSocket transport on the Boot 4 gateway over a real port (mirrors
 * {@code PushWebSocketEndToEndTest} in spring-boot3/shiguchi-cursus-starter; the push module is
 * byte-identical between the lines): {@code push.v1.json} frames.
 * Subscribe, receive events published over HTTP, several topics on one socket, unsubscribe,
 * resume with {@code after}, publish from the client, the error frames, a browser origin refused
 * at the handshake, and the lifetime ending the socket with a {@code close} frame and 1012.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.graphql.enabled=false",
            "shiguchi.cursus.enabled=true",
            "shiguchi.cursus.namespaces.ticker.publish-auth=NONE",
            "shiguchi.cursus.namespaces.orders.qos=AT_LEAST_ONCE",
            "shiguchi.cursus.namespaces.orders.history-size=100",
            "shiguchi.cursus.namespaces.orders.publish-auth=NONE",
            "shiguchi.cursus.namespaces.inbox.auth=AUTHENTICATED",
            "shiguchi.cursus.namespaces.chat.publish-auth=NONE",
            "shiguchi.cursus.namespaces.chat.client-publish=true",
            "shiguchi.cursus.connection.keepalive=200ms",
            "shiguchi.cursus.connection.max-lifetime=4s",
            "shiguchi.cursus.connection.lifetime-jitter=0"
        })
@DisplayName("Push over WebSocket end to end (Boot 4)")
class PushWebSocketEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private static final ObjectMapper JSON = new ObjectMapper();

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
            "GIVEN a socket subscribed to a topic WHEN events are published over HTTP SHOULD deliver event frames in order after a subscribed frame")
    void given_subscribed_when_published_should_deliverEventFrames() {
        try (Socket ws = connect()) {
            ws.send("{\"type\":\"sub\",\"topics\":[\"ticker:btc\"]}");
            JsonNode subscribed = ws.await("subscribed");
            assertThat(subscribed.get("topic").asText()).isEqualTo("ticker:btc");
            assertThat(subscribed.get("qos").asText()).isEqualTo("AT_MOST_ONCE");
            for (int i = 1; i <= 3; i++) publish("ticker:btc", "{\"price\":" + i + "}");
            List<JsonNode> events = ws.awaitEvents(3);
            assertThat(events)
                    .extracting(e -> e.get("data").get("price").asInt())
                    .containsExactly(1, 2, 3);
            assertThat(events).extracting(e -> e.get("topic").asText()).containsOnly("ticker:btc");
            assertThat(events)
                    .extracting(e -> e.get("id").asText())
                    .doesNotContainNull()
                    .isSorted();
            assertThat(ws.first("connected").get("connectionId").asText()).isNotBlank();
        }
    }

    @Test
    @DisplayName(
            "GIVEN two topics on one socket WHEN one is unsubscribed SHOULD deliver only the other's events afterwards")
    void given_twoTopics_when_oneUnsubscribed_should_deliverOnlyTheOther() {
        try (Socket ws = connect()) {
            ws.send("{\"type\":\"sub\",\"topics\":[\"ticker:btc\",\"ticker:eth\"]}");
            ws.await("subscribed");
            ws.await("subscribed", 2);
            ws.send("{\"type\":\"unsub\",\"topics\":[\"ticker:btc\"]}");
            assertThat(ws.await("unsubscribed").get("topic").asText()).isEqualTo("ticker:btc");
            publish("ticker:btc", "\"b\"");
            publish("ticker:eth", "\"e\"");
            List<JsonNode> events = ws.awaitEvents(1);
            sleep(300);
            assertThat(ws.events()).hasSize(1);
            assertThat(events.get(0).get("topic").asText()).isEqualTo("ticker:eth");
        }
    }

    @Test
    @DisplayName(
            "GIVEN events kept on an AT_LEAST_ONCE topic WHEN a socket subscribes with after SHOULD replay what it missed then stream live")
    void given_history_when_subscribedWithAfter_should_replayThenLive() {
        String first = publishedId("orders:9", "{\"n\":1}");
        publish("orders:9", "{\"n\":2}");
        publish("orders:9", "{\"n\":3}");
        try (Socket ws = connect()) {
            ws.send("{\"type\":\"sub\",\"topics\":[\"orders:9\"],\"after\":\"" + first + "\"}");
            JsonNode subscribed = ws.await("subscribed");
            assertThat(subscribed.get("position").asText()).isEqualTo(first);
            assertThat(subscribed.get("qos").asText()).isEqualTo("AT_LEAST_ONCE");
            List<JsonNode> replayed = ws.awaitEvents(2);
            assertThat(replayed).extracting(e -> e.get("data").get("n").asInt()).containsExactly(2, 3);
            publish("orders:9", "{\"n\":4}");
            assertThat(ws.awaitEvents(3).get(2).get("data").get("n").asInt()).isEqualTo(4);
        }
    }

    @Test
    @DisplayName(
            "GIVEN a namespace that lets clients publish WHEN a socket sends pub SHOULD answer published and deliver to the other socket")
    void given_clientPublish_when_pubFrame_should_publishAndDeliver() {
        try (Socket a = connect();
                Socket b = connect()) {
            a.send("{\"type\":\"sub\",\"topics\":[\"chat:room1\"]}");
            a.await("subscribed");
            b.send("{\"type\":\"pub\",\"topic\":\"chat:room1\",\"data\":{\"text\":\"hi\"}}");
            JsonNode published = b.await("published");
            assertThat(published.get("topic").asText()).isEqualTo("chat:room1");
            assertThat(published.get("id").asText()).isNotBlank();
            assertThat(a.awaitEvents(1).get(0).get("data").get("text").asText()).isEqualTo("hi");
        }
    }

    @Test
    @DisplayName("GIVEN wrong frames WHEN sent SHOULD answer error frames with a code and keep the socket open")
    void given_badFrames_when_sent_should_answerErrorFrames() {
        try (Socket ws = connect()) {
            ws.send("not json");
            assertThat(ws.await("error").get("code").asText()).isEqualTo("bad_request");
            ws.send("{\"type\":\"sub\",\"topics\":[\"nope:x\"]}");
            assertThat(ws.await("error", 2).get("code").asText()).isEqualTo("unknown_namespace");
            ws.send("{\"type\":\"sub\",\"topics\":[\"inbox:alice\"]}");
            assertThat(ws.await("error", 3).get("code").asText()).isEqualTo("unauthenticated");
            ws.send("{\"type\":\"pub\",\"topic\":\"ticker:btc\",\"data\":1}");
            assertThat(ws.await("error", 4).get("code").asText()).isEqualTo("forbidden");
            ws.send("{\"type\":\"connect\",\"token\":\"x.y.z\"}");
            assertThat(ws.await("error", 5).get("code").asText()).isEqualTo("unauthenticated");
            ws.send("{\"type\":\"ping\"}");
            ws.await("pong");
            ws.send("{\"type\":\"ack\",\"ids\":[\"1\"]}");
            ws.send("{\"type\":\"sub\",\"topics\":[\"ticker:btc\"]}");
            ws.await("subscribed");
        }
    }

    @Test
    @DisplayName("GIVEN a browser origin that is not the gateway's WHEN it opens the socket SHOULD close it with 1008")
    void given_foreignOrigin_when_handshake_should_closeWithPolicyViolation() {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("https://evil.example");
        Sinks.One<CloseStatus> status = Sinks.one();
        new ReactorNettyWebSocketClient()
                .execute(
                        wsUri,
                        headers,
                        session -> session.closeStatus()
                                .doOnNext(status::tryEmitValue)
                                .then(session.receive().then()))
                .onErrorResume(e -> Mono.empty())
                .block(Duration.ofSeconds(30));
        CloseStatus closed = status.asMono().block(Duration.ofSeconds(30));
        assertThat(closed).isNotNull();
        assertThat(closed.getCode()).isEqualTo(1008);
    }

    @Test
    @DisplayName(
            "GIVEN the connection lifetime WHEN it elapses SHOULD send a close frame then close the socket with 1012 and release the gauge")
    void given_lifetime_when_elapsed_should_closeFrameThen1012() {
        List<JsonNode> frames = new CopyOnWriteArrayList<>();
        Sinks.One<CloseStatus> status = Sinks.one();
        new ReactorNettyWebSocketClient()
                .execute(wsUri, session -> {
                    session.closeStatus().subscribe(status::tryEmitValue);
                    Mono<Void> send = session.send(
                            Mono.just(session.textMessage("{\"type\":\"sub\",\"topics\":[\"ticker:life\"]}")));
                    return send.then(session.receive()
                            .map(WebSocketMessage::getPayloadAsText)
                            .doOnNext(t -> frames.add(parse(t)))
                            .then());
                })
                .block(Duration.ofSeconds(30));
        JsonNode last = frames.get(frames.size() - 1);
        assertThat(last.get("type").asText()).isEqualTo("close");
        assertThat(last.get("code").asInt()).isEqualTo(1012);
        assertThat(last.get("reason").asText()).isEqualTo("rebalance");
        CloseStatus closed = status.asMono().block(Duration.ofSeconds(30));
        assertThat(closed.getCode()).isEqualTo(1012);
        awaitTrue(() -> metrics.connections("ws") == 0);
    }

    // ---- helpers ----

    private void publish(String topic, String dataJson) {
        int status = client.post()
                .uri("/push/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"topic\":\"" + topic + "\",\"data\":" + dataJson + "}")
                .exchangeToMono(r -> Mono.just(r.statusCode().value()))
                .block(Duration.ofSeconds(30));
        assertThat(status).isEqualTo(200);
    }

    private String publishedId(String topic, String dataJson) {
        String body = client.post()
                .uri("/push/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"topic\":\"" + topic + "\",\"data\":" + dataJson + "}")
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(30));
        int at = body.indexOf("\"id\":\"") + 6;
        return body.substring(at, body.indexOf('"', at));
    }

    private static JsonNode parse(String text) {
        try {
            return JSON.readTree(text);
        } catch (java.io.IOException e) {
            throw new AssertionError(text, e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition not met in 30s");
            Thread.onSpinWait();
        }
    }

    /** Opens a socket and waits for its {@code connected} frame. */
    private Socket connect() {
        Socket socket = new Socket(wsUri);
        socket.await("connected");
        return socket;
    }

    private static final class Socket implements AutoCloseable {
        private final List<JsonNode> frames = new CopyOnWriteArrayList<>();
        private final Sinks.Many<String> outbound = Sinks.many().unicast().onBackpressureBuffer();
        private final Disposable connection;

        Socket(URI uri) {
            connection = new ReactorNettyWebSocketClient()
                    .execute(uri, session -> {
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

        JsonNode first(String type) {
            return frames.stream()
                    .filter(f -> type.equals(f.path("type").asText()))
                    .findFirst()
                    .orElseThrow();
        }

        JsonNode await(String type) {
            return await(type, 1);
        }

        /** The {@code nth} frame of {@code type}, waiting for it. */
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

        List<JsonNode> events() {
            return frames.stream()
                    .filter(f -> "event".equals(f.path("type").asText()))
                    .toList();
        }

        List<JsonNode> awaitEvents(int count) {
            awaitTrue(() -> events().size() >= count);
            return events();
        }

        @Override
        public void close() {
            outbound.tryEmitComplete();
            connection.dispose();
        }
    }
}
