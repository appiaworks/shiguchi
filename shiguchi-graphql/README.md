# shiguchi-graphql

GraphQL surface (Netflix DGS): generates the SDL schema from proto descriptors at startup
and dispatches queries/mutations/subscriptions to gRPC backends via the zero-JSON Map path.

Highlights:

- **Schema generation** — `GraphQlSchemaBuilder` walks service descriptors; `(shiguchi.api.graphql)`
  method options and `(shiguchi.api.graphql_field)` field options (plus YAML `fieldOverrides`) control
  operation type, naming, federation keys, plucks, and more
  (reference: [docs/graphql-annotations.md](../docs/graphql-annotations.md)).
- **Relay** — paged responses (`page_info` + a repeated items field) automatically become
  Connection/Edge types with synthetic cursors. The `first` / `after` / `sort` arguments are
  mapped onto the `page_request` / `sort_fields` convention fields by the shared
  `ConventionArgMapper`, so REST and GraphQL send backends the same request shape
  (reference: [docs/projections-paging-sorting.md](../docs/projections-paging-sorting.md)).
- **Projection pushdown** — the selection set becomes both a `google.protobuf.FieldMask`
  on the request (backend computes less) and a conversion projection on the response
  (gateway converts less). Opt out: `shiguchi.graphql.selection-projection: false`.
- **Subscriptions** — server-streaming methods exposed as GraphQL subscriptions with
  demand-driven gRPC flow control.
- **Federation** — Apollo Federation `@key` support via `batch_key` annotations, with
  batched entity loaders.
