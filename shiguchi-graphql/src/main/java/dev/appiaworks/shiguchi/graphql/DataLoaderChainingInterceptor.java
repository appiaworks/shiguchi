package dev.appiaworks.shiguchi.graphql;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Enables graphql-java 25's data loader chaining mode for every request.
 *
 * <p>Chaining is not what makes relationship loaders dispatch: that is the dispatch predicate
 * {@link GatewayBatchLoaderRegistry} puts on every loader the gateway registers, which works on
 * graphql-java 24 (Boot 3) and 25 (Boot 4) alike. Chaining stays on because it is what the Boot 4
 * line has run with; graphql-java 24 ignores the key.
 *
 * Without this, PerLevelDataLoaderDispatchStrategy.fieldFetched(level=1) dispatches
 * all DataLoaders synchronously when DynamicGraphQlDataFetcher returns its CompletableFuture
 * (before it resolves). Level-2 relationship DataLoaders registered by GrpcRelationshipDataFetcher
 * are loaded after that dispatch fires and are never re-dispatched — they time out.
 *
 * Chaining mode causes DataFetchingEnvironmentImpl.getDataLoader() to wrap each DataLoader in
 * DataLoaderWithContext, which calls newDataLoaderInvocation() on load(). This registers each
 * individual load as a pending invocation that PerLevelDataLoaderDispatchStrategy can dispatch
 * after all fields at that level have been fetched.
 */
@Component
@ConditionalOnProperty(prefix = "shiguchi.graphql", name = "enabled", matchIfMissing = true)
public class DataLoaderChainingInterceptor implements WebGraphQlInterceptor {

    @Override
    public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
        request.configureExecutionInput((input, builder) -> builder.graphQLContext(
                        ctx -> ctx.put("__GJ_enable_data_loader_chaining", Boolean.TRUE))
                .build());
        return chain.next(request);
    }
}
