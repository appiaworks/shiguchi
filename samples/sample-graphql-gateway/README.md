# sample-graphql-gateway

REST + GraphQL from the same gRPC backend, no gRPC proxy and no console. GraphQL schema
generation reads `(shiguchi.api.graphql)` on each RPC and `(shiguchi.api.graphql_field)` on each message field
(`docs/graphql-annotations.md`) to decide which methods become queries/mutations/resolvers and
how types are shaped — REST routes still come from `google.api.http` as usual.

There is no backend bundled with this sample. Point `shiguchi.services[0]` at any gRPC
service with reflection enabled and `(shiguchi.api.graphql)` options on the methods you want exposed;
the examples below assume the `demo.library.Library` service from `docs/graphql-annotations.md`
and `docs/ui.md` (`GetBook` → query `book`, `ListBooks` → query `books`), so adjust field names
to your own schema.

## Run

```bash
./gradlew publishToMavenLocal          # once, from the repo root
cd samples
./gradlew :sample-graphql-gateway:bootRun
```

## Ports

| Port | Surface |
| --- | --- |
| `8080` | HTTP: REST routes, OpenAPI document, `/graphql`, GraphiQL |
| `9090` | Expected backend gRPC port (`shiguchi.services[0].port`) — not served by this sample |

## Try

```bash
# A query mapped from a method with (shiguchi.api.graphql).type = QUERY
curl -s localhost:8080/graphql -H 'content-type: application/json' \
  -d '{"query":"{ book(id: 1) { id title author } }"}'

# A list query with selection-projection: only requested fields are read off the response
curl -s localhost:8080/graphql -H 'content-type: application/json' \
  -d '{"query":"{ books(pageSize: 5) { title } }"}'

# A mutation mapped from a method with (shiguchi.api.graphql).type = MUTATION
curl -s localhost:8080/graphql -H 'content-type: application/json' \
  -d '{"query":"mutation { createBook(input: { title: \"Dune\" }) { id title } }"}'

# The same backend is still on REST — same routes as sample-rest-gateway
curl -s localhost:8080/v1/books/1

# The generated OpenAPI document (REST surface stays on)
curl -s localhost:8080/v3/api-docs | jq '.paths | keys'
```

Then open <http://localhost:8080/graphiql> to browse the generated schema — every
`(shiguchi.api.graphql)`-annotated method appears there as a query, mutation, subscription or
resolver field, with doc-strings from the option's `description`.

## Configuration

```yaml
shiguchi:
  services:
    - name: my-grpc-service
      host: localhost
      port: 9090
      mode: DYNAMIC     # discovers methods, HTTP bindings and shiguchi.api options via reflection

  openapi:
    enabled: true       # REST + OpenAPI stay on alongside GraphQL

  graphql:
    enabled: true       # activates schema generation from (shiguchi.api.graphql)/(shiguchi.api.graphql_field) + /graphql, /graphiql

  grpc:
    enabled: false       # no pass-through gRPC proxy

  ui:
    enabled: false        # no embedded console
```

## See also

- [docs/graphql-annotations.md](../../docs/graphql-annotations.md) — `(shiguchi.api.graphql)`,
  `(shiguchi.api.graphql_field)`, DataLoader batching (`loader_for`/`resolves_type`), and the YAML
  `field-overrides` alternative to touching protos.
- [docs/http-annotations.md](../../docs/http-annotations.md) — the REST surface this sample
  keeps enabled alongside GraphQL.
- [docs/configuration.md](../../docs/configuration.md) — full `shiguchi.*` reference.
