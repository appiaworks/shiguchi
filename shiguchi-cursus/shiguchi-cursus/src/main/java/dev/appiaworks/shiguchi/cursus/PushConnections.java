package dev.appiaworks.shiguchi.cursus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.concurrent.Queues;

/**
 * The connections open on this instance, over every transport: who they are, what they listen
 * to, since when; and a way for an operator to end one. Each transport registers its connection
 * on open, keeps its topic set current, and ends the stream when {@link Live#closed()} completes.
 */
public final class PushConnections {

    private final ConcurrentHashMap<String, Live> live = new ConcurrentHashMap<>();
    private volatile PushDeliveries deliveries;
    private volatile PushHooks hooks;
    private volatile PushRegistry registry;

    /** The attribute that marks an event sent to one connection ({@code Send}) rather than published to a topic. */
    public static final String DIRECT = "push.direct";

    /** How many directly sent events one connection holds before its transport has written them. */
    static final int DIRECT_QUEUE = 64;

    /** Where the connections are published for the rest of the cluster; optional. */
    public void setRegistry(PushRegistry registry) {
        this.registry = registry;
    }

    /** The hooks told about every connection opening and closing; optional. */
    public void setHooks(PushHooks hooks) {
        this.hooks = hooks;
    }

    /** Where the connections report what they delivered and dropped; optional. */
    public void setDeliveries(PushDeliveries deliveries) {
        this.deliveries = deliveries;
    }

    /** One open connection. */
    public final class Live {
        private final String id = UUID.randomUUID().toString();
        private final String transport;
        private volatile String principal;
        private final Instant since = Instant.now();
        private final Set<Topic> topics = new TopicSet();
        private volatile String deviceId;
        private volatile String origin;
        private final Sinks.Empty<Void> closed = Sinks.empty();
        private volatile String closeReason;
        private final Sinks.Many<PushEvent> direct = Sinks.many()
                .unicast()
                .onBackpressureBuffer(Queues.<PushEvent>get(DIRECT_QUEUE).get());
        private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();

        private record Pending(Sinks.One<Boolean> written, Sinks.One<Boolean> acked) {}

        private Live(String transport, Authentication auth) {
            this.transport = transport;
            this.principal = TopicAuthorizer.isAuthenticated(auth) ? auth.getName() : null;
        }

        public String id() {
            return id;
        }

        /** The connection authenticated as {@code auth} after it opened (a WebSocket {@code connect} frame). */
        public void authenticated(Authentication auth) {
            this.principal = TopicAuthorizer.isAuthenticated(auth) ? auth.getName() : null;
            changed();
        }

        public String transport() {
            return transport;
        }

        /**
         * The origin id the client declared for this connection (SSE {@code ?origin=}, gRPC
         * {@code SubscribeRequest.origin}, WebSocket {@code connect{origin}}); null when none. A
         * directly sent event stamped with the same origin is not delivered to it.
         */
        public String origin() {
            return origin;
        }

        public void origin(String origin) {
            this.origin = origin == null || origin.isBlank() ? null : origin.trim();
        }

        /** The principal's name, or null when anonymous. */
        public String principal() {
            return principal;
        }

        /** The transport wrote {@code event} to this connection. */
        public void delivered(PushEvent event) {
            PushDeliveries d = deliveries;
            if (d != null) d.delivered(event, this);
            if (!pending.isEmpty() && event.attributes().containsKey(DIRECT)) {
                Pending p = pending.get(event.id());
                if (p != null) p.written.tryEmitValue(Boolean.TRUE);
            }
        }

        /**
         * The events sent to this connection by {@code Send}; the transport merges them into what it
         * writes, and subscribes once.
         */
        public Flux<PushEvent> direct() {
            return direct.asFlux();
        }

