package dev.appiaworks.shiguchi.cursus.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Value;
import dev.appiaworks.shiguchi.cursus.PushMetrics;
import dev.appiaworks.shiguchi.proto.cursus.PublishRequest;
import dev.appiaworks.shiguchi.proto.cursus.PushEvent;
import dev.appiaworks.shiguchi.proto.cursus.PushServiceGrpc;
import dev.appiaworks.shiguchi.proto.cursus.SubscribeRequest;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
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
 * Echo suppression on the Boot 3 gateway over a real port: a subscriber that declares an origin
 * does not receive events stamped with it, over SSE ({@code ?origin=}), WebSocket
 * ({@code connect{origin}}) and gRPC ({@code SubscribeRequest.origin}); an operator publish names
 * the origin, a client publish carries the one its connection declared and cannot forge it.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.graphql.enabled=false",
            "shiguchi.grpc.enabled=true",
            "shiguchi.cursus.enabled=true",
            "shiguchi.cursus.namespaces.ticker.publish-auth=NONE",
            "shiguchi.cursus.namespaces.chat.publish-auth=NONE",
            "shiguchi.cursus.namespaces.chat.client-publish=true",
            "shiguchi.cursus.connection.keepalive=200ms",
            "shiguchi.cursus.connection.max-lifetime=0"
        })
