package dev.appiaworks.shiguchi.bff.starter;

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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * What the live-query end-to-end tests share: a gRPC backend with a query {@code item(id)} and a
 * mutation {@code setItemName(input)} over one mutable name, and a hand-rolled graphql-transport-ws
 * client that keeps every frame it receives.
 */
final class GraphQlLiveTestSupport {

    static final String LIVE_QUERY = "query Live @live { item(id: \"7\") { name } }";
    static final String MUTATION_PREFIX = "mutation { setItemName(input: {id: \"7\", name: \"";

    private GraphQlLiveTestSupport() {}

    /** The backend: {@link #name} is what {@code item} returns, {@code setItemName} changes it. */
    static final class Backend implements AutoCloseable {
        private static final FileDescriptor FILE = buildFile();
        private static final Descriptor ITEM = FILE.findMessageTypeByName("Item");
        private static final Descriptor GET_REQUEST = FILE.findMessageTypeByName("GetRequest");
        private static final Descriptor SET_REQUEST = FILE.findMessageTypeByName("SetRequest");

        final AtomicReference<String> name = new AtomicReference<>("a");
        final Server server;
        final Path descriptors;

        Backend() {
            this.server = start();
            this.descriptors = writeDescriptorSet();
        }

        /** The service entry gateways read the backend from. */
        List<String> gatewayProperties() {
            return List.of(
                    "shiguchi.services[0].name=store",
                    "shiguchi.services[0].mode=PB_FILE",
                    "shiguchi.services[0].pb-descriptor-path=" + descriptors,
                    "shiguchi.services[0].host=localhost",
                    "shiguchi.services[0].port=" + server.getPort());
        }

        @Override
        public void close() throws IOException {
            server.shutdownNow();
            Files.deleteIfExists(descriptors);
        }

        private Server start() {
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
                        name.set((String) request.getField(SET_REQUEST.findFieldByName("name")));
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

        private DynamicMessage item(String id) {
            return DynamicMessage.newBuilder(ITEM)
                    .setField(ITEM.findFieldByName("id"), id)
                    .setField(ITEM.findFieldByName("name"), name.get())
                    .build();
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
                        .addMessageType(DescriptorProto.newBuilder()
                                .setName("GetRequest")
                                .addField(field("id", 1)))
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
    }

    /** One graphql-transport-ws connection that keeps the raw text of every frame. */
    static final class Socket implements AutoCloseable {
        final List<String> frames = new CopyOnWriteArrayList<>();
        private final Sinks.Many<String> outbound = Sinks.many().unicast().onBackpressureBuffer();
        private final Disposable connection;

        Socket(int port, Consumer<HttpHeaders> headers) {
            outbound.tryEmitNext("{\"type\":\"connection_init\"}");
            WebSocketHandler handler = new WebSocketHandler() {
                @Override
                public List<String> getSubProtocols() {
                    return List.of("graphql-transport-ws");
                }

                @Override
                public Mono<Void> handle(WebSocketSession session) {
                    return Mono.when(
                            session.send(outbound.asFlux().map(session::textMessage)),
                            session.receive()
                                    .map(WebSocketMessage::getPayloadAsText)
                                    .doOnNext(frames::add)
                                    .then());
                }
            };
            HttpHeaders h = new HttpHeaders();
            headers.accept(h);
            connection = new ReactorNettyWebSocketClient()
                    .execute(URI.create("ws://localhost:" + port + "/graphql/ws"), h, handler)
                    .subscribe(v -> {}, e -> frames.add("{\"type\":\"failed\",\"message\":\"" + e + "\"}"));
        }

        void subscribe(String id, String query) {
            await(() -> frames.stream().anyMatch(f -> f.contains("connection_ack")));
            outbound.tryEmitNext("{\"id\":\"" + id + "\",\"type\":\"subscribe\",\"payload\":{\"query\":\""
                    + query.replace("\"", "\\\"") + "\"}}");
        }

        /** The {@code next} frames of operation {@code id} whose data names the item {@code name}. */
        List<String> nexts(String id, String name) {
            return frames.stream()
                    .filter(f -> f.contains("\"type\":\"next\"")
                            && f.contains("\"id\":\"" + id + "\"")
                            && f.contains("\"name\":\"" + name + "\""))
                    .toList();
        }

        long nextCount(String id) {
            return frames.stream()
                    .filter(f -> f.contains("\"type\":\"next\"") && f.contains("\"id\":\"" + id + "\""))
                    .count();
        }

        @Override
        public void close() {
            outbound.tryEmitComplete();
            connection.dispose();
        }
    }

    static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition not met in 30 s");
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
