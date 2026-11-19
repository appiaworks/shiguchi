# sample-graphql-relationships

GraphQL **relationships between REST services**, declared three ways, with no proto and no proto
annotation at all. A list of parents costs one batched call for their children, however many parents
there are (no N+1).

The backend is an in-process reactor-netty server on `:8081` (`ShopBackend`). It serves two OpenAPI
documents and four list endpoints, and logs every request it gets so you can count the calls.

| Service | Declared with | Where |
| --- | --- | --- |
| `shop` (`Customer`, `Order`) | `x-shiguchi-*` extensions in the OpenAPI document | `src/main/resources/shop-openapi.json` |
| `catalog` (`Product`, `Review`) | `relationships[]`, `rest-batch-loaders[]` in the gateway's YAML (the document is plain) | `src/main/resources/application.yml` |
| `Shipment` (served by `ShipmentClient`) | a `BatchLoaderDefinition` and a `RelationshipDefinition` bean over a client that returns Jackson-style maps | `ShipmentRelationships.java` |

Types carry a prefix (`ShopOrder`, `CatalogProduct`) from `graphql-type-prefix`. The sample's
`build.gradle.kts` declares `shiguchi-graphql` itself because the bean path compiles against it; the
GraphQL starter brings it in at runtime scope only.

## Run

```bash
./gradlew publishToMavenLocal              # once, from the repo root
./gradlew -p samples sample-graphql-relationships:bootRun
```

## Try

```bash
GQL='curl -s localhost:8080/graphql -H content-type:application/json -d'

# Many-to-one from an extension: Order.customer. Backend sees GET /orders, then ONE GET /customers?ids=c1,c2,c3
$GQL '{"query":"{ listOrders { id total customer { id name } } }"}'

# One-to-many from an extension: Customer.orders. Backend sees GET /customers, then ONE GET /orders?customerIds=c1,c2,c3
$GQL '{"query":"{ listCustomers { id name orders { id total } } }"}'

# YAML, both directions: Product.reviews and Review.product
$GQL '{"query":"{ listProducts { name reviews { stars } } }"}'
$GQL '{"query":"{ listReviews { stars product { name } } }"}'

# A bean over a custom client: Order.shipments. One client call for all orders, none to the backend
$GQL '{"query":"{ listOrders { id shipments { carrier status } } }"}'
```

An order with no shipments answers `[]`, not `null`.

What the gateway resolved, and who declared it (`extension`, `yaml`, `bean`):

```bash
curl -s localhost:8080/actuator/gateway-relationships | jq '.types[] | {type, relationships: [.relationships[] | {field, source}]}'
```

The endpoint is exposed in `application.yml` (`management.endpoints.web.exposure.include`). It is read-only; see
[actuator endpoints](../../docs/guides/security.md#actuator-endpoints) before exposing it beyond a trusted network.

## Test

```bash
./gradlew -p samples sample-graphql-relationships:test
```

`SampleGraphqlRelationshipsTest` starts the backend on a free port and asserts the exact requests it
received for each path (for example `GET /orders`, then `GET /customers?ids=c1,c2,c3`), and the single
`ShipmentClient` call for the bean path.

## Which way to pick

See [Which way should I declare it?](../../docs/guides/graphql-annotations.md#which-way-should-i-declare-it).
