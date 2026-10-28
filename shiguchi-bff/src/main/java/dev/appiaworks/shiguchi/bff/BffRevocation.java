package dev.appiaworks.shiguchi.bff;

import dev.appiaworks.shiguchi.bff.session.BffSession;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Revokes the tokens of an ended session at the issuer (RFC 7009), best effort and detached from the
 * request: the refresh token, or the access token when the session has no refresh token. A failure is
 * counted and logged (provider and error class or status, never a token) and changes nothing else.
 * Counts {@code gateway.bff.revocation} tagged {@code outcome}: {@code revoked}, {@code failed} or
 * {@code skipped} (the provider has no revocation endpoint). Counts nothing without a registry.
 */
final class BffRevocation {

    static final String NAME = "gateway.bff.revocation";

    /** How many revocations one admin call runs at once. */
    static final int CONCURRENCY = 4;

    private static final Logger log = LoggerFactory.getLogger(BffRevocation.class);

    private final BffProviders providers;
    private final MeterRegistry registry;
    private final Set<String> skippedLogged = ConcurrentHashMap.newKeySet();

    BffRevocation(BffProviders providers, MeterRegistry registry) {
        this.providers = providers;
        this.registry = registry;
    }

    /**
     * Revokes the session's token. Completes after the outcome is counted ({@code revoked} or
     * {@code skipped}); errors on failure, uncounted (the detaching callers count {@code failed}).
     */
    Mono<Void> revoke(BffSession session) {
        boolean refresh = !blank(session.refreshToken());
        String token = refresh ? session.refreshToken() : session.accessToken();
        if (blank(token)) return Mono.empty();
        String hint = refresh ? "refresh_token" : "access_token";
        String provider = session.provider();
        return providers.hasRevocation(provider).flatMap(has -> {
            if (!has) {
                if (skippedLogged.add(provider)) {
                    log.debug(
                            "Provider '{}' has no revocation endpoint; tokens are not revoked at the issuer", provider);
                }
                count("skipped");
                return Mono.<Void>empty();
            }
            return providers.revoke(provider, token, hint).doOnSuccess(v -> count("revoked"));
        });
    }

    /** Revokes in the background; nothing reaches the caller or Reactor's dropped-error hook. */
    void revokeDetached(BffSession session) {
        revoke(session).subscribe(v -> {}, e -> failed(session.provider(), e));
    }

    /** Revokes several sessions in the background, {@value #CONCURRENCY} at a time. */
    void revokeDetached(Collection<BffSession> sessions) {
        Flux.fromIterable(sessions)
                .flatMap(
                        s -> revoke(s).onErrorResume(e -> {
                            failed(s.provider(), e);
                            return Mono.empty();
                        }),
                        CONCURRENCY)
                .subscribe(
                        v -> {},
                        e -> log.warn(
                                "BFF token revocation stopped: {}", e.getClass().getName()));
    }

    private void failed(String provider, Throwable error) {
        count("failed");
        String reason = error instanceof BffProviders.RevocationFailedException
                ? error.getMessage()
                : error.getClass().getName();
        log.warn("Revoking a token at provider '{}' failed: {}", provider, reason);
    }

    private void count(String outcome) {
        if (registry == null) return;
        Counter.builder(NAME)
                .description("Token revocations at the issuer on logout, by outcome")
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
