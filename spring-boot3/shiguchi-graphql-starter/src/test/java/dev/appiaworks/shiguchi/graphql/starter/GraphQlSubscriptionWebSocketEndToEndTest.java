package dev.appiaworks.shiguchi.graphql.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodOptions;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.protobuf.util.JsonFormat;
import dev.appiaworks.shiguchi.proto.api.GraphQlProto;
import dev.appiaworks.shiguchi.proto.api.GraphQlRule;
import dev.appiaworks.shiguchi.proto.api.GraphQlRule.Op;
import dev.appiaworks.shiguchi.serde.DynamicMessageMarshaller;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerServiceDefinition;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.ServerCalls;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * A server-streaming RPC annotated {@code (shiguchi.api.graphql) = {type: SUBSCRIPTION}} served over a
 * real graphql-transport-ws connection on the Boot 3 gateway (DGS on spring-graphql, {@code
 * spring.graphql.websocket.path}): every backend item arrives as a {@code next} frame in order
 * followed by {@code complete}, and a subscriber that closes the socket stalls and then cancels
 * the backend stream instead of the gateway buffering it.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.openapi.enabled=false",
            "spring.graphql.websocket.path=/graphql/ws"
        })
@DisplayName("GraphQL subscriptions over WebSocket end to end (Boot 3, DGS)")
class GraphQlSubscriptionWebSocketEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private static final FileDescriptor FILE = buildFile();
    private static final Descriptor ITEM = FILE.findMessageTypeByName("Item");
    private static final Descriptor WATCH_REQUEST = FILE.findMessageTypeByName("WatchRequest");
    private static final AtomicInteger SENT = new AtomicInteger();
    private static final AtomicBoolean CANCELLED = new AtomicBoolean();
    private static final Server BACKEND = startBackend();
    private static final Path PB_FILE = writeDescriptorSet();

    @DynamicPropertySource
    static void backend(DynamicPropertyRegistry registry) {
        registry.add("shiguchi.services[0].name", () -> "events");
        registry.add("shiguchi.services[0].mode", () -> "PB_FILE");
        registry.add("shiguchi.services[0].pb-descriptor-path", () -> PB_FILE.toString());
        registry.add("shiguchi.services[0].host", () -> "localhost");
        registry.add("shiguchi.services[0].port", () -> BACKEND.getPort());
    }

    @AfterAll
    static void stop() throws IOException {
        BACKEND.shutdownNow();
        Files.deleteIfExists(PB_FILE);
    }

    @Autowired
    private Environment environment;

    private URI wsUri;

    @BeforeEach
    void reset() {
        wsUri = URI.create("ws://localhost:" + environment.getRequiredProperty("local.server.port") + "/graphql/ws");
        SENT.set(0);
        CANCELLED.set(false);
    }

    @Test
    @DisplayName(
            "GIVEN a SUBSCRIPTION-bound streaming RPC WHEN subscribed over graphql-transport-ws SHOULD deliver every item as a next frame in order then complete")
    void given_subscription_when_subscribedOverWebSocket_should_deliverNextFramesThenComplete() {
        List<Struct> frames = subscribe("subscription { itemEvents(count: 3, size: 8) { id } }", Integer.MAX_VALUE);

        assertThat(frames)
                .extracting(GraphQlSubscriptionWebSocketEndToEndTest::type)
                .startsWith("connection_ack");
        List<Struct> next = frames.stream().filter(f -> "next".equals(type(f))).toList();
        assertThat(next).hasSize(3);
        for (int i = 0; i < 3; i++) {
            assertThat(itemId(next.get(i))).isEqualTo(Integer.toString(i));
            assertThat(at(next.get(i), "payload", "errors"))
                    .as("errors in frame %d", i)
                    .isNull();
        }
        assertThat(type(frames.get(frames.size() - 1))).isEqualTo("complete");
        assertThat(SENT.get()).isEqualTo(3);
    }

    @Test
    @DisplayName(
            "GIVEN a subscriber that takes two items of a huge stream and closes the socket WHEN it leaves SHOULD have stalled the backend and then cancelled it")
    void given_subscriberThatLeaves_when_socketClosed_should_stallThenCancelBackend() {
        int total = 200_000;

        List<Struct> frames = subscribe("subscription { itemEvents(count: " + total + ", size: 1024) { id } }", 2);

        List<Struct> next = frames.stream().filter(f -> "next".equals(type(f))).toList();
        assertThat(next).hasSize(2);
        assertThat(itemId(next.get(1))).isEqualTo("1");
        awaitCancelled();
        assertThat(SENT.get())
                .as("items the backend produced before the cancel reached it")
                .isLessThan(total / 4);
    }

    /**
     * Speaks graphql-transport-ws by hand: connection_init, wait for the ack, subscribe, then
     * collect frames until {@code complete} or until {@code maxNext} next frames arrived — in the
     * latter case the socket is simply closed, the way a departing browser tab would.
     */
    private List<Struct> subscribe(String query, int maxNext) {
        List<Struct> frames = new CopyOnWriteArrayList<>();
        AtomicInteger nextSeen = new AtomicInteger();
        Sinks.Many<String> outbound = Sinks.many().unicast().onBackpressureBuffer();
        outbound.tryEmitNext("{\"type\":\"connection_init\"}");
        String subscribeFrame = "{\"id\":\"1\",\"type\":\"subscribe\",\"payload\":{\"query\":\"" + query + "\"}}";

        // The subprotocol goes through the handler: that is what the client handshaker checks the
        // server's Sec-WebSocket-Protocol answer against.
        WebSocketHandler handler = new WebSocketHandler() {
            @Override
            public List<String> getSubProtocols() {
                return List.of("graphql-transport-ws");
            }

            @Override
            public Mono<Void> handle(WebSocketSession session) {
                Mono<Void> send = session.send(outbound.asFlux().map(session::textMessage));
                Flux<Struct> inbound = session.receive()
                        .map(WebSocketMessage::getPayloadAsText)
                        .map(GraphQlSubscriptionWebSocketEndToEndTest::parse)
                        .doOnNext(frames::add)
                        .doOnNext(frame -> {
                            if ("connection_ack".equals(type(frame))) outbound.tryEmitNext(subscribeFrame);
                            if ("next".equals(type(frame))) nextSeen.incrementAndGet();
                        })
                        .takeUntil(frame -> {
                            String type = type(frame);
                            return "complete".equals(type) || "error".equals(type) || nextSeen.get() >= maxNext;
                        })
                        .doOnTerminate(outbound::tryEmitComplete);
                return Mono.when(send, inbound.then());
            }
        };
        new ReactorNettyWebSocketClient().execute(wsUri, handler).block(Duration.ofSeconds(30));
        return frames;
    }

    // JSON frames are read into protobuf Structs: the one JSON parser both Boot lines share.
    private static Struct parse(String text) {
        try {
            Struct.Builder b = Struct.newBuilder();
            JsonFormat.parser().merge(text, b);
            return b.build();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String type(Struct frame) {
        Value v = at(frame, "type");
        return v == null ? "" : v.getStringValue();
    }

    private static String itemId(Struct frame) {
        Value v = at(frame, "payload", "data", "itemEvents", "id");
        return v == null ? null : v.getStringValue();
    }

    /** Walks nested objects; null when any segment is absent. */
    private static Value at(Struct root, String... path) {
        Struct cur = root;
        Value v = null;
        for (String seg : path) {
            if (cur == null) return null;
            v = cur.getFieldsMap().get(seg);
            if (v == null) return null;
            cur = v.hasStructValue() ? v.getStructValue() : null;
        }
        return v;
    }

    private static void awaitCancelled() {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!CANCELLED.get() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(CANCELLED.get())
                .as("backend call cancelled after the subscriber left")
                .isTrue();
    }

    // ---------------------------------------------------------------- the gRPC backend

    /** ev.Events/Watch(WatchRequest{count,size}) returns (stream Item{id,payload}), bound as subscription itemEvents. */
    private static FileDescriptor buildFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("ev/events.proto")
                    .setSyntax("proto3")
                    .setPackage("ev")
                    .addDependency("shiguchi/api/graphql.proto")
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Item")
                            .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_STRING))
                            .addField(field("payload", 2, FieldDescriptorProto.Type.TYPE_STRING)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("WatchRequest")
                            .addField(field("count", 1, FieldDescriptorProto.Type.TYPE_INT32))
                            .addField(field("size", 2, FieldDescriptorProto.Type.TYPE_INT32)))
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Events")
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("Watch")
                                    .setInputType(".ev.WatchRequest")
                                    .setOutputType(".ev.Item")
                                    .setServerStreaming(true)
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    GraphQlProto.graphql,
                                                    GraphQlRule.newBuilder()
                                                            .setName("itemEvents")
                                                            .setType(Op.SUBSCRIPTION)
                                                            .build()))))
                    .build();
            return FileDescriptor.buildFrom(proto, new FileDescriptor[] {GraphQlProto.getDescriptor()});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static FieldDescriptorProto.Builder field(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
    }

    private static Path writeDescriptorSet() {
        try {
            Path path = Files.createTempFile("ev-events", ".pb");
            FileDescriptorSet set = FileDescriptorSet.newBuilder()
                    .addFile(DescriptorProtos.getDescriptor().toProto())
                    .addFile(GraphQlProto.getDescriptor().toProto())
                    .addFile(FILE.toProto())
                    .build();
            Files.write(path, set.toByteArray());
            return path;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Produces only while gRPC reports the transport ready, so SENT mirrors forwarded demand. */
    private static Server startBackend() {
        io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> watch =
                io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                        .setType(MethodType.SERVER_STREAMING)
                        .setFullMethodName("ev.Events/Watch")
                        .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(WATCH_REQUEST))
                        .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(ITEM))
                        .build();
        ServerServiceDefinition service = ServerServiceDefinition.builder("ev.Events")
                .addMethod(watch, ServerCalls.asyncServerStreamingCall((request, observer) -> {
                    int count = (int) request.getField(WATCH_REQUEST.findFieldByName("count"));
                    int size = (int) request.getField(WATCH_REQUEST.findFieldByName("size"));
                    String payload = "x".repeat(Math.max(size, 1));
                    ServerCallStreamObserver<DynamicMessage> call = (ServerCallStreamObserver<DynamicMessage>) observer;
                    AtomicInteger next = new AtomicInteger();
                    call.setOnCancelHandler(() -> CANCELLED.set(true));
                    call.setOnReadyHandler(() -> {
                        while (call.isReady() && !call.isCancelled()) {
                            int i = next.getAndIncrement();
                            if (i >= count) {
                                call.onCompleted();
                                return;
                            }
                            call.onNext(DynamicMessage.newBuilder(ITEM)
                                    .setField(ITEM.findFieldByName("id"), Integer.toString(i))
                                    .setField(ITEM.findFieldByName("payload"), payload)
                                    .build());
                            SENT.incrementAndGet();
                        }
                    });
                }))
                .build();
        try {
            return ServerBuilder.forPort(0).addService(service).build().start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
