# sample-static-schema

A gateway in front of a backend that **does not serve gRPC reflection** — and the two ways to
give it a schema anyway.

Every other sample uses `mode: DYNAMIC`, where the gateway asks the backend what it serves. That
is the right default, but it is not always available: reflection may be disabled by policy, the
backend may run an older gRPC library, or it may simply not be yours to change. The schema then
has to come from somewhere else, and there are exactly two somewhere-elses.

| Profile | Mode | The gateway reads | Needs |
| --- | --- | --- | --- |
| default | `STATIC` | the generated `*Grpc` classes on its own classpath | the backend's compiled stubs as a dependency |
| `pb` | `PB_FILE` | a `FileDescriptorSet` produced by `protoc` | a `.pb` file on disk |

Same backend, same two routes, same JSON. Only the way the gateway learned about them differs —
which is exactly what the test asserts.

This is also the only sample that compiles real protos (`src/main/proto/catalog/v1/catalog.proto`),
because `STATIC` mode has nothing to read otherwise. That makes it the place to look for the
protobuf Gradle plugin setup too.

## Run it — STATIC

```bash
./gradlew publishToMavenLocal          # from the repo root, once
cd samples && ../gradlew :sample-static-schema:bootRun
```

```bash
curl -s localhost:8080/v1/products/B200 | jq
```

```json
{ "sku": "B200", "name": "Bird seed, 10 kg", "price": 18.5 }
```

The gateway never called the backend to find out that route existed — it read the descriptor out
of `CatalogServiceGrpc` at startup:

```yaml
shiguchi:
  services:
    - name: catalog
      host: localhost
      port: 9090
      mode: STATIC
      static-service-classes:
        - dev.appiaworks.shiguchi.sample.staticschema.proto.CatalogServiceGrpc
```

Listing the classes is one option; `auto-discover.classpath: true` scans for them instead
(optionally limited to `scan-packages`), which is easier with many services and slower at
startup.

**The useful consequence:** routes are registered before the backend is listening at all. A
`STATIC` gateway starts fully formed and serves 503 from the channel rather than 404 from a
missing route — the failure says "backend is down", not "that endpoint does not exist".

## Run it — PB_FILE

```bash
../gradlew :sample-static-schema:bootRun --args='--spring.profiles.active=pb'
```

Identical responses. The difference is that the gateway holds **no generated Java at all** for
this service — only a descriptor file:

```yaml
shiguchi:
  services:
    - name: catalog
      mode: PB_FILE
      pb-descriptor-path: build/descriptors/catalog.pb
```

That matters when the gateway should not carry the backend's classes: a `FileDescriptorSet` is
language-neutral and a normal build artifact, so a Java gateway can front a Go or Rust service
without a Java stub for it existing anywhere.

The file comes from `protoc`. This sample's build produces it as part of `build`:

```bash
protoc --include_imports \
       --include_source_info \
       --descriptor_set_out=catalog.pb \
       catalog/v1/catalog.proto
```

> **`--include_imports` is not optional.** Without it the set omits
> `google/api/annotations.proto`, the gateway cannot resolve the `google.api.http` options, and
> every method registers with **no REST route** — a gateway that starts cleanly, reports healthy,
> and 404s everything. The `.pb` in this sample is 15 KB for one small proto precisely because it
> carries its imports.

`--include_source_info` is optional, and worth passing. It keeps the comments the `.proto` was
written with, and the gateway uses them as descriptions on the surfaces it generates. This
sample's proto documents its first method:

```proto
// Fetches one product by SKU.
rpc GetProduct(GetProductRequest) returns (Product) {
```

which arrives in the OpenAPI document without anyone restating it:

```bash
curl -s localhost:8080/v3/api-docs | jq -r '.paths."/v1/products/{sku}".get.description'
```

```
Fetches one product by SKU.
```

An explicit `(shiguchi.api.graphql).description` still wins where one is set — see
[docs/graphql-annotations.md](../../docs/graphql-annotations.md). This is the only mode where it
works: server reflection does not send source info, and compiled Java stubs do not retain it, so
`DYNAMIC` and `STATIC` have nothing to read. Gradle's protobuf plugin takes it as
`descriptorSetOptions.includeSourceInfo = true` (this sample's `build.gradle.kts` sets it); the
cost is a larger `.pb`, read once at startup.

## Check what loaded

Either profile:

```bash
curl -s localhost:8080/actuator/gateway-routes | jq '.services[0] | {mode, routeCount}'
```

```json
{ "mode": "STATIC", "routeCount": 2 }
```

The `mode` is the only field that differs between the two runs.

## Which should you use?

| | `DYNAMIC` | `STATIC` | `PB_FILE` |
| --- | --- | --- | --- |
| Backend must serve reflection | **yes** | no | no |
| Gateway needs the backend's Java classes | no | **yes** | no |
| Gateway needs a build artifact from the backend | no | yes (a jar) | yes (a `.pb`) |
| Picks up a new backend method | on the next refresh | on redeploy | on redeploy |
| Routes exist before the backend is up | no | **yes** | **yes** |
| Proto comments become descriptions | no | no | **yes** (with `--include_source_info`) |

Prefer `DYNAMIC` when you can — it is the one that does not couple the gateway's deployment to
the backend's. Reach for these when reflection is not on the table, or when you want routes
registered independently of whether the backend is reachable.

→ [docs/configuration.md](../../docs/configuration.md) ·
[docs/troubleshooting.md](../../docs/troubleshooting.md)
