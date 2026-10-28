package dev.appiaworks.shiguchi.routing;

import io.grpc.ClientStreamTracer;
import io.grpc.ConnectivityState;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.util.ForwardingClientStreamTracer;
import io.grpc.util.MultiChildLoadBalancer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Routes each call of a {@code discovery-name} channel to one version of the backend
 * ({@code shiguchi_versioned}, configured by {@code services[].versions}).
 *
 * <p>The resolver tags every address with its instance's registry metadata; this policy groups the
 * addresses by the version under {@code metadata-key} and runs one child policy
 * ({@code round_robin} by default) per version. A call's version is decided by the picker:
 *
 * <ol>
 *   <li>the preview header, when the call names a version that has a ready instance;
 *   <li>otherwise the traffic split: versions with a positive weight, laid out oldest first on a
 *       line as long as the total weight, and a point on it — the hash of the sticky key, or a
 *       random number — picks the version. Oldest-first layout means raising the newest version's
 *       weight only moves callers onto it;
 *   <li>when no weighted version has a ready instance, any version that has one, so a rollout
 *       whose new version is not up yet keeps answering.
 * </ol>
 *
 * <p>Without a split (a preview header alone), each version weighs as many instances as it has,
 * which is the plain round-robin over every instance.
 *
 * <p>The weights come from the service's {@link TrafficControl} when the config names one: a
 * change there re-picks at once, on the same connections. Every call routed is counted on its
 * version there too — in flight, total, failed — which is how a draining version is seen to be
 * idle.
 */
final class VersionedLoadBalancer extends MultiChildLoadBalancer {

    private volatile VersionedLoadBalancerProvider.Config config;

    /** Addresses per version in the last resolution, the weights when no split is configured. */
    private volatile Map<String, Integer> instanceCounts = Map.of();

    /** The live split and counters; null when the config names none (the configured split only). */
    private TrafficControl control;

    private Runnable unsubscribe;

    VersionedLoadBalancer(Helper helper) {
        super(helper);
    }

