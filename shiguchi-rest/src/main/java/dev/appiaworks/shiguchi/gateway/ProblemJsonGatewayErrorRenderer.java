package dev.appiaworks.shiguchi.gateway;

import dev.appiaworks.protobuf.extras.serde.JsonFactories;
import io.grpc.Status;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * RFC 9457 {@code application/problem+json} error body, selected by
 * {@code shiguchi.rest.error-format=PROBLEM_JSON}: {@code type} ({@code about:blank}), {@code title}
 * (the HTTP reason phrase), {@code status}, {@code detail} (the gRPC description), {@code instance}
 * (the request path) and the extension member {@code code} (the gRPC status name). A backend
 * {@code BadRequest} becomes {@code errors:[{field, message, reason}]}; any other detail stays under
 * {@code details} as proto3 JSON.
 */
public class ProblemJsonGatewayErrorRenderer implements GatewayErrorRenderer {

    private static final ObjectMapper MAPPER = JsonMapper.builder(JsonFactories.create())
            .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    @Override
    public byte[] render(HttpStatus httpStatus, Status grpcStatus, ServerWebExchange exchange) {
        return render(httpStatus, grpcStatus, GatewayErrorDetails.none(), exchange);
    }

    @Override
    public byte[] render(
            HttpStatus httpStatus, Status grpcStatus, GatewayErrorDetails details, ServerWebExchange exchange) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("type", "about:blank");
        body.put("title", httpStatus.getReasonPhrase());
        body.put("status", httpStatus.value());
        body.put("detail", grpcStatus.getDescription() == null ? "" : grpcStatus.getDescription());
        if (exchange != null && exchange.getRequest() != null) {
            body.put("instance", exchange.getRequest().getPath().value());
        }
        body.put("code", grpcStatus.getCode().name());
        if (details != null && !details.isEmpty()) {
            var violations = details.fieldViolations();
            if (!violations.isEmpty()) {
                ArrayNode errors = body.putArray("errors");
                for (var v : violations) {
                    ObjectNode e = errors.addObject();
                    e.put("field", v.field());
                    e.put("message", v.description());
                    if (!v.reason().isEmpty()) e.put("reason", v.reason());
                }
            }
            var others = details.asJsonExceptBadRequest();
            if (!others.isEmpty()) body.set("details", MAPPER.valueToTree(others));
        }
        try {
            return MAPPER.writeValueAsBytes(body);
        } catch (JacksonException e) {
            return ("{\"type\":\"about:blank\",\"status\":" + httpStatus.value() + ",\"code\":\""
                            + grpcStatus.getCode().name() + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
        }
    }

    @Override
    public MediaType contentType() {
        return MediaType.APPLICATION_PROBLEM_JSON;
    }
}
