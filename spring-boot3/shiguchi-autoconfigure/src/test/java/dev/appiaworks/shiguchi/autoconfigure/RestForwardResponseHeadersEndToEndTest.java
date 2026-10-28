package dev.appiaworks.shiguchi.autoconfigure;

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
import dev.appiaworks.shiguchi.serde.DynamicMessageMarshaller;
import io.grpc.ForwardingServerCall;
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
import io.grpc.stub.ServerCalls;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * {@code rest.forward-response-headers} through the whole Boot 3 gateway against a real gRPC
 * server that sends response headers ({@code x-backend-version}, {@code x-secret}) and a trailer
 * ({@code x-cost}): listed names reach the HTTP caller, unlisted ones do not, a streamed response
 * carries headers but not trailers, and a service can override the list.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.graphql.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.rest.forward-response-headers=x-backend-version,x-cost"
        })
@DisplayName("Forwarding backend response headers and trailers end to end (Boot 3)")
class RestForwardResponseHeadersEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private static final Metadata.Key<String> VERSION =
            Metadata.Key.of("x-backend-version", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> SECRET = Metadata.Key.of("x-secret", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> COST = Metadata.Key.of("x-cost", Metadata.ASCII_STRING_MARSHALLER);

    /** Adds the response headers and the trailer to every call. */
    private static final ServerInterceptor METADATA = new ServerInterceptor() {
        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            return next.startCall(
                    new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
                        @Override
                        public void sendHeaders(Metadata responseHeaders) {
                            responseHeaders.put(VERSION, "7");
                            responseHeaders.put(SECRET, "s");
                            super.sendHeaders(responseHeaders);
                        }

                        @Override
                        public void close(Status status, Metadata trailers) {
                            trailers.put(COST, "3");
                            super.close(status, trailers);
                        }
                    },
                    headers);
        }
    };

    private static final FileDescriptor FILE = buildFile();
    private static final Descriptor MSG = FILE.findMessageTypeByName("Msg");
    private static final Server BACKEND = startBackend();
    private static final Path PB_FILE = writeDescriptorSet();

    @DynamicPropertySource
    static void backend(DynamicPropertyRegistry registry) {
        registry.add("shiguchi.services[0].name", () -> "echo");
        registry.add("shiguchi.services[0].mode", () -> "PB_FILE");
        registry.add("shiguchi.services[0].pb-descriptor-path", () -> PB_FILE.toString());
        registry.add("shiguchi.services[0].host", () -> "localhost");
        registry.add("shiguchi.services[0].port", () -> BACKEND.getPort());
        // The same backend again under /own, with its own list.
        registry.add("shiguchi.services[1].name", () -> "echo-own");
        registry.add("shiguchi.services[1].mode", () -> "PB_FILE");
        registry.add("shiguchi.services[1].pb-descriptor-path", () -> PB_FILE.toString());
        registry.add("shiguchi.services[1].host", () -> "localhost");
        registry.add("shiguchi.services[1].port", () -> BACKEND.getPort());
        registry.add("shiguchi.services[1].path-prefix", () -> "/own");
        registry.add("shiguchi.services[1].forward-response-headers", () -> "x-secret");
    }

    @AfterAll
    static void stop() throws IOException {
        BACKEND.shutdownNow();
        Files.deleteIfExists(PB_FILE);
    }

    private WebTestClient client;

    @Autowired
    private Environment environment;

    @BeforeEach
    void bindClient() {
        client = WebTestClient.bindToServer()
                .responseTimeout(java.time.Duration.ofSeconds(30))
                .baseUrl("http://localhost:" + environment.getRequiredProperty("local.server.port"))
                .build();
    }

    @Test
    @DisplayName(
            "GIVEN forward-response-headers x-backend-version,x-cost WHEN a unary call answers SHOULD send the listed header and trailer only")
    void given_allowlist_when_unaryCall_should_forwardListedHeaderAndTrailer() {
        client.get()
                .uri("/v1/echo/hi")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("x-backend-version", "7")
                .expectHeader()
                .valueEquals("x-cost", "3")
                .expectHeader()
                .doesNotExist("x-secret");
    }

    @Test
    @DisplayName("GIVEN the allowlist WHEN the backend fails the call SHOULD still send its trailer with the error")
    void given_allowlist_when_callFails_should_forwardTrailerWithError() {
        client.get()
                .uri("/v1/fail")
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectHeader()
                .valueEquals("x-cost", "3");
    }

    @Test
    @DisplayName(
            "GIVEN the allowlist WHEN a server-streaming call answers SHOULD send the header but not the trailer, which comes after commit")
    void given_allowlist_when_streamingCall_should_forwardHeadersOnly() {
        client.get()
                .uri("/v1/watch/hi")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("x-backend-version", "7")
                .expectHeader()
                .doesNotExist("x-cost");
    }

    @Test
    @DisplayName("GIVEN a service overriding the list with x-secret WHEN called SHOULD forward only that name")
    void given_serviceOverride_when_called_should_useServiceList() {
        client.get()
                .uri("/own/v1/echo/hi")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("x-secret", "s")
                .expectHeader()
                .doesNotExist("x-backend-version")
                .expectHeader()
                .doesNotExist("x-cost");
    }

    // ---------------------------------------------------------------- the gRPC backend

    /**
     * lib.Echo: Get (GET /v1/echo/{text}), Watch (server streaming, GET /v1/watch/{text}) and
     * Fail (GET /v1/fail, answers NOT_FOUND). Every call gets x-backend-version and x-secret as
     * response headers and x-cost as a trailer.
     */
    private static FileDescriptor buildFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("lib/echo.proto")
                    .setSyntax("proto3")
                    .setPackage("lib")
                    .addDependency("google/api/annotations.proto")
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Msg")
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("text")
                                    .setNumber(1)
                                    .setType(FieldDescriptorProto.Type.TYPE_STRING)
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)))
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Echo")
                            .addMethod(
                                    method("Get", false, HttpRule.newBuilder().setGet("/v1/echo/{text}")))
                            .addMethod(
                                    method("Watch", true, HttpRule.newBuilder().setGet("/v1/watch/{text}")))
                            .addMethod(
                                    method("Fail", false, HttpRule.newBuilder().setGet("/v1/fail"))))
                    .build();
            return FileDescriptor.buildFrom(proto, new FileDescriptor[] {AnnotationsProto.getDescriptor()});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static MethodDescriptorProto.Builder method(String name, boolean serverStreaming, HttpRule.Builder rule) {
        return MethodDescriptorProto.newBuilder()
                .setName(name)
                .setInputType(".lib.Msg")
                .setOutputType(".lib.Msg")
                .setServerStreaming(serverStreaming)
                .setOptions(MethodOptions.newBuilder().setExtension(AnnotationsProto.http, rule.build()));
    }

    private static Path writeDescriptorSet() {
        try {
            Path path = Files.createTempFile("lib-echo", ".pb");
            FileDescriptorSet set = FileDescriptorSet.newBuilder()
                    .addFile(DescriptorProtos.getDescriptor().toProto())
                    .addFile(HttpProto.getDescriptor().toProto())
                    .addFile(AnnotationsProto.getDescriptor().toProto())
                    .addFile(FILE.toProto())
                    .build();
            Files.write(path, set.toByteArray());
            return path;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> descriptor(String name, MethodType type) {
        return io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(type)
                .setFullMethodName("lib.Echo/" + name)
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(MSG))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(MSG))
                .build();
    }

    private static Server startBackend() {
        ServerServiceDefinition service = ServerServiceDefinition.builder("lib.Echo")
                .addMethod(descriptor("Get", MethodType.UNARY), ServerCalls.asyncUnaryCall((request, observer) -> {
                    observer.onNext(request);
                    observer.onCompleted();
                }))
                .addMethod(
                        descriptor("Watch", MethodType.SERVER_STREAMING),
                        ServerCalls.asyncServerStreamingCall((request, observer) -> {
                            observer.onNext(request);
                            observer.onNext(request);
                            observer.onCompleted();
                        }))
                .addMethod(descriptor("Fail", MethodType.UNARY), ServerCalls.asyncUnaryCall((request, observer) -> {
                    // Headers first, as a backend that fails mid-call would have sent them.
                    observer.onError(
                            Status.NOT_FOUND.withDescription("nothing here").asRuntimeException());
                }))
                .build();
        try {
            return ServerBuilder.forPort(0)
                    .addService(ServerInterceptors.intercept(service, METADATA))
                    .build()
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
