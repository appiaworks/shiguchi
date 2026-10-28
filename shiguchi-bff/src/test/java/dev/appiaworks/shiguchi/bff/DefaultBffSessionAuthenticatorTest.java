package dev.appiaworks.shiguchi.bff;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.bff.BffTestFixtures.MutableClock;
import dev.appiaworks.shiguchi.bff.session.BffLogin;
import dev.appiaworks.shiguchi.bff.session.BffSession;
import dev.appiaworks.shiguchi.bff.session.BffSessionKeys;
import dev.appiaworks.shiguchi.bff.session.BffSessionStore;
import dev.appiaworks.shiguchi.bff.session.MemoryBffSessionStore;
import dev.appiaworks.shiguchi.security.CookieAuthenticated;
import dev.appiaworks.shiguchi.security.JwtPrincipalConverter;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpCookie;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

@DisplayName("DefaultBffSessionAuthenticator: session cookie to caller")
class DefaultBffSessionAuthenticatorTest {

    private static final Duration HUNDRED_DAYS = Duration.ofDays(100);

    private MutableClock clock;
    private MemoryBffSessionStore memory;
    private CountingStore store;
    private BffProperties properties;
    private AtomicInteger decodes;
    private boolean decoderFails;
    private DefaultBffSessionAuthenticator authenticator;

    /** Counts touch calls; delegates everything. */
    private static final class CountingStore implements BffSessionStore {
        final BffSessionStore delegate;
        final AtomicInteger touches = new AtomicInteger();

        CountingStore(BffSessionStore delegate) {
            this.delegate = delegate;
        }

        public Mono<Void> save(BffSession session, Duration ttl) {
            return delegate.save(session, ttl);
        }

        public Mono<BffSession> find(String key) {
            return delegate.find(key);
        }

        public Mono<Void> touch(String key, Instant lastSeen, Duration ttl) {
            touches.incrementAndGet();
            return delegate.touch(key, lastSeen, ttl);
        }

        public Mono<Void> delete(String key) {
            return delegate.delete(key);
        }

        public Mono<Void> saveLogin(BffLogin login, Duration ttl) {
            return delegate.saveLogin(login, ttl);
        }

        public Mono<BffLogin> takeLogin(String state) {
            return delegate.takeLogin(state);
        }
    }

    @BeforeEach
    void setUp() {
        clock = new MutableClock(BffTestFixtures.T0);
        memory = new MemoryBffSessionStore(clock);
        store = new CountingStore(memory);
        properties = BffTestFixtures.properties();
        decodes = new AtomicInteger();
        decoderFails = false;
        ReactiveJwtDecoder decoder = token -> {
            decodes.incrementAndGet();
            if (decoderFails) return Mono.error(new BadJwtException("bad signature"));
            return Mono.just(Jwt.withTokenValue(token)
                    .header("alg", "RS256")
                    .subject("alice")
                    .issuedAt(clock.instant().minusSeconds(60))
                    .expiresAt(clock.instant().plus(Duration.ofMinutes(10)))
                    .build());
        };
        authenticator = new DefaultBffSessionAuthenticator(
                store,
                new BffCookies(properties),
                properties,
                provider -> Mono.just(decoder),
                new JwtPrincipalConverter(null)::convert,
                clock);
    }

    @AfterEach
    void tearDown() {
        memory.close();
    }

    private String seed(Instant created, Instant lastSeen, Instant tokenExp) {
        String cookie = BffSessionKeys.newCookieValue();
        memory.save(BffTestFixtures.session(cookie, created, lastSeen, tokenExp), HUNDRED_DAYS)
                .block();
        return cookie;
    }

