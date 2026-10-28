# Avro

Two directions, one starter (`shiguchi-avro-spring-boot3-starter` / `-boot4-`), both off without it:

- [Avro backends](#avro-backends): the gateway talks Avro **to** a `REST_DOWNSTREAM` service.
- [Avro callers](#avro-on-the-gateways-rest-surface): a REST caller talks Avro **to the gateway**
  for routes that front gRPC services.

# Avro backends

`REST_DOWNSTREAM` services can be spoken to in **Avro** instead of JSON. The gateway still
discovers the backend from its OpenAPI document and still exposes it as REST, OpenAPI and
GraphQL; only the bytes between the gateway and the backend change. Callers see the same JSON
and GraphQL they would see for a JSON backend.

Add the starter and set the service's preference:

```kotlin
implementation("io.github.appiaworks:shiguchi-avro-spring-boot3-starter:<version>")   // or -boot4-
```

```yaml
shiguchi:
  services:
    - name: inventory
      mode: REST_DOWNSTREAM
      openapi-url: http://inventory:8080/v3/api-docs
      upstream-content:
        preferred: avro/binary          # default application/json
```

Nothing else is required when the backend's document carries its Avro schemas (below).

## How an operation ends up in Avro

Three things have to agree, per operation, per direction (request body and response):

1. **The service prefers it.** `upstream-content.preferred: avro/binary` (also accepted:
   `application/avro`, `application/vnd.apache.avro+binary`).
2. **The document advertises it.** The operation's `requestBody.content` or its first 2xx
   `responses.*.content` lists `avro/binary` (the media types are read as declared, in order;
   parameters such as `charset` are ignored).
3. **A schema is bound.** The body's schema is a `$ref` to a `components.schemas` entry (or an
   array of one) and the Avro codec has an Avro schema for that component.

When all three hold, the gateway sends `Accept: avro/binary, application/json;q=0.5` and, for a
body, `Content-Type: avro/binary`. Otherwise that operation stays JSON. The response is always
decoded by the `Content-Type` the backend **actually returns**, so a backend that ignores
`Accept` and answers JSON keeps working, and a mixed document (some operations Avro, some JSON)
needs no configuration.

## Where the schemas come from

`avro.schema-source` on the service (default `OPENAPI`):

| Source | What binds a component |
| --- | --- |
| `OPENAPI` | The component carries `x-avro-schema`: an inline Avro schema, as a JSON object or a JSON string. A schema may reference a record declared in another component's `x-avro-schema` by name; parse order does not matter |
| `FILES` | `avro.schema-files` lists `.avsc` resources (`classpath:` / `file:`). A component binds when its name equals a record's simple or full name, or when it carries `x-avro-record: <full name>` |
| `OPENAPI_AND_FILES` | Both; files first, the document fills the gaps |

```yaml
      avro:
        schema-source: OPENAPI_AND_FILES
        schema-files: [classpath:schemas/inventory.avsc]
        wire-format: BINARY               # BINARY | SINGLE_OBJECT
```

Document form (no YAML needed beyond `preferred`):

```yaml
paths:
  /items/{sku}:
    get:
      operationId: getItem
      responses:
        "200":
          content:
            avro/binary:
              schema: { $ref: "#/components/schemas/Item" }
            application/json:
              schema: { $ref: "#/components/schemas/Item" }
components:
  schemas:
    Item:
      type: object
      properties:
        sku: { type: string }
        qty: { type: integer, format: int64 }
        updatedAt: { type: string, format: date-time }
      x-avro-schema:
        type: record
        name: Item
        namespace: com.acme.inventory
        fields:
          - { name: sku, type: string }
          - { name: qty, type: long }
          - { name: updatedAt, type: { type: long, logicalType: timestamp-millis } }
```

The JSON Schema part still drives the GraphQL type and the merged OpenAPI document; the Avro
part drives the bytes. Keep the two in step: a field the Avro record has and the JSON Schema
lacks is decoded and then dropped when the value is bound to the synthetic message.

Schemas are (re)bound every time the document is fetched, so a refresh that changes
`x-avro-schema` takes effect with the routes. The startup log lists what bound:
`avro/binary bound 2 schema(s) of REST_DOWNSTREAM service 'inventory': [Item, Category]`.

## Value mapping

Decoded Avro becomes the same plain Java the JSON path produces, then goes through the same
binding as JSON, so GraphQL results and JSON responses are byte-for-byte what a JSON backend
gives for the same values.

| Avro | Decoded as | Encoded from |
| --- | --- | --- |
| `record` | `Map` in field order; null fields omitted | `Map`; absent field → schema default, else null if nullable, else error |
| `array` | `List` | `List` (a scalar is wrapped) |
| `map` | `Map` with String keys | `Map` |
| `["null", T]` and other unions | the branch's value; null omitted | branch chosen by the value's Java type |
| `enum` | symbol as `String`, then mapped to the GraphQL enum value like a JSON string would be | symbol `String` (GraphQL enum values are mapped back to the symbol first); unknown symbol → error |
| `string` | `String` | anything, via `toString` |
| `bytes`, `fixed` | base64 `String` | base64 `String`, `byte[]` or `ByteBuffer` |
| `decimal` (bytes/fixed) | plain decimal `String` (`"12.50"`) | `String`, `BigDecimal` or number; scale must fit |
| `int`, `long` | `Integer`, `Long` | any `Number` or numeric `String` (GraphQL sends 64-bit integers as strings) |
| `float`, `double`, `boolean` | as is | `Number` / numeric string; `Boolean` / `"true"` `"false"` |
| `date` | ISO date `String` | ISO date or epoch days |
| `time-millis`, `time-micros` | ISO local time `String` | ISO time or the raw number |
| `timestamp-millis/micros/nanos` | ISO-8601 instant `String` (`Z`) | ISO instant or the raw epoch number |
| `local-timestamp-millis/micros` | ISO local date-time `String` | ISO local date-time or the raw number |
| `uuid` | `String` | `String` |

Errors: a request body that does not fit the schema (missing required field, bad enum, wrong
type) is rejected with `INVALID_ARGUMENT` before anything is sent, naming the field path. A
response that does not decode with the bound schema is `INTERNAL` with the Avro error in the
description, exactly like malformed JSON.

## Wire format

`avro.wire-format` chooses how request bodies are **written**:

- `BINARY` (default): the raw datum. The backend must know the schema.
- `SINGLE_OBJECT`: the [Avro single-object encoding](https://avro.apache.org/docs/1.12.0/specification/#single-object-encoding):
  `C3 01`, the writer schema's 8-byte CRC-64-AVRO fingerprint (little-endian), then the datum.
- `CONFLUENT`: the [Confluent wire format](https://docs.confluent.io/platform/current/schema-registry/fundamentals/serdes-develop/index.html#wire-format):
  `00`, the 4-byte big-endian id the schema registry assigned the writer schema, then the datum.
  Needs `avro.schema-registry-url` (below).

Responses are recognised by their bytes regardless of the setting. Single-object responses whose
fingerprint is not the bound schema's are resolved against every schema the gateway knows for
that service (Avro schema resolution: added fields with defaults, dropped fields, reordered
fields, promoted numeric types), so a backend one schema version ahead or behind still decodes
as long as its version is registered, for example from `schema-files`. An unknown fingerprint
is an `INTERNAL` error naming it.

### Schema registry

```yaml
shiguchi:
  services:
    - name: inventory
      mode: REST_DOWNSTREAM
      upstream-content:
        preferred: avro/binary
      avro:
        wire-format: CONFLUENT
        schema-registry-url: http://user:pass@schema-registry:8081   # Basic auth from the URL
        subject-strategy: RECORD_NAME      # RECORD_NAME (default) | COMPONENT_NAME
        subjects:                          # per-component override
          Item: inventory-item-value
        registry-timeout: 10s
```

With `avro.schema-registry-url` set, the service load registers every bound schema with a
Confluent-compatible registry (`POST /subjects/{subject}/versions`, a no-op for a schema the
registry already holds) and fetches every version of each subject, so a backend writing an older
or newer version of the record is decoded by the id its bytes carry. The subject is the record's
full name by default, the OpenAPI component name with `subject-strategy: COMPONENT_NAME`, or
whatever `subjects` maps the component to.

The registry is only called at load time and on refresh. A request never blocks on it: an id the
load did not see is an `INTERNAL` error naming it, and a registry that is down fails the service
load rather than every request after it. There is no Confluent client dependency; the gateway
speaks the registry's REST API over `WebClient`.

Avro container files (streaming) are not supported.

## Limits

- Per-operation media types come from the document; there is no YAML override per operation.
- Field projections (`read_mask`, GraphQL selections) are applied after decoding. Avro has no
  partial decode, so an Avro backend always sends the full record.
- The Avro record is not used to generate the GraphQL type or the merged OpenAPI document; the
  component's JSON Schema is. A component with `x-avro-schema` but no `properties` becomes an
  empty object type.

## Performance

`AvroCodecBenchmark` in [shiguchi-benchmarks](../shiguchi-benchmarks/README.md)
measures the only step that differs between the two encodings: bytes to the plain `Map` and
back. Binding, projection and rendering are shared. Reference numbers (JDK 21, one record
with a 10-element array): Avro → `Map` 0.63 ops/µs against Jackson's 0.83, `Map` → Avro 0.59
against 1.17. Avro is a choice for the backend (smaller payloads, schema enforcement), not a
gateway CPU saving.

# Avro on the gateway's REST surface

With the Avro starter on the classpath, every unary route that fronts a gRPC service also speaks
Avro to callers. Nothing to configure: the schemas are derived from the proto descriptors.

```bash
# Ask for Avro: the response is single-object encoded (C3 01 + fingerprint + datum)
curl -s localhost:8080/v1/books/1 -H 'accept: avro/binary' -o book.avro -D -
# HTTP/1.1 200 OK
# Content-Type: avro/binary
# X-Avro-Schema-Fingerprint: 7c1f0a2b9e3d4c55

# Fetch the reader schema by proto message name or by that fingerprint
curl -s localhost:8080/avro/schemas/library.v1.Book
curl -s localhost:8080/avro/schemas/7c1f0a2b9e3d4c55

# Send Avro: encode the request with the schema of the request message
curl -s localhost:8080/v1/books -X POST -H 'content-type: avro/binary' -H 'accept: application/json' \
  --data-binary @create-book.avro
```

## Negotiation

| Header | Effect |
| --- | --- |
| `Content-Type: avro/binary` (or `application/avro`, `application/vnd.apache.avro+binary`) | the body is decoded with the schema of the route's request message; path variables and query parameters are overlaid exactly as for JSON |
| `Accept: avro/binary` | the response is encoded with the schema of the response message; `X-Avro-Schema-Fingerprint` names it |
| `Accept: application/json, avro/binary;q=0.5` | quality values are honoured: JSON here |
| `Accept: */*`, `application/*`, `application/json`, `*+json`, absent | JSON, as before |
| `Content-Type` in the Avro family that no codec speaks (`avro/x-container`) | 415 |
| `Accept` naming only types nothing speaks (`text/csv`) | 406 |
| streaming and merge routes | JSON / NDJSON only: 406 for an Avro `Accept`, 415 for an Avro body |

Errors keep the `GatewayErrorRenderer` shape and content type (JSON) whatever the caller asked for.
A body that does not decode with the request schema is 400. Pagination headers and `Cache-Control`
are set exactly as for JSON. Without the starter none of this negotiation exists and any `Accept`
gets JSON, the historical behaviour.

## The derived schema

`ProtoToAvroSchema` derives one record per proto message, once, cached by full name and by
fingerprint. The inverse of the table above, with the lossy edges called out:

| proto | Avro |
| --- | --- |
| proto3 scalar without presence | the scalar, zero value as default, never null |
| `optional` / proto2 optional scalar, message field, wrapper type | `["null", T]`, default null |
| `repeated` | array, default `[]` |
| `map<K,V>` | map with string keys (numeric and bool keys are stringified), default `{}` |
| enum | enum with the proto value names, default the zero value |
| `int32`, `sint32`, `sfixed32` | `int` |
| `uint32`, `fixed32`, `int64`, `sint64`, `sfixed64` | `long` |
| `uint64`, `fixed64` | `long` (wraps above 2^63) or `string`, per `shiguchi.avro.uint64-as` |
| `google.protobuf.Timestamp` | `long` `timestamp-micros` (nanos truncated) |
| `google.protobuf.Duration` | `string` (the JSON mapping's text) |
| `google.type` `Date`, `TimeOfDay`, `DateTime`, `Decimal` (while [mapped](json-types.md)) | `string`, the same ISO text as JSON — the `date`/`time-*` logical types cannot hold partial dates, `24:00:00` or zone ids |
| `google.type.Money` (while mapped) | record `{currency, amount}`, amount a decimal string |
| `Struct`, `Value`, `ListValue`, `Any`, `FieldMask` | `string` holding JSON |
| `bytes` | `bytes` |
| nested message `Outer.Inner` | record `Outer_Inner` in the package's namespace |
| recursive message | the record referenced by name |

Field names are the proto JSON names (`created_at` → `createdAt`), the same names the JSON body
uses, so a caller can move between the two encodings without renaming anything.

## Configuration

```yaml
shiguchi:
  avro:
    enabled: true                 # master switch for both directions; needs the starter
    framing: SINGLE_OBJECT        # BINARY | SINGLE_OBJECT | CONFLUENT — how responses are written
    schema-endpoint: /avro/schemas
    uint64-as: LONG               # LONG | STRING
    schema-registry-url:          # Confluent-compatible registry; required for framing: CONFLUENT
    registry-timeout: 10s
```

With `schema-registry-url` set, every derived request and response schema is registered with the
registry (subject = the proto message full name, e.g. `library.v1.Book`) once the routes are
loaded and again after every schema reload, and every version of those subjects is fetched. A
`CONFLUENT` response then carries the id the registry assigned (`00` + 4-byte id + datum) instead
of the fingerprint, and a `CONFLUENT`-framed request is decoded by its id. The
`X-Avro-Schema-Fingerprint` header is still sent. The registry is never called per request.

The schema endpoint answers `GET <endpoint>/{proto full name}` and `GET <endpoint>/{fingerprint}`
with the Avro schema as JSON and the fingerprint header; 404 for a message no registered service
declares. It is part of the REST surface for security purposes.

## Limits

- Unary routes only. Server-streaming routes stay NDJSON; merge routes stay JSON.
- The `GatewayAuthorizer` receives path variables and query parameters for an Avro request, but
  not the body (it is opaque bytes until decoded); a per-object authorizer that inspects the body
  must use a JSON request.
- Error bodies are JSON.
- Container files are not supported. Schema evolution between caller and gateway is limited to
  what the registry holds: with `framing: CONFLUENT` a caller may write any registered version of
  the request schema; without a registry the caller uses the schema the gateway publishes.

## Reference

Properties in [configuration.md](configuration.md#per-service-shiguchiservices):
`upstream-content.preferred`, `upstream-content.fallback`, `avro.schema-source`,
`avro.schema-files`, `avro.wire-format`, `avro.schema-registry-url`, `avro.subject-strategy`,
`avro.subjects`, `avro.registry-timeout`, and the global `shiguchi.avro.*` (`enabled`,
`framing`, `schema-endpoint`, `uint64-as`, `schema-registry-url`, `registry-timeout`; `enabled`
defaults to `true` when the starter is present). Extension points in `shiguchi-rest`: `UpstreamBodyCodec` (to backends) and
`RestBodyCodec` (from callers), the interfaces the Avro codecs implement; another codec bean is
picked up the same way. Samples: [samples/sample-avro-downstream](../samples/sample-avro-downstream)
(backend side) and `sample-full` with the Avro starter (caller side).
