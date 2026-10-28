package dev.appiaworks.shiguchi.sample.discovery;

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
 * One instance of a catalog backend, served over real gRPC with reflection on, so the gateway
 * discovers it in {@code DYNAMIC} mode exactly as it would a deployed service:
 *
 * <pre>
 * service CatalogService {
 *   rpc GetProduct(GetProductRequest) returns (Product)         { get: "/v1/products/{sku}" }
 *   rpc ListProducts(ListProductsRequest) returns (ProductList) { get: "/v1/products" }
 * }
 * </pre>
 *
 * <p>Every response carries {@code served_by} — the id of the instance that answered. That is the
 * whole point of this sample: with three instances registered and the channel on
 * {@code round_robin}, repeating one request returns a different {@code servedBy} each time, so
 * the balancing is something you can see rather than something the README claims.
 *
 * <p>The descriptor is built here rather than by protoc so the sample stays one module with no
 * codegen step; the shape is what a {@code catalog.proto} would give you.
 */
public class CatalogServiceServer {

    private static final Logger log = LoggerFactory.getLogger(CatalogServiceServer.class);

    static final FileDescriptor FILE = buildFile();
    static final Descriptor PRODUCT = FILE.findMessageTypeByName("Product");
    static final Descriptor PRODUCT_LIST = FILE.findMessageTypeByName("ProductList");
    static final Descriptor GET_REQUEST = FILE.findMessageTypeByName("GetProductRequest");
    static final Descriptor LIST_REQUEST = FILE.findMessageTypeByName("ListProductsRequest");

    private static final Map<String, String> PRODUCTS = Map.of(
            "A100", "Anvil, 50 kg",
            "B200", "Bird seed, 10 kg",
            "C300", "Catapult, portable");

    private static final Map<String, Double> PRICES = Map.of("A100", 129.0, "B200", 18.5, "C300", 349.99);

    private final String instanceId;
    private final int port;
    private Server server;

    public CatalogServiceServer(String instanceId, int port) {
        this.instanceId = instanceId;
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
        log.info("catalog instance '{}' listening on :{} (reflection on)", instanceId, port);
    }

    public void stop() {
        if (server != null) server.shutdownNow();
    }

    /** The port this instance actually bound, which is what gets registered with discovery. */
    public int port() {
        return server == null ? port : server.getPort();
    }

    public String instanceId() {
        return instanceId;
    }

    private void getProduct(DynamicMessage request, StreamObserver<DynamicMessage> out) {
        String sku = String.valueOf(request.getField(GET_REQUEST.findFieldByName("sku")));
        String name = PRODUCTS.get(sku);
        if (name == null) {
            out.onError(Status.NOT_FOUND.withDescription("no product " + sku).asRuntimeException());
            return;
        }
        out.onNext(product(sku, name, PRICES.get(sku)));
        out.onCompleted();
    }

    private void listProducts(DynamicMessage request, StreamObserver<DynamicMessage> out) {
        var builder = DynamicMessage.newBuilder(PRODUCT_LIST);
        var products = PRODUCT_LIST.findFieldByName("products");
        PRODUCTS.keySet().stream()
                .sorted()
                .forEach(sku -> builder.addRepeatedField(products, product(sku, PRODUCTS.get(sku), PRICES.get(sku))));
        builder.setField(PRODUCT_LIST.findFieldByName("served_by"), instanceId);
        out.onNext(builder.build());
        out.onCompleted();
    }

    private DynamicMessage product(String sku, String name, double price) {
        return DynamicMessage.newBuilder(PRODUCT)
                .setField(PRODUCT.findFieldByName("sku"), sku)
                .setField(PRODUCT.findFieldByName("name"), name)
                .setField(PRODUCT.findFieldByName("price"), price)
                .setField(PRODUCT.findFieldByName("served_by"), instanceId)
                .build();
    }

    private static MethodDescriptor<DynamicMessage, DynamicMessage> unary(String name, Descriptor in, Descriptor out) {
        return MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName("sample.catalog.CatalogService/" + name)
                .setRequestMarshaller(ProtoUtils.marshaller(
                        DynamicMessage.newBuilder(in).build()))
                .setResponseMarshaller(ProtoUtils.marshaller(
                        DynamicMessage.newBuilder(out).build()))
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
                        .addField(field("price", 3, FieldDescriptorProto.Type.TYPE_DOUBLE))
                        .addField(field("served_by", 4, FieldDescriptorProto.Type.TYPE_STRING)))
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("ProductList")
                        .addField(field("products", 1, FieldDescriptorProto.Type.TYPE_MESSAGE)
                                .setTypeName(".sample.catalog.Product")
                                .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED))
                        .addField(field("served_by", 2, FieldDescriptorProto.Type.TYPE_STRING)))
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
