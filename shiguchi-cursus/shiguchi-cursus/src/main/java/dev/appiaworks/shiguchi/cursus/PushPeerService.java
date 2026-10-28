package dev.appiaworks.shiguchi.cursus;

import dev.appiaworks.shiguchi.grpc.GatewayHostedService;
import dev.appiaworks.shiguchi.proto.cursus.DeliverRequest;
import dev.appiaworks.shiguchi.proto.cursus.DeliverResponse;
import dev.appiaworks.shiguchi.proto.cursus.DeliveryStatus;
import dev.appiaworks.shiguchi.proto.cursus.DisconnectRequest;
import dev.appiaworks.shiguchi.proto.cursus.DisconnectResponse;
import dev.appiaworks.shiguchi.proto.cursus.PushPeerGrpc;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The {@code PushPeer} service other instances call: write a {@code Send}'s event to connections
 * this instance holds, and end one. Hosted on the native gRPC port as a {@link GatewayHostedService},
 * never declared as a served service, so the HTTP surfaces and JSON transcoding do not know it. Every
 * call must carry the shared {@code cursus.peer.token} as {@code x-push-peer-token}; one without it,
 * or with another, is {@code UNAUTHENTICATED}.
 */
public final class PushPeerService implements GatewayHostedService {

    private final PushConnections connections;
    private final GrpcRouteRegistry registry;
    private final byte[] token;

    public PushPeerService(PushConnections connections, GrpcRouteRegistry registry, String token) {
        this.connections = connections;
        this.registry = registry;
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public ServerServiceDefinition bindService() {
        PushPeerGrpc.PushPeerImplBase impl = new PushPeerGrpc.PushPeerImplBase() {
            @Override
            public void deliver(DeliverRequest request, StreamObserver<DeliverResponse> response) {
                PushPeerService.this.deliver(request, response);
            }

            @Override
            public void disconnect(DisconnectRequest request, StreamObserver<DisconnectResponse> response) {
                PushPeerService.this.disconnect(request, response);
            }
        };
        return ServerInterceptors.intercept(impl.bindService(), new TokenCheck(token));
    }

    void deliver(DeliverRequest request, StreamObserver<DeliverResponse> response) {
        Mono.defer(() -> {
                    Topic template = Topic.parse(request.getEvent().getTopic());
                    PushEventProtos.Payload payload = PushEventProtos.payload(request.getEvent(), registry);
                    Map<String, String> attributes =
                            new HashMap<>(request.getEvent().getAttributesMap());
                    Duration wait = Duration.ofMillis(Math.max(1, request.getTimeoutMs()));
                    return Flux.fromIterable(request.getConnectionIdsList())
                            .flatMapSequential(id -> deliverOne(id, template, payload, attributes, request, wait))
                            .collectList();
                })
                .map(list -> DeliverResponse.newBuilder().addAllDeliveries(list).build())
                .onErrorMap(
                        IllegalArgumentException.class,
                        e -> Status.INVALID_ARGUMENT
                                .withDescription(e.getMessage())
                                .asRuntimeException())
                .onErrorMap(PushException.class, PushEventProtos::status)
                .subscribe(
                        out -> {
                            response.onNext(out);
                            response.onCompleted();
                        },
                        response::onError);
    }

    private Mono<DeliveryStatus> deliverOne(
            String id,
            Topic template,
            PushEventProtos.Payload payload,
            Map<String, String> attributes,
            DeliverRequest request,
            Duration wait) {
        PushConnections.Live live = connections.find(id);
        if (live == null) {
            return Mono.just(DeliveryStatus.newBuilder()
                    .setConnectionId(id)
                    .setStatus(DeliveryStatus.Status.NOT_CONNECTED)
                    .build());
        }
        Map<String, String> attrs = new HashMap<>(attributes);
        attrs.put(PushConnections.DIRECT, "true");
        PushEvent event = new PushEvent(
                new Topic(template.namespace(), id),
                request.getEvent().getId(),
                payload.json(),
                attrs,
                null,
                Instant.now(),
                payload.typeUrl(),
                payload.proto());
        return live.deliverDirect(event, request.getRequireAck(), wait)
                .map(s -> DeliveryStatus.newBuilder()
                        .setConnectionId(id)
                        .setStatus(PushSender.toProto(s))
                        .build());
    }

    void disconnect(DisconnectRequest request, StreamObserver<DisconnectResponse> response) {
        boolean done = connections.disconnect(request.getConnectionId(), request.getReason());
        response.onNext(DisconnectResponse.newBuilder().setDisconnected(done).build());
        response.onCompleted();
    }

    /** Refuses every call that does not present the shared secret. */
    private record TokenCheck(byte[] token) implements ServerInterceptor {
        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            String presented = headers.get(PushPeerClient.TOKEN);
            boolean ok = presented != null && MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), token);
            if (!ok) {
                call.close(Status.UNAUTHENTICATED.withDescription("peer token required"), new Metadata());
                return new ServerCall.Listener<>() {};
            }
            return next.startCall(call, headers);
        }
    }
}
