package dev.appiaworks.shiguchi.graphql;

import io.grpc.Status;
import java.util.concurrent.TimeoutException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * A REST_DOWNSTREAM batch loader's call failed: the backend answered with an error status, did not
 * answer in time, could not be reached, or answered with a body that is not what the loader reads.
 *
 * <p>{@link GrpcGraphQlExceptionHandler} turns it into the same error shape a gRPC failure gets:
 * {@code extensions.grpcCode} (the closest gRPC code, see {@link #grpcCode()}), {@code errorType},
 * plus {@code httpStatus} (when the backend answered), {@code type} and {@code endpoint}.
 */
public class RestBackendException extends RuntimeException {

    private final int httpStatus;
    private final String type;
    private final String endpoint;

    /**
     * @param httpStatus the backend's HTTP status, or {@code 0} when there was none (timeout,
     *     connection failure, unreadable body)
     * @param type the GraphQL type the loader loads
     * @param endpoint the configured endpoint path
     */
    public RestBackendException(int httpStatus, String type, String endpoint, String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.type = type;
        this.endpoint = endpoint;
    }

    /** {@code error} as a {@code RestBackendException} of the loader for {@code type} (itself when it is one). */
    static RestBackendException of(Throwable error, String type, String endpoint) {
        if (error instanceof RestBackendException already) return already;
        if (error instanceof WebClientResponseException response) {
            int status = response.getStatusCode().value();
            return new RestBackendException(
                    status, type, endpoint, "REST " + type + " " + endpoint + " answered HTTP " + status, error);
        }
        String what = error instanceof TimeoutException
                        || error.getClass().getSimpleName().contains("Timeout")
                ? "timed out"
                : "failed: " + error.getMessage();
        return new RestBackendException(0, type, endpoint, "REST " + type + " " + endpoint + " " + what, error);
    }

    /** The backend's HTTP status; {@code 0} when it did not answer with one. */
    public int getHttpStatus() {
        return httpStatus;
    }

    public String getType() {
        return type;
    }

    public String getEndpoint() {
        return endpoint;
    }

    /** The gRPC code a gRPC client would see for this failure, the vocabulary the gateway's errors share. */
    public Status.Code grpcCode() {
        if (httpStatus == 0) {
            boolean timeout = getCause() instanceof TimeoutException
                    || (getCause() != null
                            && getCause().getClass().getSimpleName().contains("Timeout"));
            return timeout ? Status.Code.DEADLINE_EXCEEDED : Status.Code.UNAVAILABLE;
        }
        return switch (httpStatus) {
            case 400, 422 -> Status.Code.INVALID_ARGUMENT;
            case 401 -> Status.Code.UNAUTHENTICATED;
            case 403 -> Status.Code.PERMISSION_DENIED;
            case 404 -> Status.Code.NOT_FOUND;
            case 409 -> Status.Code.ABORTED;
            case 429 -> Status.Code.RESOURCE_EXHAUSTED;
            case 502, 503 -> Status.Code.UNAVAILABLE;
            case 504 -> Status.Code.DEADLINE_EXCEEDED;
            default -> Status.Code.INTERNAL;
        };
    }
}
