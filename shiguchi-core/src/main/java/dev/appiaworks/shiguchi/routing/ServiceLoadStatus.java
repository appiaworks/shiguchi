package dev.appiaworks.shiguchi.routing;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

/**
 * What the gateway knows about each configured backend's schema load: whether it is loaded (its
 * context is in the {@link GrpcRouteRegistry}), when, with how many routes, the channel's state,
 * and — when it is not — the last failure and how many attempts there have been. The route
 * loaders and the retrier record outcomes here; the health indicator, the readiness policy and
 * the logs read them.
 *
 * <p>Loaded-ness itself is derived from the route registry, so there is one source of truth; this
 * class only remembers the failures and the attempt counts. Listeners are told after every
 * recorded outcome, on the recording thread.
 */
@Component
public class ServiceLoadStatus {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ServiceLoadStatus.class);

    /** The last recorded failure of a service that is (or was) not loadable. */
    public record Failure(Instant at, String phase, String error, int attempts) {}

    /** One configured service, as reported by the health indicator. */
    public record ServiceState(
            String name,
            String mode,
            String target,
            boolean loaded,
            Instant loadedAt,
            int routes,
            String channelState,
            Failure lastFailure) {}

    private final Map<String, Failure> failures = new ConcurrentHashMap<>();
    private final Map<String, Instant> loadedAt = new ConcurrentHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    /**
     * Notified of each load attempt and its outcome. Kept as a plain functional interface so this
     * class stays free of any metrics dependency: core knows what happened, and whoever cares
     * about counting it lives elsewhere.
     */
    public interface OutcomeListener {
        /**
         * @param service the backend that was loaded
         * @param phase where it happened: startup, ready-retry, retry, refresh
         * @param loaded whether the attempt succeeded
         */
        void onOutcome(String service, String phase, boolean loaded);
    }

    private final List<OutcomeListener> outcomeListeners = new CopyOnWriteArrayList<>();

    /** Registers a listener for load attempts. Called during startup wiring, not per request. */
    public void addOutcomeListener(OutcomeListener listener) {
        if (listener != null) outcomeListeners.add(listener);
    }

    private void notifyOutcome(String service, String phase, boolean loaded) {
        for (OutcomeListener listener : outcomeListeners) {
            try {
                listener.onOutcome(service, phase, loaded);
            } catch (RuntimeException e) {
                // A listener is an observer. Recording a load must never fail because counting it did.
                log.debug("Load outcome listener failed for service '{}'", service, e);
            }
        }
    }

    /** A load or reload of {@code service} failed in {@code phase} (startup, ready-retry, retry, refresh). */
    public void recordFailure(String service, String phase, Throwable error) {
        Failure previous = failures.get(service);
        int attempts = previous == null ? 1 : previous.attempts() + 1;
        String message =
                error == null ? "unknown" : (error.getMessage() != null ? error.getMessage() : error.toString());
        failures.put(service, new Failure(Instant.now(), phase, message, attempts));
        notifyOutcome(service, phase, false);
        notifyListeners();
    }

    /** {@code service} loaded (or reloaded) successfully; clears its failure. */
    public void recordLoaded(String service) {
        boolean reload = loadedAt.containsKey(service);
        failures.remove(service);
        loadedAt.put(service, Instant.now());
        // A first load and a reload are different events on a graph: one is startup, the other
        // means something changed (or the refresh scheduler is churning).
        notifyOutcome(service, reload ? "reload" : "initial", true);
        notifyListeners();
    }

    public Optional<Failure> failure(String service) {
        return Optional.ofNullable(failures.get(service));
    }

    /** Number of attempts recorded for a service still failing; 0 once it loaded. */
    public int attempts(String service) {
        Failure f = failures.get(service);
        return f == null ? 0 : f.attempts();
    }

    /** Called after every recorded outcome. */
    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    /** Names of the configured services whose context is not in the registry. */
    public List<String> notLoaded(GatewayProperties properties, GrpcRouteRegistry registry) {
        List<String> out = new ArrayList<>();
        for (ServiceDefinition svc : properties.getServices()) {
            if (svc.getName() == null) continue;
            if (registry.contextByServiceName(svc.getName()).isEmpty()) out.add(svc.getName());
        }
        return out;
    }

    /** Every configured service with its current state. */
    public List<ServiceState> snapshot(GatewayProperties properties, GrpcRouteRegistry registry) {
        List<ServiceState> out = new ArrayList<>();
        for (ServiceDefinition svc : properties.getServices()) {
            if (svc.getName() == null) continue;
            Optional<ServiceEndpointContext> ctx = registry.contextByServiceName(svc.getName());
            String channelState = "n/a";
            int routes = 0;
            if (ctx.isPresent()) {
                routes = ctx.get().getRoutes().size();
                if (ctx.get().getChannel() != null) {
                    try {
                        channelState = ctx.get().getChannel().getState(false).name();
                    } catch (RuntimeException e) {
                        channelState = "unknown";
                    }
                }
            }
            out.add(new ServiceState(
                    svc.getName(),
                    svc.getMode() == null ? null : svc.getMode().name(),
                    svc.isRestDownstream() ? svc.effectiveBaseUrl() : svc.getTarget(),
                    ctx.isPresent(),
                    ctx.isPresent()
                            ? loadedAt.getOrDefault(svc.getName(), ctx.get().getLoadedAt())
                            : null,
                    routes,
                    channelState,
                    failures.get(svc.getName())));
        }
        return out;
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // a listener's failure must not break the loader that reported the outcome
            }
        }
    }
}
