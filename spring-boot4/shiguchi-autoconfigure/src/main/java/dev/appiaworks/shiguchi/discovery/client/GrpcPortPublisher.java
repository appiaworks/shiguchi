package dev.appiaworks.shiguchi.discovery.client;

import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.HealthCheckHandler;
import com.netflix.discovery.EurekaClient;
import dev.appiaworks.shiguchi.config.DiscoveryMetadata;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Publishes the app's gRPC port in its Eureka registration.
 *
 * <p>Before registration the metadata goes into the instance config; after it,
 * {@link #publish(int)} calls {@code ApplicationInfoManager.registerAppMetadata}, which marks the
 * instance dirty, then asks the client for an on-demand replication so the new metadata reaches the
 * registry at once instead of on the next replication tick (30 s by default). A key the app already
 * set to another value is left alone unless {@code shiguchi.discovery.client.grpc-port} is set.
 */
public class GrpcPortPublisher implements GrpcPortSink {

    private static final Logger log = LoggerFactory.getLogger(GrpcPortPublisher.class);

    private final DiscoveryClientProperties properties;
    private final ObjectProvider<ApplicationInfoManager> infoManager;
    private final ObjectProvider<EurekaClient> eurekaClient;
    private final ObjectProvider<GrpcServerPortSupplier> suppliers;
    private final Map<String, String> published = new ConcurrentHashMap<>();

    private final ObjectProvider<GrpcPortHold> holds;

    public GrpcPortPublisher(
            DiscoveryClientProperties properties,
            ObjectProvider<ApplicationInfoManager> infoManager,
            ObjectProvider<EurekaClient> eurekaClient,
            ObjectProvider<GrpcServerPortSupplier> suppliers) {
        this(properties, infoManager, eurekaClient, suppliers, null);
    }

    public GrpcPortPublisher(
            DiscoveryClientProperties properties,
            ObjectProvider<ApplicationInfoManager> infoManager,
            ObjectProvider<EurekaClient> eurekaClient,
            ObjectProvider<GrpcServerPortSupplier> suppliers,
            ObjectProvider<GrpcPortHold> holds) {
        this.properties = properties;
        this.infoManager = infoManager;
        this.eurekaClient = eurekaClient;
        this.suppliers = suppliers;
        this.holds = holds;
    }

    /**
     * Publishes {@code port} in the live registration.
     *
     * @return {@code true} when the registration now carries the port, {@code false} when it was
     *     left alone (no Eureka instance, or the app set the key itself)
     */
    @Override
    public boolean publish(int port) {
        ApplicationInfoManager manager = infoManager.getIfAvailable();
        if (manager == null) {
            log.warn("No Eureka ApplicationInfoManager: cannot publish gRPC port {}", port);
            releaseHold(null);
            return false;
        }
        Map<String, String> current = manager.getInfo().getMetadata();
        Map<String, String> update = new LinkedHashMap<>();
        collect(update, port, current);
        if (update.isEmpty()) {
            // Nothing to write (already there, or the app set the key): do not keep the instance held.
            releaseHold(manager);
            return current != null && String.valueOf(port).equals(current.get(properties.getMetadataKey()));
        }
        // Metadata first, status second: one registration carries both, and no registration can show
        // the instance UP without the port.
        manager.registerAppMetadata(update);
        log.info("Published gRPC port {} in the Eureka registration as {}", port, update);
        releaseHold(manager);
        requestReplication();
        return true;
    }

    private void releaseHold(ApplicationInfoManager manager) {
        GrpcPortHold hold = holds == null ? null : holds.getIfAvailable();
        if (hold != null) hold.release(manager);
    }

    /** The port to publish, or empty: the explicit property, then the supplier beans in order. */
    public OptionalInt resolvePort() {
        return resolve(true);
    }

    /** Like {@link #resolvePort()} without the detected server, so no server bean is created early. */
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

    /** Writes the port metadata into the instance config's map before the instance registers. */
    void applyTo(Map<String, String> metadata, int port) {
        Map<String, String> update = new LinkedHashMap<>();
        collect(update, port, metadata);
        if (!update.isEmpty()) {
            metadata.putAll(update);
            log.info("Registering gRPC port {} in the Eureka metadata as {}", port, update);
        }
    }

    private void collect(Map<String, String> update, int port, Map<String, String> existing) {
        boolean force = properties.getGrpcPort() != null;
        put(update, existing, properties.getMetadataKey(), String.valueOf(port), force);
        if (properties.isTypeMetadata()) {
            put(update, existing, typeKey(), "grpc", force);
        }
    }

    private void put(
            Map<String, String> update, Map<String, String> existing, String key, String value, boolean force) {
        String present = existing == null ? null : existing.get(key);
        if (value.equals(present)) return;
        // A value we wrote earlier is ours to change; anything else was set by the app.
        boolean ours = present != null && present.equals(published.get(key));
        if (present != null && !present.isBlank() && !force && !ours) {
            log.info(
                    "Not overwriting Eureka metadata {}={} (set explicitly); would have published {}",
                    key,
                    present,
                    value);
            return;
        }
        update.put(key, value);
        published.put(key, value);
    }

    private String typeKey() {
        String prefix = properties.getMetadataPrefix();
        return (prefix == null ? DiscoveryMetadata.DEFAULT_PREFIX : prefix) + DiscoveryMetadata.TYPE;
    }

    /**
     * {@code registerAppMetadata} only marks the instance dirty for the replicator's next tick.
     * Re-registering the existing health check handler is the public call that also triggers
     * {@code InstanceInfoReplicator.onDemandUpdate()}.
     */
    private void requestReplication() {
        try {
            EurekaClient client = eurekaClient.getIfAvailable();
            if (client == null) return;
            HealthCheckHandler handler = client.getHealthCheckHandler();
            if (handler != null) client.registerHealthCheck(handler);
        } catch (RuntimeException e) {
            log.debug("On-demand Eureka replication not requested; the next replication tick sends it", e);
        }
    }

    private static OptionalInt validPort(int port) {
        return port >= 1 && port <= 65535 ? OptionalInt.of(port) : OptionalInt.empty();
    }
}
