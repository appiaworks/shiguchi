package dev.appiaworks.shiguchi.graphql;

import com.netflix.graphql.dgs.exceptions.DefaultDataFetcherExceptionHandler;
import com.netflix.graphql.types.errors.ErrorType;
import com.netflix.graphql.types.errors.TypedGraphQLError;
import graphql.GraphQLError;
import graphql.execution.DataFetcherExceptionHandler;
import graphql.execution.DataFetcherExceptionHandlerParameters;
import graphql.execution.DataFetcherExceptionHandlerResult;
import graphql.schema.DataFetchingEnvironment;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Turns a failed backend call into a GraphQL error that keeps the gRPC semantics: the message is
 * the backend's status description, and {@code extensions} carries {@code grpcCode} (the
 * {@code io.grpc.Status.Code} name), {@code errorType} (the DGS/Apollo classification derived from
 * it, so clients that already switch on {@code errorType} keep working), {@code service} and
 * {@code method}, and {@code details} — the backend's {@code google.rpc.Status} details as proto3
 * JSON, e.g. a {@code google.rpc.BadRequest} with its field violations. The error is a DGS
 * {@code TypedGraphQLError}, so DGS also adds its usual {@code classification}. Every other
 * exception is handed to the DGS default handler unchanged.
 *
 * <p>Registered as the application's {@code DataFetcherExceptionHandler}; DGS backs off its own
 * default when one is present.
 */
@Component
@ConditionalOnClass(DefaultDataFetcherExceptionHandler.class)
@ConditionalOnProperty(prefix = "shiguchi.graphql", name = "enabled", matchIfMissing = true)
public class GrpcGraphQlExceptionHandler implements DataFetcherExceptionHandler {

    private final DataFetcherExceptionHandler fallback;

    public GrpcGraphQlExceptionHandler() {
        this(new DefaultDataFetcherExceptionHandler());
    }

    GrpcGraphQlExceptionHandler(DataFetcherExceptionHandler fallback) {
        this.fallback = fallback;
    }

    @Override
    public CompletableFuture<DataFetcherExceptionHandlerResult> handleException(
            DataFetcherExceptionHandlerParameters parameters) {
        GraphQLError error = toGraphQlError(parameters);
        if (error == null) return fallback.handleException(parameters);
        return CompletableFuture.completedFuture(
                DataFetcherExceptionHandlerResult.newResult().error(error).build());
    }

    /** The GraphQL error for a gRPC failure anywhere in the cause chain, or null if there is none. */
    static GraphQLError toGraphQlError(DataFetcherExceptionHandlerParameters parameters) {
        Throwable t = parameters.getException();
        for (int depth = 0; t != null && depth < 8; depth++, t = t.getCause()) {
            if (t instanceof GrpcBackendException backend) {
                return build(parameters, backend.getStatus(), backend.getService(), backend.getMethod(), backend);
            }
            if (t instanceof StatusRuntimeException sre) {
                return build(parameters, sre.getStatus(), null, null, null);
            }
        }
        return null;
    }

    /**
     * A DGS {@code TypedGraphQLError} rather than a plain graphql-java error: DGS's
     * {@code GraphQLJavaErrorInstrumentation} rewrites every error still classified as
     * {@code DataFetchingException} into {@code errorType: UNAVAILABLE, errorDetail: SERVICE_ERROR},
     * and leaves one that already carries a DGS {@code ErrorType} alone.
     */
    private static GraphQLError build(
            DataFetcherExceptionHandlerParameters parameters,
            Status status,
            String service,
            String method,
            GrpcBackendException backend) {
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("grpcCode", status.getCode().name());
        if (service != null) extensions.put("service", service);
        if (method != null) extensions.put("method", method);
        if (backend != null && !backend.getDetails().isEmpty()) {
            extensions.put("details", backend.getDetails().asJson());
        }
        String message = status.getDescription() != null
                ? status.getDescription()
                : status.getCode().name();
        DataFetchingEnvironment env = parameters.getDataFetchingEnvironment();
        TypedGraphQLError.Builder error = TypedGraphQLError.newBuilder()
                .errorType(ErrorType.valueOf(errorType(status.getCode())))
                .message(message)
                .extensions(extensions);
        if (env != null) {
            if (env.getExecutionStepInfo() != null)
                error.path(env.getExecutionStepInfo().getPath());
            if (env.getField() != null && env.getField().getSourceLocation() != null) {
                error.location(env.getField().getSourceLocation());
            }
        }
        return error.build();
    }

    /** The DGS {@code ErrorType} name a gRPC code maps onto (the Apollo error classification). */
    static String errorType(Status.Code code) {
        return switch (code) {
            case INVALID_ARGUMENT, OUT_OF_RANGE -> "BAD_REQUEST";
            case FAILED_PRECONDITION, ALREADY_EXISTS, ABORTED -> "FAILED_PRECONDITION";
            case NOT_FOUND -> "NOT_FOUND";
            case PERMISSION_DENIED -> "PERMISSION_DENIED";
            case UNAUTHENTICATED -> "UNAUTHENTICATED";
            case UNAVAILABLE, DEADLINE_EXCEEDED, RESOURCE_EXHAUSTED, CANCELLED -> "UNAVAILABLE";
            case UNKNOWN -> "UNKNOWN";
            default -> "INTERNAL";
        };
    }
}