        /**
         * Queues {@code event} for this connection and reports how it went: {@code DELIVERED} once the
         * transport wrote it (and, with {@code requireAck}, the client acknowledged it), {@code TIMEOUT}
         * when the acknowledgement did not come within {@code timeout}, {@code REJECTED} when the
         * connection's queue is full, it closed, or it never wrote the event. SSE has no way to
         * acknowledge a direct event, so {@code requireAck} on it is {@code REJECTED}.
         */
        public Mono<PushSender.Status> deliverDirect(PushEvent event, boolean requireAck, java.time.Duration timeout) {
            if (!"sse".equals(transport) && !"ws".equals(transport) && !"grpc".equals(transport)) {
                return Mono.just(PushSender.Status.REJECTED);
            }
            if (requireAck && "sse".equals(transport)) return Mono.just(PushSender.Status.REJECTED);
            if (PushOrigin.matches(event, origin)) return Mono.just(PushSender.Status.SUPPRESSED);
            Pending p = new Pending(Sinks.one(), Sinks.one());
            pending.put(event.id(), p);
            if (!direct.tryEmitNext(event).isSuccess()) {
                pending.remove(event.id());
                return Mono.just(PushSender.Status.REJECTED);
            }
            Mono<PushSender.Status> outcome = p.written
                    .asMono()
                    .timeout(timeout)
                    .map(ok -> ok ? PushSender.Status.DELIVERED : PushSender.Status.REJECTED)
                    .onErrorReturn(java.util.concurrent.TimeoutException.class, PushSender.Status.REJECTED);
            if (requireAck) {
                outcome = outcome.flatMap(s -> s != PushSender.Status.DELIVERED
                        ? Mono.just(s)
                        : p.acked
                                .asMono()
                                .timeout(timeout)
                                .map(ok -> ok ? PushSender.Status.DELIVERED : PushSender.Status.REJECTED)
                                .onErrorReturn(java.util.concurrent.TimeoutException.class, PushSender.Status.TIMEOUT));
            }
            return outcome.doFinally(sig -> pending.remove(event.id()));
        }

        /** The client acknowledged the directly sent event {@code eventId}; false when none is waiting. */
        boolean acknowledge(String eventId) {
            Pending p = pending.get(eventId);
            return p != null && p.acked.tryEmitValue(Boolean.TRUE).isSuccess();
        }

        private void ended() {
            direct.tryEmitComplete();
            for (Pending p : pending.values()) {
                p.written.tryEmitValue(Boolean.FALSE);
                p.acked.tryEmitValue(Boolean.FALSE);
            }
        }

        /** {@code event} was dropped on its way to this connection ({@code queue_full}). */
        public void dropped(PushEvent event, String reason) {
            PushDeliveries d = deliveries;
            if (d != null) d.dropped(event, this, reason);
        }

        /** The client's device id when it named one ({@code X-Push-Device}, {@code connect{device}}); else null. */
        public String deviceId() {
            return deviceId;
        }

        public void deviceId(String deviceId) {
            this.deviceId = deviceId == null || deviceId.isBlank() ? null : deviceId.trim();
            changed();
        }

        public Instant since() {
            return since;
        }

        public Set<Topic> topics() {
            return topics;
        }

        /** Completes when an operator disconnects this connection; {@link #closeReason()} says so. */
        public Mono<Void> closed() {
            return closed.asMono();
        }

        public String closeReason() {
            return closeReason;
        }

        public Info info() {
            return new Info(
                    id,
                    transport,
                    principal,
                    since,
                    topics.stream().map(Topic::toString).sorted().toList(),
                    registry == null ? null : registry.instance(),
                    deviceId);
        }

        private void changed() {
            PushRegistry r = registry;
            if (r != null) r.changed();
        }

        /** The topic set; every change tells the registry. */
        private final class TopicSet extends java.util.AbstractSet<Topic> {
            private final Set<Topic> set = ConcurrentHashMap.newKeySet();

            @Override
            public boolean add(Topic t) {
                boolean added = set.add(t);
                if (added) changed();
                return added;
            }

            @Override
            public boolean remove(Object o) {
                boolean removed = set.remove(o);
                if (removed) changed();
                return removed;
            }

            @Override
            public boolean contains(Object o) {
                return set.contains(o);
            }

            @Override
            public java.util.Iterator<Topic> iterator() {
                java.util.Iterator<Topic> it = set.iterator();
                return new java.util.Iterator<>() {
                    @Override
                    public boolean hasNext() {
                        return it.hasNext();
                    }

                    @Override
                    public Topic next() {
                        return it.next();
                    }

                    @Override
                    public void remove() {
                        it.remove();
                        changed();
                    }
                };
            }

            @Override
            public int size() {
                return set.size();
            }
        }
    }

    /** A connection as the admin surface shows it. */
    public record Info(
            String id,
            String transport,
            String principal,
            Instant since,
            List<String> topics,
            String instance,
            String deviceId) {
        public Info(String id, String transport, String principal, Instant since, List<String> topics) {
            this(id, transport, principal, since, topics, null, null);
        }
    }

