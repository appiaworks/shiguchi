package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

@DisplayName("Push WebSocket handler")
class PushWebSocketHandlerTest {

    private final PushProperties properties = new PushProperties();
    private final MemoryPushBroker broker = new MemoryPushBroker();
    private final MemoryPushStore store = new MemoryPushStore();
    private final PushMetrics metrics = new PushMetrics(new SimpleMeterRegistry());
    private final PushPublisher publisher;
    private final PushWebSocketHandler handler;
    private final ObjectMapper json = new ObjectMapper();

    PushWebSocketHandlerTest() {
        // Set up a namespace for testing
        PushProperties.Namespace ns = new PushProperties.Namespace();
        ns.setQos(Qos.AT_LEAST_ONCE);
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

    // ---- originAllowed tests ----

    @Test
    @DisplayName("originAllowed with null Origin header allows access")
    void given_null_origin_when_checked_should_allow() {
        assertThat(handler.originAllowed(null, "gw:8080")).isTrue();
    }

    @Test
    @DisplayName("originAllowed with blank Origin header allows access")
    void given_blank_origin_when_checked_should_allow() {
        assertThat(handler.originAllowed("", "gw:8080")).isTrue();
    }

    @Test
    @DisplayName("originAllowed allows matching origin and host")
    void given_matching_origin_and_host_when_checked_should_allow() {
        assertThat(handler.originAllowed("http://gw:8080", "gw:8080")).isTrue();
    }

    @Test
    @DisplayName("originAllowed rejects mismatched ports")
    void given_mismatched_ports_when_checked_should_reject() {
        assertThat(handler.originAllowed("http://gw:8080", "gw:9090")).isFalse();
    }

    @Test
    @DisplayName("originAllowed infers port 443 for https by default")
    void given_https_origin_with_default_port_when_checked_should_allow() {
        assertThat(handler.originAllowed("https://gw", "gw:443")).isTrue();
    }

    @Test
    @DisplayName("originAllowed infers port 80 for http by default")
    void given_http_origin_with_default_port_when_checked_should_allow() {
        assertThat(handler.originAllowed("http://gw", "gw")).isTrue();
    }

    @Test
    @DisplayName("originAllowed rejects evil origins when not in allowlist")
    void given_evil_origin_not_in_allowlist_when_checked_should_reject() {
        assertThat(handler.originAllowed("http://evil.example", "gw:8080")).isFalse();
    }

    @Test
    @DisplayName("originAllowed allows configured origins regardless of host")
    void given_origin_in_allowlist_when_checked_should_allow() {
        properties.getWs().setAllowedOrigins(List.of("https://app.example"));
        assertThat(handler.originAllowed("https://app.example", "gw:8080")).isTrue();
    }

    @Test
    @DisplayName("originAllowed rejects origins not in restrictive allowlist")
    void given_origin_not_in_allowlist_when_checked_should_reject() {
        properties.getWs().setAllowedOrigins(List.of("https://app.example"));
        assertThat(handler.originAllowed("https://other.example", "gw:8080")).isFalse();
    }

    @Test
    @DisplayName("originAllowed allows any origin when allowlist is wildcard")
    void given_wildcard_allowlist_when_checked_should_allow_any() {
        properties.getWs().setAllowedOrigins(List.of("*"));
        assertThat(handler.originAllowed("https://any.example", "gw:8080")).isTrue();
        assertThat(handler.originAllowed("http://another.example:9090", "gw:8080"))
                .isTrue();
    }

    // ---- getSubProtocols test ----

    @Test
    @DisplayName("getSubProtocols returns push.v1.json")
    void given_handler_when_subprotocols_requested_should_return_push_v1_json() {
        assertThat(handler.getSubProtocols()).containsExactly("push.v1.json");
    }

    // ---- Frame builder tests ----

    @Test
    @DisplayName("eventFrame produces JSON with type, topic, id, data, and time")
    void given_push_event_when_frame_built_should_have_all_fields() throws Exception {
        Topic topic = Topic.parse("orders:123");
        byte[] data = "{\"a\":1}".getBytes();
        Instant time = Instant.parse("2026-10-08T12:00:00Z");
        PushEvent event = new PushEvent(topic, "ev-42", data, Map.of(), null, time);

        String frame = PushWebSocketHandler.eventFrame(event);
        JsonNode node = json.readTree(frame);

        assertThat(node.get("type").asText()).isEqualTo("event");
        assertThat(node.get("topic").asText()).isEqualTo("orders:123");
        assertThat(node.get("id").asText()).isEqualTo("ev-42");
        assertThat(node.get("data")).isNotNull();
        assertThat(node.get("data").get("a").asInt()).isEqualTo(1); // data embedded raw
        assertThat(node.get("time").asLong()).isEqualTo(time.toEpochMilli());
    }

    @Test
    @DisplayName("eventFrame includes attributes when present")
    void given_push_event_with_attributes_when_frame_built_should_include_attributes() throws Exception {
        Topic topic = Topic.parse("orders:456");
        byte[] data = "{}".getBytes();
        PushEvent event =
                new PushEvent(topic, "ev-43", data, Map.of("from", "alice", "region", "us-west"), null, Instant.now());

        String frame = PushWebSocketHandler.eventFrame(event);
        JsonNode node = json.readTree(frame);

        assertThat(node.get("attributes")).isNotNull();
        assertThat(node.get("attributes").get("from").asText()).isEqualTo("alice");
        assertThat(node.get("attributes").get("region").asText()).isEqualTo("us-west");
    }

    @Test
    @DisplayName("eventFrame omits attributes when empty")
    void given_push_event_without_attributes_when_frame_built_should_not_include_attributes() throws Exception {
        Topic topic = Topic.parse("orders:789");
        byte[] data = "{}".getBytes();
        PushEvent event = new PushEvent(topic, "ev-44", data, Map.of(), null, Instant.now());

        String frame = PushWebSocketHandler.eventFrame(event);
        JsonNode node = json.readTree(frame);

        assertThat(node.get("attributes")).isNull();
    }

    @Test
    @DisplayName("subscribed frame has type, topic, position, and qos")
    void given_topic_and_position_when_subscribed_frame_built_should_have_all_fields() throws Exception {
        Topic topic = Topic.parse("orders:999");
        String frame = PushWebSocketHandler.subscribed(topic, "5", Qos.AT_LEAST_ONCE);

        JsonNode node = json.readTree(frame);
        assertThat(node.get("type").asText()).isEqualTo("subscribed");
        assertThat(node.get("topic").asText()).isEqualTo("orders:999");
        assertThat(node.get("position").asText()).isEqualTo("5");
        assertThat(node.get("qos").asText()).isEqualTo("AT_LEAST_ONCE");
    }

    @Test
    @DisplayName("subscribed frame omits position when null")
    void given_null_position_when_subscribed_frame_built_should_omit_position() throws Exception {
        Topic topic = Topic.parse("orders:888");
        String frame = PushWebSocketHandler.subscribed(topic, null, Qos.AT_MOST_ONCE);

        JsonNode node = json.readTree(frame);
        assertThat(node.get("type").asText()).isEqualTo("subscribed");
        assertThat(node.get("position")).isNull();
        assertThat(node.get("qos").asText()).isEqualTo("AT_MOST_ONCE");
    }

    @Test
    @DisplayName("error frame has type, code, message, and topic")
    void given_error_details_when_frame_built_should_have_all_fields() throws Exception {
        Topic topic = Topic.parse("orders:111");
        String frame = PushWebSocketHandler.error("forbidden", "no access", topic);

        JsonNode node = json.readTree(frame);
        assertThat(node.get("type").asText()).isEqualTo("error");
        assertThat(node.get("code").asText()).isEqualTo("forbidden");
        assertThat(node.get("message").asText()).isEqualTo("no access");
        assertThat(node.get("topic").asText()).isEqualTo("orders:111");
    }

    @Test
    @DisplayName("error frame omits topic when null")
    void given_null_topic_when_error_frame_built_should_omit_topic() throws Exception {
        String frame = PushWebSocketHandler.error("bad_request", "invalid frame", null);

        JsonNode node = json.readTree(frame);
        assertThat(node.get("type").asText()).isEqualTo("error");
        assertThat(node.get("code").asText()).isEqualTo("bad_request");
        assertThat(node.get("topic")).isNull();
    }

    @Test
    @DisplayName("overflow frame has type, topic, and reason")
    void given_topic_when_overflow_frame_built_should_have_all_fields() throws Exception {
        Topic topic = Topic.parse("orders:222");
        String frame = PushWebSocketHandler.overflow(topic);

        JsonNode node = json.readTree(frame);
        assertThat(node.get("type").asText()).isEqualTo("overflow");
        assertThat(node.get("topic").asText()).isEqualTo("orders:222");
        assertThat(node.get("reason").asText()).isEqualTo("queue_full");
    }

    @Test
    @DisplayName(
            "GIVEN a reset signal WHEN the signal frame is built SHOULD be a reset frame with type, topic and reason and no id")
    void given_reset_signal_when_signal_frame_built_should_be_reset_frame() throws Exception {
        Topic topic = Topic.parse("orders:222");

        JsonNode node = json.readTree(PushWebSocketHandler.signalFrame(PushEvent.resetSignal(topic, "history_gap")));

        assertThat(node.get("type").asText()).isEqualTo("reset");
        assertThat(node.get("topic").asText()).isEqualTo("orders:222");
        assertThat(node.get("reason").asText()).isEqualTo("history_gap");
        assertThat(node.has("id")).isFalse();
    }

    @Test
    @DisplayName("GIVEN a no_history reset WHEN the signal frame is built SHOULD carry that reason")
    void given_no_history_reset_when_signal_frame_built_should_carry_reason() throws Exception {
        JsonNode node = json.readTree(
                PushWebSocketHandler.signalFrame(PushEvent.resetSignal(Topic.parse("orders:222"), "no_history")));

        assertThat(node.get("reason").asText()).isEqualTo("no_history");
    }

    @Test
    @DisplayName("GIVEN an overflow signal WHEN the signal frame is built SHOULD stay an overflow frame")
    void given_overflow_signal_when_signal_frame_built_should_stay_overflow_frame() throws Exception {
        JsonNode node =
                json.readTree(PushWebSocketHandler.signalFrame(PushEvent.overflowSignal(Topic.parse("orders:222"), 4)));

        assertThat(node.get("type").asText()).isEqualTo("overflow");
        assertThat(node.get("dropped").asInt()).isEqualTo(4);
    }

    @Test
    @DisplayName("closeFrame includes code and reason")
    void given_close_status_when_frame_built_should_include_code_and_reason() throws Exception {
        CloseStatus status = new CloseStatus(1012, "rebalance");
        String frame = PushWebSocketHandler.closeFrame(status);

        JsonNode node = json.readTree(frame);
        assertThat(node.get("code").asInt()).isEqualTo(1012);
        assertThat(node.get("reason").asText()).isEqualTo("rebalance");
    }

    // ---- origin (echo suppression) ----

    @Test
    @DisplayName(
            "GIVEN a socket that declared origin A in connect WHEN events stamped A and B are published SHOULD write only the event stamped B")
    void given_connect_origin_when_events_of_that_origin_published_should_not_write_them() throws Exception {
        try (FakeSocket socket = new FakeSocket()) {
            socket.send("{\"type\":\"connect\",\"origin\":\"A\"}");
            socket.send("{\"type\":\"sub\",\"topics\":[\"orders:1\"]}");
            socket.await("subscribed");

            operatorPublish("{\"n\":1}", "A");
            operatorPublish("{\"n\":2}", "B");

            JsonNode event = socket.await("event");
            assertThat(event.get("data").get("n").asInt()).isEqualTo(2);
            Thread.sleep(200);
            assertThat(socket.frames("event")).hasSize(1);
        }
    }

    @Test
    @DisplayName(
            "GIVEN a socket whose connect declared A WHEN a sub names origin B SHOULD suppress B on that subscription, the sub origin overriding the connection default")
    void given_sub_origin_when_it_differs_from_connect_origin_should_suppress_the_sub_origin() throws Exception {
        try (FakeSocket socket = new FakeSocket()) {
            socket.send("{\"type\":\"connect\",\"origin\":\"A\"}");
            socket.send("{\"type\":\"sub\",\"topics\":[\"orders:1\"],\"origin\":\"B\"}");
            socket.await("subscribed");

            operatorPublish("{\"n\":1}", "B");
            operatorPublish("{\"n\":2}", "A");

            assertThat(socket.await("event").get("data").get("n").asInt()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName(
            "GIVEN a connect frame with an origin outside the grammar WHEN handled SHOULD answer a bad_request error frame")
    void given_invalid_connect_origin_when_handled_should_answer_bad_request() throws Exception {
        try (FakeSocket socket = new FakeSocket()) {
            socket.send("{\"type\":\"connect\",\"origin\":\"no spaces!\"}");

            assertThat(socket.await("error").get("code").asText()).isEqualTo("bad_request");
        }
    }

    @Test
    @DisplayName(
            "GIVEN a socket that declared origin A WHEN it publishes with forged from and push.* attributes SHOULD store push.origin A and no forged attribute")
    void given_client_publish_with_forged_attributes_when_handled_should_stamp_connection_origin() throws Exception {
        properties.getNamespaces().get("orders").setClientPublish(true);
        List<PushEvent> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        broker.subscribe(Topic.parse("orders:1")).subscribe(seen::add);
        try (FakeSocket socket = new FakeSocket()) {
            socket.send("{\"type\":\"connect\",\"origin\":\"A\"}");
            socket.send("{\"type\":\"pub\",\"topic\":\"orders:1\",\"data\":{\"n\":1},"
                    + "\"attributes\":{\"push.origin\":\"forged\",\"push.signal\":\"overflow\",\"from\":\"mallory\","
                    + "\"tag\":\"t\"}}");
            socket.await("published");
        }

        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).attributes()).containsOnly(Map.entry("push.origin", "A"), Map.entry("tag", "t"));
    }

    private void operatorPublish(String data, String origin) {
        publisher
                .publishAsOperator(
                        Topic.parse("orders:1"),
                        data.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        Map.of(),
                        null,
                        null,
                        null,
                        origin,
                        null)
                .block();
    }

    /** A WebSocketSession the handler runs against: frames in through a sink, frames out collected as JSON. */
    private final class FakeSocket implements AutoCloseable {
        private final Sinks.Many<WebSocketMessage> in = Sinks.many().unicast().onBackpressureBuffer();
        private final List<JsonNode> out = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final reactor.core.Disposable running;

        FakeSocket() {
            WebSocketSession session = org.mockito.Mockito.mock(WebSocketSession.class);
            org.springframework.web.reactive.socket.HandshakeInfo info =
                    new org.springframework.web.reactive.socket.HandshakeInfo(
                            java.net.URI.create("ws://gw/push/ws"),
                            new org.springframework.http.HttpHeaders(),
                            Mono.empty(),
                            "push.v1.json");
            org.mockito.Mockito.when(session.getHandshakeInfo()).thenReturn(info);
            org.mockito.Mockito.when(session.receive()).thenReturn(in.asFlux());
            org.mockito.Mockito.when(session.textMessage(org.mockito.ArgumentMatchers.anyString()))
                    .thenAnswer(inv -> text(inv.getArgument(0)));
            org.mockito.Mockito.when(session.close()).thenReturn(Mono.empty());
            org.mockito.Mockito.when(session.close(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(Mono.empty());
            org.mockito.Mockito.when(session.send(org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(inv -> Flux.from((org.reactivestreams.Publisher<WebSocketMessage>) inv.getArgument(0))
                            .doOnNext(m -> out.add(read(m.getPayloadAsText())))
                            .then());
            running = handler.handle(session).subscribe();
        }

        void send(String frame) {
            in.tryEmitNext(text(frame));
        }

        JsonNode await(String type) throws InterruptedException {
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline) {
                List<JsonNode> found = frames(type);
                if (!found.isEmpty()) return found.get(0);
                Thread.sleep(10);
            }
            throw new AssertionError("no '" + type + "' frame within 5 s; saw " + out);
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
                    org.springframework.core.io.buffer.DefaultDataBufferFactory.sharedInstance.wrap(
                            payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }

        private JsonNode read(String text) {
            try {
                return json.readTree(text);
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Test
    @DisplayName("control frame has type and topic")
    void given_type_and_topic_when_control_frame_built_should_have_all_fields() throws Exception {
        Topic topic = Topic.parse("orders:333");
        String frame = PushWebSocketHandler.control("unsubscribed", topic);

        JsonNode node = json.readTree(frame);
        assertThat(node.get("type").asText()).isEqualTo("unsubscribed");
        assertThat(node.get("topic").asText()).isEqualTo("orders:333");
    }
}
