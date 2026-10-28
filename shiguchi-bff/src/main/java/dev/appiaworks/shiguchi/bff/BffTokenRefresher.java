package dev.appiaworks.shiguchi.bff;

import com.nimbusds.jwt.JWTParser;
import dev.appiaworks.shiguchi.bff.BffProviders.Refreshed;
import dev.appiaworks.shiguchi.bff.BffProviders.TokenRefusedException;
import dev.appiaworks.shiguchi.bff.session.BffSession;
import dev.appiaworks.shiguchi.bff.session.BffSessionClaims;
import dev.appiaworks.shiguchi.bff.session.BffSessionStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;

/**
 * Refreshes a session's access token, once per session across every gateway instance. The issuer
 * rotates refresh tokens and revokes the whole family when one is used twice, so two refreshes of
 * one session (even at the same moment, on different instances) sign the user out everywhere.
 *
 * <p>Two layers. In this JVM, concurrent requests for one session share one attempt. Across
 * instances, the attempt first takes the session's lease in the {@link BffSessionStore}; nobody
 * calls the token endpoint without holding it. The holder re-reads the session (another instance
 * may just have refreshed it), refreshes, writes the result with a compare-and-set on
 * {@code version} and releases. A request that finds the lease taken polls the session every 100 ms
 * until its version changes or the lease time has passed; then it tries to take the lease itself, and
 * after two such rounds gives up as transient.
 *
 * <p>A token that is inside the skew window but not yet expired is used at once and never waits for
 * another request's refresh: only an expired token waits.
 */
final class BffTokenRefresher {

    private static final Logger log = LoggerFactory.getLogger(BffTokenRefresher.class);

    /** How long the token endpoint may take; the lease must outlast it. */
    static final Duration TOKEN_ENDPOINT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * The part of the lease kept back for releasing it: the token call, the decodes and the check run
     * for the lease minus this, so a slow one ends before another instance may take the lease.
     */
    static final Duration LEASE_MARGIN = Duration.ofSeconds(2);

    /** What {@code refresh-lease} must be at least: the token call, the margin, and a second to decode and store. */
    static final Duration MIN_LEASE = TOKEN_ENDPOINT_TIMEOUT.plus(LEASE_MARGIN).plusSeconds(1);

    private static final Duration POLL = Duration.ofMillis(100);
    private static final int ROUNDS = 2;
    private static final Duration TRANSIENT_BACKOFF = Duration.ofSeconds(1);
    private static final int BACKOFF_CAP = 10_000;

    private enum Kind {
        /** This instance refreshed it. */
        REFRESHED,
        /** Another request or instance had refreshed it. */
        FOUND,
        /** The issuer refused, or gave a token for someone else: the session was deleted. */
        REJECTED,
        /** The session is gone. */
        GONE,
        /** The issuer or the store could not be reached. */
        TRANSIENT,
        /** The store has no lease: refresh is off. */
        UNSUPPORTED
    }

    private record Result(Kind kind, BffSession session) {
        static Result of(Kind kind) {
            return new Result(kind, null);
        }
    }

    private record Decoded(Jwt jwt, String name) {}

    /** The ID token to store, and the session claims read from it ({@code null}: keep the session's own). */
    private record StoredIdToken(Optional<String> token, Map<String, Object> claims) {}

    /** What the verified answer of the issuer comes to: a result to return as is, or a session to store. */
    private record Plan(Result result, BffSession next) {}

    /** The refreshed ID token names another subject than the one the session was signed in with. */
    private static final class IdTokenSubjectException extends RuntimeException {
        IdTokenSubjectException() {
            super("The refreshed ID token names another subject than the session's");
        }
    }

    private final BffSessionStore store;
    private final BiFunction<String, String, Mono<Refreshed>> tokenEndpoint;
    private final Function<String, Mono<ReactiveJwtDecoder>> decoders;
    private final Function<String, Mono<ReactiveJwtDecoder>> idDecoders;
    private final Function<Jwt, Mono<AbstractAuthenticationToken>> converter;
    private final BffProperties properties;
    private final Duration skew;
    private final Duration lease;
    private final Duration criticalSection;
    private final Clock clock;
    private final Map<Kind, Counter> counters = new EnumMap<>(Kind.class);
    private final BffLogoutMeter logouts;
    private final ConcurrentHashMap<String, Mono<Result>> inflight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> backoff = new ConcurrentHashMap<>();
    private final AtomicBoolean unsupportedLogged = new AtomicBoolean();

