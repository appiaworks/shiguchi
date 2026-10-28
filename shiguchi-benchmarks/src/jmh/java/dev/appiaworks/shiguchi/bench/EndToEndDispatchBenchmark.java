package dev.appiaworks.shiguchi.bench;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MessageOptions;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.JsonFormat;
import dev.appiaworks.protobuf.extras.serde.WireJsonTranscoder;
import dev.appiaworks.shiguchi.grpc.GrpcDescriptorCache;
import dev.appiaworks.shiguchi.rest.GrpcRequestBuilder;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import dev.appiaworks.shiguchi.serde.DynamicMessageMarshaller;
import dev.appiaworks.shiguchi.serde.DynamicProtoSerde;
import dev.appiaworks.shiguchi.serde.DynamicSerializerFactory;
import dev.appiaworks.shiguchi.serde.SerdeSelector;
import dev.appiaworks.shiguchi.serde.StaticSerdeDetector;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * End-to-end REST response path over a real (in-process) gRPC channel: full client call
 * machinery + per-tier response serde to JSON bytes. The server returns cached wire bytes
 * through a passthrough marshaller, so measurements are dominated by the gateway-side cost.
 *
 *  - baseline_rpcOnly       — channel round-trip, response bytes untouched (floor)
 *  - tier0_wireToJson       — raw bytes → WireJsonTranscoder (no message object)
 *  - tier2_dynamicToJson    — DynamicMessage parse → generated serializer → bytes
 *  - tier3_jsonFormat       — DynamicMessage parse → JsonFormat → bytes
 *  - full_tier0             — request map build + deserialize + call + Tier 0 transcode
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
// Explicit iteration time: without it JMH defaults to 10 s per iteration, which made this
// class alone ~7 min of a ~20 min suite. 2 s matches the profile the README figures were
// taken with (3 warmup + 5×2 s, fork 1).
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class EndToEndDispatchBenchmark {

    private enum RawMarshaller implements io.grpc.MethodDescriptor.Marshaller<byte[]> {
        INSTANCE;

        @Override
        public InputStream stream(byte[] value) {
            return new ByteArrayInputStream(value);
        }

        @Override
        public byte[] parse(InputStream stream) {
            try {
                return stream.readAllBytes();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private Server server;
    private ManagedChannel channel;

    private Descriptor requestDescriptor;
    private Descriptor responseDescriptor;

    private io.grpc.MethodDescriptor<byte[], byte[]> rawMethod;
    private io.grpc.MethodDescriptor<byte[], DynamicMessage> dynamicMethod;
    private io.grpc.MethodDescriptor<DynamicMessage, byte[]> fullMethod;

    private byte[] requestWire;
    private WireJsonTranscoder transcoder;
    private WireJsonTranscoder.Program program;
    private SerdeSelector serdeSelector;
    private ServiceEndpointContext tier2Ctx;
    private boolean tier2;
    private ServiceEndpointContext tier3Ctx;
    private GrpcRequestBuilder requestBuilder;

    // Tier 1: the same schema served by a STATIC-mode service, i.e. with the generated stub on
    // the classpath. Its dynamic descriptor is rebuilt from the compiled file so the detector's
    // schema-equality check passes the way it does for a reflection-served descriptor.
    private io.grpc.MethodDescriptor<byte[], com.google.protobuf.Message> tier1Method;
    private Descriptor tier1ResponseDescriptor;
    private ServiceEndpointContext tier1Ctx;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        FileDescriptor file = buildFile();
        requestDescriptor = file.findMessageTypeByName("GetOrderRequest");
        responseDescriptor = file.findMessageTypeByName("OrderResponse");
        Descriptor itemDescriptor = file.findMessageTypeByName("Item");

        byte[] responseWire = buildResponse(itemDescriptor).toByteArray();
        requestWire = DynamicMessage.newBuilder(requestDescriptor)
                .setField(requestDescriptor.findFieldByName("id"), "order-1")
                .build()
                .toByteArray();

        String fullName = "bench.Orders/Get";
        rawMethod = io.grpc.MethodDescriptor.<byte[], byte[]>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName(fullName)
                .setRequestMarshaller(RawMarshaller.INSTANCE)
                .setResponseMarshaller(RawMarshaller.INSTANCE)
                .build();
        dynamicMethod = io.grpc.MethodDescriptor.<byte[], DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName(fullName)
                .setRequestMarshaller(RawMarshaller.INSTANCE)
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(responseDescriptor))
                .build();
        fullMethod = io.grpc.MethodDescriptor.<DynamicMessage, byte[]>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName(fullName)
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(requestDescriptor))
                .setResponseMarshaller(RawMarshaller.INSTANCE)
                .build();

        ServerServiceDefinition service = ServerServiceDefinition.builder("bench.Orders")
                .addMethod(rawMethod, ServerCalls.asyncUnaryCall((req, obs) -> {
                    obs.onNext(responseWire);
                    obs.onCompleted();
                }))
                .build();
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(service)
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();

        transcoder = new WireJsonTranscoder();
        program = transcoder.programFor(responseDescriptor);
        if (program == null) throw new IllegalStateException("response type must be Tier 0 eligible");

        serdeSelector = new SerdeSelector(new StaticSerdeDetector(), new DynamicProtoSerde(new GrpcDescriptorCache()));
        JsonFormat.TypeRegistry registry = JsonFormat.TypeRegistry.newBuilder()
                .add(responseDescriptor)
                .add(itemDescriptor)
                .build();

        tier2Ctx = new ServiceEndpointContext(new dev.appiaworks.shiguchi.config.ServiceDefinition(), null);
        tier2Ctx.setTypeRegistry(registry);
        // Below Java 25 the generator is absent: the Tier 2 leg refuses to run (JMH reports it
        // and goes on); Tier 1 still runs, through the writer the protoc plugin put on the stub.
        tier2 = dev.appiaworks.protobuf.extras.serde.Codegen.available();
        if (tier2) {
            DynamicSerializerFactory factory = new DynamicSerializerFactory();
            var orderSer = factory.compileDescriptor(responseDescriptor);
            var itemSer = factory.compileDescriptor(itemDescriptor);
            orderSer.setNested(itemDescriptor.getFullName(), itemSer);
            tier2Ctx.putSerializer(responseDescriptor, orderSer);
            tier2Ctx.putSerializer(itemDescriptor, itemSer);
            tier2Ctx.putSerializer(requestDescriptor, factory.compileDescriptor(requestDescriptor));
        }

        tier3Ctx = new ServiceEndpointContext(new dev.appiaworks.shiguchi.config.ServiceDefinition(), null);
        tier3Ctx.setTypeRegistry(registry);

        requestBuilder = new GrpcRequestBuilder();

        FileDescriptor compiled = dev.appiaworks.shiguchi.bench.proto.OrderResponse.getDescriptor()
                .getFile();
        FileDescriptor tier1File = FileDescriptor.buildFrom(
                compiled.toProto(),
                new FileDescriptor[] {Timestamp.getDescriptor().getFile()});
        tier1ResponseDescriptor = tier1File.findMessageTypeByName("OrderResponse");
        tier1Ctx = new ServiceEndpointContext(new dev.appiaworks.shiguchi.config.ServiceDefinition(), null);
        tier1Ctx.setFileDescriptors(Map.of(tier1File.getName(), tier1File));
        tier1Ctx.setTypeRegistry(registry);
        StaticSerdeDetector detector = new StaticSerdeDetector();
        detector.scan(tier1Ctx);
        com.google.protobuf.Message tier1Default = detector.defaultInstanceFor(tier1ResponseDescriptor);
        if (tier1Default == null) {
            throw new IllegalStateException("Tier 1 did not register the compiled OrderResponse stub");
        }
        // The selector the Tier 1 leg serialises through must hold the detector that scanned.
        tier1Selector = new SerdeSelector(detector, new DynamicProtoSerde(new GrpcDescriptorCache()));
        tier1Method = io.grpc.MethodDescriptor.<byte[], com.google.protobuf.Message>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName(fullName)
                .setRequestMarshaller(RawMarshaller.INSTANCE)
                .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(tier1Default))
                .build();
    }

    private SerdeSelector tier1Selector;

    @TearDown(Level.Trial)
    public void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    // ── Benchmarks ───────────────────────────────────────────────────────────────────

    @Benchmark
    public byte[] e2e_baseline_rpcOnly() {
        return ClientCalls.blockingUnaryCall(channel, rawMethod, CallOptions.DEFAULT, requestWire);
    }

    @Benchmark
    public byte[] e2e_tier0_wireToJson() {
        byte[] wire = ClientCalls.blockingUnaryCall(channel, rawMethod, CallOptions.DEFAULT, requestWire);
        byte[] json = transcoder.transcode(program, wire);
        if (json == null) throw new IllegalStateException("unexpected Tier 0 bail");
        return json;
    }

    /** Tier 1: the generated parser reads the wire, the writer compiled for the stub class emits JSON. */
    @Benchmark
    public byte[] e2e_tier1_typedWriter() throws Exception {
        com.google.protobuf.Message response =
                ClientCalls.blockingUnaryCall(channel, tier1Method, CallOptions.DEFAULT, requestWire);
        return tier1Selector.serializeToBytes(response, tier1ResponseDescriptor, tier1Ctx, tier1Ctx.getTypeRegistry());
    }

    @Benchmark
    public byte[] e2e_tier2_dynamicToJson() throws Exception {
        if (!tier2) throw new IllegalStateException("needs the serializer code generator (Java 25+)");
        DynamicMessage response =
                ClientCalls.blockingUnaryCall(channel, dynamicMethod, CallOptions.DEFAULT, requestWire);
        return serdeSelector.serializeToBytes(response, responseDescriptor, tier2Ctx, tier2Ctx.getTypeRegistry());
    }

    @Benchmark
    public byte[] e2e_tier3_jsonFormat() throws Exception {
        DynamicMessage response =
                ClientCalls.blockingUnaryCall(channel, dynamicMethod, CallOptions.DEFAULT, requestWire);
        return serdeSelector.serializeToBytes(response, responseDescriptor, tier3Ctx, tier3Ctx.getTypeRegistry());
    }

    @Benchmark
    public byte[] e2e_full_tier0() throws Exception {
        Map<String, Object> requestMap =
                requestBuilder.buildRequestMap(requestDescriptor, Map.of("id", "order-1"), Map.of(), null);
        DynamicMessage request = serdeSelector.deserializeFromMap(requestMap, requestDescriptor, tier2Ctx);
        byte[] wire = ClientCalls.blockingUnaryCall(channel, fullMethod, CallOptions.DEFAULT, request);
        byte[] json = transcoder.transcode(program, wire);
        if (json == null) throw new IllegalStateException("unexpected Tier 0 bail");
        return json;
    }

    private static final String REQUEST_BODY = "{\"id\":\"order-1\"}";
    private final dev.appiaworks.shiguchi.rest.DirectRequestEncoder directEncoder =
            new dev.appiaworks.shiguchi.rest.DirectRequestEncoder(false);

    /** As {@link #e2e_full_tier0}, with the request as a JSON body instead of a path variable. */
    @Benchmark
    public byte[] e2e_full_tier0_body() throws Exception {
        Map<String, Object> requestMap =
                requestBuilder.buildRequestMap(requestDescriptor, Map.of(), Map.of(), REQUEST_BODY);
        DynamicMessage request = serdeSelector.deserializeFromMap(requestMap, requestDescriptor, tier2Ctx);
        byte[] wire = ClientCalls.blockingUnaryCall(channel, fullMethod, CallOptions.DEFAULT, request);
        byte[] json = transcoder.transcode(program, wire);
        if (json == null) throw new IllegalStateException("unexpected Tier 0 bail");
        return json;
    }

    /** Request side on the wire too: the path variable encoded straight to request bytes, no Map, no message. */
    @Benchmark
    public byte[] e2e_full_direct() {
        byte[] request = directEncoder.encode(requestDescriptor, Map.of("id", "order-1"), Map.of(), null, null);
        if (request == null) throw new IllegalStateException("unexpected direct-request bail");
        byte[] wire = ClientCalls.blockingUnaryCall(channel, rawMethod, CallOptions.DEFAULT, request);
        byte[] json = transcoder.transcode(program, wire);
        if (json == null) throw new IllegalStateException("unexpected Tier 0 bail");
        return json;
    }

    /** As {@link #e2e_full_direct}, with the request as a JSON body streamed to wire bytes. */
    @Benchmark
    public byte[] e2e_full_direct_body() {
        byte[] request = directEncoder.encode(requestDescriptor, Map.of(), Map.of(), REQUEST_BODY, null);
        if (request == null) throw new IllegalStateException("unexpected direct-request bail");
        byte[] wire = ClientCalls.blockingUnaryCall(channel, rawMethod, CallOptions.DEFAULT, request);
        byte[] json = transcoder.transcode(program, wire);
        if (json == null) throw new IllegalStateException("unexpected Tier 0 bail");
        return json;
    }

    // ── Fixture ──────────────────────────────────────────────────────────────────────

    private DynamicMessage buildResponse(Descriptor itemDescriptor) {
        DynamicMessage.Builder b = DynamicMessage.newBuilder(responseDescriptor);
        b.setField(responseDescriptor.findFieldByName("id"), 42_000_000_123L);
        b.setField(responseDescriptor.findFieldByName("name"), "benchmark-order-name");
        b.setField(responseDescriptor.findFieldByName("active"), true);
        b.setField(responseDescriptor.findFieldByName("score"), 12.75d);
        b.setField(responseDescriptor.findFieldByName("count"), 314);
        b.setField(responseDescriptor.findFieldByName("note"), "a moderately sized note field for realism");
        FieldDescriptor items = responseDescriptor.findFieldByName("items");
        for (int i = 0; i < 3; i++) {
            b.addRepeatedField(
                    items,
                    DynamicMessage.newBuilder(itemDescriptor)
                            .setField(itemDescriptor.findFieldByName("sku"), "sku-" + i)
                            .setField(itemDescriptor.findFieldByName("qty"), i + 1)
                            .setField(itemDescriptor.findFieldByName("price"), 9.99d * (i + 1))
                            .setField(itemDescriptor.findFieldByName("note"), "item note " + i)
                            .setField(itemDescriptor.findFieldByName("flag"), i % 2 == 0)
                            .build());
        }
        FieldDescriptor labels = responseDescriptor.findFieldByName("labels");
        Descriptor entry = labels.getMessageType();
        for (int i = 0; i < 3; i++) {
            b.addRepeatedField(
                    labels,
                    DynamicMessage.newBuilder(entry)
                            .setField(entry.findFieldByNumber(1), "k" + i)
                            .setField(entry.findFieldByNumber(2), "v" + i)
                            .build());
        }
        Descriptor ts = responseDescriptor.findFieldByName("created_at").getMessageType();
        b.setField(
                responseDescriptor.findFieldByName("created_at"),
                DynamicMessage.newBuilder(ts)
                        .setField(ts.findFieldByNumber(1), 1_700_000_000L)
                        .setField(ts.findFieldByNumber(2), 123_456_789)
                        .build());
        return b.build();
    }

    private static FileDescriptor buildFile() throws Exception {
        DescriptorProto item = DescriptorProto.newBuilder()
                .setName("Item")
                .addField(scalar("sku", 1, FieldDescriptorProto.Type.TYPE_STRING))
                .addField(scalar("qty", 2, FieldDescriptorProto.Type.TYPE_INT32))
                .addField(scalar("price", 3, FieldDescriptorProto.Type.TYPE_DOUBLE))
                .addField(scalar("note", 4, FieldDescriptorProto.Type.TYPE_STRING))
                .addField(scalar("flag", 5, FieldDescriptorProto.Type.TYPE_BOOL))
                .build();
        DescriptorProto request = DescriptorProto.newBuilder()
                .setName("GetOrderRequest")
                .addField(scalar("id", 1, FieldDescriptorProto.Type.TYPE_STRING))
                .build();
        DescriptorProto response = DescriptorProto.newBuilder()
                .setName("OrderResponse")
                .addField(scalar("id", 1, FieldDescriptorProto.Type.TYPE_INT64))
                .addField(scalar("name", 2, FieldDescriptorProto.Type.TYPE_STRING))
                .addField(scalar("active", 3, FieldDescriptorProto.Type.TYPE_BOOL))
                .addField(scalar("score", 4, FieldDescriptorProto.Type.TYPE_DOUBLE))
                .addField(scalar("count", 5, FieldDescriptorProto.Type.TYPE_INT32))
                .addField(scalar("note", 6, FieldDescriptorProto.Type.TYPE_STRING))
                .addField(message("items", 7, ".bench.Item").toBuilder()
                        .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
                        .build())
                .addNestedType(DescriptorProto.newBuilder()
                        .setName("LabelsEntry")
                        .setOptions(MessageOptions.newBuilder().setMapEntry(true))
                        .addField(scalar("key", 1, FieldDescriptorProto.Type.TYPE_STRING))
                        .addField(scalar("value", 2, FieldDescriptorProto.Type.TYPE_STRING)))
                .addField(message("labels", 8, ".bench.OrderResponse.LabelsEntry").toBuilder()
                        .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
                        .build())
                .addField(message("created_at", 9, ".google.protobuf.Timestamp"))
                .build();

        return FileDescriptor.buildFrom(
                FileDescriptorProto.newBuilder()
                        .setName("bench-e2e.proto")
                        .setSyntax("proto3")
                        .setPackage("bench")
                        .addDependency("google/protobuf/timestamp.proto")
                        .addMessageType(item)
                        .addMessageType(request)
                        .addMessageType(response)
                        .build(),
                new FileDescriptor[] {Timestamp.getDescriptor().getFile()});
    }

    private static FieldDescriptorProto scalar(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .build();
    }

    private static FieldDescriptorProto message(String name, int number, String typeName) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                .setTypeName(typeName)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .build();
    }
}
