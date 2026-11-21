# Proto annotations

The gateway reads its settings from custom options on your methods and fields, in the same style
as `google.api.http`. They all live in the `shiguchi` package, one file per concern:

| File | Option | Applies to |
| --- | --- | --- |
| `shiguchi/graphql.proto` | `(shiguchi.graphql)` on a method, `(shiguchi.field)` on a field | The generated GraphQL schema |
| `shiguchi/method.proto` | `(shiguchi.method)` on a method | Every surface: REST, GraphQL, gRPC-Web |
| `shiguchi/merge.proto` | `(shiguchi.merge)` on a method | A route served by merging backends, see [merging.md](merging.md) |
| `shiguchi/topic.proto` | `(shiguchi.topic)` on a method | A push topic fed by a server stream, see [push.md](cursus/index.md) |

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
[configuration.md](../reference/configuration.md)) — YAML wins.

## Getting the file

It ships in `shiguchi-proto`, three ways.

**As a jar dependency (JVM builds).** Gradle's protobuf plugin extracts `.proto` files out of
`compile`-scoped dependencies onto the include path by itself, so declaring the dependency is the
whole setup — no `protobuf(...)` configuration needed:

```kotlin
dependencies {
    implementation("io.github.appiaworks:shiguchi-proto:0.27.0")
}
```

Usually you already have it: every gateway starter depends on `shiguchi-proto`, which is why
the samples in this repository import `shiguchi/*.proto` without naming it. If an import will not
resolve, look at what your build extracted under `build/extracted-include-protos/main/`.

