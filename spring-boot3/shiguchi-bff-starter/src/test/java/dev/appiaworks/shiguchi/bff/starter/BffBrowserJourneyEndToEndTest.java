package dev.appiaworks.shiguchi.bff.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.appiaworks.shiguchi.bff.testing.StubIssuer;
import io.grpc.Server;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * The whole browser story on the Boot 3 gateway, in one scenario: sign in through the stub issuer
 * with redirects and cookies walked by hand (nothing seeded), then call the gateway the way a page
 * does ({@code Origin} and {@code Sec-Fetch-*} headers, the cookie jar honouring {@code Path}), see
 * a cross-site POST and a cross-site WebSocket refused, receive a push event over the cookie
 * socket, ride out a token refresh, and log out. There is no real browser in CI; this sends what
 * one sends. Mirrors {@code spring-boot4/shiguchi-bff-starter}.
 */
@DisplayName("BFF browser journey end to end (Boot 3)")
class BffBrowserJourneyEndToEndTest {

    private static final String PAGE = "https://a.example.com";
    private static final String EVIL = "https://evil.example.com";
    private static final String COOKIE = "__Host-gw-session";
    private static final StubIssuer STUB = StubIssuer.start();
    private static final Server BACKEND = BffTestBackend.start();
    private static final Path PB_FILE = BffTestBackend.writeDescriptorSet();
    private static final HttpClient ISSUER_BROWSER =
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {

        /** The bearer path (the publisher below): tokens the stub issuer signed. */
        @Bean
        ReactiveJwtDecoder testJwtDecoder() {
            return NimbusReactiveJwtDecoder.withJwkSetUri(STUB.issuer() + "/jwks")
                    .build();
        }
    }

    private static ConfigurableApplicationContext gateway;
    private static WebClient publisher;
    private static WebTestClient browser;
    private static int port;

