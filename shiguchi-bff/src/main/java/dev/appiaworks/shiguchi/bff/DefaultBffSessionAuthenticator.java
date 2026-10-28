package dev.appiaworks.shiguchi.bff;

import dev.appiaworks.shiguchi.bff.session.BffSession;
import dev.appiaworks.shiguchi.bff.session.BffSessionKeys;
import dev.appiaworks.shiguchi.bff.session.BffSessionStore;
import dev.appiaworks.shiguchi.security.CookieAuthenticated;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Cookie to caller: session cookie, store key, session, absolute and idle checks, access token
 * check (refreshed first when it expires within the skew, see {@link BffTokenRefresher}), then the
 * same converter a bearer request goes through. Any failure is an anonymous request, never an
 * error; the one error is {@link BffSessionUnavailableException}, an expired token the issuer
 * could not be asked to renew. An expired or missing session also expires the cookie, but only on
 * requests under the BFF path, so the proxied hot path never gains a {@code Set-Cookie}.
 */
final class DefaultBffSessionAuthenticator implements BffSessionAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(DefaultBffSessionAuthenticator.class);

    private static final int DECODED_CACHE_CAP = 10_000;

    private final BffSessionStore store;
    private final BffCookies cookies;
    private final String path;
    private final Duration idle;
    private final Duration absolute;
    private final Function<String, Mono<ReactiveJwtDecoder>> decoders;
    private final Function<Jwt, Mono<AbstractAuthenticationToken>> converter;
    private final Clock clock;
    private final BffTokenRefresher refresher;
    private final ConcurrentHashMap<String, Jwt> decoded = new ConcurrentHashMap<>();

    DefaultBffSessionAuthenticator(
            BffSessionStore store,
            BffCookies cookies,
            BffProperties properties,
            Function<String, Mono<ReactiveJwtDecoder>> decoders,
            Function<Jwt, Mono<AbstractAuthenticationToken>> converter,
            Clock clock) {
        this(store, cookies, properties, decoders, converter, clock, null);
    }

    /** @param refresher renews access tokens near expiry, or {@code null} to treat an expired one as anonymous */
    DefaultBffSessionAuthenticator(
            BffSessionStore store,
            BffCookies cookies,
            BffProperties properties,
            Function<String, Mono<ReactiveJwtDecoder>> decoders,
            Function<Jwt, Mono<AbstractAuthenticationToken>> converter,
            Clock clock,
            BffTokenRefresher refresher) {
        this.store = store;
        this.cookies = cookies;
        this.path = properties.getPath();
        this.idle = properties.getSession().getIdleTimeout();
        this.absolute = properties.getSession().getAbsoluteTimeout();
        this.decoders = decoders;
        this.converter = converter;
        this.clock = clock;
        this.refresher = refresher;
    }

    @Override
    public Mono<Authentication> authenticate(ServerWebExchange exchange) {
        String cookie = cookies.sessionValue(exchange.getRequest());
        if (cookie == null) return Mono.empty();
        String key = BffSessionKeys.key(cookie);
        return store.find(key)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(found -> found.isEmpty() ? expire(exchange, key, false) : check(exchange, found.get()))
                .onErrorResume(e -> {
                    if (e instanceof BffSessionUnavailableException) return Mono.error(e);
                    log.warn("Session lookup failed, treating the request as anonymous: {}", e.toString());
                    return Mono.empty();
                });
    }

    private Mono<Authentication> check(ServerWebExchange exchange, BffSession session) {
        Instant now = clock.instant();
        if (!now.isBefore(session.created().plus(absolute))
                || !now.isBefore(session.lastSeen().plus(idle))) {
            return expire(exchange, session.key(), true);
        }
        boolean expired = !session.accessTokenExpiresAt().isAfter(now);
        if (refresher == null || session.refreshToken() == null || !refresher.due(session, now)) {
            if (expired) {
                log.debug("Access token of a session has expired; the request is anonymous");
                return Mono.empty();
            }
            return authenticated(session, now);
        }
        return refresher
                .refresh(session, expired)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(refreshed -> {
                    if (refreshed.isEmpty()) return expire(exchange, session.key(), false);
                    Instant after = clock.instant();
                    return refreshed.get().accessTokenExpiresAt().isAfter(after)
                            ? authenticated(refreshed.get(), after)
                            : Mono.<Authentication>empty();
                });
    }

    private Mono<Authentication> authenticated(BffSession session, Instant now) {
        return decode(session, now)
                .flatMap(converter)
                .map(auth -> {
                    auth.setDetails(new CookieAuthenticated(auth.getDetails(), session.key(), session.application()));
                    return (Authentication) auth;
                })
                .flatMap(auth -> touch(session, now).thenReturn(auth))
                .onErrorResume(e -> {
                    log.debug("Session token rejected, treating the request as anonymous: {}", e.toString());
                    return Mono.empty();
                });
    }

    private Mono<Jwt> decode(BffSession session, Instant now) {
        Jwt cached = decoded.get(session.key());
        if (cached != null
                && cached.getTokenValue().equals(session.accessToken())
                && cached.getExpiresAt() != null
                && cached.getExpiresAt().isAfter(now)) {
            return Mono.just(cached);
        }
        return decoders.apply(session.provider())
                .flatMap(decoder -> decoder.decode(session.accessToken()))
                .doOnNext(jwt -> {
                    if (decoded.size() >= DECODED_CACHE_CAP) decoded.clear();
                    decoded.put(session.key(), jwt);
                });
    }

    private Mono<Void> touch(BffSession session, Instant now) {
        if (Duration.between(session.lastSeen(), now).compareTo(idle.dividedBy(10)) < 0) return Mono.empty();
        Duration left = absolute.minus(Duration.between(session.created(), now));
        Duration ttl = left.compareTo(idle) < 0 ? left : idle;
        return store.touch(session.key(), now, ttl).onErrorResume(e -> {
            log.debug("Could not record session activity: {}", e.toString());
            return Mono.empty();
        });
    }

    private Mono<Authentication> expire(ServerWebExchange exchange, String key, boolean delete) {
        decoded.remove(key);
        Mono<Void> deletion = delete ? store.delete(key) : Mono.empty();
        return deletion.then(Mono.fromRunnable(() -> clearCookie(exchange))).then(Mono.empty());
    }

    private void clearCookie(ServerWebExchange exchange) {
        String requestPath = exchange.getRequest().getPath().value();
        if (!requestPath.equals(path) && !requestPath.startsWith(path + "/")) return;
        if (exchange.getResponse().isCommitted()) return;
        exchange.getResponse().addCookie(cookies.clearSession());
    }
}
