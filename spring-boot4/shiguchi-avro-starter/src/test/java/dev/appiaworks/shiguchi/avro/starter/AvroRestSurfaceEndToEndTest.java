package dev.appiaworks.shiguchi.avro.starter;

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
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.TimestampProto;
import dev.appiaworks.shiguchi.serde.DynamicMessageMarshaller;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerServiceDefinition;
import io.grpc.stub.ServerCalls;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The Boot 4 gateway's own REST surface speaking Avro to callers, in front of a real gRPC server
 * (PB_FILE discovery, one unary service with {@code google.api.http} bindings). The test side uses
 * plain Apache Avro with the schema fetched from the gateway's schema endpoint, so it proves what
 * a real caller would do: ask for the schema, encode a request, decode a response.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"shiguchi.ui.enabled=false", "shiguchi.graphql.enabled=false"})
@DisplayName("Avro on the REST surface end to end (Boot 4)")
class AvroRestSurfaceEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private static final FileDescriptor FILE = buildFile();
    private static final Descriptor ITEM = FILE.findMessageTypeByName("Item");
    private static final Descriptor GET_REQUEST = FILE.findMessageTypeByName("GetItemRequest");
    private static final Server BACKEND = startBackend();
    private static final Path PB_FILE = writeDescriptorSet();

    @DynamicPropertySource
    static void backend(DynamicPropertyRegistry registry) {
        registry.add("shiguchi.services[0].name", () -> "inventory");
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

    @Autowired
    private Environment environment;

    @BeforeEach
    void bindClient() {
        client = WebTestClient.bindToServer()
                .responseTimeout(java.time.Duration.ofSeconds(30))
                .baseUrl("http://localhost:" + environment.getRequiredProperty("local.server.port"))
                // The 5 s default is too tight when the build runs modules in parallel on a small
                // CI pod: the first /v3/api-docs render alone has timed out there.
                .responseTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Test
    @DisplayName("Given a JSON request asking for avro/binary, when the route is called, "
            + "should answer single-object Avro the caller can decode with the schema from the schema endpoint")
    void given_acceptAvro_when_called_should_answerAvroDecodableWithPublishedSchema() {
        EntityExchangeResult<byte[]> result = client.post()
                .uri("/v1/items")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.parseMediaType("avro/binary"))
                .bodyValue(
                        "{\"sku\":\"42\",\"name\":\"Widget\",\"qty\":\"7\",\"tags\":[\"a\",\"b\"],\"status\":\"ACTIVE\"}")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentTypeCompatibleWith("avro/binary")
                .expectHeader()
                .exists("X-Avro-Schema-Fingerprint")
                .expectBody(byte[].class)
                .returnResult();

        byte[] bytes = result.getResponseBody();
        assertThat(bytes[0]).isEqualTo((byte) 0xC3);
        assertThat(bytes[1]).isEqualTo((byte) 0x01);
        Schema schema = fetchSchema(result.getResponseHeaders().getFirst("X-Avro-Schema-Fingerprint"));
        assertThat(schema.getFullName()).isEqualTo("inv.Item");

        GenericRecord record = decode(bytes, schema);
        assertThat(record.get("sku").toString()).isEqualTo("42");
        assertThat(record.get("qty")).isEqualTo(7L);
        assertThat(record.get("status").toString()).isEqualTo("ACTIVE");
        assertThat(record.get("tags").toString()).isEqualTo("[a, b]");
        assertThat(record.get("createdAt")).isEqualTo(1704164645000000L); // 2024-01-02T03:04:05Z in micros
    }

    @Test
    @DisplayName("Given an avro/binary request body encoded with the published request schema, "
            + "when sent with Accept JSON, should be decoded, dispatched and answered as JSON")
    void given_avroBody_when_acceptJson_should_decodeAndAnswerJson() {
        Schema schema = fetchSchema("inv.Item");
        GenericRecord in = new GenericData.Record(schema);
        in.put("sku", "9");
        in.put("name", "Bolt");
        in.put("qty", 3L);
        in.put("tags", List.of("x"));
        in.put("status", new GenericData.EnumSymbol(schema.getField("status").schema(), "RETIRED"));
        in.put("createdAt", null);

        client.post()
                .uri("/v1/items")
                .contentType(MediaType.parseMediaType("avro/binary"))
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(encode(in, schema))
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.sku")
                .isEqualTo("9")
                .jsonPath("$.name")
                .isEqualTo("Bolt")
                .jsonPath("$.status")
                .isEqualTo("RETIRED")
                .jsonPath("$.tags[0]")
                .isEqualTo("x");
    }

    @Test
    @DisplayName("Given a GET route asked for avro/binary, when called, should encode the response too")
    void given_getRouteAcceptAvro_when_called_should_answerAvro() {
        byte[] bytes = client.get()
                .uri("/v1/items/77")
                .accept(MediaType.parseMediaType("avro/binary"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(byte[].class)
                .returnResult()
                .getResponseBody();

        GenericRecord record = decode(bytes, fetchSchema("inv.Item"));
        assertThat(record.get("sku").toString()).isEqualTo("77");
    }

    @Test
    @DisplayName("Given a JSON caller, when the route is called without Avro headers, should behave exactly as before")
    void given_jsonCaller_when_called_should_stayJson() {
        client.get()
                .uri("/v1/items/5")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectHeader()
                .doesNotExist("X-Avro-Schema-Fingerprint")
                .expectBody()
                .jsonPath("$.sku")
                .isEqualTo("5");
    }

    @Test
    @DisplayName("Given an Avro-family Content-Type no codec speaks, when sent, should answer 415")
    void given_unknownAvroContentType_when_sent_should_be415() {
        client.post()
                .uri("/v1/items")
                .contentType(MediaType.parseMediaType("avro/x-container"))
                .bodyValue(new byte[] {1, 2, 3})
                .exchange()
                .expectStatus()
                .isEqualTo(415);
    }

    @Test
    @DisplayName("Given an Accept header naming only types nobody speaks, when sent, should answer 406")
    void given_unsupportedAcceptOnly_when_sent_should_be406() {
        client.get()
                .uri("/v1/items/5")
                .accept(MediaType.parseMediaType("text/csv"))
                .exchange()
                .expectStatus()
                .isEqualTo(406);
    }

    @Test
    @DisplayName(
            "Given the OpenAPI document, when read, should list avro/binary next to JSON with the derived x-avro-schema")
    void given_openApi_when_read_should_advertiseAvro() {
        client.get()
                .uri("/v3/api-docs")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.paths['/v1/items'].post.requestBody.content['avro/binary']")
                .exists()
                .jsonPath("$.paths['/v1/items'].post.requestBody.content['avro/binary'].x-avro-schema.name")
                .isEqualTo("Item")
                .jsonPath("$.paths['/v1/items'].post.responses['200'].content['application/json']")
                .exists();
    }

    @Test
    @DisplayName("Given the schema endpoint, when asked for an unknown message, should answer 404")
    void given_schemaEndpoint_when_unknownName_should_be404() {
        client.get().uri("/avro/schemas/inv.Nope").exchange().expectStatus().isNotFound();
    }

    // ---------------------------------------------------------------- caller-side Avro

    private Schema fetchSchema(String nameOrFingerprint) {
        String json = client.get()
                .uri("/avro/schemas/" + nameOrFingerprint)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        return new Schema.Parser().parse(json);
    }

    private static byte[] encode(GenericRecord record, Schema schema) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            BinaryEncoder enc = EncoderFactory.get().binaryEncoder(out, null);
            new GenericDatumWriter<GenericRecord>(schema).write(record, enc);
            enc.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static GenericRecord decode(byte[] bytes, Schema schema) {
        try {
            int offset = bytes.length > 10 && bytes[0] == (byte) 0xC3 && bytes[1] == 0x01 ? 10 : 0;
            return new GenericDatumReader<GenericRecord>(schema)
                    .read(null, DecoderFactory.get().binaryDecoder(bytes, offset, bytes.length - offset, null));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- the gRPC backend

    /** inv.Items with CreateItem (POST /v1/items, body *) and GetItem (GET /v1/items/{sku}). */
    private static FileDescriptor buildFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("inv/items.proto")
                    .setSyntax("proto3")
                    .setPackage("inv")
                    .addDependency("google/api/annotations.proto")
                    .addDependency("google/protobuf/timestamp.proto")
                    .addEnumType(EnumDescriptorProto.newBuilder()
                            .setName("Status")
                            .addValue(EnumValueDescriptorProto.newBuilder()
                                    .setName("UNSPECIFIED")
                                    .setNumber(0))
                            .addValue(EnumValueDescriptorProto.newBuilder()
                                    .setName("ACTIVE")
                                    .setNumber(1))
                            .addValue(EnumValueDescriptorProto.newBuilder()
                                    .setName("RETIRED")
                                    .setNumber(2)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Item")
                            .addField(field("sku", 1, FieldDescriptorProto.Type.TYPE_STRING))
                            .addField(field("name", 2, FieldDescriptorProto.Type.TYPE_STRING))
                            .addField(field("qty", 3, FieldDescriptorProto.Type.TYPE_INT64))
                            .addField(field("tags", 4, FieldDescriptorProto.Type.TYPE_STRING)
                                    .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED))
                            .addField(field("status", 5, FieldDescriptorProto.Type.TYPE_ENUM)
                                    .setTypeName(".inv.Status"))
                            .addField(field("created_at", 6, FieldDescriptorProto.Type.TYPE_MESSAGE)
                                    .setTypeName(".google.protobuf.Timestamp")))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("GetItemRequest")
                            .addField(field("sku", 1, FieldDescriptorProto.Type.TYPE_STRING)))
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Items")
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("CreateItem")
                                    .setInputType(".inv.Item")
                                    .setOutputType(".inv.Item")
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    AnnotationsProto.http,
                                                    HttpRule.newBuilder()
                                                            .setPost("/v1/items")
                                                            .setBody("*")
                                                            .build())))
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("GetItem")
                                    .setInputType(".inv.GetItemRequest")
                                    .setOutputType(".inv.Item")
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    AnnotationsProto.http,
                                                    HttpRule.newBuilder()
                                                            .setGet("/v1/items/{sku}")
                                                            .build()))))
                    .build();
            return FileDescriptor.buildFrom(
                    proto, new FileDescriptor[] {AnnotationsProto.getDescriptor(), TimestampProto.getDescriptor()});
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

    /** The whole descriptor graph as a .pb file, the way protoc --descriptor_set_out --include_imports writes it. */
    private static Path writeDescriptorSet() {
        try {
            Path path = Files.createTempFile("inv-items", ".pb");
            FileDescriptorSet set = FileDescriptorSet.newBuilder()
                    .addFile(DescriptorProtos.getDescriptor().toProto())
                    .addFile(TimestampProto.getDescriptor().toProto())
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
        io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> create =
                io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                        .setType(MethodType.UNARY)
                        .setFullMethodName("inv.Items/CreateItem")
                        .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(ITEM))
                        .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(ITEM))
                        .build();
        io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> get =
                io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                        .setType(MethodType.UNARY)
                        .setFullMethodName("inv.Items/GetItem")
                        .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(GET_REQUEST))
                        .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(ITEM))
                        .build();
        ServerServiceDefinition service = ServerServiceDefinition.builder("inv.Items")
                .addMethod(create, ServerCalls.asyncUnaryCall((request, observer) -> {
                    // Echo the request back with created_at stamped: 2024-01-02T03:04:05Z.
                    observer.onNext(withCreatedAt(request));
                    observer.onCompleted();
                }))
                .addMethod(get, ServerCalls.asyncUnaryCall((request, observer) -> {
                    String sku = (String) request.getField(GET_REQUEST.findFieldByName("sku"));
                    DynamicMessage item = DynamicMessage.newBuilder(ITEM)
                            .setField(ITEM.findFieldByName("sku"), sku)
                            .setField(ITEM.findFieldByName("name"), "Item " + sku)
                            .setField(ITEM.findFieldByName("qty"), 1L)
                            .build();
                    observer.onNext(withCreatedAt(item));
                    observer.onCompleted();
                }))
                .build();
        try {
            return ServerBuilder.forPort(0).addService(service).build().start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static DynamicMessage withCreatedAt(DynamicMessage item) {
        FieldDescriptor createdAt = ITEM.findFieldByName("created_at");
        Descriptor ts = createdAt.getMessageType();
        DynamicMessage stamp = DynamicMessage.newBuilder(ts)
                .setField(ts.findFieldByName("seconds"), 1704164645L)
                .setField(ts.findFieldByName("nanos"), 0)
                .build();
        return item.toBuilder().setField(createdAt, stamp).build();
    }
}
