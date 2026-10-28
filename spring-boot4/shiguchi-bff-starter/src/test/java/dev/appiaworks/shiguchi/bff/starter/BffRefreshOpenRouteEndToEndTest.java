package dev.appiaworks.shiguchi.bff.starter;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.web.reactive.function.client.WebClient;

/**
 * An open route (the transcoded routes are not gated) on the Boot 4 gateway: a signed-in user whose
 * token cannot be refreshed because the issuer is down must get 503, never an anonymous call to
 * the backend. Mirrors {@code spring-boot3/shiguchi-bff-starter}.
 */
@DisplayName("BFF token refresh on an open route end to end (Boot 4)")
class BffRefreshOpenRouteEndToEndTest {

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
        properties.add("shiguchi.security.surfaces.rest.require-auth=false");
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

    @Test
    @DisplayName(
            "GIVEN an open route and a down issuer WHEN a signed-in user's token has expired SHOULD answer 503, not call the backend anonymously")
    void given_open_route_and_down_issuer_when_token_expired_should_answer_503() {
        String cookie = BffRefreshSupport.seed(store, STUB, Duration.ofSeconds(-5));
        STUB.refreshStatus(503);

        Reply reply = BffRefreshSupport.echo(client, cookie).block(Duration.ofSeconds(30));

        assertThat(reply.status()).isEqualTo(503);
        assertThat(reply.retryAfter()).isEqualTo(1);
        assertThat(reply.body()).doesNotContain("anonymous");
        assertThat(store.find(BffSessionKeys.key(cookie)).block()).isNotNull();
    }

    @Test
    @DisplayName(
            "GIVEN an open route WHEN a signed-in user's token has expired and the issuer is up SHOULD call the backend as the user")
    void given_open_route_when_token_expired_and_issuer_up_should_call_backend_as_user() {
        String cookie = BffRefreshSupport.seed(store, STUB, Duration.ofSeconds(-5));

        Reply reply = BffRefreshSupport.echo(client, cookie).block(Duration.ofSeconds(30));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body()).contains("Bearer ").endsWith("|alice\"}");
    }
}
