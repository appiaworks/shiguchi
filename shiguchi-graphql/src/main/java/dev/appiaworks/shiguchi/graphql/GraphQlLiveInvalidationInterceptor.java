package dev.appiaworks.shiguchi.graphql;

import graphql.language.OperationDefinition;
import org.springframework.core.Ordered;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

/**
 * After a mutation that returned data, tells the live queries that depend on the services behind
 * its root fields to run again. Independent of the response cache, which evicts on its own.
 */
class GraphQlLiveInvalidationInterceptor implements WebGraphQlInterceptor, Ordered {

    private final GraphQlLiveDependencies dependencies;
    private final GraphQlLiveInvalidations invalidations;

    GraphQlLiveInvalidationInterceptor(GraphQlLiveDependencies dependencies, GraphQlLiveInvalidations invalidations) {
        this.dependencies = dependencies;
        this.invalidations = invalidations;
    }

    @Override
    public int getOrder() {
        // outside the response cache interceptor: its eviction has happened before the live queries run again
        return Ordered.HIGHEST_PRECEDENCE + 80;
    }

    @Override
    public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
        if (!StringUtils.hasText(request.getDocument())) return chain.next(request);
        GraphQlLiveDependencies.Shape shape = dependencies.shape(request.getDocument(), request.getOperationName());
        if (shape == null || shape.type() != OperationDefinition.Operation.MUTATION) return chain.next(request);
        return chain.next(request).doOnNext(response -> {
            if (response.getExecutionResult().getData() != null) invalidations.invalidate(shape.services());
        });
    }
}
