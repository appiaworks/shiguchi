package dev.appiaworks.shiguchi.cursus.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Empty;
import com.google.protobuf.EmptyProto;
import com.google.protobuf.TimestampProto;
import dev.appiaworks.shiguchi.cursus.PushMetrics;
import dev.appiaworks.shiguchi.proto.cursus.ConnectionInfo;
import dev.appiaworks.shiguchi.proto.cursus.PushHooksProto;
import dev.appiaworks.shiguchi.proto.cursus.TopicActivity;
import dev.appiaworks.shiguchi.serde.DynamicMessageMarshaller;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerServiceDefinition;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
import org.springframework.context.annotation.Bean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
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
 * A gRPC-stream source and the gRPC hooks on the Boot 4 gateway (mirrors
 * {@code PushSourcesHooksEndToEndTest} in spring-boot3/shiguchi-cursus-starter; the push module is
 * byte-identical between the lines): {@code ev.Orders/Events} is
 * configured as a {@code GRPC_STREAM} source with {@code topic-template: orders:{order_id}}; the
 * gateway opens it at startup (one owner, the lease), a subscriber of {@code orders:42} receives
 * its typed events, the two messages without an order id land in the dead-letter queue, where an
 * operator retries one to a topic of their choice and deletes the other. {@code ev.Hooks}
 * implements the {@code shiguchi.cursus.v1.PushHooks} contract and is told about the connection,
 * the topic's first subscriber and, after {@code hooks.idle-linger}, its last one.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.graphql.enabled=false",
            "shiguchi.cursus.enabled=true",
            "shiguchi.cursus.sources[0].name=orders-stream",
            "shiguchi.cursus.sources[0].type=GRPC_STREAM",
            "shiguchi.cursus.sources[0].topic-template=orders:{order_id}",
            "shiguchi.cursus.sources[0].key-field=order_id",
            "shiguchi.cursus.sources[0].attributes.region=eu",
            "shiguchi.cursus.sources[0].grpc.service=ev.Orders",
            "shiguchi.cursus.sources[0].grpc.method=Events",
            "shiguchi.cursus.hooks.grpc-service=ev.Hooks",
            "shiguchi.cursus.hooks.idle-linger=300ms",
            "shiguchi.cursus.upstream.lease-ttl=2s",
            "shiguchi.cursus.upstream.retry-backoff=100ms",
            "shiguchi.cursus.admin.role=PUSH_ADMIN",
            "shiguchi.cursus.admin.payload-visibility=FULL",
            "shiguchi.cursus.connection.keepalive=200ms",
            "shiguchi.cursus.connection.max-lifetime=0",
            "management.endpoints.web.exposure.include=gateway-push,health"
        })
