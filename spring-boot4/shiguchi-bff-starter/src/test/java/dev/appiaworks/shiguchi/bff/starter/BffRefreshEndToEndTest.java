package dev.appiaworks.shiguchi.bff.starter;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.bff.session.BffSession;
import dev.appiaworks.shiguchi.bff.session.BffSessionKeys;
import dev.appiaworks.shiguchi.bff.session.BffSessionStore;
import dev.appiaworks.shiguchi.bff.starter.BffRefreshSupport.Reply;
import dev.appiaworks.shiguchi.bff.testing.StubIssuer;
import io.grpc.Server;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Token refresh on the Boot 4 gateway against the stub issuer, which rotates refresh tokens and
 * revokes the whole family on any reuse: a call after the access token expired reaches the backend
 * with the new token, 50 simultaneous calls refresh once, an issuer that refuses ends the session,
 * and an issuer that is down answers 503 rather than signing anybody out. Mirrors
 * {@code spring-boot3/shiguchi-bff-starter}.
 */
@DisplayName("BFF token refresh end to end (Boot 4)")
class BffRefreshEndToEndTest {

    private static final StubIssuer STUB = StubIssuer.start();
    private static final Server BACKEND = BffTestBackend.start();
    private static final Path PB_FILE = BffTestBackend.writeDescriptorSet();

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

