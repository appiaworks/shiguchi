package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.google.protobuf.Value;
import dev.appiaworks.shiguchi.proto.cursus.DeliverRequest;
import dev.appiaworks.shiguchi.proto.cursus.DeliveryStatus;
import dev.appiaworks.shiguchi.proto.cursus.DisconnectRequest;
import dev.appiaworks.shiguchi.proto.cursus.PushPeerGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

@DisplayName("PushPeerService")
class PushPeerServiceTest {

    private static final String SECRET = "s3cret";

    private final PushConnections connections = new PushConnections();
    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void startServer() throws Exception {
        String name = "push-peer-" + UUID.randomUUID();
        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(new PushPeerService(connections, null, SECRET).bindService())
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    @AfterEach
    void stopServer() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    /** A blocking stub that sends {@code token} as the peer token, or none when null. */
    private PushPeerGrpc.PushPeerBlockingStub stubWith(String token) {
        PushPeerGrpc.PushPeerBlockingStub stub = PushPeerGrpc.newBlockingStub(channel);
        if (token == null) return stub;
        Metadata headers = new Metadata();
        headers.put(PushPeerClient.TOKEN, token);
        return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    private static DeliverRequest deliver(String... connectionIds) {
        dev.appiaworks.shiguchi.proto.cursus.PushEvent event =
                dev.appiaworks.shiguchi.proto.cursus.PushEvent.newBuilder()
                        .setTopic("_direct:x")
                        .setId("e1")
                        .setJson(Value.newBuilder().setStringValue("hi"))
                        .build();
        DeliverRequest.Builder request =
                DeliverRequest.newBuilder().setEvent(event).setRequireAck(false).setTimeoutMs(3000);
        for (String id : connectionIds) request.addConnectionIds(id);
        return request.build();
    }

    @Test
    @DisplayName("GIVEN a call without the peer token WHEN Deliver is called SHOULD fail UNAUTHENTICATED")
    void given_call_without_peer_token_when_deliver_is_called_should_fail_unauthenticated() {
        // Act and Assert
        assertThatThrownBy(() -> stubWith(null).deliver(deliver("any")))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(io.grpc.Status.Code.UNAUTHENTICATED));
    }

    @Test
    @DisplayName("GIVEN a call with a wrong peer token WHEN Deliver is called SHOULD fail UNAUTHENTICATED")
    void given_call_with_wrong_peer_token_when_deliver_is_called_should_fail_unauthenticated() {
        // Act and Assert
        assertThatThrownBy(() -> stubWith("guess").deliver(deliver("any")))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(io.grpc.Status.Code.UNAUTHENTICATED));
    }

    @Test
    @DisplayName(
            "GIVEN a local connection and an unknown id WHEN Deliver is called with the token SHOULD answer each in request order")
    void given_local_connection_and_unknown_id_when_deliver_is_called_with_token_should_answer_in_request_order() {
        // Arrange
        PushConnections.Live live = connections.open("ws", null);
        live.direct().subscribe(live::delivered);

        // Act
        var response = stubWith(SECRET).deliver(deliver(live.id(), "unknown-id"));

        // Assert
        assertThat(response.getDeliveriesList())
                .extracting(DeliveryStatus::getConnectionId, DeliveryStatus::getStatus)
                .containsExactly(
                        tuple(live.id(), DeliveryStatus.Status.DELIVERED),
                        tuple("unknown-id", DeliveryStatus.Status.NOT_CONNECTED));
    }

    @Test
    @DisplayName("GIVEN a local connection WHEN Disconnect is called with the token SHOULD end it")
    void given_local_connection_when_disconnect_is_called_with_token_should_end_it() {
        // Arrange
        PushConnections.Live live = connections.open("ws", null);

        // Act
        var response = stubWith(SECRET)
                .disconnect(DisconnectRequest.newBuilder()
                        .setConnectionId(live.id())
                        .setReason("test")
                        .build());

        // Assert
        assertThat(response.getDisconnected()).isTrue();
        StepVerifier.create(live.closed()).verifyComplete();
    }

    @Test
    @DisplayName(
            "GIVEN no connection with the id WHEN Disconnect is called with the token SHOULD report not disconnected")
    void given_no_connection_with_the_id_when_disconnect_is_called_with_token_should_report_not_disconnected() {
        // Act
        var response = stubWith(SECRET)
                .disconnect(DisconnectRequest.newBuilder()
                        .setConnectionId("unknown-id")
                        .build());

        // Assert
        assertThat(response.getDisconnected()).isFalse();
    }
}
