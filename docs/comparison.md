# How this compares

Where Shiguchi fits against the other ways to put HTTP in front of gRPC, and — as
honestly as we can manage — when one of those is the better choice.

The short version: this project exists for teams whose gateway is **a Spring Boot application**
and whose backends' shape is **discovered at runtime** rather than compiled in. If neither is
true for you, one of the alternatives below is probably a better fit.

---

## At a glance

| | Shiguchi | grpc-gateway (Go) | Envoy gRPC-JSON transcoder | Spring Cloud Gateway alone |
| --- | --- | --- | --- | --- |
| Runs as | Spring Boot app | generated Go service | proxy (C++) | Spring Boot app |
| Schema source | reflection, classpath, or `.pb` — **at runtime** | protoc plugin — **at build time** | `.pb` descriptor — at config time | n/a (no transcoding) |
| Codegen step | none | yes, per proto change | no, but rebuild the descriptor | n/a |
| New backend method | appears after a refresh | regenerate + redeploy | rebuild descriptor + reload | n/a |
| REST transcoding | yes | yes | yes | no |
| GraphQL | yes, generated | no | no | no |
| OpenAPI document | yes, generated | via a separate plugin | no | no |
| Web console for gRPC | yes | no | no | no |
| Language/runtime fit | JVM shops | Go shops | any (infrastructure) | JVM shops |

---

## grpc-gateway (Go)

