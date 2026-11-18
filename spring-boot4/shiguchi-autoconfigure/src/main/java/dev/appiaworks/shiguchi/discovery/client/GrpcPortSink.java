package dev.appiaworks.shiguchi.discovery.client;

/**
 * Where a gRPC port goes once it is known: the registry the backend registers with. Implemented by
 * {@link GrpcPortPublisher} (Eureka) and {@link ConsulGrpcPortPublisher} (Consul). Call it from a
 * server that starts after the app is ready.
 */
@FunctionalInterface
public interface GrpcPortSink {

    /**
     * Publishes {@code port} in the live registration.
     *
     * @return {@code true} when the registration now carries the port, {@code false} when it was left
     *     alone (no registration, or the app set the key itself)
     */
    boolean publish(int port);
}
