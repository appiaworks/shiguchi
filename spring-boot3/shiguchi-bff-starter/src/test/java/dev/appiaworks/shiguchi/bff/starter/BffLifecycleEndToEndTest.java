package dev.appiaworks.shiguchi.bff.starter;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.bff.session.BffSessionKeys;
import dev.appiaworks.shiguchi.bff.session.BffSessionStore;
import dev.appiaworks.shiguchi.bff.testing.StubIssuer;
import io.grpc.Server;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Session lifecycle on the Boot 3 gateway: the issuer ends sessions with a back-channel logout token
 * (by {@code sid}, or by {@code sub} for every session of the user), an operator ends them through the
 * {@code gateway-bff-sessions} endpoint, and an idle session ends by itself. In each case the
 * session is gone from the store and the push WebSocket bound to it is closed with 4440. Sessions are
 * written straight to the store (as in the push test), tokens are signed by the stub issuer, which stands
 * for the issuer's sender: the issuer it was built against had none, so a real end-to-end is still owed. Mirrors
 * {@code spring-boot4/shiguchi-bff-starter}.
 */
@DisplayName("BFF session lifecycle end to end (Boot 3)")
class BffLifecycleEndToEndTest {

    private static final String ORIGIN = "https://a.example.com";
    private static final String BCL_EVENT = "http://schemas.openid.net/event/backchannel-logout";
    private static final StubIssuer STUB = StubIssuer.start();
    private static final Server BACKEND = BffTestBackend.start();
    private static final Path PB_FILE = BffTestBackend.writeDescriptorSet();

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {

        /** The bearer path (operators): tokens the stub issuer signed. */
        @Bean
        ReactiveJwtDecoder testJwtDecoder() {
            return NimbusReactiveJwtDecoder.withJwkSetUri(STUB.issuer() + "/jwks")
                    .build();
        }
    }

    private static ConfigurableApplicationContext gateway;
    private static WebTestClient client;
    private static BffSessionStore store;
    private static int port;

    private static List<String> gatewayProperties(String... extra) {
        List<String> properties = new ArrayList<>(BffRefreshSupport.properties(STUB, BACKEND, PB_FILE));
        properties.addAll(List.of(
                "shiguchi.cursus.enabled=true",
                "shiguchi.cursus.namespaces.user.auth=PRINCIPAL_MATCH",
                "shiguchi.cursus.namespaces.user.publish-auth=NONE",
                "shiguchi.cursus.session-check-interval=5s",
                "shiguchi.cursus.connection.keepalive=1s",
                "shiguchi.cursus.connection.max-lifetime=0",
                // The token of a seeded session is never renewed behind the sockets' back.
                "shiguchi.bff.refresh-skew=0s",
                "management.endpoints.web.exposure.include=gateway-bff-sessions",
                "shiguchi.bff.admin.role=ops"));
        properties.addAll(List.of(extra));
        return properties;
    }

    @BeforeAll
    static void start() {
        gateway = BffRefreshSupport.start(TestApp.class, gatewayProperties());
        port = BffRefreshSupport.port(gateway);
        client = clientFor(port);
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

    private static WebTestClient clientFor(int onPort) {
        return WebTestClient.bindToServer()
                .responseTimeout(Duration.ofSeconds(30))
                .baseUrl("http://localhost:" + onPort)
                .build();
    }

    private static String signedIn(String subject, String sid) {
        return BffRefreshSupport.seed(store, STUB, Duration.ofMinutes(5), subject, sid);
    }

    private static boolean alive(String cookie) {
        return store.find(BffSessionKeys.key(cookie)).blockOptional().isPresent();
    }

    private static HttpHeaders cookieHeaders(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, BffRefreshSupport.COOKIE + "=" + cookie);
        headers.add(HttpHeaders.ORIGIN, ORIGIN);
        return headers;
    }

