package dev.appiaworks.shiguchi.discovery.client;

import io.grpc.Server;
import java.util.OptionalInt;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Reads the port of the app's {@code io.grpc.Server} bean once it is started. Only registered when
 * {@code io.grpc.Server} is on the classpath, and always consulted after any user-provided
 * {@link GrpcServerPortSupplier}.
 */
class GrpcServerBeanPortSupplier implements GrpcServerPortSupplier {

    private final ObjectProvider<Server> servers;

    GrpcServerBeanPortSupplier(ObjectProvider<Server> servers) {
        this.servers = servers;
    }

    @Override
    public OptionalInt port() {
        Server server = servers.getIfAvailable();
        if (server == null || server.isShutdown()) return OptionalInt.empty();
        try {
            int port = server.getPort();
            return port > 0 ? OptionalInt.of(port) : OptionalInt.empty();
        } catch (IllegalStateException notStarted) {
            return OptionalInt.empty();
        }
    }
}
