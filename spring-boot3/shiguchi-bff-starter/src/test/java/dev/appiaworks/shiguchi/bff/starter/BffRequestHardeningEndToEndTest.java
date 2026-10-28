package dev.appiaworks.shiguchi.bff.starter;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.bff.testing.StubIssuer;
import io.grpc.Server;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
import org.springframework.web.server.WebFilter;

/**
 * What the gateway does to a request that carries a session cookie, on the Boot 3 gateway over a
 * real port: refuses a state-changing request, a gRPC-Web call, a GraphQL query and a WebSocket
 * handshake whose {@code Origin} is not the session's application's, refuses form-like REST
 * bodies, never lets two cookie users share a cached response, never sends the cookie to a
 * backend and never lets a layer behind the gateway set the session cookie. Bearer callers are
 * untouched. Mirrors {@code spring-boot4/shiguchi-bff-starter}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.security.mode=AUTHENTICATED",
            "shiguchi.grpc-web.enabled=true",
            "shiguchi.rest.response-cache.enabled=true",
            "shiguchi.bff.enabled=true",
            "shiguchi.bff.cookie.name=gw-session",
            "shiguchi.bff.cookie.secure=false",
            "shiguchi.bff.providers.idp.client-id=gateway",
            "shiguchi.bff.providers.idp.client-secret=secret",
            "shiguchi.bff.applications.a.origins=https://a.example.com",
            "shiguchi.bff.applications.a.providers=idp",
            "shiguchi.bff.applications.a.return-to=https://a.example.com/app/",
            "shiguchi.bff.applications.b.origins=https://b.example.com",
            "shiguchi.bff.applications.b.providers=idp",
            "shiguchi.bff.applications.b.return-to=https://b.example.com/"
        })
@DisplayName("BFF request hardening end to end (Boot 3)")
class BffRequestHardeningEndToEndTest {

    private static final StubIssuer STUB = StubIssuer.start();
    private static final Server BACKEND = BffTestBackend.start();
    private static final Path PB_FILE = BffTestBackend.writeDescriptorSet();

    private static final String A = "https://a.example.com";
    private static final String B = "https://b.example.com";
    private static final String EVIL = "https://evil.example.com";

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {

        @Bean
        ReactiveJwtDecoder testJwtDecoder() {
            return NimbusReactiveJwtDecoder.withJwkSetUri(STUB.issuer() + "/jwks")
                    .build();
        }

        /** A layer behind the gateway's chain that tries to set the session cookie, as a backend or a custom filter might. */
        @Bean
        WebFilter cookieThief() {
            return (exchange, chain) -> {
                if (exchange.getRequest().getPath().value().equals("/v1/echo/evil")) {
                    exchange.getResponse()
                            .addCookie(
                                    ResponseCookie.from("gw-session", "stolen").build());
                    exchange.getResponse().getHeaders().add(HttpHeaders.SET_COOKIE, "gw-session=stolen2; Path=/");
                    exchange.getResponse()
                            .addCookie(ResponseCookie.from("theme", "dark").build());
                }
                return chain.filter(exchange);
            };
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
        registry.add("shiguchi.services[0].cache[0].methods[0]", () -> "Get");
        registry.add("shiguchi.services[0].cache[0].ttl", () -> "60s");
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
            "GIVEN a session of application a WHEN a state-changing REST request has no Origin, a foreign one or application b's SHOULD answer 403, and 403 is not what its own origin gets")
    void given_session_when_stateChangingRestRequest_should_checkOrigin() throws Exception {
        String session = signIn("alice");

        client.post()
                .uri("/v1/echo/hi")
                .cookie("gw-session", session)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus()
                .isEqualTo(403)
                .expectBody()
                .json("{\"error\":\"origin not allowed\"}");
        for (String origin : new String[] {EVIL, B, "null", "https://a.example.com.evil.net"}) {
            client.post()
                    .uri("/v1/echo/hi")
                    .cookie("gw-session", session)
                    .header(HttpHeaders.ORIGIN, origin)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{}")
                    .exchange()
                    .expectStatus()
                    .isEqualTo(403);
        }
        int own = client.post()
                .uri("/v1/echo/hi")
                .cookie("gw-session", session)
                .header(HttpHeaders.ORIGIN, A)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .returnResult(String.class)
                .getStatus()
                .value();
        assertThat(own).isNotIn(403, 415);
    }

    @Test
    @DisplayName(
            "GIVEN a session WHEN a REST body is text/plain, a form or untyped SHOULD answer 415 with the right Origin")
    void given_session_when_formLikeBody_should_answer415() throws Exception {
        String session = signIn("alice");

        for (String type :
                new String[] {"text/plain", "application/x-www-form-urlencoded", "multipart/form-data; boundary=x"}) {
            client.post()
                    .uri("/v1/echo/hi")
                    .cookie("gw-session", session)
                    .header(HttpHeaders.ORIGIN, A)
                    .header(HttpHeaders.CONTENT_TYPE, type)
                    .bodyValue("a=b")
                    .exchange()
                    .expectStatus()
                    .isEqualTo(415);
        }
        client.post()
                .uri("/v1/echo/hi")
                .cookie("gw-session", session)
                .header(HttpHeaders.ORIGIN, A)
                .header(HttpHeaders.CONTENT_LENGTH, "3")
                .bodyValue("a=b".getBytes(StandardCharsets.UTF_8))
                .exchange()
                .expectStatus()
                .value(status -> assertThat(status).isIn(415, 400));
    }

    @Test
    @DisplayName(
            "GIVEN a session WHEN calling gRPC-Web SHOULD answer 403 without the application's Origin and reach the backend with it, and the backend never sees a cookie")
    void given_session_when_grpcWeb_should_checkOriginAndKeepCookieFromBackend() throws Exception {
        String session = signIn("alice");

        client.post()
                .uri("/lib.Echo/Keys")
                .cookie("gw-session", session)
                .header(HttpHeaders.CONTENT_TYPE, "application/grpc-web+proto")
                .bodyValue(EMPTY_GRPC_WEB_FRAME)
                .exchange()
                .expectStatus()
                .isEqualTo(403);
        client.post()
                .uri("/lib.Echo/Keys")
                .cookie("gw-session", session)
                .header(HttpHeaders.ORIGIN, EVIL)
                .header(HttpHeaders.CONTENT_TYPE, "application/grpc-web+proto")
                .bodyValue(EMPTY_GRPC_WEB_FRAME)
                .exchange()
                .expectStatus()
                .isEqualTo(403);

        byte[] reply = client.post()
                .uri("/lib.Echo/Keys")
                .cookie("gw-session", session)
                .header(HttpHeaders.ORIGIN, A)
                .header(HttpHeaders.CONTENT_TYPE, "application/grpc-web+proto")
                .bodyValue(EMPTY_GRPC_WEB_FRAME)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .returnResult()
                .getResponseBody();
        String frames = new String(reply, StandardCharsets.ISO_8859_1);
        assertThat(frames).contains("authorization").contains("x-principal").doesNotContain("cookie");
    }

    @Test
    @DisplayName(
            "GIVEN a session WHEN posting a GraphQL query SHOULD answer 403 without the application's Origin and reach the backend with it, and the backend never sees a cookie")
    void given_session_when_graphql_should_checkOriginAndKeepCookieFromBackend() throws Exception {
        String session = signIn("alice");
        Map<String, String> query = Map.of("query", "{ keys(text: \"x\") { text } }");

        client.post()
                .uri("/graphql")
                .cookie("gw-session", session)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(query)
                .exchange()
                .expectStatus()
                .isEqualTo(403);
        client.post()
                .uri("/graphql")
                .cookie("gw-session", session)
                .header(HttpHeaders.ORIGIN, B)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(query)
                .exchange()
                .expectStatus()
                .isEqualTo(403);

        client.post()
                .uri("/graphql")
                .cookie("gw-session", session)
                .header(HttpHeaders.ORIGIN, A)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(query)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.data.keys.text")
                .value(text -> assertThat((String) text)
                        .contains("authorization")
                        .contains("x-principal")
                        .doesNotContain("cookie"));
    }

    @Test
    @DisplayName(
            "GIVEN a session of application a WHEN application b's origin reads REST, gRPC-Web, GraphQL or /bff/session SHOULD answer 403, and a's own origin or none gets through")
    void given_session_when_readFromOtherApplicationsOrigin_should_forbid() throws Exception {
        String session = signIn("alice");
        String graphqlGet = "/graphql?query=%7B%20keys(text%3A%20%22x%22)%20%7B%20text%20%7D%20%7D";

        for (String origin : new String[] {B, "null", EVIL}) {
            for (String uri : new String[] {"/v1/keys/x", "/bff/session", "/bff/providers?app=a", graphqlGet}) {
                client.get()
                        .uri(uri)
                        .cookie("gw-session", session)
                        .header(HttpHeaders.ORIGIN, origin)
                        .exchange()
                        .expectStatus()
                        .isEqualTo(403)
                        .expectBody()
                        .json("{\"error\":\"origin not allowed\"}");
            }
            client.post()
                    .uri("/lib.Echo/Keys")
                    .cookie("gw-session", session)
                    .header(HttpHeaders.ORIGIN, origin)
                    .header(HttpHeaders.CONTENT_TYPE, "application/grpc-web+proto")
                    .bodyValue(EMPTY_GRPC_WEB_FRAME)
                    .exchange()
                    .expectStatus()
                    .isEqualTo(403);
        }
        for (String origin : new String[] {A, null}) {
            for (String uri : new String[] {"/v1/keys/x", "/bff/session", "/bff/providers?app=a"}) {
                WebTestClient.RequestHeadersSpec<?> request =
                        client.get().uri(uri).cookie("gw-session", session);
                if (origin != null) request = request.header(HttpHeaders.ORIGIN, origin);
                request.exchange().expectStatus().isOk();
            }
        }
        client.get()
                .uri("/bff/session")
                .cookie("gw-session", session)
                .header(HttpHeaders.ORIGIN, A)
                .exchange()
                .expectBody()
                .jsonPath("$.application")
                .isEqualTo("a");
    }

    @Test
    @DisplayName(
            "GIVEN a CORS preflight without a cookie WHEN it names application b's origin SHOULD NOT be refused by the origin rule")
    void given_preflight_when_noCookie_should_notBeForbidden() {
        client.options()
                .uri("/v1/keys/x")
                .header(HttpHeaders.ORIGIN, B)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange()
                .expectStatus()
                .value(status -> assertThat(status).isNotEqualTo(403));
    }

    @Test
    @DisplayName("GIVEN a session WHEN a REST call goes to the backend SHOULD send the backend no Cookie header")
    void given_session_when_restCall_should_notSendCookieToBackend() throws Exception {
        String session = signIn("alice");

        client.get()
                .uri("/v1/keys/x")
                .cookie("gw-session", session)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.text")
                .value(text -> assertThat((String) text)
                        .contains("authorization")
                        .contains("x-principal")
                        .doesNotContain("cookie"));
    }

    @Test
    @DisplayName("GIVEN a session WHEN a WebSocket handshake has a bad or no Origin SHOULD answer 403")
    void given_session_when_webSocketHandshake_should_checkOrigin() throws Exception {
        String session = signIn("alice");

        client.get()
                .uri("/graphql/ws")
                .cookie("gw-session", session)
                .header(HttpHeaders.CONNECTION, "Upgrade")
                .header(HttpHeaders.UPGRADE, "websocket")
                .exchange()
                .expectStatus()
                .isEqualTo(403);
        client.get()
                .uri("/graphql/ws")
                .cookie("gw-session", session)
                .header(HttpHeaders.CONNECTION, "Upgrade")
                .header(HttpHeaders.UPGRADE, "websocket")
                .header(HttpHeaders.ORIGIN, EVIL)
                .exchange()
                .expectStatus()
                .isEqualTo(403);
        int own = client.get()
                .uri("/graphql/ws")
                .cookie("gw-session", session)
                .header(HttpHeaders.CONNECTION, "Upgrade")
                .header(HttpHeaders.UPGRADE, "websocket")
                .header(HttpHeaders.ORIGIN, A)
                .exchange()
                .returnResult(String.class)
                .getStatus()
                .value();
        assertThat(own).isNotEqualTo(403);
    }

    @Test
    @DisplayName("GIVEN a bearer caller WHEN posting with no Origin and a plain content type SHOULD pass the filter")
    void given_bearer_when_posting_should_beExempt() {
        int status = client.post()
                .uri("/v1/echo/hi")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + STUB.accessToken("carol"))
                .header(HttpHeaders.CONTENT_TYPE, "text/plain")
                .bodyValue("x")
                .exchange()
                .returnResult(String.class)
                .getStatus()
                .value();

        assertThat(status).isNotIn(403, 415, 401);
    }

    @Test
    @DisplayName(
            "GIVEN two cookie users WHEN both call a cacheable route SHOULD give each their own response, never the other's cached one")
    void given_twoCookieUsers_when_cacheableRoute_should_notShareResponses() throws Exception {
        String alice = signIn("alice");
        String bob = signIn("bob");

        for (int round = 0; round < 2; round++) {
            WebTestClient.ResponseSpec first = client.get()
                    .uri("/v1/echo/shared")
                    .cookie("gw-session", alice)
                    .exchange();
            first.expectStatus().isOk();
            first.expectHeader()
                    .value(HttpHeaders.CACHE_CONTROL, v -> assertThat(v).contains("private"));
            first.expectBody()
                    .jsonPath("$.text")
                    .value(text -> assertThat((String) text).endsWith("|alice"));

            client.get()
                    .uri("/v1/echo/shared")
                    .cookie("gw-session", bob)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.text")
                    .value(text -> assertThat((String) text).endsWith("|bob").doesNotContain("alice"));
        }
    }

    @Test
    @DisplayName(
            "GIVEN a layer behind the chain that sets the session cookie SHOULD never reach the browser, while other cookies do")
    void given_layerSettingSessionCookie_when_responding_should_dropIt() throws Exception {
        String session = signIn("alice");

        EntityExchangeResult<byte[]> result = client.get()
                .uri("/v1/echo/evil")
                .cookie("gw-session", session)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .returnResult();

        assertThat(result.getResponseCookies().keySet())
                .doesNotContain("gw-session")
                .contains("theme");
        assertThat(result.getResponseHeaders().get(HttpHeaders.SET_COOKIE))
                .noneMatch(value -> value.startsWith("gw-session="));
    }

    @Test
    @DisplayName(
            "GIVEN a session WHEN logging out SHOULD need its application's Origin, with a form post allowed, and so should a request with no session")
    void given_session_when_loggingOut_should_requireOrigin() throws Exception {
        String session = signIn("alice");

        client.post()
                .uri("/bff/logout")
                .cookie("gw-session", session)
                .header(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded")
                .exchange()
                .expectStatus()
                .isEqualTo(403);
        client.post()
                .uri("/bff/logout")
                .cookie("gw-session", session)
                .header(HttpHeaders.ORIGIN, B)
                .header(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded")
                .exchange()
                .expectStatus()
                .isEqualTo(403);
        client.post().uri("/bff/logout").exchange().expectStatus().isEqualTo(403);
        client.post()
                .uri("/bff/logout")
                .header(HttpHeaders.ORIGIN, EVIL)
                .exchange()
                .expectStatus()
                .isEqualTo(403);

        client.post()
                .uri("/bff/logout")
                .cookie("gw-session", session)
                .header(HttpHeaders.ORIGIN, A)
                .header(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded")
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
