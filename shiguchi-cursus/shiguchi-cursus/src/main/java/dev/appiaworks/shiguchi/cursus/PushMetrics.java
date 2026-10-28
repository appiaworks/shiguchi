package dev.appiaworks.shiguchi.cursus;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The push surface's meters: {@code shiguchi.cursus.connections} (a gauge per transport) and
 * {@code .subscriptions} (a gauge), {@code .events.published}, {@code .events.delivered} and {@code .events.dropped}
 * (counters, dropped tagged by reason), and {@code .publish} (a timer around accepting one
 * event). Without a registry the counters still drive the actuator-less numbers in logs and tests.
 */
public final class PushMetrics {

    private final java.util.Map<String, AtomicInteger> connections = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicInteger subscriptions = new AtomicInteger();
    private final AtomicInteger upstreams = new AtomicInteger();
    private final Counter published;
    private final Counter delivered;
    private final Counter droppedQueue;
    private final Counter droppedUnauthorized;
    private final Counter droppedSessionOverflow;
    private final Counter acks;
    private final Counter groupAcks;
    private final java.util.Map<String, Counter> groupDeliveries = new java.util.concurrent.ConcurrentHashMap<>();
    private final Timer publishTimer;
    private final MeterRegistry registry;
    private final AtomicInteger sources = new AtomicInteger();
    private final java.util.Map<String, Counter> hookCounters = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Counter> sourceCounters = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Counter> upstreamIdCounters = new java.util.concurrent.ConcurrentHashMap<>();

    public PushMetrics(MeterRegistry registry) {
        MeterRegistry r = registry;
        this.registry = registry;
        Gauge.builder("shiguchi.cursus.sources", sources, AtomicInteger::get)
                .description("Sources this instance consumes")
                .register(r);
        for (String transport : TRANSPORTS) {
            AtomicInteger n = new AtomicInteger();
            connections.put(transport, n);
            Gauge.builder("shiguchi.cursus.connections", n, AtomicInteger::get)
                    .tag("transport", transport)
                    .description("Open push connections on this instance")
                    .register(r);
        }
        Gauge.builder("shiguchi.cursus.subscriptions", subscriptions, AtomicInteger::get)
                .description("Topic subscriptions across open connections")
                .register(r);
        Gauge.builder("shiguchi.cursus.upstreams", upstreams, AtomicInteger::get)
                .description("Upstream streams this instance owns for upstream-backed topics")
                .register(r);
        published = Counter.builder("shiguchi.cursus.events.published")
                .description("Events accepted from publishers")
                .register(r);
        delivered = Counter.builder("shiguchi.cursus.events.delivered")
                .description("Events written to a subscriber")
                .register(r);
        droppedQueue = Counter.builder("shiguchi.cursus.events.dropped")
                .tag("reason", "queue_full")
                .description("Events dropped because a subscriber was not reading")
                .register(r);
        droppedUnauthorized = Counter.builder("shiguchi.cursus.events.dropped")
                .tag("reason", "unauthorized")
                .register(r);
        droppedSessionOverflow = Counter.builder("shiguchi.cursus.events.dropped")
                .tag("reason", "session_overflow")
                .description("Events a persistent session lost because more than max-queued were pending at resume")
                .register(r);
        acks = Counter.builder("shiguchi.cursus.sessions.acks")
                .description("Cursor moves acknowledged by persistent-session subscribers")
                .register(r);
        groupAcks = Counter.builder("shiguchi.cursus.groups.acks")
                .description("Claims acknowledged by consumer-group members")
                .register(r);
        publishTimer = Timer.builder("shiguchi.cursus.publish")
                .description("Accepting one event: validation, id, history, fan-out")
                .register(r);
    }

    static final java.util.List<String> TRANSPORTS = java.util.List.of("sse", "ws", "grpc", "graphql");

    private final java.util.Map<String, Counter> sendCounters = new java.util.concurrent.ConcurrentHashMap<>();

    /** One connection of a targeted {@code Send} ended as {@code status} (delivered, not_connected, timeout, rejected). */
    public void send(String status) {
        sendCounters
                .computeIfAbsent(
                        status,
                        k -> Counter.builder("shiguchi.cursus.send")
                                .tag("status", status)
                                .description("Targeted sends by delivery status, one per matched connection")
                                .register(registry))
                .increment();
    }

    /** A connection was closed because its BFF session ended. */
    public void sessionEnded(String transport) {
        sendCounters
                .computeIfAbsent(
                        "closed:" + transport,
                        k -> Counter.builder("shiguchi.cursus.connections.closed")
                                .tag("reason", "session-ended")
                                .tag("transport", transport)
                                .description("Connections closed by the gateway, by reason")
                                .register(registry))
                .increment();
    }

    /** A poll of a BFF session failed (the store could not be read); the connection was kept. */
    public void sessionCheckFailed() {
        sendCounters
                .computeIfAbsent(
                        "check-failed",
                        k -> Counter.builder("shiguchi.cursus.session.checks")
                                .tag("outcome", "error")
                                .description("Session checks of cookie connections that failed")
                                .register(registry))
                .increment();
    }

    /** One hook call ended: {@code outcome} ok or error. */
    public void hook(String hook, String outcome) {
        hookCounters
                .computeIfAbsent(
                        hook + "|" + outcome,
                        k -> Counter.builder("shiguchi.cursus.hooks")
                                .tag("hook", hook)
                                .tag("outcome", outcome)
                                .description("Hook calls by outcome")
                                .register(registry))
                .increment();
    }

