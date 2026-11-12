package dev.appiaworks.shiguchi.backend.jpa;

import com.google.protobuf.Message;
import dev.appiaworks.protobuf.extras.api.ApiStatus;
import dev.appiaworks.shiguchi.backend.Column;
import dev.appiaworks.shiguchi.backend.OrderBy;
import dev.appiaworks.shiguchi.backend.PageResult;
import dev.appiaworks.shiguchi.backend.PageWindow;
import dev.appiaworks.shiguchi.backend.ProtoProjector;
import io.grpc.Status;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Fetches one page of an entity as protobuf messages, selecting only the columns asked for.
 *
 * <pre>{@code
 * private final ProjectionQuery<BookEntity, Book> books =
 *         ProjectionQuery.of(em, BookEntity.class, Book.getDefaultInstance());
 *
 * PageResult<Book> page = books
 *         .select(MASK.resolve(request.getReadMask()))
 *         .sort(SORT.resolve(request.getSortFieldsList()))
 *         .fetch(PageWindow.of(request.getPageRequest(), PAGING));
 * }</pre>
 *
 * <p>The query is a Criteria tuple query: unmasked columns are never read from the database. Each
 * row becomes a message through a {@link ProtoProjector}, matching columns to message fields by
 * the {@link Column#field() field name} given to {@code MaskSpec}.
 *
 * <p>With a {@linkplain PageWindow#keyset keyset window} the query adds "rows after the previous
 * page's last key" instead of an offset. The sort columns are fetched alongside, whether or not the
 * mask asked for them, so the cursor can always be built. Keyset paging requires that the sort
 * columns are non-null and that every page is requested with the same sort; a token from another
 * sort is refused with {@code INVALID_ARGUMENT}. Sort columns may be of the common scalar, date/time,
 * UUID and enum types.
 *
 * <p>Instances are immutable: {@link #select}, {@link #sort} and {@link #where} return copies, so
 * the one built at construction can be shared and each request derives its own. Run {@link #fetch}
 * inside the transaction (or with the {@code EntityManager} Spring injects) as for any JPA read.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.21.0")
public final class ProjectionQuery<E, M extends Message> {

    /** The cursor entry that names the sort the token was issued for. */
    private static final String SORT_KEY = "s";

    private final EntityManager em;
    private final Class<E> entity;
    private final ProtoProjector<M> projector;
    private final List<Column> columns;
    private final List<OrderBy> orders;
    private final BiFunction<Root<E>, CriteriaBuilder, Predicate> where;

    private ProjectionQuery(
            EntityManager em,
            Class<E> entity,
            ProtoProjector<M> projector,
            List<Column> columns,
            List<OrderBy> orders,
            BiFunction<Root<E>, CriteriaBuilder, Predicate> where) {
        this.em = em;
        this.entity = entity;
        this.projector = projector;
        this.columns = columns;
        this.orders = orders;
        this.where = where;
    }

    /** Messages like {@code prototype}, e.g. {@code Book.getDefaultInstance()}. */
    public static <E, M extends Message> ProjectionQuery<E, M> of(EntityManager em, Class<E> entity, M prototype) {
        return of(em, entity, ProtoProjector.of(prototype));
    }

    /** With a projector carrying per-field {@code mapper} overrides. */
    public static <E, M extends Message> ProjectionQuery<E, M> of(
            EntityManager em, Class<E> entity, ProtoProjector<M> projector) {
        return new ProjectionQuery<>(em, entity, projector, List.of(), List.of(), null);
    }

    /** The columns to fetch (from {@code MaskSpec#resolve}). Required before {@link #fetch}. */
    public ProjectionQuery<E, M> select(List<Column> columns) {
        return new ProjectionQuery<>(em, entity, projector, List.copyOf(columns), orders, where);
    }

    /** The order (from {@code SortSpec#resolve}). */
    public ProjectionQuery<E, M> sort(List<OrderBy> orders) {
        return new ProjectionQuery<>(em, entity, projector, columns, List.copyOf(orders), where);
    }

    /** A filter, e.g. {@code (root, cb) -> cb.equal(root.get("tenant"), tenantId)}. */
    public ProjectionQuery<E, M> where(BiFunction<Root<E>, CriteriaBuilder, Predicate> where) {
        return new ProjectionQuery<>(em, entity, projector, columns, orders, where);
    }

    /** The rows matching the filter, for {@code PageResult#toPageInfo(long)}. */
    public long count() {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<E> root = query.from(entity);
        query.select(cb.count(root));
        if (where != null) {
            query.where(where.apply(root, cb));
        }
        return em.createQuery(query).getSingleResult();
    }

    /** One page, as messages, with the token for the next. */
    public PageResult<M> fetch(PageWindow window) {
        if (columns.isEmpty()) {
            throw new IllegalStateException("select(...) the columns to fetch before fetch(...)");
        }
        if (window.isKeyset() && orders.isEmpty()) {
            throw new IllegalStateException("Keyset paging needs a sort(...): the cursor is the sort key");
        }
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<E> root = query.from(entity);

        List<Selection<?>> selection = new ArrayList<>();
        for (Column column : columns) {
            selection.add(path(root, column.property()).alias(column.field()));
        }
        // Where each sort key is read from in the tuple. A key that is also a requested column reuses
        // that column's alias: aliasing the same path twice is a "duplicate alias" error.
        List<String> keySource = new ArrayList<>();
        if (window.isKeyset()) {
            for (int i = 0; i < orders.size(); i++) {
                String property = orders.get(i).property();
                String column = columns.stream()
                        .filter(c -> c.property().equals(property))
                        .map(Column::field)
                        .findFirst()
                        .orElse(null);
                if (column == null) {
                    selection.add(path(root, property).alias(keyAlias(i)));
                }
                keySource.add(column != null ? column : keyAlias(i));
            }
        }
        query.multiselect(selection);

        List<Predicate> predicates = new ArrayList<>();
        if (where != null) {
            predicates.add(where.apply(root, cb));
        }
        if (window.isKeyset() && !window.after().isEmpty()) {
            predicates.add(after(root, cb, window.after()));
        }
        if (!predicates.isEmpty()) {
            query.where(predicates.toArray(Predicate[]::new));
        }
        query.orderBy(orders.stream()
                .map(o -> o.ascending() ? cb.asc(path(root, o.property())) : cb.desc(path(root, o.property())))
                .toList());

        List<Tuple> tuples = em.createQuery(query)
                .setFirstResult(window.isKeyset() ? 0 : window.offset())
                .setMaxResults(window.fetchSize())
                .getResultList();

        List<M> rows = new ArrayList<>(tuples.size());
        List<Map<String, String>> keys = new ArrayList<>(tuples.size());
        for (Tuple tuple : tuples) {
            Map<String, Object> values = new HashMap<>();
            for (Column column : columns) {
                values.put(column.field(), tuple.get(column.field()));
            }
            rows.add(projector.project(values));
            if (window.isKeyset()) {
                keys.add(keyOf(tuple, keySource));
            }
        }
        return window.isKeyset() ? window.trimKeyed(rows, keys) : window.trim(rows);
    }

    private Map<String, String> keyOf(Tuple tuple, List<String> keySource) {
        Map<String, String> key = new LinkedHashMap<>();
        for (int i = 0; i < orders.size(); i++) {
            Object value = tuple.get(keySource.get(i));
            if (value == null) {
                throw new IllegalStateException("Keyset paging needs non-null sort columns, but '"
                        + orders.get(i).property() + "' is null; use offset paging or sort by a non-null column");
            }
            key.put(keyAlias(i), KeyValues.encode(value));
        }
        key.put(SORT_KEY, sortFingerprint());
        return key;
    }

    /**
     * Identifies the sort a cursor was issued for. Without it a token from "year" would be read
     * as a "title" position the moment both sorts have the same number of keys.
     */
    private String sortFingerprint() {
        StringBuilder sb = new StringBuilder();
        for (OrderBy order : orders) {
            sb.append(order.property()).append(order.ascending() ? '+' : '-').append(';');
        }
        return Integer.toHexString(sb.toString().hashCode());
    }

    /** "Rows strictly after {@code after}" in the current order, for any mix of directions. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Predicate after(Root<E> root, CriteriaBuilder cb, Map<String, String> after) {
        if (!sortFingerprint().equals(after.get(SORT_KEY))) {
            throw sortMismatch();
        }
        Comparable[] values = new Comparable[orders.size()];
        Expression<Comparable>[] paths = new Expression[orders.size()];
        for (int i = 0; i < orders.size(); i++) {
            String text = after.get(keyAlias(i));
            if (text == null) {
                throw sortMismatch();
            }
            paths[i] = (Expression<Comparable>) path(root, orders.get(i).property());
            values[i] = KeyValues.decode(paths[i].getJavaType(), text);
        }
        List<Predicate> alternatives = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) {
            List<Predicate> all = new ArrayList<>();
            for (int j = 0; j < i; j++) {
                all.add(cb.equal(paths[j], values[j]));
            }
            all.add(orders.get(i).ascending() ? cb.greaterThan(paths[i], values[i]) : cb.lessThan(paths[i], values[i]));
            alternatives.add(cb.and(all.toArray(Predicate[]::new)));
        }
        return cb.or(alternatives.toArray(Predicate[]::new));
    }

    private static RuntimeException sortMismatch() {
        return Status.INVALID_ARGUMENT
                .withDescription("page_token does not match the requested sort")
                .asRuntimeException();
    }

    private static String keyAlias(int index) {
        return "k" + index;
    }

    /** {@code "author.lastName"} as a chain of {@code get} calls. */
    private static Path<?> path(Root<?> root, String property) {
        Path<?> path = root;
        for (String segment : property.split("\\.")) {
            path = path.get(segment);
        }
        return path;
    }
}
