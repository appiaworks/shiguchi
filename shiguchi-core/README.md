# shiguchi-core

The engine: schema discovery, routing, and the tiered dynamic serde that turns protobuf
into JSON/Maps and back without code generation.

Key pieces:

- `serde/` — the four tiers (`WireJsonTranscoder`, `StaticSerdeDetector`,
  `DynamicSerializerFactory` + generated serializers, `JsonFormat` fallback),
  `SerdeSelector` routing, `ProtoWkt` well-known-type JSON mapping,
  `ProtoMapConverter` Map walkers, selection `ProjectionNode`.
- `grpc/` — `GrpcDescriptorCache` (per-method gRPC descriptors, dynamic / typed / raw
  response marshallers), zero-copy `DynamicMessageMarshaller`.
- `routing/` — `GrpcRouteRegistry`, `RouteDefinition`, `ServiceEndpointContext`, and
  `ConventionArgMapper` (maps the paging/sorting arguments every surface accepts onto the
  `page_request` / `sort_fields` convention fields —
  see [docs/projections-paging-sorting.md](../docs/projections-paging-sorting.md)).
- `reflection/` — gRPC server-reflection client with transitive dependency resolution.
- `config/GatewayProperties` — all `shiguchi.*` configuration
  (see [docs/configuration.md](../docs/configuration.md)).

Correctness contract: every serde tier must produce JSON tree-equal to
`JsonFormat.printer().includingDefaultValueFields()` — enforced by `SerdeTierParityTest`.
Architecture and tier details: [docs/transcoding.md](../docs/transcoding.md).
