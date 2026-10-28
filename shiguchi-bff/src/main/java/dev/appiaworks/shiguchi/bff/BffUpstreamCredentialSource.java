package dev.appiaworks.shiguchi.bff;

import dev.appiaworks.shiguchi.bff.session.BffSession;
import dev.appiaworks.shiguchi.bff.session.BffSessionStore;
import dev.appiaworks.shiguchi.gateway.UpstreamCredentialSource;
import dev.appiaworks.shiguchi.security.CookieAuthenticated;
import dev.appiaworks.shiguchi.security.CookieSessionWatch;
import io.grpc.Status;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

/**
 * Sends a cookie-authenticated caller's session token upstream as {@code Bearer <token>}, so a
 * backend sees the same credential shape as for a bearer caller. Anyone else gets nothing from here.
 *
 * <p>The token is the one on the caller's {@link Authentication} while it is fresh, which is every
 * ordinary request: it was loaded from the session an instant ago, so no store is read. A caller
 * that lives longer than its token (an operation on a GraphQL WebSocket opened with the cookie,
 * authenticated once at the upgrade) gets the session's current token once the one it holds is
 * within the refresh skew of its expiry: the session is read, and renewed through the same
 * {@link BffTokenRefresher} the cookie authenticator uses (one refresh per session across
 * instances). A session that ended, or whose refresh the issuer refused, fails the call as
 * {@code UNAUTHENTICATED}.
 */
final class BffUpstreamCredentialSource implements UpstreamCredentialSource {

    private final BffSessionStore store;
    private final BffTokenRefresher refresher;
    private final Clock clock;

    /** A source that always sends the token the caller holds. */
    BffUpstreamCredentialSource() {
        this(null, null, Clock.systemUTC());
    }

    /**
     * @param store where the session of a caller with an old token is read, or {@code null}
     * @param refresher renews an old token, or {@code null} to send the caller's token as it is
     */
    BffUpstreamCredentialSource(BffSessionStore store, BffTokenRefresher refresher, Clock clock) {
        this.store = store;
        this.refresher = refresher;
        this.clock = clock;
    }

    @Override
    public Mono<String> authorization(ServerHttpRequest request, Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwt)
                || !BffSecurityContextRepository.isCookieAuthenticated(jwt)) {
            return Mono.empty();
        }
        String held = "Bearer " + jwt.getToken().getTokenValue();
        Instant expiresAt = jwt.getToken().getExpiresAt();
        String handle = CookieAuthenticated.handle(authentication);
        Instant now = clock.instant();
        if (refresher == null
                || store == null
                || handle == null
                || expiresAt == null
                || !refresher.due(expiresAt, now)) {
            return Mono.just(held);
        }
        return store.find(handle).flatMap(session -> current(session, now)).switchIfEmpty(Mono.error(ended()));
    }

    private Mono<String> current(BffSession session, Instant now) {
        boolean expired = !session.accessTokenExpiresAt().isAfter(now);
        if (!refresher.due(session.accessTokenExpiresAt(), now)) return bearer(session);
        if (session.refreshToken() == null) return expired ? Mono.error(ended()) : bearer(session);
        return refresher
                .refresh(session, expired)
                .flatMap(refreshed -> refreshed.accessTokenExpiresAt().isAfter(clock.instant())
                        ? bearer(refreshed)
                        : Mono.<String>error(ended()))
                .switchIfEmpty(Mono.error(ended()));
    }

    private static Mono<String> bearer(BffSession session) {
        return Mono.just("Bearer " + session.accessToken());
    }

    private static RuntimeException ended() {
        return Status.UNAUTHENTICATED.withDescription(CookieSessionWatch.REASON).asRuntimeException();
    }
}
