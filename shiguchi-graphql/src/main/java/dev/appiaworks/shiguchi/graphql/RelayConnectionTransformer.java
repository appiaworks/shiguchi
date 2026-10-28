package dev.appiaworks.shiguchi.graphql;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Detects paged gRPC response Descriptors and transforms the JSON response
 * into a Relay-compatible Connection shape for GraphQL clients.
 *
 * Detection: response Descriptor has both a "page_info" field
 * and at least one repeated message field (the items collection).
 *
 * Connection type name: strip "ListResponse" / "Response" / "ListResult" suffix
 * from Descriptor name, then append "Connection".
 */
@Component
@ConditionalOnProperty(prefix = "shiguchi.graphql", name = "enabled", matchIfMissing = true)
public class RelayConnectionTransformer {

    private static final String RELAY_PAGE_INFO_SDL = """
            type RelayPageInfo {
              endCursor:       String
              hasNextPage:     Boolean!
              startCursor:     String
              hasPreviousPage: Boolean!
              totalCount:      Int
            }
            """;

    public boolean isConnectionCandidate(Descriptor responseDescriptor) {
        return responseDescriptor.findFieldByName("page_info") != null
                && responseDescriptor.getFields().stream()
                        .anyMatch(f -> f.isRepeated() && f.getType() == FieldDescriptor.Type.MESSAGE);
    }

    public String connectionTypeName(Descriptor responseDescriptor) {
        String name = responseDescriptor.getName();
        for (String suffix : List.of("ListResponse", "Response", "ListResult", "Result")) {
            if (name.endsWith(suffix)) {
                name = name.substring(0, name.length() - suffix.length());
                break;
            }
        }
        return name + "Connection";
    }

    public String edgeTypeName(Descriptor responseDescriptor) {
        return connectionTypeName(responseDescriptor).replace("Connection", "Edge");
    }

    public String getEdgesFieldName(Descriptor responseDescriptor) {
        return responseDescriptor.getFields().stream()
                .filter(f -> f.isRepeated() && f.getType() == FieldDescriptor.Type.MESSAGE)
                .findFirst()
                .map(FieldDescriptor::getName)
                .orElseThrow();
    }

    public String getNodeTypeName(Descriptor responseDescriptor) {
        return responseDescriptor.getFields().stream()
                .filter(f -> f.isRepeated() && f.getType() == FieldDescriptor.Type.MESSAGE)
                .findFirst()
                .map(f -> f.getMessageType().getName())
                .orElseThrow();
    }

    /**
     * Generates Connection + Edge SDL type definitions for a paged response Descriptor.
     * RelayPageInfo is emitted separately (once per schema).
     */
    public String generateConnectionSdl(Descriptor responseDescriptor) {
        String connName = connectionTypeName(responseDescriptor);
        String edgeName = edgeTypeName(responseDescriptor);
        String nodeType = getNodeTypeName(responseDescriptor);

        return """
                type %s {
                  edges:     [%s!]!
                  pageInfo:  RelayPageInfo!
                  totalCount: Int
                }
                type %s {
                  node:   %s!
                  cursor: String!
                }
                """.formatted(connName, edgeName, edgeName, nodeType);
    }

    public String relayPageInfoSdl() {
        return RELAY_PAGE_INFO_SDL;
    }

    /** Blank and null both mean "no cursor". */
    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * Transforms a gRPC response JSON map into the Relay Connection shape.
     * Synthetic edge cursors: base64(nextPageToken + ":" + itemIndex).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> transform(Map<String, Object> grpcResponse, String edgesFieldName) {
        return transform(grpcResponse, edgesFieldName, null);
    }

    /**
     * As {@link #transform(Map, String)}, with the cursor the caller paged in on.
     *
     * <p>Needed for the backward half of {@code PageInfo}. {@code hasPreviousPage} is "is there a
     * page before this one", which the response alone cannot answer: a backend that returns only
     * {@code next_page_token} has said nothing about what came before. The request has: a caller
     * that sent an {@code after} cursor is by definition not on the first page.
     *
     * @param requestCursor the {@code after} cursor this page was requested with, or null
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> transform(
            Map<String, Object> grpcResponse, String edgesFieldName, String requestCursor) {
        Object itemsRaw = grpcResponse.get(toCamelCase(edgesFieldName));
        List<Object> items = itemsRaw instanceof List ? (List<Object>) itemsRaw : List.of();

        Object pageInfoRaw = grpcResponse.get("pageInfo");
        Map<String, Object> pageInfo = pageInfoRaw instanceof Map ? (Map<String, Object>) pageInfoRaw : Map.of();

        String nextToken = (String) pageInfo.getOrDefault("nextPageToken", "");
        Object totalCount = pageInfo.getOrDefault("totalCount", null);

        // Build edges with synthetic cursors
        List<Map<String, Object>> edges = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            String cursor = Base64.getEncoder().encodeToString((nextToken + ":" + i).getBytes(StandardCharsets.UTF_8));
            edges.add(Map.of("node", items.get(i), "cursor", cursor));
        }

        // The backward half of the cursor pair, when the backend chose to provide it. Optional by
        // design: page_info's documented shape is next_page_token + total_count, and a backend
        // that cannot cheaply produce a backward cursor should not have to.
        String prevToken = (String) pageInfo.getOrDefault("prevPageToken", "");

        // Build Relay PageInfo
        Map<String, Object> relayPageInfo = new HashMap<>();
        relayPageInfo.put("endCursor", nextToken.isBlank() ? null : nextToken);
        relayPageInfo.put("hasNextPage", !nextToken.isBlank());

        // startCursor is where this page begins, so a client can page back to it. The backend's
        // prev_page_token when there is one; otherwise the cursor the caller arrived with, which
        // is the same position by definition.
        String startCursor = !prevToken.isBlank() ? prevToken : emptyToNull(requestCursor);
        relayPageInfo.put("startCursor", startCursor);

        // Previously hardcoded to false, which was wrong the moment a caller paged past the first
        // page: the schema advertised the field and then always denied there was anything behind.
        // A backward token proves it; so does having arrived here on a cursor.
        relayPageInfo.put("hasPreviousPage", !prevToken.isBlank() || emptyToNull(requestCursor) != null);
        relayPageInfo.put("totalCount", totalCount);

        Map<String, Object> connection = new HashMap<>();
        connection.put("edges", edges);
        connection.put("pageInfo", relayPageInfo);
        connection.put("totalCount", totalCount);
        return connection;
    }

    private static String toCamelCase(String snake) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else if (upper) {
                sb.append(Character.toUpperCase(c));
                upper = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
