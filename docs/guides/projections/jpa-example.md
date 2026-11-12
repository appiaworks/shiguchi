# Implementing the backend: Spring Data JPA

Part of the [projections guide](index.md).

The gateway hands your service a `PageRequest`, a `sort_fields` list and a `read_mask`. Honouring
all three is the same code in every list method: check the sort keys against an allow-list, pick
the columns, apply the page, cut the extra row, build a cursor. `shiguchi-backend-support-jpa`
does those steps, so a list method is a few lines. Nothing here is gateway-specific: it is
ordinary code behind a gRPC service implementation.

A runnable version, with tests, is [`sample-jpa-backend`](../../../samples/sample-jpa-backend).

## Dependency

```kotlin
implementation("io.github.appiaworks:shiguchi-backend-support-jpa:<version>")
```

```xml
<dependency>
  <groupId>io.github.appiaworks</groupId>
  <artifactId>shiguchi-backend-support-jpa</artifactId>
  <version>${shiguchi.version}</version>
</dependency>
```

It brings `shiguchi-backend-support` with it. You bring your own JPA provider; the module only
compiles against the Jakarta Persistence API. Both modules are `@ApiStatus(EXPERIMENTAL)`: the
shape may still change in a minor release, with a changelog note.

## The service

```java
@Component
public class BookBackend extends BookServiceGrpc.BookServiceImplBase {

    // What a client may sort by: proto field name, and the JPA property it maps to.
    private static final SortSpec SORT = SortSpec.builder()
            .field("id").field("title").field("author")
            .field("published_year", "year")
            .build();

    // What a client may select. The id is fetched whatever the mask says.
    private static final MaskSpec MASK = MaskSpec.builder()
            .field("id").field("title").field("author")
            .field("published_year", "year")
            .build()
            .alwaysInclude("id");

    private static final PageDefaults PAGING = PageDefaults.of(25, 200);

    private final ProjectionQuery<BookEntity, Book> books;

    BookBackend(EntityManager em) {
        this.books = ProjectionQuery.of(em, BookEntity.class, Book.getDefaultInstance());
    }

    @Override
    public void listBooks(ListBooksRequest request, StreamObserver<ListBooksResponse> out) {
        UnaryCalls.respond(out, () -> {
            PageWindow window = PageWindow.of(request.getPageRequest(), PAGING);

            var query = books.select(MASK.resolve(request.getReadMask()))
                             .sort(SORT.resolve(request.getSortFieldsList()));
            PageResult<Book> page = query.fetch(window);

            return ListBooksResponse.newBuilder()
                    .addAllBooks(page.items())
                    .setPageInfo(page.toPageInfo(query.count())) // use toPageInfo() to skip the COUNT
                    .build();
        });
    }
}
```

The entity is plain JPA; nothing about it is gateway-specific:

```java
@Entity
@Table(name = "book")
public class BookEntity {
    @Id private String id;
    private String title;
    private String author;
    @Column(name = "published_year") private int year;
    // constructors, getters
}
```

## What each piece does

| You write | The library does |
| --- | --- |
| `SORT.resolve(request.getSortFieldsList())` | Rejects any key outside the allow-list with `INVALID_ARGUMENT`; accepts `snake_case` and `camelCase`; appends `id` as a tie-breaker so paging stays deterministic. Without a total order, two rows with equal sort keys can swap between pages and an item is skipped or repeated. |
| `MASK.resolve(request.getReadMask())` | An empty mask means every column. Otherwise the top-level segment of each path selects a column, unknown paths are ignored, and the always-included ones are added. |
| `PageWindow.of(request.getPageRequest(), PAGING)` | Applies the default and the ceiling to `page_size` (a client cannot ask for the whole table) and decodes `page_token`. |
| `query.fetch(window)` | Runs one Criteria tuple query selecting only the masked columns, asks for `limit + 1` rows so it knows whether another page exists without a second query, builds the response messages, and issues the next token (blank on the last page). |
| `UnaryCalls.respond(out, ...)` | Turns a client mistake thrown by the helpers into the call's `INVALID_ARGUMENT`. |

