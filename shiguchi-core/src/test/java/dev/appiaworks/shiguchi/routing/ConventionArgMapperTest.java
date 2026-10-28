package dev.appiaworks.shiguchi.routing;

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
 * BDD tests for ConventionArgMapper: normalisation of loose paging/sorting arguments from REST
 * query params and GraphQL SDL args onto the nested proto fields the conventions define.
 */
class ConventionArgMapperTest {

    private static Descriptor inputWithoutPaging;
    private static Descriptor inputWithPaging;

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

        DescriptorProto plainInput = DescriptorProto.newBuilder()
                .setName("PlainInput")
                .addField(scalar("id", 1, FieldDescriptorProto.Type.TYPE_STRING))
                .addField(scalar("name", 2, FieldDescriptorProto.Type.TYPE_STRING))
                .build();

        DescriptorProto pagedInput = DescriptorProto.newBuilder()
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

        FileDescriptor file = FileDescriptor.buildFrom(
                FileDescriptorProto.newBuilder()
                        .setName("convention-mapper-test.proto")
                        .setSyntax("proto3")
                        .setPackage("t")
                        .addMessageType(pageRequest)
                        .addMessageType(sortField)
                        .addMessageType(plainInput)
                        .addMessageType(pagedInput)
                        .build(),
                new FileDescriptor[0]);

