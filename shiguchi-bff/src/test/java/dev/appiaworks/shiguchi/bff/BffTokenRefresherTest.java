package dev.appiaworks.shiguchi.bff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.appiaworks.shiguchi.bff.BffProviders.Refreshed;
import dev.appiaworks.shiguchi.bff.BffProviders.TokenRefusedException;
import dev.appiaworks.shiguchi.bff.session.BffLogin;
import dev.appiaworks.shiguchi.bff.session.BffSession;
import dev.appiaworks.shiguchi.bff.session.BffSessionStore;
import dev.appiaworks.shiguchi.bff.session.MemoryBffSessionStore;
import dev.appiaworks.shiguchi.security.JwtPrincipalConverter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@DisplayName("BffTokenRefresher: one refresh per session across instances")
class BffTokenRefresherTest {

    private static final Duration GENEROUS = Duration.ofSeconds(10);

    private final Clock clock = Clock.systemUTC();
    private MemoryBffSessionStore memory;
    private BffSessionStore store;
    private BffProperties properties;
    private SimpleMeterRegistry meters;
    private final AtomicInteger endpointCalls = new AtomicInteger();
    private final AtomicReference<Supplier<Mono<Refreshed>>> endpoint = new AtomicReference<>();
    private final AtomicReference<String> decodedSubject = new AtomicReference<>("alice");
    private final AtomicReference<Throwable> decodeFailure = new AtomicReference<>();
    private final AtomicReference<Throwable> warmUpFailure = new AtomicReference<>();
    private final AtomicReference<Duration> refreshedDecodeDelay = new AtomicReference<>(Duration.ZERO);
    private final AtomicReference<String> decodedIdSubject = new AtomicReference<>("alice");
    private final AtomicReference<java.util.Map<String, Object>> refreshedIdClaims =
            new AtomicReference<>(java.util.Map.of());
    private final AtomicReference<Throwable> idDecodeFailure = new AtomicReference<>();
    private final List<String> events = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** A store whose lease and compare-and-set methods are the interface defaults. */
    private static final class PlainStore implements BffSessionStore {
        final BffSessionStore delegate;

        PlainStore(BffSessionStore delegate) {
            this.delegate = delegate;
        }

        public Mono<Void> save(BffSession session, Duration ttl) {
            return delegate.save(session, ttl);
        }

        public Mono<BffSession> find(String key) {
            return delegate.find(key);
        }

        public Mono<Void> touch(String key, Instant lastSeen, Duration ttl) {
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
        memory = new MemoryBffSessionStore(clock);
        store = memory;
        properties = BffTestFixtures.properties();
        properties.setRefreshSkew(Duration.ofSeconds(60));
        properties.setRefreshLease(Duration.ofMillis(400));
        meters = new SimpleMeterRegistry();
        endpoint.set(() -> Mono.just(new Refreshed("access-2", "id-2", "refresh-2")));
    }

    @AfterEach
    void tearDown() {
        memory.close();
    }

    /** A store that records when the lease is taken, in {@link #events}. */
    private final class RecordingStore implements BffSessionStore {
        public Mono<Void> save(BffSession session, Duration ttl) {
            return memory.save(session, ttl);
        }

        public Mono<BffSession> find(String key) {
            return memory.find(key);
        }

        public Mono<Void> touch(String key, Instant lastSeen, Duration ttl) {
            return memory.touch(key, lastSeen, ttl);
        }

        public Mono<Void> delete(String key) {
            return memory.delete(key);
        }

        public Mono<Void> saveLogin(BffLogin login, Duration ttl) {
            return memory.saveLogin(login, ttl);
        }

        public Mono<BffLogin> takeLogin(String state) {
            return memory.takeLogin(state);
        }

        @Override
        public Mono<Boolean> tryLease(String key, String owner, Duration ttl) {
            events.add("lease");
            return memory.tryLease(key, owner, ttl);
        }

        @Override
        public Mono<Void> releaseLease(String key, String owner) {
            return memory.releaseLease(key, owner);
        }

