package dev.appiaworks.shiguchi.graphql;

import io.grpc.Metadata;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * The result of one one-to-many batch load: for each parent key that loaded, its child entities
 * (empty when it has none), and the keys that failed with why. As {@link BatchOutcome}, with a list
 * of entities per key instead of one.
 */
record GroupedOutcome(Map<String, List<Map<String, Object>>> groups, Map<String, Throwable> failures) {

    static final GroupedOutcome EMPTY = new GroupedOutcome(Map.of(), Map.of());

    /** Every one of {@code keys} failed with {@code cause}. */
    static GroupedOutcome failed(List<String> keys, Throwable cause) {
        Map<String, Throwable> failures = new LinkedHashMap<>(keys.size() * 4 / 3 + 1);
        for (String key : keys) failures.put(key, cause);
        return new GroupedOutcome(Map.of(), failures);
    }

    boolean hasFailures() {
        return !failures.isEmpty();
    }

    GroupedOutcome merge(GroupedOutcome other) {
        Map<String, List<Map<String, Object>>> mergedGroups = new LinkedHashMap<>(groups);
        mergedGroups.putAll(other.groups);
        Map<String, Throwable> mergedFailures = new LinkedHashMap<>(failures);
        mergedFailures.putAll(other.failures);
        return new GroupedOutcome(mergedGroups, mergedFailures);
    }

    /** A loader of child entities grouped by the parent key they belong to. */
    interface Loader {

        Mono<GroupedOutcome> loadGrouped(List<String> keys, Metadata metadata);
    }

    /** {@code loader}'s outcome for {@code keys}; an error it throws or ends with fails all of them. */
    static Mono<GroupedOutcome> of(Loader loader, List<String> keys, Metadata metadata) {
        return Mono.defer(() -> loader.loadGrouped(keys, metadata)).onErrorResume(e -> Mono.just(failed(keys, e)));
    }
}
