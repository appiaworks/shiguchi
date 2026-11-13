<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/brand/shiguchi-lockup-stacked-dark.svg">
    <img src="docs/brand/shiguchi-lockup-stacked.svg" alt="Shiguchi 仕口" width="260">
  </picture>
</p>

# Shiguchi: a gateway for gRPC services, for Spring Boot

[![CI](https://github.com/appiaworks/shiguchi/actions/workflows/ci.yml/badge.svg)](https://github.com/appiaworks/shiguchi/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.appiaworks/shiguchi-core.svg?label=Maven%20Central)](https://central.sonatype.com/namespace/io.github.appiaworks)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

*Shiguchi* (仕口) is the Japanese carpenter's word for a joint where timbers meet at an angle and
lock together without nails. The mark is a *sanpō* joint, three beams meeting at one corner. That
is the project: one `.proto` contract meets REST, GraphQL and gRPC at the gateway, held together by
the schema itself rather than by glue code.

**Give your gRPC services a REST, GraphQL, and OpenAPI front door — without writing or
generating a single line of glue code.**

Add one starter, point it at a gRPC backend, and the gateway reads that service's schema at
startup — over gRPC server reflection, a classpath scan of compiled stubs, or a `.pb`
descriptor file — then serves it as REST/JSON, GraphQL, an OpenAPI 3 document with Swagger
UI, and a built-in web console for trying calls out. No stub generation step in the
gateway, no per-endpoint controllers, no schema kept in sync by hand. Add an RPC to your
proto and it shows up on every surface.

### One proto, three calls

```proto
service BookService {
  rpc ListBooks(ListBooksRequest) returns (ListBooksResponse) {
    option (google.api.http) = {get: "/v1/books"};   // the only annotation
  }
}
```

**REST**

```bash
curl 'localhost:8080/v1/books?pageSize=2&sort=publishedYear:desc'
```

```json
{"books":[{"id":"05","title":"Heat","author":"Mann","publishedYear":2020},
          {"id":"06","title":"Beloved","author":"Morrison","publishedYear":1987}],
 "pageInfo":{"totalCount":10, "nextPageToken":"…"}}
```

**GraphQL** (with the GraphQL starter; lists come back as Relay connections)

```graphql
{ bookListBooks(first: 2, sort: [{field: "publishedYear", sortOrder: SORT_ORDER_DESC}]) {
    totalCount
    edges { node { title author publishedYear } }
} }
```

```json
{"data":{"bookListBooks":{"totalCount":10,"edges":[
  {"node":{"title":"Heat","author":"Mann","publishedYear":2020}},
  {"node":{"title":"Beloved","author":"Morrison","publishedYear":1987}}]}}}
```

**gRPC**: the same service, untouched, on its own port. The gateway added no code to it.
The OpenAPI document, Swagger UI and the web console come from the same descriptor. The example
is [`sample-jpa-backend`](samples/sample-jpa-backend); the configuration is in the
[quick start](#quick-start).

| Surface | Path | Module |
| --- | --- | --- |
| REST/JSON gateway | per-method HTTP routes | `shiguchi-*-starter` |
| GraphQL + Apollo Federation | `/graphql`, `/graphiql` | `shiguchi-graphql-*-starter` |
| OpenAPI 3 + Swagger UI | `/v3/api-docs`, `/swagger-ui.html` | `shiguchi-openapi-*-starter` |
| gRPC web console (try-it UI, [screenshots](docs/guides/ui.md)) | `/grpc-ui` | `shiguchi-ui-*-starter` |
| Pass-through gRPC proxy | `:9090` (configurable) | `shiguchi-grpc-proxy` |
| gRPC-Web for browser clients ([docs](docs/guides/grpc-web.md)) | `POST /<package.Service>/<Method>` | `shiguchi-*-starter` (`grpc-web.enabled`) |
| protovalidate request validation | (interceptor) | `shiguchi-validation-*-starter` |
| Avro, to REST backends and for REST callers ([docs](docs/guides/avro.md)) | `avro/binary` on gRPC-backed routes, `/avro/schemas` | `shiguchi-avro-*-starter` |

**Why it exists.** The usual alternatives are `protoc-gen-grpc-gateway` (a Go binary and a
codegen step wired into every build) or hand-written Spring controllers that wrap each RPC
and drift from the proto. This is a Spring Boot dependency instead: descriptor-driven at
runtime, on the reactive stack, with the transcoding hot path optimised down to
microseconds ([benchmarks](docs/concepts/performance.md)). Runs on Spring Boot 4.

**The gateway does not authenticate callers by default.** Put authentication in front of it before it leaves a trusted network: [SECURITY.md](SECURITY.md), [security guide](docs/guides/security.md).

### Object links, built from the proto

The GraphQL schema is generated from the same descriptor, and a few field options turn an id into a
link between types. Annotate a `Book` that carries an `author_id`, and `Book.author` appears in the
schema by itself, batched into one backend call per query:

```proto
message Author {
  string id   = 1 [(shiguchi.field) = {id: true, batch_key: true}];
  string name = 2;
}

message Book {
  string id        = 1 [(shiguchi.field) = {id: true, batch_key: true}];
  string title     = 2;
  int32  year      = 3;
  string author_id = 4 [(shiguchi.field) = {id: true, resolves_type: "Author"}];  // becomes Book.author
}

service Library {
  rpc ListBooks(ListBooksRequest) returns (ListBooksResponse) {
    option (shiguchi.graphql) = {name: "books", type: QUERY};
  }
  rpc GetAuthorsByIds(GetAuthorsByIdsRequest) returns (GetAuthorsByIdsResponse) {
    option (shiguchi.graphql) = {name: "authorsByIds", type: QUERY, loader_for: "Author", loader_ids_field: "ids"};
  }
}
```

```graphql
type Book { id: ID!  title: String  year: Int  authorId: ID  author: Author }   # generated
```

```graphql
{ books(first: 2) { edges { node { title author { name } } } } }
```

However many books come back, the authors are fetched with a single `GetAuthorsByIds` call. The
rules are in the [GraphQL annotations guide](docs/guides/graphql-annotations.md#how-the-pieces-combine).

---

## Architecture

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/images/architecture-dark.svg">
  <img src="docs/images/architecture.svg" alt="Clients (REST, GraphQL, OpenAPI, console, streaming, browsers, gRPC) reach Shiguchi's auth edge, route registry, serde and dispatcher, which call gRPC and REST backends">
</picture>

Streaming RPCs (server, client and bidirectional) reach REST callers as NDJSON, SSE or
WebSocket frames, GraphQL callers as subscriptions or `@live` queries, and browsers as gRPC-Web.
The request path, end to end:

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/images/request-flow-dark.svg">
  <img src="docs/images/request-flow.svg" alt="A REST request is authenticated, routed, converted to protobuf, sent to the gRPC backend and the response converted back to JSON">
</picture>

How a request flows through the serde tiers is documented in
[docs/concepts/transcoding.md](docs/concepts/transcoding.md).

---

## Quick start

Requires **JDK 21+** (generated serde tiers on 25+). Add the starter (Spring Boot 4):

```kotlin
dependencies {
    implementation("io.github.appiaworks:shiguchi-spring-boot4-starter:0.22.0")
}
```

Point it at a backend in `application.yml` and run:

```yaml
shiguchi:
  services:
    - name: library-service
      host: localhost
      port: 9090
```

That is the whole configuration. One backend, four surfaces:

```bash
curl localhost:8080/v1/books/1
curl -X POST localhost:8080/graphql -H 'Content-Type: application/json' \
  -d '{"query":"{ bookListBooks(first: 2) { edges { node { title author } } } }"}'
# /swagger-ui.html  and  /grpc-ui (when enabled)
```

Full steps (Maven, annotating protos, samples, building from source):
[docs/getting-started/quick-start.md](docs/getting-started/quick-start.md). A guided walk-through:
[docs/getting-started/tutorial.md](docs/getting-started/tutorial.md).

## See it

| Console | GraphiQL | Swagger UI |
| --- | --- | --- |
| ![Invoking a method from the gRPC console](docs/images/grpc-ui-invoke.png) | ![GraphiQL running a query against the sample schema](docs/images/graphiql.png) | ![Swagger UI listing the generated operations](docs/images/swagger-ui.png) |

The embedded gRPC console, GraphiQL and Swagger UI, all generated from the same protos ([console guide](docs/guides/ui.md)).

## Features

Schema discovery without codegen, REST/JSON transcoding, GraphQL with Apollo Federation, OpenAPI
and a web console, Cursus push messaging, security and rate limiting, resilience, caching,
metrics and tracing, a Helm chart and an OCI image. Each is described, with its options, in
[docs/getting-started/features.md](docs/getting-started/features.md) and the guides linked from [docs/README.md](docs/README.md).

## Security, performance, operations

- Secure by opt-in: [docs/guides/security.md](docs/guides/security.md), [SECURITY.md](SECURITY.md).
- Serde tiers and numbers: [docs/concepts/performance.md](docs/concepts/performance.md),
  [benchmarks](shiguchi-benchmarks/README.md).
- Metrics, tracing, access log: [docs/operations/observability.md](docs/operations/observability.md).
- Helm chart and image: [docs/operations/deployment.md](docs/operations/deployment.md).
- Spring Boot 4 only (0.18.x is the last Boot 3 line): [docs/operations/compatibility.md](docs/operations/compatibility.md).

---

## Documentation

**Start here**

- [docs/getting-started/tutorial.md](docs/getting-started/tutorial.md) — a gRPC backend to REST, GraphQL, OpenAPI and a console, one step at a time, with what each step actually did.
- [docs/operations/troubleshooting.md](docs/operations/troubleshooting.md) — symptoms and the exact messages: 404s, backends that will not load, JSON that looks wrong, startup failures.
- [docs/concepts/comparison.md](docs/concepts/comparison.md) — versus grpc-gateway (Go), proxy-based transcoders, plain SCG and hand-written controllers — including when to pick one of those.

**Everything else**

- [docs/README.md](docs/README.md) — every guide, grouped by task: shaping the API, other client surfaces, backends and traffic, security, push, production, the standalone libraries and the design notes.
- [shiguchi-benchmarks](shiguchi-benchmarks/README.md) — JMH suites and reference numbers.
- [SECURITY.md](SECURITY.md) — security model, default posture, vulnerability reporting.
- [CONTRIBUTING.md](CONTRIBUTING.md) — build, test, and PR guidelines.
- [CHANGELOG.md](CHANGELOG.md) — release history.

## Built on

This project exists because of the open source it builds on. The load-bearing ones:

| Project | Role here |
| --- | --- |
| [gRPC-Java](https://github.com/grpc/grpc-java) | Channels, server reflection, the proxy transport |
| [Protocol Buffers](https://github.com/protocolbuffers/protobuf) | Descriptors and dynamic messages — the basis of zero-codegen discovery |
| [Spring Boot](https://github.com/spring-projects/spring-boot) | Autoconfiguration and the starter model |
| [Spring Cloud Gateway](https://github.com/spring-cloud/spring-cloud-gateway) | Reactive route registry and filter chain |
| [Netflix DGS](https://github.com/Netflix/dgs-framework) + [graphql-java](https://github.com/graphql-java/graphql-java) | GraphQL surface and Apollo Federation |
| [springdoc-openapi](https://github.com/springdoc/springdoc-openapi) + [Swagger UI](https://github.com/swagger-api/swagger-ui) | OpenAPI 3 document and the Swagger surface |
| [Caffeine](https://github.com/ben-manes/caffeine) | Descriptor and marshaller caches on the hot path |
| [ClassGraph](https://github.com/classgraph/classgraph) | Classpath scan discovery mode |
| [protovalidate](https://github.com/bufbuild/protovalidate-java) | Request validation interceptor |
| [Micrometer](https://github.com/micrometer-metrics/micrometer) + [OpenTelemetry](https://github.com/open-telemetry/opentelemetry-java) | Metrics and distributed tracing |
| [React](https://github.com/facebook/react) + [Monaco Editor](https://github.com/microsoft/monaco-editor) | The embedded gRPC web console |

Full list with versions and license identifiers: [THIRD-PARTY.md](THIRD-PARTY.md).

## License

Copyright 2024 Shiguchi contributors.

Licensed under the [Apache License, Version 2.0](LICENSE). You may obtain a copy of the
License at <https://www.apache.org/licenses/LICENSE-2.0>.

Unless required by applicable law or agreed to in writing, software distributed under the
License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
either express or implied.

The name "Shiguchi" and the logo are trademarks and are not licensed with the code; see
[TRADEMARKS.md](TRADEMARKS.md) for how you may use them.

Attribution notices for redistributed components are in [NOTICE](NOTICE); if you
redistribute this software or a derivative, Apache-2.0 §4 requires you to carry that file
along with the license. Contributions are accepted under the same license
([CONTRIBUTING.md](CONTRIBUTING.md)).
