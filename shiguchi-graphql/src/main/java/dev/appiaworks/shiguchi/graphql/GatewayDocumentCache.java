package dev.appiaworks.shiguchi.graphql;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.appiaworks.shiguchi.config.GraphQlConfig;
import dev.appiaworks.shiguchi.routing.SchemaReloadListener;
import graphql.ExecutionInput;
import graphql.execution.preparsed.PreparsedDocumentEntry;
import graphql.execution.preparsed.PreparsedDocumentProvider;
import graphql.execution.preparsed.persisted.ApolloPersistedQuerySupport;
import graphql.execution.preparsed.persisted.PersistedQueryCache;
import graphql.execution.preparsed.persisted.PersistedQueryCacheMiss;
import graphql.execution.preparsed.persisted.PersistedQueryNotFound;
import graphql.execution.preparsed.persisted.PersistedQuerySupport;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Parsed-document cache and automatic persisted queries for the GraphQL surface
 * ({@code shiguchi.graphql.document-cache}, {@code shiguchi.graphql.persisted-queries}).
 *
 * <p>Parsing and validating a query costs more than most of what the gateway does to answer it,
 * and a client sends the same few queries over and over. The document cache keeps the outcome
 * per query text — the parsed document, or the validation errors — so a repeat skips both.
 *
 * <p>Automatic persisted queries (the Apollo protocol) go one step further: the client sends the
 * SHA-256 of its query in {@code extensions.persistedQuery}, and the text only when the gateway
 * answers {@code PersistedQueryNotFound}. The registered text is kept here too.
 *
 * <p>Both caches are emptied whenever the schema reloads. A validation result is only true of
 * the schema it ran against: kept across a reload, a query using a field the backend just dropped
 * would pass validation and fail in a data fetcher, and one using a field just added would keep
 * failing validation. The equivalent DGS properties ({@code dgs.graphql.preparsedDocumentProvider},
 * {@code dgs.graphql.apq}) do not know when the gateway's schema changes, which is why these exist.
 */
public class GatewayDocumentCache implements PreparsedDocumentProvider, SchemaReloadListener {

    private final Cache<String, PreparsedDocumentEntry> documents;
    private final Cache<Object, PreparsedDocumentEntry> persisted;
    private final PersistedQuerySupport persistedQueries;

    public GatewayDocumentCache(GraphQlConfig config) {
        var documentConfig = config.getDocumentCache();
        this.documents = documentConfig != null && documentConfig.isEnabled()
                ? Caffeine.newBuilder()
                        .maximumSize(documentConfig.getMaxEntries())
                        .expireAfterAccess(documentConfig.getExpireAfterAccess())
                        .build()
                : null;
        var apqConfig = config.getPersistedQueries();
        if (apqConfig != null && apqConfig.isEnabled()) {
            this.persisted = Caffeine.newBuilder()
                    .maximumSize(apqConfig.getMaxEntries())
                    .expireAfterAccess(apqConfig.getExpireAfterAccess())
                    .build();
            this.persistedQueries = new ApolloPersistedQuerySupport(new CaffeinePersistedQueryCache(persisted));
        } else {
            this.persisted = null;
            this.persistedQueries = null;
        }
    }

    @Override
    public CompletableFuture<PreparsedDocumentEntry> getDocumentAsync(
            ExecutionInput executionInput, Function<ExecutionInput, PreparsedDocumentEntry> parseAndValidate) {
        // A query that is not a persisted one reaches the parse function, which consults the
        // document cache; a persisted one is served from the persisted-query cache instead.
        Function<ExecutionInput, PreparsedDocumentEntry> cached = documents == null
                ? parseAndValidate
                : input -> documents.get(input.getQuery(), query -> parseAndValidate.apply(input));
        if (persistedQueries != null) {
            return persistedQueries.getDocumentAsync(executionInput, cached);
        }
        return CompletableFuture.completedFuture(cached.apply(executionInput));
    }

    @Override
    public void onSchemaReloaded() {
        if (documents != null) documents.invalidateAll();
        if (persisted != null) persisted.invalidateAll();
    }

    /** Entries kept, for tests and diagnostics. */
    long documentEntries() {
        return documents == null ? 0 : documents.estimatedSize();
    }

    long persistedEntries() {
        return persisted == null ? 0 : persisted.estimatedSize();
    }

    /**
     * graphql-java's persisted-query cache contract over Caffeine: a hash seen before returns its
     * document; an unknown one with the query text registers it; an unknown one without text is
     * {@code PersistedQueryNotFound}, which tells the client to send the text.
     */
    private record CaffeinePersistedQueryCache(Cache<Object, PreparsedDocumentEntry> cache)
            implements PersistedQueryCache {
        @Override
        public CompletableFuture<PreparsedDocumentEntry> getPersistedQueryDocumentAsync(
                Object persistedQueryId, ExecutionInput executionInput, PersistedQueryCacheMiss onCacheMiss)
                throws PersistedQueryNotFound {
            PreparsedDocumentEntry entry = cache.getIfPresent(persistedQueryId);
            if (entry != null) return CompletableFuture.completedFuture(entry);
            String query = executionInput.getQuery();
            if (query == null || query.isBlank() || query.equals(PersistedQuerySupport.PERSISTED_QUERY_MARKER)) {
                throw new PersistedQueryNotFound(persistedQueryId);
            }
            entry = onCacheMiss.apply(query);
            // A query that failed to parse or validate is not registered: the client should be able
            // to fix it and register the corrected text under its own hash.
            if (!entry.hasErrors()) cache.put(persistedQueryId, entry);
            return CompletableFuture.completedFuture(entry);
        }
    }
}
