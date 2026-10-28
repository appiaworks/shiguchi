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
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import dev.appiaworks.shiguchi.cursus.PushMetrics;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * The push namespaces as GraphQL subscription fields on the Boot 4 gateway (mirrors
 * {@code PushGraphQlEndToEndTest} in spring-boot3/shiguchi-cursus-starter; the push and GraphQL
 * modules are byte-identical between the lines), over a real
 * graphql-transport-ws connection: a typed namespace ({@code payload-type}) delivers its payload
 * as the generated object type with field selection, an untyped one as {@code JSON}; {@code after}
 * resumes from the history; a namespace that needs a principal refuses an anonymous subscriber
 * and serves one with a token; the schema advertises the fields; the connection is counted under
 * the {@code graphql} transport.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.openapi.enabled=false",
            "spring.graphql.websocket.path=/graphql/ws",
            "shiguchi.cursus.enabled=true",
            "shiguchi.cursus.namespaces.ticker.publish-auth=NONE",
            "shiguchi.cursus.namespaces.orders.qos=AT_LEAST_ONCE",
            "shiguchi.cursus.namespaces.orders.history-size=100",
            "shiguchi.cursus.namespaces.orders.publish-auth=NONE",
            "shiguchi.cursus.namespaces.orders.payload-type=ev.OrderEvent",
            "shiguchi.cursus.namespaces.secure.auth=AUTHENTICATED",
            "shiguchi.cursus.namespaces.secure.publish-auth=NONE",
            "shiguchi.cursus.namespaces.secure.graphql-field=privateEvents",
            "shiguchi.cursus.connection.keepalive=200ms",
            "shiguchi.cursus.connection.max-lifetime=0",
            "management.endpoints.web.exposure.include=gateway-push,health"
        })
@DisplayName("Push as GraphQL subscriptions end to end (Boot 4, DGS)")
class PushGraphQlEndToEndTest {

