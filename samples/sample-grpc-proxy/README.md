# sample-grpc-proxy

A pass-through gRPC proxy: one endpoint that forwards calls to multiple downstream gRPC
services, routed by service fully-qualified name. Clients connect to the proxy exactly as they
would to any gRPC server — reflection on the proxy itself lists every method of every wired
backend. No REST, no GraphQL, no OpenAPI; only `shiguchi-grpc-proxy`.

There is no backend bundled with this sample. `application.yml` wires two backend slots,
`service-a` on `:9090` and `service-b` on `:9091` — point them at any two gRPC services with
reflection enabled (or edit down to one) and the proxy will merge their reflection responses.

## Run

```bash
./gradlew publishToMavenLocal          # once, from the repo root
cd samples
./gradlew :sample-grpc-proxy:bootRun
```

## Ports

| Port | Surface |
| --- | --- |
| `8080` | HTTP: `/actuator/health`, `/actuator/info` only — no REST routes in this sample |
| `9191` | The consolidated gRPC proxy (`shiguchi.grpc.port`) — connect your gRPC client here |
| `9090` / `9091` | Expected backend gRPC ports (`service-a` / `service-b`) — not served by this sample |

## Try

```bash
# List every service the proxy has discovered across both backends
grpcurl -plaintext localhost:9191 list

# List the methods of one discovered service
grpcurl -plaintext localhost:9191 list demo.library.Library

# Describe a method's request/response shape
grpcurl -plaintext localhost:9191 describe demo.library.Library.GetBook

# Invoke it through the proxy — the call is routed to whichever backend serves that FQN
grpcurl -plaintext -d '{"id": 1}' localhost:9191 demo.library.Library/GetBook

# Server reflection is also queryable directly
grpcurl -plaintext localhost:9191 grpc.reflection.v1alpha.ServerReflection/ServerReflectionInfo
```

## Configuration

```yaml
shiguchi:
  services:
    - name: service-a
      host: localhost
      port: 9090
      mode: DYNAMIC     # discovered via reflection; routed by service FQN, not by this name
    - name: service-b
      host: localhost
      port: 9091
      mode: DYNAMIC

  openapi:
    enabled: false        # no REST/OpenAPI surface in this sample

  graphql:
    enabled: false        # no GraphQL surface in this sample

  grpc:
    enabled: true         # activates the consolidated gRPC proxy server
    port: 9191            # clients connect here, not to service-a/service-b directly
    rate-limit:
      enabled: false      # per-method token-bucket limiting on the proxy, off by default

  ui:
    enabled: false          # no embedded console

  refresh:
    enabled: true          # re-run reflection periodically, so new methods/backends are picked up
    interval-seconds: 60
```

Two backends declaring the *same* proto service would collide at the proxy (whichever answers
reflection last wins that FQN); `docs/service-discovery.md` covers routing many instances of one
backend instead of two distinct ones.

## See also

- [docs/configuration.md](../../docs/configuration.md) — `shiguchi.grpc.*` and per-service
  options (`exclude-methods` does **not** filter this proxy, per the table there).
- [docs/service-discovery.md](../../docs/service-discovery.md) — load-balancing across instances
  of a single backend, as an alternative to two fixed `host:port` entries.
