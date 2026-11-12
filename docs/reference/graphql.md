# GraphQL reference

The GraphQL schema is generated from the same proto descriptors as the REST routes, shaped by the
`(shiguchi.graphql)`, `(shiguchi.field)`, `(shiguchi.type)` and `(shiguchi.method)` options. Option
fields are listed in [proto-options.md](proto-options.md); config keys in
[configuration.md](configuration.md).

## 1. Schema mapping

### Scalars and composites

| Proto | GraphQL |
| --- | --- |
| `string`, `bytes` | `String` |
| `int32`, `sint32`, `uint32`, `fixed32`, `sfixed32` | `Int` |
| `int64`, `sint64`, `uint64`, `fixed64`, `sfixed64` | `String` |
| `float`, `double` | `Float` |
| `bool` | `Boolean` |
| enum | `enum <SimpleName>` with the proto value names |
| message | `type <SimpleName>` (output), `<SimpleName>Input` (input) |
| `repeated T` | `[T!]` |
| `oneof` | fields emitted flat, no union or special handling |
| `map<K,V>` | no map-specific handling; appears as repeated `<Name>Entry` message |

Types are named by simple name (package dropped), so two messages with the same simple name in
different packages collide. Message nesting is capped at depth 10.

### Well-known and mapped types

| Proto type | GraphQL |
| --- | --- |
| `google.protobuf.Any`, `Struct`, `Value` | `JSON` |
| `google.protobuf.ListValue` | `[JSON]` |
| `google.protobuf.Timestamp` | `DateTime` |
| `google.protobuf.Empty` | `Void` |
| `Duration`, `FieldMask`, wrapper types | `String` |
| `google.type.Date` | `LocalDate` |
| `google.type.TimeOfDay` | `LocalTime` |
| `google.type.Decimal` | `Decimal` |
| `google.type.DateTime` | `String` |
| `google.type.Money` | `JSON` |
| type with a custom `ProtoScalarCodec` | its `graphQlName`, else chosen by shape |

`LocalDate`, `LocalTime` and `Decimal` are declared only when `shiguchi.json.scalar-types` enables
them. `JSON`, `DateTime` and `Void` are always declared.

### Operation type and field name

| Case | Result |
| --- | --- |
| `(shiguchi.graphql).name` set | field name = `name`; `type` selects QUERY (default), MUTATION, SUBSCRIPTION or RESOLVER |
| no `name` | convention: service name without `Service` suffix, first letter lowercased, plus method name capitalised (`bookGetBook`) |
| convention op type | MUTATION if the method name starts with `create`, `update`, `delete` or `upsert` (case-insensitive), else QUERY |
| per-service GraphQL disabled | the service's methods are not exposed |

### Return types

| Case | Return type |
| --- | --- |
| server-streaming RPC that is not a SUBSCRIPTION | `[T!]` |
| SUBSCRIPTION | one item per event |
| response `pluck` | the plucked field's type (list if repeated) |
| response with a repeated message field and `page_info` | Relay `<X>Connection`; shared `PageInfo` type |
| no queries in the schema | `_placeholder: Boolean` on `Query` |

### Arguments

| Case | Result |
| --- | --- |
| `page_request`, `sort_fields`, `read_mask` request fields | removed from user args; pagination args are injected instead |
| `arg_fields` set (entries may be comma separated) | only those request fields become top-level args |
| MUTATION | `input: <Request>Input!` (when the request has user fields) |
| QUERY, all fields scalar, at most 5 | one argument per field |
| QUERY otherwise | `input: <Request>Input` (nullable) |

Injected arguments: `first: Int, after: String` when the request has `page_request`, and
`sort: [SortFieldInput]` when it has `sort_fields`. The shared types are `input SortFieldInput { field:
String!, sortOrder: SortOrder }` and `enum SortOrder { SORT_ORDER_UNSPECIFIED SORT_ORDER_ASC
SORT_ORDER_DESC }`.

Field-level options on input fields: `default` emits `= value`, `required` adds `!`, `id` forces
`ID`, `omit` hides, `name` renames. YAML `fieldOverrides` (`TypeName.protoFieldName`) win over
proto annotations per field.

## 2. Annotation options

Full field lists: [proto-options.md](proto-options.md).

