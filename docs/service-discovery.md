# Service discovery and load balancing

A backend usually runs as several instances. The gateway can find them through a Spring Cloud
`DiscoveryClient` (Eureka, Consul, Kubernetes, ...) or through DNS, and balances calls across
all of them on the client side: gRPC keeps one HTTP/2 connection per instance and `round_robin`
spreads RPCs over the ones that are up. No sidecar and no extra hop.

A runnable version of everything below:
[`samples/sample-discovery-gateway`](../samples/sample-discovery-gateway) — three backend
instances, a `DiscoveryClient` over them, and responses that say which instance answered, so the
balancing and the failover are visible without any external registry.

## Through Spring Cloud (`discovery-name`)

```yaml
shiguchi:
  services:
    - name: orders
      discovery-name: order-service     # the id the backend registered under
      mode: DYNAMIC
  client:
    discovery-refresh: 30s              # how often the instance list is re-read (default)
```

With a `DiscoveryClient` bean on the classpath the channel is built on `discovery:///order-service`
and the gateway's own gRPC name resolver (`DiscoveryNameResolverProvider`) does the rest:

- every registered instance is an address of the channel; calls round-robin across them;
- the list is re-read every `client.discovery-refresh` and immediately after a connection
  failure, so scale-outs, rolling restarts and evictions are followed without a schema refresh
  or a gateway restart;
- an instance that stops answering is skipped until it answers again; an empty registry fails
  calls with `UNAVAILABLE` (HTTP 503) rather than hanging;
- the registry port is used unless the instance carries a `grpc-port` metadata entry, for
  services that register their HTTP port but serve gRPC on another.

