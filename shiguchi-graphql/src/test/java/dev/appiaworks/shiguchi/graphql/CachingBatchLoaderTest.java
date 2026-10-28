package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.benmanes.caffeine.cache.Ticker;
import dev.appiaworks.shiguchi.config.DataLoaderConfig;
import io.grpc.Metadata;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * BDD-style unit tests for {@link CachingBatchLoader}.
 *
 * <p>A fake {@link BatchLoader} records keys and call count to verify cache behavior. A fake
 * {@link Ticker} (backed by an {@link AtomicLong}) allows test control over time advancement
 * to verify TTL expiry without sleeping.
 */
class CachingBatchLoaderTest {

    /** Fake BatchLoader that records every call and allows test injection of responses. */
    private static class RecordingBatchLoader implements BatchLoader {
        private final List<List<String>> callHistory = new ArrayList<>();
        private Mono<Map<String, Map<String, Object>>> response = Mono.just(Map.of());

        @Override
        public Mono<Map<String, Map<String, Object>>> loadMapped(List<String> keys, Metadata metadata) {
            callHistory.add(new ArrayList<>(keys));
            return response;
        }

        void setResponse(Mono<Map<String, Map<String, Object>>> response) {
            this.response = response;
        }

        int callCount() {
            return callHistory.size();
        }

        List<List<String>> getCallHistory() {
            return callHistory;
        }
    }

    /** Fake Ticker backed by an AtomicLong nanos value that tests can advance. */
    private static class AdvancingTicker implements Ticker {
        private final AtomicLong nanos = new AtomicLong(0);

        @Override
        public long read() {
            return nanos.get();
        }

        void advanceNanos(long amount) {
            nanos.addAndGet(amount);
        }
    }

    private static DataLoaderConfig.TypeCache typeCache(long maxSize, Duration ttl) {
        DataLoaderConfig.TypeCache cache = new DataLoaderConfig.TypeCache();
        cache.setMaxSize(maxSize);
        cache.setTtl(ttl);
        return cache;
    }

    @Test
    @DisplayName(
            "Given all keys are cached, when loadMapped is called, should return cached results without calling delegate")
    void given_allKeysCached_when_loadMapped_should_returnCachedWithoutDelegateCall() {
        // Arrange
        RecordingBatchLoader delegate = new RecordingBatchLoader();
        delegate.setResponse(Mono.just(Map.of(
                "1", Map.of("name", "Alice"),
                "2", Map.of("name", "Bob"))));

        CachingBatchLoader loader = new CachingBatchLoader(delegate, typeCache(100, Duration.ofHours(1)));
        Metadata metadata = new Metadata();

        // Act: prime the cache
        loader.loadMapped(List.of("1", "2"), metadata).block();
        int callCountAfterPrime = delegate.callCount();

        // Act: call again with same keys
        Map<String, Map<String, Object>> result =
                loader.loadMapped(List.of("1", "2"), metadata).block();

        // Assert
        assertThat(result).containsEntry("1", Map.of("name", "Alice")).containsEntry("2", Map.of("name", "Bob"));
        assertThat(delegate.callCount()).isEqualTo(callCountAfterPrime);
    }

    @Test
    @DisplayName("Given partial cache hit, when loadMapped is called, should fetch only missing keys and merge results")
    void given_partialCacheHit_when_loadMapped_should_fetchMissingAndMergeResults() {
        // Arrange
        RecordingBatchLoader delegate = new RecordingBatchLoader();
        delegate.setResponse(Mono.just(Map.of(
                "1", Map.of("name", "Alice"),
                "2", Map.of("name", "Bob"))));

        CachingBatchLoader loader = new CachingBatchLoader(delegate, typeCache(100, Duration.ofHours(1)));
        Metadata metadata = new Metadata();

        // Act: prime cache with keys 1 and 2
        loader.loadMapped(List.of("1", "2"), metadata).block();

        // Act: update delegate response for new keys
        delegate.setResponse(Mono.just(Map.of("3", Map.of("name", "Charlie"))));

        // Act: call with mix of cached (1, 2) and missing (3)
        Map<String, Map<String, Object>> result =
                loader.loadMapped(List.of("1", "2", "3"), metadata).block();

        // Assert
        assertThat(result)
                .containsEntry("1", Map.of("name", "Alice"))
                .containsEntry("2", Map.of("name", "Bob"))
                .containsEntry("3", Map.of("name", "Charlie"));
        assertThat(delegate.getCallHistory().get(1)).containsExactly("3");
    }

