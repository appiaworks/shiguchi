# GraphQL cookbook

One short example per GraphQL feature, each with a `curl` command and the same operation to paste into
GraphiQL (`/graphiql`). They use the bookstore protos from the [tutorial](../getting-started/tutorial.md).
The rules behind them are in the [GraphQL reference](../reference/graphql.md) and the
[annotation guide](graphql-annotations.md).

All `curl` examples assume the gateway on `localhost:8080` and this shell variable:

```bash
GQL='curl -s localhost:8080/graphql -H content-type:application/json -d'
```

## From proto to response

The proto says what the field is called and whether it is a query or a mutation:

```protobuf
rpc ListBooks(ListBooksRequest) returns (ListBooksResponse) {
  option (shiguchi.graphql) = { name: "books", type: QUERY };
}
rpc CreateBook(CreateBookRequest) returns (Book) {
  option (shiguchi.graphql) = { name: "createBook", type: MUTATION };
}
```

The schema is generated from the descriptors; `ListBooksResponse` carries `page_info`, so `books` is a
Relay connection. Query it:

```bash
$GQL '{"query":"{ books(first: 2) { edges { node { id title } } pageInfo { hasNextPage endCursor } } }"}'
```

```graphql
{
  books(first: 2) {
    edges { node { id title } }
    pageInfo { hasNextPage endCursor }
  }
}
```

A failing call keeps the gRPC status in `extensions`. With the default `NATIVE` error format a validation
failure from the backend looks like this (field paths are GraphQL argument paths):

```json
{"errors": [{"message": "name is required", "path": ["createBook"],
  "extensions": {"code": "BAD_USER_INPUT", "grpcCode": "INVALID_ARGUMENT", "errorType": "BAD_REQUEST",
    "fieldErrors": [{"field": "input.name", "message": "must not be empty", "rule": "required"}]}}]}
```

The full list of keys and the status mapping: [error extensions](../reference/graphql.md#3-error-extensions).

## Variables and mutations

A mutation takes an `input` object built from the request message:

```bash
$GQL '{"query":"mutation($input: CreateBookRequestInput!) { createBook(input: $input) { id title } }","variables":{"input":{"title":"Dune"}}}'
```

```graphql
mutation ($input: CreateBookRequestInput!) {
  createBook(input: $input) { id title }
}
```

Variables (`{"input": {"title": "Dune"}}`) go in the GraphiQL variables pane.

## Paging and sorting

Requests with `page_request` get `first` and `after`; requests with `sort_fields` get `sort`:

```graphql
{
  books(first: 5, after: "<endCursor from the previous page>",
        sort: [{ field: "title", sortOrder: SORT_ORDER_DESC }]) {
    edges { cursor node { id title } }
    pageInfo { hasNextPage endCursor }
  }
}
```

See [Paging](projections/paging.md) and [Sorting](projections/sorting.md).

## Field selection

Only the fields in the selection set are read from the backend when the request has a `read_mask`: ask for
`{ id title }` and the mask is `id,title`. See [Field projection](projections/field-projection.md).

## Subscriptions

A server-streaming method annotated `type: SUBSCRIPTION` is served over WebSocket
(`graphql-transport-ws`) once `spring.graphql.websocket.path` is set. The field name is whatever your proto
says; `bookEvents` is illustrative:

```graphql
subscription { bookEvents { id title } }
```

## `@live` queries

With `shiguchi.graphql.live.enabled: true`, a query re-runs when a mutation through this gateway succeeds on
a service behind one of its fields:

```graphql
query Books @live(throttleMs: 2000) { books(first: 5) { edges { node { id title } } } }
```

Send it over `graphql-transport-ws`, or as a POST with `Accept: text/event-stream`. Each new full result
arrives as a `next` frame with `extensions.live.seq`. Details: [`@live`](graphql-annotations.md#live).

## `@defer`

With `shiguchi.graphql.incremental.enabled: true`, a client that accepts `multipart/mixed` gets the cheap
fields first:

```bash
curl -s localhost:8080/graphql -H content-type:application/json \
  -H 'Accept: multipart/mixed;deferSpec=20220824' \
  -d '{"query":"{ book(id: \"7\") { id ... @defer(label: \"more\") { title } } }"}'
```

A client that does not accept `multipart/mixed` gets the complete JSON result. Details:
[`@defer`](graphql-annotations.md#defer).

## Authentication

Pass the same bearer token the REST surface takes; it is forwarded to the backend as gRPC metadata:

```bash
curl -s localhost:8080/graphql -H content-type:application/json -H "Authorization: Bearer $TOKEN" \
  -d '{"query":"{ books(first: 1) { edges { node { id } } } }"}'
```

In GraphiQL, add `{"Authorization": "Bearer <token>"}` to the headers pane. See [Security](security.md).
