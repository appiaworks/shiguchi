# Request validation

Constraints declared in the proto with [protovalidate](https://github.com/bufbuild/protovalidate),
checked by the gateway before a call leaves, so an invalid request costs no round trip and comes
back as a 400 that names the field. The same engine can run as an interceptor inside your own
gRPC server, for the callers that do not come through the gateway.

```protobuf
import "buf/validate/validate.proto";

message CreateOrderRequest {
  string sku            = 1 [(buf.validate.field).string.pattern = "^[A-Z]{3}[0-9]{3}$"];
  int32  quantity       = 2 [(buf.validate.field).int32 = {gte: 1, lte: 100}];
  string customer_email = 3 [(buf.validate.field).string.email = true];
}
```

```yaml
shiguchi:
  validation:
    gateway-enabled: true
```

That is the whole setup. The rules travel with the descriptors the gateway already loads, so
nothing is declared twice and nothing in the gateway's configuration names a field.

## Setup

Add the validation starter next to the gateway starter:

```kotlin
implementation("io.github.appiaworks:shiguchi-validation-spring-boot3-starter:0.15.0")   // or -boot4-
```

It brings `build.buf:protovalidate`, which is both the engine and the jar that carries
`buf/validate/validate.proto`. The protobuf Gradle plugin extracts `.proto` files from
`compile`-scoped dependencies onto the include path by itself, so a project that has the starter
can `import "buf/validate/validate.proto"` with no further configuration. A backend that compiles
the same protos but does not depend on the starter needs `build.buf:protovalidate` for the same
reason.

| Property | Default | Meaning |
| --- | --- | --- |
| `shiguchi.validation.gateway-enabled` | `false` | Check constraints at the gateway, after the request is deserialised and before it is dispatched |
| `shiguchi.validation.server-enabled` | `false` | Expose a `ValidationServerInterceptor` bean for a gRPC server in the same application |
| `shiguchi.validation.log-violations` | `true` | WARN-log each rejection with the field paths and rule messages; field values are never logged |

## Where the check runs

`gateway-enabled` validates the request message on every surface that dispatches through the
gateway's dynamic dispatcher, once the surface has built the message and before the backend is
called:

| Surface | What is validated |
| --- | --- |
| REST, unary and server-streaming | the request built from path, query and body |
| REST, client- and bidi-streaming | every request message, one per newline-delimited JSON line, as it is read |
| GraphQL | the request built from the field's arguments, for queries, mutations, subscriptions and batched loaders |
| Web console | the message typed into the invoke view |
| gRPC-Web | the request message, parsed from the caller's frame only for this check; the backend still gets the original bytes. A violation answers trailers-only `INVALID_ARGUMENT` with the `google.rpc.BadRequest` in `grpc-status-details-bin`, before the backend is called |

Two surfaces are not covered, for different reasons:

- **The pass-through gRPC proxy** forwards the caller's bytes; it never builds a message, so
  there is nothing to validate. A backend behind the proxy validates for itself — see
  [Validating in the backend](#validating-in-the-backend).
- **`REST_DOWNSTREAM` services** have no proto. Their requests are proxied as received; the
  gateway does not validate them against the OpenAPI schema.

### Which schema modes

The rules are descriptor options, and the gateway reads descriptors in three ways. All three
work:

- **`STATIC`** — the compiled stubs on the gateway's classpath carry the options as known
  extensions.
- **`DYNAMIC`** and **`PB_FILE`** — descriptors arrive over reflection or from a
  `FileDescriptorSet` and are parsed with the gateway's own extension registry, which knows
  `google.api.http` and `(shiguchi.graphql.*)` but not `buf.validate`. The rule options therefore land as
  *unknown fields* on each descriptor. protovalidate expects exactly that: its rule resolver
  re-parses the unknown fields with its own registry, so nothing has to be registered on the
  gateway side and the backend needs no special build. The validation sample runs in `DYNAMIC`
  mode against a backend that only exposes reflection.

## What the caller sees

A violation is raised as `INVALID_ARGUMENT` and goes through the usual
[error mapping](error-handling.md): HTTP 400, the gateway's error body, and a
`google.rpc.BadRequest` detail with one entry per violation. `field` is the dotted path,
`reason` the protovalidate rule id, `description` the rule's message.

```json
{
  "code": "INVALID_ARGUMENT",
  "message": "sku: value does not match regex pattern `^[A-Z]{3}[0-9]{3}$`; quantity: value must be greater than or equal to 1",
  "details": [
    {
      "@type": "type.googleapis.com/google.rpc.BadRequest",
      "fieldViolations": [
        { "field": "sku",      "description": "value does not match regex pattern `^[A-Z]{3}[0-9]{3}$`", "reason": "string.pattern" },
        { "field": "quantity", "description": "value must be greater than or equal to 1",                "reason": "int32.gte" }
      ]
    }
  ]
}
```

Every violation is reported, not just the first, so a caller fixes a form in one round.

On GraphQL the same violation is one error with `errorType: BAD_REQUEST`,
`grpcCode: INVALID_ARGUMENT` and the same `details` array under `extensions`, and the field's
data is `null`. On a client- or bidi-streaming route the offending line fails the call as
`INVALID_ARGUMENT`; the messages before it have already reached the backend.

The rejection is logged at WARN on `dev.appiaworks.shiguchi.validation.ProtoValidator` as
`Proto validation failed: <the message above>`. That line contains field paths and rule
messages, never the submitted values — a rejected payload is often exactly the data you least
want in a log. Set `log-violations: false` to silence it.

## Writing rules

The engine is protovalidate's, unmodified, so every rule in its
[reference](https://buf.build/docs/protovalidate/) applies: the per-type standard rules
(`string.min_len`, `int32.lte`, `repeated.unique`, `timestamp.lt_now`, ...), `required`, and
CEL expressions on a field or a whole message for anything the standard rules cannot say:

```protobuf
message DateRange {
  option (buf.validate.message).cel = {
    id: "range.ordered"
    message: "from must not be after to"
    expression: "this.from <= this.to"
  };
  google.protobuf.Timestamp from = 1 [(buf.validate.field).required = true];
  google.protobuf.Timestamp to   = 2 [(buf.validate.field).required = true];
}
```

Two proto3 facts make rules more useful than they first look:

- **A missing scalar and its default are the same value.** `quantity: 0` and no `quantity` at
  all produce the same message, so an order for zero units is well-formed until a rule says
  `gte: 1`. `required` on a scalar means "not the default"; use a wrapper type or an `optional`
  field when zero is a legitimate value that must be distinguished from absent.
- **Rules are compiled once per message type**, on the first request that uses it, and cached
  for the life of the validator. The per-request cost afterwards is the rule evaluation itself.

## Where to validate

`gateway-enabled` and `server-enabled` are separate switches because they answer different
questions:

| | Protects | Costs |
| --- | --- | --- |
| `gateway-enabled` | callers coming through the gateway | one check at the edge, saving the round trip |
| `server-enabled` | **every** caller — other services, the proxy, the console, a `grpcurl` | one check per RPC in the backend |

Enabling both is normal. The gateway's check is a fast rejection with a helpful message; the
backend's is the one that actually guarantees the invariant, because the gateway is not the only
way in.

### Validating in the backend

`server-enabled: true` creates a `ValidationServerInterceptor` bean. Register it with the gRPC
server however that server takes interceptors:

```java
Server server = ServerBuilder.forPort(9090)
        .addService(ServerInterceptors.intercept(new OrderService(), validationServerInterceptor))
        .build();
```

With grpc-spring-boot-starter, expose the bean under `@GrpcGlobalServerInterceptor`; with Spring
gRPC, under `@GlobalServerInterceptor`. The interceptor validates each request message as it is
received and, on a violation, closes the call with `INVALID_ARGUMENT` and the same
`google.rpc.BadRequest` in the trailers. The service method never sees the message. Through the
gateway that trailer is decoded into the same 400 body shown above, so a caller cannot tell which
side refused — which is the point.

A plain gRPC service that has nothing to do with the gateway can still use this: the validation
starter depends on the validation module and `shiguchi-core` only, not on the REST surface,
so adding it and setting `server-enabled` brings the interceptor and nothing else.

## Your own validation

The gateway calls one `RequestValidationHook` bean, if there is one, at the point described
above. The starter's hook is protovalidate; a bean of your own runs instead of it:

```java
@Bean
RequestValidationHook tenantHook() {
    return (request, method) -> {
        var tenant = method.getInputType().findFieldByName("tenant_id");
        if (tenant != null && request.getField(tenant).toString().isBlank()) {
            throw Status.INVALID_ARGUMENT.withDescription("tenant_id is required").asRuntimeException();
        }
    };
}
```

Throw `StatusRuntimeException` with `INVALID_ARGUMENT` to reject; the same error path turns it
into a 400. Attach a `google.rpc.BadRequest` through `StatusProto.toStatusRuntimeException` to get
the structured `details`. Only one hook bean may exist — do not set `gateway-enabled` alongside
your own. To keep protovalidate *and* add a check, construct a `ProtoValidator` inside your hook
and call it first.

The hook runs on the request path, on the event loop: it must not block.

→ [`sample-validation`](../samples/sample-validation) runs all of this against an in-process
backend that validates nothing, so every rejection is visibly the gateway's ·
[error-handling.md](error-handling.md) for the error body and how to reshape it ·
[configuration.md](configuration.md) for the property reference
