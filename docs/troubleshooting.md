# Troubleshooting & FAQ

Symptoms, what causes them, and how to confirm it. Every message quoted here is one the gateway
actually logs or returns — search this page for the text you are seeing.

**Two endpoints answer most questions.** Expose them first:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,gateway-routes
  endpoint:
    health:
      show-details: always
```

- `GET /actuator/gateway-routes` — every configured backend, whether it loaded, and every route
  it contributes with the exact HTTP binding.
- `GET /actuator/health` — per-backend load state, channel state, and the last error.

---

## A call returns 404

### Is the route there at all?

```bash
curl -s localhost:8080/actuator/gateway-routes | jq '.services[].routes[].http'
```

| What you see | What it means |
| --- | --- |
| The path, but with different `{vars}` | You are calling the wrong shape — `/v1/books/1` does not match `GET /v1/books/{isbn}/editions` |
| The service, but not that method | The method has no `google.api.http` option — see below |
| `"loaded": false` on the service | The backend never loaded; skip to [a backend will not load](#a-backend-will-not-load) |
| `"configured": 0` | The gateway has no services at all — your YAML is not being bound (wrong file, wrong prefix, or a typo above `shiguchi:`) |

### The method is missing from the list

A gRPC method gets a REST route **only** if its proto declares one:

```protobuf
rpc GetBook(GetBookRequest) returns (Book) {
  option (google.api.http) = { get: "/v1/books/{id}" };
}
```

No option, no route. That is by design — the gateway does not invent URLs. Either add the
annotation and restart the backend (`DYNAMIC` mode re-reads it), or call the method through the
web console, which lists every method regardless of bindings.

### A path with `%2F` answers 400

Spring Security's default firewall rejects an encoded slash before any route runs, so
`GET /v1/items/a%2Fb` is a 400 with an empty body. If path values legitimately contain `/`,
declare a `StrictServerWebExchangeFirewall` bean with `setAllowUrlEncodedSlash(true)` — see
[Path variable values](http-annotations.md#path-variable-values).

### The path has a trailing slash or a different prefix

`path-prefix` on the service is prepended to every binding in it. A service with
`path-prefix: /api` serves `GET /api/v1/books/{id}`, and `/v1/books/1` will 404.

---

## A backend will not load

The application starts anyway — deliberately, so one missing backend does not take the gateway
down — and reports it:

```bash
curl -s localhost:8080/actuator/health | jq '.components.shiguchi.details.services'
```

```json
{ "library": { "status": "DOWN", "target": "localhost:9090",
               "lastError": "UNAVAILABLE: io exception", "attempts": 3 } }
```

### `UNAVAILABLE: io exception`

Nothing is listening, or nothing that speaks gRPC. Check the host and port, and that the backend
is up. In Kubernetes, check you are using the right Service name and the gRPC port, not the HTTP
one.

### `UNIMPLEMENTED: Method not found: grpc.reflection.v1.ServerReflection/ServerReflectionInfo`

`mode: DYNAMIC` needs server reflection, and this backend does not serve it. Either:

- enable it on the backend — for Java, register `ProtoReflectionServiceV1.newInstance()`; or
- switch modes: `STATIC` reads compiled stubs from the gateway's classpath, `PB_FILE` reads a
  `FileDescriptorSet` produced by `protoc --descriptor_set_out`.

The gateway speaks the **v1** reflection service (`grpc.reflection.v1`). A backend that only
serves the older `v1alpha` service will not be discovered — upgrade its gRPC library, or use
`STATIC` / `PB_FILE`.

### `UNAUTHENTICATED` from `ServerReflection`

The backend requires credentials on every method, reflection included, and the gateway's
reflection call carries none: a caller's `authorization` header is forwarded on proxied calls
only. `static-metadata` would work for a fixed key, but it is attached to every call and cannot
follow an expiring token. Add the credentials with a `GrpcChannelBuilderCustomizer` instead,
scoped to the reflection methods so proxied calls keep the caller's own token:

```java
@Bean
GrpcChannelBuilderCustomizer reflectionCredentials(TokenSupplier tokens) {
    ClientInterceptor reflectionAuth = new ClientInterceptor() {
        @Override
        public <Q, R> ClientCall<Q, R> interceptCall(
                MethodDescriptor<Q, R> method, CallOptions options, Channel next) {
            if (!method.getFullMethodName().startsWith("grpc.reflection.")) {
                return next.newCall(method, options);
            }
            return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, options)) {
                @Override
                public void start(Listener<R> listener, Metadata headers) {
                    headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                            "Bearer " + tokens.currentToken());   // fetched per call, so it can rotate
                    super.start(listener, headers);
                }
            };
        }
    };
    return (service, builder) -> builder.intercept(reflectionAuth);
}
```

The customizer runs for every channel the gateway builds for the service, so this covers the
first load, the retries of a backend that was down at startup, and refresh. Use
`service.getName()` to limit it to some services. Do not add `authorization` to every call from
a customizer: gRPC metadata appends, so a proxied call would carry two `authorization` values.

### It loaded, then stopped working

Look at `channel` in the health details. `TRANSIENT_FAILURE` means the connection dropped — the
routes are still registered from the last successful load, so calls will fail rather than 404.
The gateway reconnects by itself; `gateway.services.loads{outcome="failed"}` counts the attempts.

### `{n} service(s) not loaded: [...]; retrying every {interval}`

Normal during startup when backends come up after the gateway. It stops on its own once they
load. If it never stops, the cause is in `lastError` above.

To refuse to start instead, set `startup.fail-fast: true`. To stop the background retry entirely,
`startup.retry-interval: 0`.

---

## The gateway reports DOWN or refuses traffic

### Readiness is `OUT_OF_SERVICE` but the app is running

That is the readiness policy doing its job: by default (`ALL_LOADED`) the gateway does not accept
traffic until every configured backend has loaded, so Kubernetes does not send requests it would
answer with 404. Options:

```yaml
shiguchi:
  health:
    readiness: ALL_LOADED   # default
    # ANY_LOADED — ready when at least one backend loaded
    # IGNORE     — backends never affect readiness
