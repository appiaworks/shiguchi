package dev.appiaworks.shiguchi.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

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
import io.grpc.Status;
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
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Server-sent events (SSE) on server-streaming routes on the Boot 3 gateway's REST surface,
 * in front of a real gRPC backend: responses are framed as text/event-stream with Cache-Control
 * no-cache, one event per message, and error events for failures after streaming starts.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"shiguchi.ui.enabled=false", "shiguchi.graphql.enabled=false", "shiguchi.openapi.enabled=false"})
@DisplayName("Server-sent events on the REST surface end to end (Boot 3)")
class RestServerStreamingSseEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private static final FileDescriptor FILE = buildFile();
    private static final Descriptor ITEM = FILE.findMessageTypeByName("Item");
    private static final Descriptor LIST_REQUEST = FILE.findMessageTypeByName("ListRequest");

    private static final Server BACKEND = startBackend();
    private static final Path PB_FILE = writeDescriptorSet();

    @org.springframework.test.context.DynamicPropertySource
    static void backend(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("shiguchi.services[0].name", () -> "sse");
        registry.add("shiguchi.services[0].mode", () -> "PB_FILE");
        registry.add("shiguchi.services[0].pb-descriptor-path", () -> PB_FILE.toString());
        registry.add("shiguchi.services[0].host", () -> "localhost");
        registry.add("shiguchi.services[0].port", () -> BACKEND.getPort());
        registry.add("shiguchi.rest.streaming.sse-keepalive", () -> "200ms");
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

    @Test
    @DisplayName("GIVEN Accept: text/event-stream and count 3 WHEN called SHOULD answer 200 with SSE framing")
    void given_acceptSseAndCount3_when_called_should_answer200WithSseFraming() {
        var response = client.get()
                .uri("/v1/items?count=3")
                .header("Accept", "text/event-stream")
                .exchangeToMono(r -> {
                    assertThat(r.statusCode().value()).isEqualTo(200);
                    assertThat(r.headers().contentType()).isPresent();
                    assertThat(r.headers().contentType().get().isCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                            .as(
                                    "content type should be text/event-stream but was %s",
                                    r.headers().contentType().get())
                            .isTrue();
                    assertThat(r.headers().asHttpHeaders().getCacheControl())
                            .as("Cache-Control should contain no-cache")
                            .contains("no-cache");
                    return r.bodyToMono(String.class);
                })
                .block(Duration.ofSeconds(10));

        // Verify SSE framing: each item should be "data: {...}\n\n"
        assertThat(response).isNotNull();
        int dataCount = countOccurrences(response, "data: {");
        assertThat(dataCount).as("should contain exactly 3 data: { events").isEqualTo(3);

        // Verify items are in order and each event ends with \n\n
        String[] lines = response.split("\n");
        int eventCount = 0;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("data: {")) {
                // Next line should be empty (part of the \n\n ending)
                assertThat(i + 1 < lines.length || response.endsWith("\n\n"))
                        .as("each data: event should be followed by blank line")
                        .isTrue();
                eventCount++;
            }
        }
        assertThat(eventCount).isEqualTo(3);
    }

    @Test
    @DisplayName(
            "GIVEN Accept: application/x-ndjson and count 3 WHEN called SHOULD answer with NDJSON framing (no SSE)")
    void given_acceptNdjsonAndCount3_when_called_should_answerWithNdjsonFraming() {
        var response = client.get()
                .uri("/v1/items?count=3")
                .header("Accept", "application/x-ndjson")
                .exchangeToMono(r -> {
                    assertThat(r.statusCode().value()).isEqualTo(200);
                    assertThat(r.headers().contentType()).isPresent();
                    assertThat(r.headers().contentType().get().isCompatibleWith(MediaType.APPLICATION_NDJSON))
                            .as(
                                    "content type should be application/x-ndjson but was %s",
                                    r.headers().contentType().get())
                            .isTrue();
                    return r.bodyToMono(String.class);
                })
                .block(Duration.ofSeconds(10));

        // Verify NDJSON framing: one JSON object per line, no "data:" prefix
        assertThat(response).isNotNull();
        String[] lines = response.split("\n");
        // Filter out empty lines
        int jsonCount = 0;
        for (String line : lines) {
            if (!line.trim().isEmpty()) {
                assertThat(line)
                        .startsWith("{")
                        .endsWith("}")
                        .as("each line should be a JSON object, no 'data:' prefix")
                        .doesNotContain("data:");
                jsonCount++;
            }
        }
        assertThat(jsonCount).as("should contain exactly 3 JSON objects").isEqualTo(3);
    }

    @Test
    @DisplayName("GIVEN no Accept header WHEN called SHOULD default to application/x-ndjson")
    void given_noAcceptHeader_when_called_should_defaultToNdjson() {
        var response = client.get()
                .uri("/v1/items?count=3")
                // No Accept header set; WebClient may send */* but gateway defaults to NDJSON
                .exchangeToMono(r -> {
                    assertThat(r.statusCode().value()).isEqualTo(200);
                    assertThat(r.headers().contentType()).isPresent();
                    assertThat(r.headers().contentType().get().isCompatibleWith(MediaType.APPLICATION_NDJSON))
                            .as("should default to application/x-ndjson when Accept is not explicit")
                            .isTrue();
                    return r.bodyToMono(String.class);
                })
                .block(Duration.ofSeconds(10));

        // Should be NDJSON, not SSE
        assertThat(response).isNotNull();
        assertThat(response)
                .doesNotContain("data: {")
                .as("should not contain SSE 'data:' prefix when defaulting to NDJSON");
    }

    @Test
    @DisplayName("GIVEN Accept: text/event-stream on ListThenFail WHEN called SHOULD return error event after 2 items")
    void given_acceptSseOnListThenFail_when_called_should_returnErrorEventAfter2Items() {
        var response = client.get()
                .uri("/v1/items-then-fail")
                .header("Accept", "text/event-stream")
                .exchangeToMono(r -> {
                    assertThat(r.statusCode().value()).isEqualTo(200);
                    return r.bodyToMono(String.class);
                })
                .block(Duration.ofSeconds(10));

        assertThat(response).isNotNull();

        // The two items that succeeded come first as plain message events...
        int errorAt = response.indexOf("event: error\n");
        assertThat(errorAt).as("should contain an 'event: error' event").isPositive();
        assertThat(countOccurrences(response.substring(0, errorAt), "data: {"))
                .as("should contain exactly 2 message events before the error")
                .isEqualTo(2);

        // ...then one error event, framed as event + data + blank line, ending the stream.
        String errorEvent = response.substring(errorAt);
        assertThat(errorEvent).startsWith("event: error\ndata: {").endsWith("\n\n");
        assertThat(errorEvent).contains("\"code\":\"UNAVAILABLE\"").contains("stream broke");
    }

    @Test
    @DisplayName(
            "GIVEN Accept: text/event-stream with sse-keepalive=200ms WHEN backend emits after 700ms SHOULD include keepalive comments")
    void given_acceptSseWithKeepalive_when_backendDelayed_should_includeKeepaliveComments() {
        var response = client.get()
                .uri("/v1/items-with-delay")
                .header("Accept", "text/event-stream")
                .exchangeToMono(r -> {
                    assertThat(r.statusCode().value()).isEqualTo(200);
                    return r.bodyToMono(String.class);
                })
                .block(Duration.ofSeconds(30));

        assertThat(response).isNotNull();

        // Should contain 2 data events
        int dataCount = countOccurrences(response, "data: {");
        assertThat(dataCount).as("should contain exactly 2 data: { events").isEqualTo(2);

        // Should contain at least one keepalive comment (: keepalive)
        int keepaliveCount = countOccurrences(response, ": keepalive\n");
        assertThat(keepaliveCount)
                .as("should contain at least one keepalive comment after 700ms delay with 200ms keepalive interval")
                .isGreaterThanOrEqualTo(1);

        // Verify order: first data event, then keepalive(s), then second data event
        int firstDataIndex = response.indexOf("data: {");
        int firstKeepaliveIndex = response.indexOf(": keepalive");
        int secondDataIndex = response.indexOf("data: {", firstDataIndex + 1);

        assertThat(firstDataIndex)
                .as("first data event should come before keepalive")
                .isLessThan(firstKeepaliveIndex);
        assertThat(firstKeepaliveIndex)
                .as("keepalive should come before second data event")
                .isLessThan(secondDataIndex);
    }

    @Test
    @DisplayName(
            "GIVEN Accept: application/x-ndjson with sse-keepalive=200ms WHEN backend emits after 700ms SHOULD NOT include keepalive comments")
    void given_acceptNdjsonWithKeepalive_when_backendDelayed_should_notIncludeKeepalive() {
        var response = client.get()
                .uri("/v1/items-with-delay")
                .header("Accept", "application/x-ndjson")
                .exchangeToMono(r -> {
                    assertThat(r.statusCode().value()).isEqualTo(200);
                    return r.bodyToMono(String.class);
                })
                .block(Duration.ofSeconds(30));

        assertThat(response).isNotNull();

        // Should NOT contain keepalive comments (NDJSON has no comment syntax)
        assertThat(response).as("NDJSON should not contain keepalive comments").doesNotContain(": keepalive");

        // Should still contain 2 JSON objects
        String[] lines = response.split("\n");
        int jsonCount = 0;
        for (String line : lines) {
            if (!line.trim().isEmpty()) {
                jsonCount++;
            }
        }
        assertThat(jsonCount).as("should contain exactly 2 JSON objects").isEqualTo(2);
    }

    // ---------------------------------------------------------------- helper methods

    private static int countOccurrences(String text, String substring) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(substring, index)) != -1) {
            count++;
            index += substring.length();
        }
        return count;
    }

    // ---------------------------------------------------------------- the gRPC backend

    /**
     * sse.ItemService/ListItems(ListRequest{count}) returns (stream Item{id,payload}) at GET
     * /v1/items. sse.ItemService/ListItemsThenFail() returns (stream Item) then
     * Status.UNAVAILABLE at GET /v1/items-then-fail.
     */
    private static FileDescriptor buildFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("sse/items.proto")
                    .setSyntax("proto3")
                    .setPackage("sse")
                    .addDependency("google/api/annotations.proto")
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Item")
                            .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_STRING))
                            .addField(field("payload", 2, FieldDescriptorProto.Type.TYPE_STRING)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("ListRequest")
                            .addField(field("count", 1, FieldDescriptorProto.Type.TYPE_INT32)))
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("ItemService")
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("ListItems")
                                    .setInputType(".sse.ListRequest")
                                    .setOutputType(".sse.Item")
                                    .setServerStreaming(true)
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    AnnotationsProto.http,
                                                    HttpRule.newBuilder()
                                                            .setGet("/v1/items")
                                                            .build())))
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("ListItemsThenFail")
                                    .setInputType(".sse.ListRequest")
                                    .setOutputType(".sse.Item")
                                    .setServerStreaming(true)
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    AnnotationsProto.http,
                                                    HttpRule.newBuilder()
                                                            .setGet("/v1/items-then-fail")
                                                            .build())))
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("ListItemsWithDelay")
                                    .setInputType(".sse.ListRequest")
                                    .setOutputType(".sse.Item")
                                    .setServerStreaming(true)
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    AnnotationsProto.http,
                                                    HttpRule.newBuilder()
                                                            .setGet("/v1/items-with-delay")
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
            Path path = Files.createTempFile("sse-items", ".pb");
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
        io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> listItems =
                io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                        .setType(MethodType.SERVER_STREAMING)
                        .setFullMethodName("sse.ItemService/ListItems")
                        .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(LIST_REQUEST))
                        .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(ITEM))
                        .build();

        io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> listItemsThenFail =
                io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                        .setType(MethodType.SERVER_STREAMING)
                        .setFullMethodName("sse.ItemService/ListItemsThenFail")
                        .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(LIST_REQUEST))
                        .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(ITEM))
                        .build();

        io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> listItemsWithDelay =
                io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                        .setType(MethodType.SERVER_STREAMING)
                        .setFullMethodName("sse.ItemService/ListItemsWithDelay")
                        .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(LIST_REQUEST))
                        .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(ITEM))
                        .build();

        ServerServiceDefinition service = ServerServiceDefinition.builder("sse.ItemService")
                .addMethod(listItems, ServerCalls.asyncServerStreamingCall((request, observer) -> {
                    int count = (int) request.getField(LIST_REQUEST.findFieldByName("count"));
                    for (int i = 0; i < count; i++) {
                        observer.onNext(DynamicMessage.newBuilder(ITEM)
                                .setField(ITEM.findFieldByName("id"), Integer.toString(i))
                                .setField(ITEM.findFieldByName("payload"), "item-" + i)
                                .build());
                    }
                    observer.onCompleted();
                }))
                .addMethod(listItemsThenFail, ServerCalls.asyncServerStreamingCall((request, observer) -> {
                    // Send 2 items
                    observer.onNext(DynamicMessage.newBuilder(ITEM)
                            .setField(ITEM.findFieldByName("id"), "0")
                            .setField(ITEM.findFieldByName("payload"), "item-0")
                            .build());
                    observer.onNext(DynamicMessage.newBuilder(ITEM)
                            .setField(ITEM.findFieldByName("id"), "1")
                            .setField(ITEM.findFieldByName("payload"), "item-1")
                            .build());
                    // Then fail
                    observer.onError(
                            Status.UNAVAILABLE.withDescription("stream broke").asException());
                }))
                .addMethod(listItemsWithDelay, ServerCalls.asyncServerStreamingCall((request, observer) -> {
                    // Emit first item immediately
                    observer.onNext(DynamicMessage.newBuilder(ITEM)
                            .setField(ITEM.findFieldByName("id"), "0")
                            .setField(ITEM.findFieldByName("payload"), "item-0-before-delay")
                            .build());
                    // Wait ~700ms to trigger keepalive (200ms + overhead), then emit second item
                    try {
                        Thread.sleep(700);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    observer.onNext(DynamicMessage.newBuilder(ITEM)
                            .setField(ITEM.findFieldByName("id"), "1")
                            .setField(ITEM.findFieldByName("payload"), "item-1-after-delay")
                            .build());
                    observer.onCompleted();
                }))
                .build();

        try {
            return ServerBuilder.forPort(0).addService(service).build().start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
