package dev.appiaworks.shiguchi.backend;

import dev.appiaworks.protobuf.extras.api.ApiStatus;

/**
 * The page sizes a list method applies: what a request for "0" gets, and the most any request can
 * ask for. A client should not be able to request the whole table.
 *
 * @param defaultSize used when the request leaves {@code page_size} at 0 or negative
 * @param maxSize the ceiling for any request
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.21.0")
public record PageDefaults(int defaultSize, int maxSize) {

    public PageDefaults {
        if (defaultSize < 1 || maxSize < defaultSize) {
            throw new IllegalArgumentException(
                    "Need 1 <= defaultSize <= maxSize, got " + defaultSize + " and " + maxSize);
        }
    }

    public static PageDefaults of(int defaultSize, int maxSize) {
        return new PageDefaults(defaultSize, maxSize);
    }

    int clamp(int requested) {
        return requested <= 0 ? defaultSize : Math.min(requested, maxSize);
    }
}
