package dev.appiaworks.shiguchi.cursus.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import dev.appiaworks.shiguchi.cursus.PushBroker;
import dev.appiaworks.shiguchi.cursus.PushMetrics;
import dev.appiaworks.shiguchi.cursus.Topic;
import dev.appiaworks.shiguchi.proto.cursus.PushEvent;
import dev.appiaworks.shiguchi.proto.cursus.PushServiceGrpc;
import dev.appiaworks.shiguchi.proto.cursus.SubscribeRequest;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
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
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * An event id read on one Cursus transport resumes on any other. The docs promise it
 * ({@code subscribing.md}: GraphQL {@code after} takes "the same ids as {@code Last-Event-ID}";
 * {@code delivery.md}: a resume id is {@code Last-Event-ID}, {@code after}, or a stored cursor), but
 * the other end-to-end classes resume only on the transport that issued the id. Every ordered pair
 * of SSE, WebSocket, gRPC and GraphQL is exercised on a kept ({@code AT_LEAST_ONCE}) topic of the
 * {@code MEMORY} engine, then the foreign, unknown and malformed ids are put to all four.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.grpc.enabled=true",
            "spring.graphql.websocket.path=/graphql/ws",
            "shiguchi.cursus.enabled=true",
            "shiguchi.cursus.namespaces.xt.publish-auth=NONE",
            "shiguchi.cursus.namespaces.xt.qos=AT_LEAST_ONCE",
            "shiguchi.cursus.namespaces.xt.history-size=100",
            "shiguchi.cursus.connection.keepalive=200ms",
            "shiguchi.cursus.connection.max-lifetime=0"
        })
@DisplayName("Push resume ids across transports end to end (Boot 4)")
class PushCrossTransportResumeEndToEndTest {

    static final int GRPC_PORT = freePort();

    private static final Path PB_FILE = writeDescriptorSet();

    @DynamicPropertySource
    static void grpcPort(DynamicPropertyRegistry registry) {
        registry.add("shiguchi.grpc.port", () -> GRPC_PORT);
        // The GraphQL surface only comes up with a service to build a schema from; its backend is never called.
        registry.add("shiguchi.services[0].name", () -> "ev");
        registry.add("shiguchi.services[0].mode", () -> "PB_FILE");
        registry.add("shiguchi.services[0].pb-descriptor-path", () -> PB_FILE.toString());
        registry.add("shiguchi.services[0].host", () -> "localhost");
        registry.add("shiguchi.services[0].port", () -> GRPC_PORT);
    }

    @AfterAll
    static void cleanUp() throws IOException {
        Files.deleteIfExists(PB_FILE);
    }

