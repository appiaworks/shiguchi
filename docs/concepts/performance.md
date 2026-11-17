# Performance tuning

What is worth changing when latency or throughput matters, in the order it pays off, with the
measurement behind each. Defaults are chosen for operability; none of these is needed to run
correctly. Numbers come from [`shiguchi-benchmarks`](../../shiguchi-benchmarks/README.md) (JMH, one
machine), so read them as directions, not promises. Measure on your own schema before you keep a
change.

## Where the time goes

- **REST routes**: the serde tiers are done. What is left is Reactor, Micrometer and WebFlux
  overhead spread thin, with no single hotspot.
- **GraphQL**: the graphql-java engine dominates. A one-field query is the HTTP stack plus one
  engine pass; a list of 100 objects is mostly engine work (about 59% of CPU, 73% of allocation).
  The gateway's own share (descriptor lookup, building the `Map` tree) is under a tenth.
- **Against a Rust gateway**: on an identical schema, Shiguchi's uncached GraphQL runs at about
  0.92x the throughput of Protocol-Lattice's `grpc_graphql_gateway` (16 300 vs 17 600 req/s).
  See [comparison.md](comparison.md#protocol-lattice-grpc_graphql_gateway-rust).

## What to change

| Setting | Effect | Cost |
| --- | --- | --- |
| `shiguchi.graphql.response-cache.enabled: true` | A repeated query is answered from memory. By far the largest gain: 17 600 req/s uncached vs 138 600 req/s cached for the Rust gateway, and the same shape applies here. | Staleness up to the method's `cache_ttl_seconds`. See [caching.md](../guides/caching.md#graphql-response-cache). |
| `management.metrics.dgs-graphql.instrumentation.enabled: false` and `management.observations.enable.graphql.datafetcher: false` | Drops the per-field timer and observation. About 15-20% of a GraphQL request (`graphql_list_100` 1 668 to 1 422 us), inside wide error bars. | No per-resolver timings. Request-level `graphql.request` and `gql.query` stay. |
| `shiguchi.graphql.document-cache.enabled: true` | Skips parsing and validation of a repeated query text. Measured +4% (16 300 to 17 000 req/s), so small. | Memory for up to `max-entries` documents. See [caching.md](../guides/caching.md#graphql-document-cache-and-persisted-queries). |
| `shiguchi.graphql.selection-projection: true` (default) | Converts only the selected subtrees of a response. Leave it on: projection is never slower than the full conversion. | None. |
| Enable the [JVM tuning and AOT cache](../operations/deployment.md) | Faster start and lower warm-up. | Cache compatibility rules. |

Things that look like switches but are not worth touching:

- `GraphQlLoggingFilter` returns straight to the chain unless its logger is at TRACE.
- Selection depth and complexity limits only add an instrumentation when configured.

## Relationship batching

Relationship fields (`resolves_type`, `resolves_many`, Federation `_entities`) go through one
batching pipeline: de-duplicate the keys, optional cross-request cache, split by `max-batch-size`
with at most `max-concurrent-batches` calls in flight. Compared with the code before the pipeline
(JMH, same machine, error bars apply):

- **No regression on the other legs.** Single query -0.8%, `graphql_list_100` -1.8%,
  `rest_list_100` +0.9% (gRPC backend) and +1.3% (HTTP backend), `rest_single` -0.2%, Federation
  gRPC batch with 100 keys -1.5%. All inside the noise of a run.
- **`graphql_nested_100` is 12.7% faster** (same-session A/B; an earlier full run measured about
  35%): a list of 100 with a nested relationship now batches the nested level into one
  upstream call instead of one per parent.
- **One-to-many (`group_by`) against one call per parent.** At 1 ms of backend latency the
  grouped loader is only 1.26x faster on wall time for 100 parents with 10 children each, and 1.17x
  for 1000 parents with 10 children. The benchmark stub's per-parent delays overlap, so the
  per-parent version is not as slow as a real backend with limited concurrency would make it. The
  gain that holds regardless of the stub is upstream load: 1 backend call against 1000, and about
  half the allocation. Expect the wall-clock gap to grow with backend latency and with how few
  calls the backend can serve at once, and measure it on your own backend.

## Measuring

- `shiguchi-benchmarks` holds the JMH benchmarks; the GraphQL ones are `GatewayGraphQlBenchmark`
  and `GraphQlProjectionBenchmark`.
- For CPU and allocation, run with `-prof jfr` and read the recording with
  `jfr print --events jdk.ExecutionSample`. The benchmarks README has the attribution table.
- The side-by-side run against the Rust gateway is `comparison/lattice/run.sh`.
