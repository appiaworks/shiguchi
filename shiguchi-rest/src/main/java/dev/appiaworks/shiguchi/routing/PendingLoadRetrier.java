package dev.appiaworks.shiguchi.routing;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.gateway.ScgRouteRegistrar;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Keeps trying to load the backends that could not be loaded at startup.
 *
 * <p>Each route loader retries its own deferred services once, when the application is ready.
 * This component runs after them: whatever is still missing from the {@link GrpcRouteRegistry}
 * is retried every {@code shiguchi.startup.retry-interval} (30 s by default, {@code 0} turns
 * the background retry off) on a single daemon thread until every configured service is loaded —
 * so a gateway that started before one of its backends picks that backend up without a restart
 * or a refresh schedule. With {@code shiguchi.startup.fail-fast: true} the application is
 * aborted instead, naming every unloaded service and its last error.
 *
 * <p>Outcomes go to {@link ServiceLoadStatus}, which the health indicator and the readiness
 * policy read; a successful load rebuilds the derived documents through the
 * {@link SchemaReloadListener}s like the loaders' own retry does.
 */
@Component
public class PendingLoadRetrier {

    private static final Logger log = LoggerFactory.getLogger(PendingLoadRetrier.class);

    private final GatewayProperties properties;
    private final GrpcRouteRegistry registry;
    private final Optional<ScgRouteRegistrar> scgRegistrar;
    private final Supplier<List<ServiceLoader>> loaders;
    private final Supplier<List<SchemaReloadListener>> reloadListeners;
    private final ServiceLoadStatus status;

    private volatile ScheduledExecutorService executor;
    private volatile ScheduledFuture<?> scheduled;

    @org.springframework.beans.factory.annotation.Autowired
    public PendingLoadRetrier(
            GatewayProperties properties,
            GrpcRouteRegistry registry,
            Optional<ScgRouteRegistrar> scgRegistrar,
            ObjectProvider<ServiceLoader> loaders,
            ObjectProvider<SchemaReloadListener> reloadListeners,
            ServiceLoadStatus status) {
        this(
                properties,
                registry,
                scgRegistrar,
                () -> loaders.orderedStream().toList(),
                () -> reloadListeners.orderedStream().toList(),
                status);
    }

    /** Suppliers resolved on use (tests, manual wiring); the loaders and listeners are looked up lazily. */
    public PendingLoadRetrier(
            GatewayProperties properties,
            GrpcRouteRegistry registry,
            Optional<ScgRouteRegistrar> scgRegistrar,
            Supplier<List<ServiceLoader>> loaders,
            Supplier<List<SchemaReloadListener>> reloadListeners,
            ServiceLoadStatus status) {
        this.properties = properties;
        this.registry = registry;
        this.scgRegistrar = scgRegistrar;
        this.loaders = loaders;
        this.reloadListeners = reloadListeners != null ? reloadListeners : List::of;
        this.status = status;
    }

    /**
     * Runs after the loaders' own ready-time retry (lowest precedence): retries what is still
     * missing, then either aborts ({@code fail-fast}) or schedules the background retries.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void onApplicationReady() {
        List<String> pending = pending();
        if (pending.isEmpty()) return;
        attempt("ready-retry");
        pending = pending();
        if (pending.isEmpty()) return;
        if (properties.getStartup().isFailFast()) {
            throw new GatewayStartupException(pending, status);
        }
        Duration interval = properties.getStartup().getRetryInterval();
        if (interval == null || interval.isZero() || interval.isNegative()) {
            log.warn(
                    "{} service(s) not loaded and shiguchi.startup.retry-interval is 0: {} will stay unavailable until"
                            + " a refresh",
                    pending.size(),
                    pending);
            return;
        }
        log.warn("{} service(s) not loaded: {}; retrying every {}", pending.size(), pending, interval);
        start(interval);
    }

    /** Names of the configured services whose context is not registered. */
    public List<String> pending() {
        return status.notLoaded(properties, registry);
    }

    /**
     * Tries to load every pending service once; returns the names that loaded. Safe to call from
     * anywhere (the refresh endpoint, tests); the derived documents are rebuilt when anything
     * loaded.
     */
    public synchronized List<String> attempt(String phase) {
        List<String> loaded = new ArrayList<>();
        for (ServiceDefinition svc : properties.getServices()) {
            if (svc.getName() == null
                    || registry.contextByServiceName(svc.getName()).isPresent()) continue;
            ServiceLoader loader = loaderFor(svc);
            if (loader == null) {
                log.debug("No loader handles service '{}' (mode {})", svc.getName(), svc.getMode());
                continue;
            }
            try {
                ServiceEndpointContext ctx = loader.buildContext(svc);
                registry.registerContext(ctx);
                scgRegistrar.ifPresent(r -> r.register(ctx));
                status.recordLoaded(svc.getName());
                loaded.add(svc.getName());
                log.info(
                        "Service '{}' loaded on {} ({} routes)",
                        svc.getName(),
                        phase,
                        ctx.getRoutes().size());
            } catch (Exception e) {
                status.recordFailure(svc.getName(), phase, e);
                int attempts = status.attempts(svc.getName());
                // Loud the first time, then quiet, with a reminder every tenth attempt.
                if (attempts <= 1 || attempts % 10 == 0) {
                    log.warn(
                            "Service '{}' still not loadable after {} attempt(s): {}",
                            svc.getName(),
                            attempts,
                            e.getMessage());
                } else {
                    log.debug("Service '{}' load attempt {} failed: {}", svc.getName(), attempts, e.getMessage());
                }
            }
        }
        if (!loaded.isEmpty()) SchemaReloadListeners.notifyAll(reloadListeners.get(), log);
        return loaded;
    }

    private ServiceLoader loaderFor(ServiceDefinition svc) {
        for (ServiceLoader loader : loaders.get()) {
            if (loader.supports(svc)) return loader;
        }
        return null;
    }

    private void start(Duration interval) {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gateway-load-retry");
            t.setDaemon(true);
            return t;
        });
        scheduled = executor.scheduleWithFixedDelay(
                this::tick, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void tick() {
        try {
            attempt("retry");
        } catch (RuntimeException e) {
            log.warn("Load retry failed unexpectedly: {}", e.getMessage(), e);
        }
        if (pending().isEmpty()) {
            log.info("Every configured service is loaded; stopping the load retry");
            stop();
        }
    }

    /** Whether the background retry is currently scheduled. */
    public boolean isRetrying() {
        ScheduledFuture<?> f = scheduled;
        return f != null && !f.isDone();
    }

    @PreDestroy
    public void stop() {
        ScheduledFuture<?> f = scheduled;
        if (f != null) f.cancel(false);
        ScheduledExecutorService e = executor;
        if (e != null) e.shutdownNow();
    }

    /** Thrown from the ready-event listener under {@code shiguchi.startup.fail-fast}; aborts startup. */
    public static class GatewayStartupException extends IllegalStateException {
        private final List<String> services;

        GatewayStartupException(List<String> services, ServiceLoadStatus status) {
            super(describe(services, status));
            this.services = List.copyOf(services);
        }

        public List<String> getServices() {
            return services;
        }

        private static String describe(List<String> services, ServiceLoadStatus status) {
            StringBuilder sb = new StringBuilder("shiguchi.startup.fail-fast is on and ")
                    .append(services.size())
                    .append(" configured service(s) could not be loaded:");
            for (String name : services) {
                sb.append("\n  - ").append(name);
                status.failure(name).ifPresent(f -> sb.append(": ").append(f.error()));
            }
            return sb.toString();
        }
    }
}
