package dev.appiaworks.shiguchi.bff.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.appiaworks.shiguchi.bff.session.BffSessionKeys;
import dev.appiaworks.shiguchi.bff.session.BffSessionStore;
import dev.appiaworks.shiguchi.bff.testing.StubIssuer;
import io.grpc.Server;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Push with the session cookie on the Boot 4 gateway: a browser with only the cookie (and the right
 * {@code Origin}) opens {@code /push/ws} and {@code /push/subscribe}, is the same principal as a
 * bearer caller of the same user, outlives the 2-second access token, and is closed with 4440 (WebSocket) or a
 * final {@code closed} event (SSE) when the session ends by logout on this instance or by the
 * store losing it, which stands for a logout on another instance and is found by the poll. Mirrors
 * {@code spring-boot3/shiguchi-bff-starter}.
 */
@DisplayName("BFF push with the session cookie end to end (Boot 4)")
class BffPushEndToEndTest {

    private static final String ORIGIN = "https://a.example.com";
    private static final StubIssuer STUB = StubIssuer.start();
    private static final Server BACKEND = BffTestBackend.start();
    private static final Path PB_FILE = BffTestBackend.writeDescriptorSet();
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE =
            new ParameterizedTypeReference<>() {};

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {

        /** The bearer path: tokens the stub issuer signed. */
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
    private static int port;

    @BeforeAll
    static void start() {
        List<String> properties = new ArrayList<>(BffRefreshSupport.properties(STUB, BACKEND, PB_FILE));
        properties.addAll(List.of(
                "shiguchi.cursus.enabled=true",
                "shiguchi.cursus.namespaces.user.auth=PRINCIPAL_MATCH",
                "shiguchi.cursus.namespaces.user.publish-auth=NONE",
                "shiguchi.cursus.session-check-interval=5s",
                "shiguchi.cursus.connection.keepalive=1s",
                "shiguchi.cursus.connection.max-lifetime=0",
                // The token of a seeded session is never renewed behind the sockets' back.
                "shiguchi.bff.refresh-skew=0s"));
        gateway = BffRefreshSupport.start(TestApp.class, properties);
        port = BffRefreshSupport.port(gateway);
        client = BffRefreshSupport.client(port);
        testClient = WebTestClient.bindToServer()
                .responseTimeout(Duration.ofSeconds(30))
                .baseUrl("http://localhost:" + port)
                .build();
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

    private static HttpHeaders cookieHeaders(String cookie, String origin) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, BffRefreshSupport.COOKIE + "=" + cookie);
        if (origin != null) headers.add(HttpHeaders.ORIGIN, origin);
        return headers;
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

    @Test
    @DisplayName(
            "GIVEN a session cookie and the right Origin WHEN opening a WebSocket without a token SHOULD subscribe to the topic named after the user and receive what is published")
    void given_cookie_when_opening_websocket_should_receive_events_for_the_principal() {
        String cookie = signedIn();
        try (Socket socket = new Socket(cookieHeaders(cookie, ORIGIN))) {
            socket.send("{\"type\":\"connect\"}");
            socket.send("{\"type\":\"sub\",\"topics\":[\"user:alice\",\"user:bob\"]}");
            socket.awaitFrame("\"type\":\"subscribed\"");
            socket.awaitFrame("\"code\":\"forbidden\"");

            publish("user:alice", "{\"n\":7}");

            assertThat(socket.awaitFrame("\"type\":\"event\""))
                    .contains("user:alice")
                    .contains("\"n\":7");
        }
    }

    @Test
    @DisplayName(
            "GIVEN a session cookie and a wrong Origin WHEN opening a WebSocket SHOULD be refused at the handshake")
    void given_cookie_and_wrong_origin_when_opening_websocket_should_be_refused() {
        String cookie = signedIn();

        assertThatThrownBy(() -> new Socket(cookieHeaders(cookie, "https://evil.example.com")).awaitOpen())
                .hasMessageContaining("403");
    }

    @Test
    @DisplayName(
            "GIVEN a WebSocket opened with the cookie WHEN the user logs out SHOULD close it with 4440 within the check interval")
    void given_cookie_websocket_when_logging_out_should_close_4440() {
        String cookie = signedIn();
        try (Socket socket = new Socket(cookieHeaders(cookie, ORIGIN))) {
            socket.send("{\"type\":\"connect\"}");
            socket.awaitFrame("\"type\":\"connected\"");

            logout(cookie);

            assertThat(socket.awaitClose().getCode()).isEqualTo(4440);
        }
    }

    @Test
    @DisplayName(
            "GIVEN a WebSocket opened with the cookie WHEN the store loses the session without an event here SHOULD close it with 4440 by the poll")
    void given_cookie_websocket_when_store_loses_session_should_close_4440_by_poll() {
        String cookie = signedIn();
        try (Socket socket = new Socket(cookieHeaders(cookie, ORIGIN))) {
            socket.send("{\"type\":\"connect\"}");
            socket.awaitFrame("\"type\":\"connected\"");

            store.delete(BffSessionKeys.key(cookie)).block(Duration.ofSeconds(10));

            assertThat(socket.awaitClose().getCode()).isEqualTo(4440);
        }
    }

    @Test
    @DisplayName(
            "GIVEN an access token of 2 seconds WHEN 4 seconds pass SHOULD keep the cookie WebSocket open and delivering")
    void given_short_access_token_when_it_expires_should_keep_cookie_websocket_open() throws Exception {
        STUB.accessTokenLifetime(Duration.ofSeconds(2));
        String cookie = BffRefreshSupport.seed(store, STUB, Duration.ofSeconds(2));
        try (Socket socket = new Socket(cookieHeaders(cookie, ORIGIN))) {
            socket.send("{\"type\":\"connect\"}");
            socket.send("{\"type\":\"sub\",\"topics\":[\"user:alice\"]}");
            socket.awaitFrame("\"type\":\"subscribed\"");

            Thread.sleep(4000);

            assertThat(socket.closeStatus.get()).isNull();
            publish("user:alice", "{\"n\":9}");
            assertThat(socket.awaitFrame("\"type\":\"event\"")).contains("\"n\":9");
        }
    }

    @Test
    @DisplayName("GIVEN a cookie WebSocket WHEN a connect frame carries a token SHOULD close with 1008")
    void given_cookie_websocket_when_connect_carries_token_should_close_1008() {
        String cookie = signedIn();
        try (Socket socket = new Socket(cookieHeaders(cookie, ORIGIN))) {
            socket.send("{\"type\":\"connect\",\"token\":\"" + STUB.accessToken("alice") + "\"}");

            assertThat(socket.awaitClose().getCode()).isEqualTo(1008);
        }
    }

    @Test
    @DisplayName(
            "GIVEN a bearer WebSocket whose token expires in 2 seconds WHEN it does SHOULD close with 4401 as before")
    void given_bearer_websocket_when_token_expires_should_close_4401() {
        STUB.accessTokenLifetime(Duration.ofSeconds(2));
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(STUB.accessToken("alice"));
        try (Socket socket = new Socket(headers)) {
            socket.send("{\"type\":\"connect\"}");

            assertThat(socket.awaitClose().getCode()).isEqualTo(4401);
        }
    }

    @Test
    @DisplayName("GIVEN an SSE stream opened with the cookie WHEN the user logs out SHOULD end it with a closed event")
    void given_cookie_sse_when_logging_out_should_end_with_closed_event() {
        String cookie = signedIn();
        List<ServerSentEvent<String>> events = new CopyOnWriteArrayList<>();
        AtomicReference<Boolean> completed = new AtomicReference<>(false);
        Disposable stream = client.get()
                .uri("/push/subscribe?topic=user:alice")
                .cookie(BffRefreshSupport.COOKIE, cookie)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(SSE)
                .doOnComplete(() -> completed.set(true))
                .subscribe(events::add);
        try {
            // The stream opens asynchronously and this namespace is at-most-once: publish until one arrives.
            awaitTrue(() -> {
                publish("user:alice", "{\"n\":1}");
                return events.stream().anyMatch(e -> "user:alice".equals(e.event()));
            });

            logout(cookie);

            awaitTrue(() -> completed.get());
            ServerSentEvent<String> last = events.get(events.size() - 1);
            assertThat(last.event()).isEqualTo("close");
            assertThat(last.data()).contains("session ended");
        } finally {
            stream.dispose();
        }
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

    /** A push WebSocket client: frames collected as text, the close status kept. */
    private static final class Socket implements AutoCloseable {
        private final List<String> frames = new CopyOnWriteArrayList<>();
        private final AtomicReference<CloseStatus> closeStatus = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final Sinks.Many<String> outbound = Sinks.many().unicast().onBackpressureBuffer();
        private final Sinks.One<Boolean> opened = Sinks.one();
        private final Disposable connection;

        Socket(HttpHeaders headers) {
            connection = new ReactorNettyWebSocketClient()
                    .execute(URI.create("ws://localhost:" + port + "/push/ws"), headers, session -> {
                        session.closeStatus().subscribe(closeStatus::set);
                        opened.tryEmitValue(true);
                        Mono<Void> send = session.send(outbound.asFlux().map(session::textMessage));
                        Mono<Void> receive = session.receive()
                                .map(WebSocketMessage::getPayloadAsText)
                                .doOnNext(frames::add)
                                .then();
                        return Mono.zip(send, receive).then();
                    })
                    .subscribe(v -> {}, e -> {
                        failure.set(e);
                        opened.tryEmitError(e);
                    });
        }

        void awaitOpen() {
            opened.asMono().block(Duration.ofSeconds(30));
        }

        void send(String frame) {
            outbound.tryEmitNext(frame);
        }

        String awaitFrame(String contains) {
            awaitTrue(() -> frames.stream().anyMatch(f -> f.contains(contains)));
            return frames.stream().filter(f -> f.contains(contains)).findFirst().orElseThrow();
        }

        CloseStatus awaitClose() {
            awaitTrue(() -> closeStatus.get() != null);
            return closeStatus.get();
        }

        @Override
        public void close() {
            outbound.tryEmitComplete();
            connection.dispose();
        }
    }
}
