# sample-rest-downstream

One gateway in front of a **REST** service, not a gRPC one. Shows `mode: REST_DOWNSTREAM`:
the gateway fetches the backend's OpenAPI document, proxies its operations under a path
prefix, enforces per-operation scopes before proxying, and merges the document into the
consolidated `/v3/api-docs`.

The backend is an in-process reactor-netty server on `:8081` (`InventoryBackend`) serving four
endpoints and `inventory-openapi.json`. Replace it with any service that publishes an OpenAPI 3
document (JSON or YAML) and the configuration stays the same.

## Run

```bash
./gradlew publishToMavenLocal              # once, from the repo root
./gradlew -p samples sample-rest-downstream:bootRun
```

## Try

```bash
# Proxied: prefix stripped, backend sees GET /items/42
curl -s localhost:8080/inv/items/42
# {"sku":"42","name":"Widget","qty":7}

# Backend 404 passes through unchanged
curl -s localhost:8080/inv/items/999

# Hidden by exclude-methods: no route, 404 at the gateway, backend never called
curl -si localhost:8080/inv/items | head -1

# Scoped in YAML (operation-scopes): anonymous caller is rejected before the proxy
curl -si -X POST localhost:8080/inv/items -H 'content-type: application/json' -d '{"name":"x"}' | head -1
# HTTP/1.1 403 Forbidden

# Scoped in the document (x-gateway-auth-scopes on deleteItem): same outcome
curl -si -X DELETE localhost:8080/inv/items/43 | head -1

# The merged document: /inv/... paths, inventory_* operationIds, inventory.* components,
# no servers, no inherited security scheme
curl -s localhost:8080/v3/api-docs | jq '.paths | keys, .components.schemas | keys'
```

Then open <http://localhost:8080/swagger-ui.html>: the inventory operations appear under the
`inventory / Items` tag next to any gRPC-derived ones.

The same document also drives the GraphQL schema. `GET` operations are queries, the rest
mutations; path/query parameters and body properties form the request type (flat arguments for
small queries, an `input` object for mutations), and the call is made over HTTP to the backend:

```bash
curl -s localhost:8080/graphql -H 'content-type: application/json' \
  -d '{"query":"{ getItem(sku: \"42\") { sku name qty } }"}'
# {"data":{"getItem":{"sku":"42","name":"Widget","qty":"7"}}}

curl -s localhost:8080/graphql -H 'content-type: application/json' \
  -d '{"query":"mutation { createItem(input: { name: \"Bolt\", qty: \"3\" }) { sku name } }"}'
# needs scope inventory.write → {"errors":[{"message":"... Missing required scopes: inventory.write"}],"data":{"createItem":null}}
```

Open <http://localhost:8080/graphiql> to browse the generated `Item` type and the `getItem`,
`createItem` and `deleteItem` fields (`listItems` is excluded by `exclude-methods`).

## What to look at

- `application.yml`: the whole feature is the one `services[]` entry. `path-prefix`,
  `exclude-methods` and `operation-scopes` are the same knobs the gRPC modes use.
- `inventory-openapi.json`: `deleteItem` carries `x-gateway-auth-scopes`, the in-document way
  to declare scopes; `createItem`'s scope comes from YAML instead, which takes precedence.
- Both `Item` and `Error` are namespaced to `inventory.Item` / `inventory.Error` in the merged
  spec, so a second backend defining its own `Error` cannot collide.

Configuration reference: [docs/configuration.md](../../docs/configuration.md#rest_downstream-mode).
