package dev.appiaworks.shiguchi.cursus.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodOptions;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import dev.appiaworks.shiguchi.cursus.PushMetrics;
import dev.appiaworks.shiguchi.proto.api.TopicProto;
import dev.appiaworks.shiguchi.proto.api.UpstreamTopic;
import dev.appiaworks.shiguchi.serde.DynamicMessageMarshaller;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerServiceDefinition;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.ServerCalls;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
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
import reactor.core.Disposable;

/**
 * A topic backed by an upstream server stream on the Boot 3 gateway: {@code ev.Orders/Watch}
 * carries {@code (shiguchi.api.topic) = {pattern: "orders:{order_id}"}}; no namespace is
 * configured by hand. Two SSE subscribers of {@code orders:42} share one upstream stream (the
 * backend counts one call with {@code order_id = 42}) and both receive its typed events; a
 * second topic opens a second stream; when the subscribers leave, the stream is cancelled after
 * {@code idle-linger}; a topic the pattern does not match is a plain topic; the endpoint shows the
 * owned stream; the namespace's GraphQL field is typed with the stream's message.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.cursus.enabled=true",
            "shiguchi.cursus.upstream.idle-linger=300ms",
            "shiguchi.cursus.upstream.lease-ttl=2s",
            "shiguchi.cursus.upstream.retry-backoff=100ms",
            "shiguchi.cursus.connection.keepalive=200ms",
            "shiguchi.cursus.connection.max-lifetime=0",
            "spring.graphql.websocket.path=/graphql/ws",
            "management.endpoints.web.exposure.include=gateway-push,health"
        })
@DisplayName("Push topics backed by an upstream stream end to end (Boot 3)")
class PushUpstreamEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE =
            new ParameterizedTypeReference<>() {};
    private static final FileDescriptor FILE = buildFile();
    private static final Descriptor EVENT = FILE.findMessageTypeByName("OrderEvent");
    private static final Descriptor REQUEST = FILE.findMessageTypeByName("WatchRequest");
    /** Open Watch calls per order id, and the most ever open at once. */
    private static final Map<String, AtomicInteger> OPEN = new ConcurrentHashMap<>();

    private static final Map<String, AtomicInteger> MAX_OPEN = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService TICKER = Executors.newSingleThreadScheduledExecutor();
    private static final Server BACKEND = startBackend();
    private static final Path PB_FILE = writeDescriptorSet();

    @DynamicPropertySource
    static void backend(DynamicPropertyRegistry registry) {
        registry.add("shiguchi.services[0].name", () -> "ev");
        registry.add("shiguchi.services[0].mode", () -> "PB_FILE");
        registry.add("shiguchi.services[0].pb-descriptor-path", () -> PB_FILE.toString());
        registry.add("shiguchi.services[0].host", () -> "localhost");
        registry.add("shiguchi.services[0].port", () -> BACKEND.getPort());
    }

    @AfterAll
    static void stop() throws IOException {
        BACKEND.shutdownNow();
        TICKER.shutdownNow();
        Files.deleteIfExists(PB_FILE);
    }

    @Autowired
    private Environment environment;

    @Autowired
    private PushMetrics metrics;

    private WebClient client;

    @BeforeEach
    void client() {
        client = WebClient.create("http://localhost:" + environment.getRequiredProperty("local.server.port"));
    }

    @Test
    @DisplayName(
            "GIVEN two subscribers of one topic WHEN they subscribe SHOULD share one upstream stream, both receive its typed events, and the stream closes after idle-linger once both left")
    void given_twoSubscribers_when_subscribed_should_shareOneUpstreamStream() {
        List<ServerSentEvent<String>> a = new CopyOnWriteArrayList<>();
        List<ServerSentEvent<String>> b = new CopyOnWriteArrayList<>();
        Disposable first = subscribe("orders:42", a);
        Disposable second = subscribe("orders:42", b);
        try {
            awaitTrue(() -> a.size() >= 2 && b.size() >= 2);
            assertThat(open("42")).isEqualTo(1);
            assertThat(maxOpen("42"))
                    .as("one upstream call for two subscribers")
                    .isEqualTo(1);
            JsonNode event = parse(a.get(0).data());
            assertThat(event.get("orderId").asText()).isEqualTo("42");
            assertThat(event.get("status").asText()).isEqualTo("tick");
            assertThat(event.get("seq").isInt()).isTrue();
            assertThat(a.get(0).event()).isEqualTo("orders:42");
            assertThat(metrics.upstreams()).isEqualTo(1);

            JsonNode overview = get("/actuator/gateway-push");
            assertThat(overview.at("/engine/upstreams/patterns").asInt()).isEqualTo(1);
            assertThat(overview.at("/engine/upstreams/owned"))
                    .extracting(JsonNode::asText)
                    .contains("orders:42");
            JsonNode topics = get("/actuator/gateway-push/topics?q=orders:42").get("topics");
            assertThat(topics.get(0).get("upstream").asText()).isEqualTo("owner");
            assertThat(topics.get(0).get("subscribers").asInt()).isEqualTo(2);
        } finally {
            first.dispose();
            second.dispose();
        }
        awaitTrue(() -> open("42") == 0);
        awaitTrue(() -> metrics.upstreams() == 0);
    }

    @Test
    @DisplayName(
            "GIVEN another topic of the pattern WHEN subscribed SHOULD open its own upstream stream with its own request")
    void given_otherTopic_when_subscribed_should_openOwnStream() {
        List<ServerSentEvent<String>> frames = new CopyOnWriteArrayList<>();
        Disposable stream = subscribe("orders:7", frames);
        try {
            awaitTrue(() -> !frames.isEmpty());
            assertThat(parse(frames.get(0).data()).get("orderId").asText()).isEqualTo("7");
            assertThat(open("7")).isEqualTo(1);
            assertThat(open("42")).isZero();
        } finally {
            stream.dispose();
        }
        awaitTrue(() -> open("7") == 0);
    }

    @Test
    @DisplayName(
            "GIVEN a topic the pattern does not match WHEN subscribed and published to over HTTP SHOULD behave as a plain topic without an upstream")
    void given_unmatchedTopic_when_used_should_bePlainTopic() {
        List<ServerSentEvent<String>> frames = new CopyOnWriteArrayList<>();
        int before = metrics.connections();
        Disposable stream = subscribe("orders:eu/west", frames);
        try {
            awaitTrue(() -> metrics.connections() == before + 1);
            assertThat(metrics.upstreams()).isZero();
            int status = client.post()
                    .uri("/push/publish")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"topic\":\"orders:eu/west\",\"data\":{\"manual\":true}}")
                    .exchangeToMono(
                            r -> reactor.core.publisher.Mono.just(r.statusCode().value()))
                    .block(Duration.ofSeconds(30));
            assertThat(status)
                    .as("publish-auth defaults to AUTHENTICATED on an auto-declared namespace")
                    .isEqualTo(401);
            JsonNode topics = get("/actuator/gateway-push/topics?q=eu/west").get("topics");
            assertThat(topics.get(0).has("upstream")).isFalse();
        } finally {
            stream.dispose();
        }
    }

    @Test
    @DisplayName(
            "GIVEN the namespace declared by the option WHEN the GraphQL schema is read SHOULD type the subscription field's payload with the stream's message")
    void given_declaredNamespace_when_schemaRead_should_typePayload() {
        JsonNode namespaces = get("/actuator/gateway-push/namespaces").get("namespaces");
        assertThat(namespaces).extracting(n -> n.get("name").asText()).contains("orders");
        JsonNode envelope = graphql("{ __type(name: \"OrdersEventsEnvelope\") { fields { name type { name } } } }")
                .at("/data/__type/fields");
        JsonNode payload = null;
        for (JsonNode f : envelope) if ("payload".equals(f.get("name").asText())) payload = f;
        assertThat(payload).isNotNull();
        assertThat(payload.at("/type/name").asText()).isEqualTo("OrderEvent");
    }

    // ---- helpers ----

    private static int open(String orderId) {
        AtomicInteger n = OPEN.get(orderId);
        return n == null ? 0 : n.get();
    }

    private static int maxOpen(String orderId) {
        AtomicInteger n = MAX_OPEN.get(orderId);
        return n == null ? 0 : n.get();
    }

    private Disposable subscribe(String topic, List<ServerSentEvent<String>> frames) {
        int before = metrics.connections();
        Disposable d = client.get()
                .uri("/push/subscribe?topic=" + topic)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(SSE)
                .filter(e -> e.event() != null && !"close".equals(e.event()))
                .subscribe(frames::add);
        awaitTrue(() -> metrics.connections() == before + 1);
        return d;
    }

    private JsonNode get(String path) {
        return parse(client.get().uri(path).retrieve().bodyToMono(String.class).block(Duration.ofSeconds(30)));
    }

    private JsonNode graphql(String query) {
        return parse(client.post()
                .uri("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("query", query))
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(30)));
    }

    private static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (IOException e) {
            throw new AssertionError(json, e);
        }
    }

    private static void awaitTrue(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition not met in 30s");
            Thread.onSpinWait();
        }
    }

    // ---- the backend: ev.Orders/Watch(WatchRequest{order_id}) returns (stream OrderEvent) ----

    private static FileDescriptor buildFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("ev/orders.proto")
                    .setSyntax("proto3")
                    .setPackage("ev")
                    .addDependency("shiguchi/api/topic.proto")
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("WatchRequest")
                            .addField(field("order_id", 1, FieldDescriptorProto.Type.TYPE_STRING)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("OrderEvent")
                            .addField(field("order_id", 1, FieldDescriptorProto.Type.TYPE_STRING))
                            .addField(field("status", 2, FieldDescriptorProto.Type.TYPE_STRING))
                            .addField(field("seq", 3, FieldDescriptorProto.Type.TYPE_INT32)))
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Orders")
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("Watch")
                                    .setInputType(".ev.WatchRequest")
                                    .setOutputType(".ev.OrderEvent")
                                    .setServerStreaming(true)
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    TopicProto.topic,
                                                    UpstreamTopic.newBuilder()
                                                            .setPattern("orders:{order_id}")
                                                            .build()))))
                    .build();
            return FileDescriptor.buildFrom(proto, new FileDescriptor[] {TopicProto.getDescriptor()});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static FieldDescriptorProto.Builder field(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
    }

    private static Path writeDescriptorSet() {
        try {
            Path path = Files.createTempFile("ev-orders-upstream", ".pb");
            FileDescriptorSet set = FileDescriptorSet.newBuilder()
                    .addFile(DescriptorProtos.getDescriptor().toProto())
                    .addFile(TopicProto.getDescriptor().toProto())
                    .addFile(FILE.toProto())
                    .build();
            Files.write(path, set.toByteArray());
            return path;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Emits an OrderEvent every 100 ms for the requested order until the call is cancelled. */
    private static Server startBackend() {
        io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> watch =
                io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                        .setType(MethodType.SERVER_STREAMING)
                        .setFullMethodName("ev.Orders/Watch")
                        .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(REQUEST))
                        .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(EVENT))
                        .build();
        ServerServiceDefinition service = ServerServiceDefinition.builder("ev.Orders")
                .addMethod(watch, ServerCalls.asyncServerStreamingCall((request, observer) -> {
                    String orderId = (String) request.getField(REQUEST.findFieldByName("order_id"));
                    ServerCallStreamObserver<DynamicMessage> call = (ServerCallStreamObserver<DynamicMessage>) observer;
                    int now = OPEN.computeIfAbsent(orderId, k -> new AtomicInteger())
                            .incrementAndGet();
                    MAX_OPEN.computeIfAbsent(orderId, k -> new AtomicInteger()).accumulateAndGet(now, Math::max);
                    AtomicInteger seq = new AtomicInteger();
                    ScheduledFuture<?> ticks = TICKER.scheduleAtFixedRate(
                            () -> {
                                if (call.isCancelled()) return;
                                try {
                                    call.onNext(DynamicMessage.newBuilder(EVENT)
                                            .setField(EVENT.findFieldByName("order_id"), orderId)
                                            .setField(EVENT.findFieldByName("status"), "tick")
                                            .setField(EVENT.findFieldByName("seq"), seq.incrementAndGet())
                                            .build());
                                } catch (RuntimeException ignored) {
                                    // the call ended under us
                                }
                            },
                            0,
                            100,
                            TimeUnit.MILLISECONDS);
                    call.setOnCancelHandler(() -> {
                        ticks.cancel(false);
                        OPEN.get(orderId).decrementAndGet();
                    });
                }))
                .build();
        try {
            return ServerBuilder.forPort(0).addService(service).build().start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
