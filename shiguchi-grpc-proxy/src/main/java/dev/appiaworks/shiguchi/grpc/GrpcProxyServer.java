package dev.appiaworks.shiguchi.grpc;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Embeds a gRPC server alongside the Spring Boot HTTP server.
 *
 * Activated when shiguchi.grpc.enabled=true. Starts as a SmartLifecycle bean
 * at phase Integer.MAX_VALUE so all registry beans (populated by DynamicRouteLoader
 * @PostConstruct) are ready before the port is opened.
 *
 * The server exposes:
 *  - GrpcReflectionAggregator: merged server reflection across all downstream services.
 *    Tools like grpcurl, Postman, and grpcui can discover the full combined schema.
 *  - Every GatewayHostedService bean: a service the gateway implements itself (the push
 *    module's PushService), answered in place.
 *  - GrpcProxyHandlerRegistry (fallback): routes any other call to the downstream
 *    service that owns it, bridging ServerCall ↔ ClientCall transparently.
 *
 * GrpcProxyServerInterceptor runs on all calls for rate limiting and request logging.
 */
@Component
@ConditionalOnProperty(name = "shiguchi.grpc.enabled", havingValue = "true")
public class GrpcProxyServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GrpcProxyServer.class);

    private final GatewayProperties properties;
    private final GrpcProxyHandlerRegistry handlerRegistry;
    private final GrpcReflectionAggregator reflectionAggregator;
    private final GrpcProxyServerInterceptor serverInterceptor;
    private final List<GatewayHostedService> hostedServices;

    private volatile Server server;
    private volatile boolean running = false;

    public GrpcProxyServer(
            GatewayProperties properties,
            GrpcProxyHandlerRegistry handlerRegistry,
            GrpcReflectionAggregator reflectionAggregator,
            GrpcProxyServerInterceptor serverInterceptor) {
        this(properties, handlerRegistry, reflectionAggregator, serverInterceptor, List.of());
    }

    /**
     * @param hostedServices services the gateway implements itself ({@link GatewayHostedService}
     *     beans), served on this port in place; they take precedence over the proxy for their
     *     method names
     */
    @Autowired
    public GrpcProxyServer(
            GatewayProperties properties,
            GrpcProxyHandlerRegistry handlerRegistry,
            GrpcReflectionAggregator reflectionAggregator,
            GrpcProxyServerInterceptor serverInterceptor,
            ObjectProvider<GatewayHostedService> hostedServices) {
        this(
                properties,
                handlerRegistry,
                reflectionAggregator,
                serverInterceptor,
                hostedServices.orderedStream().toList());
    }

    GrpcProxyServer(
            GatewayProperties properties,
            GrpcProxyHandlerRegistry handlerRegistry,
            GrpcReflectionAggregator reflectionAggregator,
            GrpcProxyServerInterceptor serverInterceptor,
            List<GatewayHostedService> hostedServices) {
        this.properties = properties;
        this.handlerRegistry = handlerRegistry;
        this.reflectionAggregator = reflectionAggregator;
        this.serverInterceptor = serverInterceptor;
        this.hostedServices = hostedServices;
    }

    @Override
    public void start() {
        int port = properties.getGrpc().getPort();
        try {
            ServerBuilder<?> builder = ServerBuilder.forPort(port)
                    .fallbackHandlerRegistry(handlerRegistry)
                    .addService(reflectionAggregator)
                    .intercept(serverInterceptor);
            for (GatewayHostedService hosted : hostedServices) builder.addService(hosted);
            server = builder.build().start();
            running = true;
            log.info(
                    "gRPC proxy server started on port {}{}",
                    port,
                    hostedServices.isEmpty()
                            ? ""
                            : ", hosting "
                                    + hostedServices.stream()
                                            .map(h -> h.bindService()
                                                    .getServiceDescriptor()
                                                    .getName())
                                            .toList());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start gRPC proxy server on port " + port, e);
        }
    }

    @Override
    public void stop() {
        if (server != null) {
            try {
                server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                server.shutdownNow();
            } finally {
                running = false;
                log.info("gRPC proxy server stopped");
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Higher phase = start later (after all @PostConstruct hooks) and stop earlier. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
