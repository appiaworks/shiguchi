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
import java.time.Instant;
import java.util.ArrayList;
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
import org.springframework.web.reactive.function.client.WebClient;

/**
 * RFC 8693 token exchange on the Boot 4 gateway against the stub issuer: a cookie caller reaches
 * the backend with the token exchanged for that service, not its own; the exchange is cached per
 * session and shared by simultaneous calls; a refusal answers 403 and an issuer that is down 503,
 * neither ever relaying the user's own token; a refresh of the session's token starts a new exchange.
 */
@DisplayName("BFF token exchange end to end (Boot 4)")
class BffTokenExchangeEndToEndTest {

    private static final String SERVICE = "lib.Echo";

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
    private static ConfigurableApplicationContext unmappedGateway;
    private static WebClient client;
    private static WebClient unmappedClient;
    private static BffSessionStore store;
    private static BffSessionStore unmappedStore;

    private static List<String> exchangeProperties(String... extra) {
        List<String> properties = BffRefreshSupport.properties(STUB, BACKEND, PB_FILE);
        properties.add("shiguchi.bff.providers.idp.upstream-token-mode=EXCHANGE");
        properties.addAll(List.of(extra));
        return properties;
    }

    @BeforeAll
    static void start() {
        gateway = BffRefreshSupport.start(
                TestApp.class,
                exchangeProperties(
                        "shiguchi.bff.providers.idp.exchange.targets[" + SERVICE + "].audience=orders-api",
                        "shiguchi.bff.providers.idp.exchange.targets[" + SERVICE + "].scope=orders.read orders.write"));
        client = BffRefreshSupport.client(BffRefreshSupport.port(gateway));
        store = gateway.getBean(BffSessionStore.class);

        // a target for some other service only, and no default: the echo service has no mapping
        unmappedGateway = BffRefreshSupport.start(
                TestApp.class,
                exchangeProperties("shiguchi.bff.providers.idp.exchange.targets[other.Service].audience=other-api"));
        unmappedClient = BffRefreshSupport.client(BffRefreshSupport.port(unmappedGateway));
        unmappedStore = unmappedGateway.getBean(BffSessionStore.class);
    }

    @AfterAll
    static void stop() throws IOException {
        if (gateway != null) gateway.close();
        if (unmappedGateway != null) unmappedGateway.close();
        BACKEND.shutdownNow();
        STUB.close();
        Files.deleteIfExists(PB_FILE);
    }

    @BeforeEach
    void resetIssuer() {
        STUB.reset();
    }

    private static BffSession stored(BffSessionStore sessions, String cookie) {
        return sessions.find(BffSessionKeys.key(cookie)).block(Duration.ofSeconds(10));
    }

