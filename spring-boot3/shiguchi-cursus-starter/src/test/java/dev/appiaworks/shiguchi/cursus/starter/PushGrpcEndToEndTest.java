package dev.appiaworks.shiguchi.cursus.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Any;
import com.google.protobuf.Value;
import com.google.protobuf.util.JsonFormat;
import dev.appiaworks.shiguchi.cursus.PushMetrics;
import dev.appiaworks.shiguchi.proto.cursus.PublishRequest;
import dev.appiaworks.shiguchi.proto.cursus.PublishResponse;
import dev.appiaworks.shiguchi.proto.cursus.PublishedEvent;
import dev.appiaworks.shiguchi.proto.cursus.PushEvent;
import dev.appiaworks.shiguchi.proto.cursus.PushServiceGrpc;
import dev.appiaworks.shiguchi.proto.cursus.SubscribeRequest;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

/**
 * The hosted {@code shiguchi.cursus.v1.PushService} on the Boot 3 gateway, reached every way the
 * gateway offers: JSON through the transcoding at {@code /push/v1/*}, the native gRPC proxy port,
 * gRPC-Web on the HTTP port. Events cross surfaces: a gRPC publish reaches an SSE subscriber, an
 * HTTP publish reaches a gRPC subscriber. Also the typed payload (an {@code Any} of a type the
 * gateway knows, rendered to JSON with {@code @type}), resume, the authorization refusals, and the
 * operator's disconnect ending a gRPC stream with the close trailer.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.graphql.enabled=false",
            "shiguchi.grpc.enabled=true",
            "shiguchi.grpc-web.enabled=true",
            "shiguchi.cursus.enabled=true",
            "shiguchi.cursus.namespaces.ticker.publish-auth=NONE",
            "shiguchi.cursus.namespaces.orders.qos=AT_LEAST_ONCE",
            "shiguchi.cursus.namespaces.orders.history-size=100",
            "shiguchi.cursus.namespaces.orders.publish-auth=NONE",
            "shiguchi.cursus.namespaces.inbox.auth=AUTHENTICATED",
            "shiguchi.cursus.connection.keepalive=200ms",
            "shiguchi.cursus.connection.max-lifetime=0",
            "management.endpoints.web.exposure.include=gateway-push,health"
        })
@DisplayName("Push over gRPC, gRPC-Web and transcoded JSON end to end (Boot 3)")
class PushGrpcEndToEndTest {

    static final int GRPC_PORT = freePort();
    static final String ALICE = "alice-token";

    @DynamicPropertySource
    static void grpcPort(DynamicPropertyRegistry registry) {
        registry.add("shiguchi.grpc.port", () -> GRPC_PORT);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {
        @Bean
        ReactiveJwtDecoder fixedTokens() {
            return token -> ALICE.equals(token)
                    ? Mono.just(Jwt.withTokenValue(token)
                            .header("alg", "none")
                            .subject("alice")
                            .claim("scope", "read")
                            .issuedAt(Instant.now())
                            .expiresAt(Instant.now().plusSeconds(3600))
                            .build())
                    : Mono.error(new BadJwtException("unknown token"));
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE =
            new ParameterizedTypeReference<>() {};

    @Autowired
    private Environment environment;

    @Autowired
    private PushMetrics metrics;

    private WebClient client;
    private ManagedChannel channel;

    @BeforeEach
    void connect() {
        client = WebClient.create("http://localhost:" + environment.getRequiredProperty("local.server.port"));
        channel = ManagedChannelBuilder.forAddress("localhost", GRPC_PORT)
                .usePlaintext()
                .build();
    }

    @AfterEach
    void disconnect() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    // ---- JSON through the gateway's transcoding ----

    @Test
    @DisplayName(
            "GIVEN an SSE subscriber WHEN JSON is posted to /push/v1/publish SHOULD answer the published ids and deliver the payload over SSE")
    void given_sseSubscriber_when_postedToV1Publish_should_deliver() {
        List<ServerSentEvent<String>> frames = new CopyOnWriteArrayList<>();
        int before = metrics.connections("sse");
        Disposable sse = client.get()
                .uri("/push/subscribe?topic=ticker:v1")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(SSE)
                .filter(e -> e.event() != null)
                .subscribe(frames::add);
        awaitTrue(() -> metrics.connections("sse") == before + 1);

        Response response = post(
                "/push/v1/publish",
                "{\"topics\":[\"ticker:v1\"],\"json\":{\"n\":1,\"ok\":true},\"attributes\":{\"a\":\"b\"},\"key\":\"k\"}",
                null);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode body = json(response.body());
        assertThat(body.get("events")).hasSize(1);
        assertThat(body.get("events").get(0).get("topic").asText()).isEqualTo("ticker:v1");
        assertThat(body.get("events").get(0).get("id").asText()).isNotEmpty();

        awaitTrue(() -> !frames.isEmpty());
        assertThat(frames.get(0).event()).isEqualTo("ticker:v1");
        assertThat(frames.get(0).data()).isEqualTo("{\"n\":1,\"ok\":true}");
        assertThat(frames.get(0).id())
                .isEqualTo(body.get("events").get(0).get("id").asText());
        sse.dispose();
    }

    @Test
    @DisplayName(
            "GIVEN a JSON client WHEN it streams GET /push/v1/subscribe as NDJSON SHOULD receive each event as a PushEvent document")
    void given_jsonClient_when_subscribesV1_should_streamPushEvents() {
        List<String> lines = new CopyOnWriteArrayList<>();
        int before = metrics.connections("grpc");
        Disposable stream = client.get()
                .uri("/push/v1/subscribe?topics=ticker:nd")
                .accept(MediaType.APPLICATION_NDJSON)
                .retrieve()
                .bodyToFlux(String.class)
                .subscribe(lines::add);
        awaitTrue(() -> metrics.connections("grpc") == before + 1);

        post("/push/publish", "{\"topic\":\"ticker:nd\",\"data\":{\"price\":42}}", null);
        awaitTrue(() -> !lines.isEmpty());
        JsonNode event = json(lines.get(0));
        assertThat(event.get("topic").asText()).isEqualTo("ticker:nd");
        assertThat(event.get("id").asText()).isNotEmpty();
        assertThat(event.get("json").get("price").asInt()).isEqualTo(42);
        assertThat(event.get("time").asText()).isNotEmpty();
        stream.dispose();
        awaitTrue(() -> metrics.connections("grpc") == before);
    }

    @Test
    @DisplayName(
            "GIVEN the JSON surface WHEN the payload is missing or the namespace unknown SHOULD answer 400 and 404")
    void given_jsonSurface_when_badRequests_should_refuse() {
        assertThat(post("/push/v1/publish", "{\"topics\":[\"ticker:x\"]}", null).status())
                .isEqualTo(400);
        assertThat(post("/push/v1/publish", "{\"topics\":[\"nope:x\"],\"json\":1}", null)
                        .status())
                .isEqualTo(404);
        assertThat(post("/push/v1/publish", "{\"topics\":[\"inbox:alice\"],\"json\":1}", null)
                        .status())
                .isEqualTo(401);
        assertThat(post("/push/v1/publish", "{\"topics\":[\"inbox:alice\"],\"json\":1}", ALICE)
                        .status())
                .isEqualTo(200);
    }

    // ---- native gRPC on the proxy port ----

    @Test
    @DisplayName(
            "GIVEN a gRPC subscriber on the proxy port WHEN a gRPC client publishes SHOULD deliver the event with its attributes and key")
    void given_grpcSubscriber_when_grpcPublish_should_deliver() throws Exception {
        Subscriber sub =
                subscribe(SubscribeRequest.newBuilder().addTopics("ticker:grpc").build(), null);
        awaitTrue(() -> sub.connected());

        PublishResponse published = PushServiceGrpc.newBlockingStub(channel)
                .publish(PublishRequest.newBuilder()
                        .addTopics("ticker:grpc")
                        .setJson(value("{\"n\":7}"))
                        .putAttributes("a", "b")
                        .setKey("k")
                        .build());
        assertThat(published.getEventsList())
                .extracting(PublishedEvent::getTopic)
                .containsExactly("ticker:grpc");

        awaitTrue(() -> !sub.events.isEmpty());
        PushEvent event = sub.events.get(0);
        assertThat(event.getTopic()).isEqualTo("ticker:grpc");
        assertThat(event.getId()).isEqualTo(published.getEvents(0).getId());
        assertThat(event.getJson().getStructValue().getFieldsOrThrow("n").getNumberValue())
                .isEqualTo(7);
        assertThat(event.getAttributesMap()).containsEntry("a", "b");
        assertThat(event.getKey()).isEqualTo("k");
        sub.cancel();
    }

    @Test
    @DisplayName(
            "GIVEN an AT_LEAST_ONCE topic with history WHEN a gRPC client subscribes with after SHOULD replay what it missed, then live events")
    void given_history_when_subscribeAfter_should_replay() throws Exception {
        PushServiceGrpc.PushServiceBlockingStub stub = PushServiceGrpc.newBlockingStub(channel);
        String first = stub.publish(publish("orders:9", "1")).getEvents(0).getId();
        stub.publish(publish("orders:9", "2"));
        stub.publish(publish("orders:9", "3"));

        Subscriber sub = subscribe(
                SubscribeRequest.newBuilder()
                        .addTopics("orders:9")
                        .setAfter(first)
                        .build(),
                null);
        awaitTrue(() -> sub.events.size() == 2);
        assertThat(sub.events).extracting(e -> e.getJson().getStringValue()).containsExactly("2", "3");

        stub.publish(publish("orders:9", "4"));
        awaitTrue(() -> sub.events.size() == 3);
        assertThat(sub.events.get(2).getJson().getStringValue()).isEqualTo("4");
        sub.cancel();
    }

    @Test
    @DisplayName(
            "GIVEN a protected namespace WHEN gRPC clients publish and subscribe without and with a bearer token SHOULD answer UNAUTHENTICATED and succeed")
    void given_protectedNamespace_when_grpcCalls_should_requireToken() throws Exception {
        PushServiceGrpc.PushServiceBlockingStub anonymous = PushServiceGrpc.newBlockingStub(channel);
        assertThatThrownBy(() -> anonymous.publish(publish("inbox:alice", "hi")))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));

        Subscriber refused =
                subscribe(SubscribeRequest.newBuilder().addTopics("inbox:alice").build(), null);
        awaitTrue(() -> refused.error.get() != null);
        assertThat(Status.fromThrowable(refused.error.get()).getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);

        Subscriber sub =
                subscribe(SubscribeRequest.newBuilder().addTopics("inbox:alice").build(), ALICE);
        awaitTrue(() -> sub.connected());
        PublishResponse ok =
                withToken(PushServiceGrpc.newBlockingStub(channel), ALICE).publish(publish("inbox:alice", "hi"));
        assertThat(ok.getEventsCount()).isEqualTo(1);
        awaitTrue(() -> !sub.events.isEmpty());
        assertThat(sub.events.get(0).getJson().getStringValue()).isEqualTo("hi");
        sub.cancel();
    }

    @Test
    @DisplayName(
            "GIVEN a typed payload WHEN published SHOULD reach a gRPC subscriber as the message it was and an SSE subscriber as JSON with @type, and refuse an unknown type")
    void given_typedPayload_when_published_should_forwardMessageAndRenderJson() throws Exception {
        Subscriber sub = subscribe(
                SubscribeRequest.newBuilder().addTopics("ticker:typed").build(), null);
        awaitTrue(() -> sub.connected());
        List<ServerSentEvent<String>> frames = new CopyOnWriteArrayList<>();
        int sseBefore = metrics.connections("sse");
        Disposable sse = client.get()
                .uri("/push/subscribe?topic=ticker:typed")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(SSE)
                .filter(e -> e.event() != null)
                .subscribe(frames::add);
        awaitTrue(() -> metrics.connections("sse") == sseBefore + 1);
        PushServiceGrpc.PushServiceBlockingStub stub = PushServiceGrpc.newBlockingStub(channel);

        PublishedEvent sent =
                PublishedEvent.newBuilder().setTopic("a:b").setId("42").build();
        stub.publish(PublishRequest.newBuilder()
                .addTopics("ticker:typed")
                .setMessage(Any.pack(sent))
                .build());

        // The gRPC subscriber gets the message as published, no JSON in between.
        awaitTrue(() -> !sub.events.isEmpty());
        PushEvent typed = sub.events.get(0);
        assertThat(typed.getPayloadCase()).isEqualTo(PushEvent.PayloadCase.MESSAGE);
        assertThat(typed.getMessage().getTypeUrl()).isEqualTo("type.googleapis.com/shiguchi.cursus.v1.PublishedEvent");
        assertThat(typed.getMessage().unpack(PublishedEvent.class)).isEqualTo(sent);

        // The SSE subscriber gets its proto JSON, @type included.
        awaitTrue(() -> !frames.isEmpty());
        JsonNode rendered = json(frames.get(0).data());
        assertThat(rendered.get("@type").asText()).isEqualTo("type.googleapis.com/shiguchi.cursus.v1.PublishedEvent");
        assertThat(rendered.get("topic").asText()).isEqualTo("a:b");
        assertThat(rendered.get("id").asText()).isEqualTo("42");
        sse.dispose();

        assertThatThrownBy(() -> stub.publish(PublishRequest.newBuilder()
                        .addTopics("ticker:typed")
                        .setMessage(Any.newBuilder()
                                .setTypeUrl("type.googleapis.com/acme.Unknown")
                                .build())
                        .build()))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
                    assertThat(e.getStatus().getDescription()).contains("acme.Unknown");
                });
        sub.cancel();
    }

    @Test
    @DisplayName(
            "GIVEN a gRPC subscriber WHEN the operator disconnects it SHOULD end the stream with UNAVAILABLE and the close reason trailer")
    void given_grpcSubscriber_when_adminDisconnects_should_closeWithTrailer() throws Exception {
        int before = metrics.connections("grpc");
        Subscriber sub =
                subscribe(SubscribeRequest.newBuilder().addTopics("ticker:adm").build(), null);
        awaitTrue(() -> metrics.connections("grpc") == before + 1);

        JsonNode connections = json(get("/actuator/gateway-push/connections?transport=grpc&topic=ticker:adm"))
                .get("connections");
        assertThat(connections).hasSize(1);
        String id = connections.get(0).get("id").asText();
        assertThat(json(get("/actuator/gateway-push"))
                        .get("connections")
                        .get("grpc")
                        .asInt())
                .isGreaterThanOrEqualTo(1);

        int status = client.delete()
                .uri("/actuator/gateway-push/connections/" + id)
                .headers(h -> h.setBearerAuth(ALICE))
                .exchangeToMono(r -> Mono.just(r.statusCode().value()))
                .block(Duration.ofSeconds(30));
        assertThat(status).isEqualTo(200);

        awaitTrue(() -> sub.error.get() != null);
        Status ended = Status.fromThrowable(sub.error.get());
        assertThat(ended.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
        Metadata trailers = Status.trailersFromThrowable(sub.error.get());
        assertThat(trailers).isNotNull();
        assertThat(trailers.get(Metadata.Key.of("push-close-reason", Metadata.ASCII_STRING_MARSHALLER)))
                .isEqualTo("admin");
        awaitTrue(() -> metrics.connections("grpc") == before);
    }

    // ---- gRPC-Web on the HTTP port ----

    @Test
    @DisplayName(
            "GIVEN a gRPC-Web client WHEN it posts a framed Publish SHOULD answer a PublishResponse frame and grpc-status 0 trailers")
    void given_grpcWebClient_when_publishes_should_answerFrames() throws Exception {
        byte[] message = publish("ticker:web", "w").toByteArray();
        byte[] body = ByteBuffer.allocate(5 + message.length)
                .put((byte) 0)
                .putInt(message.length)
                .put(message)
                .array();
        byte[] bytes = client.post()
                .uri("/shiguchi.cursus.v1.PushService/Publish")
                .contentType(MediaType.parseMediaType("application/grpc-web+proto"))
                .bodyValue(body)
                .exchangeToMono(r -> {
                    assertThat(r.statusCode().value()).isEqualTo(200);
                    return r.bodyToMono(byte[].class);
                })
                .block(Duration.ofSeconds(30));

        ByteBuffer frames = ByteBuffer.wrap(bytes);
        assertThat(frames.get()).isEqualTo((byte) 0);
        byte[] data = new byte[frames.getInt()];
        frames.get(data);
        PublishResponse published = PublishResponse.parseFrom(data);
        assertThat(published.getEvents(0).getTopic()).isEqualTo("ticker:web");
        assertThat(frames.get()).isEqualTo((byte) 0x80);
        byte[] trailers = new byte[frames.getInt()];
        frames.get(trailers);
        assertThat(new String(trailers)).contains("grpc-status:0");
    }

    // ---- helpers ----

    private static PublishRequest publish(String topic, String string) {
        return PublishRequest.newBuilder()
                .addTopics(topic)
                .setJson(Value.newBuilder().setStringValue(string))
                .build();
    }

    private static Value value(String json) throws IOException {
        Value.Builder b = Value.newBuilder();
        JsonFormat.parser().merge(json, b);
        return b.build();
    }

    private static <T extends io.grpc.stub.AbstractStub<T>> T withToken(T stub, String token) {
        Metadata md = new Metadata();
        md.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
        return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(md));
    }

    /** One async gRPC subscription: the events, the terminal error, and whether the gateway counted it. */
    private final class Subscriber implements StreamObserver<PushEvent> {
        final List<PushEvent> events = new CopyOnWriteArrayList<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final int before = metrics.connections("grpc");
        final io.grpc.Context.CancellableContext context =
                io.grpc.Context.current().withCancellation();

        boolean connected() {
            return metrics.connections("grpc") > before;
        }

        void cancel() {
            context.cancel(null);
            awaitTrue(() -> metrics.connections("grpc") == before);
        }

        @Override
        public void onNext(PushEvent value) {
            events.add(value);
        }

        @Override
        public void onError(Throwable t) {
            error.set(t);
        }

        @Override
        public void onCompleted() {}
    }

    private Subscriber subscribe(SubscribeRequest request, String token) {
        Subscriber sub = new Subscriber();
        PushServiceGrpc.PushServiceStub stub = PushServiceGrpc.newStub(channel);
        if (token != null) stub = withToken(stub, token);
        PushServiceGrpc.PushServiceStub s = stub;
        sub.context.run(() -> s.subscribe(request, sub));
        return sub;
    }

    /** An HTTP answer: the status and the body, read inside the exchange (a released response has none). */
    private record Response(int status, String body) {}

    private Response post(String path, String json, String token) {
        return client.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> {
                    if (token != null) h.setBearerAuth(token);
                })
                .bodyValue(json)
                .exchangeToMono(r -> r.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(b -> new Response(r.statusCode().value(), b)))
                .block(Duration.ofSeconds(30));
    }

    private String get(String path) {
        return client.get().uri(path).retrieve().bodyToMono(String.class).block(Duration.ofSeconds(30));
    }

    private static JsonNode json(String body) {
        try {
            return JSON.readTree(body);
        } catch (IOException e) {
            throw new AssertionError(body, e);
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
}
