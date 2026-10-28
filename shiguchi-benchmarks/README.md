# shiguchi-benchmarks

JMH micro- and macro-benchmarks for the gateway's transcoding hot path.

```bash
./gradlew :shiguchi-benchmarks:jmh          # full run, results in build/jmh-results.json
```

For a quick targeted run, build the fat jar and pass JMH options directly:

```bash
./gradlew :shiguchi-benchmarks:jmhJar
java -jar build/libs/shiguchi-benchmarks-*-jmh.jar "EndToEnd" -f 1 -wi 3 -i 5 -r 2s
```

## Suites

| Benchmark | What it measures |
| --- | --- |
| `EndToEndDispatchBenchmark` | Full response path over a real in-process gRPC channel: call machinery + per-tier serde to JSON bytes, Tiers 0–3 (Tier 1 through a compiled stub from `src/jmh/proto`, built with `protoc-gen-java-extras`, so it carries its writer and the leg runs on Java 21 too). The server returns cached wire bytes, so gateway-side cost dominates. |
| `HttpDispatchBenchmark` | The GraphQL surface's two transports for one field through the real dispatchers: `ReactiveGrpcDispatcher.dispatchToMap` over an in-process channel vs `HttpUpstreamDispatcher.dispatchToMap` (`REST_DOWNSTREAM`) over a loopback reactor-netty stub, with a bare `WebClient` GET as the HTTP floor. |
| `GatewayHttpBenchmark` | The whole gateway over HTTP: a Boot 3 gateway booted on a port, a reflection-served backend on a socket, one `POST /bench.Orders/Get` per operation through a pooled client — next to a bare reactor-netty server answering the same JSON (the HTTP floor) and the backend call alone (the gRPC hop). Where a request's time goes outside the serde. |
| `GatewayGraphQlBenchmark` | The GraphQL surface over HTTP next to the REST route for the same data: the Boot 3 gateway with DGS on, a reflection-served `bench.Shop` whose orders point at customers through a DataLoader. One order, a list of 10 / 100, and the list with each order's customer selected (one list hop plus one batch hop). What GraphQL execution adds on top of `GatewayHttpBenchmark`. |
| `PushBenchmark` | The push surface: `engine_fanout` (one `MemoryPushBroker.publish` with 1 / 100 / 1000 live subscribers), `engine_publish` (`PushPublisher.publish`: JSON check, id, history of 100, fan-out), and publish-to-first-delivery through a Boot 3 gateway with that many subscribers connected: `http_publish_delivery` (`POST /push/publish` → SSE), `httpv1_publish_delivery` (`POST /push/v1/publish`, the transcoding into the hosted `PushService` → SSE), `ws_publish_delivery` (→ WebSocket), `grpc_publish_delivery` (`Publish` on the native gRPC port → a gRPC `Subscribe` stream). `engine` also takes `JDBC` (an in-memory H2, broker polling every 50 ms). Numbers under [Push](#push-jdk-25-windows-11-loopback-2026-10-08). |
| `SerdeBenchmark` | Tier 2 (generated) vs Tier 3 (JsonFormat / ProtoMapConverter) for flat, nested, and repeated messages — serialize, toMap, fromMap. |
| `TimestampBenchmark` | google.protobuf.Timestamp conversion strategies (OffsetDateTime for GraphQL, RFC 3339 string for REST). |
| `GrpcProxyBenchmark` | Proxy method lookup and descriptor/marshaller cache hot paths. |
| `RouteRegistryBenchmark` | HTTP and GraphQL route lookup at 200 / 2 000 / 20 000 routes, exact and template paths — the check that the registry is flat in size. |
| `MergeBenchmark` | Cross-backend merge: comparator cost across descriptor instances, streaming vs materialised, and how both scale with source count. |
| `MergePrefetchBenchmark` | Merge prefetch sweep at a fixed source count — what buffering ahead costs a page versus a full drain. |
| `MergeEndToEndBenchmark` | A merge route over real in-process gRPC backends: fan-out, decode, merge, page; `mixedPage` adds one HTTP (`REST_DOWNSTREAM`) source. |
| `AvroCodecBenchmark` | `REST_DOWNSTREAM` body codecs at the step where they differ: Avro bytes ⇄ `Map` (via `AvroFraming` + `AvroMapConverter`) against JSON bytes ⇄ `Map` (Jackson), plus the single-object framing overhead. |
| `GraphQlProjectionBenchmark` | The GraphQL response path, proto → `Map`, full vs selection-projected — both Tier 2 compiled — on a message with 3 or 30 nested items. |
| `StreamingBackpressureBenchmark` | Server-streaming through `dispatchStreaming` with the consumer's demand as the variable — unbounded drain vs `request(1)` vs batches of 16 — over a real in-process channel; the allocation column is the no-buffering check. |
| `WktTranscodeBenchmark` | Tier 0 on a message of the structural well-known types — a `Struct` of ten keys with a nested struct and list, a `ListValue`, a `Value`, a `FieldMask` — against `JsonFormat`. |
| `Tier0FallbackBenchmark` | What a Tier 0 bail costs: the unary REST path on a clean wire vs one carrying proto merge semantics (a non-repeated field twice), which the transcoder cannot express and the dispatcher re-parses for Tier 2. |
| `FederationBatchLoaderBenchmark` | The federation batch loaders per batch of 1 / 10 / 100 keys: `GrpcBatchLoader` over an in-process unary batch rpc vs `OpenApiBatchLoader` over a loopback REST stub. |

## Reference results

Short profile (3 warmup + 5×2s, fork 1), JDK 25 on a Windows 11 laptop (2026-10-05), medium fixture message
(6 scalars, 3 nested items, 3-entry map, Timestamp). Directional, not lab-grade.

### End to end (in-process channel)

| Leg | µs/request | serde cost over RPC floor |
| --- | --- | --- |
| RPC only (baseline floor) | 1.1 | — |
| Tier 0 — wire bytes → JSON, no message object | 2.6 | 1.5 µs |
| Tier 1 — generated parser + typed writer (STATIC mode) | 2.8 | 1.7 µs |
| Full Tier 0 — request build + dispatch + transcode | 3.7 | — |
| Tier 2 — DynamicMessage + generated serializer | 4.5 | 3.4 µs |
| Tier 3 — DynamicMessage + JsonFormat | 6.5 | 5.4 µs |

Tier 0 is the fastest leg, and needs no stub on the classpath: ≈3.6× Tier 3 and ≈2.3× Tier 2
on serde cost. It reads map entries, `Timestamp`, `Duration` and the wrappers straight off the
wire and formats the instant arithmetically; before that it materialised a
`DynamicMessage` for each of those and sat 0.3 µs behind Tier 1. Tier 1 — the compiled parser
and a writer over typed getters — is within noise of it (its Timestamp goes through the same
formatter). The deltas are absolute per-request savings — a real network raises the RPC floor
but not the serde cost. All six rows come from one run, so compare across rows, not with older
tables.

Compiling the Tier 0 program itself to bytecode was measured before being built: a hand-written
straight-line transcoder for the fixture type (every switch resolved, names as constants) ran
2.60 µs against the interpreted 2.70 µs over two forks, inside the error bars. The
interpretation overhead is ≈0.1 µs per request; the rest is Jackson, UTF-8 decoding and number
formatting, which no code generation removes. So Tier 0 stays interpreted.

The same jar on **JDK 21** (2026-10-05, where the stub's `writeJson` from `protoc-gen-java-extras`
stands in for the run-time generated writer, and Tier 2 is unavailable): RPC floor 1.5 µs,
Tier 1 4.5 µs (3.0 serde), Tier 0 4.8 µs (3.3), Tier 3 8.1 µs (6.6). Tier 1 keeps its lead
without the Class-File API module.

### The gateway over HTTP (µs/request, JDK 25, Windows 11 loopback, 2026-10-06)

`GatewayHttpBenchmark`: the Boot 3 gateway on a port, DYNAMIC discovery against a
reflection-served `bench.Orders` on a socket, `POST /bench.Orders/Get` with `{"id":"order-1"}`
(auto-mapped route), one request at a time through a pooled `WebClient`.

| Leg | µs/request |
| --- | --- |
| `http_floor` — the same POST to a bare reactor-netty server answering the same JSON | 112 |
| `grpc_hop` — the backend call alone, plain channel, blocking | 116 |
| `grpc_hop_gatewayChannel` — the same call over the channel the gateway built (its interceptors and settings) | 119 |
| `gateway_post` — the gateway end to end | 432 |
| `gateway_post`, `lean` profile (observations, SCG metrics and Reactor context propagation off) | 408 |

So the gateway's own share of a request is ≈200 µs on this machine, next to ≈1.5 µs of serde
and ≈3 µs of request encoding. It was ≈250 µs before this series: Spring Security's default
session-backed context repository looked up (and created) a `WebSession` on every request, and
`InMemoryWebSessionStore` builds one on `boundedElastic` and hands it on through `parallel` —
two thread hand-offs per request for a gateway that never stores anything in a session. The
security chain is stateless now (`NoOpServerSecurityContextRepository`, no request cache).

What is left is CPU, spread thin: JFR over the gateway JVM (`-prof jfr`) puts ≈47% of samples in
Reactor operator plumbing (`currentContext` walks of a chain several hundred operators deep,
`contextWrite` per Spring Security filter), ≈16% in Micrometer observations, ≈9% in Spring
Security's wrappers, ≈6% in the gateway's own code. Switching the observations off is the
`lean` row: ≈25 µs. Two things measured and not kept: gRPC's `directExecutor()` on the channel
(≈20 µs, inside the error bars, and it stalls the server-streaming path once backpressure
engages — `RestStreamingHttpBodyEndToEndTest` at 2 000 chunks hangs) and building the duration
timer per call in `GrpcMetricsInterceptor` (now cached per status; not measurable here).

The security chain, measured on its own (2026-10-07, `gateway_post`, 3 forks):

| Profile | µs/request |
| --- | --- |
| `default` — the gateway's chain (stateless, no logout filter, no saved-request replay) | 423 |
| `no-security-observations` — `management.observations.enable.spring.security=false` | 406 |
| `no-security` — a caller-supplied chain that matches only the always-gated actuator paths, so a transcoded route never enters Spring Security | 369 |

So Spring Security is ≈55 µs of a REST request under `PERMIT_ALL`: ≈17 µs is the observation
it opens around every filter, the rest the filters themselves (header writer, CORS, context,
exception translation, authorization) and the `contextWrite` each adds. Dropping the logout and
saved-request filters measured inside the error bars and stayed for correctness (a transcoded
`POST /logout` is no longer claimed by `LogoutWebFilter`). The observations stay on by default;
skipping the chain for open REST routes is the opt-in
`shiguchi.security.open-rest-bypasses-chain` ([security.md](../docs/security.md)).

**Boot 3 against Boot 4** (`:shiguchi-benchmarks-boot4`, the same sources on the Boot 4
auto-configuration: Spring Framework 7, Spring Cloud Gateway 5, Spring Security 7; 2026-10-07,
`gateway_post`, 3 forks each, back to back):

| Profile | Boot 3 | Boot 4 |
| --- | --- | --- |
| `default` | 396 ± 20 | 395 ± 21 |
| `lean` | 370 ± 16 | 372 ± 13 |
| `no-security` | 337 ± 6 | 340 ± 8 |

The two lines are the same gateway: no measurable difference in any profile. Read the two
columns of one run against each other, never a number here against one taken an hour earlier —
the same Boot 3 `default` measured 363, 396 and 423 µs in three runs on this laptop over a day,
with the bare HTTP floor drifting 76–113 µs alongside it. Deltas within a run are stable to a few
microseconds; absolute numbers are not.

JMH's `-prof stack` is useless for this benchmark: safepoint-biased and dominated by idle event
loops. Use `-prof jfr` and read the recording with `jfr print --events jdk.ExecutionSample`,
or `jstack` the forked JVM. The JMH jar flattens every dependency, so Spring's per-jar
registration files are merged by `mergeSpringMeta` in `build.gradle.kts`.

### GraphQL over HTTP (µs/request, JDK 25, Windows 11 loopback, 2026-10-07)

`GatewayGraphQlBenchmark`: the same gateway with the GraphQL surface on, DYNAMIC discovery
against a reflection-served `bench.Shop` whose protos carry `(shiguchi.api.graphql)` and
`(shiguchi.api.graphql_field)` options: `Shop.Get` → `order`, `Shop.List` → `orders`, `Shop.GetCustomers` the
`loader_for: "Customer"` batch method behind `ShopOrder.customer_id`. Each order carries 3 items.
The `rest_*` legs are the auto-mapped route for the same call on the same gateway, so the GraphQL
excess is read against a REST number taken with DGS on the classpath. Allocation is the
`default` profile's `gc.alloc.rate.norm`.

| Leg | default | no-otel | lean | KB/request |
| --- | --- | --- | --- | --- |
| `rest_single` — `POST /bench.Shop/Get` | 486 | 435 | 404 | 108 |
| `graphql_single` — `{ order(id) { id name active score items { sku qty price } } }` | 613 | 606 | 593 | 242 |
| `rest_list_10` — `POST /bench.Shop/List {"count":10}` | 454 | 446 | 430 | 133 |
| `graphql_list_10` — `{ orders(count: 10) { items { …9 fields… } } }` | 739 | 715 | 698 | 514 |
| `graphql_nested_10` — `{ orders(count: 10) { items { id name customer { id name tier } } } }` | 1 010 | 962 | 814 | 443 |
| `rest_list_100` | 585 | 544 | 541 | 373 |
| `graphql_list_100` | 1 717 | 1 759 | 1 639 | 3 178 |
| `graphql_nested_100` | 1 741 | 1 736 | 2 045 | 2 203 |

GraphQL error bars are ±200–400 µs where REST's are ±5–70: the GraphQL legs allocate 2–8× more
and their spread is GC. Read the means as such.

- One field costs ≈130 µs over its REST route: parse, validate, execute, one fetcher, the
  result serialised again through Jackson. The `lean` and `no-otel` rows barely move, so
  observations are not where GraphQL's time goes.
- The cost scales with the fields completed, not the bytes: 100 orders × 9 fields is ≈1.1 ms
  over the REST list of the same data, ≈1.2 µs and ≈28 KB per field-bearing object. graphql-java
  builds an `ExecutionStepInfo`, a `FieldValueInfo` and a `CompletableFuture` per field, the
  gateway hands it a `Map` tree, and Jackson writes the result; the REST route streams the wire
  bytes to JSON in ≈1.5 µs for the whole message.
- The nested selection over 10 orders adds ≈270 µs to the list: one more gRPC hop (≈116 µs) plus
  the DataLoader round (10 relationship fetchers scheduled, dispatched once, the batch
  completed back into 10 futures). Over 100 orders the nested query is no slower than the flat
  list because it selects 5 fields per order instead of 9.

JFR over the gateway JVM (`-prof jfr`, samples attributed to the first non-JDK frame):

| | `graphql_single` CPU | `graphql_list_100` CPU | `graphql_list_100` allocation |
| --- | --- | --- | --- |
| graphql-java engine (`executeObject`, `getFieldDefinition`, `DataFetchingEnvironmentImpl`, the instrumentation chain) | 24% | 59% | 73% |
| Reactor operators | 26% | 6% | 2% |
| Netty + reactor-netty | 11% | 4% | 1% |
| Micrometer + OTel | 9% | 3% | — |
| protobuf parse + the gateway's `toMap` | 3% | 12% | 8% |
| Jackson (the result) | 2% | 6% | 2% |
| Spring Security + WebFlux | 5% | 2% | — |

A one-field query is the HTTP stack with an engine pass on top; a hundred-object list is the
engine: a `DataFetchingEnvironmentImpl`, an `ExecutionStrategyParameters`, an `ExecutionStepInfo`
and a lambda per field (28% of the bytes), the field-value map per object, the per-fetcher
tags DGS's metrics instrumentation resolves. The gateway's own share — descriptor lookup, the
`Map` tree — is under a tenth; it hands graphql-java a tree and graphql-java walks it field by
field.

The one knob measured that moves GraphQL: the per-field instrumentation. DGS times every data
fetcher (`gql.resolver`) and Spring GraphQL opens a `graphql.datafetcher` observation per field;
both run once per field completed. The `no-field-metrics` profile turns them off
(`management.metrics.dgs-graphql.instrumentation.enabled=false`,
`management.observations.enable.graphql.datafetcher=false`) and keeps the request-level
`graphql.request` observation and `gql.query` timer:

| Leg | default | no-field-metrics |
| --- | --- | --- |
| `graphql_single` | 621 ± 219 | 481 ± 141 |
| `graphql_list_100` | 1 668 ± 333 | 1 422 ± 282 |
| `graphql_nested_100` | 2 468 ± 421 | 2 138 ± 305 |

≈15–20% of a GraphQL request on this machine, inside error bars that wide, so read it as a
direction. The default stays: per-field timings are what a GraphQL operator reads to find the
slow resolver, and the gateway's resolvers are its gRPC calls. A deployment that watches only
request latency can set the two properties.

Measuring the nested legs found a bug before any number: on the Boot 3 line every relationship
field waited ten seconds and answered `null`. DGS gives each request a
`ScheduledDataLoaderRegistry` whose registry-level predicate is `DISPATCH_NEVER`; the loaders the
gateway registers through Spring GraphQL's `BatchLoaderRegistry` carried no predicate, so
graphql-java's per-level `dispatchAll()` skipped them. graphql-java 25's chaining mode (Boot 4)
dispatches loaders directly and hid it. `GatewayBatchLoaderRegistry` now registers every loader
with `DISPATCH_ALWAYS`; `GraphQlRelationshipDataLoaderEndToEndTest` holds it on both lines.

The JMH plugin's `jmh` task runs the forked JVM on the fat jar *and* every original jar, so a
library that loads resources with `classpath*:` (DGS and its `META-INF/schema/*.graphqls`) sees
them twice; `build.gradle.kts` clears the task's extra classpaths so the run matches `java -jar`.

### GraphQL over an OpenAPI backend (`backend` param)

`GatewayGraphQlBenchmark` takes `@Param backend` (`grpc` | `http`). `grpc` is the table above.
`http` runs the same gateway with the service declared `mode=REST_DOWNSTREAM` and `openapi-url`
pointing at a reactor-netty stub that serves an OpenAPI 3 document for the same shapes
(`Order` with `id name active score customer_id items[{sku qty price}]`, `Customer`) and the
JSON for them: `GET /orders/{id}` (`getOrder`), `GET /orders?count=N` (`listOrders`, a bare
array) and `GET /customers?ids=` (`getCustomers`). The response bytes are precomputed once per
request shape from the same `order(n)` / `customer(id)` data, so the stub costs little. The
GraphQL field names come from the `operationId`s:

| Leg | `grpc` query | `http` query |
| --- | --- | --- |
| `graphql_single` | `{ order(id: "order-1") { id name active score items { sku qty price } } }` | `{ getOrder(id: "order-1") { id name active score items { sku qty price } } }` |
| `graphql_list_N` | `{ orders(count: N) { items { …same 5 fields… } } }` | `{ listOrders(count: N) { …same 5 fields… } }` |
| `graphql_nested_N` | `{ orders(count: N) { items { id name customer { id name tier } } } }` | not available |

- `rest_*` on `http` is the REST passthrough of the `REST_DOWNSTREAM` service
  (`GET /shop/orders/order-1`, `GET /shop/orders?count=N`): a proxy of the stub's JSON, where
  on `grpc` it is a transcode. Read it as the REST floor for that backend, not as the same work.
- `graphql_nested_*` throw `UnsupportedOperationException` with `-p backend=http`. The schema
  links `customer_id` to `Customer` through the proto `resolves_type` option, which a
  descriptor built from OpenAPI cannot carry, and `rest-batch-loaders[]` only feed Federation
  `_entities`. Run them with `-p backend=grpc` (JMH reports the `http` rows as errors
  otherwise).
- `setUp` checks every `http` leg before measuring: the REST passthrough and the GraphQL
  answers carry the expected order names, scores and items, the list legs return exactly N
  rows, and no response contains `"errors"`.

```bash
java -jar build/libs/shiguchi-benchmarks-*-jmh.jar "GatewayGraphQlBenchmark.(rest|graphql_(single|list))" -p profile=default -p backend=grpc,http -f 1 -wi 3 -i 5 -r 2s
```

| Leg (µs/request, `default`) | `grpc` | `http` |
| --- | --- | --- |
| `rest_single` | 420.6 ± 28.9 | 362.1 ± 10.0 |
| `graphql_single` | 645.9 ± 248.6 | 587.7 ± 159.2 |
| `rest_list_10` | 440.3 ± 33.6 | 373.3 ± 12.0 |
| `graphql_list_10` | 779.2 ± 163.2 | 737.1 ± 242.7 |
| `rest_list_100` | 571.6 ± 85.7 | 402.4 ± 22.5 |
| `graphql_list_100` | 1885.4 ± 246.6 | 1739.3 ± 324.1 |

Measured 2026-10-27, JDK 25.0.4.1, Windows 11, one fork, 3×2 s warm-up, 5×2 s measured.
At this precision the two backends cost the same for GraphQL: the gateway's GraphQL work
dominates, not the backend protocol. REST on `http` is cheaper because it is a proxy, not
a transcode.

### HTTP dispatch vs gRPC dispatch (µs/op, one field, same order both ways)

`HttpDispatchBenchmark`, through the dispatchers the GraphQL data fetcher calls:

| Leg | µs/op |
| --- | --- |
| `grpc_dispatchToMap` — in-process channel, wire → message → projected Map | 5.5 |
| `http_webClientOnly` — loopback GET through a bare `WebClient`, body discarded | 74.9 |
| `http_dispatchToMap` — loopback GET, JSON → Map → synthetic message → projected Map | 103.0 |

`http_bindOnly` isolates the bind step of `http_dispatchToMap`: the decoded response `Map`
through `SerdeSelector.deserializeFromMap` into the synthetic message and
`serializeToMapProjected` back out (no socket, no URI, no JSON parsing). It is the part of the
HTTP dispatcher's work that the gRPC leg does not have a counterpart for; the rest of the gap
is parsing, URI building and the socket. Short runs put it at 1.6 to 2.7 µs/op, about 2% of
`http_dispatchToMap`.

The HTTP dispatcher's own work — URI building, JSON parsing, binding through the synthetic
message and projecting back — is the 28 µs between the last two rows. The other 75 µs is the
socket: a `REST_DOWNSTREAM` backend costs a real round trip where an in-process gRPC channel
costs none, which is a property of the benchmark's loopback, not of the gateway. Against a
real network both transports pay the same floor and the gateway-side gap is 28 µs vs 4 µs.

### Serde tiers (unit level, ops/µs)

| Benchmark | Tier 2 | Tier 3 |
| --- | --- | --- |
| serialize flat message | 2.9 | 1.2 |
| toMap flat | 7.3 | 6.1 |
| toMap repeated (100 int64) | 80 | 3.5 |
| fromMap flat | 3.6 | 3.4 |

### Avro upstream codec vs JSON (ops/µs, one record with `tags` of N strings)

| Step | N = 10 | N = 100 |
| --- | --- | --- |
| Avro bytes → `Map` (`avro_decode_toMap`) | 0.63 | 0.17 |
| Avro single-object bytes → `Map` | 0.32 | 0.14 |
| JSON bytes → `Map` (Jackson) | 0.83 | 0.22 |
| `Map` → Avro bytes (`avro_encode_fromMap`) | 0.59 | 0.16 |
| `Map` → JSON bytes (Jackson) | 1.17 | 0.32 |

At the gateway, Avro costs about 1.3× the CPU of JSON on the way in and 2× on the way out
for this record (a generic-record decode plus a `Map` copy, against Jackson's direct
tree). The 10-byte single-object header itself is cheap; the gap in that row is the
fingerprint computation, which is per call today. Choose Avro for the backend's sake
(payload size, schema enforcement), not for gateway throughput; the rest of the request
path is identical for both.

### Cross-backend merge (µs/op, 200 rows per source, every source pre-sorted)

| | 2 sources | 4 | 8 | 16 |
| --- | --- | --- | --- | --- |
| `mergeStreamingFirstPage` (25 rows) | 4.4 | 12.2 | 27.0 | 61.0 |
| `mergeStreamingFull` | 60.1 | 340.4 | 1520.5 | 6610.8 |
| `mergeListsSameDescriptor` | 105.9 | 477.0 | 1486.6 | 3924.1 |
| `mergeListsCrossDescriptor` | 101.2 | 478.8 | 1581.1 | 4032.0 |
| `compareCrossDescriptor` (100 compares) | 9.2 | 9.5 | 10.0 | 9.0 |

Prefetch sweep, 8 sources (`MergePrefetchBenchmark`):

| prefetch | 1 | 8 | 32 | 128 | 256 |
| --- | --- | --- | --- | --- | --- |
| `drainAll` | 1767 | 1617 | 1889 | 1546 | 1571 |
| `firstPage` | 22.9 | 26.0 | 32.1 | 44.0 | 55.9 |

**Serving a page is cheap, and prefetch is what makes it cheap.** A first page costs 61 µs at 16
sources against 6.6 ms to drain everything. The sweep shows why the default prefetch is small:
`firstPage` runs 22.9 µs at prefetch 1 and 55.9 µs at 256, because a page only needs a head per
source and anything read beyond that is wasted. `drainAll` is flat across prefetch — once every row
is wanted, buffering neither helps nor hurts.

**Cross-descriptor merging is free.** `mergeListsCrossDescriptor` tracks `mergeListsSameDescriptor`
within a few percent, and `compareCrossDescriptor` stays at ~9-10 µs per 100 comparisons whatever
the source count. Records in a real merge come from different backends, so their field descriptors
are distinct instances and cannot be matched by identity; `SortComparator` caches the resolved path
per descriptor, and those two flat rows are that cache working.

**Draining every source scales worse than the heap alone.** `mergeStreamingFull` runs 150 ns/row at
2 sources and 2066 ns/row at 16 — around 14×, where the heap's O(log sources) predicts nearer 3×.
Two explanations were checked and both are wrong: it is not per-source prefetch buffering (the
sweep above is flat), and it is not unsorted input (sorting every source, which is the merge's
actual precondition, made the full drain *slower*, because properly interleaved data forces real
cross-source comparisons instead of draining one source at a time). What is left is per-source
operator overhead in `Flux.mergeComparing`, paid on every row.

It does not affect a merge route: those serve pages, and `mergeStreamingFirstPage` stays close to
linear in source count. Worth knowing before draining many sources in full — and worth noting that
past ~8 sources a full drain is cheaper materialised than streamed.

### Merge route end to end (µs/op, 100 rows per source, pageSize 25)

Real gRPC backends, one in-process server each, every source pre-sorted:

| | 2 sources | 4 | 8 |
| --- | --- | --- | --- |
| `fanOutOnly` (N calls, no merge) | 83.2 | 169.7 | 349.7 |
| `mergePage` (fan-out + merge + page) | 131.2 | 336.9 | 876.3 |
| `twoSourcePage` (always 2 sources) | 127.8 | 129.4 | 127.5 |

`twoSourcePage` merges the same two backends whatever the parameter says, so its flatness is the
harness checking itself: the numbers move because the fan-out widens, not because the benchmark
drifts.

**Fan-out is linear; assembly is not.** Merging costs 48 µs over raw fan-out at 2 sources and
527 µs at 8 — 1.6× to 2.5× the transport. Each added backend brings both another call and another
source of rows to decode and order, and the second grows faster. Consistent with
[`MergeBenchmark`](#cross-backend-merge-µsop-200-rows-per-source-every-source-pre-sorted), where
per-source operator overhead, not the heap, drives the curve.

This suite exists as much for coverage as for numbers. The merge feature's two worst defects — an
SCG route-id collision that made a merged path 404, and a comparator that threw across backends —
were both invisible to unit tests and only appeared when something drove the whole path. This
benchmark drives it on every run.

#### One HTTP source in the mix

`mixedPage` swaps the last gRPC backend for a `REST_DOWNSTREAM` one: a reactor-netty stub
answering the same rows as a JSON array over a real socket, split into rows and merged with the
gRPC records. Same run as the `mergePage` column next to it (a different, slower machine than the
table above, so compare across the row, not with it):

| | 2 sources | 4 | 8 |
| --- | --- | --- | --- |
| `mergePage` (all gRPC, this run) | 206.6 | 597.6 | 1366.9 |
| `mixedPage` (one of them HTTP) | 453.6 | 827.0 | 1606.4 |

The HTTP source adds roughly 240 µs whatever the width — one HTTP round trip, JSON parsing of
100 rows, and binding each row through the synthetic message — and the merge itself does not
care where the rows came from. It is also the one source read whole on every page: an HTTP
backend cannot be handed the merge's sort or window, so `presorted` is ignored for it (see
[docs/merging.md](../docs/merging.md#http-sources)).

### Push (JDK 25, Windows 11 loopback, 2026-10-08)

`PushBenchmark`, `-f 1 -wi 3 -i 5 -r 3s`, `engine` MEMORY (in-process), REDIS (Pub/Sub +
Streams on an embedded Redis on the same host) and POSTGRES (`LISTEN/NOTIFY` + one table on an
embedded Postgres; its rows are in a second table below). The engine alone, then the whole stack: a Boot 3
gateway with push on, N Server-Sent Events subscribers connected on one topic, one
`POST /push/publish` per operation, the operation done when the first subscriber has the event.

| µs | MEMORY 1 | MEMORY 100 | MEMORY 1000 | REDIS 1 | REDIS 100 | REDIS 1000 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `engine_fanout` — `PushBroker.publish` | 0.023 | 0.58 | 5.4 | 56 | 59 | 55 |
| `engine_publish` — JSON check + id + history + fan-out | 0.47 | 0.96 | 6.0 | 46 | 66 | 62 |
| `http_publish_delivery` — publish → first SSE subscriber has it | 206 ± 11 | 554 ± 74 | 2294 ± 1048 | 433 ± 50 | 478 ± 69 | 1891 ± 238 |
| `ws_publish_delivery` — publish → first WebSocket subscriber has it | 205 ± 10 | 614 ± 42 | 3924 ± 194 | 356 ± 57 | 499 ± 93 | 3948 ± 523 |
| `httpv1_publish_delivery` — `POST /push/v1/publish` (transcoding → hosted `PushService`) → first SSE subscriber has it | 262 ± 36 | 630 ± 110 | 2157 ± 287 | 413 ± 129 | 594 ± 84 | 2023 ± 213 |
| `grpc_publish_delivery` — `Publish` on the gRPC port → first gRPC `Subscribe` stream has it, first cut: proxy bridge into the in-process channel, one message encode per stream, all streams on one connection | 151 ± 7 | 485 ± 22 | 3260 ± 181 | 273 ± 11 | 556 ± 26 | 3250 ± 78 |
| `grpc_publish_delivery` — served in place on the port, one shared frame per event, one connection per 100 streams | 121 ± 15 | 233 ± 12 | 5144 ± 289 | 261 ± 29 | 396 ± 30 | 3848 ± 1215 |

Reading it: the in-memory engine costs about 5 ns per subscriber, flat from 1 to 1000. The Redis
engine costs one round trip per publish (`PUBLISH`, or the `XADD` + `EXPIRE` script then
`PUBLISH`), ≈50 µs on loopback, independent of the subscriber count: the fan-out happens after
the event comes back on the listener connection. Over HTTP that is a second round trip before
the first subscriber sees the event, ≈230 µs more with one subscriber; from 100 subscribers on
the SSE writes dominate and the engines read the same, the Redis leg even a little lower
because its fan-out runs on the listener thread rather than inside the publish request. The
MEMORY 1000 row was measured with a compile running on the same machine, hence its error bar;
the earlier solo run gave 2187 ± 241. A thousand simultaneous connects overflow the listen
backlog, which Windows answers with a refused connection, so the trial connects its subscribers
in batches of 50.

The hosted `PushService` (measured on 2026-10-09, the same machine, with `http_publish_delivery`
re-run alongside: 194 / 544 / 2000 and 342 / 441 / 1759 µs, so the two rows above compare
like for like). `POST /push/v1/publish` costs 60–90 µs more than the hand-written handler up to
100 subscribers (the request's JSON is parsed into a `PublishRequest`, crosses the in-process
channel, and the `Value` payload is written back to JSON) and the same within error at 1000,
where the SSE writes dominate either way. Publishing over the native gRPC port to gRPC
subscribers is the fastest leg with one subscriber, 151 µs against 194 for HTTP (a binary request,
no JSON on the way in), level with SSE at 100, and ≈3.3 ms at 1000 against ≈2.0 ms, in the
WebSocket leg's range: every delivery converts the event's JSON to a `Value` and encodes one
proto message per stream, and in this trial the thousand `Subscribe` streams share one HTTP/2
connection, so their writes serialise on it, which real clients on their own connections would
not. Converting the payload once per event rather than once per subscriber is the obvious next
step if that leg matters.

What the second gRPC row changed, measured one step at a time on the MEMORY engine: building
the event's wire bytes once per event and writing the same `byte[]` to every stream (instead of
one JSON-to-`Value` conversion and one proto encode per stream) took the 1000-subscriber leg
from 3260 to 2952 ± 267 µs, about a tenth, so conversion was not where the time went. Serving the
service in place on the native port, as a `GatewayHostedService`, instead of through the proxy's
bridge into the in-process channel, took the one-subscriber call from 151 to 121 µs (and 485 to
233 at 100): that hop was the per-call cost. Spreading the thousand streams over ten connections,
as a fleet of clients would, made the 1000 row slower, 2952 → 5144 µs, where one connection lets
the server flush a thousand writes from one event loop in a few batches and ten connections cost
ten wake-ups per event instead; the 100 row improved for the same reason in reverse. What is left
at 1000 is per-stream HTTP/2 framing on the server, ≈3–5 µs a stream against ≈2 for an SSE chunk.
A typed payload (`message`) skips the JSON rendering for gRPC subscribers entirely: it is
forwarded as published.

The POSTGRES engine (measured 2026-10-12; an embedded Postgres 18.6 on the same host, the
gateway on Boot's pooled `ConnectionFactory`; the 2026-10-09 run on Postgres 16 read within the
same noise):

| µs, POSTGRES | 1 | 100 | 1000 |
| --- | ---: | ---: | ---: |
| `engine_fanout` — `PushBroker.publish` | 171 ± 105 | 228 ± 66 | 158 ± 6 |
| `engine_publish` — JSON check + id + history + fan-out | 198 ± 16 | 197 ± 101 | 213 ± 13 |
| `http_publish_delivery` — publish → first SSE subscriber has it | 608 ± 144 | 792 ± 112 | 2796 ± 4094 |
| `ws_publish_delivery` — publish → first WebSocket subscriber has it | 786 ± 211 | 865 ± 183 | 2667 ± 5167 |
| `httpv1_publish_delivery` — transcoding → hosted `PushService` → SSE | 714 ± 218 | 786 ± 282 | 3319 ± 2514 |
| `grpc_publish_delivery` — gRPC port → gRPC stream (served in place, one connection per 100 streams) | 491 ± 28 | 543 ± 23 | 6197 ± 4124 |

Reading it: a Postgres publish is one `pg_notify` round trip on a pooled connection, about
170–200 µs on loopback with the engine alone, flat in the subscriber count as with Redis, four
times a Redis `PUBLISH`; `engine_publish` adds the `INSERT ... RETURNING id` for the history and
reads the same within noise (198 µs), two short statements on one pooled connection. End to end
that puts the first delivery about 260 µs behind Redis with one subscriber (608 against
342 over HTTP), and level with it from a hundred subscribers on, where the transport writes
dominate. The gateway's own pool matters: the same statements on a connection factory without a
pool open a connection per statement and cost ~20 ms each, which is where a `spring.r2dbc.url`
without `r2dbc-pool` on the classpath would land.

The archive (`archive.retention` above zero, measured 2026-10-12 on Postgres 18 with `-p archive=true,false` in
one run): the same `INSERT ... RETURNING id`, now also maintaining the three search indexes (time,
a GIN over the attributes, a GIN over the payload's `tsvector`):

| µs, POSTGRES, 1 subscriber | archive off | archive on |
| --- | ---: | ---: |
| `engine_publish` | 198 ± 16 | 155 ± 33 |
| `http_publish_delivery` | 608 ± 144 | 670 ± 116 |

Reading it: on Postgres 18 the index maintenance no longer shows on the engine leg (198 → 155,
the archive-on run reading faster, so this is noise; the 2026-10-09 Postgres 16 run measured
+53 µs, 156 → 209), and the end-to-end delivery moves inside its error (608 → 670). Treat the
cost as small, not zero, until a quieter run separates it. A topic without history pays the insert it did
not pay before (its events now have a row), which is the point: they are searchable.

The Redis archive (`-p engine=REDIS -p archive=false,true -p subscribers=1`, 2026-10-11): one
extra script per publish (`XADD` with the event's id, the head trim, the topic index), awaited
before the publish returns. `http_publish_delivery` goes 447 ± 82 → 736 ± 230 µs, about 290 µs
more on loopback, with a wide error on the archive leg; `engine_publish` does not show it because
that leg publishes through a `MemoryPushStore` whatever the engine is. Turn it on when the
operators' search is worth roughly one more Redis round trip per event.

The JDBC engine (`-p engine=JDBC -p subscribers=1,100`, in-memory H2, 50 ms poll interval,
2026-10-11, `-wi 1 -i 3`): `engine_publish` 43 ± 133 µs against 0.6 µs in memory (an append is one
counter update and one insert in a transaction; the wide error is three short iterations), and
`http_publish_delivery` 51.2 ± 1.6 ms at 1 subscriber and 52.0 ± 4.0 ms at 100: the poll interval,
not the subscriber count, is the latency (the benchmark publishes right after the previous
delivery, just after a poll, so it sees nearly the whole interval; an unsynchronised publisher
averages half). Lower `poll-interval` trades database queries for latency.

WebSocket against SSE: the same within noise up to 100 subscribers; at 1000 the WebSocket leg
reads ≈3.7–3.9 ms against ≈2.1 ms for SSE. Building the frame by hand instead of through a
Jackson tree, and raising the per-topic merge prefetch from 1 to 32, each changed nothing at
1000 (3687 → 3924 → 3699 µs, all within each other's error), so the difference is not in the
gateway's operator chain: it sits in the WebSocket write path (one Netty frame encoder pass per
socket where SSE writes raw chunks) and in the 1000 client-side frame decoders sharing the
benchmark JVM. The `ws_` rows were measured in separate runs from the `http_` rows of the same
table (the `http_` 1 / 100 / 1000 rows in those runs read 195 / 519 / 2102 on MEMORY and
361 / 484 / 1908 on REDIS), so compare the two rows, not the two runs.

### GraphQL selection projection (ops/µs, proto → Map)

`GraphQlProjectionBenchmark`. Both conversions are Tier 2 compiled: the full one is the generated
`toMap`, the projected one the generated `toMap(message, projection)`, which converts only the
composite fields the query selected (scalars always convert). `tier3_fullMap` is the reflective
walker, for scale.

| | 3 items | 30 items |
| --- | --- | --- |
| `tier2_fullMap` (`selection-projection: false`, or a query selecting everything) | 1.24 | 0.21 |
| `tier3_fullMap` (the reflective walker, everything) | 0.58 | 0.17 |
| `projected_scalarsOnly` (query selects `id name`) | 4.9 | 5.2 |
| `projected_itemsSku` (query selects `id items { sku }`) | 1.4 | 0.21 |

Two runs averaged; this machine is noisy at these sizes (±30%), so read the shape, not the
decimals. **Projection is now never worse than the full conversion and pays exactly when it
prunes**: a query that leaves the composites out converts at a flat ≈5 ops/µs whatever the
message holds — 4× the full walk at 3 items, 25× at 30 — while a query that selects the big list
costs the same as converting everything. Before the compiled variant (the first version of this
benchmark) the projected path was the reflective walker: 1.7 ops/µs when pruning and *slower*
than the full conversion when the selection included the list. The reflective `projectToMap`
remains the fallback for descriptors without a compiled serializer.

### Streaming backpressure (µs per 100-message stream, in-process channel)

`StreamingBackpressureBenchmark`, `dispatchStreaming` (Tier 0 NDJSON items), 2026-10-06:

| Leg | µs/stream | alloc/stream |
| --- | --- | --- |
| `drainUnbounded` (`collectList` of the items as `byte[]`) | 201 | 0.39 MB |
| `drainFramedByCopy` (each item copied into a pooled buffer with its newline — the old NDJSON writer) | 217 | 0.41 MB |
| `drainFramedDirect` (`dispatchStreamingFrames`: each item transcoded straight into its pooled buffer) | 221 | 0.31 MB |

The same three legs before this series: 313 µs and 2.04 MB per 100 items — 20 KB per 440-byte
item. Nearly all of it was the raw response marshaller reading each message with
`InputStream.readAllBytes()`, which fills a 16 KiB scratch buffer per message before copying;
it now reads the known length into an array of that size. Writing each item straight into its
response buffer (`drainFramedDirect`) then drops the intermediate `byte[]` and its copy, 1 KB
per item here and the whole payload once over for a large one.

Flow control still costs nothing measurable and buffers nothing (`demandOneAtATime` and
`demandInBatchesOf16` track `drainUnbounded`): the dispatcher disables gRPC's auto-request and
forwards downstream demand, so a `request(1)` consumer makes the *server* wait. On a real
network the demand round trips would add latency per message for the slowest consumer shape;
the gateway-side cost of honouring it is zero.

### Structural well-known types (µs per message, 2026-10-06)

`WktTranscodeBenchmark`: a message holding a `Struct` (ten keys, a nested struct, a list), a
`ListValue`, a `Value` and a `FieldMask`.

| Leg | µs/op | alloc/op |
| --- | --- | --- |
| `tier0` — before, these four went through a `DynamicMessage` and `ProtoWkt` | 9.2 | 32 KB |
| `tier0` — now, read off the wire like every other field | 2.4 | 6 KB |
| `jsonFormat` — `DynamicMessage.parseFrom` + `JsonFormat` | 11.0 | 35 KB |

### Tier 0 bail and fallback (µs/op, unary REST path)

`Tier0FallbackBenchmark`:

| | clean wire | duplicated field (merge semantics) |
| --- | --- | --- |
| `restDispatch` (whole unary REST path) | 5.5 | 10.0 |
| `transcodeOnly` (the transcoder; `null` = bail) | 2.7 | 2.8 |

A bail costs the failed transcode plus a `DynamicMessage` parse plus the Tier 2 write: 4.5 µs
on top of the clean path, i.e. the request lands where a Tier 2 response would (the 5.3 µs Tier 2
leg above, minus the RPC floor). The detection itself is not early: the duplicate sits at the end
of the wire, so the transcoder scans the whole message before it can know, which is why
`transcodeOnly` costs the same either way. Rare in practice — it takes a producer that emits a
non-repeated field twice — and bounded when it happens.

### Federation batch loaders (µs per batch)

`FederationBatchLoaderBenchmark`, one fixture order returned per key:

| | 1 key | 10 keys | 100 keys |
| --- | --- | --- | --- |
| `grpcBatch` (`GrpcBatchLoader`, in-process unary `GetBatch`) | 24.5 | 66.7 | 506.4 |
| `restBatch` (`OpenApiBatchLoader`, loopback `GET /orders?ids=…`) | 132.5 | 212.9 | 922.0 |

Both scale with the batch (the response grows with it); the REST column carries the ≈75 µs
loopback socket the HTTP dispatch table above measured, so read the gap as an upper bound on the
transport difference. What matters for the DataLoader design is the per-key cost at 100 keys —
5.1 µs (gRPC) and 9.2 µs (REST) — against 24.5 / 132.5 µs for a batch of one: batching is
what makes an `_entities` request with a hundred representations cheaper than a hundred
field resolutions.

### Route registry at scale (ns/op)

`RouteRegistryBenchmark`, 20 routes per service, half of them templates (`…/{id}`):

| | 200 routes | 2 000 | 20 000 |
| --- | --- | --- | --- |
| `findByHttpKey_exact` | 31 | — | 42 |
| `findByHttpKey_template` | 235 | 301 | 262 |

Exact paths were always a map hit. Template paths were not: the registry matched every template
route's `PathPattern` in turn, so a templated request cost 1.6 µs at 200 routes, 12.5 µs at 2 000
and 125 µs at 20 000 — routing alone, before any dispatch. Templates are now bucketed by HTTP
method and the static prefix before their first variable, and a lookup walks the request path's
prefixes from longest to shortest: O(segments) map hits, flat in registry size, and a more
specific template beats a catch-all. `contextFor` and `findByGraphQlName` are map hits and were
already flat.

## Side by side with the other transcoders

`comparison/compare.sh` puts this gateway and the two transcoders [docs/comparison.md](../docs/comparison.md)
discusses in front of the same reflection-served `bench.Orders` backend, on one podman network, and runs
`oha` against each (10 s warm-up, then `DURATION` at `CONCURRENCY`, 30 s × 32 by default):

```bash
./gradlew :shiguchi-benchmarks:jmhJar
comparison/compare.sh            # needs podman; builds the Go image on first run
```

The numbers and what they mean are in [docs/comparison.md](../docs/comparison.md#measured-side-by-side).
`ComparisonBackend` and `ComparisonGateway` in `src/jmh/java` are the two JVM sides, run from
the JMH jar; `comparison/envoy.yaml` and `comparison/grpc-gateway-go/` are the other two.

## What the tiers are

- **Tier 0** — `WireJsonTranscoder`: protobuf wire bytes → JSON tokens directly, per-descriptor
  precompiled program. Used for REST responses of dynamic-only services.
- **Tier 1** — a generated writer over compiled stub classes (STATIC mode): generated parser +
  a JSON writer that reads the typed getters (Class-File API, Java 25+).
- **Tier 2** — generated per-descriptor serializers over DynamicMessage (Class-File API, Java 25+).
- **Tier 3** — `JsonFormat` / reflective walkers, the always-correct baseline.

`SerdeTierParityTest` in shiguchi-core is the contract that keeps every tier's JSON
identical; see [docs/transcoding.md](../docs/transcoding.md) for the full picture.

---

JMH microbenchmarks that validate the gateway's hot-path optimizations (the `P1`–`P4`
fixes). Each benchmark compares an **optimized** path against the **baseline** it replaced,
so the numbers are a regression guard, not just trivia.

## Quick start

Run the whole suite (slow — full warmup + measurement on every benchmark):

```bash
./gradlew :shiguchi-benchmarks:jmh
```

Results are written to `build/jmh-results.json` (also echoed to the console).

The HTTP-layer suites (`GatewayHttpBenchmark`, `GatewayGraphQlBenchmark`) also build on the Boot 4
line as `:shiguchi-benchmarks-boot4` (`spring-boot4/shiguchi-benchmarks`, the same
sources against the Boot 4 auto-configuration):

```bash
./gradlew :shiguchi-benchmarks-boot4:jmh -Pjmh.includes='GatewayHttpBenchmark'
java -jar spring-boot4/shiguchi-benchmarks/build/libs/*-jmh.jar GatewayHttpBenchmark.gateway_post -p profile=default
```

The `no-otel` profile names Boot 3 auto-configuration classes; on Boot 4 it excludes nothing and
reads as `default`.

### Run ONE benchmark

You almost never want the full suite while iterating. `-Pjmh.includes` takes a class name,
a `Class.method`, or any regex:

```bash
# one method
./gradlew :shiguchi-benchmarks:jmh -Pjmh.includes='SerdeBenchmark.tier2_toMap_flat'

# one class
./gradlew :shiguchi-benchmarks:jmh -Pjmh.includes='TimestampBenchmark'

# regex subset (all Tier-2 toMap variants)
./gradlew :shiguchi-benchmarks:jmh -Pjmh.includes='tier2_toMap'
```

PowerShell — same flag, quote with single quotes:

```powershell
.\gradlew.bat :shiguchi-benchmarks:jmh -Pjmh.includes='SerdeBenchmark.tier2_toMap_flat'
```

## What each benchmark proves

| Class | Hot path | Optimized vs baseline | Fix |
|---|---|---|---|
| `SerdeBenchmark` | proto ↔ Map / JSON | Tier-2 (generated) vs Tier-3 (reflective `ProtoMapConverter` / `JsonFormat`) | Tier-2 codegen |
| `TimestampBenchmark` | `Timestamp` → `OffsetDateTime` | static-cached `FieldDescriptor` vs `findFieldByName` per call | P1-B |
| `RouteRegistryBenchmark` | `contextFor` / `findByHttpKey` (exact and template) / `findByGraphQlName` | flat in registry size (200 → 20 000 routes); template lookup was O(templates) until bucketed by static prefix | P1-C, template index |
| `GrpcProxyBenchmark` | `lookupMethod` / descriptor resolve | cached `ServerMethodDefinition` vs rebuild per call | P1-D |
| `BaselineBenchmark` | nothing | JMH harness floor (`noop`, `consumeCPU`) | — |
| `AvroCodecBenchmark` | Avro bytes ⇄ `Map` | the Avro upstream codec vs Jackson on the same record | — (characterisation) |
| `GraphQlProjectionBenchmark` | proto → `Map` for GraphQL | compiled projected `toMap(msg, projection)` vs compiled full `toMap` | selection projection, Tier 2 |
| `StreamingBackpressureBenchmark` | server-streaming demand | `request(1)` vs unbounded: same time, same allocation | flow-controlled streaming bridge |
| `Tier0FallbackBenchmark` | Tier 0 bail | clean wire vs merge-semantics wire through the REST path | Tier 0 fallback contract |
| `FederationBatchLoaderBenchmark` | `_entities` / DataLoader batches | per-key cost at 1 / 10 / 100 keys, gRPC vs REST loader | batching |

Expected shape: **Tier-2 beats Tier-3** on throughput, and (under `-prof gc`, on by default)
shows a much lower `gc.alloc.rate.norm` — ideally near zero on the flat path.

## Parameterized payload size

`SerdeBenchmark` carries a JMH `@Param`:

```java
@Param({"10", "100", "1000"}) public int repeatedSize;
```

The `*_repeated` benchmarks rebuild a repeated-int64 message at each size, so a single run
produces a 3-row scaling curve per benchmark (10 / 100 / 1000 elements). This both prevents
the JIT from over-specializing on one message shape and shows how Tier-2 and Tier-3 diverge
as payloads grow. Non-repeated benchmarks also run once per size — the extra rows are
identical, ignore them or narrow with `-Pjmh.includes`.

## Reading the results

- Console table: `Score` is ops/time-unit (Throughput mode). Higher = faster.
- Time units differ by class on purpose: `SerdeBenchmark` / `GrpcProxyBenchmark` report
  **µs**; `TimestampBenchmark` / `RouteRegistryBenchmark` / `BaselineBenchmark` report **ns**
  (their ops are sub-microsecond — µs would lose resolution).
- Subtract the `BaselineBenchmark` floor to estimate harness overhead in any figure.
- GC columns (`gc.alloc.rate`, `gc.alloc.rate.norm`) come from `-prof gc`. `*.norm` is
  bytes allocated **per operation** — the cleanest allocation comparison between tiers.

### Visualize

Upload `build/jmh-results.json` to the JMH Visualizer for charts:
<https://jmh.morethan.io/>

## CI

`.github/workflows/bench.yml` runs the suite on demand only — *Run workflow* from the
Actions tab, with an optional `includes` regex (same semantics as `-Pjmh.includes`) for a
targeted run. Pick the branch to benchmark in the same dialog. There is no schedule and no
PR trigger: a full run is 20-30 hosted-runner minutes and the org is on GitHub's free plan.

Each run uploads the raw `jmh-results.json` as a workflow artifact (90 days). Runs dispatched
on `main` also append their scores to the orphan `benchmarks` branch under `jmh/`, which is
the comparison baseline; dispatch one there after merging a request-path change so the
baseline follows main. A benchmark that moves more than 20% the wrong way (lower throughput,
or higher time for time-mode benchmarks) leaves an alert comment on the PR or commit. The
alert does not fail the job: shared runners drift 20-40% between runs (`baseline_noop`, which
does nothing, has moved 1.2-1.4x between two runs of the same commit), so read an alert
against the `BaselineBenchmark` and `e2e_baseline_rpcOnly` rows first: if those moved by the
same ratio, it is the runner. A change that moves one hot path while the control rows stay
put is real. A gate that measures baseline and candidate on the same runner in the same job
is the sound fix and is on the backlog.

The `benchmarks` branch is created by the first `main` run. Never merge it, and do not
edit it by hand; delete it to reset the baseline.

## Notes

- `GrpcProxyBenchmark` opens a `ManagedChannel` to a dead port on purpose — the measured
  methods only need a channel reference, no RPC is issued.
- Iteration counts live in per-class `@Warmup` / `@Measurement` / `@Fork` annotations, not in
  `build.gradle.kts`. Edit the annotation on the class you're tuning.
