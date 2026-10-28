package dev.appiaworks.shiguchi.gateway;

import java.time.Duration;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Signal;
import reactor.core.scheduler.Scheduler;

/**
 * Idle keepalives for a Server-Sent Events stream ({@code shiguchi.rest.streaming.sse-keepalive}).
 *
 * <p>A proxy or load balancer between the gateway and a browser closes a connection that has been
 * silent for its idle timeout (60 s is common), and a server stream can easily be silent that
 * long — a watch waiting for the next change. After {@code every} with no event, this sends an SSE
 * comment, which {@code EventSource} ignores and which keeps the connection busy. The timer
 * restarts on each event, so a stream that is already talking gets no extra traffic.
 *
 * <p>Keepalives go out before the first event too, since waiting for the first one is the typical
 * silent stretch. The consequence: once a keepalive has been sent the {@code 200} is committed, so
 * a backend failure after it is reported as the in-band {@code error} event rather than an HTTP
 * error status.
 */
public final class SseKeepAlive {

    private SseKeepAlive() {}

    /**
     * {@code frames} with {@code ping} interleaved after every {@code every} of silence. Completes
     * and fails with {@code frames}; backpressure and cancellation reach {@code frames} unchanged.
     * A keepalive due while the caller is not reading is dropped rather than queued.
     */
    public static <T> Flux<T> withKeepAlive(Flux<T> frames, Duration every, Supplier<T> ping, Scheduler scheduler) {
        if (every == null || every.isZero() || every.isNegative()) return frames;
        // Prefetch 1 on both publish and merge: the operator must not read ahead of the caller, or a
        // slow reader would have messages buffered here instead of the backend being held back.
        //
        // The stream travels materialized: its completion or failure is an ordinary queued value
        // behind its last message. publish and merge both fail at once on an error, dropping what
        // they still hold for a slow reader, so an unmaterialized failure could overtake the final
        // messages and the error event would arrive without them.
        return frames.materialize()
                .publish(
                        shared -> {
                            // Restarted by every frame: switchMap drops the previous timer when the
                            // next frame arrives.
                            Flux<Signal<T>> pings = Flux.<Object>just(Boolean.TRUE)
                                    .concatWith(shared)
                                    .switchMap(ignored -> Flux.interval(every, every, scheduler)
                                            .onBackpressureDrop()
                                            .map(tick -> Signal.next(ping.get())))
                                    .takeUntilOther(shared.then());
                            return Flux.merge(1, shared, pings);
                        },
                        1)
                // The terminal signal ends the output (and cancels the timer) after every message
                // queued ahead of it has been delivered.
                .<T>dematerialize();
    }
}
