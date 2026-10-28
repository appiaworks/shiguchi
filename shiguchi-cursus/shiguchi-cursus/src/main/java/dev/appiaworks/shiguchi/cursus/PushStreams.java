package dev.appiaworks.shiguchi.cursus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.security.core.Authentication;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * What every transport shares: the per-topic event stream with resume, and the signal that ends a
 * connection (the instance stopping, or the connection's lifetime).
 */
final class PushStreams {

    private final PushBroker broker;
    private final PushStore store;
    private final PushProperties properties;
    private final PushShutdown shutdown;
    private final PushUpstreams upstreams;
    private final PushSessions sessions;

    PushStreams(PushBroker broker, PushStore store, PushProperties properties, PushShutdown shutdown) {
        this(broker, store, properties, shutdown, null);
    }

    /** With {@code upstreams}, a topic an upstream pattern matches opens its upstream stream on the first subscriber. */
    PushStreams(
            PushBroker broker,
            PushStore store,
            PushProperties properties,
            PushShutdown shutdown,
            PushUpstreams upstreams) {
        this(broker, store, properties, shutdown, upstreams, null);
    }

    /** With the persistent sessions: a subscriber of a namespace with {@code session-expiry} resumes from its cursor. */
    PushStreams(
            PushBroker broker,
            PushStore store,
            PushProperties properties,
            PushShutdown shutdown,
            PushUpstreams upstreams,
            PushSessions sessions) {
        this.sessions = sessions;
        this.broker = broker;
        this.store = store;
        this.properties = properties;
        this.shutdown = shutdown;
        this.upstreams = upstreams;
    }

    /**
     * Live events on {@code topic}; when resuming, the kept events after {@code afterId} first. The
     * live subscription is opened before the history is read and its events queued, so nothing
     * published in between is lost; what the replay already delivered is filtered out of the queue
     * by id.
     */
    /**
     * {@link #topicStream(Topic, PushProperties.Namespace, Qos, String)} for a persistent session:
     * {@code afterId} is acknowledged, the stream starts at the session's cursor, and an
     * {@code overflow} signal precedes the events when the oldest pending ones were dropped. With
     * a null {@code session} the subscription is transient, as before.
     */
    Flux<PushEvent> topicStream(
            Topic topic, PushProperties.Namespace ns, Qos effective, String afterId, String session) {
        return topicStream(topic, ns, effective, afterId, session, null, null);
    }

    /**
     * {@link #topicStream(Topic, PushProperties.Namespace, Qos, String, String)} for a subscriber
     * that declared {@code origin} (null: none): events carrying the same {@code push.origin} are
     * not delivered, and each is counted in {@code metrics} (may be null). On a persistent session
     * the stream only filters; the session's cursor moves past the leading run of own-origin
     * events at start (see {@link PushSessions#start(PushProperties.Namespace, String, Topic,
     * String, String, PushMetrics)}), never from here.
     */
    Flux<PushEvent> topicStream(
            Topic topic,
            PushProperties.Namespace ns,
            Qos effective,
            String afterId,
            String session,
            String origin,
            PushMetrics metrics) {
        if (session == null || sessions == null || effective != Qos.AT_LEAST_ONCE || !PushSessions.persistent(ns)) {
            return topicStream(topic, ns, effective, afterId, origin, metrics);
        }
        return sessions.start(ns, session, topic, afterId, origin, metrics).flatMapMany(start -> {
            // The session tested its stored cursor; when that found no gap the inner stream tests the
            // cursor it starts from, after its replay read, so a trim racing the start still shows.
            Flux<PushEvent> events = replayed(topic, ns, effective, start.afterId(), origin, metrics, !start.gap());
            if (start.dropped() > 0) {
                events = Flux.concat(Mono.just(PushEvent.overflowSignal(topic, start.dropped())), events);
            }
            if (start.gap()) {
                events = Flux.concat(Mono.fromSupplier(() -> reset(topic, "history_gap", metrics)), events);
            }
            return events;
        });
    }

    Flux<PushEvent> topicStream(Topic topic, PushProperties.Namespace ns, Qos effective, String afterId) {
        return topicStream(topic, ns, effective, afterId, null, null);
    }

    /**
     * The stream of {@code topic}, with events of the subscriber's own {@code origin} left out. The
     * filter sits on what the replay and the live tail produce together, so the resume position
     * ({@code last}) still advances past a suppressed replayed event, and nothing suppressed
     * reaches the transport (so the delivery log never records it).
     */
    Flux<PushEvent> topicStream(
            Topic topic,
            PushProperties.Namespace ns,
            Qos effective,
            String afterId,
            String origin,
            PushMetrics metrics) {
        return replayed(topic, ns, effective, afterId, origin, metrics, true);
    }

    /** The {@code reset} signal for {@code topic}, counted. */
    private static PushEvent reset(Topic topic, String reason, PushMetrics metrics) {
        if (metrics != null) metrics.reset(topic.namespace(), reason);
        return PushEvent.resetSignal(topic, reason);
    }

    private Flux<PushEvent> replayed(
            Topic topic,
            PushProperties.Namespace ns,
            Qos effective,
            String afterId,
            String origin,
            PushMetrics metrics,
            boolean testGap) {
        Flux<PushEvent> live =
                upstreams == null ? broker.subscribe(topic) : upstreams.attach(topic, broker.subscribe(topic));
        if (effective != Qos.AT_LEAST_ONCE || afterId == null || ns.getHistorySize() <= 0) {
            // A resume id where nothing is kept cannot be honoured: say so, then go live.
            if (testGap && afterId != null) {
                return suppress(
                        Flux.concat(Mono.fromSupplier(() -> reset(topic, "no_history", metrics)), live),
                        topic,
                        origin,
                        metrics);
            }
            return suppress(live, topic, origin, metrics);
        }
        return Flux.defer(() -> {
            Sinks.Many<PushEvent> queued = Sinks.many().unicast().onBackpressureBuffer();
            Disposable connection =
                    live.subscribe(e -> queued.tryEmitNext(e), queued::tryEmitError, queued::tryEmitComplete);
            AtomicReference<String> last = new AtomicReference<>(afterId);
            Flux<PushEvent> replay =
                    store.range(topic, afterId, ns.getHistorySize()).doOnNext(e -> last.set(e.id()));
            Flux<PushEvent> tail = queued.asFlux().filter(e -> store.compareIds(e.id(), last.get()) > 0);
            Flux<PushEvent> body;
            if (testGap) {
                // The watermark is read after the replay: a trim that reached the replay is then
                // visible to it (a trim after it costs a spurious reset, never a missed one). The
                // replay is buffered for that; it is bounded by history-size.
                body = replay.collectList()
                        .flatMapMany(kept -> Flux.concat(
                                store.trimmedThrough(topic)
                                        .filter(trimmed -> store.compareIds(afterId, trimmed) < 0)
                                        .map(trimmed -> reset(topic, "history_gap", metrics)),
                                Flux.fromIterable(kept),
                                tail));
            } else {
                body = Flux.concat(replay, tail);
            }
            return suppress(body, topic, origin, metrics).doFinally(s -> connection.dispose());
        });
    }

    /** {@code events} without those stamped with {@code origin}, each counted; unchanged for a null origin. */
    private static Flux<PushEvent> suppress(Flux<PushEvent> events, Topic topic, String origin, PushMetrics metrics) {
        if (origin == null) return events;
        return events.filter(e -> {
            if (!PushOrigin.matches(e, origin)) return true;
            if (metrics != null) metrics.suppressed(topic.namespace(), 1);
            return false;
        });
    }

    /**
     * Who subscribes: the principal, a client session id, for a consumer group the group and member
     * names, and the origin the subscriber declared (null: none; a group ignores it).
     */
    record Caller(Authentication auth, String session, String group, String member, String origin) {
        static final Caller NONE = new Caller(null, null, null, null, null);

        Caller(Authentication auth, String session, String group, String member) {
            this(auth, session, group, member, null);
        }
    }

    /** The live events of {@code topic} (with its upstream attached when it has one). */
    Flux<PushEvent> live(Topic topic) {
        return upstreams == null ? broker.subscribe(topic) : upstreams.attach(topic, broker.subscribe(topic));
    }

    /**
     * {@code member}'s share of {@code topic} in consumer group {@code group} (a {@code session-key: GROUP}
     * namespace): what the store hands it, acknowledged member by member.
     */
    Flux<PushEvent> groupStream(Topic topic, PushProperties.Namespace ns, String group, String member) {
        PushGroups groups = sessions == null ? null : sessions.groups();
        if (groups == null) return Flux.error(PushException.badRequest("consumer groups are not configured"));
        return groups.memberStream(
                topic, ns, PushGroups.name("group", group), PushGroups.name("member", member), live(topic));
    }

    /** What a subscription over several topics delivers, and whether any of them is {@code AT_LEAST_ONCE}. */
    record Merged(Flux<PushEvent> events, boolean atLeastOnce) {}

    /**
     * One stream over {@code topics}: each topic's stream (with resume), merged, behind the
     * smallest {@code client-queue-size} among the namespaces. An {@code AT_LEAST_ONCE} subscriber
     * that stops reading gets an overflow error once the queue fills, so it reconnects and resumes;
     * an {@code AT_MOST_ONCE} one loses the oldest queued events and the drop is counted.
     */
    Merged merged(List<Topic> topics, Qos requested, String afterId, PushMetrics metrics) {
        return merged(topics, requested, afterId, metrics, null);
    }

    /** With the connection, so a dropped event is recorded against it. */
    Merged merged(List<Topic> topics, Qos requested, String afterId, PushMetrics metrics, PushConnections.Live live) {
        return merged(topics, requested, afterId, metrics, live, null, null);
    }

    /** With the caller, so a namespace that keeps sessions resumes each topic from the caller's cursor. */
    Merged merged(
            List<Topic> topics,
            Qos requested,
            String afterId,
            PushMetrics metrics,
            PushConnections.Live live,
            Authentication auth,
            String clientSession) {
        return merged(topics, requested, afterId, metrics, live, new Caller(auth, clientSession, null, null));
    }

    /** With the full caller: a consumer-group namespace needs {@code caller.group()} and {@code caller.member()}. */
    Merged merged(
            List<Topic> topics,
            Qos requested,
            String afterId,
            PushMetrics metrics,
            PushConnections.Live live,
            Caller caller) {
        Authentication auth = caller.auth();
        String clientSession = caller.session();
        boolean atLeastOnce = false;
        int queue = Integer.MAX_VALUE;
        List<Flux<PushEvent>> perTopic = new ArrayList<>(topics.size());
        for (Topic topic : topics) {
            PushProperties.Namespace ns = properties.namespaceOf(topic);
            Qos effective = requested == Qos.AT_MOST_ONCE ? Qos.AT_MOST_ONCE : ns.getQos();
            atLeastOnce |= effective == Qos.AT_LEAST_ONCE;
            queue = Math.min(queue, ns.getClientQueueSize());
            if (PushGroups.grouped(ns)) {
                if (requested == Qos.AT_MOST_ONCE) {
                    throw PushException.badRequest(topic + " is a consumer-group topic: qos cannot be lowered");
                }
                if (caller.group() == null || caller.group().isBlank()) {
                    throw PushException.badRequest(topic.namespace()
                            + " is a consumer-group namespace: name the group (and the member) when subscribing");
                }
                perTopic.add(groupStream(topic, ns, caller.group(), caller.member()));
                continue;
            }
            if (caller.group() != null && !caller.group().isBlank()) {
                throw PushException.badRequest(
                        topic.namespace() + " is not a consumer-group namespace; drop the group");
            }
            String session = sessions == null ? null : sessions.key(ns, auth, clientSession);
            perTopic.add(topicStream(topic, ns, effective, afterId, session, caller.origin(), metrics));
        }
        // Subscribe to every topic now, on this subscribe, not when the transport first asks for
        // data: a merge alone subscribes its inners on the first request, which over HTTP comes
        // later, so an event published between "connected" and that request would miss the topic.
        // The topics feed one fan-in sink per connection; the bounded queue policy below applies
        // to what comes out of it, as before.
        Flux<PushEvent> events = Flux.defer(() -> {
            Sinks.Many<PushEvent> fanIn = Sinks.many().unicast().onBackpressureBuffer();
            Disposable.Composite subscriptions = Disposables.composite();
            for (Flux<PushEvent> topic : perTopic) {
                subscriptions.add(topic.subscribe(
                        e -> fanIn.emitNext(e, Sinks.EmitFailureHandler.busyLooping(Duration.ofSeconds(1))),
                        fanIn::tryEmitError,
                        () -> {}));
            }
            return fanIn.asFlux().doFinally(signal -> subscriptions.dispose());
        });
        events = atLeastOnce
                ? events.onBackpressureBuffer(queue, e -> {}, BufferOverflowStrategy.ERROR)
                : events.onBackpressureBuffer(
                        queue,
                        e -> {
                            metrics.droppedQueueFull();
                            if (live != null) live.dropped(e, "queue_full");
                        },
                        BufferOverflowStrategy.DROP_OLDEST);
        return new Merged(events, atLeastOnce);
    }

    /**
     * Topics from what a client named: comma-separated values are split, blanks skipped, each must
     * parse and belong to a declared namespace, duplicates collapse, and the count stays within
     * {@code connection.max-subscriptions}.
     */
    static List<Topic> topics(List<String> raw, PushProperties properties) {
        List<String> names = new ArrayList<>();
        for (String v : raw) {
            if (v == null) continue;
            for (String part : v.split(",")) {
                if (!part.isBlank()) names.add(part.trim());
            }
        }
        if (names.isEmpty()) throw PushException.badRequest("at least one topic is required");
        int max = properties.getConnection().getMaxSubscriptions();
        if (names.size() > max) throw PushException.badRequest("at most " + max + " topics per connection");
        List<Topic> topics = new ArrayList<>(names.size());
        for (String r : names) {
            Topic topic;
            try {
                topic = Topic.parse(r);
            } catch (IllegalArgumentException e) {
                throw PushException.badRequest(e.getMessage());
            }
            if (properties.namespaceOf(topic) == null) throw PushException.unknownNamespace(topic);
            if (!topics.contains(topic)) topics.add(topic);
        }
        return topics;
    }

    /** The id to resume after, checked against the store's id format; null when none was given. */
    static String after(String last, PushStore store) {
        if (last == null || last.isBlank()) return null;
        try {
            store.compareIds(last, last);
        } catch (IllegalArgumentException e) {
            throw PushException.badRequest(e.getMessage());
        }
        return last;
    }

    /**
     * Completes when the connection must end: the instance stopping, or the lifetime elapsing
     * when there is one. Whichever comes first writes its name into {@code reason}, which starts
     * as {@code shutdown}.
     */
    Mono<Void> end(AtomicReference<String> reason) {
        Mono<Void> end = shutdown.stopping();
        Duration lifetime = lifetime(properties.getConnection());
        if (lifetime != null) {
            end = Mono.firstWithSignal(
                    end,
                    Mono.delay(lifetime, Schedulers.parallel())
                            .doOnNext(t -> reason.set("rebalance"))
                            .then());
        }
        return end;
    }

    static Duration lifetime(PushProperties.Connection conn) {
        Duration max = conn.getMaxLifetime();
        if (max == null || max.isZero() || max.isNegative()) return null;
        Duration jitter = conn.getLifetimeJitter();
        long extra = jitter == null || jitter.isZero() || jitter.isNegative()
                ? 0
                : ThreadLocalRandom.current().nextLong(jitter.toMillis() + 1);
        return max.plusMillis(extra);
    }
}
