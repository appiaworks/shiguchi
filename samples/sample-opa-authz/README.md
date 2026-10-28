# sample-opa-authz

Gateway authorization delegated to [Open Policy Agent](https://www.openpolicyagent.org/).

Companion to [`sample-openfga-authz`](../sample-openfga-authz). Both replace the same
`GatewayAuthorizer` bean, but they use the SPI in opposite ways — which is the point of having
both.

## Why two samples

|  | OpenFGA | OPA |
| --- | --- | --- |
| Question shape | `check(user, relation, object)` — fixed | arbitrary Rego over an arbitrary document |
| Calls per request | one per `authz` rule | one, carrying the whole request |
| Who decides what matters | the **gateway** (it resolves rules into tuples) | the **policy** |
| Reads `authz` rules | resolved via `AuthzRuleResolver` into `(relation, object)` pairs | **raw**, passed through structurally |
| Extra signals (time, IP, tenant, body) | needs contextual tuples | already in the input document |

That last row is the interesting one. `AuthzRuleResolver` produces Zanzibar-shaped
`(relation, object)` pairs, which is exactly right for OpenFGA and lossy for OPA — flattening
into it would discard the object type, the field the id came from, and the method name, all of
which a Rego policy may legitimately want. So this sample reads
`route.getAuthzRules()` directly.

**The SPI held up.** `AuthorizationRequest` carries the route, the caller, the arguments and the
body — enough for either engine, and it does not assume a tuple-shaped question. The shared
`authz` annotation describes the *requirement*; each engine decides how to ask.

## The input document

One POST per request to `/v1/data/<decision-path>`:

```json
{
  "input": {
    "subject": {
      "id": "alice",
      "authenticated": true,
      "authorities": ["SCOPE_orders:read"],
      "claims": { "groups": ["auditors"] }
    },
    "action": {
      "service": "orders.OrderService",
      "method": "GetOrder",
      "http_method": "GET",
      "http_path": "/v1/orders/{orderId}",
      "graphql_field": null
    },
    "resource": {
      "arguments": { "orderId": "42" },
      "authz": [
        { "object_type": "order", "relation": "viewer",
          "id_field": "order_id", "id": "42" }
      ]
    },
    "required_scopes": ["orders:read"]
  }
}
```

Each `resource.authz` entry carries the declared rule **and** the gateway's resolved `id`, so a
policy can trust that lookup or redo it against `resource.arguments`.

**`id` is `null` when the request did not supply that field.** The policy must treat that as a
failure to authorize, never as an absent constraint — `policy.rego` checks `rule.id != null`
explicitly rather than letting the lookup quietly fail.

## Running it

### 1. Start OPA with the policy and data

```bash
cd samples/sample-opa-authz
opa run --server policy.rego data.json
```

OPA listens on `:8181`. `data.json` seeds the grants:

```json
{
  "grants": {
    "order:42":     { "owner": ["alice"], "viewer": ["group:auditors"] },
    "order:99":     { "owner": ["dave"] },
    "account:acme": { "viewer": ["alice", "group:auditors"] }
  }
}
```

### 2. Annotate the method

```protobuf
rpc GetOrder(GetOrderRequest) returns (Order) {
  option (shiguchi.api.graphql) = { name: "order", type: QUERY };
  option (shiguchi.api.method) = {
    auth_scopes: ["orders:read"]
    authz: [{ object_type: "order", relation: "viewer", id_field: "order_id" }]
  };
}
```

### 3. Run the gateway

```bash
./gradlew publishToMavenLocal                 # from the repo root, once
./gradlew -p samples sample-opa-authz:bootRun
```

### 4. Try it

```bash
# alice owns order 42, and owner implies viewer -> 200
curl -H "Authorization: Bearer $ALICE_TOKEN" http://localhost:8080/v1/orders/42

# bob is in `auditors`, which is a viewer of order 42 -> 200
curl -H "Authorization: Bearer $BOB_TOKEN" http://localhost:8080/v1/orders/42

# carol has no grant on order 42 -> 403
curl -H "Authorization: Bearer $CAROL_TOKEN" http://localhost:8080/v1/orders/42

# nobody has a grant on order 99 except dave -> 403 for everyone else
curl -H "Authorization: Bearer $ALICE_TOKEN" http://localhost:8080/v1/orders/99
```

Test the policy without the gateway:

```bash
curl -s localhost:8181/v1/data/gateway/authz/allow -d @- <<'JSON' | jq
{"input":{
  "subject":{"id":"alice","authenticated":true,"authorities":["SCOPE_orders:read"],"claims":{}},
  "action":{"service":"orders.OrderService","method":"GetOrder"},
  "resource":{"arguments":{"orderId":"42"},
    "authz":[{"object_type":"order","relation":"viewer","id_field":"order_id","id":"42"}]},
  "required_scopes":["orders:read"]}}
JSON
```

## Testing the policy

`policy_test.rego` pins the behaviours documented above. Run it with the `opa` CLI:

```bash
cd samples/sample-opa-authz
opa test policy.rego policy_test.rego data.json -v
```

Two of the ten are there specifically to stay honest about the dangerous cases:

- `test_viewer_grant_does_not_satisfy_owner` — `viewer: ... or owner` implies one direction only,
  so a group viewer of `order:42` must not pass the `owner` requirement on the DELETE route.
- `test_null_id_denies_even_when_a_null_key_exists` — an unresolvable rule arrives as `id: null`.
  The obvious test for this passes even with the `rule.id != null` guard deleted, because
  `"order:null"` simply is not in the data; it therefore passes for the wrong reason. This one
  injects a grant literally keyed `"order:null"`, so the guard is the only thing denying, and
  removing it turns the test red.

The policy is also `opa check --strict` clean.

## Failure modes

An **undefined rule is a deny.** OPA returns `{}` with no `result` key when the queried rule does
not exist — a typo in `decision-path` or in the Rego package name. `OpaResponse.result` is a boxed
`Boolean` and only `TRUE` allows, so a misconfigured path fails closed instead of opening the
gateway. The policy also sets `default allow := false` for the same reason at the other end.

Everything else is bounded and explicit:

- `timeout: 300ms` per decision — the policy engine cannot hang the gateway.
- `fail-open: false` (default) — an OPA outage **denies**. Failing open converts an outage into a
  silent authorization bypass; enable it only where availability genuinely outranks the
  access-control guarantee.
- The scope gate runs **before** OPA, so a caller failing the coarse check is rejected without a
  network round trip.

## Not included

**No decision cache.** The OpenFGA sample caches because its question is narrow and its key is
well-defined. An OPA input document includes the full argument map, so a correct cache key would
have to hash the whole document — and any policy reading time, or anything else not in the
document, would be silently wrong when cached. If you add one, key on the serialized input and
make sure your policies are pure functions of it.

**No `batchCheck` equivalent.** GraphQL fans one query out to many routes and therefore many
policy calls. OPA has no batch endpoint; the usual answer is to run OPA as a sidecar so each call
is a loopback request rather than a network hop.

## Scope

Gateway checks are coarse by construction — the gateway sees the request, not the rows the backend
returns. A route like `GET /v1/orders?customer=acme` cannot be authorized per returned row here.
Defence in depth, not a replacement for authorization inside your services.
