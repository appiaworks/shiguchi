package dev.appiaworks.shiguchi.cursus.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import dev.appiaworks.shiguchi.cursus.PushHook;
import dev.appiaworks.shiguchi.proto.cursus.ClientMessage;
import dev.appiaworks.shiguchi.proto.cursus.ClientMessageResult;
import dev.appiaworks.shiguchi.proto.cursus.ConnectDecision;
import dev.appiaworks.shiguchi.proto.cursus.ConnectionInfo;
import dev.appiaworks.shiguchi.proto.cursus.SubscribeDecision;
import dev.appiaworks.shiguchi.proto.cursus.SubscribeHook;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Hook decisions on the Boot 3 gateway over a real port (mirrors
 * {@code PushHookDecisionsEndToEndTest} in spring-boot4/shiguchi-cursus-starter): an in-process {@link PushHook} bean
 * rejects a subscribe (and, when switched on, every connection) with a reason, rewrites a
 * subscribed topic, subscribes a connection to a topic on the server side, and drops, rejects or
 * rewrites what a WebSocket client publishes.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.graphql.enabled=false",
            "shiguchi.cursus.enabled=true",
            "shiguchi.cursus.namespaces.ticker.publish-auth=NONE",
            "shiguchi.cursus.namespaces.chat.publish-auth=NONE",
            "shiguchi.cursus.namespaces.chat.client-publish=true",
            "shiguchi.cursus.connection.keepalive=200ms",
            "shiguchi.cursus.connection.max-lifetime=0"
        })
@DisplayName("Push hook decisions end to end (Boot 3)")
class PushHookDecisionsEndToEndTest {

    /** What the test hook does next; reset after every test. */
    static volatile boolean rejectConnections;

