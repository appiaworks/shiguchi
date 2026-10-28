package dev.appiaworks.shiguchi.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.google.protobuf.Descriptors.ServiceDescriptor;
import com.google.protobuf.DynamicMessage;
import dev.appiaworks.shiguchi.serde.DynamicMessageMarshaller;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerServiceDefinition;
import io.grpc.protobuf.ProtoFileDescriptorSupplier;
import io.grpc.protobuf.ProtoServiceDescriptorSupplier;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import io.grpc.stub.ServerCalls;
import java.io.IOException;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Splitting traffic between two running versions of a backend through the whole Boot 4 gateway:
 * two registered instances, {@code 1.4.0} and {@code 1.10.0}, each answering with its own version,
 * reached under four services configured with {@code services[].versions}: a canary at 0/100, a
 * sticky 50/50 split, a preview header, and a split naming a version that is not running.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.graphql.enabled=false",
            "shiguchi.openapi.enabled=false",
            "management.endpoints.web.exposure.include=gateway-routes"
        })
@DisplayName("Traffic split between versions end to end (Boot 4)")
class VersionedTrafficEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private static final FileDescriptor FILE = buildFile();
    private static final Server OLD = startBackend("1.4.0");
    private static final Server NEW = startBackend("1.10.0");

    @DynamicPropertySource
    static void backends(DynamicPropertyRegistry registry) {
        String simple = "spring.cloud.discovery.client.simple.instances.catalog";
        registry.add(simple + "[0].uri", () -> "http://localhost:" + OLD.getPort());
        registry.add(simple + "[0].metadata.version", () -> "1.4.0");
        registry.add(simple + "[1].uri", () -> "http://localhost:" + NEW.getPort());
        registry.add(simple + "[1].metadata.version", () -> "1.10.0");

        service(registry, 0, "canary");
        registry.add("shiguchi.services[0].versions.traffic[0].version", () -> "1.4.0");
        registry.add("shiguchi.services[0].versions.traffic[0].weight", () -> "0");
        registry.add("shiguchi.services[0].versions.traffic[1].version", () -> "1.10.0");
        registry.add("shiguchi.services[0].versions.traffic[1].weight", () -> "100");

        service(registry, 1, "split");
        registry.add("shiguchi.services[1].versions.traffic[0].version", () -> "1.4.0");
        registry.add("shiguchi.services[1].versions.traffic[0].weight", () -> "50");
        registry.add("shiguchi.services[1].versions.traffic[1].version", () -> "1.10.0");
        registry.add("shiguchi.services[1].versions.traffic[1].weight", () -> "50");
        registry.add("shiguchi.services[1].versions.sticky-header", () -> "x-user");

        service(registry, 2, "preview");
        registry.add("shiguchi.services[2].versions.traffic[0].version", () -> "1.4.0");
        registry.add("shiguchi.services[2].versions.traffic[0].weight", () -> "100");
        registry.add("shiguchi.services[2].versions.preview-header", () -> "x-backend-version");
        // Cached for everyone, so a preview answer must not be handed to callers without the header.
        registry.add("shiguchi.rest.response-cache.enabled", () -> "true");
        registry.add("shiguchi.services[2].cache[0].methods", () -> "Get");
        registry.add("shiguchi.services[2].cache[0].ttl", () -> "60s");
        registry.add("shiguchi.services[2].cache[0].visibility", () -> "PUBLIC");

        service(registry, 3, "fallback");
        registry.add("shiguchi.services[3].versions.traffic[0].version", () -> "9.9.9");
        registry.add("shiguchi.services[3].versions.traffic[0].weight", () -> "100");
    }

    private static void service(DynamicPropertyRegistry registry, int i, String name) {
        registry.add("shiguchi.services[" + i + "].name", () -> name);
        registry.add("shiguchi.services[" + i + "].discovery-name", () -> "catalog");
        registry.add("shiguchi.services[" + i + "].path-prefix", () -> "/" + name);
    }

    @AfterAll
    static void stop() {
        OLD.shutdownNow();
        NEW.shutdownNow();
    }

    private WebTestClient client;

    @Autowired
    private Environment environment;

    @BeforeEach
    void bindClient() {
        client = WebTestClient.bindToServer()
                .responseTimeout(Duration.ofSeconds(30))
                .baseUrl("http://localhost:" + environment.getRequiredProperty("local.server.port"))
                .build();
    }

    /** The version that answered: the backends echo their own version as the book id. */
    private String versionAnswering(String path, String header, String value) {
        var spec = client.get().uri(path);
        if (header != null) spec = spec.header(header, value);
        byte[] body = spec.exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .returnResult()
                .getResponseBody();
        String json = new String(body, java.nio.charset.StandardCharsets.UTF_8);
        return json.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1");
    }

    @Test
    @DisplayName("GIVEN a 0/100 split WHEN many calls are made SHOULD send every one to the new version")
    void given_canaryAtFull_when_called_should_reachNewVersionOnly() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 30; i++) seen.add(versionAnswering("/canary/v1/books/x", null, null));

        assertThat(seen).containsExactly("1.10.0");
    }

    @Test
    @DisplayName(
            "GIVEN a sticky 50/50 split WHEN each of many users calls repeatedly SHOULD keep every user on one version and spread users over both")
    void given_stickySplit_when_usersCallRepeatedly_should_keepEachUserOnOneVersion() {
        Set<String> versionsSeen = new HashSet<>();
        for (int user = 0; user < 40; user++) {
            String first = versionAnswering("/split/v1/books/x", "x-user", "user-" + user);
            for (int call = 0; call < 3; call++) {
                assertThat(versionAnswering("/split/v1/books/x", "x-user", "user-" + user))
                        .as("user-%d call %d", user, call)
                        .isEqualTo(first);
            }
            versionsSeen.add(first);
        }

        assertThat(versionsSeen).containsExactlyInAnyOrder("1.4.0", "1.10.0");
    }

    @Test
    @DisplayName(
            "GIVEN a split sending everything to the old version WHEN a call names the new one in the preview header SHOULD reach the new version")
    void given_previewHeader_when_namingNewVersion_should_reachIt() {
        assertThat(versionAnswering("/preview/v1/books/x", null, null)).isEqualTo("1.4.0");
        assertThat(versionAnswering("/preview/v1/books/x", "x-backend-version", "1.10.0"))
                .isEqualTo("1.10.0");
        // A version with no instance falls back to the split rather than failing.
        assertThat(versionAnswering("/preview/v1/books/x", "x-backend-version", "3.0.0"))
                .isEqualTo("1.4.0");
    }

    @Test
    @DisplayName(
            "GIVEN a publicly cached route WHEN callers with and without the preview header alternate SHOULD give each its own version's answer and vary on the header")
    void given_publicCache_when_previewAlternates_should_keepAnswersApart() {
        String path = "/preview/v1/books/cached";
        assertThat(versionAnswering(path, null, null)).isEqualTo("1.4.0");
        assertThat(versionAnswering(path, "x-backend-version", "1.10.0")).isEqualTo("1.10.0");
        assertThat(versionAnswering(path, null, null)).isEqualTo("1.4.0");
        assertThat(versionAnswering(path, "x-backend-version", "1.10.0")).isEqualTo("1.10.0");

        client.get()
                .uri(path)
                .exchange()
                .expectHeader()
                .value(HttpHeaders.VARY, vary -> assertThat(vary).containsIgnoringCase("x-backend-version"));
    }

    @Test
    @DisplayName(
            "GIVEN a split whose only weighted version is not running WHEN called SHOULD fall back to a running version rather than fail")
    void given_weightedVersionDown_when_called_should_fallBack() {
        assertThat(versionAnswering("/fallback/v1/books/x", null, null)).isIn("1.4.0", "1.10.0");
    }

    @Test
    @DisplayName("GIVEN a split WHEN the routes endpoint is read SHOULD show the weights and the sticky header")
    void given_split_when_routesEndpointRead_should_showTraffic() {
        client.get()
                .uri("/actuator/gateway-routes/split")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.traffic['1.4.0']")
                .isEqualTo(50)
                .jsonPath("$.traffic['1.10.0']")
                .isEqualTo(50)
                .jsonPath("$.stickyHeader")
                .isEqualTo("x-user");
    }

    // ---------------------------------------------------------------- the backend versions

    /** lib.Books.Get (GET /v1/books/{id}). */
    private static FileDescriptor buildFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("lib/traffic_books.proto")
                    .setSyntax("proto3")
                    .setPackage("lib")
                    .addDependency("google/api/annotations.proto")
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Book")
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("id")
                                    .setNumber(1)
                                    .setType(FieldDescriptorProto.Type.TYPE_STRING)
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)))
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Books")
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("Get")
                                    .setInputType(".lib.Book")
                                    .setOutputType(".lib.Book")
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    AnnotationsProto.http,
                                                    HttpRule.newBuilder()
                                                            .setGet("/v1/books/{id}")
                                                            .build()))))
                    .build();
            return FileDescriptor.buildFrom(proto, new FileDescriptor[] {AnnotationsProto.getDescriptor()});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record Schema(FileDescriptor file, ServiceDescriptor service)
            implements ProtoFileDescriptorSupplier, ProtoServiceDescriptorSupplier {
        @Override
        public FileDescriptor getFileDescriptor() {
            return file;
        }

        @Override
        public ServiceDescriptor getServiceDescriptor() {
            return service;
        }
    }

    /** A real gRPC server with reflection whose Get answers with {@code version} as the id. */
    private static Server startBackend(String version) {
        Descriptor book = FILE.findMessageTypeByName("Book");
        ServiceDescriptor service = FILE.findServiceByName("Books");
        var get = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("lib.Books/Get")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(book))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(book))
                .build();
        var descriptor = io.grpc.ServiceDescriptor.newBuilder("lib.Books")
                .setSchemaDescriptor(new Schema(FILE, service))
                .addMethod(get)
                .build();
        ServerServiceDefinition definition = ServerServiceDefinition.builder(descriptor)
                .addMethod(get, ServerCalls.asyncUnaryCall((request, observer) -> {
                    observer.onNext(DynamicMessage.newBuilder(book)
                            .setField(book.findFieldByName("id"), version)
                            .build());
                    observer.onCompleted();
                }))
                .build();
        try {
            return ServerBuilder.forPort(0)
                    .addService(definition)
                    .addService(ProtoReflectionServiceV1.newInstance())
                    .build()
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
