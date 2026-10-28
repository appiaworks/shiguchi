package dev.appiaworks.shiguchi.bff.starter;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.bff.BffConfiguration;
import dev.appiaworks.shiguchi.bff.BffSessionAuthenticator;
import dev.appiaworks.shiguchi.gateway.UpstreamCredentialSource;
import dev.appiaworks.shiguchi.security.GatewaySecurityCustomizer;
import io.grpc.Server;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * The BFF module on the Boot 4 classpath with {@code shiguchi.bff.enabled} unset: none of its
 * beans exist, and bearer and anonymous calls behave as without the module. A session
 * authenticator bean the application defines is ignored. Mirrors
 * {@code spring-boot3/shiguchi-bff-starter}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"shiguchi.ui.enabled=false", "shiguchi.graphql.enabled=false", "shiguchi.openapi.enabled=false"})
@DisplayName("BFF module present but disabled end to end (Boot 4)")
class BffDisabledEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {

        @Bean
        ReactiveJwtDecoder testJwtDecoder() {
            return token -> "bob-token".equals(token)
                    ? Mono.just(BffTestBackend.bobJwt())
                    : Mono.error(new BadJwtException("unknown token"));
        }

        /** Would authenticate cookie {@code test=alice} if the module were on. */
        @Bean
        BffSessionAuthenticator testSessionAuthenticator() {
            return exchange -> Mono.<Authentication>just(BffTestBackend.sessionUser("alice", "secret.read"));
        }
    }

    private static final Server BACKEND = BffTestBackend.start();
    private static final Path PB_FILE = BffTestBackend.writeDescriptorSet();

    @DynamicPropertySource
    static void backend(DynamicPropertyRegistry registry) {
        registry.add("shiguchi.services[0].name", () -> "echo");
        registry.add("shiguchi.services[0].mode", () -> "PB_FILE");
        registry.add("shiguchi.services[0].pb-descriptor-path", () -> PB_FILE.toString());
        registry.add("shiguchi.services[0].host", () -> "localhost");
        registry.add("shiguchi.services[0].port", () -> BACKEND.getPort());
    }

    @AfterAll
    static void stop() throws IOException {
        BACKEND.shutdownNow();
        Files.deleteIfExists(PB_FILE);
    }

    private WebTestClient client;

    @Autowired
    private Environment environment;

    @Autowired
    private ApplicationContext context;

    @BeforeEach
    void bindClient() {
        client = WebTestClient.bindToServer()
                .responseTimeout(Duration.ofSeconds(30))
                .baseUrl("http://localhost:" + environment.getRequiredProperty("local.server.port"))
                .build();
    }

    @Test
    @DisplayName("GIVEN bff.enabled unset WHEN the context starts SHOULD define none of the module's beans")
    void given_disabled_when_contextStarts_should_defineNoBffBeans() {
        assertThat(context.getBeanNamesForType(BffConfiguration.class)).isEmpty();
        assertThat(context.getBeanNamesForType(GatewaySecurityCustomizer.class)).isEmpty();
        assertThat(context.getBeanNamesForType(UpstreamCredentialSource.class)).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN bff.enabled unset WHEN calling with a bearer token SHOULD forward it and its name, and set no cookie")
    void given_disabled_when_bearerCall_should_forwardHeader() {
        client.get()
                .uri("/v1/echo/hi")
                .header(HttpHeaders.AUTHORIZATION, "Bearer bob-token")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .doesNotExist(HttpHeaders.SET_COOKIE)
                .expectBody()
                .jsonPath("$.text")
                .isEqualTo("Bearer bob-token|bob");
    }

    @Test
    @DisplayName(
            "GIVEN bff.enabled unset WHEN calling with only a cookie SHOULD answer exactly as an anonymous call, with no credential upstream")
    void given_disabled_when_cookieCall_should_matchAnonymous() {
        String anonymous = client.get()
                .uri("/v1/echo/hi")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .doesNotExist(HttpHeaders.SET_COOKIE)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        String withCookie = client.get()
                .uri("/v1/echo/hi")
                .cookie("test", "alice")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .doesNotExist(HttpHeaders.SET_COOKIE)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(withCookie).isEqualTo(anonymous).doesNotContain("Bearer").doesNotContain("alice");
    }
}