    /**
     * @param store the session store; its lease and compare-and-set methods decide whether refresh works
     * @param tokenEndpoint {@code (provider id, refresh token)} to the issuer's answer
     * @param decoders the access-token decoder of a provider
     * @param idDecoders the ID-token decoder of a provider; it must not check the nonce (a refresh has none)
     * @param converter the converter the sign-in used, to compare the principal
     * @param properties for {@code refresh-skew} and {@code refresh-lease}
     * @param clock the time source
     * @param registry where {@code gateway.bff.refresh} is counted, or {@code null}
     */
    BffTokenRefresher(
            BffSessionStore store,
            BiFunction<String, String, Mono<Refreshed>> tokenEndpoint,
            Function<String, Mono<ReactiveJwtDecoder>> decoders,
            Function<String, Mono<ReactiveJwtDecoder>> idDecoders,
            Function<Jwt, Mono<AbstractAuthenticationToken>> converter,
            BffProperties properties,
            Clock clock,
            MeterRegistry registry) {
        this.store = store;
        this.tokenEndpoint = tokenEndpoint;
        this.decoders = decoders;
        this.idDecoders = idDecoders;
        this.converter = converter;
        this.properties = properties;
        this.skew = properties.getRefreshSkew();
        this.lease = properties.getRefreshLease();
        // A lease shorter than validation allows (tests) keeps a proportional margin.
        Duration margin = lease.dividedBy(5).compareTo(LEASE_MARGIN) < 0 ? lease.dividedBy(5) : LEASE_MARGIN;
        this.criticalSection = lease.minus(margin);
        this.clock = clock;
        this.logouts = new BffLogoutMeter(registry);
        if (registry != null) {
            counters.put(Kind.REFRESHED, counter(registry, "ok"));
            counters.put(Kind.REJECTED, counter(registry, "rejected"));
            counters.put(Kind.TRANSIENT, counter(registry, "transient"));
            counters.put(Kind.FOUND, counter(registry, "waited"));
        }
    }

    private static Counter counter(MeterRegistry registry, String outcome) {
        return Counter.builder("gateway.bff.refresh")
                .description("Session token refreshes by outcome")
                .tag("outcome", outcome)
                .register(registry);
    }

    /** Whether the session's access token expires within the skew (or already has). */
    boolean due(BffSession session, Instant now) {
        return due(session.accessTokenExpiresAt(), now);
    }

    /** Whether an access token that expires at {@code expiresAt} is within the skew of expiry (or past it). */
    boolean due(Instant expiresAt, Instant now) {
        return !expiresAt.minus(skew).isAfter(now);
    }

    /**
     * The session to authenticate the request with, after refreshing it if needed.
     *
     * @param session what the request read from the store
     * @param expired whether its access token has expired (the request then waits for a refresh)
     * @return the refreshed (or other instance's refreshed) session; the given one when it is still
     *     valid and the refresh could not be done; empty when the session ended (refused, deleted)
     *     or the store cannot refresh and the token has expired; an error
     *     {@link BffSessionUnavailableException} when the token has expired and the issuer could
     *     not be reached
     */
    Mono<BffSession> refresh(BffSession session, boolean expired) {
        String key = session.key();
        if (!expired) {
            if (inflight.containsKey(key)) return Mono.just(session);
            Instant until = backoff.get(key);
            if (until != null && clock.instant().isBefore(until)) return Mono.just(session);
        }
        Mono<Result> flight = inflight.computeIfAbsent(
                key,
                k -> Mono.defer(() -> acquire(session, 0))
                        .switchIfEmpty(Mono.fromSupplier(() -> Result.of(Kind.TRANSIENT)))
                        .onErrorResume(this::failed)
                        .doOnNext(r -> record(k, r))
                        .doFinally(s -> inflight.remove(k))
                        .cache());
        return flight.flatMap(r -> switch (r.kind()) {
            case REFRESHED, FOUND -> Mono.just(r.session());
            case REJECTED, GONE -> Mono.empty();
            case UNSUPPORTED -> expired ? Mono.empty() : Mono.just(session);
            case TRANSIENT ->
                expired
                        ? Mono.error(new BffSessionUnavailableException(
                                "the access token has expired and the issuer could not be reached to refresh it"))
                        : Mono.just(session);
        });
    }

    private Mono<Result> failed(Throwable e) {
        if (e instanceof UnsupportedOperationException) {
            if (unsupportedLogged.compareAndSet(false, true)) {
                log.error(
                        "The BFF session store cannot hold a refresh lease ({}): access tokens are not refreshed,"
                                + " an expired one ends the request as anonymous",
                        e.getMessage());
            }
            return Mono.just(Result.of(Kind.UNSUPPORTED));
        }
        log.warn("Session refresh failed: {}", e.toString());
        return Mono.just(Result.of(Kind.TRANSIENT));
    }

