# sample-discovery-gateway

A REST gateway in front of a gRPC service that runs as **three instances**, found through Spring
Cloud service discovery and balanced across client-side with `round_robin`. No sidecar, no extra
hop, and nothing external to start — the instances and the registry both run in this JVM.

Every response carries `servedBy`, the id of the instance that answered, so the balancing is
something you can watch rather than something this page claims.

| Piece | Where | What it shows |
| --- | --- | --- |
| Discovery | `application.yml` → `services[0].discovery-name` | `catalog-service` instead of a host and port: ask the registry |
| Policy | `services[0].load-balancing-policy` | `round_robin` (default for discovery channels); `pick-first` profile for the other one |
| Refresh | `client.discovery-refresh: 2s` | how often the instance list is re-read — deliberately short here |
| The fleet | `CatalogFleet` | three real gRPC servers on ephemeral ports, standing in for pods |
| The registry | `InProcessDiscoveryClient` | a `DiscoveryClient` over the fleet, standing in for Eureka/Consul/Kubernetes |
| State | `/actuator/gateway-routes`, `/actuator/metrics/gateway.discovery.instances` | the discovered target, and the instance count as a gauge |

## Run it

```bash
./gradlew publishToMavenLocal          # from the repo root, once
cd samples && ../gradlew :sample-discovery-gateway:bootRun
```

Repeat one request and watch it rotate:

```bash
for i in $(seq 1 6); do curl -s localhost:8080/v1/products | jq -r .servedBy; done
```

```
catalog-3
catalog-1
catalog-2
catalog-3
catalog-1
catalog-2
```

That is gRPC's `round_robin` over three subchannels, one per discovered instance. The gateway
holds an HTTP/2 connection to each and spreads RPCs over the ones that are up. Which instance
gets the first call depends on the order the connections became ready, so the cycle can start
anywhere — what matters is that it is a cycle over all three.

## Kill an instance

```bash
curl -s -X DELETE localhost:8080/fleet/one | jq
```

```json
{ "stopped": "catalog-3", "remaining": 2 }
```

Keep the loop running: within one refresh interval the calls settle onto `catalog-1` and
`catalog-2`. Nothing was restarted and no schema was reloaded — the name resolver re-read the
registry, the channel dropped the address, and the policy balanced over what was left. That is
the whole reason for pointing at a registry instead of a host.

The instance count is a gauge, so the same thing is visible on a dashboard:

```bash
curl -s 'localhost:8080/actuator/metrics/gateway.discovery.instances?tag=service:catalog-service' | jq '.measurements[0].value'
```

## The other policy

```bash
../gradlew :sample-discovery-gateway:bootRun --args='--spring.profiles.active=pick-first'
```

Now `servedBy` does not change: `pick_first` sends everything to the first address that
connects and only moves when that connection breaks (try `DELETE /fleet/one`). Discovery still
finds all three — only the balancing differs. Worth choosing when a backend holds
per-connection state, or when something downstream already balances and a second layer would
only fragment connections.

## Pointing it at a real registry

`InProcessDiscoveryClient` exists so the sample needs no infrastructure. **The gateway
configuration does not change** when you replace it: `discovery-name: catalog-service` means
"ask the registry", whichever registry is on the classpath. Delete that class, add one
dependency and the registry's own properties.

### Eureka

```kotlin
implementation("org.springframework.cloud:spring-cloud-starter-netflix-eureka-client")
```

```yaml
eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka/
```

On the **backend**, register the gRPC port when it differs from the HTTP one — the gateway reads
`grpc-port` from the instance metadata and falls back to the registry port:

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
      host: localhost
      port: 8500
```

Backend side: `spring.cloud.consul.discovery.metadata.grpc-port: 9090`, or register the gRPC
listener as its own Consul service and point `discovery-name` at that.

### Kubernetes

```kotlin
implementation("org.springframework.cloud:spring-cloud-starter-kubernetes-client")
```

`discovery-name` is the Service name; the client lists the Service's endpoints, so the gateway
balances across **pods** rather than letting kube-proxy pick one pod per connection. The
gateway's ServiceAccount needs to read `endpoints`/`endpointslices` and `services`. With several
ports on the Service, name the gRPC one and set
`spring.cloud.kubernetes.discovery.primary-port-name: grpc`.

Without Spring Cloud at all, a headless Service works through gRPC's own DNS resolver — no
`DiscoveryClient` needed:

```yaml
shiguchi:
  services:
    - name: catalog
      target: dns:///catalog-headless.shop.svc.cluster.local:9090
      mode: DYNAMIC
      load-balancing-policy: round_robin
```

A **headless** Service (`clusterIP: None`) resolves to every ready pod. A normal ClusterIP
Service resolves to one virtual IP, and gRPC would hold a single connection to whichever pod
kube-proxy chose — so use the headless form.

## What to keep from this sample

- `discovery-refresh: 2s` is for watching. Every tick is a registry call, per service, per
  gateway instance; **30 s (the default) is right in production** — the resolver also refreshes
  immediately on a connection failure, so a dead instance is dropped without waiting for the tick.
- Schema discovery (`DYNAMIC` reflection) goes over the same balanced channel to whichever
  instance answers, so every instance must serve the same API version. A rolling deployment
  already requires that.
- An empty registry fails calls with `UNAVAILABLE` (HTTP 503) rather than hanging, and is logged
  at WARN.

→ [docs/service-discovery.md](../../docs/service-discovery.md)
