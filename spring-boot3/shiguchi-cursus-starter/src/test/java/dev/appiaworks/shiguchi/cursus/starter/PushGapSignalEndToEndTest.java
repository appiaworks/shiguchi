package dev.appiaworks.shiguchi.cursus.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.appiaworks.shiguchi.cursus.PushMetrics;
import dev.appiaworks.shiguchi.proto.cursus.PushEvent;
import dev.appiaworks.shiguchi.proto.cursus.PushServiceGrpc;
import dev.appiaworks.shiguchi.proto.cursus.SubscribeRequest;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * The {@code reset} signal on the Boot 3 gateway over a real port: a subscriber that resumes from
 * an id older than the {@code MEMORY} store's history ({@code history-size: 3}, ten events
 * published) is told once, before the retained events, over SSE ({@code event: reset}), WebSocket
 * ({@code {"type":"reset"}}) and gRPC (an event carrying {@code push.signal=reset}); a resume id
 * that missed nothing gets no signal.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.graphql.enabled=false",
            "shiguchi.grpc.enabled=true",
            "shiguchi.cursus.enabled=true",
            "shiguchi.cursus.namespaces.gap.publish-auth=NONE",
            "shiguchi.cursus.namespaces.gap.qos=AT_LEAST_ONCE",
            "shiguchi.cursus.namespaces.gap.history-size=3",
            "shiguchi.cursus.connection.keepalive=200ms",
            "shiguchi.cursus.connection.max-lifetime=0"
        })
@DisplayName("Push reset signal (history gap) end to end (Boot 3)")
class PushGapSignalEndToEndTest {

    static final int GRPC_PORT = freePort();

    @DynamicPropertySource
    static void grpcPort(DynamicPropertyRegistry registry) {
        registry.add("shiguchi.grpc.port", () -> GRPC_PORT);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE =
            new ParameterizedTypeReference<>() {};

    @Autowired
    private Environment environment;

    @Autowired
    private PushMetrics metrics;

    private WebClient client;
    private URI wsUri;
    private ManagedChannel channel;

    @BeforeEach
    void connect() {
        String port = environment.getRequiredProperty("local.server.port");
        client = WebClient.builder().baseUrl("http://localhost:" + port).build();
        wsUri = URI.create("ws://localhost:" + port + "/push/ws");
        channel = ManagedChannelBuilder.forAddress("localhost", GRPC_PORT)
                .usePlaintext()
                .build();
    }

    @AfterEach
    void disconnect() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    // ---- SSE ----

    @Test
    @DisplayName(
            "GIVEN ten events and a history of three WHEN an SSE subscriber resumes with the id of the second SHOULD get event: reset, then the three retained events")
    void given_stale_last_event_id_when_sse_resumes_should_get_reset_then_retained_events() {
        List<String> ids = publishTen("gap:sse-stale");

        try (Sse sse = sse("gap:sse-stale", ids.get(1))) {
            List<ServerSentEvent<String>> frames = sse.await(4);

            assertThat(frames.get(0).event()).isEqualTo("reset");
            assertThat(frames.get(0).id()).isNull();
            JsonNode body = parse(frames.get(0).data());
            assertThat(body.get("topic").asText()).isEqualTo("gap:sse-stale");
            assertThat(body.get("reason").asText()).isEqualTo("history_gap");
            assertThat(frames.subList(1, 4))
                    .extracting(ServerSentEvent::id)
                    .containsExactly(ids.get(7), ids.get(8), ids.get(9));
        }
    }

    @Test
    @DisplayName(
            "GIVEN ten events and a history of three WHEN an SSE subscriber resumes with the id of the ninth SHOULD get the tenth only, with no reset")
    void given_fresh_last_event_id_when_sse_resumes_should_get_no_reset() {
        List<String> ids = publishTen("gap:sse-fresh");

        try (Sse sse = sse("gap:sse-fresh", ids.get(8))) {
            List<ServerSentEvent<String>> frames = sse.await(1);
            sleep(500);

            assertThat(sse.frames).hasSize(1);
            assertThat(frames.get(0).event()).isEqualTo("gap:sse-fresh");
            assertThat(frames.get(0).id()).isEqualTo(ids.get(9));
        }
    }

    // ---- WebSocket ----

    @Test
    @DisplayName(
            "GIVEN ten events and a history of three WHEN a socket subscribes with the id of the second SHOULD get a reset frame, then the three retained events")
    void given_stale_after_when_socket_subscribes_should_get_reset_frame_then_retained_events() {
        List<String> ids = publishTen("gap:ws-stale");

        try (Socket ws = socket()) {
            ws.send("{\"type\":\"sub\",\"topics\":[\"gap:ws-stale\"],\"after\":\"" + ids.get(1) + "\"}");

            List<JsonNode> frames = ws.awaitData(4);

            assertThat(frames.get(0).get("type").asText()).isEqualTo("reset");
            assertThat(frames.get(0).get("topic").asText()).isEqualTo("gap:ws-stale");
            assertThat(frames.get(0).get("reason").asText()).isEqualTo("history_gap");
            assertThat(frames.subList(1, 4))
                    .extracting(f -> f.get("id").asText())
                    .containsExactly(ids.get(7), ids.get(8), ids.get(9));
        }
    }

    @Test
    @DisplayName(
            "GIVEN ten events and a history of three WHEN a socket subscribes with the id of the ninth SHOULD get the tenth only, with no reset")
    void given_fresh_after_when_socket_subscribes_should_get_no_reset() {
        List<String> ids = publishTen("gap:ws-fresh");

        try (Socket ws = socket()) {
            ws.send("{\"type\":\"sub\",\"topics\":[\"gap:ws-fresh\"],\"after\":\"" + ids.get(8) + "\"}");

            List<JsonNode> frames = ws.awaitData(1);
            sleep(500);

            assertThat(ws.data()).hasSize(1);
            assertThat(frames.get(0).get("type").asText()).isEqualTo("event");
            assertThat(frames.get(0).get("id").asText()).isEqualTo(ids.get(9));
        }
    }

    // ---- gRPC ----

    @Test
    @DisplayName(
            "GIVEN ten events and a history of three WHEN a gRPC subscriber resumes after the second SHOULD get an event carrying push.signal=reset, then the three retained events")
    void given_stale_after_when_grpc_resumes_should_get_reset_event_then_retained_events() {
        List<String> ids = publishTen("gap:grpc-stale");

        Grpc grpc = grpc("gap:grpc-stale", ids.get(1));
        try {
            awaitTrue(() -> grpc.events.size() >= 4);

            assertThat(grpc.events.get(0).getAttributesMap())
                    .containsEntry("push.signal", "reset")
                    .containsEntry("reason", "history_gap");
            assertThat(grpc.events.get(0).getId()).isEmpty();
            assertThat(grpc.events.subList(1, 4))
                    .extracting(PushEvent::getId)
                    .containsExactly(ids.get(7), ids.get(8), ids.get(9));
        } finally {
            grpc.cancel();
        }
    }

    @Test
    @DisplayName(
            "GIVEN ten events and a history of three WHEN a gRPC subscriber resumes after the ninth SHOULD get the tenth only, with no reset")
    void given_fresh_after_when_grpc_resumes_should_get_no_reset() {
        List<String> ids = publishTen("gap:grpc-fresh");

        Grpc grpc = grpc("gap:grpc-fresh", ids.get(8));
        try {
            awaitTrue(() -> grpc.events.size() >= 1);
            sleep(500);

            assertThat(grpc.events).hasSize(1);
            assertThat(grpc.events.get(0).getId()).isEqualTo(ids.get(9));
            assertThat(grpc.events.get(0).getAttributesMap()).doesNotContainKey("push.signal");
        } finally {
            grpc.cancel();
        }
    }

    // ---- helpers ----

    private List<String> publishTen(String topic) {
        List<String> ids = new ArrayList<>();
        for (int n = 1; n <= 10; n++) {
            String body = client.post()
                    .uri("/push/publish")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"topic\":\"" + topic + "\",\"data\":{\"n\":" + n + "}}")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(30));
            int at = body.indexOf("\"id\":\"") + 6;
            ids.add(body.substring(at, body.indexOf('"', at)));
        }
        return ids;
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

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode parse(String text) {
        try {
            return JSON.readTree(text);
        } catch (IOException e) {
            throw new AssertionError(text, e);
        }
    }

