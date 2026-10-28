package dev.appiaworks.shiguchi.cursus;

import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * In-process fan-out: one multicast sink per topic, created on first subscriber and dropped with
 * the last. A publish is one {@code tryEmitNext} on the publisher's thread; a subscriber without
 * demand misses the event (best effort), which is why every connection queues behind this with its
 * own policy.
 */
public final class MemoryPushBroker implements PushBroker {

    private final ConcurrentHashMap<Topic, Sinks.Many<PushEvent>> sinks = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> publish(PushEvent event) {
        Sinks.Many<PushEvent> sink = sinks.get(event.topic());
        if (sink != null) {
            // FAIL_ZERO_SUBSCRIBER and FAIL_NON_SERIALIZED are the only outcomes besides OK:
            // the former is nothing to do, the latter cannot happen with directBestEffort.
            sink.tryEmitNext(event);
        }
        return Mono.empty();
    }

    @Override
    public Flux<PushEvent> subscribe(Topic topic) {
        return Flux.defer(() -> {
            Sinks.Many<PushEvent> sink =
                    sinks.computeIfAbsent(topic, t -> Sinks.many().multicast().directBestEffort());
            return sink.asFlux()
                    .doFinally(signal -> sinks.computeIfPresent(topic, (t, s) -> {
                        // The last subscriber removes the sink; a concurrent subscribe recreates it.
                        return s == sink && s.currentSubscriberCount() == 0 ? null : s;
                    }));
        });
    }

    @Override
    public long subscriberCount(Topic topic) {
        Sinks.Many<PushEvent> sink = sinks.get(topic);
        return sink == null ? 0 : sink.currentSubscriberCount();
    }

    @Override
    public java.util.Map<Topic, Long> subscriptions() {
        java.util.Map<Topic, Long> out = new java.util.HashMap<>();
        sinks.forEach((t, s) -> {
            long n = s.currentSubscriberCount();
            if (n > 0) out.put(t, n);
        });
        return out;
    }

    /** Topics with at least one subscriber, for metrics and tests. */
    public int activeTopics() {
        return sinks.size();
    }
}