```

### `Refusing traffic: the readiness policy is no longer satisfied`

A refresh does not cause this. A backend that loaded once stays loaded: when a later refresh
fails, the gateway keeps the last good schema, stays ready, and reports the failure as `lastError`
on the backends health indicator. Calls to that backend can still fail, as in
[Health is `UP` but calls fail](#health-is-up-but-calls-fail).

Only a backend that has not loaded yet counts against the readiness policy, which is what
`Refusing traffic: readiness policy ...` reports at startup.

### Health is `UP` but calls fail

Check `shiguchi` specifically rather than the top-level status:

```bash
curl -s localhost:8080/actuator/health | jq '.components.shiguchi.status'
```

Spring's own `discoveryComposite` indicator reports `UNKNOWN` when a `DiscoveryClient` is present
but not one it knows how to probe, and other unrelated indicators can be `DOWN`. The one that
says whether the gateway can serve is `shiguchi`.

---

## JSON does not look how I expected

This is almost always the [proto3 JSON mapping](transcoding.md) rather than a bug.

| You see | Why |
| --- | --- |
| `"count": "42"` for an `int64` | 64-bit integers render as **strings** — JSON numbers cannot hold them exactly. Standard proto3 JSON. `shiguchi.json.int64-as: NUMBER` writes numbers when your values stay below 2^53 ([json-types.md](json-types.md#64-bit-integers)). |
| Fields you never set, with `0`/`""`/`false` | Scalars are always emitted, matching `JsonFormat.printer().includingDefaultValueFields()`. Fields with explicit presence (message, `optional`, `oneof`) are omitted when unset. `shiguchi.json.print.omit-default-values: true` leaves them out ([json-types.md](json-types.md#response-layout-print-options)). |
| `fieldName` where the proto says `field_name` | proto3 JSON uses `lowerCamelCase` by default. `shiguchi.json.print.preserve-proto-field-names: true` (globally or per service) keeps the proto names. |
| A timestamp as `"2026-09-17T22:53:51.879Z"` | Well-known types render as their JSON forms, not as `{seconds, nanos}`. |
| A `google.type.Date` as `"2026-09-22"`, not `{year, month, day}` | The gateway writes the common `google.type` messages as ISO strings (`shiguchi.json.scalar-types.*`). Switch a type off to get the proto3 object form back — [json-types.md](json-types.md). |
| A `google.type.Date` still as `{year, month, day}` | The message also reaches `google.protobuf.Any`, so `JsonFormat` prints it, and it cannot apply the mapping. Or the type is switched off. |
| `400 Invalid google.type.Date value '2026-02-30'` (or `google.protobuf.Timestamp`, `Int32Value`, ...) | Well-known and mapped types are validated on input rather than silently dropped; the message names the type, the value and what was expected. |
| An enum as its name, not its number | Also standard. |
| `1.0` for a number set to `1`, or `1.0E20` | `double`, `float` and `google.protobuf.Value` numbers print with Java's `Double.toString`. Valid proto3 JSON, same value; only the layout differs from `JSON.stringify` ([json-types.md](json-types.md#doubles-floats-and-googleprotobufvalue-numbers)). |
| A size, hash or `ETag` that differs from the client's | The JSON text is not canonical; compare or measure parsed values, not bytes ([json-types.md](json-types.md#comparing-json-text)). |

---

## Startup fails with a configuration error

The configuration is validated while Spring binds it, so a mistake stops the application with the
property path and the reason rather than failing mysteriously later:

```
Property: shiguchi.services[0].name
Reason: every service needs a name
```

Common ones, verbatim:

| Message | Fix |
| --- | --- |
| `every service needs a name` | Each entry under `services:` needs `name:` |
| `service name 'x' is used more than once` | Names are the key for routing and metrics; make them unique |
| `a gRPC backend needs one of host + port, discovery-name or target` | Pick exactly one addressing style |
| `port must be between 1 and 65535 when host is set` | Usually a missing `port:` |
| `mode PB_FILE needs the path of a FileDescriptorSet` | Set `pb-descriptor-path` |
| `mode REST_DOWNSTREAM needs the url of the backend's OpenAPI document` | Set `openapi-url` |
| `target must be a gRPC target such as dns:///host:port or host:port` | `http://` is not a gRPC target |
| `path-prefix must start with '/'` | Leading slash required |
| `client.discoveryRefresh must be at least 100ms` | Guard against hammering the registry |

