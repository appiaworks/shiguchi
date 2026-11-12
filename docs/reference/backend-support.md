# Backend helpers reference

Part of the [projections guide](../guides/projections/index.md). The walkthrough is the
[JPA example](../guides/projections/jpa-example.md);
this page is the reference for what is in the two modules.

| Module | Needs | Contains |
| --- | --- | --- |
| `shiguchi-backend-support` | the request protos, gRPC `Status` | `SortSpec`, `MaskSpec`, `PageDefaults`, `PageWindow`, `PageResult`, `PageCursor`, `ProtoProjector`, `UnaryCalls` |
| `shiguchi-backend-support-jpa` | Jakarta Persistence (yours) | `ProjectionQuery` |

Everything is `@ApiStatus(EXPERIMENTAL)` since 0.21.0. Instances of the spec classes are
immutable and thread-safe, so declare them `static final`.

## SortSpec

Resolves `sort_fields` against an allow-list into an ordered `List<OrderBy>`.

```java
SortSpec.of("id", "title", "year");                       // name == property
SortSpec.builder()
        .field("published_year", "year")                  // request name != property
        .field("author", "author.lastName")               // a path
        .tieBreaker("id")                                 // the default; noTieBreaker() to turn off
        .defaultSort(OrderBy.desc("year"))                // when the request sends no keys
        .build();
```

- A key outside the allow-list fails with `INVALID_ARGUMENT` and `Unsortable field: <name>` (the
  name is truncated and stripped of control characters before it is echoed).
- Names match case-insensitively and across `snake_case` / `camelCase`.
- A key repeated in the request counts once, at its first position. Blank entries are skipped,
  as the gateway does.
- The tie-breaker is appended unless already present. `OrderBy` is `(property, ascending)`;
  converting to a Spring Data `Sort.Order` is `o.ascending() ? Sort.Order.asc(p) : Sort.Order.desc(p)`.

## MaskSpec

Resolves `read_mask` to a `List<Column>` (`field` on the message, `property` on the entity).

```java
MaskSpec.of("id", "title", "author");
MaskSpec.builder().field("published_year", "year").field("id").build().alwaysInclude("id");
```

- An empty mask returns every column in declaration order.
- Only the top-level segment counts: `author.display_name` selects `author`.
- Unknown paths are ignored, which is what a field mask means. `.strict()` rejects them with
  `INVALID_ARGUMENT`.
- `alwaysInclude(...)` must name declared fields; it fails at construction otherwise.

## PageDefaults, PageWindow, PageResult

```java
PageWindow window = PageWindow.of(request.getPageRequest(), PageDefaults.of(25, 200));
window.limit();      // page_size after the default and the ceiling
window.offset();     // rows to skip
window.fetchSize();  // limit + 1: the extra row says whether a next page exists
PageResult<T> page = window.trim(rows);   // items, nextPageToken ("" on the last page)
page.toPageInfo();                        // PageInfo without total_count
page.toPageInfo(total);                   // with it
```

`PageWindow.keyset(request, defaults, cursor)` is the keyset form: `after()` is the decoded key of
the previous page's last row, and `trim(rows, keyOf)` issues a token from the last kept row's
key. With `ProjectionQuery` you never call `trim` yourself.

`PageDefaults(defaultSize, maxSize)` requires `1 <= defaultSize <= maxSize`. A request for 0 or a
negative size gets `defaultSize`; anything above `maxSize` is clamped.

## PageCursor

The opaque `page_token` codec. It carries a small string map (an offset, or a sort key) as a
protobuf `Struct`, base64url encoded.

```java
PageCursor.unsigned();                    // forged positions are harmless
PageCursor.signed(secretBytes);           // + HMAC-SHA256; 16+ bytes, stable across instances
```

A malformed, oversized (more than 2048 characters) or wrongly signed token fails with
`INVALID_ARGUMENT` and `Malformed page_token`. A signed cursor rejects an unsigned token and the
reverse. Rotating the secret invalidates tokens already handed out.

