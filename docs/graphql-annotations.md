# Proto annotations

The gateway reads its settings from custom options on your methods and fields, in the same style
as `google.api.http`. They all live in the `shiguchi.api` package, one file per concern:

| File | Option | Applies to |
| --- | --- | --- |
| `shiguchi/api/graphql.proto` | `(shiguchi.api.graphql)` on a method, `(shiguchi.api.graphql_field)` on a field | The generated GraphQL schema |
| `shiguchi/api/method.proto` | `(shiguchi.api.method)` on a method | Every surface: REST, GraphQL, gRPC-Web |
| `shiguchi/api/merge.proto` | `(shiguchi.api.merge)` on a method | A route served by merging backends, see [merging.md](merging.md) |
| `shiguchi/api/topic.proto` | `(shiguchi.api.topic)` on a method | A push topic fed by a server stream, see [push.md](push.md) |

Import only the files you use:

```proto
import "shiguchi/api/graphql.proto";
import "shiguchi/api/method.proto";

rpc GetOrder(GetOrderRequest) returns (Order) {
  option (google.api.http) = { get: "/v1/orders/{id}" };
  option (shiguchi.api.graphql) = { name: "order", type: QUERY };
  option (shiguchi.api.method) = {
    auth_scopes: "orders.read"
    cache_ttl_seconds: 30
    authz: [{ object_type: "order", relation: "viewer", id_field: "id" }]
  };
}
```

This page covers `graphql.proto` and `method.proto`. A method needs only the options it uses:
GraphQL binding works without `(shiguchi.api.method)`, and the method settings work without a
GraphQL option.

Everything here can also be set without touching protos via the YAML `field-overrides` map (see
[configuration.md](configuration.md)) — YAML wins.

## Getting the file

It ships in `shiguchi-proto`, three ways.

**As a jar dependency (JVM builds).** Gradle's protobuf plugin extracts `.proto` files out of
`compile`-scoped dependencies onto the include path by itself, so declaring the dependency is the
whole setup — no `protobuf(...)` configuration needed:

```kotlin
dependencies {
    implementation("io.github.appiaworks:shiguchi-proto:0.14.0")
}
```

Usually you already have it: every gateway starter depends on `shiguchi-proto`, which is why
the samples in this repository import `shiguchi/api/*.proto` without naming it. If an import will not
resolve, look at what your build extracted under `build/extracted-include-protos/main/`.

