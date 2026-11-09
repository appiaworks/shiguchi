# GraphQL live queries (`@live`) — design

Status: decisions taken 2026-11-08 (section 10); proposal otherwise. Nothing here is built. It follows the response cache (E1) and the
push module (`shiguchi-cursus`), and is meant to be approved before any code.

## 1. What and why

A client sends `query ... @live` over the GraphQL WebSocket (`graphql-transport-ws`) or SSE. The
gateway runs it once, sends the result, and runs it again, sending the new result, whenever
something that the query read has changed. The client never polls and never subscribes to a
separate event stream just to learn that it should refetch.

Protocol-Lattice's `grpc_graphql_gateway` has the same feature (`live_query`, extension 1080). The
`graphql-alias` reader (A1) will map that option to "live allowed on this method" once this exists.

Non-goals: JSON patches between results (full results only, section 7), per-field dependency
tracking, live queries over plain HTTP POST, `@live` on mutations or subscriptions, federation.

## 2. Existing pieces it builds on

- **Subscriptions over `graphql-transport-ws`.** `PushGraphQlContributor` already serves
  `Subscription.<ns>Events` through `PushSubscriptions`; `GraphQlSubscriptionWebSocketEndToEndTest`
  is the test model. A live query is a subscription whose publisher is "run this operation again
  on a signal".
- **The response cache.** `GraphQlResponseCacheInterceptor` already works out which gRPC services a
  mutation touches (`Plan.services()`, root fields mapped to methods) and evicts the entries built
  from them. Two limits: it only runs when the cache is on, and the eviction is local to the
  instance.
- **Cursus.** `PushPublisher` / `PushBroker` fan an event out to every instance (memory, Redis,
  Postgres or JDBC engines), and `PushSource` (Kafka, gRPC stream) brings upstream events in.
- **Cost (E2).** `GraphQlLimitsInterceptor` prices an operation and charges the caller's budget.

## 3. Client contract

```graphql
query Orders @live(throttleMs: 2000) { orders(status: OPEN) { id total } }
```

`@live(throttleMs: Int)` — declared in the schema only while `shiguchi.graphql.live.enabled` is on,
`on QUERY`. The operation must be a query; `@live` on anything else is a validation error.

- **WebSocket:** the client sends `subscribe` with the query. The server answers with `next` frames,
  one per result, and never sends `complete` unless the client stops it, the principal's session
  ends or the server shuts down. Each frame is the full result.
  Frames carry `extensions.live = {"seq": n}` (n starts at 1) so a client can order them.
- **SSE:** `Accept: text/event-stream` on a POST to the GraphQL path streams `event: next` frames
  with the same payloads (graphql-sse "distinct connections" mode), through the same publisher.
- **`throttleMs`:** the least time between two results for this client. The server never goes
  below `shiguchi.graphql.live.min-interval`; a smaller hint is raised to it, a larger one is kept.
  Signals that arrive inside the window collapse into one re-execution at its end (trailing edge).
- A live query whose operation errors keeps the stream open: the error is a `next` with `errors`,
  and the next signal retries. A request-level failure (validation, budget, introspection switch)
  ends the stream with `error`, as for any subscription.

## 4. Invalidation

An operation has a **dependency set**: the gRPC services behind its root fields, worked out once
when the stream starts with the same `Plan` logic as the cache. A re-execution is triggered when any
service in that set is **invalidated**. Two sources, plus the time floor, all ending up as the same event.

**(a) Mutations through this gateway.** After a mutation succeeds, the gateway publishes an
invalidation for each service behind the mutation's root fields. This is independent of the
response cache, which may be off, so the observer lives in its own interceptor
(`GraphQlLiveInvalidationInterceptor`) and the cache interceptor keeps its own local eviction. When
both are on they share the `Plan` computation.

**(b) Upstream events.** A new field on `(shiguchi.graphql)`: `repeated string invalidated_by`, a
list of Cursus topic patterns (the same pattern syntax as `TopicPattern`). A method whose result
changes when an event arrives on one of those topics is invalidated by it. The topics are fed by
whatever already feeds Cursus: `PushSource` (Kafka, gRPC stream), `(shiguchi.topic)` declarative
topics, or an explicit publish. The field number is the next free one in `GraphQlRule` at the time
of the change; it is additive, so the API gate is not involved.

**(c) Time.** Only the global floor of section 6 (`refresh-interval`). There is no per-method timer: an
upstream that changes without telling us is covered by that floor, or by an `invalidated_by` topic.

**The event.** One Cursus topic per gRPC service: `shiguchi.graphql.invalidate.<service>`, payload
empty, attribute `origin` = the instance id. Every instance subscribes (through the broker, not
`PushSubscriptions`: no client queue, no authorization, no history) to the services that have at
least one live stream on that instance, and unsubscribes when the last one ends. Invalidations are
not stored: a missed one is a lost optimization, not lost data, because every stream also has the
time floor of section 6.

**No Cursus module.** When `shiguchi-cursus` is absent or `cursus.enabled` is off, an in-process
sink stands in. That is single-instance only; the starter logs a warning at startup if `live` is
on, more than one instance cannot be detected, and the docs say so. With Cursus on and the
`MEMORY` engine the same limit applies (the engine is per instance); `REDIS`, `POSTGRES` and
`JDBC` carry the invalidations across instances.