    private void record(String key, Result r) {
        Counter c = counters.get(r.kind());
        if (c != null) c.increment();
        if (r.kind() == Kind.TRANSIENT) {
            if (backoff.size() >= BACKOFF_CAP) backoff.clear();
            backoff.put(key, clock.instant().plus(TRANSIENT_BACKOFF));
        } else {
            backoff.remove(key);
        }
    }

    // ---- the cross-instance part -------------------------------------------------------------

    private Mono<Optional<BffSession>> read(String key) {
        return store.find(key).map(Optional::of).defaultIfEmpty(Optional.empty());
    }

    /** Whether {@code current} needs no refresh by us: someone changed it since {@code seen}, or it is not due. */
    private boolean settled(BffSession seen, BffSession current) {
        return current.version() != seen.version() || current.refreshToken() == null || !due(current, clock.instant());
    }

    private Mono<Result> acquire(BffSession seen, int round) {
        String key = seen.key();
        return read(key).flatMap(current -> {
            if (current.isEmpty()) return Mono.just(Result.of(Kind.GONE));
            if (settled(seen, current.get())) return Mono.just(new Result(Kind.FOUND, current.get()));
            String owner = UUID.randomUUID().toString();
            // The JWK set is fetched before the lease is taken, so the slow part normally happens outside it.
            return warmUp(current.get())
                    .then(Mono.defer(() -> store.tryLease(key, owner, lease)))
                    .flatMap(got -> got ? holding(seen, owner) : waiting(seen, round));
        });
    }

    /**
     * Makes the access-token decoder fetch and cache the issuer's JWK set, by decoding the session's own
     * (old) access token; the outcome does not matter. Best effort: a failure here never blocks the refresh.
     */
    private Mono<Void> warmUp(BffSession session) {
        return decoders.apply(session.provider())
                .flatMap(decoder -> decoder.decode(session.accessToken()))
                .timeout(BffProviders.JWKS_TIMEOUT)
                .onErrorResume(e -> Mono.empty())
                .then();
    }

    /** Holds the lease: re-read, and refresh only if nobody else has. The lease is released however this ends. */
    private Mono<Result> holding(BffSession seen, String owner) {
        String key = seen.key();
        return Mono.usingWhen(
                Mono.just(owner),
                o -> read(key).flatMap(current -> {
                    if (current.isEmpty()) return Mono.just(Result.of(Kind.GONE));
                    if (settled(seen, current.get())) return Mono.just(new Result(Kind.FOUND, current.get()));
                    return refreshNow(current.get());
                }),
                o -> store.releaseLease(key, o).onErrorResume(e -> {
                    log.debug("Could not release the refresh lease: {}", e.toString());
                    return Mono.empty();
                }));
    }

    private Mono<Result> waiting(BffSession seen, int round) {
        return poll(seen, round, System.nanoTime() + lease.toNanos());
    }

    private Mono<Result> poll(BffSession seen, int round, long deadline) {
        return Mono.delay(POLL).then(Mono.defer(() -> read(seen.key()))).flatMap(current -> {
            if (current.isEmpty()) return Mono.just(Result.of(Kind.GONE));
            if (current.get().version() != seen.version()) return Mono.just(new Result(Kind.FOUND, current.get()));
            if (System.nanoTime() - deadline >= 0) {
                return round + 1 >= ROUNDS ? Mono.just(Result.of(Kind.TRANSIENT)) : acquire(seen, round + 1);
            }
            return poll(seen, round, deadline);
        });
    }

    // ---- the refresh itself ------------------------------------------------------------------

    /**
     * The token call and the checks of its answer run for at most the lease minus the margin, so they end
     * before another instance may take the lease. The store write that follows is one atomic
     * compare-and-set and is never cancelled: cancelling it could lose the rotated refresh token.
     */
    private Mono<Result> refreshNow(BffSession current) {
        return tokenEndpoint
                .apply(current.provider(), current.refreshToken())
                .flatMap(refreshed -> plan(current, refreshed))
                .timeout(criticalSection)
                .flatMap(plan -> plan.next() == null ? Mono.just(plan.result()) : store(current, plan.next()))
                .onErrorResume(TokenRefusedException.class, e -> {
                    log.info("The issuer refused the refresh token of a session, ending it: {}", e.getMessage());
                    return end(current);
                })
                .onErrorResume(BadJwtException.class, e -> {
                    log.warn("A refreshed access token did not verify, ending the session: {}", e.getMessage());
                    return end(current);
                })
                .onErrorResume(IdTokenSubjectException.class, e -> {
                    log.warn("{}, ending it", e.getMessage());
                    return end(current);
                })
                .onErrorResume(TimeoutException.class, e -> {
                    log.warn(
                            "Token refresh took longer than the lease allows ({}), giving the lease up",
                            criticalSection);
                    return Mono.just(Result.of(Kind.TRANSIENT));
                })
                .onErrorResume(e -> !(e instanceof UnsupportedOperationException), e -> {
                    log.warn("Token refresh failed, the issuer looks unavailable: {}", e.toString());
                    return Mono.just(Result.of(Kind.TRANSIENT));
                })
                .defaultIfEmpty(Result.of(Kind.TRANSIENT));
    }