Maven's `protobuf-maven-plugin` does not extract protos from dependencies. Unpack them with
`maven-dependency-plugin` and add the output directory to `<additionalProtoPathElements>` — the
full snippet is in
[projections-paging-sorting.md](projections-paging-sorting.md#getting-the-protos).

**As a zip (any toolchain).** A `protos` classifier artifact carries the same files with no jar
involved, for Go, Rust, Python, buf or a plain `protoc`:

```bash
curl -sLO https://repo1.maven.org/maven2/io/github/appiaworks/shiguchi-proto/0.14.0/shiguchi-proto-0.14.0-protos.zip
unzip -q shiguchi-proto-0.14.0-protos.zip -d gateway-protos
protoc -I . -I gateway-protos --go_out=. bookstore/v1/books.proto
```

The archive reproduces the import paths verbatim, so the directory you unzip into goes on `-I`
unchanged.

**By copying.** The `shiguchi/api` files depend only on `google/protobuf/descriptor.proto`, so
vendoring them works. Unlike the paging messages, though, keep the `shiguchi.api` package and the
extension field numbers exactly as they are: these are **extensions of `descriptor.proto`**, and the gateway
finds them by fully-qualified name in the descriptor. A renamed package means annotations the
gateway silently does not see — a schema that builds and simply omits your fields.

> A non-JVM backend can still be annotated. The options only have to survive into the descriptor
> the gateway reads, so `protoc --include_imports --descriptor_set_out=...` over a Go or Rust
> service's protos carries them fine — see [`PB_FILE` mode](configuration.md).

## Method option `(shiguchi.api.graphql)`

Binds a gRPC method to a GraphQL field:

```protobuf
rpc ListBooks(ListBooksRequest) returns (ListBooksResponse) {
  option (shiguchi.api.graphql) = {
    name: "listBooks"
    type: QUERY
    description: "All books, paged"
  };
}
```

| Field | Meaning |
| --- | --- |
| `name` | GraphQL field name — required to expose the method in GraphQL |
| `type` | `QUERY` · `MUTATION` · `SUBSCRIPTION` (server-streaming, one item per event; needs `spring.graphql.websocket.path` set, e.g. `/graphql/ws`, and is then served as graphql-transport-ws) · `RESOLVER` (adds a field to an existing type) |

| `parent_type` | For `RESOLVER`: the GraphQL type that gains the field |
| `description` / `deprecation_reason` | Doc-string / `@deprecated`. In `PB_FILE` mode the comment above the `rpc` is used when this is unset — see [Descriptions from proto comments](#descriptions-from-proto-comments) |
| `arg_fields` | Request fields (snake_case) lifted to individual top-level GraphQL arguments |
| `pluck` | Response field returned directly instead of the wrapper message |
| `loader_for` | Declares the method as a DataLoader for the named type (batched resolution) |
| `loader_ids_field` / `loader_result_field` | Request ids field / response items field for the loader |

Push namespaces get subscription fields of their own without an annotation; see [push.md](push.md#graphql-subscriptions).

## Field option `(shiguchi.api.graphql_field)`

```protobuf
string author_id = 3 [(shiguchi.api.graphql_field) = { id: true, resolves_type: "Author" }];
```

| Field | Meaning |
| --- | --- |
| `required` | Emit `Type!` |
| `id` | Use the `ID` scalar |
| `name` | Rename the GraphQL field |
| `omit` | Hide from the schema |
| `default` | Default value in input types |
| `description` / `deprecation_reason` | Doc-string / `@deprecated` |
| `resolves_type` | This id field resolves to the named type(s): the gateway wires a DataLoader-backed nested resolver (field name derived by stripping `_id`) |
| `batch_key` | Marks the identity field used for DataLoader batch keying and federation `@key` |

## Descriptions from proto comments

Most protos are already documented — a comment above the service, above each `rpc`, above each
field. In `PB_FILE` mode the gateway reads those comments and uses them as descriptions, so an
API documented once in the `.proto` is documented on the surfaces it generates:

```proto
// Fetches one product by SKU.
rpc GetProduct(GetProductRequest) returns (Product) {
  option (google.api.http) = {get: "/v1/products/{sku}"};
}
```

becomes the GraphQL doc-string and the OpenAPI operation's `description` (its first line also
becomes the `summary`) with no annotation involved. An explicit `(shiguchi.api.graphql).description`
wins where one is set — the comment is a fallback, not an override.

Two requirements, both on the descriptor rather than the gateway:

- **`protoc --include_source_info`**, or `descriptorSetOptions.includeSourceInfo = true` with
  Gradle's protobuf plugin. Comments live in the descriptor's `SourceCodeInfo`, which protoc
  omits by default.
- **`mode: PB_FILE`**. `DYNAMIC` and `STATIC` cannot do this and are not failing when they
  don't: gRPC server reflection never sends source info, and compiled Java stubs do not retain
  it. There is nothing to read in those modes.

Leading comments are preferred over trailing ones, and a method with no comment simply gets no
description. See [samples/sample-static-schema](../samples/sample-static-schema/README.md) for a
runnable end-to-end example.

## How the pieces combine

```protobuf
message Book {
  string id        = 1 [(shiguchi.api.graphql_field) = { id: true, batch_key: true }];
  string author_id = 2 [(shiguchi.api.graphql_field) = { id: true, resolves_type: "Author" }];
  string title     = 3;
}

rpc GetAuthorsByIds(GetAuthorsByIdsRequest) returns (GetAuthorsByIdsResponse) {
  option (shiguchi.api.graphql) = {
    name: "authorsByIds"
    type: QUERY
    loader_for: "Author"
    loader_ids_field: "ids"
  };
}
```

yields:

```graphql
type Book {
  id: ID!
  authorId: ID
  author: Author      # nested resolver, batched through the authorsByIds loader
  title: String
}
```

Queries selecting `books { author { name } }` batch all author ids into one
`GetAuthorsByIds` call per request (DGS DataLoader), and the selection set is pushed down
as a `read_mask` when the request message has one.


## Method option `(shiguchi.api.method)`

Settings that apply to the method on every surface it is exposed on (REST, GraphQL, gRPC-Web).
They are read whether or not the method has a GraphQL binding, and whether or not GraphQL is on.

```protobuf
import "shiguchi/api/method.proto";

rpc GetOrder(GetOrderRequest) returns (Order) {
  option (shiguchi.api.method) = {
    auth_scopes: ["orders:read"]
    cache_ttl_seconds: 30
  };
}
```

| Field | Meaning |
| --- | --- |
| `auth_scopes` | JWT scopes enforced before dispatch — *may this caller call this method?* |
| `authz` | Object-level rules — *may this caller act on **this object**?* See below |
| `cache_ttl_seconds` | `Cache-Control: max-age` on the REST route, plus a strong `ETag` over the response bytes — a caller sending `If-None-Match` gets a 304 with no body when nothing changed. A `services[].cache` rule for the method overrides it; see [caching.md](caching.md) |

### `authz` — object-level authorization

`auth_scopes` is a property of the caller alone, so it cannot express "may this caller read
**order 42**?". `authz` declares that question against a specific object:

```protobuf
rpc GetOrder(GetOrderRequest) returns (Order) {
  option (shiguchi.api.graphql) = { name: "order", type: QUERY };
  option (shiguchi.api.method) = {
    auth_scopes: ["orders:read"]          // may you call this method at all
    authz: [{                             // may you act on THIS order
      object_type: "order"
      relation: "viewer"
      id_field: "order_id"
    }]
  };
}
```

| Field | Meaning |
| --- | --- |
| `object_type` | Object type in your policy engine's model, e.g. `order` |
| `relation` | Relation/permission the caller must hold, e.g. `viewer` |
| `id_field` | Request field (snake_case) whose value is the object id. Dots address nested fields (`order.id`) |

All rules on a method must pass. `id_field` is resolved against what the caller actually sent —
a REST path variable or query parameter, or a GraphQL field argument — and matches either the
snake_case name or its camelCase form.

**The gateway does not evaluate these rules.** They are carried on the route for a
`GatewayAuthorizer` bean to consume (see
[configuration.md](configuration.md#per-object-authorization)); `AuthzRuleResolver` does the
lookup so each implementation does not re-derive it. With no such bean wired the rules are inert
and the route serves normally, so the same `.proto` works behind a gateway with or without a
policy engine.

A rule whose `id_field` is absent from the request is reported as *unresolved*, and the shipped
sample denies in that case: an annotation typo or a missing parameter must not degrade into
"allow". A rule missing any of the three fields is dropped at startup with a WARN rather than
failing the route.