## 5. Re-execution

A live stream holds the original `WebGraphQlRequest` (document, variables, operation name, headers)
and the principal's `SecurityContext` captured when it started. On a signal it builds a fresh
request from them, with a new `DataLoaderRegistry` and none of the previous execution's state, and
runs it through the whole interceptor chain: limits, the cost budget, introspection, the document
cache and the field masks all apply again. The result is compared with the last one; an identical
result is not sent.

Consequences:

- **Cost.** The budget (E2) is charged per re-execution, to the same caller. A caller who runs out
  gets a `RATE_LIMITED` `next` with `retryAfter` and the stream stays open; the next signal after the
  budget refills succeeds.
- **Response cache.** A live re-execution bypasses the cache (reads and stores). Otherwise a result
  invalidated by a mutation could be served stale from an entry that was not yet evicted.
- **Authorization.** The principal is the one captured at connect. A BFF session that ends, or a JWT
  that reaches `exp`, ends the stream. This reuses the mechanism already applied to subscriptions
  (`GraphQlSessionEnded`, token expiry on streams; #433 #434).
- **Errors in the upstream.** A failing execution is sent as a result with `errors` and counts like
  any other result for throttling.

## 6. Limits and fairness

| Property | Default | Meaning |
| --- | --- | --- |
| `shiguchi.graphql.live.enabled` | `false` | Declare `@live` and serve it |
| `shiguchi.graphql.live.min-interval` | `1s` | Least time between two results of one stream |
| `shiguchi.graphql.live.max-per-principal` | `20` | Concurrent live queries of one principal; the next is refused with `LIVE_LIMIT` |
| `shiguchi.graphql.live.max-total` | `10000` | Concurrent live queries on the instance |
| `shiguchi.graphql.live.refresh-interval` | `5m` | A floor: a stream with no signal is re-executed this often, unless `0` |

Anonymous callers share the principal `anonymous`, so `max-per-principal` bounds them together.
The refresh floor means a lost invalidation is repaired within `refresh-interval` and costs
the same as any re-execution.

Fan-out: a signal for service S wakes every stream whose dependency set includes S. Streams
whose throttle window is open schedule one execution at its end. Executions run on the normal
request scheduler, with at most `max-concurrent-reexecutions` (default 32, per instance) at once; the
rest wait in order. Metrics: `shiguchi.graphql.live.streams` (gauge), `.executions`, `.skipped`
(identical result), `.signals` (counters, tagged by `source` = mutation, upstream, refresh).

## 7. Result shape and diffing

Full results. Lattice sends full results too, clients (Apollo, urql, Relay) already merge them
into a normalized cache, and a patch format would need a client contract of our own. An identical
result is suppressed (section 5). JSON patch or `@defer`-style incremental updates are a later
optimization once real traffic shows the payload size matters.

## 8. Interaction with other features

- **`@defer` (E5).** A live query is not incremental: `@defer` inside a live query is ignored (the
  stream carries complete results).
- **Batching, cost (E2), introspection:** apply as in section 5; a batch cannot contain `@live`.
- **Federation:** out of scope; a subgraph does not serve `@live`.
- **Alias (A1):** Lattice `live_query` (1080) maps to "this method may appear in a live query".
  Without the option, the gateway permits `@live` on any query. A method can opt out with
  `live: false` (new, in the same additive field set); decided in section 10.

## 9. Tests and docs

Tests (all in `shiguchi-graphql-starter`):

1. WebSocket end to end with an in-process upstream: connect, receive the first result; run a
   mutation through the gateway; receive a second result with the new data and `seq` 2.
2. Throttling: two mutations inside `min-interval` produce one extra result.
3. Identical result suppressed.
4. `invalidated_by`: publish to the topic through Cursus; the stream re-executes.
5. Limits: the 21st stream of one principal is refused with `LIVE_LIMIT`; a spent cost budget
   yields `RATE_LIMITED` and recovers.
6. Session end / token expiry closes the stream.
7. SSE variant of 1.
8. Two instances over Redis (Testcontainers, as the Cursus tests): a mutation on A re-executes a
   stream on B. Two instances on the memory engine cannot be tested; the single-instance limit is
   asserted by the startup warning.

Docs: `docs/graphql-annotations.md` (client contract, the options), `docs/push.md` (cross-link and
the invalidation topics), `docs/configuration.md`, README, CHANGELOG; the architecture note in
`docs/performance.md` (cost of a live stream).

## 10. Decisions

Settled on 2026-11-08:

1. **Dependencies are per service.** A mutation re-runs every live query that touches the service.
   A finer `live_group` on a method can come later if profiling shows noisy re-runs.
2. **Any query may be live** while `live.enabled`; a method excludes itself with `live: false`.
3. **Throttling is trailing-edge:** signals inside the window collapse into one re-run at its end.
4. **The in-process sink is the default and Cursus an upgrade,** so `live` works without the push
   module on one instance. Two code paths.
5. **No per-method timer.** `refresh-interval` is the only time-based re-run.
6. **Two interceptors** (live observer and response cache) sharing `Plan`, so the cache works
   without `live`.
