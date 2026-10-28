package dev.appiaworks.shiguchi.bff.starter;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.bff.BffSecurityContextRepository;
import dev.appiaworks.shiguchi.bff.BffSessionAuthenticator;
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
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * The cookie/bearer path rule on the Boot 4 gateway over a real port, under {@code mode:
 * AUTHENTICATED}, with a test {@link BffSessionAuthenticator} that maps cookie {@code test=<user>}
 * to a session token: a cookie-only caller reaches the backend with the session token as
 * {@code authorization} and its name as {@code x-principal}; a bearer header wins over the
 * cookie; a malformed header is not rescued by the cookie; scope gates see the cookie caller's
 * authorities. Mirrors {@code spring-boot3/shiguchi-bff-starter}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            // GraphQL stays on: the route loader reads auth_scopes only for GraphQL-enabled services,
            // and the scope-gated route needs them. The GraphQL starter is on the test classpath.
            "shiguchi.openapi.enabled=false",
            "shiguchi.security.mode=AUTHENTICATED",
            "shiguchi.bff.enabled=true",
            "shiguchi.bff.providers.idp.issuer-uri=https://id.example.com",
            "shiguchi.bff.providers.idp.client-id=gateway",
            "shiguchi.bff.providers.idp.client-secret=secret",
            "shiguchi.bff.applications.app.origins=https://app.example.com",
            "shiguchi.bff.applications.app.providers=idp",
            "shiguchi.bff.applications.app.return-to=https://app.example.com/"
        })
@DisplayName("BFF cookie/bearer path rule end to end (Boot 4)")
class BffPathRuleEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {

        /** Bearer callers: only {@code bob-token} is valid. */
        @Bean
        ReactiveJwtDecoder testJwtDecoder() {
            return token -> "bob-token".equals(token)
                    ? Mono.just(BffTestBackend.bobJwt())
                    : Mono.error(new BadJwtException("unknown token"));
        }

        /** Cookie {@code test=alice} holds scope secret.read, {@code test=carol} none. */
        @Bean
        BffSessionAuthenticator testSessionAuthenticator() {
            return exchange -> {
                HttpCookie cookie = exchange.getRequest().getCookies().getFirst("test");
                if (cookie == null) return Mono.empty();
                return switch (cookie.getValue()) {
                    case "alice" -> Mono.<Authentication>just(BffTestBackend.sessionUser("alice", "secret.read"));
                    case "carol" -> Mono.<Authentication>just(BffTestBackend.sessionUser("carol"));
                    default -> Mono.empty();
                };
            };
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
    @DisplayName(
            "GIVEN a session cookie and no Authorization header WHEN calling SHOULD send the session token and the session user upstream, and set no cookie")
    void given_cookieOnly_when_calling_should_sendSessionTokenAndPrincipal() {
        client.get()
                .uri("/v1/echo/hi")
                .cookie("test", "alice")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .doesNotExist(HttpHeaders.SET_COOKIE)
                .expectBody()
                .jsonPath("$.text")
                .isEqualTo("Bearer alice-session-token|alice");
    }

    @Test
    @DisplayName(
            "GIVEN a bearer token for bob and a session cookie for alice WHEN calling SHOULD send bob's own header and name upstream")
    void given_bearerAndCookie_when_calling_should_preferBearer() {
        client.get()
                .uri("/v1/echo/hi")
                .header(HttpHeaders.AUTHORIZATION, "Bearer bob-token")
                .cookie("test", "alice")
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
    @DisplayName("GIVEN a malformed Authorization header and a session cookie WHEN calling SHOULD answer 401")
    void given_malformedHeaderAndCookie_when_calling_should_return401() {
        client.get()
                .uri("/v1/echo/hi")
                .header(HttpHeaders.AUTHORIZATION, "garbage")
                .cookie("test", "alice")
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    @DisplayName("GIVEN an invalid bearer token and a session cookie WHEN calling SHOULD answer 401")
    void given_invalidBearerAndCookie_when_calling_should_return401() {
        client.get()
                .uri("/v1/echo/hi")
                .header(HttpHeaders.AUTHORIZATION, "Bearer forged")
                .cookie("test", "alice")
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    @DisplayName("GIVEN neither a header nor a session WHEN calling under AUTHENTICATED SHOULD answer 401")
    void given_noCredentials_when_calling_should_return401() {
        client.get().uri("/v1/echo/hi").exchange().expectStatus().isUnauthorized();
        client.get()
                .uri("/v1/echo/hi")
                .cookie("test", "nobody")
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    @DisplayName("GIVEN a cookie user holding the route's scope WHEN calling the scope-gated route SHOULD pass")
    void given_cookieUserWithScope_when_callingGatedRoute_should_pass() {
        client.get()
                .uri("/v1/secret/hi")
                .cookie("test", "alice")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.text")
                .isEqualTo("Bearer alice-session-token|alice");
    }

    @Test
    @DisplayName("GIVEN a cookie user without the route's scope WHEN calling the scope-gated route SHOULD answer 403")
    void given_cookieUserWithoutScope_when_callingGatedRoute_should_return403() {
        client.get()
                .uri("/v1/secret/hi")
                .cookie("test", "carol")
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    @DisplayName(
            "GIVEN the module enabled WHEN the context starts SHOULD install its repository and leave the JWT decoder beans as the application defined them")
    void given_enabled_when_contextStarts_should_notAddJwtDecoder() {
        assertThat(context.getBeanNamesForType(BffSecurityContextRepository.class))
                .hasSize(1);
        assertThat(context.getBeanNamesForType(ReactiveJwtDecoder.class)).containsExactly("testJwtDecoder");
        assertThat(context.getBeanNamesForType(BffSessionAuthenticator.class))
                .containsExactly("testSessionAuthenticator");
    }

    @Test
    @DisplayName(
            "GIVEN a bearer caller holding the field's scope WHEN posting a GraphQL query SHOULD send the backend the bearer header and the caller's name")
    void given_bearer_when_graphqlScopedField_should_sendBearerAndPrincipal() {
        client.post()
                .uri("/graphql")
                .header(HttpHeaders.AUTHORIZATION, "Bearer bob-token")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("query", "{ secret(text: \"x\") { text } }"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.errors")
                .doesNotExist()
                .jsonPath("$.data.secret.text")
                .isEqualTo("Bearer bob-token|bob");
    }

    @Test
    @DisplayName(
            "GIVEN a cookie user holding the field's scope WHEN posting a GraphQL query SHOULD send the backend the session token and the user's name")
    void given_cookieUser_when_graphqlScopedField_should_sendSessionTokenAndPrincipal() {
        client.post()
                .uri("/graphql")
                .cookie("test", "alice")
                .header(HttpHeaders.ORIGIN, "https://app.example.com")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("query", "{ secret(text: \"x\") { text } }"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.errors")
                .doesNotExist()
                .jsonPath("$.data.secret.text")
                .isEqualTo("Bearer alice-session-token|alice");
    }
}
