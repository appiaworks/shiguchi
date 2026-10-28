package dev.appiaworks.shiguchi.config;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Query guards on the GraphQL surface. A query beyond a limit is rejected before any backend
 * is called, with a GraphQL error naming the limit. Both are off ({@code 0}) by default so
 * existing schemas keep working; a public gateway should set them.
 */
@Data
@NoArgsConstructor
public class LimitsConfig {
    /**
     * Maximum selection depth ({@code { a { b { c } } }} is 3). {@code 0} = unlimited.
     * Federation's {@code _entities} and introspection count like any other field.
     */
    private int maxDepth = 0;
    /**
     * Maximum query complexity, graphql-java's default calculation: one point per field in
     * the selection, so a list of 100 items costs the same as one item. {@code 0} = unlimited.
     */
    private int maxComplexity = 0;
}
