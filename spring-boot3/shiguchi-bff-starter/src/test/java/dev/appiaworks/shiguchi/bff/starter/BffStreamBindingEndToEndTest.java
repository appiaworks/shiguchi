package dev.appiaworks.shiguchi.bff.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.appiaworks.shiguchi.bff.session.BffSessionKeys;
import dev.appiaworks.shiguchi.bff.session.BffSessionStore;
import dev.appiaworks.shiguchi.bff.testing.StubIssuer;
import dev.appiaworks.shiguchi.cursus.PushMetrics;
import dev.appiaworks.shiguchi.proto.cursus.SubscribeRequest;
import io.grpc.Server;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Long-lived streams opened with the BFF session cookie on the Boot 3 gateway, over a real port:
 * a gRPC-Web server stream ({@code PushService/Subscribe}) and a GraphQL subscription on
 * graphql-transport-ws. Each runs as the cookie's user, outlives the 2-second access token, and
 * ends when its session ends: at once on logout here, within the check interval when the store
 * loses the session. A new operation on a socket whose session ended is refused. A bearer stream
 * is not bound. Mirrors {@code spring-boot4/shiguchi-bff-starter}.
 */
@DisplayName("BFF session-bound gRPC-Web and GraphQL streams end to end (Boot 3)")
class BffStreamBindingEndToEndTest {

    private static final String ORIGIN = "https://a.example.com";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final StubIssuer STUB = StubIssuer.start();
    private static final Server BACKEND = BffTestBackend.start();
    private static final Path PB_FILE = BffTestBackend.writeDescriptorSet();
    private static final String SUBSCRIBE_ALICE = "subscription { userEvents(topic: \"alice\") { id payload } }";

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {

        @Bean
        ReactiveJwtDecoder testJwtDecoder() {
            return NimbusReactiveJwtDecoder.withJwkSetUri(STUB.issuer() + "/jwks")
                    .build();
        }
    }

    private static ConfigurableApplicationContext gateway;
    private static WebClient client;
    private static WebTestClient testClient;
    private static BffSessionStore store;
    private static PushMetrics metrics;
    private static int port;

    @BeforeAll
    static void start() {
        List<String> properties = new ArrayList<>(BffRefreshSupport.properties(STUB, BACKEND, PB_FILE));
        properties.removeIf(p -> p.startsWith("shiguchi.graphql.enabled="));
        properties.addAll(List.of(
                "shiguchi.graphql.enabled=true",
                "spring.graphql.websocket.path=/graphql/ws",
                "shiguchi.grpc-web.enabled=true",
                "shiguchi.cursus.enabled=true",
                "shiguchi.cursus.namespaces.user.auth=PRINCIPAL_MATCH",
                "shiguchi.cursus.namespaces.user.publish-auth=NONE",
                "shiguchi.cursus.connection.keepalive=1s",
                "shiguchi.cursus.connection.max-lifetime=0",
                // The gRPC-Web streams and GraphQL subscriptions are polled at this interval.
                "shiguchi.security.session-check-interval=5s",
                // The token of a seeded session is never renewed behind the streams' back.
                "shiguchi.bff.refresh-skew=0s",
                // The page and the gateway are on different origins here: the client sends Origin.
                "shiguchi.cors.enabled=true",
                "shiguchi.cors.allowed-origins=https://a.example.com",
                "shiguchi.cors.allow-credentials=true",
                "spring.graphql.cors.allowed-origins=https://a.example.com",
                "spring.graphql.cors.allow-credentials=true"));
        gateway = BffRefreshSupport.start(TestApp.class, properties);
        port = BffRefreshSupport.port(gateway);
        client = BffRefreshSupport.client(port);
        testClient = WebTestClient.bindToServer()
                .responseTimeout(Duration.ofSeconds(30))
                .baseUrl("http://localhost:" + port)
                .build();
        store = gateway.getBean(BffSessionStore.class);
        metrics = gateway.getBean(PushMetrics.class);
    }

    @AfterAll
    static void stop() throws IOException {
        if (gateway != null) gateway.close();
        BACKEND.shutdownNow();
        STUB.close();
        Files.deleteIfExists(PB_FILE);
    }

    @BeforeEach
    void resetIssuer() {
        STUB.reset();
        // The tests compare the connection gauges with what they read first. An earlier test's
        // socket or stream can still be closing on a slow machine and move the gauge after that
        // read, so start each test from none open.
        awaitTrue(() -> metrics.connections("graphql") == 0 && metrics.connections("grpc") == 0);
    }

