package dev.appiaworks.shiguchi.bff.starter;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.bff.session.BffSession;
import dev.appiaworks.shiguchi.bff.session.BffSessionKeys;
import dev.appiaworks.shiguchi.bff.session.BffSessionStore;
import dev.appiaworks.shiguchi.bff.testing.StubIssuer;
import io.grpc.Server;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.reactive.function.client.WebClient;

/**
 * RFC 8693 token exchange on the GraphQL path of the Boot 4 gateway: a cookie caller's query
 * reaches the backend with the token exchanged for that service, not the session's own; a refusal
 * and an unreachable issuer surface as GraphQL errors, never relaying the user's token.
 */
@DisplayName("BFF token exchange on GraphQL end to end (Boot 4)")
class BffTokenExchangeGraphQlEndToEndTest {

    private static final String SERVICE = "lib.Echo";
    private static final String QUERY = "{ echo(text: \"hi\") { text } }";

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
    private static BffSessionStore store;

    @BeforeAll
    static void start() {
        List<String> properties = BffRefreshSupport.properties(STUB, BACKEND, PB_FILE);
        properties.add("shiguchi.graphql.enabled=true");
        properties.add("shiguchi.bff.providers.idp.upstream-token-mode=EXCHANGE");
        properties.add("shiguchi.bff.providers.idp.exchange.targets[" + SERVICE + "].audience=orders-api");
        gateway = BffRefreshSupport.start(TestApp.class, properties);
        client = BffRefreshSupport.client(BffRefreshSupport.port(gateway));
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

    private static String post(String cookie) {
        return client.post()
                .uri("/graphql")
                .cookie(BffRefreshSupport.COOKIE, cookie)
                .header(HttpHeaders.ORIGIN, "https://a.example.com")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("query", QUERY))
                .exchangeToMono(r -> r.bodyToMono(String.class).defaultIfEmpty(""))
                .block(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName(
            "GIVEN a cookie caller WHEN querying SHOULD reach the backend with the exchanged token, not the session's")
    void given_cookie_caller_when_querying_should_use_exchanged_token() {
        String cookie = BffRefreshSupport.seed(store, STUB, Duration.ofMinutes(5));
        BffSession session = store.find(BffSessionKeys.key(cookie)).block(Duration.ofSeconds(10));

        String body = post(cookie);

        assertThat(body).contains("Bearer ").doesNotContain("\"errors\"").doesNotContain(session.accessToken());
        assertThat(STUB.exchangeCalls()).isEqualTo(1);
        assertThat(STUB.lastExchangeRequestBody()).contains("audience=orders-api");
    }

    @Test
    @DisplayName(
            "GIVEN an issuer that refuses the exchange WHEN querying SHOULD return a GraphQL error without the user's token")
    void given_refused_exchange_when_querying_should_error_without_relaying_token() {
        String cookie = BffRefreshSupport.seed(store, STUB, Duration.ofMinutes(5));
        BffSession session = store.find(BffSessionKeys.key(cookie)).block(Duration.ofSeconds(10));
        STUB.exchangeStatus(400).exchangeError("invalid_target");

        String body = post(cookie);

        assertThat(body).contains("\"errors\"").doesNotContain(session.accessToken());
        assertThat(body).doesNotContain("Bearer ");
    }
}
