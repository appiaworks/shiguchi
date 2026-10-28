package dev.appiaworks.shiguchi.rest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.merge.MergeSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD tests for the merge request/response contract: how a caller's paging and sorting arguments
 * are translated per source, and what the two source modes actually do differently.
 *
 * <p>The merge algorithm itself is covered in {@code dev.appiaworks.shiguchi.merge}; these tests are about
 * the dispatch decisions layered on top — which is where the pre-sorted/unsorted distinction turns
 * into different requests going out to different backends.
 */
class MergeDispatcherTest {

    @Test
    @DisplayName("GIVEN a pre-sorted source WHEN building its request SHOULD push the sort down to the backend")
    void given_presortedSource_when_buildingRequest_should_pushSortDown() {
        Map<String, Object> caller = Map.of(
                "sortFields", List.of(Map.of("field", "name", "sortOrder", "SORT_ORDER_ASC")),
                "pageRequest", Map.of("pageSize", 25));

        Map<String, Object> sent = MergeRequestPlanner.argsForSource(caller, null, true, 75);

        assertThat(sent).containsKey("sortFields");
        assertThat(sent.get("pageRequest"))
                .as("a backend that sorts can also page, so only a window is requested")
                .isEqualTo(Map.of("pageSize", 75));
    }

    @Test
    @DisplayName("GIVEN an unsorted source WHEN building its request SHOULD drop the sort and not page it")
    void given_unsortedSource_when_buildingRequest_should_dropSortAndPaging() {
        Map<String, Object> caller = Map.of(
                "sortFields", List.of(Map.of("field", "name", "sortOrder", "SORT_ORDER_ASC")),
                "pageRequest", Map.of("pageSize", 25));

        Map<String, Object> sent = MergeRequestPlanner.argsForSource(caller, null, false, 75);

        // Asking an unsorted backend for a page returns an arbitrary subset, not the smallest rows,
        // so the merge would order the wrong records. It has to be read whole and sorted here.
        assertThat(sent).doesNotContainKey("sortFields");
        assertThat(sent).doesNotContainKey("pageRequest");
    }

    @Test
    @DisplayName("GIVEN caller filters WHEN building a source request SHOULD pass them through untouched")
    void given_callerFilters_when_buildingRequest_should_passThrough() {
        Map<String, Object> caller = Map.of("id", List.of(1, 2, 3), "pageRequest", Map.of("pageSize", 10));

        Map<String, Object> sent = MergeRequestPlanner.argsForSource(caller, null, false, 30);

        assertThat(sent.get("id")).isEqualTo(List.of(1, 2, 3));
    }

    @Test
    @DisplayName("GIVEN no page size from the caller WHEN planning SHOULD fall back to the default")
    void given_noPageSize_when_planning_should_useDefault() {
        assertThat(MergeRequestPlanner.pageSizeOf(Map.of())).isEqualTo(50);
    }

    @Test
    @DisplayName("GIVEN a zero or negative page size WHEN planning SHOULD fall back to the default")
    void given_nonPositivePageSize_when_planning_should_useDefault() {
        assertThat(MergeRequestPlanner.pageSizeOf(Map.of("pageRequest", Map.of("pageSize", 0))))
                .isEqualTo(50);
    }

    @Test
    @DisplayName("GIVEN a caller page size WHEN planning SHOULD honour it")
    void given_callerPageSize_when_planning_should_honourIt() {
        assertThat(MergeRequestPlanner.pageSizeOf(Map.of("pageRequest", Map.of("pageSize", 7))))
                .isEqualTo(7);
    }

    @Test
    @DisplayName("GIVEN descending sort from the caller WHEN parsing keys SHOULD mark the key descending")
    void given_descendingSort_when_parsingKeys_should_markDescending() {
        var keys = MergeRequestPlanner.sortKeysOf(
                Map.of("sortFields", List.of(Map.of("field", "rank", "sortOrder", "SORT_ORDER_DESC"))));

        assertThat(keys).hasSize(1);
        assertThat(keys.get(0).field()).isEqualTo("rank");
        assertThat(keys.get(0).ascending()).isFalse();
    }

    @Test
    @DisplayName("GIVEN multiple sort keys WHEN parsing SHOULD preserve caller order, primary first")
    void given_multipleSortKeys_when_parsing_should_preserveOrder() {
        var keys = MergeRequestPlanner.sortKeysOf(Map.of(
                "sortFields",
                List.of(
                        Map.of("field", "rank", "sortOrder", "SORT_ORDER_ASC"),
                        Map.of("field", "name", "sortOrder", "SORT_ORDER_DESC"))));

        assertThat(keys).hasSize(2);
        assertThat(keys.get(0).field()).isEqualTo("rank");
        assertThat(keys.get(1).field()).isEqualTo("name");
    }

    @Test
    @DisplayName("GIVEN an unspecified sort order WHEN parsing SHOULD default to ascending")
    void given_unspecifiedOrder_when_parsing_should_defaultAscending() {
        var keys = MergeRequestPlanner.sortKeysOf(Map.of("sortFields", List.of(Map.of("field", "rank"))));

        assertThat(keys.get(0).ascending()).isTrue();
    }

    @Test
    @DisplayName("GIVEN a spec with every source pre-sorted WHEN asked SHOULD report it can stream")
    void given_allPresorted_when_asked_should_reportStreamable() {
        var spec = new MergeSpec(
                List.of(
                        dev.appiaworks.shiguchi.merge.MergeSource.presorted("a.A", "list"),
                        dev.appiaworks.shiguchi.merge.MergeSource.presorted("b.B", "list")),
                "id",
                null,
                null,
                0);

        assertThat(spec.canStreamAll()).isTrue();
    }

    @Test
    @DisplayName("GIVEN one unsorted source WHEN asked SHOULD report the merge cannot stream throughout")
    void given_oneUnsorted_when_asked_should_reportNotStreamable() {
        var spec = new MergeSpec(
                List.of(
                        dev.appiaworks.shiguchi.merge.MergeSource.presorted("a.A", "list"),
                        dev.appiaworks.shiguchi.merge.MergeSource.unsorted("b.B", "list")),
                "id",
                null,
                null,
                0);

        assertThat(spec.canStreamAll()).isFalse();
    }
}
