package dev.appiaworks.shiguchi.bff.starter;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.bff.testing.StubIssuer;
import io.grpc.Server;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The optional anti-CSRF header ({@code shiguchi.bff.csrf-header.name}) on the Boot 4 gateway over
 * a real port: every cookie-authenticated unsafe request, on REST, gRPC-Web and GraphQL, needs the
 * header with the configured value on top of the {@code Origin} rule; reads, WebSocket handshakes and
 * bearer callers do not. Mirrors {@code spring-boot3/shiguchi-bff-starter}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.security.mode=AUTHENTICATED",
            "shiguchi.grpc-web.enabled=true",
            "shiguchi.bff.enabled=true",
            "shiguchi.bff.csrf-header.name=X-CSRF",
            "shiguchi.bff.cookie.name=gw-session",
            "shiguchi.bff.cookie.secure=false",
            "shiguchi.bff.providers.idp.client-id=gateway",
            "shiguchi.bff.providers.idp.client-secret=secret",
            "shiguchi.bff.applications.a.origins=https://a.example.com",
            "shiguchi.bff.applications.a.providers=idp",
            "shiguchi.bff.applications.a.return-to=https://a.example.com/app/"
        })
@DisplayName("BFF anti-CSRF header end to end (Boot 4)")
class BffCsrfHeaderEndToEndTest {

    private static final StubIssuer STUB = StubIssuer.start();
    private static final Server BACKEND = BffTestBackend.start();
    private static final Path PB_FILE = BffTestBackend.writeDescriptorSet();