### Why `UnaryCalls.respond`

grpc-java reports an exception that escapes a handler as `UNKNOWN`, even a
`StatusRuntimeException` carrying `INVALID_ARGUMENT`. The gateway would then answer a bad sort key
with a 500. Only `observer.onError(...)` sets the status the caller sees, which is what `respond`
does. Any other exception is a bug in your backend and still escapes.

## Keyset paging

Offset paging is the default. It drifts when rows are inserted or deleted while someone is
paging, and it gets slower the deeper the page. For large or busy tables, switch the window:

```java
PageCursor cursor = PageCursor.signed(secretBytes);        // HMAC-SHA256; 16+ bytes
PageWindow window = PageWindow.keyset(request.getPageRequest(), PAGING, cursor);
```

Nothing else changes. The token then carries the last row's sort key, and the next page is
"rows after this key" in the requested order, for any mix of ascending and descending keys.
Things to know:

- **Sort columns must be non-null.** A null key cannot be compared, so the query fails with an
  `IllegalStateException` that names the column. Sort by a non-null column or use offset paging.
- **Every page must use the same sort.** A token carries a fingerprint of the sort it was issued
  for; presented with a different one it is refused with `INVALID_ARGUMENT`.
- **Sort columns may be** strings, numbers, booleans, `UUID`, enums, `java.time` types and
  `java.util.Date`.
- **The sort columns are fetched even when the mask leaves them out**, so the cursor can always
  be built; they are not copied into the message.
- **Signing proves the token came from you, not that it is fresh.** A client can replay an old
  token and get that page again. Use `PageCursor.unsigned()` if a forged position is harmless.

The gateway contract is unchanged: the token stays opaque either way.

## Filtering, renames and custom fields

```java
// A filter, applied before paging and counted by count(); returns a copy
var mine = books.where((root, cb) -> cb.equal(root.get("tenant"), tenantId));

// A dotted path through an association
SortSpec.builder().field("author", "author.lastName") ...

// A field the built-in conversions do not cover (a nested message, a value object)
ProtoProjector<Book> projector = ProtoProjector.builder(Book.getDefaultInstance())
        .mapper("price", (b, v) -> ((Book.Builder) b).setPrice(Money.of((BigDecimal) v)))
        .build();
ProjectionQuery.of(em, BookEntity.class, projector);
```

Which Java values convert to which proto fields on their own is in the
[backend helpers reference](../../reference/backend-support.md#protoprojector). Fields you do not select are not
read from the database and are left unset: the message carries proto defaults for them, and the
gateway renders plain proto3 scalars as `""` / `0` (declare a field `optional` and it is omitted).

## End result

```bash
$ curl -i 'http://localhost:8080/v1/books?pageSize=2&sort=publishedYear:desc&fields=title'
HTTP/1.1 200 OK
X-Next-Page-Token: CggKAW8SAxoBMg.t9Zh8t96VOzpQOQdEZwvMqo4ee06Rh_jBWbdqmf7Z1M
X-Total-Count: 10
Content-Type: application/json

{"books":[{"id":"05","title":"Heat","author":"","publishedYear":0},
          {"id":"06","title":"Beloved","author":"","publishedYear":0}],
 "pageInfo":{"nextPageToken":"CggKAW8SAxoBMg.t9Zh8t96VOzpQOQdEZwvMqo4ee06Rh_jBWbdqmf7Z1M",
             "totalCount":10,"prevPageToken":""}}
```

`total_count` costs a `COUNT` query on every request. Leave it out (`page.toPageInfo()`) unless a
UI genuinely needs it: clients can page until the token disappears.

## Without the helpers

The helpers are a convenience, not a requirement; the contract is just the three fields. If you
use JDBC, jOOQ or Mongo, `SortSpec`, `MaskSpec`, `PageWindow`, `PageCursor` and `ProtoProjector`
work without JPA (they are in `shiguchi-backend-support`, which has no JPA dependency), and
`ProjectionQuery` is the only JPA-specific class. See the
[reference](../../reference/backend-support.md#without-jpa) for a JDBC sketch.

---
