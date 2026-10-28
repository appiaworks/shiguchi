package dev.appiaworks.shiguchi.sample.bff.gateway;

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
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServiceDescriptor;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.ProtoFileDescriptorSupplier;
import io.grpc.protobuf.ProtoServiceDescriptorSupplier;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The sample's backend: one unary RPC, {@code sample.bff.WhoAmI/Whoami}, that answers with what the
 * gateway told it about the caller. The page calls it from a cookie session, so the answer shows
 * what a backend sees for a browser that holds no token: {@code x-principal} (the session user's
 * {@code sub}) and an {@code authorization} header carrying the session's access token, which the
 * gateway attached from the session store ({@code upstream-token-mode: RELAY}).
 *
 * <p>The schema is built in code and served by gRPC reflection, so the sample needs no protoc.
 */
final class WhoAmIBackend {

    private static final Logger log = LoggerFactory.getLogger(WhoAmIBackend.class);

    static final FileDescriptor FILE = buildFile();
    private static final Descriptor REQUEST = FILE.findMessageTypeByName("WhoamiRequest");
    private static final Descriptor REPLY = FILE.findMessageTypeByName("WhoamiReply");

    private static final Metadata.Key<String> X_PRINCIPAL =
            Metadata.Key.of("x-principal", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final Context.Key<String> PRINCIPAL = Context.key("principal");
    private static final Context.Key<String> TOKEN = Context.key("token");

    private final int port;
    private Server server;

    WhoAmIBackend(int port) {
        this.port = port;
    }

    void start() throws Exception {
        var whoami = MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName("sample.bff.WhoAmI/Whoami")
                .setRequestMarshaller(ProtoUtils.marshaller(
                        DynamicMessage.newBuilder(REQUEST).build()))
                .setResponseMarshaller(ProtoUtils.marshaller(
                        DynamicMessage.newBuilder(REPLY).build()))
                .build();

        var definition = ServerServiceDefinition.builder(ServiceDescriptor.newBuilder("sample.bff.WhoAmI")
                        .setSchemaDescriptor(new Schema())
                        .addMethod(whoami)
                        .build())
                .addMethod(whoami, ServerCalls.asyncUnaryCall(this::whoami))
                .build();

        server = NettyServerBuilder.forPort(port)
                .addService(definition)
                .addService(ProtoReflectionServiceV1.newInstance())
                .intercept(new CallerMetadata())
                .build()
                .start();
        log.info("whoami backend listening on :{}", port);
    }

    void stop() {
        if (server != null) server.shutdownNow();
    }

    private void whoami(DynamicMessage request, StreamObserver<DynamicMessage> out) {
        String principal = PRINCIPAL.get();
        String bearer = TOKEN.get();
        out.onNext(DynamicMessage.newBuilder(REPLY)
                .setField(REPLY.findFieldByName("principal"), principal == null ? "anonymous" : principal)
                .setField(REPLY.findFieldByName("claims_json"), bearer == null ? "" : claimsOf(bearer))
                .build());
        out.onCompleted();
    }

    /**
     * The token's payload, unverified: the gateway already validated it, and this is only to show
     * the page what arrived. A real backend would validate the token or trust {@code x-principal}.
     */
    private static String claimsOf(String bearer) {
        String token = bearer.regionMatches(true, 0, "Bearer ", 0, 7) ? bearer.substring(7) : bearer;
        String[] parts = token.split("\\.");
        if (parts.length < 2) return "";
        try {
            return new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /** Copies the two headers the gateway adds for the backend into the call's context. */
    private static final class CallerMetadata implements ServerInterceptor {
        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            Context context = Context.current()
                    .withValue(PRINCIPAL, headers.get(X_PRINCIPAL))
                    .withValue(TOKEN, headers.get(AUTHORIZATION));
            return Contexts.interceptCall(context, call, headers, next);
        }
    }

    private static FileDescriptor buildFile() {
        FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                .setName("sample/bff.proto")
                .setPackage("sample.bff")
                .setSyntax("proto3")
                .addDependency("google/api/annotations.proto")
                .addMessageType(DescriptorProto.newBuilder().setName("WhoamiRequest"))
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("WhoamiReply")
                        .addField(field("principal", 1))
                        .addField(field("claims_json", 2)))
                .addService(ServiceDescriptorProto.newBuilder()
                        .setName("WhoAmI")
                        .addMethod(MethodDescriptorProto.newBuilder()
                                .setName("Whoami")
                                .setInputType(".sample.bff.WhoamiRequest")
                                .setOutputType(".sample.bff.WhoamiReply")
                                .setOptions(MethodOptions.newBuilder()
                                        .setExtension(
                                                AnnotationsProto.http,
                                                HttpRule.newBuilder()
                                                        .setGet("/v1/whoami")
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
            return FILE.findServiceByName("WhoAmI");
        }
    }
}
