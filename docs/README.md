# Shiguchi documentation

Every guide in this folder, grouped by what you are trying to do. New to Shiguchi? Read the
[tutorial](tutorial.md) first. Something not working? Search
[troubleshooting](troubleshooting.md) for the message you see.

## Where to start

| If you want to... | Read |
| --- | --- |
| put REST, GraphQL and OpenAPI in front of a gRPC service | [tutorial](tutorial.md), then [configuration](configuration.md) |
| decide whether Shiguchi fits | [comparison](comparison.md) |
| fix a 404, a backend that does not load, or odd JSON | [troubleshooting](troubleshooting.md) |
| run it in Kubernetes | [deployment](deployment.md), [observability](observability.md) |
| lock it down | [security](security.md), then [bff](bff.md) for browser apps |
| push events to clients | [push](push.md), [push-cli](push-cli.md) |
| use the JSON serde or typed accessors without the gateway | [proto-serde-standalone](proto-serde-standalone.md), [protoc-java-extras](protoc-java-extras.md) |

## Getting started

- [tutorial.md](tutorial.md): from a gRPC backend to REST, GraphQL, OpenAPI and a web console,
  one step at a time, with what each step did.
- [configuration.md](configuration.md): every `shiguchi.*` property and the extension-point beans.
- [comparison.md](comparison.md): Shiguchi next to grpc-gateway (Go), proxy-based transcoders,
  Spring Cloud Gateway on its own and hand-written controllers, including when to pick one of those.
- [troubleshooting.md](troubleshooting.md): symptoms, their causes and how to confirm them, keyed
  on the exact messages the gateway logs or returns.

## Shaping the API

How a proto contract becomes the HTTP surfaces.

- [http-annotations.md](http-annotations.md): `google.api.http`: verbs, path templates, `body`,
  `additional_bindings`, binary bodies, unknown query parameters.
- [graphql-annotations.md](graphql-annotations.md): the `shiguchi.api` proto options:
  `(shiguchi.api.graphql)`, `(shiguchi.api.graphql_field)` and `(shiguchi.api.method)`.
- [transcoding.md](transcoding.md): how schemas are discovered at runtime, the request flow, the
  serde tiers and the JSON mapping rules.
- [json-types.md](json-types.md): dates, times, decimals, money, durations and numbers on every
  surface; string escaping and print options.
- [projections-paging-sorting.md](projections-paging-sorting.md): the `read_mask`,
  `page_request` and `sort_fields` conventions, with a Spring Data JPA backend.
- [merging.md](merging.md): one sorted, paged endpoint served by several backends.
- [error-handling.md](error-handling.md): the gRPC to HTTP status table and custom error bodies.
- [validation.md](validation.md): protovalidate constraints checked at the gateway or in your
  gRPC server.

## Other client surfaces

- [grpc-web.md](grpc-web.md): gRPC-Web and Connect clients in the browser without a separate proxy.
- [avro.md](avro.md): Avro to `REST_DOWNSTREAM` backends and for REST callers.
- [ui.md](ui.md): the embedded `/grpc-ui` console, with screenshots.

## Backends and traffic

- [service-discovery.md](service-discovery.md): Eureka, Consul, Kubernetes or DNS; balancing
  across instances; rolling deploys and versions.
- [resilience.md](resilience.md): retries, the retry budget, hedging, circuit breaking,
  concurrency limits and channel pools.
- [caching.md](caching.md): HTTP caching headers, the response cache, request coalescing, etags,
  compression, and the GraphQL document cache.
- [rate-limiting.md](rate-limiting.md): per-caller token buckets on REST and GraphQL, in memory
  or in Redis.

## Security

- [security.md](security.md): the JWT resource server, per-surface gating, scopes and claims,
  what backends receive, TLS to backends, your own filter chain. The default posture is in
  [SECURITY.md](../SECURITY.md).
- [bff.md](bff.md): browser sign-in with a session cookie (experimental): threat model, setup,
  sessions, refresh, CSRF, logout and revocation.

## Push messaging (Cursus)

Experimental: the shape may change in a minor release.

- [push.md](push.md): server-to-client messaging over SSE, WebSocket, gRPC and GraphQL
  subscriptions; topics, namespaces, delivery guarantees, engines, authorization, administration.
- [push-cli.md](push-cli.md): the `cursus` command line: install, profiles, every verb, exit codes.
- The npm client: [shiguchi-cursus-client-js](../shiguchi-cursus-client-js/README.md).

## Running in production

- [deployment.md](deployment.md): the Helm chart, the `sample-full` image, your compiled stubs in
  the image, JVM tuning and the AOT cache.
- [observability.md](observability.md): Micrometer metrics, Prometheus and the
  [Grafana dashboard](grafana/shiguchi.json), OpenTelemetry tracing, call logging, health and
  readiness, the routes endpoint.

## Libraries without the gateway

- [proto-serde-standalone.md](proto-serde-standalone.md): `protobuf-java-extras`: the same JSON
  dialect and type converters from plain Java, without Spring or gRPC.
- [protoc-java-extras.md](protoc-java-extras.md): the protoc plugin that adds Java-typed accessors
  (`getDepartsOnAsLocalDate()`) to generated classes.
- [api-compatibility.md](api-compatibility.md): which types carry a compatibility promise and the
  build gate that enforces it.

## Design notes

Background on why things are built the way they are. They record decisions and history, so
read the guides above for current behaviour.

- [design/push.md](design/push.md): Cursus: prior art, vocabulary, SPIs, QoS and sessions.
- [design/bff.md](design/bff.md): the BFF: decisions, the threat model and the build plan.

## About the project

- [README](../README.md): what Shiguchi is, the feature list and a quick start.
- [CHANGELOG.md](../CHANGELOG.md): release history.
- [CONTRIBUTING.md](../CONTRIBUTING.md): build, test and pull request guidelines.
- [SECURITY.md](../SECURITY.md): the security model and how to report a vulnerability.
- [shiguchi-benchmarks](../shiguchi-benchmarks/README.md): JMH suites and reference numbers.
- [brand/](brand/README.md): the logo and how to use it.
