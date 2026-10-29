package dev.appiaworks.shiguchi.bff.starter;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.bff.session.BffSessionKeys;
import dev.appiaworks.shiguchi.bff.session.BffSessionStore;
import dev.appiaworks.shiguchi.bff.testing.StubIssuer;
import io.grpc.Server;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;

/**
 * REST server streams (NDJSON and Server-Sent Events) opened with the BFF session cookie on the
 * Boot 3 gateway, over a real port: each runs as the cookie's user, outlives the 2-second access
 * token, and ends when its session ends (at once on logout here, within the check interval when the
 * store loses the session) with the stream's in-band error, UNAUTHENTICATED "session ended", after
 * the backend call was cancelled. A bearer stream is not bound. Mirrors
 * {@code spring-boot4/shiguchi-bff-starter}.
 */
@DisplayName("BFF session-bound REST streams end to end (Boot 3)")
class BffRestStreamBindingEndToEndTest {

    private static final String ORIGIN = "https://a.example.com";
    private static final String NDJSON = "application/x-ndjson";
    private static final String SSE = "text/event-stream";
    private static final StubIssuer STUB = StubIssuer.start();
    private static final Server BACKEND = BffTestBackend.start();
    private static final Path PB_FILE = BffTestBackend.writeDescriptorSet();

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

