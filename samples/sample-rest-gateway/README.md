# sample-rest-gateway

The minimal REST/JSON gateway: one gRPC backend, discovered by reflection, exposed as HTTP
routes derived from its `google.api.http` annotations, plus a generated OpenAPI document and
Swagger UI. No GraphQL, no gRPC proxy, no console — just `shiguchi-rest` +
`shiguchi-openapi`.

There is no backend bundled with this sample. Point `shiguchi.services[0]` at any gRPC
service that has server reflection enabled; the examples below assume it looks like the
`demo.library.Library` service used throughout `docs/` (`GetBook`, `ListBooks`, `CreateBook`,
`UpdateBook`, `DeleteBook`), so adjust paths to your own proto's `google.api.http` options.

## Run

```bash
./gradlew publishToMavenLocal          # once, from the repo root
cd samples
./gradlew :sample-rest-gateway:bootRun
```

## Ports

| Port | Surface |
| --- | --- |
| `8080` | HTTP: REST routes, OpenAPI document, Swagger UI |
| `9090` | Expected backend gRPC port (`shiguchi.services[0].port`) — not served by this sample |

## Try

```bash
# GET with a path variable → GetBookRequest.id
curl -s localhost:8080/v1/books/1

# GET with query parameters → ListBooksRequest fields
curl -s 'localhost:8080/v1/books?pageSize=10'

# POST with a JSON body → CreateBookRequest.book
curl -s -X POST localhost:8080/v1/books -H 'content-type: application/json' \
  -d '{"title":"Dune","author":"Herbert"}'

# PATCH with a dotted path variable → UpdateBookRequest.book.id
curl -s -X PATCH localhost:8080/v1/books/1 -H 'content-type: application/json' \
  -d '{"book":{"title":"Dune Messiah"}}'

# DELETE
curl -si -X DELETE localhost:8080/v1/books/1 | head -1

# The generated OpenAPI document
curl -s localhost:8080/v3/api-docs | jq '.paths | keys'
```

Then open <http://localhost:8080/swagger-ui.html> to browse and call every discovered route
interactively.

## Configuration

```yaml
shiguchi:
  services:
    - name: my-grpc-service
      host: localhost
      port: 9090
      mode: DYNAMIC     # discovers methods and google.api.http bindings via gRPC reflection

  openapi:
    enabled: true       # /v3/api-docs + /swagger-ui.html

  graphql:
    enabled: false       # this sample is REST-only

  grpc:
    enabled: false       # no pass-through gRPC proxy

  ui:
    enabled: false        # no embedded console
```

`mode: DYNAMIC` is what lets this sample work against any backend without recompiling: swap
`host`/`port` (or use `discovery-name`/`target`, see service-discovery.md) and the routes,
request shapes and OpenAPI schema all follow the new service's descriptors.

## See also

- [docs/http-annotations.md](../../docs/http-annotations.md) — how `google.api.http` becomes a
  route, verb, path variables, query parameters and body mapping.
- [docs/configuration.md](../../docs/configuration.md) — full `shiguchi.*` reference.
- [docs/service-discovery.md](../../docs/service-discovery.md) — pointing `services[]` at more
  than a fixed `host`/`port`.
