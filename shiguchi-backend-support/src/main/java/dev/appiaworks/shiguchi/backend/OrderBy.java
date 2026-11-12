package dev.appiaworks.shiguchi.backend;

import dev.appiaworks.protobuf.extras.api.ApiStatus;

/**
 * One resolved sort key: a property of your persistence model and a direction.
 *
 * <p>Produced by {@link SortSpec#resolve}. It is deliberately not a Spring Data {@code Sort.Order},
 * so a JDBC or jOOQ backend can use it without Spring Data on the classpath. Converting is one
 * line: {@code ascending ? Sort.Order.asc(p) : Sort.Order.desc(p)}.
 *
 * @param property the entity property, column or path to order by (never client input)
 * @param ascending {@code true} for ascending
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.21.0")
public record OrderBy(String property, boolean ascending) {

    public static OrderBy asc(String property) {
        return new OrderBy(property, true);
    }

    public static OrderBy desc(String property) {
        return new OrderBy(property, false);
    }
}
