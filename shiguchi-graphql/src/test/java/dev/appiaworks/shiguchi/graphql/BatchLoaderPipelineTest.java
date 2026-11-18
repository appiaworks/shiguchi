package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.appiaworks.shiguchi.config.DataLoaderConfig;
import io.grpc.Metadata;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/** BDD-style unit tests for {@link BatchLoaderPipeline}. */
@DisplayName("BatchLoaderPipeline")
class BatchLoaderPipelineTest {

    /** Echoes every key as an entity, recording each call and how many ran at once. */
    private static class EchoLoader implements BatchLoader {
        final List<List<String>> calls = new CopyOnWriteArrayList<>();
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        Duration delay = Duration.ZERO;

        @Override
        public Mono<Map<String, Map<String, Object>>> loadMapped(List<String> keys, Metadata metadata) {
            calls.add(List.copyOf(keys));
            Map<String, Map<String, Object>> result = new LinkedHashMap<>();
            for (String key : keys) result.put(key, Map.of("id", key));
            return Mono.defer(() -> {
                maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                return Mono.just(result).delayElement(delay).doOnNext(r -> inFlight.decrementAndGet());
            });
        }
    }

    private static BatchLoaderPipeline pipelineOf(EchoLoader loader, DataLoaderConfig config) {
        GrpcBatchDataLoaderRegistrar registrar = mock(GrpcBatchDataLoaderRegistrar.class);
        when(registrar.getLoaders()).thenReturn(Map.of("Book", loader));
        return new BatchLoaderPipeline(registrar, (RestBatchDataLoaderRegistrar) null, config);
    }

