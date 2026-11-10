# Retries and circuit breaking

Two per-service settings for backends that fail: `retry` repeats a call that had a transient
failure, `circuit-breaker` stops calling a backend that is failing consistently. Both are off
by default, and both are configured per entry in `shiguchi.services[]`. For backends that are
slow rather than failing, see [concurrency limit](#concurrency-limit) and
[more connections per backend](#more-connections-per-backend); when only some instances of a
backend fail, [outlier detection](#outlier-detection) takes them out of rotation.

```yaml
shiguchi:
  services:
    - name: orders
      host: orders
      port: 9090
      retry:
        enabled: true
      circuit-breaker:
        enabled: true
```

They are complementary: retries absorb the single failed attempt, the breaker notices when
retrying has stopped helping and gets out of the way.

## Retries

A retry is a gRPC service config on the service's channel, so gRPC does the retrying rather
than the gateway. That matters for two reasons:

- **Each attempt is re-resolved and re-balanced.** A retry after `UNAVAILABLE` can land on a
  different instance of the backend, which is exactly the case worth retrying. A retry loop
  wrapped around the channel would keep hitting the instance that just failed.
- **gRPC throttles retries itself.** When a backend starts failing broadly, the channel's retry
  budget (`retry.throttling`, on by default) stops the gateway from multiplying the load on a
  struggling service — see [Retry budget](#retry-budget).

| Property | Default | Meaning |
| --- | --- | --- |
| `retry.enabled` | `false` | Retry failed calls to this backend |
| `retry.max-attempts` | `3` | Total attempts, the first one included, so 3 means two retries. gRPC caps this at 5 |
| `retry.initial-backoff` | `100ms` | Wait before the first retry |
| `retry.max-backoff` | `1s` | Ceiling for the growing backoff |
| `retry.backoff-multiplier` | `2.0` | Backoff growth per attempt |
| `retry.retryable-status-codes` | `[UNAVAILABLE, RESOURCE_EXHAUSTED]` | Which failures are worth repeating |
| `retry.retry-non-idempotent` | `false` | Retry methods not declared safe to repeat |
| `retry.throttling.enabled` | `true` | Budget retries per channel, so a broad outage gets first attempts only |
| `retry.throttling.max-tokens` | `10` | Bucket size, 1–1000; larger tolerates longer failure bursts before retries stop |
| `retry.throttling.token-ratio` | `0.1` | Tokens a successful call earns back |

### Retry budget

Without a budget, an outage multiplies load: every retryable call that fails is sent
`max-attempts` times, so a backend that is already falling over receives up to three times the
traffic at exactly the wrong moment. `retry.throttling` is gRPC's own remedy
([gRFC A6](https://github.com/grpc/proposal/blob/master/A6-client-retries.md#throttling-retry-attempts-and-hedged-rpcs)),
a token bucket per channel:

- The bucket starts full at `max-tokens`.
- Every failed call (a status in `retryable-status-codes`) takes one token; every successful
  call returns `token-ratio`.
- Retries are sent only while more than half the tokens remain. Below that, a failing call gets
  its first attempt and nothing more, until successes refill the bucket.

With the defaults, about five more failures than successes stop retrying, and each failure
takes ten successes to earn back. A backend that is merely flaky keeps its retries; one that is
failing broadly gets first attempts only, and the circuit breaker (below) takes it from there.
Raise `max-tokens` for a high-traffic service where five failures is noise, or set
`throttling.enabled: false` to let every call use all its attempts.

The budget is per gateway instance and per service channel, like the breaker.

### Which failures are retried

The default pair is the safe one. `UNAVAILABLE` usually means the call never reached the
backend, and `RESOURCE_EXHAUSTED` means the backend asked to be tried again later. Adding codes
the backend returns *after* doing work — `INTERNAL`, `UNKNOWN`, `DEADLINE_EXCEEDED` — means a
retry may repeat that work, so only add them when the backend deduplicates requests.

A response larger than `max-inbound-message-size` also ends the call with `RESOURCE_EXHAUSTED`
(502 `RESPONSE_TOO_LARGE` over REST), but it is never retried: the backend's response headers
arrive before the oversized message, and gRPC does not retry an attempt that has received them.
Repeating the call would only fetch the same oversized reply.

`DEADLINE_EXCEEDED` deserves its own warning: the deadline covers the whole call including its
retries, so retrying it rarely helps and mostly burns the remaining budget.

### Which methods are retried

Repeating a read is harmless. Repeating `CreateOrder` can create a second order. The gateway
therefore retries only methods it can tell are safe to repeat:

1. the proto declares `idempotency_level`:

   ```proto
   rpc GetOrder(GetOrderRequest) returns (Order) {
     option idempotency_level = NO_SIDE_EFFECTS;   // a pure read
   }
   rpc ReplaceOrder(ReplaceOrderRequest) returns (Order) {
     option idempotency_level = IDEMPOTENT;        // has effects, but repeating lands in the same state
   }
   ```

2. or, for a REST route whose proto says nothing, the `google.api.http` binding uses a safe verb
   (`get`, `head`, `options`). A `get` binding is a strong statement that the call is a read.

Everything else is excluded: the gateway emits a service config entry per excluded method with
no retry policy, which gRPC honours over the channel-wide one. At startup it logs how many
methods were excluded and which.

Most protos never declare `idempotency_level`, so expect this to exclude a lot at first. The
fix is to declare it on the methods you want retried. `retry.retry-non-idempotent: true` retries
everything instead, and is only sensible when the backend deduplicates requests itself.

A method reached by several routes is retried only when every route onto it is safe — one
unsafe binding excludes the method, since retry is a property of the method, not the route.

## Hedging

A retry waits for a failure. Hedging does not wait: if the backend has not answered within
`hedging-delay`, the channel sends the same call again (usually to another instance) and keeps
whichever answer arrives first, cancelling the rest. It trades extra load for a shorter tail —
worth it for reads where one slow instance sets your p99.

```yaml
shiguchi:
  services:
    - name: catalog
      hedging:
        enabled: true
        max-attempts: 3        # attempts in flight for one call, the first included (gRPC caps at 5)
        hedging-delay: 100ms   # set near the backend's p95; 0 sends every attempt at once
```

| Property | Default | Meaning |
| --- | --- | --- |
| `hedging.enabled` | `false` | Hedge calls to this backend. Cannot be combined with `retry.enabled` |
| `hedging.max-attempts` | `3` | Attempts in flight for one call, the first included |
| `hedging.hedging-delay` | `100ms` | Wait before each additional attempt |
| `hedging.non-fatal-status-codes` | `[UNAVAILABLE]` | Failures that do not end the call while other attempts are still running; any other status is returned at once |
| `hedging.hedge-non-idempotent` | `false` | Hedge methods not declared safe to repeat |
| `hedging.throttling.*` | on, `10`, `0.1` | The same budget as [retries](#retry-budget), counting hedged attempts |

- **Only methods safe to repeat are hedged**, by the same rule as retries (proto
  `idempotency_level`, or a GET/HEAD/OPTIONS binding). Hedging is riskier than retrying: the
  duplicate goes out while the first attempt may still be running, so a non-idempotent method
  could execute twice even when both attempts succeed.
- **Retry or hedging, not both** on one service: a gRPC method config carries one policy, and
  startup fails with a message if both are enabled.
- The deadline covers every attempt, and a hedge is not sent once the first response has
  arrived.

## Circuit breaking

Once a backend is properly down, retrying each call only adds latency and load. The breaker
counts failures per service and, after `failure-threshold` consecutive ones, fails calls
immediately with `UNAVAILABLE` for `open-duration`. It then lets `half-open-calls` trial calls
through: all succeeding closes it, any failing opens it again.

| Property | Default | Meaning |
| --- | --- | --- |
| `circuit-breaker.enabled` | `false` | Trip a breaker on this backend's failures |
| `circuit-breaker.failure-threshold` | `5` | Consecutive failures that open it |
| `circuit-breaker.open-duration` | `30s` | How long calls fail fast before a trial |
| `circuit-breaker.half-open-calls` | `1` | Trial calls admitted; all must succeed to close |
| `circuit-breaker.failure-status-codes` | `[UNAVAILABLE, DEADLINE_EXCEEDED, INTERNAL, UNKNOWN]` | Which statuses count as the backend failing |

Only consecutive failures open the breaker, so a success resets the run and occasional errors
never trip it.

The default status list deliberately leaves out the codes that mean the *caller* got it wrong:
`INVALID_ARGUMENT`, `NOT_FOUND`, `PERMISSION_DENIED` and friends. A backend answering those is
healthy, and counting them would let one client sending bad requests take a backend out of
service for everyone.

A refused call gets a message naming the service and when to retry, through the usual
[error renderer](error-handling.md), so callers see 503 rather than a timeout.

The breaker lives in one gateway instance and counts one service, not one method and not one
backend instance. Three gateway replicas have three independent breakers.

### Where the breaker sits

Above the retry machinery, so one logical call is one outcome: a call that succeeds on its
second attempt counts as a success, not a failure plus a success. That ordering is what makes
the two features compose rather than fight.

## Concurrency limit

A backend that stops keeping up rarely fails. It gets slow, and every caller queues behind it until
deadlines expire. `max-concurrent-calls` caps how many calls one gateway instance has in flight to
the backend. Past the cap, a call fails at once with `UNAVAILABLE` (HTTP 503, the message names the
service and the limit) without reaching the backend. The load is shed at the gateway while the calls
already running finish.

```yaml
shiguchi:
  services:
    - name: orders
      host: orders
      port: 9090
      max-concurrent-calls: 200
```

It counts logical calls. The limit sits above gRPC's retry machinery, so a retried or hedged call
holds one permit however many attempts it makes. The limit also sits above the circuit breaker, so
a rejection is never counted as a backend failure: an overloaded gateway must not also take a
healthy backend out of service. Like the breaker, the limit is per gateway instance: three
replicas with `max-concurrent-calls: 200` allow 600 calls in total.

A caller that gets the 503 should back off and retry; `UNAVAILABLE` is the code gRPC clients
already treat as retryable.

## More connections per backend

Each backend gets one channel by default, and gRPC multiplexes every call over its HTTP/2
connection(s). One connection per address has three ceilings:

- the backend's `MAX_CONCURRENT_STREAMS`, often 100–250, beyond which calls queue in the client;
- one event-loop thread's worth of framing;
- behind an L4 balancer, such as a Kubernetes ClusterIP Service, the balancer's single choice. It
  picks a pod once per connection, so every call goes to that pod.

`channels` opens that many channels and hands out calls round-robin:

```yaml
shiguchi:
  services:
    - name: orders
      target: dns:///orders.prod.svc.cluster.local:9090   # a ClusterIP address
      channels: 4
```

The circuit breaker and `max-concurrent-calls` are shared by the whole pool, not kept per channel.
When the backend's instances can be resolved individually, prefer that: a headless Service through
`target: dns:///…`, or `discovery-name` (see [service-discovery.md](service-discovery.md)). The
channel then balances per call across every instance, and `channels` only matters for the
per-connection limits.

## Outlier detection

A breaker judges the backend as a whole. When one instance out of five is sick — a bad node, a pod
that lost its database connection — the service keeps answering, but a fifth of every caller's
requests fail. Outlier detection judges instances against each other and takes the bad one out of
rotation:

```yaml
shiguchi:
  services:
    - name: orders
      discovery-name: orders          # or a target resolving to several addresses
      outlier-detection:
        enabled: true
```

Every `interval` (`10s`), the load balancer looks at each instance's calls since the last look.
An instance with at least `failure-percentage.request-volume` (`20`) calls whose failure rate is
`failure-percentage.threshold` (`50`) percent or more is ejected. The check only runs when at least
`failure-percentage.minimum-hosts` (`2`) instances had that many calls.

An ejected instance gets no calls for `base-ejection-time` (`30s`) times the number of times it has
been ejected, capped at `max-ejection-time` (`5m`), and then returns to rotation. At most
`max-ejection-percent` (`50`) of the instances are out at once, so an outage of the whole fleet
never ejects everything.

For larger fleets, `success-rate.enabled: true` adds the statistical check. It ejects an instance
whose success rate is more than `success-rate.stdev-factor` (`1.9`) standard deviations below the
fleet's mean, and needs `success-rate.minimum-hosts` (`5`) instances to be meaningful.

| Property | Default | Meaning |
| --- | --- | --- |
| `outlier-detection.enabled` | `false` | Eject instances whose calls fail far more than their peers' |
| `outlier-detection.interval` | `10s` | How often instances are evaluated |
| `outlier-detection.base-ejection-time` / `max-ejection-time` | `30s` / `5m` | How long an ejection lasts (grows with repeat ejections, capped) |
| `outlier-detection.max-ejection-percent` | `50` | Most instances that may be ejected at once |
| `outlier-detection.failure-percentage.*` | on, `threshold: 50`, `minimum-hosts: 2`, `request-volume: 20`, `enforcement-percentage: 100` | Eject at or above a failure rate |
| `outlier-detection.success-rate.*` | off, `stdev-factor: 1.9`, `minimum-hosts: 5`, `request-volume: 100`, `enforcement-percentage: 100` | Eject a statistical outlier |

It is gRPC's own `outlier_detection_experimental` policy, installed around the channel's
load-balancing policy (`round_robin` for `discovery-name` and `target`), so balancing is otherwise
unchanged. Every status except `OK` counts as a failure: gRPC cannot tell a caller's `NOT_FOUND`
from a sick instance. That is fine as long as caller errors spread evenly over instances, which is
why both checks are relative to the fleet. On a single `host`/`port` there is nothing to eject; the
gateway logs a warning and the setting has no effect.

With the breaker and outlier detection both on, one sick instance is ejected, and the breaker only
opens when the backend is failing everywhere.

## Both together

```yaml
shiguchi:
  services:
    - name: orders
      host: orders
      port: 9090
      deadline: 2s
      retry:
        enabled: true
        max-attempts: 3
        initial-backoff: 50ms
        max-backoff: 500ms
      circuit-breaker:
        enabled: true
        failure-threshold: 10
        open-duration: 15s
```

Keep the total retry time inside the deadline: the deadline bounds the whole call, retries
included, so a 2 s deadline with three attempts and a 500 ms ceiling leaves room, while a 5 s
initial backoff would simply expire.

Bad values here fail at startup with Boot's failure report rather than at the first call: gRPC
rejects a malformed service config by failing every call on the channel, which is a confusing
way to learn about a typo.

## Doing it yourself

Anything these settings do not cover belongs in a `GrpcChannelBuilderCustomizer` bean, which
runs after the gateway's own configuration and can override it:

```java
@Bean
GrpcChannelBuilderCustomizer hedgingCustomizer() {
    return (service, builder) -> {
        if ("search".equals(service.getName())) {
            builder.defaultServiceConfig(myHedgingConfig).enableRetry();
        }
    };
}
```

Hedging (sending several attempts at once and taking the first answer) is a gRPC feature the
gateway does not expose as configuration; a customizer is the way to it.