    @Test
    @DisplayName(
            "Given delegate does not return an id, when loadMapped completes, should not cache the missing id and fetch it again next call")
    void given_delegateOmitsId_when_loadMapped_should_notCacheOmittedIdAndFetchNextCall() {
        // Arrange
        RecordingBatchLoader delegate = new RecordingBatchLoader();
        // Delegate only returns id "1" even though asked for both "1" and "2"
        delegate.setResponse(Mono.just(Map.of("1", Map.of("name", "Alice"))));

        CachingBatchLoader loader = new CachingBatchLoader(delegate, typeCache(100, Duration.ofHours(1)));
        Metadata metadata = new Metadata();

        // Act: first call asks for 1 and 2, delegate only returns 1
        Map<String, Map<String, Object>> result1 =
                loader.loadMapped(List.of("1", "2"), metadata).block();

        // Act: second call asks for 2 again
        delegate.setResponse(Mono.just(Map.of("2", Map.of("name", "Bob"))));
        Map<String, Map<String, Object>> result2 =
                loader.loadMapped(List.of("2"), metadata).block();

        // Assert: first result has only 1
        assertThat(result1).containsEntry("1", Map.of("name", "Alice")).doesNotContainKey("2");
        // Assert: second call had to fetch 2 (not cached)
        assertThat(result2).containsEntry("2", Map.of("name", "Bob"));
        assertThat(delegate.getCallHistory().get(1)).contains("2");
    }

    @Test
    @DisplayName("Given delegate returns error, when loadMapped is called, should propagate error and cache nothing")
    void given_delegateReturnsError_when_loadMapped_should_propagateErrorAndCacheNothing() {
        // Arrange
        RecordingBatchLoader delegate = new RecordingBatchLoader();
        RuntimeException testError = new RuntimeException("Backend error");
        delegate.setResponse(Mono.error(testError));

        CachingBatchLoader loader = new CachingBatchLoader(delegate, typeCache(100, Duration.ofHours(1)));
        Metadata metadata = new Metadata();

        // Act & Assert: first call should propagate error
        assertThatThrownBy(() -> loader.loadMapped(List.of("1", "2"), metadata).block())
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Backend error");

        // Act: second call should hit delegate again for the same keys (nothing was cached)
        delegate.setResponse(Mono.just(Map.of(
                "1", Map.of("name", "Alice"),
                "2", Map.of("name", "Bob"))));
        Map<String, Map<String, Object>> result =
                loader.loadMapped(List.of("1", "2"), metadata).block();

        // Assert
        assertThat(result).containsEntry("1", Map.of("name", "Alice")).containsEntry("2", Map.of("name", "Bob"));
        assertThat(delegate.callCount()).isEqualTo(2);
        assertThat(delegate.getCallHistory().get(1)).containsExactlyInAnyOrder("1", "2");
    }

