package dev.appiaworks.shiguchi.routing;

import dev.appiaworks.shiguchi.config.ServiceDefinition;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.cloud.client.ServiceInstance;

/**
 * Picks the one instance a {@code discovery-name} backend's schema is read from
 * ({@code services[].versions}).
 *
 * <p>Reading the schema through the service's channel lets the load balancer choose the instance
 * for every reflection call. With one version deployed that is harmless; during a rolling deploy it
 * is not: one load can list services on the old version and resolve them on the new, and each
 * refresh can see a different version and swap the routes back and forth. Here the version is
 * chosen by {@code versions.schema-from} and, within it, the instance by address, so the choice
 * is the same on every load and refresh while that instance lives.
 */
public final class SchemaSourceSelector {

    /** The instance to read the schema from. {@code version} is null when it publishes none. */
    public record SchemaSource(String host, int port, String version, String instanceId) {
        public String address() {
            return host + ":" + port;
        }
    }

    private SchemaSourceSelector() {}

    /**
     * The instance to read {@code service}'s schema from, or empty when the service is not reached
     * through discovery or has no instance registered (the caller then uses the service channel,
     * which fails the load as before).
     */
    public static Optional<SchemaSource> select(ServiceDefinition service, DiscoveryNameResolverProvider discovery) {
        return select(service, discovery, null);
    }

    /**
     * As {@link #select(ServiceDefinition, DiscoveryNameResolverProvider)}, with the split in
     * effect now ({@code null} for the configured one).
     */
    public static Optional<SchemaSource> select(
            ServiceDefinition service, DiscoveryNameResolverProvider discovery, Map<String, Double> liveWeights) {
        if (discovery == null || !discovery.isDiscoveryAvailable()) return Optional.empty();
        if (service.getDiscoveryName() == null || service.getDiscoveryName().isBlank()) return Optional.empty();
        if (!service.isTargetDerived()) return Optional.empty(); // a raw target wins over discovery-name
        List<ServiceInstance> instances = discovery.instances(service.getDiscoveryName());
        return select(instances, service.getVersions(), liveWeights);
    }

    /** {@link #select(ServiceDefinition, DiscoveryNameResolverProvider)} over a given instance list. */
    static Optional<SchemaSource> select(List<ServiceInstance> instances, ServiceDefinition.VersionsConfig config) {
        return select(instances, config, null);
    }

    static Optional<SchemaSource> select(
            List<ServiceInstance> instances, ServiceDefinition.VersionsConfig config, Map<String, Double> liveWeights) {
        if (instances == null || instances.isEmpty()) return Optional.empty();
        String key = metadataKey(config);
        Map<String, List<ServiceInstance>> byVersion = byVersion(instances, key);
        java.util.Set<String> served = liveWeights != null
                ? servedVersions(byVersion.keySet(), liveWeights)
                : servedVersions(byVersion.keySet(), config);
        String version = chooseVersion(served, config == null ? null : config.getSchemaFrom());
        ServiceInstance chosen = byVersion.get(version).stream()
                .min(Comparator.comparing((ServiceInstance i) -> i.getHost())
                        .thenComparingInt(DiscoveryNameResolverProvider::grpcPort))
                .orElseThrow();
        return Optional.of(new SchemaSource(
                chosen.getHost(),
                DiscoveryNameResolverProvider.grpcPort(chosen),
                version.isEmpty() ? null : version,
                chosen.getInstanceId()));
    }

    /**
     * Registered instances per version, oldest first — {@code ""} for instances publishing none —
     * for the routes report: the view of a rollout in progress.
     */
    public static Map<String, Integer> instanceCounts(
            ServiceDefinition service, DiscoveryNameResolverProvider discovery) {
        if (discovery == null || !discovery.isDiscoveryAvailable()) return Map.of();
        if (service.getDiscoveryName() == null || service.getDiscoveryName().isBlank()) return Map.of();
        Map<String, Integer> counts = new LinkedHashMap<>();
        byVersion(discovery.instances(service.getDiscoveryName()), metadataKey(service.getVersions()))
                .forEach((version, list) -> counts.put(version, list.size()));
        return counts;
    }

    private static String metadataKey(ServiceDefinition.VersionsConfig config) {
        return config == null
                        || config.getMetadataKey() == null
                        || config.getMetadataKey().isBlank()
                ? "version"
                : config.getMetadataKey();
    }

    private static Map<String, List<ServiceInstance>> byVersion(List<ServiceInstance> instances, String key) {
        Map<String, List<ServiceInstance>> byVersion = new TreeMap<>(VersionOrder.INSTANCE::compare);
        for (ServiceInstance instance : instances) {
            String version = instance.getMetadata() == null
                    ? null
                    : instance.getMetadata().get(key);
            String normalised = version == null ? "" : version.trim();
            byVersion.computeIfAbsent(normalised, v -> new ArrayList<>()).add(instance);
        }
        return byVersion;
    }

    /**
     * The running versions the schema may come from: with a traffic split, those receiving calls
     * (weight above 0), so {@code OLDEST} never serves the schema of a version that is only kept
     * for preview or drain; all of them without a split, or when none of the weighted ones runs.
     */
    static java.util.Set<String> servedVersions(
            java.util.Set<String> running, ServiceDefinition.VersionsConfig config) {
        if (config == null || config.getTraffic() == null || config.getTraffic().isEmpty()) return running;
        Map<String, Double> weights = new LinkedHashMap<>();
        config.getTraffic().forEach(split -> weights.put(split.getVersion(), (double) split.getWeight()));
        return servedVersions(running, weights);
    }

    /** {@link #servedVersions(java.util.Set, ServiceDefinition.VersionsConfig)} for a version → weight split. */
    static java.util.Set<String> servedVersions(java.util.Set<String> running, Map<String, Double> weights) {
        if (weights == null || weights.isEmpty()) return running;
        java.util.Set<String> served = new java.util.LinkedHashSet<>();
        for (String version : running) {
            weights.forEach((v, w) -> {
                if (w != null && w > 0 && VersionOrder.INSTANCE.compare(v, version) == 0) served.add(version);
            });
        }
        return served.isEmpty() ? running : served;
    }

    /** OLDEST, NEWEST, or the exact version when it is running (else OLDEST). */
    static String chooseVersion(java.util.Set<String> versions, String schemaFrom) {
        List<String> sorted = new ArrayList<>(versions);
        sorted.sort(VersionOrder.INSTANCE);
        String policy = schemaFrom == null || schemaFrom.isBlank() ? "OLDEST" : schemaFrom.trim();
        if (policy.equalsIgnoreCase("NEWEST")) return sorted.get(sorted.size() - 1);
        if (!policy.equalsIgnoreCase("OLDEST")) {
            for (String v : sorted) {
                if (VersionOrder.INSTANCE.compare(v, policy) == 0) return v;
            }
        }
        return sorted.get(0);
    }
}
