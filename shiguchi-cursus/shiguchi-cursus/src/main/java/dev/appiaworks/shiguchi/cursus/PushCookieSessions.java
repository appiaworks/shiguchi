package dev.appiaworks.shiguchi.cursus;

import dev.appiaworks.shiguchi.security.CookieSessionEnded;
import dev.appiaworks.shiguchi.security.CookieSessionWatch;
import dev.appiaworks.shiguchi.security.CookieSessions;
import java.time.Duration;
import org.springframework.context.event.EventListener;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Mono;

/**
 * Binds a connection opened with the BFF session cookie to that session: {@link #ended} completes
 * when the session ends, at once when this instance ends it and within {@code session-check-interval}
 * when another does. The binding itself is the gateway's {@link CookieSessionWatch}; this holds one
 * with push's own interval and counts the polls that failed in {@link PushMetrics}. Without a
 * {@link CookieSessions} bean (the BFF is off) no connection is bound and nothing changes.
 */
public final class PushCookieSessions {

    static final String REASON = CookieSessionWatch.REASON;

    private final CookieSessionWatch watch;

    /**
     * @param sessions the BFF's answer to "is it still active", or {@code null} when there is none
     * @param interval how often to ask for each bound connection
     * @param metrics where polls that failed are counted
     */
    public PushCookieSessions(CookieSessions sessions, Duration interval, PushMetrics metrics) {
        this.watch = new CookieSessionWatch(sessions, interval, e -> metrics.sessionCheckFailed());
    }

    /** Whether the connection of {@code auth} lives and dies with a BFF session. */
    boolean bound(Authentication auth) {
        return watch.bound(auth);
    }

    /** Same-instance session ends arrive here at once, as a Spring application event. */
    @EventListener
    public void onSessionEnded(CookieSessionEnded event) {
        watch.onSessionEnded(event);
    }

    /**
     * Completes when the session of {@code auth} has ended; never completes for a connection that
     * is not {@link #bound}. Cancel it when the connection closes.
     */
    Mono<Void> ended(Authentication auth) {
        return watch.ended(auth);
    }
}
