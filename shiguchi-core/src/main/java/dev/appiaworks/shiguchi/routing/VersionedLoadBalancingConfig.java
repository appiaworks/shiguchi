package dev.appiaworks.shiguchi.routing;

import dev.appiaworks.shiguchi.config.ServiceDefinition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code loadBalancingConfig} entry for a service's {@code versions} block: the gateway's
 * {@code shiguchi_versioned} policy, which keeps one child policy per version and picks the
 * version of every call from the traffic split, a sticky key or a preview header.
 *
 * <p>Parsed-JSON shape, as gRPC hands it to the policy's provider: weights as {@code Double},
 * absent keys omitted.
 */
public final class VersionedLoadBalancingConfig {

    /** The name the policy is registered under with gRPC. */
    public static final String POLICY = "shiguchi_versioned";

    private VersionedLoadBalancingConfig() {}

    /**
     * The policy entry for {@code service}, or null when it does not route by version.
     *
     * @param childPolicy the policy used within one version, e.g. {@code round_robin}
     */
    public static Map<String, Object> forService(ServiceDefinition service, String childPolicy) {
        return forService(service, childPolicy, null);
    }

    /**
     * The policy entry for {@code service}, or null when it does not route by version.
     *
     * @param childPolicy the policy used within one version, e.g. {@code round_robin}
     * @param control the id of the live traffic control the policy reads its weights from, or null
     *     for the configured split only
     */
    public static Map<String, Object> forService(ServiceDefinition service, String childPolicy, String control) {
        ServiceDefinition.VersionsConfig versions = service.getVersions();
        if (versions == null || !versions.routesByVersion()) return null;
        Map<String, Object> config = new LinkedHashMap<>();
        config.put(
                "metadataKey",
                versions.getMetadataKey() == null || versions.getMetadataKey().isBlank()
                        ? "version"
                        : versions.getMetadataKey());
        List<Map<String, Object>> traffic = new ArrayList<>();
        if (versions.getTraffic() != null) {
            for (ServiceDefinition.TrafficSplit split : versions.getTraffic()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("version", split.getVersion().trim());
                entry.put("weight", (double) Math.max(0, split.getWeight()));
                traffic.add(entry);
            }
        }
        config.put("traffic", traffic);
        if (versions.getStickyHeader() != null && !versions.getStickyHeader().isBlank()) {
            config.put("stickyHeader", versions.getStickyHeader().trim().toLowerCase(java.util.Locale.ROOT));
        }
        if (versions.getPreviewHeader() != null && !versions.getPreviewHeader().isBlank()) {
            config.put("previewHeader", versions.getPreviewHeader().trim().toLowerCase(java.util.Locale.ROOT));
        }
        config.put("childPolicy", childPolicy == null || childPolicy.isBlank() ? "round_robin" : childPolicy);
        if (control != null) config.put("control", control);
        return Map.of(POLICY, config);
    }
}
