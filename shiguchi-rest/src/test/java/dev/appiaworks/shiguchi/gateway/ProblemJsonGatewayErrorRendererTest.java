package dev.appiaworks.shiguchi.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Any;
import com.google.rpc.BadRequest;
import com.google.rpc.ErrorInfo;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("ProblemJsonGatewayErrorRenderer")
class ProblemJsonGatewayErrorRendererTest {

    private final ProblemJsonGatewayErrorRenderer renderer = new ProblemJsonGatewayErrorRenderer();

    @Test
    @DisplayName(
            "GIVEN a BadRequest and an ErrorInfo WHEN rendered SHOULD give RFC 9457 members, errors for the violations and details for the rest")
    void given_badRequestAndErrorInfo_when_rendered_should_flattenViolations() throws Exception {
        StatusRuntimeException sre = StatusProto.toStatusRuntimeException(com.google.rpc.Status.newBuilder()
                .setCode(Status.Code.INVALID_ARGUMENT.value())
                .setMessage("invalid")
                .addDetails(Any.pack(BadRequest.newBuilder()
                        .addFieldViolations(BadRequest.FieldViolation.newBuilder()
                                .setField("email")
                                .setDescription("must be an address")
                                .setReason("EMAIL"))
                        .build()))
                .addDetails(Any.pack(
                        ErrorInfo.newBuilder().setReason("R").setDomain("d").build()))
                .build());
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/v1/users"));

        byte[] body = renderer.render(
                HttpStatus.BAD_REQUEST, Status.fromThrowable(sre), GatewayErrorDetails.from(sre, null), exchange);

        JsonNode json = JsonMapper.builder().build().readTree(new String(body, StandardCharsets.UTF_8));
        assertThat(json.get("type").asString()).isEqualTo("about:blank");
        assertThat(json.get("title").asString()).isEqualTo("Bad Request");
        assertThat(json.get("status").asInt()).isEqualTo(400);
        assertThat(json.get("detail").asString()).isEqualTo("invalid");
        assertThat(json.get("instance").asString()).isEqualTo("/v1/users");
        assertThat(json.get("code").asString()).isEqualTo("INVALID_ARGUMENT");
        assertThat(json.get("errors")).hasSize(1);
        assertThat(json.get("errors").get(0).get("field").asString()).isEqualTo("email");
        assertThat(json.get("errors").get(0).get("message").asString()).isEqualTo("must be an address");
        assertThat(json.get("errors").get(0).get("reason").asString()).isEqualTo("EMAIL");
        assertThat(json.get("details")).hasSize(1);
        assertThat(json.get("details").get(0).get("@type").asString()).endsWith("google.rpc.ErrorInfo");
        assertThat(renderer.contentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    @DisplayName("GIVEN no details and no exchange WHEN rendered SHOULD omit errors, details and instance")
    void given_noDetails_when_rendered_should_omitOptionalMembers() throws Exception {
        byte[] body = renderer.render(HttpStatus.NOT_FOUND, Status.NOT_FOUND.withDescription("gone"), null);

        JsonNode json = JsonMapper.builder().build().readTree(new String(body, StandardCharsets.UTF_8));
        assertThat(json.has("errors")).isFalse();
        assertThat(json.has("details")).isFalse();
        assertThat(json.has("instance")).isFalse();
        assertThat(json.get("status").asInt()).isEqualTo(404);
    }
}