@DisplayName("Push echo suppression (origin) end to end (Boot 3)")
class PushOriginEndToEndTest {

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
            "GIVEN two SSE subscribers with origins A and B WHEN an operator publishes naming A and then B SHOULD deliver the first only to B and the second only to A")
    void given_two_sse_subscribers_when_operator_publishes_with_origin_should_skip_the_named_one() {
        try (Sse a = sse("ticker:sse", "A");
                Sse b = sse("ticker:sse", "B")) {
            assertThat(publish("ticker:sse", "{\"n\":1}", "A")).isEqualTo(200);
            assertThat(publish("ticker:sse", "{\"n\":2}", "B")).isEqualTo(200);

            assertThat(b.awaitEvents(1)).extracting(ServerSentEvent::data).containsExactly("{\"n\":1}");
            assertThat(a.awaitEvents(1)).extracting(ServerSentEvent::data).containsExactly("{\"n\":2}");
            sleep(300);
            assertThat(a.events).hasSize(1);
            assertThat(b.events).hasSize(1);
        }
    }

    @Test
    @DisplayName(
            "GIVEN an SSE subscriber with no origin WHEN an event naming an origin is published SHOULD still deliver it")
    void given_sse_subscriber_without_origin_when_event_with_origin_published_should_deliver_it() {
        try (Sse plain = sse("ticker:plain", null)) {
            assertThat(publish("ticker:plain", "{\"n\":1}", "A")).isEqualTo(200);

            assertThat(plain.awaitEvents(1)).extracting(ServerSentEvent::data).containsExactly("{\"n\":1}");
        }
    }

    @Test
    @DisplayName(
            "GIVEN an operator publish with a push.* attribute or a malformed origin WHEN posted SHOULD answer 400, and a malformed origin on subscribe SHOULD answer 400")
    void given_reserved_attribute_or_bad_origin_when_posted_should_answer_bad_request() {
        int reserved = client.post()
                .uri("/push/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"topic\":\"ticker:x\",\"data\":1,\"attributes\":{\"push.origin\":\"forged\"}}")
                .exchangeToMono(r -> Mono.just(r.statusCode().value()))
                .block(Duration.ofSeconds(30));
        assertThat(reserved).isEqualTo(400);
        assertThat(publish("ticker:x", "1", "has space")).isEqualTo(400);

        int subscribe = client.get()
                .uri("/push/subscribe?topic=ticker:x&origin=no%20spaces")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchangeToMono(r -> Mono.just(r.statusCode().value()))
                .block(Duration.ofSeconds(30));
        assertThat(subscribe).isEqualTo(400);
    }

    @Test
    @DisplayName(
            "GIVEN an SSE subscriber with origin A WHEN a client publishes to a client-publish topic declaring A and then B SHOULD deliver only the one declared B")
    void given_sse_subscriber_when_client_publishes_declaring_origin_should_skip_own_origin() {
        try (Sse a = sse("chat:sse", "A")) {
            assertThat(clientPublish("chat:sse", "A", "\"mine\"")).isEqualTo(200);
            assertThat(clientPublish("chat:sse", "B", "\"theirs\"")).isEqualTo(200);

            assertThat(a.awaitEvents(1)).extracting(ServerSentEvent::data).containsExactly("\"theirs\"");
            sleep(300);
            assertThat(a.events).hasSize(1);
        }
    }

    // ---- WebSocket ----

    @Test
    @DisplayName(
            "GIVEN two sockets with origins A and B on one topic WHEN an operator publish names A SHOULD reach only B")
    void given_two_sockets_when_operator_publishes_with_origin_should_reach_only_the_other() {
        try (Socket a = socket("A");
                Socket b = socket("B")) {
            a.send("{\"type\":\"sub\",\"topics\":[\"ticker:ws\"]}");
            b.send("{\"type\":\"sub\",\"topics\":[\"ticker:ws\"]}");
            a.await("subscribed");
            b.await("subscribed");

            assertThat(publish("ticker:ws", "{\"n\":1}", "A")).isEqualTo(200);
            assertThat(publish("ticker:ws", "{\"n\":2}", "B")).isEqualTo(200);

            assertThat(b.awaitEvents(1).get(0).get("data").get("n").asInt()).isEqualTo(1);
            assertThat(a.awaitEvents(1).get(0).get("data").get("n").asInt()).isEqualTo(2);
            sleep(300);
            assertThat(a.events()).hasSize(1);
            assertThat(b.events()).hasSize(1);
            assertThat(b.events().get(0).get("attributes").get("push.origin").asText())
                    .isEqualTo("A");
        }
    }

    @Test
    @DisplayName(
            "GIVEN two sockets with origins A and B WHEN A publishes from the client, forging push.origin and from SHOULD reach B stamped with A and not reach A")
    void given_two_sockets_when_one_publishes_should_reach_the_other_only_and_not_forge_origin() {
        try (Socket a = socket("A");
                Socket b = socket("B")) {
            a.send("{\"type\":\"sub\",\"topics\":[\"chat:room\"]}");
            b.send("{\"type\":\"sub\",\"topics\":[\"chat:room\"]}");
            a.await("subscribed");
            b.await("subscribed");

            a.send("{\"type\":\"pub\",\"topic\":\"chat:room\",\"data\":{\"say\":\"hi\"},\"attributes\":"
                    + "{\"push.origin\":\"B\",\"from\":\"mallory\"}}");
            a.await("published");

            JsonNode seen = b.awaitEvents(1).get(0);
            assertThat(seen.get("data").get("say").asText()).isEqualTo("hi");
            assertThat(seen.get("attributes").get("push.origin").asText()).isEqualTo("A");
            assertThat(seen.get("attributes").has("from")).isFalse();
            sleep(300);
            assertThat(a.events()).isEmpty();

            b.send("{\"type\":\"pub\",\"topic\":\"chat:room\",\"data\":{\"say\":\"yo\"}}");
            b.await("published");
            assertThat(a.awaitEvents(1).get(0).get("data").get("say").asText()).isEqualTo("yo");
            sleep(300);
            assertThat(b.events()).hasSize(1);
        }
    }

    // ---- gRPC ----

    @Test
    @DisplayName(
            "GIVEN two gRPC subscribers with origins A and B WHEN Publish names origin A SHOULD reach only B, and a push.* attribute SHOULD be INVALID_ARGUMENT")
    void given_two_grpc_subscribers_when_publish_names_origin_should_reach_only_the_other() throws Exception {
        Grpc a = grpc("ticker:grpc", "A");
        Grpc b = grpc("ticker:grpc", "B");
        try {
            PushServiceGrpc.PushServiceBlockingStub stub = PushServiceGrpc.newBlockingStub(channel);
            stub.publish(PublishRequest.newBuilder()
                    .addTopics("ticker:grpc")
                    .setJson(Value.newBuilder().setNumberValue(1))
                    .setOrigin("A")
                    .build());
            stub.publish(PublishRequest.newBuilder()
                    .addTopics("ticker:grpc")
                    .setJson(Value.newBuilder().setNumberValue(2))
                    .setOrigin("B")
                    .build());

            awaitTrue(() -> b.events.size() == 1 && a.events.size() == 1);
            sleep(300);
            assertThat(b.events).hasSize(1);
            assertThat(a.events).hasSize(1);
            assertThat(b.events.get(0).getJson().getNumberValue()).isEqualTo(1);
            assertThat(b.events.get(0).getAttributesMap()).containsEntry("push.origin", "A");
            assertThat(a.events.get(0).getJson().getNumberValue()).isEqualTo(2);

            assertThatThrownBy(() -> stub.publish(PublishRequest.newBuilder()
                            .addTopics("ticker:grpc")
                            .setJson(Value.newBuilder().setNumberValue(3))
                            .putAttributes("push.origin", "forged")
                            .build()))
                    .isInstanceOfSatisfying(
                            StatusRuntimeException.class,
                            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
        } finally {
            a.cancel();
            b.cancel();
        }
    }

    // ---- helpers ----

    private int publish(String topic, String dataJson, String origin) {
        return client.post()
                .uri("/push/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"topic\":\"" + topic + "\",\"data\":" + dataJson + ",\"origin\":\"" + origin + "\"}")
                .exchangeToMono(r -> Mono.just(r.statusCode().value()))
                .block(Duration.ofSeconds(30));
    }

    private int clientPublish(String topic, String origin, String dataJson) {
        return client.post()
                .uri("/push/topics/" + topic + "?origin=" + origin)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dataJson)
                .exchangeToMono(r -> Mono.just(r.statusCode().value()))
                .block(Duration.ofSeconds(30));
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

    /** Opens an SSE stream (declaring {@code origin} when not null) and waits until the gateway counts it. */
    private Sse sse(String topic, String origin) {
        int before = metrics.connections("sse");
        String uri = "/push/subscribe?topic=" + topic + (origin == null ? "" : "&origin=" + origin);
        Flux<ServerSentEvent<String>> stream = client.get()
                .uri(uri)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(SSE);
        Sse sse = new Sse(stream);
        awaitTrue(() -> metrics.connections("sse") > before);
        return sse;
    }

    private static final class Sse implements AutoCloseable {
        final List<ServerSentEvent<String>> events = new CopyOnWriteArrayList<>();
        private final Disposable connection;

        Sse(Flux<ServerSentEvent<String>> stream) {
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

    /** Opens a socket declaring {@code origin} in its connect frame, and waits for the {@code connected} frame. */
    private Socket socket(String origin) {
        Socket socket = new Socket(wsUri);
        socket.await("connected");
        socket.send("{\"type\":\"connect\",\"origin\":\"" + origin + "\"}");
        socket.await("connected", 2);
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

    /** One async gRPC subscription declaring {@code origin}, and whether the gateway counted it. */
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

    private Grpc grpc(String topic, String origin) {
        Grpc sub = new Grpc();
        PushServiceGrpc.PushServiceStub stub = PushServiceGrpc.newStub(channel);
        SubscribeRequest request =
                SubscribeRequest.newBuilder().addTopics(topic).setOrigin(origin).build();
        sub.context.run(() -> stub.subscribe(request, sub));
        awaitTrue(() -> metrics.connections("grpc") > sub.before);
        return sub;
    }
}
