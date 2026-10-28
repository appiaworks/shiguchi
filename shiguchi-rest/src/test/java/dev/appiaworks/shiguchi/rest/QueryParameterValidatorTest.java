package dev.appiaworks.shiguchi.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.StructProto;
import com.google.rpc.BadRequest;
import io.grpc.Status;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD tests for query parameter validation: determining which parameters are unknown to the
 * request message, and constructing failure responses naming them.
 */
@DisplayName("Query parameter validation")
class QueryParameterValidatorTest {

    private static Descriptor bookDescriptor;

    @BeforeAll
    static void buildDescriptors() throws Exception {
        DescriptorProto author = DescriptorProto.newBuilder()
                .setName("Author")
                .addField(scalar("name", 1, FieldDescriptorProto.Type.TYPE_STRING))
                .build();

        DescriptorProto labelsEntry = DescriptorProto.newBuilder()
                .setName("LabelsEntry")
                .setOptions(com.google.protobuf.DescriptorProtos.MessageOptions.newBuilder()
                        .setMapEntry(true)
                        .build())
                .addField(scalar("key", 1, FieldDescriptorProto.Type.TYPE_STRING))
                .addField(scalar("value", 2, FieldDescriptorProto.Type.TYPE_STRING))
                .build();

        DescriptorProto book = DescriptorProto.newBuilder()
                .setName("Book")
                .addField(scalar("title", 1, FieldDescriptorProto.Type.TYPE_STRING))
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("display_name")
                        .setNumber(2)
                        .setType(FieldDescriptorProto.Type.TYPE_STRING)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .setJsonName("displayName")
                        .build())
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("author")
                        .setNumber(3)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName(".t.Author")
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("labels")
                        .setNumber(4)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName(".t.Book.LabelsEntry")
                        .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
                        .build())
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("filter")
                        .setNumber(5)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName(".google.protobuf.Struct")
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .addNestedType(labelsEntry)
                .build();

        FileDescriptor file = FileDescriptor.buildFrom(
                FileDescriptorProto.newBuilder()
                        .setName("validator-test.proto")
                        .setSyntax("proto3")
                        .setPackage("t")
                        .addDependency("google/protobuf/struct.proto")
                        .addMessageType(author)
                        .addMessageType(book)
                        .build(),
                new FileDescriptor[] {StructProto.getDescriptor()});

        bookDescriptor = file.findMessageTypeByName("Book");
    }

    @Test
    @DisplayName("GIVEN proto name 'title' WHEN checking unknown parameters SHOULD report none unknown")
    void given_protoName_when_checking_should_recogniseIt() {
        List<String> unknown = QueryParameterValidator.unknownParameters(bookDescriptor, List.of("title"), Set.of());

        assertThat(unknown).isEmpty();
    }

    @Test
    @DisplayName("GIVEN JSON name 'displayName' WHEN checking unknown parameters SHOULD report none unknown")
    void given_jsonName_when_checking_should_recogniseIt() {
        List<String> unknown =
                QueryParameterValidator.unknownParameters(bookDescriptor, List.of("displayName"), Set.of());

        assertThat(unknown).isEmpty();
    }

    @Test
    @DisplayName("GIVEN proto name 'display_name' WHEN checking unknown parameters SHOULD report none unknown")
    void given_snakeCaseProtoName_when_checking_should_recogniseIt() {
        List<String> unknown =
                QueryParameterValidator.unknownParameters(bookDescriptor, List.of("display_name"), Set.of());

        assertThat(unknown).isEmpty();
    }

    @Test
    @DisplayName("GIVEN dotted nested 'author.name' WHEN checking unknown parameters SHOULD report none unknown")
    void given_dottedNestedFieldName_when_checking_should_recogniseIt() {
        List<String> unknown =
                QueryParameterValidator.unknownParameters(bookDescriptor, List.of("author.name"), Set.of());

        assertThat(unknown).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN dotted path 'author.nope' where nope is unknown WHEN checking unknown parameters SHOULD report it unknown")
    void given_dottedPathWithUnknownLeaf_when_checking_should_reportUnknown() {
        List<String> unknown =
                QueryParameterValidator.unknownParameters(bookDescriptor, List.of("author.nope"), Set.of());

        assertThat(unknown).containsExactly("author.nope");
    }

    @Test
    @DisplayName(
            "GIVEN 'title.x' descending into a scalar field WHEN checking unknown parameters SHOULD report it unknown")
    void given_dottedPathDescendingIntoScalar_when_checking_should_reportUnknown() {
        List<String> unknown = QueryParameterValidator.unknownParameters(bookDescriptor, List.of("title.x"), Set.of());

        assertThat(unknown).containsExactly("title.x");
    }

    @Test
    @DisplayName("GIVEN map field 'labels.anything' WHEN checking unknown parameters SHOULD report none unknown")
    void given_mapFieldWithAnyKey_when_checking_should_reportNoneUnknown() {
        List<String> unknown = QueryParameterValidator.unknownParameters(
                bookDescriptor, List.of("labels.anything", "labels.x", "labels.y"), Set.of());

        assertThat(unknown).isEmpty();
    }

    @Test
    @DisplayName("GIVEN Struct field 'filter.any.deep' WHEN checking unknown parameters SHOULD report none unknown")
    void given_structFieldWithDeepPath_when_checking_should_reportNoneUnknown() {
        List<String> unknown = QueryParameterValidator.unknownParameters(
                bookDescriptor, List.of("filter.any", "filter.any.deep"), Set.of());

        assertThat(unknown).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN gateway parameters pageSize, pageToken, first, after, sort, fields WHEN checking unknown parameters SHOULD report none unknown")
    void given_gatewayParameters_when_checking_should_recogniseThem() {
        List<String> unknown = QueryParameterValidator.unknownParameters(
                bookDescriptor, List.of("pageSize", "pageToken", "first", "after", "sort", "fields"), Set.of());

        assertThat(unknown).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN parameter 'access_token' in the ignored set WHEN checking unknown parameters SHOULD not report it")
    void given_ignoredParameter_when_checking_should_notReportIt() {
        List<String> unknown = QueryParameterValidator.unknownParameters(
                bookDescriptor, List.of("access_token"), Set.of("access_token"));

        assertThat(unknown).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN multiple unknowns 'txet', 'bogus', 'nope' WHEN checking unknown parameters SHOULD preserve input order")
    void given_multipleUnknowns_when_checking_should_preserveOrder() {
        List<String> unknown = QueryParameterValidator.unknownParameters(
                bookDescriptor, Arrays.asList("txet", "title", "bogus", "nope"), Set.of());

        assertThat(unknown).containsExactly("txet", "bogus", "nope");
    }

    @Test
    @DisplayName("GIVEN empty segment 'author.' WHEN checking unknown parameters SHOULD report it unknown")
    void given_emptySegment_when_checking_should_reportUnknown() {
        List<String> unknown = QueryParameterValidator.unknownParameters(bookDescriptor, List.of("author."), Set.of());

        assertThat(unknown).containsExactly("author.");
    }

    @Test
    @DisplayName(
            "GIVEN single unknown 'txet' WHEN constructing failure SHOULD return status INVALID_ARGUMENT with singular message")
    void given_singleUnknown_when_failure_should_useSingularMessage() {
        QueryParameterValidator.BadRequestFailure result =
                QueryParameterValidator.failure(bookDescriptor, List.of("txet"));

        assertThat(result.status().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(result.status().getDescription()).isEqualTo("Unknown query parameter: txet");
    }

    @Test
    @DisplayName(
            "GIVEN multiple unknowns 'txet', 'bogus' WHEN constructing failure SHOULD return status INVALID_ARGUMENT with plural message")
    void given_multipleUnknowns_when_failure_should_usePluralMessage() {
        QueryParameterValidator.BadRequestFailure result =
                QueryParameterValidator.failure(bookDescriptor, List.of("txet", "bogus"));

        assertThat(result.status().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(result.status().getDescription()).isEqualTo("Unknown query parameters: txet, bogus");
    }

    @Test
    @DisplayName("GIVEN failures for unknowns WHEN unpacking detail Any SHOULD extract BadRequest with violations")
    void given_failure_when_unpackingDetail_should_extractBadRequest() throws Exception {
        QueryParameterValidator.BadRequestFailure result =
                QueryParameterValidator.failure(bookDescriptor, List.of("txet", "bogus"));

        BadRequest badRequest = result.detail().unpack(BadRequest.class);

        assertThat(badRequest.getFieldViolationsList())
                .hasSize(2)
                .anySatisfy(violation -> assertThat(violation.getField()).isEqualTo("txet"))
                .anySatisfy(violation -> assertThat(violation.getField()).isEqualTo("bogus"));
    }

    @Test
    @DisplayName("GIVEN failures with violations WHEN checking reason SHOULD set UNKNOWN_QUERY_PARAMETER")
    void given_violations_when_checking_should_setReason() throws Exception {
        QueryParameterValidator.BadRequestFailure result =
                QueryParameterValidator.failure(bookDescriptor, List.of("txet"));

        BadRequest badRequest = result.detail().unpack(BadRequest.class);

        assertThat(badRequest.getFieldViolations(0).getReason())
                .isEqualTo(QueryParameterValidator.UNKNOWN_QUERY_PARAMETER);
    }

    @Test
    @DisplayName("GIVEN failures with violations WHEN checking description SHOULD name the message type")
    void given_violations_when_checking_should_nameMessageType() throws Exception {
        QueryParameterValidator.BadRequestFailure result =
                QueryParameterValidator.failure(bookDescriptor, List.of("txet"));

        BadRequest badRequest = result.detail().unpack(BadRequest.class);

        assertThat(badRequest.getFieldViolations(0).getDescription()).contains("t.Book");
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
