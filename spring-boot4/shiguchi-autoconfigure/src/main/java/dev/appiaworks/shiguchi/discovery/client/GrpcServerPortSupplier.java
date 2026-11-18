package dev.appiaworks.shiguchi.discovery.client;

import java.util.OptionalInt;

/**
 * Tells {@link GrpcPortPublisher} which port the app's gRPC server listens on. Declare a bean of
 * this type for a server the library cannot see (Spring gRPC, a custom Netty server): return
 * {@link OptionalInt#empty()} while the port is not known yet.
 */
@FunctionalInterface
public interface GrpcServerPortSupplier {

    /** The bound gRPC port, or empty when it is not known (yet). */
    OptionalInt port();
}
