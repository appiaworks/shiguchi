package dev.appiaworks.shiguchi.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Batching and caching of the per-type DataLoaders that resolve {@code resolves_type} fields.
 *
 * <p>DataLoaders are request-scoped: without a {@link #cache} entry every GraphQL request batches
 * its own lookups and calls the backend again. A {@code cache} entry keeps loaded entities of that
 * type across requests for {@code ttl}, keyed by entity id only, so only list types whose entities
 * are the same for every caller and change rarely (reference data). Never cache a type whose
 * backend filters or shapes results by the caller's identity.
 */
@Data
@NoArgsConstructor
public class DataLoaderConfig {

    /** Largest number of ids sent in one batch call; larger batches are split. {@code 0} = unlimited. */
    private int maxBatchSize = 0;

    /** Cross-request entity cache per GraphQL type name (the {@code loader_for} value). Empty by default. */
    private Map<String, TypeCache> cache = new LinkedHashMap<>();

    @Data
    @NoArgsConstructor
    public static class TypeCache {
        /** How long a loaded entity is served before it is fetched again. */
        private Duration ttl = Duration.ofHours(1);
        /** Most entities of this type kept in memory. */
        private long maxSize = 10_000;
    }
}
