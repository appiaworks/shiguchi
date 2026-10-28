package dev.appiaworks.shiguchi.cursus;

import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import dev.appiaworks.protobuf.extras.api.ApiStatus;
import dev.appiaworks.shiguchi.proto.cursus.ClientMessage;
import dev.appiaworks.shiguchi.proto.cursus.ClientMessageResult;
import dev.appiaworks.shiguchi.proto.cursus.ConnectDecision;
import dev.appiaworks.shiguchi.proto.cursus.ConnectionInfo;
import dev.appiaworks.shiguchi.proto.cursus.SubscribeDecision;
import dev.appiaworks.shiguchi.proto.cursus.SubscribeHook;
import dev.appiaworks.shiguchi.proto.cursus.TopicActivity;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import io.grpc.Metadata;
import io.grpc.Status;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * The {@link PushHook} that calls a served service implementing the
 * {@code shiguchi.cursus.v1.PushHooks} contract ({@code hooks.grpc-service}): each notification
 * is re-encoded as the backend's own input message (the wire shape of {@code push_hooks.proto};
 * a service may name it differently but must keep the field numbers) and sent as a unary call
 * through the gateway's dispatcher, so the backend's channel, load balancing and metadata apply.
 * A method the service does not implement is skipped. The service must exist when the gateway
 * starts; it is looked up again on every call, so a schema reload is followed.
 *
 * <p>{@code OnConnect}, {@code OnSubscribe} and {@code OnClientMessage} are decisions: their reply is
 * read as {@code ConnectDecision}, {@code SubscribeDecision} and {@code ClientMessageResult}, so a
 * backend that still answers {@code google.protobuf.Empty} to {@code OnConnect} allows. A method
 * the server answers {@code UNIMPLEMENTED} is remembered and not called again (an old backend
 * costs one failed call, not one per event).
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.12.0")
public final class GrpcPushHook implements PushHook {

    private static final Logger log = LoggerFactory.getLogger(GrpcPushHook.class);
    static final List<String> METHODS =
            List.of("OnTopicActive", "OnTopicIdle", "OnConnect", "OnDisconnect", "OnSubscribe", "OnClientMessage");

    /** A unary call through the gateway, by service and method name. */
    @FunctionalInterface
    public interface Caller {
        Mono<DynamicMessage> call(
                ServiceEndpointContext ctx, String serviceFqn, String methodName, DynamicMessage request);
    }

    private final GrpcRouteRegistry registry;
    private final String serviceFqn;
    private final Caller caller;
    private final Set<String> unimplemented = ConcurrentHashMap.newKeySet();

    public GrpcPushHook(GrpcRouteRegistry registry, String serviceFqn, Caller caller) {
        this.registry = registry;
        this.serviceFqn = serviceFqn.trim();
        this.caller = caller;
        ServiceEndpointContext ctx = context();
        if (ctx == null) {
            throw new IllegalStateException(
                    "shiguchi.cursus.hooks.grpc-service '" + this.serviceFqn
                            + "' is not a service the gateway serves; name a service of a configured backend that implements shiguchi.cursus.v1.PushHooks");
        }
        List<String> implemented = new ArrayList<>();
        for (String m : METHODS) {
            MethodDescriptor md = ctx.lookupMethod(this.serviceFqn, m).orElse(null);
            if (md == null) continue;
            if (md.isClientStreaming() || md.isServerStreaming()) {
                throw new IllegalStateException(
                        "shiguchi.cursus.hooks.grpc-service: " + this.serviceFqn + "/" + m + " must be unary");
            }
            implemented.add(m);
        }
        if (implemented.isEmpty()) {
            throw new IllegalStateException(
                    "shiguchi.cursus.hooks.grpc-service '" + this.serviceFqn + "' implements none of " + METHODS);
        }
        log.info("Push: hooks go to {} ({})", this.serviceFqn, String.join(", ", implemented));
    }

    /** The caller over the gateway's dispatcher. */
    public static Caller caller(dev.appiaworks.shiguchi.rest.ReactiveGrpcDispatcher dispatcher) {
        return (ctx, service, method, request) ->
                dispatcher.dispatchRequest(ctx, service, method, request, new Metadata());
    }

    public String serviceFqn() {
        return serviceFqn;
    }

    @Override
    public Mono<Void> onTopicActive(TopicActivity activity) {
        return call("OnTopicActive", activity);
    }

    @Override
    public Mono<Void> onTopicIdle(TopicActivity activity) {
        return call("OnTopicIdle", activity);
    }

    /** {@code OnConnect} is the connect decision (see {@link #decideConnect}); the notification would only call it twice. */
    @Override
    public Mono<Void> onConnect(ConnectionInfo connection) {
        return Mono.empty();
    }

    @Override
    public Mono<ConnectDecision> decideConnect(ConnectionInfo connection) {
        return decide("OnConnect", connection, ConnectDecision.parser()::parseFrom);
    }

    @Override
    public Mono<SubscribeDecision> decideSubscribe(SubscribeHook subscription) {
        return decide("OnSubscribe", subscription, SubscribeDecision.parser()::parseFrom);
    }

    @Override
    public Mono<ClientMessageResult> onClientMessage(ClientMessage message) {
        return decide("OnClientMessage", message, ClientMessageResult.parser()::parseFrom);
    }

    @Override
    public Mono<Void> onDisconnect(ConnectionInfo connection) {
        return call("OnDisconnect", connection);
    }

    private Mono<Void> call(String method, Message message) {
        return send(method, message).then();
    }

    /** A decision call: the reply parsed as {@code T}; empty (no opinion) when the method is absent or unimplemented. */
    private <T> Mono<T> decide(String method, Message message, Parser<T> parser) {
        return send(method, message).flatMap(reply -> {
            try {
                return Mono.just(parser.parse(reply.toByteArray()));
            } catch (InvalidProtocolBufferException e) {
                return Mono.error(e);
            }
        });
    }

    @FunctionalInterface
    private interface Parser<T> {
        T parse(byte[] bytes) throws InvalidProtocolBufferException;
    }

    private Mono<DynamicMessage> send(String method, Message message) {
        if (unimplemented.contains(method)) return Mono.empty();
        ServiceEndpointContext ctx = context();
        if (ctx == null) {
            return Mono.error(new IllegalStateException(serviceFqn + " is no longer served"));
        }
        MethodDescriptor md = ctx.lookupMethod(serviceFqn, method).orElse(null);
        if (md == null) return Mono.empty();
        DynamicMessage request;
        try {
            request = DynamicMessage.parseFrom(md.getInputType(), message.toByteArray());
        } catch (InvalidProtocolBufferException e) {
            return Mono.error(e);
        }
        return caller.call(ctx, serviceFqn, method, request).onErrorResume(e -> {
            if (Status.fromThrowable(e).getCode() == Status.Code.UNIMPLEMENTED) {
                if (unimplemented.add(method)) {
                    log.info("Push: {} does not implement {}; it is not called again", serviceFqn, method);
                }
                return Mono.empty();
            }
            return Mono.error(e);
        });
    }

    private ServiceEndpointContext context() {
        for (ServiceEndpointContext ctx : registry.allContexts()) {
            if (ctx.getServiceByFqn().containsKey(serviceFqn)) return ctx;
        }
        return null;
    }
}
