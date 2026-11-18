package dev.appiaworks.shiguchi.graphql;

import dev.appiaworks.shiguchi.config.DataLoaderConfig;
import graphql.GraphQLContext;
import graphql.execution.DataFetcherResult;
import graphql.schema.DataFetchingEnvironmentImpl;
import java.util.List;
import java.util.Map;

/**
 * Split-package helper for {@code FederationBatchLoaderBenchmark}: the {@code _entities} data
 * fetcher and its batch pipeline are package-private, so the benchmark drives them through this
 * class instead of widening production visibility.
 *
 * <p>It wires one loader as the type's gRPC-side loader (the pipeline does not care what the loader
 * talks to) with the {@code max-batch-size} and {@code max-concurrent-batches} the run asks for.
 */
public final class FederationBench {

    private FederationBench() {}

    /** An {@code _entities} fetcher over one type served by {@code loader}. */
    public static Fetcher fetcher(
            String type, String keyField, BatchLoader loader, int maxBatchSize, int maxConcurrentBatches) {
        var config = new DataLoaderConfig();
        config.setMaxBatchSize(maxBatchSize);
        config.setMaxConcurrentBatches(maxConcurrentBatches);
        var registrar = new GrpcBatchDataLoaderRegistrar(null, null, config, null) {
            @Override
            public Map<String, BatchLoader> getLoaders() {
                return Map.of(type, loader);
            }

            @Override
            Map<String, GroupedOutcome.Loader> getGroupedLoaders() {
                return Map.of();
            }
        };
        var schema = new GraphQlSchemaBuilder(null, null, null, null) {
            @Override
            public Map<String, String> getFederationKeys() {
                return Map.of(type, keyField);
            }
        };
        return new Fetcher(new FederationDataFetcher(schema, new BatchLoaderPipeline(registrar, null, config), null));
    }

    public static final class Fetcher {
        private final FederationDataFetcher fetcher;

        private Fetcher(FederationDataFetcher fetcher) {
            this.fetcher = fetcher;
        }

        /** Resolves {@code representations} through {@code fetchEntities}; fails on any GraphQL error. */
        public List<Object> fetch(List<Map<String, Object>> representations) {
            var env = DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
                    .arguments(Map.of("representations", representations))
                    .graphQLContext(GraphQLContext.getDefault())
                    .build();
            DataFetcherResult<List<Object>> result = fetcher.fetchEntities(env).join();
            if (!result.getErrors().isEmpty()) {
                throw new IllegalStateException("fetchEntities failed: " + result.getErrors());
            }
            return result.getData();
        }
    }
}
