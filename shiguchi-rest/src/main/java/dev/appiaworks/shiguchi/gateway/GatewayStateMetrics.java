package dev.appiaworks.shiguchi.gateway;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.ServiceLoadStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * What the gateway currently knows about itself, as metrics
 * ({@code gateway.services.*}, {@code gateway.routes}, {@code gateway.discovery.instances},
 * {@code gateway.descriptor.cache.*}).
 *
 * <p>The health indicator answers "is the gateway all right"; these answer "what is it doing, and
 * is that changing". A backend that reloads every 30 seconds, a route count that quietly halves
 * after a refresh, a discovery service down to one instance — none of that shows up in a health
 * check that says UP, but each is visible here as a line on a graph.
 *
 * <p>Gauges read live state rather than caching it, so a refresh that changes the route count is
 * reflected at the next scrape without anything having to remember to update a number.
 */
@Component
public class GatewayStateMetrics {

    public static final String SERVICE_LOADS = "gateway.services.loads";
    public static final String SERVICES_LOADED = "gateway.services.loaded";
    public static final String SERVICES_CONFIGURED = "gateway.services.configured";
    public static final String ROUTES = "gateway.routes";
    public static final String DISCOVERY_INSTANCES = "gateway.discovery.instances";
    public static final String DESCRIPTOR_LOOKUPS = "gateway.descriptor.cache.lookups";
    public static final String DESCRIPTOR_MISSES = "gateway.descriptor.cache.misses";

    private final MeterRegistry meters;
    private final GatewayProperties properties;
    private final GrpcRouteRegistry registry;

    /** One gauge per discovery service id, created the first time that id resolves. */
    private final Map<String, AtomicInteger> discoveryInstances = new ConcurrentHashMap<>();

    public GatewayStateMetrics(
            ObjectProvider<MeterRegistry> meterRegistry,
            GatewayProperties properties,
            GrpcRouteRegistry registry,
            ObjectProvider<ServiceLoadStatus> loadStatus,
            ObjectProvider<dev.appiaworks.shiguchi.grpc.GrpcDescriptorCache> descriptorCache) {
        this.meters = meterRegistry.getIfAvailable(() -> Metrics.globalRegistry);
        this.properties = properties;
        this.registry = registry;

        Gauge.builder(SERVICES_CONFIGURED, properties, p -> p.getServices().size())
                .description("Backends named in configuration")
                .register(meters);
        Gauge.builder(SERVICES_LOADED, registry, r -> r.allContexts().size())
                .description("Backends whose schema is currently loaded")
                .register(meters);
        Gauge.builder(ROUTES, registry, r -> r.allRoutes().size())
                .description("Routes the gateway is currently serving")
                .register(meters);

        // Counters as functions rather than Micrometer Counters: the cache owns the numbers and
        // simply counts, and a FunctionCounter reports monotonic totals without the cache needing
        // to know a MeterRegistry exists. Hit rate is then misses/lookups in the dashboard, which
        // is the honest way round — a ratio computed here would hide how much traffic it is over.
        dev.appiaworks.shiguchi.grpc.GrpcDescriptorCache cache = descriptorCache.getIfAvailable();
        if (cache != null) {
            io.micrometer.core.instrument.FunctionCounter.builder(
                            DESCRIPTOR_LOOKUPS, cache, dev.appiaworks.shiguchi.grpc.GrpcDescriptorCache::lookupCount)
                    .description("gRPC method descriptor lookups")
                    .register(meters);
            io.micrometer.core.instrument.FunctionCounter.builder(
                            DESCRIPTOR_MISSES, cache, dev.appiaworks.shiguchi.grpc.GrpcDescriptorCache::missCount)
                    .description("Descriptor lookups that had to build the descriptor")
                    .register(meters);
        }

        // Load outcomes come through ServiceLoadStatus, which every loader, the retrier and the
        // refresh scheduler already report to — so one hook here covers startup, retry and
        // refresh without touching any of them.
        ServiceLoadStatus status = loadStatus.getIfAvailable();
        if (status != null) {
            status.addOutcomeListener(this::recordLoadOutcome);
        }
    }

    /**
     * One load attempt finished. Tagged with the service, the phase it happened in (startup,
     * ready-retry, retry, refresh) and whether it worked, so a backend that keeps failing to
     * reload is a rate on a graph rather than a line in a log nobody reads.
     */
    private void recordLoadOutcome(String service, String phase, boolean loaded) {
        Counter.builder(SERVICE_LOADS)
                .tag("service", service == null ? "unknown" : service)
                .tag("phase", phase == null ? "unknown" : phase)
                .tag("outcome", loaded ? "loaded" : "failed")
                .description("Schema load attempts per backend")
                .register(meters)
                .increment();
    }

    /**
     * How many instances a discovery service id resolved to, called by the resolver each time it
     * asks the registry. A gauge rather than a counter: the interesting question is "how many are
     * there now", and a drop to zero is what a dashboard should show.
     */
    public void recordDiscoveryInstances(String serviceId, int instances) {
        discoveryInstances
                .computeIfAbsent(serviceId, id -> {
                    AtomicInteger holder = new AtomicInteger();
                    Gauge.builder(DISCOVERY_INSTANCES, holder, AtomicInteger::get)
                            .tag("service", id)
                            .description("Instances the registry currently reports for a discovery-name backend")
                            .register(meters);
                    return holder;
                })
                .set(instances);
    }

    /** Visible for tests. */
    GatewayProperties properties() {
        return properties;
    }

    /** Visible for tests. */
    GrpcRouteRegistry registry() {
        return registry;
    }
}
