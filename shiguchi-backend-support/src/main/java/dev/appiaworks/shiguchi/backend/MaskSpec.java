package dev.appiaworks.shiguchi.backend;

import com.google.protobuf.FieldMask;
import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the gateway's {@code read_mask} to the columns worth fetching.
 *
 * <pre>{@code
 * private static final MaskSpec MASK =
 *         MaskSpec.of("id", "title", "author", "year").alwaysInclude("id");
 *
 * List<Column> columns = MASK.resolve(request.getReadMask());
 * }</pre>
 *
 * <p>Behaviour worth knowing:
 *
 * <ul>
 *   <li>An empty mask means every column, in the order declared.
 *   <li>Only the top-level segment of a path counts: {@code author.display_name} selects {@code
 *       author}. Nested pruning is yours to do when you map the row.
 *   <li>Unknown paths are ignored, which is what a field mask means. {@link #strict()} answers
 *       {@code INVALID_ARGUMENT} instead.
 *   <li>"Always include" columns are present whatever was asked, for fields a cursor or an
 *       identity needs.
 *   <li>Names match case-insensitively and across {@code snake_case}/{@code camelCase}.
 * </ul>
 *
 * Instances are immutable and thread-safe; {@link #alwaysInclude} and {@link #strict()} return
 * new instances.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.21.0")
public final class MaskSpec {

    private final Map<String, Column> columnByName;
    private final List<Column> all;
    private final List<Column> always;
    private final boolean strict;

    private MaskSpec(Map<String, Column> columnByName, List<Column> always, boolean strict) {
        this.columnByName = new LinkedHashMap<>(columnByName);
        this.all = List.copyOf(columnByName.values());
        this.always = List.copyOf(always);
        this.strict = strict;
    }

    /** Each name is both the message field and the property that supplies it. */
    public static MaskSpec of(String... fields) {
        Builder builder = new Builder();
        for (String field : fields) {
            builder.field(field);
        }
        return builder.build();
    }

    /** For fields whose property differs from the message field. */
    public static Builder builder() {
        return new Builder();
    }

    /** A copy that always fetches these fields, whatever the mask says. They must be declared. */
    public MaskSpec alwaysInclude(String... names) {
        List<Column> required = new ArrayList<>(always);
        for (String name : names) {
            Column column = columnByName.get(Names.normalize(name));
            if (column == null) {
                throw new IllegalArgumentException("alwaysInclude names an undeclared field: " + name);
            }
            required.add(column);
        }
        return new MaskSpec(columnByName, required, strict);
    }

    /** A copy that rejects paths naming no declared field instead of ignoring them. */
    public MaskSpec strict() {
        return new MaskSpec(columnByName, always, true);
    }

    /** Every selectable column, in declaration order. */
    public List<Column> all() {
        return all;
    }

    /**
     * The columns to fetch for {@code mask}.
     *
     * @throws io.grpc.StatusRuntimeException {@code INVALID_ARGUMENT} for an unknown path, in
     *     {@linkplain #strict() strict} mode only
     */
    public List<Column> resolve(FieldMask mask) {
        if (mask.getPathsCount() == 0) {
            return all;
        }
        Map<String, Column> selected = new LinkedHashMap<>();
        for (String path : mask.getPathsList()) {
            int dot = path.indexOf('.');
            String top = (dot < 0 ? path : path.substring(0, dot)).strip();
            Column column = columnByName.get(Names.normalize(top));
            if (column != null) {
                selected.putIfAbsent(column.field(), column);
            } else if (strict) {
                throw Names.invalid("Unknown field in read_mask: " + Names.echo(path));
            }
        }
        for (Column column : always) {
            selected.putIfAbsent(column.field(), column);
        }
        return List.copyOf(selected.values());
    }

    /** Declares fields whose property name differs from the message field. */
    public static final class Builder {

        private final Map<String, Column> columnByName = new LinkedHashMap<>();

        private Builder() {}

        /** A selectable field whose name is also the property. */
        public Builder field(String name) {
            return field(name, name);
        }

        /** A selectable field supplied by another property, e.g. {@code field("published_year", "year")}. */
        public Builder field(String name, String property) {
            columnByName.put(Names.normalize(name), new Column(name, property));
            return this;
        }

        public MaskSpec build() {
            if (columnByName.isEmpty()) {
                throw new IllegalStateException("A MaskSpec needs at least one selectable field");
            }
            return new MaskSpec(columnByName, List.of(), false);
        }
    }
}
