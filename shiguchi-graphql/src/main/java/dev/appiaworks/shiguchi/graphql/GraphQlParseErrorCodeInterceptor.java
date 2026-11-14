package dev.appiaworks.shiguchi.graphql;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import graphql.ErrorType;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import reactor.core.publisher.Mono;

/**
 * With {@code shiguchi.graphql.error-format=NATIVE}, gives the errors raised before any data
 * fetcher runs (syntax errors, validation errors, and queries aborted by the depth or complexity
 * limit) the same {@code extensions.code}
 * the gateway puts on backend validation failures, {@code BAD_USER_INPUT}. Errors that already
 * carry a {@code code} are left alone.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.23.0")
public class GraphQlParseErrorCodeInterceptor implements WebGraphQlInterceptor, Ordered {

    static final String CODE = "BAD_USER_INPUT";

    @Override
    public int getOrder() {
        // Outermost, so it also sees the errors the limits and introspection interceptors answer with.
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
        return chain.next(request).map(response -> {
            List<GraphQLError> errors = response.getExecutionResult().getErrors();
            if (errors.stream().noneMatch(GraphQlParseErrorCodeInterceptor::needsCode)) {
                return response;
            }
            return response.transform(b -> b.errors(errors.stream()
                    .map(GraphQlParseErrorCodeInterceptor::withCode)
                    .toList()));
        });
    }

    private static boolean needsCode(GraphQLError error) {
        Map<String, Object> ext = error.getExtensions();
        if (ext != null && ext.containsKey("code")) {
            return false;
        }
        // DGS rewrites getErrorType() to BAD_REQUEST, so match the graphql-java classification
        // it keeps in the extensions (and the original type, for errors DGS did not touch).
        String classification = String.valueOf(ext == null ? null : ext.get("classification"));
        return "InvalidSyntax".equals(classification)
                || "ValidationError".equals(classification)
                || "ExecutionAborted".equals(classification)
                || error.getErrorType() == ErrorType.InvalidSyntax
                || error.getErrorType() == ErrorType.ValidationError;
    }

    private static GraphQLError withCode(GraphQLError error) {
        if (!needsCode(error)) {
            return error;
        }
        Map<String, Object> extensions = new LinkedHashMap<>();
        if (error.getExtensions() != null) {
            extensions.putAll(error.getExtensions());
        }
        extensions.put("code", CODE);
        return GraphqlErrorBuilder.newError()
                .message(error.getMessage())
                .errorType(error.getErrorType())
                .locations(error.getLocations())
                .path(error.getPath())
                .extensions(extensions)
                .build();
    }
}
