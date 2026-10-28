package dev.appiaworks.shiguchi.bff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.appiaworks.shiguchi.bff.BffProviders.Refreshed;
import dev.appiaworks.shiguchi.bff.BffProviders.TokenRefusedException;
import dev.appiaworks.shiguchi.bff.BffTestFixtures.MutableClock;
import dev.appiaworks.shiguchi.bff.session.BffSessionKeys;
import dev.appiaworks.shiguchi.bff.session.MemoryBffSessionStore;
import dev.appiaworks.shiguchi.security.JwtPrincipalConverter;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

@DisplayName("The session authenticator with token refresh")
class BffRefreshAuthenticationTest {

    private MutableClock clock;
    private MemoryBffSessionStore store;
    private BffSessionAuthenticator authenticator;
    private final AtomicInteger endpointCalls = new AtomicInteger();
    private final AtomicReference<Supplier<Mono<Refreshed>>> endpoint = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        clock = new MutableClock(BffTestFixtures.T0);
        store = new MemoryBffSessionStore(clock);
        BffProperties properties = BffTestFixtures.properties();
        endpoint.set(() -> Mono.just(new Refreshed("access-2", "id-2", "refresh-2")));
        ReactiveJwtDecoder decoder = token -> Mono.just(Jwt.withTokenValue(token)
                .header("alg", "RS256")
                .subject("alice")
                .issuedAt(clock.instant().minusSeconds(60))
                .expiresAt(clock.instant().plus(Duration.ofMinutes(10)))
                .build());
        BffTokenRefresher refresher = new BffTokenRefresher(
                store,
                (provider, refreshToken) -> {
                    endpointCalls.incrementAndGet();
                    return Mono.defer(endpoint.get());
                },
                provider -> Mono.just(decoder),
                provider -> Mono.just(decoder),
                new JwtPrincipalConverter(null)::convert,
                properties,
                clock,
                null);
        authenticator = new DefaultBffSessionAuthenticator(
                store,
                new BffCookies(properties),
                properties,
                provider -> Mono.just(decoder),
                new JwtPrincipalConverter(null)::convert,
                clock,
                refresher);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private String seed(Duration tokenLeft) {
        String cookie = BffSessionKeys.newCookieValue();
        store.save(
                        BffTestFixtures.session(
                                cookie,
                                clock.instant(),
                                clock.instant(),
                                clock.instant().plus(tokenLeft)),
                        Duration.ofDays(100))
                .block();
        return cookie;
    }

