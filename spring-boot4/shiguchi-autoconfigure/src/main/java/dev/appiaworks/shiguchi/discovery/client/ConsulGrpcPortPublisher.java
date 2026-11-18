package dev.appiaworks.shiguchi.discovery.client;

import dev.appiaworks.shiguchi.config.DiscoveryMetadata;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.consul.ConsulClient;
import org.springframework.cloud.consul.discovery.ConsulDiscoveryProperties;
import org.springframework.cloud.consul.discovery.HeartbeatProperties;
import org.springframework.cloud.consul.model.http.agent.NewService;
import org.springframework.cloud.consul.serviceregistry.ConsulAutoServiceRegistration;
import org.springframework.cloud.consul.serviceregistry.ConsulRegistration;
import org.springframework.cloud.consul.serviceregistry.ConsulRegistrationCustomizer;

/**
 * Publishes the app's gRPC port in its Consul registration, as service meta ({@code grpc-port} and
 * {@code <prefix>type=grpc}), which the gateway's {@code discovery:///} name resolver reads.
 *
 * <p>Spring Cloud Consul builds the {@link ConsulRegistration} once and calls every
 * {@link ConsulRegistrationCustomizer} on it before it registers. A port that is already known goes
 * into the service meta there. A late port (a server bound to port 0) is published by
 * {@link #publish(int)}: it adds the meta and registers the same service id again, which Consul treats
 * as an update. A key the app already set to another value is left alone unless
 * {@code shiguchi.discovery.client.grpc-port} is set.
 *
 * <p><strong>Hold.</strong> Consul has no {@code STARTING} state, so the hold is the health check: the
 * service registers with its check {@code critical} and is not returned as healthy until the port is
 * published (the re-registration sets the check {@code passing}; the check's own next run takes over).
 * This only works when the app registers a check that Spring Cloud Consul does not keep passing:
 * with {@code spring.cloud.consul.discovery.heartbeat.enabled=true} the TTL scheduler marks the check
 * passing every interval, and with {@code register-health-check=false} there is no check, so there is
 * no hold and the port still arrives by re-registration. The check must also exist when the
 * registration is customized, which is the case for a fixed web server port; with a random one Spring
 * Cloud Consul creates the check after the customizers ran.
 */
