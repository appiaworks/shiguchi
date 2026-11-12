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
[Pagination response headers](paging.md#pagination-response-headers)).

> [!IMPORTANT]
> The gateway does not implement paging, sorting, or filtering. It maps transport-level
> query parameters onto your request message and reads your response message back out.
> **Your backend still has to honour those fields**. The [JPA example](jpa-example.md) shows one way,
> using the [backend helpers](../../reference/backend-support.md) so each list method is a few lines.

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

`pagination.proto` and the `shiguchi/*.proto` option files both live in `shiguchi-proto`, and both ship two ways.

**From the jar (JVM builds).** The protos are packaged inside `shiguchi-proto`. Gradle's
protobuf plugin extracts `.proto` files out of `compile`-scoped dependencies onto the include
path by itself, so an ordinary dependency is the whole setup — no `protobuf(...)` configuration
needed:

```kotlin
dependencies {
    implementation("io.github.appiaworks:shiguchi-proto:0.20.0")
}
```

```proto
import "shiguchi/pagination.proto";
```

It arrives transitively too: the samples in this repository import `shiguchi/*.proto` without naming
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
            <version>0.20.0</version>
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
curl -sLO https://repo1.maven.org/maven2/io/github/appiaworks/shiguchi-proto/0.20.0/shiguchi-proto-0.20.0-protos.zip
unzip -q shiguchi-proto-0.20.0-protos.zip -d gateway-protos

protoc -I . -I gateway-protos --go_out=. bookstore/v1/books.proto
```

The archive reproduces the import paths verbatim (`shiguchi/pagination.proto`), so
the directory you unzip into goes on `-I` unchanged.

**Or copy.** Both files are plain proto3 with no gateway-specific dependencies —
`pagination.proto` imports nothing at all. Vendoring them into your own tree is a supported
choice, not a workaround; adjust the `package` and `java_package` to yours if you do.

---

## Related

- [jpa-example.md](jpa-example.md) and [backend-support.md](../../reference/backend-support.md) — honouring these fields in your backend.
- [configuration.md](../../reference/configuration.md) — every `shiguchi.*` property.
- [transcoding.md](../../concepts/transcoding.md) — the serde tiers, and why `page_info` responses skip Tier 0.
- [graphql-annotations.md](../graphql-annotations.md) — `(shiguchi.graphql)` / `(shiguchi.field)` options.
