# Proto annotations

The gateway reads its settings from custom options on your methods and fields, in the same style
as `google.api.http`. They all live in the `shiguchi` package, one file per concern:

| File | Option | Applies to |
| --- | --- | --- |
| `shiguchi/graphql.proto` | `(shiguchi.graphql)` on a method, `(shiguchi.field)` on a field | The generated GraphQL schema |
| `shiguchi/method.proto` | `(shiguchi.method)` on a method | Every surface: REST, GraphQL, gRPC-Web |
| `shiguchi/merge.proto` | `(shiguchi.merge)` on a method | A route served by merging backends, see [merging.md](merging.md) |
| `shiguchi/topic.proto` | `(shiguchi.topic)` on a method | A push topic fed by a server stream, see [push.md](push.md) |

Import `shiguchi/annotations.proto` to get all four at once, or only the files you use:

```proto
import "shiguchi/annotations.proto";

rpc GetOrder(GetOrderRequest) returns (Order) {
  option (google.api.http) = { get: "/v1/orders/{id}" };
  option (shiguchi.graphql) = { name: "order", type: QUERY };
  option (shiguchi.method) = {
    auth_scopes: ["orders:read"]
    cache_ttl_seconds: 30
    authz: [{ object_type: "order", relation: "viewer", id_field: "id" }]
  };
}
```

