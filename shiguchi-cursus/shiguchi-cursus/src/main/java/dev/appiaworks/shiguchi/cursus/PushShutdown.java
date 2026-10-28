package dev.appiaworks.shiguchi.cursus;

import org.springframework.context.SmartLifecycle;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Tells every open subscription that this instance is going away: on context stop, each stream
 * ends with {@code event: close} and {@code {"reason":"shutdown"}}, so clients reconnect to a live
 * instance at once instead of waiting for the socket to die. Stops early in the lifecycle so the
 * streams drain inside the graceful-shutdown window.
 */
public final class PushShutdown implements SmartLifecycle {

    private final Sinks.Empty<Void> signal = Sinks.empty();
    private volatile boolean running;

    /** Completes when the instance is stopping. */
    public Mono<Void> stopping() {
        return signal.asMono();
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        signal.tryEmitEmpty();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // Before the web server stops accepting (SmartLifecycle.DEFAULT_PHASE - 2048 in Boot), so
        // the close frames go out while the connections are still open.
        return DEFAULT_PHASE - 1024;
    }
}
