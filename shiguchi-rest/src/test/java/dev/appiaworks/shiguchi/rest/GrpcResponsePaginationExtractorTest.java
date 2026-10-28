package dev.appiaworks.shiguchi.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/**
 * BDD-style tests for GrpcResponsePaginationExtractor.
 *
 * Tests verify that:
 * - Pagination headers are extracted from response messages with page_info fields
 * - Custom header names from configuration are used
 * - Messages without page_info fields don't cause errors
 * - Blank tokens and negative counts are handled correctly
 */
class GrpcResponsePaginationExtractorTest {

    private static Descriptor messageWithPageInfo;
    private static Descriptor messageWithoutPageInfo;

    @BeforeAll
    static void buildDescriptors() throws Exception {
        // Create a PageInfo message type
        DescriptorProto pageInfo = DescriptorProto.newBuilder()
                .setName("PageInfo")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("next_page_token")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_STRING)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("total_count")
                        .setNumber(2)
                        .setType(FieldDescriptorProto.Type.TYPE_INT64)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .build();

        // Create a response message WITH page_info
        DescriptorProto responseWithPageInfo = DescriptorProto.newBuilder()
                .setName("ResponseWithPageInfo")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("items")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_STRING)
                        .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
                        .build())
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("page_info")
                        .setNumber(2)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName(".test.PageInfo")
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .build();

        // Create a response message WITHOUT page_info
        DescriptorProto responseWithoutPageInfo = DescriptorProto.newBuilder()
                .setName("ResponseWithoutPageInfo")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("items")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_STRING)
                        .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
                        .build())
                .build();

        FileDescriptor file = FileDescriptor.buildFrom(
                FileDescriptorProto.newBuilder()
                        .setName("pagination-test.proto")
                        .setSyntax("proto3")
                        .setPackage("test")
                        .addMessageType(pageInfo)
                        .addMessageType(responseWithPageInfo)
                        .addMessageType(responseWithoutPageInfo)
                        .build(),
                new FileDescriptor[0]);

        messageWithPageInfo = file.findMessageTypeByName("ResponseWithPageInfo");
        messageWithoutPageInfo = file.findMessageTypeByName("ResponseWithoutPageInfo");
    }

    @Test
    @DisplayName(
            "Given response message without page_info field, when applyHeaders is called, should not set any pagination headers")
    void given_messageWithoutPageInfo_when_applyHeaders_should_notSetHeaders() {
        // Arrange
        GatewayProperties properties = new GatewayProperties();
        GrpcResponsePaginationExtractor extractor = new GrpcResponsePaginationExtractor(properties);
        HttpHeaders headers = new HttpHeaders();

        Message response = DynamicMessage.newBuilder(messageWithoutPageInfo).build();

        // Act
        extractor.applyHeaders(response, headers);

        // Assert
        assertThat(headers.getFirst("X-Next-Page-Token")).isNull();
        assertThat(headers.getFirst("X-Total-Count")).isNull();
    }

    @Test
    @DisplayName(
            "Given response with page_info and non-blank next_page_token, when applyHeaders is called, should set token header with custom name")
    void given_pageInfoWithToken_when_applyHeaders_should_setTokenHeader() {
        // Arrange
        GatewayProperties properties = new GatewayProperties();
        properties.getRest().getPagination().setNextPageTokenHeader("X-Custom-Token");
        GrpcResponsePaginationExtractor extractor = new GrpcResponsePaginationExtractor(properties);
        HttpHeaders headers = new HttpHeaders();

        Descriptor pageInfoDescriptor =
                messageWithPageInfo.findFieldByName("page_info").getMessageType();
        Message pageInfo = DynamicMessage.newBuilder(pageInfoDescriptor)
                .setField(pageInfoDescriptor.findFieldByName("next_page_token"), "token-abc123")
                .build();

        Message response = DynamicMessage.newBuilder(messageWithPageInfo)
                .setField(messageWithPageInfo.findFieldByName("page_info"), pageInfo)
                .build();

        // Act
        extractor.applyHeaders(response, headers);

        // Assert
        assertThat(headers.getFirst("X-Custom-Token")).isEqualTo("token-abc123");
    }

    @Test
    @DisplayName(
            "Given response with page_info and valid total_count, when applyHeaders is called, should set count header with custom name")
    void given_pageInfoWithTotalCount_when_applyHeaders_should_setCountHeader() {
        // Arrange
        GatewayProperties properties = new GatewayProperties();
        properties.getRest().getPagination().setTotalCountHeader("X-Record-Count");
        GrpcResponsePaginationExtractor extractor = new GrpcResponsePaginationExtractor(properties);
        HttpHeaders headers = new HttpHeaders();

        Descriptor pageInfoDescriptor =
                messageWithPageInfo.findFieldByName("page_info").getMessageType();
        Message pageInfo = DynamicMessage.newBuilder(pageInfoDescriptor)
                .setField(pageInfoDescriptor.findFieldByName("total_count"), 42L)
                .build();

        Message response = DynamicMessage.newBuilder(messageWithPageInfo)
                .setField(messageWithPageInfo.findFieldByName("page_info"), pageInfo)
                .build();

        // Act
        extractor.applyHeaders(response, headers);

        // Assert
        assertThat(headers.getFirst("X-Record-Count")).isEqualTo("42");
    }

    @Test
    @DisplayName(
            "Given response with both token and count, when applyHeaders is called, should set both headers using custom names")
    void given_pageInfoWithBothTokenAndCount_when_applyHeaders_should_setBothHeaders() {
        // Arrange
        GatewayProperties properties = new GatewayProperties();
        properties.getRest().getPagination().setNextPageTokenHeader("X-Page-Token");
        properties.getRest().getPagination().setTotalCountHeader("X-Total-Items");
        GrpcResponsePaginationExtractor extractor = new GrpcResponsePaginationExtractor(properties);
        HttpHeaders headers = new HttpHeaders();

        Descriptor pageInfoDescriptor =
                messageWithPageInfo.findFieldByName("page_info").getMessageType();
        Message pageInfo = DynamicMessage.newBuilder(pageInfoDescriptor)
                .setField(pageInfoDescriptor.findFieldByName("next_page_token"), "next-token-xyz")
                .setField(pageInfoDescriptor.findFieldByName("total_count"), 100L)
                .build();

        Message response = DynamicMessage.newBuilder(messageWithPageInfo)
                .setField(messageWithPageInfo.findFieldByName("page_info"), pageInfo)
                .build();

        // Act
        extractor.applyHeaders(response, headers);

        // Assert
        assertThat(headers.getFirst("X-Page-Token")).isEqualTo("next-token-xyz");
        assertThat(headers.getFirst("X-Total-Items")).isEqualTo("100");
    }

    @Test
    @DisplayName(
            "Given response with blank next_page_token, when applyHeaders is called, should not set the token header")
    void given_blankToken_when_applyHeaders_should_notSetTokenHeader() {
        // Arrange
        GatewayProperties properties = new GatewayProperties();
        GrpcResponsePaginationExtractor extractor = new GrpcResponsePaginationExtractor(properties);
        HttpHeaders headers = new HttpHeaders();

        Descriptor pageInfoDescriptor =
                messageWithPageInfo.findFieldByName("page_info").getMessageType();
        Message pageInfo = DynamicMessage.newBuilder(pageInfoDescriptor)
                .setField(pageInfoDescriptor.findFieldByName("next_page_token"), "  ") // Blank
                .build();

        Message response = DynamicMessage.newBuilder(messageWithPageInfo)
                .setField(messageWithPageInfo.findFieldByName("page_info"), pageInfo)
                .build();

        // Act
        extractor.applyHeaders(response, headers);

        // Assert
        assertThat(headers.getFirst("X-Next-Page-Token")).isNull();
    }
}
