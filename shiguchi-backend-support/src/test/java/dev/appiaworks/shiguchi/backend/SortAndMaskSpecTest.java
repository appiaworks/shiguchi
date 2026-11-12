package dev.appiaworks.shiguchi.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.FieldMask;
import dev.appiaworks.shiguchi.proto.pagination.SortField;
import dev.appiaworks.shiguchi.proto.pagination.SortOrder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SortSpec and MaskSpec")
class SortAndMaskSpecTest {

    private static SortField sort(String field, SortOrder order) {
        return SortField.newBuilder().setField(field).setSortOrder(order).build();
    }

    private static final SortSpec SORT = SortSpec.builder()
            .field("title")
            .field("published_year", "year")
            .field("author", "author.lastName")
            .build();

    @Test
    @DisplayName("GIVEN no sort keys WHEN resolved SHOULD order by the tie-breaker alone")
    void given_noKeys_when_resolve_should_orderById() {
        assertThat(SORT.resolve(List.of())).containsExactly(OrderBy.asc("id"));
    }

    @Test
    @DisplayName("GIVEN a default sort WHEN no keys are sent SHOULD apply it before the tie-breaker")
    void given_defaultSort_when_noKeys_should_applyIt() {
        SortSpec spec = SortSpec.builder()
                .field("title")
                .defaultSort(OrderBy.desc("title"))
                .build();

        assertThat(spec.resolve(List.of())).containsExactly(OrderBy.desc("title"), OrderBy.asc("id"));
    }

    @Test
    @DisplayName("GIVEN keys in either spelling WHEN resolved SHOULD map names, honour direction and append the id")
    void given_keys_when_resolve_should_mapAndTieBreak() {
        List<OrderBy> orders = SORT.resolve(List.of(
                sort("publishedYear", SortOrder.SORT_ORDER_DESC), sort("AUTHOR", SortOrder.SORT_ORDER_UNSPECIFIED)));

        assertThat(orders).containsExactly(OrderBy.desc("year"), OrderBy.asc("author.lastName"), OrderBy.asc("id"));
    }

    @Test
    @DisplayName("GIVEN a repeated key and a blank entry WHEN resolved SHOULD keep the first and skip the blank")
    void given_repeatedAndBlank_when_resolve_should_dedupe() {
        List<OrderBy> orders = SORT.resolve(List.of(
                sort("title", SortOrder.SORT_ORDER_DESC),
                sort("", SortOrder.SORT_ORDER_ASC),
                sort("title", SortOrder.SORT_ORDER_ASC)));

        assertThat(orders).containsExactly(OrderBy.desc("title"), OrderBy.asc("id"));
    }

    @Test
    @DisplayName("GIVEN the id is sorted explicitly WHEN resolved SHOULD not append it twice")
    void given_explicitId_when_resolve_should_notDuplicate() {
        SortSpec spec = SortSpec.of("id", "title");

        assertThat(spec.resolve(List.of(sort("id", SortOrder.SORT_ORDER_DESC)))).containsExactly(OrderBy.desc("id"));
    }

    @Test
    @DisplayName("GIVEN a field outside the allow-list WHEN resolved SHOULD fail with INVALID_ARGUMENT")
    void given_unknownField_when_resolve_should_beInvalidArgument() {
        assertThatThrownBy(() -> SORT.resolve(List.of(sort("password", SortOrder.SORT_ORDER_ASC))))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
                    assertThat(e.getStatus().getDescription()).contains("password");
                });
    }

    @Test
    @DisplayName("GIVEN noTieBreaker WHEN resolved SHOULD return only the requested keys")
    void given_noTieBreaker_when_resolve_should_returnRequestedOnly() {
        SortSpec spec = SortSpec.builder().field("title").noTieBreaker().build();

        assertThat(spec.resolve(List.of(sort("title", SortOrder.SORT_ORDER_ASC))))
                .containsExactly(OrderBy.asc("title"));
    }

    private static final MaskSpec MASK = MaskSpec.builder()
            .field("id")
            .field("title")
            .field("published_year", "year")
            .build()
            .alwaysInclude("id");

    private static FieldMask mask(String... paths) {
        return FieldMask.newBuilder().addAllPaths(List.of(paths)).build();
    }

    @Test
    @DisplayName("GIVEN an empty mask WHEN resolved SHOULD select every column in declaration order")
    void given_emptyMask_when_resolve_should_selectAll() {
        assertThat(MASK.resolve(FieldMask.getDefaultInstance()))
                .extracting(Column::field)
                .containsExactly("id", "title", "published_year");
    }

    @Test
    @DisplayName(
            "GIVEN a mask WHEN resolved SHOULD select those columns, map the property and add the always-include ones")
    void given_mask_when_resolve_should_selectAndAddAlways() {
        assertThat(MASK.resolve(mask("publishedYear")))
                .containsExactly(new Column("published_year", "year"), new Column("id", "id"));
    }

    @Test
    @DisplayName(
            "GIVEN a nested path and an unknown path WHEN resolved SHOULD use the top segment and ignore the unknown")
    void given_nestedAndUnknown_when_resolve_should_useTopLevelOnly() {
        assertThat(MASK.resolve(mask("title.sub", "nope")))
                .extracting(Column::field)
                .containsExactly("title", "id");
    }

    @Test
    @DisplayName("GIVEN a strict spec WHEN an unknown path arrives SHOULD fail with INVALID_ARGUMENT")
    void given_strict_when_unknownPath_should_beInvalidArgument() {
        assertThatThrownBy(() -> MASK.strict().resolve(mask("nope")))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    @Test
    @DisplayName("GIVEN alwaysInclude names an undeclared field WHEN configured SHOULD fail fast")
    void given_undeclaredAlways_when_configure_should_fail() {
        assertThatThrownBy(() -> MaskSpec.of("id").alwaysInclude("ghost")).isInstanceOf(IllegalArgumentException.class);
    }
}
