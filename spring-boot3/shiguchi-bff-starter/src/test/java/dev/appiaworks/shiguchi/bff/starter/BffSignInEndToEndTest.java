package dev.appiaworks.shiguchi.bff.starter;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.bff.session.BffLogin;
import dev.appiaworks.shiguchi.bff.session.BffSession;
import dev.appiaworks.shiguchi.bff.session.BffSessionStore;
import dev.appiaworks.shiguchi.bff.session.MemoryBffSessionStore;
import dev.appiaworks.shiguchi.bff.testing.StubIssuer;
import io.grpc.Server;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

/**
 * The sign-in flow on the Boot 3 gateway over a real port against an in-process stub OIDC issuer:
 * browser-shaped navigations (redirects followed by hand, cookies carried by hand, no {@code
 * Origin} on login, callback and providers) with two applications on different origins, session
 * cookie and bearer callers reaching the same principal, logout, rotation and a nonce mismatch.
 * Mirrors {@code spring-boot4/shiguchi-bff-starter}.
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
            "shiguchi.bff.providers.idp.display-name=Corporate SSO",
            "shiguchi.bff.providers.idp.authorize-parameters.audience=api",
            "shiguchi.bff.providers.idp.authorize-parameters[resource_id]=orders",
            "shiguchi.bff.providers.other.client-id=gateway",
            "shiguchi.bff.providers.other.client-secret=secret",
            "shiguchi.bff.applications.a.origins=https://a.example.com",
            "shiguchi.bff.applications.a.providers=idp",
            "shiguchi.bff.applications.a.return-to=https://a.example.com/app/",
            "shiguchi.bff.applications.b.origins=https://b.example.com",
            "shiguchi.bff.applications.b.providers=idp,other",
            "shiguchi.bff.applications.b.return-to=https://b.example.com/"
        })
@DisplayName("BFF sign-in end to end (Boot 3)")
class BffSignInEndToEndTest {

    private static final StubIssuer STUB = StubIssuer.start();
    private static final Server BACKEND = BffTestBackend.start();
    private static final Path PB_FILE = BffTestBackend.writeDescriptorSet();

    /** Counts calls to the session store so a test can prove the bearer path never touches it. */
    static final class CountingStore implements BffSessionStore {
        final AtomicInteger calls = new AtomicInteger();
        private final MemoryBffSessionStore delegate = new MemoryBffSessionStore();

        @Override
        public Mono<Void> save(BffSession session, Duration ttl) {
            calls.incrementAndGet();
            return delegate.save(session, ttl);
        }

        @Override
        public Mono<BffSession> find(String key) {
            calls.incrementAndGet();
            return delegate.find(key);
        }

        @Override
        public Mono<Void> touch(String key, Instant lastSeen, Duration ttl) {
            calls.incrementAndGet();
            return delegate.touch(key, lastSeen, ttl);
        }

        @Override
        public Mono<Void> delete(String key) {
            calls.incrementAndGet();
            return delegate.delete(key);
        }

        @Override
        public Mono<Void> saveLogin(BffLogin login, Duration ttl) {
            calls.incrementAndGet();
            return delegate.saveLogin(login, ttl);
        }

        @Override
        public Mono<BffLogin> takeLogin(String state) {
            calls.incrementAndGet();
            return delegate.takeLogin(state);
        }

        @Override
        public Mono<Boolean> tryLease(String key, String owner, Duration ttl) {
            return delegate.tryLease(key, owner, ttl);
        }

        @Override
        public Mono<Void> releaseLease(String key, String owner) {
            return delegate.releaseLease(key, owner);
        }

        @Override
        public Mono<Boolean> replaceIfVersion(BffSession next, long expectedVersion) {
            return delegate.replaceIfVersion(next, expectedVersion);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {

        /** The bearer path: tokens the stub issuer signed. */
        @Bean
        ReactiveJwtDecoder testJwtDecoder() {
            return NimbusReactiveJwtDecoder.withJwkSetUri(STUB.issuer() + "/jwks")
                    .build();
        }

        @Bean
        CountingStore countingStore() {
            return new CountingStore();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("shiguchi.bff.providers.idp.issuer-uri", STUB::issuer);
        registry.add("shiguchi.bff.providers.other.issuer-uri", STUB::issuer);
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

    @Autowired
    private CountingStore store;

    @BeforeEach
    void bindClient() {
        STUB.reset();
        client = WebTestClient.bindToServer()
                .responseTimeout(Duration.ofSeconds(30))
                .baseUrl("http://localhost:" + environment.getRequiredProperty("local.server.port"))
                .build();
    }

    /** A response reduced to what a browser keeps: status, headers and cookie values. */
    private record Reply(int status, HttpHeaders headers, Map<String, String> cookies, String body) {

        String location() {
            return headers.getFirst(HttpHeaders.LOCATION);
        }
    }

    /** GET of a path with query, or of an absolute URL (only its path and query are used). */
    private Reply get(String target, Map<String, String> cookies) {
        URI uri = URI.create(target);
        String pathAndQuery = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        return exchange(client.get().uri(pathAndQuery), cookies, null);
    }

    private Reply post(String path, Map<String, String> cookies, String origin) {
        return exchange(client.post().uri(path), cookies, origin);
    }

    private Reply exchange(WebTestClient.RequestHeadersSpec<?> spec, Map<String, String> cookies, String origin) {
        cookies.forEach(spec::cookie);
        if (origin != null) spec.header(HttpHeaders.ORIGIN, origin);
        EntityExchangeResult<byte[]> result = spec.exchange().expectBody().returnResult();
        Map<String, String> set = new HashMap<>();
        result.getResponseCookies()
                .forEach((name, list) -> set.put(name, list.get(0).getValue()));
        byte[] bytes = result.getResponseBody();
        return new Reply(
                result.getStatus().value(),
                result.getResponseHeaders(),
                set,
                bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8));
    }

    /** Merges Set-Cookie results into a jar the way a browser does; an empty value deletes. */
    private static Map<String, String> merge(Map<String, String> jar, Reply reply) {
        Map<String, String> next = new HashMap<>(jar);
        reply.cookies().forEach((name, value) -> {
            if (value.isEmpty()) next.remove(name);
            else next.put(name, value);
        });
        return next;
    }

    /** The issuer's step: the browser follows the authorize URL; returns the callback URL it is sent to. */
    private static String authorize(String authorizeUrl) throws Exception {
        HttpResponse<String> response = BROWSER.send(
                HttpRequest.newBuilder(URI.create(authorizeUrl)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(302);
        return response.headers().firstValue("Location").orElseThrow();
    }

    /** Logs in through application a, presenting {@code startJar}; returns the jar after the callback. */
    private Map<String, String> signIn(Map<String, String> startJar) throws Exception {
        Reply login = get("/bff/login/idp?return_to=https://a.example.com/app/page", startJar);
        assertThat(login.status()).isEqualTo(302);
        Map<String, String> jar = merge(startJar, login);
        Reply done = get(authorize(login.location()), jar);
        assertThat(done.status()).isEqualTo(302);
        assertThat(done.location()).isEqualTo("https://a.example.com/app/page");
        return merge(jar, done);
    }

    @Test
    @DisplayName(
            "GIVEN the stub issuer WHEN completing the browser flow SHOULD set a session that the gateway, the backend and /bff/session all honour")
    void given_issuer_when_completingFlow_should_establishSession() throws Exception {
        Reply login = get("/bff/login/idp?return_to=https://a.example.com/app/page&hint=google", Map.of());
        assertThat(login.status()).isEqualTo(302);
        assertThat(login.cookies()).hasSize(1);
        assertThat(login.location()).startsWith(STUB.issuer() + "/authorize?");
        String callback = authorize(login.location());
        assertThat(STUB.lastAuthorize())
                .containsEntry("provider", "google")
                .containsEntry("audience", "api")
                .containsEntry("resource_id", "orders")
                .containsEntry("client_id", "gateway")
                .containsEntry("code_challenge_method", "S256");
        assertThat(callback).contains("/bff/callback/idp?").contains("code=").contains("state=");

        Map<String, String> jar = merge(Map.of(), login);
        Reply done = get(callback, jar);
        assertThat(done.status()).isEqualTo(302);
        assertThat(done.location()).isEqualTo("https://a.example.com/app/page");
        assertThat(done.cookies().get("gw-session")).isNotEmpty();
        assertThat(done.cookies().entrySet().stream()
                        .filter(e -> e.getKey().equals("gw-login"))
                        .map(Map.Entry::getValue))
                .containsExactly("");
        Map<String, String> signedIn = merge(jar, done);
        assertThat(signedIn).containsOnlyKeys("gw-session");
        String session = signedIn.get("gw-session");

        client.get()
                .uri("/v1/echo/hi")
                .cookie("gw-session", session)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.text")
                .value(text -> assertThat((String) text).matches("Bearer [\\w-]+\\.[\\w-]+\\.[\\w-]+\\|alice"));

        client.get()
                .uri("/bff/session")
                .cookie("gw-session", session)
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectBody()
                .jsonPath("$.authenticated")
                .isEqualTo(true)
                .jsonPath("$.subject")
                .isEqualTo("alice")
                .jsonPath("$.application")
                .isEqualTo("a")
                .jsonPath("$.provider")
                .isEqualTo("idp");

        client.get()
                .uri("/v1/echo/hi")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + STUB.accessToken("alice"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.text")
                .value(text -> assertThat((String) text).endsWith("|alice"));
    }

    @Test
    @DisplayName(
            "GIVEN a bearer request and a cookie request WHEN calling SHOULD touch the session store only for the cookie")
    void given_bearerAndCookie_when_calling_should_touchStoreOnlyForCookie() throws Exception {
        Map<String, String> jar = signIn(Map.of());
        store.calls.set(0);

        client.get()
                .uri("/v1/echo/hi")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + STUB.accessToken("alice"))
                .exchange()
                .expectStatus()
                .isOk();
        assertThat(store.calls.get()).isZero();

        client.get()
                .uri("/v1/echo/hi")
                .cookie("gw-session", jar.get("gw-session"))
                .exchange()
                .expectStatus()
                .isOk();
        assertThat(store.calls.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("GIVEN a provider the application does not list WHEN starting a login SHOULD answer 403")
    void given_providerNotAllowed_when_login_should_return403() {
        assertThat(get("/bff/login/other?return_to=https://a.example.com/app/", Map.of())
                        .status())
                .isEqualTo(403);
        assertThat(get("/bff/login/other?return_to=https://b.example.com/x", Map.of())
                        .status())
                .isEqualTo(302);
    }

    @Test
    @DisplayName("GIVEN no Origin WHEN return_to belongs to application b or to nobody SHOULD pick b, or answer 400")
    void given_noOrigin_when_returnTo_should_pickApplicationFromIt() {
        Reply b = get("/bff/login/idp?return_to=https://b.example.com/", Map.of());
        assertThat(b.status()).isEqualTo(302);
        assertThat(b.location()).startsWith(STUB.issuer() + "/authorize?");

        assertThat(get("/bff/login/idp?return_to=https://evil.example.com/", Map.of())
                        .status())
                .isEqualTo(400);
        assertThat(get("/bff/login/idp?return_to=https://a.example.com/app-evil/", Map.of())
                        .status())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("GIVEN no Origin WHEN listing providers SHOULD answer for the application named by app")
    void given_noOrigin_when_listingProviders_should_useAppParameter() {
        client.get()
                .uri("/bff/providers?app=a")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(1)
                .jsonPath("$[0].id")
                .isEqualTo("idp")
                .jsonPath("$[0].displayName")
                .isEqualTo("Corporate SSO");
        client.get()
                .uri("/bff/providers?app=b")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(2);
    }

    @Test
    @DisplayName(
            "GIVEN a session WHEN logging out from its own origin SHOULD answer the end-session URL and end the session")
    void given_session_when_loggingOut_should_endSessionAndReturnUrl() throws Exception {
        Map<String, String> jar = signIn(Map.of());

        Reply out = post("/bff/logout", jar, "https://a.example.com");
        assertThat(out.status()).isEqualTo(200);
        assertThat(out.cookies()).containsEntry("gw-session", "");
        Matcher body = Pattern.compile("\"logoutUrl\"\\s*:\\s*\"([^\"]*)\"").matcher(out.body());
        assertThat(body.find()).as(out.body()).isTrue();
        String logoutUrl = body.group(1);
        assertThat(logoutUrl).startsWith(STUB.issuer() + "/logout?");
        Map<String, String> query = new HashMap<>();
        UriComponentsBuilder.fromUriString(logoutUrl)
                .build()
                .getQueryParams()
                .forEach((k, v) -> query.put(k, URLDecoder.decode(v.get(0), StandardCharsets.UTF_8)));
        assertThat(query)
                .containsEntry("post_logout_redirect_uri", "https://a.example.com/app/")
                .containsEntry("client_id", "gateway");
        String[] parts = query.get("id_token_hint").split("\\.");
        assertThat(parts).hasSize(3);
        assertThat(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8))
                .contains("\"nonce\"")
                .contains("\"sub\":\"alice\"");

        client.get()
                .uri("/v1/echo/hi")
                .cookie("gw-session", jar.get("gw-session"))
                .exchange()
                .expectStatus()
                .isUnauthorized();
        client.get()
                .uri("/bff/session")
                .cookie("gw-session", jar.get("gw-session"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.authenticated")
                .isEqualTo(false);
    }

    @Test
    @DisplayName("GIVEN a session WHEN logging out without an Origin SHOULD answer 403 and keep the session")
    void given_session_when_loggingOutWithoutOrigin_should_return403() throws Exception {
        Map<String, String> jar = signIn(Map.of());

        assertThat(post("/bff/logout", jar, null).status()).isEqualTo(403);
        client.get()
                .uri("/v1/echo/hi")
                .cookie("gw-session", jar.get("gw-session"))
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    @DisplayName("GIVEN a session cookie WHEN signing in again SHOULD issue a new cookie value and retire the old one")
    void given_existingSession_when_signingInAgain_should_rotate() throws Exception {
        Map<String, String> first = signIn(Map.of());
        String oldValue = first.get("gw-session");

        Map<String, String> second = signIn(first);
        String newValue = second.get("gw-session");

        assertThat(newValue).isNotEmpty().isNotEqualTo(oldValue);
        client.get()
                .uri("/v1/echo/hi")
                .cookie("gw-session", oldValue)
                .exchange()
                .expectStatus()
                .isUnauthorized();
        client.get()
                .uri("/v1/echo/hi")
                .cookie("gw-session", newValue)
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    @DisplayName(
            "GIVEN an ID token with a wrong nonce WHEN completing the callback SHOULD redirect with bff_error and set no session")
    void given_nonceMismatch_when_callback_should_failWithoutSession() throws Exception {
        STUB.nonceOverride("wrong");
        Reply login = get("/bff/login/idp?return_to=https://a.example.com/app/page", Map.of());
        String callback = authorize(login.location());

        Reply done = get(callback, merge(Map.of(), login));

        assertThat(done.status()).isEqualTo(302);
        assertThat(done.location()).isEqualTo("https://a.example.com/app/page?bff_error=sign_in_failed");
        assertThat(done.cookies().getOrDefault("gw-session", "")).isEmpty();
    }

    /** Starts a sign-in through application a with extra query text and follows the issuer; returns the callback reply. */
    private Reply signInWith(String extraQuery, Map<String, String> startJar) throws Exception {
        Reply login = get("/bff/login/idp?return_to=https://a.example.com/app/page" + extraQuery, startJar);
        assertThat(login.status()).isEqualTo(302);
        return get(authorize(login.location()), merge(startJar, login));
    }

    @Test
    @DisplayName(
            "GIVEN an issuer session WHEN logging in with prompt=none SHOULD forward it and sign in silently with the authentication context on /bff/session")
    void given_issuerSession_when_silentLogin_should_signInAndReportContext() throws Exception {
        STUB.amr(List.of("pwd", "otp"));
        Reply done = signInWith("&prompt=none", Map.of());

        assertThat(STUB.lastAuthorize()).containsEntry("prompt", "none");
        assertThat(done.status()).isEqualTo(302);
        assertThat(done.location()).isEqualTo("https://a.example.com/app/page");
        String session = done.cookies().get("gw-session");
        assertThat(session).isNotEmpty();
        client.get()
                .uri("/bff/session")
                .cookie("gw-session", session)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.acr")
                .isEqualTo("stub:basic")
                .jsonPath("$.amr[0]")
                .isEqualTo("pwd")
                .jsonPath("$.amr[1]")
                .isEqualTo("otp")
                .jsonPath("$.authTime")
                .isNotEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN no issuer session WHEN logging in with prompt=none SHOULD redirect with bff_error=login_required and leave the existing session alone")
    void given_noIssuerSession_when_silentLogin_should_reportLoginRequiredAndKeepSession() throws Exception {
        Map<String, String> jar = signIn(Map.of());
        STUB.issuerSession(false);

        Reply done = signInWith("&prompt=none", jar);

        assertThat(done.status()).isEqualTo(302);
        assertThat(done.location()).isEqualTo("https://a.example.com/app/page?bff_error=login_required");
        assertThat(done.cookies()).doesNotContainKey("gw-session");
        client.get()
                .uri("/v1/echo/hi")
                .cookie("gw-session", jar.get("gw-session"))
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    @DisplayName(
            "GIVEN a signed-in user WHEN stepping up with acr_values and max_age=0 SHOULD replace the session with one whose acr is the higher level")
    void given_session_when_steppingUp_should_replaceSessionWithHigherAcr() throws Exception {
        Map<String, String> jar = signIn(Map.of());
        STUB.authTimeAge(Duration.ofMinutes(20));

        Reply done = signInWith("&acr_values=urn:stub:mfa&max_age=0", jar);

        assertThat(STUB.lastAuthorize())
                .containsEntry("acr_values", "urn:stub:mfa")
                .containsEntry("max_age", "0");
        assertThat(done.status()).isEqualTo(302);
        assertThat(done.location()).isEqualTo("https://a.example.com/app/page");
        String upgraded = done.cookies().get("gw-session");
        assertThat(upgraded).isNotEmpty().isNotEqualTo(jar.get("gw-session"));
        client.get()
                .uri("/bff/session")
                .cookie("gw-session", upgraded)
                .exchange()
                .expectBody()
                .jsonPath("$.acr")
                .isEqualTo("urn:stub:mfa");
        client.get()
                .uri("/v1/echo/hi")
                .cookie("gw-session", jar.get("gw-session"))
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    @DisplayName(
            "GIVEN an issuer that returns a stale auth_time WHEN max_age was sent SHOULD fail the sign-in and set no session")
    void given_staleAuthTime_when_maxAgeSent_should_failSignIn() throws Exception {
        STUB.authTimeAge(Duration.ofMinutes(20)).honorReauth(false);

        Reply done = signInWith("&max_age=60", Map.of());

        assertThat(done.status()).isEqualTo(302);
        assertThat(done.location()).isEqualTo("https://a.example.com/app/page?bff_error=sign_in_failed");
        assertThat(done.cookies().getOrDefault("gw-session", "")).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN a prompt value outside the allowed set WHEN logging in SHOULD answer 400 without echoing the value")
    void given_invalidPrompt_when_loggingIn_should_return400WithoutEcho() {
        Reply reply = get("/bff/login/idp?return_to=https://a.example.com/app/page&prompt=zzz-secret-zzz", Map.of());

        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.body()).contains("invalid_parameter").doesNotContain("zzz-secret-zzz");
    }

    @Test
    @DisplayName("GIVEN an ID token without auth_time WHEN max_age was sent SHOULD fail the sign-in and set no session")
    void given_missingAuthTime_when_maxAgeSent_should_failSignIn() throws Exception {
        STUB.omitAuthTime(true);

        Reply done = signInWith("&max_age=60", Map.of());

        assertThat(done.status()).isEqualTo(302);
        assertThat(done.location()).isEqualTo("https://a.example.com/app/page?bff_error=sign_in_failed");
        assertThat(done.cookies().getOrDefault("gw-session", "")).isEmpty();
    }
}
