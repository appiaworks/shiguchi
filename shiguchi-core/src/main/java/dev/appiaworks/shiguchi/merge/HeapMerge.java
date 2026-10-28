package dev.appiaworks.shiguchi.merge;

import com.google.protobuf.Message;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import reactor.core.publisher.Flux;

/**
 * N-way merge of pre-sorted message streams into one sorted stream.
 *
 * <p>Classic k-way merge: hold one record from each source in a heap, emit the smallest, pull the
 * next record from whichever source supplied it. Memory is O(sources) — one head each — however
 * large the sources are, and each emitted record costs O(log sources).
 *
 * <p>Two sources and twenty run the same path; nothing here special-cases a pair.
 *
 * <p><strong>Every input must already be sorted by the same comparator.</strong> This is a merge of
 * sorted sequences, not a sort: the heap only ever sees the current head of each source, so a
 * record that sorts earlier but sits further down a stream is never reconsidered — it is emitted
 * late, out of order, with no error. Feeding an unsorted source here yields output that looks
 * ordered and is wrong. Sources that cannot promise ordering must be sorted before they get here.
 *
 * <p>Cancellation propagates: cancelling the merged {@link Flux} cancels every source, so a page
 * that fills early closes the streams still open instead of draining them.
 *
 * <p>Demand, though, does <em>not</em> pass straight through. The operator eagerly pulls
 * {@code prefetch} records from each source on subscribe — it cannot know which source sorts first
 * without a head from each — so a merge reads up to {@code sources × prefetch} records even under a
 * one-record request. Bound it with {@link #merge(List, Comparator, int)} on any paged route; see
 * that overload for sizing.
 */
public final class HeapMerge {

    private HeapMerge() {}

    /**
     * Default prefetch. Reactor's own default is 32 per source; this drops it to a page-ish size so
     * a merge over unbounded backends does not pull far more than one page needs.
     */
    public static final int DEFAULT_PREFETCH = 32;

    /**
     * Merges with {@link #DEFAULT_PREFETCH}. Prefer
     * {@link #merge(List, Comparator, int)} on a page-bounded path, where the window is known.
     *
     * @param sources streams to merge; each must be sorted by {@code comparator}
     * @param comparator ordering shared by every source and by the output
     * @return one merged, sorted stream — empty when {@code sources} is empty
     */
    public static Flux<Message> merge(List<Flux<Message>> sources, Comparator<Message> comparator) {
        return merge(sources, comparator, DEFAULT_PREFETCH);
    }

    /**
     * @param sources streams to merge; each must be sorted by {@code comparator}
     * @param comparator ordering shared by every source and by the output
     * @param prefetch records to pull eagerly <em>per source</em>
     * @return one merged, sorted stream — empty when {@code sources} is empty
     *     <p><strong>Prefetch is not backpressure.</strong> The underlying operator requests
     *     {@code prefetch} records from every source as soon as it is subscribed, regardless of what
     *     the subscriber has asked for — it needs a head from each source before it can decide which
     *     sorts first, and it buffers ahead beyond that. So a merge of N sources pulls up to
     *     {@code N × prefetch} records even if the caller only ever takes one page. Size this from
     *     the page size on a paged route; leaving it at Reactor's default (256) would pull hundreds
     *     of rows per source per page from an unbounded backend.
     */
    public static Flux<Message> merge(List<Flux<Message>> sources, Comparator<Message> comparator, int prefetch) {
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(comparator, "comparator");
        if (prefetch < 1) {
            throw new IllegalArgumentException("prefetch must be at least 1, got " + prefetch);
        }
        if (sources.isEmpty()) {
            return Flux.empty();
        }
        if (sources.size() == 1) {
            return sources.get(0);
        }
        return Flux.mergeComparing(prefetch, comparator, sources.toArray(Flux[]::new));
    }

    /**
     * Merges already-materialised sources — the path taken when sources could not be trusted to
     * arrive sorted and were therefore buffered and sorted gateway-side first.
     *
     * <p>Each list is sorted defensively before merging rather than assumed ordered: having already
     * paid to materialise them, the cost of guaranteeing the merge's precondition is small next to
     * the cost of violating it.
     */
    public static List<Message> mergeLists(List<List<Message>> sources, Comparator<Message> comparator) {
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(comparator, "comparator");

        List<List<Message>> sorted = new ArrayList<>(sources.size());
        int total = 0;
        for (List<Message> source : sources) {
            List<Message> copy = new ArrayList<>(source);
            copy.sort(comparator);
            sorted.add(copy);
            total += copy.size();
        }

        PriorityQueue<Head> heap = new PriorityQueue<>((a, b) -> comparator.compare(a.value(), b.value()));
        for (int i = 0; i < sorted.size(); i++) {
            if (!sorted.get(i).isEmpty()) {
                heap.add(new Head(i, 0, sorted.get(i).get(0)));
            }
        }

        List<Message> merged = new ArrayList<>(total);
        while (!heap.isEmpty()) {
            Head head = heap.poll();
            merged.add(head.value());
            int next = head.index() + 1;
            List<Message> source = sorted.get(head.source());
            if (next < source.size()) {
                heap.add(new Head(head.source(), next, source.get(next)));
            }
        }
        return merged;
    }

    /** One source's current position during a materialised merge. */
    private record Head(int source, int index, Message value) {}
}
