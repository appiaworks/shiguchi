package dev.appiaworks.shiguchi.routing;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * What the gateway is currently serving, as plain maps ready for an actuator endpoint.
 *
 * <p>This lives in {@code shiguchi-rest} rather than in either Boot line because the data and
 * its shape are identical for both; only the {@code @Endpoint} wrapper differs, and Actuator is
 * not on this module's classpath. Keeping the shape here means the two endpoints cannot drift.
 *
 * <p>Read-only, and deliberately not a debug dump. It answers the question someone actually has
 * when a call 404s — "did this route load, and where does it go" — without exposing anything a
 * caller could not already learn from the OpenAPI document or the GraphQL schema.
 */
@Component
public class GatewayRoutesReport {

    private final GatewayProperties properties;
    private final GrpcRouteRegistry registry;
    private final ServiceLoadStatus loadStatus;

    public GatewayRoutesReport(
            GatewayProperties properties,
            GrpcRouteRegistry registry,
            org.springframework.beans.factory.ObjectProvider<ServiceLoadStatus> loadStatus) {
        this.properties = properties;
        this.registry = registry;
        this.loadStatus = loadStatus.getIfAvailable(ServiceLoadStatus::new);
    }

    /** For the per-version instance counts of discovery backends; absent in tests that build the report alone. */
    private GrpcChannelFactory channelFactory;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setChannelFactory(GrpcChannelFactory channelFactory) {
        this.channelFactory = channelFactory;
    }

    /** Every configured service, whether it loaded, and the routes it contributes. */
    public Map<String, Object> report() {
        List<Map<String, Object>> services = new ArrayList<>();
        int totalRoutes = 0;

        for (ServiceLoadStatus.ServiceState state : loadStatus.snapshot(properties, registry)) {
            Map<String, Object> service = new LinkedHashMap<>();
            service.put("name", state.name());
            service.put("mode", state.mode());
            service.put("target", state.target());
            service.put("loaded", state.loaded());
            if (state.loadedAt() != null)
                service.put("loadedAt", state.loadedAt().toString());
            if (state.channelState() != null) service.put("channel", state.channelState());
            describeVersions(state.name(), service);
            ServiceLoadStatus.Failure failure = state.lastFailure();
            if (failure != null) {
                service.put("lastError", failure.error());
                service.put("lastFailureAt", failure.at().toString());
                service.put("attempts", failure.attempts());
            }

            List<Map<String, Object>> routes = routesOf(state.name());
            totalRoutes += routes.size();
            service.put("routeCount", routes.size());
            service.put("routes", routes);
            services.add(service);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("configured", properties.getServices().size());
        report.put("loaded", registry.allContexts().size());
        report.put("routeCount", totalRoutes);
        report.put("services", services);
        return report;
    }

    /**
     * Where the schema was read from and which versions are running — during a rolling deploy,
     * the answer to "which version is the gateway serving, and how far along is the rollout".
     */
    private void describeVersions(String serviceName, Map<String, Object> service) {
        registry.contextByServiceName(serviceName).ifPresent(ctx -> {
            if (ctx.getSchemaInstance() != null) service.put("schemaInstance", ctx.getSchemaInstance());
            if (ctx.getSchemaVersion() != null) service.put("schemaVersion", ctx.getSchemaVersion());
        });
        if (channelFactory == null) return;
        properties.getServices().stream()
                .filter(def -> serviceName.equals(def.getName()))
                .findFirst()
                .map(channelFactory::instanceVersions)
                .filter(counts -> !counts.isEmpty())
                .ifPresent(counts -> {
                    // Instances publishing no version are listed under "unversioned".
                    Map<String, Integer> named = new LinkedHashMap<>();
                    counts.forEach((version, n) -> named.put(version.isEmpty() ? "unversioned" : version, n));
                    service.put("instanceVersions", named);
                });
        properties.getServices().stream()
                .filter(def -> serviceName.equals(def.getName()))
                .findFirst()
                .map(ServiceDefinition::getVersions)
                .filter(ServiceDefinition.VersionsConfig::routesByVersion)
                .ifPresent(versions -> {
                    Map<String, Number> weights = new LinkedHashMap<>();
                    java.util.Optional<TrafficControl> live = channelFactory.trafficControl(serviceName);
                    if (live.isPresent()) {
                        // The split in effect, which the gateway-traffic endpoint may have changed.
                        live.get().currentWeights().forEach((v, w) -> weights.put(v, whole(w)));
                        if (live.get().overridden()) service.put("trafficOverridden", true);
                    } else {
                        versions.getTraffic().forEach(split -> weights.put(split.getVersion(), split.getWeight()));
                    }
                    if (!weights.isEmpty()) service.put("traffic", weights);
                    if (versions.getStickyHeader() != null) service.put("stickyHeader", versions.getStickyHeader());
                    if (versions.getPreviewHeader() != null) service.put("previewHeader", versions.getPreviewHeader());
                });
    }

    /** A weight as JSON shows it best: {@code 25}, not {@code 25.0}, when it is whole. */
    static Number whole(double weight) {
        return weight == Math.rint(weight) && Math.abs(weight) < 1e15 ? (Number) (long) weight : weight;
    }

    /**
     * One configured service, or {@code null} when nothing is configured under that name —
     * Actuator turns a null read into a 404, which is the right answer for a name that does not
     * exist.
     */
    public Map<String, Object> service(String name) {
        return servicesOf(report()).stream()
                .filter(service -> name.equals(service.get("name")))
                .findFirst()
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> servicesOf(Map<String, Object> report) {
        return (List<Map<String, Object>>) report.get("services");
    }

    /** The routes of one service, sorted so the output is stable between calls. */
    private List<Map<String, Object>> routesOf(String serviceName) {
        return registry
                .contextByServiceName(serviceName)
                .map(ServiceEndpointContext::getRoutes)
                .orElseGet(List::of)
                .stream()
                .map(GatewayRoutesReport::describe)
                .sorted(Comparator.comparing(r -> String.valueOf(r.get("method"))))
                .toList();
    }

    /** One route, with the fields that answer "why is my call not reaching the backend". */
    private static Map<String, Object> describe(RouteDefinition route) {
        Map<String, Object> described = new LinkedHashMap<>();
        described.put("method", route.getFullServiceName() + "/" + route.getMethodName());
        described.put("type", route.getMethodType().name());
        described.put("transport", route.getTransport().name());

        if (route.hasRestBinding()) {
            described.put("http", route.getHttpMethod() + " " + route.getHttpPath());
            if (route.getHttpBody() != null) described.put("body", route.getHttpBody());
        }
        if (route.getGraphQlName() != null) {
            described.put("graphql", route.getGraphQlName());
            if (route.getGraphQlOperationType() != null) {
                described.put("graphqlType", route.getGraphQlOperationType().name());
            }
        }
        if (route.isMerge()) described.put("merge", true);
        if (!route.getAuthScopes().isEmpty()) described.put("scopes", route.getAuthScopes());
        // Only when the proto actually said something: an "UNKNOWN" on every row is noise.
        if (route.getIdempotencyLevel() != RouteDefinition.IdempotencyLevel.IDEMPOTENCY_UNKNOWN) {
            described.put("idempotency", route.getIdempotencyLevel().name());
        }
        if (route.getCacheTtlSeconds() > 0) described.put("cacheTtlSeconds", route.getCacheTtlSeconds());
        if (route.getUpstreamUri() != null) described.put("upstream", route.getUpstreamUri());
        return described;
    }
}
