# sample-validation

Constraints declared in the proto, enforced by the gateway, so an invalid request is refused
before it reaches the backend.

The rules live next to the fields they constrain:

```protobuf
message CreateOrderRequest {
  string sku            = 1 [(buf.validate.field).string.pattern = "^[A-Z]{3}[0-9]{3}$"];
  int32  quantity       = 2 [(buf.validate.field).int32 = {gte: 1, lte: 100}];
  string customer_email = 3 [(buf.validate.field).string.email = true];
}
```

Written once, readable by everyone who has the descriptors — which is the gateway's whole
premise. Turn it on with one property:

```yaml
shiguchi:
  validation:
    gateway-enabled: true
    log-violations: true    # logs the field paths that failed, never the values
```

The backend in this sample deliberately validates **nothing**, so every rejection you see came
from the gateway and every request that reaches it is one the gateway accepted. Do not copy that
part: a real backend should still validate, because the gateway is not the only way in.

## Run it

```bash
./gradlew publishToMavenLocal          # from the repo root, once
cd samples && ../gradlew :sample-validation:bootRun
```

A request that satisfies every constraint reaches the backend:

```bash
curl -s localhost:8080/v1/orders -H 'content-type: application/json' \
  -d '{"sku":"ABC123","quantity":5,"customerEmail":"buyer@example.com"}' | jq
```

```json
{ "id": "ORD-1", "sku": "ABC123", "quantity": 5, "customerEmail": "buyer@example.com" }
```

One that does not is refused with **400**, and the call never leaves the gateway:

```bash
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/v1/orders \
  -H 'content-type: application/json' \
  -d '{"sku":"nope","quantity":5,"customerEmail":"buyer@example.com"}'
# 400
```

Watch the backend's log while you do it: `backend reached:` appears for the first request and not
the second.

## The cases worth trying

| Body | Result | Rule |
| --- | --- | --- |
| `{"sku":"ABC123","quantity":5,"customerEmail":"buyer@example.com"}` | 200 | — |
| `{"sku":"nope",...}` | 400 | `string.pattern` |
| `{"quantity":9999,...}` | 400 | `int32.lte` |
| `{"quantity":0,...}` | 400 | `int32.gte` |
| `{"customerEmail":"not-an-email",...}` | 400 | `string.email` |

The `quantity: 0` case is the interesting one. In proto3, `0` is the default for an `int32`, so a
field left out of the JSON entirely is indistinguishable from one explicitly set to zero — the
constraint is what turns "missing" into an error. Without it, an order for zero units would reach
the backend looking perfectly well-formed.

## Where to validate

`gateway-enabled` and `server-enabled` are separate switches because they answer different
questions:

| | What it protects | Cost |
| --- | --- | --- |
| `gateway-enabled` | callers coming through the gateway | one check at the edge; saves a round trip |
| `server-enabled` | **every** caller, including other services and the console | a check per RPC at the backend |

Enabling both is normal. The gateway's check is a fast rejection with a helpful message; the
backend's is the one that actually guarantees the invariant, because not everything arrives
through the gateway.

`log-violations` logs which field paths failed and never the values — a rejected payload is often
exactly the data you least want in a log.

→ [docs/validation.md](../../docs/validation.md) ·
[docs/configuration.md](../../docs/configuration.md) ·
[docs/error-handling.md](../../docs/error-handling.md) ·
[protovalidate](https://github.com/bufbuild/protovalidate)
