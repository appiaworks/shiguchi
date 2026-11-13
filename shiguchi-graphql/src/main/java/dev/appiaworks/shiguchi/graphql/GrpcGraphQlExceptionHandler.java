package dev.appiaworks.shiguchi.graphql;

import com.netflix.graphql.dgs.exceptions.DefaultDataFetcherExceptionHandler;
import com.netflix.graphql.types.errors.ErrorType;
import com.netflix.graphql.types.errors.TypedGraphQLError;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.GraphQlConfig;
import dev.appiaworks.shiguchi.gateway.GatewayErrorDetails;
import graphql.GraphQLError;
import graphql.execution.DataFetcherExceptionHandler;
import graphql.execution.DataFetcherExceptionHandlerParameters;
import graphql.execution.DataFetcherExceptionHandlerResult;
import graphql.schema.DataFetchingEnvironment;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final boolean nativeFormat;

    /** Reads {@code shiguchi.graphql.error-format}. */
    @Autowired
    public GrpcGraphQlExceptionHandler(GatewayProperties properties) {
        this(
                new DefaultDataFetcherExceptionHandler(),
                properties.getGraphql().getErrorFormat() == GraphQlConfig.ErrorFormat.NATIVE);
    }

    public GrpcGraphQlExceptionHandler() {
        this(new DefaultDataFetcherExceptionHandler(), false);
    }

    GrpcGraphQlExceptionHandler(DataFetcherExceptionHandler fallback) {
        this(fallback, false);
    }

    GrpcGraphQlExceptionHandler(DataFetcherExceptionHandler fallback, boolean nativeFormat) {
        this.fallback = fallback;
        this.nativeFormat = nativeFormat;
    }

    @Override
    public CompletableFuture<DataFetcherExceptionHandlerResult> handleException(
            DataFetcherExceptionHandlerParameters parameters) {
        GraphQLError error = toGraphQlError(parameters, nativeFormat);
        if (error == null) return fallback.handleException(parameters);
        return CompletableFuture.completedFuture(
                DataFetcherExceptionHandlerResult.newResult().error(error).build());
    }

    /** The GraphQL error for a gRPC failure anywhere in the cause chain, or null if there is none. */
    static GraphQLError toGraphQlError(DataFetcherExceptionHandlerParameters parameters, boolean nativeFormat) {
        Throwable t = parameters.getException();
        for (int depth = 0; t != null && depth < 8; depth++, t = t.getCause()) {
            if (t instanceof GrpcBackendException backend) {
                return build(
                        parameters,
                        backend.getStatus(),
                        backend.getService(),
                        backend.getMethod(),
                        backend.getDetails(),
                        nativeFormat);
            }
            if (t instanceof StatusRuntimeException sre) {
                return build(
                        parameters, sre.getStatus(), null, null, GatewayErrorDetails.from(sre, null), nativeFormat);
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
            GatewayErrorDetails details,
            boolean nativeFormat) {
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("grpcCode", status.getCode().name());
        if (service != null) extensions.put("service", service);
        if (method != null) extensions.put("method", method);
        DataFetchingEnvironment env = parameters.getDataFetchingEnvironment();
        if (nativeFormat) {
            extensions.put("code", nativeCode(status.getCode()));
            List<GatewayErrorDetails.FieldViolation> violations = details.fieldViolations();
            if (!violations.isEmpty()) {
                List<Map<String, Object>> fieldErrors = new ArrayList<>(violations.size());
                for (GatewayErrorDetails.FieldViolation v : violations) {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("field", graphQlFieldPath(v.field(), env));
                    e.put("message", v.description());
                    if (!v.reason().isEmpty()) e.put("rule", v.reason());
                    fieldErrors.add(e);
                }
                extensions.put("fieldErrors", fieldErrors);
            }
            List<Map<String, Object>> others = details.asJsonExceptBadRequest();
            if (!others.isEmpty()) extensions.put("details", others);
        } else if (!details.isEmpty()) {
            extensions.put("details", details.asJson());
        }
        String message = status.getDescription() != null
                ? status.getDescription()
                : status.getCode().name();
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

    /** The {@code extensions.code} of {@code error-format: NATIVE}, in the Apollo vocabulary. */
    static String nativeCode(Status.Code code) {
        return switch (code) {
            case INVALID_ARGUMENT, OUT_OF_RANGE, FAILED_PRECONDITION, ALREADY_EXISTS, ABORTED -> "BAD_USER_INPUT";
            case UNAUTHENTICATED -> "UNAUTHENTICATED";
            case PERMISSION_DENIED -> "FORBIDDEN";
            case NOT_FOUND -> "NOT_FOUND";
            case UNAVAILABLE, DEADLINE_EXCEEDED, RESOURCE_EXHAUSTED, CANCELLED -> "SERVICE_UNAVAILABLE";
            default -> "INTERNAL_SERVER_ERROR";
        };
    }

    /**
     * A proto field path ({@code user.email}, {@code items[0].sku}, {@code user_name}) as the
     * GraphQL argument path the caller wrote: each segment lowerCamelCased, and prefixed with
     * {@code input.} when the field takes an {@code input} argument and the path does not already
     * start with one of its arguments.
     */
    static String graphQlFieldPath(String protoPath, DataFetchingEnvironment env) {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (int i = 0; i < protoPath.length(); i++) {
            char c = protoPath.charAt(i);
            if (c == '_') {
                upper = true;
            } else {
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        String path = out.toString();
        if (env == null || env.getArguments() == null) return path;
        Map<String, Object> args = env.getArguments();
        int end = 0;
        while (end < path.length() && path.charAt(end) != '.' && path.charAt(end) != '[') end++;
        String first = path.substring(0, end);
        if (!args.containsKey(first) && args.containsKey("input")) return "input." + path;
        return path;
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
