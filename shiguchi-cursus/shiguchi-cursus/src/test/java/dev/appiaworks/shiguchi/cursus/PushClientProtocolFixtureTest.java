package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Keeps the TypeScript client ({@code shiguchi-cursus-client-js}) and the gateway from drifting apart: the
 * client's tests read {@code frames.json}, and this test drives {@link PushWebSocketHandler} and
 * checks that every frame it emits has the field names the fixture lists. Values (ids, times,
 * messages) are examples and are not compared.
 */
@DisplayName("Push client protocol fixture")
class PushClientProtocolFixtureTest {

    /** Relative to the Gradle project directory, which is the working directory of the tests. */
    private static final Path FIXTURE = Path.of("../../shiguchi-cursus-client-js/test/fixtures/frames.json");

    /** Fixture server frames no test drives: {@code ack} answers only on a gateway with persistent sessions. */
    private static final List<String> NOT_DRIVEN = List.of("acked");

    private final ObjectMapper json = new ObjectMapper();
    private final PushProperties properties = new PushProperties();
    private final MemoryPushBroker broker = new MemoryPushBroker();
    private final MemoryPushStore store = new MemoryPushStore();
    private final PushMetrics metrics = new PushMetrics(new SimpleMeterRegistry());
    private final PushPublisher publisher;
    private final PushWebSocketHandler handler;

    PushClientProtocolFixtureTest() {
        PushProperties.Namespace ns = new PushProperties.Namespace();
        ns.setQos(Qos.AT_LEAST_ONCE);
        ns.setClientPublish(true);
        properties.getNamespaces().put("orders", ns);
        this.publisher = new PushPublisher(properties, store, broker, metrics);
        this.handler = new PushWebSocketHandler(
                properties,
                broker,
                store,
                publisher,
                new TopicAuthorizer(null),
                metrics,
                new PushShutdown(),
                new PushTokenAuthenticator(null, null),
                new PushConnections());
    }

    @Test
    @DisplayName(
            "GIVEN the fixture WHEN the client frames are sent to the handler SHOULD be answered by the server frames the fixture lists")
    void given_fixture_client_frames_when_sent_should_be_answered_with_fixture_shaped_server_frames() throws Exception {
        JsonNode client = fixture().get("client");
        JsonNode server = fixture().get("server");

        try (FakeSocket socket = new FakeSocket()) {
            JsonNode connected = socket.await("connected");
            assertSameFields("connected", server.get("connected"), connected);
            assertSameFields("connected.limits", server.get("connected").get("limits"), connected.get("limits"));

            // The fixture's token is a placeholder; a bare connect with the origin is what a cookie-mode client sends.
            ObjectNode connect = ((ObjectNode) client.get("connect").deepCopy());
            connect.remove("token");
            socket.send(connect);
            socket.send(withTopicsAfter(client.get("sub"), "0"));
            assertSameFields("subscribed", server.get("subscribed"), socket.await("subscribed"));

            operatorPublish(
                    Map.of("tenant", "acme"),
                    "k1",
                    client.get("pub").get("data").toString());
            assertSameFields("event", server.get("event"), socket.await("event"));

            socket.send(client.get("pub"));
            assertSameFields("published", server.get("published"), socket.await("published"));

            socket.send(client.get("ping"));
            assertSameFields("pong", server.get("pong"), socket.await("pong"));

            socket.send(client.get("unsub"));
            assertSameFields("unsubscribed", server.get("unsubscribed"), socket.await("unsubscribed"));

            socket.send(json.readTree("{\"type\":\"nope\"}"));
            assertSameFields("error", server.get("error"), socket.await("error"));

            socket.send(json.readTree("{\"type\":\"sub\",\"topics\":[\"ghost:1\"]}"));
            assertSameFields("errorWithTopic", server.get("errorWithTopic"), socket.awaitError("ghost:1"));

            assertThat(socket.frames("error").stream().map(f -> f.path("code").asText()))
                    .contains("bad_request", "unknown_namespace");
        }
    }

    @Test
    @DisplayName(
            "GIVEN the fixture WHEN the gateway builds its signal and close frames SHOULD have the fixture's field names")
    void given_fixture_signal_frames_when_built_should_have_the_fixture_field_names() throws Exception {
        JsonNode server = fixture().get("server");
        Topic topic = Topic.parse("orders:42");

        assertSameFields("overflow", server.get("overflow"), json.readTree(PushWebSocketHandler.overflow(topic)));
        assertSameFields(
                "overflowSession",
                server.get("overflowSession"),
                json.readTree(PushWebSocketHandler.signalFrame(PushEvent.overflowSignal(topic, 4))));
        assertSameFields(
                "reset",
                server.get("reset"),
                json.readTree(PushWebSocketHandler.signalFrame(PushEvent.resetSignal(topic, "history_gap"))));
        assertSameFields(
                "close",
                server.get("close"),
                json.readTree(PushWebSocketHandler.closeFrame(new CloseStatus(1012, "rebalance"))));
        assertSameFields(
                "closeSessionEnded",
                server.get("closeSessionEnded"),
                json.readTree(PushWebSocketHandler.closeFrame(
                        new CloseStatus(PushWebSocketHandler.SESSION_ENDED, PushCookieSessions.REASON))));
        assertThat(server.get("closeSessionEnded").get("code").asInt()).isEqualTo(4440);
    }

