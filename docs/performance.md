# Performance tuning

What is worth changing when latency or throughput matters, in the order it pays off, with the
measurement behind each. Defaults are chosen for operability; none of these is needed to run
correctly. Numbers come from [`shiguchi-benchmarks`](../shiguchi-benchmarks/README.md) (JMH, one
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
| `shiguchi.graphql.response-cache.enabled: true` | A repeated query is answered from memory. By far the largest gain: 17 600 req/s uncached vs 138 600 req/s cached for the Rust gateway, and the same shape applies here. | Staleness up to the method's `cache_ttl_seconds`. See [caching.md](caching.md#graphql-response-cache). |
| `management.metrics.dgs-graphql.instrumentation.enabled: false` and `management.observations.enable.graphql.datafetcher: false` | Drops the per-field timer and observation. About 15-20% of a GraphQL request (`graphql_list_100` 1 668 to 1 422 us), inside wide error bars. | No per-resolver timings. Request-level `graphql.request` and `gql.query` stay. |
| `shiguchi.graphql.document-cache.enabled: true` | Skips parsing and validation of a repeated query text. Measured +4% (16 300 to 17 000 req/s), so small. | Memory for up to `max-entries` documents. See [caching.md](caching.md#graphql-document-cache-and-persisted-queries). |
| `shiguchi.graphql.selection-projection: true` (default) | Converts only the selected subtrees of a response. Leave it on: projection is never slower than the full conversion. | None. |
| Enable the [JVM tuning and AOT cache](deployment.md) | Faster start and lower warm-up. | Cache compatibility rules. |

Things that look like switches but are not worth touching:

- `GraphQlLoggingFilter` returns straight to the chain unless its logger is at TRACE.
- Selection depth and complexity limits only add an instrumentation when configured.

## Measuring

- `shiguchi-benchmarks` holds the JMH benchmarks; the GraphQL ones are `GatewayGraphQlBenchmark`
  and `GraphQlProjectionBenchmark`.
- For CPU and allocation, run with `-prof jfr` and read the recording with
  `jfr print --events jdk.ExecutionSample`. The benchmarks README has the attribution table.
- The side-by-side run against the Rust gateway is `comparison/lattice/run.sh`.