    /** Rewrites the stored session's access token expiry, keeping everything else (and the version). */
    private static void expireAccessToken(BffSessionStore sessions, String cookie) {
        BffSession s = stored(sessions, cookie);
        sessions.save(
                        new BffSession(
                                s.key(),
                                s.subject(),
                                s.provider(),
                                s.application(),
                                s.sid(),
                                s.name(),
                                s.email(),
                                s.picture(),
                                s.accessToken(),
                                Instant.now().minusSeconds(5),
                                s.refreshToken(),
                                s.idToken(),
                                s.created(),
                                s.lastSeen(),
                                s.version(),
                                s.acr(),
                                new ArrayList<>(s.amr()),
                                s.authTime(),
                                s.claims(),
                                s.userAgent()),
                        Duration.ofHours(1))
                .block(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName(
            "GIVEN a cookie caller WHEN calling SHOULD reach the backend with the exchanged token for the mapped audience, not the session's")
    void given_cookie_caller_when_calling_should_reach_backend_with_exchanged_token() {
        String cookie = BffRefreshSupport.seed(store, STUB, Duration.ofMinutes(5));
        BffSession session = stored(store, cookie);

        Reply reply = BffRefreshSupport.echo(client, cookie).block(Duration.ofSeconds(30));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.bearer()).isNotBlank().isNotEqualTo(session.accessToken());
        assertThat(STUB.exchangeCalls()).isEqualTo(1);
        assertThat(STUB.lastExchangeRequestBody())
                .contains("audience=orders-api")
                .contains("subject_token=" + session.accessToken())
                .containsPattern("scope=orders\\.read(\\+|%20)orders\\.write");
    }

    @Test
    @DisplayName("GIVEN a second call within the cache lifetime WHEN calling SHOULD not call the issuer again")
    void given_second_call_within_ttl_when_calling_should_not_exchange_again() {
        String cookie = BffRefreshSupport.seed(store, STUB, Duration.ofMinutes(5));

        Reply first = BffRefreshSupport.echo(client, cookie).block(Duration.ofSeconds(30));
        Reply second = BffRefreshSupport.echo(client, cookie).block(Duration.ofSeconds(30));

        assertThat(first.status()).isEqualTo(200);
        assertThat(second.bearer()).isEqualTo(first.bearer());
        assertThat(STUB.exchangeCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("GIVEN 50 simultaneous calls WHEN exchanging SHOULD call the issuer once and answer all 200")
    void given_50_simultaneous_calls_when_exchanging_should_call_issuer_once() {
        String cookie = BffRefreshSupport.seed(store, STUB, Duration.ofMinutes(5));

        List<Reply> replies = BffRefreshSupport.burst(List.of(client), 50, cookie);

        assertThat(replies).hasSize(50).allSatisfy(r -> assertThat(r.status()).isEqualTo(200));
        assertThat(replies.stream().map(Reply::bearer).distinct()).hasSize(1);
        assertThat(STUB.exchangeCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("GIVEN an issuer that refuses the exchange WHEN calling SHOULD answer 403 and keep the session")
    void given_refused_exchange_when_calling_should_answer_403_and_keep_session() {
        String cookie = BffRefreshSupport.seed(store, STUB, Duration.ofMinutes(5));
        BffSession before = stored(store, cookie);
        STUB.exchangeStatus(400).exchangeError("invalid_target");

        Reply refused = BffRefreshSupport.echo(client, cookie).block(Duration.ofSeconds(30));
        STUB.exchangeError("access_denied");
        Reply denied = BffRefreshSupport.echo(client, cookie).block(Duration.ofSeconds(30));

        assertThat(refused.status()).isEqualTo(403);
        assertThat(denied.status()).isEqualTo(403);
        assertThat(refused.bearer()).isNull();
        assertThat(stored(store, cookie)).isEqualTo(before);
    }

    @Test
    @DisplayName("GIVEN a service with no target and no default WHEN calling SHOULD answer 403")
    void given_unmapped_service_when_calling_should_answer_403() {
        String cookie = BffRefreshSupport.seed(unmappedStore, STUB, Duration.ofMinutes(5));

        Reply reply = BffRefreshSupport.echo(unmappedClient, cookie).block(Duration.ofSeconds(30));

        assertThat(reply.status()).isEqualTo(403);
        assertThat(reply.bearer()).isNull();
        assertThat(STUB.exchangeCalls()).isZero();
        assertThat(stored(unmappedStore, cookie)).isNotNull();
    }

    @Test
    @DisplayName("GIVEN an issuer that is down WHEN calling SHOULD answer 503 and never relay the user's own token")
    void given_issuer_down_when_calling_should_answer_503_without_relaying_user_token() {
        String cookie = BffRefreshSupport.seed(store, STUB, Duration.ofMinutes(5));
        BffSession session = stored(store, cookie);
        STUB.exchangeStatus(503).exchangeError("temporarily_unavailable");

        Reply down = BffRefreshSupport.echo(client, cookie).block(Duration.ofSeconds(30));

        assertThat(down.status()).isEqualTo(503);
        assertThat(down.bearer()).isNull();
        assertThat(down.body()).doesNotContain(session.accessToken());
        assertThat(stored(store, cookie)).isNotNull();

        STUB.exchangeStatus(0);
        Reply recovered = BffRefreshSupport.echo(client, cookie).block(Duration.ofSeconds(30));
        assertThat(recovered.status()).isEqualTo(200);
        assertThat(recovered.bearer()).isNotEqualTo(session.accessToken());
    }

    @Test
    @DisplayName(
            "GIVEN a session whose access token was refreshed WHEN calling SHOULD exchange again with the new subject token")
    void given_refreshed_session_when_calling_should_exchange_again() {
        String cookie = BffRefreshSupport.seed(store, STUB, Duration.ofMinutes(5));
        BffSession before = stored(store, cookie);
        Reply first = BffRefreshSupport.echo(client, cookie).block(Duration.ofSeconds(30));
        assertThat(STUB.exchangeCalls()).isEqualTo(1);

        expireAccessToken(store, cookie);
        Reply second = BffRefreshSupport.echo(client, cookie).block(Duration.ofSeconds(30));

        BffSession after = stored(store, cookie);
        assertThat(STUB.refreshCalls()).isEqualTo(1);
        assertThat(after.accessToken()).isNotEqualTo(before.accessToken());
        assertThat(after.version()).isGreaterThan(before.version());
        assertThat(second.status()).isEqualTo(200);
        assertThat(STUB.exchangeCalls()).isEqualTo(2);
        assertThat(STUB.lastExchangeRequestBody()).contains("subject_token=" + after.accessToken());
        assertThat(second.bearer()).isNotEqualTo(first.bearer()).isNotEqualTo(after.accessToken());
    }
}