    @BeforeAll
    static void start() {
        List<String> properties = new ArrayList<>(BffRefreshSupport.properties(STUB, BACKEND, PB_FILE));
        properties.addAll(List.of(
                // The real cookie: __Host- prefix, Secure, Path=/. The jar below honours it by hand.
                "shiguchi.bff.cookie.name=" + COOKIE,
                "shiguchi.bff.cookie.secure=true",
                "shiguchi.bff.refresh-skew=0s",
                "shiguchi.grpc-web.enabled=true",
                "shiguchi.cursus.enabled=true",
                "shiguchi.cursus.namespaces.user.auth=PRINCIPAL_MATCH",
                "shiguchi.cursus.namespaces.user.publish-auth=NONE",
                "shiguchi.cursus.session-check-interval=5s",
                "shiguchi.cursus.connection.keepalive=1s",
                "shiguchi.cursus.connection.max-lifetime=0"));
        gateway = BffRefreshSupport.start(TestApp.class, properties);
        port = BffRefreshSupport.port(gateway);
        publisher = BffRefreshSupport.client(port);
        browser = WebTestClient.bindToServer()
                .responseTimeout(Duration.ofSeconds(30))
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @AfterAll
    static void stop() throws IOException {
        if (gateway != null) gateway.close();
        BACKEND.shutdownNow();
        STUB.close();
        Files.deleteIfExists(PB_FILE);
    }

    // ---- the browser: a cookie jar and the headers a page sends ----

    /** What the browser keeps from Set-Cookie: by name, with the attributes that decide when it is sent back. */
    private static final Map<String, ResponseCookie> JAR = new LinkedHashMap<>();

    private static void store(EntityExchangeResult<?> result) {
        result.getResponseCookies().forEach((name, list) -> {
            ResponseCookie cookie = list.get(0);
            if (cookie.getValue().isEmpty() || cookie.getMaxAge().isZero()) JAR.remove(name);
            else JAR.put(name, cookie);
        });
    }

    /** The headers of one request, applied to whichever client sends it (no reliance on HttpHeaders being a Map). */
    private record Headers(List<String[]> pairs) {

        Headers add(String name, String value) {
            pairs.add(new String[] {name, value});
            return this;
        }

        HttpHeaders toHttpHeaders() {
            HttpHeaders headers = new HttpHeaders();
            apply(headers);
            return headers;
        }

        void apply(HttpHeaders headers) {
            pairs.forEach(p -> headers.add(p[0], p[1]));
        }
    }

    /** The cookies a request to {@code path} carries: those whose Path is a prefix of it. */
    private static Headers cookiesFor(String path, Headers headers) {
        List<String> cookies = new ArrayList<>();
        JAR.forEach((name, cookie) -> {
            String scope = cookie.getPath() == null ? "/" : cookie.getPath();
            if (path.startsWith(scope)) cookies.add(name + "=" + cookie.getValue());
        });
        if (!cookies.isEmpty()) headers.add(HttpHeaders.COOKIE, String.join("; ", cookies));
        return headers;
    }

    /** {@code fetch()} from the page: its own Origin, CORS mode, no destination document. */
    private static Headers fetch(String path, String origin, String site) {
        Headers headers = new Headers(new ArrayList<>());
        if (origin != null) headers.add(HttpHeaders.ORIGIN, origin);
        headers.add("Sec-Fetch-Site", site).add("Sec-Fetch-Mode", "cors").add("Sec-Fetch-Dest", "empty");
        return cookiesFor(path, headers);
    }

    private static Headers fetch(String path) {
        return fetch(path, null, "same-origin");
    }

    /** A top-level navigation: no Origin, document destination. */
    private static Headers navigate(String path, String site) {
        Headers headers = new Headers(new ArrayList<>());
        headers.add("Sec-Fetch-Site", site).add("Sec-Fetch-Mode", "navigate").add("Sec-Fetch-Dest", "document");
        return cookiesFor(path, headers);
    }

    private static EntityExchangeResult<byte[]> get(String uri, Headers headers) {
        EntityExchangeResult<byte[]> result = browser.get()
                .uri(uri)
                .headers(headers::apply)
                .exchange()
                .expectBody()
                .returnResult();
        store(result);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(String uri, Headers headers) {
        EntityExchangeResult<Map> result = browser.get()
                .uri(uri)
                .headers(headers::apply)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Map.class)
                .returnResult();
        store(result);
        return (Map<String, Object>) result.getResponseBody();
    }

    /** The token the backend saw for {@code GET /v1/echo/hi} ({@code "Bearer <jwt>|<principal>"}); null when not sent. */
    private static String backendSaw(Headers headers) {
        EntityExchangeResult<byte[]> result = get("/v1/echo/hi", headers);
        String body = new String(result.getResponseBody(), StandardCharsets.UTF_8);
        assertThat(result.getStatus().value()).as(body).isEqualTo(200);
        int start = body.indexOf("Bearer ");
        assertThat(start).as(body).isNotNegative();
        return body.substring(start + "Bearer ".length(), body.indexOf('|', start));
    }

    private static void publish(String topic, String dataJson) {
        publisher
                .post()
                .uri("/push/publish")
                .headers(h -> h.setBearerAuth(STUB.accessToken("publisher")))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"topic\":\"" + topic + "\",\"data\":" + dataJson + "}")
                .retrieve()
                .toBodilessEntity()
                .block(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName(
            "GIVEN a user signing in like a browser WHEN using the app, attacked from another site, refreshed and logged out SHOULD behave as the whole BFF story says")
    void given_browser_when_signing_in_using_app_and_logging_out_should_follow_the_whole_story() throws Exception {
        STUB.accessTokenLifetime(Duration.ofSeconds(2));

        // 1. Sign in: a top-level navigation to the login endpoint, the issuer, and back with the code.
        EntityExchangeResult<byte[]> login =
                get("/bff/login/idp?return_to=" + PAGE + "/app/page", navigate("/bff/login/idp", "cross-site"));
        assertThat(login.getStatus().value()).isEqualTo(302);
        assertThat(JAR).as("only the login cookie so far").hasSize(1);
        HttpResponse<String> issuer = ISSUER_BROWSER.send(
                HttpRequest.newBuilder(URI.create(login.getResponseHeaders().getFirst(HttpHeaders.LOCATION)))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(issuer.statusCode()).isEqualTo(302);
        URI callback = URI.create(issuer.headers().firstValue("Location").orElseThrow());
        EntityExchangeResult<byte[]> done = get(
                callback.getRawPath() + "?" + callback.getRawQuery(), navigate(callback.getRawPath(), "cross-site"));
        assertThat(done.getStatus().value()).isEqualTo(302);
        assertThat(done.getResponseHeaders().getFirst(HttpHeaders.LOCATION)).isEqualTo(PAGE + "/app/page");
        ResponseCookie session = JAR.get(COOKIE);
        assertThat(session).as("the session cookie").isNotNull();
        assertThat(session.isHttpOnly()).isTrue();
        assertThat(session.isSecure()).isTrue();
        assertThat(session.getPath()).isEqualTo("/");
        assertThat(session.getDomain()).isNull();
        assertThat(session.getSameSite()).isEqualToIgnoringCase("Lax");
        assertThat(JAR)
                .as("the login cookie is gone, the session cookie is the only one")
                .containsOnlyKeys(COOKIE);

        // 2. The page asks who is signed in.
        Map<String, Object> who = json("/bff/session", fetch("/bff/session"));
        assertThat(who).containsEntry("authenticated", true).containsEntry("subject", "alice");

        // 3. REST reaches the backend with the access token, not the cookie.
        String firstToken = backendSaw(fetch("/v1/echo/hi"));
        assertThat(firstToken).matches("[\\w-]+\\.[\\w-]+\\.[\\w-]+");

        // 4. gRPC-Web, a POST: needs the page's own Origin and reaches the backend with the same bearer.
        byte[] frames = browser.post()
                .uri("/lib.Echo/Get")
                .headers(fetch("/lib.Echo/Get", PAGE, "same-origin")::apply)
                .header(HttpHeaders.CONTENT_TYPE, "application/grpc-web+proto")
                .bodyValue(new byte[5])
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .returnResult()
                .getResponseBody();
        assertThat(new String(frames, StandardCharsets.ISO_8859_1)).contains("Bearer ");

        // 5. CSRF: another site posts with the cookie attached (SameSite=Lax would stop a real
        // browser; the gateway must not depend on it). The Origin does not match: 403.
        browser.post()
                .uri("/v1/echo/hi")
                .headers(fetch("/v1/echo/hi", EVIL, "cross-site")::apply)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus()
                .isEqualTo(403)
                .expectBody()
                .json("{\"error\":\"origin not allowed\"}");

        // 6. A cross-site page opening the push socket with the cookie: refused at the handshake.
        assertThatThrownBy(
                        () -> new Socket(fetch("/push/ws", EVIL, "cross-site").toHttpHeaders()).awaitOpen())
                .hasMessageContaining("403");

        // 7. The app's own socket: the cookie is the credential, no token anywhere, PRINCIPAL_MATCH topic.
        try (Socket socket = new Socket(fetch("/push/ws", PAGE, "same-origin").toHttpHeaders())) {
            socket.send("{\"type\":\"connect\"}");
            socket.send("{\"type\":\"sub\",\"topics\":[\"user:alice\"]}");
            socket.awaitFrame("\"type\":\"subscribed\"");
            publish("user:alice", "{\"n\":42}");
            assertThat(socket.awaitFrame("\"type\":\"event\""))
                    .contains("user:alice")
                    .contains("\"n\":42");

            // 8. Refresh: the 2-second access token expires, the next call carries a new one, the socket stays up.
            String before = backendSaw(fetch("/v1/echo/hi"));
            Thread.sleep(3000);
            String after = backendSaw(fetch("/v1/echo/hi"));
            assertThat(after).isNotEqualTo(before).isNotEqualTo(firstToken);
            assertThat(STUB.refreshCalls()).isGreaterThanOrEqualTo(1);
            assertThat(json("/bff/session", fetch("/bff/session"))).containsEntry("authenticated", true);
            assertThat(socket.closeStatus.get())
                    .as("the refresh did not close the socket")
                    .isNull();

            // 9. Logout: the page posts with its Origin; the cookie is cleared and the socket ends with 4440.
            String staleCookie = JAR.get(COOKIE).getValue();
            EntityExchangeResult<byte[]> logout = browser.post()
                    .uri("/bff/logout")
                    .headers(fetch("/bff/logout", PAGE, "same-origin")::apply)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .returnResult();
            store(logout);
            assertThat(JAR).as("the browser dropped the cleared cookie").doesNotContainKey(COOKIE);
            assertThat(socket.awaitClose().getCode()).isEqualTo(4440);

            // 10. Afterwards, with the jar and with the old cookie value replayed: signed out, and 401 on REST.
            assertThat(json("/bff/session", fetch("/bff/session"))).containsEntry("authenticated", false);
            Headers replay = fetch("/bff/session");
            replay.add(HttpHeaders.COOKIE, COOKIE + "=" + staleCookie);
            assertThat(json("/bff/session", replay)).containsEntry("authenticated", false);
            Headers replayRest = fetch("/v1/echo/hi");
            replayRest.add(HttpHeaders.COOKIE, COOKIE + "=" + staleCookie);
            assertThat(get("/v1/echo/hi", replayRest).getStatus().value()).isEqualTo(401);
            assertThat(get("/v1/echo/hi", fetch("/v1/echo/hi")).getStatus().value())
                    .isEqualTo(401);
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
                    .subscribe(v -> {}, opened::tryEmitError);
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
