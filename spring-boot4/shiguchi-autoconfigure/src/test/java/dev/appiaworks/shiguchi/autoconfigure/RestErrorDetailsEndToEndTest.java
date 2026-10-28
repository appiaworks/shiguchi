package dev.appiaworks.shiguchi.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.api.AnnotationsProto;
import com.google.api.HttpProto;
import com.google.api.HttpRule;
import com.google.protobuf.Any;
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
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.protobuf.util.JsonFormat;
import com.google.rpc.BadRequest;
import com.google.rpc.ErrorInfo;
import com.google.rpc.RetryInfo;
import dev.appiaworks.shiguchi.serde.DynamicMessageMarshaller;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.ServerCalls;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Rich gRPC errors on the Boot 4 gateway's REST surface, in front of a real gRPC server: the
 * {@code google.rpc.Status} details a backend puts in {@code grpc-status-details-bin} reach the
 * caller as the {@code details} array of the error body — standard {@code google.rpc} types and the
 * service's own message types alike — a {@code RetryInfo} becomes {@code Retry-After}, and a
 * server stream that fails after it started ends with an in-band {@code {"error": ...}} line.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"shiguchi.ui.enabled=false", "shiguchi.graphql.enabled=false", "shiguchi.openapi.enabled=false"})
@DisplayName("gRPC error details on the REST surface end to end (Boot 4)")
class RestErrorDetailsEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private static final FileDescriptor FILE = buildFile();
    private static final Descriptor ITEM = FILE.findMessageTypeByName("Item");
    private static final Descriptor GET_REQUEST = FILE.findMessageTypeByName("GetRequest");
    private static final Descriptor LIST_REQUEST = FILE.findMessageTypeByName("ListRequest");
    private static final Server BACKEND = startBackend();
    private static final Path PB_FILE = writeDescriptorSet();

    @org.springframework.test.context.DynamicPropertySource
    static void backend(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("shiguchi.services[0].name", () -> "store");
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

    private WebClient client;

    @Autowired
    private Environment environment;

    @BeforeEach
    void bindClient() {
        client = WebClient.create("http://localhost:" + environment.getRequiredProperty("local.server.port"));
    }

    private record Reply(int status, String retryAfter, String body) {}

    private Reply get(String uri) {
        return client.get()
                .uri(uri)
                .exchangeToMono(r -> r.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(b -> new Reply(
                                r.statusCode().value(),
                                r.headers().asHttpHeaders().getFirst("Retry-After"),
                                b)))
                .block(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName(
            "GIVEN the backend fails with INVALID_ARGUMENT carrying BadRequest, ErrorInfo and RetryInfo WHEN called over REST SHOULD answer 400 with every detail in the body and Retry-After from the RetryInfo")
    void given_badRequestWithRetryInfo_when_called_should_listDetailsAndSetRetryAfter() throws Exception {
        Reply reply = get("/v1/items/bad");

        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.retryAfter()).isEqualTo("3");
        Struct body = parse(reply.body());
        assertThat(str(body, "code")).isEqualTo("INVALID_ARGUMENT");
        assertThat(str(body, "message")).isEqualTo("id must be numeric");
        assertThat(at(body, "details").getListValue().getValuesCount()).isEqualTo(3);
        assertThat(str(body, "details", 0, "@type")).isEqualTo("type.googleapis.com/google.rpc.BadRequest");
        assertThat(str(body, "details", 0, "fieldViolations", 0, "field")).isEqualTo("id");
        assertThat(str(body, "details", 1, "@type")).isEqualTo("type.googleapis.com/google.rpc.ErrorInfo");
        assertThat(str(body, "details", 1, "reason")).isEqualTo("MALFORMED_ID");
        assertThat(str(body, "details", 1, "metadata", "hint")).isEqualTo("digits only");
        assertThat(str(body, "details", 2, "@type")).isEqualTo("type.googleapis.com/google.rpc.RetryInfo");
        assertThat(str(body, "details", 2, "retryDelay")).isEqualTo("2.500s");
    }

    @Test
    @DisplayName(
            "GIVEN the backend attaches a detail of its own message type WHEN called over REST SHOULD print it as JSON through the service's discovered types")
    void given_serviceOwnDetailType_when_called_should_printThroughDiscoveredTypes() throws Exception {
        Reply reply = get("/v1/items/custom");

        assertThat(reply.status()).isEqualTo(404);
        assertThat(reply.retryAfter()).isNull();
        Struct body = parse(reply.body());
        assertThat(str(body, "code")).isEqualTo("NOT_FOUND");
        assertThat(str(body, "details", 0, "@type")).isEqualTo("type.googleapis.com/errs.Item");
        assertThat(str(body, "details", 0, "id")).isEqualTo("custom");
        assertThat(str(body, "details", 0, "payload")).isEqualTo("closest match");
    }

    @Test
    @DisplayName(
            "GIVEN the backend fails without any detail WHEN called over REST SHOULD answer the code and message with no details member")
    void given_plainFailure_when_called_should_omitDetails() throws Exception {
        Reply reply = get("/v1/items/plain");

        assertThat(reply.status()).isEqualTo(404);
        Struct body = parse(reply.body());
        assertThat(str(body, "code")).isEqualTo("NOT_FOUND");
        assertThat(str(body, "message")).isEqualTo("no item plain");
        assertThat(body.containsFields("details")).isFalse();
    }

    @Test
    @DisplayName(
            "GIVEN a server stream that fails after two items WHEN called over REST SHOULD answer 200 NDJSON with the two items followed by one {\"error\":...} line carrying the details")
    void given_streamFailsAfterItems_when_called_should_endWithErrorLine() throws Exception {
        List<String> lines = client.get()
                .uri("/v1/items?failAfter=2")
                .exchangeToFlux(r -> {
                    assertThat(r.statusCode().value()).isEqualTo(200);
                    assertThat(r.headers().contentType().orElseThrow().isCompatibleWith(MediaType.APPLICATION_NDJSON))
                            .isTrue();
                    return r.bodyToFlux(String.class);
                })
                .collectList()
                .block(Duration.ofSeconds(30));

        assertThat(lines).hasSize(3);
        assertThat(str(parse(lines.get(0)), "id")).isEqualTo("0");
        assertThat(str(parse(lines.get(1)), "id")).isEqualTo("1");
        Struct last = parse(lines.get(2));
        assertThat(last.containsFields("error")).isTrue();
        assertThat(str(last, "error", "code")).isEqualTo("UNAVAILABLE");
        assertThat(str(last, "error", "message")).isEqualTo("stream broke");
        assertThat(str(last, "error", "details", 0, "@type")).isEqualTo("type.googleapis.com/google.rpc.RetryInfo");
    }

    @Test
    @DisplayName(
            "GIVEN a server stream that fails before its first item WHEN called over REST SHOULD answer a plain HTTP error with the mapped status and the details")
    void given_streamFailsBeforeFirstItem_when_called_should_answerHttpError() throws Exception {
        Reply reply = get("/v1/items?failAfter=0");

        assertThat(reply.status()).isEqualTo(503);
        assertThat(reply.retryAfter()).isEqualTo("1");
        Struct body = parse(reply.body());
        assertThat(str(body, "code")).isEqualTo("UNAVAILABLE");
        assertThat(str(body, "details", 0, "@type")).isEqualTo("type.googleapis.com/google.rpc.RetryInfo");
    }

    // JSON is read back through protobuf's Struct so the same test source compiles on both Boot
    // lines (Boot 4's test classpath has no Jackson 2).
    private static Struct parse(String json) throws Exception {
        Struct.Builder b = Struct.newBuilder();
        JsonFormat.parser().merge(json, b);
        return b.build();
    }

    /** Walks a parsed document: a String segment is a member, an Integer segment a list index. */
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

    // ------------------------------------------------------------------ fixtures

    private static FileDescriptor buildFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("errs/store.proto")
                    .setPackage("errs")
                    .setSyntax("proto3")
                    .addDependency("google/api/annotations.proto")
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Item")
                            .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_STRING))
                            .addField(field("payload", 2, FieldDescriptorProto.Type.TYPE_STRING)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("GetRequest")
                            .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_STRING)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("ListRequest")
                            .addField(field("fail_after", 1, FieldDescriptorProto.Type.TYPE_INT32)))
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Store")
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("GetItem")
                                    .setInputType(".errs.GetRequest")
                                    .setOutputType(".errs.Item")
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    AnnotationsProto.http,
                                                    HttpRule.newBuilder()
                                                            .setGet("/v1/items/{id}")
                                                            .build())))
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("ListItems")
                                    .setInputType(".errs.ListRequest")
                                    .setOutputType(".errs.Item")
                                    .setServerStreaming(true)
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    AnnotationsProto.http,
                                                    HttpRule.newBuilder()
                                                            .setGet("/v1/items")
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

    private static Path writeDescriptorSet() {
        try {
            Path path = Files.createTempFile("errs-store", ".pb");
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

    private static StatusRuntimeException rich(
            Status.Code code, String message, com.google.protobuf.Message... details) {
        com.google.rpc.Status.Builder status =
                com.google.rpc.Status.newBuilder().setCode(code.value()).setMessage(message);
        for (com.google.protobuf.Message d : details) status.addDetails(Any.pack(d));
        return StatusProto.toStatusRuntimeException(status.build());
    }

    private static RetryInfo retryIn(long seconds, int nanos) {
        return RetryInfo.newBuilder()
                .setRetryDelay(com.google.protobuf.Duration.newBuilder()
                        .setSeconds(seconds)
                        .setNanos(nanos))
                .build();
    }

    private static DynamicMessage item(String id, String payload) {
        return DynamicMessage.newBuilder(ITEM)
                .setField(ITEM.findFieldByName("id"), id)
                .setField(ITEM.findFieldByName("payload"), payload)
                .build();
    }

    private static Server startBackend() {
        var get = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("errs.Store/GetItem")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(GET_REQUEST))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(ITEM))
                .build();
        var list = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.SERVER_STREAMING)
                .setFullMethodName("errs.Store/ListItems")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(LIST_REQUEST))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(ITEM))
                .build();
        ServerServiceDefinition service = ServerServiceDefinition.builder("errs.Store")
                .addMethod(get, ServerCalls.asyncUnaryCall((request, observer) -> {
                    String id = (String) request.getField(GET_REQUEST.findFieldByName("id"));
                    switch (id) {
                        case "bad" ->
                            observer.onError(rich(
                                    Status.Code.INVALID_ARGUMENT,
                                    "id must be numeric",
                                    BadRequest.newBuilder()
                                            .addFieldViolations(BadRequest.FieldViolation.newBuilder()
                                                    .setField("id")
                                                    .setDescription("must be numeric"))
                                            .build(),
                                    ErrorInfo.newBuilder()
                                            .setReason("MALFORMED_ID")
                                            .setDomain("store.example.com")
                                            .putMetadata("hint", "digits only")
                                            .build(),
                                    retryIn(2, 500_000_000)));
                        case "custom" ->
                            observer.onError(
                                    rich(Status.Code.NOT_FOUND, "no item custom", item("custom", "closest match")));
                        case "plain" ->
                            observer.onError(Status.NOT_FOUND
                                    .withDescription("no item plain")
                                    .asRuntimeException());
                        default -> {
                            observer.onNext(item(id, "ok"));
                            observer.onCompleted();
                        }
                    }
                }))
                .addMethod(list, ServerCalls.asyncServerStreamingCall((request, observer) -> {
                    int failAfter = (int) request.getField(LIST_REQUEST.findFieldByName("fail_after"));
                    for (int i = 0; i < failAfter; i++) observer.onNext(item(Integer.toString(i), "x"));
                    observer.onError(rich(Status.Code.UNAVAILABLE, "stream broke", retryIn(0, 100_000_000)));
                }))
                .build();
        try {
            return ServerBuilder.forPort(0).addService(service).build().start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
