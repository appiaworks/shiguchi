package dev.appiaworks.shiguchi.graphql;

import com.google.protobuf.util.JsonFormat;
import dev.appiaworks.shiguchi.gateway.GatewayErrorDetails;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

/**
 * A backend call behind a GraphQL field failed with a gRPC status. Carries the status, the
 * service and method that failed, and the {@code google.rpc.Status} details the backend attached,
 * so {@link GrpcGraphQlExceptionHandler} can put all of it in the GraphQL error's
 * {@code extensions} instead of a flattened message.
 */
public class GrpcBackendException extends RuntimeException {

    private final String service;
    private final String method;
    private final transient Status status;
    private final transient GatewayErrorDetails details;

    public GrpcBackendException(
            String service, String method, StatusRuntimeException cause, JsonFormat.TypeRegistry serviceTypes) {
        super(describe(service, method, cause.getStatus()), cause);
        this.service = service;
        this.method = method;
        this.status = cause.getStatus();
        this.details = GatewayErrorDetails.from(cause.getStatus(), cause.getTrailers(), serviceTypes);
    }

    private static String describe(String service, String method, Status status) {
        String desc = status.getDescription();
        return "[" + service + "/" + method + "] gRPC " + status.getCode() + (desc != null ? ": " + desc : "");
    }

    /** Fully qualified service name of the backend that failed. */
    public String getService() {
        return service;
    }

    public String getMethod() {
        return method;
    }

    public Status getStatus() {
        return status;
    }

    /** Never null; {@link GatewayErrorDetails#none()} when the backend attached nothing. */
    public GatewayErrorDetails getDetails() {
        return details;
    }
}
