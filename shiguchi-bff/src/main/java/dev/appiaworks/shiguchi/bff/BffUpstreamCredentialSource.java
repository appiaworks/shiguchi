package dev.appiaworks.shiguchi.bff;

import dev.appiaworks.shiguchi.bff.session.BffSession;
import dev.appiaworks.shiguchi.bff.session.BffSessionStore;
import dev.appiaworks.shiguchi.gateway.UpstreamCredentialSource;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
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
    private final BffTokenExchanger exchanger;

    /** A source that always sends the token the caller holds. */
    BffUpstreamCredentialSource() {
        this(null, null, Clock.systemUTC());
    }

    /**
     * @param store where the session of a caller with an old token is read, or {@code null}
     * @param refresher renews an old token, or {@code null} to send the caller's token as it is
     */
    BffUpstreamCredentialSource(BffSessionStore store, BffTokenRefresher refresher, Clock clock) {
        this(store, refresher, clock, null);
    }

    /**
     * @param exchanger trades the session token for a per-backend one for providers in {@code EXCHANGE}
     *     mode, or {@code null} to always relay
     */
    BffUpstreamCredentialSource(
            BffSessionStore store, BffTokenRefresher refresher, Clock clock, BffTokenExchanger exchanger) {
        this.store = store;
        this.refresher = refresher;
        this.clock = clock;
        this.exchanger = exchanger;
    }

    @Override
    public Mono<String> authorization(ServerHttpRequest request, Authentication authentication) {
        return authorization(request, authentication, null);
    }

    /**
     * RELAY providers ignore the route. An EXCHANGE provider's session is always read from the store
     * (the caller's {@link Authentication} knows neither its provider nor its session version), then
     * its token, renewed if due, is exchanged for the one the route's service gets. A refused
     * exchange, or a service with no target, is {@code PERMISSION_DENIED}; an exchange that could
     * not be done is {@code UNAVAILABLE}. There is no fallback to the caller's own token.
     */
    @Override
    public Mono<String> authorization(ServerHttpRequest request, Authentication authentication, RouteDefinition route) {
        if (!(authentication instanceof JwtAuthenticationToken jwt)
                || !BffSecurityContextRepository.isCookieAuthenticated(jwt)) {
            return Mono.empty();
        }
        String held = "Bearer " + jwt.getToken().getTokenValue();
        Instant expiresAt = jwt.getToken().getExpiresAt();
        String handle = CookieAuthenticated.handle(authentication);
        Instant now = clock.instant();
        if (exchanger != null && exchanger.anyEnabled() && store != null && handle != null) {
            String service = route == null ? null : route.getFullServiceName();
            return store.find(handle)
                    .switchIfEmpty(Mono.error(ended()))
                    .flatMap(session -> exchanger.enabled(session.provider())
                            ? renewed(session, now).flatMap(current -> exchange(current, service))
                            : current(session, now).map(BffUpstreamCredentialSource::bearerValue))
                    .switchIfEmpty(Mono.error(ended()));
        }
        if (refresher == null
                || store == null
                || handle == null
                || expiresAt == null
                || !refresher.due(expiresAt, now)) {
            return Mono.just(held);
        }
        return store.find(handle)
                .flatMap(session -> current(session, now).map(BffUpstreamCredentialSource::bearerValue))
                .switchIfEmpty(Mono.error(ended()));
    }

    private Mono<String> exchange(BffSession session, String service) {
        return exchanger
                .exchange(session, service)
                .map(token -> "Bearer " + token)
                .onErrorMap(
                        BffTokenExchanger.ExchangeRefused.class,
                        e -> Status.PERMISSION_DENIED
                                .withDescription("token exchange refused")
                                .asRuntimeException())
                .onErrorMap(
                        BffTokenExchanger.ExchangeUnavailable.class,
                        e -> Status.UNAVAILABLE
                                .withDescription("token exchange unavailable")
                                .asRuntimeException());
    }

    /** The session with a token that is not about to expire, renewed when it is due. */
    private Mono<BffSession> renewed(BffSession session, Instant now) {
        return refresher == null ? Mono.just(session) : current(session, now);
    }

    private Mono<BffSession> current(BffSession session, Instant now) {
        boolean expired = !session.accessTokenExpiresAt().isAfter(now);
        if (refresher == null || !refresher.due(session.accessTokenExpiresAt(), now)) return Mono.just(session);
        if (session.refreshToken() == null) return expired ? Mono.error(ended()) : Mono.just(session);
        return refresher
                .refresh(session, expired)
                .flatMap(refreshed -> refreshed.accessTokenExpiresAt().isAfter(clock.instant())
                        ? Mono.just(refreshed)
                        : Mono.<BffSession>error(ended()))
                .switchIfEmpty(Mono.error(ended()));
    }

    private static String bearerValue(BffSession session) {
        return "Bearer " + session.accessToken();
    }

    private static RuntimeException ended() {
        return Status.UNAUTHENTICATED.withDescription(CookieSessionWatch.REASON).asRuntimeException();
    }
}
