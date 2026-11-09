package dev.appiaworks.shiguchi.graphql;

import java.util.Set;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Tells live queries that the data behind some gRPC services changed. In-process: it reaches the
 * live queries of this instance only. A signal naming {@link GraphQlLiveDependencies#ALL} touches
 * every service.
 */
class GraphQlLiveInvalidations {

    private final Sinks.Many<Set<String>> sink = Sinks.many().multicast().directBestEffort();

    /** Signals that the data behind these services changed; a signal nobody listens to is dropped. */
    void invalidate(Set<String> services) {
        if (services.isEmpty()) return;
        synchronized (sink) {
            sink.tryEmitNext(services);
        }
    }

    Flux<Set<String>> signals() {
        return sink.asFlux();
    }
}
