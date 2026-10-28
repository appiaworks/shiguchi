package dev.appiaworks.shiguchi.graphql;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import java.util.List;
import java.util.Map;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.execution.SubscriptionPublisherException;

/**
 * The error a client sees when the credential of its GraphQL operation ended: {@code UNAUTHORIZED},
 * "session ended" (BFF session) or "token expired" (bearer token), {@code extensions.code
 * unauthenticated}. Used by {@link CookieSessionBindingInterceptor} for a refused operation and for
 * a subscription it ends.
 */
final class GraphQlSessionEnded {

    private GraphQlSessionEnded() {}

    static GraphQLError error(String reason) {
        return GraphqlErrorBuilder.newError()
                .message(reason)
                .errorType(ErrorType.UNAUTHORIZED)
                .extensions(Map.of("code", "unauthenticated", "status", 401))
                .build();
    }

    /**
     * The error that ends a subscription's stream. Spring GraphQL wraps the publisher's own errors
     * in this before an interceptor sees the response, so one raised here has to arrive wrapped.
     */
    static SubscriptionPublisherException exception(String reason) {
        return new SubscriptionPublisherException(List.of(error(reason)), new IllegalStateException(reason));
    }
}