    @BeforeAll
    static void start() {
        List<String> properties = new java.util.ArrayList<>(BffRefreshSupport.properties(STUB, BACKEND, PB_FILE));
        properties.addAll(List.of(
                "shiguchi.security.session-check-interval=5s",
                // The token of a seeded session is never renewed behind the streams' back.
                "shiguchi.bff.refresh-skew=0s",
                "shiguchi.cors.enabled=true",
                "shiguchi.cors.allowed-origins=https://a.example.com",
                "shiguchi.cors.allow-credentials=true"));
        gateway = BffRefreshSupport.start(TestApp.class, properties);
        int port = BffRefreshSupport.port(gateway);
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

    // ---- logout ends the stream ----

    @Test
    @DisplayName(
            "GIVEN an NDJSON stream opened with the cookie WHEN the user logs out SHOULD end it with an UNAUTHENTICATED session ended error line and cancel the backend call")
    void given_cookie_ndjson_stream_when_logging_out_should_end_with_error_line() {
        assertLogoutEnds(NDJSON, "{\"error\":");
    }

    @Test
    @DisplayName(
            "GIVEN an SSE stream opened with the cookie WHEN the user logs out SHOULD end it with an error event saying session ended and cancel the backend call")
    void given_cookie_sse_stream_when_logging_out_should_end_with_error_event() {
        assertLogoutEnds(SSE, "event: error");
    }

    private void assertLogoutEnds(String format, String errorMarker) {
        String cookie = signedIn();
        Stream stream = open(format, cookieHeaders(cookie));
        awaitTrue(() -> stream.items(format) >= 1);

        logout(cookie);

        awaitTrue(() -> stream.completed.get());
        assertThat(stream.text())
                .contains(errorMarker)
                .contains("session ended")
                .contains("UNAUTHENTICATED");
        awaitTrue(() -> BffTestBackend.WATCH_CANCELLED.contains(stream.id));
    }

    // ---- the store loses the session ----

    @Test
    @DisplayName(
            "GIVEN an NDJSON stream opened with the cookie WHEN the store loses the session without an event here SHOULD end it by the poll and cancel the backend call")
    void given_cookie_ndjson_stream_when_store_loses_session_should_end_by_poll() {
        assertPollEnds(NDJSON, "{\"error\":");
    }

    @Test
    @DisplayName(
            "GIVEN an SSE stream opened with the cookie WHEN the store loses the session without an event here SHOULD end it by the poll and cancel the backend call")
    void given_cookie_sse_stream_when_store_loses_session_should_end_by_poll() {
        assertPollEnds(SSE, "event: error");
    }

    private void assertPollEnds(String format, String errorMarker) {
        String cookie = signedIn();
        Stream stream = open(format, cookieHeaders(cookie));
        awaitTrue(() -> stream.items(format) >= 1);

        store.delete(BffSessionKeys.key(cookie)).block(Duration.ofSeconds(10));

        awaitTrue(() -> stream.completed.get());
        assertThat(stream.text()).contains(errorMarker).contains("session ended");
        awaitTrue(() -> BffTestBackend.WATCH_CANCELLED.contains(stream.id));
    }

    // ---- the access token's expiry does not end it ----

    @Test
    @DisplayName(
            "GIVEN an access token of 2 seconds WHEN 4 seconds pass SHOULD keep the cookie NDJSON stream open and delivering")
    void given_short_access_token_when_it_expires_should_keep_cookie_ndjson_stream_open() throws Exception {
        assertOutlivesToken(NDJSON);
    }

    @Test
    @DisplayName(
            "GIVEN an access token of 2 seconds WHEN 4 seconds pass SHOULD keep the cookie SSE stream open and delivering")
    void given_short_access_token_when_it_expires_should_keep_cookie_sse_stream_open() throws Exception {
        assertOutlivesToken(SSE);
    }

    private void assertOutlivesToken(String format) throws Exception {
        STUB.accessTokenLifetime(Duration.ofSeconds(2));
        String cookie = BffRefreshSupport.seed(store, STUB, Duration.ofSeconds(2));
        Stream stream = open(format, cookieHeaders(cookie));
        awaitTrue(() -> stream.items(format) >= 1);

        Thread.sleep(4000);

        int seen = stream.items(format);
        assertThat(stream.completed.get()).isFalse();
        assertThat(stream.text()).doesNotContain("error");
        awaitTrue(() -> stream.items(format) > seen);
        stream.close();
    }

    // ---- a bearer stream is not bound ----

    @Test
    @DisplayName(
            "GIVEN a bearer NDJSON stream WHEN the cookie sessions end and the poll runs SHOULD keep it open, bound to no session")
    void given_bearer_ndjson_stream_when_sessions_end_should_stay_open() throws Exception {
        assertBearerStaysOpen(NDJSON);
    }

    @Test
    @DisplayName(
            "GIVEN a bearer SSE stream WHEN the cookie sessions end and the poll runs SHOULD keep it open, bound to no session")
    void given_bearer_sse_stream_when_sessions_end_should_stay_open() throws Exception {
        assertBearerStaysOpen(SSE);
    }

    private void assertBearerStaysOpen(String format) throws Exception {
        String cookie = signedIn();
        Stream stream = open(format, h -> h.setBearerAuth(STUB.accessToken("alice")));
        awaitTrue(() -> stream.items(format) >= 1);

        logout(cookie);
        Thread.sleep(7000);

        int seen = stream.items(format);
        assertThat(stream.completed.get()).isFalse();
        assertThat(stream.text()).doesNotContain("session ended");
        awaitTrue(() -> stream.items(format) > seen);
        stream.close();
    }

    // ---- a bearer stream ends at the token's expiry ----

    @Test
    @DisplayName(
            "GIVEN a bearer NDJSON stream with a token of 3 seconds WHEN the token expires SHOULD end it with an UNAUTHENTICATED token expired error line and cancel the backend call")
    void given_bearer_ndjson_stream_when_token_expires_should_end_with_error_line() {
        assertBearerEndsAtExpiry(NDJSON, "{\"error\":");
    }

    @Test
    @DisplayName(
            "GIVEN a bearer SSE stream with a token of 3 seconds WHEN the token expires SHOULD end it with an error event saying token expired and cancel the backend call")
    void given_bearer_sse_stream_when_token_expires_should_end_with_error_event() {
        assertBearerEndsAtExpiry(SSE, "event: error");
    }

    private void assertBearerEndsAtExpiry(String format, String errorMarker) {
        STUB.accessTokenLifetime(Duration.ofSeconds(3));
        String token = STUB.accessToken("alice");
        Stream stream = open(format, h -> h.setBearerAuth(token));
        awaitTrue(() -> stream.items(format) >= 1);

        awaitTrue(() -> stream.completed.get());
        assertThat(stream.text())
                .contains(errorMarker)
                .contains("token expired")
                .contains("UNAUTHENTICATED");
        awaitTrue(() -> BffTestBackend.WATCH_CANCELLED.contains(stream.id));
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

    private static Stream open(String accept, Consumer<HttpHeaders> headers) {
        Stream stream = new Stream();
        stream.subscription = client.get()
                .uri("/v1/watch/{text}", stream.id)
                .headers(headers)
                .header(HttpHeaders.ACCEPT, accept)
                // Raw buffers: a String reader would strip the SSE framing this test asserts on.
                .exchangeToFlux(r -> r.body(BodyExtractors.toDataBuffers()))
                .map(buffer -> {
                    String chunk = buffer.toString(StandardCharsets.UTF_8);
                    DataBufferUtils.release(buffer);
                    return chunk;
                })
                .subscribe(stream.text::append, e -> stream.completed.set(true), () -> stream.completed.set(true));
        return stream;
    }

    /** A REST stream read as it arrives, watching a text of its own. */
    private static final class Stream {
        final String id = java.util.UUID.randomUUID().toString();
        final StringBuffer text = new StringBuffer();
        final AtomicBoolean completed = new AtomicBoolean();
        Disposable subscription;

        String text() {
            return text.toString();
        }

        /** Messages received so far: NDJSON lines or SSE data events, errors not counted. */
        int items(String format) {
            String marker = SSE.equals(format) ? "data:" : "\"text\"";
            String all = text();
            int count = 0;
            for (int i = all.indexOf(marker); i >= 0; i = all.indexOf(marker, i + marker.length())) count++;
            return count;
        }

        void close() {
            subscription.dispose();
        }
    }
}
