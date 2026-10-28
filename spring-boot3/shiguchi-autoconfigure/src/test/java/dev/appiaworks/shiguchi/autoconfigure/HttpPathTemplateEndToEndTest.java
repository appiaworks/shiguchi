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
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerServiceDefinition;
import io.grpc.stub.ServerCalls;
import java.io.IOException;
import java.net.URI;
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
 * google.api.http path templates beyond a bare {@code {var}}, through the whole Boot 3 gateway
 * against a real gRPC server: resource-name variables ({@code {name=shelves/*}/books/*}), a custom
 * verb, a {@code **} variable, and percent-decoding of the values the backend receives.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"shiguchi.ui.enabled=false", "shiguchi.graphql.enabled=false", "shiguchi.openapi.enabled=false"})
@DisplayName("google.api.http path templates and path-variable decoding end to end (Boot 3)")
class HttpPathTemplateEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {

        /**
         * Spring Security's default firewall answers 400 to an encoded slash before any route runs;
         * a deployment that wants {@code %2F} in path values lets it through like this.
         */
        @org.springframework.context.annotation.Bean
        org.springframework.security.web.server.firewall.ServerWebExchangeFirewall firewall() {
            var firewall = new org.springframework.security.web.server.firewall.StrictServerWebExchangeFirewall();
            firewall.setAllowUrlEncodedSlash(true);
            return firewall;
        }
    }

    private static final String[] METHODS = {"GetBook", "PublishBook", "ReadFile", "GetItem"};
    private static final FileDescriptor FILE = buildFile();
    private static final Descriptor REQUEST = FILE.findMessageTypeByName("Req");
    private static final Server BACKEND = startBackend();
    private static final Path PB_FILE = writeDescriptorSet();

    @DynamicPropertySource
    static void backend(DynamicPropertyRegistry registry) {
        registry.add("shiguchi.services[0].name", () -> "shelf");
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

    private WebTestClient client;
    private String base;

    @Autowired
    private Environment environment;

    @BeforeEach
    void bindClient() {
        base = "http://localhost:" + environment.getRequiredProperty("local.server.port");
        client = WebTestClient.bindToServer()
                .responseTimeout(java.time.Duration.ofSeconds(30))
                .baseUrl(base)
                .build();
    }

    /** The path exactly as written: a String URI would be encoded again, turning %20 into %2520. */
    private URI raw(String path) {
        return URI.create(base + path);
    }

    @Test
    @DisplayName(
            "GIVEN get: /v1/{name=shelves/*/books/*} WHEN a book path is called SHOULD bind the whole resource name")
    void given_resourceNameTemplate_when_called_should_bindWholeName() {
        client.get()
                .uri(raw("/v1/shelves/s1/books/b2"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.name")
                .isEqualTo("shelves/s1/books/b2")
                .jsonPath("$.id")
                .isEqualTo("GetBook");
    }

    @Test
    @DisplayName(
            "GIVEN post: /v1/{name=shelves/*/books/*}:publish WHEN the verb path is posted SHOULD reach the method bound to the verb")
    void given_customVerb_when_posted_should_reachVerbMethod() {
        client.post()
                .uri(raw("/v1/shelves/s1/books/b2:publish"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.name")
                .isEqualTo("shelves/s1/books/b2")
                .jsonPath("$.id")
                .isEqualTo("PublishBook");
    }

    @Test
    @DisplayName("GIVEN get: /v1/files/{name=**} WHEN a nested path is called SHOULD bind the rest of the path")
    void given_deepWildcard_when_called_should_bindRestOfPath() {
        client.get()
                .uri(raw("/v1/files/a/b/c.txt"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.name")
                .isEqualTo("a/b/c.txt");
    }

    @Test
    @DisplayName(
            "GIVEN get: /v1/items/{name} WHEN the value is percent-encoded SHOULD send the backend the decoded value, %2F included")
    void given_encodedSingleSegment_when_called_should_decodeFully() {
        client.get()
                .uri(raw("/v1/items/a%20b%2Fc"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.name")
                .isEqualTo("a b/c");
    }

    @Test
    @DisplayName(
            "GIVEN get: /v1/files/{name=**} WHEN a segment holds an encoded slash SHOULD keep %2F so it differs from a separator")
    void given_encodedSlashInMultiSegment_when_called_should_keepEscapedSlash() {
        client.get()
                .uri(raw("/v1/files/dir%2Fx/my%20file"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.name")
                .isEqualTo("dir%2Fx/my file");
    }

    @Test
    @DisplayName("GIVEN get: /v1/{name=shelves/*/books/*} WHEN a shelf path without a book is called SHOULD answer 404")
    void given_resourceNameTemplate_when_partialPathCalled_should_notMatch() {
        client.get().uri(raw("/v1/shelves/s1")).exchange().expectStatus().isNotFound();
    }

    // ---------------------------------------------------------------- the gRPC backend

    /**
     * lib.Shelf: every method takes and answers Req{name, id}; the backend echoes name and puts its
     * own method name in id, so a test can tell which route a path reached.
     */
    private static FileDescriptor buildFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("lib/shelf.proto")
                    .setSyntax("proto3")
                    .setPackage("lib")
                    .addDependency("google/api/annotations.proto")
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Req")
                            .addField(scalar("name", 1))
                            .addField(scalar("id", 2)))
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Shelf")
                            .addMethod(method("GetBook", HttpRule.newBuilder().setGet("/v1/{name=shelves/*/books/*}")))
                            .addMethod(method(
                                    "PublishBook",
                                    HttpRule.newBuilder()
                                            .setPost("/v1/{name=shelves/*/books/*}:publish")
                                            .setBody("*")))
                            .addMethod(method("ReadFile", HttpRule.newBuilder().setGet("/v1/files/{name=**}")))
                            .addMethod(method("GetItem", HttpRule.newBuilder().setGet("/v1/items/{name}"))))
                    .build();
            return FileDescriptor.buildFrom(proto, new FileDescriptor[] {AnnotationsProto.getDescriptor()});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static MethodDescriptorProto.Builder method(String name, HttpRule.Builder rule) {
        return MethodDescriptorProto.newBuilder()
                .setName(name)
                .setInputType(".lib.Req")
                .setOutputType(".lib.Req")
                .setOptions(MethodOptions.newBuilder().setExtension(AnnotationsProto.http, rule.build()));
    }

    private static FieldDescriptorProto.Builder scalar(String name, int number) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(FieldDescriptorProto.Type.TYPE_STRING)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
    }

    private static Path writeDescriptorSet() {
        try {
            Path path = Files.createTempFile("lib-shelf", ".pb");
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

    private static Server startBackend() {
        ServerServiceDefinition.Builder service = ServerServiceDefinition.builder("lib.Shelf");
        for (String name : METHODS) {
            io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> md =
                    io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                            .setType(MethodType.UNARY)
                            .setFullMethodName("lib.Shelf/" + name)
                            .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(REQUEST))
                            .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(REQUEST))
                            .build();
            service.addMethod(md, ServerCalls.asyncUnaryCall((request, observer) -> {
                observer.onNext(request.toBuilder()
                        .setField(REQUEST.findFieldByName("id"), name)
                        .build());
                observer.onCompleted();
            }));
        }
        try {
            return ServerBuilder.forPort(0).addService(service.build()).build().start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
