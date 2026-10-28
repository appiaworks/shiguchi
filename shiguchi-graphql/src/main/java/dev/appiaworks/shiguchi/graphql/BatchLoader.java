package dev.appiaworks.shiguchi.graphql;

import io.grpc.Metadata;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * Common contract for batch loaders that support Apollo Federation _entities resolution.
 *
 * Given a list of string entity IDs and a request Metadata (carries auth headers),
 * returns a Mono of a map from ID → entity object (as a plain JSON-like map).
 * Missing keys are absent from the returned map; callers treat them as null.
 */
public interface BatchLoader {

    Mono<Map<String, Map<String, Object>>> loadMapped(List<String> keys, Metadata metadata);
}
