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

    private final ConcurrentHashMap<Topic, TopicSink> sinks = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> publish(PushEvent event) {
        TopicSink entry = sinks.get(event.topic());
        if (entry != null) {
            // FAIL_ZERO_SUBSCRIBER and FAIL_NON_SERIALIZED are the only outcomes besides OK:
            // the former is nothing to do, the latter cannot happen with directBestEffort.
            entry.sink.tryEmitNext(event);
        }
        return Mono.empty();
    }

    @Override
    public Flux<PushEvent> subscribe(Topic topic) {
        return Flux.defer(() -> {
            // The claim is taken before the subscriber attaches, so a last subscriber leaving at
            // this moment cannot drop the sink the new one is about to join. The sink's own
            // subscriber count can't tell: it is still zero between these two steps.
            TopicSink entry = sinks.compute(topic, (t, current) -> {
                TopicSink claimed = current == null ? new TopicSink() : current;
                claimed.claims++;
                return claimed;
            });
            return entry.sink
                    .asFlux()
                    .doFinally(signal ->
                            sinks.computeIfPresent(topic, (t, current) -> --current.claims == 0 ? null : current));
        });
    }

    @Override
    public long subscriberCount(Topic topic) {
        TopicSink entry = sinks.get(topic);
        return entry == null ? 0 : entry.sink.currentSubscriberCount();
    }

    @Override
    public java.util.Map<Topic, Long> subscriptions() {
        java.util.Map<Topic, Long> out = new java.util.HashMap<>();
        sinks.forEach((t, entry) -> {
            long n = entry.sink.currentSubscriberCount();
            if (n > 0) out.put(t, n);
        });
        return out;
    }

    /** Topics with at least one subscriber, for metrics and tests. */
    public int activeTopics() {
        return sinks.size();
    }

    /** A topic's sink and how many subscriptions hold it; {@code claims} changes only inside the map's compute. */
    private static final class TopicSink {
        final Sinks.Many<PushEvent> sink = Sinks.many().multicast().directBestEffort();
        int claims;
    }
}
