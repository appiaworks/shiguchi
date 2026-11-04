package dev.appiaworks.protobuf.extras.serde;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;

/**
 * The streaming factory the serde reads and writes through. It keeps Jackson 2's behaviour where
 * Jackson 3 moved a default, so the JSON the serde accepts and prints did not change with the
 * upgrade: Jackson 2's number parsing, escaping and thread-local buffer pool, and its read limits
 * (nesting 1000 deep, strings of up to 20 million characters).
 */
@ApiStatus(value = ApiStatus.Status.INTERNAL)
public final class JsonFactories {
    private JsonFactories() {}

    /** A new factory; factories are thread-safe, so callers keep one. */
    public static JsonFactory create() {
        return JsonFactory.builderWithJackson2Defaults()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(1000)
                        .maxStringLength(20_000_000)
                        .build())
                .build();
    }
}
