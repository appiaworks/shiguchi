# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- `shiguchi.graphql.live.*`: `query ... @live` over WebSocket or SSE re-runs whenever a mutation through the
  gateway touches a service behind its root fields; throttled, de-duplicated and limited per principal. In-process
  invalidation only for now.
- `shiguchi.graphql.incremental.enabled`: `@defer` answered as `multipart/mixed` (draft 2022-08-24) to clients
  that send `Accept: multipart/mixed`; anyone else gets the complete JSON. `@stream` is not supported.
- `docs/performance.md`: ranked tuning guide for REST and GraphQL with the benchmark numbers behind each setting.
- Federation proto options. `(shiguchi.field)` gains `shareable`, `external`, `requires`, `provides`
  (emitted while federation is on; `requires`/`provides` are checked as selection sets) and `directives`;
  `(shiguchi.graphql)` gains `directives`; the new message option `(shiguchi.type)` (extension number
  50104) sets `keys` (one `@key` each, replacing the `batch_key` fallback), `unresolvable`, `directives`
  and `description`. `directives` appends raw SDL verbatim and is not validated by the gateway.
- GraphQL query cost. The `cost` proto option on `(shiguchi.graphql)` and `(shiguchi.field)`
  weights a field (default 1); a list field costs its weight times its `first` argument, else
  `shiguchi.graphql.limits.default-list-size` (10). `limits.max-complexity` now compares
  against this cost instead of a plain field count, `limits.budget.per-minute` gives each caller
  (principal, else client IP) a bucket of cost points per minute, in memory or in Redis, and
  answers an exhausted one with a `RATE_LIMITED` error, `extensions.retryAfter` and a
  `Retry-After` header (HTTP 200); `limits.report-cost` returns the cost in `extensions.cost`.
- `shiguchi.graphql.introspection: ENABLED | AUTHENTICATED | DISABLED`, decided per request.
  A refused operation that selects `__schema` or `__type` is answered with an error and not run.
  `AUTHENTICATED` is recommended for gateways reachable by untrusted clients.
- `shiguchi.graphql.batch.max-operations`: a POST whose body is a JSON array runs each element
  as its own operation (limits, introspection and budget apply per element) and answers an
  array of results in order. Off by default.
- `shiguchi.graphql.compat-options: [graphql-1079]` reads the `graphql.*` proto options
  (package `graphql`, extension number 1079, used by protos written for other GraphQL gateways)
  as aliases of `(shiguchi.graphql)` and `(shiguchi.field)`: `schema.type` (by name), `name`,
  `request.plucks` → `arg_fields`, `response.pluck` → `pluck`; `field.required`, `name`,
  `default`, `omit`, `description`. Off by default: a proto carrying them logs one INFO hint per
  file. On, the Shiguchi option wins when both are present, and fields with no counterpart
  (`RESOLVER`, `field.resolver`, the `service` option) are ignored with one WARN each. Routes
  bound through the alias carry `graphqlOptions: graphql-1079` in the routes report. Nothing is
  vendored; the options are decoded by number against the descriptors the proto brings along.
  New experimental type `dev.appiaworks.shiguchi.reflection.GraphQlOptionReader`;
  `RouteDefinition.getGraphQlOptionSource()`.
- GraphQL response cache (`shiguchi.graphql.response-cache.enabled`, off by default): a query
  whose root fields all resolve to a method with `(shiguchi.method).cache_ttl_seconds` is answered
  from memory for the smallest of those TTLs, without running the engine or any backend call.
  Keyed by document, operation, variables and, with `vary-by-principal` (default `true`) or
  `auth_scopes` on a root method, the caller. A response with errors is never stored. A mutation
  runs, then evicts every entry built from the services behind its root fields; a schema reload
  empties the cache. `stale-while-revalidate` (default `0s`) serves an expired entry while one
  background refresh runs; `max-entries` (default `2000`) bounds it. Every answered query carries
  `Cache-Status` (RFC 9211) and, on a hit, `Age`; counter `shiguchi.graphql.response.cache` tagged
  `result` = `hit`, `stale` or `miss`. New experimental type
  `dev.appiaworks.shiguchi.graphql.GraphQlResponseCacheConfiguration`.

### Changed

- **Breaking:** the proto options moved from package `shiguchi.api` to `shiguchi`, with shorter
  names, and a new umbrella file `shiguchi/annotations.proto` imports all of them at once
  (`import "shiguchi/annotations.proto";`). The individual files moved one directory up and stay
  importable on their own. Generated Java classes moved from `dev.appiaworks.shiguchi.proto.api`
  to `dev.appiaworks.shiguchi.proto`; the outer classes (`GraphQlProto`, `MethodProto`,
  `MergeProto`, `TopicProto`) keep their names. The message `GraphQlFieldRule` is now `FieldRule`,
  and its never-implemented `resolver` field (8) is removed and reserved.

  | Before | After |
  | --- | --- |
  | `import "shiguchi/api/graphql.proto";` | `import "shiguchi/graphql.proto";` or the umbrella |
  | `(shiguchi.api.graphql)` | `(shiguchi.graphql)` |
  | `(shiguchi.api.graphql_field)` | `(shiguchi.field)` |
  | `(shiguchi.api.method)` | `(shiguchi.method)` |
  | `(shiguchi.api.merge)` | `(shiguchi.merge)` |
  | `(shiguchi.api.topic)` | `(shiguchi.topic)` |

  The extension numbers (50100–50103, 50200) are unchanged, so upstreams compiled against the
  `shiguchi.api.*` options keep working in `DYNAMIC` and `PB_FILE` mode without a rebuild: the
  gateway decodes options by number. `STATIC`-mode consumers and anyone who imports the generated
  classes regenerate their stubs against the 0.20.0 `protos` zip and update the imports.

