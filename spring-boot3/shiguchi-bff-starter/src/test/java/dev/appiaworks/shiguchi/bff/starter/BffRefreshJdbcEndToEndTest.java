package dev.appiaworks.shiguchi.bff.starter;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.bff.session.BffSession;
import dev.appiaworks.shiguchi.bff.session.BffSessionKeys;
import dev.appiaworks.shiguchi.bff.session.BffSessionStore;
import dev.appiaworks.shiguchi.bff.starter.BffRefreshSupport.Reply;
import dev.appiaworks.shiguchi.bff.testing.StubIssuer;
import io.grpc.Server;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The slice's proof with the JDBC store on the Boot 3 gateway: two gateway instances sharing one
 * embedded PostgreSQL, 25 simultaneous calls to each with the same expired session. One extra
 * refresh anywhere would make the issuer revoke the family. Mirrors
 * {@code spring-boot4/shiguchi-bff-starter}.
 */
@DisplayName("BFF token refresh, two instances over PostgreSQL, end to end (Boot 3)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BffRefreshJdbcEndToEndTest {

    private static final StubIssuer STUB = StubIssuer.start();
    private static final Server BACKEND = BffTestBackend.start();
    private static final Path PB_FILE = BffTestBackend.writeDescriptorSet();
    private static EmbeddedPostgres postgres;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {

        @Bean
        ReactiveJwtDecoder testJwtDecoder() {
            return NimbusReactiveJwtDecoder.withJwkSetUri(STUB.issuer() + "/jwks")
                    .build();
        }

        /** Each instance opens its own connections to the one database. */
        @Bean
        DataSource dataSource() {
            return postgres.getPostgresDatabase();
        }
    }

    private static ConfigurableApplicationContext first;
    private static ConfigurableApplicationContext second;
    private static List<WebClient> clients;

    @BeforeAll
    static void start() throws IOException {
        postgres = EmbeddedPostgres.start();
        List<String> properties = BffRefreshSupport.properties(STUB, BACKEND, PB_FILE);
        properties.add("shiguchi.bff.session.store=JDBC");
        // Tokens are encrypted at rest: every test below runs through the encrypting store.
        properties.addAll(BffRefreshSupport.encryption());
        // One after the other: both create the tables.
        first = BffRefreshSupport.start(TestApp.class, properties);
        second = BffRefreshSupport.start(TestApp.class, properties);
        clients = List.of(
                BffRefreshSupport.client(BffRefreshSupport.port(first)),
                BffRefreshSupport.client(BffRefreshSupport.port(second)));
    }

    @AfterAll
    static void stop() throws IOException {
        if (second != null) second.close();
        if (first != null) first.close();
        if (postgres != null) postgres.close();
        BACKEND.shutdownNow();
        STUB.close();
        Files.deleteIfExists(PB_FILE);
    }

    @BeforeEach
    void resetIssuer() {
        STUB.reset();
    }

    @Test
    @Order(1)
    @DisplayName(
            "GIVEN a slow JWK set on a cold start and 25 simultaneous calls to each of two instances WHEN refreshing SHOULD still use the refresh token once and revoke nothing")
    void given_slow_jwks_and_two_instances_when_refreshing_should_use_refresh_token_once() {
        BffSessionStore store = first.getBean(BffSessionStore.class);
        // Runs first: the decoders have not fetched the JWK set yet, so the fetch is slow inside this refresh.
        STUB.jwksDelay(Duration.ofMillis(1500));
        STUB.refreshDelay(Duration.ofMillis(400));
        String cookie = BffRefreshSupport.seed(store, STUB, Duration.ofSeconds(-5));

        List<Reply> replies = BffRefreshSupport.burst(clients, 25, cookie);

        BffSession after = store.find(BffSessionKeys.key(cookie)).block(Duration.ofSeconds(10));
        assertThat(replies).hasSize(50).allSatisfy(r -> assertThat(r.status()).isEqualTo(200));
        assertThat(STUB.jwksCalls()).isGreaterThanOrEqualTo(1);
        assertThat(STUB.refreshCalls()).isEqualTo(1);
        assertThat(STUB.familyRevoked()).isFalse();
        assertThat(after.version()).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "GIVEN an expired session and 25 simultaneous calls to each of two instances WHEN refreshing SHOULD call the issuer once and revoke nothing")
    void given_two_instances_and_expired_session_when_refreshing_should_call_issuer_once() {
        BffSessionStore store = first.getBean(BffSessionStore.class);
        // The issuer holds each refresh back, so calls to both instances arrive while it is in flight.
        STUB.refreshDelay(Duration.ofMillis(400));
        String cookie = BffRefreshSupport.seed(store, STUB, Duration.ofSeconds(-5));

        List<Reply> replies = BffRefreshSupport.burst(clients, 25, cookie);

        BffSession after = store.find(BffSessionKeys.key(cookie)).block(Duration.ofSeconds(10));
        assertThat(replies).hasSize(50).allSatisfy(r -> assertThat(r.status()).isEqualTo(200));
        assertThat(STUB.refreshCalls()).isEqualTo(1);
        assertThat(STUB.familyRevoked()).isFalse();
        assertThat(after.version()).isEqualTo(1);
        assertThat(replies.stream().map(Reply::bearer).distinct()).containsExactly(after.accessToken());
    }

    @Test
    @DisplayName(
            "GIVEN encryption keys WHEN a session is stored SHOULD hold ciphertext in the table and plaintext for the gateway")
    void given_keys_when_session_stored_should_hold_ciphertext_in_the_table() {
        BffSessionStore store = first.getBean(BffSessionStore.class);
        String cookie = BffRefreshSupport.seed(store, STUB, Duration.ofMinutes(5));
        String key = BffSessionKeys.key(cookie);

        BffSession session = store.find(key).block(Duration.ofSeconds(10));
        Map<String, Object> raw = new JdbcTemplate(first.getBean(DataSource.class))
                .queryForMap(
                        "SELECT access_token, refresh_token, id_token FROM gw_bff_session WHERE session_key = ?", key);

        assertThat(session.accessToken()).startsWith("eyJ");
        assertThat(raw.values())
                .hasSize(3)
                .allSatisfy(v -> assertThat(String.valueOf(v))
                        .startsWith("v1." + BffRefreshSupport.KEY_ID + ".")
                        .doesNotContain(session.accessToken(), session.refreshToken(), session.idToken()));
    }

    @Test
    @DisplayName(
            "GIVEN an encrypted session WHEN logging out through the other instance SHOULD revoke the real refresh token at the issuer")
    void given_encrypted_session_when_logging_out_should_revoke_the_real_refresh_token() throws InterruptedException {
        BffSessionStore store = first.getBean(BffSessionStore.class);
        String cookie = BffRefreshSupport.seed(store, STUB, Duration.ofMinutes(5));
        String refreshToken = store.find(BffSessionKeys.key(cookie)).block().refreshToken();

        clients.get(1)
                .post()
                .uri("/bff/logout")
                .header(HttpHeaders.ORIGIN, "https://a.example.com")
                .cookie(BffRefreshSupport.COOKIE, cookie)
                .retrieve()
                .toBodilessEntity()
                .block(Duration.ofSeconds(30));

        assertThat(BffRefreshSupport.awaitRevoked(STUB, refreshToken)).isTrue();
        assertThat(store.find(BffSessionKeys.key(cookie)).blockOptional()).isEmpty();
    }

    @Test
    @DisplayName("GIVEN an encrypted session WHEN the issuer sends a back-channel logout SHOULD end it by its sid")
    void given_encrypted_session_when_backchannel_logout_should_end_it() {
        BffSessionStore store = first.getBean(BffSessionStore.class);
        String cookie = BffRefreshSupport.seed(store, STUB, Duration.ofMinutes(5), "alice", "sid-bcl");

        clients.get(1)
                .post()
                .uri("/bff/backchannel-logout/idp")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(
                        "logout_token", BffRefreshSupport.logoutToken(STUB, "alice", "sid-bcl")))
                .retrieve()
                .toBodilessEntity()
                .block(Duration.ofSeconds(30));

        assertThat(store.find(BffSessionKeys.key(cookie)).blockOptional()).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN a session refreshed through one instance WHEN the other serves it SHOULD use the stored tokens without refreshing")
    void given_session_refreshed_through_one_instance_when_other_serves_it_should_not_refresh() {
        BffSessionStore store = first.getBean(BffSessionStore.class);
        String cookie = BffRefreshSupport.seed(store, STUB, Duration.ofSeconds(-5));

        Reply viaFirst = BffRefreshSupport.echo(clients.get(0), cookie).block(Duration.ofSeconds(30));
        Reply viaSecond = BffRefreshSupport.echo(clients.get(1), cookie).block(Duration.ofSeconds(30));

        assertThat(viaFirst.status()).isEqualTo(200);
        assertThat(viaSecond.bearer()).isEqualTo(viaFirst.bearer());
        assertThat(STUB.refreshCalls()).isEqualTo(1);
    }
}
