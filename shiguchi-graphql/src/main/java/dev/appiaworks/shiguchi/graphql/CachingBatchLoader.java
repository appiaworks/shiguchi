package dev.appiaworks.shiguchi.graphql;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import dev.appiaworks.shiguchi.config.DataLoaderConfig;
import io.grpc.Metadata;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * Serves entities from a cross-request cache and asks the delegate only for the ids it misses.
 *
 * <p>Entities are cached by id alone, whoever asked for them, so this is only for types whose
 * entities are the same for every caller. Ids the backend did not return are not cached, and a
 * failed batch caches nothing.
 */
public class CachingBatchLoader implements BatchOutcome.Loader {

    private final BatchLoader delegate;
    private final Cache<String, Map<String, Object>> cache;

    public CachingBatchLoader(BatchLoader delegate, DataLoaderConfig.TypeCache config) {
        this(delegate, config, Ticker.systemTicker());
    }

    CachingBatchLoader(BatchLoader delegate, DataLoaderConfig.TypeCache config, Ticker ticker) {
        this.delegate = delegate;
        this.cache = Caffeine.newBuilder()
                .maximumSize(config.getMaxSize())
                .expireAfterWrite(config.getTtl())
                .ticker(ticker)
                .build();
    }

    @Override
    public Mono<Map<String, Map<String, Object>>> loadMapped(List<String> keys, Metadata metadata) {
        return loadOutcome(keys, metadata).flatMap(BatchOutcome::strict);
    }

    /**
     * Cached entities plus what the delegate loads for the rest. Only entities are cached: an id
     * the backend did not return, and one whose load failed, are asked for again next time.
     */
    @Override
    public Mono<BatchOutcome> loadOutcome(List<String> keys, Metadata metadata) {
        Map<String, Map<String, Object>> hits = new LinkedHashMap<>();
        List<String> misses = new ArrayList<>();
        for (String key : keys) {
            Map<String, Object> cached = cache.getIfPresent(key);
            if (cached != null) {
                hits.put(key, cached);
            } else {
                misses.add(key);
            }
        }
        if (misses.isEmpty()) {
            return Mono.just(BatchOutcome.ok(hits));
        }
        return BatchOutcome.of(delegate, misses, metadata).map(loaded -> {
            cache.putAll(loaded.entities());
            Map<String, Map<String, Object>> result = new LinkedHashMap<>(hits);
            result.putAll(loaded.entities());
            return new BatchOutcome(result, loaded.failures());
        });
    }
}
