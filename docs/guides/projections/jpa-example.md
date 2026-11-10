# Implementing the backend: a Spring Data JPA example

Part of the [projections guide](index.md).

## Implementing the backend: a Spring Data JPA example

The gateway hands your service a `PageRequest`, a `sort_fields` list, and a `read_mask`.
Below is a complete, minimal way to honour all three with Spring Data JPA. Nothing here is
gateway-specific — it is ordinary Spring code behind a gRPC service implementation.

### Entity and repository

```java
@Entity
@Table(name = "book")
public class BookEntity {
    @Id
    private String id;

    private String title;
    private String author;
    private int year;

    // getters / setters omitted
}

public interface BookRepository
        extends JpaRepository<BookEntity, String>, JpaSpecificationExecutor<BookEntity> {}
```

### Translating `sort_fields` → `org.springframework.data.domain.Sort`

Always resolve incoming sort keys against an allow-list. The gateway passes the client's
string through untouched, so an unchecked name reaches your persistence layer.

```java
final class SortMapper {

    /** Proto/JSON field name → JPA property. Also the allow-list. */
    private static final Map<String, String> SORTABLE = Map.of(
            "id", "id",
            "title", "title",
            "author", "author",
            "year", "year");

    private SortMapper() {}

    static Sort toSort(List<SortField> sortFields) {
        if (sortFields.isEmpty()) {
            return Sort.by(Sort.Order.asc("id")); // stable default — see the cursor note
        }
        List<Sort.Order> orders = new ArrayList<>();
        for (SortField sf : sortFields) {
            String property = SORTABLE.get(sf.getField());
            if (property == null) {
                throw Status.INVALID_ARGUMENT
                        .withDescription("Unsortable field: " + sf.getField())
                        .asRuntimeException();
            }
            orders.add(sf.getSortOrder() == SortOrder.SORT_ORDER_DESC
                    ? Sort.Order.desc(property)
                    : Sort.Order.asc(property));
        }
        orders.add(Sort.Order.asc("id")); // tie-breaker keeps paging deterministic
        return Sort.by(orders);
    }
}
```

The trailing `id` tie-breaker matters: without a total ordering, two rows with equal sort
keys can swap between pages and an item is silently skipped or duplicated.

### Translating `read_mask` → a JPA projection

A `FieldMask` is a runtime value, so a compile-time interface projection doesn't fit.
Select the columns dynamically with the Criteria API and leave unmasked fields unset — the
proto3 JSON mapping then omits them for `optional`/message fields.

```java
@Component
class BookProjectionReader {

    private static final Set<String> SELECTABLE = Set.of("id", "title", "author", "year");

    private final EntityManager em;

    BookProjectionReader(EntityManager em) {
        this.em = em;
    }

    /** Resolves the mask to the entity columns to fetch; empty mask = all columns. */
    static Set<String> resolve(FieldMask mask) {
        if (mask.getPathsList().isEmpty()) {
            return SELECTABLE;
        }
        Set<String> paths = mask.getPathsList().stream()
                .map(p -> p.contains(".") ? p.substring(0, p.indexOf('.')) : p) // top level only
                .filter(SELECTABLE::contains)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        paths.add("id"); // the id is needed for cursors even when not requested
        return paths;
    }

    List<Book> findPage(Set<String> columns, Sort sort, int offset, int limit) {
        var cb = em.getCriteriaBuilder();
        var query = cb.createTupleQuery();
        var root = query.from(BookEntity.class);

        List<String> ordered = List.copyOf(columns);
        query.multiselect(ordered.stream()
                .map(c -> root.get(c).alias(c))
                .toArray(jakarta.persistence.criteria.Selection[]::new));

        query.orderBy(sort.stream()
                .map(o -> o.isAscending()
                        ? cb.asc(root.get(o.getProperty()))
                        : cb.desc(root.get(o.getProperty())))
                .toList());

        return em.createQuery(query)
                .setFirstResult(offset)
                .setMaxResults(limit)
                .getResultList()
                .stream()
                .map(tuple -> toProto(tuple, ordered))
                .toList();
    }

    private static Book toProto(Tuple tuple, List<String> columns) {
        Book.Builder b = Book.newBuilder();
        for (String c : columns) {
            Object v = tuple.get(c);
            if (v == null) continue;
            switch (c) {
                case "id" -> b.setId((String) v);
                case "title" -> b.setTitle((String) v);
                case "author" -> b.setAuthor((String) v);
                case "year" -> b.setYear((Integer) v);
                default -> {}
            }
        }
        return b.build();
    }
}
```

