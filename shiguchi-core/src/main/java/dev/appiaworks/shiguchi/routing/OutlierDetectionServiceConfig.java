package dev.appiaworks.shiguchi.routing;

import dev.appiaworks.shiguchi.config.ServiceDefinition;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a service's {@code outlier-detection} block into the {@code loadBalancingConfig} entry of a
 * gRPC service config: gRPC's {@code outlier_detection_experimental} policy
 * (<a href="https://github.com/grpc/proposal/blob/master/A50-xds-outlier-detection.md">gRFC A50</a>)
 * wrapping the channel's own policy as its child.
 *
 * <p>The map has the parsed-JSON shape gRPC reads: numbers as {@code Double}, durations as
 * {@code "1.5s"} strings, and {@code stdevFactor} in thousandths ({@code 1.9} becomes
 * {@code 1900}), as the gRFC defines it.
 */
public final class OutlierDetectionServiceConfig {

    /** The policy name gRPC registers for outlier detection. */
    public static final String POLICY = "outlier_detection_experimental";

    private OutlierDetectionServiceConfig() {}

    /**
     * The {@code loadBalancingConfig} list for a service, or null when outlier detection is off.
     *
     * @param childPolicy the policy outlier detection wraps, e.g. {@code round_robin}
     */
    public static List<Map<String, Object>> loadBalancingConfig(ServiceDefinition service, String childPolicy) {
        String child = childPolicy == null || childPolicy.isBlank() ? "round_robin" : childPolicy;
        return loadBalancingConfig(service, List.of(Map.of(child, Map.of())));
    }

    /**
     * {@link #loadBalancingConfig(ServiceDefinition, String)} around a child given as its own
     * {@code loadBalancingConfig} list — the version-routing policy, say, with its config.
     */
    public static List<Map<String, Object>> loadBalancingConfig(
            ServiceDefinition service, List<Map<String, Object>> childConfig) {
        ServiceDefinition.OutlierDetectionConfig od = service.getOutlierDetection();
        if (od == null || !od.isEnabled()) return null;

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("interval", seconds(od.getInterval()));
        config.put("baseEjectionTime", seconds(od.getBaseEjectionTime()));
        config.put("maxEjectionTime", seconds(od.getMaxEjectionTime()));
        config.put("maxEjectionPercent", (double) od.getMaxEjectionPercent());

        var sr = od.getSuccessRate();
        if (sr != null && sr.isEnabled()) {
            Map<String, Object> ejection = new LinkedHashMap<>();
            ejection.put("stdevFactor", (double) Math.round(sr.getStdevFactor() * 1000));
            ejection.put("enforcementPercentage", (double) sr.getEnforcementPercentage());
            ejection.put("minimumHosts", (double) sr.getMinimumHosts());
            ejection.put("requestVolume", (double) sr.getRequestVolume());
            config.put("successRateEjection", ejection);
        }
        var fp = od.getFailurePercentage();
        if (fp != null && fp.isEnabled()) {
            Map<String, Object> ejection = new LinkedHashMap<>();
            ejection.put("threshold", (double) fp.getThreshold());
            ejection.put("enforcementPercentage", (double) fp.getEnforcementPercentage());
            ejection.put("minimumHosts", (double) fp.getMinimumHosts());
            ejection.put("requestVolume", (double) fp.getRequestVolume());
            config.put("failurePercentageEjection", ejection);
        }
        config.put("childPolicy", childConfig);
        return List.of(Map.of(POLICY, config));
    }

    private static String seconds(Duration duration) {
        return RetryServiceConfig.seconds(duration);
    }
}