    @Override
    public Status acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
        Object parsed = resolvedAddresses.getLoadBalancingPolicyConfig();
        if (parsed instanceof VersionedLoadBalancerProvider.Config c) config = c;
        if (config == null) {
            return Status.INTERNAL.withDescription("shiguchi_versioned received no config");
        }
        TrafficControl named = TrafficControl.byId(config.control());
        // Before the addresses are grouped, so the control learns what this resolution runs.
        if (named != null && named != control) {
            if (unsubscribe != null) unsubscribe.run();
            control = named;
            // Weights changed elsewhere: re-pick on the policy's own thread, as gRPC requires.
            unsubscribe = named.subscribe(
                    () -> getHelper().getSynchronizationContext().execute(this::repick));
        }
        return super.acceptResolvedAddresses(resolvedAddresses);
    }

    private void repick() {
        if (!getChildLbStates().isEmpty()) updateOverallBalancingState();
    }

    @Override
    public void shutdown() {
        if (unsubscribe != null) unsubscribe.run();
        unsubscribe = null;
        super.shutdown();
    }

    /** One child per version, oldest first; the key is the version ({@code ""} for none). */
    @Override
    protected Map<Object, ResolvedAddresses> createChildAddressesMap(ResolvedAddresses resolved) {
        Map<String, List<EquivalentAddressGroup>> byVersion = new TreeMap<>(VersionOrder.INSTANCE);
        for (EquivalentAddressGroup eag : resolved.getAddresses()) {
            byVersion.computeIfAbsent(versionOf(eag), v -> new ArrayList<>()).add(eag);
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        byVersion.forEach((version, addresses) -> counts.put(version, addresses.size()));
        instanceCounts = counts;
        if (control != null) control.setRunning(counts);
        Map<Object, ResolvedAddresses> children = new LinkedHashMap<>();
        byVersion.forEach((version, addresses) -> children.put(
                version,
                resolved.toBuilder()
                        .setAddresses(addresses)
                        .setLoadBalancingPolicyConfig(null)
                        .build()));
        return children;
    }

    private String versionOf(EquivalentAddressGroup eag) {
        Map<String, String> metadata = eag.getAttributes().get(DiscoveryNameResolverProvider.INSTANCE_METADATA);
        String version = metadata == null ? null : metadata.get(config.metadataKey());
        return version == null ? "" : version.trim();
    }

    @Override
    protected ChildLbState createChildLbState(Object key) {
        LoadBalancerProvider child = LoadBalancerRegistry.getDefaultRegistry().getProvider(config.childPolicy());
        return new ChildLbState(key, child);
    }

    @Override
    protected void updateOverallBalancingState() {
        List<VersionPicker.Group> groups = new ArrayList<>();
        ConnectivityState overall = null;
        for (ChildLbState child : getChildLbStates()) {
            String version = (String) child.getKey();
            overall = aggregateState(overall, child.getCurrentState());
            groups.add(new VersionPicker.Group(
                    version,
                    weightOf(version),
                    child.getCurrentState(),
                    child.getCurrentPicker(),
                    control == null ? null : control.stats(version)));
        }
        if (overall == null) return;
        currentConnectivityState = overall;
        getHelper().updateBalancingState(overall, new VersionPicker(groups, config.stickyKey(), config.previewKey()));
    }

    /** The version's weight, live or configured; without a split, its instance count (plain round-robin). */
    private double weightOf(String version) {
        List<VersionedLoadBalancerProvider.VersionWeight> traffic =
                control != null ? control.weights() : config.traffic();
        if (traffic.isEmpty()) {
            return instanceCounts.getOrDefault(version, 0);
        }
        for (VersionedLoadBalancerProvider.VersionWeight split : traffic) {
            if (VersionOrder.INSTANCE.compare(split.version(), version) == 0) return split.weight();
        }
        return 0;
    }

    /** Picks the version, then hands the call to that version's own picker. */
    static final class VersionPicker extends SubchannelPicker {

        record Group(
                String version,
                double weight,
                ConnectivityState state,
                SubchannelPicker picker,
                TrafficControl.VersionStats stats) {
            Group(String version, double weight, ConnectivityState state, SubchannelPicker picker) {
                this(version, weight, state, picker, null);
            }

            boolean ready() {
                return state == ConnectivityState.READY;
            }
        }

        private final List<Group> groups;
        private final Metadata.Key<String> stickyKey;
        private final Metadata.Key<String> previewKey;

        VersionPicker(List<Group> groups, Metadata.Key<String> stickyKey, Metadata.Key<String> previewKey) {
            this.groups = List.copyOf(groups);
            this.stickyKey = stickyKey;
            this.previewKey = previewKey;
        }

        @Override
        public PickResult pickSubchannel(PickSubchannelArgs args) {
            Choice choice = choose(args.getHeaders());
            return switch (choice.kind()) {
                case PICK -> counted(choice.group(), choice.group().picker().pickSubchannel(args));
                // The version this call belongs to is still connecting: wait for it rather than
                // send the call to another version. gRPC re-picks once the state changes.
                case WAIT -> PickResult.withNoResult();
                case FAIL ->
                    groups.isEmpty()
                            ? PickResult.withError(Status.UNAVAILABLE.withDescription("no instance of any version"))
                            // Every version is failing: the first one's picker carries its error.
                            : groups.get(0).picker().pickSubchannel(args);
            };
        }

        /** The pick, with its stream counted on the version's stats once it starts. */
        private static PickResult counted(Group group, PickResult result) {
            if (group.stats() == null || result.getSubchannel() == null) return result;
            return result.copyWithStreamTracerFactory(
                    new CountingTracerFactory(group.stats(), result.getStreamTracerFactory()));
        }

        enum Kind {
            PICK,
            WAIT,
            FAIL
        }

        record Choice(Kind kind, Group group) {
            static final Choice WAIT = new Choice(Kind.WAIT, null);
            static final Choice FAIL = new Choice(Kind.FAIL, null);

            static Choice of(Group group) {
                return new Choice(Kind.PICK, group);
            }
        }

        private static boolean connecting(Group g) {
            return g.state() == ConnectivityState.CONNECTING || g.state() == ConnectivityState.IDLE;
        }

        /** Which version a call goes to, or whether it waits or fails. */
        Choice choose(Metadata headers) {
            if (previewKey != null && headers != null) {
                String wanted = headers.get(previewKey);
                if (wanted != null && !wanted.isBlank()) {
                    for (Group g : groups) {
                        if (VersionOrder.INSTANCE.compare(g.version(), wanted.trim()) != 0) continue;
                        if (g.ready()) return Choice.of(g);
                        if (connecting(g)) return Choice.WAIT;
                    }
                    // Not running, or failing: the call is served by the split instead.
                }
            }
            // The line is laid out over every weighted version that is up or coming up, so where a
            // caller lands — and a sticky caller always lands in the same place — does not depend
            // on which version happened to connect first. Landing on one still connecting waits.
            List<Group> candidates = new ArrayList<>();
            double total = 0;
            for (Group g : groups) {
                if (g.weight() > 0 && (g.ready() || connecting(g))) {
                    candidates.add(g);
                    total += g.weight();
                }
            }
            if (candidates.isEmpty()) {
                // Every weighted version is down or not running: whichever version is up serves the
                // call, which beats failing it.
                for (Group g : groups) {
                    if (g.ready()) return Choice.of(g);
                }
                for (Group g : groups) {
                    if (connecting(g)) return Choice.WAIT;
                }
                return Choice.FAIL;
            }
            double point = position(headers) * total;
            double edge = 0;
            Group landed = candidates.get(candidates.size() - 1);
            for (Group g : candidates) {
                edge += g.weight();
                if (point < edge) {
                    landed = g;
                    break;
                }
            }
            return landed.ready() ? Choice.of(landed) : Choice.WAIT;
        }

        /** Where on [0, 1) the call falls: the sticky key's hash, or random without one. */
        private double position(Metadata headers) {
            String value = stickyKey == null || headers == null ? null : headers.get(stickyKey);
            if (value == null || value.isBlank() || value.equals("anonymous")) {
                return ThreadLocalRandom.current().nextDouble();
            }
            return (mix(value.hashCode()) >>> 11) / (double) (1L << 53);
        }

        /** A 64-bit finaliser (SplitMix64), so neighbouring ids do not land side by side. */
        private static long mix(long z) {
            z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
            z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
            return z ^ (z >>> 33);
        }
    }

    /** Counts a stream on its version, in front of the child policy's own tracer if it set one. */
    private static final class CountingTracerFactory extends ClientStreamTracer.Factory {
        private static final ClientStreamTracer NONE = new ClientStreamTracer() {};

        private final TrafficControl.VersionStats stats;
        private final ClientStreamTracer.Factory inner;

        CountingTracerFactory(TrafficControl.VersionStats stats, ClientStreamTracer.Factory inner) {
            this.stats = stats;
            this.inner = inner;
        }

        @Override
        public ClientStreamTracer newClientStreamTracer(ClientStreamTracer.StreamInfo info, Metadata headers) {
            ClientStreamTracer delegate = inner == null ? NONE : inner.newClientStreamTracer(info, headers);
            boolean unary = Boolean.TRUE.equals(info.getCallOptions().getOption(TrafficControl.UNARY));
            long startedAt = System.nanoTime();
            stats.started();
            AtomicBoolean closed = new AtomicBoolean();
            return new ForwardingClientStreamTracer() {
                @Override
                protected ClientStreamTracer delegate() {
                    return delegate;
                }

                @Override
                public void streamClosed(Status status) {
                    if (closed.compareAndSet(false, true)) {
                        stats.finished(status.getCode(), unary ? System.nanoTime() - startedAt : -1);
                    }
                    super.streamClosed(status);
                }
            };
        }
    }
}
