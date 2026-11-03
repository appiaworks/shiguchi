# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
