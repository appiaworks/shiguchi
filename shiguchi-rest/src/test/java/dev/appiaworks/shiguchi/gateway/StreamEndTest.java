package dev.appiaworks.shiguchi.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.security.CookieAuthenticated;
import dev.appiaworks.shiguchi.security.CookieSessionWatch;
import dev.appiaworks.shiguchi.security.TokenExpiry;
import io.grpc.Status;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class StreamEndTest {

    private static final CookieSessionWatch WATCH =
            new CookieSessionWatch(handle -> Mono.just(true), Duration.ofHours(1), null);

    private static Authentication bearer(Instant exp) {
        return new JwtAuthenticationToken(Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject("alice")
                .issuedAt(exp.minusSeconds(60))
                .expiresAt(exp)
                .build());
    }

    private static Authentication cookieCarrying(Instant exp) {
        var auth = bearer(exp);
        ((JwtAuthenticationToken) auth).setDetails(new CookieAuthenticated(null, "h1", "web"));
        return auth;
    }

    @Test
    @DisplayName(
            "GIVEN a bearer caller with a future exp WHEN signalling SHOULD end at exp with UNAUTHENTICATED token expired")
    void given_bearer_with_future_exp_when_signalling_should_end_at_exp() {
        Optional<Mono<Status>> end = StreamEnd.signal(bearer(Instant.now().plusSeconds(1)), WATCH, true);

        assertThat(end).isPresent();
        StepVerifier.create(end.get())
                .expectNextMatches(s ->
                        s.getCode() == Status.Code.UNAUTHENTICATED && TokenExpiry.REASON.equals(s.getDescription()))
                .verifyComplete();
    }

    @Test
    @DisplayName("GIVEN a bearer caller with an exp in the past WHEN signalling SHOULD end at once")
    void given_bearer_with_past_exp_when_signalling_should_end_at_once() {
        Optional<Mono<Status>> end = StreamEnd.signal(bearer(Instant.now().minusSeconds(30)), WATCH, true);

        StepVerifier.create(end.orElseThrow())
                .expectNextCount(1)
                .expectComplete()
                .verify(Duration.ofMillis(500));
    }

    @Test
    @DisplayName("GIVEN the property off WHEN signalling a bearer caller SHOULD give no signal")
    void given_property_off_when_signalling_bearer_should_give_no_signal() {
        assertThat(StreamEnd.signal(bearer(Instant.now().plusSeconds(1)), WATCH, false))
                .isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN a cookie caller carrying a short-lived access token WHEN signalling SHOULD never end at the token expiry")
    void given_cookie_caller_when_signalling_should_not_follow_token_expiry() {
        Optional<Mono<Status>> end =
                StreamEnd.signal(cookieCarrying(Instant.now().minusSeconds(30)), WATCH, true);

        assertThat(end).isPresent();
        StepVerifier.create(end.get())
                .expectSubscription()
                .expectNoEvent(Duration.ofMillis(300))
                .thenCancel()
                .verify();
    }

    @Test
    @DisplayName("GIVEN a cookie caller and no session watch WHEN signalling SHOULD give no signal, not a token expiry")
    void given_cookie_caller_without_watch_when_signalling_should_give_no_signal() {
        assertThat(StreamEnd.signal(cookieCarrying(Instant.now().minusSeconds(30)), null, true))
                .isEmpty();
    }

    @Test
    @DisplayName("GIVEN a caller that is not a JWT WHEN signalling SHOULD give no signal")
    void given_non_jwt_caller_when_signalling_should_give_no_signal() {
        assertThat(StreamEnd.signal(new TestingAuthenticationToken("u", "n"), WATCH, true))
                .isEmpty();
    }

    @Test
    @DisplayName("GIVEN a signal that fires WHEN streaming SHOULD stop the source and record the status")
    void given_signal_when_it_fires_should_stop_source_and_record_status() {
        var ended = new AtomicReference<Status>();
        Status expired = Status.UNAUTHENTICATED.withDescription(TokenExpiry.REASON);

        StepVerifier.create(StreamEnd.until(Flux.never(), Optional.of(Mono.just(expired)), ended))
                .verifyComplete();

        assertThat(ended.get()).isEqualTo(expired);
    }

    @Test
    @DisplayName("GIVEN no signal WHEN streaming SHOULD return the source unchanged")
    void given_no_signal_when_streaming_should_return_source() {
        Flux<String> source = Flux.just("a");

        assertThat(StreamEnd.until(source, Optional.empty(), new AtomicReference<>()))
                .isSameAs(source);
    }
}
