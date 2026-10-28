package dev.appiaworks.shiguchi.graphql;

import dev.appiaworks.shiguchi.gateway.GrpcMetadataPopulator;
import graphql.schema.DataFetchingEnvironment;
import io.grpc.Metadata;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Handles the two Apollo Federation protocol queries:
 *
 *   _service { sdl }        — returns this subgraph's SDL so a router can stitch schemas
 *   _entities(representations) — resolves a batch of typed entity representations by ID
 *
 * Both are registered as DGS data fetchers by {@link GatewayDataFetcherRegistrar} when
 * federation is enabled.
 *
 * _entities protocol (Federation v1/v2):
 *   Each representation is a JSON object with at least __typename and the @key field(s).
 *   Representations are grouped by __typename, dispatched via the matching BatchLoader
 *   (either gRPC or REST), then reassembled in the original input order (null for unresolved entries).
 */
public class FederationDataFetcher {

    private static final Logger log = LoggerFactory.getLogger(FederationDataFetcher.class);

    private final GraphQlSchemaBuilder schemaBuilder;
    private final GrpcBatchDataLoaderRegistrar loaderRegistrar;
    private final RestBatchDataLoaderRegistrar restLoaderRegistrar;
    private final GrpcMetadataPopulator metadataPopulator;

    public FederationDataFetcher(GraphQlSchemaBuilder schemaBuilder, GrpcBatchDataLoaderRegistrar loaderRegistrar) {
        this(schemaBuilder, loaderRegistrar, null);
    }

    public FederationDataFetcher(
            GraphQlSchemaBuilder schemaBuilder,
            GrpcBatchDataLoaderRegistrar loaderRegistrar,
            RestBatchDataLoaderRegistrar restLoaderRegistrar) {
        this(schemaBuilder, loaderRegistrar, restLoaderRegistrar, null);
    }

    /** With a populator the entity loads carry the caller's metadata exactly as the other GraphQL calls do. */
    public FederationDataFetcher(
            GraphQlSchemaBuilder schemaBuilder,
            GrpcBatchDataLoaderRegistrar loaderRegistrar,
            RestBatchDataLoaderRegistrar restLoaderRegistrar,
            GrpcMetadataPopulator metadataPopulator) {
        this.schemaBuilder = schemaBuilder;
        this.loaderRegistrar = loaderRegistrar;
        this.restLoaderRegistrar = restLoaderRegistrar;
        this.metadataPopulator = metadataPopulator;
    }

    private Map<String, BatchLoader> allLoaders() {
        Map<String, BatchLoader> combined = new LinkedHashMap<>(loaderRegistrar.getLoaders());
        if (restLoaderRegistrar != null) {
            combined.putAll(restLoaderRegistrar.getLoaders());
        }
        return combined;
    }

    /** Data fetcher for Query._service — returns { sdl: "..." }. */
    public Object fetchService(DataFetchingEnvironment env) {
        return Map.of("sdl", schemaBuilder.getGeneratedSdl());
    }

    /**
     * Data fetcher for Query._entities — resolves representations to entity objects.
     *
     * Returns CompletableFuture<List<Object>> where each element corresponds to the
     * input representation at the same index (null when unresolvable).
     */
    public CompletableFuture<List<Object>> fetchEntities(DataFetchingEnvironment env) {
        List<Map<String, Object>> representations = env.getArgument("representations");
        if (representations == null || representations.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        Mono<Metadata> metadata = GraphQlMetadata.build(env.getGraphQlContext(), metadataPopulator)
                .cache();

        // Group indices by __typename so we can batch per type
        Map<String, List<Integer>> indicesByType = new LinkedHashMap<>();
        Map<String, List<String>> idsByType = new LinkedHashMap<>();
        Map<String, String> typeToKeyField = schemaBuilder.getFederationKeys();

        for (int i = 0; i < representations.size(); i++) {
            Map<String, Object> rep = representations.get(i);
            String typeName = (String) rep.get("__typename");
            if (typeName == null) {
                log.warn("[Federation] Representation at index {} missing __typename; skipping", i);
                continue;
            }
            String keyField = typeToKeyField.get(typeName);
            if (keyField == null) {
                log.warn("[Federation] No @key registered for type '{}'; skipping", typeName);
                continue;
            }
            Object idObj = rep.get(keyField);
            if (idObj == null) {
                log.warn(
                        "[Federation] Representation for type '{}' missing key field '{}'; skipping",
                        typeName,
                        keyField);
                continue;
            }
            String id = String.valueOf(idObj);
            indicesByType.computeIfAbsent(typeName, k -> new ArrayList<>()).add(i);
            idsByType.computeIfAbsent(typeName, k -> new ArrayList<>()).add(id);
        }

        // Dispatch per type and assemble results
        Object[] results = new Object[representations.size()];

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> entry : indicesByType.entrySet()) {
            String typeName = entry.getKey();
            List<Integer> indices = entry.getValue();
            List<String> ids = idsByType.get(typeName);

            BatchLoader loader = allLoaders().get(typeName);
            if (loader == null) {
                log.warn(
                        "[Federation] No batch loader for type '{}'; {} representations will be null",
                        typeName,
                        ids.size());
                continue;
            }

            CompletableFuture<Void> f = GraphQlRequestContext.within(
                            env.getGraphQlContext(), metadata.flatMap(md -> loader.loadMapped(ids, md)))
                    .toFuture()
                    .thenAccept(entityMap -> {
                        for (int j = 0; j < indices.size(); j++) {
                            int resultIdx = indices.get(j);
                            String id = ids.get(j);
                            results[resultIdx] = entityMap.get(id);
                        }
                    })
                    .exceptionally(ex -> {
                        log.warn("[Federation] Batch load failed for type '{}': {}", typeName, ex.getMessage());
                        return null;
                    });
            futures.add(f);
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<Object> list = new ArrayList<>(representations.size());
                    for (Object r : results) list.add(r);
                    return list;
                });
    }
}