    private Mono<Plan> plan(BffSession current, Refreshed refreshed) {
        return decoders.apply(current.provider())
                .flatMap(decoder -> decoder.decode(refreshed.accessToken()))
                .flatMap(jwt -> converter.apply(jwt).map(auth -> new Decoded(jwt, auth.getName())))
                .flatMap(decoded -> {
                    if (!Objects.equals(decoded.name(), current.subject())) {
                        log.warn("A refreshed access token names another principal than the session's, ending it");
                        return end(current).map(r -> new Plan(r, null));
                    }
                    Instant exp = decoded.jwt().getExpiresAt();
                    if (exp == null) {
                        log.warn("A refreshed access token has no exp, ending the session");
                        return end(current).map(r -> new Plan(r, null));
                    }
                    return idToken(current, refreshed)
                            .map(fresh -> new Plan(
                                    null,
                                    new BffSession(
                                            current.key(),
                                            current.subject(),
                                            current.provider(),
                                            current.application(),
                                            current.sid(),
                                            current.name(),
                                            current.email(),
                                            current.picture(),
                                            refreshed.accessToken(),
                                            exp,
                                            refreshed.refreshToken() != null
                                                    ? refreshed.refreshToken()
                                                    : current.refreshToken(),
                                            fresh.token().orElse(null),
                                            current.created(),
                                            current.lastSeen(),
                                            current.version() + 1,
                                            current.acr(),
                                            current.amr(),
                                            current.authTime(),
                                            fresh.claims() == null ? current.claims() : fresh.claims(),
                                            current.userAgent())));
                });
    }

    /**
     * The ID token to store: the refreshed one when it verifies (signature, issuer, audience, expiry; there is no
     * nonce in a refresh response) and names the subject of the one the session signed in with (OIDC Core 12.2);
     * the previous one when there is none or it does not verify; an {@link IdTokenSubjectException} when the
     * subject differs.
     */
    private Mono<StoredIdToken> idToken(BffSession current, Refreshed refreshed) {
        String fresh = refreshed.idToken();
        StoredIdToken previous = new StoredIdToken(Optional.ofNullable(current.idToken()), null);
        if (fresh == null) return Mono.just(previous);
        return idDecoders
                .apply(current.provider())
                .flatMap(decoder -> decoder.decode(fresh))
                .flatMap(jwt -> Objects.equals(jwt.getSubject(), subjectOf(current))
                        ? Mono.just(new StoredIdToken(
                                Optional.of(fresh),
                                BffSessionClaims.select(jwt.getClaims(), claimNames(current), current.application())))
                        : Mono.<StoredIdToken>error(new IdTokenSubjectException()))
                .onErrorResume(e -> !(e instanceof IdTokenSubjectException), e -> {
                    log.warn("A refreshed ID token did not verify, keeping the previous one: {}", e.toString());
                    return Mono.just(previous);
                });
    }

    /** The claim names the session's application lists; the default ones for an application that is gone. */
    private List<String> claimNames(BffSession session) {
        BffProperties.Application application = properties.getApplications().get(session.application());
        return application == null
                ? new BffProperties.Application().getSessionClaims()
                : application.getSessionClaims();
    }

    /** The {@code sub} of the ID token the session signed in with (verified then), else the session's subject. */
    private static String subjectOf(BffSession session) {
        try {
            String sub = JWTParser.parse(session.idToken()).getJWTClaimsSet().getSubject();
            if (sub != null) return sub;
        } catch (ParseException | RuntimeException e) {
            // Not a readable JWT: fall back below.
        }
        return session.subject();
    }

    private Mono<Result> store(BffSession current, BffSession next) {
        return store.replaceIfVersion(next, current.version()).flatMap(stored -> {
            if (stored) return Mono.just(new Result(Kind.REFRESHED, next));
            // Lost the lease to a slow call: whatever is stored now is what to use.
            return read(current.key())
                    .map(again -> again.map(s -> new Result(Kind.FOUND, s)).orElseGet(() -> Result.of(Kind.GONE)));
        });
    }

    private Mono<Result> end(BffSession session) {
        return store.delete(session.key())
                .doOnSuccess(v -> logouts.count("refresh-rejected", 1))
                .thenReturn(Result.of(Kind.REJECTED));
    }
}
