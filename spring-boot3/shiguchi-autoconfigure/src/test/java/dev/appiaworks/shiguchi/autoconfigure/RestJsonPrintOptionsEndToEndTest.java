package dev.appiaworks.shiguchi.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.api.AnnotationsProto;
import com.google.api.HttpProto;
import com.google.api.HttpRule;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
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
import java.nio.file.Files;
import java.nio.file.Path;
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
 * REST responses with different JSON print options on Boot 3: a Plain service uses default
 * options (camelCase, enum names, all fields), while a Custom service preserves field names,
 * prints enums as ints, and omits defaults. Both render google.type.Date as ISO strings
 * (ProtoJsonMapping applies equally to both).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"shiguchi.ui.enabled=false", "shiguchi.graphql.enabled=false", "shiguchi.openapi.enabled=false"})
@DisplayName("REST JSON print options end to end (Boot 3)")
class RestJsonPrintOptionsEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private static final FileDescriptor FILE = buildFile();
    private static final Descriptor ITEM = FILE.findMessageTypeByName("Item");
    private static final Server BACKEND = startBackend();
    private static final Path PB_FILE = writeDescriptorSet();

    @org.springframework.test.context.DynamicPropertySource
    static void backend(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("shiguchi.services[0].name", () -> "plain");
        registry.add("shiguchi.services[0].include-methods", () -> "po.Plain/*");
        registry.add("shiguchi.services[0].mode", () -> "PB_FILE");
        registry.add("shiguchi.services[0].pb-descriptor-path", () -> PB_FILE.toString());
        registry.add("shiguchi.services[0].host", () -> "localhost");
        registry.add("shiguchi.services[0].port", () -> BACKEND.getPort());

        registry.add("shiguchi.services[1].name", () -> "custom");
        registry.add("shiguchi.services[1].include-methods", () -> "po.Custom/*");
        registry.add("shiguchi.services[1].mode", () -> "PB_FILE");
        registry.add("shiguchi.services[1].pb-descriptor-path", () -> PB_FILE.toString());
        registry.add("shiguchi.services[1].host", () -> "localhost");
        registry.add("shiguchi.services[1].port", () -> BACKEND.getPort());
        registry.add("shiguchi.services[1].json-print.preserve-proto-field-names", () -> "true");
        registry.add("shiguchi.services[1].json-print.enums-as-ints", () -> "true");
        registry.add("shiguchi.services[1].json-print.omit-default-values", () -> "true");

        registry.add("shiguchi.services[2].name", () -> "numeric");
        registry.add("shiguchi.services[2].include-methods", () -> "po.Numeric/*");
        registry.add("shiguchi.services[2].mode", () -> "PB_FILE");
        registry.add("shiguchi.services[2].pb-descriptor-path", () -> PB_FILE.toString());
        registry.add("shiguchi.services[2].host", () -> "localhost");
        registry.add("shiguchi.services[2].port", () -> BACKEND.getPort());
        registry.add("shiguchi.services[2].json-print.number-format", () -> "ecmascript");
    }

    @AfterAll
    static void stop() throws IOException {
        BACKEND.shutdownNow();
        Files.deleteIfExists(PB_FILE);
    }

    private WebClient client;

    @Autowired
    private Environment environment;

    @BeforeEach
    void bindClient() {
        client = WebClient.create("http://localhost:" + environment.getRequiredProperty("local.server.port"));
    }

    // ── Scenarios ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName(
            "GIVEN a request to plain Get endpoint WHEN calling SHOULD return default JSON (camelCase, enum names, all fields)")
    void given_plainGetRequest_when_called_should_returnDefaultJsonFormat() {
        String body = client.get()
                .uri("/v1/plain/items/1")
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(10));

        assertThat(body)
                .contains("\"displayName\":\"Dune\"")
                .contains("\"kind\":\"BOOK\"")
                .contains("\"stock\":0")
                .contains("\"tags\":[]")
                .contains("\"released\":\"2026-09-22\"");
    }

    @Test
    @DisplayName(
            "GIVEN a request to custom Get endpoint WHEN called SHOULD return custom JSON (snake_case, enum ints, no defaults)")
    void given_customGetRequest_when_called_should_returnCustomJsonFormat() {
        String body = client.get()
                .uri("/v1/custom/items/1")
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(10));

        assertThat(body)
                .contains("\"display_name\":\"Dune\"")
                .contains("\"kind\":1")
                .contains("\"released\":\"2026-09-22\"")
                .doesNotContain("\"stock\"")
                .doesNotContain("\"tags\"")
                .doesNotContain("\"displayName\"");
    }

    @Test
    @DisplayName(
            "GIVEN a request to custom List endpoint with response_body WHEN called SHOULD return array with custom JSON format")
    void given_customListRequest_when_called_should_returnArrayWithCustomJson() {
        String body = client.get()
                .uri("/v1/custom/list/1")
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(10));

        assertThat(body)
                .startsWith("[")
                .endsWith("]")
                .contains("\"display_name\":\"Dune\"")
                .contains("\"kind\":1");
    }

    @Test
    @DisplayName(
            "GIVEN a request to plain List endpoint with response_body WHEN called SHOULD return array with default JSON format")
    void given_plainListRequest_when_called_should_returnArrayWithDefaultJson() {
        String body = client.get()
                .uri("/v1/plain/list/1")
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(10));

        assertThat(body).startsWith("[").endsWith("]").contains("\"displayName\":\"Dune\"");
    }

    @Test
    @DisplayName("GIVEN a service on number-format ecmascript WHEN a double 1.0 is returned SHOULD print 1")
    void given_ecmascriptService_when_doubleReturned_should_printWithoutPoint() {
        String body = client.get()
                .uri("/v1/numeric/items/1")
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(30));

        assertThat(body).contains("\"price\":1,").contains("\"displayName\":\"Dune\"");
    }

    @Test
    @DisplayName("GIVEN a service on the default number format WHEN a double 1.0 is returned SHOULD still print 1.0")
    void given_defaultService_when_doubleReturned_should_printJavaText() {
        String body = client.get()
                .uri("/v1/plain/items/1")
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(30));

        assertThat(body).contains("\"price\":1.0");
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────────────

    private static FileDescriptor buildFile() {
        try {
            EnumDescriptorProto kindEnum = EnumDescriptorProto.newBuilder()
                    .setName("Kind")
                    .addValue(EnumValueDescriptorProto.newBuilder()
                            .setName("KIND_UNSPECIFIED")
                            .setNumber(0))
                    .addValue(EnumValueDescriptorProto.newBuilder()
                            .setName("BOOK")
                            .setNumber(1))
                    .build();

            DescriptorProto itemMsg = DescriptorProto.newBuilder()
                    .setName("Item")
                    .addField(FieldDescriptorProto.newBuilder()
                            .setName("display_name")
                            .setNumber(1)
                            .setType(FieldDescriptorProto.Type.TYPE_STRING)
                            .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                    .addField(FieldDescriptorProto.newBuilder()
                            .setName("kind")
                            .setNumber(2)
                            .setType(FieldDescriptorProto.Type.TYPE_ENUM)
                            .setTypeName(".po.Kind")
                            .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                    .addField(FieldDescriptorProto.newBuilder()
                            .setName("stock")
                            .setNumber(3)
                            .setType(FieldDescriptorProto.Type.TYPE_INT32)
                            .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                    .addField(FieldDescriptorProto.newBuilder()
                            .setName("tags")
                            .setNumber(4)
                            .setType(FieldDescriptorProto.Type.TYPE_STRING)
                            .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED))
                    .addField(FieldDescriptorProto.newBuilder()
                            .setName("released")
                            .setNumber(5)
                            .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                            .setTypeName(".google.type.Date")
                            .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                    .addField(FieldDescriptorProto.newBuilder()
                            .setName("price")
                            .setNumber(6)
                            .setType(FieldDescriptorProto.Type.TYPE_DOUBLE)
                            .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                    .build();

            DescriptorProto getItemReqMsg = DescriptorProto.newBuilder()
                    .setName("GetItemRequest")
                    .addField(FieldDescriptorProto.newBuilder()
                            .setName("id")
                            .setNumber(1)
                            .setType(FieldDescriptorProto.Type.TYPE_STRING)
                            .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                    .build();

            DescriptorProto listItemsRespMsg = DescriptorProto.newBuilder()
                    .setName("ListItemsResponse")
                    .addField(FieldDescriptorProto.newBuilder()
                            .setName("items")
                            .setNumber(1)
                            .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                            .setTypeName(".po.Item")
                            .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED))
                    .build();

            ServiceDescriptorProto plainService = ServiceDescriptorProto.newBuilder()
                    .setName("Plain")
                    .addMethod(buildMethod("Get", ".po.GetItemRequest", ".po.Item", "get", "/v1/plain/items/{id}"))
                    .addMethod(buildMethod(
                            "List",
                            ".po.GetItemRequest",
                            ".po.ListItemsResponse",
                            "get",
                            "/v1/plain/list/{id}",
                            "items"))
                    .build();

            ServiceDescriptorProto customService = ServiceDescriptorProto.newBuilder()
                    .setName("Custom")
                    .addMethod(buildMethod("Get", ".po.GetItemRequest", ".po.Item", "get", "/v1/custom/items/{id}"))
                    .addMethod(buildMethod(
                            "List",
                            ".po.GetItemRequest",
                            ".po.ListItemsResponse",
                            "get",
                            "/v1/custom/list/{id}",
                            "items"))
                    .build();

            ServiceDescriptorProto numericService = ServiceDescriptorProto.newBuilder()
                    .setName("Numeric")
                    .addMethod(buildMethod("Get", ".po.GetItemRequest", ".po.Item", "get", "/v1/numeric/items/{id}"))
                    .build();

            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("po/items.proto")
                    .setPackage("po")
                    .setSyntax("proto3")
                    .addDependency("google/api/annotations.proto")
                    .addDependency("google/type/date.proto")
                    .addEnumType(kindEnum)
                    .addMessageType(itemMsg)
                    .addMessageType(getItemReqMsg)
                    .addMessageType(listItemsRespMsg)
                    .addService(plainService)
                    .addService(customService)
                    .addService(numericService)
                    .build();

            return FileDescriptor.buildFrom(
                    proto,
                    new FileDescriptor[] {AnnotationsProto.getDescriptor(), com.google.type.DateProto.getDescriptor()});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static MethodDescriptorProto buildMethod(
            String name, String inputType, String outputType, String httpMethod, String path) {
        return buildMethod(name, inputType, outputType, httpMethod, path, null);
    }

    private static MethodDescriptorProto buildMethod(
            String name, String inputType, String outputType, String httpMethod, String path, String responseBody) {
        HttpRule.Builder rule = HttpRule.newBuilder().setResponseBody(responseBody != null ? responseBody : "");
        if ("get".equals(httpMethod)) {
            rule.setGet(path);
        } else if ("post".equals(httpMethod)) {
            rule.setPost(path);
        }

        return MethodDescriptorProto.newBuilder()
                .setName(name)
                .setInputType(inputType)
                .setOutputType(outputType)
                .setOptions(MethodOptions.newBuilder().setExtension(AnnotationsProto.http, rule.build()))
                .build();
    }

    private static Path writeDescriptorSet() {
        try {
            Path path = Files.createTempFile("po-items", ".pb");
            FileDescriptorSet set = FileDescriptorSet.newBuilder()
                    .addFile(DescriptorProtos.getDescriptor().toProto())
                    .addFile(HttpProto.getDescriptor().toProto())
                    .addFile(AnnotationsProto.getDescriptor().toProto())
                    .addFile(com.google.type.DateProto.getDescriptor().toProto())
                    .addFile(FILE.toProto())
                    .build();
            Files.write(path, set.toByteArray());
            return path;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Server startBackend() {
        var plainGet = buildMethod("po.Plain", "Get", ITEM);
        var plainList = buildMethod("po.Plain", "List", FILE.findMessageTypeByName("ListItemsResponse"));
        var customGet = buildMethod("po.Custom", "Get", ITEM);
        var customList = buildMethod("po.Custom", "List", FILE.findMessageTypeByName("ListItemsResponse"));

        var numericGet = buildMethod("po.Numeric", "Get", ITEM);
        ServerServiceDefinition numericService = ServerServiceDefinition.builder("po.Numeric")
                .addMethod(numericGet, ServerCalls.asyncUnaryCall((request, observer) -> {
                    observer.onNext(buildItem());
                    observer.onCompleted();
                }))
                .build();

        ServerServiceDefinition plainService = ServerServiceDefinition.builder("po.Plain")
                .addMethod(plainGet, ServerCalls.asyncUnaryCall((request, observer) -> {
                    observer.onNext(buildItem());
                    observer.onCompleted();
                }))
                .addMethod(plainList, ServerCalls.asyncUnaryCall((request, observer) -> {
                    DynamicMessage resp = DynamicMessage.newBuilder(FILE.findMessageTypeByName("ListItemsResponse"))
                            .addRepeatedField(
                                    FILE.findMessageTypeByName("ListItemsResponse")
                                            .findFieldByName("items"),
                                    buildItem())
                            .build();
                    observer.onNext(resp);
                    observer.onCompleted();
                }))
                .build();

        ServerServiceDefinition customService = ServerServiceDefinition.builder("po.Custom")
                .addMethod(customGet, ServerCalls.asyncUnaryCall((request, observer) -> {
                    observer.onNext(buildItem());
                    observer.onCompleted();
                }))
                .addMethod(customList, ServerCalls.asyncUnaryCall((request, observer) -> {
                    DynamicMessage resp = DynamicMessage.newBuilder(FILE.findMessageTypeByName("ListItemsResponse"))
                            .addRepeatedField(
                                    FILE.findMessageTypeByName("ListItemsResponse")
                                            .findFieldByName("items"),
                                    buildItem())
                            .build();
                    observer.onNext(resp);
                    observer.onCompleted();
                }))
                .build();

        try {
            return ServerBuilder.forPort(0)
                    .addService(plainService)
                    .addService(customService)
                    .addService(numericService)
                    .build()
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> buildMethod(
            String fullName, String methodName, Descriptor responseDescriptor) {
        // Every method here takes a GetItemRequest.
        Descriptor requestDescriptor = FILE.findMessageTypeByName("GetItemRequest");
        return io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName(fullName + "/" + methodName)
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(requestDescriptor))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(responseDescriptor))
                .build();
    }

    private static DynamicMessage buildItem() {
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(ITEM);

        builder.setField(ITEM.findFieldByName("display_name"), "Dune");

        var kindEnum = ITEM.findFieldByName("kind").getEnumType();
        builder.setField(ITEM.findFieldByName("kind"), kindEnum.findValueByName("BOOK"));

        builder.setField(ITEM.findFieldByName("stock"), 0);
        builder.setField(ITEM.findFieldByName("price"), 1.0d);

        // tags is empty (repeated field, omitted when empty)

        // released is google.type.Date: 2026-09-22
        Descriptor dateDescriptor = FILE.findMessageTypeByName("Date");
        if (dateDescriptor == null) {
            dateDescriptor = com.google.type.DateProto.getDescriptor().findMessageTypeByName("Date");
        }
        DynamicMessage dateMsg = DynamicMessage.newBuilder(dateDescriptor)
                .setField(dateDescriptor.findFieldByName("year"), 2026)
                .setField(dateDescriptor.findFieldByName("month"), 9)
                .setField(dateDescriptor.findFieldByName("day"), 22)
                .build();
        builder.setField(ITEM.findFieldByName("released"), dateMsg);

        return builder.build();
    }
}
