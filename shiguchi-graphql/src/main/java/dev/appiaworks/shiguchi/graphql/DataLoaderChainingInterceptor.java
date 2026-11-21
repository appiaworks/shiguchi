package dev.appiaworks.shiguchi.graphql;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import graphql.language.Document;
import graphql.language.OperationDefinition;
import graphql.parser.Parser;
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

    private final boolean markSubscriptions;

    public DataLoaderChainingInterceptor() {
        this(false);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DataLoaderChainingInterceptor(GatewayProperties properties) {
        this(!properties
                .getGraphql()
                .getDataLoader()
                .getSubscriptionBatchWindow()
                .isZero());
    }

    DataLoaderChainingInterceptor(boolean markSubscriptions) {
        this.markSubscriptions = markSubscriptions;
    }

    @Override
    public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
        boolean subscription = markSubscriptions && isSubscription(request.getDocument(), request.getOperationName());
        request.configureExecutionInput((input, builder) -> builder.graphQLContext(ctx -> {
                    ctx.put("__GJ_enable_data_loader_chaining", Boolean.TRUE);
                    if (subscription) ctx.put(GatewayBatchLoaderRegistry.SUBSCRIPTION_KEY, Boolean.TRUE);
                })
                .build());
        return chain.next(request);
    }

    /** Whether the operation the request runs is a subscription; false when the document does not parse. */
    static boolean isSubscription(String document, String operationName) {
        try {
            Document parsed = Parser.parse(document);
            var operations = parsed.getDefinitionsOfType(OperationDefinition.class);
            for (OperationDefinition op : operations) {
                if (operationName == null ? operations.size() == 1 : operationName.equals(op.getName())) {
                    return op.getOperation() == OperationDefinition.Operation.SUBSCRIPTION;
                }
            }
        } catch (RuntimeException e) {
            // an unparsable document fails in the engine with a proper error
        }
        return false;
    }
}