    /** One record of {@code source} ended as {@code outcome}: published or dead_lettered. */
    public void sourced(String source, String outcome) {
        sourceCounters
                .computeIfAbsent(
                        source + "|" + outcome,
                        k -> Counter.builder("shiguchi.cursus.sources.events")
                                .tag("source", source)
                                .tag("outcome", outcome)
                                .description("Source records by outcome")
                                .register(registry))
                .increment();
    }

    /** The dead-letter queue's size, as a gauge. */
    public void registerDlq(PushDlq dlq) {
        Gauge.builder("shiguchi.cursus.dlq", dlq, PushDlq::size)
                .description("Dead-lettered records held on this instance")
                .register(registry);
    }

    public void sourceOpened() {
        sources.incrementAndGet();
    }

    public void sourceClosed() {
        sources.decrementAndGet();
    }

    public int sources() {
        return sources.get();
    }

    /** One event handed to a group member: {@code first}, {@code redelivery} or {@code dead_lettered}. */
    public void groupDelivery(String outcome) {
        groupDeliveries
                .computeIfAbsent(
                        outcome,
                        k -> Counter.builder("shiguchi.cursus.groups.deliveries")
                                .tag("outcome", outcome)
                                .description("Consumer-group deliveries by outcome")
                                .register(registry))
                .increment();
    }

    /** A cursor or group position moved by an admin: {@code kind} is {@code group} or {@code session}. */
    public void seek(String kind) {
        Counter.builder("shiguchi.cursus.groups.seeks")
                .tag("kind", kind)
                .description("Admin seeks of a group position or a session cursor")
                .register(registry)
                .increment();
    }

    /** A member took over a {@code STRICT} group after another one left or lapsed. */
    public void strictTakeover() {
        Counter.builder("shiguchi.cursus.groups.strict.takeovers")
                .description("STRICT consumer groups whose active member changed")
                .register(registry)
                .increment();
    }

    public void groupAck() {
        groupAcks.increment();
    }

    public double groupAcksCount() {
        return groupAcks.count();
    }

    private final java.util.Map<String, Counter> suppressedCounters = new java.util.concurrent.ConcurrentHashMap<>();

    /** {@code n} events of {@code namespace} were not delivered to a subscriber whose declared origin is theirs. */
    public void suppressed(String namespace, long n) {
        suppressedCounters
                .computeIfAbsent(
                        namespace,
                        k -> Counter.builder("shiguchi.cursus.suppressed")
                                .tag("namespace", namespace)
                                .description("Events skipped because the subscriber declared the event's origin")
                                .register(registry))
                .increment(n);
    }

    public double suppressedCount(String namespace) {
        Counter c = suppressedCounters.get(namespace);
        return c == null ? 0 : c.count();
    }

    private final java.util.Map<String, Counter> resetCounters = new java.util.concurrent.ConcurrentHashMap<>();

    /** A {@code reset} signal went to a subscriber of {@code namespace}: {@code reason} is {@code history_gap} or {@code no_history}. */
    public void reset(String namespace, String reason) {
        resetCounters
                .computeIfAbsent(
                        namespace + "\u0000" + reason,
                        k -> Counter.builder("shiguchi.cursus.resets")
                                .tag("namespace", namespace)
                                .tag("reason", reason)
                                .description("Reset signals: a resume point older than the retained history")
                                .register(registry))
                .increment();
    }

    public double resetCount(String namespace, String reason) {
        Counter c = resetCounters.get(namespace + "\u0000" + reason);
        return c == null ? 0 : c.count();
    }

    public void droppedSessionOverflow(long n) {
        droppedSessionOverflow.increment(n);
    }

    public void acked() {
        acks.increment();
    }

    public double acksCount() {
        return acks.count();
    }

    /** An upstream message's {@code event_id_field}: {@code kept}, {@code rejected}, {@code attribute} (store without caller ids), {@code missing} or {@code duplicate}. */
    public void upstreamId(String outcome) {
        upstreamIdCounters
                .computeIfAbsent(
                        outcome,
                        k -> Counter.builder("shiguchi.cursus.upstream.ids")
                                .tag("outcome", k)
                                .description("Upstream event ids by outcome")
                                .register(registry))
                .increment();
    }

    public void upstreamOpened() {
        upstreams.incrementAndGet();
    }

    public void upstreamClosed() {
        upstreams.decrementAndGet();
    }

    public int upstreams() {
        return upstreams.get();
    }

    public void connectionOpened(String transport, int topics) {
        connections.get(transport).incrementAndGet();
        subscriptions.addAndGet(topics);
    }

    public void connectionClosed(String transport, int topics) {
        connections.get(transport).decrementAndGet();
        subscriptions.addAndGet(-topics);
    }

    public void subscribed() {
        subscriptions.incrementAndGet();
    }

    public void unsubscribed() {
        subscriptions.decrementAndGet();
    }

    /** Open connections over every transport. */
    public int connections() {
        int total = 0;
        for (AtomicInteger n : connections.values()) total += n.get();
        return total;
    }

    public int connections(String transport) {
        AtomicInteger n = connections.get(transport);
        return n == null ? 0 : n.get();
    }

    public int subscriptions() {
        return subscriptions.get();
    }

    public void published() {
        published.increment();
    }

    public void delivered() {
        delivered.increment();
    }

    public void droppedQueueFull() {
        droppedQueue.increment();
    }

    public void droppedUnauthorized() {
        droppedUnauthorized.increment();
    }

    public double publishedCount() {
        return published.count();
    }

    public double deliveredCount() {
        return delivered.count();
    }

    public double droppedCount() {
        return droppedQueue.count() + droppedUnauthorized.count();
    }

    public Timer publishTimer() {
        return publishTimer;
    }
}
