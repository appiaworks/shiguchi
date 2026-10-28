package dev.appiaworks.shiguchi.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One merged endpoint declared in configuration: a single HTTP route whose response is gathered
 * from several backends.
 *
 * <p>The equivalent of the {@code (merge)} proto option, for backends whose protos you do not
 * control. See {@code docs/merging.md} for how ordering, paging and failure policy interact.
 */
@Data
@NoArgsConstructor
public class MergeRouteConfig {
    /** HTTP path this merge serves, e.g. {@code /v1/orders}. */
    private String path;

    /** HTTP method; defaults to GET. */
    private String method = "GET";

    /**
     * Stable, unique field appended as the final sort key. Without one, records sharing a sort
     * value have no defined relative order, and a page boundary landing inside such a group
     * cannot say which rows it already served — so pages can repeat or drop them.
     */
    private String tieBreakField = "";

    /** UNKNOWN (default, reports -1) or SUM — only valid when sources hold disjoint rows. */
    private String totalCountStrategy = "UNKNOWN";

    /** FAIL_FAST (default) or BEST_EFFORT. */
    private String failurePolicy = "FAIL_FAST";

    /** Overrides the computed window; 0 uses {@code window-multiplier × page size}. */
    private int windowSize = 0;

    private List<MergeSourceConfig> sources = new ArrayList<>();
}