@DisplayName("Push gRPC-stream source, dead-letter queue and gRPC hooks end to end (Boot 4)")
class PushSourcesHooksEndToEndTest {

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
    private static final FileDescriptor ORDERS = buildOrders();
    private static final FileDescriptor HOOKS = buildHooks();
    private static final Descriptor EVENT = ORDERS.findMessageTypeByName("OrderEvent");
    private static final Descriptor REQUEST = ORDERS.findMessageTypeByName("EventsRequest");
    private static final AtomicInteger OPEN = new AtomicInteger();
    private static final List<TopicActivity> ACTIVE = new CopyOnWriteArrayList<>();
    private static final List<TopicActivity> IDLE = new CopyOnWriteArrayList<>();
    private static final List<ConnectionInfo> CONNECTED = new CopyOnWriteArrayList<>();
    private static final List<ConnectionInfo> DISCONNECTED = new CopyOnWriteArrayList<>();
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
            "GIVEN a gRPC-stream source WHEN the gateway runs SHOULD own the stream, publish its typed events into the templated topics for a subscriber, and show the source in the admin views")
    void given_grpcStreamSource_when_running_should_publishTypedEvents() {
        awaitTrue(() -> OPEN.get() == 1);
        List<ServerSentEvent<String>> frames = new CopyOnWriteArrayList<>();
        Disposable stream = subscribe("orders:42", frames);
        try {
            awaitTrue(() -> frames.size() >= 2);
            JsonNode event = parse(frames.get(0).data());
            assertThat(event.get("orderId").asText()).isEqualTo("42");
            assertThat(event.get("status").asText()).isEqualTo("tick");
            assertThat(frames.get(0).event()).isEqualTo("orders:42");
            assertThat(metrics.sources()).isEqualTo(1);

            JsonNode overview = get(ADMIN, null);
            JsonNode source = overview.at("/engine/sources/0");
            assertThat(source.get("name").asText()).isEqualTo("orders-stream");
            assertThat(source.get("state").asText()).isEqualTo("owner");
            assertThat(source.get("shared").asBoolean()).isTrue();
            assertThat(source.get("published").asLong()).isPositive();
            assertThat(source.get("kind").asText()).contains("GRPC_STREAM", "ev.Orders/Events");
            assertThat(get(ADMIN + "/sources", null).get("sources")).hasSize(1);
            assertThat(overview.at("/engine/hooks/count").asInt()).isEqualTo(1);
            assertThat(overview.at("/engine/hooks/names").get(0).asText()).isEqualTo("GrpcPushHook");
            JsonNode namespaces = get(ADMIN + "/namespaces", null).get("namespaces");
            assertThat(namespaces).extracting(n -> n.get("name").asText()).contains("orders");
        } finally {
            stream.dispose();
        }
        assertThat(OPEN.get()).as("the source stream outlives its subscribers").isEqualTo(1);
    }

    @Test
    @DisplayName(
            "GIVEN the hooks service WHEN a client connects, subscribes and leaves SHOULD receive OnConnect, OnTopicActive, OnDisconnect with the topics, and OnTopicIdle after the linger")
    void given_hooksService_when_clientComesAndGoes_should_beToldEachTransition() {
        int connectedBefore = CONNECTED.size();
        List<ServerSentEvent<String>> frames = new CopyOnWriteArrayList<>();
        Disposable stream = subscribe("orders:hooked", frames);
        String connectionId;
        try {
            awaitTrue(() -> CONNECTED.size() > connectedBefore);
            ConnectionInfo connected = CONNECTED.get(CONNECTED.size() - 1);
            assertThat(connected.getTransport()).isEqualTo("sse");
            assertThat(connected.getInstance()).isNotBlank();
            assertThat(connected.getPrincipal()).isEmpty();
            connectionId = connected.getId();
            awaitTrue(() -> ACTIVE.stream().anyMatch(a -> a.getTopic().equals("orders:hooked")));
            TopicActivity active = ACTIVE.stream()
                    .filter(a -> a.getTopic().equals("orders:hooked"))
                    .findFirst()
                    .orElseThrow();
            assertThat(active.getSubscribers()).isEqualTo(1);
            assertThat(active.getInstance()).isEqualTo(connected.getInstance());
            assertThat(active.getTime().getSeconds()).isPositive();
            assertThat(IDLE.stream().noneMatch(a -> a.getTopic().equals("orders:hooked")))
                    .isTrue();
        } finally {
            stream.dispose();
        }
        awaitTrue(() -> DISCONNECTED.stream().anyMatch(c -> c.getId().equals(connectionId)));
        ConnectionInfo gone = DISCONNECTED.stream()
                .filter(c -> c.getId().equals(connectionId))
                .findFirst()
                .orElseThrow();
        assertThat(gone.getTopicsList()).containsExactly("orders:hooked");
        assertThat(gone.getReason()).isEqualTo("closed");
        awaitTrue(() -> IDLE.stream().anyMatch(a -> a.getTopic().equals("orders:hooked")));
        assertThat(IDLE.stream()
                        .filter(a -> a.getTopic().equals("orders:hooked"))
                        .findFirst()
                        .orElseThrow()
                        .getSubscribers())
                .isZero();
    }

    @Test
    @DisplayName(
            "GIVEN messages the template cannot map WHEN they arrive SHOULD land in the dead-letter queue, where the admin retries one to a chosen topic and deletes the other; other callers are refused")
    void given_unmappableMessages_when_arrived_should_deadLetterForRetryOrDelete() {
        awaitTrue(() -> get(ADMIN + "/dlq", null).get("dlq").size() >= 2);
        JsonNode dlq = get(ADMIN + "/dlq?q=orders-stream", null).get("dlq");
        JsonNode first = dlq.get(0);
        assertThat(first.get("source").asText()).isEqualTo("orders-stream");
        assertThat(first.get("reason").asText()).startsWith("topic:");
        assertThat(first.get("topic").isNull()).isTrue();
        assertThat(first.get("typeUrl").asText()).isEqualTo("type.googleapis.com/ev.OrderEvent");
        assertThat(first.get("data").asText()).contains("\"status\":\"no-order\"");
        String retryId = first.get("id").asText();
        String deleteId = dlq.get(1).get("id").asText();
        assertThat(get(ADMIN + "/dlq/" + retryId, null).get("id").asText()).isEqualTo(retryId);
        assertThat(get(ADMIN + "/dlq?q=nope", null).get("dlq")).isEmpty();

        List<ServerSentEvent<String>> frames = new CopyOnWriteArrayList<>();
        Disposable stream = subscribe("orders:manual", frames);
        try {
            assertThat(status("POST", ADMIN + "/dlq/" + retryId + "/retry", "{\"topic\":\"orders:manual\"}", null))
                    .isEqualTo(401);
            assertThat(status("POST", ADMIN + "/dlq/" + retryId + "/retry", "{}", ADMIN_TOKEN))
                    .as("an entry without a topic needs one")
                    .isEqualTo(400);
            JsonNode retried = body(exchange(
                    "POST", ADMIN + "/dlq/" + retryId + "/retry", "{\"topic\":\"orders:manual\"}", ADMIN_TOKEN));
            assertThat(retried.get("topic").asText()).isEqualTo("orders:manual");
            assertThat(retried.get("eventId").asText()).isNotBlank();
            awaitTrue(() -> !frames.isEmpty());
            assertThat(parse(frames.get(0).data()).get("status").asText()).isEqualTo("no-order");
            assertThat(status("GET", ADMIN + "/dlq/" + retryId, null, null)).isEqualTo(404);

            assertThat(status("DELETE", ADMIN + "/dlq/" + deleteId, null, null)).isEqualTo(401);
            assertThat(status("DELETE", ADMIN + "/dlq/" + deleteId, null, ADMIN_TOKEN))
                    .isEqualTo(200);
            assertThat(status("DELETE", ADMIN + "/dlq/" + deleteId, null, ADMIN_TOKEN))
                    .isEqualTo(404);
            JsonNode audit = get(ADMIN + "/audit", null).get("audit");
            assertThat(audit).extracting(e -> e.get("action").asText()).contains("dlq_retry", "dlq_delete");
            assertThat(get(ADMIN, null).at("/engine/dlq/added").asLong()).isGreaterThanOrEqualTo(2);
        } finally {
            stream.dispose();
        }
    }

    // ---- helpers ----

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
        Mono<String[]> call = (json == null
                        ? spec
                        : spec.contentType(MediaType.APPLICATION_JSON).bodyValue(json))
                .exchangeToMono(r -> r.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(b -> new String[] {String.valueOf(r.statusCode().value()), b}));
        return call.block(Duration.ofSeconds(30));
    }

    private static JsonNode body(String[] response) {
        assertThat(response[0]).as("status: %s", response[1]).isEqualTo("200");
        return parse(response[1]);
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

    // ---- the backend: ev.Orders/Events (stream OrderEvent) and ev.Hooks (the PushHooks contract) ----

    private static FileDescriptor buildOrders() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("ev/orders.proto")
                    .setSyntax("proto3")
                    .setPackage("ev")
                    .addMessageType(DescriptorProto.newBuilder().setName("EventsRequest"))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("OrderEvent")
                            .addField(field("order_id", 1, FieldDescriptorProto.Type.TYPE_STRING))
                            .addField(field("status", 2, FieldDescriptorProto.Type.TYPE_STRING))
                            .addField(field("seq", 3, FieldDescriptorProto.Type.TYPE_INT32)))
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Orders")
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("Events")
                                    .setInputType(".ev.EventsRequest")
                                    .setOutputType(".ev.OrderEvent")
                                    .setServerStreaming(true)))
                    .build();
            return FileDescriptor.buildFrom(proto, new FileDescriptor[0]);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static FileDescriptor buildHooks() {
        try {
            ServiceDescriptorProto.Builder service =
                    ServiceDescriptorProto.newBuilder().setName("Hooks");
            for (String m : List.of("OnTopicActive", "OnTopicIdle")) {
                service.addMethod(MethodDescriptorProto.newBuilder()
                        .setName(m)
                        .setInputType(".shiguchi.cursus.v1.TopicActivity")
                        .setOutputType(".google.protobuf.Empty"));
            }
            for (String m : List.of("OnConnect", "OnDisconnect")) {
                service.addMethod(MethodDescriptorProto.newBuilder()
                        .setName(m)
                        .setInputType(".shiguchi.cursus.v1.ConnectionInfo")
                        .setOutputType(".google.protobuf.Empty"));
            }
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("ev/hooks.proto")
                    .setSyntax("proto3")
                    .setPackage("ev")
                    .addDependency("shiguchi/push_hooks.proto")
                    .addDependency("google/protobuf/empty.proto")
                    .addService(service)
                    .build();
            return FileDescriptor.buildFrom(
                    proto, new FileDescriptor[] {PushHooksProto.getDescriptor(), EmptyProto.getDescriptor()});
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
            Path path = Files.createTempFile("ev-sources-hooks", ".pb");
            FileDescriptorSet set = FileDescriptorSet.newBuilder()
                    .addFile(TimestampProto.getDescriptor().toProto())
                    .addFile(EmptyProto.getDescriptor().toProto())
                    .addFile(PushHooksProto.getDescriptor().toProto())
                    .addFile(ORDERS.toProto())
                    .addFile(HOOKS.toProto())
                    .build();
            Files.write(path, set.toByteArray());
            return path;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Events: two messages without an order id first (dead-lettered), then an OrderEvent for order
     * 42 every 100 ms until the call is cancelled. Hooks: every call recorded, Empty answered.
     */
    private static Server startBackend() {
        io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> events =
                io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                        .setType(MethodType.SERVER_STREAMING)
                        .setFullMethodName("ev.Orders/Events")
                        .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(REQUEST))
                        .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(EVENT))
                        .build();
        ServerServiceDefinition orders = ServerServiceDefinition.builder("ev.Orders")
                .addMethod(events, ServerCalls.asyncServerStreamingCall((request, observer) -> {
                    ServerCallStreamObserver<DynamicMessage> call = (ServerCallStreamObserver<DynamicMessage>) observer;
                    OPEN.incrementAndGet();
                    for (int i = 0; i < 2; i++) {
                        call.onNext(DynamicMessage.newBuilder(EVENT)
                                .setField(EVENT.findFieldByName("status"), "no-order")
                                .setField(EVENT.findFieldByName("seq"), -1 - i)
                                .build());
                    }
                    AtomicInteger seq = new AtomicInteger();
                    ScheduledFuture<?> ticks = TICKER.scheduleAtFixedRate(
                            () -> {
                                if (call.isCancelled()) return;
                                try {
                                    call.onNext(DynamicMessage.newBuilder(EVENT)
                                            .setField(EVENT.findFieldByName("order_id"), "42")
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
                        OPEN.decrementAndGet();
                    });
                }))
                .build();
        ServerServiceDefinition.Builder hooks = ServerServiceDefinition.builder("ev.Hooks");
        hooks.addMethod(
                unary("OnTopicActive", TopicActivity.getDefaultInstance()), ServerCalls.asyncUnaryCall((req, obs) -> {
                    ACTIVE.add(req);
                    done(obs);
                }));
        hooks.addMethod(
                unary("OnTopicIdle", TopicActivity.getDefaultInstance()), ServerCalls.asyncUnaryCall((req, obs) -> {
                    IDLE.add(req);
                    done(obs);
                }));
        hooks.addMethod(
                unary("OnConnect", ConnectionInfo.getDefaultInstance()), ServerCalls.asyncUnaryCall((req, obs) -> {
                    CONNECTED.add(req);
                    done(obs);
                }));
        hooks.addMethod(
                unary("OnDisconnect", ConnectionInfo.getDefaultInstance()), ServerCalls.asyncUnaryCall((req, obs) -> {
                    DISCONNECTED.add(req);
                    done(obs);
                }));
        try {
            return ServerBuilder.forPort(0)
                    .addService(orders)
                    .addService(hooks.build())
                    .build()
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static <T extends com.google.protobuf.Message> io.grpc.MethodDescriptor<T, Empty> unary(
            String name, T request) {
        return io.grpc.MethodDescriptor.<T, Empty>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("ev.Hooks/" + name)
                .setRequestMarshaller(ProtoUtils.marshaller(request))
                .setResponseMarshaller(ProtoUtils.marshaller(Empty.getDefaultInstance()))
                .build();
    }

    private static void done(StreamObserver<Empty> observer) {
        observer.onNext(Empty.getDefaultInstance());
        observer.onCompleted();
    }
}
