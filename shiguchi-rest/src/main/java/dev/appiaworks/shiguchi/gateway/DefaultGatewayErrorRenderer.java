package dev.appiaworks.shiguchi.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.grpc.Status;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;

/**
 * Default error body: {@code {"code":"<GRPC_CODE>","message":"<description>"}}, plus
 * {@code "details":[...]} when the backend attached {@code google.rpc.Status} details — each one
 * as proto3 JSON with its {@code @type}, e.g. a {@code google.rpc.BadRequest} listing the field
 * violations. Registered by the gateway auto-configuration only when the application does not
 * define its own {@link GatewayErrorRenderer} bean.
 */
public class DefaultGatewayErrorRenderer implements GatewayErrorRenderer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public byte[] render(HttpStatus httpStatus, Status grpcStatus, ServerWebExchange exchange) {
        return render(httpStatus, grpcStatus, GatewayErrorDetails.none(), exchange);
    }

    @Override
    public byte[] render(
            HttpStatus httpStatus, Status grpcStatus, GatewayErrorDetails details, ServerWebExchange exchange) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("code", grpcStatus.getCode().name());
        body.put("message", grpcStatus.getDescription() == null ? "" : grpcStatus.getDescription());
        if (details != null && !details.isEmpty()) {
            body.set("details", MAPPER.valueToTree(details.asJson()));
        }
        try {
            return MAPPER.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            // Cannot happen for a tree of strings, numbers and maps; keep the contract total anyway.
            return ("{\"code\":\"" + grpcStatus.getCode().name() + "\",\"message\":\"\"}")
                    .getBytes(StandardCharsets.UTF_8);
        }
    }
}
