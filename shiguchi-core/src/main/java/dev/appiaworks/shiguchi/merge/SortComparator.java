package dev.appiaworks.shiguchi.merge;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds a {@link Comparator} over protobuf messages from the caller's {@code sortFields} — the
 * normalised {@code [{field, sortOrder}]} shape both HTTP surfaces produce.
 *
 * <p>Field paths are dot-separated and may address nested messages ({@code "effective.start_date"}),
 * matching a {@code SortField} message. Paths are resolved against the descriptor <em>once</em>, at
 * construction: an unknown or unorderable field fails here rather than once per record, so a
 * misconfigured route breaks at registration instead of mid-page.
 *
 * <p>Ordering rules worth stating, because they decide real output:
 * <ul>
 *   <li>An unset field sorts before any set value (ascending). Proto3 cannot distinguish "unset"
 *       from "default" for non-optional scalars, so this only bites on {@code optional} and message
 *       fields — but there it is deliberate and stable rather than incidental.
 *   <li>Strings compare by Unicode code point ({@link String#compareTo}), not by locale. A
 *       locale-sensitive collation would make the merge order depend on the gateway's default
 *       locale, which would differ between machines and silently reorder pages.
 *   <li>Enums compare by declared number, not by name — the proto's own ordering.
 * </ul>
 */
public final class SortComparator {

    private SortComparator() {}

    /**
     * @param descriptor message type being sorted
     * @param sortFields ordered sort keys, primary first; must not be empty
     * @param tieBreakField optional field appended as the final key to make the order total. Empty
     *     or blank appends nothing.
     * @throws IllegalArgumentException if {@code sortFields} is empty, or any path is unknown or
     *     names a field that cannot be ordered
     */
    public static Comparator<Message> build(Descriptor descriptor, List<SortKey> sortFields, String tieBreakField) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(sortFields, "sortFields");
        if (sortFields.isEmpty()) {
            throw new IllegalArgumentException(
                    "a merge needs at least one sort field: with no sort key the merged order is undefined");
        }

        List<SortKey> keys = new ArrayList<>(sortFields);
        if (tieBreakField != null && !tieBreakField.isBlank() && !mentions(sortFields, tieBreakField)) {
            keys.add(new SortKey(tieBreakField, true));
        }

        Comparator<Message> comparator = null;
        for (SortKey key : keys) {
            // Resolve once here so a bad field fails at build time with the route named, rather
            // than silently sorting every record as unset. The per-record path used by valueAt is
            // resolved against each message's own descriptor and cached, since a merge sees several
            // descriptor instances for the same schema.
            resolve(descriptor, key.field());
            String path = key.field();
            Comparator<Message> next = Comparator.comparing(m -> valueAt(m, path), SortComparator::compareValues);
            if (!key.ascending()) {
                next = next.reversed();
            }
            comparator = comparator == null ? next : comparator.thenComparing(next);
        }
        return comparator;
    }

    private static boolean mentions(List<SortKey> sortFields, String field) {
        return sortFields.stream().anyMatch(k -> k.field().equals(field));
    }

    /** Resolves a dot path to the chain of field descriptors that reaches it. */
    private static List<FieldDescriptor> resolve(Descriptor descriptor, String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("sort field must not be blank");
        }
        List<FieldDescriptor> chain = new ArrayList<>();
        Descriptor current = descriptor;
        String[] segments = path.split("\\.");
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (current == null) {
                throw new IllegalArgumentException(
                        "sort field '" + path + "' descends into scalar field '" + segments[i - 1] + "'");
            }
            FieldDescriptor fd = current.findFieldByName(segment);
            if (fd == null) {
                throw new IllegalArgumentException(
                        "unknown sort field '" + path + "': no field '" + segment + "' on " + current.getFullName());
            }
            if (fd.isRepeated()) {
                throw new IllegalArgumentException(
                        "sort field '" + path + "' is repeated; a list has no single value to order by");
            }
            chain.add(fd);
            current = fd.getJavaType() == FieldDescriptor.JavaType.MESSAGE ? fd.getMessageType() : null;
        }
        FieldDescriptor last = chain.get(chain.size() - 1);
        if (last.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
            throw new IllegalArgumentException(
                    "sort field '" + path + "' names a message; order by one of its scalar fields instead");
        }
        return chain;
    }

    /**
     * Per-descriptor resolved paths.
     *
     * <p>A merge draws records from several backends, and two backends serving the same
     * {@code .proto} still hand back distinct {@link Descriptor} instances — protobuf matches field
     * descriptors by identity, so a path resolved against one source's descriptor cannot be used on
     * another's message ("FieldDescriptor does not match message type").
     *
     * <p>Resolving by name on every access fixes that but puts a string hash lookup per field, per
     * path segment, in the comparator — which a sort calls O(n log n) times. Caching the resolved
     * path per descriptor keeps the cross-source correctness and pays the lookup once per
     * (descriptor × path) instead. Weak keys so a descriptor from a refreshed service context is
     * collected rather than pinned, matching {@code GrpcDescriptorCache}. The cache holds field
     * indexes, not {@code FieldDescriptor}s: those reference their descriptor, which would pin the
     * weak key and keep every replaced schema alive.
     */
    private static final com.github.benmanes.caffeine.cache.Cache<Descriptor, Map<String, int[]>> PATH_CACHE =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder().weakKeys().build();

    /** Resolves {@code path} against this specific message's descriptor, memoised per descriptor. */
    private static int[] localPath(Descriptor descriptor, String path) {
        Map<String, int[]> byPath = PATH_CACHE.get(descriptor, d -> new java.util.concurrent.ConcurrentHashMap<>());
        return byPath.computeIfAbsent(path, p -> {
            try {
                return resolve(descriptor, p).stream()
                        .mapToInt(FieldDescriptor::getIndex)
                        .toArray();
            } catch (IllegalArgumentException e) {
                // A source whose schema lacks the sort field sorts as unset rather than failing the
                // whole page: backends in a merge can legitimately be at different schema versions.
                return new int[0];
            }
        });
    }

    /**
     * Walks the path against the message's own descriptor, returning null as soon as a segment is
     * missing or an intermediate message is unset.
     *
     * <p>The whole path is resolved once per root descriptor and cached, so this walks pre-resolved
     * field indexes rather than doing a name lookup per segment per comparison.
     */
    private static Object valueAt(Message message, String path) {
        int[] resolved = localPath(message.getDescriptorForType(), path);
        if (resolved.length == 0) {
            return null;
        }
        Object current = message;
        for (int index : resolved) {
            if (!(current instanceof Message m)) {
                return null;
            }
            FieldDescriptor fd = m.getDescriptorForType().getFields().get(index);
            if (!m.hasField(fd)) {
                return null;
            }
            current = m.getField(fd);
        }
        return current;
    }

    /**
     * Orders two same-typed proto values, nulls (unset) first. Numeric types are widened so an
     * int32 and an int64 key compare sensibly rather than by boxed identity.
     */
    @SuppressWarnings("unchecked")
    private static int compareValues(Object a, Object b) {
        if (a == null || b == null) {
            return a == null ? (b == null ? 0 : -1) : 1;
        }
        if (a instanceof EnumValueDescriptor ea && b instanceof EnumValueDescriptor eb) {
            return Integer.compare(ea.getNumber(), eb.getNumber());
        }
        if (a instanceof Number na && b instanceof Number nb) {
            if (a instanceof Double || a instanceof Float || b instanceof Double || b instanceof Float) {
                return Double.compare(na.doubleValue(), nb.doubleValue());
            }
            return Long.compare(na.longValue(), nb.longValue());
        }
        if (a instanceof Comparable<?> && a.getClass() == b.getClass()) {
            return ((Comparable<Object>) a).compareTo(b);
        }
        throw new IllegalArgumentException(
                "cannot order values of type " + a.getClass().getSimpleName());
    }

    /**
     * One resolved sort key.
     *
     * @param field dot-separated field path
     * @param ascending direction; {@code SORT_ORDER_UNSPECIFIED} maps to ascending
     */
    public record SortKey(String field, boolean ascending) {
        public SortKey {
            Objects.requireNonNull(field, "field");
        }
    }
}
