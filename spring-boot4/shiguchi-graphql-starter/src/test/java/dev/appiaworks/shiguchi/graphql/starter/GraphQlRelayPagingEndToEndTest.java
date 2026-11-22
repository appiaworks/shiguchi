package dev.appiaworks.shiguchi.graphql.starter;

import static org.assertj.core.api.Assertions.assertThat;

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
import dev.appiaworks.shiguchi.proto.GraphQlProto;
import dev.appiaworks.shiguchi.proto.GraphQlRule;
import dev.appiaworks.shiguchi.proto.GraphQlRule.Op;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Relay paging as a client drives it: take {@code pageInfo.endCursor} from one page and send it
 * back as {@code after}. The unit tests covered the transformer (cursors out) and the argument
 * mapper (cursors in) separately, each right against its own assumption, and the two disagreed:
 * {@code endCursor} was the raw backend token, {@code after} expected a {@code base64(token:index)}
 * cursor, and a binary token came back as {@code Malformed page_token}.
 *
 * <p>The fake backend hands out opaque binary tokens (unpadded base64 of non-UTF-8 bytes, like a
 * protobuf-encoded token) and rejects anything it did not issue, so a cursor that is not unpacked
 * correctly fails here the way it fails against a real service.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"shiguchi.ui.enabled=false", "shiguchi.openapi.enabled=false"})
