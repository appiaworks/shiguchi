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
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.protobuf.util.JsonFormat;
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
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Health and readiness on the Boot 3 gateway when a backend is not there at startup: the
 * application starts, reports {@code OUT_OF_SERVICE} on the readiness probe and {@code DOWN} on
 * the {@code shiguchi} indicator with the failure spelled out, keeps retrying, and once the
 * backend comes up flips to ready, serves the route, and reports {@code UP} — no restart, no
 * refresh schedule.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.graphql.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.startup.retry-interval=300ms",
            "management.endpoints.web.exposure.include=health",
            "management.endpoint.health.show-details=always",
            "management.endpoint.health.probes.enabled=true"
        })
@DisplayName("Backend health and readiness end to end (Boot 3)")
class GatewayHealthEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private static final FileDescriptor FILE = buildFile();
    private static final Descriptor ITEM = FILE.findMessageTypeByName("Item");
    private static final Descriptor GET_REQUEST = FILE.findMessageTypeByName("GetRequest");

    /** Reserved before the gateway starts; the backend binds it only later in the test. */
    private static final int BACKEND_PORT = freePort();

    private static volatile Server backend;

    @org.springframework.test.context.DynamicPropertySource
    static void backend(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("shiguchi.services[0].name", () -> "store");
        registry.add("shiguchi.services[0].mode", () -> "DYNAMIC");
        registry.add("shiguchi.services[0].host", () -> "localhost");
        registry.add("shiguchi.services[0].port", () -> BACKEND_PORT);
    }

    @AfterAll
    static void stop() {
        if (backend != null) backend.shutdownNow();
    }

    private WebClient client;

    @Autowired
    private Environment environment;

    @BeforeEach
    void bindClient() {
        client = WebClient.create("http://localhost:" + environment.getRequiredProperty("local.server.port"));
    }

    private record Reply(int status, String body) {}

    private Reply get(String uri) {
        return client.get()
                .uri(uri)
                .exchangeToMono(r -> r.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(b -> new Reply(r.statusCode().value(), b)))
                .block(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName(
            "GIVEN the backend is down at startup WHEN probed SHOULD be OUT_OF_SERVICE with the failure on the shiguchi indicator, and once the backend starts SHOULD become ready and serve the route without a restart")
    void given_backendDownAtStartup_when_backendComesUp_should_flipToReadyAndServe() throws Exception {
        // Phase 1: started degraded.
        Reply readiness = get("/actuator/health/readiness");
        assertThat(readiness.status()).isEqualTo(503);
        assertThat(str(parse(readiness.body()), "status")).isEqualTo("OUT_OF_SERVICE");

        Reply liveness = get("/actuator/health/liveness");
        assertThat(liveness.status())
                .as("liveness is not affected by a missing backend")
                .isEqualTo(200);

        Reply health = get("/actuator/health");
        assertThat(health.status()).isEqualTo(503);
        Struct h = parse(health.body());
        assertThat(str(h, "status")).isEqualTo("DOWN");
        assertThat(str(h, "components", "shiguchi", "status")).isEqualTo("DOWN");
        assertThat(num(h, "components", "shiguchi", "details", "configured")).isEqualTo(1);
        assertThat(num(h, "components", "shiguchi", "details", "loaded")).isEqualTo(0);
        assertThat(str(h, "components", "shiguchi", "details", "services", "store", "status"))
                .isEqualTo("DOWN");
        assertThat(str(h, "components", "shiguchi", "details", "services", "store", "target"))
                .isEqualTo("localhost:" + BACKEND_PORT);
        assertThat(str(h, "components", "shiguchi", "details", "services", "store", "lastError"))
                .isNotBlank();
        assertThat(num(h, "components", "shiguchi", "details", "services", "store", "attempts"))
                .isGreaterThanOrEqualTo(1);

        assertThat(get("/v1/items/1").status())
                .as("no route while the backend is missing")
                .isEqualTo(404);

        // Phase 2: the backend appears; the background retry picks it up.
        backend = startBackend(BACKEND_PORT);
        Instant deadline = Instant.now().plusSeconds(60);
        Reply ready = null;
        while (Instant.now().isBefore(deadline)) {
            ready = get("/actuator/health/readiness");
            if (ready.status() == 200) break;
            Thread.sleep(100);
        }
        assertThat(ready).isNotNull();
        assertThat(ready.status())
                .as("readiness within 60 s of the backend starting")
                .isEqualTo(200);
        assertThat(str(parse(ready.body()), "status")).isEqualTo("UP");

        Struct up = parse(get("/actuator/health").body());
        assertThat(str(up, "status")).isEqualTo("UP");
        assertThat(str(up, "components", "shiguchi", "details", "services", "store", "status"))
                .isEqualTo("UP");
        assertThat(num(up, "components", "shiguchi", "details", "services", "store", "routes"))
                .isEqualTo(1);
        assertThat(at(up, "components", "shiguchi", "details", "services", "store", "lastError"))
                .isNull();

        Reply item = get("/v1/items/42");
        assertThat(item.status()).isEqualTo(200);
        assertThat(str(parse(item.body()), "id")).isEqualTo("42");
    }

    // ------------------------------------------------------------------ JSON via Struct (both Boot lines)

    private static Struct parse(String json) throws Exception {
        Struct.Builder b = Struct.newBuilder();
        JsonFormat.parser().ignoringUnknownFields().merge(json, b);
        return b.build();
    }

    private static Value at(Struct root, Object... path) {
        Value v = Value.newBuilder().setStructValue(root).build();
        for (Object seg : path) {
            if (seg instanceof Integer i) {
                if (!v.hasListValue() || v.getListValue().getValuesCount() <= i) return null;
                v = v.getListValue().getValues(i);
            } else {
                if (!v.hasStructValue() || !v.getStructValue().containsFields((String) seg)) return null;
                v = v.getStructValue().getFieldsOrThrow((String) seg);
            }
        }
        return v;
    }

    private static String str(Struct root, Object... path) {
        Value v = at(root, path);
        return v == null ? null : v.getStringValue();
    }

    private static int num(Struct root, Object... path) {
        Value v = at(root, path);
        return v == null ? -1 : (int) v.getNumberValue();
    }

    // ------------------------------------------------------------------ fixtures

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static FileDescriptor buildFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("hs/store.proto")
                    .setPackage("hs")
                    .setSyntax("proto3")
                    .addDependency("google/api/annotations.proto")
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Item")
                            .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_STRING)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("GetRequest")
                            .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_STRING)))
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Store")
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("GetItem")
                                    .setInputType(".hs.GetRequest")
                                    .setOutputType(".hs.Item")
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    AnnotationsProto.http,
                                                    HttpRule.newBuilder()
                                                            .setGet("/v1/items/{id}")
                                                            .build()))))
                    .build();
            return FileDescriptor.buildFrom(proto, new FileDescriptor[] {AnnotationsProto.getDescriptor()});
        } catch (Exception e) {
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

    /** The proto schema gRPC server reflection serves for the dynamic service. */
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

    /** A real (socket) gRPC server with reflection, echoing the requested id. */
    private static Server startBackend(int port) {
        ServiceDescriptor service = FILE.findServiceByName("Store");
        var get = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("hs.Store/GetItem")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(GET_REQUEST))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(ITEM))
                .build();
        var descriptor = io.grpc.ServiceDescriptor.newBuilder("hs.Store")
                .setSchemaDescriptor(new Schema(FILE, service))
                .addMethod(get)
                .build();
        ServerServiceDefinition definition = ServerServiceDefinition.builder(descriptor)
                .addMethod(get, ServerCalls.asyncUnaryCall((request, observer) -> {
                    String id = (String) request.getField(GET_REQUEST.findFieldByName("id"));
                    observer.onNext(DynamicMessage.newBuilder(ITEM)
                            .setField(ITEM.findFieldByName("id"), id)
                            .build());
                    observer.onCompleted();
                }))
                .build();
        try {
            return ServerBuilder.forPort(port)
                    .addService(definition)
                    .addService(ProtoReflectionServiceV1.newInstance())
                    .build()
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
