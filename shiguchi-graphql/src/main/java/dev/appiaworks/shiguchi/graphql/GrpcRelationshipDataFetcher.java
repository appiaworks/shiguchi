package dev.appiaworks.shiguchi.graphql;

import dev.appiaworks.shiguchi.config.DataLoaderConfig;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.dataloader.DataLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DataFetcher for GraphQL fields that are references to other types via ID.
 *
 * <p>For a field marked with {@code resolves_type} (e.g., {@code author: Author} resolved from
 * {@code authorId}), this fetcher:
 * <ol>
 *   <li>Gets the ID from the parent Map using the ID field's JSON name (camelCase)</li>
 *   <li>Delegates to the DataLoader for the resolved GraphQL type</li>
 *   <li>Returns a CompletableFuture to be resolved by the DataLoader's batch provider</li>
 * </ol>
 *
 * <p>Wired up by {@link GatewayDataFetcherRegistrar} for every {@code resolves_type} field, on
 * nested types too.
 *
 * <p>Failures: a key the backend did not return resolves to null without an error. A key whose
 * batch failed (backend error, timeout) fails the field under {@code data-loader.on-error: ERROR}
 * (the default), so the response carries an error from {@link GrpcGraphQlExceptionHandler} with
 * {@code extensions.grpcCode}; under {@code NULL} it resolves to null and only a warning is logged.
 * Without a DataLoader registered for the type, or when no loader is available for it as the batch
 * runs, the first is a warning and null in both modes (a schema without a matching {@code
 * loader_for}); the second fails the batch's keys like any other backend failure.
 */
public class GrpcRelationshipDataFetcher implements DataFetcher<CompletableFuture<Object>> {

    private static final Logger log = LoggerFactory.getLogger(GrpcRelationshipDataFetcher.class);

    private final String idFieldJsonName; // camelCase name of the ID field in the parent Map

    private final String resolvedTypeName; // DataLoader name (the GraphQL type name)

    private final DataLoaderConfig.OnError onError;

    public GrpcRelationshipDataFetcher(String idFieldJsonName, String resolvedTypeName) {
        this(idFieldJsonName, resolvedTypeName, DataLoaderConfig.OnError.ERROR);
    }

    public GrpcRelationshipDataFetcher(
            String idFieldJsonName, String resolvedTypeName, DataLoaderConfig.OnError onError) {
        this.idFieldJsonName = idFieldJsonName;
        this.resolvedTypeName = resolvedTypeName;
        this.onError = onError == null ? DataLoaderConfig.OnError.ERROR : onError;
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<Object> get(DataFetchingEnvironment env) {
        Object source = env.getSource();
        if (!(source instanceof Map<?, ?> sourceMap)) {
            return CompletableFuture.completedFuture(null);
        }

        Object id = sourceMap.get(idFieldJsonName);
        if (id == null) {
            return CompletableFuture.completedFuture(null);
        }

        DataLoader<String, Object> loader = env.getDataLoader(resolvedTypeName);
        if (loader == null) {
            log.warn(
                    "[Relationship] No DataLoader registered for type '{}' (field: {}). "
                            + "Check that a loader_for annotation targets this type in the proto.",
                    resolvedTypeName,
                    idFieldJsonName);
            return CompletableFuture.completedFuture(null);
        }

        String idStr = String.valueOf(id);
        log.debug("[Relationship] Scheduling DataLoader load: type={} id={}", resolvedTypeName, idStr);

        // The batch call's own timeout (data-loader.timeout, REST timeout-seconds) bounds the wait.
        // A failed key is not swallowed here: its exception reaches GrpcGraphQlExceptionHandler, so
        // the response carries an error (grpcCode, service, method) and the field is null.
        return loader.load(idStr).handle((value, failure) -> {
            if (failure == null) return value == BatchFailureSummary.SUPPRESSED ? null : value;
            if (onError == DataLoaderConfig.OnError.NULL) {
                log.warn(
                        "[Relationship] DataLoader load failed for type='{}' id={}: {}",
                        resolvedTypeName,
                        idStr,
                        failure.getMessage());
                return null;
            }
            throw failure instanceof CompletionException completion ? completion : new CompletionException(failure);
        });
    }
}