    private static Path writeDescriptorSet() {
        try {
            FileDescriptorProto file = FileDescriptorProto.newBuilder()
                    .setName("ev/orders.proto")
                    .setSyntax("proto3")
                    .setPackage("ev")
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("OrderEvent")
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("status")
                                    .setNumber(1)
                                    .setType(FieldDescriptorProto.Type.TYPE_STRING)
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)))
                    .addMessageType(DescriptorProto.newBuilder().setName("GetOrderRequest"))
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Orders")
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("Get")
                                    .setInputType(".ev.GetOrderRequest")
                                    .setOutputType(".ev.OrderEvent")))
                    .build();
            Path path = Files.createTempFile("ev-orders", ".pb");
            Files.write(
                    path,
                    FileDescriptorSet.newBuilder()
                            .addFile(DescriptorProtos.getDescriptor().toProto())
                            .addFile(file)
                            .build()
                            .toByteArray());
            return path;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private static final ObjectMapper JSON = JsonMapper.builder()
            .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE =
            new ParameterizedTypeReference<>() {};

    private static final int TOTAL = 6;
    private static final int READ_FIRST = 2;

    enum Transport {
        SSE,
        WEBSOCKET,
        GRPC,
        GRAPHQL
    }

    /** One delivered event, whichever transport carried it: the id it came with and its {@code n}. */
    record Ev(String id, int n) {}

    /** One open subscription, whatever the transport. */
    interface Feed extends AutoCloseable {
        List<Ev> events();

        /** Why the gateway refused the subscription, or null while none has been. */
        String refusal();

        @Override
        void close();
    }

    @Autowired
    private Environment environment;

    @Autowired
    private PushMetrics metrics;

    @Autowired
    private PushBroker broker;

    private WebClient client;
    private URI wsUri;
    private URI graphqlUri;
    private ManagedChannel channel;
    private int topicSeq;

    @BeforeEach
    void connect() {
        String port = environment.getRequiredProperty("local.server.port");
        client = WebClient.builder().baseUrl("http://localhost:" + port).build();
        wsUri = URI.create("ws://localhost:" + port + "/push/ws");
        graphqlUri = URI.create("ws://localhost:" + port + "/graphql/ws");
        channel = ManagedChannelBuilder.forAddress("localhost", GRPC_PORT)
                .usePlaintext()
                .build();
    }

    @AfterEach
    void disconnect() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        // A disposed stream disconnects asynchronously: gone before the next test takes its baseline.
        awaitTrue(() -> metrics.connections() == 0);
    }

    static Stream<Arguments> ordered_pairs() {
        List<Arguments> pairs = new ArrayList<>();
        for (Transport from : Transport.values()) {
            for (Transport to : Transport.values()) {
                if (from != to) pairs.add(Arguments.of(from, to));
            }
        }
        return pairs.stream();
    }

    // ---- an id from one transport resumes on another ----

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("ordered_pairs")
    @DisplayName(
            "GIVEN events read live on one transport WHEN the id of the second is used to resume on another SHOULD deliver exactly the rest once each in order, then live events")
    void given_idFromOneTransport_when_resumedOnAnother_should_deliverRestOnceInOrder(Transport from, Transport to) {
        String name = nextTopic();
        List<String> ids = new ArrayList<>();
        String resumeId;
        try (Feed first = open(from, name, null)) {
            for (int n = 1; n <= TOTAL; n++) ids.add(publish(name, n));
            awaitTrue(() -> first.events().size() >= READ_FIRST);
            Ev anchor = first.events().get(READ_FIRST - 1);
            assertThat(anchor.n()).isEqualTo(READ_FIRST);
            // the transport's own id is the one the publisher was given
            assertThat(anchor.id()).isEqualTo(ids.get(READ_FIRST - 1));
            resumeId = anchor.id();
        }
        // the first stream disconnects asynchronously: gone before the resumed one is counted
        awaitTrue(() -> broker.subscriberCount(Topic.parse("xt:" + name)) == 0);

        try (Feed resumed = open(to, name, resumeId)) {
            awaitTrue(() -> resumed.events().size() >= TOTAL - READ_FIRST);
            sleep(500);
            assertThat(resumed.events())
                    .as("%s resumed on %s", from, to)
                    .extracting(Ev::n)
                    .containsExactly(3, 4, 5, 6);
            assertThat(resumed.events()).extracting(Ev::id).containsExactlyElementsOf(ids.subList(READ_FIRST, TOTAL));

            String live = publish(name, TOTAL + 1);
            awaitTrue(() -> resumed.events().size() >= TOTAL - READ_FIRST + 1);
            sleep(300);
            assertThat(resumed.events()).hasSize(TOTAL - READ_FIRST + 1);
            assertThat(resumed.events().get(TOTAL - READ_FIRST).id()).isEqualTo(live);
        }
    }

    // ---- foreign, unknown and malformed ids ----

    @ParameterizedTest
    @EnumSource(value = Transport.class, mode = EnumSource.Mode.EXCLUDE, names = "WEBSOCKET")
    @DisplayName(
            "GIVEN a resume id that is not an event id WHEN a subscriber presents it SHOULD be refused as a bad request and receive no event, the same on every transport")
    void given_malformedId_when_subscribed_should_beRefusedAsBadRequest(Transport transport) {
        String name = nextTopic();
        publish(name, 1);

        try (Feed feed = open(transport, name, "not-an-id", false)) {
            awaitTrue(() -> feed.refusal() != null);
            sleep(300);
            assertThat(feed.events()).isEmpty();
            assertThat(feed.refusal()).as("%s refusal", transport).isEqualTo(expectedMalformedRefusal(transport));
        }
    }

    @Disabled("DEFECT: a WebSocket sub frame with after=not-an-id is not refused. Expected an error frame with code"
            + " bad_request like SSE 400, gRPC INVALID_ARGUMENT and GraphQL; observed no error frame,"
            + " no event and no connect error within 30s")
    @Test
    @DisplayName(
            "GIVEN a resume id that is not an event id WHEN a WebSocket subscriber presents it SHOULD be refused as a bad request")
    void given_malformedId_when_subscribedOverWebSocket_should_beRefusedAsBadRequest() {
        given_malformedId_when_subscribed_should_beRefusedAsBadRequest(Transport.WEBSOCKET);
    }

    @ParameterizedTest
    @EnumSource(Transport.class)
    @DisplayName(
            "GIVEN an id issued for another topic that is older than this topic's events WHEN a subscriber resumes with it SHOULD replay this topic's whole history with no reset, the same on every transport")
    void given_foreignOlderId_when_resumed_should_replayWholeHistory(Transport transport) {
        // Ids come from one process-wide counter on the MEMORY engine, so an id of another topic is just
        // a position: nothing marks it foreign. Observed behaviour, not a documented promise.
        String other = nextTopic();
        String foreign = publish(other, 99);
        String name = nextTopic();
        publish(name, 1);
        publish(name, 2);

        try (Feed feed = open(transport, name, foreign)) {
            awaitTrue(() -> feed.events().size() >= 2);
            sleep(500);
            assertThat(feed.events()).extracting(Ev::n).containsExactly(1, 2);
            assertThat(feed.refusal()).isNull();
        }
    }

    @ParameterizedTest
    @EnumSource(Transport.class)
    @DisplayName(
            "GIVEN an id issued for another topic that is newer than this topic's events WHEN a subscriber resumes with it SHOULD replay nothing and then stream live, the same on every transport")
    void given_foreignNewerId_when_resumed_should_replayNothingThenLive(Transport transport) {
        String name = nextTopic();
        publish(name, 1);
        String foreign = publish(nextTopic(), 99);

        try (Feed feed = open(transport, name, foreign)) {
            sleep(500);
            assertThat(feed.events()).isEmpty();
            assertThat(feed.refusal()).isNull();
            publish(name, 2);
            awaitTrue(() -> feed.events().size() >= 1);
            sleep(300);
            assertThat(feed.events()).extracting(Ev::n).containsExactly(2);
        }
    }

    @ParameterizedTest
    @EnumSource(Transport.class)
    @DisplayName(
            "GIVEN a well-formed id above everything ever issued WHEN a subscriber resumes with it SHOULD replay nothing and not refuse, the same on every transport")
    void given_unknownFutureId_when_resumed_should_replayNothing(Transport transport) {
        String name = nextTopic();
        publish(name, 1);

        try (Feed feed = open(transport, name, "9223372036854775807")) {
            sleep(500);
            assertThat(feed.events()).isEmpty();
            assertThat(feed.refusal()).isNull();
        }
    }

    /** What each transport says about a malformed resume id; they agree it is a bad request. */
    private static String expectedMalformedRefusal(Transport transport) {
        return switch (transport) {
            case SSE -> "400";
            case WEBSOCKET -> "bad_request";
            case GRPC -> "INVALID_ARGUMENT";
            case GRAPHQL -> "bad_request";
        };
    }

    // ---- helpers ----

    private String nextTopic() {
        return "t" + (++topicSeq) + "-" + Long.toHexString(System.nanoTime());
    }

    private String publish(String name, int n) {
        String body = client.post()
                .uri("/push/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"topic\":\"xt:" + name + "\",\"data\":{\"n\":" + n + "}}")
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(30));
        return parse(body).get("id").asString();
    }

    private Feed open(Transport transport, String name, String after) {
        return open(transport, name, after, true);
    }

    /** Opens a subscription and, when it should be accepted, waits until the broker has it. */
    private Feed open(Transport transport, String name, String after, boolean awaitBroker) {
        Topic topic = Topic.parse("xt:" + name);
        long before = broker.subscriberCount(topic);
        Feed feed = switch (transport) {
            case SSE -> new SseFeed(name, after);
            case WEBSOCKET -> new WebSocketFeed(name, after);
            case GRPC -> new GrpcFeed(name, after);
            case GRAPHQL -> new GraphQlFeed(name, after);
        };
        if (awaitBroker) {
            try {
                awaitTrue(() -> broker.subscriberCount(topic) > before || feed.refusal() != null);
            } catch (AssertionError e) {
                feed.close();
                throw e;
            }
            assertThat(feed.refusal()).as("%s refusal", transport).isNull();
        }
        return feed;
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
        } catch (tools.jackson.core.JacksonException e) {
            throw new AssertionError(text, e);
        }
    }

    private static int nOf(JsonNode payload) {
        return payload.get("n").asInt();
    }

    // ---- SSE ----

    private final class SseFeed implements Feed {
        private final List<Ev> events = new CopyOnWriteArrayList<>();
        private final AtomicReference<String> refusal = new AtomicReference<>();
        private final Disposable connection;

        SseFeed(String name, String after) {
            connection = client.get()
                    .uri("/push/subscribe?topic=xt:" + name)
                    .headers(h -> {
                        if (after != null) h.add("Last-Event-ID", after);
                    })
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .retrieve()
                    .bodyToFlux(SSE)
                    .filter(e -> e.event() != null && e.event().startsWith("xt:"))
                    .subscribe(
                            e -> events.add(new Ev(e.id(), nOf(parse(e.data())))),
                            t -> refusal.set(
                                    t instanceof WebClientResponseException w
                                            ? String.valueOf(w.getStatusCode().value())
                                            : String.valueOf(t)));
        }

        @Override
        public List<Ev> events() {
            return List.copyOf(events);
        }

        @Override
        public String refusal() {
            return refusal.get();
        }

        @Override
        public void close() {
            connection.dispose();
        }
    }

    // ---- WebSocket ----

    private final class WebSocketFeed implements Feed {
        private final List<Ev> events = new CopyOnWriteArrayList<>();
        private final AtomicReference<String> refusal = new AtomicReference<>();
        private final Sinks.Many<String> outbound = Sinks.many().unicast().onBackpressureBuffer();
        private final Disposable connection;

        WebSocketFeed(String name, String after) {
            String sub = "{\"type\":\"sub\",\"topics\":[\"xt:" + name + "\"]"
                    + (after == null ? "" : ",\"after\":\"" + after + "\"") + "}";
            connection = new ReactorNettyWebSocketClient()
                    .execute(wsUri, session -> {
                        Mono<Void> send = session.send(outbound.asFlux().map(session::textMessage));
                        Mono<Void> receive = session.receive()
                                .map(WebSocketMessage::getPayloadAsText)
                                .map(PushCrossTransportResumeEndToEndTest::parse)
                                .doOnNext(f -> {
                                    String type = f.path("type").asString();
                                    if ("connected".equals(type)) {
                                        outbound.tryEmitNext(sub);
                                    } else if ("event".equals(type)) {
                                        events.add(new Ev(f.get("id").asString(), nOf(f.get("data"))));
                                    } else if ("error".equals(type)) {
                                        refusal.set(f.get("code").asString());
                                    }
                                })
                                .then();
                        return Mono.zip(send, receive).then();
                    })
                    .subscribe(v -> {}, e -> refusal.compareAndSet(null, "connect: " + e));
        }

        @Override
        public List<Ev> events() {
            return List.copyOf(events);
        }

        @Override
        public String refusal() {
            return refusal.get();
        }

        @Override
        public void close() {
            outbound.tryEmitComplete();
            connection.dispose();
        }
    }

    // ---- gRPC ----

    private final class GrpcFeed implements Feed, StreamObserver<PushEvent> {
        private final List<Ev> events = new CopyOnWriteArrayList<>();
        private final AtomicReference<String> refusal = new AtomicReference<>();
        private final io.grpc.Context.CancellableContext context =
                io.grpc.Context.current().withCancellation();

        GrpcFeed(String name, String after) {
            SubscribeRequest.Builder request = SubscribeRequest.newBuilder().addTopics("xt:" + name);
            if (after != null) request.setAfter(after);
            PushServiceGrpc.PushServiceStub stub = PushServiceGrpc.newStub(channel);
            SubscribeRequest built = request.build();
            context.run(() -> stub.subscribe(built, this));
        }

        @Override
        public void onNext(PushEvent value) {
            events.add(new Ev(value.getId(), (int)
                    value.getJson().getStructValue().getFieldsOrThrow("n").getNumberValue()));
        }

        @Override
        public void onError(Throwable t) {
            Status status = Status.fromThrowable(t);
            if (status.getCode() != Status.Code.CANCELLED)
                refusal.set(status.getCode().name());
        }

        @Override
        public void onCompleted() {}

        @Override
        public List<Ev> events() {
            return List.copyOf(events);
        }

        @Override
        public String refusal() {
            return refusal.get();
        }

        @Override
        public void close() {
            context.cancel(null);
        }
    }

    // ---- GraphQL (graphql-transport-ws) ----

    private final class GraphQlFeed implements Feed {
        private final List<Ev> events = new CopyOnWriteArrayList<>();
        private final AtomicReference<String> refusal = new AtomicReference<>();
        private final Sinks.Many<String> outbound = Sinks.many().unicast().onBackpressureBuffer();
        private final Disposable connection;

        GraphQlFeed(String name, String after) {
            String query = "subscription { xtEvents(topic: \"" + name + "\""
                    + (after == null ? "" : ", after: \"" + after + "\"") + ") { id payload } }";
            outbound.tryEmitNext("{\"type\":\"connection_init\"}");
            String subscribeFrame;
            try {
                subscribeFrame = JSON.writeValueAsString(
                        Map.of("id", "1", "type", "subscribe", "payload", Map.of("query", query)));
            } catch (tools.jackson.core.JacksonException e) {
                throw new IllegalStateException(e);
            }
            WebSocketHandler handler = new WebSocketHandler() {
                @Override
                public List<String> getSubProtocols() {
                    return List.of("graphql-transport-ws");
                }

                @Override
                public Mono<Void> handle(WebSocketSession session) {
                    Mono<Void> send = session.send(outbound.asFlux().map(session::textMessage));
                    Mono<Void> receive = session.receive()
                            .map(WebSocketMessage::getPayloadAsText)
                            .map(PushCrossTransportResumeEndToEndTest::parse)
                            .doOnNext(f -> {
                                String type = f.path("type").asString();
                                if ("connection_ack".equals(type)) {
                                    outbound.tryEmitNext(subscribeFrame);
                                } else if ("next".equals(type)) {
                                    JsonNode event = f.at("/payload/data/xtEvents");
                                    if (event.isMissingNode() || event.isNull()) {
                                        refusal.set(f.at("/payload").toString());
                                    } else {
                                        events.add(new Ev(event.get("id").asString(), nOf(event.get("payload"))));
                                    }
                                } else if ("error".equals(type)) {
                                    refusal.set(f.at("/payload/0/extensions/code")
                                            .asString(f.at("/payload").toString()));
                                }
                            })
                            .then();
                    return Mono.when(send, receive);
                }
            };
            connection = new ReactorNettyWebSocketClient()
                    .execute(graphqlUri, handler)
                    .subscribe(v -> {}, e -> refusal.compareAndSet(null, "connect: " + e));
        }

        @Override
        public List<Ev> events() {
            return List.copyOf(events);
        }

        @Override
        public String refusal() {
            return refusal.get();
        }

        @Override
        public void close() {
            outbound.tryEmitComplete();
            connection.dispose();
        }
    }
}
