package dev.appiaworks.shiguchi.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD tests for the request-merge logic that feeds SerdeSelector.deserializeFromMap on the
 * REST hot path: body/query/path precedence and convention-based pagination/sort injection.
 */
class GrpcRequestBuilderTest {

    private static Descriptor plainInput;
    private static Descriptor pagedInput;
    private static Descriptor inputWithFieldMask;

    private final GrpcRequestBuilder builder = new GrpcRequestBuilder();

    @BeforeAll
    static void buildDescriptors() throws Exception {
        DescriptorProto pageRequest = DescriptorProto.newBuilder()
                .setName("PageRequest")
                .addField(scalar("page_size", 1, FieldDescriptorProto.Type.TYPE_INT32))
                .addField(scalar("page_token", 2, FieldDescriptorProto.Type.TYPE_STRING))
                .build();
        DescriptorProto sortField = DescriptorProto.newBuilder()
                .setName("SortField")
                .addField(scalar("field", 1, FieldDescriptorProto.Type.TYPE_STRING))
                .addField(scalar("sort_order", 2, FieldDescriptorProto.Type.TYPE_STRING))
                .build();
        DescriptorProto plain = DescriptorProto.newBuilder()
                .setName("PlainInput")
                .addField(scalar("id", 1, FieldDescriptorProto.Type.TYPE_STRING))
                .addField(scalar("name", 2, FieldDescriptorProto.Type.TYPE_STRING))
                .build();
        DescriptorProto paged = DescriptorProto.newBuilder()
                .setName("PagedInput")
                .addField(scalar("id", 1, FieldDescriptorProto.Type.TYPE_STRING))
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("page_request")
                        .setNumber(2)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName(".t.PageRequest")
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("sort_fields")
                        .setNumber(3)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName(".t.SortField")
                        .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED))
                .build();

        DescriptorProto fieldMask = DescriptorProto.newBuilder()
                .setName("FieldMask")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("paths")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_STRING)
                        .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED))
                .build();
        DescriptorProto maskedInput = DescriptorProto.newBuilder()
                .setName("MaskedInput")
                .addField(scalar("id", 1, FieldDescriptorProto.Type.TYPE_STRING))
                .addField(scalar("title", 2, FieldDescriptorProto.Type.TYPE_STRING))
                .addField(scalar("author_id", 3, FieldDescriptorProto.Type.TYPE_STRING))
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("read_mask")
                        .setNumber(4)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName(".t.FieldMask")
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                .build();

        FileDescriptor file = FileDescriptor.buildFrom(
                FileDescriptorProto.newBuilder()
                        .setName("builder-test.proto")
                        .setSyntax("proto3")
                        .setPackage("t")
                        .addMessageType(pageRequest)
                        .addMessageType(sortField)
                        .addMessageType(plain)
                        .addMessageType(paged)
                        .addMessageType(fieldMask)
                        .addMessageType(maskedInput)
                        .build(),
                new FileDescriptor[0]);
        plainInput = file.findMessageTypeByName("PlainInput");
        pagedInput = file.findMessageTypeByName("PagedInput");
        inputWithFieldMask = file.findMessageTypeByName("MaskedInput");
    }

    @Test
    @DisplayName(
            "Given body, query param, and path variable for the same field, when buildRequestMap, should apply path > query > body precedence")
    void given_overlappingSources_when_buildRequestMap_should_applyPrecedence() throws Exception {
        Map<String, Object> result = builder.buildRequestMap(
                plainInput,
                Map.of("id", "from-path"),
                Map.of("id", "from-query", "name", "query-name"),
                "{\"id\":\"from-body\",\"name\":\"body-name\"}");

        assertThat(result).containsEntry("id", "from-path").containsEntry("name", "query-name");
    }

    @Test
    @DisplayName("Given only a JSON body, when buildRequestMap, should return the parsed body untouched")
    void given_onlyBody_when_buildRequestMap_should_returnParsedBody() throws Exception {
        Map<String, Object> result =
                builder.buildRequestMap(plainInput, Map.of(), Map.of(), "{\"id\":\"a\",\"name\":\"b\"}");

        assertThat(result).containsExactlyInAnyOrderEntriesOf(Map.of("id", "a", "name", "b"));
    }

    @Test
    @DisplayName(
            "Given pageSize and pageToken query params on a paged input, when buildRequestMap, should inject pageRequest and drop top-level params")
    void given_paginationParams_when_buildRequestMap_should_injectPageRequest() throws Exception {
        Map<String, Object> result =
                builder.buildRequestMap(pagedInput, Map.of(), Map.of("pageSize", "25", "pageToken", "tok-1"), null);

        assertThat(result).doesNotContainKeys("pageSize", "pageToken");
        assertThat(result.get("pageRequest")).isEqualTo(Map.of("pageSize", 25, "pageToken", "tok-1"));
    }

    @Test
    @DisplayName("Given a Relay after-cursor, when buildRequestMap, should base64-decode it into pageRequest.pageToken")
    void given_relayAfterCursor_when_buildRequestMap_should_unpackIntoPageToken() throws Exception {
        String cursor = Base64.getEncoder().encodeToString("real-token:42".getBytes(StandardCharsets.UTF_8));

        Map<String, Object> result = builder.buildRequestMap(pagedInput, Map.of(), Map.of("after", cursor), null);

        assertThat(result).doesNotContainKey("after");
        assertThat(result.get("pageRequest")).isEqualTo(Map.of("pageToken", "real-token"));
    }

    @Test
    @DisplayName(
            "Given a sort query param on an input with sort_fields, when buildRequestMap, should expand it into sortFields entries")
    void given_sortParam_when_buildRequestMap_should_expandSortFields() throws Exception {
        Map<String, Object> result =
                builder.buildRequestMap(pagedInput, Map.of(), Map.of("sort", "name:asc,createdAt:desc"), null);

        assertThat(result.get("sortFields"))
                .isEqualTo(java.util.List.of(
                        Map.of("field", "name", "sortOrder", "SORT_ORDER_ASC"),
                        Map.of("field", "createdAt", "sortOrder", "SORT_ORDER_DESC")));
    }

    @Test
    @DisplayName(
            "Given pagination params on an input without page_request, when buildRequestMap, should not leak them into the request")
    void given_paginationParamsWithoutPageRequestField_when_buildRequestMap_should_dropThem() throws Exception {
        Map<String, Object> result =
                builder.buildRequestMap(plainInput, Map.of(), Map.of("pageSize", "10", "id", "x"), null);

        assertThat(result).containsOnlyKeys("id");
    }

    @Test
    @DisplayName("Given buildRequestJson, when called, should produce the JSON form of buildRequestMap")
    void given_buildRequestJson_when_called_should_matchMapVariant() throws Exception {
        String json = builder.buildRequestJson(plainInput, Map.of("id", "x"), Map.of(), null);

        assertThat(json).isEqualTo("{\"id\":\"x\"}");
    }

    @Test
    @DisplayName(
            "Given fields query param on a request whose input descriptor has a read_mask field, when buildRequestMap, should inject readMask with normalised paths")
    void given_fieldsParamWithReadMaskField_when_buildRequestMap_should_injectReadMask() throws Exception {
        Map<String, Object> result =
                builder.buildRequestMap(inputWithFieldMask, Map.of(), Map.of("fields", "id,title"), null);

        assertThat(result).containsEntry("readMask", Map.of("paths", List.of("id", "title")));
    }

    @Test
    @DisplayName(
            "Given camelCase field name in fields param, when buildRequestMap, should normalise to snake_case in readMask paths")
    void given_camelCaseFieldName_when_buildRequestMap_should_normaliseToSnakeCase() throws Exception {
        Map<String, Object> result =
                builder.buildRequestMap(inputWithFieldMask, Map.of(), Map.of("fields", "authorId"), null);

        assertThat(result).containsEntry("readMask", Map.of("paths", List.of("author_id")));
    }

    @Test
    @DisplayName("Given snake_case field name in fields param, when buildRequestMap, should pass through unchanged")
    void given_snakeCaseFieldName_when_buildRequestMap_should_passThrough() throws Exception {
        Map<String, Object> result =
                builder.buildRequestMap(inputWithFieldMask, Map.of(), Map.of("fields", "author_id"), null);

        assertThat(result).containsEntry("readMask", Map.of("paths", List.of("author_id")));
    }

    @Test
    @DisplayName(
            "Given dotted nested path with camelCase segments in fields param, when buildRequestMap, should normalise segment-wise")
    void given_dottedNestedPathWithCamelCase_when_buildRequestMap_should_normaliseSegmentWise() throws Exception {
        Map<String, Object> result =
                builder.buildRequestMap(inputWithFieldMask, Map.of(), Map.of("fields", "author.displayName"), null);

        assertThat(result).containsEntry("readMask", Map.of("paths", List.of("author.display_name")));
    }

    @Test
    @DisplayName(
            "Given fields param on a request without read_mask field, when buildRequestMap, should drop fields and not leak it")
    void given_fieldsParamWithoutReadMaskField_when_buildRequestMap_should_dropFields() throws Exception {
        Map<String, Object> result =
                builder.buildRequestMap(plainInput, Map.of(), Map.of("fields", "id", "name", "test"), null);

        assertThat(result).containsOnlyKeys("name").containsEntry("name", "test");
    }

    @Test
    @DisplayName("Given absent fields param, when buildRequestMap, should not inject readMask key")
    void given_abscentFieldsParam_when_buildRequestMap_should_notInjectReadMask() throws Exception {
        Map<String, Object> result = builder.buildRequestMap(inputWithFieldMask, Map.of(), Map.of(), null);

        assertThat(result).doesNotContainKey("readMask");
    }

    @Test
    @DisplayName("Given blank fields param, when buildRequestMap, should not inject readMask key")
    void given_blankFieldsParam_when_buildRequestMap_should_notInjectReadMask() throws Exception {
        Map<String, Object> result =
                builder.buildRequestMap(inputWithFieldMask, Map.of(), Map.of("fields", "  "), null);

        assertThat(result).doesNotContainKey("readMask");
    }

    @Test
    @DisplayName("Given duplicate paths in fields param, when buildRequestMap, should deduplicate in readMask paths")
    void given_duplicatePathsInFields_when_buildRequestMap_should_deduplicate() throws Exception {
        Map<String, Object> result =
                builder.buildRequestMap(inputWithFieldMask, Map.of(), Map.of("fields", "id,id,title,id"), null);

        assertThat(result).containsEntry("readMask", Map.of("paths", List.of("id", "title")));
    }

    @Test
    @DisplayName(
            "Given malformed paths like double dots and trailing dots in fields param, when buildRequestMap, should drop those entries")
    void given_malformedPaths_when_buildRequestMap_should_dropThem() throws Exception {
        Map<String, Object> result =
                builder.buildRequestMap(inputWithFieldMask, Map.of(), Map.of("fields", "id,a..b,c.,title"), null);

        assertThat(result).containsEntry("readMask", Map.of("paths", List.of("id", "title")));
    }

    @Test
    @DisplayName(
            "Given fields param with whitespace around entries, when buildRequestMap, should tolerate and trim whitespace")
    void given_fieldsParamWithWhitespace_when_buildRequestMap_should_tolerateAndTrim() throws Exception {
        Map<String, Object> result = builder.buildRequestMap(
                inputWithFieldMask, Map.of(), Map.of("fields", " id , title , author_id "), null);

        assertThat(result).containsEntry("readMask", Map.of("paths", List.of("id", "title", "author_id")));
    }

    private static FieldDescriptorProto scalar(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .build();
    }
}
