package dev.appiaworks.shiguchi.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.routing.HttpOperationBinding;
import io.grpc.Status;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD-style tests for HttpUpstreamDispatcher static utility methods and negotiate(...).
 *
 * Tests verify that:
 * - HTTP status codes map to the correct gRPC Status codes
 * - Status mapping covers error codes for authorization, throttling, and server errors
 * - negotiate() falls back to JSON when no preference, no codecs, or the operation/codec cannot
 *   serve the preferred media type
 * - negotiate() prefers the codec's media type for request and/or response when it can be served
 */
@DisplayName("HttpUpstreamDispatcher utility tests")
class HttpUpstreamDispatcherTest {

    /** Hand-written fake codec: serves whatever schema names are listed as servable. */
    private static final class FakeUpstreamBodyCodec implements UpstreamBodyCodec {
        private final String mediaType;
        private final Set<String> servableSchemas;

        FakeUpstreamBodyCodec(String mediaType, Set<String> servableSchemas) {
            this.mediaType = mediaType;
            this.servableSchemas = servableSchemas;
        }

        @Override
        public boolean supports(String candidate) {
            return mediaType.equalsIgnoreCase(candidate);
        }

        @Override
        public String mediaType() {
            return mediaType;
        }

        @Override
        public Set<String> registerService(
                String serviceName, ServiceDefinition definition, Map<String, Map<String, Object>> componentSchemas) {
            return Set.of();
        }

        @Override
        public boolean canServe(String serviceName, String schemaName) {
            return servableSchemas.contains(schemaName);
        }

        @Override
        public Object decode(String serviceName, String schemaName, boolean array, byte[] body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] encode(String serviceName, String schemaName, Map<String, Object> body) {
            throw new UnsupportedOperationException();
        }
    }

    private static ServiceDefinition serviceWithPreferred(String preferred) {
        ServiceDefinition def = new ServiceDefinition();
        def.setName("inventory");
        ServiceDefinition.UpstreamContent content = new ServiceDefinition.UpstreamContent();
        content.setPreferred(preferred);
        def.setUpstreamContent(content);
        return def;
    }

    private static HttpOperationBinding jsonOnlyGetBinding() {
        return new HttpOperationBinding("GET", "/items", List.of(), List.of(), false, false, false);
    }

    @Test
    @DisplayName("GIVEN 404 HTTP status WHEN toGrpcStatus called SHOULD return NOT_FOUND")
    void given_404Status_when_toGrpcStatus_should_returnNotFound() {
        // Act
        Status status = HttpUpstreamDispatcher.toGrpcStatus(404);

        // Assert
        assertThat(status.getCode()).isEqualTo(Status.Code.NOT_FOUND);
    }

