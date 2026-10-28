package dev.appiaworks.shiguchi.gateway;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import io.grpc.Status;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;

/**
 * Renders the HTTP response body for a failed gateway call (gRPC error or access denied).
 *
 * <p>The default implementation ({@link DefaultGatewayErrorRenderer}) emits the compact
 * {@code {"code":"...","message":"...","details":[...]}} JSON shape, {@code details} being the
 * {@code google.rpc.Status} details the backend attached to the call (see {@link
 * GatewayErrorDetails}). Register your own bean of this type to match a corporate error schema â€”
 * add trace/request IDs from the exchange, nest details, emit RFC 7807 problem+json, etc. The HTTP
 * status itself comes from {@link dev.appiaworks.shiguchi.rest.GrpcStatusMapper}, which is overridable
 * separately.
 *
 * <p>Implement {@link #render(HttpStatus, Status, GatewayErrorDetails, ServerWebExchange)} to see
 * the details; the three-argument form is kept for renderers written before details existed and
 * is what the four-argument default delegates to.
 */
@ApiStatus(value = ApiStatus.Status.STABLE, since = "0.14.0")
public interface GatewayErrorRenderer {

    /**
     * Builds the error response body without access to the backend's error details.
     *
     * @param httpStatus the already-mapped HTTP status about to be sent
     * @param grpcStatus the gRPC status that caused the failure (for access-denied failures a
     *                   synthetic {@code PERMISSION_DENIED} status carrying the exception message)
     * @param exchange   the current exchange â€” source for request path, headers, trace IDs
     * @return the response body bytes
     */
    byte[] render(HttpStatus httpStatus, Status grpcStatus, ServerWebExchange exchange);

    /**
     * Builds the error response body. This is what the gateway calls.
     *
     * @param httpStatus the already-mapped HTTP status about to be sent
     * @param grpcStatus the gRPC status that caused the failure
     * @param details    the {@code google.rpc.Status} details the backend attached, never null;
     *                   {@link GatewayErrorDetails#none()} when the failure carried none
     * @param exchange   the current exchange
     * @return the response body bytes
     */
    default byte[] render(
            HttpStatus httpStatus, Status grpcStatus, GatewayErrorDetails details, ServerWebExchange exchange) {
        return render(httpStatus, grpcStatus, exchange);
    }

    /** Content type of the rendered body. Default: {@code application/json}. */
    default MediaType contentType() {
        return MediaType.APPLICATION_JSON;
    }
}
