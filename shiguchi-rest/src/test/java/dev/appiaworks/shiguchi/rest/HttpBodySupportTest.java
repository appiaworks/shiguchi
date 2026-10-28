package dev.appiaworks.shiguchi.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.api.HttpBodyProto;
import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code google.api.HttpBody} detection and payload handling: raw HTTP body bytes for uploads,
 * downloads, and responses that carry non-JSON content types.
 */
@DisplayName("HttpBodySupport: google.api.HttpBody detection and payload handling")
class HttpBodySupportTest {

    private static Descriptor httpBodyDescriptor;
    private static Descriptor regularMessageDescriptor;
    private static Descriptor requestWithHttpBodyField;
    private static Descriptor requestWithStringField;
    private static Descriptor responseWithHttpBodyField;

    @BeforeAll
    static void descriptors() throws Exception {
        // HttpBody descriptor from proto-google-common-protos
        httpBodyDescriptor = HttpBodyProto.getDescriptor().findMessageTypeByName("HttpBody");

        // Regular message for testing non-HttpBody types
        DescriptorProto regularMessage = DescriptorProto.newBuilder()
                .setName("RegularMessage")
                .addField(scalar("id", 1))
                .addField(scalar("name", 2))
                .build();

        // Request with an HttpBody field
        DescriptorProto requestWithBody = DescriptorProto.newBuilder()
                .setName("UploadRequest")
                .addField(scalar("filename", 1))
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("body")
                        .setNumber(2)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName(".google.api.HttpBody")
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .build();

        // Request with a non-HttpBody field
        DescriptorProto requestWithStringField = DescriptorProto.newBuilder()
                .setName("StringFieldRequest")
                .addField(scalar("name", 1))
                .addField(scalar("data", 2))
                .build();

        // Response with an HttpBody field
        DescriptorProto responseWithBody = DescriptorProto.newBuilder()
                .setName("DownloadResponse")
                .addField(scalar("status", 1))
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("response_body")
                        .setNumber(2)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName(".google.api.HttpBody")
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .build();

        // Build all descriptors from a single file to resolve dependencies
        FileDescriptor fd = FileDescriptor.buildFrom(
                FileDescriptorProto.newBuilder()
                        .setName("test.proto")
                        .setPackage("test")
                        .setSyntax("proto3")
                        .addMessageType(regularMessage)
                        .addMessageType(requestWithBody)
                        .addMessageType(requestWithStringField)
                        .addMessageType(responseWithBody)
                        .build(),
                new FileDescriptor[] {HttpBodyProto.getDescriptor()});

        regularMessageDescriptor = fd.findMessageTypeByName("RegularMessage");
        requestWithHttpBodyField = fd.findMessageTypeByName("UploadRequest");
        HttpBodySupportTest.requestWithStringField = fd.findMessageTypeByName("StringFieldRequest");
        responseWithHttpBodyField = fd.findMessageTypeByName("DownloadResponse");
    }

    @Test
    @DisplayName("GIVEN descriptor is google.api.HttpBody WHEN isHttpBody is called SHOULD return true")
    void given_httpBodyDescriptor_when_isHttpBody_should_returnTrue() {
        assertThat(HttpBodySupport.isHttpBody(httpBodyDescriptor)).isTrue();
    }

    @Test
    @DisplayName("GIVEN descriptor is not google.api.HttpBody WHEN isHttpBody is called SHOULD return false")
    void given_nonHttpBodyDescriptor_when_isHttpBody_should_returnFalse() {
        assertThat(HttpBodySupport.isHttpBody(regularMessageDescriptor)).isFalse();
    }

    @Test
    @DisplayName(
            "GIVEN input descriptor is HttpBody and bodyRule is not set WHEN rawRequest is called SHOULD return true")
    void given_httpBodyInput_when_rawRequest_should_returnTrue() {
        assertThat(HttpBodySupport.rawRequest(httpBodyDescriptor, null)).isTrue();
        assertThat(HttpBodySupport.rawRequest(httpBodyDescriptor, "*")).isTrue();
        assertThat(HttpBodySupport.rawRequest(httpBodyDescriptor, "")).isTrue();
    }

    @Test
    @DisplayName("GIVEN bodyRule names an HttpBody field WHEN rawRequest is called SHOULD return true")
    void given_namedHttpBodyField_when_rawRequest_should_returnTrue() {
        assertThat(HttpBodySupport.rawRequest(requestWithHttpBodyField, "body")).isTrue();
    }

    @Test
    @DisplayName("GIVEN bodyRule names a non-HttpBody field WHEN rawRequest is called SHOULD return false")
    void given_namedNonHttpBodyField_when_rawRequest_should_returnFalse() {
        assertThat(HttpBodySupport.rawRequest(requestWithStringField, "data")).isFalse();
    }

