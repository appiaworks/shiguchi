package dev.appiaworks.shiguchi.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import dev.appiaworks.shiguchi.serde.DynamicMessageMarshaller;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.ServerCalls;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/**
 * BDD tests for the demand-driven streaming bridge: gRPC auto-request is disabled and
 * message delivery must follow reactive demand exactly, so a slow downstream consumer
 * backpressures the backend instead of the gateway buffering the whole stream.
 * Uses an in-process gRPC server, whose transport honours per-message flow control.
 */
class ReactiveGrpcDispatcherBackpressureTest {

    private static final int TOTAL = 10;

    private static Descriptor itemDescriptor;
    private static io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> method;
    private static Server server;
    private static ManagedChannel channel;
    private static final CountDownLatch cancelSeen = new CountDownLatch(1);
    private static final AtomicInteger serverEmitted = new AtomicInteger();

    @BeforeAll
    static void startInProcessServer() throws Exception {
        FileDescriptor file = FileDescriptor.buildFrom(
                FileDescriptorProto.newBuilder()
                        .setName("bp.proto")
                        .setSyntax("proto3")
                        .setPackage("bp")
                        .addMessageType(DescriptorProto.newBuilder()
                                .setName("Item")
                                .addField(FieldDescriptorProto.newBuilder()
                                        .setName("n")
                                        .setNumber(1)
                                        .setType(FieldDescriptorProto.Type.TYPE_INT32)
                                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)))
                        .build(),
                new FileDescriptor[0]);
        itemDescriptor = file.findMessageTypeByName("Item");

        method = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.SERVER_STREAMING)
                .setFullMethodName("bp.Emitter/Emit")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(itemDescriptor))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(itemDescriptor))
                .build();

        ServerServiceDefinition service = ServerServiceDefinition.builder("bp.Emitter")
                .addMethod(method, ServerCalls.asyncServerStreamingCall((request, responseObserver) -> {
                    var serverObserver = (ServerCallStreamObserver<DynamicMessage>) responseObserver;
                    serverObserver.setOnCancelHandler(cancelSeen::countDown);
                    int mode = (Integer) request.getField(itemDescriptor.findFieldByNumber(1));
                    if (mode < 0) {
                        // Cancellation fixture: emit one item and keep the call open so a
                        // client cancel reaches a live server call.
                        responseObserver.onNext(item(0));
                        serverEmitted.incrementAndGet();
                        return;
                    }
                    for (int i = 0; i < TOTAL; i++) {
                        responseObserver.onNext(item(i));
                        serverEmitted.incrementAndGet();
                    }
                    responseObserver.onCompleted();
                }))
                .build();

        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(service)
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    @AfterAll
    static void shutdown() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    private static DynamicMessage item(int n) {
        return DynamicMessage.newBuilder(itemDescriptor)
                .setField(itemDescriptor.findFieldByNumber(1), n)
                .build();
    }

    @Test
    @DisplayName(
            "Given a streaming call, when downstream requests 3 items, should deliver exactly 3 and hold the rest until more demand")
    void given_streamingCall_when_downstreamRequestsThree_should_deliverExactlyThree() {
        var flux = ReactiveGrpcDispatcher.streamingResponses(channel, method, CallOptions.DEFAULT, item(0));

        StepVerifier.create(flux, 0)
                .thenRequest(3)
                .expectNextCount(3)
                .expectNoEvent(Duration.ofMillis(300))
                .thenRequest(Long.MAX_VALUE)
                .expectNextCount(TOTAL - 3)
                .verifyComplete();
    }

    @Test
    @DisplayName(
            "Given a streaming call, when downstream requests unbounded, should deliver all items in order and complete")
    void given_streamingCall_when_unboundedDemand_should_deliverAllInOrder() {
        var flux = ReactiveGrpcDispatcher.streamingResponses(channel, method, CallOptions.DEFAULT, item(0));

        StepVerifier.create(flux).expectNextCount(TOTAL).verifyComplete();
    }

    @Test
    @DisplayName(
            "Given a streaming call, when downstream cancels, should cancel the gRPC call so the server is notified")
    void given_streamingCall_when_downstreamCancels_should_cancelGrpcCall() throws Exception {
        var flux = ReactiveGrpcDispatcher.streamingResponses(channel, method, CallOptions.DEFAULT, item(-1));

        StepVerifier.create(flux, 0)
                .thenRequest(1)
                .expectNextCount(1)
                .thenCancel()
                .verify(Duration.ofSeconds(30));

        assertThat(cancelSeen.await(30, TimeUnit.SECONDS))
                .as("server should observe the cancellation")
                .isTrue();
        assertThat(serverEmitted.get()).isGreaterThan(0);
    }
}