This page covers `graphql.proto` and `method.proto`. A method needs only the options it uses:
GraphQL binding works without `(shiguchi.method)`, and the method settings work without a
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
    implementation("io.github.appiaworks:shiguchi-proto:0.19.0")
}
```

Usually you already have it: every gateway starter depends on `shiguchi-proto`, which is why
the samples in this repository import `shiguchi/*.proto` without naming it. If an import will not
resolve, look at what your build extracted under `build/extracted-include-protos/main/`.

Maven's `protobuf-maven-plugin` does not extract protos from dependencies. Unpack them with
`maven-dependency-plugin` and add the output directory to `<additionalProtoPathElements>` — the
full snippet is in
[projections-paging-sorting.md](projections-paging-sorting.md#getting-the-protos).

**As a zip (any toolchain).** A `protos` classifier artifact carries the same files with no jar
involved, for Go, Rust, Python, buf or a plain `protoc`:

```bash
curl -sLO https://repo1.maven.org/maven2/io/github/appiaworks/shiguchi-proto/0.19.0/shiguchi-proto-0.19.0-protos.zip
unzip -q shiguchi-proto-0.19.0-protos.zip -d gateway-protos
protoc -I . -I gateway-protos --go_out=. bookstore/v1/books.proto
```

The archive reproduces the import paths verbatim, so the directory you unzip into goes on `-I`
unchanged.

**By copying.** The `shiguchi/*.proto` option files depend only on `google/protobuf/descriptor.proto`, so
vendoring them works. Unlike the paging messages, though, keep the extension field numbers
(50100–50103, 50200) exactly as they are: these are **extensions of `descriptor.proto`**, and the
gateway finds them by number in the descriptor. A changed number means annotations the gateway
silently does not see — a schema that builds and simply omits your fields. Keep the `shiguchi`
package too, so the generated code and the documentation match.

> A non-JVM backend can still be annotated. The options only have to survive into the descriptor
> the gateway reads, so `protoc --include_imports --descriptor_set_out=...` over a Go or Rust
> service's protos carries them fine — see [`PB_FILE` mode](configuration.md).

## Method option `(shiguchi.graphql)`

Binds a gRPC method to a GraphQL field:

```protobuf
rpc ListBooks(ListBooksRequest) returns (ListBooksResponse) {
  option (shiguchi.graphql) = {
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
| `cost` | Weight of this field when the operation's cost is computed (default 1); see [Query cost](#query-cost) |
| `shareable`, `external`, `requires`, `provides` | Federation directives on the field, see [Federation](#federation) |
| `directives` | Raw SDL directives appended to the field, see [Federation](#federation) |

Push namespaces get subscription fields of their own without an annotation; see [push.md](push.md#graphql-subscriptions).

## Field option `(shiguchi.field)`

```protobuf
string author_id = 2 [(shiguchi.field) = { id: true, resolves_type: "Author" }];
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
| `cost` | Weight of this field when the operation's cost is computed (default 1); see [Query cost](#query-cost) |

## Federation

With `shiguchi.graphql.federation.enabled: true` the gateway publishes `_service { sdl }` and resolves `_entities`. A type's key is the field marked `batch_key`, or the key sets of the message option `(shiguchi.type)`:

```protobuf
message Book {
  option (shiguchi.type) = { keys: "id" keys: "isbn" description: "A book" };
  int64 id = 1 [(shiguchi.field) = { id: true }];
  string isbn = 2;
  string shelf = 3 [(shiguchi.field) = { shareable: true }];
  float price = 4 [(shiguchi.field) = { external: true }];
  float price_with_tax = 5 [(shiguchi.field) = { requires: "price" }];
}
```

| `(shiguchi.type)` | Meaning |
| --- | --- |
| `keys` | One `@key(fields: "...")` per entry (`"id"`, `"orgId id"`); proto or JSON field names. Replaces the `batch_key` fallback. `_entities` resolves by the first single-field key; composite keys are published in the SDL only |
| `unresolvable` | Emit `@key(resolvable: false)`: other subgraphs reference the type, this gateway does not resolve it |
| `directives` | Raw SDL directives appended to the type |
| `description` | Doc-string of the GraphQL type |

`shareable`, `external`, `requires` and `provides` (field option) emit the directive of the same name and are ignored, with one WARN, while federation is off. `requires` and `provides` must parse as a selection set; a bad one fails the schema build and names the field.

`directives` (on `(shiguchi.graphql)`, `(shiguchi.field)` and `(shiguchi.type)`) is an escape hatch: the text is appended verbatim, e.g. `directives: "@tag(name: \"internal\")"`. The gateway does not validate the directive names or arguments, and the schema is only valid if the directive is defined: the federation ones are (`@key`, `@shareable`, `@external`, `@requires`, `@provides`, `@extends`), anything else must be declared by a `GraphQlContributor`.

## Query cost

`shiguchi.graphql.limits.max-complexity`, the per-caller budget and `report-cost` all use one number: the
cost of the operation. A field costs its `cost` (1 when unset); a list field costs that many
times its `first` argument, else the enclosing field's `first`, else
`shiguchi.graphql.limits.default-list-size` (10); the cost of the fields selected under a field
is added. Put the weight where the work is:

```protobuf
rpc SearchBooks(SearchRequest) returns (SearchResponse) {
  option (shiguchi.graphql) = { name: "searchBooks" type: QUERY cost: 20 };
}
message Book { Author author = 3 [(shiguchi.field) = { cost: 5 }]; }
```

```yaml
shiguchi.graphql.limits:
  max-complexity: 200      # one operation
  budget.per-minute: 1000  # per caller: principal, else client IP (shiguchi.rate-limit.key)
  report-cost: true        # extensions.cost in every response
```

An operation over `max-complexity` is not run and answers `maximum query complexity exceeded 250 > 200`.
An operation the caller's budget cannot cover is not run either: the error has classification
`RATE_LIMITED` and `extensions.retryAfter`, the response a `Retry-After` header, and the HTTP
status stays 200 as GraphQL over HTTP expects. The budget is a bucket that refills at
`per-minute` points per minute; it lives in memory, or in Redis (shared by every gateway
instance) when `shiguchi.rate-limit.backend` is `REDIS`.

## Introspection and batched requests

`shiguchi.graphql.introspection` is `ENABLED` (default), `AUTHENTICATED` or `DISABLED`, decided per
request. An operation that selects `__schema` or `__type` while refused is answered with a
GraphQL error and is not run. Prefer `AUTHENTICATED` on a gateway that is reachable by
untrusted clients.

`shiguchi.graphql.batch.max-operations: N` (default 0, off) accepts a POST whose body is a JSON array of up to
N operations. They run in order, each through the whole interceptor chain, so every operation
is checked against the limits on its own and spends its own cost from the caller's budget. The
response is an array of results in the same order, with the largest `Retry-After` of the
operations. An array that is empty or longer than N is refused with 400. Each operation has
its own DataLoader registry: loaders batch within an operation, not across the array.

### `@live`

`shiguchi.graphql.live.enabled: true` (default off) declares `directive @live(throttleMs: Int) on QUERY`.
`query Orders @live(throttleMs: 2000) { orders { id total } }` sent over `graphql-transport-ws` (or a POST
with `Accept: text/event-stream`) runs once, then again whenever a mutation through this gateway succeeds on a
gRPC service behind one of its root fields, sending each new full result as a `next` frame with
`extensions.live.seq` (1, 2, ...). Two runs are at least `max(min-interval, throttleMs)` apart, signals in
between collapse into one run, and a result equal to the one before is not sent. Every run passes the limits and
the cost budget again, as the caller who started it, and skips the response cache. A refused budget
(`RATE_LIMITED`) keeps the stream open; a result without data (validation error) or `unauthenticated` ends it.
Past `max-per-principal` the new query is answered with a `LIVE_LIMIT` error. On any other transport `@live` is
ignored and the query runs once. Invalidation is in-process: with several gateway instances a mutation re-runs
the live queries on the instance that served it, and `refresh-interval` repairs the rest. Design:
[design/graphql-live.md](design/graphql-live.md).

### `@defer`

`shiguchi.graphql.incremental.enabled: true` (default off) declares the `@defer` directive and lets a client
read a query in pieces: it sends `Accept: multipart/mixed;deferSpec=20220824` (Apollo Client and urql do) and
`{ item(id: "7") { id ... @defer(label: "more") { name } } }` answers `multipart/mixed` with the initial
payload (`{data, hasNext: true}`) first and each deferred payload (`{incremental: [{path, label, data}],
hasNext}`) after it. A client that does not accept `multipart/mixed` gets the complete JSON result, with
`@defer` ignored. The operation is priced and limited as a whole before anything runs. The response cache,
batches and WebSocket operations never answer incrementally. `@stream` is not available: the engine
(graphql-java 25) does not implement it.

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
becomes the `summary`) with no annotation involved. An explicit `(shiguchi.graphql).description`
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
  string id        = 1 [(shiguchi.field) = { id: true, batch_key: true }];
  string author_id = 2 [(shiguchi.field) = { id: true, resolves_type: "Author" }];
  string title     = 3;
}

rpc GetAuthorsByIds(GetAuthorsByIdsRequest) returns (GetAuthorsByIdsResponse) {
  option (shiguchi.graphql) = {
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

All three pieces are needed for `resolves_type` to resolve:

- a method with `loader_for` naming the target type,
- a `batch_key` field on the target type's message (the loader keys its results by it; without one it falls back to a field named `id`),
- no existing field on the referencing message with the derived resolver name (`author_id` → `author`).


## Protos written for other gateways

Some gRPC-to-GraphQL gateways annotate protos with their own option family, the `graphql.*`
options: proto package `graphql`, extension number 1079 on method, field and service options.
Shiguchi can read those as aliases of `(shiguchi.graphql)` and `(shiguchi.field)`, so one proto
serves both gateways. The reader is off by default; a proto carrying the options logs one INFO
hint per file and the methods keep their convention-based binding. Switch it on with:

```yaml
shiguchi:
  graphql:
    compat-options: [graphql-1079]
```

Nothing is vendored: the gateway decodes the options by number against the `graphql` package's
own descriptors, which travel with the proto (`DYNAMIC`, `PB_FILE`) or sit on the classpath
(`STATIC`). A proto that uses extension number 1079 for something else, without importing the
`graphql` package, is left alone.

What maps:

| `graphql.*` option (1079) | Shiguchi option |
| --- | --- |
| `schema.type` (`QUERY`, `MUTATION`, `SUBSCRIPTION`, matched by name) | `(shiguchi.graphql).type` |
| `schema.name` | `(shiguchi.graphql).name` |
| `schema.request.plucks` | `(shiguchi.graphql).arg_fields` |
| `schema.response.pluck` | `(shiguchi.graphql).pluck` |
| `field.required` | `(shiguchi.field).required` |
| `field.name` | `(shiguchi.field).name` |
| `field.default` | `(shiguchi.field).default` |
| `field.omit` | `(shiguchi.field).omit` |
| `field.description` | `(shiguchi.field).description` |

When a method or field carries both families, the Shiguchi option wins and the alias is not
read. Everything else in the family is ignored with one WARN per file, method or field: a
`schema.type` of `RESOLVER` and `field.resolver` (nested resolvers are declared with
`parent_type`, `loader_for` and `resolves_type` instead), `schema.request.name`,
`schema.response.required`, the `service` option (hosts come from `shiguchi.services`), and the
federation fields some variants add. A route bound through the alias shows
`graphqlOptions: graphql-1079` in the routes report, so you can tell which protos still depend
on it.

## Method option `(shiguchi.method)`

Settings that apply to the method on every surface it is exposed on (REST, GraphQL, gRPC-Web).
They are read whether or not the method has a GraphQL binding, and whether or not GraphQL is on.

```protobuf
import "shiguchi/method.proto";

rpc GetOrder(GetOrderRequest) returns (Order) {
  option (shiguchi.method) = {
    auth_scopes: ["orders:read"]
    cache_ttl_seconds: 30
  };
}
```

| Field | Meaning |
| --- | --- |
| `auth_scopes` | JWT scopes enforced before dispatch — *may this caller call this method?* |
| `authz` | Object-level rules — *may this caller act on **this object**?* See below |
| `cache_ttl_seconds` | `Cache-Control: max-age` on the REST route, plus a strong `ETag` over the response bytes — a caller sending `If-None-Match` gets a 304 with no body when nothing changed. A `services[].cache` rule for the method overrides it. With `shiguchi.graphql.response-cache` on, it is also the TTL of the GraphQL response cache for queries whose root fields all resolve to a method carrying one; see [caching.md](caching.md) |

### `authz` — object-level authorization

`auth_scopes` is a property of the caller alone, so it cannot express "may this caller read
**order 42**?". `authz` declares that question against a specific object:

```protobuf
rpc GetOrder(GetOrderRequest) returns (Order) {
  option (shiguchi.graphql) = { name: "order", type: QUERY };
  option (shiguchi.method) = {
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