    private static final String A = "https://a.example.com";
    private static final String HEADER = "X-CSRF";

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {

        @Bean
        ReactiveJwtDecoder testJwtDecoder() {
            return NimbusReactiveJwtDecoder.withJwkSetUri(STUB.issuer() + "/jwks")
                    .build();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("shiguchi.bff.providers.idp.issuer-uri", STUB::issuer);
        registry.add("shiguchi.services[0].name", () -> "echo");
        registry.add("shiguchi.services[0].mode", () -> "PB_FILE");
        registry.add("shiguchi.services[0].pb-descriptor-path", () -> PB_FILE.toString());
        registry.add("shiguchi.services[0].host", () -> "localhost");
        registry.add("shiguchi.services[0].port", () -> BACKEND.getPort());
    }

    @AfterAll
    static void stop() throws IOException {
        BACKEND.shutdownNow();
        STUB.close();
        Files.deleteIfExists(PB_FILE);
    }

    private static final HttpClient BROWSER =
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    private static final byte[] EMPTY_GRPC_WEB_FRAME = new byte[5];

    private WebTestClient client;

    @Autowired
    private Environment environment;

    @BeforeEach
    void bindClient() {
        STUB.reset();
        client = WebTestClient.bindToServer()
                .responseTimeout(Duration.ofSeconds(30))
                .baseUrl("http://localhost:" + environment.getRequiredProperty("local.server.port"))
                .build();
    }

    /** Signs {@code subject} in through application a and returns the session cookie value. */
    private String signIn(String subject) throws Exception {
        STUB.subject(subject);
        Map<String, String> jar = new HashMap<>();
        EntityExchangeResult<byte[]> login = client.get()
                .uri("/bff/login/idp?return_to=https://a.example.com/app/page")
                .exchange()
                .expectStatus()
                .isEqualTo(302)
                .expectBody()
                .returnResult();
        login.getResponseCookies()
                .forEach((name, list) -> jar.put(name, list.get(0).getValue()));
        String authorizeUrl = login.getResponseHeaders().getFirst(HttpHeaders.LOCATION);
        HttpResponse<String> issuer = BROWSER.send(
                HttpRequest.newBuilder(URI.create(authorizeUrl)).GET().build(), HttpResponse.BodyHandlers.ofString());
        URI callback = URI.create(issuer.headers().firstValue("Location").orElseThrow());
        WebTestClient.RequestHeadersSpec<?> spec =
                client.get().uri(callback.getRawPath() + "?" + callback.getRawQuery());
        jar.forEach(spec::cookie);
        EntityExchangeResult<byte[]> done =
                spec.exchange().expectStatus().isEqualTo(302).expectBody().returnResult();
        ResponseCookie session = done.getResponseCookies().getFirst("gw-session");
        assertThat(session).isNotNull();
        return session.getValue();
    }

    @Test
    @DisplayName(
            "GIVEN the header is required WHEN a REST POST carries the right Origin SHOULD answer 403 without the header or with a wrong value, and not 403 with it")
    void given_headerRequired_when_restPost_should_checkHeader() throws Exception {
        String session = signIn("alice");

        client.post()
                .uri("/v1/echo/hi")
                .cookie("gw-session", session)
                .header(HttpHeaders.ORIGIN, A)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus()
                .isEqualTo(403)
                .expectBody()
                .json("{\"error\":\"csrf header required\"}");
        client.post()
                .uri("/v1/echo/hi")
                .cookie("gw-session", session)
                .header(HttpHeaders.ORIGIN, A)
                .header(HEADER, "yes")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus()
                .isEqualTo(403);
        int status = client.post()
                .uri("/v1/echo/hi")
                .cookie("gw-session", session)
                .header(HttpHeaders.ORIGIN, A)
                .header(HEADER, "1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .returnResult(String.class)
                .getStatus()
                .value();
        assertThat(status).isNotIn(403, 415);
    }

    @Test
    @DisplayName(
            "GIVEN the header is required WHEN calling gRPC-Web SHOULD answer 403 without it and reach the backend with it")
    void given_headerRequired_when_grpcWeb_should_checkHeader() throws Exception {
        String session = signIn("alice");

        client.post()
                .uri("/lib.Echo/Keys")
                .cookie("gw-session", session)
                .header(HttpHeaders.ORIGIN, A)
                .header(HttpHeaders.CONTENT_TYPE, "application/grpc-web+proto")
                .bodyValue(EMPTY_GRPC_WEB_FRAME)
                .exchange()
                .expectStatus()
                .isEqualTo(403);
        client.post()
                .uri("/lib.Echo/Keys")
                .cookie("gw-session", session)
                .header(HttpHeaders.ORIGIN, A)
                .header(HEADER, "1")
                .header(HttpHeaders.CONTENT_TYPE, "application/grpc-web+proto")
                .bodyValue(EMPTY_GRPC_WEB_FRAME)
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    @DisplayName(
            "GIVEN the header is required WHEN posting a GraphQL query SHOULD answer 403 without it and succeed with it")
    void given_headerRequired_when_graphql_should_checkHeader() throws Exception {
        String session = signIn("alice");
        Map<String, String> query = Map.of("query", "{ keys(text: \"x\") { text } }");

        client.post()
                .uri("/graphql")
                .cookie("gw-session", session)
                .header(HttpHeaders.ORIGIN, A)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(query)
                .exchange()
                .expectStatus()
                .isEqualTo(403);
        client.post()
                .uri("/graphql")
                .cookie("gw-session", session)
                .header(HttpHeaders.ORIGIN, A)
                .header(HEADER, "1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(query)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.data.keys.text")
                .exists();
    }

    @Test
    @DisplayName(
            "GIVEN the header is required WHEN reading, handshaking a WebSocket or calling with a bearer token SHOULD NOT need it")
    void given_headerRequired_when_readUpgradeOrBearer_should_notNeedHeader() throws Exception {
        String session = signIn("alice");

        client.get()
                .uri("/v1/keys/x")
                .cookie("gw-session", session)
                .header(HttpHeaders.ORIGIN, A)
                .exchange()
                .expectStatus()
                .isOk();
        int upgrade = client.get()
                .uri("/graphql/ws")
                .cookie("gw-session", session)
                .header(HttpHeaders.CONNECTION, "Upgrade")
                .header(HttpHeaders.UPGRADE, "websocket")
                .header(HttpHeaders.ORIGIN, A)
                .exchange()
                .returnResult(String.class)
                .getStatus()
                .value();
        assertThat(upgrade).isNotEqualTo(403);
        int bearer = client.post()
                .uri("/v1/echo/hi")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + STUB.accessToken("carol"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .returnResult(String.class)
                .getStatus()
                .value();
        assertThat(bearer).isNotIn(401, 403, 415);
    }

    @Test
    @DisplayName("GIVEN the header is required WHEN logging out SHOULD need it, and sign the session out with it")
    void given_headerRequired_when_loggingOut_should_needHeader() throws Exception {
        String session = signIn("alice");

        client.post()
                .uri("/bff/logout")
                .cookie("gw-session", session)
                .header(HttpHeaders.ORIGIN, A)
                .exchange()
                .expectStatus()
                .isEqualTo(403);
        client.post()
                .uri("/bff/logout")
                .cookie("gw-session", session)
                .header(HttpHeaders.ORIGIN, A)
                .header(HEADER, "1")
                .exchange()
                .expectStatus()
                .isOk();
        client.get()
                .uri("/v1/echo/hi")
                .cookie("gw-session", session)
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }
}