| Option | Effect on the GraphQL surface |
| --- | --- |
| `(shiguchi.graphql)` `name`, `type` | root field name and operation type |
| `description`, `deprecation_reason` | field docstring, `@deprecated(reason:)`; description falls back to the proto comment |
| `parent_type` | attaches the field to another type (RESOLVER) |
| `arg_fields` | restricts and flattens arguments |
| `pluck` | returns a nested field of the response |
| `loader_for`, `loader_ids_field`, `loader_result_field` | DataLoader batching; ids field is inferred when empty |
| `cost` | weight of the field in the cost calculation (see 4) |
| `directives` | raw directives appended to the root field |
| `live`, `invalidated_by` | live query behaviour (see 6) |
| `(shiguchi.field)` `required`, `id`, `omit`, `name`, `default` | input field shape |
| `resolves_type` | adds a companion resolver field returning that type |
| `batch_key` | DataLoader key; also federation key fallback |
| `cost` | recorded as `Type.field` weight |
| `shareable`, `external`, `requires`, `provides` | federation directives (only with federation on) |
| `directives` | raw directives appended to the field |
| `(shiguchi.type)` `keys`, `unresolvable` | federation keys; `unresolvable` emits `resolvable: false` |
| `(shiguchi.type)` `description`, `directives` | type docstring, raw directives |
| `(shiguchi.method)` `cache_ttl_seconds` | makes the method cacheable by the response cache |
| `(shiguchi.method)` `auth_scopes` | forces per-caller cache keys |

With federation off, federation options log a warning once and are ignored. `requires` and
`provides` are validated as selection sets at build time.

## 3. Error extensions

Selected by `shiguchi.graphql.error-format` (`NATIVE` default since 0.21, `GRPC`). Applies to
`GrpcBackendException` or `StatusRuntimeException` found in the cause chain; other exceptions go to
the default DGS handler. The message is the status description, else the status code name.

| Key | GRPC | NATIVE | Value |
| --- | --- | --- | --- |
| `grpcCode` | yes | yes | gRPC status code name |
| `errorType` | yes | yes | see mapping below |
| `service`, `method` | yes | yes | when known |
| `code` | no | yes | see mapping below |
| `details` | yes (proto3 JSON with `@type`, if non-empty) | non-BadRequest details only | |
| `fieldErrors` | no | yes, for a `BadRequest` | `[{field, message, rule?}]` |

`fieldErrors[].field` is the GraphQL argument path: snake_case becomes camelCase, prefixed with
`input.` when the field has an `input` argument and the path does not start with a declared
argument. `rule` is the violation reason and is omitted when empty.

NATIVE example:

```json
{"message": "name is required", "path": ["createBook"],
 "extensions": {"code": "BAD_USER_INPUT", "grpcCode": "INVALID_ARGUMENT", "errorType": "BAD_REQUEST",
   "service": "bookstore.BookService", "method": "CreateBook",
   "fieldErrors": [{"field": "input.name", "message": "must not be empty", "rule": "required"}]}}
```

GRPC example:

```json
{"message": "name is required", "path": ["createBook"],
 "extensions": {"grpcCode": "INVALID_ARGUMENT", "errorType": "BAD_REQUEST",
   "service": "bookstore.BookService", "method": "CreateBook",
   "details": [{"@type": "type.googleapis.com/google.rpc.BadRequest"}]}}
```

The examples are illustrative. Each `details` entry is the detail message as proto3 JSON with its
`@type`; only the `@type` is shown above. The error is a DGS `TypedGraphQLError`, so DGS also adds
its own `classification` key to `extensions`.

| gRPC status | `code` (NATIVE) | `errorType` |
| --- | --- | --- |
| INVALID_ARGUMENT, OUT_OF_RANGE | `BAD_USER_INPUT` | `BAD_REQUEST` |
| FAILED_PRECONDITION, ALREADY_EXISTS, ABORTED | `BAD_USER_INPUT` | `FAILED_PRECONDITION` |
| UNAUTHENTICATED | `UNAUTHENTICATED` | `UNAUTHENTICATED` |
| PERMISSION_DENIED | `FORBIDDEN` | `PERMISSION_DENIED` |
| NOT_FOUND | `NOT_FOUND` | `NOT_FOUND` |
| UNAVAILABLE, DEADLINE_EXCEEDED, RESOURCE_EXHAUSTED, CANCELLED | `SERVICE_UNAVAILABLE` | `UNAVAILABLE` |
| UNKNOWN | `INTERNAL_SERVER_ERROR` | `UNKNOWN` |
| any other | `INTERNAL_SERVER_ERROR` | `INTERNAL` |

