package dev.appiaworks.shiguchi.grpc;

import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import com.google.protobuf.DynamicMessage;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.HandlerRegistry;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Dynamic HandlerRegistry that transparently proxies any gRPC call to the
 * downstream service that owns it.
 *
 * For each incoming call the gRPC server asks lookupMethod() with the full method
 * name (e.g. "com.example.SomeService/ListItems"). This registry resolves the
 * matching ServiceEndpointContext from GrpcRouteRegistry and returns a
 * ServerMethodDefinition whose handler bridges the call through to the downstream
 * ManagedChannel.
 *
 * All four gRPC call types (UNARY, SERVER_STREAMING, CLIENT_STREAMING, BIDI_STREAMING)
 * are handled via the same async ServerCall ↔ ClientCall bridge. Flow control signals
 * (request(1)) are propagated in both directions to avoid unbounded buffering.
 *
 * Inbound Metadata is forwarded to the downstream call — authorization tokens, correlation IDs
 * and any custom headers — except {@code x-principal}, which is always {@code anonymous}: this
 * listener authenticates nobody, so a caller must not be able to name its own principal.
 *
 * Registered as fallbackHandlerRegistry on the gRPC server so it handles all
 * methods not explicitly registered (i.e., everything except the reflection service).
 */
@Component
public class GrpcProxyHandlerRegistry extends HandlerRegistry {

    private static final Logger log = LoggerFactory.getLogger(GrpcProxyHandlerRegistry.class);

    private static final Metadata.Key<String> PRINCIPAL_KEY =
            Metadata.Key.of("x-principal", Metadata.ASCII_STRING_MARSHALLER);

    private final GrpcRouteRegistry registry;
    private final GrpcDescriptorCache descriptorCache;
    // Key: System.identityHashCode(ctx) + "|" + fullMethodName.
    // Including context identity prevents stale entries from a replaced context being served
    // to callers that already resolved the new context from GrpcRouteRegistry.
    private final java.util.concurrent.ConcurrentHashMap<String, ServerMethodDefinition<DynamicMessage, DynamicMessage>>
            definitionCache = new java.util.concurrent.ConcurrentHashMap<>();

    public GrpcProxyHandlerRegistry(GrpcRouteRegistry registry, GrpcDescriptorCache descriptorCache) {
        this.registry = registry;
        this.descriptorCache = descriptorCache;
    }

    @Override
    public ServerMethodDefinition<?, ?> lookupMethod(String fullMethodName, String authority) {
        // fullMethodName format: "package.ServiceName/MethodName"
        int slash = fullMethodName.lastIndexOf('/');
        if (slash < 0) return null;

        String serviceFqn = fullMethodName.substring(0, slash);
        String methodName = fullMethodName.substring(slash + 1);

        ServiceEndpointContext ctx = registry.contextByServiceFqn(serviceFqn).orElse(null);
        // A REST_DOWNSTREAM context carries synthetic descriptors for GraphQL but has no channel:
        // there is no gRPC backend to proxy to, so the service is not exposed on the proxy port.
        if (ctx != null && ctx.getChannel() == null) return null;
        if (ctx == null) {
            log.debug("No context found for service FQN: {}", serviceFqn);
            return null;
        }

        MethodDescriptor md = ctx.lookupMethod(serviceFqn, methodName).orElse(null);
        if (md == null) {
            log.debug("No method found: {}/{}", serviceFqn, methodName);
            return null;
        }

        ServiceDescriptor sd = ctx.getServiceByFqn().get(serviceFqn);
        String cacheKey = System.identityHashCode(ctx) + "|" + fullMethodName;
        return definitionCache.computeIfAbsent(cacheKey, k -> buildDefinition(sd, md, ctx));
    }

    @Override
    public List<ServerServiceDefinition> getServices() {
        return Collections.emptyList();
    }

