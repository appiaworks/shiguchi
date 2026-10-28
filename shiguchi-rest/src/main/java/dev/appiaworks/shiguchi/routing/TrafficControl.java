package dev.appiaworks.shiguchi.routing;

import io.grpc.CallOptions;
import io.grpc.Status;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * The live traffic split of one version-routed service, and what each version is doing.
 *
 * <p>Created by {@link GrpcChannelFactory} for a service with {@code versions.traffic} or a
 * preview header, shared by every channel of that service and kept across channel rebuilds. The
 * {@code shiguchi_versioned} policy finds it by {@link #id()} — which its config carries —
 * reads the weights from it, re-picks when they change, and counts every call it routes on the
 * version that took it. The {@code gateway-traffic} actuator endpoint reads and changes it.
 *
 * <p>Weights set here live in this gateway process only, until reset or restart; the configured
 * split is what a restart (or another replica) starts from.
 */
public final class TrafficControl {

    /** Who last changed the weights. */
    public enum Origin {
        /** Nobody yet: the configured split. */
        CONFIG,
        /** An operator, through the {@code gateway-traffic} endpoint. */
        ENDPOINT,
        /** The automated rollout ({@code versions.rollout}). */
        ROLLOUT
    }

    /**
     * Set on a call's options by the service's channel when the method is unary, so the call's
     * latency is recorded: a stream's duration is its length, not how fast the version answers.
     */
    static final CallOptions.Key<Boolean> UNARY = CallOptions.Key.createWithDefault("shiguchi.unary", false);

    private static final Map<String, TrafficControl> BY_ID = new ConcurrentHashMap<>();

    private final String id = UUID.randomUUID().toString();
    private final String service;
    private final List<VersionedLoadBalancerProvider.VersionWeight> configured;
    private volatile List<VersionedLoadBalancerProvider.VersionWeight> weights;
    private volatile Instant changedAt;
    private volatile Origin origin = Origin.CONFIG;
    private volatile long generation;
    private volatile Map<String, Integer> running = Map.of();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, VersionStats> stats = new ConcurrentSkipListMap<>(VersionOrder.INSTANCE);

    private TrafficControl(String service, List<VersionedLoadBalancerProvider.VersionWeight> configured) {
        this.service = service;
        this.configured = List.copyOf(configured);
        this.weights = this.configured;
    }

    /** A control for {@code service}, findable by its id until {@link #release()}. */
    static TrafficControl create(String service, List<VersionedLoadBalancerProvider.VersionWeight> configured) {
        TrafficControl control = new TrafficControl(service, configured);
        BY_ID.put(control.id, control);
        return control;
    }

    /** The control a policy config names, or null once released (or never created). */
    static TrafficControl byId(String id) {
        return id == null ? null : BY_ID.get(id);
    }

    /** Forgets the control, when its factory closes; policies still holding it keep working. */
    void release() {
        BY_ID.remove(id);
    }

    String id() {
        return id;
    }

    public String service() {
        return service;
    }

    /** The weights in effect, in order; empty means "no split" (each version weighs its instances). */
    List<VersionedLoadBalancerProvider.VersionWeight> weights() {
        return weights;
    }

    /** The weights in effect as version → weight, in order. */
    public Map<String, Double> currentWeights() {
        return asMap(weights);
    }

    /** The configured split, which {@link #reset()} returns to. */
    public Map<String, Double> configuredWeights() {
        return asMap(configured);
    }

    /** Whether the weights were changed at runtime and differ from the configuration. */
    public boolean overridden() {
        return weights != configured;
    }

    /** When the weights were last changed at runtime, or null. */
    public Instant changedAt() {
        return changedAt;
    }

    /** Who last changed the weights. */
    public Origin origin() {
        return origin;
    }

    /** Incremented on every change, so a change by someone else can be told from one's own. */
    long generation() {
        return generation;
    }

    /**
     * The addresses per version the service's channel resolved last, oldest first ({@code ""}
     * for addresses publishing none) — what the calls can actually reach, kept up to date by the
     * version policy without asking the registry.
     */
    public Map<String, Integer> running() {
        return running;
    }

    void setRunning(Map<String, Integer> byVersion) {
        running = Map.copyOf(byVersion);
    }

    /** The weight {@code version} gets, by version order ({@code v1.10} is {@code 1.10.0}); 0 when unlisted. */
    double weightOf(String version) {
        for (VersionedLoadBalancerProvider.VersionWeight w : weights) {
            if (VersionOrder.INSTANCE.compare(w.version(), version) == 0) return w.weight();
        }
        return 0;
    }

    /**
     * Replaces the split; calls are re-picked at once, without a new channel or connection.
     *
     * @param split version → weight, relative, at least one positive
     * @throws IllegalArgumentException for an empty split, a blank or repeated version, or a
     *     negative, non-finite or all-zero weight
     */
    public void setWeights(Map<String, ? extends Number> split) {
        apply(split, Origin.ENDPOINT);
    }

    /** {@link #setWeights}, recording who made the change. */
    synchronized void apply(Map<String, ? extends Number> split, Origin by) {
        if (split == null || split.isEmpty()) throw new IllegalArgumentException("weights must name a version");
        List<VersionedLoadBalancerProvider.VersionWeight> next = new ArrayList<>();
        double total = 0;
        for (Map.Entry<String, ? extends Number> e : split.entrySet()) {
            String version = e.getKey() == null ? "" : e.getKey().trim();
            if (version.isEmpty()) throw new IllegalArgumentException("a weight without a version");
            if (e.getValue() == null) throw new IllegalArgumentException("no weight for " + version);
            double weight = e.getValue().doubleValue();
            if (!Double.isFinite(weight) || weight < 0) {
                throw new IllegalArgumentException("weight of " + version + " must be 0 or more, was " + e.getValue());
            }
            for (VersionedLoadBalancerProvider.VersionWeight w : next) {
                if (VersionOrder.INSTANCE.compare(w.version(), version) == 0) {
                    throw new IllegalArgumentException(version + " is listed twice (as " + w.version() + ")");
                }
            }
            next.add(new VersionedLoadBalancerProvider.VersionWeight(version, weight));
            total += weight;
        }
        if (total <= 0) throw new IllegalArgumentException("at least one weight must be above 0");
        weights = List.copyOf(next);
        changed(by);
    }

    /** Back to the configured split, by an operator. */
    public synchronized void reset() {
        weights = configured;
        changed(Origin.ENDPOINT);
    }

    private void changed(Origin by) {
        changedAt = Instant.now();
        origin = by;
        generation++;
        notifyListeners();
    }

    /** Calls {@code listener} after every change; the returned handle unsubscribes. */
    Runnable subscribe(Runnable listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) listener.run();
    }

    /** The counters of {@code version}, created on first use. */
    VersionStats stats(String version) {
        return stats.computeIfAbsent(version, v -> new VersionStats());
    }

    /** Every version that has taken a call, oldest first. */
    public Map<String, VersionStats> allStats() {
        return java.util.Collections.unmodifiableMap(stats);
    }

    private static Map<String, Double> asMap(List<VersionedLoadBalancerProvider.VersionWeight> list) {
        Map<String, Double> map = new LinkedHashMap<>();
        list.forEach(w -> map.put(w.version(), w.weight()));
        return map;
    }

    /**
     * What one version is doing: calls routed to it and still open, calls since start, the ones
     * that ended with a non-OK status (and with which), unary latencies, and when it last took one.
     * A retried call counts once per attempt; a streaming call stays in flight until its stream
     * closes.
     */
    public static final class VersionStats {
        private final AtomicLong inFlight = new AtomicLong();
        private final LongAdder calls = new LongAdder();
        private final LongAdder failures = new LongAdder();
        private final LongAdder[] byCode = new LongAdder[Status.Code.values().length];
        private final LatencyHistogram latency = new LatencyHistogram();
        private volatile long lastCallMillis;

        VersionStats() {
            for (int i = 0; i < byCode.length; i++) byCode[i] = new LongAdder();
        }

        void started() {
            inFlight.incrementAndGet();
            calls.increment();
            lastCallMillis = System.currentTimeMillis();
        }

        void finished(boolean ok) {
            finished(ok ? Status.Code.OK : Status.Code.UNKNOWN, -1);
        }

        /**
         * @param unaryNanos how long a unary call took, or -1 for a stream (whose length is not
         *     latency)
         */
        void finished(Status.Code code, long unaryNanos) {
            inFlight.decrementAndGet();
            if (code != Status.Code.OK) failures.increment();
            byCode[code.ordinal()].increment();
            if (unaryNanos >= 0) latency.record(unaryNanos);
        }

        /** The counters now, to subtract a later snapshot from: a window over a rollout step. */
        Snapshot snapshot() {
            long[] codes = new long[byCode.length];
            for (int i = 0; i < codes.length; i++) codes[i] = byCode[i].sum();
            return new Snapshot(calls.sum(), codes, latency.snapshot());
        }

        public long inFlight() {
            return inFlight.get();
        }

        public long calls() {
            return calls.sum();
        }

        public long failures() {
            return failures.sum();
        }

        /** When the version last took a call, or null when it never did. */
        public Instant lastCallAt() {
            long at = lastCallMillis;
            return at == 0 ? null : Instant.ofEpochMilli(at);
        }
    }

    /**
     * A version's counters at one moment. {@code calls} counts calls started; {@code byCode},
     * calls ended, per gRPC status code; {@code latency}, unary latencies per bucket.
     */
    record Snapshot(long calls, long[] byCode, long[] latency) {
        static final Snapshot EMPTY = new Snapshot(0, new long[Status.Code.values().length], null);

        /** Calls ended between {@code before} and this snapshot. */
        long ended(Snapshot before) {
            long n = 0;
            for (int i = 0; i < byCode.length; i++) n += byCode[i] - before.byCode[i];
            return n;
        }

        /** Calls ended in one of {@code codes} between {@code before} and this snapshot. */
        long ended(Snapshot before, java.util.Set<Status.Code> codes) {
            long n = 0;
            for (Status.Code code : codes) n += byCode[code.ordinal()] - before.byCode[code.ordinal()];
            return n;
        }

        /** The unary latencies recorded between {@code before} and this snapshot. */
        long[] latencySince(Snapshot before) {
            return LatencyHistogram.since(latency, before.latency);
        }
    }
}
