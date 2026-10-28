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
import java.util.List;
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
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Session claims on {@code /bff/session} and the signed-in user's own session list
 * ({@code GET /bff/sessions}, {@code DELETE /bff/sessions/{handle}}) on the Boot 3 gateway over a
 * real port against the stub OIDC issuer, with two browsers (cookie jars) signed in as the same
 * user and a second user. Mirrors {@code spring-boot4/shiguchi-bff-starter}.
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
            "shiguchi.bff.applications.a.origins=https://a.example.com",
            "shiguchi.bff.applications.a.providers=idp",
            "shiguchi.bff.applications.a.return-to=https://a.example.com/app/",
            "shiguchi.bff.applications.a.session-claims=email,roles,tenant,nested,groups",
            "shiguchi.bff.applications.b.origins=https://b.example.com",
            "shiguchi.bff.applications.b.providers=idp",
            "shiguchi.bff.applications.b.return-to=https://b.example.com/"
        })
@DisplayName("BFF session claims and my sessions end to end (Boot 3)")
class BffSessionClaimsEndToEndTest {

    private static final StubIssuer STUB = StubIssuer.start();
    private static final Server BACKEND = BffTestBackend.start();
    private static final Path PB_FILE = BffTestBackend.writeDescriptorSet();
    private static final String ORIGIN_A = "https://a.example.com";

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

    private WebTestClient client;
    private String user;

    @Autowired
    private Environment environment;

    @BeforeEach
    void bindClient() {
        STUB.reset();
        // Every test signs in as its own user: the memory store outlives the tests.
        user = "user-" + System.nanoTime();
        STUB.subject(user);
        client = WebTestClient.bindToServer()
                .responseTimeout(Duration.ofSeconds(30))
                .baseUrl("http://localhost:" + environment.getRequiredProperty("local.server.port"))
                .build();
    }