    private static MockServerWebExchange exchange(String cookie) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/x").cookie(new HttpCookie("__Host-gw-session", cookie)));
    }

    @Test
    @DisplayName("GIVEN an expired access token WHEN authenticating SHOULD refresh and authenticate with the new token")
    void given_expired_token_when_authenticating_should_refresh_and_authenticate() {
        String cookie = seed(Duration.ofSeconds(-1));

        Authentication auth = authenticator.authenticate(exchange(cookie)).block();

        assertThat(((JwtAuthenticationToken) auth).getToken().getTokenValue()).isEqualTo("access-2");
        assertThat(store.find(BffSessionKeys.key(cookie)).block().refreshToken())
                .isEqualTo("refresh-2");
        assertThat(endpointCalls).hasValue(1);
    }

    @Test
    @DisplayName("GIVEN a token far from expiry WHEN authenticating SHOULD not touch the issuer")
    void given_token_far_from_expiry_when_authenticating_should_not_call_issuer() {
        String cookie = seed(Duration.ofMinutes(5));

        Authentication auth = authenticator.authenticate(exchange(cookie)).block();

        assertThat(((JwtAuthenticationToken) auth).getToken().getTokenValue()).isEqualTo("access-token");
        assertThat(endpointCalls).hasValue(0);
    }

    @Test
    @DisplayName(
            "GIVEN a token inside the skew and an unreachable issuer WHEN authenticating SHOULD serve the old token")
    void given_token_inside_skew_and_unreachable_issuer_when_authenticating_should_serve_old_token() {
        String cookie = seed(Duration.ofSeconds(30));
        endpoint.set(() -> Mono.error(new IllegalStateException("down")));

        Authentication auth = authenticator.authenticate(exchange(cookie)).block();

        assertThat(((JwtAuthenticationToken) auth).getToken().getTokenValue()).isEqualTo("access-token");
    }

    @Test
    @DisplayName(
            "GIVEN an expired token and an unreachable issuer WHEN authenticating SHOULD fail as unavailable, not anonymous")
    void given_expired_token_and_unreachable_issuer_when_authenticating_should_fail_unavailable() {
        String cookie = seed(Duration.ofSeconds(-1));
        endpoint.set(() -> Mono.error(new IllegalStateException("down")));

        assertThatThrownBy(() -> authenticator.authenticate(exchange(cookie)).block())
                .isInstanceOf(BffSessionUnavailableException.class);
        assertThat(store.find(BffSessionKeys.key(cookie)).block()).isNotNull();
    }

    @Test
    @DisplayName(
            "GIVEN a refresh token the issuer refuses WHEN authenticating SHOULD be anonymous and delete the session")
    void given_refused_refresh_token_when_authenticating_should_be_anonymous() {
        String cookie = seed(Duration.ofSeconds(-1));
        endpoint.set(() -> Mono.error(new TokenRefusedException(400)));

        assertThat(authenticator.authenticate(exchange(cookie)).block()).isNull();
        assertThat(store.find(BffSessionKeys.key(cookie)).block()).isNull();
    }

    @Test
    @DisplayName(
            "GIVEN a request read by several security readers WHEN its token needs refreshing SHOULD refresh once, even on failure")
    void given_several_readers_when_token_needs_refresh_should_refresh_once() {
        BffSecurityContextRepository repository = new BffSecurityContextRepository(authenticator);
        String ok = seed(Duration.ofSeconds(-1));
        MockServerWebExchange first = exchange(ok);

        repository.load(first).block();
        repository.load(first).block();
        repository.load(first).block();

        assertThat(endpointCalls).hasValue(1);

        String down = seed(Duration.ofSeconds(-1));
        endpoint.set(() -> Mono.error(new IllegalStateException("down")));
        MockServerWebExchange second = exchange(down);
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> repository.load(second).block())
                    .isInstanceOf(BffSessionUnavailableException.class);
        }
        assertThat(endpointCalls).hasValue(2);
    }

    @Test
    @DisplayName("GIVEN a failing session WHEN the filter wraps the chain SHOULD answer 503 with Retry-After 1")
    void given_unavailable_session_when_filter_wraps_chain_should_answer_503() {
        BffUnavailableFilter filter = new BffUnavailableFilter();
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/v1/x"));

        filter.filter(
                        exchange,
                        e -> Mono.error(new IllegalStateException("wrapped", new BffSessionUnavailableException("x"))))
                .block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("1");
    }

    @Test
    @DisplayName("GIVEN another failure WHEN the filter wraps the chain SHOULD let it through")
    void given_other_failure_when_filter_wraps_chain_should_let_it_through() {
        BffUnavailableFilter filter = new BffUnavailableFilter();
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/v1/x"));

        assertThatThrownBy(() -> filter.filter(exchange, e -> Mono.error(new IllegalStateException("boom")))
                        .block())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("GIVEN refresh settings WHEN validating SHOULD reject a skew of 5 minutes and a lease below 8 seconds")
    void given_bad_refresh_settings_when_validating_should_reject() {
        BffProperties properties = BffTestFixtures.properties();
        properties.setRefreshSkew(Duration.ofMinutes(5));
        properties.setRefreshLease(Duration.ofSeconds(7));

        assertThatThrownBy(() -> BffConfiguration.validate(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refresh-skew")
                .hasMessageContaining("refresh-lease")
                .hasMessageContaining("at least 8 seconds");

        properties.setRefreshSkew(Duration.ZERO);
        properties.setRefreshLease(Duration.ofSeconds(8));
        BffConfiguration.validate(properties);
    }
}
