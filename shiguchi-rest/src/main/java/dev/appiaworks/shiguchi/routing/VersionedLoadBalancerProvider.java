package dev.appiaworks.shiguchi.routing;

import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.Metadata;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Status;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Registers the {@code shiguchi_versioned} load-balancing policy with gRPC and parses its
 * config, as {@link VersionedLoadBalancingConfig} writes it.
 *
 * <p>Registered once per JVM in gRPC's process-wide registry; the policy itself holds no state
 * beyond the channel it serves, so one registration serves every application context.
 */
public final class VersionedLoadBalancerProvider extends LoadBalancerProvider {

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    /** Registers the policy with gRPC's default registry, once. */
    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            LoadBalancerRegistry.getDefaultRegistry().register(new VersionedLoadBalancerProvider());
        }
    }

    /** The parsed policy config. {@code traffic} is in the configured order; empty means "no split". */
    record Config(
            String metadataKey,
            List<VersionWeight> traffic,
            Metadata.Key<String> stickyKey,
            Metadata.Key<String> previewKey,
            String childPolicy,
            String control) {}

    record VersionWeight(String version, double weight) {}

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public int getPriority() {
        return 5;
    }

    @Override
    public String getPolicyName() {
        return VersionedLoadBalancingConfig.POLICY;
    }

    @Override
    public LoadBalancer newLoadBalancer(LoadBalancer.Helper helper) {
        return new VersionedLoadBalancer(helper);
    }

    @Override
    public ConfigOrError parseLoadBalancingPolicyConfig(Map<String, ?> raw) {
        try {
            String metadataKey = string(raw, "metadataKey", "version");
            List<VersionWeight> traffic = new ArrayList<>();
            Object rawTraffic = raw.get("traffic");
            if (rawTraffic instanceof List<?> list) {
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> entry)) throw new IllegalArgumentException("traffic entry " + item);
                    Object version = entry.get("version");
                    Object weight = entry.get("weight");
                    if (!(version instanceof String v) || v.isBlank()) {
                        throw new IllegalArgumentException("traffic entry without a version");
                    }
                    double w = weight instanceof Number n ? n.doubleValue() : 0;
                    if (w < 0) throw new IllegalArgumentException("negative weight for " + v);
                    traffic.add(new VersionWeight(v.trim(), w));
                }
            }
            String childPolicy = string(raw, "childPolicy", "round_robin");
            if (LoadBalancerRegistry.getDefaultRegistry().getProvider(childPolicy) == null) {
                throw new IllegalArgumentException("unknown child policy " + childPolicy);
            }
            return ConfigOrError.fromConfig(new Config(
                    metadataKey,
                    List.copyOf(traffic),
                    key(string(raw, "stickyHeader", null)),
                    key(string(raw, "previewHeader", null)),
                    childPolicy,
                    string(raw, "control", null)));
        } catch (RuntimeException e) {
            return ConfigOrError.fromError(Status.UNAVAILABLE
                    .withDescription("Invalid " + getPolicyName() + " config: " + e.getMessage())
                    .withCause(e));
        }
    }

    private static String string(Map<String, ?> raw, String name, String fallback) {
        Object value = raw.get(name);
        return value instanceof String s && !s.isBlank() ? s : fallback;
    }

    private static Metadata.Key<String> key(String name) {
        return name == null ? null : Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER);
    }
}