        inputWithoutPaging = file.findMessageTypeByName("PlainInput");
        inputWithPaging = file.findMessageTypeByName("PagedInput");
    }

    // ===== applyPaging tests =====

    @Test
    @DisplayName(
            "Given GraphQL first argument on a descriptor WITH page_request, when applyPaging, should nest into pageRequest.pageSize")
    void given_graphqlFirst_when_applyPaging_should_nestPageSize() {
        Map<String, Object> args = Map.of("first", 25, "id", "test-id");

        Map<String, Object> result = ConventionArgMapper.applyPaging(args, inputWithPaging);

        assertThat(result)
                .containsEntry("pageRequest", Map.of("pageSize", 25))
                .containsEntry("id", "test-id")
                .doesNotContainKey("first");
    }

    @Test
    @DisplayName(
            "Given REST pageSize as a string (from query params) on a descriptor WITH page_request, when applyPaging, should parse to Integer")
    void given_restPageSizeString_when_applyPaging_should_parseToInteger() {
        Map<String, Object> args = Map.of("pageSize", "25", "name", "test");

        Map<String, Object> result = ConventionArgMapper.applyPaging(args, inputWithPaging);

        assertThat(result)
                .containsEntry("pageRequest", Map.of("pageSize", 25))
                .containsEntry("name", "test")
                .doesNotContainKey("pageSize");
    }

    @Test
    @DisplayName(
            "Given pageToken argument on a descriptor WITH page_request, when applyPaging, should nest into pageRequest.pageToken")
    void given_pageToken_when_applyPaging_should_nestPageToken() {
        Map<String, Object> args = Map.of("pageToken", "abc-token", "id", "x");

        Map<String, Object> result = ConventionArgMapper.applyPaging(args, inputWithPaging);

        assertThat(result)
                .containsEntry("pageRequest", Map.of("pageToken", "abc-token"))
                .containsEntry("id", "x")
                .doesNotContainKey("pageToken");
    }

    @Test
    @DisplayName(
            "Given base64-encoded Relay cursor in after argument, when applyPaging, should decode and extract page token before colon")
    void given_relayAfterCursor_when_applyPaging_should_unpackCursor() {
        String cursor = Base64.getEncoder().encodeToString("tok123:5".getBytes(StandardCharsets.UTF_8));
        Map<String, Object> args = Map.of("after", cursor, "id", "x");

        Map<String, Object> result = ConventionArgMapper.applyPaging(args, inputWithPaging);

        assertThat(result)
                .containsEntry("pageRequest", Map.of("pageToken", "tok123"))
                .containsEntry("id", "x")
                .doesNotContainKey("after");
    }

    @Test
    @DisplayName("Given non-base64 after value, when applyPaging, should fall back to raw token usage")
    void given_invalidBase64After_when_applyPaging_should_fallbackToRawToken() {
        Map<String, Object> args = Map.of("after", "not-base64-at-all", "id", "x");

        Map<String, Object> result = ConventionArgMapper.applyPaging(args, inputWithPaging);

        assertThat(result)
                .containsEntry("pageRequest", Map.of("pageToken", "not-base64-at-all"))
                .containsEntry("id", "x")
                .doesNotContainKey("after");
    }

    @Test
    @DisplayName("Given both after cursor and explicit pageToken, when applyPaging, should prefer after cursor")
    void given_afterAndPageToken_when_applyPaging_should_preferAfter() {
        String cursor = Base64.getEncoder().encodeToString("relay:123".getBytes(StandardCharsets.UTF_8));
        Map<String, Object> args = Map.of("after", cursor, "pageToken", "explicit-token");

        Map<String, Object> result = ConventionArgMapper.applyPaging(args, inputWithPaging);

        assertThat(result)
                .containsEntry("pageRequest", Map.of("pageToken", "relay"))
                .doesNotContainKey("after")
                .doesNotContainKey("pageToken");
    }

    @Test
    @DisplayName(
            "Given paging arguments on a descriptor WITHOUT page_request, when applyPaging, should strip aliases but not create pageRequest")
    void given_pagingArgsWithoutPageRequestField_when_applyPaging_should_stripAliasesOnly() {
        Map<String, Object> args = Map.of("first", 25, "pageToken", "tok-1", "id", "x");

        Map<String, Object> result = ConventionArgMapper.applyPaging(args, inputWithPaging);

        // Need to use inputWithoutPaging to test the case without page_request field
        Map<String, Object> result2 = ConventionArgMapper.applyPaging(args, inputWithoutPaging);

        assertThat(result2).containsOnlyKeys("id").doesNotContainKeys("first", "pageToken", "pageRequest");
    }

    @Test
    @DisplayName("Given no paging arguments, when applyPaging, should return the map unchanged")
    void given_noPagingArgs_when_applyPaging_should_returnUnchanged() {
        Map<String, Object> args = Map.of("id", "x", "name", "y");

        Map<String, Object> result = ConventionArgMapper.applyPaging(args, inputWithPaging);

        assertThat(result).isEqualTo(args);
    }

    // ===== applySorting tests =====

    @Test
    @DisplayName(
            "Given REST sort string form with explicit directions on a descriptor WITH sort_fields, when applySorting, should parse and expand")
    void given_restSortString_when_applySorting_should_parseAndExpand() {
        Map<String, Object> args = Map.of("sort", "author:asc,year:desc", "id", "test-id");

        Map<String, Object> result = ConventionArgMapper.applySorting(args, inputWithPaging);

        assertThat(result)
                .containsEntry(
                        "sortFields",
                        List.of(
                                Map.of("field", "author", "sortOrder", "SORT_ORDER_ASC"),
                                Map.of("field", "year", "sortOrder", "SORT_ORDER_DESC")))
                .containsEntry("id", "test-id")
                .doesNotContainKey("sort");
    }

    @Test
    @DisplayName(
            "Given REST sort string with fields missing direction suffix, when applySorting, should default to ascending")
    void given_sortStringWithoutDirection_when_applySorting_should_defaultToAscending() {
        Map<String, Object> args = Map.of("sort", "author,year");

        Map<String, Object> result = ConventionArgMapper.applySorting(args, inputWithPaging);

        assertThat(result)
                .containsEntry(
                        "sortFields",
                        List.of(
                                Map.of("field", "author", "sortOrder", "SORT_ORDER_ASC"),
                                Map.of("field", "year", "sortOrder", "SORT_ORDER_ASC")))
                .doesNotContainKey("sort");
    }

    @Test
    @DisplayName(
            "Given GraphQL list form with sortOrder as SORT_ORDER_DESC enum, when applySorting, should pass through as-is")
    void given_graphqlListWithEnumSortOrder_when_applySorting_should_passThrough() {
        Map<String, Object> args = Map.of("sort", List.of(Map.of("field", "year", "sortOrder", "SORT_ORDER_DESC")));

        Map<String, Object> result = ConventionArgMapper.applySorting(args, inputWithPaging);

        assertThat(result)
                .containsEntry("sortFields", List.of(Map.of("field", "year", "sortOrder", "SORT_ORDER_DESC")))
                .doesNotContainKey("sort");
    }

    @Test
    @DisplayName(
            "Given GraphQL list form with bare 'desc' direction string, when applySorting, should normalise to SORT_ORDER_DESC")
    void given_graphqlListWithBareDescDirection_when_applySorting_should_normalise() {
        Map<String, Object> args = Map.of("sort", List.of(Map.of("field", "author", "sortOrder", "desc")));

        Map<String, Object> result = ConventionArgMapper.applySorting(args, inputWithPaging);

        assertThat(result)
                .containsEntry("sortFields", List.of(Map.of("field", "author", "sortOrder", "SORT_ORDER_DESC")))
                .doesNotContainKey("sort");
    }

    @Test
    @DisplayName("Given GraphQL sort entry with missing field, when applySorting, should skip that entry")
    void given_graphqlEntryWithMissingField_when_applySorting_should_skipEntry() {
        Map<String, Object> args = Map.of(
                "sort",
                List.of(
                        Map.of("sortOrder", "SORT_ORDER_ASC"),
                        Map.of("field", "year", "sortOrder", "SORT_ORDER_DESC")));

        Map<String, Object> result = ConventionArgMapper.applySorting(args, inputWithPaging);

        assertThat(result)
                .containsEntry("sortFields", List.of(Map.of("field", "year", "sortOrder", "SORT_ORDER_DESC")))
                .doesNotContainKey("sort");
    }

    @Test
    @DisplayName(
            "Given sort arguments on a descriptor WITHOUT sort_fields, when applySorting, should strip sort but not create sortFields")
    void given_sortArgWithoutSortFieldsField_when_applySorting_should_stripSortOnly() {
        Map<String, Object> args = Map.of("sort", "author:asc", "id", "x");

        Map<String, Object> result = ConventionArgMapper.applySorting(args, inputWithoutPaging);

        assertThat(result).containsOnlyKeys("id").doesNotContainKeys("sort", "sortFields");
    }

    @Test
    @DisplayName("Given no sort argument, when applySorting, should return the map unchanged")
    void given_noSortArg_when_applySorting_should_returnUnchanged() {
        Map<String, Object> args = Map.of("id", "x", "name", "y");

        Map<String, Object> result = ConventionArgMapper.applySorting(args, inputWithPaging);

        assertThat(result).isEqualTo(args);
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