## 4. Limits

All off by default. Keys under `shiguchi.graphql.limits`.

| Limit | Key | Behaviour |
| --- | --- | --- |
| Depth | `max-depth` | graphql-java `MaxQueryDepthInstrumentation`; `0` = off; the error message contains `maximum query depth exceeded` |
| Complexity | `max-complexity` | refused with `maximum query complexity exceeded <cost> > <max>` (ValidationError) |
| Cost budget | `budget.per-minute` | token bucket per caller; see below |
| List size | `default-list-size` | multiplier for list fields without a `first` argument; minimum 1 |
| Cost report | `report-cost` | puts `extensions.cost` on the response |

Cost of a field is `weight * multiplier + childCost`. Weight comes from the `cost` option (default
1). The multiplier applies to list-typed fields (including non-null lists): the field's own `first`
argument if greater than 0, else the parent field's `first`, else `default-list-size`. The total is
capped at `Integer.MAX_VALUE`. The cost is also in the GraphQL context under
`shiguchi.graphql.cost`.

Budget: the bucket has capacity `per-minute` and refills at `per-minute / 60` tokens per second.
An operation is charged `max(1, min(cost, per-minute))`. The caller key follows
`shiguchi.rate-limit.key` (`IP`, `PRINCIPAL`, `PRINCIPAL_OR_IP`; default `PRINCIPAL_OR_IP`) and
`client-ip-header`; a request with no key is not charged. State is in memory, or Redis when
`shiguchi.rate-limit.backend=REDIS`. On exhaustion the error message is
`query cost budget exhausted: retry in <n> s`, classification `RATE_LIMITED`, with
`extensions.retryAfter` and a `Retry-After` header. The HTTP status stays 200.

## 5. Response cache

`shiguchi.graphql.response-cache.enabled`. Per-instance Caffeine LRU; no cross-instance
invalidation; emptied on schema reload.

| Aspect | Rule |
| --- | --- |
| Cached | queries whose every root field's method has `cache_ttl_seconds > 0`; TTL = minimum |
| Not cached | unknown root field, root-level fragments (opaque), subscriptions, WebSocket, requests accepting `multipart/mixed`, responses with errors; multi-operation documents need `operationName` |
| Key | SHA-256 of document, operationName, canonical variables, principal |
| Principal part | name (or `anonymous`) when `vary-by-principal` is true or any root method has `auth_scopes`; else empty |
| Mutations | always run, then evict entries built from the services behind their root fields; opaque root evicts all |
| Stale | within `stale-while-revalidate` past TTL the entry is served and one background refresh runs |

| Header | Value |
| --- | --- |
| `Cache-Status` | `shiguchi; hit`, `shiguchi; hit; detail=stale`, `shiguchi; fwd=miss; stored`, `shiguchi; fwd=miss` |
| `Age` | seconds, on a hit |

Metric: counter `shiguchi.graphql.response.cache`, tag `result=hit|stale|miss`.

## 6. Features

### Incremental delivery (`@defer`, `@stream`)

`shiguchi.graphql.incremental.enabled`. Declares `directive @defer(if: Boolean! = true, label: String)
on FRAGMENT_SPREAD | INLINE_FRAGMENT`. A POST to the GraphQL path with `Accept: multipart/mixed` and a
JSON object body is answered as `multipart/mixed; boundary="-"; deferSpec=20220824`: the initial
`{data, hasNext}` part, then `{incremental: [{path, label, data}], hasNext}` parts. When nothing was
deferred the answer is plain JSON. A client that does not accept `multipart/mixed` gets the complete
JSON result. Not applied to cached responses, batches or WebSocket. Request bodies over 256 KiB get
413. `@stream` is not supported by the engine.

### Live queries (`@live`)

`shiguchi.graphql.live.*`. Declares `directive @live(throttleMs: Int) on QUERY`. The operation runs,
then again when a mutation through this gateway touches a service behind its root fields, sending
each full result. WebSocket (`graphql-transport-ws`) and SSE only; invalidation reaches this instance
only.

