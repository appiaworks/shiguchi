package dev.appiaworks.shiguchi.merge;

import java.util.List;
import java.util.Objects;

/**
 * Where a merged, paged result set left off.
 *
 * <p>No single backend token can express this. A backend's {@code page_token} says how far into
 * <em>that</em> backend the caller has read; merged position is how far into every source at once,
 * plus enough of the sort key to know where the boundary fell relative to the other sources'
 * interleaved rows. Neither backend knows the others exist, so the gateway mints this itself.
 *
 * @param lastKey rendered sort-key value of the last record emitted, shared across all sources. On
 *     resume, records ordering at or before this are already-served and get skipped.
 * @param sources per-source resume state, positionally aligned with {@link MergeSpec#sources()}.
 */
public record MergeCursor(String lastKey, List<SourcePosition> sources) {

    /**
     * Token standing in for "this source was materialised, not resumed by a backend cursor".
     *
     * <p>An unsorted source has no backend page_token to carry — it is read whole on every page —
     * but it still has to be distinguishable from an exhausted source, whose token is empty. This
     * marker says "live, but there is nothing for the backend to resume from".
     */
    public static final String MATERIALISED_MARKER = "+";

    public MergeCursor {
        Objects.requireNonNull(lastKey, "lastKey");
        Objects.requireNonNull(sources, "sources");
        sources = List.copyOf(sources);
    }

    /** True once no source has anything left — the merge is finished and mints no next cursor. */
    public boolean exhausted() {
        return sources.stream().allMatch(SourcePosition::exhausted);
    }

    /**
     * One source's position within the merge.
     *
     * @param token that backend's own opaque {@code page_token}; empty means the source is spent
     * @param emittedAtLastKey how many records with exactly {@code lastKey} this source already
     *     contributed. This is what makes ties safe across a page boundary: when several records
     *     share a sort-key value and the page cut lands inside that group, the count says how far
     *     into it each source had got, so the next page resumes without repeating or skipping rows.
     */
    public record SourcePosition(String token, int emittedAtLastKey) {

        public SourcePosition {
            Objects.requireNonNull(token, "token");
            if (emittedAtLastKey < 0) {
                throw new IllegalArgumentException("emittedAtLastKey must not be negative, got " + emittedAtLastKey);
            }
        }

        /** A source with nothing left to read. */
        public static SourcePosition exhaustedSource() {
            return new SourcePosition("", 0);
        }

        public boolean exhausted() {
            return token.isEmpty();
        }
    }
}
