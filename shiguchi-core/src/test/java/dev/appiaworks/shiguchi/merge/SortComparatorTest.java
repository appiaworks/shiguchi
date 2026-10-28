package dev.appiaworks.shiguchi.merge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD tests for the merge comparator: field-path resolution, direction, tie-breaking, and the
 * validation that keeps a misconfigured sort from reaching the merge at all.
 */
class SortComparatorTest {

    private static SortComparator.SortKey asc(String field) {
        return new SortComparator.SortKey(field, true);
    }

    // ===== ordering =====

    @Test
    @DisplayName("GIVEN an ascending sort key WHEN comparing SHOULD order smallest first")
    void given_ascendingKey_when_comparing_should_orderSmallestFirst() {
        Comparator<Message> comparator = SortComparator.build(MergeTestSupport.RECORD, List.of(asc("rank")), "");

        assertThat(comparator.compare(MergeTestSupport.record(1), MergeTestSupport.record(2)))
                .isNegative();
    }

    @Test
    @DisplayName("GIVEN a descending sort key WHEN comparing SHOULD order largest first")
    void given_descendingKey_when_comparing_should_orderLargestFirst() {
        Comparator<Message> comparator =
                SortComparator.build(MergeTestSupport.RECORD, List.of(new SortComparator.SortKey("rank", false)), "");

        assertThat(comparator.compare(MergeTestSupport.record(1), MergeTestSupport.record(2)))
                .isPositive();
    }

    @Test
    @DisplayName("GIVEN records tied on the primary key WHEN a tie-break field is set SHOULD order by it")
    void given_tiedPrimaryKey_when_tieBreakSet_should_orderByTieBreak() {
        Comparator<Message> comparator = SortComparator.build(MergeTestSupport.RECORD, List.of(asc("rank")), "id");

        Message first = MergeTestSupport.record(5, 1);
        Message second = MergeTestSupport.record(5, 2);

        assertThat(comparator.compare(first, second)).isNegative();
    }

    @Test
    @DisplayName("GIVEN a tie-break field already named in the sort WHEN building SHOULD not apply it twice")
    void given_tieBreakAlreadyInSort_when_building_should_notDuplicateIt() {
        Comparator<Message> comparator = SortComparator.build(
                MergeTestSupport.RECORD, List.of(asc("rank"), new SortComparator.SortKey("id", false)), "id");

        // If the tie-break were appended again ascending, it would fight the explicit descending id.
        Message lowId = MergeTestSupport.record(5, 1);
        Message highId = MergeTestSupport.record(5, 2);

        assertThat(comparator.compare(lowId, highId))
                .as("the caller's explicit descending id must win over the default tie-break")
                .isPositive();
    }

    @Test
    @DisplayName("GIVEN multiple sort keys WHEN the primary ties SHOULD fall through to the secondary")
    void given_multipleKeys_when_primaryTies_should_useSecondary() {
        Comparator<Message> comparator =
                SortComparator.build(MergeTestSupport.RECORD, List.of(asc("rank"), asc("name")), "");

        assertThat(comparator.compare(MergeTestSupport.named(1, "alpha"), MergeTestSupport.named(1, "beta")))
                .isNegative();
    }

    @Test
    @DisplayName("GIVEN a string sort key WHEN comparing SHOULD order by code point, not locale")
    void given_stringKey_when_comparing_should_orderByCodePoint() {
        Comparator<Message> comparator = SortComparator.build(MergeTestSupport.RECORD, List.of(asc("name")), "");

        // Uppercase sorts before lowercase by code point; a locale-sensitive collation would not.
        assertThat(comparator.compare(MergeTestSupport.named(1, "Zebra"), MergeTestSupport.named(1, "apple")))
                .isNegative();
    }

    @Test
    @DisplayName("GIVEN a nested dot-path sort key WHEN comparing SHOULD resolve the nested value")
    void given_dotPathKey_when_comparing_should_resolveNestedValue() {
        var holder = MergeTestSupport.nestedHolder();
        var effective = holder.findFieldByName("effective");
        var inner = effective.getMessageType();

        Message early = DynamicMessage.newBuilder(holder)
                .setField(
                        effective,
                        DynamicMessage.newBuilder(inner)
                                .setField(inner.findFieldByName("start_date"), 100L)
                                .build())
                .build();
        Message late = DynamicMessage.newBuilder(holder)
                .setField(
                        effective,
                        DynamicMessage.newBuilder(inner)
                                .setField(inner.findFieldByName("start_date"), 200L)
                                .build())
                .build();

        Comparator<Message> comparator = SortComparator.build(holder, List.of(asc("effective.start_date")), "");

        assertThat(comparator.compare(early, late)).isNegative();
    }

