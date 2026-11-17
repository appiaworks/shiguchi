package dev.appiaworks.shiguchi.graphql;

import io.grpc.Metadata;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * The result of one batch load that may have failed in part: the entities that came back, and the
 * keys that failed with why. A key in neither map is absent (the backend has no such entity).
 */
record BatchOutcome(Map<String, Map<String, Object>> entities, Map<String, Throwable> failures) {

    static final BatchOutcome EMPTY = new BatchOutcome(Map.of(), Map.of());

    static BatchOutcome ok(Map<String, Map<String, Object>> entities) {
        return new BatchOutcome(entities, Map.of());
    }

    /** Every one of {@code keys} failed with {@code cause}. */
    static BatchOutcome failed(List<String> keys, Throwable cause) {
        Map<String, Throwable> failures = new LinkedHashMap<>(keys.size() * 4 / 3 + 1);
        for (String key : keys) failures.put(key, cause);
        return new BatchOutcome(Map.of(), failures);
    }

    boolean hasFailures() {
        return !failures.isEmpty();
    }

    BatchOutcome merge(BatchOutcome other) {
        Map<String, Map<String, Object>> mergedEntities = new LinkedHashMap<>(entities);
        mergedEntities.putAll(other.entities);
        Map<String, Throwable> mergedFailures = new LinkedHashMap<>(failures);
        mergedFailures.putAll(other.failures);
        return new BatchOutcome(mergedEntities, mergedFailures);
    }

    /** The entities when nothing failed, otherwise an error carrying the first failure. */
    Mono<Map<String, Map<String, Object>>> strict() {
        if (failures.isEmpty()) return Mono.just(entities);
        return Mono.error(failures.values().iterator().next());
    }

    /** A loader that can report some of its keys failed while others loaded. */
    interface Loader extends BatchLoader {

        Mono<BatchOutcome> loadOutcome(List<String> keys, Metadata metadata);

        @Override
        default Mono<Map<String, Map<String, Object>>> loadMapped(List<String> keys, Metadata metadata) {
            return loadOutcome(keys, metadata).flatMap(BatchOutcome::strict);
        }
    }

    /** {@code loader}'s outcome for {@code keys}: its own when it reports one, else all-or-nothing. */
    static Mono<BatchOutcome> of(BatchLoader loader, List<String> keys, Metadata metadata) {
        return Mono.defer(() -> loader instanceof Loader partial
                        ? partial.loadOutcome(keys, metadata)
                        : loader.loadMapped(keys, metadata).map(BatchOutcome::ok))
                .onErrorResume(e -> Mono.just(BatchOutcome.failed(keys, e)));
    }
}