    /**
     * Evicts all cached ServerMethodDefinitions for the given context.
     * Must be called before replacing the context in GrpcRouteRegistry so that
     * subsequent lookupMethod() calls rebuild definitions against the new context.
     */
    public void invalidate(ServiceEndpointContext oldCtx) {
        if (oldCtx == null) return;
        String prefix = System.identityHashCode(oldCtx) + "|";
        definitionCache.keySet().removeIf(key -> key.startsWith(prefix));
        log.debug("Invalidated definition cache for context {} ({})", oldCtx.target(), prefix.trim());
    }

    // -------------------------------------------------------------------------

    private ServerMethodDefinition<DynamicMessage, DynamicMessage> buildDefinition(
            ServiceDescriptor sd, MethodDescriptor md, ServiceEndpointContext ctx) {

        io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> grpcDesc =
                descriptorCache.resolve(ctx, sd, md, resolveMethodType(md));

        return ServerMethodDefinition.create(
                grpcDesc, (serverCall, inboundHeaders) -> startBridge(serverCall, inboundHeaders, ctx, grpcDesc));
    }

    /**
     * Starts the bidirectional bridge between the inbound ServerCall and a new
     * ClientCall to the downstream channel.
     *
     * Message flow:
     *   inbound caller → ServerCall.Listener.onMessage() → clientCall.sendMessage()
     *   downstream     → ClientCall.Listener.onMessage() → serverCall.sendMessage()
     *
     * Half-close / cancel signals are propagated in the direction they originate.
     */
    private ServerCall.Listener<DynamicMessage> startBridge(
            ServerCall<DynamicMessage, DynamicMessage> serverCall,
            Metadata inboundHeaders,
            ServiceEndpointContext ctx,
            io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> grpcDesc) {

        AtomicBoolean serverCallClosed = new AtomicBoolean(false);

        ClientCall<DynamicMessage, DynamicMessage> clientCall = ctx.getChannel().newCall(grpcDesc, CallOptions.DEFAULT);

        // Forward all inbound metadata to downstream (auth tokens, correlation IDs, etc.), except
        // x-principal: this listener authenticates nobody, so the caller's own claim is replaced.
        Metadata outboundHeaders = new Metadata();
        outboundHeaders.merge(inboundHeaders);
        outboundHeaders.removeAll(PRINCIPAL_KEY);
        outboundHeaders.put(PRINCIPAL_KEY, "anonymous");

        clientCall.start(
                new ClientCall.Listener<>() {
                    @Override
                    public void onHeaders(Metadata responseHeaders) {
                        serverCall.sendHeaders(responseHeaders);
                    }

                    @Override
                    public void onMessage(DynamicMessage response) {
                        serverCall.sendMessage(response);
                        clientCall.request(1); // keep downstream flowing
                    }

                    @Override
                    public void onClose(Status status, Metadata trailers) {
                        if (serverCallClosed.compareAndSet(false, true)) {
                            serverCall.close(status, trailers != null ? trailers : new Metadata());
                        }
                    }
                },
                outboundHeaders);

        clientCall.request(1); // prime downstream flow control
        serverCall.request(1); // prime inbound flow control

        return new ServerCall.Listener<>() {
            @Override
            public void onMessage(DynamicMessage request) {
                clientCall.sendMessage(request);
                serverCall.request(1); // keep inbound flowing
            }

            @Override
            public void onHalfClose() {
                clientCall.halfClose();
            }

            @Override
            public void onCancel() {
                if (serverCallClosed.compareAndSet(false, true)) {
                    clientCall.cancel("upstream cancelled", null);
                }
            }

            @Override
            public void onComplete() {
                // Completed normally — downstream side closes via ClientCall.Listener.onClose
            }
        };
    }

    private static io.grpc.MethodDescriptor.MethodType resolveMethodType(MethodDescriptor md) {
        if (md.isClientStreaming() && md.isServerStreaming()) {
            return io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING;
        }
        if (md.isClientStreaming()) return io.grpc.MethodDescriptor.MethodType.CLIENT_STREAMING;
        if (md.isServerStreaming()) return io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING;
        return io.grpc.MethodDescriptor.MethodType.UNARY;
    }
}
