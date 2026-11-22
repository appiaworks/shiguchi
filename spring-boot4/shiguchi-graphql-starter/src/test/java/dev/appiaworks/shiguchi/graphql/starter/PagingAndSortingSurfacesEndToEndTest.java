package dev.appiaworks.shiguchi.graphql.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.api.AnnotationsProto;
import com.google.api.HttpProto;
import com.google.api.HttpRule;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodOptions;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * Paging, sorting and total-count seams, each crossing producer and consumer, on both surfaces
 * of one gateway: REST ({@code X-Next-Page-Token} out, {@code ?pageToken=} / {@code ?after=} in)
 * and GraphQL (Relay {@code endCursor} out, {@code after} in).
 *
 * <p>The fake backend hands out opaque binary page tokens (unpadded base64 of non-UTF-8 bytes
 * containing {@code +} and {@code /}), rejects any token it did not issue with
 * {@code INVALID_ARGUMENT}, and records what it received ({@code sort_fields}, {@code page_size},
 * {@code page_token}) so a test can assert what arrived, not only what came back.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"shiguchi.ui.enabled=false", "shiguchi.openapi.enabled=false"})
@DisplayName("Paging, sorting and totalCount across REST and GraphQL end to end (Boot 4)")
class PagingAndSortingSurfacesEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private static final String[] NAMES = {"delta", "alpha", "golden", "bravo", "echo", "charlie", "foxtrot"};
    private static final int TOTAL = NAMES.length;
    /** The ids ({@code item-N}) in name order: alpha, bravo, charlie, delta, echo, foxtrot, golden. */
    private static final List<String> BY_NAME_ASC =
            List.of("item-2", "item-4", "item-6", "item-1", "item-5", "item-7", "item-3");

    private static final FileDescriptor FILE = buildFile();
    private static final Descriptor ITEM = FILE.findMessageTypeByName("Item");
    private static final Descriptor LIST_REQUEST = FILE.findMessageTypeByName("ListItemsRequest");
    private static final Descriptor PAGE_REQUEST = FILE.findMessageTypeByName("PageRequest");
    private static final Descriptor SORT_FIELD = FILE.findMessageTypeByName("SortField");
    private static final Descriptor LIST_RESPONSE = FILE.findMessageTypeByName("ItemListResponse");
    private static final Descriptor PAGE_INFO = FILE.findMessageTypeByName("PageInfo");

    /** What the backend saw, per call. */
    private record Seen(List<String> sort, int pageSize, String pageToken) {}

    private static final List<Seen> CALLS = new CopyOnWriteArrayList<>();
    private static volatile int totalCountToReport = TOTAL;

    private static final Server BACKEND = startBackend();
    private static final Path PB_FILE = writeDescriptorSet();

    @DynamicPropertySource
    static void backend(DynamicPropertyRegistry registry) {
        registry.add("shiguchi.services[0].name", () -> "ps");
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
        CALLS.clear();
        totalCountToReport = TOTAL;
    }

    // ------------------------------------------------------------------ REST helpers

    private record Reply(int status, Map<String, List<String>> headers, String body) {
        String header(String name) {
            return headers.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().get(0))
                    .findFirst()
                    .orElse(null);
        }

        Struct json() {
            try {
                Struct.Builder b = Struct.newBuilder();
                JsonFormat.parser().ignoringUnknownFields().merge(body, b);
                return b.build();
            } catch (Exception e) {
                throw new IllegalStateException(body, e);
            }
        }
    }

    /** GET with the query written as a template so every variable is URL-encoded ({@code +}, {@code /}). */
    private Reply rest(String pathAndQuery, Object... vars) {
        return client.get()
                .uri(pathAndQuery, vars)
                .exchangeToMono(r -> r.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(b -> new Reply(
                                r.statusCode().value(),
                                r.headers().asHttpHeaders().toSingleValueMap().entrySet().stream()
                                        .collect(java.util.stream.Collectors.toMap(
                                                Map.Entry::getKey, e -> List.of(e.getValue()))),
                                b)))
                .block(Duration.ofSeconds(30));
    }

    private static List<String> restIds(Reply reply) {
        List<String> ids = new ArrayList<>();
        for (Value item : reply.json().getFieldsOrThrow("items").getListValue().getValuesList()) {
            ids.add(item.getStructValue().getFieldsOrThrow("id").getStringValue());
        }
        return ids;
    }

    // ------------------------------------------------------------------ GraphQL helpers

    private Reply graphqlRaw(String query) {
        return client.post()
                .uri("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("query", query))
                .exchangeToMono(r -> r.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(b -> new Reply(
                                r.statusCode().value(),
                                r.headers().asHttpHeaders().toSingleValueMap().entrySet().stream()
                                        .collect(java.util.stream.Collectors.toMap(
                                                Map.Entry::getKey, e -> List.of(e.getValue()))),
                                b)))
                .block(Duration.ofSeconds(30));
    }

    private Struct connection(String args) {
        Reply reply = graphqlRaw("{ items(" + args
                + ") { totalCount edges { cursor node { id name } } pageInfo { endCursor hasNextPage totalCount } } }");
        Struct response = reply.json();
        assertThat(response.containsFields("errors")).as(reply.body()).isFalse();
        return response.getFieldsOrThrow("data")
                .getStructValue()
                .getFieldsOrThrow("items")
                .getStructValue();
    }

    private static List<String> gqlIds(Struct connection) {
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

    private static Struct gqlPageInfo(Struct connection) {
        return connection.getFieldsOrThrow("pageInfo").getStructValue();
    }

    private static String quoted(String s) {
        return "\"" + s + "\"";
    }

    private static final String SORT_NAME_DESC = "sort: [{field: \"name\", sortOrder: SORT_ORDER_DESC}]";

    // ================================================================ 1. REST paging round trip

    @Test
    @DisplayName(
            "GIVEN seven items WHEN REST pages by feeding X-Next-Page-Token back as ?pageToken= SHOULD see each item once and no token on the last page")
    void given_restPageToken_when_walked_should_visitEveryItemOnce() {
        List<String> seen = new ArrayList<>();
        String token = null;
        int pages = 0;
        while (true) {
            Reply page =
                    token == null ? rest("/v1/items?pageSize=3") : rest("/v1/items?pageSize=3&pageToken={t}", token);
            assertThat(page.status()).as(page.body()).isEqualTo(200);
            seen.addAll(restIds(page));
            token = page.header("X-Next-Page-Token");
            assertThat(++pages).as("terminates").isLessThanOrEqualTo(TOTAL);
            if (token == null) break;
        }

        assertThat(seen).containsExactly("item-1", "item-2", "item-3", "item-4", "item-5", "item-6", "item-7");
        assertThat(pages).isEqualTo(3);
    }

    @Test
    @DisplayName(
            "GIVEN seven items WHEN REST pages with ?first=&after= carrying the raw X-Next-Page-Token SHOULD see each item once")
    void given_restFirstAfter_when_walked_should_visitEveryItemOnce() {
        List<String> seen = new ArrayList<>();
        String token = null;
        int pages = 0;
        while (true) {
            Reply page = token == null ? rest("/v1/items?first=3") : rest("/v1/items?first=3&after={t}", token);
            assertThat(page.status()).as(page.body()).isEqualTo(200);
            seen.addAll(restIds(page));
            token = page.header("X-Next-Page-Token");
            assertThat(++pages).as("terminates").isLessThanOrEqualTo(TOTAL);
            if (token == null) break;
        }

        assertThat(seen).containsExactly("item-1", "item-2", "item-3", "item-4", "item-5", "item-6", "item-7");
    }

    // ================================================================ 3. sort round trip

    @Test
    @DisplayName(
            "GIVEN ?sort=name:desc on REST and sort: [{name, DESC}] on GraphQL SHOULD reach the backend as the same sort_fields")
    void given_sortOnBothSurfaces_when_called_should_arriveAsSameSortFields() {
        rest("/v1/items?sort=name:desc");
        graphqlRaw("{ items(first: 3, " + SORT_NAME_DESC + ") { edges { node { id } } } }");

        assertThat(CALLS).hasSize(2);
        assertThat(CALLS.get(0).sort()).containsExactly("name:SORT_ORDER_DESC");
        assertThat(CALLS.get(1).sort()).isEqualTo(CALLS.get(0).sort());
    }

    @Test
    @DisplayName("GIVEN a multi-key sort on REST WHEN sent SHOULD arrive in order with ascending as the default")
    void given_multiKeySortOnRest_when_called_should_arriveInOrder() {
        rest("/v1/items?sort=name:desc,id");

        assertThat(CALLS.get(0).sort()).containsExactly("name:SORT_ORDER_DESC", "id:SORT_ORDER_ASC");
    }

    @Test
    @DisplayName("GIVEN a sort WHEN REST pages by token with the sort resent SHOULD keep one global order across pages")
    void given_sortedRestPaging_when_walked_should_keepOrderAcrossPages() {
        List<String> seen = new ArrayList<>();
        String token = null;
        do {
            Reply page = token == null
                    ? rest("/v1/items?pageSize=3&sort=name:desc")
                    : rest("/v1/items?pageSize=3&sort=name:desc&pageToken={t}", token);
            seen.addAll(restIds(page));
            token = page.header("X-Next-Page-Token");
        } while (token != null && seen.size() < 2 * TOTAL);

        List<String> expected = new ArrayList<>(BY_NAME_ASC);
        java.util.Collections.reverse(expected);
        assertThat(seen).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("GIVEN a sort WHEN GraphQL pages by endCursor with the sort resent SHOULD keep the same order as REST")
    void given_sortedGraphQlPaging_when_walked_should_matchRestOrder() {
        List<String> seen = new ArrayList<>();
        String after = null;
        boolean more = true;
        while (more && seen.size() < 2 * TOTAL) {
            Struct page =
                    connection("first: 3, " + SORT_NAME_DESC + (after == null ? "" : ", after: " + quoted(after)));
            seen.addAll(gqlIds(page));
            more = gqlPageInfo(page).getFieldsOrThrow("hasNextPage").getBoolValue();
            if (more) after = gqlPageInfo(page).getFieldsOrThrow("endCursor").getStringValue();
        }

        List<String> expected = new ArrayList<>(BY_NAME_ASC);
        java.util.Collections.reverse(expected);
        assertThat(seen).containsExactlyElementsOf(expected);
    }

    // ================================================================ 4. malformed / foreign token

    @Test
    @DisplayName(
            "GIVEN a token the backend never issued WHEN sent on REST SHOULD answer 400 with a body naming the problem")
    void given_foreignTokenOnRest_when_called_should_answer400WithUsefulBody() {
        Reply reply = rest("/v1/items?pageSize=3&pageToken={t}", "not-a-token!");

        assertThat(reply.status()).as(reply.body()).isEqualTo(400);
        assertThat(reply.body()).contains("Malformed page_token");
    }

    @Test
    @DisplayName(
            "GIVEN a token the backend never issued WHEN sent as GraphQL after SHOULD answer an error naming the problem, not hang or 500")
    void given_foreignCursorOnGraphQl_when_called_should_answerErrorWithCode() {
        Reply reply = graphqlRaw("{ items(first: 3, after: \"bm9wZQ==\") { edges { node { id } } } }");

        assertThat(reply.status()).as(reply.body()).isEqualTo(200);
        Struct response = reply.json();
        assertThat(response.containsFields("errors")).as(reply.body()).isTrue();
        Struct error =
                response.getFieldsOrThrow("errors").getListValue().getValues(0).getStructValue();
        assertThat(error.getFieldsOrThrow("message").getStringValue()).contains("Malformed page_token");
        assertThat(error.getFieldsOrThrow("extensions")
                        .getStructValue()
                        .getFieldsOrThrow("code")
                        .getStringValue())
                .isEqualTo("BAD_USER_INPUT");
    }

    // ================================================================ 5. totalCount

    @Test
    @DisplayName("GIVEN a backend total_count of 7 WHEN read on both surfaces SHOULD agree on 7")
    void given_totalCountSet_when_readOnBothSurfaces_should_agree() {
        Reply rest = rest("/v1/items?pageSize=3");
        Struct gql = connection("first: 3");

        assertThat(rest.header("X-Total-Count")).isEqualTo("7");
        assertThat(gql.getFieldsOrThrow("totalCount").getNumberValue()).isEqualTo(7);
        assertThat(gqlPageInfo(gql).getFieldsOrThrow("totalCount").getNumberValue())
                .isEqualTo(7);
    }

    @Test
    @DisplayName(
            "GIVEN a backend total_count of -1 (not computed) WHEN read on both surfaces SHOULD omit it on REST and null it on GraphQL")
    void given_totalCountMinusOne_when_readOnBothSurfaces_should_beAbsent() {
        totalCountToReport = -1;

        Reply rest = rest("/v1/items?pageSize=3");
        Struct gql = connection("first: 3");

        assertThat(rest.header("X-Total-Count")).isNull();
        assertThat(gql.getFieldsOrThrow("totalCount").hasNullValue())
                .as("connection.totalCount: %s", gql.getFieldsOrThrow("totalCount"))
                .isTrue();
        assertThat(gqlPageInfo(gql).getFieldsOrThrow("totalCount").hasNullValue())
                .as("pageInfo.totalCount: %s", gqlPageInfo(gql).getFieldsOrThrow("totalCount"))
                .isTrue();
    }

    @Test
    @DisplayName(
            "GIVEN a backend total_count of 0 (proto3 default, also what unset decodes to) WHEN read on both surfaces SHOULD agree with each other")
    void given_totalCountZero_when_readOnBothSurfaces_should_agree() {
        totalCountToReport = 0;

        Reply rest = rest("/v1/items?pageSize=3");
        Struct gql = connection("first: 3");

        String header = rest.header("X-Total-Count");
        var total = gql.getFieldsOrThrow("totalCount");
        if (header == null) {
            assertThat(total.hasNullValue())
                    .as("REST omits it, so GraphQL must too: %s", total)
                    .isTrue();
        } else {
            assertThat(total.getNumberValue()).isEqualTo(Double.parseDouble(header));
        }
    }

    // ================================================================ 6. first <= 0 / negative / huge

    @Test
    @DisplayName(
            "GIVEN ?pageSize=0, -5 and a huge value WHEN sent on REST SHOULD forward each verbatim to the backend (documents current behaviour)")
    void given_oddPageSizesOnRest_when_called_should_forwardVerbatim() {
        Reply zero = rest("/v1/items?pageSize=0");
        Reply negative = rest("/v1/items?pageSize=-5");
        Reply huge = rest("/v1/items?pageSize=2147483647");

        assertThat(zero.status()).as(zero.body()).isEqualTo(200);
        assertThat(negative.status()).as(negative.body()).isEqualTo(200);
        assertThat(huge.status()).as(huge.body()).isEqualTo(200);
        // 0 is the proto default, so the backend cannot tell it from "unset": it applies its own default.
        assertThat(CALLS.get(0).pageSize()).isZero();
        assertThat(CALLS.get(1).pageSize()).isEqualTo(-5);
        assertThat(CALLS.get(2).pageSize()).isEqualTo(Integer.MAX_VALUE);
        // The backend here treats <= 0 as its default of 10: all seven items come back.
        assertThat(restIds(zero)).hasSize(TOTAL);
        assertThat(restIds(negative)).hasSize(TOTAL);
        assertThat(restIds(huge)).hasSize(TOTAL);
    }

    @Test
    @DisplayName(
            "GIVEN a non-numeric or overflowing ?pageSize WHEN sent on REST SHOULD drop it and let the backend default (documents current behaviour)")
    void given_unparseablePageSizeOnRest_when_called_should_beDropped() {
        Reply text = rest("/v1/items?pageSize=abc");
        Reply overflow = rest("/v1/items?pageSize=99999999999");

        assertThat(text.status()).as(text.body()).isEqualTo(200);
        assertThat(overflow.status()).as(overflow.body()).isEqualTo(200);
        assertThat(CALLS.get(0).pageSize()).isZero();
        assertThat(CALLS.get(1).pageSize()).isZero();
    }

    @Test
    @DisplayName(
            "GIVEN first: 0, -1 and Int max WHEN sent on GraphQL SHOULD forward each verbatim to the backend (documents current behaviour)")
    void given_oddFirstOnGraphQl_when_called_should_forwardVerbatim() {
        connection("first: 0");
        connection("first: -1");
        connection("first: 2147483647");

        assertThat(CALLS).hasSize(3);
        assertThat(CALLS.get(0).pageSize()).isZero();
        assertThat(CALLS.get(1).pageSize()).isEqualTo(-1);
        assertThat(CALLS.get(2).pageSize()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName(
            "GIVEN first beyond the GraphQL Int range WHEN sent SHOULD fail validation without reaching the backend")
    void given_firstBeyondIntRange_when_sent_should_failValidation() {
        Reply reply = graphqlRaw("{ items(first: 99999999999) { edges { node { id } } } }");

        assertThat(reply.json().containsFields("errors")).as(reply.body()).isTrue();
        assertThat(CALLS).isEmpty();
    }

    // ================================================================ fixtures

    /** Opaque, non-UTF-8, unpadded, with + and / in the encoding: nothing like a "token:index" string. */
    private static String token(int offset) {
        return Base64.getEncoder()
                .withoutPadding()
                .encodeToString(new byte[] {0x0a, 0x03, (byte) 0xfb, (byte) 0xff, (byte) offset});
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
        if (bytes.length != 5 || bytes[0] != 0x0a || bytes[2] != (byte) 0xfb) {
            throw Status.INVALID_ARGUMENT
                    .withDescription("Malformed page_token")
                    .asRuntimeException();
        }
        return bytes[4];
    }

    private static FileDescriptor buildFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("ps/items.proto")
                    .setSyntax("proto3")
                    .setPackage("ps")
                    .addDependency("google/api/annotations.proto")
                    .addDependency("shiguchi/graphql.proto")
                    .addEnumType(EnumDescriptorProto.newBuilder()
                            .setName("SortOrder")
                            .addValue(DescriptorProtos.EnumValueDescriptorProto.newBuilder()
                                    .setName("SORT_ORDER_UNSPECIFIED")
                                    .setNumber(0))
                            .addValue(DescriptorProtos.EnumValueDescriptorProto.newBuilder()
                                    .setName("SORT_ORDER_ASC")
                                    .setNumber(1))
                            .addValue(DescriptorProtos.EnumValueDescriptorProto.newBuilder()
                                    .setName("SORT_ORDER_DESC")
                                    .setNumber(2)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Item")
                            .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_STRING, null))
                            .addField(field("name", 2, FieldDescriptorProto.Type.TYPE_STRING, null)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("PageRequest")
                            .addField(field("page_size", 1, FieldDescriptorProto.Type.TYPE_INT32, null)
                                    .setJsonName("pageSize"))
                            .addField(field("page_token", 2, FieldDescriptorProto.Type.TYPE_STRING, null)
                                    .setJsonName("pageToken")))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("SortField")
                            .addField(field("field", 1, FieldDescriptorProto.Type.TYPE_STRING, null))
                            .addField(field("sort_order", 2, FieldDescriptorProto.Type.TYPE_ENUM, ".ps.SortOrder")
                                    .setJsonName("sortOrder")))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("PageInfo")
                            .addField(field("next_page_token", 1, FieldDescriptorProto.Type.TYPE_STRING, null)
                                    .setJsonName("nextPageToken"))
                            .addField(field("total_count", 2, FieldDescriptorProto.Type.TYPE_INT32, null)
                                    .setJsonName("totalCount")))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("ListItemsRequest")
                            .addField(
                                    field("page_request", 1, FieldDescriptorProto.Type.TYPE_MESSAGE, ".ps.PageRequest")
                                            .setJsonName("pageRequest"))
                            .addField(field("sort_fields", 2, FieldDescriptorProto.Type.TYPE_MESSAGE, ".ps.SortField")
                                    .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
                                    .setJsonName("sortFields")))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("ItemListResponse")
                            .addField(field("items", 1, FieldDescriptorProto.Type.TYPE_MESSAGE, ".ps.Item")
                                    .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED))
                            .addField(field("page_info", 2, FieldDescriptorProto.Type.TYPE_MESSAGE, ".ps.PageInfo")
                                    .setJsonName("pageInfo")))
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Catalog")
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("ListItems")
                                    .setInputType(".ps.ListItemsRequest")
                                    .setOutputType(".ps.ItemListResponse")
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    AnnotationsProto.http,
                                                    HttpRule.newBuilder()
                                                            .setGet("/v1/items")
                                                            .build())
                                            .setExtension(
                                                    GraphQlProto.graphql,
                                                    GraphQlRule.newBuilder()
                                                            .setName("items")
                                                            .setType(Op.QUERY)
                                                            .build()))))
                    .build();
            return FileDescriptor.buildFrom(
                    proto, new FileDescriptor[] {AnnotationsProto.getDescriptor(), GraphQlProto.getDescriptor()});
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
            Path path = Files.createTempFile("ps-items", ".pb");
            FileDescriptorSet set = FileDescriptorSet.newBuilder()
                    .addFile(DescriptorProtos.getDescriptor().toProto())
                    .addFile(HttpProto.getDescriptor().toProto())
                    .addFile(AnnotationsProto.getDescriptor().toProto())
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
                .setFullMethodName("ps.Catalog/ListItems")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(LIST_REQUEST))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(LIST_RESPONSE))
                .build();
        ServerServiceDefinition service = ServerServiceDefinition.builder("ps.Catalog")
                .addMethod(list, ServerCalls.asyncUnaryCall((request, observer) -> {
                    int requested = 0;
                    String token = "";
                    if (request.hasField(LIST_REQUEST.findFieldByName("page_request"))) {
                        DynamicMessage page =
                                (DynamicMessage) request.getField(LIST_REQUEST.findFieldByName("page_request"));
                        requested = (Integer) page.getField(PAGE_REQUEST.findFieldByName("page_size"));
                        token = (String) page.getField(PAGE_REQUEST.findFieldByName("page_token"));
                    }
                    List<String> sort = new ArrayList<>();
                    int sortCount = request.getRepeatedFieldCount(LIST_REQUEST.findFieldByName("sort_fields"));
                    for (int i = 0; i < sortCount; i++) {
                        DynamicMessage sf = (DynamicMessage)
                                request.getRepeatedField(LIST_REQUEST.findFieldByName("sort_fields"), i);
                        EnumValueDescriptor order =
                                (EnumValueDescriptor) sf.getField(SORT_FIELD.findFieldByName("sort_order"));
                        sort.add(sf.getField(SORT_FIELD.findFieldByName("field")) + ":" + order.getName());
                    }
                    CALLS.add(new Seen(sort, requested, token));

                    int start;
                    try {
                        start = offsetOf(token);
                    } catch (io.grpc.StatusRuntimeException e) {
                        observer.onError(e);
                        return;
                    }
                    int size = requested > 0 ? requested : 10;

                    // Item n is "item-n" named NAMES[n-1]; ordered by the first sort key, then id.
                    List<Integer> order = new ArrayList<>();
                    for (int n = 1; n <= TOTAL; n++) order.add(n);
                    if (!sort.isEmpty() && sort.get(0).startsWith("name:")) {
                        Comparator<Integer> byName = Comparator.comparing(n -> NAMES[n - 1]);
                        order.sort(sort.get(0).endsWith("DESC") ? byName.reversed() : byName);
                    }

                    int end = Math.min(start + size, TOTAL);
                    DynamicMessage.Builder reply = DynamicMessage.newBuilder(LIST_RESPONSE);
                    for (int i = start; i < end; i++) {
                        int n = order.get(i);
                        reply.addRepeatedField(
                                LIST_RESPONSE.findFieldByName("items"),
                                DynamicMessage.newBuilder(ITEM)
                                        .setField(ITEM.findFieldByName("id"), "item-" + n)
                                        .setField(ITEM.findFieldByName("name"), NAMES[n - 1])
                                        .build());
                    }
                    reply.setField(
                            LIST_RESPONSE.findFieldByName("page_info"),
                            DynamicMessage.newBuilder(PAGE_INFO)
                                    .setField(
                                            PAGE_INFO.findFieldByName("next_page_token"), end < TOTAL ? token(end) : "")
                                    .setField(PAGE_INFO.findFieldByName("total_count"), totalCountToReport)
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
