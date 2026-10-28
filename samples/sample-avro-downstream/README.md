# sample-avro-downstream

One gateway in front of a REST service that speaks **Avro**. Everything from
[sample-rest-downstream](../sample-rest-downstream) applies; the difference is one YAML line,
`upstream-content.preferred: avro/binary`, and a document that says which operations can do
Avro and what the records look like.

The backend is an in-process reactor-netty server on `:8081` (`AvroInventoryBackend`) using
plain Apache Avro: it answers `avro/binary` when asked, JSON otherwise, decodes Avro request
bodies, and serves `inventory-openapi.json` with `x-avro-schema` on the `Item` and `Category`
components. `getLegacyItem` ignores `Accept` and always answers JSON; `listItems` only declares
JSON.

## Run

```bash
./gradlew publishToMavenLocal              # once, from the repo root
./gradlew -p samples sample-avro-downstream:bootRun
```

Startup log: `avro/binary bound 2 schema(s) of REST_DOWNSTREAM service 'inventory': [Item, Category]`.

## Try

```bash
# GraphQL: the gateway asks the backend for Avro and decodes it; the result is ordinary JSON.
curl -s localhost:8080/graphql -H 'content-type: application/json' \
  -d '{"query":"{ getItem(sku: \"42\") { sku name qty price status updatedAt category { name } } }"}'
# {"data":{"getItem":{"sku":"42","name":"Widget","qty":"7","price":"12.50","status":"ITEM_STATUS_ACTIVE",
#   "updatedAt":"2024-01-02T03:04:05Z","category":{"name":"tools"}}}}
# (status is the GraphQL enum value; the backend's "ACTIVE" is translated at the HTTP boundary)
# backend log:  GET /items/42  Accept=avro/binary, application/json;q=0.5
# gateway log:  [HTTP dispatch→] GET http://localhost:8081/items/42 body=false accept=avro/binary, ...

# JSON-only operation: the gateway does not ask for Avro
curl -s localhost:8080/graphql -H 'content-type: application/json' \
  -d '{"query":"{ listItems { sku qty } }"}'
# backend log:  GET /items  Accept=application/json

# Backend that ignores Accept: decoded by the returned Content-Type
curl -s localhost:8080/graphql -H 'content-type: application/json' \
  -d '{"query":"{ getLegacyItem(sku: \"43\") { sku status } }"}'

# Mutation body encoded as Avro (scope inventory.write is required; anonymous → error)
curl -s localhost:8080/graphql -H 'content-type: application/json' \
  -d '{"query":"mutation { createItem(input: { sku: \"x\", name: \"Bolt\", qty: \"3\", price: \"0.10\", updatedAt: \"2024-05-06T07:08:09Z\" }) { sku name } }"}'

# REST passthrough is untouched: your own Accept header reaches the backend
curl -s localhost:8080/inv/items/42                                   # JSON
curl -s localhost:8080/inv/items/42 -H 'accept: avro/binary' | xxd | head -3   # raw Avro datum
```

## What to look at

- `application.yml`: `upstream-content.preferred` and the `avro` block. Remove the first and
  the whole sample runs over JSON with no other change.
- `inventory-openapi.json`: `avro/binary` listed under `content` on `getItem`, `createItem`
  and `getLegacyItem` but not `listItems`; `x-avro-schema` on `Item` (decimal, enum,
  timestamp-millis, nullable nested record) and on `Category`, which `Item` references by name.
- `AvroInventoryBackend`: the Avro side is ~40 lines of plain `GenericDatumWriter` /
  `GenericDatumReader`; the JSON side is hand-rolled from the same record so both encodings
  carry the same values and the GraphQL result is identical either way.

How it works: [docs/avro.md](../../docs/avro.md).