@DisplayName("GraphQL Relay paging round trip end to end (Boot 4, DGS)")
class GraphQlRelayPagingEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private static final int TOTAL = 7;

    private static final FileDescriptor FILE = buildFile();
    private static final Descriptor ITEM = FILE.findMessageTypeByName("Item");
    private static final Descriptor LIST_REQUEST = FILE.findMessageTypeByName("ListItemsRequest");
    private static final Descriptor PAGE_REQUEST = FILE.findMessageTypeByName("PageRequest");
    private static final Descriptor LIST_RESPONSE = FILE.findMessageTypeByName("ItemListResponse");
    private static final Descriptor PAGE_INFO = FILE.findMessageTypeByName("PageInfo");

    private static final Server BACKEND = startBackend();
    private static final Path PB_FILE = writeDescriptorSet();

    @DynamicPropertySource
    static void backend(DynamicPropertyRegistry registry) {
        registry.add("shiguchi.services[0].name", () -> "pg");
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

    private Struct graphql(String query) throws Exception {
        String body = client.post()
                .uri("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("query", query))
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(30));
        Struct.Builder b = Struct.newBuilder();
        JsonFormat.parser().merge(body, b);
        return b.build();
    }

    private Struct connection(String args) throws Exception {
        Struct response = graphql(
                "{ items(" + args
                        + ") { edges { cursor node { id } } pageInfo { startCursor endCursor hasNextPage hasPreviousPage } } }");
        assertThat(response.containsFields("errors")).as(response.toString()).isFalse();
        return response.getFieldsOrThrow("data")
                .getStructValue()
                .getFieldsOrThrow("items")
                .getStructValue();
    }

    private static List<String> ids(Struct connection) {
        List<String> ids = new ArrayList<>();
        for (Value edge : connection.getFieldsOrThrow("edges").getListValue().getValuesList()) {
            ids.add(edge.getStructValue()
                    .getFieldsOrThrow("node")
                    .getStructValue()
                    .getFieldsOrThrow("id")
                    .getStringValue());
        }
        return ids;
    }

    private static List<String> edgeCursors(Struct connection) {
        return connection.getFieldsOrThrow("edges").getListValue().getValuesList().stream()
                .map(e -> e.getStructValue().getFieldsOrThrow("cursor").getStringValue())
                .toList();
    }

    private static Struct pageInfo(Struct connection) {
        return connection.getFieldsOrThrow("pageInfo").getStructValue();
    }

    private static String quoted(String cursor) {
        return "\"" + cursor + "\"";
    }

    @Test
    @DisplayName(
            "GIVEN a first page WHEN its endCursor is sent back as after SHOULD answer the next page, which is what Relay clients do")
    void given_endCursor_when_sentAsAfter_should_answerNextPage() throws Exception {
        Struct first = connection("first: 3");
        assertThat(ids(first)).containsExactly("item-1", "item-2", "item-3");
        assertThat(pageInfo(first).getFieldsOrThrow("hasNextPage").getBoolValue())
                .isTrue();

        String endCursor = pageInfo(first).getFieldsOrThrow("endCursor").getStringValue();
        Struct second = connection("first: 3, after: " + quoted(endCursor));

        assertThat(ids(second)).containsExactly("item-4", "item-5", "item-6");
    }

    @Test
    @DisplayName("GIVEN a page WHEN transformed SHOULD make endCursor the last edge's cursor, and startCursor valid")
    void given_page_when_queried_should_endCursorEqualLastEdgeCursor() throws Exception {
        Struct first = connection("first: 3");
        List<String> cursors = edgeCursors(first);

        assertThat(pageInfo(first).getFieldsOrThrow("endCursor").getStringValue())
                .isEqualTo(cursors.get(cursors.size() - 1));
    }

    @Test
    @DisplayName("GIVEN a first page WHEN any of its edge cursors is sent as after SHOULD answer the next page")
    void given_edgeCursor_when_sentAsAfter_should_answerNextPage() throws Exception {
        Struct first = connection("first: 3");

        for (String cursor : edgeCursors(first)) {
            Struct next = connection("first: 3, after: " + quoted(cursor));
            assertThat(ids(next)).as("after %s", cursor).containsExactly("item-4", "item-5", "item-6");
        }
    }

    @Test
    @DisplayName(
            "GIVEN seven items WHEN a client pages by endCursor until hasNextPage is false SHOULD see each item once, in order")
    void given_pagedByEndCursor_when_walked_should_visitEveryItemOnce() throws Exception {
        List<String> seen = new ArrayList<>();
        String after = null;
        int pages = 0;
        boolean more = true;
        while (more) {
            Struct page = connection("first: 3" + (after == null ? "" : ", after: " + quoted(after)));
            seen.addAll(ids(page));
            more = pageInfo(page).getFieldsOrThrow("hasNextPage").getBoolValue();
            if (more) after = pageInfo(page).getFieldsOrThrow("endCursor").getStringValue();
            assertThat(++pages).as("terminates").isLessThanOrEqualTo(TOTAL);
        }

        assertThat(seen).containsExactly("item-1", "item-2", "item-3", "item-4", "item-5", "item-6", "item-7");
        assertThat(pages).isEqualTo(3);
    }

    @Test
    @DisplayName(
            "GIVEN a page reached by cursor WHEN queried SHOULD report a previous page and echo a usable startCursor")
    void given_pagedIn_when_queried_should_reportPreviousPage() throws Exception {
        String endCursor =
                pageInfo(connection("first: 3")).getFieldsOrThrow("endCursor").getStringValue();

        Struct second = connection("first: 3, after: " + quoted(endCursor));

        assertThat(pageInfo(second).getFieldsOrThrow("hasPreviousPage").getBoolValue())
                .isTrue();
        assertThat(pageInfo(second).getFieldsOrThrow("startCursor").getStringValue())
                .isEqualTo(endCursor);
    }

    @Test
    @DisplayName(
            "GIVEN a raw backend token (an endCursor a client stored before cursors were unified) WHEN sent as after SHOULD still page")
    void given_rawBackendToken_when_sentAsAfter_should_stillPage() throws Exception {
        // Unpadded base64 of the bytes the backend issues for "next page starts at item 4".
        String raw = token(3);

        Struct page = connection("first: 3, after: " + quoted(raw));

        assertThat(ids(page)).containsExactly("item-4", "item-5", "item-6");
    }

    @Test
    @DisplayName("GIVEN a cursor the backend never issued WHEN sent as after SHOULD answer an error, not a page")
    void given_foreignCursor_when_sentAsAfter_should_answerError() throws Exception {
        Struct response = graphql("{ items(first: 3, after: \"bm9wZQ==\") { edges { cursor } } }");

        assertThat(response.containsFields("errors")).as(response.toString()).isTrue();
    }

    // ------------------------------------------------------------------ fixtures

    /** Opaque, non-UTF-8, unpadded: nothing like a "token:index" string. */
    private static String token(int offset) {
        return Base64.getEncoder()
                .withoutPadding()
                .encodeToString(new byte[] {0x0a, 0x03, (byte) 0xff, (byte) 0xfe, (byte) offset});
    }

    private static int offsetOf(String token) {
        if (token.isEmpty()) return 0;
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(token);
        } catch (IllegalArgumentException e) {
            throw Status.INVALID_ARGUMENT
                    .withDescription("Malformed page_token")
                    .asRuntimeException();
        }
        if (bytes.length != 5 || bytes[0] != 0x0a || bytes[2] != (byte) 0xff) {
            throw Status.INVALID_ARGUMENT
                    .withDescription("Malformed page_token")
                    .asRuntimeException();
        }
        return bytes[4];
    }

    private static FileDescriptor buildFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("pg/items.proto")
                    .setSyntax("proto3")
                    .setPackage("pg")
                    .addDependency("shiguchi/graphql.proto")
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Item")
                            .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_STRING, null)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("PageRequest")
                            .addField(field("page_size", 1, FieldDescriptorProto.Type.TYPE_INT32, null)
                                    .setJsonName("pageSize"))
                            .addField(field("page_token", 2, FieldDescriptorProto.Type.TYPE_STRING, null)
                                    .setJsonName("pageToken")))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("PageInfo")
                            .addField(field("next_page_token", 1, FieldDescriptorProto.Type.TYPE_STRING, null)
                                    .setJsonName("nextPageToken"))
                            .addField(field("total_count", 2, FieldDescriptorProto.Type.TYPE_INT32, null)
                                    .setJsonName("totalCount")))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("ListItemsRequest")
                            .addField(
                                    field("page_request", 1, FieldDescriptorProto.Type.TYPE_MESSAGE, ".pg.PageRequest")
                                            .setJsonName("pageRequest")))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("ItemListResponse")
                            .addField(field("items", 1, FieldDescriptorProto.Type.TYPE_MESSAGE, ".pg.Item")
                                    .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED))
                            .addField(field("page_info", 2, FieldDescriptorProto.Type.TYPE_MESSAGE, ".pg.PageInfo")
                                    .setJsonName("pageInfo")))
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Catalog")
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("ListItems")
                                    .setInputType(".pg.ListItemsRequest")
                                    .setOutputType(".pg.ItemListResponse")
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    GraphQlProto.graphql,
                                                    GraphQlRule.newBuilder()
                                                            .setName("items")
                                                            .setType(Op.QUERY)
                                                            .build()))))
                    .build();
            return FileDescriptor.buildFrom(proto, new FileDescriptor[] {GraphQlProto.getDescriptor()});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static FieldDescriptorProto.Builder field(
            String name, int number, FieldDescriptorProto.Type type, String typeName) {
        FieldDescriptorProto.Builder b = FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
        if (typeName != null) b.setTypeName(typeName);
        return b;
    }

    private static Path writeDescriptorSet() {
        try {
            Path path = Files.createTempFile("pg-items", ".pb");
            FileDescriptorSet set = FileDescriptorSet.newBuilder()
                    .addFile(DescriptorProtos.getDescriptor().toProto())
                    .addFile(GraphQlProto.getDescriptor().toProto())
                    .addFile(FILE.toProto())
                    .build();
            Files.write(path, set.toByteArray());
            return path;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Server startBackend() {
        var list = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("pg.Catalog/ListItems")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(LIST_REQUEST))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(LIST_RESPONSE))
                .build();
        ServerServiceDefinition service = ServerServiceDefinition.builder("pg.Catalog")
                .addMethod(list, ServerCalls.asyncUnaryCall((request, observer) -> {
                    int size = 10;
                    String token = "";
                    if (request.hasField(LIST_REQUEST.findFieldByName("page_request"))) {
                        DynamicMessage page =
                                (DynamicMessage) request.getField(LIST_REQUEST.findFieldByName("page_request"));
                        int requested = (Integer) page.getField(PAGE_REQUEST.findFieldByName("page_size"));
                        if (requested > 0) size = requested;
                        token = (String) page.getField(PAGE_REQUEST.findFieldByName("page_token"));
                    }
                    int start;
                    try {
                        start = offsetOf(token);
                    } catch (io.grpc.StatusRuntimeException e) {
                        observer.onError(e);
                        return;
                    }
                    int end = Math.min(start + size, TOTAL);
                    DynamicMessage.Builder reply = DynamicMessage.newBuilder(LIST_RESPONSE);
                    for (int n = start + 1; n <= end; n++) {
                        reply.addRepeatedField(
                                LIST_RESPONSE.findFieldByName("items"),
                                DynamicMessage.newBuilder(ITEM)
                                        .setField(ITEM.findFieldByName("id"), "item-" + n)
                                        .build());
                    }
                    reply.setField(
                            LIST_RESPONSE.findFieldByName("page_info"),
                            DynamicMessage.newBuilder(PAGE_INFO)
                                    .setField(
                                            PAGE_INFO.findFieldByName("next_page_token"), end < TOTAL ? token(end) : "")
                                    .setField(PAGE_INFO.findFieldByName("total_count"), TOTAL)
                                    .build());
                    observer.onNext(reply.build());
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
