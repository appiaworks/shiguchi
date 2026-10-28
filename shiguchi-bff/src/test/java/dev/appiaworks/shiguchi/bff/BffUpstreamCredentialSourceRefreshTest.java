package dev.appiaworks.shiguchi.bff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.appiaworks.shiguchi.bff.BffProviders.Refreshed;
import dev.appiaworks.shiguchi.bff.BffProviders.TokenRefusedException;
import dev.appiaworks.shiguchi.bff.session.BffSession;
import dev.appiaworks.shiguchi.bff.session.MemoryBffSessionStore;
import dev.appiaworks.shiguchi.security.CookieAuthenticated;
import dev.appiaworks.shiguchi.security.JwtPrincipalConverter;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

@DisplayName("BffUpstreamCredentialSource: the current session token for a caller that outlives its token")
class BffUpstreamCredentialSourceRefreshTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String COOKIE = "cookie";

    private final Clock clock = Clock.systemUTC();
    private final AtomicInteger endpointCalls = new AtomicInteger();
    private final AtomicReference<Supplier<Mono<Refreshed>>> endpoint = new AtomicReference<>();
    private final ServerHttpRequest request =
            MockServerHttpRequest.get("/graphql").build();
    private MemoryBffSessionStore store;
    private BffUpstreamCredentialSource source;

    @BeforeEach
    void setUp() {
        store = new MemoryBffSessionStore(clock);
        BffProperties properties = BffTestFixtures.properties();
        properties.setRefreshSkew(Duration.ofSeconds(60));
        properties.setRefreshLease(Duration.ofMillis(400));
        endpoint.set(() -> Mono.just(new Refreshed("access-2", "id-2", "refresh-2")));
        ReactiveJwtDecoder decoder = token -> Mono.just(Jwt.withTokenValue(token)
                .header("alg", "RS256")
                .subject("alice")
                .issuedAt(clock.instant())
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
        source = new BffUpstreamCredentialSource(store, refresher, clock);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private BffSession seed(Duration tokenLeft, String refreshToken) {
        Instant now = clock.instant();
        BffSession base = BffTestFixtures.session(COOKIE, now.minusSeconds(60), now, now.plus(tokenLeft));
        BffSession session = new BffSession(
                base.key(),
                base.subject(),
                base.provider(),
                base.application(),
                base.sid(),
                base.name(),
                base.email(),
                base.picture(),
                base.accessToken(),
                base.accessTokenExpiresAt(),
                refreshToken,
                base.idToken(),
                base.created(),
                base.lastSeen(),
                base.version());
        store.save(session, Duration.ofHours(1)).block();
        return session;
    }

    /** The caller as the handshake saw it: holding {@code held}, which expires {@code tokenLeft} from now. */
    private JwtAuthenticationToken caller(BffSession session, String held, Duration tokenLeft) {
        Instant now = clock.instant();
        Jwt jwt = Jwt.withTokenValue(held)
                .header("alg", "none")
                .subject("alice")
                .issuedAt(now.minusSeconds(60))
                .expiresAt(now.plus(tokenLeft))
                .build();
        JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt, List.of(), "alice");
        auth.setDetails(new CookieAuthenticated(null, session.key(), session.application()));
        return auth;
    }

    @Test
    @DisplayName("GIVEN a caller whose token is fresh WHEN asked SHOULD send it without reading the session")
    void given_fresh_token_when_asked_should_send_held_token() {
        BffSession session = seed(Duration.ofMinutes(10), "refresh-token");
        store.delete(session.key()).block();

        String value = source.authorization(request, caller(session, "held", Duration.ofMinutes(10)))
                .block(TIMEOUT);

        assertThat(value).isEqualTo("Bearer held");
        assertThat(endpointCalls).hasValue(0);
    }

    @Test
    @DisplayName("GIVEN a caller whose token expired WHEN asked SHOULD refresh the session and send the new token")
    void given_expired_token_when_asked_should_refresh_and_send_new_token() {
        BffSession session = seed(Duration.ofSeconds(-5), "refresh-token");

        String value = source.authorization(request, caller(session, "held", Duration.ofSeconds(-5)))
                .block(TIMEOUT);

        assertThat(value).isEqualTo("Bearer access-2");
        assertThat(endpointCalls).hasValue(1);
        assertThat(store.find(session.key()).block().accessToken()).isEqualTo("access-2");
    }

    @Test
    @DisplayName("GIVEN a caller whose token is inside the skew WHEN asked SHOULD send the new token")
    void given_token_inside_skew_when_asked_should_send_new_token() {
        BffSession session = seed(Duration.ofSeconds(30), "refresh-token");

        String value = source.authorization(request, caller(session, "held", Duration.ofSeconds(30)))
                .block(TIMEOUT);

        assertThat(value).isEqualTo("Bearer access-2");
    }

    @Test
    @DisplayName(
            "GIVEN a caller with an old token and a session another request refreshed WHEN asked SHOULD send the stored token without calling the issuer")
    void given_session_already_refreshed_when_asked_should_send_stored_token() {
        BffSession session = seed(Duration.ofMinutes(10), "refresh-token");

        String value = source.authorization(request, caller(session, "held", Duration.ofSeconds(-5)))
                .block(TIMEOUT);

        assertThat(value).isEqualTo("Bearer access-token");
        assertThat(endpointCalls).hasValue(0);
    }

    @Test
    @DisplayName("GIVEN two operations at once with an expired token WHEN asked SHOULD refresh once")
    void given_concurrent_operations_when_asked_should_refresh_once() {
        BffSession session = seed(Duration.ofSeconds(-5), "refresh-token");
        JwtAuthenticationToken auth = caller(session, "held", Duration.ofSeconds(-5));

        List<String> values = reactor.core.publisher.Flux.range(0, 4)
                .flatMap(i -> source.authorization(request, auth))
                .collectList()
                .block(TIMEOUT);

        assertThat(values).containsOnly("Bearer access-2");
        assertThat(endpointCalls).hasValue(1);
    }

    @Test
    @DisplayName(
            "GIVEN an issuer that refuses the refresh token WHEN asked SHOULD fail UNAUTHENTICATED and end the session")
    void given_refused_refresh_when_asked_should_fail_unauthenticated() {
        BffSession session = seed(Duration.ofSeconds(-5), "refresh-token");
        endpoint.set(() -> Mono.error(new TokenRefusedException(400)));

        assertThatThrownBy(() -> source.authorization(request, caller(session, "held", Duration.ofSeconds(-5)))
                        .block(TIMEOUT))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
        assertThat(store.find(session.key()).block()).isNull();
    }

    @Test
    @DisplayName("GIVEN a session that is gone WHEN asked with an expired token SHOULD fail UNAUTHENTICATED")
    void given_gone_session_when_asked_should_fail_unauthenticated() {
        BffSession session = seed(Duration.ofSeconds(-5), "refresh-token");
        store.delete(session.key()).block();

        assertThatThrownBy(() -> source.authorization(request, caller(session, "held", Duration.ofSeconds(-5)))
                        .block(TIMEOUT))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
    }

    @Test
    @DisplayName("GIVEN an expired session with no refresh token WHEN asked SHOULD fail UNAUTHENTICATED")
    void given_expired_session_without_refresh_token_when_asked_should_fail_unauthenticated() {
        BffSession session = seed(Duration.ofSeconds(-5), null);

        assertThatThrownBy(() -> source.authorization(request, caller(session, "held", Duration.ofSeconds(-5)))
                        .block(TIMEOUT))
                .isInstanceOf(StatusRuntimeException.class);
        assertThat(endpointCalls).hasValue(0);
    }

    @Test
    @DisplayName(
            "GIVEN an issuer that cannot be reached WHEN asked with an expired token SHOULD fail, not send the old token")
    void given_unreachable_issuer_when_asked_with_expired_token_should_fail() {
        BffSession session = seed(Duration.ofSeconds(-5), "refresh-token");
        endpoint.set(() -> Mono.error(new IllegalStateException("connection refused")));

        assertThatThrownBy(() -> source.authorization(request, caller(session, "held", Duration.ofSeconds(-5)))
                        .block(TIMEOUT))
                .isInstanceOf(BffSessionUnavailableException.class);
    }

    @Test
    @DisplayName("GIVEN a source without a refresher WHEN asked with an expired token SHOULD send the held token")
    void given_no_refresher_when_asked_should_send_held_token() {
        BffSession session = seed(Duration.ofSeconds(-5), "refresh-token");

        String value = new BffUpstreamCredentialSource()
                .authorization(request, caller(session, "held", Duration.ofSeconds(-5)))
                .block(TIMEOUT);

        assertThat(value).isEqualTo("Bearer held");
    }
}