    /** Opens an SSE stream resuming after {@code lastEventId} and waits until the gateway counts it. */
    private Sse sse(String topic, String lastEventId) {
        int before = metrics.connections("sse");
        Flux<ServerSentEvent<String>> stream = client.get()
                .uri("/push/subscribe?topic=" + topic)
                .header("Last-Event-ID", lastEventId)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(SSE);
        Sse sse = new Sse(stream);
        awaitTrue(() -> metrics.connections("sse") > before);
        return sse;
    }

    private static final class Sse implements AutoCloseable {
        final List<ServerSentEvent<String>> frames = new CopyOnWriteArrayList<>();
        private final Disposable connection;

        Sse(Flux<ServerSentEvent<String>> stream) {
            connection = stream.filter(e -> e.event() != null && !"close".equals(e.event()))
                    .subscribe(frames::add);
        }

        List<ServerSentEvent<String>> await(int count) {
            awaitTrue(() -> frames.size() >= count);
            return List.copyOf(frames);
        }

        @Override
        public void close() {
            connection.dispose();
        }
    }

    /** Opens a socket and waits for the {@code connected} frame. */
    private Socket socket() {
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

        void await(String type) {
            awaitTrue(() ->
                    frames.stream().anyMatch(f -> type.equals(f.path("type").asText())));
        }

        /** The {@code reset} and {@code event} frames, in arrival order. */
        List<JsonNode> data() {
            return frames.stream()
                    .filter(f -> "reset".equals(f.path("type").asText())
                            || "event".equals(f.path("type").asText()))
                    .toList();
        }

        List<JsonNode> awaitData(int count) {
            awaitTrue(() -> data().size() >= count);
            return data();
        }

        @Override
        public void close() {
            outbound.tryEmitComplete();
            connection.dispose();
        }
    }

    /** One async gRPC subscription, and whether the gateway counted it. */
    private final class Grpc implements StreamObserver<PushEvent> {
        final List<PushEvent> events = new CopyOnWriteArrayList<>();
        final int before = metrics.connections("grpc");
        final io.grpc.Context.CancellableContext context =
                io.grpc.Context.current().withCancellation();

        void cancel() {
            context.cancel(null);
        }

        @Override
        public void onNext(PushEvent value) {
            events.add(value);
        }

        @Override
        public void onError(Throwable t) {}

        @Override
        public void onCompleted() {}
    }

    private Grpc grpc(String topic, String after) {
        Grpc sub = new Grpc();
        PushServiceGrpc.PushServiceStub stub = PushServiceGrpc.newStub(channel);
        SubscribeRequest request =
                SubscribeRequest.newBuilder().addTopics(topic).setAfter(after).build();
        sub.context.run(() -> stub.subscribe(request, sub));
        awaitTrue(() -> metrics.connections("grpc") > sub.before);
        return sub;
    }
}
