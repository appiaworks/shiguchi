package dev.appiaworks.shiguchi.bench;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import dev.appiaworks.protobuf.extras.serde.WireJsonTranscoder;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.grpc.GrpcDescriptorCache;
import dev.appiaworks.shiguchi.rest.GrpcRequestBuilder;
import dev.appiaworks.shiguchi.rest.ReactiveGrpcDispatcher;
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
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * What a Tier 0 bail costs. {@code WireJsonTranscoder} streams wire bytes straight to JSON but
 * cannot express proto merge semantics — a non-repeated field that appears twice on the wire (the
 * last one wins) — so on such input it bails and the dispatcher re-parses the bytes into a
 * {@code DynamicMessage} for Tier 2. Legal, rare, and the reason the fast path has a fallback at
 * all; this measures the detour through the real REST dispatch.
 *
 * <ul>
 *   <li>{@code wire = clean} — the fixture order as protobuf writes it: Tier 0 succeeds.
 *   <li>{@code wire = duplicatedField} — the same bytes with {@code name} encoded a second time at
 *       the end (a merge-semantics wire, exactly as a proto library would still parse it): Tier 0
 *       bails, the dispatcher parses and Tier 2 writes the JSON.
 * </ul>
 *
 * <p>{@code restDispatch} is the whole unary REST path over an in-process channel;
 * {@code transcodeOnly} isolates the transcoder itself — for the duplicated wire that is the cost
 * of detecting the bail, since the result is {@code null}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class Tier0FallbackBenchmark {

    @Param({"clean", "duplicatedField"})
    public String wire;

    /** Passes the response bytes through untouched, so the server serves exactly the fixture wire. */
    private static final io.grpc.MethodDescriptor.Marshaller<byte[]> BYTES =
            new io.grpc.MethodDescriptor.Marshaller<>() {
                @Override
                public java.io.InputStream stream(byte[] value) {
                    return new java.io.ByteArrayInputStream(value);
                }

                @Override
                public byte[] parse(java.io.InputStream stream) {
                    try {
                        return stream.readAllBytes();
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                }
            };

    private Server server;
    private ManagedChannel channel;
    private ReactiveGrpcDispatcher dispatcher;
    private RouteDefinition route;
    private ServiceEndpointContext ctx;
    private WireJsonTranscoder transcoder;
    private WireJsonTranscoder.Program program;
    private byte[] responseWire;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        byte[] clean = BenchOrders.order(42_000_000_123L, 3).toByteArray();
        responseWire = "clean".equals(wire) ? clean : withDuplicatedName(clean);

        FileDescriptor file = BenchOrders.dynamicFile();
        Descriptor response = file.findMessageTypeByName("OrderResponse");
        transcoder = new WireJsonTranscoder();
        program = transcoder.programFor(response);
        if (program == null) throw new IllegalStateException("fixture must be Tier 0 eligible");
        byte[] direct = transcoder.transcode(program, responseWire);
        if ("clean".equals(wire) != (direct != null)) {
            throw new IllegalStateException("expected Tier 0 " + ("clean".equals(wire) ? "success" : "bail"));
        }
        // The duplicated wire must still be a legal message — last value wins.
        DynamicMessage parsed = DynamicMessage.parseFrom(response, responseWire);
        if (!"clean".equals(wire) && !"duplicated-name".equals(parsed.getField(response.findFieldByName("name")))) {
            throw new IllegalStateException("merge semantics not exercised");
        }

        var method = io.grpc.MethodDescriptor.<byte[], byte[]>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName(BenchOrders.SERVICE + "/Get")
                .setRequestMarshaller(BYTES)
                .setResponseMarshaller(BYTES)
                .build();
        var service = ServerServiceDefinition.builder(BenchOrders.SERVICE)
                .addMethod(method, ServerCalls.asyncUnaryCall((req, obs) -> {
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

        var props = new GatewayProperties();
        props.getClient().setDefaultDeadline(java.time.Duration.ZERO);
        var serde = new SerdeSelector(new StaticSerdeDetector(), new DynamicProtoSerde(new GrpcDescriptorCache()));
        dispatcher = new ReactiveGrpcDispatcher(
                new GrpcRequestBuilder(), serde, new GrpcDescriptorCache(), new WireJsonTranscoder(), props);
        ctx = BenchOrders.context("orders", file, channel);
        route = RouteDefinition.builder(BenchOrders.SERVICE, "Get", MethodType.UNARY, "orders")
                .httpBinding("GET", "/v1/orders/{id}")
                .build();
        ctx.addRoute(route);

        byte[] json = restDispatch();
        if (json == null || json.length == 0) throw new IllegalStateException("dispatch produced no body");
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    /** The unary REST path: Tier 0 when the wire allows it, parse + Tier 2 when it bails. */
    @Benchmark
    public byte[] restDispatch() {
        return dispatcher
                .dispatch(route, ctx, Map.of("id", "order-1"), Map.of(), null, null)
                .block()
                .body();
    }

    /** The transcoder alone: JSON for the clean wire, {@code null} (the bail) for the duplicated one. */
    @Benchmark
    public byte[] transcodeOnly() {
        return transcoder.transcode(program, responseWire);
    }

    /** Appends a second {@code name} (field 2, string) so the wire carries merge semantics. */
    private static byte[] withDuplicatedName(byte[] clean) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(clean.length + 32);
        out.write(clean);
        CodedOutputStream coded = CodedOutputStream.newInstance(out);
        coded.writeString(2, "duplicated-name");
        coded.flush();
        return out.toByteArray();
    }
}
