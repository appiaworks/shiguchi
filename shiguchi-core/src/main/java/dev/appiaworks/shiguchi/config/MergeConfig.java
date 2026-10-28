package dev.appiaworks.shiguchi.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Cross-backend merge: routes served by fanning out to several backends returning the same
 * message type, ordered by the caller's sort. See docs/merging.md.
 */
@Data
@NoArgsConstructor
public class MergeConfig {
    /**
     * Upper bound on sources per merge route. Not an algorithmic limit — the merge is N-way —
     * but a blast-radius one: each source is another concurrent call per page, per caller.
     * Validated when routes are registered, so an over-wide merge fails at startup rather than
     * on someone's second page.
     */
    private int maxSources = 8;

    /**
     * Records fetched per source per page, as a multiple of the page size. The extra room lets
     * the merge see past a tie straddling the page boundary and tell "exhausted" from
     * "more to come". Only applies to pre-sorted sources; an unsorted source is read whole.
     */
    private int windowMultiplier = 3;

    /** Merge route declarations. Overridden by a MergeSourceProvider bean if one matches. */
    private List<MergeRouteConfig> routes = new ArrayList<>();
}
