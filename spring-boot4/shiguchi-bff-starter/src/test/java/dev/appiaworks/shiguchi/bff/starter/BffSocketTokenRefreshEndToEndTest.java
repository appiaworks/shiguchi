package dev.appiaworks.shiguchi.bff.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.appiaworks.shiguchi.bff.session.BffSessionKeys;
import dev.appiaworks.shiguchi.bff.session.BffSessionStore;
import dev.appiaworks.shiguchi.bff.testing.StubIssuer;
import io.grpc.Server;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * A GraphQL WebSocket opened with the BFF session cookie authenticates once, at the upgrade; every
 * later operation must still reach the backend with the session's current access token. With a
 * 2-second access token, an operation sent after it expired refreshes it (once, through the
 * session's refresh path) and sends the new one; one sent while it is fresh refreshes nothing; one
 * sent after the issuer refused the refresh token is refused as unauthenticated. A bearer socket is
 * unaffected. Mirrors {@code spring-boot3/shiguchi-bff-starter}.
 */
@DisplayName("BFF cookie GraphQL socket token refresh end to end (Boot 4)")
class BffSocketTokenRefreshEndToEndTest {

    private static final String ORIGIN = "https://a.example.com";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final StubIssuer STUB = StubIssuer.start();
    private static final Server BACKEND = BffTestBackend.start();
    private static final Path PB_FILE = BffTestBackend.writeDescriptorSet();
    private static final String ECHO = "query { echo(text: \"x\") { text } }";

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
    private static BffSessionStore store;
    private static int port;

    @BeforeAll
    static void start() {
        List<String> properties = new java.util.ArrayList<>(BffRefreshSupport.properties(STUB, BACKEND, PB_FILE));
        properties.removeIf(p -> p.startsWith("shiguchi.graphql.enabled="));
        properties.addAll(List.of(
                "shiguchi.graphql.enabled=true",
                "spring.graphql.websocket.path=/graphql/ws",
                "shiguchi.bff.refresh-skew=1s",
                "shiguchi.cors.enabled=true",
                "shiguchi.cors.allowed-origins=https://a.example.com",
                "shiguchi.cors.allow-credentials=true",
                "spring.graphql.cors.allowed-origins=https://a.example.com",
                "spring.graphql.cors.allow-credentials=true"));
        gateway = BffRefreshSupport.start(TestApp.class, properties);
        port = BffRefreshSupport.port(gateway);
        store = gateway.getBean(BffSessionStore.class);
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
        STUB.accessTokenLifetime(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName(
            "GIVEN a cookie socket older than the access token WHEN it sends a query SHOULD refresh once and send the backend the new token")
    void given_socket_older_than_access_token_when_querying_should_send_refreshed_token() throws Exception {
        String cookie = BffRefreshSupport.seed(store, STUB, Duration.ofSeconds(2));
        String handshakeToken = sessionToken(cookie);
        try (GraphQlSocket socket = new GraphQlSocket(cookieHeaders(cookie))) {
            socket.query("1", ECHO);
            assertThat(socket.awaitResult("1")).contains("Bearer " + handshakeToken);
            assertThat(STUB.refreshCalls()).isZero();

            Thread.sleep(3500);
            socket.query("2", ECHO);

            String text = socket.awaitResult("2");
            String current = sessionToken(cookie);
            assertThat(current).isNotEqualTo(handshakeToken);
            assertThat(text).contains("Bearer " + current).doesNotContain(handshakeToken);
            assertThat(text).endsWith("|alice");
            assertThat(STUB.refreshCalls()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("GIVEN a cookie socket with a fresh access token WHEN it sends queries SHOULD not refresh")
    void given_fresh_access_token_when_querying_should_not_refresh() {
        STUB.accessTokenLifetime(Duration.ofMinutes(5));
        String cookie = BffRefreshSupport.seed(store, STUB, Duration.ofMinutes(5));
        String token = sessionToken(cookie);
        try (GraphQlSocket socket = new GraphQlSocket(cookieHeaders(cookie))) {
            socket.query("1", ECHO);
            socket.query("2", ECHO);

            assertThat(socket.awaitResult("1")).contains("Bearer " + token);
            assertThat(socket.awaitResult("2")).contains("Bearer " + token);
            assertThat(STUB.refreshCalls()).isZero();
        }
    }

    @Test
    @DisplayName(
            "GIVEN a cookie socket whose refresh token was revoked WHEN it sends a query after the access token expired SHOULD refuse it as unauthenticated")
    void given_revoked_refresh_token_when_querying_after_expiry_should_be_refused() throws Exception {
        String cookie = BffRefreshSupport.seed(store, STUB, Duration.ofSeconds(2));
        try (GraphQlSocket socket = new GraphQlSocket(cookieHeaders(cookie))) {
            socket.query("1", ECHO);
            assertThat(socket.awaitResult("1")).contains("|alice");
            STUB.revokeAllRefreshTokens();

            Thread.sleep(3500);
            socket.query("2", ECHO);

            String frame = socket.awaitFrame("2");
            assertThat(frame).doesNotContain("Bearer").doesNotContain("|alice");
            assertThat(frame).contains("UNAUTHENTICATED").contains("session ended");
            assertThat(store.find(BffSessionKeys.key(cookie)).block(Duration.ofSeconds(10)))
                    .isNull();
        }
    }

    @Test
    @DisplayName("GIVEN a bearer socket WHEN it sends a query SHOULD send the backend its own header, never refreshed")
    void given_bearer_socket_when_querying_should_send_its_own_token() throws Exception {
        // A bearer socket is bound to its token's exp (a past-exp operation is refused), so the token outlives the
        // wait.
        STUB.accessTokenLifetime(Duration.ofMinutes(5));
        String bearer = STUB.accessToken("bob");
        try (GraphQlSocket socket = new GraphQlSocket(h -> h.setBearerAuth(bearer))) {
            Thread.sleep(3500);
            socket.query("1", ECHO);

            assertThat(socket.awaitResult("1")).contains("Bearer " + bearer).endsWith("|bob");
            assertThat(STUB.refreshCalls()).isZero();
        }
    }

    // ---- helpers ----

    private static String sessionToken(String cookie) {
        return store.find(BffSessionKeys.key(cookie))
                .block(Duration.ofSeconds(10))
                .accessToken();
    }

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
                            .map(BffSocketTokenRefreshEndToEndTest::parse)
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

        void query(String id, String query) {
            awaitTrue(() -> received.stream()
                    .anyMatch(f -> "connection_ack".equals(f.path("type").asText())));
            try {
                outbound.tryEmitNext(JSON.writeValueAsString(
                        Map.of("id", id, "type", "subscribe", "payload", Map.of("query", query))));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        /** Any frame answering operation {@code id}, as JSON text. */
        String awaitFrame(String id) {
            awaitTrue(
                    () -> received.stream().anyMatch(f -> id.equals(f.path("id").asText())));
            return received.stream()
                    .filter(f -> id.equals(f.path("id").asText()))
                    .findFirst()
                    .orElseThrow()
                    .toString();
        }

        /** The {@code echo.text} of operation {@code id}: {@code <authorization>|<x-principal>}. */
        String awaitResult(String id) {
            awaitTrue(() -> received.stream()
                    .anyMatch(f -> id.equals(f.path("id").asText())
                            && !f.at("/payload/data/echo/text").isMissingNode()));
            return received.stream()
                    .filter(f -> id.equals(f.path("id").asText()))
                    .map(f -> f.at("/payload/data/echo/text"))
                    .filter(n -> !n.isMissingNode())
                    .findFirst()
                    .orElseThrow()
                    .asText();
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
