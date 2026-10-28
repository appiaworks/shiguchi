# Rate limiting

Two limiters, for two different surfaces:

| Surface | Property prefix | Scope | Backend |
| --- | --- | --- | --- |
| REST and GraphQL (inbound HTTP) | `shiguchi.rate-limit` | per caller: principal or client IP | in memory or Redis |
| Pass-through gRPC proxy | `shiguchi.grpc.rate-limit` | per method, all callers together | in memory |

This page is about the first. The proxy limiter is a single `requests-per-second` bucket per
method and is described with the proxy in [configuration.md](configuration.md).

## What it does

Every limited request costs one token from the caller's bucket. A bucket holds
`burst-capacity` tokens and refills at `replenish-rate` tokens per second, so a caller can
send a burst of `burst-capacity` requests at once and then `replenish-rate` per second
sustained. A request that finds the bucket empty is refused with HTTP 429 and the gateway's
usual error body (`RESOURCE_EXHAUSTED`) through the [error renderer](error-handling.md),
`Retry-After` in whole seconds, and it never reaches a backend. Every limited response,
allowed or not, carries:

| Header | Value |
| --- | --- |
| `X-RateLimit-Limit` | `burst-capacity` |
| `X-RateLimit-Remaining` | tokens left after this request |
| `X-RateLimit-Replenish-Rate` | `replenish-rate` |
| `X-RateLimit-Burst-Capacity` | `burst-capacity` |

Set `include-headers: false` to send only `Retry-After` on refusals.

```yaml
shiguchi:
  rate-limit:
    enabled: true
    replenish-rate: 100      # tokens per second
    burst-capacity: 200      # bucket size
    key: PRINCIPAL_OR_IP
```

Outcomes are counted in `gateway.ratelimit.requests{outcome=allowed|rejected}` and each
refusal is logged at DEBUG on `dev.appiaworks.shiguchi.ratelimit` with the caller's key.

## Who is limited: the key

| `key` | Bucket per |
| --- | --- |
| `PRINCIPAL_OR_IP` (default) | the authenticated principal's name (`user:<name>`) when there is one, else the client IP (`ip:<address>`) |
| `PRINCIPAL` | the principal only; anonymous requests are not limited at all |
| `IP` | the client IP only |

The client IP is the first value of `client-ip-header` (`X-Forwarded-For` by default) when
the request carries it, otherwise the socket peer. **Only trust the header when a proxy you
control sets it**: a caller who can reach the gateway directly can otherwise pick any bucket
by sending the header. Set `client-ip-header: ""` to ignore it.

To key on anything else (an API key, a tenant claim, a header) define a bean:

```java
@Bean
RateLimitKeyResolver apiKeyResolver() {
    return exchange -> Mono.justOrEmpty(exchange.getRequest().getHeaders().getFirst("X-Api-Key"))
            .map(k -> "key:" + k);
}
```

An empty `Mono` means "do not limit this request".

## Which requests are limited: the paths

With no `paths`, the limiter applies to every transcoded REST route, every `REST_DOWNSTREAM`
route and the GraphQL endpoint (`spring.graphql.http.path`). Swagger UI, the console and
anything else the gateway did not register are not limited, and the actuator base path
never is. `paths` replaces that with Ant patterns; `exclude-paths` always wins:

```yaml
shiguchi:
  rate-limit:
    paths: ["/v1/**", "/graphql"]
    exclude-paths: ["/v1/public/**"]
```

The filter runs after Spring Security (so the principal is known and an unauthenticated
request on an `AUTHENTICATED` surface is answered 401 before it costs a token) and before
routing, validation and authorization.

## Backends

### `MEMORY` (default)

Buckets live in a Caffeine cache inside the gateway process; an idle bucket is dropped once
it would have refilled anyway. Zero setup, exact, and per instance: three gateway replicas
each let a caller through `burst-capacity` times.

### `REDIS`

The same bucket kept in Redis and updated atomically by a Lua script, so every gateway
instance counts a caller against one bucket. Needs `spring-boot-starter-data-redis-reactive`
on the classpath and the standard `spring.data.redis.*` connection settings; the gateway uses
Boot's `ReactiveStringRedisTemplate`.

```yaml
shiguchi:
  rate-limit:
    backend: REDIS
    redis:
      key-prefix: "shiguchi:rate-limit:"   # keys: <prefix><caller>.tokens and .timestamp
      fail-open: true
spring:
  data:
    redis:
      host: redis
      port: 6379
```

One round trip to Redis per limited request. `fail-open: true` (default) lets a request
through when Redis cannot be reached and logs a warning; `fail-open: false` refuses with 429
instead. Keys expire on their own once a caller has been quiet long enough to have a full
bucket again, so a stopped gateway leaves nothing behind. Both limiters serve every gateway
version the same way, so mixing versions behind one Redis is fine.

Startup fails with a plain message when `backend: REDIS` is set without Spring Data Redis on
the classpath or without a template bean.

### Your own

A `RateLimiter` bean replaces either. It gets the key and the requested token count and
answers a `Decision` (allowed, tokens remaining, seconds to wait), so a per-plan limit, a
sliding window or a call to an external quota service all fit.

## Properties

| Property | Default | Meaning |
| --- | --- | --- |
| `shiguchi.rate-limit.enabled` | `false` | Turn the inbound limiter on |
| `shiguchi.rate-limit.backend` | `MEMORY` | `MEMORY` or `REDIS` |
| `shiguchi.rate-limit.replenish-rate` | `100` | Tokens added per second (decimal allowed: `0.5`) |
| `shiguchi.rate-limit.burst-capacity` | `200` | Bucket size; the largest burst a quiet caller can send |
| `shiguchi.rate-limit.requested-tokens` | `1` | Tokens one request costs |
| `shiguchi.rate-limit.key` | `PRINCIPAL_OR_IP` | `PRINCIPAL_OR_IP`, `PRINCIPAL`, `IP` |
| `shiguchi.rate-limit.client-ip-header` | `X-Forwarded-For` | Header whose first value is the client IP; empty to use the socket peer only |
| `shiguchi.rate-limit.paths` | `[]` | Ant patterns to limit; empty = every gateway route and the GraphQL endpoint |
| `shiguchi.rate-limit.exclude-paths` | `[]` | Ant patterns never limited (actuator is always excluded) |
| `shiguchi.rate-limit.include-headers` | `true` | Send the `X-RateLimit-*` headers |
| `shiguchi.rate-limit.redis.key-prefix` | `shiguchi:rate-limit:` | Prefix of the Redis keys |
| `shiguchi.rate-limit.redis.fail-open` | `true` | Allow (true) or refuse (false) when Redis is unreachable |

`replenish-rate` must be positive, `burst-capacity` at least 1 and `requested-tokens`
between 1 and `burst-capacity`; anything else stops startup with Boot's failure report.

The [sample-rate-limited-gateway](../samples/sample-rate-limited-gateway) sample runs both
backends with limits small enough to hit from a shell loop.
