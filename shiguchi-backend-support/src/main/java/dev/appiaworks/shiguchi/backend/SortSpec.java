package dev.appiaworks.shiguchi.backend;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import dev.appiaworks.shiguchi.proto.pagination.SortField;
import dev.appiaworks.shiguchi.proto.pagination.SortOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns the gateway's {@code sort_fields} into an ordered list of {@link OrderBy}, checking every
 * key against an allow-list.
 *
 * <p>The gateway forwards the client's string untouched, so an unchecked name would reach your
 * persistence layer. This is that check, written once:
 *
 * <pre>{@code
 * private static final SortSpec SORT = SortSpec.of("id", "title", "author", "year");
 *
 * List<OrderBy> order = SORT.resolve(request.getSortFieldsList());
 * }</pre>
 *
 * <p>Behaviour worth knowing:
 *
 * <ul>
 *   <li>A key not in the allow-list fails with {@code INVALID_ARGUMENT}.
 *   <li>Names match case-insensitively and across {@code snake_case}/{@code camelCase}.
 *   <li>A <em>tie-breaker</em> (default {@code id}) is appended unless already present. Without a
 *       total order two rows with equal keys can swap between pages, so an item is skipped or
 *       repeated. Turn it off with {@link Builder#noTieBreaker()} only if your keys are unique.
 *   <li>With no keys supplied, the {@linkplain Builder#defaultSort default sort} applies, else the
 *       tie-breaker alone.
 *   <li>A key repeated in the request counts once, at its first position.
 *   <li>Blank entries are skipped, as the gateway does.
 * </ul>
 *
 * Instances are immutable and thread-safe.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.21.0")
public final class SortSpec {

    private final Map<String, String> propertyByName;
    private final String tieBreaker;
    private final List<OrderBy> defaultSort;

    private SortSpec(Map<String, String> propertyByName, String tieBreaker, List<OrderBy> defaultSort) {
        this.propertyByName = Map.copyOf(propertyByName);
        this.tieBreaker = tieBreaker;
        this.defaultSort = List.copyOf(defaultSort);
    }

    /** Each name is both the sortable field and the property it sorts by; tie-breaker {@code id}. */
    public static SortSpec of(String... fields) {
        Builder builder = builder();
        for (String field : fields) {
            builder.field(field);
        }
        return builder.build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Resolves the request's sort keys.
     *
     * @throws io.grpc.StatusRuntimeException {@code INVALID_ARGUMENT} for a field outside the
     *     allow-list
     */
    public List<OrderBy> resolve(List<SortField> sortFields) {
        List<OrderBy> orders = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (SortField sortField : sortFields) {
            String name = sortField.getField();
            if (name.isBlank()) {
                continue;
            }
            String property = propertyByName.get(Names.normalize(name.strip()));
            if (property == null) {
                throw Names.invalid("Unsortable field: " + Names.echo(name));
            }
            if (seen.add(property)) {
                orders.add(
                        sortField.getSortOrder() == SortOrder.SORT_ORDER_DESC
                                ? OrderBy.desc(property)
                                : OrderBy.asc(property));
            }
        }
        if (orders.isEmpty()) {
            for (OrderBy order : defaultSort) {
                if (seen.add(order.property())) {
                    orders.add(order);
                }
            }
        }
        if (tieBreaker != null && seen.add(tieBreaker)) {
            orders.add(OrderBy.asc(tieBreaker));
        }
        return List.copyOf(orders);
    }

    /** Builds a {@link SortSpec}. */
    public static final class Builder {

        private final Map<String, String> propertyByName = new LinkedHashMap<>();
        private String tieBreaker = "id";
        private final List<OrderBy> defaultSort = new ArrayList<>();

        private Builder() {}

        /** A sortable field whose name is also the property. */
        public Builder field(String name) {
            return field(name, name);
        }

        /**
         * A sortable field whose request name differs from the property, e.g. {@code
         * field("published_year", "year")} or a path such as {@code "author.lastName"}.
         */
        public Builder field(String name, String property) {
            propertyByName.put(Names.normalize(name), property);
            return this;
        }

        /** The property appended last to make the order total. Defaults to {@code id}. */
        public Builder tieBreaker(String property) {
            this.tieBreaker = property;
            return this;
        }

        /** No tie-breaker: only if the request's keys are unique on their own. */
        public Builder noTieBreaker() {
            this.tieBreaker = null;
            return this;
        }

        /** What to sort by when the request carries no sort keys. */
        public Builder defaultSort(OrderBy... orders) {
            defaultSort.addAll(List.of(orders));
            return this;
        }

        public SortSpec build() {
            if (propertyByName.isEmpty()) {
                throw new IllegalStateException("A SortSpec needs at least one sortable field");
            }
            return new SortSpec(propertyByName, tieBreaker, defaultSort);
        }
    }
}
