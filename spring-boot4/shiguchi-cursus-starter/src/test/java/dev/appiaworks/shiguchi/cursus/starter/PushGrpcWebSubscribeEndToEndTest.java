package dev.appiaworks.shiguchi.cursus.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Value;
import dev.appiaworks.shiguchi.cursus.PushMetrics;
import dev.appiaworks.shiguchi.proto.cursus.PublishRequest;
import dev.appiaworks.shiguchi.proto.cursus.PushEvent;
import dev.appiaworks.shiguchi.proto.cursus.PushServiceGrpc;
import dev.appiaworks.shiguchi.proto.cursus.SubscribeRequest;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;

/**
 * {@code PushService/Subscribe} over gRPC-Web on the Boot 4 gateway with a small
 * {@code rest.max-response-body}: the cap limits each message of a server stream, not its running
 * total, so a long-lived subscription is not cut once the bytes sent pass the cap. Also the resume:
 * a second stream opened with {@code after} gets only the later events. The shape a Connect-ES
 * client ({@code createGrpcWebTransport}) sends.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.graphql.enabled=false",
            "shiguchi.grpc.enabled=true",
            "shiguchi.grpc-web.enabled=true",
            "shiguchi.cursus.enabled=true",
            "shiguchi.cursus.namespaces.orders.qos=AT_LEAST_ONCE",
            "shiguchi.cursus.namespaces.orders.history-size=100",
            "shiguchi.cursus.namespaces.orders.publish-auth=NONE",
            "shiguchi.cursus.connection.max-lifetime=0",
            "shiguchi.rest.max-response-body=1KB"
        })
@DisplayName("Push Subscribe over gRPC-Web under a small response cap end to end (Boot 4)")
class PushGrpcWebSubscribeEndToEndTest {

    static final int GRPC_PORT = freePort();
    private static final int EVENTS = 12;

    @DynamicPropertySource
    static void grpcPort(DynamicPropertyRegistry registry) {
        registry.add("shiguchi.grpc.port", () -> GRPC_PORT);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    @Autowired
    private Environment environment;

    @Autowired
    private PushMetrics metrics;

    private WebClient client;
    private ManagedChannel channel;

    @BeforeEach
    void connect() {
        client = WebClient.create("http://localhost:" + environment.getRequiredProperty("local.server.port"));
        channel = ManagedChannelBuilder.forAddress("localhost", GRPC_PORT)
                .usePlaintext()
                .build();
    }

    @AfterEach
    void disconnect() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName(
            "GIVEN a small response cap WHEN a gRPC-Web Subscribe receives more bytes than the cap in total SHOULD deliver every event and keep the stream open")
    void given_smallCap_when_subscribeStreamPassesCapInTotal_should_deliverAllAndStayOpen() {
        int before = metrics.connections("grpc");
        WebStream stream =
                open(SubscribeRequest.newBuilder().addTopics("orders:cap").build());
        awaitTrue(() -> metrics.connections("grpc") > before);

        List<String> ids = publishEvents("orders:cap", EVENTS);

        awaitTrue(() -> stream.events.size() == EVENTS);
        assertThat(stream.events).extracting(PushEvent::getId).containsExactlyElementsOf(ids);
        // The same events came to well over the 1 KB cap in total, and none of them was cut.
        assertThat(stream.events.stream().mapToInt(PushEvent::getSerializedSize).sum())
                .isGreaterThan(1024);
        assertThat(stream.trailers).isEmpty();
        assertThat(stream.subscription.isDisposed()).isFalse();

        // Still open: one more event arrives after all that.
        publishEvents("orders:cap", 1);
        awaitTrue(() -> stream.events.size() == EVENTS + 1);
        assertThat(stream.trailers).isEmpty();
        stream.close();
    }

    @Test
    @DisplayName(
            "GIVEN a gRPC-Web stream that ended WHEN the client reopens Subscribe with after SHOULD receive only the events published after that id")
    void given_endedStream_when_reopenedWithAfter_should_receiveOnlyLaterEvents() {
        int before = metrics.connections("grpc");
        WebStream first =
                open(SubscribeRequest.newBuilder().addTopics("orders:resume").build());
        awaitTrue(() -> metrics.connections("grpc") > before);
        List<String> ids = publishEvents("orders:resume", EVENTS);
        awaitTrue(() -> first.events.size() == EVENTS);
        first.close();
        awaitTrue(() -> metrics.connections("grpc") == before);

        String last = ids.get(5);
        WebStream second = open(SubscribeRequest.newBuilder()
                .addTopics("orders:resume")
                .setAfter(last)
                .build());

        awaitTrue(() -> second.events.size() == EVENTS - 6);
        assertThat(second.events).extracting(PushEvent::getId).containsExactlyElementsOf(ids.subList(6, EVENTS));
        assertThat(second.trailers).isEmpty();
        second.close();
    }

    // ---- helpers ----

    private List<String> publishEvents(String topic, int count) {
        PushServiceGrpc.PushServiceBlockingStub stub = PushServiceGrpc.newBlockingStub(channel);
        List<String> ids = new ArrayList<>();
        String payload = "p".repeat(200);
        for (int i = 0; i < count; i++) {
            ids.add(stub.publish(PublishRequest.newBuilder()
                            .addTopics(topic)
                            .setJson(Value.newBuilder().setStringValue(payload))
                            .build())
                    .getEvents(0)
                    .getId());
        }
        return ids;
    }

    /** A gRPC-Web server stream read frame by frame as the bytes arrive. */
    private final class WebStream {
        final List<PushEvent> events = new CopyOnWriteArrayList<>();
        final List<String> trailers = new CopyOnWriteArrayList<>();
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        Disposable subscription;

        synchronized void accept(byte[] chunk) {
            pending.writeBytes(chunk);
            byte[] all = pending.toByteArray();
            ByteBuffer in = ByteBuffer.wrap(all);
            while (in.remaining() >= 5) {
                int start = in.position();
                byte flag = in.get();
                int length = in.getInt();
                if (in.remaining() < length) {
                    in.position(start);
                    break;
                }
                byte[] payload = new byte[length];
                in.get(payload);
                if (flag == 0) {
                    try {
                        events.add(PushEvent.parseFrom(payload));
                    } catch (IOException e) {
                        throw new AssertionError(e);
                    }
                } else {
                    trailers.add(new String(payload, StandardCharsets.ISO_8859_1));
                }
            }
            pending.reset();
            pending.write(all, in.position(), all.length - in.position());
        }

        void close() {
            subscription.dispose();
        }
    }

    private WebStream open(SubscribeRequest request) {
        byte[] message = request.toByteArray();
        byte[] body = ByteBuffer.allocate(5 + message.length)
                .put((byte) 0)
                .putInt(message.length)
                .put(message)
                .array();
        WebStream stream = new WebStream();
        stream.subscription = client.post()
                .uri("/shiguchi.cursus.v1.PushService/Subscribe")
                .contentType(MediaType.parseMediaType("application/grpc-web+proto"))
                .bodyValue(body)
                .exchangeToFlux(r -> {
                    assertThat(r.statusCode().value()).isEqualTo(200);
                    return r.bodyToFlux(byte[].class);
                })
                .timeout(Duration.ofSeconds(60))
                .subscribe(stream::accept, e -> stream.trailers.add("transport error: " + e));
        return stream;
    }

    private static void awaitTrue(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition not met in 30s");
            Thread.onSpinWait();
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
