package dev.appiaworks.shiguchi.rest;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import io.grpc.Status;
import org.springframework.http.HttpStatus;

/**
 * Maps gRPC Status codes to HTTP status codes.
 *
 * <p>The default implementation ({@link DefaultGrpcStatusMapper}) follows the standard
 * transcoding convention (google.rpc.Code â†’ HTTP mapping used by grpc-gateway and Cloud
 * Endpoints). Register your own bean of this type to replace the mapping â€” the gateway
 * backs off automatically.
 */
@ApiStatus(value = ApiStatus.Status.STABLE, since = "0.1.0")
public interface GrpcStatusMapper {

    HttpStatus toHttpStatus(Status status);
}