    private static List<String> keys(int count) {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < count; i++) keys.add("k" + i);
        return keys;
    }

    @Test
    @DisplayName("GIVEN repeated keys WHEN loaded THEN the loader sees each key once, in first-seen order")
    void GIVEN_repeatedKeys_WHEN_loaded_SHOULD_callLoaderWithDistinctKeys() {
        EchoLoader loader = new EchoLoader();
        BatchLoaderPipeline pipeline = pipelineOf(loader, new DataLoaderConfig());

        Map<String, Map<String, Object>> result = pipeline.load("Book", List.of("b", "a", "b", "a"), new Metadata())
                .block();

        assertThat(loader.calls).containsExactly(List.of("b", "a"));
        assertThat(result).containsOnlyKeys("a", "b");
    }

    @Test
    @DisplayName("GIVEN max-batch-size 2 and five keys WHEN loaded THEN three calls of 2, 2 and 1 merge into one map")
    void GIVEN_maxBatchSize2_WHEN_fiveKeysLoaded_SHOULD_splitIntoThreeCalls() {
        EchoLoader loader = new EchoLoader();
        DataLoaderConfig config = new DataLoaderConfig();
        config.setMaxBatchSize(2);
        BatchLoaderPipeline pipeline = pipelineOf(loader, config);

        Map<String, Map<String, Object>> result =
                pipeline.load("Book", keys(5), new Metadata()).block();

        assertThat(loader.calls).extracting(List::size).containsExactlyInAnyOrder(2, 2, 1);
        assertThat(result).hasSize(5);
    }

    @Test
    @DisplayName("GIVEN max-batch-size 0 WHEN many keys are loaded THEN there is a single call")
    void GIVEN_noMaxBatchSize_WHEN_manyKeysLoaded_SHOULD_callOnce() {
        EchoLoader loader = new EchoLoader();
        BatchLoaderPipeline pipeline = pipelineOf(loader, new DataLoaderConfig());

        pipeline.load("Book", keys(500), new Metadata()).block();

        assertThat(loader.calls).hasSize(1);
        assertThat(loader.calls.get(0)).hasSize(500);
    }

    @Test
    @DisplayName("GIVEN max-concurrent-batches 2 WHEN six chunks load THEN never more than two are in flight")
    void GIVEN_maxConcurrentBatches2_WHEN_sixChunksLoaded_SHOULD_boundInFlightCalls() {
        EchoLoader loader = new EchoLoader();
        loader.delay = Duration.ofMillis(50);
        DataLoaderConfig config = new DataLoaderConfig();
        config.setMaxBatchSize(1);
        config.setMaxConcurrentBatches(2);
        BatchLoaderPipeline pipeline = pipelineOf(loader, config);

        pipeline.load("Book", keys(6), new Metadata()).block();

        assertThat(loader.calls).hasSize(6);
        assertThat(loader.maxInFlight.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("GIVEN max-concurrent-batches 0 WHEN six chunks load THEN all six run at once")
    void GIVEN_unboundedConcurrency_WHEN_sixChunksLoaded_SHOULD_runAllAtOnce() {
        EchoLoader loader = new EchoLoader();
        loader.delay = Duration.ofMillis(100);
        DataLoaderConfig config = new DataLoaderConfig();
        config.setMaxBatchSize(1);
        config.setMaxConcurrentBatches(0);
        BatchLoaderPipeline pipeline = pipelineOf(loader, config);

        pipeline.load("Book", keys(6), new Metadata()).block();

        assertThat(loader.maxInFlight.get()).isEqualTo(6);
    }

    @Test
    @DisplayName("GIVEN a cache entry for the type WHEN the same keys load twice THEN the loader is called once")
    void GIVEN_cacheEntry_WHEN_sameKeysLoadedTwice_SHOULD_callLoaderOnce() {
        EchoLoader loader = new EchoLoader();
        DataLoaderConfig config = new DataLoaderConfig();
        config.getCache().put("Book", new DataLoaderConfig.TypeCache());
        BatchLoaderPipeline pipeline = pipelineOf(loader, config);

        pipeline.load("Book", List.of("1", "2"), new Metadata()).block();
        Map<String, Map<String, Object>> second =
                pipeline.load("Book", List.of("2", "1"), new Metadata()).block();

        assertThat(loader.calls).hasSize(1);
        assertThat(second).containsOnlyKeys("1", "2");
    }

    @Test
    @DisplayName("GIVEN a cache entry WHEN a partly cached set loads THEN only the misses reach the loader")
    void GIVEN_cacheEntry_WHEN_partlyCachedSetLoaded_SHOULD_askLoaderForMissesOnly() {
        EchoLoader loader = new EchoLoader();
        DataLoaderConfig config = new DataLoaderConfig();
        config.getCache().put("Book", new DataLoaderConfig.TypeCache());
        BatchLoaderPipeline pipeline = pipelineOf(loader, config);

        pipeline.load("Book", List.of("1"), new Metadata()).block();
        pipeline.load("Book", List.of("1", "2"), new Metadata()).block();

        assertThat(loader.calls).containsExactly(List.of("1"), List.of("2"));
    }

    @Test
    @DisplayName("GIVEN an unknown type or no keys WHEN loaded THEN nothing is loaded")
    void GIVEN_unknownTypeOrNoKeys_WHEN_loaded_SHOULD_returnEmptyMap() {
        EchoLoader loader = new EchoLoader();
        BatchLoaderPipeline pipeline = pipelineOf(loader, new DataLoaderConfig());

        assertThat(pipeline.load("Nope", List.of("1"), new Metadata()).block()).isEmpty();
        assertThat(pipeline.load("Book", List.of(), new Metadata()).block()).isEmpty();
        assertThat(pipeline.has("Nope")).isFalse();
        assertThat(pipeline.has("Book")).isTrue();
        assertThat(loader.calls).isEmpty();
    }

    @Test
    @DisplayName("GIVEN a type with a gRPC and a REST loader WHEN loaded THEN the gRPC one answers")
    void GIVEN_typeInGrpcAndRest_WHEN_loaded_SHOULD_useGrpcLoader() {
        EchoLoader grpc = new EchoLoader();
        EchoLoader rest = new EchoLoader();
        GrpcBatchDataLoaderRegistrar grpcRegistrar = mock(GrpcBatchDataLoaderRegistrar.class);
        when(grpcRegistrar.getLoaders()).thenReturn(Map.of("Book", grpc));
        RestBatchDataLoaderRegistrar restRegistrar = mock(RestBatchDataLoaderRegistrar.class);
        when(restRegistrar.getLoaders()).thenReturn(Map.of("Book", rest, "Tag", rest));
        BatchLoaderPipeline pipeline = new BatchLoaderPipeline(grpcRegistrar, restRegistrar, new DataLoaderConfig());

        pipeline.load("Book", List.of("1"), new Metadata()).block();
        pipeline.load("Tag", List.of("7"), new Metadata()).block();

        assertThat(pipeline.types()).containsExactlyInAnyOrder("Book", "Tag");
        assertThat(grpc.calls).hasSize(1);
        assertThat(rest.calls).containsExactly(List.of("7"));
    }

    @Test
    @DisplayName(
            "GIVEN one of three chunks fails WHEN loaded as an outcome THEN the other chunks' entities and that chunk's keys as failures are returned")
    void GIVEN_oneChunkFails_WHEN_loadedAsOutcome_SHOULD_isolateTheFailure() {
        BatchLoader loader = (keys, metadata) ->
                keys.contains("k2") ? Mono.error(new IllegalStateException("chunk down")) : Mono.just(echo(keys));
        GrpcBatchDataLoaderRegistrar registrar = mock(GrpcBatchDataLoaderRegistrar.class);
        when(registrar.getLoaders()).thenReturn(Map.of("Book", loader));
        DataLoaderConfig config = new DataLoaderConfig();
        config.setMaxBatchSize(2);
        BatchLoaderPipeline pipeline = new BatchLoaderPipeline(registrar, (RestBatchDataLoaderRegistrar) null, config);

        BatchOutcome outcome =
                pipeline.loadOutcome("Book", keys(5), new Metadata()).block();

        assertThat(outcome.entities()).containsOnlyKeys("k0", "k1", "k4");
        assertThat(outcome.failures()).containsOnlyKeys("k2", "k3");
        assertThat(outcome.failures().get("k2")).hasMessage("chunk down");
    }

    @Test
    @DisplayName("GIVEN a chunk fails WHEN loaded strictly THEN the load fails")
    void GIVEN_oneChunkFails_WHEN_loadedStrictly_SHOULD_fail() {
        BatchLoader loader = (keys, metadata) -> Mono.error(new IllegalStateException("down"));
        GrpcBatchDataLoaderRegistrar registrar = mock(GrpcBatchDataLoaderRegistrar.class);
        when(registrar.getLoaders()).thenReturn(Map.of("Book", loader));
        BatchLoaderPipeline pipeline =
                new BatchLoaderPipeline(registrar, (RestBatchDataLoaderRegistrar) null, new DataLoaderConfig());

        assertThatThrownBy(() -> pipeline.load("Book", keys(2), new Metadata()).block())
                .isInstanceOf(IllegalStateException.class);
    }

    private static Map<String, Map<String, Object>> echo(List<String> keys) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (String key : keys) result.put(key, Map.of("id", key));
        return result;
    }

    @Test
    @DisplayName(
            "GIVEN 120 distinct keys, duplicates and max-batch-size 50 WHEN loaded grouped SHOULD make three calls")
    void GIVEN_120Keys_WHEN_loadedGrouped_SHOULD_makeThreeChunkedCallsWithDistinctKeys() {
        List<List<String>> calls = new CopyOnWriteArrayList<>();
        GroupedOutcome.Loader grouped = (ks, md) -> {
            calls.add(List.copyOf(ks));
            Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
            for (String k : ks) groups.put(k, List.of());
            return Mono.just(new GroupedOutcome(groups, Map.of()));
        };
        GrpcBatchDataLoaderRegistrar registrar = mock(GrpcBatchDataLoaderRegistrar.class);
        when(registrar.getGroupedLoaders()).thenReturn(Map.of("Book#by:authorId", grouped));
        DataLoaderConfig config = new DataLoaderConfig();
        config.setMaxBatchSize(50);
        BatchLoaderPipeline pipeline = new BatchLoaderPipeline(registrar, (RestBatchDataLoaderRegistrar) null, config);
        List<String> all = new ArrayList<>(keys(120));
        all.addAll(keys(10));

        GroupedOutcome outcome =
                pipeline.loadGrouped("Book#by:authorId", all, new Metadata()).block();

        assertThat(calls).extracting(List::size).containsExactlyInAnyOrder(50, 50, 20);
        assertThat(outcome.groups()).hasSize(120);
    }

    @Test
    @DisplayName("GIVEN no grouped loader of that name WHEN loaded grouped SHOULD fail every key")
    void GIVEN_noGroupedLoader_WHEN_loadedGrouped_SHOULD_failEveryKey() {
        GrpcBatchDataLoaderRegistrar registrar = mock(GrpcBatchDataLoaderRegistrar.class);
        when(registrar.getGroupedLoaders()).thenReturn(Map.of());
        BatchLoaderPipeline pipeline =
                new BatchLoaderPipeline(registrar, (RestBatchDataLoaderRegistrar) null, new DataLoaderConfig());

        GroupedOutcome outcome = pipeline.loadGrouped("Book#by:authorId", List.of("a", "b"), new Metadata())
                .block();

        assertThat(outcome.failures()).containsOnlyKeys("a", "b");
        assertThat(outcome.groups()).isEmpty();
    }
}
