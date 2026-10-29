package dev.appiaworks.shiguchi.cursus;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Publishes this instance's connections to the store so every instance sees all of them, and
 * answers who listens to a topic. Writes never sit on the connect path: a connection opening,
 * closing or changing its topics marks the registry dirty, and one flush a few milliseconds later
 * writes the batch; a tick at half the row TTL rewrites every live connection so the rows of a
 * dead instance lapse by themselves. A store without a registry makes this class inert.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.14.0")
public final class PushRegistry implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PushRegistry.class);
    private static final long FLUSH_DELAY_MS = 25;
    private static final Duration DEFAULT_TTL = Duration.ofSeconds(30);
    private static final int REMOTE_LIMIT = 1000;

    private final PushProperties properties;
    private final PushStore store;
    private final PushConnections connections;
    private final String instance;
    private final Duration ttl;
    private final AtomicBoolean flushScheduled = new AtomicBoolean();
    private volatile Disposable ticker;

    public PushRegistry(PushProperties properties, PushStore store, PushConnections connections) {
        this.properties = properties;
        this.store = store;
        this.connections = connections;
        this.instance = properties.resolvedInstanceId();
        Duration keepalive = properties.getConnection().getKeepalive();
        this.ttl = keepalive == null || keepalive.isZero() ? DEFAULT_TTL : keepalive.multipliedBy(2);
        connections.setRegistry(this);
    }

    /** True when connections are shared through the store. */
    public boolean enabled() {
        return store.connectionRegistry();
    }

    public String instance() {
        return instance;
    }

    /** Starts the keepalive tick; call once at startup. */
    public void start() {
        if (!enabled() || ticker != null) return;
        ticker = Flux.interval(ttl.dividedBy(2), Schedulers.parallel()).subscribe(t -> flushNow());
    }

    /** Something about a local connection changed: write the batch shortly, once for all changes. */
    void changed() {
        if (!enabled() || !flushScheduled.compareAndSet(false, true)) return;
        Schedulers.parallel().schedule(this::flushNow, FLUSH_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    /** A local connection ended: drop its row now. */
    void removed(String id) {
        if (!enabled()) return;
        store.unregisterConnection(id)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(v -> {}, e -> log.debug("push registry: unregister failed: {}", e.toString()));
    }

    private void flushNow() {
        flushScheduled.set(false);
        List<PushStore.ConnectionRecord> rows = records();
        if (rows.isEmpty()) return;
        store.registerConnections(rows, ttl)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        v -> {},
                        e -> log.warn(
                                "push registry: could not write {} connection rows: {}", rows.size(), e.toString()));
    }

    private List<PushStore.ConnectionRecord> records() {
        List<PushStore.ConnectionRecord> rows = new ArrayList<>();
        for (PushConnections.Live l : connections.liveConnections()) rows.add(record(l));
        return rows;
    }

    private PushStore.ConnectionRecord record(PushConnections.Live l) {
        List<String> topics = new ArrayList<>();
        List<String> presence = new ArrayList<>();
        for (Topic t : l.topics()) {
            topics.add(t.toString());
            PushProperties.Namespace ns = properties.getNamespaces().get(t.namespace());
            if (ns != null && ns.isPresence()) presence.add(t.toString());
        }
        java.util.Collections.sort(topics);
        return new PushStore.ConnectionRecord(
                l.id(),
                instance,
                l.principal(),
                l.transport(),
                l.deviceId(),
                l.since(),
                topics,
                presence,
                peerAddress());
    }

    /** The address peers reach this instance's peer channel at, or null when the channel is off. */
    String peerAddress() {
        PushProperties.Peer peer = properties.getPeer();
        boolean on = peer.getToken() != null && !peer.getToken().isBlank();
        String address = peer.getAddress() == null ? "" : peer.getAddress().trim();
        return on && !address.isEmpty() ? address : null;
    }

    /** The connections {@code target} names that other instances hold (this instance's are found in memory). */
    Flux<PushStore.ConnectionRecord> remote(PushSender.Target target) {
        Flux<PushStore.ConnectionRecord> rows = target.connectionId() != null
                ? store.connection(target.connectionId()).flux()
                : store.connections(target.principal(), REMOTE_LIMIT);
        return rows.filter(r -> !instance.equals(r.instance()))
                .filter(r -> target.deviceId() == null || target.deviceId().equals(r.deviceId()));
    }

    /** The registry row of connection {@code id}, whichever instance holds it. */
    Mono<PushStore.ConnectionRecord> find(String id) {
        return enabled() ? store.connection(id) : Mono.empty();
    }

    private static PushConnections.Info info(PushStore.ConnectionRecord r) {
        return new PushConnections.Info(
                r.id(), r.transport(), r.principal(), r.since(), r.topics(), r.instance(), r.deviceId());
    }

    /** Every connection of the cluster (this instance's own when the store keeps no registry). */
    public Mono<List<PushConnections.Info>> list(String principal, int limit) {
        if (!enabled()) {
            return Mono.just(connections.list(i -> principal == null || principal.equals(i.principal())));
        }
        // This instance's own connections come from memory, so a connection is listed the moment it
        // opens; the store supplies the other instances' (and may lag by a flush).
        List<PushConnections.Info> local = connections.list(i -> principal == null || principal.equals(i.principal()));
        return store.connections(principal, limit)
                .filter(r -> !instance.equals(r.instance()))
                .map(PushRegistry::info)
                .collectList()
                .map(remote -> {
                    List<PushConnections.Info> all = new ArrayList<>(local);
                    all.addAll(remote);
                    all.sort(java.util.Comparator.comparing(PushConnections.Info::since));
                    return all;
                });
    }

    /** True when some connection of the cluster listens to {@code topic}. */
    public Mono<Boolean> hasSubscribers(Topic topic) {
        return count(topic).map(n -> n > 0);
    }

    /** How many connections listen to {@code topic}: cluster-wide for a presence namespace, else this instance's. */
    public Mono<Long> count(Topic topic) {
        if (indexed(topic)) return store.countConnectionsOn(topic);
        return Mono.just(connections.localCount(topic));
    }

    /** One page of the members listening to {@code topic} (presence namespaces; this instance's otherwise). */
    public Mono<List<PushConnections.Info>> subscribers(Topic topic, int offset, int limit) {
        if (indexed(topic)) {
            return store.connectionsOn(topic, offset, limit)
                    .map(PushRegistry::info)
                    .collectList();
        }
        String name = topic.toString();
        List<PushConnections.Info> local = connections.list(i -> i.topics().contains(name));
        int from = Math.min(offset, local.size());
        return Mono.just(local.subList(from, Math.min(local.size(), from + limit)));
    }

    private boolean indexed(Topic topic) {
        PushProperties.Namespace ns = properties.getNamespaces().get(topic.namespace());
        return enabled() && ns != null && ns.isPresence();
    }

    @Override
    public void close() {
        Disposable t = ticker;
        if (t != null) t.dispose();
        if (!enabled()) return;
        for (PushConnections.Live l : connections.liveConnections()) removed(l.id());
    }
}