    @Test
    @DisplayName(
            "GIVEN the fixture WHEN its server frame names are listed SHOULD be exactly the ones these tests check, so a new frame cannot slip in untested")
    void given_fixture_when_server_frames_listed_should_all_be_checked() throws Exception {
        TreeSet<String> names = new TreeSet<>();
        fixture().get("server").fieldNames().forEachRemaining(names::add);
        TreeSet<String> checked = new TreeSet<>(List.of(
                "connected",
                "subscribed",
                "event",
                "published",
                "pong",
                "unsubscribed",
                "error",
                "errorWithTopic",
                "overflow",
                "overflowSession",
                "reset",
                "close",
                "closeSessionEnded"));
        checked.addAll(NOT_DRIVEN);

        assertThat(names).isEqualTo(checked);
    }

    @Test
    @DisplayName("GIVEN the fixture WHEN the client frame types are listed SHOULD be types the handler dispatches")
    void given_fixture_when_client_frame_types_listed_should_be_known_to_the_handler() throws Exception {
        TreeSet<String> names = new TreeSet<>();
        fixture().get("client").fieldNames().forEachRemaining(names::add);

        assertThat(names).containsExactly("ack", "connect", "ping", "pub", "refresh", "sub", "unsub");
        fixture()
                .get("client")
                .properties()
                .forEach(f -> assertThat(f.getValue().get("type").asText()).isEqualTo(f.getKey()));
    }

    // ---- helpers ----

    private JsonNode fixture() throws IOException {
        assertThat(FIXTURE)
                .as("the TypeScript package's fixture, found from the module directory")
                .exists();
        return json.readTree(Files.readString(FIXTURE, StandardCharsets.UTF_8));
    }

    private ObjectNode withTopicsAfter(JsonNode sub, String after) {
        ObjectNode copy = sub.deepCopy();
        ObjectNode map = copy.putObject("after");
        copy.get("topics").forEach(t -> map.put(t.asText(), after));
        return copy;
    }

    /** Same top-level field names, whatever the values. */
    private void assertSameFields(String name, JsonNode expected, JsonNode actual) {
        List<String> want = new ArrayList<>();
        expected.fieldNames().forEachRemaining(want::add);
        List<String> got = new ArrayList<>();
        actual.fieldNames().forEachRemaining(got::add);
        assertThat(got).as("fields of the '%s' frame: %s", name, actual).containsExactlyInAnyOrderElementsOf(want);
        assertThat(actual.path("type").asText()).isEqualTo(expected.path("type").asText());
    }

    private void operatorPublish(Map<String, String> attributes, String key, String data) {
        publisher
                .publishAsOperator(
                        Topic.parse("orders:42"),
                        data.getBytes(StandardCharsets.UTF_8),
                        new LinkedHashMap<>(attributes),
                        key,
                        null,
                        null,
                        null,
                        null)
                .block();
    }

    /** A WebSocketSession the handler runs against: frames in through a sink, frames out collected as JSON. */
    private final class FakeSocket implements AutoCloseable {
        private final Sinks.Many<WebSocketMessage> in = Sinks.many().unicast().onBackpressureBuffer();
        private final List<JsonNode> out = new CopyOnWriteArrayList<>();
        private final Disposable running;

        FakeSocket() {
            WebSocketSession session = Mockito.mock(WebSocketSession.class);
            HandshakeInfo info =
                    new HandshakeInfo(URI.create("ws://gw/push/ws"), new HttpHeaders(), Mono.empty(), "push.v1.json");
            Mockito.when(session.getHandshakeInfo()).thenReturn(info);
            Mockito.when(session.receive()).thenReturn(in.asFlux());
            Mockito.when(session.textMessage(ArgumentMatchers.anyString())).thenAnswer(inv -> text(inv.getArgument(0)));
            Mockito.when(session.close()).thenReturn(Mono.empty());
            Mockito.when(session.close(ArgumentMatchers.any())).thenReturn(Mono.empty());
            Mockito.when(session.send(ArgumentMatchers.any()))
                    .thenAnswer(inv -> Flux.from((Publisher<WebSocketMessage>) inv.getArgument(0))
                            .doOnNext(m -> out.add(read(m.getPayloadAsText())))
                            .then());
            running = handler.handle(session).subscribe();
        }

        void send(JsonNode frame) {
            in.tryEmitNext(text(frame.toString()));
        }

        JsonNode await(String type) throws InterruptedException {
            return awaitFrame(f -> type.equals(f.path("type").asText()), "'" + type + "' frame");
        }

        JsonNode awaitError(String topic) throws InterruptedException {
            return awaitFrame(
                    f -> "error".equals(f.path("type").asText())
                            && topic.equals(f.path("topic").asText()),
                    "error frame for " + topic);
        }

        private JsonNode awaitFrame(java.util.function.Predicate<JsonNode> match, String what)
                throws InterruptedException {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline) {
                for (JsonNode f : out) if (match.test(f)) return f;
                Thread.sleep(10);
            }
            throw new AssertionError("no " + what + " within 5 s; saw " + out);
        }

        List<JsonNode> frames(String type) {
            return out.stream()
                    .filter(f -> type.equals(f.path("type").asText()))
                    .toList();
        }

        @Override
        public void close() {
            in.tryEmitComplete();
            running.dispose();
        }

        private WebSocketMessage text(String payload) {
            return new WebSocketMessage(
                    WebSocketMessage.Type.TEXT,
                    DefaultDataBufferFactory.sharedInstance.wrap(payload.getBytes(StandardCharsets.UTF_8)));
        }

        private JsonNode read(String text) {
            try {
                return json.readTree(text);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
