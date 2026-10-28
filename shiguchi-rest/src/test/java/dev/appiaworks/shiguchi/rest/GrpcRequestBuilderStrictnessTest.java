package dev.appiaworks.shiguchi.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.rpc.BadRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD tests for opt-in request strictness: reject-conflicting-values and case-insensitive-enums.
 * These features are controlled by the constructor parameters of GrpcRequestBuilder and apply
 * in the overlay() method when merging body, query parameters, and path variables into one map.
 */
@DisplayName("GrpcRequestBuilder: opt-in request strictness")
class GrpcRequestBuilderStrictnessTest {

    private static Descriptor bookDescriptor;
    private static Descriptor noteDescriptor;

    private final GrpcRequestBuilder strictBuilder = new GrpcRequestBuilder(true, false);
    private final GrpcRequestBuilder enumBuilder = new GrpcRequestBuilder(false, true);
    private final GrpcRequestBuilder strictEnumBuilder = new GrpcRequestBuilder(true, true);
    private final GrpcRequestBuilder defaultBuilder = new GrpcRequestBuilder();

    @BeforeAll
    static void buildDescriptors() throws Exception {
        // Color enum for reuse in multiple message types
        EnumDescriptorProto colorEnum = EnumDescriptorProto.newBuilder()
                .setName("Color")
                .addValue(enumValue("COLOR_UNSPECIFIED", 0))
                .addValue(enumValue("RED", 1))
                .addValue(enumValue("DARK_BLUE", 2))
                .build();

        // Author message with nested color enum
        DescriptorProto author = DescriptorProto.newBuilder()
                .setName("Author")
                .addField(scalar("name", 1))
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("color")
                        .setNumber(2)
                        .setType(FieldDescriptorProto.Type.TYPE_ENUM)
                        .setTypeName(".t.Color")
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .build();

        // Book message with various field types
        DescriptorProto book = DescriptorProto.newBuilder()
                .setName("Book")
                .addField(scalar("name", 1))
                .addField(scalar("display_name", 2))
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("pages")
                        .setNumber(3)
                        .setType(FieldDescriptorProto.Type.TYPE_INT32)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("color")
                        .setNumber(4)
                        .setType(FieldDescriptorProto.Type.TYPE_ENUM)
                        .setTypeName(".t.Color")
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("tags")
                        .setNumber(5)
                        .setType(FieldDescriptorProto.Type.TYPE_ENUM)
                        .setTypeName(".t.Color")
                        .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
                        .build())
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("author")
                        .setNumber(6)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName(".t.Author")
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("coauthors")
                        .setNumber(7)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName(".t.Author")
                        .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
                        .build())
                .build();

        // Note message for end-to-end tests
        EnumDescriptorProto moodEnum = EnumDescriptorProto.newBuilder()
                .setName("Mood")
                .addValue(enumValue("MOOD_UNSPECIFIED", 0))
                .addValue(enumValue("HAPPY", 1))
                .build();

        DescriptorProto note = DescriptorProto.newBuilder()
                .setName("Note")
                .addField(scalar("id", 1))
                .addField(scalar("text", 2))
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("mood")
                        .setNumber(3)
                        .setType(FieldDescriptorProto.Type.TYPE_ENUM)
                        .setTypeName(".t.Mood")
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .build();

        FileDescriptor fd = FileDescriptor.buildFrom(
                FileDescriptorProto.newBuilder()
                        .setName("strictness-test.proto")
                        .setPackage("t")
                        .setSyntax("proto3")
                        .addEnumType(colorEnum)
                        .addEnumType(moodEnum)
                        .addMessageType(author)
                        .addMessageType(book)
                        .addMessageType(note)
                        .build(),
                new FileDescriptor[0]);

        bookDescriptor = fd.findMessageTypeByName("Book");
        noteDescriptor = fd.findMessageTypeByName("Note");
    }

    private static com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto enumValue(String name, int number) {
        return com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .build();
    }

    private static FieldDescriptorProto scalar(String name, int number) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(FieldDescriptorProto.Type.TYPE_STRING)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .build();
    }

    // -------- Conflict detection (rejectConflictingValues=true)

    @Test
    @DisplayName("GIVEN path {name:\"books/7\"} and body {\"name\":\"books/9\"} WITH rejectConflictingValues "
            + "WHEN buildRequestMap SHOULD throw StatusRuntimeException with INVALID_ARGUMENT and BINDING_CONFLICT")
    void given_conflictingPathAndBodyStrings_when_strict_should_throwConflict() throws Exception {
        assertThatThrownBy(() -> strictBuilder.buildRequestMap(
                        bookDescriptor, Map.of("name", "books/7"), Map.of(), "{\"name\":\"books/9\"}"))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> {
                    StatusRuntimeException sre = (StatusRuntimeException) e;
                    assertThat(sre.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
                    BadRequest detail = StatusProto.fromThrowable(sre).getDetailsList().stream()
                            .filter(a -> a.is(BadRequest.class))
                            .map(a -> {
                                try {
                                    return a.unpack(BadRequest.class);
                                } catch (Exception ex) {
                                    throw new RuntimeException(ex);
                                }
                            })
                            .findFirst()
                            .orElseThrow();
                    assertThat(detail.getFieldViolationsList())
                            .hasSize(1)
                            .satisfies(violations -> assertThat(violations.get(0))
                                    .satisfies(v -> {
                                        assertThat(v.getField()).isEqualTo("name");
                                        assertThat(v.getReason()).isEqualTo(RequestMapChecks.BINDING_CONFLICT);
                                    }));
                });
    }

    @Test
    @DisplayName("GIVEN path {name:\"books/7\"} and body {\"name\":\"books/7\"} WITH rejectConflictingValues "
            + "WHEN buildRequestMap SHOULD succeed with name=\"books/7\"")
    void given_sameValueInPathAndBody_when_strict_should_notConflict() throws Exception {
        Map<String, Object> result = strictBuilder.buildRequestMap(
                bookDescriptor, Map.of("name", "books/7"), Map.of(), "{\"name\":\"books/7\"}");

        assertThat(result).containsEntry("name", "books/7");
    }

    @Test
    @DisplayName("GIVEN query {pages:\"7\"} and body {\"pages\":7} WITH rejectConflictingValues "
            + "WHEN buildRequestMap SHOULD succeed (numeric equality)")
    void given_numericEqualityQueryAndBody_when_strict_should_notConflict() throws Exception {
        Map<String, Object> result =
                strictBuilder.buildRequestMap(bookDescriptor, Map.of(), Map.of("pages", "7"), "{\"pages\":7}");

        assertThat(result).containsEntry("pages", "7");
    }

    @Test
    @DisplayName("GIVEN query {displayName:\"X\"} and body {\"display_name\":\"Y\"} WITH rejectConflictingValues "
            + "WHEN buildRequestMap SHOULD throw (spelling-aware field resolution)")
    void given_conflictingQueryAndBodySnakeCase_when_strict_should_throwConflict() throws Exception {
        assertThatThrownBy(() -> strictBuilder.buildRequestMap(
                        bookDescriptor, Map.of(), Map.of("displayName", "X"), "{\"display_name\":\"Y\"}"))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> {
                    StatusRuntimeException sre = (StatusRuntimeException) e;
                    assertThat(sre.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
                });
    }

    @Test
    @DisplayName("GIVEN query {name:\"a\"} and path {name:\"b\"} WITH rejectConflictingValues "
            + "WHEN buildRequestMap SHOULD throw BINDING_CONFLICT")
    void given_conflictingQueryAndPath_when_strict_should_throwConflict() throws Exception {
        assertThatThrownBy(() ->
                        strictBuilder.buildRequestMap(bookDescriptor, Map.of("name", "b"), Map.of("name", "a"), null))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> {
                    StatusRuntimeException sre = (StatusRuntimeException) e;
                    assertThat(sre.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
                });
    }

    @Test
    @DisplayName(
            "GIVEN nested conflict author.name path={\"author.name\":\"Bob\"} vs body {\"author\":{\"name\":\"Ann\"}} "
                    + "WITH rejectConflictingValues WHEN buildRequestMap SHOULD throw with field \"author.name\"")
    void given_conflictingNestedField_when_strict_should_throwWithDottedField() throws Exception {
        assertThatThrownBy(() -> strictBuilder.buildRequestMap(
                        bookDescriptor, Map.of("author.name", "Bob"), Map.of(), "{\"author\":{\"name\":\"Ann\"}}"))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> {
                    StatusRuntimeException sre = (StatusRuntimeException) e;
                    assertThat(sre.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
                    BadRequest detail = StatusProto.fromThrowable(sre).getDetailsList().stream()
                            .filter(a -> a.is(BadRequest.class))
                            .map(a -> {
                                try {
                                    return a.unpack(BadRequest.class);
                                } catch (Exception ex) {
                                    throw new RuntimeException(ex);
                                }
                            })
                            .findFirst()
                            .orElseThrow();
                    assertThat(detail.getFieldViolationsList())
                            .satisfies(violations ->
                                    assertThat(violations.get(0).getField()).isEqualTo("author.name"));
                });
    }

    @Test
    @DisplayName("GIVEN path {name:\"books/7\"} and body {\"name\":\"books/9\"} WITH default builder "
            + "WHEN buildRequestMap SHOULD NOT throw (path wins historically)")
    void given_conflictingInputs_when_default_should_notThrow() throws Exception {
        Map<String, Object> result = defaultBuilder.buildRequestMap(
                bookDescriptor, Map.of("name", "books/7"), Map.of(), "{\"name\":\"books/9\"}");

        assertThat(result).containsEntry("name", "books/7");
    }

    // -------- Enum case normalization (caseInsensitiveEnums=true)

    @Test
    @DisplayName("GIVEN query {color:\"red\"} WITH caseInsensitiveEnums "
            + "WHEN buildRequestMap SHOULD normalize to declared \"RED\"")
    void given_lowercaseEnumInQuery_when_caseInsensitive_should_normalize() throws Exception {
        Map<String, Object> result =
                enumBuilder.buildRequestMap(bookDescriptor, Map.of(), Map.of("color", "red"), null);

        assertThat(result).containsEntry("color", "RED");
    }

    @Test
    @DisplayName("GIVEN body {\"color\":\"Dark_Blue\"} WITH caseInsensitiveEnums "
            + "WHEN buildRequestMap SHOULD normalize to declared \"DARK_BLUE\"")
    void given_mixedCaseEnumInBody_when_caseInsensitive_should_normalize() throws Exception {
        Map<String, Object> result =
                enumBuilder.buildRequestMap(bookDescriptor, Map.of(), Map.of(), "{\"color\":\"Dark_Blue\"}");

        assertThat(result).containsEntry("color", "DARK_BLUE");
    }

    @Test
    @DisplayName("GIVEN repeated query ?tags=red&tags=dark_blue WITH caseInsensitiveEnums "
            + "WHEN buildRequestMap SHOULD send [\"RED\", \"DARK_BLUE\"]")
    void given_repeatedEnumsInQuery_when_caseInsensitive_should_normalizeEachElement() throws Exception {
        Map<String, Object> result = enumBuilder.buildRequestMap(
                bookDescriptor, Map.of(), QueryParameters.of(Map.of("tags", List.of("red", "dark_blue"))), null);

        assertThat(result).containsEntry("tags", List.of("RED", "DARK_BLUE"));
    }

    @Test
    @DisplayName("GIVEN body tags [\"RED\",\"BLUE\"] and the same repeated query WITH rejectConflictingValues "
            + "WHEN buildRequestMap SHOULD accept it")
    void given_sameRepeatedValuesInBodyAndQuery_when_strict_should_accept() throws Exception {
        Map<String, Object> result = strictBuilder.buildRequestMap(
                bookDescriptor,
                Map.of(),
                QueryParameters.of(Map.of("tags", List.of("RED", "BLUE"))),
                "{\"tags\":[\"RED\",\"BLUE\"]}");

        assertThat(result).containsEntry("tags", List.of("RED", "BLUE"));
    }

    @Test
    @DisplayName("GIVEN body tags [\"RED\",\"BLUE\"] and query ?tags=RED WITH rejectConflictingValues "
            + "WHEN buildRequestMap SHOULD reject the conflict")
    void given_differentRepeatedValuesInBodyAndQuery_when_strict_should_reject() {
        assertThatThrownBy(() -> strictBuilder.buildRequestMap(
                        bookDescriptor,
                        Map.of(),
                        QueryParameters.of(Map.of("tags", List.of("RED"))),
                        "{\"tags\":[\"RED\",\"BLUE\"]}"))
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("tags");
    }

    @Test
    @DisplayName("GIVEN a plain single-value query map WHEN a repeated field is set SHOULD send a one-element list")
    void given_plainQueryMap_when_repeatedField_should_wrapInList() throws Exception {
        Map<String, Object> result =
                defaultBuilder.buildRequestMap(bookDescriptor, Map.of(), Map.of("tags", "RED"), null);

        assertThat(result).containsEntry("tags", List.of("RED"));
    }

    @Test
    @DisplayName("GIVEN body {\"tags\":[\"red\",\"dark_blue\"]} WITH caseInsensitiveEnums "
            + "WHEN buildRequestMap SHOULD normalize each list element")
    void given_enumListInBody_when_caseInsensitive_should_normalizeEachElement() throws Exception {
        Map<String, Object> result =
                enumBuilder.buildRequestMap(bookDescriptor, Map.of(), Map.of(), "{\"tags\":[\"red\",\"dark_blue\"]}");

        assertThat(result).containsEntry("tags", List.of("RED", "DARK_BLUE"));
    }

    @Test
    @DisplayName("GIVEN body {\"author\":{\"color\":\"red\"}} WITH caseInsensitiveEnums "
            + "WHEN buildRequestMap SHOULD normalize nested enum")
    void given_enumInNestedMessage_when_caseInsensitive_should_normalize() throws Exception {
        Map<String, Object> result =
                enumBuilder.buildRequestMap(bookDescriptor, Map.of(), Map.of(), "{\"author\":{\"color\":\"red\"}}");

        @SuppressWarnings("unchecked")
        Map<String, Object> author = (Map<String, Object>) result.get("author");
        assertThat(author).containsEntry("color", "RED");
    }

    @Test
    @DisplayName("GIVEN body {\"coauthors\":[{\"color\":\"red\"}]} WITH caseInsensitiveEnums "
            + "WHEN buildRequestMap SHOULD normalize enums in repeated messages")
    void given_enumInRepeatedMessages_when_caseInsensitive_should_normalize() throws Exception {
        Map<String, Object> result = enumBuilder.buildRequestMap(
                bookDescriptor, Map.of(), Map.of(), "{\"coauthors\":[{\"color\":\"red\"}]}");

        @SuppressWarnings("unchecked")
        List<Object> coauthors = (List<Object>) result.get("coauthors");
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) coauthors.get(0);
        assertThat(first).containsEntry("color", "RED");
    }

    @Test
    @DisplayName("GIVEN body {\"color\":\"purple\"} (unknown value) WITH caseInsensitiveEnums "
            + "WHEN buildRequestMap SHOULD leave as-is for serializer to reject")
    void given_unknownEnumValue_when_caseInsensitive_should_notNormalize() throws Exception {
        Map<String, Object> result =
                enumBuilder.buildRequestMap(bookDescriptor, Map.of(), Map.of(), "{\"color\":\"purple\"}");

        assertThat(result).containsEntry("color", "purple");
    }

    @Test
    @DisplayName(
            "GIVEN query {color:\"red\"} WITH default builder " + "WHEN buildRequestMap SHOULD stay lowercase \"red\"")
    void given_lowercaseEnum_when_default_should_notNormalize() throws Exception {
        Map<String, Object> result =
                defaultBuilder.buildRequestMap(bookDescriptor, Map.of(), Map.of("color", "red"), null);

        assertThat(result).containsEntry("color", "red");
    }

    // -------- Combined strictness (both checks enabled)

    @Test
    @DisplayName("GIVEN path {id:\"7\"}, query {mood:\"happy\"}, body {\"text\":\"hi\"} "
            + "WITH both strictness features WHEN buildRequestMap "
            + "SHOULD normalize enum and detect no conflicts")
    void given_strictEnumBuilder_when_mixedSources_should_normalizeEnumAndNotConflict() throws Exception {
        Map<String, Object> result = strictEnumBuilder.buildRequestMap(
                noteDescriptor, Map.of("id", "7"), Map.of("mood", "Happy"), "{\"text\":\"hi\"}");

        assertThat(result)
                .containsEntry("id", "7")
                .containsEntry("mood", "HAPPY")
                .containsEntry("text", "hi");
    }

    @Test
    @DisplayName("GIVEN path {id:\"7\"}, body {\"id\":\"9\"}, query {mood:\"happy\"} "
            + "WITH both strictness features WHEN buildRequestMap "
            + "SHOULD throw on id conflict despite enum normalization")
    void given_strictEnumBuilder_when_conflictingId_should_throwOnConflict() throws Exception {
        assertThatThrownBy(() -> strictEnumBuilder.buildRequestMap(
                        noteDescriptor, Map.of("id", "7"), Map.of("mood", "Happy"), "{\"id\":\"9\",\"text\":\"hi\"}"))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> {
                    StatusRuntimeException sre = (StatusRuntimeException) e;
                    assertThat(sre.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
                });
    }
}
