package dev.appiaworks.shiguchi.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import dev.appiaworks.protobuf.extras.serde.WireJsonTranscoder;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.grpc.GrpcDescriptorCache;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import dev.appiaworks.shiguchi.serde.DynamicProtoSerde;
import dev.appiaworks.shiguchi.serde.SerdeSelector;
import dev.appiaworks.shiguchi.serde.StaticSerdeDetector;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ServerCalls;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;

/**
 * BDD tests for dispatchStreamingFrames: transcoding wire bytes straight to framed response buffers
 * without materializing message objects on Tier 0. Tests NDJSON and SSE framing, verifies JSON
 * correctness, ensures frame contents match the byte array API, and exercises buffer management
 * including the rewind-on-bail path.
 */
class ReactiveGrpcDispatcherStreamingFramesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DataBufferFactory BUFFER_FACTORY = DefaultDataBufferFactory.sharedInstance;

    private static Descriptor itemDescriptor;
    private static Descriptor requestDescriptor;
    private static io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> grpcMethod;
    private static Server server;
    private static ManagedChannel channel;
    private static ReactiveGrpcDispatcher dispatcher;
    private static RouteDefinition route;
    private static ServiceEndpointContext ctx;

    @BeforeAll
    static void startInProcessServer() throws Exception {
        FileDescriptor file = FileDescriptor.buildFrom(
                FileDescriptorProto.newBuilder()
                        .setName("frames.proto")
                        .setSyntax("proto3")
                        .setPackage("frames")
                        .addMessageType(DescriptorProto.newBuilder()
                                .setName("Item")
                                .addField(scalar("id", 1, FieldDescriptorProto.Type.TYPE_STRING))
                                .addField(scalar("n", 2, FieldDescriptorProto.Type.TYPE_INT32)))
                        .addMessageType(DescriptorProto.newBuilder()
                                .setName("ListRequest")
                                .addField(scalar("count", 1, FieldDescriptorProto.Type.TYPE_INT32)))
                        .addService(ServiceDescriptorProto.newBuilder()
                                .setName("ItemList")
                                .addMethod(MethodDescriptorProto.newBuilder()
                                        .setName("List")
                                        .setInputType(".frames.ListRequest")
                                        .setOutputType(".frames.Item")
                                        .setServerStreaming(true)))
                        .build(),
                new FileDescriptor[0]);
        itemDescriptor = file.findMessageTypeByName("Item");
        requestDescriptor = file.findMessageTypeByName("ListRequest");

        grpcMethod = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.SERVER_STREAMING)
                .setFullMethodName("frames.ItemList/List")
                .setRequestMarshaller(
                        dev.appiaworks.shiguchi.serde.DynamicMessageMarshaller.forDescriptor(requestDescriptor))
                .setResponseMarshaller(
                        dev.appiaworks.shiguchi.serde.DynamicMessageMarshaller.forDescriptor(itemDescriptor))
                .build();

        ServerServiceDefinition service = ServerServiceDefinition.builder("frames.ItemList")
                .addMethod(grpcMethod, ServerCalls.asyncServerStreamingCall((request, responseObserver) -> {
                    int count = (Integer) request.getField(requestDescriptor.findFieldByNumber(1));
                    for (int i = 0; i < count; i++) {
                        DynamicMessage item = DynamicMessage.newBuilder(itemDescriptor)
                                .setField(itemDescriptor.findFieldByName("id"), "item-" + i)
                                .setField(itemDescriptor.findFieldByName("n"), i * 10)
                                .build();
                        responseObserver.onNext(item);
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

        var props = new GatewayProperties();
        props.getClient().setDefaultDeadline(Duration.ZERO);
        var serde = new SerdeSelector(new StaticSerdeDetector(), new DynamicProtoSerde(new GrpcDescriptorCache()));
        dispatcher = new ReactiveGrpcDispatcher(
                new GrpcRequestBuilder(), serde, new GrpcDescriptorCache(), new WireJsonTranscoder(), props);

        ctx = new ServiceEndpointContext(new dev.appiaworks.shiguchi.config.ServiceDefinition(), channel);
        ctx.setFileDescriptors(Map.of(file.getName(), file));
        var service_desc = file.findServiceByName("ItemList");
        ctx.setServiceByFqn(Map.of(service_desc.getFullName(), service_desc));
        Map<String, com.google.protobuf.Descriptors.MethodDescriptor> byKey = new HashMap<>();
        for (var md : service_desc.getMethods()) {
            byKey.put(service_desc.getFullName() + "/" + md.getName(), md);
        }
        ctx.setMethodByKey(byKey);
        var types = com.google.protobuf.util.JsonFormat.TypeRegistry.newBuilder();
        types.add(itemDescriptor);
        types.add(requestDescriptor);
        ctx.setTypeRegistry(types.build());

        route = RouteDefinition.builder("frames.ItemList", "List", MethodType.SERVER_STREAMING, "items")
                .httpBinding("GET", "/v1/items")
                .build();
        ctx.addRoute(route);
    }

    @AfterAll
    static void shutdown() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    @Test
    @DisplayName("Given count=3 and NDJSON framing (empty prefix, newline suffix), "
            + "when frames are collected, should yield 3 buffers each ending with newline "
            + "and each parsing as JSON with id and n fields")
    void given_count3_when_ndjsonFraming_should_yield3FramesWithNewlineSuffix() throws Exception {
        String requestBody = "{\"count\": 3}";

        List<DataBuffer> buffers = dispatcher
                .dispatchStreamingFrames(
                        route,
                        ctx,
                        Map.of(),
                        Map.of(),
                        requestBody,
                        new io.grpc.Metadata(),
                        BUFFER_FACTORY,
                        new byte[0],
                        new byte[] {10},
                        4096)
                .collectList()
                .block(Duration.ofSeconds(10));

        assertThat(buffers).hasSize(3);
        for (int i = 0; i < 3; i++) {
            DataBuffer buf = buffers.get(i);
            byte[] bytes = new byte[buf.readableByteCount()];
            buf.read(bytes);
            DataBufferUtils.release(buf);

            // Each frame should end with newline
            assertThat(bytes[bytes.length - 1]).isEqualTo((byte) 10);
            // Remove newline and parse
            byte[] jsonBytes = new byte[bytes.length - 1];
            System.arraycopy(bytes, 0, jsonBytes, 0, jsonBytes.length);
            var tree = MAPPER.readTree(jsonBytes);
            assertThat(tree.has("id")).isTrue();
            assertThat(tree.has("n")).isTrue();
            assertThat(tree.get("id").asText()).isEqualTo("item-" + i);
            assertThat(tree.get("n").asInt()).isEqualTo(i * 10);
        }
    }

    @Test
    @DisplayName("Given SSE framing (prefix 'data: ', suffix two newlines), "
            + "each buffer should start with 'data: ' and end with two newlines")
    void given_sseFraming_when_framed_should_haveDataPrefixAndDoubleNewline() throws Exception {
        byte[] prefix = "data: ".getBytes(StandardCharsets.UTF_8);
        byte[] suffix = new byte[] {10, 10};
        String requestBody = "{\"count\": 2}";

        List<DataBuffer> buffers = dispatcher
                .dispatchStreamingFrames(
                        route,
                        ctx,
                        Map.of(),
                        Map.of(),
                        requestBody,
                        new io.grpc.Metadata(),
                        BUFFER_FACTORY,
                        prefix,
                        suffix,
                        4096)
                .collectList()
                .block(Duration.ofSeconds(10));

        assertThat(buffers).hasSize(2);
        for (int i = 0; i < 2; i++) {
            DataBuffer buf = buffers.get(i);
            byte[] bytes = new byte[buf.readableByteCount()];
            buf.read(bytes);
            DataBufferUtils.release(buf);

            // Should start with "data: "
            assertThat(bytes).startsWith("data: ".getBytes(StandardCharsets.UTF_8));
            // Should end with two newlines
            assertThat(bytes[bytes.length - 1]).isEqualTo((byte) 10);
            assertThat(bytes[bytes.length - 2]).isEqualTo((byte) 10);
        }
    }

    @Test
    @DisplayName("Given count=0, when frames are collected, should complete with no frames")
    void given_count0_when_framed_should_haveZeroFrames() {
        String requestBody = "{\"count\": 0}";

        List<DataBuffer> buffers = dispatcher
                .dispatchStreamingFrames(
                        route,
                        ctx,
                        Map.of(),
                        Map.of(),
                        requestBody,
                        new io.grpc.Metadata(),
                        BUFFER_FACTORY,
                        new byte[0],
                        new byte[] {10},
                        4096)
                .collectList()
                .block(Duration.ofSeconds(10));

        assertThat(buffers).isEmpty();
    }

    @Test
    @DisplayName("Given the frames from dispatchStreamingFrames with NDJSON, "
            + "the JSON of each should equal what dispatchStreaming yields for the same request, "
            + "element by element")
    void given_dispatchStreamingFrames_when_compared_should_yieldSameJsonAsDispatchStreaming() throws Exception {
        String requestBody = "{\"count\": 3}";

        List<byte[]> streamingJson = dispatcher
                .dispatchStreaming(route, ctx, Map.of(), Map.of(), requestBody, new io.grpc.Metadata())
                .collectList()
                .block(Duration.ofSeconds(10));

        List<DataBuffer> frames = dispatcher
                .dispatchStreamingFrames(
                        route,
                        ctx,
                        Map.of(),
                        Map.of(),
                        requestBody,
                        new io.grpc.Metadata(),
                        BUFFER_FACTORY,
                        new byte[0],
                        new byte[] {10},
                        4096)
                .collectList()
                .block(Duration.ofSeconds(10));

        assertThat(frames).hasSize(streamingJson.size());
        for (int i = 0; i < streamingJson.size(); i++) {
            DataBuffer buf = frames.get(i);
            byte[] frameBytes = new byte[buf.readableByteCount()];
            buf.read(frameBytes);
            DataBufferUtils.release(buf);

            // Remove suffix (newline) from frame
            byte[] frameJson = new byte[frameBytes.length - 1];
            System.arraycopy(frameBytes, 0, frameJson, 0, frameJson.length);

            // Compare as JSON trees (order-independent)
            var frameTree = MAPPER.readTree(frameJson);
            var streamingTree = MAPPER.readTree(streamingJson.get(i));
            assertThat(frameTree).isEqualTo(streamingTree);
        }
    }

    @Test
    @DisplayName(
            "Given prefix/suffix and a sizeHint of 1 (forcing buffer growth), " + "should still produce correct frames")
    void given_sizeHint1AndGrowth_when_framed_should_produceCorrectFrames() throws Exception {
        byte[] prefix = "pre:".getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ":end".getBytes(StandardCharsets.UTF_8);
        int sizeHint = 1; // Force growth
        String requestBody = "{\"count\": 2}";

        List<DataBuffer> buffers = dispatcher
                .dispatchStreamingFrames(
                        route,
                        ctx,
                        Map.of(),
                        Map.of(),
                        requestBody,
                        new io.grpc.Metadata(),
                        BUFFER_FACTORY,
                        prefix,
                        suffix,
                        sizeHint)
                .collectList()
                .block(Duration.ofSeconds(10));

        assertThat(buffers).isNotEmpty();
        for (DataBuffer buf : buffers) {
            byte[] bytes = new byte[buf.readableByteCount()];
            buf.read(bytes);
            DataBufferUtils.release(buf);

            // Should have prefix and suffix
            String str = new String(bytes, StandardCharsets.UTF_8);
            assertThat(str).startsWith("pre:");
            assertThat(str).endsWith(":end");

            // Should contain valid JSON in between
            String jsonStr = str.substring(4, str.length() - 4);
            var tree = MAPPER.readTree(jsonStr);
            assertThat(tree.has("id")).isTrue();
            assertThat(tree.has("n")).isTrue();
        }
    }

    private static FieldDescriptorProto scalar(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .build();
    }
}
