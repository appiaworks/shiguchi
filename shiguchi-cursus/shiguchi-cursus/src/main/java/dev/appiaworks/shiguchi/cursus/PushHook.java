package dev.appiaworks.shiguchi.cursus;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import dev.appiaworks.shiguchi.proto.cursus.ClientMessage;
import dev.appiaworks.shiguchi.proto.cursus.ClientMessageResult;
import dev.appiaworks.shiguchi.proto.cursus.ConnectDecision;
import dev.appiaworks.shiguchi.proto.cursus.ConnectionInfo;
import dev.appiaworks.shiguchi.proto.cursus.SubscribeDecision;
import dev.appiaworks.shiguchi.proto.cursus.SubscribeHook;
import dev.appiaworks.shiguchi.proto.cursus.TopicActivity;
import reactor.core.publisher.Mono;

/**
 * What a backend wants to know about the push surface, called by the gateway: declare a bean of
 * this type (any subset of the methods; the defaults do nothing), or point
 * {@code shiguchi.cursus.hooks.grpc-service} at a served service implementing the
 * {@code shiguchi.cursus.v1.PushHooks} contract ({@code push_hooks.proto}) and the gateway calls
 * it over gRPC. The {@code on...} calls about topics and disconnects are notifications: they run
 * after the fact, off the client's path, with {@code hooks.timeout}; a failure is logged and
 * counted ({@code shiguchi.cursus.hooks}), never shown to the client. The {@code decide...} calls
 * and {@link #onClientMessage} are decisions: the gateway waits for them before serving the client,
 * the first reject among the hooks wins, rewrites compose in bean order, and a hook that times out
 * or fails allows unless {@code hooks.fail-closed} is true. An empty {@link Mono} means no opinion.
 * Topic activity is per gateway instance ({@link TopicActivity#getInstance}).
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.14.0")
public interface PushHook {

    /** The first subscriber of {@code activity.getTopic()} arrived on this instance. */
    default Mono<Void> onTopicActive(TopicActivity activity) {
        return Mono.empty();
    }

    /** The last subscriber left and {@code hooks.idle-linger} passed without a new one. */
    default Mono<Void> onTopicIdle(TopicActivity activity) {
        return Mono.empty();
    }

    /**
     * Admits or rejects a connection that just opened, and may subscribe it to topics on the server
     * side. Awaited before the first event is written (on WebSocket, at the first {@code connect},
     * {@code sub} or {@code pub} frame, when the token is known). Empty: allow.
     */
    default Mono<ConnectDecision> decideConnect(ConnectionInfo connection) {
        return Mono.empty();
    }

    /** Admits, rejects or rewrites the topics a client asked to subscribe to. Empty: allow as asked. */
    default Mono<SubscribeDecision> decideSubscribe(SubscribeHook subscription) {
        return Mono.empty();
    }

    /** Forwards, drops or rejects a message a client published over its connection. Empty: forward. */
    default Mono<ClientMessageResult> onClientMessage(ClientMessage message) {
        return Mono.empty();
    }

    /** A client connection opened; its topics are not known yet. A notification: see {@link #decideConnect}. */
    default Mono<Void> onConnect(ConnectionInfo connection) {
        return Mono.empty();
    }

    /** A client connection closed, with its topics and {@link ConnectionInfo#getReason()}. */
    default Mono<Void> onDisconnect(ConnectionInfo connection) {
        return Mono.empty();
    }
}