        @Override
        public Mono<Boolean> replaceIfVersion(BffSession next, long expectedVersion) {
            return memory.replaceIfVersion(next, expectedVersion);
        }
    }

    private BffTokenRefresher refresher() {
        ReactiveJwtDecoder decoder = token -> {
            events.add("decode:" + token);
            boolean refreshed = token.equals("access-2");
            if (!refreshed && warmUpFailure.get() != null) return Mono.error(warmUpFailure.get());
            if (decodeFailure.get() != null) return Mono.error(decodeFailure.get());
            Mono<Jwt> jwt = Mono.just(Jwt.withTokenValue(token)
                    .header("alg", "RS256")
                    .subject(decodedSubject.get())
                    .issuedAt(clock.instant())
                    .expiresAt(clock.instant().plus(Duration.ofMinutes(10)))
                    .build());
            return refreshed ? jwt.delaySubscription(refreshedDecodeDelay.get()) : jwt;
        };
        ReactiveJwtDecoder idDecoder = token -> {
            if (idDecodeFailure.get() != null) return Mono.error(idDecodeFailure.get());
            return Mono.just(Jwt.withTokenValue(token)
                    .header("alg", "RS256")
                    .subject(decodedIdSubject.get())
                    .claims(c -> c.putAll(refreshedIdClaims.get()))
                    .issuedAt(clock.instant())
                    .expiresAt(clock.instant().plus(Duration.ofMinutes(10)))
                    .build());
        };
        return new BffTokenRefresher(
                store,
                (provider, refreshToken) -> {
                    endpointCalls.incrementAndGet();
                    return Mono.defer(endpoint.get());
                },
                provider -> Mono.just(decoder),
                provider -> Mono.just(idDecoder),
                new JwtPrincipalConverter(null)::convert,
                properties,
                clock,
                meters);
    }

    /** A session whose token expires {@code in} from now (negative: already expired). */
    private BffSession seed(Duration in) {
        Instant now = clock.instant();
        BffSession session = BffTestFixtures.session("cookie", now.minusSeconds(60), now, now.plus(in));
        memory.save(session, Duration.ofHours(1)).block();
        return session;
    }

