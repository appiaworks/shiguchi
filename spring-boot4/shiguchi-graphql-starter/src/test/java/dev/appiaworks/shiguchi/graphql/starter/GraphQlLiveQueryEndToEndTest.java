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
import com.google.protobuf.util.JsonFormat;
import dev.appiaworks.shiguchi.proto.GraphQlProto;
import dev.appiaworks.shiguchi.proto.GraphQlRule;
import dev.appiaworks.shiguchi.proto.GraphQlRule.Op;
import dev.appiaworks.shiguchi.serde.DynamicMessageMarshaller;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerServiceDefinition;
import io.grpc.stub.ServerCalls;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * {@code query ... @live} over a real graphql-transport-ws connection on the Boot 4 gateway: the
 * first result, a new one after a mutation through the gateway, no new one when the data did not
 * change, the per-principal limit, and a plain query on the socket still running once.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.openapi.enabled=false",
            "spring.graphql.websocket.path=/graphql/ws",
            "shiguchi.graphql.live.enabled=true",
            "shiguchi.graphql.live.min-interval=100ms",
            "shiguchi.graphql.live.max-per-principal=2"
        })
@DisplayName("GraphQL live queries over WebSocket end to end (Boot 4, DGS)")
class GraphQlLiveQueryEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private static final FileDescriptor FILE = buildFile();
    private static final Descriptor ITEM = FILE.findMessageTypeByName("Item");
    private static final Descriptor GET_REQUEST = FILE.findMessageTypeByName("GetRequest");
    private static final Descriptor SET_REQUEST = FILE.findMessageTypeByName("SetRequest");
    private static final AtomicReference<String> NAME = new AtomicReference<>("a");
    private static final Server BACKEND = startBackend();
    private static final Path PB_FILE = writeDescriptorSet();

    @DynamicPropertySource
    static void backend(DynamicPropertyRegistry registry) {
        registry.add("shiguchi.services[0].name", () -> "store");
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
    private WebClient http;

    @BeforeEach
    void reset() {
        String port = environment.getRequiredProperty("local.server.port");
        wsUri = URI.create("ws://localhost:" + port + "/graphql/ws");
        http = WebClient.create("http://localhost:" + port);
        NAME.set("a");
    }

    private static final String LIVE = "query Live @live { item(id: \\\"7\\\") { name } }";

    @Test
    @DisplayName("GIVEN a live query WHEN a mutation changes its data SHOULD send a second result numbered 2")
    void given_liveQuery_when_mutationChangesData_should_sendSecondResult() throws Exception {
        List<Struct> frames = new CopyOnWriteArrayList<>();
        Disposable socket = subscribe(LIVE, frames);
        try {
            waitFor(() -> nexts(frames).size() == 1);
            assertThat(name(nexts(frames).get(0))).isEqualTo("a");
            assertThat(seq(nexts(frames).get(0))).isEqualTo(1);

            mutate("b");

            waitFor(() -> nexts(frames).size() == 2);
            assertThat(name(nexts(frames).get(1))).isEqualTo("b");
            assertThat(seq(nexts(frames).get(1))).isEqualTo(2);
        } finally {
            socket.dispose();
        }
    }

    @Test
    @DisplayName("GIVEN a live query WHEN a mutation leaves its data unchanged SHOULD send no new result")
    void given_liveQuery_when_dataUnchanged_should_sendNothing() throws Exception {
        List<Struct> frames = new CopyOnWriteArrayList<>();
        Disposable socket = subscribe(LIVE, frames);
        try {
            waitFor(() -> nexts(frames).size() == 1);

            mutate("a");
            Thread.sleep(1500);

            assertThat(nexts(frames)).hasSize(1);
        } finally {
            socket.dispose();
        }
    }

    @Test
    @DisplayName("GIVEN two live queries running WHEN a third starts SHOULD be refused with LIVE_LIMIT")
    void given_limitReached_when_anotherLiveQuery_should_beRefused() throws Exception {
        List<Struct> first = new CopyOnWriteArrayList<>();
        List<Struct> second = new CopyOnWriteArrayList<>();
        List<Struct> third = new CopyOnWriteArrayList<>();
        Disposable one = subscribe(LIVE, first);
        Disposable two = subscribe(LIVE, second);
        try {
            waitFor(() -> nexts(first).size() == 1 && nexts(second).size() == 1);
            Disposable three = subscribe(LIVE, third);
            try {
                waitFor(() -> !third.stream()
                                .filter(f -> "next".equals(type(f)))
                                .toList()
                                .isEmpty()
                        || third.stream().anyMatch(f -> "error".equals(type(f))));
                assertThat(third.toString()).contains("LIVE_LIMIT");
            } finally {
                three.dispose();
            }
        } finally {
            one.dispose();
            two.dispose();
        }
    }

    @Test
    @DisplayName("GIVEN a query without @live WHEN sent over the socket SHOULD run once and complete")
    void given_plainQuery_when_sentOverSocket_should_runOnceAndComplete() throws Exception {
        List<Struct> frames = new CopyOnWriteArrayList<>();
        Disposable socket = subscribe("query { item(id: \\\"7\\\") { name } }", frames);
        try {
            waitFor(() -> frames.stream().anyMatch(f -> "complete".equals(type(f))));
            assertThat(nexts(frames)).hasSize(1);
        } finally {
            socket.dispose();
        }
    }

    @Test
    @DisplayName("GIVEN a live query over SSE WHEN a mutation changes its data SHOULD stream a second event")
    void given_liveQueryOverSse_when_mutationChangesData_should_streamSecondEvent() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        Disposable stream = http.post()
                .uri("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(Map.of("query", "query Live @live { item(id: \"7\") { name } }"))
                .retrieve()
                .bodyToFlux(String.class)
                .subscribe(events::add, error -> events.add("error:" + error));
        try {
            waitFor(() -> events.stream().anyMatch(e -> e.contains("\"name\":\"a\"")));

            mutate("b");

            waitFor(() -> events.stream().anyMatch(e -> e.contains("\"name\":\"b\"")));
            assertThat(events.stream()
                            .filter(e -> e.contains("\"name\":\"b\""))
                            .findFirst()
                            .orElseThrow())
                    .contains("\"seq\":2");
        } finally {
            stream.dispose();
        }
    }

    private void mutate(String name) {
        http.post()
                .uri("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "query", "mutation { setItemName(input: {id: \"7\", name: \"" + name + "\"}) { name } }"))
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(30));
    }

    /** Speaks graphql-transport-ws by hand and leaves the socket open; dispose to close it. */
    private Disposable subscribe(String query, List<Struct> frames) {
        Sinks.Many<String> outbound = Sinks.many().unicast().onBackpressureBuffer();
        outbound.tryEmitNext("{\"type\":\"connection_init\"}");
        String subscribeFrame = "{\"id\":\"1\",\"type\":\"subscribe\",\"payload\":{\"query\":\"" + query + "\"}}";
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
                        .map(GraphQlLiveQueryEndToEndTest::parse)
                        .doOnNext(frames::add)
                        .doOnNext(frame -> {
                            if ("connection_ack".equals(type(frame))) outbound.tryEmitNext(subscribeFrame);
                        });
                return Mono.when(send, inbound.then());
            }
        };
        return new ReactorNettyWebSocketClient().execute(wsUri, handler).subscribe();
    }

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition not met in 20 s");
            Thread.sleep(50);
        }
    }

    private static List<Struct> nexts(List<Struct> frames) {
        return frames.stream().filter(f -> "next".equals(type(f))).toList();
    }

    private static String type(Struct frame) {
        return frame.getFieldsOrThrow("type").getStringValue();
    }

    private static String name(Struct next) {
        return payload(next)
                .getFieldsOrThrow("data")
                .getStructValue()
                .getFieldsOrThrow("item")
                .getStructValue()
                .getFieldsOrThrow("name")
                .getStringValue();
    }

    private static int seq(Struct next) {
        return (int) payload(next)
                .getFieldsOrThrow("extensions")
                .getStructValue()
                .getFieldsOrThrow("live")
                .getStructValue()
                .getFieldsOrThrow("seq")
                .getNumberValue();
    }

    private static Struct payload(Struct next) {
        return next.getFieldsOrThrow("payload").getStructValue();
    }

    private static Struct parse(String text) {
        try {
            Struct.Builder b = Struct.newBuilder();
            JsonFormat.parser().merge(text, b);
            return b.build();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static FileDescriptor buildFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("cg/live.proto")
                    .setSyntax("proto3")
                    .setPackage("cg")
                    .addDependency("shiguchi/graphql.proto")
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Item")
                            .addField(field("id", 1))
                            .addField(field("name", 2)))
                    .addMessageType(
                            DescriptorProto.newBuilder().setName("GetRequest").addField(field("id", 1)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("SetRequest")
                            .addField(field("id", 1))
                            .addField(field("name", 2)))
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Store")
                            .addMethod(method("GetItem", "GetRequest", "item", Op.QUERY))
                            .addMethod(method("SetItemName", "SetRequest", "setItemName", Op.MUTATION)))
                    .build();
            return FileDescriptor.buildFrom(proto, new FileDescriptor[] {GraphQlProto.getDescriptor()});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static MethodDescriptorProto.Builder method(String name, String input, String graphQlName, Op op) {
        return MethodDescriptorProto.newBuilder()
                .setName(name)
                .setInputType(".cg." + input)
                .setOutputType(".cg.Item")
                .setOptions(MethodOptions.newBuilder()
                        .setExtension(
                                GraphQlProto.graphql,
                                GraphQlRule.newBuilder()
                                        .setName(graphQlName)
                                        .setType(op)
                                        .build()));
    }

    private static FieldDescriptorProto.Builder field(String name, int number) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(FieldDescriptorProto.Type.TYPE_STRING)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
    }

    private static Path writeDescriptorSet() {
        try {
            Path path = Files.createTempFile("cg-live", ".pb");
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

    private static Server startBackend() {
        var get = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("cg.Store/GetItem")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(GET_REQUEST))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(ITEM))
                .build();
        var set = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("cg.Store/SetItemName")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(SET_REQUEST))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(ITEM))
                .build();
        ServerServiceDefinition service = ServerServiceDefinition.builder("cg.Store")
                .addMethod(get, ServerCalls.asyncUnaryCall((request, observer) -> {
                    observer.onNext(item((String) request.getField(GET_REQUEST.findFieldByName("id"))));
                    observer.onCompleted();
                }))
                .addMethod(set, ServerCalls.asyncUnaryCall((request, observer) -> {
                    NAME.set((String) request.getField(SET_REQUEST.findFieldByName("name")));
                    observer.onNext(item((String) request.getField(SET_REQUEST.findFieldByName("id"))));
                    observer.onCompleted();
                }))
                .build();
        try {
            return ServerBuilder.forPort(0).addService(service).build().start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static DynamicMessage item(String id) {
        return DynamicMessage.newBuilder(ITEM)
                .setField(ITEM.findFieldByName("id"), id)
                .setField(ITEM.findFieldByName("name"), NAME.get())
                .build();
    }
}
