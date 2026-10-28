package dev.appiaworks.shiguchi.bench;

import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.FileDescriptor;
import dev.appiaworks.shiguchi.bench.proto.GetOrderRequest;
import dev.appiaworks.shiguchi.bench.proto.OrderResponse;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServiceDescriptor;
import io.grpc.protobuf.ProtoFileDescriptorSupplier;
import io.grpc.protobuf.ProtoServiceDescriptorSupplier;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import io.grpc.stub.ServerCalls;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The backend of the side-by-side comparison ({@code comparison/compare.sh}): {@code bench.Orders}
 * with server reflection on {@code $PORT} (default 9090), answering the same three-item order
 * {@link GatewayHttpBenchmark} serves. With a path as the first argument it also writes the
 * {@code FileDescriptorSet} (imports included) that Envoy's transcoder reads, then keeps serving.
 *
 * <pre>java -cp shiguchi-benchmarks-*-jmh.jar dev.appiaworks.shiguchi.bench.ComparisonBackend [orders.pb]</pre>
 */
public final class ComparisonBackend {

    private record Schema(FileDescriptor file, com.google.protobuf.Descriptors.ServiceDescriptor service)
            implements ProtoFileDescriptorSupplier, ProtoServiceDescriptorSupplier {
        @Override
        public FileDescriptor getFileDescriptor() {
            return file;
        }

        @Override
        public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
            return service;
        }
    }

    public static void main(String[] args) throws Exception {
        FileDescriptor file = OrderResponse.getDescriptor().getFile();
        if (args.length > 0) {
            Map<String, FileDescriptorProto> files = new LinkedHashMap<>();
            collect(file, files);
            try (OutputStream out = new FileOutputStream(args[0])) {
                FileDescriptorSet.newBuilder()
                        .addAllFile(files.values())
                        .build()
                        .writeTo(out);
            }
            System.err.println("descriptor set written to " + args[0] + " (" + files.size() + " files)");
        }
        OrderResponse order = BenchOrders.order(1, 3);
        MethodDescriptor<GetOrderRequest, OrderResponse> get =
                MethodDescriptor.<GetOrderRequest, OrderResponse>newBuilder()
                        .setType(MethodType.UNARY)
                        .setFullMethodName("bench.Orders/Get")
                        .setRequestMarshaller(ProtoUtils.marshaller(GetOrderRequest.getDefaultInstance()))
                        .setResponseMarshaller(ProtoUtils.marshaller(OrderResponse.getDefaultInstance()))
                        .build();
        ServiceDescriptor descriptor = ServiceDescriptor.newBuilder("bench.Orders")
                .setSchemaDescriptor(new Schema(file, file.findServiceByName("Orders")))
                .addMethod(get)
                .build();
        ServerServiceDefinition service = ServerServiceDefinition.builder(descriptor)
                .addMethod(get, ServerCalls.asyncUnaryCall((req, obs) -> {
                    obs.onNext(order);
                    obs.onCompleted();
                }))
                .build();
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "9090"));
        Server server = ServerBuilder.forPort(port)
                .addService(service)
                .addService(ProtoReflectionServiceV1.newInstance())
                .build()
                .start();
        System.err.println("bench.Orders serving on " + port);
        server.awaitTermination();
    }

    /** Dependencies first, so the set loads in one pass. */
    private static void collect(FileDescriptor file, Map<String, FileDescriptorProto> into) {
        for (FileDescriptor dep : file.getDependencies()) collect(dep, into);
        into.putIfAbsent(file.getName(), file.toProto());
    }
}