## ProtoProjector

Builds a message from named values, through the message's descriptor.

```java
ProtoProjector<Book> projector = ProtoProjector.of(Book.getDefaultInstance());
Book book = projector.project(Map.of("id", "7", "title", "Dune", "published_year", 1965));
```

Keys match fields case-insensitively and across `snake_case` / `camelCase`. A `null` value leaves
the field unset. A key that names no field, and has no mapper, throws `IllegalArgumentException`:
that is a programming error, not a client one.

| Proto field | Java values accepted |
| --- | --- |
| `int32`, `int64`, `float`, `double` (and the unsigned / fixed / sint forms) | any `Number` |
| `bool` | `Boolean` |
| `string` | anything, via `toString()`; an enum by `name()` |
| `bytes` | `byte[]`, `ByteString` |
| enum | a Java enum (by name), a number (by value), a name, an `EnumValueDescriptor` |
| `google.protobuf.Timestamp` | `Instant`, `OffsetDateTime`, `ZonedDateTime`, `LocalDateTime` (as UTC), `java.util.Date` |
| `google.protobuf.Duration` | `java.time.Duration` |
| any message type | an instance of that message |
| `repeated` | an `Iterable` of the above |

Maps, and anything else, need a mapper:
`ProtoProjector.builder(proto).mapper("price", (builder, value) -> ...).build()`. The mapper
receives the non-null value and takes over that field entirely.

## ProjectionQuery

```java
ProjectionQuery<BookEntity, Book> q = ProjectionQuery.of(em, BookEntity.class, Book.getDefaultInstance());
PageResult<Book> page = q.select(columns).sort(orders).where(filter).fetch(window);
long total = q.where(filter).count();
```

Immutable: `select`, `sort` and `where` return copies, so one built at start-up can be shared.

- The query is a Criteria tuple query. Unmasked columns are never read.
- Columns map to message fields by the field name given to `MaskSpec`.
- `where` takes `(Root<E>, CriteriaBuilder) -> Predicate`, and applies to both `fetch` and `count`.
- Paths such as `author.lastName` become a chain of `get` calls (an inner join for an association).
- In keyset mode, see [keyset paging](../guides/projections/jpa-example.md#keyset-paging) for the constraints: non-null
  sort columns, one sort for the whole listing, and a supported key type. The query fails with
  `IllegalStateException` for a configuration problem (no `select`, keyset without a `sort`, a
  null key, an unsupported key type) and `INVALID_ARGUMENT` for a bad token.

## UnaryCalls

`UnaryCalls.respond(observer, () -> response)` completes the call with the supplied response, or
fails it with the status of a `StatusRuntimeException` thrown inside. Any other exception
escapes. See [why it matters](../guides/projections/jpa-example.md#why-unarycallsrespond).

## Without JPA

Everything except `ProjectionQuery` is independent of JPA. A JDBC sketch:

```java
List<OrderBy> orders = SORT.resolve(request.getSortFieldsList());
List<Column> columns = MASK.resolve(request.getReadMask());
PageWindow window = PageWindow.of(request.getPageRequest(), PAGING);

String sql = "SELECT " + columns.stream().map(Column::property).collect(joining(", "))
        + " FROM book ORDER BY " + orders.stream()
                .map(o -> o.property() + (o.ascending() ? " ASC" : " DESC")).collect(joining(", "))
        + " LIMIT ? OFFSET ?";
// every identifier above comes from a spec, never from the request
List<Book> rows = jdbc.query(sql, (rs, i) -> projector.project(valuesOf(rs, columns)),
        window.fetchSize(), window.offset());
PageResult<Book> page = window.trim(rows);
```

The identifiers interpolated into the SQL are the allow-listed properties from your own specs. A
client-supplied name never reaches the query.

---