| Key | Meaning |
| --- | --- |
| `min-interval` | least time between runs; a larger `throttleMs` hint is kept |
| `max-per-principal` | concurrent live queries per principal; next is refused with `LIVE_LIMIT` |
| `max-total` | concurrent live queries on this instance |
| `refresh-interval` | rerun after this long without news; zero = never |
| `max-concurrent-reexecutions` | reruns in flight; the rest queue in order |

`invalidated_by` (on `(shiguchi.graphql)`, `repeated string`) names Cursus topics whose events re-run the
live queries selecting that method. Topics must be exact (`namespace:name`); a wildcard entry is skipped
with a warning. It needs the push module (`shiguchi.cursus.enabled`). The routes are re-read every 30 s,
and each instance reacts to the event itself (the result is not relayed).

### Subscriptions

Methods with `(shiguchi.graphql).type = SUBSCRIPTION` emit one item per stream message. They are served
over WebSocket (`graphql-transport-ws`) only when `spring.graphql.websocket.path` is set (the end-to-end
test uses `/graphql/ws`); there is no default path. `GraphQlContributor` beans (the push module) can add
Subscription fields and types.

### Batching

`shiguchi.graphql.batch.max-operations` (0 = off). A POST whose JSON body is an array runs each
element through the full interceptor chain in order, and answers with an array of results in the same
order. Limits, introspection and the cost budget apply per operation; the batch is charged the sum.
Each operation has its own DataLoader registry. A batch over the maximum, an empty batch, invalid
JSON or a non-object element gets 400; a body over 256 KiB times the maximum gets 413. `Retry-After`
is the largest among the results.

### Federation

`shiguchi.graphql.federation.enabled`. Declares scalars `_Any` and `_FieldSet`; directives `@key`,
`@external`, `@requires`, `@provides`, `@extends`, `@shareable`; `Query._service: _Service!`
(`type _Service { sdl: String! }`); `Query._entities(representations: [_Any!]!): [_Entity]!`; and
`union _Entity` over keyed types. Keys come from `(shiguchi.type).keys`, else the field with
`batch_key`. `_entities` resolves by the first single-field key; composite keys are published only.

### Introspection

`shiguchi.graphql.introspection`: `ENABLED` (default), `AUTHENTICATED` (non-anonymous principal
only), `DISABLED`. Decided per request. A refused operation that selects `__schema` or `__type` is
answered with a GraphQL error and not run.

### Other

| Feature | Key | Notes |
| --- | --- | --- |
| Document cache | `document-cache.*` | parsed and validated documents by query text; cleared on schema reload |
| Persisted queries | `persisted-queries.*` | SHA-256 registration; cleared on schema reload |
| DataLoader | `data-loader.*` | batch size, optional cross-request entity cache |

## 7. Configuration keys

All under `shiguchi.graphql`. See [configuration.md](configuration.md).

| Key | Default |
| --- | --- |
| `enabled` | `true` |
| `selection-projection` | `true` |
| `federation.enabled` | `false` |
| `limits.max-depth` | `0` |
| `limits.max-complexity` | `0` |
| `limits.default-list-size` | `10` |
| `limits.report-cost` | `false` |
| `limits.budget.per-minute` | `0` |
| `error-format` | `NATIVE` (`GRPC`, `NATIVE`) |
| `introspection` | `ENABLED` (`ENABLED`, `AUTHENTICATED`, `DISABLED`) |
| `incremental.enabled` | `false` |
| `live.enabled` | `false` |
| `live.min-interval` | `PT1S` |
| `live.max-per-principal` | `20` |
| `live.max-total` | `10000` |
| `live.refresh-interval` | `PT5M` |
| `live.max-concurrent-reexecutions` | `32` |
| `batch.max-operations` | `0` |
| `document-cache.enabled` | `false` |
| `document-cache.max-entries` | `2000` |
| `document-cache.expire-after-access` | `PT1H` |
| `persisted-queries.enabled` | `false` |
| `persisted-queries.max-entries` | `1000` |
| `persisted-queries.expire-after-access` | `PT1H` |
| `response-cache.enabled` | `false` |
| `response-cache.max-entries` | `2000` |
| `response-cache.stale-while-revalidate` | `PT0S` |
| `response-cache.vary-by-principal` | `true` |
| `data-loader.max-batch-size` | `0` (unlimited) |
| `data-loader.cache.<TypeName>.ttl` / `.max-size` | `PT1H` / `10000` (cache map empty by default) |
| `compat-options` | empty; only value `graphql-1079` |
