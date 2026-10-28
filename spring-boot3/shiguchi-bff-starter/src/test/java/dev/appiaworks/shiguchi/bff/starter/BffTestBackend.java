package dev.appiaworks.shiguchi.bff.starter;

import com.google.api.AnnotationsProto;
import com.google.api.HttpProto;
import com.google.api.HttpRule;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodOptions;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import dev.appiaworks.shiguchi.proto.api.GraphQlProto;
import dev.appiaworks.shiguchi.proto.api.GraphQlRule;
import dev.appiaworks.shiguchi.proto.api.MethodProto;
import dev.appiaworks.shiguchi.proto.api.MethodRule;
import dev.appiaworks.shiguchi.serde.DynamicMessageMarshaller;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.stub.ServerCalls;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * A gRPC backend that answers with what the gateway sent it: {@code lib.Echo/Get} ({@code GET
 * /v1/echo/{text}}, GraphQL field {@code echo}) and {@code lib.Echo/Secret} ({@code GET /v1/secret/{text}}, gated on scope
 * {@code secret.read}) both reply {@code text = "<authorization>|<x-principal>"}, each part empty
 * when the key was not sent. {@code lib.Echo/Keys} ({@code GET /v1/keys/{text}}, GraphQL field
 * {@code keys}) replies with the sorted names of every metadata key it received, so a test can
 * prove which request headers reach a backend. {@code lib.Echo/Watch} ({@code GET
 * /v1/watch/{text}}) is a server stream that sends the same reply every 250 ms until the call is
 * cancelled; {@link #WATCH_CANCELLED} counts the cancellations the backend saw. Also the callers the end-to-end tests use. Mirrors
 * {@code spring-boot4/shiguchi-bff-starter}.
 */
final class BffTestBackend {

    private BffTestBackend() {}

    static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    static final Metadata.Key<String> PRINCIPAL = Metadata.Key.of("x-principal", Metadata.ASCII_STRING_MARSHALLER);

    /** Watch calls started, and watch calls the backend saw cancelled (by the gateway or its client). */
    static final java.util.concurrent.atomic.AtomicInteger WATCH_OPENED =
            new java.util.concurrent.atomic.AtomicInteger();

    static final java.util.concurrent.atomic.AtomicInteger WATCH_CANCELLED =
            new java.util.concurrent.atomic.AtomicInteger();

    private static final java.util.concurrent.ScheduledExecutorService TICKER =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "bff-test-watch");
                t.setDaemon(true);
                return t;
            });

    private static final Context.Key<String> SEEN = Context.key("seen");
    private static final Context.Key<String> KEYS = Context.key("keys");

    /** Puts "authorization|x-principal" in the call's context for the handler to answer with. */
    private static final ServerInterceptor CAPTURE = new ServerInterceptor() {
        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            String seen = nullToEmpty(headers.get(AUTHORIZATION)) + "|" + nullToEmpty(headers.get(PRINCIPAL));
            String keys = String.join(",", new java.util.TreeSet<>(headers.keys()));
            return Contexts.interceptCall(
                    Context.current().withValue(SEEN, seen).withValue(KEYS, keys), call, headers, next);
        }
    };

    static final FileDescriptor FILE = buildFile();
    private static final Descriptor MSG = FILE.findMessageTypeByName("Msg");

    /** A session token the test authenticator hands out for cookie {@code test=<user>}. */
    static JwtAuthenticationToken sessionUser(String user, String... scopes) {
        Jwt jwt = Jwt.withTokenValue(user + "-session-token")
                .header("alg", "none")
                .subject(user)
                .claim("scope", String.join(" ", scopes))
                .build();
        List<SimpleGrantedAuthority> authorities = java.util.Arrays.stream(scopes)
                .map(s -> new SimpleGrantedAuthority("SCOPE_" + s))
                .toList();
        return new JwtAuthenticationToken(jwt, authorities, user);
    }

    /** What the test decoder returns for bearer {@code bob-token}. */
    static Jwt bobJwt() {
        return Jwt.withTokenValue("bob-token")
                .header("alg", "none")
                .subject("bob")
                .claim("scope", "secret.read")
                .build();
    }

    static Server start() {
        ServerServiceDefinition service = ServerServiceDefinition.builder("lib.Echo")
                .addMethod(descriptor("Get"), ServerCalls.asyncUnaryCall(BffTestBackend::answer))
                .addMethod(descriptor("Secret"), ServerCalls.asyncUnaryCall(BffTestBackend::answer))
                .addMethod(descriptor("Keys"), ServerCalls.asyncUnaryCall(BffTestBackend::answerKeys))
                .addMethod(
                        descriptor("Watch", MethodType.SERVER_STREAMING),
                        ServerCalls.asyncServerStreamingCall(BffTestBackend::watch))
                .build();
        try {
            return ServerBuilder.forPort(0)
                    .addService(ServerInterceptors.intercept(service, CAPTURE))
                    .build()
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static Path writeDescriptorSet() {
        try {
            Path path = Files.createTempFile("lib-bff-echo", ".pb");
            FileDescriptorSet set = FileDescriptorSet.newBuilder()
                    .addFile(DescriptorProtos.getDescriptor().toProto())
                    .addFile(HttpProto.getDescriptor().toProto())
                    .addFile(AnnotationsProto.getDescriptor().toProto())
                    .addFile(GraphQlProto.getDescriptor().toProto())
                    .addFile(MethodProto.getDescriptor().toProto())
                    .addFile(FILE.toProto())
                    .build();
            Files.write(path, set.toByteArray());
            return path;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void answer(DynamicMessage request, io.grpc.stub.StreamObserver<DynamicMessage> observer) {
        observer.onNext(DynamicMessage.newBuilder(MSG)
                .setField(MSG.findFieldByName("text"), SEEN.get())
                .build());
        observer.onCompleted();
    }

    private static void watch(DynamicMessage request, io.grpc.stub.StreamObserver<DynamicMessage> observer) {
        var call = (io.grpc.stub.ServerCallStreamObserver<DynamicMessage>) observer;
        DynamicMessage reply = DynamicMessage.newBuilder(MSG)
                .setField(MSG.findFieldByName("text"), SEEN.get())
                .build();
        WATCH_OPENED.incrementAndGet();
        var ticks = TICKER.scheduleAtFixedRate(
                () -> {
                    if (!call.isCancelled()) {
                        try {
                            call.onNext(reply);
                        } catch (RuntimeException ignored) {
                            // the call went away between the check and the send
                        }
                    }
                },
                0,
                250,
                java.util.concurrent.TimeUnit.MILLISECONDS);
        call.setOnCancelHandler(() -> {
            ticks.cancel(false);
            WATCH_CANCELLED.incrementAndGet();
        });
    }

    private static void answerKeys(DynamicMessage request, io.grpc.stub.StreamObserver<DynamicMessage> observer) {
        observer.onNext(DynamicMessage.newBuilder(MSG)
                .setField(MSG.findFieldByName("text"), KEYS.get())
                .build());
        observer.onCompleted();
    }

    private static FileDescriptor buildFile() {
        try {
            // The scope gate is read from the (shiguchi.api.method) annotation.
            GraphQlRule secret = GraphQlRule.newBuilder().setName("secret").build();
            MethodRule secretAccess =
                    MethodRule.newBuilder().addAuthScopes("secret.read").build();
            GraphQlRule keys = GraphQlRule.newBuilder().setName("keys").build();
            GraphQlRule echo = GraphQlRule.newBuilder().setName("echo").build();
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("lib/bff_echo.proto")
                    .setSyntax("proto3")
                    .setPackage("lib")
                    .addDependency("google/api/annotations.proto")
                    .addDependency(GraphQlProto.getDescriptor().getName())
                    .addDependency(MethodProto.getDescriptor().getName())
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Msg")
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("text")
                                    .setNumber(1)
                                    .setType(FieldDescriptorProto.Type.TYPE_STRING)
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)))
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Echo")
                            .addMethod(method(
                                    "Get",
                                    MethodOptions.newBuilder()
                                            .setExtension(
                                                    AnnotationsProto.http,
                                                    HttpRule.newBuilder()
                                                            .setGet("/v1/echo/{text}")
                                                            .build())
                                            .setExtension(GraphQlProto.graphql, echo)))
                            .addMethod(method(
                                    "Secret",
                                    MethodOptions.newBuilder()
                                            .setExtension(
                                                    AnnotationsProto.http,
                                                    HttpRule.newBuilder()
                                                            .setGet("/v1/secret/{text}")
                                                            .build())
                                            .setExtension(GraphQlProto.graphql, secret)
                                            .setExtension(MethodProto.method, secretAccess)))
                            .addMethod(method(
                                    "Keys",
                                    MethodOptions.newBuilder()
                                            .setExtension(
                                                    AnnotationsProto.http,
                                                    HttpRule.newBuilder()
                                                            .setGet("/v1/keys/{text}")
                                                            .build())
                                            .setExtension(GraphQlProto.graphql, keys)))
                            .addMethod(method(
                                            "Watch",
                                            MethodOptions.newBuilder()
                                                    .setExtension(
                                                            AnnotationsProto.http,
                                                            HttpRule.newBuilder()
                                                                    .setGet("/v1/watch/{text}")
                                                                    .build()))
                                    .setServerStreaming(true)))
                    .build();
            return FileDescriptor.buildFrom(proto, new FileDescriptor[] {
                AnnotationsProto.getDescriptor(), GraphQlProto.getDescriptor(), MethodProto.getDescriptor()
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static MethodDescriptorProto.Builder method(String name, MethodOptions.Builder options) {
        return MethodDescriptorProto.newBuilder()
                .setName(name)
                .setInputType(".lib.Msg")
                .setOutputType(".lib.Msg")
                .setOptions(options);
    }

    private static io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> descriptor(String name) {
        return descriptor(name, MethodType.UNARY);
    }

    private static io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> descriptor(String name, MethodType type) {
        return io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(type)
                .setFullMethodName("lib.Echo/" + name)
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(MSG))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(MSG))
                .build();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
