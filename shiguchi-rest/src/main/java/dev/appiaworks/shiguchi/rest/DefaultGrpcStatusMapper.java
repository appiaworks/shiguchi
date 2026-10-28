package dev.appiaworks.shiguchi.rest;

import io.grpc.Status;
import org.springframework.http.HttpStatus;

/**
 * Default gRPC→HTTP status mapping following the standard transcoding convention
 * (google.rpc.Code → HTTP mapping used by grpc-gateway and Cloud Endpoints).
 * Registered by the gateway auto-configuration only when the application does not
 * define its own {@link GrpcStatusMapper} bean.
 */
public class DefaultGrpcStatusMapper implements GrpcStatusMapper {

    @Override
    public HttpStatus toHttpStatus(Status status) {
        return switch (status.getCode()) {
            case OK -> HttpStatus.OK;
            case CANCELLED -> HttpStatus.valueOf(499); // Client Closed Request
            case UNKNOWN -> HttpStatus.INTERNAL_SERVER_ERROR;
            case INVALID_ARGUMENT -> HttpStatus.BAD_REQUEST;
            case DEADLINE_EXCEEDED -> HttpStatus.GATEWAY_TIMEOUT;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ALREADY_EXISTS -> HttpStatus.CONFLICT;
            case PERMISSION_DENIED -> HttpStatus.FORBIDDEN;
            case RESOURCE_EXHAUSTED -> HttpStatus.TOO_MANY_REQUESTS;
            case FAILED_PRECONDITION -> HttpStatus.BAD_REQUEST;
            case ABORTED -> HttpStatus.CONFLICT;
            case OUT_OF_RANGE -> HttpStatus.BAD_REQUEST;
            case UNIMPLEMENTED -> HttpStatus.NOT_IMPLEMENTED;
            case INTERNAL -> HttpStatus.INTERNAL_SERVER_ERROR;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case DATA_LOSS -> HttpStatus.INTERNAL_SERVER_ERROR;
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