If your masks are always shallow and the row count is modest, the simpler option is to
fetch whole entities and prune during proto mapping. That saves no database work but still
shrinks the payload, and it is a reasonable first implementation.

### Cursor encoding

`page_token` is opaque to the gateway — any encoding works, as long as your service both
produces and consumes it. Offset cursors are the shortest path:

```java
final class Cursor {

    private Cursor() {}

    static int decode(String token) {
        if (token == null || token.isBlank()) return 0;
        try {
            String json = new String(Base64.getUrlDecoder().decode(token), UTF_8);
            int offset = Integer.parseInt(json.replaceAll("\\D+", ""));
            return Math.max(offset, 0);
        } catch (IllegalArgumentException e) {
            throw Status.INVALID_ARGUMENT
                    .withDescription("Malformed page_token")
                    .asRuntimeException();
        }
    }

    static String encode(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"o\":" + offset + "}").getBytes(UTF_8));
    }
}
```

Offset paging is simple but drifts under concurrent writes and degrades on deep pages. For
large or busy tables, encode the last row's sort key instead and use a keyset predicate
(`WHERE (author, id) > (:lastAuthor, :lastId)`); the gateway contract is unchanged, since
the token stays opaque either way.

### The service implementation

```java
@GrpcService
public class BookServiceImpl extends BookServiceGrpc.BookServiceImplBase {

    private static final int DEFAULT_PAGE_SIZE = 25;
    private static final int MAX_PAGE_SIZE = 200;

    private final BookRepository repository;
    private final BookProjectionReader reader;

    public BookServiceImpl(BookRepository repository, BookProjectionReader reader) {
        this.repository = repository;
        this.reader = reader;
    }

    @Override
    public void listBooks(ListBooksRequest request, StreamObserver<ListBooksResponse> observer) {
        int limit = clamp(request.getPageRequest().getPageSize());
        int offset = Cursor.decode(request.getPageRequest().getPageToken());
        Sort sort = SortMapper.toSort(request.getSortFieldsList());
        Set<String> columns = BookProjectionReader.resolve(request.getReadMask());

        // Fetch one extra row: its presence is what tells us another page exists,
        // without a second count query.
        List<Book> rows = reader.findPage(columns, sort, offset, limit + 1);

        boolean hasMore = rows.size() > limit;
        List<Book> page = hasMore ? rows.subList(0, limit) : rows;

        PageInfo.Builder pageInfo = PageInfo.newBuilder();
        if (hasMore) {
            pageInfo.setNextPageToken(Cursor.encode(offset + limit));
        }
        // Only set total_count if you actually want to pay for the COUNT query.
        pageInfo.setTotalCount((int) repository.count());

        observer.onNext(ListBooksResponse.newBuilder()
                .addAllBooks(page)
                .setPageInfo(pageInfo)
                .build());
        observer.onCompleted();
    }

    private static int clamp(int requested) {
        if (requested <= 0) return DEFAULT_PAGE_SIZE;
        return Math.min(requested, MAX_PAGE_SIZE);
    }
}
```

Two things worth copying: **clamp `page_size`** so a client cannot request the whole table,
and leave `next_page_token` unset on the last page — the gateway omits the
`X-Next-Page-Token` header entirely when it is blank, which is how REST clients detect the
end of the collection.

`total_count` is optional and costs a `COUNT` query on every request. Omit it unless a UI
genuinely needs the total; clients can page until the token disappears.

### End result

```bash
$ curl -i 'http://localhost:8080/v1/books?pageSize=2&sort=year:desc'
HTTP/1.1 200 OK
X-Next-Page-Token: eyJvIjoyfQ
X-Total-Count: 1284
Content-Type: application/json

{"books":[
  {"id":"9","title":"Designing Data-Intensive Applications","author":"Kleppmann","year":2017},
  {"id":"4","title":"Release It!","author":"Nygard","year":2007}
]}
```

---
