package dev.appiaworks.shiguchi.merge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** BDD tests for the merge declaration: defaults that fail safe, and validation of the sources. */
class MergeSpecTest {

    private static MergeSpec spec(List<MergeSource> sources) {
        return new MergeSpec(sources, "id", null, null, 0);
    }

    private static final MergeSource A = MergeSource.unsorted("svc.A", "list");
    private static final MergeSource B = MergeSource.unsorted("svc.B", "list");

    @Test
    @DisplayName("GIVEN no explicit strategies WHEN building a spec SHOULD default to the safe choices")
    void given_noStrategies_when_building_should_defaultSafely() {
        MergeSpec merge = spec(List.of(A, B));

        assertThat(merge.totalCountStrategy())
                .as("summing counts is only valid for disjoint sources, which cannot be verified here")
                .isEqualTo(MergeSpec.TotalCountStrategy.UNKNOWN);
        assertThat(merge.failurePolicy())
                .as("dropping a failed source would return a plausible page with rows missing")
                .isEqualTo(MergeSpec.FailurePolicy.FAIL_FAST);
    }

    @Test
    @DisplayName("GIVEN a source with no sortedness stated WHEN declared SHOULD default to unsorted")
    void given_unstatedSortedness_when_declared_should_defaultToUnsorted() {
        assertThat(MergeSource.unsorted("svc.A", "list").presorted())
                .as("the gateway cannot verify backend ordering, so it must not assume it")
                .isFalse();
    }

    @Test
    @DisplayName("GIVEN every source pre-sorted WHEN asked SHOULD report the merge can stream")
    void given_allPresorted_when_asked_should_reportStreamable() {
        MergeSpec merge = spec(List.of(MergeSource.presorted("svc.A", "list"), MergeSource.presorted("svc.B", "list")));

        assertThat(merge.canStreamAll()).isTrue();
    }

    @Test
    @DisplayName("GIVEN one unsorted source among pre-sorted ones WHEN asked SHOULD report it cannot stream")
    void given_oneUnsortedSource_when_asked_should_reportNotStreamable() {
        MergeSpec merge = spec(List.of(MergeSource.presorted("svc.A", "list"), B));

        assertThat(merge.canStreamAll()).isFalse();
    }

    @Test
    @DisplayName("GIVEN fewer than two sources WHEN building a spec SHOULD reject it as not a merge")
    void given_tooFewSources_when_building_should_reject() {
        assertThatThrownBy(() -> spec(List.of(A)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least two sources");
    }

    @Test
    @DisplayName("GIVEN a caller mutating the source list afterwards WHEN reading the spec SHOULD be unaffected")
    void given_mutatedSourceList_when_reading_should_beUnaffected() {
        List<MergeSource> mutable = new ArrayList<>(List.of(A, B));
        MergeSpec merge = spec(mutable);
        mutable.add(MergeSource.unsorted("svc.C", "list"));

        assertThat(merge.sources())
                .as("source order defines cursor ordinals, so the spec must own its own copy")
                .hasSize(2);
    }

    @Test
    @DisplayName("GIVEN a negative window size WHEN building a spec SHOULD reject it")
    void given_negativeWindow_when_building_should_reject() {
        assertThatThrownBy(() -> new MergeSpec(List.of(A, B), "id", null, null, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("windowSize");
    }

    @Test
    @DisplayName("GIVEN a blank service or method name WHEN declaring a source SHOULD reject it")
    void given_blankNames_when_declaring_should_reject() {
        assertThatThrownBy(() -> MergeSource.unsorted("  ", "list"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fullServiceName");
        assertThatThrownBy(() -> MergeSource.unsorted("svc.A", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("methodName");
    }

    @Test
    @DisplayName("GIVEN a source WHEN labelled SHOULD render service and method for logs")
    void given_source_when_labelled_should_renderServiceAndMethod() {
        assertThat(MergeSource.unsorted("svc.A", "list").label()).isEqualTo("svc.A/list");
    }
}