Schema discovery in `DYNAMIC` mode does not go through the balanced channel: the gateway reads
the schema from one pinned instance, so a rolling deploy with two versions running cannot mix
their schemas — see [Rolling deploys and versions](#rolling-deploys-and-versions).

### Eureka

```kotlin
implementation("org.springframework.cloud:spring-cloud-starter-netflix-eureka-client")
```

```yaml
eureka:
  client:
    service-url:
      defaultZone: http://eureka:8761/eureka/
```

On the backend, register the gRPC port when it differs from the HTTP one:

```yaml
eureka:
  instance:
    metadata-map:
      grpc-port: 9090
```

### Consul

```kotlin
implementation("org.springframework.cloud:spring-cloud-starter-consul-discovery")
```

```yaml
spring:
  cloud:
    consul:
      host: consul
      port: 8500
```

Backend side, `spring.cloud.consul.discovery.metadata.grpc-port: 9090` (Consul metadata
becomes `ServiceInstance.getMetadata()`); or register the gRPC listener itself as a separate
Consul service and point `discovery-name` at that.

### Kubernetes

```kotlin
implementation("org.springframework.cloud:spring-cloud-starter-kubernetes-client")
// or: spring-cloud-starter-kubernetes-fabric8
```

`discovery-name` is the Kubernetes Service name; the `DiscoveryClient` lists the Service's
endpoints (the pods), so the gateway balances across pods rather than letting kube-proxy pick
one per connection. The gateway's ServiceAccount needs to read `endpoints`/`endpointslices`
and `services` in the namespaces it watches (`spring.cloud.kubernetes.discovery.namespaces`
or `all-namespaces: true`). Port selection: a Service with one port needs nothing; with several,
name the gRPC port and set `spring.cloud.kubernetes.discovery.primary-port-name: grpc`, or put
`grpc-port` in the pod labels/annotations the discovery client maps to metadata
(`spring.cloud.kubernetes.discovery.metadata.add-labels` / `add-annotations`).

## Rolling deploys and versions

While a deploy rolls out, old and new instances run side by side and their schemas differ — the
new version adds a method, a field, a service. Calls are balanced across all of them, but the
schema the gateway serves has to come from one of them, and it must not change back and forth.

**Each instance publishes its version** as registry metadata, under `version` by default:

| Registry | Backend side |
| --- | --- |
| Eureka | `eureka.instance.metadata-map.version: ${APP_VERSION}` |
| Consul | `spring.cloud.consul.discovery.metadata.version: ${APP_VERSION}` |
| Kubernetes | a pod label `version: 1.10.0` (or `app.kubernetes.io/version`) with `spring.cloud.kubernetes.discovery.metadata.add-labels: true` on the gateway |

Usually the deploy pipeline sets it from the image tag, so the service needs no code.

**The gateway reads the schema from one instance** of the version `versions.schema-from` picks,
over a direct connection of its own, and the same instance on every refresh while it lives:

```yaml
shiguchi:
  services:
    - name: catalog
      discovery-name: catalog
      versions:
        metadata-key: version          # default
        schema-from: OLDEST            # default; NEWEST, or an exact version such as 1.10.0
```

- `OLDEST` exposes only what every running instance can answer. A method added in the new
  version gets no route until the last old instance is gone, so no call is ever sent to an
  instance that answers `UNIMPLEMENTED`. The next refresh after the rollout picks the new schema
  up; `POST /actuator/gateway-refresh` does it at once.
- `NEWEST` exposes the additions as soon as one new instance is up — for deploys that only add,
  and whose callers can retry an occasional `UNIMPLEMENTED` from an old instance.
- An exact version serves that version's schema while one of its instances runs, and the oldest
  otherwise.

Versions compare part by part, numbers numerically: `1.10.0` is newer than `1.9.2`, `2.0.0-rc.1`
older than `2.0.0`, a leading `v` is ignored. An instance without the metadata counts as the
oldest. Without any version metadata the schema is still read from one instance (the lowest
address), which keeps a single load from mixing instances.

`GET /actuator/gateway-routes/{service}` shows where the schema came from and how far a rollout
has got:

```json
{ "name": "catalog", "schemaVersion": "1.4.0", "schemaInstance": "10.0.0.3:9090",
  "instanceVersions": { "1.4.0": 2, "1.10.0": 1 }, ... }
```

### Moving traffic between versions

By default every instance gets an equal share of calls, whatever its version. `versions.traffic`
splits calls by version instead, so a new version can take 5 %, then 25 %, then all of them, while
the old one keeps running until nothing reaches it:

```yaml
shiguchi:
  services:
    - name: catalog
      discovery-name: catalog
      versions:
        traffic:
          - { version: 1.4.0,  weight: 95 }
          - { version: 1.10.0, weight: 5 }
        sticky-header: x-principal       # optional: one caller stays on one version
        preview-header: x-backend-version  # optional: a caller can ask for a version
```

- Weights are relative shares, not percentages; within a version, calls are balanced across its
  instances by `load-balancing-policy`. A version running but not listed gets no calls (weight 0).
- **`sticky-header`** hashes the header's value to a version, so one user sees one version for
  the whole rollout instead of flipping per call. Versions are laid out oldest to newest, so as the
  new version's weight rises callers only ever move onto it, never back. A missing value, or
  `anonymous` (the principal of an unauthenticated call), is spread per call. `x-principal` is the
  gateway's own authenticated principal, which a caller cannot set.
- **`preview-header`** sends the call to the version it names (`1.10.0`, or `v1.10` — versions
  compare as above) while that version has an instance, even at weight 0: testers reach the new
  version before any user does. A version with no instance falls back to the split. Answers the
  response cache keeps are keyed by the header, and cached responses carry it in `Vary`.
- Both headers are forwarded to the backend as metadata.
- **When a weighted version is not up**, calls wait while it is still connecting (a new instance
  starting), and go to another running version once it has failed, so a split naming a version
  that is not deployed degrades to plain balancing rather than to errors.
- The schema is chosen among the versions that receive calls: with the split above `OLDEST` means
  `1.4.0`; at `1.4.0: 0`, it is `1.10.0`, even while the old instances still run.
- With `outlier-detection` on, ejection applies across all instances, around the version split.

`GET /actuator/gateway-routes/{service}` shows `traffic`, `stickyHeader` and `previewHeader` next
to `instanceVersions`.

### Changing the split at runtime, and knowing when a version is drained

The `gateway-traffic` actuator endpoint moves calls without a restart, on the connections already
open, and shows what each version is still doing:

```yaml
management:
  endpoints.web.exposure.include: health,gateway-traffic
```

```console
$ curl -s localhost:8080/actuator/gateway-traffic/catalog
{ "service": "catalog",
  "weights": { "1.4.0": 95, "1.10.0": 5 }, "configuredWeights": { "1.4.0": 95, "1.10.0": 5 },
  "overridden": false,
  "versions": [
    { "version": "1.4.0",  "weight": 95, "instances": 3, "state": "SERVING",
      "inFlight": 12, "calls": 48210, "failures": 3, "lastCallAt": "2026-10-01T18:02:11Z" },
    { "version": "1.10.0", "weight": 5,  "instances": 1, "state": "SERVING",
      "inFlight": 1,  "calls": 2533,  "failures": 0, "lastCallAt": "2026-10-01T18:02:11Z" } ],
  "safeToStop": [] }

$ curl -s -X POST localhost:8080/actuator/gateway-traffic/catalog \
    -H 'Authorization: Bearer …' -H 'Content-Type: application/json' \
    -d '{"weights": "1.4.0=0,1.10.0=100"}'
```

- `POST` takes the new split as `version=weight` pairs and applies it at once; sticky callers move
  forward exactly as with a configured change. An invalid split (a negative weight, all zero, a
  version twice) is a 400 and changes nothing. `DELETE` puts the configured split back.
- Writes need an authenticated caller — even under `PERMIT_ALL`, like `gateway-refresh` — since
  they move production traffic; reads are open like `gateway-routes`. Keep the endpoint off
  public exposure either way.
- Each version's `state`: `SERVING` (a share of the split and a registered instance), `MISSING`
  (a share but no instance, so its calls go elsewhere), `DRAINING` (no share, calls still in
  flight) or `DRAINED` (no share, nothing in flight). `safeToStop` lists the drained versions that
  still have instances: stopping them fails no call through this gateway.
- `inFlight` counts calls open right now, streams included, so a long server stream keeps its
  version `DRAINING` until it ends. `calls` and `failures` (non-`OK` statuses) count since the
  gateway started, each attempt of a retried call once. A preview call can still reach a drained
  version; `lastCallAt` shows when one last did.
- The schema follows the live split: once a version gets no share, the next refresh (or
  `POST /actuator/gateway-refresh`) reads the schema from the versions that do.
- The change lives in this gateway process: with several replicas, post to each one, and make the
  final split the configured one so a restart keeps it. A restart starts from `versions.traffic`.

A rollout is then: deploy the new version next to the old one (weight 0, reachable through the
preview header), post a small weight, watch its `failures`, raise it step by step, post
`old=0`, wait for `safeToStop` to name the old version, and stop it. Or let the gateway do it:

### Automated progressive rollout

With `versions.rollout`, the gateway runs those steps itself and rolls back on its own when the
new version misbehaves:

```yaml
shiguchi:
  services:
    - name: catalog
      discovery-name: catalog
      versions:
        traffic:
          - { version: 1.4.0, weight: 100 }  # optional: pins the current version from the first call
        rollout:
          enabled: true
          steps: [5, 25, 50, 100]     # the new version's share at each step, in percent
          interval: 2m                # each step lasts at least this long
          min-calls: 100              # calls the new version must finish in a step before it is judged
          max-error-rate: 0.01        # at most 1 % of them ending in an error code
          max-p99: 300ms              # optional: p99 of its unary calls
          # error-codes: [UNKNOWN, DEADLINE_EXCEEDED, UNIMPLEMENTED, INTERNAL, UNAVAILABLE, DATA_LOSS]
```

- **It starts** when one version takes every call and a newer one registers — the deploy
  pipeline only has to start the new instances. The new version gets the first step's share.
- **Each step is judged on the new version's own calls since the step began.** As soon as
  `min-calls` have finished, an error rate above `max-error-rate` — or a unary p99 above
  `max-p99` — rolls back at once: every call goes back to the old version, and that version is not
  tried again (a newer one would be). Within the limits, the next step starts once `interval` has
  passed. Too few calls holds the step. Passing the last step (`100`) completes the rollout; the old
  version then takes nothing, drains, and shows in `safeToStop`.
- **Errors** are calls ending in one of `error-codes`: server and deploy problems, not a caller's
  `NOT_FOUND` or `INVALID_ARGUMENT`. `UNIMPLEMENTED` catches a new version missing a method. Set
  `max-error-rate` above the old version's usual rate — `GET /actuator/gateway-traffic` shows
  each version's `failures`.
- **The p99** is measured on unary calls only, within 25 % (latency buckets): a stream's duration
  is its length, not how fast the version answers.
- `GET /actuator/gateway-traffic/{service}` shows the rollout: `phase` (`WATCHING`, `ROLLING`,
  `PAUSED`, `ROLLED_BACK`), `stable`, `candidate`, the current `step`, the step's `window` (calls,
  errors, error rate, p99) and the `lastDecision` with its reason. Each decision is logged too.
