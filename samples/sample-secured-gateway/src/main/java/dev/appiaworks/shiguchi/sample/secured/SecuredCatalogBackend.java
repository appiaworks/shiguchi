package dev.appiaworks.shiguchi.sample.secured;

import com.google.api.AnnotationsProto;
import com.google.api.HttpRule;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodOptions;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServiceDescriptor;
import io.grpc.Status;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.ProtoFileDescriptorSupplier;
import io.grpc.protobuf.ProtoServiceDescriptorSupplier;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import io.netty.handler.ssl.ClientAuth;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The catalog backend, listening over TLS and <em>requiring</em> a client certificate.
 *
 * <p>{@link ClientAuth#REQUIRE} is what makes this mutual TLS rather than ordinary TLS: a caller
 * that does not present a certificate signed by the sample CA is rejected during the handshake,
 * before any RPC is attempted. That is what the gateway's {@code tls.cert-chain} /
 * {@code tls.private-key} configuration exists to satisfy.
 */
final class SecuredCatalogBackend {

    private static final Logger log = LoggerFactory.getLogger(SecuredCatalogBackend.class);

    static final FileDescriptor FILE = buildFile();
    private static final Descriptor PRODUCT = FILE.findMessageTypeByName("Product");
    private static final Descriptor GET_REQUEST = FILE.findMessageTypeByName("GetProductRequest");

    private static final Map<String, String> PRODUCTS =
            Map.of("A100", "Anvil, 50 kg", "B200", "Bird seed, 10 kg", "C300", "Catapult, portable");

    private final int port;
    private final DevCertificates certs;
    private Server server;

    SecuredCatalogBackend(int port, DevCertificates certs) {
        this.port = port;
        this.certs = certs;
    }

    void start() throws Exception {
        var get = MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName("sample.secured.CatalogService/GetProduct")
                .setRequestMarshaller(ProtoUtils.marshaller(
                        DynamicMessage.newBuilder(GET_REQUEST).build()))
                .setResponseMarshaller(ProtoUtils.marshaller(
                        DynamicMessage.newBuilder(PRODUCT).build()))
                .build();

        var definition = ServerServiceDefinition.builder(ServiceDescriptor.newBuilder("sample.secured.CatalogService")
                        .setSchemaDescriptor(new Schema())
                        .addMethod(get)
                        .build())
                .addMethod(get, ServerCalls.asyncUnaryCall(this::getProduct))
                .build();

        var ssl = GrpcSslContexts.forServer(
                        certs.serverCert().toFile(), certs.serverKey().toFile())
                // The mutual half: present a certificate or do not get a connection.
                .trustManager(certs.caCert().toFile())
                .clientAuth(ClientAuth.REQUIRE)
                .build();

        server = NettyServerBuilder.forPort(port)
                .sslContext(ssl)
                .addService(definition)
                .addService(ProtoReflectionServiceV1.newInstance())
                .build()
                .start();
        log.info("secured catalog backend listening on :{} (TLS, client certificate REQUIRED)", port);
    }

    void stop() {
        if (server != null) server.shutdownNow();
    }

    private void getProduct(DynamicMessage request, StreamObserver<DynamicMessage> out) {
        String sku = String.valueOf(request.getField(GET_REQUEST.findFieldByName("sku")));
        String name = PRODUCTS.get(sku);
        if (name == null) {
            out.onError(Status.NOT_FOUND.withDescription("no product " + sku).asRuntimeException());
            return;
        }
        out.onNext(DynamicMessage.newBuilder(PRODUCT)
                .setField(PRODUCT.findFieldByName("sku"), sku)
                .setField(PRODUCT.findFieldByName("name"), name)
                .build());
        out.onCompleted();
    }

    private static FileDescriptor buildFile() {
        FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                .setName("sample/secured.proto")
                .setPackage("sample.secured")
                .setSyntax("proto3")
                .addDependency("google/api/annotations.proto")
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Product")
                        .addField(field("sku", 1))
                        .addField(field("name", 2)))
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("GetProductRequest")
                        .addField(field("sku", 1)))
                .addService(ServiceDescriptorProto.newBuilder()
                        .setName("CatalogService")
                        .addMethod(MethodDescriptorProto.newBuilder()
                                .setName("GetProduct")
                                .setInputType(".sample.secured.GetProductRequest")
                                .setOutputType(".sample.secured.Product")
                                .setOptions(MethodOptions.newBuilder()
                                        .setExtension(
                                                AnnotationsProto.http,
                                                HttpRule.newBuilder()
                                                        .setGet("/v1/products/{sku}")
                                                        .build()))))
                .build();
        try {
            return FileDescriptor.buildFrom(proto, new FileDescriptor[] {AnnotationsProto.getDescriptor()});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static FieldDescriptorProto.Builder field(String name, int number) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(FieldDescriptorProto.Type.TYPE_STRING)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
    }

    /** Exposes the descriptor to gRPC reflection. */
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
