package dev.appiaworks.shiguchi.ui;

import java.util.List;
import java.util.Map;

/**
 * The JSON shapes the web console exchanges with the gateway: the schema it renders (services,
 * methods, message fields) and the invoke request it sends back.
 *
 * <p>A holder of records rather than a type of its own — it exists so the console's wire format
 * is declared in one place. Changing a record here changes what the bundled frontend expects, so
 * the two move together.
 */
public final class GrpcUiSchemaDto {

    private GrpcUiSchemaDto() {}

    public record ServiceSchema(
            String name,
            String target,
            List<MethodSchema> methods,
            /** Proto file package, e.g. "com.example.api" */
            String namespace,
            /** ServiceDefinition.name from YAML config */
            String configName) {}

    public record MethodSchema(
            String name,
            String fullServiceName,
            /** UNARY | SERVER_STREAMING | CLIENT_STREAMING | BIDI_STREAMING */
            String methodType,
            MessageSchema inputSchema,
            String outputTypeName,
            /** Nullable — from google.api.http annotation */
            String httpMethod,
            String httpPath) {}

    public record MessageSchema(String fullName, List<FieldSchema> fields) {}

    public record FieldSchema(
            String name,
            String jsonName,
            /** Lowercase proto type name: string, int32, message, enum, bytes, bool, etc. */
            String type,
            /** optional | repeated | map */
            String label,
            /** FQN for message and enum fields; null for primitives */
            String typeName,
            /** Nested fields for message type (inline, up to MAX_DEPTH) */
            List<FieldSchema> fields,
            /** Values for enum type */
            List<EnumValueSchema> enumValues,
            /** Non-null when this field belongs to a protobuf oneof */
            String oneofName,
            /** True when this field represents a map entry (key or value) */
            boolean mapEntry,
            /** Non-null for google.protobuf well-known types: TIMESTAMP, DURATION,
             *  FIELD_MASK, EMPTY, STRUCT, VALUE, ANY, STRING_VALUE, INT32_VALUE, etc. */
            String wellKnownType) {}

    public record EnumValueSchema(String name, int number) {}

    // -------------------------------------------------------------------------
    // API request / response records
    // -------------------------------------------------------------------------

    public record MetadataEntry(String key, String value) {}

    public record InvokeRequest(
            /** Fully-qualified proto service name, e.g. com.example.MyService */
            String serviceName,
            String methodName,
            /** JSON string payload; null or blank is treated as {} */
            String payload,
            /**
             * The request messages of a client-streaming call, each a JSON string. Null or empty
             * for a unary or server-streaming call, which carry their one message in
             * {@link #payload} instead.
             */
            List<String> payloads,
            /** Optional extra gRPC metadata entries sent by the UI */
            List<MetadataEntry> metadata) {

        /** A single-message request, the shape every non-client-streaming call uses. */
        public InvokeRequest(String serviceName, String methodName, String payload, List<MetadataEntry> metadata) {
            this(serviceName, methodName, payload, null, metadata);
        }

        /** The request messages to send, falling back to the single {@link #payload}. */
        public List<String> messages() {
            if (payloads != null && !payloads.isEmpty()) return payloads;
            return payload == null ? List.of() : List.of(payload);
        }
    }

    public record GatewayUiLinks(
            boolean swaggerEnabled,
            String swaggerPath,
            boolean graphqlEnabled,
            String graphqlPath,
            boolean grpcProxyEnabled,
            int grpcProxyPort,
            List<AuthProviderDto> authProviders,
            /** The push surface is on; the console shows its admin tab. */
            boolean pushEnabled,
            /** Base path of the push endpoints ({@code /push}), for the tail's WebSocket. */
            String pushPath,
            /** The {@code gateway-push} actuator endpoint ({@code /actuator/gateway-push}). */
            String pushAdminPath) {}

    public record InvokeResponse(
            /** JSON string response; null on error */
            String response,
            /** gRPC status code name, e.g. OK, NOT_FOUND, PERMISSION_DENIED */
            String grpcStatus,
            /** Error description; null on success */
            String errorMessage,
            /**
             * The google.rpc.Status details the backend attached (grpc-status-details-bin), each as
             * proto3 JSON with its @type; null on success or when there were none
             */
            List<Map<String, Object>> errorDetails,
            long durationMs) {

        /** A response without error details. */
        public InvokeResponse(String response, String grpcStatus, String errorMessage, long durationMs) {
            this(response, grpcStatus, errorMessage, null, durationMs);
        }
    }

    // -------------------------------------------------------------------------
    // Auth provider records — serialised into GatewayUiLinks.authProviders
    // -------------------------------------------------------------------------

    public record AuthProviderDto(
            String id,
            /** http | oauth2 | apiKey | openIdConnect */
            String type,
            /** bearer | basic (for type=http) */
            String scheme,
            String description,
            /** header | query | cookie (for type=apiKey) */
            String apiKeyIn,
            /** e.g. X-API-Key (for type=apiKey) */
            String apiKeyName,
            OAuthFlowDto authorizationCode,
            OAuthFlowDto clientCredentials) {}

    public record OAuthFlowDto(String authorizationUrl, String tokenUrl, Map<String, String> scopes) {}

    // -------------------------------------------------------------------------
    // Auth token exchange records
    // -------------------------------------------------------------------------

    public record ClientCredentialsRequest(String clientId, String clientSecret, List<String> scopes) {}

    public record TokenResponse(String accessToken, long expiresIn, String tokenType) {}
}