    static final String USER_TOKEN = "user-token";

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {
        @Bean
        ReactiveJwtDecoder fixedTokens() {
            return token -> USER_TOKEN.equals(token)
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
    private static final Path PB_FILE = writeDescriptorSet();
    private static final int UNUSED_BACKEND_PORT = freePort();

    /** A service whose proto declares {@code ev.OrderEvent}; its backend is never called. */
    @DynamicPropertySource
    static void service(DynamicPropertyRegistry registry) {
        registry.add("shiguchi.services[0].name", () -> "ev");
        registry.add("shiguchi.services[0].mode", () -> "PB_FILE");
        registry.add("shiguchi.services[0].pb-descriptor-path", () -> PB_FILE.toString());
        registry.add("shiguchi.services[0].host", () -> "localhost");
        registry.add("shiguchi.services[0].port", () -> UNUSED_BACKEND_PORT);
    }

    @AfterAll
    static void cleanUp() throws IOException {
        Files.deleteIfExists(PB_FILE);
    }

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
        wsUri = URI.create("ws://localhost:" + port + "/graphql/ws");
    }

    @Test
    @DisplayName(
            "GIVEN the push surface and the GraphQL surface on WHEN the schema is introspected SHOULD have one subscription field per namespace with a typed or JSON payload")
    void given_pushOn_when_introspected_should_haveSubscriptionFields() {
        JsonNode subscription = graphql(
                        "{ __type(name: \"Subscription\") { fields { name args { name } type { name } } } }")
                .at("/data/__type/fields");
        assertThat(subscription)
                .extracting(f -> f.get("name").asText())
                .containsExactlyInAnyOrder("tickerEvents", "ordersEvents", "privateEvents");
        JsonNode orders = find(subscription, "ordersEvents");
        assertThat(orders.get("args"))
                .extracting(a -> a.get("name").asText())
                .containsExactly("topic", "after", "origin");
        assertThat(orders.at("/type/name").asText()).isEqualTo("OrdersEventsEnvelope");

        JsonNode envelope = graphql(
                        "{ __type(name: \"OrdersEventsEnvelope\") { fields { name type { name ofType { name } } } } }")
                .at("/data/__type/fields");
        assertThat(find(envelope, "payload").at("/type/name").asText()).isEqualTo("OrderEvent");
        JsonNode tickerEnvelope = graphql(
                        "{ __type(name: \"TickerEventsEnvelope\") { fields { name type { name } } } }")
                .at("/data/__type/fields");
        assertThat(find(tickerEnvelope, "payload").at("/type/name").asText()).isEqualTo("JSON");
    }

    @Test
    @DisplayName(
            "GIVEN a typed namespace WHEN subscribed over graphql-transport-ws and an event is published as JSON SHOULD deliver a next frame with the selected payload fields, the attributes and the id")
    void given_typedNamespace_when_published_should_deliverTypedPayload() {
        int before = metrics.connections("graphql");
        try (Subscription sub = subscribe(
                "subscription { ordersEvents(topic: \"42\") { id topic payload { orderId status } attributes { key value } } }",
                null)) {
            awaitTrue(() -> metrics.connections("graphql") == before + 1);
            JsonNode overview = get("/actuator/gateway-push");
            assertThat(overview.at("/connections/graphql").asInt()).isEqualTo(before + 1);
            JsonNode connections =
                    get("/actuator/gateway-push/connections?transport=graphql").get("connections");
            assertThat(connections).anyMatch(c -> c.get("topics").toString().contains("orders:42"));

            String id = publish(
                    "orders:42", "{\"orderId\":\"42\",\"status\":\"SHIPPED\",\"amount\":3}", "{\"src\":\"test\"}");

            JsonNode next = sub.awaitNext(1).get(0);
            JsonNode event = next.at("/payload/data/ordersEvents");
            assertThat(event.get("id").asText()).isEqualTo(id);
            assertThat(event.get("topic").asText()).isEqualTo("orders:42");
            assertThat(event.at("/payload/orderId").asText()).isEqualTo("42");
            assertThat(event.at("/payload/status").asText()).isEqualTo("SHIPPED");
            assertThat(event.at("/payload").has("amount"))
                    .as("unselected field")
                    .isFalse();
            assertThat(event.get("attributes")).hasSize(1);
            assertThat(event.at("/attributes/0/key").asText()).isEqualTo("src");
            assertThat(next.at("/payload/errors").isMissingNode()).isTrue();
        }
        awaitTrue(() -> metrics.connections("graphql") == before);
    }

    @Test
    @DisplayName(
            "GIVEN three events in a history WHEN subscribed with after = the first id SHOULD replay the other two then deliver live events")
    void given_history_when_subscribedWithAfter_should_replayThenLive() {
        String first = publish("orders:7", "{\"orderId\":\"7\",\"status\":\"NEW\"}", null);
        publish("orders:7", "{\"orderId\":\"7\",\"status\":\"PAID\"}", null);
        publish("orders:7", "{\"orderId\":\"7\",\"status\":\"SHIPPED\"}", null);
        int before = metrics.connections("graphql");
        try (Subscription sub = subscribe(
                "subscription { ordersEvents(topic: \"7\", after: \"" + first + "\") { payload { status } } }", null)) {
            List<JsonNode> replayed = sub.awaitNext(2);
            assertThat(replayed)
                    .extracting(n ->
                            n.at("/payload/data/ordersEvents/payload/status").asText())
                    .containsExactly("PAID", "SHIPPED");
            awaitTrue(() -> metrics.connections("graphql") == before + 1);
            publish("orders:7", "{\"orderId\":\"7\",\"status\":\"DELIVERED\"}", null);
            assertThat(sub.awaitNext(3)
                            .get(2)
                            .at("/payload/data/ordersEvents/payload/status")
                            .asText())
                    .isEqualTo("DELIVERED");
        }
    }

    @Test
    @DisplayName("GIVEN an untyped namespace WHEN an event is published SHOULD deliver its payload as JSON")
    void given_untypedNamespace_when_published_should_deliverJsonPayload() {
        int before = metrics.connections("graphql");
        try (Subscription sub = subscribe("subscription { tickerEvents(topic: \"btc\") { id payload } }", null)) {
            awaitTrue(() -> metrics.connections("graphql") == before + 1);
            publish("ticker:btc", "{\"price\":101.5,\"tags\":[\"a\"]}", null);
            JsonNode event = sub.awaitNext(1).get(0).at("/payload/data/tickerEvents");
            assertThat(event.at("/payload/price").asDouble()).isEqualTo(101.5);
            assertThat(event.at("/payload/tags/0").asText()).isEqualTo("a");
        }
    }

    @Test
    @DisplayName(
            "GIVEN a namespace that needs a principal WHEN subscribed anonymously SHOULD answer an error frame, and WHEN subscribed with a token SHOULD deliver")
    void given_authenticatedNamespace_when_subscribed_should_refuseAnonymousAndServeToken() {
        try (Subscription anonymous = subscribe("subscription { privateEvents(topic: \"x\") { id } }", null)) {
            JsonNode error = anonymous.awaitType("error");
            assertThat(error.at("/payload/0/extensions/code").asText()).isEqualTo("unauthenticated");
            assertThat(error.at("/payload/0/extensions/status").asInt()).isEqualTo(401);
            assertThat(error.at("/payload/0/message").asText()).isNotBlank();
        }
        int before = metrics.connections("graphql");
        try (Subscription authed =
                subscribe("subscription { privateEvents(topic: \"x\") { id payload } }", USER_TOKEN)) {
            awaitTrue(() -> metrics.connections("graphql") == before + 1);
            JsonNode connections =
                    get("/actuator/gateway-push/connections?transport=graphql").get("connections");
            assertThat(connections)
                    .anyMatch(c -> "alice".equals(c.get("principal").asText()));
            publish("secure:x", "{\"n\":1}", null);
            assertThat(authed.awaitNext(1)
                            .get(0)
                            .at("/payload/data/privateEvents/payload/n")
                            .asInt())
                    .isEqualTo(1);
        }
    }

    // ---- helpers ----

    private static JsonNode find(JsonNode fields, String name) {
        for (JsonNode f : fields) if (name.equals(f.get("name").asText())) return f;
        throw new AssertionError("no field " + name + " in " + fields);
    }

    private JsonNode graphql(String query) {
        String body = client.post()
                .uri("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("query", query))
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(30));
        return parse(body);
    }

    private JsonNode get(String path) {
        return parse(client.get().uri(path).retrieve().bodyToMono(String.class).block(Duration.ofSeconds(30)));
    }

    private String publish(String topic, String dataJson, String attributesJson) {
        String body = "{\"topic\":\"" + topic + "\",\"data\":" + dataJson
                + (attributesJson == null ? "" : ",\"attributes\":" + attributesJson) + "}";
        String response = client.post()
                .uri("/push/publish")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(30));
        return parse(response).get("id").asText();
    }

