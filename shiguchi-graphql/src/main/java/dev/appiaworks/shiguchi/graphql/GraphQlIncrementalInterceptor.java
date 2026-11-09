package dev.appiaworks.shiguchi.graphql;

import graphql.ExperimentalApi;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.graphql.server.WebSocketGraphQlRequest;
import reactor.core.publisher.Mono;

/**
 * Switches graphql-java's incremental delivery on for a request that can receive it, one whose
 * {@code Accept} lists {@code multipart/mixed} (see {@link GraphQlIncrementalWebFilter}). Any
 * other request leaves it off, so {@code @defer} is ignored and the answer is the complete JSON
 * result, which is what a client without incremental support expects.
 */
class GraphQlIncrementalInterceptor implements WebGraphQlInterceptor {

    @Override
    public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
        if (!(request instanceof WebSocketGraphQlRequest)
                && GraphQlIncrementalWebFilter.acceptsMultipart(request.getHeaders())) {
            request.configureExecutionInput((input, builder) -> builder.graphQLContext(
                            ctx -> ctx.put(ExperimentalApi.ENABLE_INCREMENTAL_SUPPORT, Boolean.TRUE))
                    .build());
        }
        return chain.next(request);
    }
}
