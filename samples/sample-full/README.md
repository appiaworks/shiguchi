# sample-full

Every surface turned on at once: REST + OpenAPI/Swagger, GraphQL, the pass-through gRPC proxy,
the embedded gRPC console, and Avro for REST callers (`shiguchi-avro-spring-boot3-starter`). This is
also the source of the published OCI image (`Dockerfile` here packages the boot jar CI builds
from this module) — the best single reference for how the configuration surfaces compose in one
`application.yml`.

There is no backend bundled with this sample. Point `shiguchi.services[0]` at any gRPC
service with reflection enabled; the examples below assume the `demo.library.Library` service
used throughout `docs/` (`GetBook`, `ListBooks`, `CreateBook`, ...), so adjust paths/fields to
your own proto.

## Run

```bash
./gradlew publishToMavenLocal          # once, from the repo root
cd samples
./gradlew :sample-full:bootRun
```

## Ports

| Port | Surface |
| --- | --- |
| `8080` | HTTP: REST routes, OpenAPI document, Swagger UI, `/graphql`, GraphiQL, `/grpc-ui`, `/avro/schemas/*` |
| `9191` | The consolidated gRPC proxy (`shiguchi.grpc.port`) |
| `9090` | Expected backend gRPC port (`shiguchi.services[0].port`) — not served by this sample |

## Try

```bash
# REST, transcoded from google.api.http
curl -s localhost:8080/v1/books/1

# Same route, but ask for Avro instead of JSON (shiguchi-avro starter is on the classpath)
curl -s localhost:8080/v1/books/1 -H 'accept: avro/binary' -o book.avro -D -

# The Avro schema for the response message, addressed by proto full name
curl -s localhost:8080/avro/schemas/demo.library.Book

# The same method, over GraphQL
curl -s localhost:8080/graphql -H 'content-type: application/json' \
  -d '{"query":"{ book(id: 1) { id title } }"}'

# The merged OpenAPI document
curl -s localhost:8080/v3/api-docs | jq '.paths | keys'

# The same backend through the gRPC proxy, unary call
grpcurl -plaintext -d '{"id": 1}' localhost:9191 demo.library.Library/GetBook
```

Then open <http://localhost:8080/swagger-ui.html>, <http://localhost:8080/graphiql> and
<http://localhost:8080/grpc-ui> for the interactive surfaces — the console's header links to
the other two.

## Configuration

```yaml
shiguchi:
  services:
    - name: my-grpc-service
      host: localhost
      port: 9090
      mode: DYNAMIC          # DYNAMIC | STATIC | PB_FILE

  openapi:
    enabled: true            # /v3/api-docs + /swagger-ui.html

  graphql:
    enabled: true            # /graphql + /graphiql, schema from (shiguchi.api.graphql)/(shiguchi.api.graphql_field)

  avro:
    enabled: true            # Avro in/out on the REST surface (accept/content-type: avro/binary)
    framing: SINGLE_OBJECT   # C3 01 + schema fingerprint framing for request/response bodies

  grpc:
    enabled: true            # activates the consolidated gRPC proxy server
    port: 9191
    rate-limit:
      enabled: false         # per-method token-bucket limiting on the proxy

  security:
    mode: PERMIT_ALL         # PERMIT_ALL (default, open transport) | AUTHENTICATED (JWT required)

  ui:
    enabled: false           # console off by default even here — see the warning below
    path: /grpc-ui
    require-auth: false

  refresh:
    enabled: false           # periodic re-discovery, off by default
    interval-seconds: 300
```

> [!WARNING]
> `ui.enabled` is `false` by default even in this "every surface on" sample, because the console
> can invoke any wired method. Flip it to `true` locally to try it, but keep `require-auth: true`
> (with an OAuth2 JWT resource server configured) or a trusted network anywhere else. See
> `../../SECURITY.md`.

## See also

- [docs/configuration.md](../../docs/configuration.md) — the full `shiguchi.*` reference this
  sample's YAML draws from.
- [docs/http-annotations.md](../../docs/http-annotations.md) and
  [docs/graphql-annotations.md](../../docs/graphql-annotations.md) — how one proto method ends up
  on REST and GraphQL at the same time.
- [docs/avro.md](../../docs/avro.md) — Avro on the REST surface, schema endpoint, framing modes.
- [docs/ui.md](../../docs/ui.md) — the embedded console this sample can enable.