Maven's `protobuf-maven-plugin` does not extract protos from dependencies. Unpack them with
`maven-dependency-plugin` and add the output directory to `<additionalProtoPathElements>` — the
full snippet is in
[projections-paging-sorting.md](projections/index.md#getting-the-protos).

**As a zip (any toolchain).** A `protos` classifier artifact carries the same files with no jar
involved, for Go, Rust, Python, buf or a plain `protoc`:

```bash
curl -sLO https://repo1.maven.org/maven2/io/github/appiaworks/shiguchi-proto/0.27.0/shiguchi-proto-0.27.0-protos.zip
unzip -q shiguchi-proto-0.27.0-protos.zip -d gateway-protos
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
> service's protos carries them fine — see [`PB_FILE` mode](../reference/configuration.md).

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
| `group_by` | Makes the loader one-to-many: the field of the result entity that holds the parent key (`author_id` on a `Book`); see [One-to-many fields](#one-to-many-fields) |
| `cost` | Weight of this field when the operation's cost is computed (default 1); see [Query cost](#query-cost) |
| `shareable`, `external`, `requires`, `provides` | Federation directives on the field, see [Federation](#federation) |
| `directives` | Raw SDL directives appended to the field, see [Federation](#federation) |

Push namespaces get subscription fields of their own without an annotation; see [push.md](cursus/subscribing.md#graphql-subscriptions).

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
| `resolves_type` | This id field resolves to the named type(s): the gateway wires a DataLoader-backed nested resolver (field name derived by stripping `_id`). On a `repeated` id field (`character_ids`) the companion is a list, `characters: [EveCharacter!]`, one entity per id in order (ids the loader does not return are dropped); the name strips `Ids` and pluralises |
| `resolves_many` | This id field resolves to the list of child entities whose `group_by` field equals it: a nullable `[Type!]` field backed by a grouped loader; see [One-to-many fields](#one-to-many-fields) |
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
the live queries on the instance that served it, and `refresh-interval` repairs the rest. With the push module on (`shiguchi.cursus.enabled`), mutations also re-run the live queries of the other instances through the Cursus broker (shared engine needed: Redis, Postgres or JDBC), and a method can name upstream topics that re-run its live queries: `option (shiguchi.graphql) = { ...; invalidated_by: "orders:changed" }`. The topics are exact (`namespace:name`; wildcards are ignored with a warning) and are re-read from the routes every 30 s. Meters: `shiguchi.graphql.live.streams` and `.waiting` (gauges), `.executions`, `.skipped` (identical result, not sent) and `.signals` (tagged `source`: mutation, upstream, remote, refresh). A method can keep itself out of live queries with `option (shiguchi.graphql) = { ...; live: false }`: a query that selects it runs once, like an ordinary query. Design:
[design/graphql-live.md](../../design/graphql-live.md).

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
description. See [samples/sample-static-schema](../../samples/sample-static-schema/README.md) for a
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
`GetAuthorsByIds` call per request (DGS DataLoader). The `read_mask` pushdown of the selection set applies to
query and mutation fields, not to a loader batch: the batch asks for whole entities. The same holds for a
relationship inside a nested message, and for a chain (order, customer, region): each loader makes one call
per level of the query.

When a batch call fails (backend error, timeout), every key in it fails: the field is null and the response
carries an error with `extensions.grpcCode`, `service` and `method`, while the rest of the response is intact. A split
batch (see `data-loader.max-batch-size`) fails only the chunk that failed. A key the backend does not return
is null without an error. Per request and loader at most 20 keys carry their own error; the next one carries a
single error with `extensions.omittedFailures` (the count of the rest of its batch) and the other failed keys of
that batch are null. Set `shiguchi.graphql.data-loader.on-error: "NULL"` to resolve failed keys to null with
only a log warning, as releases before this setting did (quote it: a bare `NULL` is a YAML null).

All three pieces are needed for `resolves_type` to resolve:

- a method with `loader_for` naming the target type,
- a `batch_key` field on the target type's message (the loader keys its results by it; without one it falls back to a field named `id`),
- no existing field on the referencing message with the derived resolver name (`author_id` → `author`).

### One-to-many fields

`resolves_type` points from a child to its one parent. The other direction, an author to all their books,
is `resolves_many` on the parent's id field, served by a loader that declares `group_by`:

```protobuf
message Author {
  string id = 1 [(shiguchi.field) = {
    id: true
    resolves_many: { type: "Book" field: "books" loader: "author_id" }
  }];
  string name = 2;
}

message Book {
  string id        = 1 [(shiguchi.field) = { id: true, batch_key: true }];
  string author_id = 2;
  string title     = 3;
}

rpc BooksByAuthors(BooksByAuthorsRequest) returns (BooksByAuthorsResponse) {
  option (shiguchi.graphql) = {
    name: "booksByAuthors"
    type: QUERY
    loader_for: "Book"
    loader_ids_field: "author_ids"   # repeated string in the request
    loader_result_field: "items"     # repeated Book in the response
    group_by: "author_id"            # the Book field that holds the parent key
  };
}
```

yields `books: [Book!]` on `Author`, and `authors { books { title } }` costs one call for the authors plus one
`BooksByAuthors` call for all of them, however many authors there are.

- `resolves_many.type` is the child type, `loader` the `group_by` of the loader that serves it, `field` the
  GraphQL field name (default: the decapitalised type plus `s`, `books`).
- The loader maps each requested key to the children whose `group_by` field equals it, in the order the
  upstream returned them. An author without children answers `[]`, not null.
- The loader is its own DataLoader, named `Book#by:authorId`. It never replaces the type's by-id loader
  (`loader_for: "Book"` without `group_by`), is not used by Federation `_entities` and is not cached.
  `data-loader.cache.<name>` naming a grouped loader fails startup, as does a `group_by` that is not a field of
  the result message.
- Duplicate parent ids send each distinct key once, and `data-loader.max-batch-size` splits a large batch into
  chunks like any other loader.
- A batch failure follows `data-loader.on-error`: under `ERROR` (default) the field errors; under `NULL` it
  resolves to `[]` with a log warning.
- `data-loader.max-children-per-key` (default `0`, no cap) fails a key that has more children than the cap
  instead of answering them. It protects the response, not the upstream: the batch response is already
  decoded when the cap is applied, so size the upstream page itself for large fan-outs.
- The list is costed like any list field (see [Query cost](#query-cost)): selecting `books` under a list
  of `first` authors multiplies by `first` (or `default-list-size`).

### Relationships on subscription events

Each event of a subscription runs its own execution, so by default every event costs its own batch call per
relationship loader. Set `data-loader.subscription-batch-window` (for example `50ms`) and the loads of events that
arrive within the window go out as one batch per loader. It adds up to one window of latency to each event, so it
is off by default; queries and mutations are not affected. A subscription keeps one DataLoader cache for its whole
life, so a key loaded once is not asked of the backend again while the subscription lasts.

### Which way should I declare it?

A relationship can come from five places. They can be mixed in one gateway, even in one service.

| You have | Declare it with | Where it lives | Pick it when |
| --- | --- | --- | --- |
| A gRPC service you own | [Annotation](#how-the-pieces-combine): `(shiguchi.field)` and `(shiguchi.graphql)` | The proto | The proto is yours to change; the relationship travels with the schema |
| A REST service whose OpenAPI document you own | [OpenAPI extension](../reference/openapi.md#x-shiguchi-relationship-extensions): `x-shiguchi-references`, `x-shiguchi-has-many`, `x-shiguchi-loader` | The backend's document | The backend team owns the relationship and the gateway should pick it up with no gateway change |
| A service you cannot change (a third party, a document you only read) | [YAML](#relationships-in-yaml): `relationships[]`, `rest-batch-loaders[]` | The gateway's `application.yml` | The gateway operator owns the relationship, or the backend document is out of your hands |
| Data the gateway cannot reach itself (a hand-written client, a database, an in-process service) | [Beans](#custom-loaders-and-relationships-as-beans): `BatchLoaderDefinition`, `RelationshipDefinition` | Java in the gateway | The loader needs code |
| No idea yet | [Suggestions](#see-what-the-gateway-resolved): the `suggestions` list of `gateway-relationships` | Nothing: it only reports | You want candidates from field names, to copy into one of the above |

When two sources declare the same `Type.field`, the higher one wins. From lowest to highest:
suggestions (never wired), proto annotation, OpenAPI extension, YAML, bean. A lower source is not an error
when it is overridden, so a gateway operator can correct a backend's declaration in YAML without touching the
backend. The `gateway-relationships` endpoint and the console show the source that won.

The [`sample-graphql-relationships`](../../samples/sample-graphql-relationships/README.md)
sample declares all three of the non-proto paths over REST services, with no annotation at all, and has a
test that counts the backend calls.

### Relationships in YAML

The same relationships, batch keys and loaders can be declared per service in YAML, for protos you do
not own or cannot annotate. Annotations and YAML merge; where both declare the same companion field the
YAML entry wins.

```yaml
shiguchi:
  services:
    - name: library
      relationships:
        - from: Author.id          # Type.proto_field holding the id
          to: Book
          many: true               # one-to-many: needs group-by
          group-by: author_id      # child field matched against the parent id
        - from: Book.author_id
          to: Author
          field: writer            # optional companion name
      batch-keys:
        Book: isbn                 # by-id loader for Book keys on isbn (default id)
      loaders:
        - type: Book
          rpc: lib.Library/BooksByAuthors
          ids-field: author_ids
          result-field: items
          group-by: author_id
```

`loaders[]` is the YAML form of `loader_for`, `loader_ids_field`, `loader_result_field` and `group_by`
and overrides the proto options of that rpc. REST loaders stay in `rest-batch-loaders[]` (next section).
Every entry is checked at startup: an unknown `from` type or field, a `to` type with no loader (or no
loader with that `group-by`), a `group-by` that is not a child field, or a duplicate companion field
fails startup with a message naming the entry and its YAML path. On a reload the problem is only logged.

### Relationships on REST_DOWNSTREAM services

The easiest place to declare them is the OpenAPI document itself, with `x-shiguchi-*` extensions (the
[extensions table](../reference/openapi.md) lists them all). Type names are the document's schema names; the
gateway prefixes them. This needs no YAML at all:

```json
{
  "paths": {
    "/customers": { "get": { "operationId": "listCustomers",
      "x-shiguchi-loader": { "type": "Customer", "ids-param": "ids" } } },
    "/orders": { "get": { "operationId": "listOrders",
      "x-shiguchi-loader": { "type": "Order", "ids-param": "customerIds", "group-by": "customerId" } } }
  },
  "components": { "schemas": {
    "Customer": { "x-shiguchi-batch-key": "id", "properties": {
      "id": { "type": "string",
              "x-shiguchi-has-many": { "type": "Order", "group-by": "customerId", "field": "orders" } } } },
    "Order": { "properties": {
      "customerId": { "type": "string", "x-shiguchi-references": "Customer" } } }
  } }
}
```

That gives `Order.customer` and `Customer.orders`, each one batched call however many parents the query returns.
A mistake (unknown schema, no loader) fails the start naming the extension and its location. The extensions stay
in the consolidated OpenAPI document. YAML is the alternative when you do not own the document, and it wins over
an extension on the same field. `RelationshipSuggester` can propose candidates from `<type>Id` property names;
it never wires them.

The same declarations in YAML:

A `REST_DOWNSTREAM` service takes the same `relationships[]`. `from` and `to` use the GraphQL type
names of the generated schema, so with `graphql-type-prefix: Shop` the OpenAPI schema `Order` is
`ShopOrder`; `from` names the property of the OpenAPI schema as written (`customerId`). The OpenAPI
document needs no annotations. The loaders go in `rest-batch-loaders[]`:

```yaml
shiguchi:
  services:
    - name: shop
      mode: REST_DOWNSTREAM
      openapi-url: http://shop:8080/v3/api-docs
      graphql-type-prefix: Shop
      relationships:
        - from: ShopOrder.customerId     # many-to-one: Order.customer
          to: ShopCustomer
        - from: ShopCustomer.id          # one-to-many: Customer.orders
          to: ShopOrder
          many: true
          field: orders
          group-by: customerId
      rest-batch-loaders:
        - type: ShopCustomer             # by id: GET /customers?ids=c1,c2
          endpoint: /customers
          ids-param: ids
        - type: ShopOrder                # by parent: GET /orders?customerIds=c1,c2
          endpoint: /orders
          ids-param: customerIds
          group-by: customerId           # the order field holding the customer id
```

An entry without `group-by` is a by-id loader (also used by Federation `_entities`). An entry with
`group-by` returns the children of the given parent ids and is named `Type#by:camelCase(group-by)`; it
may share a `type` with a by-id entry. `group-by` is matched against the key as it appears in the
response, so write it as the backend spells it. `endpoint`, `ids-param`, `result-field`, `batch-endpoint`,
`timeout-seconds`, `max-batch-size` and `max-concurrency` mean the same as for by-id entries, and
`data-loader.max-children-per-key` caps the children of one parent.

With `batch-endpoint: false` the loader makes one GET per parent id, with `{id}` in `endpoint`
(`/customers/{id}/orders`), at most `max-concurrency` at a time; a 404 means no children. The batch form
sends the ids comma separated, so an id containing a comma fails; use the per-id form for those.
A relationship whose loader does not exist (`to` without a `rest-batch-loaders` entry, or a many entry
without one that has the same `group-by`) is checked like the gRPC ones. Loaders are rebuilt on every
schema reload, so a service whose OpenAPI document loads late still gets its relationships.

### Several backends serve the same type

A type's loader belongs to one endpoint. If two endpoints each declare a `loader_for` for the same type, the
gateway does not guess: startup fails with a message naming the type, both endpoints and the key to set.
Pick the owner in YAML:

```yaml
shiguchi:
  graphql:
    data-loader:
      types:
        Customer:
          endpoint: customers-east   # the configured name of the owning service
```

The pinned endpoint's loader is registered under the plain type name, so `resolves_type` fields and Federation
`_entities` both use it. The other endpoint's loader is not registered; the gateway logs that at INFO. A pin that
names an endpoint with no `loader_for` for the type fails startup, which catches a misspelt name. That includes an
endpoint that is not loaded yet: pinning a type to a `DYNAMIC` service (or any endpoint) whose loader is not there
when the gateway starts fails startup, so pin only endpoints that are present at boot. After startup the same
mistake is logged at ERROR and the running gateway stays up.

The pin decides the loader only. It does not change which endpoint serves a Query field: a `customers` query on the
other endpoint keeps working. The loader's cache key does not include the endpoint either.

If a reload (a refreshed descriptor, a late-loaded service) brings a loader that conflicts with the one already
serving a type, the gateway keeps the existing owner, rejects the new loader, logs an ERROR naming both endpoints
and increments the `shiguchi.graphql.loader.conflicts` counter (tag `type`). It does not fail the running gateway.

### Custom loaders and relationships as beans

For data the gateway cannot reach itself (a hand-written Jackson or REST client, a database, an in-process
service) declare the loader and the relationship as Spring beans. A `BatchLoaderDefinition` is a third source for
the batching pipeline, next to the gRPC and REST loaders, behind the same de-duplication, `max-batch-size`
chunking, bounded concurrency, error handling and, for a by-id loader, cache. By-id loaders also serve Federation
`_entities`.

```java
@Bean BatchLoaderDefinition booksByAuthor(BookClient client) {
    // one call for every parent of the operation; each child map carries its parent's key
    return BatchLoaderDefinition.grouped("Book", "author_id",
            (authorIds, metadata) -> client.findByAuthors(authorIds));   // Mono<List<Map<String, Object>>>
}

@Bean BatchLoaderDefinition authors(AuthorClient client) {
    return BatchLoaderDefinition.byId("Author",
            (ids, metadata) -> client.findAll(ids));                    // Mono<Map<id, Map<String, Object>>>
}

@Bean RelationshipDefinition authorBooks() {
    return RelationshipDefinition.many("Author.id", "Book", "author_id").named("books");
}
```

The two types are compiled against, so the application declares `io.github.appiaworks:shiguchi-graphql`
itself: the GraphQL starter brings it in at runtime scope only.

The maps are the JSON form of the entity, with the JSON field names, which is what a Jackson client reads. A
grouped loader is named `Type#by:camelCase(groupBy)` and, like the gRPC one, stays out of `_entities` and the
caches; a parent with no children gets an empty list. `RelationshipDefinition.one` / `many` are the code form of a
YAML `relationships` entry and win over one on the same `Type.field`.

Limits:

- The GraphQL type must already exist, from a gRPC route or a REST service of the gateway. A bean supplies data and
  relationships, it does not derive a type from a Java class or a JSON schema.
- If a gRPC or REST loader also serves the type, the bean wins and the gateway logs a WARN once. To keep the
  service's loader, pin the type with `types.<Type>.endpoint` (see above): the pin names a service, so the bean is
  ignored.
- Two beans for the same loader name fail startup. A relationship naming an unknown type or field, or a target with no
  loader, is checked like a YAML one: startup fails, and after a reload it is logged at ERROR.

### See what the gateway resolved

With the `gateway-relationships` actuator endpoint exposed (`management.endpoints.web.exposure.include`),
`GET /actuator/gateway-relationships` lists, per parent type, each effective relationship with where it was
declared (`annotation`, `extension`, `yaml` or `bean`), whether a loader serves it and which kind (`grpc`,
`rest`, `bean`), plus the batch keys and the loaders. For REST_DOWNSTREAM documents it also lists
`suggestions`: `<type>Id` candidates nobody declared, labelled `suggested` and `wired: false`, each with the
`x-shiguchi-references` or YAML line that would wire it. See
[observability](../operations/observability.md#the-gateway-relationships-endpoint).
The web console shows the same resolved relationships, whichever source declared them, as `→ Book[]` badges on the
ID fields of its output schema.

![Output schema of ListBooks in the console: Book.authorId badged "→ Author" and Author.id badged "→ Book[]", each with a YAML source chip](../images/grpc-ui-relationships.png)


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
[configuration.md](../reference/configuration.md#per-object-authorization)); `AuthzRuleResolver` does the
lookup so each implementation does not re-derive it. With no such bean wired the rules are inert
and the route serves normally, so the same `.proto` works behind a gateway with or without a
policy engine.

A rule whose `id_field` is absent from the request is reported as *unresolved*, and the shipped
sample denies in that case: an annotation typo or a missing parameter must not degrade into
"allow". A rule missing any of the three fields is dropped at startup with a WARN rather than
failing the route.


> Reference: [GraphQL reference](../reference/graphql.md) lists the full mapping rules, keys and defaults.
