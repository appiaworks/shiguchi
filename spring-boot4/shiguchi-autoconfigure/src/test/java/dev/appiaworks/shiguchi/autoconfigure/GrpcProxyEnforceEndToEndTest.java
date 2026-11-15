package dev.appiaworks.shiguchi.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.appiaworks.shiguchi.proto.AuthzRule;
import dev.appiaworks.shiguchi.proto.MethodProto;
import dev.appiaworks.shiguchi.proto.MethodRule;
import dev.appiaworks.shiguchi.serde.DynamicMessageMarshaller;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.ServerCalls;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The gRPC proxy in {@code ENFORCE} mode end to end with real signed JWTs: the proxy port
 * authenticates the bearer token against a decoder holding the test key, applies the method's
 * scopes and rejects authz-rule methods, forwards the verified principal to a real backend as
 * {@code x-principal}, and answers a method hidden by {@code shiguchi.exposure} like one that does
 * not exist, before any authentication.
 */
@SpringBootTest(
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.graphql.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.grpc.enabled=true",
            "shiguchi.grpc.security.mode=ENFORCE",
            "shiguchi.exposure.expose-tags[0]=external"
        })
@DisplayName("gRPC proxy ENFORCE mode with real JWTs end to end (Boot 4)")
class GrpcProxyEnforceEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {
        @Bean
        ReactiveJwtDecoder jwtDecoder() throws Exception {
            return NimbusReactiveJwtDecoder.withPublicKey((RSAPublicKey) KEY.toPublicKey())
                    .build();
        }
    }

    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> X_PRINCIPAL =
            Metadata.Key.of("x-principal", Metadata.ASCII_STRING_MARSHALLER);

    private static final RSAKey KEY = generateKey();
    private static final FileDescriptor FILE = buildFile();
    private static final Descriptor REQUEST = FILE.findMessageTypeByName("Req");
    private static final Descriptor REPLY = FILE.findMessageTypeByName("Res");
    private static final List<String> SEEN_PRINCIPALS = new CopyOnWriteArrayList<>();
    private static final Server BACKEND = startBackend();
    private static final Path PB_FILE = writeDescriptorSet();
    private static final int PROXY_PORT = freePort();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("shiguchi.grpc.port", () -> PROXY_PORT);
        registry.add("shiguchi.services[0].name", () -> "px");
        registry.add("shiguchi.services[0].mode", () -> "PB_FILE");
        registry.add("shiguchi.services[0].pb-descriptor-path", () -> PB_FILE.toString());
        registry.add("shiguchi.services[0].host", () -> "localhost");
        registry.add("shiguchi.services[0].port", () -> BACKEND.getPort());
    }

    @AfterAll
    static void stop() throws IOException {
        BACKEND.shutdownNow();
        Files.deleteIfExists(PB_FILE);
    }

    @BeforeEach
    void reset() {
        SEEN_PRINCIPALS.clear();
    }

    private static io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> method(String name) {
        return io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("px.Svc/" + name)
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(REQUEST))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(REPLY))
                .build();
    }

    private static DynamicMessage call(String name, String token) {
        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", PROXY_PORT)
                .usePlaintext()
                .build();
        try {
            Channel with = channel;
            if (token != null) {
                Metadata headers = new Metadata();
                headers.put(AUTHORIZATION, "Bearer " + token);
                with = ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(headers));
            }
            return ClientCalls.blockingUnaryCall(
                    with,
                    method(name),
                    CallOptions.DEFAULT,
                    DynamicMessage.newBuilder(REQUEST).build());
        } finally {
            channel.shutdownNow();
        }
    }

    private static Status.Code codeOf(String name, String token) {
        try {
            call(name, token);
            return Status.Code.OK;
        } catch (StatusRuntimeException e) {
            return e.getStatus().getCode();
        }
    }

    private static String token(String subject, String scope, Instant expires) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .claim("scope", scope)
                    .issueTime(Date.from(Instant.now().minusSeconds(5)))
                    .expirationTime(Date.from(expires))
                    .build();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .keyID(KEY.getKeyID())
                            .build(),
                    claims);
            jwt.sign(new RSASSASigner(KEY));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String valid(String scope) {
        return token("alice", scope, Instant.now().plusSeconds(300));
    }

    @Test
    @DisplayName(
            "GIVEN ENFORCE mode WHEN a call carries no token SHOULD answer UNAUTHENTICATED and not reach the backend")
    void given_enforce_when_noToken_should_unauthenticated() {
        assertThat(codeOf("Open", null)).isEqualTo(Status.Code.UNAUTHENTICATED);
        assertThat(SEEN_PRINCIPALS).isEmpty();
    }

    @Test
    @DisplayName("GIVEN ENFORCE mode WHEN the token is signed with another key SHOULD answer UNAUTHENTICATED")
    void given_enforce_when_forgedToken_should_unauthenticated() throws Exception {
        RSAKey other = new RSAKeyGenerator(2048).keyID("other").generate();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("other").build(),
                new JWTClaimsSet.Builder()
                        .subject("mallory")
                        .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                        .build());
        jwt.sign(new RSASSASigner(other));

        assertThat(codeOf("Open", jwt.serialize())).isEqualTo(Status.Code.UNAUTHENTICATED);
        assertThat(SEEN_PRINCIPALS).isEmpty();
    }

    @Test
    @DisplayName("GIVEN ENFORCE mode WHEN the token is expired SHOULD answer UNAUTHENTICATED")
    void given_enforce_when_expiredToken_should_unauthenticated() {
        String expired = token("alice", "read", Instant.now().minusSeconds(120));

        assertThat(codeOf("Open", expired)).isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    @Test
    @DisplayName(
            "GIVEN a valid signed token WHEN an exposed method is called SHOULD reach the backend, which sees the verified subject as x-principal")
    void given_validToken_when_called_should_reachBackendWithPrincipal() {
        DynamicMessage reply = call("Open", valid("read"));

        assertThat(reply.getField(REPLY.findFieldByName("id"))).isEqualTo("ok");
        assertThat(SEEN_PRINCIPALS).containsExactly("alice");
    }

    @Test
    @DisplayName(
            "GIVEN a method requiring scope admin WHEN the token only has read SHOULD answer PERMISSION_DENIED, and with admin SHOULD reach the backend")
    void given_scopedMethod_when_scopeMissing_should_permissionDenied() {
        assertThat(codeOf("Admin", valid("read"))).isEqualTo(Status.Code.PERMISSION_DENIED);
        assertThat(SEEN_PRINCIPALS).isEmpty();

        assertThat(codeOf("Admin", valid("read admin"))).isEqualTo(Status.Code.OK);
        assertThat(SEEN_PRINCIPALS).containsExactly("alice");
    }

    @Test
    @DisplayName(
            "GIVEN a method with authz rules WHEN called with a valid token SHOULD answer PERMISSION_DENIED, since the proxy cannot resolve the rule's object")
    void given_authzRuleMethod_when_called_should_permissionDenied() {
        assertThat(codeOf("Doc", valid("read admin"))).isEqualTo(Status.Code.PERMISSION_DENIED);
        assertThat(SEEN_PRINCIPALS).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN a method hidden by expose-tags WHEN called, with or without a token, SHOULD answer UNIMPLEMENTED and not reach the backend")
    void given_hiddenMethod_when_called_should_unimplemented() {
        assertThat(codeOf("Hidden", null)).isEqualTo(Status.Code.UNIMPLEMENTED);
        assertThatThrownBy(() -> call("Hidden", valid("read admin")))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNIMPLEMENTED));
        assertThat(SEEN_PRINCIPALS).isEmpty();
    }

    // ------------------------------------------------------------------ fixtures

    private static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("proxy-test").generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
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

    private static MethodDescriptorProto.Builder rpc(String name, MethodRule rule) {
        MethodDescriptorProto.Builder method = MethodDescriptorProto.newBuilder()
                .setName(name)
                .setInputType(".px.Req")
                .setOutputType(".px.Res");
        if (rule != null) {
            method.setOptions(MethodOptions.newBuilder().setExtension(MethodProto.method, rule));
        }
        return method;
    }

    /** Open and Admin (scope admin) and Doc (an authz rule) are tagged external; Hidden has no tag. */
    private static FileDescriptor buildFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("px/svc.proto")
                    .setSyntax("proto3")
                    .setPackage("px")
                    .addDependency(MethodProto.getDescriptor().getName())
                    .addMessageType(DescriptorProto.newBuilder().setName("Req").addField(field("id", 1)))
                    .addMessageType(DescriptorProto.newBuilder().setName("Res").addField(field("id", 1)))
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Svc")
                            .addMethod(rpc(
                                    "Open",
                                    MethodRule.newBuilder().addTags("external").build()))
                            .addMethod(rpc(
                                    "Admin",
                                    MethodRule.newBuilder()
                                            .addTags("external")
                                            .addAuthScopes("admin")
                                            .build()))
                            .addMethod(rpc(
                                    "Doc",
                                    MethodRule.newBuilder()
                                            .addTags("external")
                                            .addAuthz(AuthzRule.newBuilder()
                                                    .setObjectType("doc")
                                                    .setRelation("viewer")
                                                    .setIdField("id"))
                                            .build()))
                            .addMethod(rpc("Hidden", null)))
                    .build();
            return FileDescriptor.buildFrom(proto, new FileDescriptor[] {MethodProto.getDescriptor()});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path writeDescriptorSet() {
        try {
            Path path = Files.createTempFile("px-svc", ".pb");
            FileDescriptorSet set = FileDescriptorSet.newBuilder()
                    .addFile(DescriptorProtos.getDescriptor().toProto())
                    .addFile(MethodProto.getDescriptor().toProto())
                    .addFile(FILE.toProto())
                    .build();
            Files.write(path, set.toByteArray());
            return path;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Server startBackend() {
        ServerServiceDefinition.Builder service = ServerServiceDefinition.builder("px.Svc");
        for (String name : List.of("Open", "Admin", "Doc", "Hidden")) {
            service.addMethod(method(name), ServerCalls.asyncUnaryCall((request, observer) -> {
                observer.onNext(DynamicMessage.newBuilder(REPLY)
                        .setField(REPLY.findFieldByName("id"), "ok")
                        .build());
                observer.onCompleted();
            }));
        }
        ServerInterceptor recorder = new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                SEEN_PRINCIPALS.add(headers.get(X_PRINCIPAL));
                return next.startCall(call, headers);
            }
        };
        try {
            return ServerBuilder.forPort(0)
                    .addService(ServerInterceptors.intercept(service.build(), recorder))
                    .build()
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