    @BeforeAll
    static void start() {
        gateway = BffRefreshSupport.start(TestApp.class, BffRefreshSupport.properties(STUB, BACKEND, PB_FILE));
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

    private static BffSession stored(String cookie) {
        return store.find(BffSessionKeys.key(cookie)).block(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("GIVEN an expired access token WHEN calling SHOULD reach the backend with the new token, once")
    void given_expired_token_when_calling_should_reach_backend_with_new_token() {
        String cookie = BffRefreshSupport.seed(store, STUB, Duration.ofSeconds(-5));
        BffSession before = stored(cookie);

        Reply first = BffRefreshSupport.echo(client, cookie).block(Duration.ofSeconds(30));
        Reply second = BffRefreshSupport.echo(client, cookie).block(Duration.ofSeconds(30));

        BffSession after = stored(cookie);
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.bearer()).isEqualTo(after.accessToken()).isNotEqualTo(before.accessToken());
        assertThat(second.bearer()).isEqualTo(after.accessToken());
        assertThat(after.refreshToken()).isNotEqualTo(before.refreshToken());
        assertThat(after.idToken()).isNotEqualTo(before.idToken());
        assertThat(after.version()).isEqualTo(before.version() + 1);
        assertThat(STUB.refreshCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("GIVEN a token about to expire WHEN calling SHOULD refresh before it does")
    void given_token_about_to_expire_when_calling_should_refresh_early() {
        String cookie = BffRefreshSupport.seed(store, STUB, Duration.ofSeconds(20));
        BffSession before = stored(cookie);

        Reply reply = BffRefreshSupport.echo(client, cookie).block(Duration.ofSeconds(30));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.bearer()).isNotEqualTo(before.accessToken());
        assertThat(STUB.refreshCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("GIVEN an issuer that returns no new ID token WHEN refreshing SHOULD keep the old one")
    void given_issuer_without_new_id_token_when_refreshing_should_keep_the_old_one() {
        STUB.refreshIssuesIdToken(false);
        String cookie = BffRefreshSupport.seed(store, STUB, Duration.ofSeconds(-5));

        assertThat(BffRefreshSupport.echo(client, cookie)
                        .block(Duration.ofSeconds(30))
                        .status())
                .isEqualTo(200);

        assertThat(stored(cookie).idToken()).isEqualTo("id-token-0");
    }

    @Test
    @DisplayName(
            "GIVEN a refreshed ID token with a forged signature WHEN refreshing SHOULD keep the old ID token and serve the request")
    void given_forged_refreshed_id_token_when_refreshing_should_keep_the_old_one() {
        STUB.refreshIdTokenForeign(true);
        String cookie = BffRefreshSupport.seed(store, STUB, Duration.ofSeconds(-5));

        Reply reply = BffRefreshSupport.echo(client, cookie).block(Duration.ofSeconds(30));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(stored(cookie).idToken()).isEqualTo("id-token-0");
        assertThat(stored(cookie).version()).isEqualTo(1);
    }

    @Test
    @DisplayName("GIVEN a refreshed ID token for another subject WHEN refreshing SHOULD answer 401 and end the session")
    void given_refreshed_id_token_for_another_subject_when_refreshing_should_end_the_session() {
        STUB.refreshIdTokenSubject("mallory");
        String cookie = BffRefreshSupport.seed(store, STUB, Duration.ofSeconds(-5));

        Reply reply = BffRefreshSupport.echo(client, cookie).block(Duration.ofSeconds(30));

        assertThat(reply.status()).isEqualTo(401);
        assertThat(stored(cookie)).isNull();
    }

    @Test
    @DisplayName(
            "GIVEN 50 simultaneous calls with an expired token WHEN refreshing SHOULD call the issuer once and answer all 200")
    void given_50_simultaneous_calls_when_refreshing_should_call_issuer_once() {
        STUB.refreshDelay(Duration.ofMillis(300));
        String cookie = BffRefreshSupport.seed(store, STUB, Duration.ofSeconds(-5));

        List<Reply> replies = BffRefreshSupport.burst(List.of(client), 50, cookie);

        assertThat(replies).hasSize(50).allSatisfy(r -> assertThat(r.status()).isEqualTo(200));
        assertThat(replies.stream().map(Reply::bearer).distinct())
                .containsExactly(stored(cookie).accessToken());
        assertThat(STUB.refreshCalls()).isEqualTo(1);
        assertThat(STUB.familyRevoked()).isFalse();
    }

    @Test
    @DisplayName(
            "GIVEN a revoked token family WHEN calling SHOULD answer 401 and the session endpoint SHOULD say signed out")
    void given_revoked_family_when_calling_should_answer_401_and_end_session() {
        String cookie = BffRefreshSupport.seed(store, STUB, Duration.ofSeconds(-5));
        STUB.revokeAllRefreshTokens();

        assertThat(BffRefreshSupport.echo(client, cookie)
                        .block(Duration.ofSeconds(30))
                        .status())
                .isEqualTo(401);

        assertThat(stored(cookie)).isNull();
        testClient
                .get()
                .uri("/bff/session")
                .cookie(BffRefreshSupport.COOKIE, cookie)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.authenticated")
                .isEqualTo(false);
    }

    @Test
    @DisplayName(
            "GIVEN an issuer that is down and an expired token WHEN calling SHOULD answer 503 with Retry-After and keep the session")
    void given_issuer_down_and_expired_token_when_calling_should_answer_503() {
        String cookie = BffRefreshSupport.seed(store, STUB, Duration.ofSeconds(-5));
        STUB.refreshStatus(503);

        Reply down = BffRefreshSupport.echo(client, cookie).block(Duration.ofSeconds(30));

        assertThat(down.status()).isEqualTo(503);
        assertThat(down.retryAfter()).isEqualTo(1);
        assertThat(stored(cookie)).isNotNull();

        STUB.refreshStatus(0);
        assertThat(BffRefreshSupport.echo(client, cookie)
                        .block(Duration.ofSeconds(30))
                        .status())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("GIVEN an issuer that is down and a token still valid WHEN calling SHOULD serve it")
    void given_issuer_down_and_valid_token_when_calling_should_serve_it() {
        String cookie = BffRefreshSupport.seed(store, STUB, Duration.ofSeconds(20));
        BffSession before = stored(cookie);
        STUB.refreshStatus(503);

        Reply reply = BffRefreshSupport.echo(client, cookie).block(Duration.ofSeconds(30));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.bearer()).isEqualTo(before.accessToken());
        assertThat(stored(cookie)).isEqualTo(before);
    }

    @Test
    @DisplayName("GIVEN a refreshed token for another principal WHEN calling SHOULD answer 401 and end the session")
    void given_refreshed_token_for_another_principal_when_calling_should_end_session() {
        STUB.refreshSubject("mallory");
        String cookie = BffRefreshSupport.seed(store, STUB, Duration.ofSeconds(-5));

        assertThat(BffRefreshSupport.echo(client, cookie)
                        .block(Duration.ofSeconds(30))
                        .status())
                .isEqualTo(401);

        assertThat(stored(cookie)).isNull();
    }
}