    private static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (IOException e) {
            throw new AssertionError(json, e);
        }
    }

    private Subscription subscribe(String query, String token) {
        return new Subscription(wsUri, query, token);
    }

    /**
     * One graphql-transport-ws subscription: connection_init, the ack, subscribe, then every frame
     * collected until the socket is closed.
     */
    private static final class Subscription implements AutoCloseable {
        final List<JsonNode> frames = new CopyOnWriteArrayList<>();
        private final Sinks.Many<String> outbound = Sinks.many().unicast().onBackpressureBuffer();
        private final Disposable connection;

        Subscription(URI uri, String query, String token) {
            outbound.tryEmitNext("{\"type\":\"connection_init\"}");
            String subscribeFrame;
            try {
                subscribeFrame = JSON.writeValueAsString(
                        Map.of("id", "1", "type", "subscribe", "payload", Map.of("query", query)));
            } catch (IOException e) {
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
                            .map(PushGraphQlEndToEndTest::parse)
                            .doOnNext(frames::add)
                            .doOnNext(frame -> {
                                if ("connection_ack".equals(frame.path("type").asText())) {
                                    outbound.tryEmitNext(subscribeFrame);
                                }
                            })
                            .then();
                    return Mono.when(send, receive);
                }
            };
            HttpHeaders headers = new HttpHeaders();
            if (token != null) headers.setBearerAuth(token);
            connection = new ReactorNettyWebSocketClient()
                    .execute(uri, headers, handler)
                    .subscribe(v -> {}, e -> {});
        }

        List<JsonNode> next() {
            return frames.stream()
                    .filter(f -> "next".equals(f.path("type").asText()))
                    .toList();
        }

        List<JsonNode> awaitNext(int count) {
            awaitTrue(() -> next().size() >= count);
            return next();
        }

        JsonNode awaitType(String type) {
            awaitTrue(() ->
                    frames.stream().anyMatch(f -> type.equals(f.path("type").asText())));
            return frames.stream()
                    .filter(f -> type.equals(f.path("type").asText()))
                    .findFirst()
                    .orElseThrow();
        }

        @Override
        public void close() {
            outbound.tryEmitComplete();
            connection.dispose();
        }
    }

    private static void awaitTrue(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition not met in 30s");
            Thread.onSpinWait();
        }
    }

    // ---- the proto the typed namespace points at ----

    /** ev.OrderEvent {order_id, status, amount} and a service that declares it as a response. */
    private static Path writeDescriptorSet() {
        try {
            FileDescriptorProto file = FileDescriptorProto.newBuilder()
                    .setName("ev/orders.proto")
                    .setSyntax("proto3")
                    .setPackage("ev")
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("OrderEvent")
                            .addField(field("order_id", 1, FieldDescriptorProto.Type.TYPE_STRING))
                            .addField(field("status", 2, FieldDescriptorProto.Type.TYPE_STRING))
                            .addField(field("amount", 3, FieldDescriptorProto.Type.TYPE_INT32)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("GetOrderRequest")
                            .addField(field("order_id", 1, FieldDescriptorProto.Type.TYPE_STRING)))
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

    private static FieldDescriptorProto.Builder field(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
