package dev.appiaworks.shiguchi.graphql.push;

import dev.appiaworks.shiguchi.cursus.PushException;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.execution.SubscriptionExceptionResolverAdapter;
import org.springframework.stereotype.Component;

/**
 * Turns a {@link PushException} raised by a subscription field (a refused namespace, a bad topic,
 * an unknown resume id, an overflow) into the graphql-transport-ws {@code error} frame's error,
 * with the message and {@code extensions.code} / {@code extensions.status}, instead of Spring
 * GraphQL's generic "Subscription error".
 */
@Component
@ConditionalOnClass(
        name = {
            "dev.appiaworks.shiguchi.cursus.PushException",
            "org.springframework.graphql.execution.SubscriptionExceptionResolverAdapter"
        })
@ConditionalOnProperty(prefix = "shiguchi.cursus", name = "enabled", havingValue = "true")
public class PushGraphQlErrors extends SubscriptionExceptionResolverAdapter {

    @Override
    protected GraphQLError resolveToSingleError(Throwable exception) {
        if (!(exception instanceof PushException e)) return null;
        return GraphqlErrorBuilder.newError()
                .message(e.getMessage())
                .errorType(errorType(e))
                .extensions(Map.of("code", e.code(), "status", e.status().value()))
                .build();
    }

    static ErrorType errorType(PushException e) {
        return switch (e.status().value()) {
            case 400, 413 -> ErrorType.BAD_REQUEST;
            case 401 -> ErrorType.UNAUTHORIZED;
            case 403 -> ErrorType.FORBIDDEN;
            case 404 -> ErrorType.NOT_FOUND;
            default -> ErrorType.INTERNAL_ERROR;
        };
    }
}