### A property seems to be ignored

In a `@SpringBootTest`, setting one indexed property replaces the **whole list**: specifying
`shiguchi.services[0].host` alone drops the `name` and `mode` you set in YAML, and the
validator will then reject the entry. Restate the whole service in the test's properties.

---

## Service discovery

### `Discovery returned no instances for service id 'x'`

The registry has nothing registered under that id. Check the id matches what the backend
registers (it is often the Spring application name, not the Kubernetes Service name), and that
the instances are passing their own health checks.

Calls fail with `UNAVAILABLE` (HTTP 503) rather than hanging — an empty registry is reported, not
waited on.

### Calls all go to one instance

`load-balancing-policy` defaults to `round_robin` for `discovery-name` and `target`, but to
gRPC's `pick_first` for plain `host` + `port` — a single address has nothing to balance over. If
you are using a Kubernetes ClusterIP Service, note it resolves to **one** virtual IP: use a
headless Service (`clusterIP: None`) with `target: dns:///...`, or `discovery-name` so the
gateway sees the pods.

### The instance count is wrong

`gateway.discovery.instances{service="..."}` is the gauge. It updates every
`client.discovery-refresh` (30 s by default) and immediately after a connection failure.

---

## Performance

### First call to a method is slow

Descriptors are compiled to serializers on first use.
`gateway.descriptor.cache.misses / .lookups` should fall to near zero once traffic is warm; if
the miss rate stays high, something is rebuilding descriptors per call.

### A request sometimes gets no answer until the client gives up

On Spring Boot 3.5, a request on a kept-alive HTTP/1.1 connection can sit unread on
the server until the client times out and closes the connection. The access log then shows the
request handled, quickly, at the moment the client gave up, and no event loop is busy. This is
[reactor/reactor-netty#4361](https://github.com/reactor/reactor-netty/issues/4361): when a
request queued behind a still-running exchange is answered at once, the connection is left with
no read requested. It is fixed in Reactor Netty 1.3.8, which Spring Boot 4.1.2 and later bring.
Boot 3.5 uses Reactor Netty 1.2.x, which has no fix.

On Boot 3.5, one request per connection avoids it, at the cost of a new connection per request:

```yaml
server:
  netty:
    max-keep-alive-requests: 1
```

### Large responses are slow

Check whether you are asking for the whole message. The gateway supports field projection, and a
projected conversion never costs more than the full one — see
[projections-paging-sorting.md](projections-paging-sorting.md).

---

## FAQ

**Do I need the .proto files in my gateway project?**
No, and that is the point. `DYNAMIC` mode asks the backend over reflection at startup. Only
`STATIC` needs compiled stubs on the classpath, and only `PB_FILE` needs a descriptor file.

**Do I need to redeploy the gateway when a backend adds a method?**
No. `POST /actuator/gateway-refresh` re-runs discovery, or set `refresh.enabled: true` for a
schedule. A failed refresh keeps the last good schema.

**Does it support streaming?**
Server streaming: yes, as NDJSON over REST and as GraphQL subscriptions over WebSocket. Client
streaming: yes, as an NDJSON request body. Bidirectional: not over REST — one HTTP request cannot
carry an interleaved conversation — but the console and the gRPC proxy port handle it.

**Can I put it in front of a plain REST service?**
Yes: `mode: REST_DOWNSTREAM` reads the backend's OpenAPI document and proxies it, optionally
wrapping it in the same GraphQL schema. See the `sample-rest-downstream` sample.

**Boot 3 or Boot 4?**
Both, as parallel artifact lines (`spring-boot3` / `spring-boot4` in the id). Every
`shiguchi.*` property is identical; pick the one matching your Spring Boot version.

**Is the web console safe to expose?**
It is an RPC client holding your gateway's credentials — it can call every method on every
backend. Never expose it with `security.mode: PERMIT_ALL` on an untrusted network. Put it behind
authentication (`shiguchi.security.surfaces.ui.require-auth: true`, which overrides the
global mode for that surface alone) or leave it off outside development.

**Why is my bearer token readable on the wire?**
A service definition is `insecure: true` by default, and the caller's `Authorization` header is
forwarded to backends. The gateway warns at startup when any surface authenticates and a backend
has no `tls` block, naming the services. Plaintext to a mesh sidecar is legitimate; plaintext
across a network is not.

**How do I see what changed after a refresh?**
`gateway.routes` is a live gauge, and `gateway.services.loads` is tagged `initial` vs `reload`.
A backend reloading every few seconds is a refresh loop — invisible on a health check, obvious on
that counter.

---

Still stuck? Open an issue with the output of `/actuator/gateway-routes` and the
`shiguchi` section of `/actuator/health` — those two answer most questions immediately.
