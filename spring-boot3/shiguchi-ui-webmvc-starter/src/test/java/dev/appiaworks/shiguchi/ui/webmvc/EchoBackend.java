package dev.appiaworks.shiguchi.ui.webmvc;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.util.JsonFormat;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.grpc.GrpcCallContext;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import dev.appiaworks.shiguchi.serde.DynamicSerializerFactory;
import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An in-process gRPC backend with {@code test.ui.EchoService}: {@code Echo} (unary, answers the
 * text it got), {@code EchoStream} (server streaming, three chunks) and {@code EchoCollect}
 * (client streaming). It remembers the metadata of the last call so a test can assert what the
 * UI's servlet adapter forwarded.
 */
final class EchoBackend implements AutoCloseable {

    static final String SERVICE = "test.ui.EchoService";

    private final Server server;
    private final ManagedChannel channel;
    private final AtomicReference<Metadata> lastMetadata;

    private EchoBackend(Server server, ManagedChannel channel, AtomicReference<Metadata> lastMetadata) {
        this.server = server;
        this.channel = channel;
        this.lastMetadata = lastMetadata;
    }

    static EchoBackend start() throws Exception {
        FileDescriptor file = echoServiceFile();
        Descriptor echoRequest = file.findMessageTypeByName("EchoRequest");
        Descriptor echoResponse = file.findMessageTypeByName("EchoResponse");

        var unary = method(MethodType.UNARY, "Echo", echoRequest, echoResponse);
        var stream = method(MethodType.SERVER_STREAMING, "EchoStream", echoRequest, echoResponse);
        var collect = method(MethodType.CLIENT_STREAMING, "EchoCollect", echoRequest, echoResponse);

        AtomicReference<Metadata> seen = new AtomicReference<>();
        ServerServiceDefinition service = ServerServiceDefinition.builder(SERVICE)
                .addMethod(
                        unary, ServerCalls.asyncUnaryCall((DynamicMessage req, StreamObserver<DynamicMessage> obs) -> {
                            obs.onNext(reply(echoResponse, text(echoRequest, req)));
                            obs.onCompleted();
                        }))
                .addMethod(
                        stream,
                        ServerCalls.asyncServerStreamingCall(
                                (DynamicMessage req, StreamObserver<DynamicMessage> obs) -> {
                                    String text = text(echoRequest, req);
                                    for (int i = 0; i < 3; i++) {
                                        obs.onNext(reply(echoResponse, text + "-" + i));
                                    }
                                    obs.onCompleted();
                                }))
                .addMethod(
                        collect,
                        ServerCalls.asyncClientStreamingCall((StreamObserver<DynamicMessage> obs) ->
                                new StreamObserver<DynamicMessage>() {
                                    private final StringBuilder joined = new StringBuilder();

                                    @Override
                                    public void onNext(DynamicMessage req) {
                                        if (joined.length() > 0) joined.append(',');
                                        joined.append(text(echoRequest, req));
                                    }

                                    @Override
                                    public void onError(Throwable t) {}

                                    @Override
                                    public void onCompleted() {
                                        obs.onNext(reply(echoResponse, joined.toString()));
                                        obs.onCompleted();
                                    }
                                }))
                .build();

        ServerInterceptor capture = new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                seen.set(headers);
                return new SimpleForwardingServerCallListener<>(next.startCall(call, headers)) {};
            }
        };

        String name = InProcessServerBuilder.generateName();
        Server server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(ServerInterceptors.intercept(service, capture))
                .build()
                .start();
        ManagedChannel channel = InProcessChannelBuilder.forName(name)
                .directExecutor()
                .intercept(GrpcCallContext.INTERCEPTOR)
                .build();
        return new EchoBackend(server, channel, seen);
    }

    /** The metadata of the most recent call the backend received, or null when it got none. */
    Metadata lastMetadata() {
        return lastMetadata.get();
    }

    void resetMetadata() {
        lastMetadata.set(null);
    }

    /** The routing context to register with the gateway's {@code GrpcRouteRegistry}. */
    ServiceEndpointContext context() throws Exception {
        var config = new ServiceDefinition();
        config.setName("echo-svc");
        var ctx = new ServiceEndpointContext(config, channel);
        FileDescriptor file = echoServiceFile();
        ctx.setFileDescriptors(Map.of(file.getName(), file));
        ServiceDescriptor service = file.findServiceByName("EchoService");
        ctx.setServiceByFqn(Map.of(service.getFullName(), service));
        ctx.setMethodByKey(Map.of(
                service.getFullName() + "/Echo", service.findMethodByName("Echo"),
                service.getFullName() + "/EchoStream", service.findMethodByName("EchoStream"),
                service.getFullName() + "/EchoCollect", service.findMethodByName("EchoCollect")));
        JsonFormat.TypeRegistry.Builder types = JsonFormat.TypeRegistry.newBuilder();
        var factory = new DynamicSerializerFactory();
        for (Descriptor d : file.getMessageTypes()) {
            types.add(d);
            ctx.putSerializer(d, factory.compileDescriptor(d));
        }
        ctx.setTypeRegistry(types.build());
        return ctx;
    }

    @Override
    public void close() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    // -------------------------------------------------------------------------

    private static io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> method(
            MethodType type, String name, Descriptor request, Descriptor response) {
        return io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(type)
                .setFullMethodName(SERVICE + "/" + name)
                .setRequestMarshaller(
                        ProtoUtils.marshaller(DynamicMessage.newBuilder(request).build()))
                .setResponseMarshaller(ProtoUtils.marshaller(
                        DynamicMessage.newBuilder(response).build()))
                .build();
    }

    private static String text(Descriptor requestType, DynamicMessage request) {
        return (String) request.getField(requestType.findFieldByName("text"));
    }

    private static DynamicMessage reply(Descriptor responseType, String text) {
        return DynamicMessage.newBuilder(responseType)
                .setField(responseType.findFieldByName("text"), text)
                .build();
    }

    private static FileDescriptor echoServiceFile() throws Exception {
        DescriptorProto echoRequest = DescriptorProto.newBuilder()
                .setName("EchoRequest")
                .addField(scalar("text", 1))
                .build();
        DescriptorProto echoResponse = DescriptorProto.newBuilder()
                .setName("EchoResponse")
                .addField(scalar("text", 1))
                .build();
        ServiceDescriptorProto service = ServiceDescriptorProto.newBuilder()
                .setName("EchoService")
                .addMethod(MethodDescriptorProto.newBuilder()
                        .setName("Echo")
                        .setInputType(".test.ui.EchoRequest")
                        .setOutputType(".test.ui.EchoResponse"))
                .addMethod(MethodDescriptorProto.newBuilder()
                        .setName("EchoStream")
                        .setInputType(".test.ui.EchoRequest")
                        .setOutputType(".test.ui.EchoResponse")
                        .setServerStreaming(true))
                .addMethod(MethodDescriptorProto.newBuilder()
                        .setName("EchoCollect")
                        .setInputType(".test.ui.EchoRequest")
                        .setOutputType(".test.ui.EchoResponse")
                        .setClientStreaming(true))
                .build();
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("test_ui_echo.proto")
                .setPackage("test.ui")
                .setSyntax("proto3")
                .addMessageType(echoRequest)
                .addMessageType(echoResponse)
                .addService(service)
                .build();
        return FileDescriptor.buildFrom(file, new FileDescriptor[0]);
    }

    private static FieldDescriptorProto scalar(String name, int number) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(FieldDescriptorProto.Type.TYPE_STRING)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .build();
    }
}