    public Live open(String transport, Authentication auth) {
        Live l = new Live(transport, auth);
        live.put(l.id, l);
        PushHooks h = hooks;
        if (h != null) h.connected(l);
        PushRegistry r = registry;
        if (r != null) r.changed();
        return l;
    }

    /**
     * The hooks' connect decision for {@code live}: the topics a hook subscribes it to on the server
     * side, or a forbidden {@link PushException} when one rejects it (the caller closes the
     * connection). With no hooks, immediately empty.
     */
    public Mono<List<Topic>> admit(Live live) {
        PushHooks h = hooks;
        return h == null ? Mono.just(List.of()) : h.admit(live);
    }

    /** {@code topics} after the hooks' subscribe decision, or a forbidden {@link PushException}. */
    public Mono<List<Topic>> subscribing(Live live, List<Topic> topics) {
        PushHooks h = hooks;
        return h == null ? Mono.just(topics) : h.subscribing(live, topics);
    }

    /**
     * Connect then subscribe decision for a transport that subscribes once, at open (SSE, gRPC,
     * GraphQL): the topics to serve (the requested ones, or the hooks' rewrite, plus any a hook
     * subscribes the connection to), or a forbidden {@link PushException}. A rejected connection is
     * closed here, with the reason as the disconnect reason.
     */
    public Mono<List<Topic>> admitted(Live live, List<Topic> requested) {
        return admit(live)
                .flatMap(extra -> subscribing(live, requested).map(topics -> {
                    List<Topic> all = new java.util.ArrayList<>(topics);
                    for (Topic t : extra) if (!all.contains(t)) all.add(t);
                    return all;
                }))
                .doOnError(e -> {
                    live.closeReason = "rejected";
                    close(live);
                });
    }

    /** The hooks' decision about a message a client published; {@code FORWARD} with the payload when there is none. */
    public Mono<dev.appiaworks.shiguchi.proto.cursus.ClientMessageResult> clientMessage(
            Live live, Topic topic, byte[] payload) {
        PushHooks h = hooks;
        return h == null
                ? Mono.just(dev.appiaworks.shiguchi.proto.cursus.ClientMessageResult.newBuilder()
                        .setPayload(com.google.protobuf.ByteString.copyFrom(payload))
                        .build())
                : h.clientMessage(live, topic, payload);
    }

    public void close(Live connection) {
        if (live.remove(connection.id) == null) return;
        connection.ended();
        PushRegistry r = registry;
        if (r != null) r.removed(connection.id);
        PushHooks h = hooks;
        if (h != null) h.disconnected(connection, connection.closeReason);
    }

    /** The live connections of this instance. */
    java.util.Collection<Live> liveConnections() {
        return live.values();
    }

    /** How many connections of this instance listen to {@code topic}. */
    long localCount(Topic topic) {
        long n = 0;
        for (Live l : live.values()) if (l.topics.contains(topic)) n++;
        return n;
    }

    public int size() {
        return live.size();
    }

    public List<Info> list(Predicate<Info> filter) {
        List<Info> out = new ArrayList<>();
        for (Live l : live.values()) {
            Info i = l.info();
            if (filter.test(i)) out.add(i);
        }
        out.sort((a, b) -> a.since().compareTo(b.since()));
        return out;
    }

    /** The connection {@code id} of this instance, or null. */
    Live find(String id) {
        return id == null ? null : live.get(id);
    }

    /**
     * The client acknowledged the directly sent event {@code eventId} on connection {@code connectionId}.
     * Only the connection's own principal may; false when no such delivery is waiting here.
     */
    boolean acknowledgeDirect(String connectionId, String eventId, Authentication auth) {
        Live l = live.get(connectionId);
        if (l == null) return false;
        if (l.principal != null && !(TopicAuthorizer.isAuthenticated(auth) && l.principal.equals(auth.getName()))) {
            return false;
        }
        return l.acknowledge(eventId);
    }

    /** Ends connection {@code id}; false when it is not on this instance. */
    public boolean disconnect(String id, String reason) {
        Live l = live.get(id);
        if (l == null) return false;
        l.closeReason = reason == null ? "admin" : reason;
        l.closed.tryEmitEmpty();
        return true;
    }
}