    @Test
    @DisplayName("GIVEN bodyRule names a field that does not exist WHEN rawRequest is called SHOULD return false")
    void given_unknownField_when_rawRequest_should_returnFalse() {
        assertThat(HttpBodySupport.rawRequest(requestWithHttpBodyField, "unknown"))
                .isFalse();
    }

    @Test
    @DisplayName(
            "GIVEN output descriptor is HttpBody and responseBodyRule is null WHEN rawResponse is called SHOULD return true")
    void given_httpBodyOutput_when_rawResponse_should_returnTrue() {
        assertThat(HttpBodySupport.rawResponse(httpBodyDescriptor, null)).isTrue();
    }

    @Test
    @DisplayName("GIVEN responseBodyRule names an HttpBody field WHEN rawResponse is called SHOULD return true")
    void given_namedHttpBodyField_when_rawResponse_should_returnTrue() {
        assertThat(HttpBodySupport.rawResponse(responseWithHttpBodyField, "response_body"))
                .isTrue();
    }

    @Test
    @DisplayName(
            "GIVEN a request (whole message or named field) WHEN withBody is called SHOULD set data and content_type, preserving other fields")
    void given_httpBodyRequest_when_withBody_should_setBodyAndPreserveFields() {
        byte[] testData = "test file content".getBytes();
        String testContentType = "application/pdf";

        // Scenario 1: whole request is HttpBody
        DynamicMessage request1 = DynamicMessage.newBuilder(httpBodyDescriptor).build();
        DynamicMessage result1 = HttpBodySupport.withBody(request1, "*", testContentType, testData);

        String contentType1 = (String) result1.getField(httpBodyDescriptor.findFieldByName("content_type"));
        ByteString data1 = (ByteString) result1.getField(httpBodyDescriptor.findFieldByName("data"));

        assertThat(contentType1).isEqualTo(testContentType);
        assertThat(data1.toByteArray()).isEqualTo(testData);

        // Scenario 2: HttpBody is in a named field
        DynamicMessage request2 = DynamicMessage.newBuilder(requestWithHttpBodyField)
                .setField(requestWithHttpBodyField.findFieldByName("filename"), "document.pdf")
                .build();

        DynamicMessage result2 = HttpBodySupport.withBody(request2, "body", testContentType, testData);

        // Verify filename is preserved
        String filename = (String) result2.getField(requestWithHttpBodyField.findFieldByName("filename"));
        assertThat(filename).isEqualTo("document.pdf");

        // Verify body field contains HttpBody with correct data
        DynamicMessage body = (DynamicMessage) result2.getField(requestWithHttpBodyField.findFieldByName("body"));
        String contentType2 = (String) body.getField(httpBodyDescriptor.findFieldByName("content_type"));
        ByteString data2 = (ByteString) body.getField(httpBodyDescriptor.findFieldByName("data"));

        assertThat(contentType2).isEqualTo(testContentType);
        assertThat(data2.toByteArray()).isEqualTo(testData);
    }

    @Test
    @DisplayName(
            "GIVEN a response (HttpBody itself or in response_body field) WHEN payload is called SHOULD extract data and content_type")
    void given_httpBodyResponse_when_payload_should_extractDataAndContentType() {
        byte[] testData1 = "download content".getBytes();
        String testContentType1 = "image/png";
        byte[] testData2 = "csv data".getBytes();
        String testContentType2 = "text/csv";

        // Scenario 1: response is HttpBody itself
        DynamicMessage response1 = DynamicMessage.newBuilder(httpBodyDescriptor)
                .setField(httpBodyDescriptor.findFieldByName("content_type"), testContentType1)
                .setField(httpBodyDescriptor.findFieldByName("data"), ByteString.copyFrom(testData1))
                .build();

        HttpBodySupport.Payload payload1 = HttpBodySupport.payload(response1, null);

        assertThat(payload1.contentType()).isEqualTo(testContentType1);
        assertThat(payload1.data()).isEqualTo(testData1);

        // Scenario 2: response has HttpBody in response_body field
        DynamicMessage httpBody = DynamicMessage.newBuilder(httpBodyDescriptor)
                .setField(httpBodyDescriptor.findFieldByName("content_type"), testContentType2)
                .setField(httpBodyDescriptor.findFieldByName("data"), ByteString.copyFrom(testData2))
                .build();

        DynamicMessage response2 = DynamicMessage.newBuilder(responseWithHttpBodyField)
                .setField(responseWithHttpBodyField.findFieldByName("status"), "success")
                .setField(responseWithHttpBodyField.findFieldByName("response_body"), httpBody)
                .build();

        HttpBodySupport.Payload payload2 = HttpBodySupport.payload(response2, "response_body");

        assertThat(payload2.contentType()).isEqualTo(testContentType2);
        assertThat(payload2.data()).isEqualTo(testData2);
    }

    private static FieldDescriptorProto scalar(String name, int number) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(FieldDescriptorProto.Type.TYPE_STRING)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .build();
    }
}