public class ConsulGrpcPortPublisher implements GrpcPortSink, ConsulRegistrationCustomizer, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConsulGrpcPortPublisher.class);

    static final String CRITICAL = "critical";
    static final String PASSING = "passing";

    private final DiscoveryClientProperties properties;
    private final ObjectProvider<GrpcServerPortSupplier> suppliers;
    private final ObjectProvider<ConsulClient> client;
    private final ObjectProvider<ConsulDiscoveryProperties> discoveryProperties;
    private final ObjectProvider<HeartbeatProperties> heartbeat;
    private final ObjectProvider<ConsulAutoServiceRegistration> autoRegistration;
    private final GrpcPortHold.Scheduler scheduler;
    private final Map<String, String> published = new ConcurrentHashMap<>();
    private final AtomicBoolean holding = new AtomicBoolean();
    private volatile ConsulRegistration registration;
    private volatile Runnable cancelTimeout = () -> {};
    private volatile ScheduledExecutorService ownExecutor;

    public ConsulGrpcPortPublisher(
            DiscoveryClientProperties properties,
            ObjectProvider<GrpcServerPortSupplier> suppliers,
            ObjectProvider<ConsulClient> client,
            ObjectProvider<ConsulDiscoveryProperties> discoveryProperties,
            ObjectProvider<HeartbeatProperties> heartbeat,
            ObjectProvider<ConsulAutoServiceRegistration> autoRegistration,
            GrpcPortHold.Scheduler scheduler) {
        this.properties = properties;
        this.suppliers = suppliers;
        this.client = client;
        this.discoveryProperties = discoveryProperties;
        this.heartbeat = heartbeat;
        this.autoRegistration = autoRegistration;
        this.scheduler = scheduler != null ? scheduler : this::scheduleOnOwnThread;
    }

    /** Whether the service is currently held out of rotation (its check is critical). */
    public boolean isHolding() {
        return holding.get();
    }

    /** Called by Spring Cloud Consul before the service registers. */
    @Override
    public synchronized void customize(ConsulRegistration registration) {
        this.registration = registration;
        NewService service = registration.getService();
        OptionalInt port = resolveStaticPort();
        if (port.isPresent()) {
            Map<String, String> update = collect(port.getAsInt(), service.getMeta());
            if (!update.isEmpty()) {
                writeMeta(service, update);
                log.info("Registering gRPC port {} in the Consul service meta as {}", port.getAsInt(), update);
            }
            return;
        }
        if (!holdWanted()) return;
        NewService.Check check = service.getCheck();
        HeartbeatProperties beat = heartbeat.getIfAvailable();
        if (check == null) {
            log.info("The gRPC port is not known yet and the Consul registration has no health check at this point:"
                    + " the service cannot be held out of rotation, so a gateway may see it without grpc-port"
                    + " until it is published");
        } else if (beat != null && beat.isEnabled()) {
            log.info("The gRPC port is not known yet, but Consul heartbeat is on and keeps the check passing: the"
                    + " service cannot be held out of rotation, so a gateway may see it without grpc-port until it"
                    + " is published");
        } else {
            check.setStatus(CRITICAL);
            holding.set(true);
            log.info("The gRPC port is not known yet: registering the Consul service with a critical check until it"
                    + " is published (shiguchi.discovery.client.hold-until-published)");
        }
    }

    /**
     * Publishes {@code port} in the live registration: adds it to the service meta, lifts the hold, and
     * registers the service id again when it already registered.
     *
     * @return {@code true} when the registration now carries the port, {@code false} when it was left
     *     alone (no Consul registration, or the app set the key itself)
     */
    @Override
    public synchronized boolean publish(int port) {
        ConsulRegistration current = registration;
        if (current == null) {
            log.warn("No Consul registration: cannot publish gRPC port {}", port);
            holding.set(false);
            cancelTimeout.run();
            return false;
        }
        NewService service = current.getService();
        Map<String, String> update = collect(port, service.getMeta());
        boolean released = release(service);
        if (update.isEmpty() && !released) {
            return port == currentPort(service);
        }
        if (!update.isEmpty()) {
            writeMeta(service, update);
            log.info("Published gRPC port {} in the Consul service meta as {}", port, update);
        }
        reregister(current);
        return port == currentPort(service);
    }

    /** The port to publish, or empty: the explicit property, then the supplier beans in order. */
    public OptionalInt resolvePort() {
        return resolve(true);
    }

    OptionalInt resolveStaticPort() {
        return resolve(false);
    }

    private OptionalInt resolve(boolean includeServer) {
        Integer explicit = properties.getGrpcPort();
        if (explicit != null) return validPort(explicit);
        for (GrpcServerPortSupplier supplier : (Iterable<GrpcServerPortSupplier>) suppliers.orderedStream()::iterator) {
            if (!includeServer && supplier instanceof GrpcServerBeanPortSupplier) continue;
            OptionalInt port = supplier.port();
            if (port.isPresent() && validPort(port.getAsInt()).isPresent()) return port;
        }
        return OptionalInt.empty();
    }

    /**
     * Starts the timeout, once the app is ready and the port is still unknown. A no-op when nothing is
     * held.
     */
    synchronized void startTimeout() {
        if (!holding.get()) return;
        Duration timeout = properties.getPublishTimeout();
        if (timeout == null || timeout.isNegative()) return;
        cancelTimeout.run();
        cancelTimeout = scheduler.schedule(this::onTimeout, timeout);
    }

    private synchronized void onTimeout() {
        if (!holding.get()) return;
        log.warn(
                "No gRPC port was published within {}: releasing the Consul service WITHOUT grpc-port, so a gateway"
                        + " falls back to the HTTP port. Set shiguchi.discovery.client.grpc-port, declare a"
                        + " GrpcServerPortSupplier bean, call ConsulGrpcPortPublisher.publish(port), or turn the hold"
                        + " off with shiguchi.discovery.client.hold-until-published=false",
                properties.getPublishTimeout());
        ConsulRegistration current = registration;
        if (current == null) {
            holding.set(false);
            return;
        }
        if (release(current.getService())) reregister(current);
    }

    /** Ends the hold: the check starts passing, and its own next run takes over. */
    private boolean release(NewService service) {
        if (!holding.compareAndSet(true, false)) return false;
        cancelTimeout.run();
        NewService.Check check = service.getCheck();
        if (check != null) check.setStatus(PASSING);
        return true;
    }

    private void reregister(ConsulRegistration current) {
        ConsulAutoServiceRegistration auto = autoRegistration.getIfAvailable();
        // Not registered yet: the registration that follows carries the new meta and status.
        if (auto == null || !auto.isRunning()) return;
        ConsulClient consul = client.getIfAvailable();
        if (consul == null) return;
        ConsulDiscoveryProperties discovery = discoveryProperties.getIfAvailable();
        try {
            consul.agentServiceRegister(discovery == null ? null : discovery.getAclToken(), current.getService());
        } catch (RuntimeException e) {
            log.warn(
                    "Could not register the gRPC port with Consul; it is in the local registration and goes out"
                            + " with the next registration",
                    e);
        }
    }

    private void writeMeta(NewService service, Map<String, String> update) {
        Map<String, String> meta = new LinkedHashMap<>();
        if (service.getMeta() != null) meta.putAll(service.getMeta());
        meta.putAll(update);
        service.setMeta(meta);
    }

    private int currentPort(NewService service) {
        Map<String, String> meta = service.getMeta();
        String value = meta == null ? null : meta.get(properties.getMetadataKey());
        try {
            return value == null ? -1 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private Map<String, String> collect(int port, Map<String, String> existing) {
        Map<String, String> update = new LinkedHashMap<>();
        boolean force = properties.getGrpcPort() != null;
        put(update, existing, properties.getMetadataKey(), String.valueOf(port), force);
        if (properties.isTypeMetadata()) {
            put(update, existing, typeKey(), "grpc", force);
        }
        return update;
    }

    private void put(
            Map<String, String> update, Map<String, String> existing, String key, String value, boolean force) {
        String present = existing == null ? null : existing.get(key);
        if (value.equals(present)) return;
        // A value we wrote earlier is ours to change; anything else was set by the app.
        boolean ours = present != null && present.equals(published.get(key));
        if (present != null && !present.isBlank() && !force && !ours) {
            log.info(
                    "Not overwriting Consul meta {}={} (set explicitly); would have published {}", key, present, value);
            return;
        }
        update.put(key, value);
        published.put(key, value);
    }

    private String typeKey() {
        String prefix = properties.getMetadataPrefix();
        return (prefix == null ? DiscoveryMetadata.DEFAULT_PREFIX : prefix) + DiscoveryMetadata.TYPE;
    }

    private boolean holdWanted() {
        return properties.isHoldUntilPublished() && properties.isUpdateOnBind();
    }

    private static OptionalInt validPort(int port) {
        return port >= 1 && port <= 65535 ? OptionalInt.of(port) : OptionalInt.empty();
    }

    private Runnable scheduleOnOwnThread(Runnable task, Duration delay) {
        ScheduledExecutorService executor = ownExecutor;
        if (executor == null) {
            synchronized (this) {
                if (ownExecutor == null) {
                    ownExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "shiguchi-grpc-port-hold");
                        t.setDaemon(true);
                        return t;
                    });
                }
                executor = ownExecutor;
            }
        }
        ScheduledFuture<?> future = executor.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    @Override
    public void close() {
        cancelTimeout.run();
        ScheduledExecutorService executor = ownExecutor;
        if (executor != null) executor.shutdownNow();
    }
}