- **Operators can step in** with `POST /actuator/gateway-traffic/{service}/{action}` (authenticated):
  `pause`, `resume` (continue a paused rollout from its step, with a fresh window, or retry a
  rolled-back version from the first step), `abort` (roll back now) or `promote` (every call to
  the new version now). Changing the weights through the endpoint pauses a running rollout.
- **Each gateway replica runs its own rollout** on the calls it routes: replicas step at about the
  same pace, and one replica seeing errors rolls back for its own callers. A restarted gateway
  starts a pending rollout again from the first step. Once a rollout completes, set
  `versions.traffic` to the new version so a restart does not begin from the old one.

Pinning needs instance metadata, so it applies to `discovery-name` services. A `target` such as a
headless-Service DNS name has no metadata; there the schema is read over the balanced channel, and
every instance should serve the same schema.

## Without Spring Cloud (`target`)

Any gRPC target string works, with gRPC's own resolvers:

```yaml
shiguchi:
  services:
    - name: inventory
      target: dns:///inventory-headless.shop.svc.cluster.local:9090
      mode: DYNAMIC
```

A **headless** Kubernetes Service (`clusterIP: None`) resolves to every ready pod's address;
gRPC's DNS resolver returns them all and `round_robin` balances across them, re-resolving on
connection failure and on its own ~30 s cadence. A normal ClusterIP Service resolves to one
virtual IP and gRPC would hold a single connection to whichever pod kube-proxy chose — use the
headless form. `xds:///name` hands resolution and balancing to an xDS control plane (Istio,
gRPC-xDS) when `grpc-xds` is on the classpath.

