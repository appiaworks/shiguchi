package dev.appiaworks.shiguchi.bff;

import dev.appiaworks.shiguchi.bff.session.BffSession;
import dev.appiaworks.shiguchi.bff.session.BffSessionStore;
import dev.appiaworks.shiguchi.security.CookieSessions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import reactor.core.publisher.Mono;

/**
 * Answers push's question "is this session still alive" with one store read: no token refresh and
 * no activity touch (a socket must not keep an idle session alive). A session past its absolute or
 * idle timeout is not active even before the store's own expiry removes it.
 */
final class BffCookieSessions implements CookieSessions {

    private final BffSessionStore store;
    private final Duration idle;
    private final Duration absolute;
    private final Clock clock;

    BffCookieSessions(BffSessionStore store, BffProperties properties, Clock clock) {
        this.store = store;
        this.idle = properties.getSession().getIdleTimeout();
        this.absolute = properties.getSession().getAbsoluteTimeout();
        this.clock = clock;
    }

    @Override
    public Mono<Boolean> active(String handle) {
        if (handle == null) return Mono.just(false);
        return store.find(handle).map(this::alive).defaultIfEmpty(false);
    }

    private boolean alive(BffSession session) {
        Instant now = clock.instant();
        return now.isBefore(session.created().plus(absolute))
                && now.isBefore(session.lastSeen().plus(idle));
    }
}
