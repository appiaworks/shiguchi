# shiguchi-grpc-proxy

An embedded gRPC server (default `:9090`, the samples use `:9191`; enable with `shiguchi.grpc.enabled=true`)
that fronts all configured backends for native gRPC clients:

- **Transparent proxy** — a fallback handler registry bridges `ServerCall ⇄ ClientCall`
  for all four call types with per-message flow-control propagation and unchanged
  metadata. Raw byte passthrough; no parsing.
- **Merged server reflection** — aggregates every backend's schema so grpcurl, Postman,
  and grpcui see one combined API.

Method definitions are cached per context; nothing is materialised on the data path.
