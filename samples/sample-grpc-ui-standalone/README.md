# sample-grpc-ui-standalone

Just the embedded gRPC web console (`docs/ui.md`), nothing else — no REST routes, no GraphQL, no
gRPC proxy. Depends only on `shiguchi-ui-spring-boot3-starter`. Useful as a developer-facing
try-it tool that sits next to a service without standing up a full gateway.

There is no backend bundled with this sample. Point `shiguchi.services[0]` at any gRPC
service with reflection enabled; the console lists whatever that backend exposes. The examples
below assume the `demo.library.Library` service used in `docs/ui.md` (`GetBook`, `ListBooks`,
...) — the console works identically against your own service, it will just show different
services/methods in the tree.

## Run

```bash
./gradlew publishToMavenLocal          # once, from the repo root
cd samples
./gradlew :sample-grpc-ui-standalone:bootRun
```

## Ports

| Port | Surface |
| --- | --- |
| `8080` | HTTP: `/grpc-ui` console, `/actuator/health`, `/actuator/info` |
| `9090` | Expected backend gRPC port (`shiguchi.services[0].port`) — not served by this sample |

## Try

Open <http://localhost:8080/grpc-ui> in a browser:

1. The **service tree** on the left lists every service/method reflection discovered on the
   backend — filter by typing, or regroup by service/namespace/host with the group toggles.
2. Select `demo.library.Library.GetBook` (or whatever your backend exposes) to open the invoke
   view. The binding header shows the call type and, if the method carries a `google.api.http`
   option, the derived REST binding — informational only, since this sample serves no REST
   route.
3. Type a request in the **REQUEST JSON** tab (or use the **Form** tab), e.g. `{"id": 1}`, and
   invoke. The response, gRPC status and call latency appear below.
4. Try a streaming method (if the backend has one) to see the incremental response view instead
   of a single JSON blob.

No REST/GraphQL curl examples apply here — the console talks gRPC directly to the backend, and
that is the only surface this sample serves.

## Configuration

```yaml
shiguchi:
  services:
    - name: my-service
      host: localhost
      port: 9090
      mode: DYNAMIC        # discovers services/methods via reflection for the console tree

  # REST/GraphQL/gRPC-proxy all disabled — UI only
  openapi:
    enabled: false
  graphql:
    enabled: false
  grpc:
    enabled: false

  ui:
    enabled: true          # this sample exists to demonstrate the console, so it is turned on
    path: /grpc-ui         # base path
    require-auth: false    # the console can invoke ANY wired method — see the warning below

  refresh:
    enabled: false         # re-discovery is manual (restart) in this sample
```

> [!WARNING]
> The console can invoke any wired gRPC method. It ships disabled by default
> (`shiguchi.ui.enabled=false`) precisely because of that; this sample turns it on to
> demonstrate it. Run it only on a trusted network, or set `require-auth: true` (needs an OAuth2
> JWT resource server configured) before exposing it anywhere else. See `../../SECURITY.md`.

## See also

- [docs/ui.md](../../docs/ui.md) — full walkthrough of the service tree, invoke view, auth and
  metadata handling, and streaming responses, with screenshots.
- [docs/configuration.md](../../docs/configuration.md) — `shiguchi.ui.*` reference and the
  `security.surfaces.ui.require-auth` override.
