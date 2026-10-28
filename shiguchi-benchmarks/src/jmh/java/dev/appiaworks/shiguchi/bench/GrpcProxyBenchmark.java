package dev.appiaworks.shiguchi.bench;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import com.google.protobuf.DynamicMessage;
import dev.appiaworks.shiguchi.grpc.GrpcDescriptorCache;
import dev.appiaworks.shiguchi.grpc.GrpcProxyHandlerRegistry;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import dev.appiaworks.shiguchi.serde.DynamicMessageMarshaller;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.ServerMethodDefinition;
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
 * Measures GrpcProxyHandlerRegistry.lookupMethod() hot path:
 *  - baseline: builds new ServerMethodDefinition on every call
 *  - optimized: ServerMethodDefinition cached (P1-D fix)
 *
 * Also measures GrpcDescriptorCache.resolve() to isolate its cost.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class GrpcProxyBenchmark {

    private GrpcProxyHandlerRegistry handlerRegistry;
    private GrpcDescriptorCache descriptorCache;
    private ServiceEndpointContext ctx;
    private ServiceDescriptor serviceDescriptor;
    private MethodDescriptor methodDescriptor;
    private ManagedChannel channel;

    private static final String FULL_METHOD = "com.example.BenchService/GetItem";

    @Setup(Level.Trial)
    public void setup() throws Exception {
        // Channel points at a dead port on purpose: the benchmarked paths (lookupMethod,
        // descriptorCache.resolve, marshallerFor) only need a non-null channel reference —
        // no RPC is ever issued, so nothing has to be listening.
        channel = ManagedChannelBuilder.forAddress("localhost", 19999)
                .usePlaintext()
                .build();

        var itemMsg = DescriptorProto.newBuilder()
                .setName("Item")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("id")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_INT64)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL));
        var requestMsg = DescriptorProto.newBuilder()
                .setName("GetItemRequest")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("id")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_INT64)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL));
        var svcProto = ServiceDescriptorProto.newBuilder()
                .setName("BenchService")
                .addMethod(MethodDescriptorProto.newBuilder()
                        .setName("GetItem")
                        .setInputType(".GetItemRequest")
                        .setOutputType(".Item"));
        var fd = FileDescriptor.buildFrom(
                FileDescriptorProto.newBuilder()
                        .setName("bench.proto")
                        .setSyntax("proto3")
                        .addMessageType(itemMsg)
                        .addMessageType(requestMsg)
                        .addService(svcProto)
                        .build(),
                new FileDescriptor[0]);

        serviceDescriptor = fd.getServices().get(0);
        methodDescriptor = serviceDescriptor.getMethods().get(0);

        dev.appiaworks.shiguchi.config.ServiceDefinition def = new dev.appiaworks.shiguchi.config.ServiceDefinition();
        def.setName("bench-svc");
        def.setHost("localhost");
        def.setPort(19999);
        ctx = new ServiceEndpointContext(def, channel);
        ctx.setServiceByFqn(java.util.Map.of(serviceDescriptor.getFullName(), serviceDescriptor));
        ctx.setMethodByKey(java.util.Map.of(serviceDescriptor.getFullName() + "/GetItem", methodDescriptor));

        RouteDefinition route = RouteDefinition.builder(
                        serviceDescriptor.getFullName(),
                        "GetItem",
                        io.grpc.MethodDescriptor.MethodType.UNARY,
                        "localhost:19999")
                .httpBinding("GET", "/v1/items/{id}")
                .build();
        ctx.addRoute(route);

        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        registry.registerContext(ctx);

        descriptorCache = new GrpcDescriptorCache();
        handlerRegistry = new GrpcProxyHandlerRegistry(registry, descriptorCache);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        channel.shutdownNow();
    }

    /** lookupMethod — first call populates cache, subsequent calls hit it (P1-D). */
    @Benchmark
    public ServerMethodDefinition<?, ?> lookupMethod_cached() {
        return handlerRegistry.lookupMethod(FULL_METHOD, "localhost");
    }

    /** GrpcDescriptorCache.resolve() — isolated cost of MethodDescriptor lookup. */
    @Benchmark
    public io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> descriptorCache_resolve() {
        return descriptorCache.resolve(
                ctx, serviceDescriptor, methodDescriptor, io.grpc.MethodDescriptor.MethodType.UNARY);
    }

    /** DynamicMessageMarshaller creation for a descriptor. */
    @Benchmark
    public DynamicMessageMarshaller marshallerFor() {
        return descriptorCache.marshallerFor(methodDescriptor.getInputType());
    }
}
