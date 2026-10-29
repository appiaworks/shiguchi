package dev.appiaworks.shiguchi.security;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.time.Duration;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * Binds a long-lived stream opened with the BFF session cookie to that session. {@link #ended}
 * completes when the session ends: at once when this instance ends it (a {@link CookieSessionEnded}
 * event), and at the latest one check interval after another instance ends it (a poll of
 * {@link CookieSessions#active}). A store error during the poll keeps the stream. Without a
 * {@link CookieSessions} bean (the BFF is off) nothing is bound and nothing changes.
 *
 * <p>Push (WebSocket, SSE) holds its own watch with its own interval; the gateway has one more as
 * a bean for the gRPC-Web server streams and the GraphQL subscriptions.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.14.0")
public final class CookieSessionWatch {

    private static final Logger log = LoggerFactory.getLogger(CookieSessionWatch.class);

    /** The reason a stream gives when its session ended. */
    public static final String REASON = "session ended";

    private final CookieSessions sessions;
    private final Duration interval;
    private final Consumer<Throwable> onCheckFailed;
    private final Sinks.Many<String> endings = Sinks.many().multicast().directBestEffort();

    /**
     * @param sessions the BFF's answer to "is it still active", or {@code null} when there is none
     * @param interval how often to ask for each bound stream
     * @param onCheckFailed told of each poll that could not be answered (the stream is kept), or
     *     {@code null}
     */
    public CookieSessionWatch(CookieSessions sessions, Duration interval, Consumer<Throwable> onCheckFailed) {
        this.sessions = sessions;
        this.interval = interval;
        this.onCheckFailed = onCheckFailed;
    }

    /** Whether the stream of {@code auth} lives and dies with a BFF session. */
    public boolean bound(Authentication auth) {
        return sessions != null && CookieAuthenticated.handle(auth) != null;
    }

    /** Same-instance session ends arrive here at once, as a Spring application event. */
    @EventListener
    public void onSessionEnded(CookieSessionEnded event) {
        synchronized (endings) {
            endings.tryEmitNext(event.handle());
        }
    }

    /**
     * Is the session of {@code auth} still active? {@code true} for a caller that is not
     * {@link #bound}, and when the store cannot be asked (that is not the session having ended).
     */
    public Mono<Boolean> active(Authentication auth) {
        if (!bound(auth)) return Mono.just(true);
        return sessions.active(CookieAuthenticated.handle(auth)).onErrorResume(this::checkFailed);
    }

    /**
     * Completes when the session of {@code auth} has ended; never completes for a stream that is
     * not {@link #bound}. Cancel it when the stream closes.
     */
    public Mono<Void> ended(Authentication auth) {
        if (!bound(auth)) return Mono.never();
        String handle = CookieAuthenticated.handle(auth);
        Mono<Void> local = endings.asFlux().filter(handle::equals).next().then();
        Mono<Void> poll = Flux.interval(interval, Schedulers.parallel())
                .concatMap(tick -> sessions.active(handle).onErrorResume(this::checkFailed))
                .filter(active -> !active)
                .next()
                .then();
        return Mono.firstWithSignal(local, poll);
    }

    private Mono<Boolean> checkFailed(Throwable e) {
        log.warn("Could not check a session, keeping its stream: {}", e.toString());
        if (onCheckFailed != null) onCheckFailed.accept(e);
        return Mono.just(true);
    }
}