    static volatile String connectSubscribes;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {
        @Bean
        PushHook decidingHook() {
            return new PushHook() {
                @Override
                public Mono<ConnectDecision> decideConnect(ConnectionInfo connection) {
                    if (rejectConnections) {
                        return Mono.just(ConnectDecision.newBuilder()
                                .setReject(true)
                                .setReason("closed for maintenance")
                                .build());
                    }
                    String topic = connectSubscribes;
                    return topic == null
                            ? Mono.empty()
                            : Mono.just(ConnectDecision.newBuilder()
                                    .addSubscribe(topic)
                                    .build());
                }

                @Override
                public Mono<SubscribeDecision> decideSubscribe(SubscribeHook subscription) {
                    if (subscription.getTopicsList().contains("ticker:blocked")) {
                        return Mono.just(SubscribeDecision.newBuilder()
                                .setReject(true)
                                .setReason("blocked by policy")
                                .build());
                    }
                    if (subscription.getTopicsList().contains("ticker:orig")) {
                        return Mono.just(SubscribeDecision.newBuilder()
                                .addTopics("ticker:rewritten")
                                .build());
                    }
                    return Mono.empty();
                }

                @Override
                public Mono<ClientMessageResult> onClientMessage(ClientMessage message) {
                    String text = message.getPayload().toStringUtf8();
                    if (text.contains("drop")) {
                        return Mono.just(ClientMessageResult.newBuilder()
                                .setAction(ClientMessageResult.Action.DROP)
                                .build());
                    }
                    if (text.contains("reject")) {
                        return Mono.just(ClientMessageResult.newBuilder()
                                .setAction(ClientMessageResult.Action.REJECT)
                                .setReason("no spam")
                                .build());
                    }
                    if (text.contains("rewrite")) {
                        return Mono.just(ClientMessageResult.newBuilder()
                                .setPayload(ByteString.copyFromUtf8("{\"text\":\"rewritten\"}"))
                                .build());
                    }
                    return Mono.empty();
                }
            };
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE =
            new ParameterizedTypeReference<>() {};

    @Autowired
    private Environment environment;

    private WebClient client;
    private URI wsUri;

    @BeforeEach
    void client() {
        String port = environment.getRequiredProperty("local.server.port");
        client = WebClient.create("http://localhost:" + port);
        wsUri = URI.create("ws://localhost:" + port + "/push/ws");
    }

    @AfterEach
    void reset() {
        rejectConnections = false;
        connectSubscribes = null;
    }

    @Test
    @DisplayName(
            "GIVEN a hook that rejects a topic WHEN a client subscribes over SSE SHOULD answer 403 with the hook's reason in the body")
    void given_rejectingHook_when_sseSubscribe_should_answer403WithReason() {
        String[] response = exchange("/push/subscribe?topic=ticker:blocked");

        assertThat(response[0]).isEqualTo("403");
        JsonNode body = parse(response[1]);
        assertThat(body.get("error").asText()).isEqualTo("forbidden");
        assertThat(body.get("message").asText()).isEqualTo("blocked by policy");
    }

    @Test
    @DisplayName(
            "GIVEN a hook that rejects every connection WHEN a client subscribes over SSE SHOULD answer 403 with the connect reason")
    void given_connectRejected_when_sseSubscribe_should_answer403WithReason() {
        rejectConnections = true;

        String[] response = exchange("/push/subscribe?topic=ticker:btc");

        assertThat(response[0]).isEqualTo("403");
        assertThat(parse(response[1]).get("message").asText()).isEqualTo("closed for maintenance");
    }

    @Test
    @DisplayName(
            "GIVEN a hook that rewrites ticker:orig to ticker:rewritten WHEN a client subscribes over SSE SHOULD receive events published to the rewritten topic")
    void given_rewritingHook_when_sseSubscribe_should_receiveRewrittenTopicEvents() {
        List<ServerSentEvent<String>> frames = new CopyOnWriteArrayList<>();
        Disposable stream = subscribe("ticker:orig", frames);
        try {
            awaitTrue(() -> {
                publish("ticker:rewritten", "{\"price\":7}");
                sleep(50);
                return !frames.isEmpty();
            });
            assertThat(frames.get(0).event()).isEqualTo("ticker:rewritten");
            assertThat(parse(frames.get(0).data()).get("price").asInt()).isEqualTo(7);
        } finally {
            stream.dispose();
        }
    }

    @Test
    @DisplayName(
            "GIVEN a hook that subscribes every connection to ticker:auto WHEN a client subscribes to another topic over SSE SHOULD also receive events of ticker:auto")
    void given_connectSubscribingHook_when_sseSubscribe_should_receiveServerSideTopic() {
        connectSubscribes = "ticker:auto";
        List<ServerSentEvent<String>> frames = new CopyOnWriteArrayList<>();
        Disposable stream = subscribe("ticker:other", frames);
        try {
            awaitTrue(() -> {
                publish("ticker:auto", "{\"price\":9}");
                sleep(50);
                return !frames.isEmpty();
            });
            assertThat(frames.get(0).event()).isEqualTo("ticker:auto");
        } finally {
            stream.dispose();
        }
    }

    @Test
    @DisplayName(
            "GIVEN a hook that drops a message WHEN a socket publishes it SHOULD ack published but never deliver it to a subscriber, while the next message flows")
    void given_droppingHook_when_socketPublishes_should_ackButNotDeliver() {
        try (Socket a = connect();
                Socket b = connect()) {
            a.send("{\"type\":\"sub\",\"topics\":[\"chat:room1\"]}");
            a.await("subscribed");

            b.send("{\"type\":\"pub\",\"topic\":\"chat:room1\",\"data\":{\"text\":\"drop me\"}}");
            JsonNode ack = b.await("published");
            assertThat(ack.get("topic").asText()).isEqualTo("chat:room1");
            assertThat(ack.get("id").asText()).isNotBlank();

            b.send("{\"type\":\"pub\",\"topic\":\"chat:room1\",\"data\":{\"text\":\"hello\"}}");
            b.await("published", 2);
            List<JsonNode> events = a.awaitEvents(1);
            sleep(300);
            assertThat(a.events()).hasSize(1);
            assertThat(events.get(0).get("data").get("text").asText()).isEqualTo("hello");
        }
    }

    @Test
    @DisplayName(
            "GIVEN a hook that rejects a message WHEN a socket publishes it SHOULD answer a forbidden error frame with the reason and deliver nothing")
    void given_rejectingHook_when_socketPublishes_should_answerForbiddenFrame() {
        try (Socket a = connect();
                Socket b = connect()) {
            a.send("{\"type\":\"sub\",\"topics\":[\"chat:room1\"]}");
            a.await("subscribed");

            b.send("{\"type\":\"pub\",\"topic\":\"chat:room1\",\"data\":{\"text\":\"reject me\"}}");
            JsonNode error = b.await("error");
            assertThat(error.get("code").asText()).isEqualTo("forbidden");
            assertThat(error.get("message").asText()).isEqualTo("no spam");

            sleep(300);
            assertThat(a.events()).isEmpty();
        }
    }

    @Test
    @DisplayName(
            "GIVEN a hook that rewrites a payload WHEN a socket publishes it SHOULD deliver the rewritten payload to the subscriber")
    void given_rewritingHook_when_socketPublishes_should_deliverRewrittenPayload() {
        try (Socket a = connect();
                Socket b = connect()) {
            a.send("{\"type\":\"sub\",\"topics\":[\"chat:room1\"]}");
            a.await("subscribed");

            b.send("{\"type\":\"pub\",\"topic\":\"chat:room1\",\"data\":{\"text\":\"rewrite me\"}}");
            b.await("published");

            assertThat(a.awaitEvents(1).get(0).get("data").get("text").asText()).isEqualTo("rewritten");
        }
    }

    @Test
    @DisplayName(
            "GIVEN a hook that rejects a topic WHEN a socket subscribes to it SHOULD answer a forbidden error frame and keep the socket open")
    void given_rejectingHook_when_socketSubscribes_should_answerForbiddenFrame() {
        try (Socket ws = connect()) {
            ws.send("{\"type\":\"sub\",\"topics\":[\"ticker:blocked\"]}");

            JsonNode error = ws.await("error");
            assertThat(error.get("code").asText()).isEqualTo("forbidden");
            assertThat(error.get("message").asText()).isEqualTo("blocked by policy");
            ws.send("{\"type\":\"ping\"}");
            ws.await("pong");
        }
    }

    // ---- helpers ----

    private Disposable subscribe(String topic, List<ServerSentEvent<String>> frames) {
        return client.get()
                .uri("/push/subscribe?topic=" + topic)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(SSE)
                .filter(e -> e.event() != null && !"close".equals(e.event()))
                .subscribe(frames::add);
    }

    private String[] exchange(String path) {
        return client.get()
                .uri(path)
                .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                .exchangeToMono(r -> r.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(b -> new String[] {String.valueOf(r.statusCode().value()), b}))
                .block(Duration.ofSeconds(30));
    }

    private void publish(String topic, String dataJson) {
        int status = client.post()
                .uri("/push/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"topic\":\"" + topic + "\",\"data\":" + dataJson + "}")
                .exchangeToMono(r -> Mono.just(r.statusCode().value()))
                .block(Duration.ofSeconds(30));
        assertThat(status).isEqualTo(200);
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

    private static void awaitTrue(BooleanSupplier condition) {
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