    private double count(String outcome) {
        var counter = meters.find("gateway.bff.refresh").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName("GIVEN a token inside the skew WHEN refreshed SHOULD persist the new tokens and bump the version")
    void given_token_inside_skew_when_refreshed_should_persist_new_tokens() {
        BffSession old = seed(Duration.ofSeconds(30));

        BffSession fresh = refresher().refresh(old, false).block(GENEROUS);

        assertThat(fresh.accessToken()).isEqualTo("access-2");
        assertThat(fresh.refreshToken()).isEqualTo("refresh-2");
        assertThat(fresh.idToken()).isEqualTo("id-2");
        assertThat(fresh.version()).isEqualTo(old.version() + 1);
        assertThat(fresh.accessTokenExpiresAt()).isAfter(clock.instant().plus(Duration.ofMinutes(9)));
        assertThat(memory.find(old.key()).block()).isEqualTo(fresh);
        assertThat(endpointCalls).hasValue(1);
        assertThat(count("ok")).isEqualTo(1);
    }

    @Test
    @DisplayName("GIVEN a response without a new ID token or refresh token WHEN refreshed SHOULD keep the old ones")
    void given_response_without_id_or_refresh_token_when_refreshed_should_keep_old() {
        BffSession old = seed(Duration.ofSeconds(-5));
        endpoint.set(() -> Mono.just(new Refreshed("access-2", null, null)));

        BffSession fresh = refresher().refresh(old, true).block(GENEROUS);

        assertThat(fresh.accessToken()).isEqualTo("access-2");
        assertThat(fresh.refreshToken()).isEqualTo(old.refreshToken());
        assertThat(fresh.idToken()).isEqualTo(old.idToken());
    }

    @Test
    @DisplayName("GIVEN a refresh token the issuer refuses WHEN refreshed SHOULD delete the session and give nothing")
    void given_refused_refresh_token_when_refreshed_should_delete_session() {
        BffSession old = seed(Duration.ofSeconds(-5));
        endpoint.set(() -> Mono.error(new TokenRefusedException(400)));

        assertThat(refresher().refresh(old, true).blockOptional(GENEROUS)).isEmpty();

        assertThat(memory.find(old.key()).blockOptional()).isEmpty();
        assertThat(count("rejected")).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "GIVEN an unreachable issuer and an expired token WHEN refreshed SHOULD fail as unavailable and keep the session")
    void given_unreachable_issuer_and_expired_token_when_refreshed_should_fail_unavailable() {
        BffSession old = seed(Duration.ofSeconds(-5));
        endpoint.set(() -> Mono.error(new IllegalStateException("connection refused")));

        assertThatThrownBy(() -> refresher().refresh(old, true).block(GENEROUS))
                .isInstanceOf(BffSessionUnavailableException.class);

        assertThat(memory.find(old.key()).block()).isEqualTo(old);
        assertThat(count("transient")).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "GIVEN an unreachable issuer and a token still valid WHEN refreshed SHOULD serve the session, and not ask again at once")
    void given_unreachable_issuer_and_valid_token_when_refreshed_should_serve_session() {
        BffSession old = seed(Duration.ofSeconds(30));
        endpoint.set(() -> Mono.error(new IllegalStateException("503")));
        BffTokenRefresher refresher = refresher();

        assertThat(refresher.refresh(old, false).block(GENEROUS)).isEqualTo(old);
        assertThat(refresher.refresh(old, false).block(GENEROUS)).isEqualTo(old);

        assertThat(endpointCalls).hasValue(1);
    }

    @Test
    @DisplayName(
            "GIVEN a lease held elsewhere WHEN another instance refreshes meanwhile SHOULD use its result without calling the issuer")
    void given_lease_held_elsewhere_when_other_refreshes_should_use_its_result() {
        BffSession old = seed(Duration.ofSeconds(-5));
        properties.setRefreshLease(Duration.ofSeconds(5));
        memory.tryLease(old.key(), "other-instance", Duration.ofSeconds(5)).block();
        BffSession theirs = new BffSession(
                old.key(),
                old.subject(),
                old.provider(),
                old.application(),
                old.sid(),
                old.name(),
                old.email(),
                old.picture(),
                "their-access",
                clock.instant().plus(Duration.ofMinutes(10)),
                "their-refresh",
                old.idToken(),
                old.created(),
                old.lastSeen(),
                old.version() + 1);

        BffSession got = Mono.zip(
                        refresher().refresh(old, true),
                        Mono.delay(Duration.ofMillis(350))
                                .then(memory.replaceIfVersion(theirs, old.version()))
                                .subscribeOn(Schedulers.parallel()))
                .map(t -> t.getT1())
                .block(GENEROUS);

        assertThat(got).isEqualTo(theirs);
        assertThat(endpointCalls).hasValue(0);
        assertThat(count("waited")).isEqualTo(1);
    }

    @Test
    @DisplayName("GIVEN a holder that died WHEN its lease runs out SHOULD take the lease and refresh once")
    void given_dead_holder_when_lease_runs_out_should_take_over() {
        BffSession old = seed(Duration.ofSeconds(-5));
        memory.tryLease(old.key(), "dead-instance", Duration.ofMillis(300)).block();

        BffSession fresh = refresher().refresh(old, true).block(GENEROUS);

        assertThat(fresh.accessToken()).isEqualTo("access-2");
        assertThat(endpointCalls).hasValue(1);
    }

    @Test
    @DisplayName(
            "GIVEN a lease that stays held WHEN two rounds pass SHOULD fail as unavailable without calling the issuer")
    void given_lease_that_stays_held_when_two_rounds_pass_should_fail_without_refreshing() {
        BffSession old = seed(Duration.ofSeconds(-5));
        // Renewed by another instance for the whole test: ttl far longer than the two 400 ms rounds.
        memory.tryLease(old.key(), "busy-instance", Duration.ofMinutes(5)).block();

        assertThatThrownBy(() -> refresher().refresh(old, true).block(GENEROUS))
                .isInstanceOf(BffSessionUnavailableException.class);

        assertThat(endpointCalls).hasValue(0);
    }

    @Test
    @DisplayName("GIVEN a refreshed token for another principal WHEN refreshed SHOULD end the session")
    void given_token_for_another_principal_when_refreshed_should_end_session() {
        BffSession old = seed(Duration.ofSeconds(-5));
        decodedSubject.set("mallory");

        assertThat(refresher().refresh(old, true).blockOptional(GENEROUS)).isEmpty();

        assertThat(memory.find(old.key()).blockOptional()).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN a decode slower than the lease allows WHEN refreshed SHOULD give up as unavailable, release the lease and use the refresh token once")
    void given_decode_slower_than_lease_when_refreshed_should_release_lease_and_not_reuse_token() {
        properties.setRefreshLease(Duration.ofSeconds(1));
        BffSession old = seed(Duration.ofSeconds(-5));
        refreshedDecodeDelay.set(Duration.ofSeconds(4));
        BffTokenRefresher refresher = refresher();

        assertThatThrownBy(() -> refresher.refresh(old, true).block(GENEROUS))
                .isInstanceOf(BffSessionUnavailableException.class);

        assertThat(endpointCalls).hasValue(1);
        assertThat(memory.find(old.key()).block()).isEqualTo(old);
        assertThat(memory.tryLease(old.key(), "next-instance", Duration.ofSeconds(5))
                        .block(GENEROUS))
                .isTrue();
        assertThat(count("transient")).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "GIVEN a refresh WHEN it starts SHOULD fetch the keys by decoding the old access token before taking the lease")
    void given_refresh_when_it_starts_should_warm_up_before_taking_the_lease() {
        store = new RecordingStore();
        BffSession old = seed(Duration.ofSeconds(-5));

        refresher().refresh(old, true).block(GENEROUS);

        assertThat(events).containsExactly("decode:" + old.accessToken(), "lease", "decode:access-2");
    }

    @Test
    @DisplayName("GIVEN a warm-up that fails WHEN refreshed SHOULD refresh anyway")
    void given_warm_up_that_fails_when_refreshed_should_refresh_anyway() {
        BffSession old = seed(Duration.ofSeconds(-5));
        warmUpFailure.set(new IllegalStateException("jwks unreachable"));

        BffSession fresh = refresher().refresh(old, true).block(GENEROUS);

        assertThat(fresh.accessToken()).isEqualTo("access-2");
        assertThat(endpointCalls).hasValue(1);
    }

    @Test
    @DisplayName("GIVEN a refreshed ID token that verifies WHEN refreshed SHOULD replace the stored one")
    void given_verified_refreshed_id_token_when_refreshed_should_replace_stored_one() {
        BffSession old = seed(Duration.ofSeconds(-5));

        BffSession fresh = refresher().refresh(old, true).block(GENEROUS);

        assertThat(fresh.idToken()).isEqualTo("id-2");
    }

    @Test
    @DisplayName(
            "GIVEN a refreshed ID token with other claims WHEN refreshed SHOULD replace the session claims with the listed ones from it")
    void given_refreshed_id_token_with_claims_when_refreshed_should_replace_session_claims() {
        properties.getApplications().get("eve").setSessionClaims(java.util.List.of("tenant", "roles"));
        BffSession seeded = seed(Duration.ofSeconds(-5));
        BffSession old = new BffSession(
                seeded.key(),
                seeded.subject(),
                seeded.provider(),
                seeded.application(),
                seeded.sid(),
                seeded.name(),
                seeded.email(),
                seeded.picture(),
                seeded.accessToken(),
                seeded.accessTokenExpiresAt(),
                seeded.refreshToken(),
                seeded.idToken(),
                seeded.created(),
                seeded.lastSeen(),
                seeded.version(),
                null,
                java.util.List.of(),
                null,
                java.util.Map.of("tenant", "old", "gone", "x"),
                "Mozilla/5.0");
        memory.save(old, Duration.ofHours(1)).block();
        refreshedIdClaims.set(java.util.Map.of("tenant", "new", "roles", java.util.List.of("admin"), "unlisted", "no"));

        BffSession fresh = refresher().refresh(old, true).block(GENEROUS);

        assertThat(fresh.claims())
                .containsOnlyKeys("tenant", "roles")
                .containsEntry("tenant", "new")
                .containsEntry("roles", java.util.List.of("admin"));
        assertThat(fresh.userAgent()).isEqualTo("Mozilla/5.0");
        assertThat(memory.find(old.key()).block().claims()).isEqualTo(fresh.claims());
    }

    @Test
    @DisplayName(
            "GIVEN a refresh answer without an ID token or with one that does not verify WHEN refreshed SHOULD keep the session claims")
    void given_no_usable_id_token_when_refreshed_should_keep_session_claims() {
        BffSession seeded = seed(Duration.ofSeconds(-5));
        BffSession old = new BffSession(
                seeded.key(),
                seeded.subject(),
                seeded.provider(),
                seeded.application(),
                seeded.sid(),
                seeded.name(),
                seeded.email(),
                seeded.picture(),
                seeded.accessToken(),
                seeded.accessTokenExpiresAt(),
                seeded.refreshToken(),
                seeded.idToken(),
                seeded.created(),
                seeded.lastSeen(),
                seeded.version(),
                null,
                java.util.List.of(),
                null,
                java.util.Map.of("tenant", "old"),
                null);
        memory.save(old, Duration.ofHours(1)).block();
        idDecodeFailure.set(new BadJwtException("bad signature"));

        BffSession fresh = refresher().refresh(old, true).block(GENEROUS);

        assertThat(fresh.claims()).containsEntry("tenant", "old");
    }

    @Test
    @DisplayName("GIVEN a refreshed ID token for another subject WHEN refreshed SHOULD end the session")
    void given_id_token_for_another_subject_when_refreshed_should_end_session() {
        BffSession old = seed(Duration.ofSeconds(-5));
        decodedIdSubject.set("mallory");

        assertThat(refresher().refresh(old, true).blockOptional(GENEROUS)).isEmpty();

        assertThat(memory.find(old.key()).blockOptional()).isEmpty();
        assertThat(count("rejected")).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "GIVEN a refreshed ID token that does not verify WHEN refreshed SHOULD keep the old ID token, use the new access token and log no token")
    void given_id_token_that_does_not_verify_when_refreshed_should_keep_old_id_token() {
        BffSession old = seed(Duration.ofSeconds(-5));
        idDecodeFailure.set(new BadJwtException("bad signature"));
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(BffTokenRefresher.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            BffSession fresh = refresher().refresh(old, true).block(GENEROUS);

            assertThat(fresh.accessToken()).isEqualTo("access-2");
            assertThat(fresh.idToken()).isEqualTo(old.idToken());
            assertThat(appender.list).anySatisfy(e -> {
                assertThat(e.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
                assertThat(e.getFormattedMessage()).doesNotContain("id-2").doesNotContain(old.idToken());
            });
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("GIVEN a refreshed token that does not verify WHEN refreshed SHOULD end the session")
    void given_token_that_does_not_verify_when_refreshed_should_end_session() {
        BffSession old = seed(Duration.ofSeconds(-5));
        decodeFailure.set(new BadJwtException("bad signature"));

        assertThat(refresher().refresh(old, true).blockOptional(GENEROUS)).isEmpty();

        assertThat(memory.find(old.key()).blockOptional()).isEmpty();
    }

    @Test
    @DisplayName("GIVEN many concurrent requests on one instance WHEN refreshing SHOULD call the issuer once for all")
    void given_concurrent_requests_when_refreshing_should_call_issuer_once() {
        BffSession old = seed(Duration.ofSeconds(-5));
        endpoint.set(
                () -> Mono.just(new Refreshed("access-2", "id-2", "refresh-2")).delayElement(Duration.ofMillis(200)));
        BffTokenRefresher refresher = refresher();

        List<BffSession> results = Flux.range(0, 40)
                .flatMap(i -> refresher.refresh(old, true).subscribeOn(Schedulers.parallel()))
                .collectList()
                .block(GENEROUS);

        assertThat(results)
                .hasSize(40)
                .allSatisfy(s -> assertThat(s.accessToken()).isEqualTo("access-2"));
        assertThat(endpointCalls).hasValue(1);
    }

    @Test
    @DisplayName("GIVEN a refresh in flight WHEN a request with a still valid token arrives SHOULD serve it at once")
    void given_refresh_in_flight_when_valid_token_request_arrives_should_not_wait() {
        BffSession expired = seed(Duration.ofSeconds(-5));
        endpoint.set(
                () -> Mono.just(new Refreshed("access-2", "id-2", "refresh-2")).delayElement(Duration.ofMillis(800)));
        BffTokenRefresher refresher = refresher();
        refresher.refresh(expired, true).subscribe();
        // Let the first request reach the token endpoint.
        Mono.delay(Duration.ofMillis(150)).block();
        BffSession valid = new BffSession(
                expired.key(),
                expired.subject(),
                expired.provider(),
                expired.application(),
                expired.sid(),
                expired.name(),
                expired.email(),
                expired.picture(),
                "still-valid",
                clock.instant().plusSeconds(30),
                expired.refreshToken(),
                expired.idToken(),
                expired.created(),
                expired.lastSeen(),
                expired.version());

        long start = System.nanoTime();
        BffSession served = refresher.refresh(valid, false).block(GENEROUS);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(300));
        assertThat(served).isSameAs(valid);
    }

    @Test
    @DisplayName(
            "GIVEN a store without leases WHEN refreshing SHOULD never call the issuer: expired is nothing, valid is served")
    void given_store_without_leases_when_refreshing_should_not_call_issuer() {
        store = new PlainStore(memory);
        BffSession expired = seed(Duration.ofSeconds(-5));
        BffTokenRefresher refresher = refresher();

        assertThat(refresher.refresh(expired, true).blockOptional(GENEROUS)).isEmpty();
        BffSession soon = BffTestFixtures.session(
                "cookie2", clock.instant(), clock.instant(), clock.instant().plusSeconds(30));
        memory.save(soon, Duration.ofHours(1)).block();
        assertThat(refresher.refresh(soon, false).block(GENEROUS)).isEqualTo(soon);

        assertThat(endpointCalls).hasValue(0);
    }

    @Test
    @DisplayName(
            "GIVEN a session another request already refreshed WHEN a stale snapshot refreshes SHOULD re-read and not call the issuer")
    void given_session_already_refreshed_when_stale_snapshot_refreshes_should_not_call_issuer() {
        BffSession stale = seed(Duration.ofSeconds(-5));
        BffSession done = new BffSession(
                stale.key(),
                stale.subject(),
                stale.provider(),
                stale.application(),
                stale.sid(),
                stale.name(),
                stale.email(),
                stale.picture(),
                "done",
                clock.instant().plus(Duration.ofMinutes(10)),
                "done-refresh",
                stale.idToken(),
                stale.created(),
                stale.lastSeen(),
                stale.version() + 1);
        memory.replaceIfVersion(done, stale.version()).block();

        assertThat(refresher().refresh(stale, true).block(GENEROUS)).isEqualTo(done);

        assertThat(endpointCalls).hasValue(0);
    }

    @Test
    @DisplayName("GIVEN the skew WHEN asking whether a token is due SHOULD count from its expiry")
    void given_skew_when_asking_due_should_count_from_expiry() {
        BffTokenRefresher refresher = refresher();
        Instant now = clock.instant();
        BffSession soon = BffTestFixtures.session("c", now, now, now.plusSeconds(59));
        BffSession later = BffTestFixtures.session("c", now, now, now.plusSeconds(120));

        assertThat(refresher.due(soon, now)).isTrue();
        assertThat(refresher.due(later, now)).isFalse();
    }
}
