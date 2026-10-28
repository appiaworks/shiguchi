package dev.appiaworks.shiguchi.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Any;
import com.google.rpc.BadRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * BDD-style tests for DefaultGatewayErrorRenderer: the gRPC code name as {@code code}, the
 * description as a properly escaped JSON string in {@code message}, the backend's
 * {@code google.rpc.Status} details as {@code details} when there are any, UTF-8 throughout.
 */
class DefaultGatewayErrorRendererTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DefaultGatewayErrorRenderer renderer = new DefaultGatewayErrorRenderer();

    private static JsonNode parse(byte[] body) throws Exception {
        return JSON.readTree(new String(body, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName(
            "Given gRPC status with a description containing quotes, newlines and backslashes, when render is called, should escape them so the body stays valid JSON")
    void given_descriptionWithJsonSpecials_when_render_should_escapeThem() throws Exception {
        Status status = Status.INVALID_ARGUMENT.withDescription("Field \"email\" is required\nsee C:\\docs");

        JsonNode body = parse(renderer.render(HttpStatus.BAD_REQUEST, status, null));

        assertThat(body.get("code").asText()).isEqualTo("INVALID_ARGUMENT");
        assertThat(body.get("message").asText()).isEqualTo("Field \"email\" is required\nsee C:\\docs");
        assertThat(body.has("details")).isFalse();
    }

    @Test
    @DisplayName("Given gRPC status with null description, when render is called, should produce empty message field")
    void given_nullDescription_when_render_should_produceEmptyMessage() throws Exception {
        JsonNode body = parse(renderer.render(HttpStatus.NOT_FOUND, Status.NOT_FOUND, null));

        assertThat(body.get("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(body.get("message").asText()).isEmpty();
    }

    @Test
    @DisplayName(
            "Given gRPC status with description, when render is called, should return the compact code/message JSON in UTF-8")
    void given_grpcStatusWithDescription_when_render_should_returnUtf8Json() {
        Status status = Status.PERMISSION_DENIED.withDescription("Access denied for resource é");

        byte[] result = renderer.render(HttpStatus.FORBIDDEN, status, null);

        assertThat(new String(result, StandardCharsets.UTF_8))
                .isEqualTo("{\"code\":\"PERMISSION_DENIED\",\"message\":\"Access denied for resource é\"}");
    }

    @Test
    @DisplayName("Given various gRPC statuses, when render is called, should include correct status code names")
    void given_variousStatuses_when_render_should_includeCorrectCodeNames() {
        assertThat(new String(renderer.render(HttpStatus.OK, Status.OK, null), StandardCharsets.UTF_8))
                .contains("\"code\":\"OK\"");
        assertThat(new String(
                        renderer.render(HttpStatus.GATEWAY_TIMEOUT, Status.DEADLINE_EXCEEDED, null),
                        StandardCharsets.UTF_8))
                .contains("\"code\":\"DEADLINE_EXCEEDED\"");
        assertThat(new String(
                        renderer.render(HttpStatus.SERVICE_UNAVAILABLE, Status.UNAVAILABLE, null),
                        StandardCharsets.UTF_8))
                .contains("\"code\":\"UNAVAILABLE\"");
    }

    @Test
    @DisplayName(
            "Given the backend attached a google.rpc.BadRequest, when render is called with the details, should list it under details with its @type")
    void given_badRequestDetails_when_render_should_includeDetailsArray() throws Exception {
        BadRequest badRequest = BadRequest.newBuilder()
                .addFieldViolations(BadRequest.FieldViolation.newBuilder()
                        .setField("book.title")
                        .setDescription("must not be blank"))
                .build();
        StatusRuntimeException ex = StatusProto.toStatusRuntimeException(com.google.rpc.Status.newBuilder()
                .setCode(Status.Code.INVALID_ARGUMENT.value())
                .setMessage("validation failed")
                .addDetails(Any.pack(badRequest))
                .build());
        GatewayErrorDetails details = GatewayErrorDetails.from(ex, null);

        JsonNode body = parse(renderer.render(HttpStatus.BAD_REQUEST, ex.getStatus(), details, null));

        assertThat(body.get("code").asText()).isEqualTo("INVALID_ARGUMENT");
        assertThat(body.get("message").asText()).isEqualTo("validation failed");
        assertThat(body.get("details")).hasSize(1);
        JsonNode detail = body.get("details").get(0);
        assertThat(detail.get("@type").asText()).isEqualTo("type.googleapis.com/google.rpc.BadRequest");
        assertThat(detail.get("fieldViolations").get(0).get("field").asText()).isEqualTo("book.title");
        assertThat(detail.get("fieldViolations").get(0).get("description").asText())
                .isEqualTo("must not be blank");
    }

    @Test
    @DisplayName("Given no details, when render is called with the four-argument form, should omit the details member")
    void given_noDetails_when_renderWithDetails_should_omitDetails() throws Exception {
        JsonNode body =
                parse(renderer.render(HttpStatus.NOT_FOUND, Status.NOT_FOUND, GatewayErrorDetails.none(), null));

        assertThat(body.has("details")).isFalse();
    }
}
