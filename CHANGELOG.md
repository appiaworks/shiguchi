# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
