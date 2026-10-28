package dev.appiaworks.shiguchi.cursus;

import dev.appiaworks.shiguchi.proto.cursus.DeliverRequest;
import dev.appiaworks.shiguchi.proto.cursus.DeliverResponse;
import dev.appiaworks.shiguchi.proto.cursus.DeliveryStatus;
import dev.appiaworks.shiguchi.proto.cursus.DisconnectRequest;
import dev.appiaworks.shiguchi.proto.cursus.DisconnectResponse;
import dev.appiaworks.shiguchi.proto.cursus.PushPeerGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import reactor.core.publisher.Mono;

/**
 * Calls the {@code PushPeer} service of other instances, presenting the shared {@code
 * cursus.peer.token}. One channel per peer address, kept for the life of the client. The channel is
 * plaintext: put mutual TLS (a mesh, or a TLS-terminating sidecar) between instances if the network
 * between them is not private.
 */
public final class PushPeerClient implements AutoCloseable {

    static final Metadata.Key<String> TOKEN = Metadata.Key.of("x-push-peer-token", Metadata.ASCII_STRING_MARSHALLER);

    private final String token;
    private final ConcurrentHashMap<String, ManagedChannel> channels = new ConcurrentHashMap<>();

    public PushPeerClient(String token) {
        this.token = token;
    }

    private PushPeerGrpc.PushPeerStub stub(String address, Duration deadline) {
        ManagedChannel channel = channels.computeIfAbsent(
                address, a -> ManagedChannelBuilder.forTarget(a).usePlaintext().build());
        Metadata headers = new Metadata();
        headers.put(TOKEN, token);
        return PushPeerGrpc.newStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                .withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Asks the instance at {@code address} to write the event to the connections it holds. */
    public Mono<List<DeliveryStatus>> deliver(String address, DeliverRequest request, Duration deadline) {
        return Mono.<DeliverResponse>create(sink -> stub(address, deadline).deliver(request, observer(sink)))
                .map(DeliverResponse::getDeliveriesList);
    }

    /** Asks the instance at {@code address} to end connection {@code id}; false when it does not hold it. */
    public Mono<Boolean> disconnect(String address, String id, String reason, Duration deadline) {
        DisconnectRequest request = DisconnectRequest.newBuilder()
                .setConnectionId(id)
                .setReason(reason == null ? "" : reason)
                .build();
        return Mono.<DisconnectResponse>create(sink -> stub(address, deadline).disconnect(request, observer(sink)))
                .map(DisconnectResponse::getDisconnected);
    }

    private static <T> StreamObserver<T> observer(reactor.core.publisher.MonoSink<T> sink) {
        return new StreamObserver<>() {
            @Override
            public void onNext(T value) {
                sink.success(value);
            }

            @Override
            public void onError(Throwable t) {
                sink.error(t);
            }

            @Override
            public void onCompleted() {
                sink.success();
            }
        };
    }

    @Override
    public void close() {
        for (ManagedChannel c : channels.values()) c.shutdown();
        channels.clear();
    }
}
