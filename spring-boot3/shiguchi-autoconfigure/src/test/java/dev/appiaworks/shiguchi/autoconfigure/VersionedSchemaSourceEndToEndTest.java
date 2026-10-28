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
import dev.appiaworks.shiguchi.refresh.GatewayRefreshScheduler;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
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
import java.util.List;
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
 * A rolling deploy in progress, through the whole Boot 3 gateway: one registered instance runs
 * version {@code 1.4.0} of a backend and another {@code 1.10.0}, whose schema adds a method and
 * a second service. {@code versions.schema-from} decides which schema is served — the oldest by
 * default, so nothing is routed that the old instance cannot answer — and it stays put across
 * refreshes instead of following whichever instance the load balancer picked.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.graphql.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.refresh.enabled=true",
            "shiguchi.refresh.interval-seconds=3600",
            "management.endpoints.web.exposure.include=gateway-routes"
        })
@DisplayName("Schema pinned to one version during a rolling deploy end to end (Boot 3)")
class VersionedSchemaSourceEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private static final FileDescriptor V1 = buildFile(false);
    private static final FileDescriptor V2 = buildFile(true);
    private static final Server OLD = startBackend(V1);
    private static final Server NEW = startBackend(V2);

    @DynamicPropertySource
    static void backends(DynamicPropertyRegistry registry) {
        String simple = "spring.cloud.discovery.client.simple.instances.catalog";
        registry.add(simple + "[0].uri", () -> "http://localhost:" + OLD.getPort());
        registry.add(simple + "[0].metadata.version", () -> "1.4.0");
        registry.add(simple + "[1].uri", () -> "http://localhost:" + NEW.getPort());
        // 1.10.0 is newer than 1.4.0: versions compare numerically, not as text.
        registry.add(simple + "[1].metadata.version", () -> "1.10.0");

        registry.add("shiguchi.services[0].name", () -> "catalog");
        registry.add("shiguchi.services[0].discovery-name", () -> "catalog");
        registry.add("shiguchi.services[1].name", () -> "catalog-newest");
        registry.add("shiguchi.services[1].discovery-name", () -> "catalog");
        registry.add("shiguchi.services[1].path-prefix", () -> "/newest");
        registry.add("shiguchi.services[1].versions.schema-from", () -> "NEWEST");
    }

    @AfterAll
    static void stop() {
        OLD.shutdownNow();
        NEW.shutdownNow();
    }

    private WebTestClient client;

    @Autowired
    private Environment environment;

    @Autowired
    private GrpcRouteRegistry registry;

    @Autowired
    private GatewayRefreshScheduler scheduler;

    @BeforeEach
    void bindClient() {
        client = WebTestClient.bindToServer()
                .responseTimeout(Duration.ofSeconds(30))
                .baseUrl("http://localhost:" + environment.getRequiredProperty("local.server.port"))
                .build();
    }

    @Test
    @DisplayName(
            "GIVEN two versions running and the default schema-from WHEN routes load SHOULD serve the oldest version's schema only")
    void given_twoVersions_when_defaultSchemaFrom_should_serveOldestSchema() {
        ServiceEndpointContext ctx = registry.contextByServiceName("catalog").orElseThrow();

        assertThat(ctx.getSchemaVersion()).isEqualTo("1.4.0");
        assertThat(ctx.getSchemaInstance()).isEqualTo("localhost:" + OLD.getPort());
        // Get exists in both versions; Search and the Extra service only in 1.10.0.
        client.get().uri("/v1/books/1").exchange().expectStatus().isOk();
        client.get().uri("/v1/books:search").exchange().expectStatus().isNotFound();
        assertThat(ctx.getServiceByFqn()).containsOnlyKeys("lib.Books");
    }

    @Test
    @DisplayName("GIVEN schema-from NEWEST WHEN routes load SHOULD serve the newest version's additions")
    void given_schemaFromNewest_when_routesLoad_should_serveNewestSchema() {
        ServiceEndpointContext ctx =
                registry.contextByServiceName("catalog-newest").orElseThrow();

        assertThat(ctx.getSchemaVersion()).isEqualTo("1.10.0");
        assertThat(ctx.getServiceByFqn()).containsOnlyKeys("lib.Books", "lib.Extra");
        assertThat(registry.findByHttpKey("GET", "/newest/v1/books:search")).isPresent();
    }

    @Test
    @DisplayName(
            "GIVEN a pinned schema and a backend with two services WHEN the scheduled check and refresh-all run repeatedly SHOULD never reload")
    void given_pinnedSchema_when_refreshedRepeatedly_should_neverReload() {
        ServiceEndpointContext before = registry.contextByServiceName("catalog").orElseThrow();
        ServiceEndpointContext beforeNewest =
                registry.contextByServiceName("catalog-newest").orElseThrow();

        for (int i = 0; i < 6; i++) {
            scheduler.checkAndRefresh();
            assertThat(scheduler.refreshAll())
                    .as("refresh %d found a change", i)
                    .isEmpty();
        }

        assertThat(registry.contextByServiceName("catalog")).containsSame(before);
        // The newest schema has two services: the probe must hash both, as a load does.
        assertThat(registry.contextByServiceName("catalog-newest")).containsSame(beforeNewest);
    }

    @Test
    @DisplayName(
            "GIVEN two versions running WHEN the routes endpoint is read SHOULD show the schema source and instances per version")
    void given_twoVersions_when_routesEndpointRead_should_showVersions() {
        client.get()
                .uri("/actuator/gateway-routes/catalog")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.schemaVersion")
                .isEqualTo("1.4.0")
                .jsonPath("$.schemaInstance")
                .isEqualTo("localhost:" + OLD.getPort())
                .jsonPath("$.instanceVersions['1.4.0']")
                .isEqualTo(1)
                .jsonPath("$.instanceVersions['1.10.0']")
                .isEqualTo(1);
    }

    // ---------------------------------------------------------------- the two versions

    /** lib.Books.Get (GET /v1/books/{id}); version 2 adds Search (GET /v1/books:search) and lib.Extra. */
    private static FileDescriptor buildFile(boolean v2) {
        try {
            ServiceDescriptorProto.Builder books =
                    ServiceDescriptorProto.newBuilder().setName("Books").addMethod(method("Get", "/v1/books/{id}"));
            if (v2) books.addMethod(method("Search", "/v1/books:search"));
            FileDescriptorProto.Builder proto = FileDescriptorProto.newBuilder()
                    .setName(v2 ? "lib/books_v2.proto" : "lib/books_v1.proto")
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
                    .addService(books);
            if (v2) {
                proto.addService(ServiceDescriptorProto.newBuilder()
                        .setName("Extra")
                        .addMethod(method("Ping", "/v1/extra/{id}")));
            }
            return FileDescriptor.buildFrom(proto.build(), new FileDescriptor[] {AnnotationsProto.getDescriptor()});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static MethodDescriptorProto.Builder method(String name, String path) {
        return MethodDescriptorProto.newBuilder()
                .setName(name)
                .setInputType(".lib.Book")
                .setOutputType(".lib.Book")
                .setOptions(MethodOptions.newBuilder()
                        .setExtension(
                                AnnotationsProto.http,
                                HttpRule.newBuilder().setGet(path).build()));
    }

    /** The proto schema gRPC server reflection serves for a dynamic service. */
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

    /** A real gRPC server with reflection serving every service of {@code file}, each method echoing. */
    private static Server startBackend(FileDescriptor file) {
        Descriptor book = file.findMessageTypeByName("Book");
        ServerBuilder<?> builder = ServerBuilder.forPort(0).addService(ProtoReflectionServiceV1.newInstance());
        for (ServiceDescriptor service : file.getServices()) {
            var descriptor = io.grpc.ServiceDescriptor.newBuilder(service.getFullName())
                    .setSchemaDescriptor(new Schema(file, service));
            List<io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage>> methods = service.getMethods().stream()
                    .map(m -> io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                            .setType(MethodType.UNARY)
                            .setFullMethodName(service.getFullName() + "/" + m.getName())
                            .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(book))
                            .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(book))
                            .build())
                    .toList();
            methods.forEach(descriptor::addMethod);
            ServerServiceDefinition.Builder definition = ServerServiceDefinition.builder(descriptor.build());
            for (var m : methods) {
                definition.addMethod(m, ServerCalls.asyncUnaryCall((request, observer) -> {
                    observer.onNext(request);
                    observer.onCompleted();
                }));
            }
            builder.addService(definition.build());
        }
        try {
            return builder.build().start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
