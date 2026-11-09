package dev.appiaworks.shiguchi.config;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Query guards on the GraphQL surface. A query beyond a limit is rejected before any backend
 * is called, with a GraphQL error naming the limit. All are off ({@code 0}) by default so
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
     * Maximum cost of one operation. Every selected field costs its {@code cost} proto option
     * ({@code (shiguchi.graphql).cost} on a root or resolver field, {@code (shiguchi.field).cost}
     * on a message field; 1 when unset), a list field multiplied by its {@code first} argument or
     * {@link #defaultListSize}, plus the cost of the fields selected under it. {@code 0} =
     * unlimited.
     */
    private int maxComplexity = 0;
    /**
     * Multiplier for a list field whose query sends no {@code first} argument, when computing the
     * cost of an operation.
     */
    private int defaultListSize = 10;
    /** Put the operation's computed cost in the response's {@code extensions.cost}. */
    private boolean reportCost = false;
    /** Per-caller cost budget; off by default. See {@link BudgetConfig}. */
    private BudgetConfig budget = new BudgetConfig();

    /**
     * A cost budget per caller: each operation spends its computed cost from the caller's bucket,
     * which refills at {@code per-minute} points per minute and holds at most that many. The
     * caller is the authenticated principal, else the client IP ({@code shiguchi.rate-limit.key}
     * rules), and the bucket lives in memory, or in Redis when {@code shiguchi.rate-limit.backend}
     * is {@code REDIS}. An operation over the budget is not run: the response carries a
     * {@code RATE_LIMITED} error with a {@code retryAfter} extension and a {@code Retry-After}
     * header, with HTTP 200 as GraphQL-over-HTTP expects.
     */
    @Data
    @NoArgsConstructor
    public static class BudgetConfig {
        /** Cost points a caller may spend per minute. {@code 0} = no budget. */
        private int perMinute = 0;
    }
}
