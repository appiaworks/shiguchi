package dev.appiaworks.shiguchi.refresh;

import dev.appiaworks.shiguchi.config.GatewayMode;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.gateway.ScgRouteRegistrar;
import dev.appiaworks.shiguchi.grpc.GrpcProxyHandlerRegistry;
import dev.appiaworks.shiguchi.routing.DynamicRouteLoader;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.RestDownstreamRouteLoader;
import dev.appiaworks.shiguchi.routing.SchemaReloadListener;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically polls downstream gRPC services for proto schema changes and performs
 * a hot reload when a change is detected — no application restart required.
 *
 * Enabled only when shiguchi.refresh.enabled=true. The polling interval is
 * configured via shiguchi.refresh.interval-seconds (default 300).
 *
 * Change detection: SHA-256 hash of the current FileDescriptorSet bytes is compared
 * against the hash cached in ServiceEndpointContext. A mismatch triggers reload().
 *
 * Reload sequence:
 *  1. Build a fresh ServiceEndpointContext via DynamicRouteLoader.buildContext()
 *  2. Atomically replace the old context in GrpcRouteRegistry
 *  3. Deregister old SCG routes; register new ones
 *  4. Notify every SchemaReloadListener (GraphQL SDL, OpenAPI document, UI schema) so the
 *     derived artefacts rebuild. The listeners are the only link to those optional modules.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "shiguchi.refresh", name = "enabled", havingValue = "true")
