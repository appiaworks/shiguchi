# Projections, paging, and sorting

The gateway activates three list-query behaviours **by convention** — no annotation, no
gateway configuration. It inspects your request and response message descriptors at
startup, and if a field carries one of the reserved names below, the matching behaviour
turns on for that method.

| Convention field | On | Behaviour |
| --- | --- | --- |
| `page_request` | request | `?pageSize` / `?pageToken` (and Relay `?first` / `?after`) are mapped into it |
| `sort_fields` | request | `?sort=field:asc,other:desc` is expanded into it |
| `read_mask` | request | Filled from `?fields=` / the `X-Fields` header on REST, or the selection set on GraphQL (`google.protobuf.FieldMask` pushdown) |
| `page_info` (+ a repeated items field) | response | `X-Next-Page-Token` / `X-Total-Count` headers in REST; Relay Connection type in GraphQL |

The names are matched **literally, in proto snake_case**, on the top-level message. They are
not configurable — only the emitted REST header names are (see
[Pagination response headers](#pagination-response-headers)).

> [!IMPORTANT]
> The gateway does not implement paging, sorting, or filtering. It maps transport-level
> query parameters onto your request message and reads your response message back out.
> **Your backend still has to honour those fields** — the JPA section below shows one way.

## At a glance: how each surface spells it

The same four conventions are reachable from every surface. Only the spelling differs — all
of them normalise to the identical request message before it reaches your backend.

| Convention | REST | GraphQL | OpenAPI | gRPC proxy |
| --- | --- | --- | --- | --- |
| Page size | `?pageSize=` / `?first=` | `first:` | `pageSize` param | set the field |
| Page cursor | `?pageToken=` / `?after=` | `after:` | `pageToken` param | set the field |
| Sorting | `?sort=year:desc` | `sort: [SortFieldInput]` | `sort` param | set the field |
| Projection | `?fields=` / `X-Fields` | the selection set | `fields` param | set the field |
| Next-page cursor out | `X-Next-Page-Token` | `pageInfo.endCursor` | documented response | `page_info` |
| Total count out | `X-Total-Count` | `pageInfo.totalCount` | documented response | `page_info` |

The **OpenAPI** column is documentation, not a separate runtime: the generated spec
advertises the REST spellings, so Swagger UI presents them as first-class parameters. The
**gRPC proxy** passes messages through untouched — clients populate `page_request`,
`sort_fields`, and `read_mask` themselves, and the gateway neither adds nor rewrites them.

---

## The proto contract

The gateway matches on **field names in your messages**, never on message types — so any message
of the right shape works, and there is nothing to import for this to function. Two ways to get
that shape, both fully supported:

- **Import `shiguchi/pagination.proto`**, which ships `PageRequest`, `PageInfo`,
  `SortField` and `SortOrder` ready to use. One dependency, and the shape is right by
  construction. → [Getting the protos](#getting-the-protos)
- **Copy the four declarations** into your own package, from the block below or from that file.
  This costs nothing at runtime — a message's wire format depends on its field numbers and types,
  not on where it was declared — and keeps your API protos free of a dependency on the gateway.

Copying is the better default if your `.proto` files are a published contract of their own;
importing is the better default inside one codebase. This is the shape either way:

```proto
syntax = "proto3";

package bookstore.v1;

import "google/api/annotations.proto";
import "google/protobuf/field_mask.proto";

// ---- shared paging / sorting types -----------------------------------------

message PageRequest {
  int32  page_size  = 1;  // max items to return
  string page_token = 2;  // opaque cursor from the previous response
}

message PageInfo {
  string next_page_token = 1;  // omit/blank on the last page
  int32  total_count     = 2;  // optional; omit when not computed
  string prev_page_token = 3;  // optional; a cursor back to this page's start
}

enum SortOrder {
  SORT_ORDER_UNSPECIFIED = 0;
  SORT_ORDER_ASC         = 1;
  SORT_ORDER_DESC        = 2;
}

message SortField {
  string    field      = 1;  // a field name on the resource
  SortOrder sort_order = 2;
}

// ---- the list RPC ----------------------------------------------------------

message ListBooksRequest {
  PageRequest              page_request = 1;  // → paging
  repeated SortField       sort_fields  = 2;  // → sorting
  google.protobuf.FieldMask read_mask   = 3;  // → projection pushdown
  string                   author       = 4;  // ordinary filter, passed through as ?author=
}

message ListBooksResponse {
  repeated Book books     = 1;  // the repeated items field
  PageInfo      page_info = 2;  // → pagination headers / Relay Connection
}

message Book {
  string id     = 1;
  string title  = 2;
  string author = 3;
  int32  year   = 4;
}

service BookService {
  rpc ListBooks(ListBooksRequest) returns (ListBooksResponse) {
    option (google.api.http) = { get: "/v1/books" };
  }
}
```

Field **numbers and ordering are yours** — only the field *names* matter. `page_info` is
detected only when the response also has at least one repeated message field (the items
collection), which is what makes the Relay Connection transform well-defined.

The same file, importing the shipped messages instead of declaring them:

```proto
syntax = "proto3";

package bookstore.v1;

import "google/api/annotations.proto";
import "google/protobuf/field_mask.proto";
import "shiguchi/pagination.proto";

message ListBooksRequest {
  shiguchi.pagination.PageRequest        page_request = 1;
  repeated shiguchi.pagination.SortField sort_fields  = 2;
  google.protobuf.FieldMask                    read_mask    = 3;
  string                                       author       = 4;
}

message ListBooksResponse {
  repeated Book                            books     = 1;
  shiguchi.pagination.PageInfo       page_info = 2;
}
```

Note what did **not** change: the field names. `shiguchi.pagination.PageRequest` is
recognised for exactly the same reason a hand-written `bookstore.v1.PageRequest` is — because the
field is called `page_request`. Renaming the field breaks the convention in both versions;
renaming the message breaks neither.

### Getting the protos

`pagination.proto` and the `shiguchi/api` protos both live in `shiguchi-proto`, and both ship two ways.

**From the jar (JVM builds).** The protos are packaged inside `shiguchi-proto`. Gradle's
protobuf plugin extracts `.proto` files out of `compile`-scoped dependencies onto the include
path by itself, so an ordinary dependency is the whole setup — no `protobuf(...)` configuration
needed:

```kotlin
dependencies {
    implementation("io.github.appiaworks:shiguchi-proto:0.14.0")
}
```

```proto
import "shiguchi/pagination.proto";
```

It arrives transitively too: the samples in this repository import `shiguchi/api/*.proto` without naming
`shiguchi-proto` at all, because a gateway starter already depends on it. Check what your
build extracted under `build/extracted-include-protos/main/` if an import will not resolve.

Maven's `protobuf-maven-plugin` does not extract automatically; unpack them first:

```xml
<plugin>
  <artifactId>maven-dependency-plugin</artifactId>
  <executions>
    <execution>
      <id>unpack-gateway-protos</id>
      <phase>generate-sources</phase>
      <goals><goal>unpack</goal></goals>
      <configuration>
        <artifactItems>
          <artifactItem>
            <groupId>io.github.appiaworks</groupId>
            <artifactId>shiguchi-proto</artifactId>
            <version>0.14.0</version>
            <includes>**/*.proto</includes>
            <outputDirectory>${project.build.directory}/gateway-protos</outputDirectory>
          </artifactItem>
        </artifactItems>
      </configuration>
    </execution>
  </executions>
</plugin>
```

then add `${project.build.directory}/gateway-protos` to the protoc plugin's
`<additionalProtoPathElements>`.

**From the zip (any toolchain).** A `protos` classifier artifact carries the same files with no
jar involved, for Go, Rust, Python, buf or a plain `protoc`:

```bash
curl -sLO https://repo1.maven.org/maven2/io/github/appiaworks/shiguchi-proto/0.14.0/shiguchi-proto-0.14.0-protos.zip
unzip -q shiguchi-proto-0.14.0-protos.zip -d gateway-protos

protoc -I . -I gateway-protos --go_out=. bookstore/v1/books.proto
```

The archive reproduces the import paths verbatim (`shiguchi/pagination.proto`), so
the directory you unzip into goes on `-I` unchanged.

**Or copy.** Both files are plain proto3 with no gateway-specific dependencies —
`pagination.proto` imports nothing at all. Vendoring them into your own tree is a supported
choice, not a workaround; adjust the `package` and `java_package` to yours if you do.

---

## Paging

### Request side

For a method whose request has a `page_request` field, both surfaces map their paging
arguments onto it. REST reads query parameters; GraphQL reads the SDL arguments the schema
builder emits. Both run through the same `ConventionArgMapper`, so a backend sees one
request shape either way:

| REST query param | GraphQL argument | Mapped to |
| --- | --- | --- |
| `?pageSize=25` | — | `page_request.page_size` |
| `?first=25` | `first: 25` | `page_request.page_size` |
| `?pageToken=abc` | — | `page_request.page_token` |
| `?after=<cursor>` | `after: "<cursor>"` | `page_request.page_token` (base64-decoded, portion before `:`) |

`first` is the Relay spelling of `pageSize`; `after` is the Relay cursor. When both a
cursor and an explicit token are supplied, the cursor wins. A cursor that isn't valid
base64 is used as a raw token rather than rejected.

If the request message has **no** `page_request` field, these arguments are dropped rather
than passed through as unknown fields.

```bash
curl 'http://localhost:8080/v1/books?pageSize=25'
curl 'http://localhost:8080/v1/books?pageSize=25&pageToken=eyJvIjoyNX0'
```

```graphql
query {
  listBooks(first: 25, after: "dG9rMTIzOjU=") {
    edges { node { id title } }
    pageInfo { endCursor hasNextPage }
  }
}
```

### Pagination response headers

When the response message has a `page_info` field, the gateway reads it off the decoded
message and sets headers
(`shiguchi-rest/src/main/java/dev/appiaworks/shiguchi/rest/GrpcResponsePaginationExtractor.java`):

```
X-Next-Page-Token: eyJvIjoyNX0     # from page_info.next_page_token, omitted when blank
X-Total-Count: 1284                # from page_info.total_count, omitted when < 0 / unset
```

Header names are configurable:

```yaml
shiguchi:
  rest:
    pagination:
      next-page-token-header: X-Next-Page-Token
      total-count-header: X-Total-Count
```

Header extraction is **best-effort** — a malformed `page_info` is swallowed rather than
failing the request. Note the serde consequence documented in
[transcoding.md](transcoding.md): responses carrying `page_info` need a decoded message, so
they use Tier 1/2 and never the Tier 0 wire-to-JSON fast path.

### GraphQL: Relay Connections

A response shaped `{repeated items, page_info}` is exposed as a Relay Connection —
`edges { node }`, `pageInfo`, `totalCount` — by `RelayConnectionTransformer`. `endCursor`
and `hasNextPage` are synthesised from `next_page_token`; edge cursors are
`base64(nextPageToken + ":" + index)`.

`startCursor` and `hasPreviousPage` come from whichever of these the gateway can know:

| Source | `hasPreviousPage` | `startCursor` |
| --- | --- | --- |
| `page_info.prev_page_token` set | `true` | that token |
| the caller sent `after` | `true` | the cursor they sent |
| neither | `false` | `null` |

`prev_page_token` is optional and the gateway ships no message for it to live in — like every
other field here it is matched by name. Set it when your backend can cheaply produce a cursor
back to the current page's start; a client can then page in both directions. Without it the
gateway still answers honestly, because a caller that arrived on an `after` cursor is by
definition past the first page.

> Before this, `startCursor` was always `null` and `hasPreviousPage` always `false` — the schema
> advertised both fields and then denied there was anything behind, no matter how far the caller
> had paged.

---

## Sorting

A request with a `sort_fields` field accepts a `sort` argument on both surfaces.

**REST** takes a single comma-separated string:

```bash
curl 'http://localhost:8080/v1/books?sort=author:asc,year:desc'
```

Syntax: comma-separated `field[:asc|desc]`. Direction is case-insensitive and **defaults to
ascending** when the `:direction` suffix is absent (`?sort=author` ≡ `?sort=author:asc`).
Any value other than `desc` is treated as ascending.

**GraphQL** takes a `[SortFieldInput]` list — the schema builder emits the `SortFieldInput`
input type and `SortOrder` enum automatically for any field whose request has `sort_fields`:

```graphql
query {
  listBooks(sort: [
    { field: "author", sortOrder: SORT_ORDER_ASC },
    { field: "year",   sortOrder: SORT_ORDER_DESC }
  ]) { edges { node { id title } } }
}
```

Both spellings produce the same request field:

```json
{ "sortFields": [
    { "field": "author", "sortOrder": "SORT_ORDER_ASC" },
    { "field": "year",   "sortOrder": "SORT_ORDER_DESC" }
] }
```

Entries with a missing or blank `field` are skipped. A bare `"asc"` / `"desc"` is accepted
where the enum is expected and normalised to the `SORT_ORDER_*` form.

The gateway does not validate that `field` names a real field on your resource — that
check belongs to the backend, which should reject unknown sort keys rather than
interpolating them into a query (see the allow-list in the JPA example below).

> There is no `order_by` convention. `sort_fields` / `?sort=` is the only spelling the
> gateway recognises.

---

## Field projection (FieldMask pushdown)

Any method whose request message has a `read_mask` field accepts a projection. Both HTTP
surfaces populate it, and the backend sees the same `google.protobuf.FieldMask` either way.

### REST

Pass a comma-separated field list as `?fields=`, or as the `X-Fields` header when a
generated client can't easily add query parameters:

```bash
curl 'http://localhost:8080/v1/books?fields=id,title'
curl -H 'X-Fields: id,title' 'http://localhost:8080/v1/books'
```

Both become `read_mask: { paths: ["id", "title"] }` on the outbound request. If both are
present the query parameter wins.

Accepted spellings, normalised to proto snake_case before the mask is built:

| Input | Mask path |
| --- | --- |
| `?fields=title` | `title` |
| `?fields=publishedYear` | `published_year` |
| `?fields=published_year` | `published_year` |
| `?fields=author.displayName` | `author.display_name` |

Surrounding whitespace is tolerated, duplicates are collapsed, and malformed entries
(`a..b`, a trailing dot) are dropped rather than forwarded. When the request message has no
`read_mask` field the parameter is ignored entirely — it is never passed through as an
ordinary request field.

This is **request-side pushdown only**: the gateway tells the backend to compute less, and
serialises whatever comes back. It deliberately does not prune the response, so REST keeps
the fast Tier 0/1 serde paths (see [transcoding.md](transcoding.md)). A backend that
ignores `read_mask` therefore still returns full messages — the mask is a request, not a
gateway-enforced filter.

Methods with a `read_mask` field advertise `fields` as a documented query parameter in the
generated OpenAPI document, alongside `pageSize`/`pageToken`/`sort` where those apply, so
the projection is discoverable in Swagger UI.

### GraphQL

Projection has two halves on the GraphQL path, and the selection set drives both.

**1. Pushdown to the backend.** When a GraphQL query resolves a method whose request has a
`read_mask` field, `GraphQlFieldMaskInjector` turns the query's immediate selection set into
a `google.protobuf.FieldMask` and injects it, so the backend can compute less:

```graphql
query {
  listBooks(first: 25) {
    edges { node { id title } }
  }
}
```

sends `read_mask: { paths: ["id", "title"] }` on the request. Paths are converted from the
GraphQL camelCase names to **proto snake_case** (`publishedYear` → `published_year`).
Selections named `page*` / `sort*` that don't correspond to a real request field are
filtered out, so paging arguments never leak into the mask.

**2. Response conversion pruning.** The same selection set builds a `ProjectionNode`
(`shiguchi-core/src/main/java/dev/appiaworks/protobuf/extras/serde/ProjectionNode.java`) that limits
what the gateway bothers to convert out of the response:

- Scalar fields and repeated scalars **always** convert — they are cheap, and type
  resolvers or federation batch keys may read fields the query didn't select.
- Nested messages, repeated messages, and map fields convert **only when selected**.

Disable the response-side pruning with:

```yaml
shiguchi:
  graphql:
    selection-projection: false   # default: true
```

What it is worth, measured (`GraphQlProjectionBenchmark` in shiguchi-benchmarks): queries
that leave the large composites out convert 4× faster on a 3-item response and 25× on a 30-item
one; a query that selects the large list costs the same as converting it in full. Both paths
are compiled (Tier 2), so there is no case in which projection is slower — leave it on.

## OpenAPI / Swagger UI

The generated OpenAPI 3 document advertises each convention parameter on the operations
that actually support it — an operation gets a `fields` parameter only if its request
message has `read_mask`, `pageSize`/`pageToken` only if it has `page_request`, and `sort`
only if it has `sort_fields`:

| Parameter | Emitted when | Description in the spec |
| --- | --- | --- |
| `pageSize` (integer) | request has `page_request` | Maximum number of items to return |
| `pageToken` (string) | request has `page_request` | Opaque cursor from `X-Next-Page-Token` |
| `sort` (string) | request has `sort_fields` | Comma-separated `field[:asc\|desc]` |
| `fields` (string) | request has `read_mask` | Comma-separated projection, plus the selectable field names |

The `fields` parameter's description lists the top-level field names a projection can
name, taken from the repeated items message of the response (falling back to the response's
own fields), and carries an example value — so Swagger UI shows valid choices instead of a
bare text box.

The convention fields themselves are **not** advertised under their raw proto names. A
`page_request` message field is never emitted as a `pageRequest` query parameter, because
the gateway would not accept one; only the `pageSize` / `pageToken` spellings work.

### Current limitations

These are real gaps in the current release, not omissions in this document:

- **REST projection does not prune the response.** `?fields=` is pushed to the backend but
  the gateway serialises the full response it receives, to keep the Tier 0/1 fast paths.
  Only the GraphQL path prunes conversion. A backend that ignores `read_mask` sees no
  payload reduction on REST.
- **The mask field name is fixed** to `read_mask`. `field_mask` is not recognised, and
  there is no property to rename it.
- **Backward pagination is only half modelled.** `hasPreviousPage` and `startCursor` are
  answered (from `page_info.prev_page_token`, or the `after` cursor the caller arrived on — see
  [GraphQL: Relay Connections](#graphql-relay-connections)), but there is no `last` / `before`
  argument: a client can tell that a previous page exists without being able to ask for it.
- **Sort keys are not validated against the resource.** The gateway forwards whatever
  `field` string it is given; rejecting unknown keys is the backend's job (see the
  allow-list in the JPA example below).

---

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

## Related

- [configuration.md](configuration.md) — every `shiguchi.*` property.
- [transcoding.md](transcoding.md) — the serde tiers, and why `page_info` responses skip Tier 0.
- [graphql-annotations.md](graphql-annotations.md) — `(shiguchi.api.graphql)` / `(shiguchi.api.graphql_field)` options.
