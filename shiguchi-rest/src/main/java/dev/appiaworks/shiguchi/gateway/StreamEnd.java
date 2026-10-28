package dev.appiaworks.shiguchi.gateway;

import dev.appiaworks.shiguchi.security.CookieAuthenticated;
import dev.appiaworks.shiguchi.security.CookieSessionWatch;
import dev.appiaworks.shiguchi.security.TokenExpiry;
import io.grpc.Status;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * When a server stream must end because of who opened it. A cookie caller's stream lives and dies
 * with the BFF session ({@link CookieSessionWatch}) and never with the access token it carries; a
 * bearer caller's stream ends at the token's {@code exp}; anything else runs until the client or
 * the backend closes it.
 */
final class StreamEnd {

    private StreamEnd() {}

    /**
     * The signal that ends the stream of the current caller, as the status it ends with; empty
     * when nothing ends it.
     *
     * @param sessionWatch the session watch, or {@code null} when the BFF is off
     * @param endAtTokenExpiry {@code shiguchi.security.end-streams-at-token-expiry}
     */
    static Mono<Optional<Mono<Status>>> of(CookieSessionWatch sessionWatch, boolean endAtTokenExpiry) {
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(SecurityContext::getAuthentication)
                .map(auth -> signal(auth, sessionWatch, endAtTokenExpiry))
                .defaultIfEmpty(Optional.empty());
    }

    static Optional<Mono<Status>> signal(
            Authentication auth, CookieSessionWatch sessionWatch, boolean endAtTokenExpiry) {
        if (CookieAuthenticated.isCookieAuthenticated(auth)) {
            if (sessionWatch == null || !sessionWatch.bound(auth)) return Optional.empty();
            return Optional.of(sessionWatch
                    .ended(auth)
                    .thenReturn(Status.UNAUTHENTICATED.withDescription(CookieSessionWatch.REASON)));
        }
        Instant exp = endAtTokenExpiry ? TokenExpiry.expiry(auth) : null;
        if (exp == null) return Optional.empty();
        // A token accepted within the clock skew is already past exp: its stream ends at once.
        Duration wait = Duration.between(Instant.now(), exp);
        return Optional.of(Mono.delay(wait.isNegative() ? Duration.ZERO : wait, Schedulers.parallel())
                .map(tick -> Status.UNAUTHENTICATED.withDescription(TokenExpiry.REASON)));
    }

    /**
     * Stops {@code source} when the signal fires, recording the status in {@code ended} (the
     * cancel reaches the backend call). Without a signal the source is returned as it is.
     */
    static <T> Flux<T> until(Flux<T> source, Optional<Mono<Status>> end, AtomicReference<Status> ended) {
        if (end.isEmpty()) return source;
        return source.takeUntilOther(end.get().doOnNext(ended::set));
    }
}
