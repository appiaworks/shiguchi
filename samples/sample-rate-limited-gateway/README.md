# sample-rate-limited-gateway

A REST gateway with inbound rate limiting: a token bucket per caller, in memory by default or
shared through Redis behind a load balancer. The gRPC backend it fronts (a three-product
catalog) runs in-process, so there is nothing else to start.

| Piece | Where | What it shows |
| --- | --- | --- |
| Limits | `application.yml` → `shiguchi.rate-limit` | 2 requests/s, bursts of 5, keyed by principal or client IP, rate-limit headers on every limited response |
| `MEMORY` backend | default profile | buckets in a Caffeine cache — one gateway instance |
| `REDIS` backend | `application-redis.yml` | the same buckets in Redis, so N gateway instances count a caller once; `fail-open` policy |
| Metrics | `/actuator/prometheus` | `gateway_ratelimit_requests_total{outcome="allowed"|"rejected"}` |

The limits are deliberately tiny so a shell loop shows a 429 within a second. A real gateway
wants something like `replenish-rate: 100`, `burst-capacity: 200`.

## Run it — in-memory

```bash
./gradlew publishToMavenLocal          # from the repo root, once
cd samples && ../gradlew :sample-rate-limited-gateway:bootRun
```

Then send more than the burst allows:

```bash
for i in $(seq 1 8); do
  curl -s -o /dev/null -w "%{http_code}  remaining=%header{x-ratelimit-remaining}  retry-after=%header{retry-after}\n" \
    localhost:8080/v1/products/A100
done
```

```
200  remaining=4  retry-after=
200  remaining=3  retry-after=
200  remaining=2  retry-after=
200  remaining=1  retry-after=
200  remaining=0  retry-after=
200  remaining=0  retry-after=      <- the bucket refilled mid-loop: 2 tokens/s
429  remaining=0  retry-after=1
429  remaining=0  retry-after=1
```

Where exactly the 429s start depends on how fast the loop runs against the 2 tokens/s refill,
so expect the boundary to move by one either way.

The refused request never reaches the backend. Its body is the gateway's usual error shape
with the gRPC code `RESOURCE_EXHAUSTED`:

```bash
curl -i localhost:8080/v1/products/A100
```

```
HTTP/1.1 429 Too Many Requests
Retry-After: 1
X-RateLimit-Limit: 5
X-RateLimit-Remaining: 0
X-RateLimit-Replenish-Rate: 2
X-RateLimit-Burst-Capacity: 5
Content-Type: application/json

{"code":"RESOURCE_EXHAUSTED","message":"Rate limit exceeded: 2 requests per second, bursts of 5; retry in 1s"}
```

Wait a second and two more requests go through (the bucket refills at `replenish-rate`).

Callers are told apart by the authenticated principal when there is one, else by the client
IP — the first hop of `X-Forwarded-For` when a proxy in front of the gateway sets it, the
socket peer otherwise. Pretend to be someone else:

```bash
curl -s -o /dev/null -w "%{http_code}\n" -H 'X-Forwarded-For: 203.0.113.9' localhost:8080/v1/products/A100
```

That is a fresh bucket, so it is 200 even while your own is empty. Only trust the header when
the gateway sits behind a proxy that overwrites it; set `client-ip-header: ""` to ignore it
and key on the socket peer alone. Actuator is never limited: `/actuator/health` answers 200
in the same loop.

## Run it — Redis

```bash
docker run --rm -p 6379:6379 redis:7
cd samples && ../gradlew :sample-rate-limited-gateway:bootRun --args='--spring.profiles.active=redis'
```

Same loop, same result — but now start a second gateway on another port and alternate between
them:

```bash
cd samples && ../gradlew :sample-rate-limited-gateway:bootRun \
  --args='--spring.profiles.active=redis --server.port=8081 --sample.backend-port=9091 --shiguchi.services[0].port=9091'

for i in $(seq 1 8); do
  curl -s -o /dev/null -w "%{http_code} via 8080\n" localhost:8080/v1/products/A100
  curl -s -o /dev/null -w "%{http_code} via 8081\n" localhost:8081/v1/products/A100
done
```

Both instances count against the one bucket in Redis: the sixth request in total is refused,
whichever gateway takes it. With the `MEMORY` backend each instance would have let five through.

The bucket lives under `sample-gateway:rate-limit:ip:127.0.0.1.tokens` / `.timestamp`
(`redis-cli keys 'sample-gateway:*'`), expiring on their own once a caller goes quiet. Stop
Redis while the loop runs: with `fail-open: true` (the default) every request passes with a
warning in the log; set it to `false` and everything is 429 until Redis is back.

## What to look at

- **`application.yml`** — the whole feature. Every `shiguchi.rate-limit.*` key is
  documented in [docs/rate-limiting.md](../../docs/rate-limiting.md).
- **`application-redis.yml`** — the delta for shared buckets: `backend: REDIS`, the key prefix
  and `fail-open`, plus the standard `spring.data.redis.*` connection settings.
- **`CatalogServiceServer`** — the backend, served over real gRPC with reflection so the gateway
  finds it in `DYNAMIC` mode. It is unaware of the limiter; a refused request never reaches it.

## Beyond the sample

- **Key on something else** (an API key header, a tenant claim): define a
  `RateLimitKeyResolver` bean; the default backs off.
- **Different limits per route or per plan**: define a `RateLimiter` bean that decides from the
  key, or run several gateways with different `paths` patterns.
- **Limit only some paths**: `paths: ["/v1/orders/**"]` and `exclude-paths`. Without `paths`,
  every transcoded REST route, every `REST_DOWNSTREAM` route and the GraphQL endpoint are
  limited; actuator never is.
- **The pass-through gRPC proxy** has its own per-method limiter, `shiguchi.grpc.rate-limit`.
