package dev.appiaworks.shiguchi.bff.starter;

import static org.assertj.core.api.Assertions.assertThat;

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
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * With {@code shiguchi.security.surfaces.graphql.require-auth=true} the GraphQL WebSocket upgrade
 * is let through without a caller (a browser cannot set headers on it) and the socket is gated
 * instead: an operation on a socket that nothing authenticated is refused, a bearer token in
 * {@code connection_init} authenticates it, and a bad one closes it. Plain HTTP stays gated.
 */
@DisplayName("Gated GraphQL surface WebSocket end to end (Boot 4)")
class BffGraphQlGatedSocketEndToEndTest {

    private static final ObjectMapper JSON = JsonMapper.builder()
            .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
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
    private static int port;

    @BeforeAll
    static void start() {
        List<String> properties = new java.util.ArrayList<>(BffRefreshSupport.properties(STUB, BACKEND, PB_FILE));
        properties.removeIf(p -> p.startsWith("shiguchi.graphql.enabled="));
        properties.addAll(List.of(
                "shiguchi.graphql.enabled=true",
                "spring.graphql.websocket.path=/graphql/ws",
                "shiguchi.security.surfaces.graphql.require-auth=true"));
        gateway = BffRefreshSupport.start(TestApp.class, properties);
        port = BffRefreshSupport.port(gateway);
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
        STUB.accessTokenLifetime(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("GIVEN a gated surface WHEN a socket sends no credential SHOULD upgrade but refuse its operations")
    void given_gated_surface_when_anonymous_socket_should_refuse_operations() {
        try (GraphQlSocket socket = new GraphQlSocket(h -> {}, "")) {
            socket.query("1", ECHO);

            String frame = socket.awaitFrame("1");
            assertThat(frame).contains("authentication required").doesNotContain("|");
        }
    }

    @Test
    @DisplayName(
            "GIVEN a gated surface WHEN connection_init carries a valid bearer SHOULD run operations as that caller")
    void given_gated_surface_when_bearer_in_connection_init_should_run_as_caller() {
        String bearer = STUB.accessToken("bob");
        try (GraphQlSocket socket =
                new GraphQlSocket(h -> {}, ",\"payload\":{\"Authorization\":\"Bearer " + bearer + "\"}")) {
            socket.query("1", ECHO);

            assertThat(socket.awaitResult("1")).endsWith("|bob");
        }
    }

    @Test
    @DisplayName(
            "GIVEN a gated surface WHEN the handshake carries a valid bearer header SHOULD run operations as that caller")
    void given_gated_surface_when_bearer_on_handshake_should_run_as_caller() {
        String bearer = STUB.accessToken("carol");
        try (GraphQlSocket socket = new GraphQlSocket(h -> h.setBearerAuth(bearer), "")) {
            socket.query("1", ECHO);

            assertThat(socket.awaitResult("1")).endsWith("|carol");
        }
    }

    @Test
    @DisplayName("GIVEN a gated surface WHEN connection_init carries a bad bearer SHOULD close the socket")
    void given_gated_surface_when_bad_bearer_should_close_socket() {
        try (GraphQlSocket socket =
                new GraphQlSocket(h -> {}, ",\"payload\":{\"Authorization\":\"Bearer not-a-token\"}")) {
            awaitTrue(socket::closed);
            assertThat(socket.received)
                    .noneMatch(f -> "connection_ack".equals(f.path("type").asString()));
        }
    }

    @Test
    @DisplayName("GIVEN a gated surface WHEN an anonymous HTTP request posts a query SHOULD answer 401")
    void given_gated_surface_when_anonymous_http_should_answer_401() throws Exception {
        var response = java.net.http.HttpClient.newHttpClient()
                .send(
                        java.net.http.HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/graphql"))
                                .header("Content-Type", "application/json")
                                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                                        "{\"query\":\"{ __typename }\"}"))
                                .build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    // ---- helpers ----

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

        GraphQlSocket(Consumer<HttpHeaders> headers, String initPayload) {
            outbound.tryEmitNext("{\"type\":\"connection_init\"" + initPayload + "}");
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
                            .map(BffGraphQlGatedSocketEndToEndTest::parse)
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
                    .anyMatch(f -> "connection_ack".equals(f.path("type").asString())));
            try {
                outbound.tryEmitNext(JSON.writeValueAsString(
                        Map.of("id", id, "type", "subscribe", "payload", Map.of("query", query))));
            } catch (tools.jackson.core.JacksonException e) {
                throw new IllegalStateException(e);
            }
        }

        /** Any frame answering operation {@code id}, as JSON text. */
        String awaitFrame(String id) {
            awaitTrue(
                    () -> received.stream().anyMatch(f -> id.equals(f.path("id").asString())));
            return received.stream()
                    .filter(f -> id.equals(f.path("id").asString()))
                    .findFirst()
                    .orElseThrow()
                    .toString();
        }

        /** The {@code echo.text} of operation {@code id}: {@code <authorization>|<x-principal>}. */
        String awaitResult(String id) {
            awaitTrue(() -> received.stream()
                    .anyMatch(f -> id.equals(f.path("id").asString())
                            && !f.at("/payload/data/echo/text").isMissingNode()));
            return received.stream()
                    .filter(f -> id.equals(f.path("id").asString()))
                    .map(f -> f.at("/payload/data/echo/text"))
                    .filter(n -> !n.isMissingNode())
                    .findFirst()
                    .orElseThrow()
                    .asString();
        }

        boolean closed() {
            return connection.isDisposed();
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
        } catch (tools.jackson.core.JacksonException e) {
            throw new AssertionError(json, e);
        }
    }
}
