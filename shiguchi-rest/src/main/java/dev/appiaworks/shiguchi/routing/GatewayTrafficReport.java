package dev.appiaworks.shiguchi.routing;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * What the {@code gateway-traffic} actuator endpoint shows and changes: per version-routed
 * service, the split in effect and, per version, its weight, registered instances, calls in
 * flight, calls and failures since start, and whether it is serving, draining or drained.
 *
 * <p>Lives here rather than in the endpoint so the Boot 3 and Boot 4 endpoints, each in its own
 * autoconfigure module, share one shape and cannot drift.
 *
 * <p>A version is {@code DRAINED} when it gets no share of the split and has nothing in flight:
 * its instances can be stopped without failing a call. A preview call can still reach it until
 * they are; {@code lastCallAt} shows when one last did.
 */
@Component
public class GatewayTrafficReport {

    /** A version with a share of the split and a registered instance. */
    public static final String SERVING = "SERVING";
    /** A version with a share of the split but no registered instance: its calls go elsewhere. */
    public static final String MISSING = "MISSING";
    /** A version with no share that still has calls in flight. */
    public static final String DRAINING = "DRAINING";
    /** A version with no share and nothing in flight: safe to stop. */
    public static final String DRAINED = "DRAINED";

    private final GatewayProperties properties;
    private final GrpcChannelFactory channelFactory;
    private final RolloutController rollouts;

    public GatewayTrafficReport(
            GatewayProperties properties, GrpcChannelFactory channelFactory, RolloutController rollouts) {
        this.properties = properties;
        this.channelFactory = channelFactory;
        this.rollouts = rollouts;
    }

    /** Every version-routed service whose channel is built. */
    public Map<String, Object> report() {
        List<Map<String, Object>> services = new ArrayList<>();
        for (ServiceDefinition def : properties.getServices()) {
            Map<String, Object> one = service(def.getName());
            if (one != null) services.add(one);
        }
        return Map.of("services", services);
    }

    /** One service, or null when it does not route by version (a 404 from Actuator). */
    public Map<String, Object> service(String name) {
        ServiceDefinition def = definition(name);
        TrafficControl control = channelFactory.trafficControl(name).orElse(null);
        if (def == null || control == null) return null;

        Map<String, Integer> instances = channelFactory.instanceVersions(def);
        Map<String, Double> weights = control.currentWeights();
        boolean split = !weights.isEmpty();

        // Every version anyone knows of: registered, weighted, or that has taken a call.
        TreeMap<String, Boolean> versions = new TreeMap<>(VersionOrder.INSTANCE);
        instances.keySet().forEach(v -> versions.put(v, true));
        weights.keySet().forEach(v -> versions.putIfAbsent(v, true));
        control.allStats().keySet().forEach(v -> versions.putIfAbsent(v, true));

        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> safeToStop = new ArrayList<>();
        for (String version : versions.keySet()) {
            int running = countOf(instances, version);
            double weight = split ? control.weightOf(version) : running;
            TrafficControl.VersionStats stats = statsOf(control, version);
            long inFlight = stats == null ? 0 : stats.inFlight();
            String state;
            if (weight > 0) state = running > 0 ? SERVING : MISSING;
            else state = inFlight > 0 ? DRAINING : DRAINED;
            if (state.equals(DRAINED) && running > 0) safeToStop.add(label(version));

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("version", label(version));
            row.put("weight", GatewayRoutesReport.whole(weight));
            row.put("instances", running);
            row.put("state", state);
            row.put("inFlight", inFlight);
            row.put("calls", stats == null ? 0 : stats.calls());
            row.put("failures", stats == null ? 0 : stats.failures());
            if (stats != null && stats.lastCallAt() != null) {
                row.put("lastCallAt", stats.lastCallAt().toString());
            }
            rows.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("service", name);
        out.put("weights", whole(weights));
        out.put("configuredWeights", whole(control.configuredWeights()));
        out.put("overridden", control.overridden());
        if (control.changedAt() != null)
            out.put("changedAt", control.changedAt().toString());
        ServiceDefinition.VersionsConfig config = def.getVersions();
        if (config.getStickyHeader() != null) out.put("stickyHeader", config.getStickyHeader());
        if (config.getPreviewHeader() != null) out.put("previewHeader", config.getPreviewHeader());
        out.put("versions", rows);
        out.put("safeToStop", safeToStop);
        Map<String, Object> rollout = rollouts == null ? null : rollouts.status(name);
        if (rollout != null) out.put("rollout", rollout);
        return out;
    }

    /**
     * Applies a rollout action ({@code pause}, {@code resume}, {@code abort}, {@code promote}) and
     * reports the service.
     *
     * @throws IllegalArgumentException for an unknown action or a service without a rollout
     * @throws IllegalStateException when the action does not apply to where the rollout is
     */
    public Map<String, Object> rollout(String name, String action) {
        rollouts.act(name, action);
        return service(name);
    }

    /**
     * Sets the split of {@code name} for this gateway process and reports it.
     *
     * @throws IllegalArgumentException when the service does not route by version, or the split is
     *     invalid (see {@link TrafficControl#setWeights})
     */
    public Map<String, Object> setWeights(String name, Map<String, ? extends Number> weights) {
        control(name).setWeights(weights);
        return service(name);
    }

    /** Puts {@code name} back on its configured split and reports it. */
    public Map<String, Object> reset(String name) {
        control(name).reset();
        return service(name);
    }

    private TrafficControl control(String name) {
        return channelFactory
                .trafficControl(name)
                .orElseThrow(() -> new IllegalArgumentException("Service '" + name
                        + "' does not route by version: set versions.traffic or versions.preview-header"
                        + " on a discovery-name service"));
    }

    private ServiceDefinition definition(String name) {
        return properties.getServices().stream()
                .filter(def -> name.equals(def.getName()))
                .findFirst()
                .orElse(null);
    }

    private static int countOf(Map<String, Integer> instances, String version) {
        int n = 0;
        for (Map.Entry<String, Integer> e : instances.entrySet()) {
            if (VersionOrder.INSTANCE.compare(e.getKey(), version) == 0) n += e.getValue();
        }
        return n;
    }

    private static TrafficControl.VersionStats statsOf(TrafficControl control, String version) {
        for (Map.Entry<String, TrafficControl.VersionStats> e :
                control.allStats().entrySet()) {
            if (VersionOrder.INSTANCE.compare(e.getKey(), version) == 0) return e.getValue();
        }
        return null;
    }

    private static String label(String version) {
        return version.isEmpty() ? "unversioned" : version;
    }

    private static Map<String, Number> whole(Map<String, Double> weights) {
        Map<String, Number> out = new LinkedHashMap<>();
        weights.forEach((v, w) -> out.put(v, GatewayRoutesReport.whole(w)));
        return out;
    }
}