[grpc-ecosystem/grpc-gateway](https://github.com/grpc-ecosystem/grpc-gateway) is the reference
implementation of this idea and the one most people mean by "gRPC gateway". A protoc plugin reads
your `google.api.http` annotations and generates a Go reverse proxy.

**Choose it when** your organisation runs Go, you want the proxy compiled and statically
verified, or you want the largest ecosystem and the most battle-tested transcoder. It is
excellent software and it defined the annotations this project also reads.

**Choose this instead when** your gateway has to be a Spring Boot application — because it sits
in a JVM deployment story, needs Spring Security against your existing IdP, or your team
maintains Java — or when regenerating and redeploying on every proto change is the cost you are
trying to remove. Here a backend that adds a method needs no gateway rebuild: reflection picks it
up on the next refresh.

**The honest trade:** build-time generation catches mismatches at compile time. Runtime discovery
finds them at startup instead, which is later. This project's answer is to make that failure
loud and legible — the health indicator names the service and the error, and
`/actuator/gateway-routes` shows exactly what loaded — but it is a genuine trade, not a free win.

## Envoy's gRPC-JSON transcoder

[Envoy](https://www.envoyproxy.io/docs/envoy/latest/configuration/http/http_filters/grpc_json_transcoder_filter)
transcodes JSON to gRPC in the proxy, from a `FileDescriptorSet` you build and ship in its
configuration.

**Choose it when** you already run Envoy or a service mesh built on it, you want transcoding to
be infrastructure rather than an application, or you need the throughput and per-connection cost
of a C++ proxy.

**Choose this instead when** you want more than transcoding from the same descriptors — a GraphQL
schema, an OpenAPI document, a console — or when the people maintaining the gateway would rather
write `application.yml` and a Spring bean than Envoy configuration. Response merging across
several backends, field projection, and per-route authorization through a Java SPI have no Envoy
equivalent without writing a filter.

**The honest trade:** Envoy will out-perform a JVM on raw proxying, and running transcoding as
infrastructure keeps it out of your application's lifecycle. If transcoding is *all* you need and
you already run a mesh, that is the simpler system.

### gRPC-Web

Envoy's `grpc_web` filter is the usual way to serve browser clients. The gateway's own gRPC-Web
support ([grpc-web.md](grpc-web.md)) speaks the same wire protocol on the same paths, so a client
written against an Envoy sidecar works unchanged against the gateway. The set of callable methods
is the loaded services, minus whatever their method filters exclude.

## Spring Cloud Gateway on its own

SCG is an excellent HTTP gateway — routing, filters, rate limiting, circuit breakers — and this
project builds on it. What it does not do is understand gRPC: it will proxy an HTTP/2 stream, but
it cannot turn `GET /v1/books/1` into a `GetBook` RPC, because that needs the protobuf
descriptors.

**Choose SCG alone when** your backends already speak HTTP and you need routing and filters.

**Choose this when** your backends speak gRPC and your callers speak JSON. You still get SCG
underneath — its filter chain, its rate limiter integration, its ecosystem — plus the
descriptor-driven surfaces.

## Writing controllers by hand

The default alternative, and the one most projects actually start with: a `@RestController` that
calls a generated gRPC stub.

**Choose it when** you have a handful of endpoints, want complete control over each payload, or
your REST API is deliberately shaped differently from your RPC API. Hand-written controllers are
not a failure mode; for a small, opinionated public API they may be the right answer.

**Choose this when** the controllers have become mechanical — each one unpacking a request,
calling one stub, repacking the reply — and every proto change means touching three files in two
repositories. That work is what the descriptors already describe.

---

## Measured side by side

The three transcoders in front of the same backend, answering the same request, under the same
load generator, as containers on one podman network on one machine (16 vCPU WSL VM, Windows 11,
2026-10-07): the benchmark module's `bench.Orders` served with reflection; `POST
/bench.Orders/Get` with `{"id":"order-1"}`, a 3-item order back; `oha` at 32 connections for 30 s
after a 10 s warm-up. `shiguchi-benchmarks/comparison/compare.sh` runs it; Envoy
(`envoyproxy/envoy:v1.34.1`) takes the descriptor set with `auto_mapping`, grpc-gateway (Go,
v2.31) is generated with `generate_unbound_methods`, this gateway boots the auto-mapped routes
with the UI, GraphQL and OpenAPI surfaces off and otherwise its defaults.

| | req/s | p50 | p90 | p99 |
| --- | --- | --- | --- | --- |
| Envoy gRPC-JSON transcoder | 37 900 | 0.77 ms | 1.29 ms | 1.96 ms |
| Shiguchi (JVM, 512 MB heap) | 18 700 | 1.60 ms | 2.36 ms | 3.70 ms |
| grpc-gateway (Go) | 17 000 | 1.76 ms | 2.77 ms | 4.02 ms |

All three share the CPU with each other's idle processes, the backend and the load generator,
so read ratios, not absolutes: Envoy's C++ transcoder is about twice the throughput of either
application-level gateway at half the latency, and this gateway and the Go one are within
10% of each other. Envoy transcodes a fixed descriptor; the two gateways run the request
through an application framework, and on this one that framework — the Spring Security chain,
Reactor's operator plumbing, Micrometer — is most of the per-request cost
([benchmarks](../shiguchi-benchmarks/README.md#the-gateway-over-http-µsrequest-jdk-25-windows-11-loopback-2026-10-06)).
`shiguchi.security.open-rest-bypasses-chain` and the observation switches take some of it
back; the GraphQL and OpenAPI surfaces, when on, are the rest of what the Go gateway and Envoy
do not have to carry.

## Browser sign-in (session cookie)

Signing a single-page app in without tokens in JavaScript is a separate concern from transcoding, and
the options differ:

| | Envoy OAuth2 filter | `shiguchi-bff` (this project, experimental) |
| --- | --- | --- |
| Session state | stateless: HMAC-signed or encrypted cookies | server-side store (memory, Redis, JDBC) behind an `HttpOnly` cookie |
| CSRF protection of its own | none | exact `Origin` match on cookie requests and WebSocket handshakes, optional custom header |
| Back-channel logout | no | yes (`sid` or `sub`) |
| Revoke at the issuer on logout, admin revoke-all | no | yes (RFC 7009), `BffSessions` and an actuator endpoint |
| Streams bound to the session | no | gRPC-Web, REST, GraphQL and push streams end with the session |
| Upstream credential | forwards the token it holds | the session's access token as `Authorization: Bearer`, refreshed once per session across instances |

[oauth2-proxy](https://oauth2-proxy.github.io/oauth2-proxy/) is the usual standalone answer: a
separate proxy in front of an application, with sessions in cookies or Redis. Check its documentation
for what it does on logout and CSRF.

Use a proxy when you want sign-in in front of an application that needs no gateway of its own; use
this module when the gateway already is your edge and you want revocation, logout propagation and
session-bound streams. It is a plain OIDC client of your issuer, not an identity provider
([bff.md](bff.md)).

---

## When *not* to use this

Kept deliberately specific, because a comparison page that concludes "always use us" is not worth
reading:

- **Your backends do not have protobuf descriptors available.** No reflection, no stubs on the
  classpath, no `.pb` file — there is nothing for the gateway to read.
- **You need a bidirectional-streaming REST endpoint.** One HTTP request cannot carry an
  interleaved conversation. The console and the gRPC proxy port handle bidi; REST does not.
- **Your REST API's shape is a product decision, not a projection of your RPCs.** If every
  endpoint needs hand-shaped payloads, aggregation logic and its own versioning, you want
  controllers (or a BFF), not a transcoder.
- **You are not on the JVM.** The whole value here is being a Spring Boot application. If you are
  not deploying one, grpc-gateway or Envoy will fit better.
- **Ultra-low-latency proxying is the requirement.** A JVM gateway adds a hop with JVM
  characteristics. The serde path is heavily optimised — compiled per-descriptor serializers, no
  intermediate JSON strings — but Envoy is still C++.

---

## What this project does that the others do not

For completeness, the features that motivated it rather than another deployment of grpc-gateway:

- **Several surfaces from one set of descriptors** — REST, GraphQL (with Apollo Federation),
  OpenAPI and a web console, all consistent by construction because they come from the same
  source.
- **Runtime schema discovery with live refresh** — `POST /actuator/gateway-refresh`, or a
  schedule; a failed refresh keeps the last good schema.
- **Response merging** — one REST or GraphQL call fanned out across several backends, with
  ordering, paging and failure policy. See [merging.md](merging.md).
- **Spring-native everything** — Spring Security for authentication, actuator for health and
  metrics, `@ConfigurationProperties` for configuration, bean SPIs for extension.
- **Backend-aware health and readiness** — the gateway reports which backends loaded and refuses
  traffic until they have, instead of serving 404s for routes it has not discovered.
- **A gRPC console for backends that have no HTTP binding at all** — useful well beyond
  transcoding.

---

Still deciding? [tutorial.md](tutorial.md) gets a gateway in front of a real backend in about ten
minutes, which is usually a faster answer than more reading.
