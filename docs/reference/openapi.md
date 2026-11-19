# OpenAPI reference

Reference for the OpenAPI 3 document the gateway generates from the loaded descriptors. Derived from
`shiguchi-openapi` (`GatewayOpenApiBuilder`, `ProtoToOpenApiSchemaConverter`, `DownstreamSpecMerger`,
`OpenApiConfig`) and `shiguchi-core` (`config/OpenApiConfig`). The document cannot describe a route that is not
being served. Configuration keys are also listed in [configuration.md](configuration.md).

## 1. Where it is served

| Path | Format | Source |
| --- | --- | --- |
| `/v3/api-docs` | JSON | springdoc, with the generated paths and components merged in by an `OpenApiCustomizer` |
| `/v3/api-docs.yaml` | YAML (`application/yaml`) | `OpenApiConfig.openApiYamlRoute`, serialised from the generated document (`OpenApiCache`) |
| `/v3/api-docs/backends/{name}` | JSON | one document per backend (`shiguchi.services[].name`): its paths, its tags and only the schemas they reach, with the configured security schemes. Unknown name: 404 |
| `/swagger-ui.html` | Swagger UI | springdoc |

- Delivered by the `shiguchi-openapi-*-starter` (WebFlux). The beans are active when `shiguchi.openapi.enabled` is not
  `false` (default `true`).
- The `info` block is fixed: title `Shiguchi`, version `1.0`, description `Auto-generated from gRPC proto descriptors`.
  There is no config key for it.
- Reload: `OpenApiCache` is a `SchemaReloadListener`. After a schema reload the spec is rebuilt and the cached JSON
  and YAML are dropped.
- Swagger UI path: the console reads `springdoc.swagger-ui.path` (default `/swagger-ui.html`) to link to it.
  Springdoc's own properties apply to the UI and to `/v3/api-docs`.
