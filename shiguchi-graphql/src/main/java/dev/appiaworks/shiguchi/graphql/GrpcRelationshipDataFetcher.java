package dev.appiaworks.shiguchi.graphql;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.dataloader.DataLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DataFetcher for GraphQL fields that are references to other types via ID.
 *
 * For a field marked with {@code resolves_type} (e.g., {@code author: Author} resolved from
 * {@code authorId}), this fetcher:
 * <ol>
 *   <li>Gets the ID from the parent Map using the ID field's JSON name (camelCase)</li>
 *   <li>Delegates to the DataLoader for the resolved GraphQL type</li>
 *   <li>Returns a CompletableFuture to be resolved by the DataLoader's batch provider</li>
 * </ol>
 *
 * Wired up by {@link GatewayDataFetcherRegistrar} during schema build.
 *
 * Includes a 10-second timeout: if the DataLoader dispatch does not fire within that window
 * (e.g., due to missing {@code DataLoaderDispatcherInstrumentation} or unreachable batch
 * service), the future completes with {@code null} so the GraphQL response is still sent.
 */
public class GrpcRelationshipDataFetcher implements DataFetcher<CompletableFuture<Object>> {

    private static final Logger log = LoggerFactory.getLogger(GrpcRelationshipDataFetcher.class);

    /** Seconds to wait for a DataLoader dispatch before giving up and returning null. */
    private static final long LOADER_TIMEOUT_SECONDS = 10;

    private final String idFieldJsonName; // camelCase name of the ID field in the parent Map
    private final String resolvedTypeName; // DataLoader name (the GraphQL type name)

    public GrpcRelationshipDataFetcher(String idFieldJsonName, String resolvedTypeName) {
        this.idFieldJsonName = idFieldJsonName;
        this.resolvedTypeName = resolvedTypeName;
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

        return loader.load(idStr)
                .orTimeout(LOADER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .exceptionally(t -> {
                    if (t instanceof TimeoutException) {
                        log.warn(
                                "[Relationship] DataLoader dispatch timed out after {}s for type='{}' id={}. "
                                        + "DataLoaderDispatcherInstrumentation may not be active or the batch "
                                        + "gRPC service is unreachable. Returning null.",
                                LOADER_TIMEOUT_SECONDS,
                                resolvedTypeName,
                                idStr);
                    } else {
                        log.warn(
                                "[Relationship] DataLoader load failed for type='{}' id={}: {}",
                                resolvedTypeName,
                                idStr,
                                t.getMessage());
                    }
                    return null;
                });
    }
}
