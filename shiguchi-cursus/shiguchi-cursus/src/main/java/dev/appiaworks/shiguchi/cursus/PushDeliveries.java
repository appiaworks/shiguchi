package dev.appiaworks.shiguchi.cursus;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * What happened to an event on its way to the subscribers. Always: delivered and dropped counts
 * per event for the most recent events on this instance. For a namespace with
 * {@code delivery-log: true}: one {@link PushDelivery} per connection and event, written to the
 * store in batches, so the counts and the per-subscriber log come from the store and span the
 * cluster. The transports report through {@link PushConnections.Live#delivered} and
 * {@link PushConnections.Live#dropped}.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.12.0")
public final class PushDeliveries implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(PushDeliveries.class);
    static final int RECENT_EVENTS = 10_000;
    static final int BATCH = 500;
    static final Duration BATCH_WAIT = Duration.ofMillis(500);

    private static final class Counts {
        final AtomicLong delivered = new AtomicLong();
        final AtomicLong dropped = new AtomicLong();
    }

    private final PushProperties properties;
    private final PushStore store;
    private final Map<String, Counts> recent = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Counts> eldest) {
            return size() > RECENT_EVENTS;
        }
    });
    private final Sinks.Many<PushDelivery> pending = Sinks.many().unicast().onBackpressureBuffer();
    private final Disposable writer;

    public PushDeliveries(PushProperties properties, PushStore store) {
        this.properties = properties;
        this.store = store;
        for (Map.Entry<String, PushProperties.Namespace> e :
                properties.getNamespaces().entrySet()) {
            if (e.getValue().isDeliveryLog() && !store.deliveryLog()) {
                throw new IllegalStateException("shiguchi.cursus: namespace '" + e.getKey()
                        + "' has delivery-log: true but the " + properties.getStore()
                        + " store keeps no delivery log (MEMORY and POSTGRES do)");
            }
        }
        this.writer = pending.asFlux()
                .bufferTimeout(BATCH, BATCH_WAIT)
                .concatMap(batch -> store.recordDeliveries(batch).onErrorResume(err -> {
                    log.warn("Push: {} delivery record(s) not written: {}", batch.size(), err.toString());
                    return Mono.empty();
                }))
                .subscribe(v -> {}, err -> log.warn("Push: delivery log writer ended: {}", err.toString()));
    }

    /** True when {@code topic}'s namespace logs every delivery to the store. */
    public boolean logged(Topic topic) {
        PushProperties.Namespace ns = properties.namespaceOf(topic);
        return ns != null && ns.isDeliveryLog();
    }

    void delivered(PushEvent event, PushConnections.Live live) {
        record(event, live, PushDelivery.DELIVERED);
    }

    void dropped(PushEvent event, PushConnections.Live live, String reason) {
        record(event, live, "dropped:" + reason);
    }

    private void record(PushEvent event, PushConnections.Live live, String outcome) {
        if (event.id() == null || event.id().isEmpty() || event.signal()) return;
        Counts counts = recent.computeIfAbsent(key(event.topic(), event.id()), k -> new Counts());
        if (PushDelivery.DELIVERED.equals(outcome)) counts.delivered.incrementAndGet();
        else counts.dropped.incrementAndGet();
        if (!logged(event.topic())) return;
        PushDelivery delivery = new PushDelivery(
                event.topic(), event.id(), live.id(), live.principal(), live.transport(), Instant.now(), outcome);
        pending.emitNext(delivery, Sinks.EmitFailureHandler.busyLooping(Duration.ofSeconds(1)));
    }

    /**
     * The deliveries of one event: counts (from the store for a logged namespace, so cluster-wide;
     * from this instance's recent events otherwise) and, for a logged namespace, the per-connection
     * log, oldest first, at most {@code limit}.
     */
    public Mono<Map<String, Object>> report(Topic topic, String id, int limit) {
        boolean logged = logged(topic);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("topic", topic.toString());
        out.put("id", id);
        out.put("logged", logged);
        out.put("scope", logged ? "cluster" : "instance");
        if (!logged) {
            Counts counts = recent.get(key(topic, id));
            out.put("delivered", counts == null ? 0L : counts.delivered.get());
            out.put("dropped", counts == null ? 0L : counts.dropped.get());
            out.put("log", List.of());
            return Mono.just(out);
        }
        return store.deliveryCounts(topic, id).flatMap(counts -> {
            long delivered = 0;
            long dropped = 0;
            for (Map.Entry<String, Long> c : counts.entrySet()) {
                if (PushDelivery.DELIVERED.equals(c.getKey())) delivered += c.getValue();
                else dropped += c.getValue();
            }
            out.put("delivered", delivered);
            out.put("dropped", dropped);
            return store.deliveries(topic, id, limit).collectList().map(entries -> {
                List<Map<String, Object>> rows = new ArrayList<>(entries.size());
                for (PushDelivery d : entries) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("connectionId", d.connectionId());
                    row.put("principal", d.principal());
                    row.put("transport", d.transport());
                    row.put("time", d.time().toString());
                    row.put("outcome", d.outcome());
                    rows.add(row);
                }
                out.put("log", rows);
                return out;
            });
        });
    }

    /** How many recent events this instance has counts for. */
    public int recentEvents() {
        return recent.size();
    }

    @Override
    public void destroy() {
        pending.tryEmitComplete();
        writer.dispose();
    }

    private static String key(Topic topic, String id) {
        return topic + "|" + id;
    }
}
