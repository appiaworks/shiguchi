package dev.appiaworks.shiguchi.graphql;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Saves the Reactor context of the HTTP request (the security context, the exchange) in the
 * {@link graphql.GraphQLContext}, so the data fetchers and batch loaders can run their gRPC calls
 * as the caller: see {@link GraphQlRequestContext}.
 */
@Component
@ConditionalOnProperty(prefix = "shiguchi.graphql", name = "enabled", matchIfMissing = true)
public class GatewayRequestContextInterceptor implements WebGraphQlInterceptor {

    @Override
    public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
        return Mono.deferContextual(view -> {
            request.configureExecutionInput((input, builder) -> {
                GraphQlRequestContext.save(input.getGraphQLContext(), view);
                return input;
            });
            return chain.next(request);
        });
    }
}