    private static MockServerWebExchange exchange(String path, String cookieValue) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get(path);
        if (cookieValue != null) request.cookie(new HttpCookie("__Host-gw-session", cookieValue));
        return MockServerWebExchange.from(request);
    }

    @Test
    @DisplayName(
            "GIVEN a valid session WHEN authenticating SHOULD return the converter's token with the session handle and application")
    void given_validSession_when_authenticating_should_returnCookieAuthenticatedToken() {
        String cookie = seed(clock.instant(), clock.instant(), clock.instant().plus(Duration.ofMinutes(5)));

        Authentication auth =
                authenticator.authenticate(exchange("/v1/x", cookie)).block();

        assertThat(auth).isInstanceOf(JwtAuthenticationToken.class);
        assertThat(auth.getName()).isEqualTo("alice");
        assertThat(((JwtAuthenticationToken) auth).getToken().getTokenValue()).isEqualTo("access-token");
        assertThat(CookieAuthenticated.handle(auth)).isEqualTo(BffSessionKeys.key(cookie));
        assertThat(CookieAuthenticated.application(auth)).isEqualTo("eve");
    }

    @Test
    @DisplayName("GIVEN no session cookie WHEN authenticating SHOULD be empty and read no session")
    void given_noCookie_when_authenticating_should_beEmpty() {
        assertThat(authenticator.authenticate(exchange("/v1/x", null)).block()).isNull();
        assertThat(decodes).hasValue(0);
    }

    @Test
    @DisplayName("GIVEN a session past its absolute timeout WHEN authenticating SHOULD be empty and delete the session")
    void given_absoluteTimeoutPassed_when_authenticating_should_deleteSession() {
        String cookie = seed(clock.instant(), clock.instant(), clock.instant().plus(Duration.ofDays(2)));
        clock.advance(Duration.ofHours(12));
        // keep the idle timeout out of the way
        memory.touch(BffSessionKeys.key(cookie), clock.instant(), HUNDRED_DAYS).block();

        assertThat(authenticator.authenticate(exchange("/v1/x", cookie)).block())
                .isNull();
        assertThat(memory.find(BffSessionKeys.key(cookie)).block()).isNull();
    }

    @Test
    @DisplayName("GIVEN a session idle for the idle timeout WHEN authenticating SHOULD be empty and delete the session")
    void given_idleTimeoutPassed_when_authenticating_should_deleteSession() {
        String cookie = seed(clock.instant(), clock.instant(), clock.instant().plus(Duration.ofDays(2)));
        clock.advance(Duration.ofMinutes(30));

        assertThat(authenticator.authenticate(exchange("/v1/x", cookie)).block())
                .isNull();
        assertThat(memory.find(BffSessionKeys.key(cookie)).block()).isNull();
    }

    @Test
    @DisplayName(
            "GIVEN a session whose access token has expired WHEN authenticating SHOULD be empty and keep the session")
    void given_accessTokenExpired_when_authenticating_should_keepSession() {
        String cookie = seed(clock.instant(), clock.instant(), clock.instant().plus(Duration.ofMinutes(1)));
        clock.advance(Duration.ofMinutes(2));

        assertThat(authenticator.authenticate(exchange("/v1/x", cookie)).block())
                .isNull();
        assertThat(memory.find(BffSessionKeys.key(cookie)).block()).isNotNull();
        assertThat(decodes).hasValue(0);
    }

    @Test
    @DisplayName(
            "GIVEN two calls within a tenth of the idle timeout WHEN authenticating SHOULD write activity at most once")
    void given_callsWithinTouchInterval_when_authenticating_should_touchOnce() {
        String cookie = seed(clock.instant(), clock.instant(), clock.instant().plus(Duration.ofMinutes(9)));

        clock.advance(Duration.ofMinutes(1));
        authenticator.authenticate(exchange("/v1/x", cookie)).block();
        authenticator.authenticate(exchange("/v1/x", cookie)).block();
        assertThat(store.touches).hasValue(0);

        clock.advance(Duration.ofMinutes(3));
        authenticator.authenticate(exchange("/v1/x", cookie)).block();
        authenticator.authenticate(exchange("/v1/x", cookie)).block();
        assertThat(store.touches).hasValue(1);
        assertThat(memory.find(BffSessionKeys.key(cookie)).block().lastSeen()).isEqualTo(clock.instant());
    }

    @Test
    @DisplayName("GIVEN two requests of one session WHEN authenticating SHOULD decode the access token once")
    void given_twoRequests_when_authenticating_should_decodeOnce() {
        String cookie = seed(clock.instant(), clock.instant(), clock.instant().plus(Duration.ofMinutes(5)));

        authenticator.authenticate(exchange("/v1/x", cookie)).block();
        authenticator.authenticate(exchange("/v1/x", cookie)).block();

        assertThat(decodes).hasValue(1);
    }

    @Test
    @DisplayName("GIVEN a token the decoder rejects WHEN authenticating SHOULD be empty rather than fail")
    void given_decoderError_when_authenticating_should_beEmpty() {
        String cookie = seed(clock.instant(), clock.instant(), clock.instant().plus(Duration.ofMinutes(5)));
        decoderFails = true;

        assertThat(authenticator.authenticate(exchange("/v1/x", cookie)).block())
                .isNull();
    }

    @Test
    @DisplayName(
            "GIVEN an unknown session under the BFF path WHEN authenticating SHOULD ask the response to clear the cookie")
    void given_unknownSessionUnderBffPath_when_authenticating_should_clearCookie() {
        MockServerWebExchange exchange = exchange("/bff/session", "stale-cookie-value");

        assertThat(authenticator.authenticate(exchange).block()).isNull();

        assertThat(exchange.getResponse().getCookies().getFirst("__Host-gw-session"))
                .isNotNull()
                .satisfies(c -> assertThat(c.getMaxAge()).isEqualTo(Duration.ZERO));
    }

    @Test
    @DisplayName("GIVEN an unknown session on a proxied path WHEN authenticating SHOULD NOT add a Set-Cookie")
    void given_unknownSessionOnProxiedPath_when_authenticating_should_notSetCookie() {
        MockServerWebExchange exchange = exchange("/v1/echo/hi", "stale-cookie-value");

        assertThat(authenticator.authenticate(exchange).block()).isNull();

        assertThat(exchange.getResponse().getCookies()).isEmpty();
    }

    @Test
    @DisplayName("GIVEN an expired session under the BFF path WHEN authenticating SHOULD clear the cookie too")
    void given_expiredSessionUnderBffPath_when_authenticating_should_clearCookie() {
        String cookie = seed(clock.instant(), clock.instant(), clock.instant().plus(Duration.ofDays(2)));
        clock.advance(Duration.ofMinutes(31));
        MockServerWebExchange exchange = exchange("/bff/logout", cookie);

        assertThat(authenticator.authenticate(exchange).block()).isNull();

        assertThat(exchange.getResponse().getCookies().getFirst("__Host-gw-session"))
                .isNotNull();
    }
}