    @Test
    @DisplayName("GIVEN 401 HTTP status WHEN toGrpcStatus called SHOULD return UNAUTHENTICATED")
    void given_401Status_when_toGrpcStatus_should_returnUnauthenticated() {
        // Act
        Status status = HttpUpstreamDispatcher.toGrpcStatus(401);

        // Assert
        assertThat(status.getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    @Test
    @DisplayName("GIVEN 403 HTTP status WHEN toGrpcStatus called SHOULD return PERMISSION_DENIED")
    void given_403Status_when_toGrpcStatus_should_returnPermissionDenied() {
        // Act
        Status status = HttpUpstreamDispatcher.toGrpcStatus(403);

        // Assert
        assertThat(status.getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    @Test
    @DisplayName("GIVEN 429 HTTP status WHEN toGrpcStatus called SHOULD return RESOURCE_EXHAUSTED")
    void given_429Status_when_toGrpcStatus_should_returnResourceExhausted() {
        // Act
        Status status = HttpUpstreamDispatcher.toGrpcStatus(429);

        // Assert
        assertThat(status.getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
    }

    @Test
    @DisplayName("GIVEN 500 HTTP status WHEN toGrpcStatus called SHOULD return INTERNAL")
    void given_500Status_when_toGrpcStatus_should_returnInternal() {
        // Act
        Status status = HttpUpstreamDispatcher.toGrpcStatus(500);

        // Assert
        assertThat(status.getCode()).isEqualTo(Status.Code.INTERNAL);
    }

    @Test
    @DisplayName("GIVEN 503 HTTP status WHEN toGrpcStatus called SHOULD return UNAVAILABLE")
    void given_503Status_when_toGrpcStatus_should_returnUnavailable() {
        // Act
        Status status = HttpUpstreamDispatcher.toGrpcStatus(503);

        // Assert
        assertThat(status.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    }

    @Test
    @DisplayName("GIVEN 400 HTTP status WHEN toGrpcStatus called SHOULD return INVALID_ARGUMENT")
    void given_400Status_when_toGrpcStatus_should_returnInvalidArgument() {
        // Act
        Status status = HttpUpstreamDispatcher.toGrpcStatus(400);

        // Assert
        assertThat(status.getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    @DisplayName("GIVEN 200 HTTP status WHEN toGrpcStatus called SHOULD return UNKNOWN")
    void given_200Status_when_toGrpcStatus_should_returnUnknown() {
        // Act
        Status status = HttpUpstreamDispatcher.toGrpcStatus(200);

        // Assert
        assertThat(status.getCode()).isEqualTo(Status.Code.UNKNOWN);
    }

    @Test
    @DisplayName(
            "GIVEN preferred left at its default application/json WHEN negotiate called SHOULD return JSON negotiation")
    void given_preferredLeftAtDefault_when_negotiate_should_returnJsonNegotiation() {
        // Arrange
        HttpUpstreamDispatcher dispatcher =
                new HttpUpstreamDispatcher(null, new GatewayProperties(), Optional.empty(), List.of());
        ServiceDefinition def = new ServiceDefinition();
        HttpOperationBinding binding = jsonOnlyGetBinding();

        // Act
        HttpUpstreamDispatcher.Negotiation negotiation = dispatcher.negotiate(def, binding);

        // Assert
        assertThat(negotiation.accept()).isEqualTo("application/json");
        assertThat(negotiation.requestContentType()).isEqualTo("application/json");
        assertThat(negotiation.requestCodec()).isNull();
    }

    @Test
    @DisplayName("GIVEN preferred avro/binary but no codecs registered WHEN negotiate called SHOULD fall back to JSON")
    void given_avroPreferredButNoCodecs_when_negotiate_should_returnJsonNegotiation() {
        // Arrange: no codecs at all on the dispatcher
        HttpUpstreamDispatcher dispatcher =
                new HttpUpstreamDispatcher(null, new GatewayProperties(), Optional.empty(), List.of());
        ServiceDefinition def = serviceWithPreferred("avro/binary");
        HttpOperationBinding binding = new HttpOperationBinding(
                "GET",
                "/items/{sku}",
                List.of("sku"),
                List.of(),
                false,
                false,
                false,
                List.of(),
                List.of("avro/binary", "application/json"),
                null,
                "Item");

        // Act
        HttpUpstreamDispatcher.Negotiation negotiation = dispatcher.negotiate(def, binding);

        // Assert
        assertThat(negotiation.accept()).isEqualTo("application/json");
        assertThat(negotiation.requestContentType()).isEqualTo("application/json");
        assertThat(negotiation.requestCodec()).isNull();
    }

    @Test
    @DisplayName(
            "GIVEN preferred avro/binary, operation produces it, and codec can serve WHEN negotiate called SHOULD accept avro first with JSON fallback")
    void given_avroPreferredAndCodecCanServe_when_negotiate_should_acceptAvroFirst() {
        // Arrange
        FakeUpstreamBodyCodec codec = new FakeUpstreamBodyCodec("avro/binary", Set.of("Item"));
        HttpUpstreamDispatcher dispatcher =
                new HttpUpstreamDispatcher(null, new GatewayProperties(), Optional.empty(), List.of(codec));
        ServiceDefinition def = serviceWithPreferred("avro/binary");
        HttpOperationBinding binding = new HttpOperationBinding(
                "GET",
                "/items/{sku}",
                List.of("sku"),
                List.of(),
                false,
                false,
                false,
                List.of(),
                List.of("avro/binary", "application/json"),
                null,
                "Item");

        // Act
        HttpUpstreamDispatcher.Negotiation negotiation = dispatcher.negotiate(def, binding);

        // Assert
        assertThat(negotiation.accept()).startsWith("avro/binary");
        assertThat(negotiation.accept()).contains("application/json;q=0.5");
    }

    @Test
    @DisplayName("GIVEN operation with no body WHEN negotiate called SHOULD return null requestCodec")
    void given_operationWithNoBody_when_negotiate_should_returnNullRequestCodec() {
        // Arrange
        FakeUpstreamBodyCodec codec = new FakeUpstreamBodyCodec("avro/binary", Set.of("Item"));
        HttpUpstreamDispatcher dispatcher =
                new HttpUpstreamDispatcher(null, new GatewayProperties(), Optional.empty(), List.of(codec));
        ServiceDefinition def = serviceWithPreferred("avro/binary");
        HttpOperationBinding binding = new HttpOperationBinding(
                "GET",
                "/items/{sku}",
                List.of("sku"),
                List.of(),
                false,
                false,
                false,
                List.of(),
                List.of("avro/binary", "application/json"),
                null,
                "Item");

        // Act
        HttpUpstreamDispatcher.Negotiation negotiation = dispatcher.negotiate(def, binding);

        // Assert
        assertThat(negotiation.requestCodec()).isNull();
    }

    @Test
    @DisplayName(
            "GIVEN operation body is avro/binary and codec can serve the request schema WHEN negotiate called SHOULD send that content type via the codec")
    void given_avroRequestBodyAndCodecCanServe_when_negotiate_should_useCodecForRequest() {
        // Arrange
        FakeUpstreamBodyCodec codec = new FakeUpstreamBodyCodec("avro/binary", Set.of("Item"));
        HttpUpstreamDispatcher dispatcher =
                new HttpUpstreamDispatcher(null, new GatewayProperties(), Optional.empty(), List.of(codec));
        ServiceDefinition def = serviceWithPreferred("avro/binary");
        HttpOperationBinding binding = new HttpOperationBinding(
                "POST",
                "/items",
                List.of(),
                List.of(),
                true,
                false,
                false,
                List.of("avro/binary", "application/json"),
                List.of("application/json"),
                "Item",
                "Item");

        // Act
        HttpUpstreamDispatcher.Negotiation negotiation = dispatcher.negotiate(def, binding);

        // Assert
        assertThat(negotiation.requestContentType()).isEqualTo("avro/binary");
        assertThat(negotiation.requestCodec()).isSameAs(codec);
    }

    @Test
    @DisplayName("GIVEN operation declares JSON only WHEN negotiate called SHOULD use JSON regardless of preference")
    void given_operationJsonOnly_when_negotiate_should_useJson() {
        // Arrange
        FakeUpstreamBodyCodec codec = new FakeUpstreamBodyCodec("avro/binary", Set.of("Item"));
        HttpUpstreamDispatcher dispatcher =
                new HttpUpstreamDispatcher(null, new GatewayProperties(), Optional.empty(), List.of(codec));
        ServiceDefinition def = serviceWithPreferred("avro/binary");
        HttpOperationBinding binding = jsonOnlyGetBinding();

        // Act
        HttpUpstreamDispatcher.Negotiation negotiation = dispatcher.negotiate(def, binding);

        // Assert
        assertThat(negotiation.accept()).isEqualTo("application/json");
    }

    @Test
    @DisplayName("GIVEN codec cannot serve the operation's schema WHEN negotiate called SHOULD fall back to JSON")
    void given_codecCannotServeSchema_when_negotiate_should_returnJson() {
        // Arrange: codec only knows "Order", operation needs "Item"
        FakeUpstreamBodyCodec codec = new FakeUpstreamBodyCodec("avro/binary", Set.of("Order"));
        HttpUpstreamDispatcher dispatcher =
                new HttpUpstreamDispatcher(null, new GatewayProperties(), Optional.empty(), List.of(codec));
        ServiceDefinition def = serviceWithPreferred("avro/binary");
        HttpOperationBinding binding = new HttpOperationBinding(
                "GET",
                "/items/{sku}",
                List.of("sku"),
                List.of(),
                false,
                false,
                false,
                List.of(),
                List.of("avro/binary", "application/json"),
                null,
                "Item");

        // Act
        HttpUpstreamDispatcher.Negotiation negotiation = dispatcher.negotiate(def, binding);

        // Assert
        assertThat(negotiation.accept()).isEqualTo("application/json");
        assertThat(negotiation.requestCodec()).isNull();
    }

    @Test
    @DisplayName("GIVEN response schema is inline (null name) WHEN negotiate called SHOULD return JSON")
    void given_inlineResponseSchema_when_negotiate_should_returnJson() {
        // Arrange
        FakeUpstreamBodyCodec codec = new FakeUpstreamBodyCodec("avro/binary", Set.of("Item"));
        HttpUpstreamDispatcher dispatcher =
                new HttpUpstreamDispatcher(null, new GatewayProperties(), Optional.empty(), List.of(codec));
        ServiceDefinition def = serviceWithPreferred("avro/binary");
        HttpOperationBinding binding = new HttpOperationBinding(
                "GET",
                "/status",
                List.of(),
                List.of(),
                false,
                false,
                false,
                List.of(),
                List.of("avro/binary", "application/json"),
                null,
                null);

        // Act
        HttpUpstreamDispatcher.Negotiation negotiation = dispatcher.negotiate(def, binding);

        // Assert
        assertThat(negotiation.accept()).isEqualTo("application/json");
    }

    // ---- toJsonNames --------------------------------------------------------

    private static Descriptor orderWithLines() throws Exception {
        DescriptorProto line = DescriptorProto.newBuilder()
                .setName("Line")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("unit_price")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_INT64)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                .build();
        DescriptorProto order = DescriptorProto.newBuilder()
                .setName("Order")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("placed_at")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_INT64)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("lines")
                        .setNumber(2)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName(".t.Line")
                        .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED))
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("first_line")
                        .setNumber(3)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName(".t.Line")
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                .build();
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("t.proto")
                .setPackage("t")
                .setSyntax("proto3")
                .addMessageType(line)
                .addMessageType(order)
                .build();
        return FileDescriptor.buildFrom(file, new FileDescriptor[0]).findMessageTypeByName("Order");
    }

    @Test
    @DisplayName("GIVEN a backend response keyed by snake_case properties WHEN re-keyed SHOULD use JSON names, "
            + "recursively through arrays and nested objects")
    void given_snakeCaseResponse_when_toJsonNames_should_rekeyRecursively() throws Exception {
        Map<String, Object> raw = Map.of(
                "placed_at",
                42L,
                "lines",
                List.of(Map.of("unit_price", 7L)),
                "first_line",
                Map.of("unit_price", 9L),
                "unknown_key",
                "kept");

        Map<String, Object> out = HttpUpstreamDispatcher.toJsonNames(raw, orderWithLines());

        assertThat(out).containsEntry("placedAt", 42L).doesNotContainKey("placed_at");
        assertThat(out.get("lines")).isEqualTo(List.of(Map.of("unitPrice", 7L)));
        assertThat(out.get("firstLine")).isEqualTo(Map.of("unitPrice", 9L));
        assertThat(out).as("keys matching no field are left alone").containsEntry("unknown_key", "kept");
    }

    @Test
    @DisplayName("GIVEN a backend response already keyed by JSON names WHEN re-keyed SHOULD keep the values")
    void given_camelCaseResponse_when_toJsonNames_should_keepValues() throws Exception {
        Map<String, Object> out =
                HttpUpstreamDispatcher.toJsonNames(Map.of("placedAt", 1L, "lines", List.of()), orderWithLines());

        assertThat(out).containsEntry("placedAt", 1L).containsEntry("lines", List.of());
    }
}