    /** A logout token as the issuer's sender would sign it; {@code sid} may be null. */
    private static String logoutToken(String subject, String sid) {
        Instant now = Instant.now();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", STUB.issuer());
        claims.put("aud", STUB.clientId());
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.plusSeconds(120).getEpochSecond());
        claims.put("jti", UUID.randomUUID().toString());
        if (subject != null) claims.put("sub", subject);
        if (sid != null) claims.put("sid", sid);
        claims.put("events", Map.of(BCL_EVENT, Map.of()));
        return STUB.signAs(claims, "logout+jwt");
    }

    private static WebTestClient.ResponseSpec backchannel(String token, String origin) {
        return client.post()
                .uri("/bff/backchannel-logout/idp")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .headers(h -> {
                    if (origin != null) h.add(HttpHeaders.ORIGIN, origin);
                })
                .body(BodyInserters.fromFormData("logout_token", token))
                .exchange();
    }

    private static String operatorToken() {
        STUB.accessClaims(Map.of("scope", "ops"));
        String token = STUB.accessToken("operator");
        STUB.accessClaims(Map.of());
        return token;
    }

    // ---- back-channel logout ----------------------------------------------------------------------

    @Test
    @DisplayName(
            "GIVEN two sessions of alice and one of bob WHEN a sub logout token for alice arrives SHOULD end both of hers, close her WebSocket with 4440 and leave bob's session and socket")
    void given_sub_logout_token_should_end_all_sessions_of_the_user() {
        String alice1 = signedIn("alice", "sid-a1");
        String alice2 = signedIn("alice", "sid-a2");
        String bob = signedIn("bob", "sid-b1");
        try (Socket aliceSocket = new Socket(cookieHeaders(alice1));
                Socket bobSocket = new Socket(cookieHeaders(bob))) {
            aliceSocket.send("{\"type\":\"connect\"}");
            aliceSocket.awaitFrame("\"type\":\"connected\"");
            bobSocket.send("{\"type\":\"connect\"}");
            bobSocket.awaitFrame("\"type\":\"connected\"");

            // Not cookie-authenticated, so the origin check does not apply: a foreign Origin changes nothing.
            backchannel(logoutToken("alice", null), "https://evil.example.com")
                    .expectStatus()
                    .isOk()
                    .expectHeader()
                    .valueEquals(HttpHeaders.CACHE_CONTROL, "no-store");

            assertThat(aliceSocket.awaitClose().getCode()).isEqualTo(4440);
            assertThat(alive(alice1)).isFalse();
            assertThat(alive(alice2)).isFalse();
            assertThat(alive(bob)).isTrue();
            assertThat(bobSocket.closeStatus.get()).isNull();
        }
    }

    @Test
    @DisplayName(
            "GIVEN two sessions of one user WHEN a sid logout token names one SHOULD end that session only and keep answering as signed in for the other")
    void given_sid_logout_token_should_end_only_that_session() {
        String first = signedIn("carol", "sid-c1");
        String second = signedIn("carol", "sid-c2");

        backchannel(logoutToken("carol", "sid-c1"), null).expectStatus().isOk();

        assertThat(alive(first)).isFalse();
        assertThat(alive(second)).isTrue();
        client.get()
                .uri("/bff/session")
                .cookie(BffRefreshSupport.COOKIE, second)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.authenticated")
                .isEqualTo(true);
        client.get()
                .uri("/bff/session")
                .cookie(BffRefreshSupport.COOKIE, first)
                .exchange()
                .expectBody()
                .jsonPath("$.authenticated")
                .isEqualTo(false);
    }

    @Test
    @DisplayName(
            "GIVEN a logout token WHEN it is posted twice SHOULD end the sessions once and refuse the replay with 400")
    void given_replayed_logout_token_should_be_refused() {
        String cookie = signedIn("dave", "sid-d1");
        String token = logoutToken("dave", "sid-d1");

        backchannel(token, null).expectStatus().isOk();
        signedIn("dave", "sid-d1");
        backchannel(token, null)
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.error")
                .isEqualTo("invalid_request");

        assertThat(alive(cookie)).isFalse();
    }

    @Test
    @DisplayName("GIVEN a logout token signed by someone else WHEN posted SHOULD answer 400 and end nothing")
    void given_forged_logout_token_should_end_nothing() {
        String cookie = signedIn("erin", "sid-e1");
        Instant now = Instant.now();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", STUB.issuer());
        claims.put("aud", STUB.clientId());
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.plusSeconds(120).getEpochSecond());
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("sub", "erin");
        claims.put("events", Map.of(BCL_EVENT, Map.of()));

        backchannel(STUB.signForeign(claims, "logout+jwt"), null).expectStatus().isBadRequest();
        backchannel(STUB.unsigned(claims), null).expectStatus().isBadRequest();

        assertThat(alive(cookie)).isTrue();
    }

    // ---- operator revoke --------------------------------------------------------------------------

    @Test
    @DisplayName(
            "GIVEN a signed-in browser user WHEN calling actuator endpoints with their own cookie and a matching Origin SHOULD answer 403 and change nothing")
    void given_cookie_user_when_calling_actuator_should_be_refused() {
        String victim = signedIn("ivan", "sid-i1");
        String attacker = signedIn("judy", "sid-j1");

        client.delete()
                .uri("/actuator/gateway-bff-sessions/idp/ivan")
                .cookie(BffRefreshSupport.COOKIE, attacker)
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.error")
                .isEqualTo("cookie sessions cannot call actuator endpoints");
        client.get()
                .uri("/actuator/gateway-bff-sessions/idp/ivan")
                .cookie(BffRefreshSupport.COOKIE, attacker)
                .exchange()
                .expectStatus()
                .isForbidden();
        client.post()
                .uri("/actuator/gateway-traffic")
                .cookie(BffRefreshSupport.COOKIE, attacker)
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.error")
                .isEqualTo("cookie sessions cannot call actuator endpoints");

        assertThat(alive(victim)).isTrue();
        assertThat(alive(attacker)).isTrue();
    }

    @Test
    @DisplayName(
            "GIVEN the admin endpoint WHEN called without a token, with a token lacking the role, or with the session cookie of an end user SHOULD refuse and end nothing")
    void given_admin_endpoint_when_caller_is_not_an_operator_should_refuse() {
        String cookie = signedIn("frank", "sid-f1");

        client.delete()
                .uri("/actuator/gateway-bff-sessions/idp/frank")
                .exchange()
                .expectStatus()
                .isUnauthorized();
        client.get()
                .uri("/actuator/gateway-bff-sessions/idp/frank")
                .exchange()
                .expectStatus()
                .isUnauthorized();
        client.delete()
                .uri("/actuator/gateway-bff-sessions/idp/frank")
                .headers(h -> h.setBearerAuth(STUB.accessToken("someone")))
                .exchange()
                .expectStatus()
                .isForbidden();
        client.delete()
                .uri("/actuator/gateway-bff-sessions/idp/frank")
                .cookie(BffRefreshSupport.COOKIE, cookie)
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .exchange()
                .expectStatus()
                .isForbidden();

        assertThat(alive(cookie)).isTrue();
    }

    @Test
    @DisplayName(
            "GIVEN an operator with the role WHEN reading and then deleting a user's sessions SHOULD show the count without tokens, end them and close the user's WebSocket with 4440")
    void given_operator_when_revoking_a_user_should_end_sessions_and_close_sockets() {
        String cookie1 = signedIn("gina", "sid-g1");
        signedIn("gina", "sid-g2");
        String operator = operatorToken();
        try (Socket socket = new Socket(cookieHeaders(cookie1))) {
            socket.send("{\"type\":\"connect\"}");
            socket.awaitFrame("\"type\":\"connected\"");

            client.get()
                    .uri("/actuator/gateway-bff-sessions/idp/gina")
                    .headers(h -> h.setBearerAuth(operator))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.count")
                    .isEqualTo(2)
                    .jsonPath("$.sessions[0].accessToken")
                    .doesNotExist()
                    .jsonPath("$.sessions[0].created")
                    .exists();
            client.delete()
                    .uri("/actuator/gateway-bff-sessions/idp/gina")
                    .headers(h -> h.setBearerAuth(operator))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.revoked")
                    .isEqualTo(2);

            assertThat(socket.awaitClose().getCode()).isEqualTo(4440);
            assertThat(alive(cookie1)).isFalse();
        }
        client.delete()
                .uri("/actuator/gateway-bff-sessions/nobody/gina")
                .headers(h -> h.setBearerAuth(operator))
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    // ---- issuer revocation ------------------------------------------------------------------------

    private static String refreshTokenOf(String cookie) {
        return store.find(BffSessionKeys.key(cookie)).block().refreshToken();
    }

    private static void awaitRevoked(String... refreshTokens) {
        awaitTrue(() -> STUB.revokedTokens().containsAll(List.of(refreshTokens)));
    }

    @Test
    @DisplayName(
            "GIVEN a signed-in user WHEN logging out SHOULD revoke the refresh token at the issuer so a later refresh grant is refused")
    void given_logout_should_revoke_the_refresh_token_at_the_issuer() {
        String cookie = signedIn("rita", "sid-r1");
        String refreshToken = refreshTokenOf(cookie);

        client.post()
                .uri("/bff/logout")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .cookie(BffRefreshSupport.COOKIE, cookie)
                .exchange()
                .expectStatus()
                .isOk();

        awaitRevoked(refreshToken);
        assertThat(alive(cookie)).isFalse();
        assertThat(STUB.lastRevocation().hint()).isEqualTo("refresh_token");
        WebTestClient.bindToServer()
                .baseUrl(STUB.issuer())
                .build()
                .post()
                .uri("/token")
                .headers(h -> h.setBasicAuth(STUB.clientId(), "secret"))
                .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("grant_type", "refresh_token").with("refresh_token", refreshToken))
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Test
    @DisplayName(
            "GIVEN two sessions of one user WHEN an operator revokes the subject SHOULD revoke both refresh tokens at the issuer")
    void given_operator_revoking_a_user_should_revoke_every_refresh_token_at_the_issuer() {
        String first = refreshTokenOf(signedIn("sam", "sid-s1"));
        String second = refreshTokenOf(signedIn("sam", "sid-s2"));
        String other = refreshTokenOf(signedIn("tina", "sid-t1"));

        client.delete()
                .uri("/actuator/gateway-bff-sessions/idp/sam")
                .headers(h -> h.setBearerAuth(operatorToken()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.revoked")
                .isEqualTo(2);

        awaitRevoked(first, second);
        assertThat(STUB.revokedTokens()).doesNotContain(other);
    }

    @Test
    @DisplayName(
            "GIVEN a back-channel logout token WHEN the issuer ends a session SHOULD not call the issuer's revocation endpoint")
    void given_backchannel_logout_should_not_call_the_revocation_endpoint() throws InterruptedException {
        String cookie = signedIn("uma", "sid-u1");

        backchannel(logoutToken("uma", "sid-u1"), null).expectStatus().isOk();

        assertThat(alive(cookie)).isFalse();
        Thread.sleep(500);
        assertThat(STUB.revocationCalls()).isZero();
    }

    // ---- idle timeout -----------------------------------------------------------------------------

    @Test
    @DisplayName(
            "GIVEN an idle timeout of 2 seconds WHEN a session is not used SHOULD close its WebSocket with 4440 and not keep it alive")
    void given_idle_timeout_when_session_unused_should_close_socket() {
        try (ConfigurableApplicationContext idle =
                BffRefreshSupport.start(TestApp.class, gatewayProperties("shiguchi.bff.session.idle-timeout=2s"))) {
            int idlePort = BffRefreshSupport.port(idle);
            BffSessionStore idleStore = idle.getBean(BffSessionStore.class);
            String cookie = BffRefreshSupport.seed(idleStore, STUB, Duration.ofMinutes(5), "hank", "sid-h1");
            try (Socket socket = new Socket(cookieHeaders(cookie), idlePort)) {
                socket.send("{\"type\":\"connect\"}");
                socket.awaitFrame("\"type\":\"connected\"");

                assertThat(socket.awaitClose().getCode()).isEqualTo(4440);
            }
            clientFor(idlePort)
                    .get()
                    .uri("/bff/session")
                    .cookie(BffRefreshSupport.COOKIE, cookie)
                    .exchange()
                    .expectBody()
                    .jsonPath("$.authenticated")
                    .isEqualTo(false);
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
        private final Disposable connection;

        Socket(HttpHeaders headers) {
            this(headers, port);
        }

        Socket(HttpHeaders headers, int onPort) {
            connection = new ReactorNettyWebSocketClient()
                    .execute(URI.create("ws://localhost:" + onPort + "/push/ws"), headers, session -> {
                        session.closeStatus().subscribe(closeStatus::set);
                        Mono<Void> send = session.send(outbound.asFlux().map(session::textMessage));
                        Mono<Void> receive = session.receive()
                                .map(WebSocketMessage::getPayloadAsText)
                                .doOnNext(frames::add)
                                .then();
                        return Mono.zip(send, receive).then();
                    })
                    .subscribe(v -> {}, e -> {});
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
