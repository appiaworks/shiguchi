package dev.appiaworks.shiguchi.sample.ratelimit;

import com.google.api.AnnotationsProto;
import com.google.api.HttpRule;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodOptions;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.DescriptorValidationException;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServiceDescriptor;
import io.grpc.Status;
import io.grpc.protobuf.ProtoFileDescriptorSupplier;
import io.grpc.protobuf.ProtoServiceDescriptorSupplier;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An in-process catalog backend served over real gRPC with reflection enabled, so the gateway
 * discovers it in {@code DYNAMIC} mode exactly as it would a deployed service. Two unary RPCs
 * with {@code google.api.http} bindings:
 *
 * <pre>
 * service CatalogService {
 *   rpc GetProduct(GetProductRequest) returns (Product)        { get: "/v1/products/{sku}" }
 *   rpc ListProducts(ListProductsRequest) returns (ProductList) { get: "/v1/products" }
 * }
 * </pre>
 *
 * The descriptor is built here rather than by protoc so the sample stays one module with no
 * codegen step; the shape is what a {@code catalog.proto} would give you.
 */
public class CatalogServiceServer {

    private static final Logger log = LoggerFactory.getLogger(CatalogServiceServer.class);

    static final FileDescriptor FILE = buildFile();
    static final Descriptor PRODUCT = FILE.findMessageTypeByName("Product");
    static final Descriptor PRODUCT_LIST = FILE.findMessageTypeByName("ProductList");
    static final Descriptor GET_REQUEST = FILE.findMessageTypeByName("GetProductRequest");
    static final Descriptor LIST_REQUEST = FILE.findMessageTypeByName("ListProductsRequest");

    private static final Map<String, DynamicMessage> PRODUCTS = Map.of(
            "A100", product("A100", "Anvil, 50 kg", 129.0),
            "B200", product("B200", "Bird seed, 10 kg", 18.5),
            "C300", product("C300", "Catapult, portable", 349.99));

    private final int port;
    private Server server;

    public CatalogServiceServer(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        var get = unary("GetProduct", GET_REQUEST, PRODUCT);
        var list = unary("ListProducts", LIST_REQUEST, PRODUCT_LIST);

        // The schema descriptor is what ProtoReflectionService serves: without it the gateway sees
        // that the service exists but not its messages or http bindings, and registers zero routes.
        var service = ServerServiceDefinition.builder(ServiceDescriptor.newBuilder("sample.catalog.CatalogService")
                        .setSchemaDescriptor(new Schema())
                        .addMethod(get)
                        .addMethod(list)
                        .build())
                .addMethod(get, ServerCalls.asyncUnaryCall(this::getProduct))
                .addMethod(list, ServerCalls.asyncUnaryCall(this::listProducts))
                .build();

        server = ServerBuilder.forPort(port)
                .addService(service)
                .addService(ProtoReflectionServiceV1.newInstance())
                .build()
                .start();
        log.info("catalog backend listening on :{} (sample.catalog.CatalogService, reflection on)", port);
    }

    public void stop() {
        if (server != null) server.shutdownNow();
    }

    private void getProduct(DynamicMessage request, StreamObserver<DynamicMessage> out) {
        String sku = String.valueOf(request.getField(GET_REQUEST.findFieldByName("sku")));
        DynamicMessage product = PRODUCTS.get(sku);
        if (product == null) {
            out.onError(Status.NOT_FOUND.withDescription("no product " + sku).asRuntimeException());
            return;
        }
        out.onNext(product);
        out.onCompleted();
    }

    private void listProducts(DynamicMessage request, StreamObserver<DynamicMessage> out) {
        var builder = DynamicMessage.newBuilder(PRODUCT_LIST);
        var field = PRODUCT_LIST.findFieldByName("products");
        PRODUCTS.keySet().stream().sorted().forEach(sku -> builder.addRepeatedField(field, PRODUCTS.get(sku)));
        out.onNext(builder.build());
        out.onCompleted();
    }

    private static MethodDescriptor<DynamicMessage, DynamicMessage> unary(String name, Descriptor in, Descriptor out) {
        return MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName("sample.catalog.CatalogService/" + name)
                .setRequestMarshaller(ProtoUtils.marshaller(DynamicMessage.newBuilder(in).build()))
                .setResponseMarshaller(ProtoUtils.marshaller(DynamicMessage.newBuilder(out).build()))
                .build();
    }

    private static DynamicMessage product(String sku, String name, double price) {
        return DynamicMessage.newBuilder(PRODUCT)
                .setField(PRODUCT.findFieldByName("sku"), sku)
                .setField(PRODUCT.findFieldByName("name"), name)
                .setField(PRODUCT.findFieldByName("price"), price)
                .build();
    }

    private static FileDescriptor buildFile() {
        FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                .setName("sample/catalog.proto")
                .setPackage("sample.catalog")
                .setSyntax("proto3")
                .addDependency("google/api/annotations.proto")
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Product")
                        .addField(field("sku", 1, FieldDescriptorProto.Type.TYPE_STRING))
                        .addField(field("name", 2, FieldDescriptorProto.Type.TYPE_STRING))
                        .addField(field("price", 3, FieldDescriptorProto.Type.TYPE_DOUBLE)))
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("ProductList")
                        .addField(field("products", 1, FieldDescriptorProto.Type.TYPE_MESSAGE)
                                .setTypeName(".sample.catalog.Product")
                                .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)))
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("GetProductRequest")
                        .addField(field("sku", 1, FieldDescriptorProto.Type.TYPE_STRING)))
                .addMessageType(DescriptorProto.newBuilder().setName("ListProductsRequest"))
                .addService(ServiceDescriptorProto.newBuilder()
                        .setName("CatalogService")
                        .addMethod(method(
                                "GetProduct",
                                ".sample.catalog.GetProductRequest",
                                ".sample.catalog.Product",
                                HttpRule.newBuilder().setGet("/v1/products/{sku}").build()))
                        .addMethod(method(
                                "ListProducts",
                                ".sample.catalog.ListProductsRequest",
                                ".sample.catalog.ProductList",
                                HttpRule.newBuilder().setGet("/v1/products").build())))
                .build();
        try {
            return FileDescriptor.buildFrom(proto, new FileDescriptor[] {AnnotationsProto.getDescriptor()});
        } catch (DescriptorValidationException e) {
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

    private static MethodDescriptorProto method(String name, String in, String out, HttpRule rule) {
        return MethodDescriptorProto.newBuilder()
                .setName(name)
                .setInputType(in)
                .setOutputType(out)
                .setOptions(MethodOptions.newBuilder().setExtension(AnnotationsProto.http, rule))
                .build();
    }

    /** Exposes the file descriptor to gRPC reflection. */
    private static final class Schema implements ProtoFileDescriptorSupplier, ProtoServiceDescriptorSupplier {
        @Override
        public FileDescriptor getFileDescriptor() {
            return FILE;
        }

        @Override
        public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
            return FILE.findServiceByName("CatalogService");
        }
    }
}
