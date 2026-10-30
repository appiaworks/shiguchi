# Tutorial: from a gRPC service to REST, GraphQL and OpenAPI

A walkthrough that starts with a gRPC backend you already have and ends with a gateway serving
REST, GraphQL, an OpenAPI document and a web console in front of it — adding one piece at a
time, and explaining what each one actually did.

The [quick start](../README.md#quick-start) is the five-minute version. This is the one that
explains why each step is there, and what to do when a step does not do what you expected.

**What you need:** JDK 21+, and a gRPC service with
[server reflection](https://grpc.io/docs/guides/reflection/) enabled. If you do not have one to
hand, [`samples/sample-rest-gateway`](../samples/sample-rest-gateway) runs one in-process and you
can read along.

---

## 1. A gateway that does nothing yet

Start a plain Spring Boot application with one dependency:

```kotlin
dependencies {
    implementation("io.github.appiaworks:shiguchi-spring-boot3-starter:0.14.1")
}
```

(Boot 4: the same artifact with `spring-boot4` in the id. Every `shiguchi.*` property below
is identical on both lines.)

```yaml
# application.yml
server:
  port: 8080

shiguchi:
  services:
    - name: library
      host: localhost
      port: 9090
      mode: DYNAMIC
```

That is the whole gateway. There is no generated code to write, no controller, and no proto file
in this project — `mode: DYNAMIC` means the gateway asks your backend what it serves, using gRPC
server reflection, at startup.

Run it. In the log you should see a line per backend:

```
Loaded 3 routes for service 'library'
```

If instead you see the application start and report `DOWN`, that is the designed behaviour for a
backend that is not reachable yet — skip to [the backend is not there](#the-backend-is-not-there).

## 2. Where did the routes come from?

Ask the gateway what it is serving:

```bash
curl -s localhost:8080/actuator/gateway-routes | jq
```

(Expose it first with `management.endpoints.web.exposure.include: health,gateway-routes`.)

```json
{
  "configured": 1, "loaded": 1, "routeCount": 3,
  "services": [
    { "name": "library", "mode": "DYNAMIC", "target": "localhost:9090",
      "loaded": true, "channel": "READY", "routeCount": 3,
      "routes": [
        { "method": "library.LibraryService/GetBook", "type": "UNARY",
          "http": "GET /v1/books/{id}" }
      ] }
  ]
}
```

Each route came from a `google.api.http` option on the method:

```protobuf
rpc GetBook(GetBookRequest) returns (Book) {
  option (google.api.http) = { get: "/v1/books/{id}" };
}
```

**A method without that option gets no REST route.** That is the single most common reason a call
404s, and the endpoint above is how you check: if the route is not in that list, no amount of
getting the URL right will help. The full binding syntax — `body`, `additional_bindings`, nested
field paths — is in [http-annotations.md](http-annotations.md).

Now call it:

```bash
curl -s localhost:8080/v1/books/1
# {"id":"1","title":"The Pragmatic Programmer","author":"Hunt & Thomas","year":1999}
```

What happened: the gateway matched the path, pulled `id` out of it into a `GetBookRequest`,
called `library.LibraryService/GetBook` over gRPC, and converted the `Book` reply to JSON
following the [proto3 JSON mapping](transcoding.md) — which is why `int64` fields come back as
strings, and why fields you never set are still present with their defaults.

## 3. Add OpenAPI and the console

```kotlin
implementation("io.github.appiaworks:shiguchi-openapi-spring-boot3-starter:0.14.1")
implementation("io.github.appiaworks:shiguchi-ui-spring-boot3-starter:0.14.1")
```

```yaml
shiguchi:
  openapi:
    enabled: true
  ui:
    enabled: true      # see the warning below
```

- `GET /v3/api-docs` — an OpenAPI 3 document generated from the same descriptors the routes came
  from, so it cannot drift from what the gateway actually serves.
- `GET /swagger-ui.html` — the usual Swagger UI over that document.
- `GET /grpc-ui` — a console that lists every discovered method, including ones with **no** HTTP
  binding, and lets you invoke them. See [ui.md](ui.md).

> **The console is an RPC client with your gateway's credentials.** It can call every method on
> every backend. `ui.enabled: true` with `security.mode: PERMIT_ALL` puts that on the open
> internet. Enable it behind authentication, or only outside production — see
> [Security](#6-lock-it-down) below.

## 4. Add GraphQL

```kotlin
implementation("io.github.appiaworks:shiguchi-graphql-spring-boot3-starter:0.14.1")
```

```yaml
shiguchi:
  graphql:
    enabled: true
```

A schema is generated from the same descriptors: messages become types, unary methods become
queries or mutations. Without annotations the gateway maps by convention — a method whose name
starts with `create`, `update`, `delete` or `upsert` (case-insensitively) becomes a **mutation**,
and everything else a **query**. So `GetBook` and `ListBooks` are queries without you saying so,
but `ArchiveBook` would also be a query, which is probably not what you meant.

Say what you actually want with `(shiguchi.api.graphql)`:

```protobuf
rpc ListBooks(ListBooksRequest) returns (ListBooksResponse) {
  option (google.api.http) = { get: "/v1/books" };
  option (shiguchi.api.graphql) = { name: "books", type: QUERY };
}
```

```bash
curl -s localhost:8080/graphql -H 'content-type: application/json' \
  -d '{"query":"{ books { books { id title } } }"}'
```

Server-streaming methods become GraphQL subscriptions over WebSocket. Details and the full
annotation set: [graphql-annotations.md](graphql-annotations.md).

## 5. Make it survive a restart of the backend

Everything so far assumed the backend was up when the gateway started. In production it will not
always be, and this is where gateways usually behave badly.

```yaml
shiguchi:
  startup:
    retry-interval: 30s     # keep retrying backends that were not there at startup
  health:
    readiness: ALL_LOADED   # do not accept traffic until every backend has loaded
```

With this, a gateway that starts before its backends does not serve 404s for routes it has not
discovered yet: it reports `OUT_OF_SERVICE` on `/actuator/health/readiness`, keeps retrying, and
flips to ready by itself once the schema loads. Kubernetes holds traffic off the pod until then.

If a backend runs as several instances, do not name one of them — ask your registry:

```yaml
shiguchi:
  services:
    - name: library
      discovery-name: library-service   # instead of host + port
      mode: DYNAMIC
      load-balancing-policy: round_robin
```

Calls then spread across every registered instance, and the list is re-read as instances come and
go. A runnable version of this, with three backends and responses that say which one answered, is
[`samples/sample-discovery-gateway`](../samples/sample-discovery-gateway); the reference is
[service-discovery.md](service-discovery.md).

## 6. Lock it down

The default is `PERMIT_ALL` — deliberately, so the first run works with no identity provider.
It is not what you deploy.

```yaml
shiguchi:
  security:
    mode: AUTHENTICATED
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://auth.example.com/realms/main
```

Every surface now needs a valid bearer token, and the caller's `Authorization` header is
forwarded to the backends. Per-surface overrides (public REST, authenticated console) and
per-method scopes from proto annotations, claim mapping and TLS to backends are in
[security.md](security.md).

Two things worth doing at the same time:

- **Rate limiting.** A gateway is one hop in front of everything you own; without a limit, one
  caller can exhaust every backend. [rate-limiting.md](rate-limiting.md).
- **TLS to the backends.** A service definition is `insecure: true` by default, so a forwarded
  bearer token is readable on that hop. The gateway warns about this at startup when any surface
  authenticates; the warning names the services.

## 7. Watch it

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,gateway-routes
```

- **`/actuator/health`** — one entry per backend: loaded or not, since when, how many routes,
  channel state, and the last error when it failed.
- **`/actuator/gateway-routes`** — the endpoint from step 2. The first thing to look at when a
  call 404s.
- **Metrics** — `grpc.client.*` for call rates and latency per service and method,
  `gateway.routes` and `gateway.services.loads` for what the gateway itself is doing. A backend
  quietly failing to reload while it keeps serving its last-known routes is `UP` on the health
  check and visible only here.

[observability.md](observability.md) has the full metric list and what to alert on.

---

## Where to go next

| If you want to | Read |
| --- | --- |
| Understand the JSON your callers see | [transcoding.md](transcoding.md) |
| Shape REST paths, bodies, query parameters | [http-annotations.md](http-annotations.md) |
| Control the GraphQL schema | [graphql-annotations.md](graphql-annotations.md) |
| Return useful errors | [error-handling.md](error-handling.md) |
| Page, sort and project large lists | [projections-paging-sorting.md](projections-paging-sorting.md) |
| Combine several backends into one response | [merging.md](merging.md) |
| Survive a flaky backend | [resilience.md](resilience.md) |
| Every property there is | [configuration.md](configuration.md) |
| Something is not working | [troubleshooting.md](troubleshooting.md) |

---

## Appendix: when a step does not work

### The backend is not there

The application starts anyway — that is deliberate — and reports the failure rather than dying:

```bash
curl -s localhost:8080/actuator/health | jq '.components.shiguchi'
```

```json
{ "status": "DOWN",
  "details": { "configured": 1, "loaded": 0,
    "services": { "library": { "status": "DOWN",
      "lastError": "UNAVAILABLE: io exception", "attempts": 3 } } } }
```

It keeps retrying every `startup.retry-interval` and recovers on its own. Set
`startup.fail-fast: true` if you would rather the application refuse to start.

### The route is not there

`GET /actuator/gateway-routes` and look for the method. If the service loaded but your method is
missing, it has no `google.api.http` option — add one, or call it through the console, which does
not need a binding.

### Reflection is not enabled on the backend

`DYNAMIC` mode needs it. The failure looks like:

```
UNIMPLEMENTED: Method not found: grpc.reflection.v1.ServerReflection/ServerReflectionInfo
```

Either enable reflection on the backend (`ProtoReflectionServiceV1` for Java) or switch to a mode
that does not need it: `STATIC` reads compiled stubs off the gateway's classpath, `PB_FILE` reads
a `FileDescriptorSet` you build with `protoc`. See [configuration.md](configuration.md).

More failure modes, with the exact messages: [troubleshooting.md](troubleshooting.md).
