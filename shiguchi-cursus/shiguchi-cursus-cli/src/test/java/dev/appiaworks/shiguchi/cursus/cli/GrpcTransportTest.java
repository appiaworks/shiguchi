package dev.appiaworks.shiguchi.cursus.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Value;
import com.google.protobuf.util.JsonFormat;
import dev.appiaworks.shiguchi.proto.cursus.AckRequest;
import dev.appiaworks.shiguchi.proto.cursus.AckResponse;
import dev.appiaworks.shiguchi.proto.cursus.PushEvent;
import dev.appiaworks.shiguchi.proto.cursus.PushServiceGrpc;
import dev.appiaworks.shiguchi.proto.cursus.SubscribeRequest;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The gRPC transport against an in-process {@code PushService} stub. */
@DisplayName("cursus gRPC transport against an in-process PushService")
class GrpcTransportTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> CLOSE_REASON =
            Metadata.Key.of("push-close-reason", Metadata.ASCII_STRING_MARSHALLER);

    private final List<SubscribeRequest> requests = new CopyOnWriteArrayList<>();
    private final List<AckRequest> acks = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();

    private Server server;
    private ManagedChannel channel;
    private StringWriter stdout;
    private Out out;

    @BeforeEach
    void start() throws Exception {
        String name = "cursus-test-" + System.nanoTime();
        PushServiceGrpc.PushServiceImplBase service = new PushServiceGrpc.PushServiceImplBase() {
            @Override
            public void subscribe(SubscribeRequest request, StreamObserver<PushEvent> responses) {
                requests.add(request);
                int call = calls.incrementAndGet();
                String auth = authorizations.isEmpty() ? null : authorizations.get(authorizations.size() - 1);
                if (!"Bearer good".equals(auth)) {
                    responses.onError(Status.UNAUTHENTICATED
                            .withDescription("token expired")
                            .asRuntimeException());
                    return;
                }
                responses.onNext(event("orders:1", "a" + call, "{\"n\":" + call + "}"));
                if (call == 1) {
                    Metadata trailers = new Metadata();
                    trailers.put(CLOSE_REASON, "rebalance");
                    responses.onError(Status.UNAVAILABLE.asRuntimeException(trailers));
                } else {
                    responses.onCompleted();
                }
            }

            @Override
            public void ack(AckRequest request, StreamObserver<AckResponse> responses) {
                acks.add(request);
                responses.onNext(
                        AckResponse.newBuilder().setTopic(request.getTopic()).build());
                responses.onCompleted();
            }
        };
        ServerInterceptor capture = new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                authorizations.add(headers.get(AUTHORIZATION));
                return next.startCall(call, headers);
            }
        };
        server = InProcessServerBuilder.forName(name)
                .addService(ServerInterceptors.intercept(service, capture))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).build();
        stdout = new StringWriter();
        out = new Out(new PrintWriter(stdout), new PrintWriter(new StringWriter()), false, true);
    }

    @AfterEach
    void stop() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    private static PushEvent event(String topic, String id, String json) {
        try {
            Value.Builder value = Value.newBuilder();
            JsonFormat.parser().merge(json, value);
            return PushEvent.newBuilder()
                    .setTopic(topic)
                    .setId(id)
                    .setJson(value)
                    .putAttributes("tenant", "acme")
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private int run(TokenSource tokens, boolean ack, long max) {
        GrpcTransport transport = new GrpcTransport(channel, tokens);
        Subscriber subscriber =
                new Subscriber(transport, tokens, out, new Lifecycle(), new Subscriber.Options(max, -1, 10, 100));
        return subscriber.run(new Transport.SubRequest(
                List.of("orders:1"), null, null, ack ? "workers" : null, ack ? "w1" : null, null, null, ack));
    }

    @Test
    @DisplayName(
            "GIVEN a stream closed with push-close-reason WHEN subscribed over gRPC SHOULD reconnect with after set to the last id")
    void given_closedWithReason_when_grpc_should_resumeAfterLastId() throws Exception {
        int code = run(new TokenSource("good", null), false, 2);

        assertThat(code).isZero();
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).getAfter()).isEmpty();
        assertThat(requests.get(1).getAfter()).isEqualTo("a1");
        List<String> lines = stdout.toString().lines().toList();
        assertThat(lines).hasSize(2);
        JsonNode first = JSON.readTree(lines.get(0));
        assertThat(first.get("topic").asText()).isEqualTo("orders:1");
        assertThat(first.get("id").asText()).isEqualTo("a1");
        assertThat(first.get("data").get("n").asInt()).isEqualTo(1);
        assertThat(first.get("attributes").get("tenant").asText()).isEqualTo("acme");
    }

    @Test
    @DisplayName("GIVEN --ack WHEN events arrive over gRPC SHOULD acknowledge each with the group and member")
    void given_ack_when_grpc_should_acknowledgeEachEvent() {
        int code = run(new TokenSource("good", null), true, 2);

        assertThat(code).isZero();
        assertThat(acks).extracting(AckRequest::getId).containsExactly("a1", "a2");
        assertThat(acks).allSatisfy(a -> {
            assertThat(a.getGroup()).isEqualTo("workers");
            assertThat(a.getMember()).isEqualTo("w1");
        });
        assertThat(requests.get(0).getGroup()).isEqualTo("workers");
    }

    @Test
    @DisplayName("GIVEN UNAUTHENTICATED and a token command WHEN subscribed SHOULD refresh the token and retry")
    void given_unauthenticated_when_grpc_should_refreshToken() {
        int code = run(new TokenSource("bad", "echo good"), false, 2);

        assertThat(code).isZero();
        assertThat(authorizations).startsWith("Bearer bad", "Bearer good");
    }

    @Test
    @DisplayName("GIVEN UNAUTHENTICATED and no token command WHEN subscribed SHOULD fail with exit code 4")
    void given_unauthenticatedWithoutCommand_when_grpc_should_exit4() {
        try {
            run(new TokenSource("bad", null), false, 1);
            org.junit.jupiter.api.Assertions.fail("expected an authentication failure");
        } catch (CliException e) {
            assertThat(e.exitCode()).isEqualTo(4);
        }
    }
}