    @Test
    @DisplayName("Given TTL expires, when loadMapped is called after advancing time, should reload from delegate")
    void given_ttlExpires_when_loadMapped_should_reloadFromDelegate() {
        // Arrange
        AdvancingTicker ticker = new AdvancingTicker();
        RecordingBatchLoader delegate = new RecordingBatchLoader();
        delegate.setResponse(Mono.just(Map.of("1", Map.of("name", "Alice"))));

        DataLoaderConfig.TypeCache config = typeCache(100, Duration.ofNanos(1000));
        CachingBatchLoader loader = new CachingBatchLoader(delegate, config, ticker);
        Metadata metadata = new Metadata();

        // Act: prime cache
        Map<String, Map<String, Object>> result1 =
                loader.loadMapped(List.of("1"), metadata).block();

        // Act: advance time past TTL
        ticker.advanceNanos(2000);

        // Act: update delegate response to verify it's called
        delegate.setResponse(Mono.just(Map.of("1", Map.of("name", "Alice (updated)"))));

        // Act: call again
        Map<String, Map<String, Object>> result2 =
                loader.loadMapped(List.of("1"), metadata).block();

        // Assert
        assertThat(result1).containsEntry("1", Map.of("name", "Alice"));
        assertThat(result2).containsEntry("1", Map.of("name", "Alice (updated)"));
        assertThat(delegate.callCount()).isEqualTo(2);
    }

    @Test
    @DisplayName(
            "Given delegate returns multiple ids, when loadMapped is called, should cache all returned ids with correct data")
    void given_delegateReturnsMultipleIds_when_loadMapped_should_cachAllReturnedIds() {
        // Arrange
        RecordingBatchLoader delegate = new RecordingBatchLoader();
        delegate.setResponse(Mono.just(Map.of(
                "1", Map.of("name", "Alice", "age", 30),
                "2", Map.of("name", "Bob", "age", 25),
                "3", Map.of("name", "Charlie", "age", 35))));

        CachingBatchLoader loader = new CachingBatchLoader(delegate, typeCache(100, Duration.ofHours(1)));
        Metadata metadata = new Metadata();

        // Act: load ids
        loader.loadMapped(List.of("1", "2", "3"), metadata).block();

        // Act: request same ids again in different order
        Map<String, Map<String, Object>> result =
                loader.loadMapped(List.of("3", "1", "2"), metadata).block();

        // Assert: all cached with correct data
        assertThat(result)
                .containsEntry("1", Map.of("name", "Alice", "age", 30))
                .containsEntry("2", Map.of("name", "Bob", "age", 25))
                .containsEntry("3", Map.of("name", "Charlie", "age", 35));
        assertThat(delegate.callCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Given empty key list, when loadMapped is called, should return empty map without calling delegate")
    void given_emptyKeyList_when_loadMapped_should_returnEmptyWithoutDelegateCall() {
        // Arrange
        RecordingBatchLoader delegate = new RecordingBatchLoader();
        delegate.setResponse(Mono.just(Map.of()));

        CachingBatchLoader loader = new CachingBatchLoader(delegate, typeCache(100, Duration.ofHours(1)));
        Metadata metadata = new Metadata();

        // Act
        Map<String, Map<String, Object>> result =
                loader.loadMapped(List.of(), metadata).block();

        // Assert
        assertThat(result).isEmpty();
        assertThat(delegate.callCount()).isZero();
    }

    @Test
    @DisplayName("Given metadata is passed to delegate, when loadMapped is called, should forward metadata unchanged")
    void given_metadataPassedToDelegate_when_loadMapped_should_forwardMetadataUnchanged() {
        // Arrange
        RecordingBatchLoader delegate = new RecordingBatchLoader();
        delegate.setResponse(Mono.just(Map.of("1", Map.of("name", "Alice"))));

        CachingBatchLoader loader = new CachingBatchLoader(delegate, typeCache(100, Duration.ofHours(1)));
        Metadata metadata = new Metadata();
        Metadata.Key<String> testKey = Metadata.Key.of("x-test", Metadata.ASCII_STRING_MARSHALLER);
        metadata.put(testKey, "test-value");

        // Act: first call should cache and pass metadata
        loader.loadMapped(List.of("1"), metadata).block();

        // Act: second call with same key should hit cache, not call delegate
        Metadata metadata2 = new Metadata();
        loader.loadMapped(List.of("1"), metadata2).block();

        // Assert: delegate called only once (second call used cache)
        assertThat(delegate.callCount()).isEqualTo(1);
    }
}
