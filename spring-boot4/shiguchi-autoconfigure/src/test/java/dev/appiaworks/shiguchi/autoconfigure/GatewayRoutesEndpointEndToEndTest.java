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
 * The {@code gateway-routes} actuator endpoint on the Boot 4 gateway: it is actually served, it
 * lists what loaded and where each route goes, it answers per service, and it 404s a name that is
 * not configured. The gateway state metrics are registered on the same application, so this also
 * proves the meters exist on a running gateway rather than only in a unit test.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.graphql.enabled=false",
            "shiguchi.openapi.enabled=false",
            "management.endpoints.web.exposure.include=gateway-routes,metrics"
        })
@DisplayName("gateway-routes endpoint end to end (Boot 4)")
class GatewayRoutesEndpointEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private static final FileDescriptor FILE = buildFile();
    private static final Descriptor ITEM = FILE.findMessageTypeByName("Item");
    private static final Descriptor GET_REQUEST = FILE.findMessageTypeByName("GetRequest");

    private static final int BACKEND_PORT = freePort();
    private static final Server BACKEND = startBackend(BACKEND_PORT);

    @org.springframework.test.context.DynamicPropertySource
    static void backend(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("shiguchi.services[0].name", () -> "store");
        registry.add("shiguchi.services[0].mode", () -> "DYNAMIC");
        registry.add("shiguchi.services[0].host", () -> "localhost");
        registry.add("shiguchi.services[0].port", () -> BACKEND_PORT);
    }

    @AfterAll
    static void stop() {
        BACKEND.shutdownNow();
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
            "GIVEN a loaded backend WHEN GET /actuator/gateway-routes SHOULD serve the routes it is actually dispatching, with the HTTP binding a caller would have to match")
    void given_loadedBackend_when_readingTheEndpoint_should_listWhatIsServed() throws Exception {
        Reply reply = get("/actuator/gateway-routes");

        assertThat(reply.status())
                .as("the endpoint is registered and exposed, not just the bean")
                .isEqualTo(200);
        Struct body = parse(reply.body());
        assertThat(num(body, "configured")).isEqualTo(1);
        assertThat(num(body, "loaded")).isEqualTo(1);
        assertThat(num(body, "routeCount")).isEqualTo(1);
        assertThat(str(body, "services", 0, "name")).isEqualTo("store");
        assertThat(bool(body, "services", 0, "loaded")).isTrue();
        assertThat(str(body, "services", 0, "target")).isEqualTo("localhost:" + BACKEND_PORT);
        assertThat(str(body, "services", 0, "routes", 0, "method")).isEqualTo("hs.Store/GetItem");
        assertThat(str(body, "services", 0, "routes", 0, "http")).isEqualTo("GET /v1/items/{id}");

        // The route it reports is the route it serves; a report that drifted from the dispatcher
        // would be worse than no report at all.
        assertThat(get("/v1/items/7").status()).isEqualTo(200);
    }

    @Test
    @DisplayName(
            "GIVEN a configured service name WHEN asked for just that one SHOULD return it, and 404 a name nobody configured")
    void given_serviceName_when_asked_should_returnOneOr404() throws Exception {
        Reply one = get("/actuator/gateway-routes/store");

        assertThat(one.status()).isEqualTo(200);
        assertThat(str(parse(one.body()), "name")).isEqualTo("store");

        assertThat(get("/actuator/gateway-routes/nope").status())
                .as("a null read is a 404, not an empty 200")
                .isEqualTo(404);
    }

    @Test
    @DisplayName(
            "GIVEN the gateway is running WHEN the state metrics are scraped SHOULD report the live route and service counts")
    void given_runningGateway_when_scrapingMetrics_should_reportLiveCounts() throws Exception {
        assertThat(metric("gateway.routes")).isEqualTo(1);
        assertThat(metric("gateway.services.configured")).isEqualTo(1);
        assertThat(metric("gateway.services.loaded")).isEqualTo(1);
    }

    /** The VALUE statistic of a meter, as the metrics endpoint reports it. */
    private double metric(String name) throws Exception {
        Reply reply = get("/actuator/metrics/" + name);
        assertThat(reply.status()).as("meter '%s' is registered", name).isEqualTo(200);
        Value value = at(parse(reply.body()), "measurements", 0, "value");
        return value == null ? -1 : value.getNumberValue();
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

    private static boolean bool(Struct root, Object... path) {
        Value v = at(root, path);
        return v != null && v.getBoolValue();
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