    private static String signedIn() {
        return BffRefreshSupport.seed(store, STUB, Duration.ofMinutes(5));
    }

    private static void publish(String topic, String dataJson) {
        client.post()
                .uri("/push/publish")
                .headers(h -> h.setBearerAuth(STUB.accessToken("publisher")))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"topic\":\"" + topic + "\",\"data\":" + dataJson + "}")
                .retrieve()
                .toBodilessEntity()
                .block(Duration.ofSeconds(30));
    }

    private static void logout(String cookie) {
        testClient
                .post()
                .uri("/bff/logout")
                .cookie(BffRefreshSupport.COOKIE, cookie)
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .exchange()
                .expectStatus()
                .isOk();
    }

    // ---- the facts the binding rests on ----

    @Test
    @DisplayName(
            "GIVEN a session cookie WHEN subscribing to a push GraphQL field over the WebSocket SHOULD run as the cookie's user, not as anonymous")
    void given_cookie_when_graphql_subscribing_should_run_as_the_user() {
        String cookie = signedIn();
        int before = metrics.connections("graphql");
        try (GraphQlSocket socket = new GraphQlSocket(cookieHeaders(cookie))) {
            socket.subscribe("1", SUBSCRIBE_ALICE);
            awaitTrue(() -> metrics.connections("graphql") == before + 1);
            publish("user:alice", "{\"n\":3}");

            JsonNode next = socket.awaitType("next");
            assertThat(next.at("/payload/data/userEvents/payload/n").asInt()).isEqualTo(3);
            assertThat(socket.frames("error")).isEmpty();
        }
    }

    @Test
    @DisplayName(
            "GIVEN a gRPC-Web stream opened with the cookie WHEN the client goes away SHOULD release the push connection on the server")
    void given_grpc_web_stream_when_client_goes_away_should_cancel_the_upstream_call() {
        String cookie = signedIn();
        int before = metrics.connections("grpc");
        WebStream stream = openStream(cookieHeaders(cookie));
        awaitTrue(() -> metrics.connections("grpc") == before + 1);

        stream.close();

        awaitTrue(() -> metrics.connections("grpc") == before);
    }

    // ---- gRPC-Web server stream ----

    @Test
    @DisplayName(
            "GIVEN a gRPC-Web stream opened with the cookie WHEN the user logs out SHOULD finish it with UNAUTHENTICATED trailers and release the backend call")
    void given_cookie_grpc_web_stream_when_logging_out_should_end_unauthenticated() {
        String cookie = signedIn();
        int before = metrics.connections("grpc");
        WebStream stream = openStream(cookieHeaders(cookie));
        awaitTrue(() -> metrics.connections("grpc") == before + 1);

        logout(cookie);

        awaitTrue(() -> !stream.trailers.isEmpty());
        assertThat(stream.trailers.get(0)).contains("grpc-status:16").contains("grpc-message:session ended");
        awaitTrue(() -> stream.completed);
        awaitTrue(() -> metrics.connections("grpc") == before);
    }

    @Test
    @DisplayName(
            "GIVEN a gRPC-Web stream opened with the cookie WHEN the store loses the session without an event here SHOULD finish it with UNAUTHENTICATED trailers by the poll")
    void given_cookie_grpc_web_stream_when_store_loses_session_should_end_by_poll() {
        String cookie = signedIn();
        int before = metrics.connections("grpc");
        WebStream stream = openStream(cookieHeaders(cookie));
        awaitTrue(() -> metrics.connections("grpc") == before + 1);

        store.delete(BffSessionKeys.key(cookie)).block(Duration.ofSeconds(10));

        awaitTrue(() -> !stream.trailers.isEmpty());
        assertThat(stream.trailers.get(0)).contains("grpc-status:16").contains("grpc-message:session ended");
        awaitTrue(() -> metrics.connections("grpc") == before);
    }

    @Test
    @DisplayName(
            "GIVEN an access token of 2 seconds WHEN 4 seconds pass SHOULD keep the cookie gRPC-Web stream open and delivering")
    void given_short_access_token_when_it_expires_should_keep_cookie_grpc_web_stream_open() throws Exception {
        STUB.accessTokenLifetime(Duration.ofSeconds(2));
        String cookie = BffRefreshSupport.seed(store, STUB, Duration.ofSeconds(2));
        int before = metrics.connections("grpc");
        WebStream stream = openStream(cookieHeaders(cookie));
        awaitTrue(() -> metrics.connections("grpc") == before + 1);

        Thread.sleep(4000);

        assertThat(stream.trailers).isEmpty();
        publish("user:alice", "{\"n\":9}");
        awaitTrue(() -> !stream.messages.isEmpty());
        assertThat(stream.trailers).isEmpty();
        stream.close();
    }

    @Test
    @DisplayName(
            "GIVEN a bearer gRPC-Web stream WHEN the cookie sessions end and the poll runs SHOULD keep it open, bound to no session")
    void given_bearer_grpc_web_stream_when_sessions_end_should_stay_open() throws Exception {
        String cookie = signedIn();
        int before = metrics.connections("grpc");
        WebStream stream = openStream(h -> h.setBearerAuth(STUB.accessToken("alice")));
        awaitTrue(() -> metrics.connections("grpc") == before + 1);

        logout(cookie);
        Thread.sleep(7000);

        assertThat(stream.trailers).isEmpty();
        assertThat(stream.completed).isFalse();
        publish("user:alice", "{\"n\":5}");
        awaitTrue(() -> !stream.messages.isEmpty());
        stream.close();
    }

    @Test
    @DisplayName(
            "GIVEN a bearer gRPC-Web stream with a token of 3 seconds WHEN the token expires SHOULD finish it with UNAUTHENTICATED token expired trailers and release the backend call")
    void given_bearer_grpc_web_stream_when_token_expires_should_end_unauthenticated() {
        STUB.accessTokenLifetime(Duration.ofSeconds(3));
        String token = STUB.accessToken("alice");
        int before = metrics.connections("grpc");
        WebStream stream = openStream(h -> h.setBearerAuth(token));
        awaitTrue(() -> metrics.connections("grpc") == before + 1);

        awaitTrue(() -> !stream.trailers.isEmpty());
        assertThat(stream.trailers.get(0)).contains("grpc-status:16").contains("grpc-message:token expired");
        awaitTrue(() -> stream.completed);
        awaitTrue(() -> metrics.connections("grpc") == before);
    }

    // ---- GraphQL subscription ----

    @Test
    @DisplayName(
            "GIVEN a GraphQL subscription opened with the cookie WHEN the user logs out SHOULD end it with an UNAUTHORIZED session ended error and release the push connection")
    void given_cookie_graphql_subscription_when_logging_out_should_end_with_error() {
        String cookie = signedIn();
        int before = metrics.connections("graphql");
        try (GraphQlSocket socket = new GraphQlSocket(cookieHeaders(cookie))) {
            socket.subscribe("1", SUBSCRIBE_ALICE);
            awaitTrue(() -> metrics.connections("graphql") == before + 1);

            logout(cookie);

            JsonNode error = socket.awaitSessionEnded("1");
            assertThat(error.toString()).contains("session ended").contains("UNAUTHORIZED");
            awaitTrue(() -> metrics.connections("graphql") == before);
        }
    }

    @Test
    @DisplayName(
            "GIVEN a GraphQL subscription opened with the cookie WHEN the store loses the session without an event here SHOULD end it with the session ended error by the poll")
    void given_cookie_graphql_subscription_when_store_loses_session_should_end_by_poll() {
        String cookie = signedIn();
        int before = metrics.connections("graphql");
        try (GraphQlSocket socket = new GraphQlSocket(cookieHeaders(cookie))) {
            socket.subscribe("1", SUBSCRIBE_ALICE);
            awaitTrue(() -> metrics.connections("graphql") == before + 1);

            store.delete(BffSessionKeys.key(cookie)).block(Duration.ofSeconds(10));

            assertThat(socket.awaitSessionEnded("1").toString()).contains("session ended");
            awaitTrue(() -> metrics.connections("graphql") == before);
        }
    }

    @Test
    @DisplayName(
            "GIVEN a socket whose session ended WHEN it sends a new subscribe SHOULD refuse it with the session ended error")
    void given_graphql_socket_after_logout_when_subscribing_again_should_be_refused() {
        String cookie = signedIn();
        try (GraphQlSocket socket = new GraphQlSocket(cookieHeaders(cookie))) {
            socket.subscribe("1", SUBSCRIBE_ALICE);
            logout(cookie);
            socket.awaitSessionEnded("1");

            socket.subscribe("2", SUBSCRIBE_ALICE);

            assertThat(socket.awaitSessionEnded("2").toString()).contains("session ended");
            assertThat(socket.frames("next"))
                    .allMatch(f -> f.at("/payload/data").isMissingNode());
        }
    }

    @Test
    @DisplayName(
            "GIVEN a socket whose session ended WHEN it sends a query SHOULD refuse it with the session ended error")
    void given_graphql_socket_after_logout_when_querying_should_be_refused() {
        String cookie = signedIn();
        try (GraphQlSocket socket = new GraphQlSocket(cookieHeaders(cookie))) {
            socket.subscribe("1", SUBSCRIBE_ALICE);
            logout(cookie);
            socket.awaitSessionEnded("1");

            socket.subscribe("3", "query { __typename }");

            assertThat(socket.awaitSessionEnded("3").toString()).contains("session ended");
        }
    }

    @Test
    @DisplayName(
            "GIVEN an access token of 2 seconds WHEN 4 seconds pass SHOULD keep the cookie GraphQL subscription open and delivering")
    void given_short_access_token_when_it_expires_should_keep_cookie_graphql_subscription_open() throws Exception {
        STUB.accessTokenLifetime(Duration.ofSeconds(2));
        String cookie = BffRefreshSupport.seed(store, STUB, Duration.ofSeconds(2));
        int before = metrics.connections("graphql");
        try (GraphQlSocket socket = new GraphQlSocket(cookieHeaders(cookie))) {
            socket.subscribe("1", SUBSCRIBE_ALICE);
            awaitTrue(() -> metrics.connections("graphql") == before + 1);

            Thread.sleep(4000);

            assertThat(socket.frames("error")).isEmpty();
            publish("user:alice", "{\"n\":9}");
            assertThat(socket.awaitType("next")
                            .at("/payload/data/userEvents/payload/n")
                            .asInt())
                    .isEqualTo(9);
        }
    }

    @Test
    @DisplayName(
            "GIVEN a bearer GraphQL subscription WHEN the cookie sessions end and the poll runs SHOULD keep it open, bound to no session")
    void given_bearer_graphql_subscription_when_sessions_end_should_stay_open() throws Exception {
        String cookie = signedIn();
        int before = metrics.connections("graphql");
        try (GraphQlSocket socket = new GraphQlSocket(h -> h.setBearerAuth(STUB.accessToken("alice")))) {
            socket.subscribe("1", SUBSCRIBE_ALICE);
            awaitTrue(() -> metrics.connections("graphql") == before + 1);

            logout(cookie);
            Thread.sleep(7000);

            assertThat(socket.frames("error")).isEmpty();
            publish("user:alice", "{\"n\":5}");
            assertThat(socket.awaitType("next")
                            .at("/payload/data/userEvents/payload/n")
                            .asInt())
                    .isEqualTo(5);
        }
    }

    @Test
    @DisplayName(
            "GIVEN a bearer GraphQL subscription with a token of 3 seconds WHEN the token expires SHOULD end it with an UNAUTHORIZED token expired error and release the push connection")
    void given_bearer_graphql_subscription_when_token_expires_should_end_with_error() {
        STUB.accessTokenLifetime(Duration.ofSeconds(3));
        String token = STUB.accessToken("alice");
        int before = metrics.connections("graphql");
        try (GraphQlSocket socket = new GraphQlSocket(h -> h.setBearerAuth(token))) {
            socket.subscribe("1", SUBSCRIBE_ALICE);
            awaitTrue(() -> metrics.connections("graphql") == before + 1);

            JsonNode error = socket.awaitEnded("1", "token expired");

            assertThat(error.toString()).contains("UNAUTHORIZED").contains("unauthenticated");
            awaitTrue(() -> metrics.connections("graphql") == before);
        }
    }

    @Test
    @DisplayName(
            "GIVEN a bearer socket whose token expired WHEN it sends a new subscribe or query SHOULD refuse it with the token expired error")
    void given_bearer_graphql_socket_after_token_expiry_when_sending_again_should_be_refused() {
        STUB.accessTokenLifetime(Duration.ofSeconds(3));
        String token = STUB.accessToken("alice");
        try (GraphQlSocket socket = new GraphQlSocket(h -> h.setBearerAuth(token))) {
            socket.subscribe("1", SUBSCRIBE_ALICE);
            socket.awaitEnded("1", "token expired");

            socket.subscribe("2", SUBSCRIBE_ALICE);
            socket.subscribe("3", "query { __typename }");

            socket.awaitEnded("2", "token expired");
            socket.awaitEnded("3", "token expired");
            assertThat(socket.frames("next"))
                    .allMatch(f -> f.at("/payload/data").isMissingNode());
        }
    }

    // ---- helpers ----

    private static Consumer<HttpHeaders> cookieHeaders(String cookie) {
        return h -> {
            h.add(HttpHeaders.COOKIE, BffRefreshSupport.COOKIE + "=" + cookie);
            h.add(HttpHeaders.ORIGIN, ORIGIN);
        };
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

    /** A gRPC-Web {@code PushService/Subscribe} on {@code user:alice}, read frame by frame. */
    private static WebStream openStream(Consumer<HttpHeaders> headers) {
        byte[] message =
                SubscribeRequest.newBuilder().addTopics("user:alice").build().toByteArray();
        byte[] body = ByteBuffer.allocate(5 + message.length)
                .put((byte) 0)
                .putInt(message.length)
                .put(message)
                .array();
        WebStream stream = new WebStream();
        stream.subscription = client.post()
                .uri("/shiguchi.cursus.v1.PushService/Subscribe")
                .headers(headers)
                .contentType(MediaType.parseMediaType("application/grpc-web+proto"))
                .bodyValue(body)
                .exchangeToFlux(r -> r.bodyToFlux(byte[].class))
                .subscribe(stream::accept, e -> stream.failure = e, () -> stream.completed = true);
        return stream;
    }

    /** A gRPC-Web server stream read as the bytes arrive: data frames kept, trailers decoded. */
    private static final class WebStream {
        final List<byte[]> messages = new CopyOnWriteArrayList<>();
        final List<String> trailers = new CopyOnWriteArrayList<>();
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        volatile Throwable failure;
        volatile boolean completed;
        Disposable subscription;

        synchronized void accept(byte[] chunk) {
            pending.writeBytes(chunk);
            byte[] all = pending.toByteArray();
            ByteBuffer in = ByteBuffer.wrap(all);
            while (in.remaining() >= 5) {
                int start = in.position();
                byte flag = in.get();
                int length = in.getInt();
                if (in.remaining() < length) {
                    in.position(start);
                    break;
                }
                byte[] payload = new byte[length];
                in.get(payload);
                if ((flag & 0x80) == 0) {
                    messages.add(payload);
                } else {
                    trailers.add(URLDecoder.decode(
                            new String(payload, StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8));
                }
            }
            pending.reset();
            pending.write(all, in.position(), all.length - in.position());
        }

        void close() {
            subscription.dispose();
        }
    }

    /** One graphql-transport-ws socket: connection_init, then the operations sent after the ack. */
    private static final class GraphQlSocket implements AutoCloseable {
        private final List<JsonNode> received = new CopyOnWriteArrayList<>();
        private final Sinks.Many<String> outbound = Sinks.many().unicast().onBackpressureBuffer();
        private final Disposable connection;

        GraphQlSocket(Consumer<HttpHeaders> headers) {
            outbound.tryEmitNext("{\"type\":\"connection_init\"}");
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
                            .map(BffStreamBindingEndToEndTest::parse)
                            .doOnNext(received::add)
                            .then();
                    return Mono.when(send, receive);
                }
            };
            HttpHeaders h = new HttpHeaders();
            headers.accept(h);
            connection = new ReactorNettyWebSocketClient()
                    .execute(URI.create("ws://localhost:" + port + "/graphql/ws"), h, handler)
                    .subscribe(v -> {}, e -> {});
        }

        void subscribe(String id, String query) {
            awaitType("connection_ack");
            try {
                outbound.tryEmitNext(JSON.writeValueAsString(
                        Map.of("id", id, "type", "subscribe", "payload", Map.of("query", query))));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        List<JsonNode> frames(String type) {
            return received.stream()
                    .filter(f -> type.equals(f.path("type").asText()))
                    .toList();
        }

        JsonNode awaitType(String type) {
            awaitTrue(() -> !frames(type).isEmpty());
            return frames(type).get(0);
        }

        /** The frame of operation {@code id} that carries the session ended error (an error frame, or a next with errors). */
        JsonNode awaitSessionEnded(String id) {
            return awaitEnded(id, "session ended");
        }

        /** The frame of operation {@code id} that carries {@code reason} as its error. */
        JsonNode awaitEnded(String id, String reason) {
            try {
                awaitTrue(() -> received.stream().anyMatch(f -> endedFrame(f, id, reason)));
            } catch (AssertionError e) {
                throw new AssertionError("no " + reason + " frame for " + id + " in " + received, e);
            }
            return received.stream()
                    .filter(f -> endedFrame(f, id, reason))
                    .findFirst()
                    .orElseThrow();
        }

        private static boolean endedFrame(JsonNode frame, String id, String reason) {
            return id.equals(frame.path("id").asText()) && frame.toString().contains(reason);
        }

        @Override
        public void close() {
            outbound.tryEmitComplete();
            connection.dispose();
        }
    }

    private static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (IOException e) {
            throw new AssertionError(json, e);
        }
    }
}
