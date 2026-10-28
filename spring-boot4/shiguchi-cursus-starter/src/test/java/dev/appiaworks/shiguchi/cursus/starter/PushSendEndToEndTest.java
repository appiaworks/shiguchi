package dev.appiaworks.shiguchi.cursus.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.appiaworks.shiguchi.proto.cursus.DeliverRequest;
import dev.appiaworks.shiguchi.proto.cursus.PushPeerGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import redis.embedded.RedisServer;

/**
 * Targeted {@code Send} across two instances (Boot 4; mirrors {@code PushSendEndToEndTest} in
 * spring-boot3/shiguchi-cursus-starter): two gateway instances over one embedded Redis, each with its
 * own instance id, native gRPC port and the shared peer token. A {@code Send} posted to instance A
 * for a connection held by instance B reaches it through the peer channel; an unknown connection is
 * {@code NOT_CONNECTED}; {@code require_ack} waits for the WebSocket client's {@code ack} frame and
 * times out when it never comes; the peer channel refuses calls without the token and is not part of
 * the HTTP surface.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.graphql.enabled=false",
            "shiguchi.grpc.enabled=true",
            "shiguchi.cursus.enabled=true",
            "shiguchi.cursus.instance-id=node-a",
            "shiguchi.cursus.broker=REDIS",
            "shiguchi.cursus.store=REDIS",
            "shiguchi.cursus.redis.key-prefix=e2e:send:",
            "shiguchi.cursus.peer.token=s3cret",
            "shiguchi.cursus.admin.role=PUSH_ADMIN",
            "shiguchi.cursus.namespaces._direct.publish-auth=NONE",
            "shiguchi.cursus.namespaces.room.publish-auth=NONE",
            "shiguchi.cursus.connection.keepalive=200ms",
            "shiguchi.cursus.connection.max-lifetime=0",
            "management.endpoints.web.exposure.include=gateway-push,health"
        })
@DisplayName("Push targeted Send across two instances, end to end (Boot 4)")
class PushSendEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {

        /** One fixed token stands in for an issuer: the operator who reads the connections and ends one. */
        @org.springframework.context.annotation.Bean
        org.springframework.security.oauth2.jwt.ReactiveJwtDecoder fixedToken() {
            return token -> ADMIN_TOKEN.equals(token)
                    ? Mono.just(org.springframework.security.oauth2.jwt.Jwt.withTokenValue(token)
                            .header("alg", "none")
                            .subject("ops")
                            .claim("scope", "PUSH_ADMIN")
                            .issuedAt(java.time.Instant.now())
                            .expiresAt(java.time.Instant.now().plusSeconds(3600))
                            .build())
                    : Mono.error(new org.springframework.security.oauth2.jwt.BadJwtException("unknown token"));
        }
    }

    static final String ADMIN_TOKEN = "admin-token";

    /** Event loops of the second instance's own; see {@code PushPresenceEndToEndTest}. */
    static class OwnResources {
        @org.springframework.context.annotation.Bean
        org.springframework.http.client.ReactorResourceFactory reactorResourceFactory() {
            var factory = new org.springframework.http.client.ReactorResourceFactory();
            factory.setUseGlobalResources(false);
            return factory;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private static RedisServer redis;
    private static int redisPort;
    private static int grpcA;
    private static int grpcB;
    private static ConfigurableApplicationContext second;
    private static String secondPort;

    @DynamicPropertySource
    static void ports(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> redisPort);
        registry.add("shiguchi.grpc.port", () -> grpcA);
        registry.add("shiguchi.cursus.peer.address", () -> "localhost:" + grpcA);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @BeforeAll
    static void startRedisAndSecondInstance() throws IOException {
        redisPort = freePort();
        grpcA = freePort();
        grpcB = freePort();
        redis = RedisServer.newRedisServer().port(redisPort).build();
        redis.start();
        second = new SpringApplicationBuilder(TestApp.class, OwnResources.class)
                .web(WebApplicationType.REACTIVE)
                .properties(
                        "server.port=0",
                        "spring.main.banner-mode=off",
                        "spring.data.redis.host=localhost",
                        "spring.data.redis.port=" + redisPort,
                        "shiguchi.ui.enabled=false",
                        "shiguchi.openapi.enabled=false",
                        "shiguchi.graphql.enabled=false",
                        "shiguchi.grpc.enabled=true",
                        "shiguchi.grpc.port=" + grpcB,
                        "shiguchi.cursus.enabled=true",
                        "shiguchi.cursus.instance-id=node-b",
                        "shiguchi.cursus.broker=REDIS",
                        "shiguchi.cursus.store=REDIS",
                        "shiguchi.cursus.redis.key-prefix=e2e:send:",
                        "shiguchi.cursus.peer.token=s3cret",
                        "shiguchi.cursus.peer.address=localhost:" + grpcB,
                        "shiguchi.cursus.namespaces._direct.publish-auth=NONE",
                        "shiguchi.cursus.namespaces.room.publish-auth=NONE",
                        "shiguchi.cursus.connection.keepalive=200ms",
                        "shiguchi.cursus.connection.max-lifetime=0")
                .run();
        secondPort = second.getEnvironment().getRequiredProperty("local.server.port");
    }

    @AfterAll
    static void stop() throws IOException {
        if (second != null) second.close();
        if (redis != null) redis.stop();
    }

    @Autowired
    private Environment environment;

    private WebClient client;

    @BeforeEach
    void client() {
        client = WebClient.builder()
                .baseUrl("http://localhost:" + environment.getRequiredProperty("local.server.port"))
                .defaultHeader("Authorization", "Bearer " + ADMIN_TOKEN)
                .build();
    }

    @Test
    @DisplayName(
            "GIVEN a WebSocket on instance B WHEN instance A sends to its connection id SHOULD deliver the event over the peer channel with B's instance and the same id")
    void given_socketOnB_when_aSends_should_deliverThroughPeerChannel() {
        try (Socket ws = new Socket(secondPort)) {
            String id = ws.connectionId();
            awaitTrue(() -> registered(id));

            JsonNode out = send("{\"connectionId\":\"" + id + "\",\"json\":{\"hello\":\"b\"}}");

            assertThat(out.get("deliveries")).hasSize(1);
            assertThat(out.get("deliveries").get(0).get("status").asText()).isEqualTo("DELIVERED");
            assertThat(out.get("deliveries").get(0).get("instance").asText()).isEqualTo("node-b");
            JsonNode event = ws.awaitEvent();
            assertThat(event.get("topic").asText()).isEqualTo("_direct:" + id);
            assertThat(event.get("data").get("hello").asText()).isEqualTo("b");
            assertThat(event.get("id").asText()).isEqualTo(out.get("id").asText());
            assertThat(event.get("attributes").get("push.direct").asText()).isEqualTo("true");
        }
    }

    @Test
    @DisplayName(
            "GIVEN a WebSocket on instance B WHEN the operator posts connections/{id}/send on A SHOULD deliver it through the peer channel")
    void given_socketOnB_when_operatorSendsFromA_should_deliver() {
        try (Socket ws = new Socket(secondPort)) {
            String id = ws.connectionId();
            awaitTrue(() -> registered(id));

            JsonNode out = json(client.post()
                    .uri("/actuator/gateway-push/connections/" + id + "/send")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"data\":\"{\\\"op\\\":1}\"}")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(30)));

            assertThat(out.get("deliveries").get(0).get("status").asText()).isEqualTo("DELIVERED");
            assertThat(out.get("deliveries").get(0).get("instance").asText()).isEqualTo("node-b");
            JsonNode event = ws.awaitEvent();
            assertThat(event.get("data").get("op").asInt()).isEqualTo(1);
            assertThat(event.get("attributes").get("from").asText()).isEqualTo("admin");
        }
    }

    @Test
    @DisplayName("GIVEN no such connection WHEN a Send names it SHOULD answer a single NOT_CONNECTED")
    void given_unknownConnection_when_sent_should_answerNotConnected() {
        JsonNode out = send("{\"connectionId\":\"no-such-connection\",\"json\":{\"x\":1}}");

        assertThat(out.get("deliveries")).hasSize(1);
        assertThat(out.get("deliveries").get(0).get("status").asText()).isEqualTo("NOT_CONNECTED");
    }

    @Test
    @DisplayName(
            "GIVEN a WebSocket on B that acknowledges WHEN A sends with require_ack SHOULD answer DELIVERED once the ack frame arrives")
    void given_ackingSocket_when_sentWithRequireAck_should_answerDelivered() {
        try (Socket ws = new Socket(secondPort)) {
            String id = ws.connectionId();
            awaitTrue(() -> registered(id));
            ws.ackEvents();

            JsonNode out = send(
                    "{\"connectionId\":\"" + id + "\",\"json\":{\"n\":1},\"requireAck\":true,\"timeoutMs\":20000}");

            assertThat(out.get("deliveries").get(0).get("status").asText()).isEqualTo("DELIVERED");
        }
    }

    @Test
    @DisplayName("GIVEN a WebSocket on B that never acknowledges WHEN A sends with require_ack SHOULD answer TIMEOUT")
    void given_silentSocket_when_sentWithRequireAck_should_answerTimeout() {
        try (Socket ws = new Socket(secondPort)) {
            String id = ws.connectionId();
            awaitTrue(() -> registered(id));

            JsonNode out =
                    send("{\"connectionId\":\"" + id + "\",\"json\":{\"n\":1},\"requireAck\":true,\"timeoutMs\":500}");

            assertThat(out.get("deliveries").get(0).get("status").asText()).isEqualTo("TIMEOUT");
            assertThat(ws.awaitEvent().get("data").get("n").asInt()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName(
            "GIVEN a WebSocket on instance B WHEN instance A's admin disconnects it SHOULD end the socket through the peer channel")
    void given_socketOnB_when_aDisconnects_should_endIt() {
        try (Socket ws = new Socket(secondPort)) {
            String id = ws.connectionId();
            awaitTrue(() -> registered(id));

            JsonNode out = json(client.delete()
                    .uri("/actuator/gateway-push/connections/" + id)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(30)));

            assertThat(out.get("disconnected").asBoolean()).isTrue();
            awaitTrue(ws::closed);
        }
    }

    @Test
    @DisplayName(
            "GIVEN the peer channel WHEN called without the token SHOULD be UNAUTHENTICATED, and its path SHOULD not exist on the HTTP surface")
    void given_peerChannel_when_calledWithoutToken_should_refuseAndStayOffHttp() {
        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", grpcB)
                .usePlaintext()
                .build();
        try {
            assertThatThrownBy(() -> PushPeerGrpc.newBlockingStub(channel)
                            .withDeadlineAfter(10, java.util.concurrent.TimeUnit.SECONDS)
                            .deliver(DeliverRequest.getDefaultInstance()))
                    .isInstanceOfSatisfying(
                            StatusRuntimeException.class,
                            e -> assertThat(e.getStatus().getCode()).isEqualTo(io.grpc.Status.Code.UNAUTHENTICATED));
        } finally {
            channel.shutdownNow();
        }
        assertThatThrownBy(() -> client.post()
                        .uri("/push/v1/peer/deliver")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("{}")
                        .retrieve()
                        .bodyToMono(String.class)
                        .block(Duration.ofSeconds(30)))
                .isInstanceOfSatisfying(
                        WebClientResponseException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
    }

    // ---- helpers ----

    /** True once instance A's cluster-wide view lists connection {@code id}. */
    private boolean registered(String id) {
        JsonNode all = json(client.get()
                .uri("/actuator/gateway-push/connections")
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(30)));
        for (JsonNode c : all.get("connections")) if (id.equals(c.get("id").asText())) return true;
        return false;
    }

    private JsonNode send(String body) {
        return json(client.post()
                .uri("/push/v1/send")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(60)));
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
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    /** A WebSocket to instance B; {@link #ackEvents()} makes it answer every event with an {@code ack} frame. */
    private static final class Socket implements AutoCloseable {
        private final List<JsonNode> frames = new CopyOnWriteArrayList<>();
        private final Sinks.Many<String> outbound = Sinks.many().unicast().onBackpressureBuffer();
        private final Disposable connection;
        private volatile boolean acking;
        private volatile boolean closed;

        Socket(String port) {
            connection = new ReactorNettyWebSocketClient()
                    .execute(URI.create("ws://localhost:" + port + "/push/ws"), session -> {
                        Mono<Void> send = session.send(outbound.asFlux().map(session::textMessage));
                        Mono<Void> receive = session.receive()
                                .map(WebSocketMessage::getPayloadAsText)
                                .doOnNext(t -> {
                                    JsonNode frame = json(t);
                                    frames.add(frame);
                                    if (acking
                                            && "event".equals(frame.path("type").asText())) {
                                        outbound.tryEmitNext("{\"type\":\"ack\",\"topic\":\""
                                                + frame.get("topic").asText() + "\",\"id\":\""
                                                + frame.get("id").asText() + "\"}");
                                    }
                                })
                                .then();
                        return Mono.zip(send, receive).then();
                    })
                    .doFinally(s -> closed = true)
                    .subscribe();
        }

        void ackEvents() {
            acking = true;
        }

        boolean closed() {
            return closed;
        }

        String connectionId() {
            awaitTrue(() -> frames.stream()
                    .anyMatch(f -> "connected".equals(f.path("type").asText())));
            return frames.stream()
                    .filter(f -> "connected".equals(f.path("type").asText()))
                    .findFirst()
                    .orElseThrow()
                    .get("connectionId")
                    .asText();
        }

        JsonNode awaitEvent() {
            awaitTrue(() ->
                    frames.stream().anyMatch(f -> "event".equals(f.path("type").asText())));
            return frames.stream()
                    .filter(f -> "event".equals(f.path("type").asText()))
                    .findFirst()
                    .orElseThrow();
        }

        @Override
        public void close() {
            connection.dispose();
        }
    }
}