    /** Signs in through application {@code app} as the stub's current subject; returns the session cookie value. */
    private String signIn(String app, String userAgent) throws Exception {
        String returnTo = app.equals("a") ? "https://a.example.com/app/page" : "https://b.example.com/page";
        EntityExchangeResult<byte[]> login = client.get()
                .uri("/bff/login/idp?return_to=" + returnTo)
                .exchange()
                .expectStatus()
                .isFound()
                .expectBody()
                .returnResult();
        String loginCookie = login.getResponseCookies().getFirst("gw-login").getValue();
        HttpResponse<String> authorize = BROWSER.send(
                HttpRequest.newBuilder(URI.create(login.getResponseHeaders().getFirst(HttpHeaders.LOCATION)))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        URI callback = URI.create(authorize.headers().firstValue("Location").orElseThrow());
        WebTestClient.RequestHeadersSpec<?> spec = client.get()
                .uri(callback.getRawPath() + "?" + callback.getRawQuery())
                .cookie("gw-login", loginCookie);
        if (userAgent != null) spec = spec.header(HttpHeaders.USER_AGENT, userAgent);
        EntityExchangeResult<byte[]> done =
                spec.exchange().expectStatus().isFound().expectBody().returnResult();
        return done.getResponseCookies().getFirst("gw-session").getValue();
    }

    private String signIn() throws Exception {
        return signIn("a", null);
    }

    private WebTestClient.ResponseSpec getSessions(String cookie) {
        return client.get()
                .uri("/bff/sessions")
                .cookie("gw-session", cookie)
                .header(HttpHeaders.ORIGIN, ORIGIN_A)
                .exchange();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listed(String cookie) {
        Map<String, Object> body = getSessions(cookie)
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();
        return (List<Map<String, Object>>) body.get("sessions");
    }

    private String handleOf(String viewer, String target) {
        return listed(viewer).stream()
                .filter(s -> !(Boolean) s.get("current"))
                .map(s -> (String) s.get("id"))
                .findFirst()
                .orElseThrow();
    }

    private WebTestClient.ResponseSpec delete(String cookie, String handle, String origin) {
        WebTestClient.RequestHeadersSpec<?> spec = client.delete().uri("/bff/sessions/" + handle);
        if (cookie != null) spec = spec.cookie("gw-session", cookie);
        if (origin != null) spec = spec.header(HttpHeaders.ORIGIN, origin);
        return spec.exchange();
    }

    private boolean authenticated(String cookie) {
        Map<?, ?> body = client.get()
                .uri("/bff/session")
                .cookie("gw-session", cookie)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();
        return Boolean.TRUE.equals(body.get("authenticated"));
    }

    @Test
    @DisplayName(
            "GIVEN session-claims listing roles, tenant, nested and groups WHEN reading /bff/session SHOULD return the listed claims in claims, keep unlisted ones out and drop too-deep ones")
    void given_sessionClaims_when_readingSession_should_returnListedClaims() throws Exception {
        STUB.idClaims(Map.of(
                "roles", List.of("admin", "dev"),
                "tenant", "acme",
                "groups", Map.of("team", "core", "level", 3),
                "nested", Map.of("a", Map.of("b", Map.of("c", 1))),
                "unlisted", "secret-value"));
        String session = signIn();

        client.get()
                .uri("/bff/session")
                .cookie("gw-session", session)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.authenticated")
                .isEqualTo(true)
                .jsonPath("$.claims.roles[0]")
                .isEqualTo("admin")
                .jsonPath("$.claims.roles[1]")
                .isEqualTo("dev")
                .jsonPath("$.claims.tenant")
                .isEqualTo("acme")
                .jsonPath("$.claims.groups.team")
                .isEqualTo("core")
                .jsonPath("$.claims.groups.level")
                .isEqualTo(3)
                .jsonPath("$.claims.nested")
                .doesNotExist()
                .jsonPath("$.claims.unlisted")
                .doesNotExist()
                .jsonPath("$.name")
                .doesNotExist()
                .jsonPath("$.picture")
                .doesNotExist();
    }

    @Test
    @DisplayName(
            "GIVEN an application with the default session-claims WHEN reading /bff/session SHOULD return name and email as before and no claims object")
    void given_defaultSessionClaims_when_readingSession_should_matchToday() throws Exception {
        STUB.idClaims(Map.of("name", "Alice", "email", "alice@example.com", "roles", List.of("admin")));
        String session = signIn("b", null);

        client.get()
                .uri("/bff/session")
                .cookie("gw-session", session)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.name")
                .isEqualTo("Alice")
                .jsonPath("$.email")
                .isEqualTo("alice@example.com")
                .jsonPath("$.claims")
                .doesNotExist();
    }

    @Test
    @DisplayName(
            "GIVEN one user signed in on two browsers WHEN listing sessions SHOULD return both with the caller's marked current, the user agent and no cookie value")
    void given_twoBrowsers_when_listing_should_returnBothAndMarkCurrent() throws Exception {
        String first = signIn("a", "Mozilla/5.0 (X11) First");
        String second = signIn("a", "Mozilla/5.0 (Mac) Second");

        List<Map<String, Object>> fromFirst = listed(first);
        assertThat(fromFirst).hasSize(2);
        assertThat(fromFirst.stream().filter(s -> (Boolean) s.get("current"))).hasSize(1);
        assertThat(fromFirst.stream()
                        .filter(s -> (Boolean) s.get("current"))
                        .findFirst()
                        .orElseThrow())
                .containsEntry("userAgent", "Mozilla/5.0 (X11) First")
                .containsEntry("application", "a");
        assertThat(fromFirst).extracting(s -> s.get("userAgent")).contains("Mozilla/5.0 (Mac) Second");
        assertThat(fromFirst).extracting(s -> (String) s.get("id")).doesNotHaveDuplicates();
        String json = fromFirst.toString();
        assertThat(json).doesNotContain(first).doesNotContain(second);
        assertThat(listed(second).stream().filter(s -> (Boolean) s.get("current")))
                .extracting(s -> s.get("userAgent"))
                .containsExactly("Mozilla/5.0 (Mac) Second");
    }

    @Test
    @DisplayName(
            "GIVEN two sessions of one user WHEN one ends the other by handle SHOULD end only that one and keep the caller signed in")
    void given_twoSessions_when_endingTheOther_should_endOnlyIt() throws Exception {
        String first = signIn();
        String second = signIn();

        delete(first, handleOf(first, second), ORIGIN_A).expectStatus().isOk();

        assertThat(authenticated(second)).isFalse();
        assertThat(authenticated(first)).isTrue();
        assertThat(listed(first)).hasSize(1);
    }

    @Test
    @DisplayName("GIVEN a session WHEN ending its own handle SHOULD act as a logout: session gone and cookie cleared")
    void given_ownHandle_when_deleting_should_logOut() throws Exception {
        String first = signIn();
        signIn();
        String own = listed(first).stream()
                .filter(s -> (Boolean) s.get("current"))
                .map(s -> (String) s.get("id"))
                .findFirst()
                .orElseThrow();

        EntityExchangeResult<byte[]> result =
                delete(first, own, ORIGIN_A).expectStatus().isOk().expectBody().returnResult();

        assertThat(result.getResponseCookies().getFirst("gw-session").getValue())
                .isEmpty();
        assertThat(authenticated(first)).isFalse();
    }

    @Test
    @DisplayName(
            "GIVEN another user's session WHEN its handle is presented by a different user SHOULD answer 404 and leave it signed in")
    void given_otherUsersHandle_when_deleting_should_answer404() throws Exception {
        STUB.subject(user + "-alice");
        String alice1 = signIn();
        String alice2 = signIn();
        String aliceHandle = handleOf(alice1, alice2);
        STUB.subject(user + "-bob");
        String bob = signIn();

        assertThat(listed(bob)).hasSize(1);
        delete(bob, aliceHandle, ORIGIN_A).expectStatus().isNotFound();
        delete(bob, "not-a-handle", ORIGIN_A).expectStatus().isNotFound();
        // The store key is never a handle either.
        assertThat(authenticated(alice2)).isTrue();
        assertThat(authenticated(alice1)).isTrue();
    }

    @Test
    @DisplayName(
            "GIVEN a delete WHEN the Origin is missing or belongs to another application, or the caller has no cookie or sends a bearer token SHOULD refuse it and end nothing")
    void given_unsafeDelete_when_notProtected_should_refuse() throws Exception {
        String first = signIn();
        String second = signIn();
        String handle = handleOf(first, second);

        delete(first, handle, null).expectStatus().isForbidden();
        delete(first, handle, "https://b.example.com").expectStatus().isForbidden();
        delete(first, handle, "https://evil.example.com").expectStatus().isForbidden();
        delete(null, handle, ORIGIN_A).expectStatus().isUnauthorized();
        client.delete()
                .uri("/bff/sessions/" + handle)
                .cookie("gw-session", first)
                .header(HttpHeaders.ORIGIN, ORIGIN_A)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + STUB.accessToken(user))
                .exchange()
                .expectStatus()
                .isUnauthorized();

        assertThat(authenticated(second)).isTrue();
    }

    @Test
    @DisplayName(
            "GIVEN a listing request WHEN it has no cookie, a bearer token, or a foreign Origin SHOULD answer 401 or 403")
    void given_listing_when_notCookieAuthenticated_should_refuse() throws Exception {
        String first = signIn();

        client.get().uri("/bff/sessions").exchange().expectStatus().isUnauthorized();
        client.get()
                .uri("/bff/sessions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + STUB.accessToken(user))
                .exchange()
                .expectStatus()
                .isUnauthorized();
        client.get()
                .uri("/bff/sessions")
                .cookie("gw-session", first)
                .header(HttpHeaders.ORIGIN, "https://b.example.com")
                .exchange()
                .expectStatus()
                .isForbidden();
        client.get()
                .uri("/bff/sessions")
                .cookie("gw-session", first)
                .exchange()
                .expectStatus()
                .isOk();
    }
}