### Fixed

- Cursus `REDIS` store over RESP2: Lettuce 7.1.0–7.6.x (Spring Boot 4.1.2 manages 7.5.2) merges
  the entries of a multi-entry stream read into one malformed message
  ([redis/lettuce#3800](https://github.com/redis/lettuce/issues/3800)). A group claim of several
  events then returns one, and the rest wait in the pending list until their ack deadline, so they
  are redelivered late and can reach the DLQ. It affects connections that speak RESP2: Redis older
  than 6, or a proxy or client setting that forces RESP2. Redis 6+ on RESP3 is not affected. Fix
  it by setting `lettuce.version` to `7.7.0.RELEASE` or later (Gradle:
  `io.lettuce:lettuce-core:7.7.0.RELEASE` or later). The cursus tests now run on Lettuce 7.9.0,
  since embedded-redis runs Redis 5 on Windows.

### Documentation

- GraphQL side-by-side against Protocol-Lattice `grpc_graphql_gateway` in `docs/comparison.md` (`shiguchi-benchmarks/comparison/lattice/`): with its cache off the two are within 8%.

## [0.19.0] - 2026-11-04

### Removed

- The Spring Boot 3 starters and auto-configuration (`*-spring-boot3-*`), deprecated in 0.18.0.
  Stay on 0.18.x for Spring Boot 3.5; `release/0.18.x` takes security fixes on a best-effort
  basis until May 2027. To move, use the `*-spring-boot4-*` artifact ids on Boot 4; the
  `shiguchi.*` configuration is unchanged.

### Changed

- The shared modules (`shiguchi-core`, `-rest`, `-graphql`, ...) now import the Spring Boot 4
  BOM, so their published POMs manage Boot 4 versions.
- All samples run on Spring Boot 4.
- **Breaking:** Jackson 3 (`tools.jackson`) replaces Jackson 2 everywhere, including the
  `protobuf-java-extras` API. `ProtoJson.write`, `JsonWritable.writeJson`,
  `JsonReadable.mergeJson` and the other serde methods that took a Jackson generator or parser now
  take `tools.jackson.core.JsonGenerator` / `JsonParser`, and no longer declare `IOException`.
  Jackson 3's exceptions are unchecked.
  To move:
  - Regenerate code made by `protoc-gen-java-extras` with this version's plugin. Classes generated
    by an older plugin no longer compile.
  - Pass a Jackson 3 `ObjectMapper` to `OpenApiBatchLoader`.
  JSON output is unchanged: the gateway keeps Jackson 2's property order and read limits.
  The OpenAPI merge still goes through swagger-core's Jackson 2 mapper, so Jackson 2 stays on the
  classpath through springdoc.

## [0.18.0] - 2026-11-03

### Deprecated

- The Spring Boot 3 starters (`*-spring-boot3-*`). 0.18.x is the last release line that ships
  them, and the next minor release removes them. `release/0.18.x` takes security fixes on a
  best-effort basis until May 2027. They now log a warning at startup. To move, use the
  `*-spring-boot4-*` artifact ids on Boot 4; the `shiguchi.*` configuration is unchanged.

### Security

- The build now tests the Boot 3 line on patched Tomcat, Netty 4.1, Jackson 2.21, Log4j and
  OpenTelemetry instead of the versions Boot 3.5 pins. Published POMs are unchanged: raise these
  in your own build, as [troubleshooting](docs/troubleshooting.md) describes.

## [0.17.0] - 2026-11-03

### Changed

- The published OCI image (`sample-full`) now runs on Spring Boot 4.1 instead of 3.5. An image
  derived from it, or stubs added to it, must work with the Boot 4 line. `shiguchi.*` configuration
  is unchanged.
- The Spring Boot 3 line is now best effort, because Boot 3.5 and Spring Framework 6.2 left open
  source support on 2026-06-30. We plan to remove it around May 2027. New work should use the Boot 4
  starters.
- Dependencies raised to their latest versions, among them gRPC 1.85.1, Jackson 2.22.4, springdoc
  3.2.0 (Boot 4) and 2.10.0 (Boot 3), Guava 33.8.0 and Caffeine 3.3.1.
- ClassGraph moved from 4.8 (end of life) to 5.0, the last line that runs on Java 21. An application
  that also uses ClassGraph 4 directly needs to port to the 5.x API.

### Fixed

- Documented the startup failure on Boot 4 with GraphQL under Boot's dependency management: the
  Boot 4.1 BOM holds json-path at 2.10 and DGS 12.1 needs 3.0. Set `json-path.version` to `3.0.0`
  ([troubleshooting](docs/troubleshooting.md)).

## [0.16.1] - 2026-11-01

### Fixed

- Cursus in-memory broker: a subscriber joining a topic at the moment its last subscriber left could
  attach to a discarded sink and silently receive nothing.
- BFF: a stream opened just as its session ended on the same instance stayed open until the next
  session check (`session-check-interval`) instead of ending at once. A session check now also runs
  when a stream starts being watched, so each bound stream makes one more store lookup.

## [0.16.0] - 2026-11-01

### Changed

- No library code has changed since 0.15.1. This release is the first minor to go through the new
  release pipeline.
- The `sample-full` image is now built natively on each platform instead of under QEMU emulation.

## [0.15.1] - 2026-11-01

### Fixed

- Cursus JDBC broker: an event appended while the first subscriber of an idle broker was joining
  could be skipped.
- GraphQL: right after a service loaded late, a request could resolve the new relationships to
  null. The DataLoaders are now registered before the schema is rebuilt.
- `auto-discover.spring-beans` found no routes for a `BindableService` written the usual way, a
  top-level subclass of `*ImplBase`, so the app started with an empty console. It now also works
  through proxies, no longer accepts a class just because its name ends in `Grpc`, skips gRPC's own
  health and reflection services and gateway-hosted services, and logs a `WARN` for a bean it cannot
  resolve.
- Docs: a failed refresh does not take the gateway out of rotation; `troubleshooting.md` said it did.

## [0.15.0] - 2026-10-30

### Added

- `shiguchi-ui-webmvc-spring-boot3-starter` and `shiguchi-ui-webmvc-spring-boot4-starter`: the gRPC
  console embedded in a servlet (Spring MVC) application, with no WebFlux on the classpath. It
  replaces the back-off warning a servlet application got from the WebFlux UI starter.
  See `docs/ui.md` and `samples/sample-grpc-ui-servlet`.

### Changed

- `shiguchi-ui` no longer brings `spring-boot-starter-webflux`; the WebFlux UI starter does.
- The console resolves its API, assets and OAuth callback relative to its own page, and sends
  `X-XSRF-TOKEN` from the `XSRF-TOKEN` cookie on its POSTs.

### Fixed

- A custom `shiguchi.ui.path` now serves the console's static assets under that path.
- The console's Swagger, GraphiQL, push and push-admin links include the application's context
  path (`server.servlet.context-path` or `spring.webflux.base-path`).
- The console's service list can be resized again: panel sizes were read as pixels, which pinned
  the sidebar at 40px.
- The console's editor no longer logs an `editorWorkerService` worker error on load.

## [0.14.1] - 2026-10-29

### Fixed

- GraphQL picks up services that load after startup (a `DYNAMIC` backend that was down at boot,
  or a refresh): the schema is rebuilt, and the new data fetchers, `loader_for` DataLoaders and
  their configured `data-loader.cache` entries are registered. Before, only what loaded at boot
  was served, and with no `loader_for` loader at the first request Spring GraphQL never consulted
  the batch loader registry again. While services are still pending, a cache entry with no matching type logs at INFO
  instead of warning.
- The UI starter no longer stops a servlet (Spring MVC) application from starting. The console
  needs WebFlux, so on a servlet application it now backs off and logs why.

### Security

- The `/grpc-ui` console bundles DOMPurify 3.4.17 or later, past its published advisories.

### Documentation

- Credentials for `DYNAMIC` reflection with a `GrpcChannelBuilderCustomizer`
  (troubleshooting), the JSON body `gateway-refresh` needs, and the console embedded in the gRPC
  server application.

## [0.14.0] - 2026-10-28

First public release. Shiguchi reads a gRPC service's schema at startup (server reflection,
classpath stubs or a descriptor file) and serves it from Spring Boot 3 or 4 as:

- REST/JSON with `google.api.http` transcoding, gRPC-Web, and a pass-through gRPC proxy.
- GraphQL with Apollo Federation, and an OpenAPI 3 document with Swagger UI.
- A built-in web console for trying calls.
- Avro to REST backends and for REST callers, and `REST_DOWNSTREAM` (OpenAPI) backends.
- Request validation with protovalidate.
- A backend-for-frontend (BFF) layer with sign-in, sessions and token relay.
- Cursus, an experimental push module (SSE, WebSocket, gRPC and GraphQL subscriptions) with
  memory, Redis and Postgres engines, the `cursus` CLI and the `@appiaworks/shiguchi-cursus-client`
  npm package.

The standalone `protobuf-java-extras` serde library and the `protoc-gen-java-extras` plugin ship
alongside. Artifacts are on Maven Central under `io.github.appiaworks`; see the
[README](README.md) to get started.