`target` wins over `host`/`port` and `discovery-name` when several are set.

## Policy and tuning

| Property | Default | Meaning |
| --- | --- | --- |
| `services[].load-balancing-policy` | `round_robin` for `discovery-name` and `target`; gRPC's `pick_first` for `host`/`port` | Any policy gRPC knows (`round_robin`, `pick_first`, `weighted_round_robin` with xDS, ...) |
| `client.discovery-refresh` | `30s` | Re-read cadence for `discovery-name` channels; minimum 100 ms |
| `client.keep-alive-time` / `keep-alive-timeout` | `30s` / `5s` | Detects a dead instance behind a silent NAT or LB before the next call fails |
| `client.idle-timeout` | `0` (gRPC default) | Drop idle transports; the next call re-resolves and reconnects |

Per-connection tuning, interceptors (retries, circuit breakers, hedging) and anything the
properties do not expose go through a `GrpcChannelBuilderCustomizer` bean, which runs on every
channel after the gateway's own configuration — see
[configuration.md](configuration.md#extension-points-beans).

## Custom resolution (`GrpcServiceAddressResolver`)

Without a `DiscoveryClient` bean, a `discovery-name` is handed to any `GrpcServiceAddressResolver`
beans in `@Order` order; the first non-empty answer becomes the channel's single address. That is
the hook for a config service, DNS SRV or a home-grown registry when all you need is one address.
For balancing across several, register a gRPC `NameResolverProvider` for your own scheme and use
it through `target:`.

## Observability

Every resolution change is logged at INFO with the instance count and addresses
(`Resolved 'order-service' via discovery: 3 instance(s) [...]`), an empty registry at WARN.
Per-instance call metrics are not split by address; the gRPC channel metrics from Micrometer
(`grpc.client.*`) are per service.
