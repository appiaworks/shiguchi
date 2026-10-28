package dev.appiaworks.shiguchi.merge;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Message;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * BDD tests for the N-way merge: ordering across any number of sources, the degenerate shapes, and
 * the backpressure and cancellation behaviour a page-bounded merge depends on.
 */
class HeapMergeTest {

    private static final Comparator<Message> BY_RANK = MergeTestSupport.byRank();

    // ===== ordering =====

    @Test
    @DisplayName("GIVEN two sorted streams WHEN merging SHOULD interleave them in sort order")
    void given_twoSortedStreams_when_merging_should_interleaveInSortOrder() {
        Flux<Message> merged =
                HeapMerge.merge(List.of(MergeTestSupport.source(1, 3, 5), MergeTestSupport.source(2, 4, 6)), BY_RANK);

        assertThat(MergeTestSupport.ranksOf(merged.collectList().block())).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
    }

    @Test
    @DisplayName("GIVEN five sorted streams WHEN merging SHOULD interleave all of them in sort order")
    void given_fiveSortedStreams_when_merging_should_interleaveInSortOrder() {
        List<Flux<Message>> sources = List.of(
                MergeTestSupport.source(1, 6, 11),
                MergeTestSupport.source(2, 7, 12),
                MergeTestSupport.source(3, 8, 13),
                MergeTestSupport.source(4, 9, 14),
                MergeTestSupport.source(5, 10, 15));

        assertThat(MergeTestSupport.ranksOf(
                        HeapMerge.merge(sources, BY_RANK).collectList().block()))
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L, 14L, 15L);
    }

    @Test
    @DisplayName("GIVEN a single source WHEN merging SHOULD pass it through unchanged")
    void given_singleSource_when_merging_should_passThroughUnchanged() {
        assertThat(MergeTestSupport.ranksOf(HeapMerge.merge(List.of(MergeTestSupport.source(4, 8, 15)), BY_RANK)
                        .collectList()
                        .block()))
                .containsExactly(4L, 8L, 15L);
    }

    @Test
    @DisplayName("GIVEN no sources at all WHEN merging SHOULD return an empty stream")
    void given_noSources_when_merging_should_returnEmpty() {
        StepVerifier.create(HeapMerge.merge(List.of(), BY_RANK)).verifyComplete();
    }

    @Test
    @DisplayName("GIVEN every source empty WHEN merging SHOULD return an empty stream")
    void given_allSourcesEmpty_when_merging_should_returnEmpty() {
        StepVerifier.create(HeapMerge.merge(List.of(Flux.empty(), Flux.empty()), BY_RANK))
                .verifyComplete();
    }

    @Test
    @DisplayName("GIVEN some sources empty WHEN merging SHOULD ignore them and merge the rest")
    void given_someSourcesEmpty_when_merging_should_ignoreThemAndMergeRest() {
        List<Flux<Message>> sources =
                List.of(Flux.empty(), MergeTestSupport.source(2, 4), Flux.empty(), MergeTestSupport.source(1, 3));

        assertThat(MergeTestSupport.ranksOf(
                        HeapMerge.merge(sources, BY_RANK).collectList().block()))
                .containsExactly(1L, 2L, 3L, 4L);
    }

    @Test
    @DisplayName("GIVEN sources with disjoint key ranges WHEN merging SHOULD drain them in sequence")
    void given_disjointKeyRanges_when_merging_should_drainInSequence() {
        List<Flux<Message>> sources = List.of(MergeTestSupport.source(10, 11, 12), MergeTestSupport.source(1, 2, 3));

        assertThat(MergeTestSupport.ranksOf(
                        HeapMerge.merge(sources, BY_RANK).collectList().block()))
                .containsExactly(1L, 2L, 3L, 10L, 11L, 12L);
    }

    @Test
    @DisplayName("GIVEN identical keys in every source WHEN merging SHOULD emit every record exactly once")
    void given_identicalKeysEverywhere_when_merging_should_emitAllExactlyOnce() {
        List<Flux<Message>> sources =
                List.of(MergeTestSupport.source(7, 7, 7), MergeTestSupport.source(7, 7), MergeTestSupport.source(7));

        assertThat(MergeTestSupport.ranksOf(
                        HeapMerge.merge(sources, BY_RANK).collectList().block()))
                .hasSize(6)
                .containsOnly(7L);
    }

    @Test
    @DisplayName("GIVEN a descending sort WHEN merging SHOULD respect the requested direction")
    void given_descendingSort_when_merging_should_respectSortOrder() {
        Comparator<Message> descending =
                SortComparator.build(MergeTestSupport.RECORD, List.of(new SortComparator.SortKey("rank", false)), "");

        List<Flux<Message>> sources = List.of(MergeTestSupport.source(5, 3, 1), MergeTestSupport.source(6, 4, 2));

        assertThat(MergeTestSupport.ranksOf(
                        HeapMerge.merge(sources, descending).collectList().block()))
                .containsExactly(6L, 5L, 4L, 3L, 2L, 1L);
    }

    // ===== the property oracle =====

    @Test
    @DisplayName("GIVEN N random sorted sources WHEN merging SHOULD equal the sorted concatenation of all rows")
    void given_nRandomSortedSources_when_merging_should_equalSortedConcatenation() {
        long seed = 20260907L;
        Random random = new Random(seed);

        for (int trial = 0; trial < 200; trial++) {
            int sourceCount = 1 + random.nextInt(8);
            // A small key space relative to row count, so ties are common rather than incidental.
            int keySpace = 1 + random.nextInt(10);

            List<Flux<Message>> sources = new ArrayList<>(sourceCount);
            List<Message> everything = new ArrayList<>();
            for (int s = 0; s < sourceCount; s++) {
                int rows = random.nextInt(12);
                List<Message> records = new ArrayList<>(rows);
                for (int r = 0; r < rows; r++) {
                    records.add(MergeTestSupport.record(random.nextInt(keySpace), random.nextInt(1_000_000)));
                }
                records.sort(BY_RANK); // the precondition every source must satisfy
                sources.add(Flux.fromIterable(records));
                everything.addAll(records);
            }
            everything.sort(BY_RANK);

            List<Message> merged =
                    HeapMerge.merge(sources, BY_RANK).collectList().block();

            assertThat(MergeTestSupport.ranksOf(merged))
                    .as("seed=%d trial=%d sources=%d", seed, trial, sourceCount)
                    .isEqualTo(MergeTestSupport.ranksOf(everything));
        }
    }

    @Test
    @DisplayName("GIVEN materialised sources WHEN merging lists SHOULD equal the sorted concatenation")
    void given_materialisedSources_when_mergingLists_should_equalSortedConcatenation() {
        long seed = 20260908L;
        Random random = new Random(seed);

        for (int trial = 0; trial < 200; trial++) {
            int sourceCount = 1 + random.nextInt(6);
            List<List<Message>> sources = new ArrayList<>(sourceCount);
            List<Message> everything = new ArrayList<>();
            for (int s = 0; s < sourceCount; s++) {
                List<Message> records = new ArrayList<>();
                for (int r = 0; r < random.nextInt(12); r++) {
                    records.add(MergeTestSupport.record(random.nextInt(8), random.nextInt(1_000_000)));
                }
                sources.add(records); // deliberately unsorted — mergeLists must sort defensively
                everything.addAll(records);
            }
            everything.sort(BY_RANK);

            assertThat(MergeTestSupport.ranksOf(HeapMerge.mergeLists(sources, BY_RANK)))
                    .as("seed=%d trial=%d", seed, trial)
                    .isEqualTo(MergeTestSupport.ranksOf(everything));
        }
    }

    @Test
    @DisplayName("GIVEN unsorted input to the streaming merge WHEN merging SHOULD produce a wrong order, "
            + "documenting why the pre-sorted precondition exists")
    void given_unsortedSource_when_merging_should_produceWrongOrder() {
        // Not a bug being asserted as correct: this pins the failure mode so nobody "fixes" the
        // engine by feeding it unsorted data. Source A hides a 1 behind a 9.
        List<Flux<Message>> sources = List.of(MergeTestSupport.source(9, 1), MergeTestSupport.source(5));

        List<Long> ranks = MergeTestSupport.ranksOf(
                HeapMerge.merge(sources, BY_RANK).collectList().block());

        assertThat(ranks)
                .as("the 1 hidden behind the 9 is emitted last, long after the 5 it should precede")
                .isNotEqualTo(List.of(1L, 5L, 9L))
                .containsExactly(5L, 9L, 1L);
    }

    // ===== backpressure and cancellation =====

    @Test
    @DisplayName("GIVEN a page-sized take WHEN the merge fills early SHOULD cancel the sources still open")
    void given_pageSizeReachedEarly_when_merging_should_cancelOpenSources() {
        MergeTestSupport.RecordingSource a = MergeTestSupport.recordingSource(1, 3, 5, 7, 9);
        MergeTestSupport.RecordingSource b = MergeTestSupport.recordingSource(2, 4, 6, 8, 10);

        List<Message> firstPage = HeapMerge.merge(List.of(a.flux(), b.flux()), BY_RANK)
                .take(3)
                .collectList()
                .block();

        assertThat(MergeTestSupport.ranksOf(firstPage)).containsExactly(1L, 2L, 3L);
        assertThat(a.cancelled() || b.cancelled())
                .as("an early cutoff must cancel the sources rather than drain them")
                .isTrue();
    }

    @Test
    @DisplayName("GIVEN a small prefetch WHEN merging SHOULD bound how much is pulled from each source")
    void given_smallPrefetch_when_merging_should_boundPerSourcePull() {
        MergeTestSupport.RecordingSource a = MergeTestSupport.recordingSource(1, 3, 5, 7, 9);
        MergeTestSupport.RecordingSource b = MergeTestSupport.recordingSource(2, 4, 6, 8, 10);

        StepVerifier.create(HeapMerge.merge(List.of(a.flux(), b.flux()), BY_RANK, 2), 0)
                .expectSubscription()
                .thenRequest(2)
                .expectNextCount(2)
                .thenCancel()
                .verify();

        // Prefetch, not downstream demand, is what bounds the pull — see HeapMerge#merge(List,
        // Comparator, int). Each source is read up to its prefetch (plus replenishment), so the
        // guarantee worth asserting is "bounded by prefetch", not "bounded by what was requested".
        assertThat(a.requested())
                .as("source A must not be drained past its prefetch window")
                .isLessThanOrEqualTo(4);
        assertThat(b.requested())
                .as("source B must not be drained past its prefetch window")
                .isLessThanOrEqualTo(4);
    }

    @Test
    @DisplayName("GIVEN the default prefetch WHEN merging many sources SHOULD stay far below Reactor's own default")
    void given_defaultPrefetch_when_merging_should_stayBelowReactorDefault() {
        // Guards the reason DEFAULT_PREFETCH exists: Reactor's built-in default would pull 256 per
        // source, which on an unbounded backend is hundreds of rows per page.
        assertThat(HeapMerge.DEFAULT_PREFETCH).isLessThan(256);
    }
}
