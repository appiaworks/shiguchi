package dev.appiaworks.shiguchi.discovery;

import dev.appiaworks.shiguchi.config.DiscoveryConfig.AutoRegisterConfig;
import dev.appiaworks.shiguchi.config.DiscoveryMetadata;
import dev.appiaworks.shiguchi.config.GatewayMode;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.gateway.ScgRouteRegistrar;
import dev.appiaworks.shiguchi.grpc.GrpcProxyHandlerRegistry;
import dev.appiaworks.shiguchi.routing.DynamicRouteLoader;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.SchemaReloadListener;
import dev.appiaworks.shiguchi.routing.SchemaReloadListeners;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import dev.appiaworks.shiguchi.routing.ServiceLoadStatus;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Routes the services a Spring Cloud registry lists, without a {@code shiguchi.services} entry per
 * service. Every sync lists {@link DiscoveryClient#getServices()}; a service whose instances carry
 * {@code <prefix>expose=true} (any one instance is enough) is loaded as a DYNAMIC service reached
 * through {@code discovery-name}, its schema read over reflection. A service that stays absent for
 * {@code removal-grace-syncs} consecutive syncs loses its routes again.
 *
 * <p>Never fatal: a service that cannot be loaded is logged at WARN, recorded in
 * {@link ServiceLoadStatus} and retried on the next sync. The first sync runs after the application
 * is ready.
 *
 * <p>Phase 1 handles gRPC only; a service without {@code grpc-port} (REST) is listed as skipped.
 */
@Component
@ConditionalOnProperty(prefix = "shiguchi.discovery.auto-register", name = "enabled", havingValue = "true")
public class DiscoveredServiceSynchronizer {

    private static final Logger log = LoggerFactory.getLogger(DiscoveredServiceSynchronizer.class);

    /** What the last sync did with one registry service. */
    public enum Outcome {
        REGISTERED,
        NOT_OPTED_IN,
        EXCLUDED_BY_PATTERN,
        NO_GRPC_PORT,
        EXPLICIT_CONFIG_WINS,
        PROBE_FAILED
    }

    /** One registry service as the last sync saw it. */
    public record Seen(String service, Outcome outcome, String detail, int instances, int missedSyncs) {}

    private record Tracked(ServiceDefinition definition, int misses) {}

    private final GatewayProperties properties;
    private final ObjectProvider<DiscoveryClient> discoveryClient;
    private final DynamicRouteLoader routeLoader;
    private final GrpcRouteRegistry registry;
    private final Optional<ScgRouteRegistrar> scgRegistrar;
    private final ServiceLoadStatus loadStatus;
    private final AutoRegisteredServices holder;
    private final ObjectProvider<SchemaReloadListener> reloadListeners;
    private final ObjectProvider<GrpcProxyHandlerRegistry> proxyHandlers;

    private final Map<String, Tracked> tracked = new HashMap<>();
    private final Set<String> warnedMixed = new HashSet<>();
    private volatile Map<String, Seen> lastSeen = Map.of();
    private volatile ScheduledExecutorService executor;

    public DiscoveredServiceSynchronizer(
            GatewayProperties properties,
            ObjectProvider<DiscoveryClient> discoveryClient,
            DynamicRouteLoader routeLoader,
            GrpcRouteRegistry registry,
            Optional<ScgRouteRegistrar> scgRegistrar,
            ServiceLoadStatus loadStatus,
            AutoRegisteredServices holder,
            ObjectProvider<SchemaReloadListener> reloadListeners,
            ObjectProvider<GrpcProxyHandlerRegistry> proxyHandlers) {
        this.properties = properties;
        this.discoveryClient = discoveryClient;
        this.routeLoader = routeLoader;
        this.registry = registry;
        this.scgRegistrar = scgRegistrar;
        this.loadStatus = loadStatus;
        this.holder = holder;
        this.reloadListeners = reloadListeners;
        this.proxyHandlers = proxyHandlers;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (discoveryClient.getIfAvailable() == null) {
            log.warn("shiguchi.discovery.auto-register.enabled is true but there is no DiscoveryClient bean;"
                    + " nothing will be registered");
            return;
        }
        long millis = Math.max(100, interval().toMillis());
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "shiguchi-discovery-sync");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::syncQuietly, 0, millis, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void stop() {
        ScheduledExecutorService e = executor;
        if (e != null) e.shutdownNow();
    }

    /** What the last sync saw, by service id (for the actuator endpoint). */
    public Map<String, Seen> seen() {
        return lastSeen;
    }

    private Duration interval() {
        AutoRegisterConfig cfg = config();
        return cfg.getInterval() != null
                ? cfg.getInterval()
                : properties.getClient().getDiscoveryRefresh();
    }

    private AutoRegisterConfig config() {
        return properties.getDiscovery().getAutoRegister();
    }

    private void syncQuietly() {
        try {
            sync();
        } catch (RuntimeException e) {
            log.warn("Registry sync failed: {}", e.getMessage(), e);
        }
    }

    /** One pass over the registry. Public for tests and manual triggering. */
    public synchronized void sync() {
        DiscoveryClient client = discoveryClient.getIfAvailable();
        if (client == null) return;
        List<String> ids;
        try {
            ids = client.getServices();
        } catch (RuntimeException e) {
            // A registry outage must not look like every service leaving.
            log.warn("Could not list services from the registry; keeping current routes: {}", e.getMessage());
            return;
        }
        AutoRegisterConfig cfg = config();
        String prefix = cfg.getMetadataPrefix();
        Map<String, Seen> seen = new TreeMap<>();
        Set<String> present = new HashSet<>();
        boolean changed = false;

        for (String id : ids == null ? List.<String>of() : ids) {
            List<ServiceInstance> instances;
            try {
                instances = client.getInstances(id);
            } catch (RuntimeException e) {
                log.warn("Could not list instances of '{}': {}", id, e.getMessage());
                // Unknown, not gone: do not count it as missed.
                if (tracked.containsKey(id)) present.add(id);
                continue;
            }
            if (instances == null) instances = List.of();
            List<ServiceInstance> optedIn = instances.stream()
                    .filter(i -> isTrue(meta(i, DiscoveryMetadata.key(prefix, DiscoveryMetadata.EXPOSE))))
                    .toList();
            int n = instances.size();

            if (optedIn.isEmpty()) {
                seen.put(
                        id, new Seen(id, Outcome.NOT_OPTED_IN, "no instance has " + exposeKey(prefix) + "=true", n, 0));
                continue;
            }
            if (isExplicit(id)) {
                seen.put(id, new Seen(id, Outcome.EXPLICIT_CONFIG_WINS, "shiguchi.services defines it", n, 0));
                present.add(id);
                continue;
            }
            if (!matchesPatterns(id, cfg)) {
                seen.put(id, new Seen(id, Outcome.EXCLUDED_BY_PATTERN, "include/exclude patterns", n, 0));
                continue;
            }
            if (optedIn.size() < n && warnedMixed.add(id + "#partial")) {
                log.warn(
                        "Service '{}': {} of {} instances carry {}=true; routing it on that basis",
                        id,
                        optedIn.size(),
                        n,
                        exposeKey(prefix));
            }
            if (!isGrpc(optedIn, prefix)) {
                if (warnedMixed.add(id + "#rest")) {
                    log.info("Service '{}' opts in without a gRPC port; REST discovery arrives in a later phase", id);
                }
                seen.put(
                        id, new Seen(id, Outcome.NO_GRPC_PORT, "no grpc-port metadata (REST not supported yet)", n, 0));
                continue;
            }
            warnRestSide(id, optedIn);

            present.add(id);
            Tracked existing = tracked.get(id);
            if (existing != null && registry.contextByServiceName(id).isPresent()) {
                tracked.put(id, new Tracked(existing.definition(), 0));
                seen.put(
                        id,
                        new Seen(id, Outcome.REGISTERED, existing.definition().getPathPrefix(), n, 0));
                continue;
            }
            ServiceDefinition def = definitionFor(id, optedIn, prefix);
            try {
                load(def);
                tracked.put(id, new Tracked(def, 0));
                seen.put(id, new Seen(id, Outcome.REGISTERED, def.getPathPrefix(), n, 0));
                changed = true;
            } catch (Exception e) {
                log.warn("Auto-registering '{}' failed, will retry next sync: {}", id, e.getMessage());
                loadStatus.recordFailure(id, "discovery", e);
                seen.put(id, new Seen(id, Outcome.PROBE_FAILED, String.valueOf(e.getMessage()), n, 0));
            }
        }

        int grace = Math.max(1, cfg.getRemovalGraceSyncs());
        for (var it = tracked.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            String id = entry.getKey();
            if (present.contains(id)) continue;
            int misses = entry.getValue().misses() + 1;
            if (misses >= grace) {
                unload(entry.getValue().definition());
                it.remove();
                seen.remove(id);
                changed = true;
                log.info("Service '{}' missing for {} syncs; routes removed", id, misses);
            } else {
                entry.setValue(new Tracked(entry.getValue().definition(), misses));
                seen.put(
                        id,
                        new Seen(
                                id,
                                Outcome.REGISTERED,
                                "no longer opted in or listed; removed after " + grace + " missed syncs",
                                0,
                                misses));
            }
        }
        lastSeen = seen;
        if (changed)
            SchemaReloadListeners.notifyAll(reloadListeners.orderedStream().toList(), log);
    }

    private void load(ServiceDefinition def) throws Exception {
        ServiceEndpointContext ctx = routeLoader.buildContext(def);
        registry.registerContext(ctx);
        scgRegistrar.ifPresent(r -> r.register(ctx));
        holder.add(def);
        loadStatus.recordLoaded(def.getName());
        log.info(
                "Auto-registered '{}' from the registry at {} ({} routes)",
                def.getName(),
                def.getPathPrefix(),
                ctx.getRoutes().size());
    }

    private void unload(ServiceDefinition def) {
        holder.remove(def.getName());
        registry.removeContext(def.getName()).ifPresent(ctx -> {
            scgRegistrar.ifPresent(r -> r.deregister(ctx));
            GrpcProxyHandlerRegistry proxy = proxyHandlers.getIfAvailable();
            if (proxy != null) proxy.invalidate(ctx);
            if (ctx.getChannel() != null) ctx.getChannel().shutdownNow();
        });
    }

    private ServiceDefinition definitionFor(String id, List<ServiceInstance> optedIn, String prefix) {
        ServiceDefinition def = new ServiceDefinition();
        def.setName(id);
        def.setDiscoveryName(id);
        def.setMode(GatewayMode.DYNAMIC);
        String custom = optedIn.stream()
                .map(i -> meta(i, DiscoveryMetadata.key(prefix, DiscoveryMetadata.PATH_PREFIX)))
                .filter(v -> v != null && !v.isBlank())
                .findFirst()
                .orElse(null);
        def.setPathPrefix(normalizePrefix(custom != null ? custom : id));
        return def;
    }

    static String normalizePrefix(String raw) {
        String p = raw.trim();
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p.startsWith("/") ? p : "/" + p;
    }

    private boolean isGrpc(List<ServiceInstance> optedIn, String prefix) {
        String type = optedIn.stream()
                .map(i -> meta(i, DiscoveryMetadata.key(prefix, DiscoveryMetadata.TYPE)))
                .filter(v -> v != null && !v.isBlank())
                .findFirst()
                .orElse(null);
        if (type != null) return "grpc".equalsIgnoreCase(type.trim());
        return optedIn.stream().anyMatch(i -> meta(i, DiscoveryMetadata.GRPC_PORT) != null);
    }

    private void warnRestSide(String id, List<ServiceInstance> optedIn) {
        boolean rest = optedIn.stream()
                .anyMatch(i -> meta(i, DiscoveryMetadata.OPENAPI_URL) != null
                        || meta(i, DiscoveryMetadata.OPENAPI_PATH) != null);
        if (rest && warnedMixed.add(id + "#mixed")) {
            log.warn(
                    "Service '{}' publishes gRPC and REST (OpenAPI) metadata; gRPC wins, the REST side is ignored", id);
        }
    }

    private boolean isExplicit(String id) {
        for (ServiceDefinition s : properties.getServices()) {
            if (id.equals(s.getName()) || id.equals(s.getDiscoveryName())) return true;
        }
        return false;
    }

    static boolean matchesPatterns(String id, AutoRegisterConfig cfg) {
        for (String p : cfg.getExclude()) if (glob(p).matcher(id).matches()) return false;
        if (cfg.getInclude().isEmpty()) return true;
        for (String p : cfg.getInclude()) if (glob(p).matcher(id).matches()) return true;
        return false;
    }

    private static Pattern glob(String glob) {
        StringBuilder re = new StringBuilder();
        for (char c : glob.trim().toCharArray()) {
            switch (c) {
                case '*' -> re.append(".*");
                case '?' -> re.append('.');
                default -> re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE);
    }

    private static String exposeKey(String prefix) {
        return DiscoveryMetadata.key(prefix, DiscoveryMetadata.EXPOSE);
    }

    private static boolean isTrue(String v) {
        return v != null && "true".equals(v.trim().toLowerCase(Locale.ROOT));
    }

    private static String meta(ServiceInstance i, String key) {
        Map<String, String> m = i.getMetadata();
        return m == null ? null : m.get(key);
    }
}