    @Test
    @DisplayName("GIVEN an unset nested message WHEN comparing SHOULD sort it before any set value")
    void given_unsetNestedMessage_when_comparing_should_sortFirst() {
        var holder = MergeTestSupport.nestedHolder();
        var effective = holder.findFieldByName("effective");
        var inner = effective.getMessageType();

        Message unset = DynamicMessage.newBuilder(holder)
                .setField(holder.findFieldByName("id"), 1L)
                .build();
        Message set = DynamicMessage.newBuilder(holder)
                .setField(
                        effective,
                        DynamicMessage.newBuilder(inner)
                                .setField(inner.findFieldByName("start_date"), 100L)
                                .build())
                .build();

        Comparator<Message> comparator = SortComparator.build(holder, List.of(asc("effective.start_date")), "");

        assertThat(comparator.compare(unset, set)).isNegative();
        assertThat(comparator.compare(set, unset)).isPositive();
        assertThat(comparator.compare(unset, unset)).isZero();
    }

    @Test
    @DisplayName("GIVEN records from two backends with separately built descriptors WHEN comparing "
            + "SHOULD order them, not reject one as a foreign message type")
    void given_recordsFromDistinctDescriptorInstances_when_comparing_should_orderThem() {
        // The case that matters in a real merge: every backend supplies its own descriptor
        // instance, and protobuf matches field descriptors by identity. A comparator that reused
        // one source's FieldDescriptor against another's message threw "FieldDescriptor does not
        // match message type" the first time two live backends were merged.
        var otherSchema = MergeTestSupport.independentRecordDescriptor();
        Message fromOtherBackend = DynamicMessage.newBuilder(otherSchema)
                .setField(otherSchema.findFieldByName("rank"), 1L)
                .setField(otherSchema.findFieldByName("id"), 1L)
                .build();

        Comparator<Message> comparator = SortComparator.build(MergeTestSupport.RECORD, List.of(asc("rank")), "id");

        assertThat(comparator.compare(fromOtherBackend, MergeTestSupport.record(2)))
                .isNegative();
        assertThat(comparator.compare(MergeTestSupport.record(2), fromOtherBackend))
                .isPositive();
    }

    // ===== validation: fail at build time, not per record =====

    @Test
    @DisplayName("GIVEN no sort fields WHEN building SHOULD fail, because merged order would be undefined")
    void given_noSortFields_when_building_should_fail() {
        assertThatThrownBy(() -> SortComparator.build(MergeTestSupport.RECORD, List.of(), ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one sort field");
    }

    @Test
    @DisplayName("GIVEN an unknown sort field WHEN building SHOULD fail with the offending path named")
    void given_unknownField_when_building_should_fail() {
        assertThatThrownBy(() -> SortComparator.build(MergeTestSupport.RECORD, List.of(asc("nope")), ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown sort field")
                .hasMessageContaining("nope");
    }

    @Test
    @DisplayName("GIVEN a repeated sort field WHEN building SHOULD reject it as unorderable")
    void given_repeatedField_when_building_should_reject() {
        assertThatThrownBy(() -> SortComparator.build(MergeTestSupport.RECORD, List.of(asc("tags")), ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repeated");
    }

    @Test
    @DisplayName("GIVEN a message-typed sort field WHEN building SHOULD reject it as unorderable")
    void given_messageField_when_building_should_reject() {
        assertThatThrownBy(() -> SortComparator.build(MergeTestSupport.nestedHolder(), List.of(asc("effective")), ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("names a message");
    }

    @Test
    @DisplayName("GIVEN a path descending into a scalar WHEN building SHOULD reject it")
    void given_pathThroughScalar_when_building_should_reject() {
        assertThatThrownBy(() -> SortComparator.build(MergeTestSupport.RECORD, List.of(asc("rank.nested")), ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scalar");
    }

    @Test
    @DisplayName("GIVEN a blank sort field WHEN building SHOULD reject it")
    void given_blankField_when_building_should_reject() {
        assertThatThrownBy(() -> SortComparator.build(MergeTestSupport.RECORD, List.of(asc("  ")), ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }
}
