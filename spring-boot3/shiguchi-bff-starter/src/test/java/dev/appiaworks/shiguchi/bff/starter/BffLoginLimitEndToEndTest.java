package dev.appiaworks.shiguchi.bff.starter;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.bff.session.BffLogin;
import dev.appiaworks.shiguchi.bff.session.BffSession;
import dev.appiaworks.shiguchi.bff.session.BffSessionStore;
import dev.appiaworks.shiguchi.bff.session.MemoryBffSessionStore;
import dev.appiaworks.shiguchi.bff.testing.StubIssuer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
import reactor.core.publisher.Mono;

/**
 * The sign-in start limit and the single login cookie on the Boot 3 gateway over a real port
 * against an in-process stub OIDC issuer: a burst of logins from one client address is refused
 * with 429 past the burst and stores no more logins, a normal sign-in still works, and a second
 * login in the same browser replaces the first one's cookie so the first callback fails with
 * {@code login_expired}. Mirrors {@code spring-boot4/shiguchi-bff-starter}.
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
            "shiguchi.bff.login-rate-limit.requests-per-second=5",
            "shiguchi.bff.login-rate-limit.burst=5",
            "shiguchi.bff.providers.idp.client-id=gateway",
            "shiguchi.bff.providers.idp.client-secret=secret",
            "shiguchi.bff.applications.a.origins=https://a.example.com",
            "shiguchi.bff.applications.a.providers=idp",
            "shiguchi.bff.applications.a.return-to=https://a.example.com/app/"
        })
@DisplayName("BFF login limit and login cookie end to end (Boot 3)")
class BffLoginLimitEndToEndTest {

    private static final StubIssuer STUB = StubIssuer.start();

    /** Counts {@code saveLogin} calls: the number of login records the gateway stored. */
    static final class LoginCountingStore implements BffSessionStore {
        final AtomicInteger logins = new AtomicInteger();
        private final MemoryBffSessionStore delegate = new MemoryBffSessionStore();

        @Override
        public Mono<Void> save(BffSession session, Duration ttl) {
            return delegate.save(session, ttl);
        }

        @Override
        public Mono<BffSession> find(String key) {
            return delegate.find(key);
        }

        @Override
        public Mono<Void> touch(String key, Instant lastSeen, Duration ttl) {
            return delegate.touch(key, lastSeen, ttl);
        }

        @Override
        public Mono<Void> delete(String key) {
            return delegate.delete(key);
        }

        @Override
        public Mono<Void> saveLogin(BffLogin login, Duration ttl) {
            logins.incrementAndGet();
            return delegate.saveLogin(login, ttl);
        }

        @Override
        public Mono<BffLogin> takeLogin(String state) {
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

        @Bean
        ReactiveJwtDecoder testJwtDecoder() {
            return NimbusReactiveJwtDecoder.withJwkSetUri(STUB.issuer() + "/jwks")
                    .build();
        }

        @Bean
        LoginCountingStore loginCountingStore() {
            return new LoginCountingStore();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("shiguchi.bff.providers.idp.issuer-uri", STUB::issuer);
    }

    @AfterAll
    static void stop() {
        STUB.close();
    }

    private static final HttpClient BROWSER =
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    private WebTestClient client;

    @Autowired
    private Environment environment;

    @Autowired
    private LoginCountingStore store;

    @BeforeEach
    void bindClient() {
        STUB.reset();
        client = WebTestClient.bindToServer()
                .responseTimeout(Duration.ofSeconds(30))
                .baseUrl("http://localhost:" + environment.getRequiredProperty("local.server.port"))
                .build();
    }

    private record Reply(int status, HttpHeaders headers, Map<String, String> cookies, String body) {

        String location() {
            return headers.getFirst(HttpHeaders.LOCATION);
        }
    }

    private Reply get(String target, Map<String, String> cookies) {
        URI uri = URI.create(target);
        String pathAndQuery = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        WebTestClient.RequestHeadersSpec<?> spec = client.get().uri(pathAndQuery);
        cookies.forEach(spec::cookie);
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

    private static Map<String, String> merge(Map<String, String> jar, Reply reply) {
        Map<String, String> next = new HashMap<>(jar);
        reply.cookies().forEach((name, value) -> {
            if (value.isEmpty()) next.remove(name);
            else next.put(name, value);
        });
        return next;
    }

    private static String authorize(String authorizeUrl) throws Exception {
        HttpResponse<String> response = BROWSER.send(
                HttpRequest.newBuilder(URI.create(authorizeUrl)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(302);
        return response.headers().firstValue("Location").orElseThrow();
    }

    /** Starts a login, waiting out the limit (Retry-After) when the shared client address is over it. */
    private Reply startLogin(Map<String, String> jar) throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            Reply login = get("/bff/login/idp?return_to=https://a.example.com/app/page", jar);
            if (login.status() != 429) return login;
            Thread.sleep(300);
        }
        throw new AssertionError("the login limit never let a sign-in start");
    }

    @Test
    @DisplayName(
            "GIVEN rate 5 and burst 5 WHEN one client fires 20 logins at once SHOULD get 429s with Retry-After and have only a bounded number stored")
    void given_loginBurst_when_firedFromOneClient_should_refuseTheExcessAndStoreFew() {
        int before = store.logins.get();
        List<Reply> replies = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            replies.add(get("/bff/login/idp?return_to=https://a.example.com/app/page", Map.of()));
        }

        List<Reply> refused = replies.stream().filter(r -> r.status() == 429).toList();
        List<Reply> started = replies.stream().filter(r -> r.status() == 302).toList();
        assertThat(refused).isNotEmpty();
        assertThat(refused).allSatisfy(r -> {
            assertThat(r.headers().getFirst(HttpHeaders.RETRY_AFTER)).isNotNull();
            assertThat(r.cookies()).isEmpty();
            assertThat(r.body()).contains("too_many_requests");
        });
        assertThat(refused.size() + started.size()).isEqualTo(20);
        // burst 5 plus what 5 per second refills while the requests run
        assertThat(started.size()).isLessThanOrEqualTo(5 + 8);
        assertThat(store.logins.get() - before).isEqualTo(started.size());
    }

    @Test
    @DisplayName("GIVEN the limit on WHEN a user signs in normally SHOULD get a session")
    void given_limitOn_when_signingInNormally_should_getSession() throws Exception {
        Reply login = startLogin(Map.of());
        Map<String, String> jar = merge(Map.of(), login);
        Reply done = get(authorize(login.location()), jar);

        assertThat(done.status()).isEqualTo(302);
        assertThat(done.location()).isEqualTo("https://a.example.com/app/page");
        assertThat(done.cookies().get("gw-session")).isNotEmpty();
        assertThat(done.cookies().get("gw-login")).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN two sign-ins started in one browser WHEN the first callback arrives SHOULD answer login_expired, and the second SHOULD sign in")
    void given_twoTabs_when_callbacks_should_failFirstAndSignInSecond() throws Exception {
        Reply first = startLogin(Map.of());
        Map<String, String> jar = merge(Map.of(), first);
        Reply second = startLogin(jar);
        jar = merge(jar, second);
        assertThat(jar).containsOnlyKeys("gw-login");

        Reply expired = get(authorize(first.location()), jar);
        assertThat(expired.status()).isEqualTo(400);
        assertThat(expired.body()).contains("login_expired");
        assertThat(expired.cookies()).isEmpty();

        Reply done = get(authorize(second.location()), jar);
        assertThat(done.status()).isEqualTo(302);
        assertThat(done.location()).isEqualTo("https://a.example.com/app/page");
        assertThat(done.cookies().get("gw-session")).isNotEmpty();
    }
}
