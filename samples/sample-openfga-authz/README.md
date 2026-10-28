# sample-openfga-authz

Per-object authorization at the gateway, backed by [OpenFGA](https://openfga.dev).

## What problem this solves

The gateway ships with a scope check driven by the `auth_scopes` field of the
`(shiguchi.api.method)` proto option. It answers:

> may this caller call `GetOrder`?

It cannot answer:

> may this caller read **order 42**?

A JWT scope is a property of the caller alone. Object-level access is a property of the
caller **and** the object, so it cannot be encoded in a token claim without minting a claim
per object. That is the gap OpenFGA fills, and the gap the `GatewayAuthorizer` SPI exists to
let you close.

## How it works

`OpenFgaGatewayAuthorizer` replaces the gateway's default `GatewayAuthorizer` bean. It:

1. Runs the built-in `ScopeGatewayAuthorizer` first — in-process, and it rejects anonymous
   callers before spending a network round trip on them.
2. Looks the route up in `sample.openfga.routes` to find the object type, the required
   relation, and which request argument carries the object id.
3. Reads the object id out of `AuthorizationRequest.arguments()` — the gateway populates that
   from REST path variables and query parameters, or from GraphQL field arguments.
4. Calls `check(user, relation, object)` on OpenFGA. Anything other than allowed becomes an
   `AccessDeniedException`, which the gateway renders as HTTP 403.

### The route → object mapping

Something has to state which request field carries the object id. The **preferred** place is the
proto annotation, so the policy travels with the service definition:

```protobuf
rpc GetOrder(GetOrderRequest) returns (Order) {
  option (shiguchi.api.graphql) = { name: "order", type: QUERY };
  option (shiguchi.api.method) = {
    auth_scopes: ["orders:read"]   // may you call this method
    authz: [{                      // may you act on THIS order
      object_type: "order"
      relation: "viewer"
      id_field: "order_id"
    }]
  };
}
```

The sample reads those rules via `AuthzRuleResolver` and needs no per-route config at all.

For backends whose protos you cannot change, the same mapping can live in `application.yml` as a
fallback — annotation rules win when both are present:

```yaml
sample:
  openfga:
    routes:
      "[GET /v1/orders/{orderId}]":
        object-type: order
        relation: viewer
        id-argument: orderId      # a key in AuthorizationRequest.arguments()

      "[DELETE /v1/orders/{orderId}]":
        object-type: order
        relation: owner           # deleting demands more than reading
        id-argument: orderId

      order:                      # GraphQL routes key on the field name
        object-type: order
        relation: viewer
        id-argument: id
```

Routes absent from that map get no per-object check — only the declared scope check. A route
that *is* listed but whose id argument is missing at request time is **denied**, so a
misconfigured mapping fails closed rather than silently degrading to "allow everything".

The `"[...]"` quoting around the REST keys is required: the key contains `/`, `{}` and a
space, and without the brackets Spring's relaxed binding splits it apart (`routes.get`) and
fails to bind. For the same reason these keys bind cleanly from YAML but not from flat
`-D`/`@TestPropertySource` properties.

A production deployment would more likely drive this from the
`(shiguchi.api.method)` option so the policy travels with the service definition. The
trade-off is identical either way: something has to state which request input carries the
object id.

## Running it

### 1. Start OpenFGA

```bash
docker run -p 8080:8080 -p 8081:8081 openfga/openfga run
```

Note this sample's gateway also defaults to `8080`. Either run OpenFGA on another port and
set `sample.openfga.api-url`, or move the gateway with `server.port`.

### 2. Create the store and model

```bash
fga store create --name gateway-demo
```

The model, in OpenFGA's DSL:

```
model
  schema 1.1

type user

type group
  relations
    define member: [user]

type order
  relations
    define owner: [user]
    define viewer: [user, group#member] or owner

type account
  relations
    define viewer: [user, group#member]
```

`viewer: ... or owner` means an owner automatically reads — so the `DELETE` route requiring
`owner` is strictly stronger than the `GET` route requiring `viewer`.

```bash
fga model write --store-id "$FGA_STORE_ID" --file model.fga
```

Put the returned store id and **authorization model id** into `application.yml`. Pinning the
model id matters: without it OpenFGA evaluates against whatever model is latest, so
publishing a new model silently changes the answers the gateway gives.

### 3. Seed tuples

```bash
# alice owns order 42; bob is in the auditors group; auditors can view order 42
fga tuple write --store-id "$FGA_STORE_ID" user:alice   owner  order:42
fga tuple write --store-id "$FGA_STORE_ID" group:auditors#member viewer order:42
```

Note that `bob`'s membership in `auditors` is deliberately **not** written. It arrives per
request as a contextual tuple, from the JWT `groups` claim.

### 4. Run the gateway

```bash
./gradlew publishToMavenLocal                      # from the repo root, once
./gradlew -p samples sample-openfga-authz:bootRun
```

### 5. Try it

```bash
# alice owns order 42 -> 200
curl -H "Authorization: Bearer $ALICE_TOKEN" http://localhost:8080/v1/orders/42

# carol has no relation to order 42 -> 403
curl -H "Authorization: Bearer $CAROL_TOKEN" http://localhost:8080/v1/orders/42

# bob reads order 42 -> 200, via `groups: ["auditors"]` in his token, sent as a contextual
# tuple. No membership was ever written to the OpenFGA store.
curl -H "Authorization: Bearer $BOB_TOKEN" http://localhost:8080/v1/orders/42

# bob tries to delete it -> 403: that route requires `owner`, and he is only a viewer.
curl -X DELETE -H "Authorization: Bearer $BOB_TOKEN" http://localhost:8080/v1/orders/42
```

## Contextual tuples

JWT `groups` claims are sent as contextual tuples rather than synchronised into OpenFGA. This
is the [documented pattern](https://openfga.dev/docs/interacting/contextual-tuples) for data
the caller already carries: it avoids a sync pipeline between your IdP and the FGA store, at
the cost of trusting the token for that portion of the graph. OpenFGA caps contextual tuples
at 100 per request, which the sample enforces.

## Caching

Off by default, and enabling it is a real trade:

```yaml
sample:
  openfga:
    cache:
      enabled: true
      ttl: 30s           # a revoked permission still passes for up to this long
      negative-ttl: 5s   # denies expire faster
      max-size: 10000
```

`ttl` is not a tuning knob, it is your **revocation lag**. Set it to what you can defend to
whoever owns the access-control requirement.

Two details in the implementation that matter more than they look:

- **Denies are cached briefly, allows for longer.** A stale allow is a security failure; a
  stale deny only inconveniences someone who was just granted access.
- **The cache key includes a fingerprint of the contextual tuples**, not just
  `(user, relation, object)`. Two callers with the same subject but different group claims
  must never share an entry — collapsing them lets one inherit the other's access. This is
  the easiest way to turn a decision cache into a privilege-escalation bug.

There is no write-through invalidation, because OpenFGA has no cheap change feed to subscribe
to. TTL is the pragmatic answer; if the staleness window is unacceptable, run without the
cache.

## Latency and failure

The check is on the request hot path and adds a network round trip. Direct relationships are
typically sub-millisecond, but deep or complex models are
[materially slower at p99](https://auth0.com/blog/self-tuning-strategy-planner-openfga/), so:

- Every check is bounded by `timeout` (300ms here).
- `fail-open: false` (the default) means an OpenFGA outage **denies**. Failing open converts a
  policy-engine outage into a silent authorization bypass — enable it only where availability
  genuinely outranks the access-control guarantee, and knowingly.
- Nothing blocks: the SDK is `CompletableFuture`-based and is adapted with `Mono.fromFuture`,
  so no Netty event-loop thread is ever parked on the policy engine.

The official [`openfga/spring-boot-starter`](https://github.com/openfga/spring-boot-starter)
is deliberately **not** used — it is servlet-only (`@PreAuthorize` / `@fga`), and this gateway
is reactive WebFlux end to end. The raw SDK is the right dependency here.

## Scope

A gateway check is coarse by construction: it sees the request, not the data the backend is
about to return. A route like `GET /v1/orders?customer=acme` cannot be authorized per returned
row at this layer. Treat this as defence in depth and keep authorization inside your services
— it is not a substitute for it.

For GraphQL specifically, one query fans out to many fields and therefore many routes. Use the
SDK's `batchCheck` (OpenFGA server 1.8.0+) rather than N sequential checks; this sample keeps
to single `check` calls for clarity.
