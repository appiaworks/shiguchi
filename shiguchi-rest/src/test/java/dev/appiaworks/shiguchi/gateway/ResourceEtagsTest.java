package dev.appiaworks.shiguchi.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

@DisplayName("ResourceEtags")
class ResourceEtagsTest {

    private static Descriptor messageWithField(String fieldName, String fieldType) throws Exception {
        var file = DescriptorProtos.FileDescriptorProto.newBuilder()
                .setName("test.proto")
                .setPackage("test")
                .addMessageType(DescriptorProtos.DescriptorProto.newBuilder()
                        .setName("Message")
                        .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                                .setName(fieldName)
                                .setNumber(1)
                                .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL)
                                .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
                                .build())
                        .build())
                .build();
        return FileDescriptor.buildFrom(file, new FileDescriptor[0]).findMessageTypeByName("Message");
    }

    private static Descriptor messageWithRepeatedField(String fieldName) throws Exception {
        var file = DescriptorProtos.FileDescriptorProto.newBuilder()
                .setName("test.proto")
                .setPackage("test")
                .addMessageType(DescriptorProtos.DescriptorProto.newBuilder()
                        .setName("Message")
                        .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                                .setName(fieldName)
                                .setNumber(1)
                                .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_REPEATED)
                                .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
                                .build())
                        .build())
                .build();
        return FileDescriptor.buildFrom(file, new FileDescriptor[0]).findMessageTypeByName("Message");
    }

    @Test
    @DisplayName("GIVEN a message with a singular string field named etag WHEN hasEtag called SHOULD return true")
    void given_messageWithEtagField_when_hasEtag_should_returnTrue() throws Exception {
        var descriptor = messageWithField("etag", "string");

        assertThat(ResourceEtags.hasEtag(descriptor)).isTrue();
    }

    @Test
    @DisplayName("GIVEN a message without etag field WHEN hasEtag called SHOULD return false")
    void given_messageWithoutEtagField_when_hasEtag_should_returnFalse() throws Exception {
        var descriptor = messageWithField("name", "string");

        assertThat(ResourceEtags.hasEtag(descriptor)).isFalse();
    }

    @Test
    @DisplayName("GIVEN a message with repeated etag field WHEN hasEtag called SHOULD return false")
    void given_messageWithRepeatedEtagField_when_hasEtag_should_returnFalse() throws Exception {
        var descriptor = messageWithRepeatedField("etag");

        assertThat(ResourceEtags.hasEtag(descriptor)).isFalse();
    }

    @Test
    @DisplayName("GIVEN an unquoted etag value WHEN toHeader called SHOULD quote it")
    void given_unquotedValue_when_toHeader_should_quoteIt() {
        var result = ResourceEtags.toHeader("abc123");

        assertThat(result).isEqualTo("\"abc123\"");
    }

    @Test
    @DisplayName("GIVEN an already quoted etag value WHEN toHeader called SHOULD keep it quoted")
    void given_quotedValue_when_toHeader_should_keepIt() {
        var result = ResourceEtags.toHeader("\"abc123\"");

        assertThat(result).isEqualTo("\"abc123\"");
    }

    @Test
    @DisplayName("GIVEN a W/-prefixed unquoted value WHEN toHeader called SHOULD quote and keep prefix")
    void given_weakUnquotedValue_when_toHeader_should_quoteWithPrefix() {
        var result = ResourceEtags.toHeader("W/abc123");

        assertThat(result).isEqualTo("W/\"abc123\"");
    }

    @Test
    @DisplayName("GIVEN a quoted etag in If-None-Match header WHEN fromHeader called SHOULD strip quotes")
    void given_quotedHeader_when_fromHeader_should_stripQuotes() {
        var result = ResourceEtags.fromHeader("\"abc123\"");

        assertThat(result).isEqualTo("abc123");
    }

    @Test
    @DisplayName("GIVEN a W/-prefixed header WHEN fromHeader called SHOULD keep prefix")
    void given_weakHeader_when_fromHeader_should_keepPrefix() {
        var result = ResourceEtags.fromHeader("W/\"abc123\"");

        assertThat(result).isEqualTo("W/abc123");
    }

    @Test
    @DisplayName("GIVEN a message with top-level etag field WHEN requestField called SHOULD return 'etag'")
    void given_messageWithTopLevelEtag_when_requestField_should_returnEtag() throws Exception {
        var descriptor = messageWithField("etag", "string");

        var result = ResourceEtags.requestField(descriptor, "*");

        assertThat(result).isEqualTo("etag");
    }

    @Test
    @DisplayName("GIVEN body '*' WHEN requestField called SHOULD return null when no top-level etag")
    void given_bodyStar_when_requestField_should_returnNull() throws Exception {
        var descriptor = messageWithField("name", "string");

        var result = ResourceEtags.requestField(descriptor, "*");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("GIVEN no body field WHEN requestField called SHOULD return null")
    void given_noBodyField_when_requestField_should_returnNull() throws Exception {
        var descriptor = messageWithField("name", "string");

        var result = ResourceEtags.requestField(descriptor, null);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("GIVEN If-Match with '*' WHEN ifMatch called SHOULD return tag null and count 1")
    void given_matchStar_when_ifMatch_should_returnTagNullCountOne() {
        var headers = new HttpHeaders();
        headers.setIfMatch("*");

        var result = ResourceEtags.ifMatch(headers);

        assertThat(result).isNotNull();
        assertThat(result.tag()).isNull();
        assertThat(result.count()).isEqualTo(1);
        assertThat(result.multiple()).isFalse();
    }

    @Test
    @DisplayName("GIVEN If-Match with two tags WHEN ifMatch called SHOULD return multiple true")
    void given_multipleTags_when_ifMatch_should_returnMultipleTrue() {
        var headers = new HttpHeaders();
        headers.setIfMatch("\"tag1\", \"tag2\"");

        var result = ResourceEtags.ifMatch(headers);

        assertThat(result).isNotNull();
        assertThat(result.multiple()).isTrue();
        assertThat(result.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("GIVEN If-Match header absent WHEN ifMatch called SHOULD return null")
    void given_noIfMatch_when_ifMatch_should_returnNull() {
        var headers = new HttpHeaders();

        var result = ResourceEtags.ifMatch(headers);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("GIVEN applyIfMatch with absent etag field WHEN called SHOULD fill it")
    void given_absentEtagField_when_applyIfMatch_should_fillIt() {
        var request = new java.util.HashMap<String, Object>();

        ResourceEtags.applyIfMatch(request, "etag", "tag123");

        assertThat(request.get("etag")).isEqualTo("tag123");
    }

    @Test
    @DisplayName("GIVEN applyIfMatch with nested body WHEN called SHOULD create the map if missing")
    void given_nestedBodyMissing_when_applyIfMatch_should_createMap() {
        var request = new java.util.HashMap<String, Object>();

        ResourceEtags.applyIfMatch(request, "body.etag", "tag123");

        assertThat(request.get("body")).isInstanceOf(java.util.Map.class);
        @SuppressWarnings("unchecked")
        var nested = (java.util.Map<String, Object>) request.get("body");
        assertThat(nested.get("etag")).isEqualTo("tag123");
    }

    @Test
    @DisplayName("GIVEN applyIfMatch with conflicting etag WHEN called SHOULD throw INVALID_ARGUMENT")
    void given_conflictingEtag_when_applyIfMatch_should_throw() {
        var request = new java.util.HashMap<String, Object>();
        request.put("etag", "oldTag");

        assertThatThrownBy(() -> ResourceEtags.applyIfMatch(request, "etag", "newTag"))
                .isInstanceOf(io.grpc.StatusRuntimeException.class)
                .satisfies(ex -> {
                    var status = ((io.grpc.StatusRuntimeException) ex).getStatus();
                    assertThat(status.getCode()).isEqualTo(io.grpc.Status.Code.INVALID_ARGUMENT);
                    assertThat(status.getDescription()).contains("oldTag").contains("newTag");
                });
    }
}
