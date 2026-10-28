package dev.appiaworks.shiguchi.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code body} of a google.api.http rule and dotted path/query names, the two parts of the
 * HTTP rule spec the request builder honours beyond "body is the message".
 */
@DisplayName("GrpcRequestBuilder: rule body field and dotted variable names")
class GrpcRequestBuilderBodyFieldTest {

    private static Descriptor createBookRequest;
    private final GrpcRequestBuilder builder = new GrpcRequestBuilder();

    @BeforeAll
    static void descriptors() throws Exception {
        DescriptorProto book = DescriptorProto.newBuilder()
                .setName("Book")
                .addField(scalar("id", 1))
                .addField(scalar("title", 2))
                .build();
        DescriptorProto request = DescriptorProto.newBuilder()
                .setName("CreateBookRequest")
                .addField(scalar("parent", 1))
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("book")
                        .setNumber(2)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName(".t.Book")
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                .addField(scalar("validate_only", 3))
                .build();
        FileDescriptor fd = FileDescriptor.buildFrom(
                FileDescriptorProto.newBuilder()
                        .setName("t.proto")
                        .setPackage("t")
                        .setSyntax("proto3")
                        .addMessageType(book)
                        .addMessageType(request)
                        .build(),
                new FileDescriptor[0]);
        createBookRequest = fd.findMessageTypeByName("CreateBookRequest");
    }

    private static FieldDescriptorProto scalar(String name, int number) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(FieldDescriptorProto.Type.TYPE_STRING)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .build();
    }

    @Test
    @DisplayName("GIVEN body: \"book\" WHEN the HTTP body is a Book WHEN built SHOULD land under the book field")
    void given_bodyField_when_built_should_nestBodyUnderField() throws Exception {
        Map<String, Object> req = builder.buildRequestMap(
                createBookRequest, Map.of("parent", "shelves/1"), Map.of(), "{\"title\":\"Dune\"}", "book");

        assertThat(req).containsEntry("parent", "shelves/1");
        assertThat(req.get("book")).isEqualTo(Map.of("title", "Dune"));
    }

    @Test
    @DisplayName("GIVEN body: \"*\" or no body field WHEN built SHOULD map the HTTP body onto the whole message")
    void given_starOrNoBodyField_when_built_should_mapBodyOntoMessage() throws Exception {
        String body = "{\"parent\":\"shelves/2\",\"book\":{\"title\":\"Emma\"}}";

        Map<String, Object> star = builder.buildRequestMap(createBookRequest, Map.of(), Map.of(), body, "*");
        Map<String, Object> unset = builder.buildRequestMap(createBookRequest, Map.of(), Map.of(), body, null);

        for (Map<String, Object> req : java.util.List.of(star, unset)) {
            assertThat(req).containsEntry("parent", "shelves/2");
            assertThat(req.get("book")).isEqualTo(Map.of("title", "Emma"));
        }
    }

    @Test
    @DisplayName("GIVEN a dotted path variable {book.id} WHEN built SHOULD nest it into the book field")
    void given_dottedPathVariable_when_built_should_nestIntoMessageField() throws Exception {
        Map<String, Object> req = builder.buildRequestMap(
                createBookRequest, Map.of("book.id", "42"), Map.of(), "{\"title\":\"Dune\"}", "book");

        assertThat(req.get("book")).isEqualTo(Map.of("title", "Dune", "id", "42"));
    }

    @Test
    @DisplayName("GIVEN a dotted query parameter ?book.title= WHEN built SHOULD nest it as well")
    void given_dottedQueryParam_when_built_should_nest() throws Exception {
        Map<String, Object> req =
                builder.buildRequestMap(createBookRequest, Map.of(), Map.of("book.title", "Emma", "parent", "p"), null);

        assertThat(req).containsEntry("parent", "p");
        assertThat(req.get("book")).isEqualTo(Map.of("title", "Emma"));
    }

    @Test
    @DisplayName("GIVEN a decoded (Avro) body and body: \"book\" WHEN built SHOULD nest the decoded map the same way")
    void given_decodedBodyAndBodyField_when_built_should_nestDecodedMap() {
        Map<String, Object> decoded = new HashMap<>(Map.of("title", "Dune"));

        Map<String, Object> req = builder.buildRequestMapFromDecoded(
                createBookRequest, Map.of("parent", "shelves/1"), Map.of(), decoded, "book");

        assertThat(req).containsEntry("parent", "shelves/1");
        assertThat(req.get("book")).isEqualTo(Map.of("title", "Dune"));
    }
}
