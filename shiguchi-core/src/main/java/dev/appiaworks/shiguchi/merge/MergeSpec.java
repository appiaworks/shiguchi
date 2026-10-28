package dev.appiaworks.shiguchi.merge;

import java.util.List;
import java.util.Objects;

/**
 * Declares that one gateway route is served by merging the responses of N backend RPCs that all
 * return the same message type.
 *
 * <p>The sources are merged under the caller's {@code sortFields} (the same spelling
 * {@code ConventionArgMapper} normalises for both HTTP surfaces), so a caller sees one sorted,
 * paged result set and cannot tell it came from more than one backend.
 *
 * <p>Nothing here is pair-specific: two sources and twenty sources run the same code. What grows
 * with N is cursor size, fan-out width, and — in {@link SourceMode#UNSORTED} — buffered memory.
 *
 * @param sources ordered participants. <strong>Order is significant</strong>: a page cursor
 *     addresses sources by their index here, so reordering the list invalidates every cursor
 *     already handed out. Must hold at least two entries.
 * @param tieBreakField sort field appended last to make the merge order total. Without it, records
 *     sharing a sort-key value have no defined relative order, and a page boundary landing inside
 *     such a group cannot say which records it already emitted. Empty means "caller's sort is
 *     already unique" — only safe when that is genuinely true.
 * @param totalCountStrategy how to report {@code page_info.total_count} for the merged result.
 * @param failurePolicy what to do when one source fails.
 * @param windowSize maximum records to pull from a single source while assembling one page. Caps
 *     the lookahead tie handling and exhaustion detection may need; 0 means "use the configured
 *     default".
 */
public record MergeSpec(
        List<MergeSource> sources,
        String tieBreakField,
        TotalCountStrategy totalCountStrategy,
        FailurePolicy failurePolicy,
        int windowSize) {

    public MergeSpec {
        Objects.requireNonNull(sources, "sources");
        if (sources.size() < 2) {
            throw new IllegalArgumentException("a merge needs at least two sources, got " + sources.size());
        }
        sources = List.copyOf(sources);
        tieBreakField = tieBreakField != null ? tieBreakField : "";
        totalCountStrategy = totalCountStrategy != null ? totalCountStrategy : TotalCountStrategy.UNKNOWN;
        failurePolicy = failurePolicy != null ? failurePolicy : FailurePolicy.FAIL_FAST;
        if (windowSize < 0) {
            throw new IllegalArgumentException("windowSize must not be negative, got " + windowSize);
        }
    }

    /**
     * True when every source is declared pre-sorted, so the merge can stream: heads only, memory
     * O(sources) whatever the result size. A single unsorted source forces that source to be
     * materialised before it can enter the heap.
     */
    public boolean canStreamAll() {
        return sources.stream().allMatch(MergeSource::presorted);
    }

    /** How a merged {@code total_count} is derived from the per-source counts. */
    public enum TotalCountStrategy {
        /**
         * Always report "not computed" (-1). The safe default: summing is only correct when the
         * sources hold disjoint rows, which the gateway cannot verify.
         */
        UNKNOWN,

        /**
         * Sum the per-source totals. Correct <em>only</em> for sources known to be disjoint —
         * overlapping id spaces double-count. Falls back to -1 if any source reports -1.
         */
        SUM
    }

    /** What to do when a source errors while a page is being assembled. */
    public enum FailurePolicy {
        /**
         * Fail the whole page. The default, because a merge that quietly drops a source returns a
         * page that looks right and is missing rows — with no signal to the caller.
         */
        FAIL_FAST,

        /**
         * Serve the merge of the surviving sources and name the failed ones in the response. Opt-in,
         * for routes where a degraded page genuinely beats an error.
         */
        BEST_EFFORT
    }
}
