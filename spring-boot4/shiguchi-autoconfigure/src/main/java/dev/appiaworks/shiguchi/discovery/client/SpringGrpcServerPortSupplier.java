package dev.appiaworks.shiguchi.discovery.client;

import java.util.OptionalInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.grpc.server.lifecycle.GrpcServerStartedEvent;

/**
 * Finds the port of a Spring gRPC server, which keeps its {@code io.grpc.Server} to itself (no bean).
 * The port is the first of: the one the server reported when it started ({@link GrpcServerStartedEvent},
 * the only source for {@code spring.grpc.server.port=0}); the fixed {@code spring.grpc.server.port}
 * property, which is also what lets a fixed port go into the registration before the server starts.
 *
 * <p>The start event is published while the context refreshes, before {@code ApplicationReadyEvent},
 * where the registration publishes whatever {@link GrpcPortPublisher#resolvePort()} then finds. A
 * server that starts after the app is ready is published from the event itself.
 */
class SpringGrpcServerPortSupplier implements GrpcServerPortSupplier {

    static final String PORT_PROPERTY = "spring.grpc.server.port";

    private static final Logger log = LoggerFactory.getLogger(SpringGrpcServerPortSupplier.class);

    private final Environment environment;
    private final ObjectProvider<GrpcPortSink> sinks;
    private final ObjectProvider<DiscoveryClientProperties> properties;
    private volatile int boundPort;
    private volatile boolean ready;

    SpringGrpcServerPortSupplier(
            Environment environment,
            ObjectProvider<GrpcPortSink> sinks,
            ObjectProvider<DiscoveryClientProperties> properties) {
        this.environment = environment;
        this.sinks = sinks;
        this.properties = properties;
    }

    @Override
    public OptionalInt port() {
        int bound = boundPort;
        if (bound > 0) return OptionalInt.of(bound);
        Integer configured = environment.getProperty(PORT_PROPERTY, Integer.class);
        return configured != null && configured > 0 ? OptionalInt.of(configured) : OptionalInt.empty();
    }

    @EventListener
    void onServerStarted(GrpcServerStartedEvent event) {
        int port = event.getPort();
        // -1: the server is not bound to a port (in-process, servlet): nothing to publish.
        if (port < 1) return;
        boundPort = port;
        DiscoveryClientProperties p = properties.getIfAvailable();
        if (ready && p != null && p.isUpdateOnBind()) {
            log.info("Spring gRPC server started on port {} after the app was ready", port);
            sinks.orderedStream().forEach(sink -> sink.publish(port));
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        ready = true;
    }
}
