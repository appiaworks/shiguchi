package dev.appiaworks.shiguchi.bff.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jwt.SignedJWT;
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
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Sign-in and an upstream call relayed with the session's token on the Boot 4 gateway against a stub issuer
 * that signs with ES256, the provider configured with {@code jws-algorithms=ES256}. Mirrors {@code
 * spring-boot3/shiguchi-bff-starter}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.security.mode=AUTHENTICATED",
            "shiguchi.bff.enabled=true",
            "shiguchi.bff.cookie.name=gw-session",
            "shiguchi.bff.cookie.secure=false",
            "shiguchi.bff.providers.idp.client-id=gateway",
            "shiguchi.bff.providers.idp.client-secret=secret",
            "shiguchi.bff.providers.idp.jws-algorithms=ES256",
            "shiguchi.bff.applications.a.origins=https://a.example.com",
            "shiguchi.bff.applications.a.providers=idp",
            "shiguchi.bff.applications.a.return-to=https://a.example.com/app/"
        })
@DisplayName("BFF ES256 issuer end to end (Boot 4)")
class BffJwsAlgorithmsEndToEndTest {

    private static final StubIssuer STUB = StubIssuer.start();
    private static final Server BACKEND = BffTestBackend.start();
    private static final Path PB_FILE = BffTestBackend.writeDescriptorSet();

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {

        /** The bearer path: tokens the stub issuer signed, with ES256. */
        @Bean
        ReactiveJwtDecoder testJwtDecoder() {
            return NimbusReactiveJwtDecoder.withJwkSetUri(STUB.issuer() + "/jwks")
                    .jwsAlgorithm(SignatureAlgorithm.ES256)
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

    private WebTestClient client;

    @Autowired
    private Environment environment;

    @BeforeEach
    void bindClient() {
        STUB.reset();
        STUB.signingAlgorithm("ES256");
        client = WebTestClient.bindToServer()
                .responseTimeout(Duration.ofSeconds(30))
                .baseUrl("http://localhost:" + environment.getRequiredProperty("local.server.port"))
                .build();
    }

    private record Reply(int status, String location, Map<String, String> cookies) {}

    private Reply get(String pathAndQuery, Map<String, String> cookies) {
        WebTestClient.RequestHeadersSpec<?> spec = client.get().uri(pathAndQuery);
        cookies.forEach(spec::cookie);
        EntityExchangeResult<byte[]> result = spec.exchange().expectBody().returnResult();
        Map<String, String> set = new HashMap<>();
        result.getResponseCookies()
                .forEach((name, list) -> set.put(name, list.get(0).getValue()));
        return new Reply(result.getStatus().value(), result.getResponseHeaders().getFirst(HttpHeaders.LOCATION), set);
    }

    private static String authorize(String authorizeUrl) throws Exception {
        HttpResponse<String> response = BROWSER.send(
                HttpRequest.newBuilder(URI.create(authorizeUrl)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(302);
        return response.headers().firstValue("Location").orElseThrow();
    }

    private static String pathAndQuery(String target) {
        URI uri = URI.create(target);
        return uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
    }

    @Test
    @DisplayName(
            "GIVEN an issuer signing with ES256 and jws-algorithms ES256 WHEN signing in and calling upstream SHOULD relay the session's ES256 access token")
    void given_es256Issuer_when_signInAndCallUpstream_should_relayEs256Token() throws Exception {
        Reply login = get("/bff/login/idp?return_to=https://a.example.com/app/page", Map.of());
        assertThat(login.status()).isEqualTo(302);

        Reply done = get(pathAndQuery(authorize(login.location())), login.cookies());

        assertThat(done.status()).isEqualTo(302);
        assertThat(done.location()).isEqualTo("https://a.example.com/app/page");
        String session = done.cookies().get("gw-session");
        assertThat(session).isNotEmpty();
        AtomicReference<String> relayed = new AtomicReference<>();
        client.get()
                .uri("/v1/echo/hi")
                .cookie("gw-session", session)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.text")
                .value(text -> {
                    assertThat((String) text).matches("Bearer [\\w-]+\\.[\\w-]+\\.[\\w-]+\\|alice");
                    relayed.set(((String) text).substring("Bearer ".length(), ((String) text).indexOf('|')));
                });
        assertThat(SignedJWT.parse(relayed.get()).getHeader().getAlgorithm().getName())
                .isEqualTo("ES256");
    }

    @Test
    @DisplayName("GIVEN an ES256 bearer token WHEN calling upstream SHOULD authenticate it with the ES256 decoder")
    void given_es256Bearer_when_callingUpstream_should_authenticate() {
        client.get()
                .uri("/v1/echo/hi")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + STUB.accessToken("alice"))
                .exchange()
                .expectStatus()
                .isOk();
    }
}