- Security surface `openapi` covers `/v3/api-docs/**`, `/swagger-ui/**` and `/swagger-ui.html`
  (`shiguchi.security.surfaces.openapi.require-auth`). `/v3/api-docs.yaml` is not in that list (`/v3/api-docs/**` does not match it), so it is not gated by the `openapi`
  surface: it follows the `rest` surface (the chain's catch-all).
- CORS default paths include `/swagger-ui/**`, `/swagger-ui.html` and `/v3/api-docs/**`.
- Organisation: every service is a top-level tag (sorted, described by its proto comment or `<service> (<backend>)`),
  and every operation has a summary, taken from the proto comment or else the method name in words
  (`ListCorporationProjects` becomes "List corporation projects"). With two or more backends and
  `shiguchi.openapi.group-by-backend` (default `true`), Swagger UI gets a document dropdown ("All services" plus one
  entry per backend), unless `springdoc.swagger-ui.urls` is set. For a long list, springdoc's own keys help:
  `springdoc.swagger-ui.doc-expansion: none`, `filter: true`, `tags-sorter: alpha`, `operations-sorter: alpha`,
  `default-models-expand-depth: -1`.
- Authorize dialog: driven by `shiguchi.openapi.security-schemes` and `global-security` (section 5).

## 2. Generation rules

A service is skipped when `services[].openapi-enabled` (falls back to `shiguchi.openapi.enabled`) is false. Only
routes with a REST binding (`google.api.http`) become operations. Methods hidden by `include-methods` /
`exclude-methods` have no route and do not appear.

### Paths, verbs, operations

| Item | Rule |
| --- | --- |
| Path | `google.api.http` path; `{name=shelves/*}` is written `{name}` (pattern left to the backend). Dotted names stay dotted (`{book.id}`) |
| Verb | `GET`, `POST`, `PUT`, `PATCH`, `DELETE` (other verbs are not emitted) |
| `operationId` | The rpc name with the first letter lower-cased (`ListBooks` -> `listBooks`). Not prefixed by service for gRPC-backed routes |
| Tag | One per operation: the simple service name, kebab-cased and lower-cased (`LibraryService` -> `library-service`) |
| Summary / description | The route description when non-blank; summary is its first line. The description is the comment above the rpc (when the descriptors carry source info), overwritten by `(shiguchi.graphql).description` when that is set; for `REST_DOWNSTREAM` routes it is the backend operation's summary. Field descriptions are not emitted |

### Parameters and request body

| Case | Result |
| --- | --- |
| Path variable | `in: path`, `required: true`, `schema: {type: string}` (always string, whatever the field type) |
| `body: "*"` or unset on POST/PUT/PATCH | `requestBody` (required) with the whole input message as a `$ref` |
| `body: "<field>"` | `requestBody` (required) with that field's schema; the other non-path fields become query parameters |
| GET/DELETE (no body) | Every input field not bound by the path becomes `in: query`, `required: false`, named by its JSON name |
| `google.api.HttpBody` request | `*/*`, `type: string, format: binary` |
| Request media types | `application/json`, plus one entry per registered `RestBodyCodec` (e.g. Avro) |
| Convention parameters | Added when the input message has the field: `page_request` -> `pageSize` (int32), `pageToken` (string); `sort_fields` -> `sort`; `read_mask` -> `fields` (example from the first two selectable fields). The raw proto fields are not listed as parameters |

### Responses

| Case | `200` content |
| --- | --- |
| Unary | `application/json` (plus codec media types) with the output message `$ref` |
| Server streaming | `application/x-ndjson` and `text/event-stream`, schema is one message |
| `response_body` set (unary, not a merge) | that field's schema, JSON only |
| `google.api.HttpBody` output | `*/*`, `string/binary` |

The response schema follows the service's JSON print options. A message printed with `preserve-proto-field-names`
and/or `enums-as-ints` becomes its own component with a suffix: `Book_proto_names`, `Book_enums_as_ints`,
`Book_proto_names_enums_as_ints`. Request schemas always use the default spelling. `omit-default-values` changes no
schema.

### Message to schema

| Proto | Schema |
| --- | --- |
| `string` | `string` |
| `bytes` | `string`, `format: byte` |
| `int32`, `uint32`, `fixed32`, `sfixed32`, `sint32` | `integer`, `format: int32` |
| `int64`, `sfixed64`, `sint64` | `string`, `format: int64` (`integer` when `shiguchi.json.int64-as: NUMBER`) |
| `uint64`, `fixed64` | `string`, `format: uint64` (`integer` when `int64-as: NUMBER`) |
| `float`, `double` | `number`, `format: float` / `double` |
| `bool` | `boolean` |
| enum | `string` with `enum:` of value names; with `enums-as-ints`: `integer int32` with numeric `enum:` and the names in `description` |
| `repeated T` | `array` with `items` |
| message | `$ref: #/components/schemas/<Name>`, `type: object`, one property per field (JSON name, or proto name under `preserve-proto-field-names`) |
| nesting | depth limit 10; recursive types are emitted once and referenced |
| `google.protobuf.Timestamp` | `string`, `date-time` |
| `google.protobuf.Duration` | `string` (`format: duration` when output is `ISO_8601`) |
| `Int32Value` / `UInt32Value` | `integer int32` / `integer int64` |
| `Int64Value` / `UInt64Value` | as int64 / uint64 above |
| `FloatValue`, `DoubleValue`, `BoolValue`, `StringValue`, `BytesValue` | `number`, `number`, `boolean`, `string`, `string byte` |
| `FieldMask` | `string` (comma-separated camelCase paths) |
| `Empty`, `Struct`, `Any` | `object` |
| `Value` | no type (any JSON) |
| `ListValue` | `array` of any |
| `google.type.Date` / `TimeOfDay` / `DateTime` / `Decimal` / `Money` | `string date` / `string time` / `string` (no format) / `string decimal` / `object {currency, amount}` |
| Custom `ProtoScalarCodec` | shape, format, pattern, description, example from its `ScalarSchema` |

Details for the `google.type` and codec rows: [json-types.md](../guides/json-types.md#openapi).

Gaps, per the code (the converter has no handling for them):

- **required / validation constraints:** no property is `required`; no `minLength`, `pattern`, `minimum` or similar is
  emitted from `buf.validate`/protovalidate rules. Validation is enforced at run time, not described. See
  [validation.md](../guides/validation.md).
- **oneof:** not modelled. Every member is an ordinary optional property; no `oneOf` is emitted.
- **maps:** not modelled specially. A `map<K,V>` is a repeated map-entry message, so it is emitted as an `array` of a
  `...Entry` object schema, not as `additionalProperties` (the converter has no map handling).
- **field descriptions:** not emitted.
- **proto `deprecated`, field `example`:** not emitted.

## 3. Error responses

Every operation declares exactly two responses:

| Status | Declared |
| --- | --- |
| `200` | as in section 2 |
| `default` | `description: Error`, **no content, no schema** |

The runtime body is controlled by `shiguchi.rest.error-format`:

| Value | Body |
| --- | --- |
| `PROBLEM_JSON` (default since 0.21) | RFC 9457 `application/problem+json`: `type`, `title`, `status`, `detail`, `instance`, `code`, `errors: [{field, message, reason}]` |
| `GRPC` | `{code, message, details:[{"@type": ...}]}` |

A custom `GatewayErrorRenderer` bean replaces the switch. See [error-handling.md](../guides/error-handling.md#native-looking-errors).

**The generated spec does not describe either shape.** Its error response is a bare `default` with no media type and no
schema, so it does not match the `PROBLEM_JSON` default (it does not match the older `{code,message,details}` shape
either). There is no `ProblemDetail` component, no `4xx`/`5xx` entries and no `application/problem+json` content in the
generator. For `REST_DOWNSTREAM` services the backend's own error responses are merged as written (section 4).

## 4. Merging and extensions

`REST_DOWNSTREAM` services contribute their own OpenAPI document (JSON or YAML, from `services[].openapi-url`) to the
consolidated spec. Behaviour of `DownstreamSpecMerger`:

| Aspect | Rule |
| --- | --- |
| Paths | Prefixed with `services[].path-prefix`. Operations with no registered route (hidden by `include-methods` / `exclude-methods`) are dropped, with paths left empty skipped |
| Path collision | A path already in the consolidated spec is kept; the downstream entry is logged and skipped |
| Component names | Namespaced `<service>.<Name>` for every component kind; every `#/components/<kind>/<Name>` `$ref` is rewritten |
| `operationId` | `<service>_<operationId>` |
| Tags | `<service> / <tag>`; untagged operations get `<service>` |
| `servers` | Removed at root, path and operation level (the gateway is the server) |
| `security` / `securitySchemes` | Removed unless `services[].inherit-security: true` |
| `x-*` extensions | Preserved untouched |
| OpenAPI 3.1 input | Bound with the 3.1 mapper |
| Unparseable document | Service skipped with a warning; others still merge |

Springdoc merge (`OpenApiConfig.gatewayOpenApiCustomizer`): proto-derived paths are added, components of every kind are
merged first-wins (springdoc's own entries keep precedence), then configured security schemes are added with
`putIfAbsent`.

Vendor extensions the generator itself writes: codec media types carry `RestBodyCodec.openApiSchemaExtensions`; the Avro
codec adds `x-avro-schema` on its media type. Extensions read from backends: `x-avro-schema`, `x-avro-record` (Avro
`REST_DOWNSTREAM`). No other `x-*` keys are produced.

### `x-shiguchi-*` relationship extensions

A `REST_DOWNSTREAM` document can declare its GraphQL relationships and batch loaders itself, with these
vendor extensions. The gateway reads them from the document it fetched; no YAML is needed. Type names are
the OpenAPI schema names as written in the document (`Customer`, not `ShopCustomer`); the gateway adds
`graphql-type-prefix` and the usual name sanitising.

| Extension | Where | Value | Meaning |
| --- | --- | --- | --- |
| `x-shiguchi-references` | property | schema name | Many-to-one: the property holds the id of that schema. Adds `Order.customer` |
| `x-shiguchi-field` | property (with `x-shiguchi-references`) | name | Companion field name; defaults to the property name without `Id` / `_id` |
| `x-shiguchi-has-many` | property | `{type, group-by, field}` | One-to-many: this property is the parent key of the `type` children whose `group-by` property holds it; `field` names the collection |
| `x-shiguchi-batch-key` | schema | property name | The property that identifies an entity of this schema; used as the loader key |
| `x-shiguchi-loader` | operation (GET) | `{type, ids-param, ...}` | A batch loader for `type`. Takes the keys of a `rest-batch-loaders[]` entry (`ids-param`, `key-field`, `result-field`, `group-by`, `batch-endpoint`, `timeout-seconds`, `max-batch-size`, `max-concurrency`); `endpoint` is the operation's path |

Precedence on the same `Type.field`: proto annotations, then these extensions, then `relationships[]` in
YAML, then bean sources. An explicit `rest-batch-loaders[]` entry wins over an `x-shiguchi-loader` of the
same loader name.

Extension declarations are validated like the YAML ones: an unknown schema or property, a `x-shiguchi-loader`
on a non-GET operation, or a relationship with no loader fails the first start with a message that names the
extension and its place (`OpenAPI x-shiguchi-references on Order.customerId: ...`). On a schema reload the same
problem is logged at error level and the gateway keeps running.

The `x-shiguchi-*` keys are **kept** in the consolidated spec, like `x-gateway-auth-scopes`: the merger copies
`x-*` untouched, so a client reading the gateway's document sees what the backend declared.

`RelationshipSuggester` (Java, EXPERIMENTAL) lists candidates from names alone (`customerId` or `customer_id`
next to a `Customer` schema, and the inverse `orders` collection). It never wires anything: copy a candidate into
an extension or the YAML to adopt it. [Which way should I declare it?](../guides/graphql-annotations.md#which-way-should-i-declare-it)
compares the five sources, and the runnable
[`sample-graphql-relationships`](../../samples/sample-graphql-relationships/README.md) uses these extensions.

## 5. Configuration keys

From `dev.appiaworks.shiguchi.config.OpenApiConfig` (prefix `shiguchi.openapi`) and `ServiceDefinition`.

| Key | Default | Meaning |
| --- | --- | --- |
| `shiguchi.openapi.enabled` | `true` | Generate the spec and register the beans; also the default for `services[].openapi-enabled` |
| `shiguchi.openapi.group-by-backend` | `true` | Serve `/v3/api-docs/backends/{name}` and fill Swagger UI's document dropdown (needs two or more backends; `springdoc.swagger-ui.urls` set by you wins) |
| `shiguchi.openapi.security-schemes.<id>` | `{}` | Named schemes added to `components.securitySchemes` |
| `shiguchi.openapi.security-schemes.<id>.type` | none | `http`, `apiKey`, `oauth2` or `openIdConnect` (matched case-insensitively) |
| `...scheme` | none | `type=http`: `bearer` or `basic` |
| `...bearer-format` | none | `type=http` bearer hint, e.g. `JWT` |
| `...in` | none | `type=apiKey`: `header`, `query` or `cookie` |
| `...name` | none | `type=apiKey`: header/query/cookie name |
| `...authorization-code`, `...client-credentials`, `...implicit`, `...password` | none | `type=oauth2` flow; each has `authorization-url`, `token-url`, `refresh-url`, `scopes` (name to description, default `{}`) |
| `...open-id-connect-url` | none | `type=openIdConnect` discovery URL |
| `...description` | none | Text for the Authorize dialog |
| `shiguchi.openapi.global-security` | `[]` | Scheme ids added as a root `security` requirement |
| `shiguchi.security.surfaces.openapi.require-auth` | inherit mode | Gate Swagger UI and `/v3/api-docs/**` |
| `services[].openapi-enabled` | inherit | Per-service include/exclude in the spec |
| `services[].openapi-url` | none | `REST_DOWNSTREAM`: backend document URL |
| `services[].openapi-fetch-timeout` | `10s` | `REST_DOWNSTREAM`: fetch timeout |
| `services[].path-prefix`, `services[].inherit-security` | see [configuration.md](configuration.md) | Merge behaviour in section 4 |
| `shiguchi.rest.error-format` | `PROBLEM_JSON` | Runtime error body (not reflected in the spec) |

Springdoc properties (`springdoc.swagger-ui.*`, `springdoc.api-docs.*`) are springdoc's, not Shiguchi's.
