package dev.appiaworks.shiguchi.rest;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * Extracts pagination metadata from a gRPC response message and sets HTTP headers.
 *
 * When the response Descriptor has a "page_info" field, the next_page_token and
 * total_count values are read directly off the DynamicMessage (no JSON parse) and
 * exposed under the header names from {@code shiguchi.rest.pagination.*}
 * (defaults):
 *   X-Next-Page-Token: <token>   (omitted on last page)
 *   X-Total-Count: <n>           (omitted when -1 / server did not compute)
 */
@Component
public class GrpcResponsePaginationExtractor {

    private final String nextPageTokenHeader;
    private final String totalCountHeader;

    public GrpcResponsePaginationExtractor(GatewayProperties properties) {
        var pagination = properties.getRest().getPagination();
        this.nextPageTokenHeader = pagination.getNextPageTokenHeader();
        this.totalCountHeader = pagination.getTotalCountHeader();
    }

    public void applyHeaders(Message response, HttpHeaders headers) {
        try {
            Descriptor descriptor = response.getDescriptorForType();
            FieldDescriptor pageInfoFd = descriptor.findFieldByName("page_info");
            if (pageInfoFd == null
                    || pageInfoFd.getType() != FieldDescriptor.Type.MESSAGE
                    || pageInfoFd.isRepeated()
                    || !response.hasField(pageInfoFd)) {
                return;
            }

            Message pageInfo = (Message) response.getField(pageInfoFd);
            Descriptor pageInfoDesc = pageInfo.getDescriptorForType();

            FieldDescriptor tokenFd = pageInfoDesc.findFieldByName("next_page_token");
            if (tokenFd != null
                    && tokenFd.getType() == FieldDescriptor.Type.STRING
                    && !tokenFd.isRepeated()
                    && pageInfo.getField(tokenFd) instanceof String token
                    && !token.isBlank()) {
                headers.set(nextPageTokenHeader, token);
            }

            FieldDescriptor countFd = pageInfoDesc.findFieldByName("total_count");
            if (countFd != null
                    && !countFd.isRepeated()
                    && pageInfo.getField(countFd) instanceof Number count
                    && count.longValue() >= 0) {
                headers.set(totalCountHeader, String.valueOf(count.longValue()));
            }
        } catch (Exception ignored) {
            // Non-fatal: pagination headers are best-effort
        }
    }
}
