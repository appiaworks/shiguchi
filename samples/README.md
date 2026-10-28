# Samples

Self-contained example apps. Each is an independent consumer build that resolves the
published `io.github.appiaworks:*` artifacts — on a fresh machine run
`./gradlew publishToMavenLocal` from the repo root first.

| Sample | What it shows | Run |
| --- | --- | --- |
| `sample-rest-gateway` | Minimal REST/JSON gateway in front of one gRPC service: `google.api.http` annotations → HTTP routes, Swagger UI | `./gradlew -p samples sample-rest-gateway:bootRun` |
| `sample-graphql-gateway` | GraphQL schema generated from protos, `(shiguchi.api.graphql)` annotations, Apollo Federation subgraph | `./gradlew -p samples sample-graphql-gateway:bootRun` |
| `sample-grpc-proxy` | Pass-through gRPC proxy on `:9191` with merged reflection across backends | `./gradlew -p samples sample-grpc-proxy:bootRun` |
| `sample-grpc-ui-standalone` | Just the embedded gRPC web console (`/grpc-ui`), no other surfaces | `./gradlew -p samples sample-grpc-ui-standalone:bootRun` |
| `sample-openfga-authz` | Per-object authorization via a custom `GatewayAuthorizer` backed by OpenFGA: route→object mapping, contextual tuples from JWT claims, optional decision cache | `./gradlew -p samples sample-openfga-authz:bootRun` |
| `sample-opa-authz` | The same SPI driven by Open Policy Agent: one Rego decision per request over a full input document — contrast with the OpenFGA sample's per-rule tuple checks | `./gradlew -p samples sample-opa-authz:bootRun` |
| `sample-rest-downstream` | A legacy **REST** service (not gRPC) aggregated via `REST_DOWNSTREAM`: OpenAPI document fetched, operations proxied under `/inv` with the prefix stripped, per-operation scopes, document merged into `/v3/api-docs` with namespaced components. Backend runs in-process | `./gradlew -p samples sample-rest-downstream:bootRun` |
| `sample-avro-downstream` | A `REST_DOWNSTREAM` backend spoken to in **Avro**: the document advertises `avro/binary` and carries `x-avro-schema`, the gateway asks for Avro, decodes it, and serves the usual JSON and GraphQL. One operation stays JSON, one ignores `Accept`. Backend runs in-process | `./gradlew -p samples sample-avro-downstream:bootRun` |
| `sample-rate-limited-gateway` | Inbound rate limiting: a token bucket per caller with limits small enough to hit from a shell loop, 429 + `Retry-After` + `X-RateLimit-*`. `MEMORY` by default, `--spring.profiles.active=redis` shares the buckets across gateway instances through Redis. Backend runs in-process | `./gradlew -p samples sample-rate-limited-gateway:bootRun` |
| `sample-discovery-gateway` | A backend running as **three instances**, found through a Spring Cloud `DiscoveryClient` and balanced across with `round_robin`. Every response says which instance answered, so the rotation is visible; `DELETE /fleet/one` kills one and calls settle onto the rest without a restart. `--spring.profiles.active=pick-first` for the other policy. Fleet and registry run in-process; the README maps it to Eureka, Consul and Kubernetes | `./gradlew -p samples sample-discovery-gateway:bootRun` |
| `sample-static-schema` | A backend with **no gRPC reflection**, fronted two ways: `STATIC` (schema read from the generated stubs on the gateway classpath) and, under the `pb` profile, `PB_FILE` (schema read from a protoc `FileDescriptorSet`). Same routes either way. The only sample that compiles real protos, so also the place to see the protobuf Gradle setup | `./gradlew -p samples sample-static-schema:bootRun` |
| `sample-secured-gateway` | Secured on both sides: a JWT required from callers (`security.mode: AUTHENTICATED`) and **mutual TLS** to a backend that demands a client certificate. Certificates are generated at startup, so no private key is committed. Shows the startup warning you get when you authenticate callers but forward their tokens over plaintext | `./gradlew -p samples sample-secured-gateway:bootRun` |
| `sample-validation` | protovalidate constraints declared in the proto (`string.pattern`, `int32.gte/lte`, `string.email`) enforced at the gateway: invalid requests are refused with 400 before the call leaves. The backend deliberately validates nothing, so every rejection is visibly the gateway’s | `./gradlew -p samples sample-validation:bootRun` |
| `sample-protoc-java-extras-maven` | **Maven**, and not a gateway: a plain Java project whose generated proto classes gain `setPlacedOn(LocalDate)` / `getPlacedOnAsLocalDate()` through `protoc-gen-java-extras`, run by `io.github.ascopes:protobuf-maven-plugin`. Not part of the Gradle samples build | `mvn -f samples/sample-protoc-java-extras-maven -q package exec:java` |
| `sample-protobuf-java-extras` | **Not a gateway**: `protobuf-java-extras` in a plain Java program. `ProtoJson` in the gateway's dialect and one of its own, a `ProtoScalarCodec`, typed accessors from the `io.github.appiaworks.protoc-java-extras` Gradle plugin, and a Kafka serializer/deserializer in proto binary or JSON plus a bridge that republishes a binary topic as JSON through the transcoder. No broker needed | `./gradlew -p samples sample-protobuf-java-extras:run` |
| `sample-merge-gateway` | One `GET /v1/orders` merged from three backends: two gRPC shards (one honours `sort_fields`, one does not) and a plain **HTTP** `REST_DOWNSTREAM` service, all in-process. Shows what pre-sorted, unsorted and HTTP sources each cost | `./gradlew -p samples sample-merge-gateway:bootRun` |
| `sample-bff-spa` | A single-page app that signs in **with no token in the browser**: BFF module with a session cookie, a tiny Spring Authorization Server as the issuer (2-minute tokens, so refresh is visible), REST and gRPC-Web calls and a push socket all on the cookie, page served by the gateway. Three parts (`issuer`, `gateway`, `web`); checked by hand in a real browser, **not run by CI** | `./gradlew -p samples sample-bff-issuer:bootRun` and `sample-bff-gateway:bootRun`; see its README |
| `sample-full` | Every surface together — REST, GraphQL, OpenAPI, UI, proxy, validation, Avro for REST callers (`accept: avro/binary`, `/avro/schemas`). Source of the published OCI image | `./gradlew -p samples sample-full:bootRun` |

The samples expect a gRPC backend with server reflection enabled at `localhost:9090`
(see each sample's `application.yml`) — point them at any of your own services, or adjust
the host/port. Every surface then works against that backend out of the box.

[`observability/`](observability/) is not a sample but goes with all of them: Prometheus and
Grafana in a `docker compose`, provisioned with the gateway dashboard, scraping whichever sample
is running on the host.

Start with `sample-rest-gateway` if you are evaluating the gateway; `sample-full`'s
`application.yml` is the best reference for how the configuration surfaces compose.