public class GatewayRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(GatewayRefreshScheduler.class);

    private final GatewayProperties properties;
    private final GrpcRouteRegistry registry;
    private final DynamicRouteLoader routeLoader;
    private final RestDownstreamRouteLoader downstreamLoader;
    private final ScgRouteRegistrar scgRegistrar;
    private final List<SchemaReloadListener> reloadListeners;

    @Autowired(required = false)
    private GrpcProxyHandlerRegistry grpcProxyHandlerRegistry;

    /** Where refresh outcomes are recorded for the health indicator; a private instance until injected. */
    private volatile dev.appiaworks.shiguchi.routing.ServiceLoadStatus loadStatus =
            new dev.appiaworks.shiguchi.routing.ServiceLoadStatus();

    @Autowired(required = false)
    public void setLoadStatus(dev.appiaworks.shiguchi.routing.ServiceLoadStatus loadStatus) {
        if (loadStatus != null) this.loadStatus = loadStatus;
    }

    public GatewayRefreshScheduler(
            GatewayProperties properties,
            GrpcRouteRegistry registry,
            DynamicRouteLoader routeLoader,
            RestDownstreamRouteLoader downstreamLoader,
            ScgRouteRegistrar scgRegistrar,
            List<SchemaReloadListener> reloadListeners) {
        this.properties = properties;
        this.registry = registry;
        this.routeLoader = routeLoader;
        this.downstreamLoader = downstreamLoader;
        this.scgRegistrar = scgRegistrar;
        this.reloadListeners = reloadListeners;
    }

    @Scheduled(fixedDelayString = "#{${shiguchi.refresh.interval-seconds:300} * 1000}")
    public void checkAndRefresh() {
        log.debug("Checking for proto schema changes...");
        for (ServiceDefinition svcDef : properties.getServices()) {
            if (!isRefreshable(svcDef)) continue;
            registry.contextByServiceName(svcDef.getName()).ifPresent(ctx -> {
                try {
                    if (svcDef.isRestDownstream()) {
                        checkDownstream(svcDef, ctx);
                    } else {
                        checkService(svcDef, ctx);
                    }
                } catch (Exception e) {
                    log.warn("Failed to check schema for service '{}': {}", svcDef.getName(), e.getMessage());
                    loadStatus.recordFailure(svcDef.getName(), "refresh", e);
                }
            });
        }
    }

    /** Triggers an immediate refresh of all DYNAMIC services; returns names of changed services. */
    public List<String> refreshAll() {
        List<String> changed = new ArrayList<>();
        for (ServiceDefinition svcDef : properties.getServices()) {
            if (!isRefreshable(svcDef)) continue;
            try {
                ServiceEndpointContext newCtx = buildContext(svcDef);
                ServiceEndpointContext oldCtx =
                        registry.contextByServiceName(svcDef.getName()).orElse(null);
                if (oldCtx == null || !Arrays.equals(oldCtx.getDescriptorHash(), newCtx.getDescriptorHash())) {
                    applyReload(svcDef.getName(), oldCtx, newCtx);
                    changed.add(svcDef.getName());
                }
            } catch (Exception e) {
                log.warn("Failed to refresh service '{}': {}", svcDef.getName(), e.getMessage());
                loadStatus.recordFailure(svcDef.getName(), "refresh", e);
            }
        }
        if (!changed.isEmpty()) postReload();
        return changed;
    }

    /** Triggers an immediate refresh of a single named DYNAMIC service. */
    public boolean refreshService(String serviceName) {
        ServiceDefinition svcDef = properties.getServices().stream()
                .filter(s -> s.getName().equals(serviceName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown service: " + serviceName));

        if (!isRefreshable(svcDef)) return false;

        try {
            ServiceEndpointContext oldCtx =
                    registry.contextByServiceName(serviceName).orElse(null);
            ServiceEndpointContext newCtx = buildContext(svcDef);
            applyReload(serviceName, oldCtx, newCtx);
            postReload();
            return true;
        } catch (Exception e) {
            log.error("Failed to refresh service '{}': {}", serviceName, e.getMessage(), e);
            loadStatus.recordFailure(serviceName, "refresh", e);
            return false;
        }
    }

    // -------------------------------------------------------------------------

    /** DYNAMIC services are re-read over reflection; REST_DOWNSTREAM ones re-fetch their document. */
    private static boolean isRefreshable(ServiceDefinition svcDef) {
        return svcDef.getMode() == GatewayMode.DYNAMIC || svcDef.isRestDownstream();
    }

    private ServiceEndpointContext buildContext(ServiceDefinition svcDef) throws Exception {
        return svcDef.isRestDownstream() ? downstreamLoader.buildContext(svcDef) : routeLoader.buildContext(svcDef);
    }

    /**
     * Re-fetches the downstream OpenAPI document; the fetch is the probe. The context is only
     * swapped when the document bytes changed, so an unchanged backend costs one GET per interval.
     */
    private void checkDownstream(ServiceDefinition svcDef, ServiceEndpointContext currentCtx) throws Exception {
        ServiceEndpointContext newCtx = downstreamLoader.buildContext(svcDef);
        if (!Arrays.equals(currentCtx.getDescriptorHash(), newCtx.getDescriptorHash())) {
            log.info("OpenAPI document change detected for '{}'; reloading", svcDef.getName());
            applyReload(svcDef.getName(), currentCtx, newCtx);
            postReload();
        }
    }

    private void checkService(ServiceDefinition svcDef, ServiceEndpointContext currentCtx) throws Exception {
        // The probe reads the schema exactly as a load would — every service, from the same pinned
        // instance — so an unchanged backend hashes the same whatever the number of its services,
        // and a rolling deploy does not look like a change on every tick.
        byte[] probeHash = routeLoader.probeHash(svcDef, currentCtx);

        if (!Arrays.equals(currentCtx.getDescriptorHash(), probeHash)) {
            log.info("Schema change detected for '{}'; reloading", svcDef.getName());
            ServiceEndpointContext newCtx = routeLoader.buildContext(svcDef);
            applyReload(svcDef.getName(), currentCtx, newCtx);
            postReload();
        }
    }

    private void applyReload(String serviceName, ServiceEndpointContext oldCtx, ServiceEndpointContext newCtx) {
        if (oldCtx != null) {
            scgRegistrar.deregister(oldCtx);
            if (grpcProxyHandlerRegistry != null) grpcProxyHandlerRegistry.invalidate(oldCtx);
        }
        registry.replaceContext(serviceName, newCtx);
        scgRegistrar.register(newCtx);
        loadStatus.recordLoaded(serviceName);
        log.info(
                "Reloaded context for service '{}': {} routes",
                serviceName,
                newCtx.getRoutes().size());
    }

    private void postReload() {
        for (SchemaReloadListener listener : reloadListeners) {
            try {
                listener.onSchemaReloaded();
            } catch (Exception e) {
                log.warn(
                        "Schema reload listener {} failed: {}",
                        listener.getClass().getSimpleName(),
                        e.getMessage());
            }
        }
    }
}
