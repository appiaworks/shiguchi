# Dates, times, decimals and durations in JSON

How the gateway writes and reads time and number types that JSON has no primitive for, on
every surface: REST, GraphQL, the OpenAPI document, Avro and the console.

Short version: **`google.protobuf.Timestamp`** has always been an RFC 3339 string. The common
**`google.type`** messages (`Date`, `TimeOfDay`, `DateTime`, `Decimal`) are now ISO strings too,
by default. `Money` can be switched on as `{currency, amount}`. Durations also accept ISO-8601
(`PT1H30M`) on input.

```json
{
  "createdAt": "2026-09-22T14:03:11.123Z",
  "birthDate": "1990-04-17",
  "opensAt": "09:30:00",
  "startsAt": "2026-09-22T14:03:11-04:00[America/New_York]",
  "price": "12.34",
  "timeout": "5400s"
}
```

## Why this differs from `JsonFormat`

The proto3 JSON mapping gives special forms only to the `google.protobuf` well-known types.
`google.type.Date` is an ordinary message there, so `JsonFormat` and grpc-gateway (Go) print it
as `{"year":1990,"month":4,"day":17}`. That is correct by the spec and awkward for clients. The
gateway prints `"1990-04-17"` instead.

That is a deliberate departure from the spec. It matters in one case: a client that parses the
gateway's JSON back into proto with `JsonFormat` fails on these fields. If you have such
clients, switch the type off (see [Configuration](#configuration)).

Nothing changes for your gRPC services. They still receive the proto message, with its fields
set.

## The mapping

| Proto type | JSON | Example | Default |
| --- | --- | --- | --- |
| `google.protobuf.Timestamp` | RFC 3339, UTC, 0/3/6/9 fraction digits | `"2026-09-22T14:03:11.123Z"` | always |
| `google.protobuf.Duration` | seconds with an `s` suffix, or ISO-8601 | `"5400s"` / `"PT1H30M"` | `5400s` |
| `google.type.Date` | ISO-8601 date | `"2026-09-22"` | on |
| `google.type.TimeOfDay` | ISO-8601 time, no offset | `"14:03:11.123"` | on |
| `google.type.DateTime` | ISO-8601 date-time, offset and `[zone]` optional | `"2026-09-22T14:03:11-04:00"` | on |
| `google.type.Decimal` | decimal string | `"12.34"` | on |
| `google.type.Money` | `{currency, amount}`, amount a decimal string | `{"currency":"USD","amount":"12.34"}` | off |

### Date

`google.type.Date` can hold partial dates, where a zero field means "not set". Each shape has an
ISO form, and the gateway uses it in both directions:

| year / month / day set | JSON |
| --- | --- |
| all three | `"2026-09-22"` |
| year, month (day 0) | `"2026-09"` |
| year only | `"2026"` |
| month, day (year 0), e.g. a birthday | `"--09-22"` |

A combination with no ISO form, such as a year and a day but no month, is written as the object
`{"year":…,"month":0,"day":…}`. On input, the calendar is checked: `2026-02-30` is rejected, and
`--02-29` is accepted.

### TimeOfDay

`HH:mm:ss`, plus a fraction when there are nanoseconds, with 3, 6 or 9 digits as for Timestamp.
On input, `HH:mm` and `HH:mm:ss.fraction` are accepted, and so are two values `LocalTime` would
reject: `24:00:00` (end of day, such as a closing time) and second `60` (a leap second).

### DateTime

A `google.type.DateTime` carries either a UTC offset, a time zone, or neither:

| The message has | JSON |
| --- | --- |
| `utc_offset` of -4h | `"2026-09-22T14:03:11-04:00"` (a zero offset prints `Z`) |
| `time_zone` `America/New_York` | `"2026-09-22T14:03:11-04:00[America/New_York]"` (RFC 9557) |
| neither (local time) | `"2026-09-22T14:03:11"` |

The offset printed with a zone comes from the JVM's time zone rules for that local time. On
input, a bracketed zone sets `time_zone`, an offset alone sets `utc_offset`, and neither sets
nothing. `time_zone.version` is not carried by the text form. A DateTime without a year, or with
a zone id the JVM does not know, is written as its object form.

### Decimal and Money

`Decimal` is a **string**, never a JSON number, so `0.1` stays `0.1` and large values keep every
digit. On input, a JSON number is also accepted. Any text Java's `BigDecimal` can parse is a
valid Decimal, including exponents such as `1.5E+3`.

With `money` on, `units` and `nanos` are combined into one decimal amount:
`{"currencyCode":"USD","units":"12","nanos":340000000}` becomes
`{"currency":"USD","amount":"12.34"}`. On input, an amount with more than 9 decimal places is
rejected, and a negative amount sets `units` and `nanos` with the same sign, as `Money` requires.
Money is off by default because it has no standard scalar form, and the flattened object is
still an object.

### Duration

Written as `"5400s"`, the proto3 form, unless `duration.output` is `ISO_8601` (`"PT1H30M"`).
Read in both forms while `duration.accept-iso8601` is on, which it is by default.

### Timestamp

Always written as RFC 3339 in UTC. On input, any RFC 3339 offset is accepted. With
`timestamp.accept-epoch-millis`, a JSON number is also read as milliseconds since the epoch.

### 64-bit integers

`int64`, `uint64`, their `sint`/`fixed`/`sfixed` variants and the `Int64Value`/`UInt64Value`
wrappers are written as **strings** by default (`"count":"42"`), as proto3 JSON specifies. A JSON
number above 2^53 loses precision in JavaScript, and in any client that parses numbers as
doubles: `9007199254740993` arrives as `9007199254740992`.

`int64-as: NUMBER` writes them as numbers (`"count":42`). Values above `Long.MAX_VALUE`
(`uint64`) come out exact too. Use it when the values stay below 2^53, such as database ids and
counts, or when every client parses 64-bit integers exactly. Input accepts both forms either way.
The OpenAPI document types these fields `integer` while the switch is on. GraphQL keeps them as
`String`, because GraphQL's `Int` is 32-bit.

### Doubles, floats and `google.protobuf.Value` numbers

`double`, `float`, their wrappers and the numbers inside a `google.protobuf.Value` (or `Struct`,
`ListValue`) print in Java's form, `Double.toString`. That is valid proto3 JSON, but it is not
the text `JSON.stringify` produces:

| Value | Gateway | `JSON.stringify` |
| --- | --- | --- |
| `1` | `1.0` | `1` |
| `1e20` | `1.0E20` | `100000000000000000000` |
| `123456789012345678` in a `Value` | `1.2345678901234568E17` | `123456789012345680` |

The digits are the shortest that read back to the same double, so nothing is lost. Only the layout
differs. `NaN` and the infinities are strings (`"NaN"`, `"Infinity"`), as proto3 JSON specifies.

`Value.number_value` is a `double`. An integer beyond 2^53 already lost precision when it was put
into the `Value`, before the gateway printed it: that is a proto limit, not the gateway's. Use
`int64`/`uint64` fields for large integers. They print as strings and stay exact (see
[64-bit integers](#64-bit-integers)).

### String escaping

No path HTML-escapes strings. `<`, `>`, `&`, `=` and `'` are written as they are, and so are
U+2028 and U+2029. The only escapes are the ones JSON requires: `\"`, `\\` and control characters.
`JsonFormat` writes `\u003c` for `<`, and its printer has no option to turn that off. Where the
gateway still prints with `JsonFormat` (types that reach `Any`, `response_body`, push payloads), it
rewrites the escapes to match the other paths. Nothing else in the text changes.

### Comparing JSON text

The JSON is valid proto3 JSON, and every proto3 JSON parser reads it back to the same values. The
**text** is not canonical, though. A value printed by the gateway, by `JsonFormat`, by grpc-gateway
(Go) or by `JSON.stringify` parses to the same thing, but the bytes can differ in number layout,
escapes, field order and whitespace. So do not compare or measure the bytes. Parse the JSON first,
or measure on the side that owns the data:

- size limits: a client that counts `JSON.stringify` bytes gets a different number.
- hashes, `ETag`s and signatures over the body: compute them over the parsed value, or over the
  proto bytes.
- snapshot tests: compare parsed trees, not strings.

### Which printer serves each path

| Path | Printer | Numbers | Notes |
| --- | --- | --- | --- |
| REST responses, streams and the console (Tiers 0 to 2) | Jackson | Java form | |
| REST, types that reach `Any` (Tier 3) | `JsonFormat`, escapes rewritten | Java form | `google.type` mapping does not apply ([above](#why-this-differs-from-jsonformat)). |
| REST `response_body` on a single field | `JsonFormat`, escapes rewritten | Java form | |
| Error bodies and their `details` | Jackson (details through `JsonFormat`, then parsed) | Java form | |
| Push `message` payloads, `push.topic` upstreams, Kafka and gRPC-stream sources | `JsonFormat`, escapes rewritten | Java form | Fields at their default value are **omitted**, unlike REST. |
| Push `json` payloads (a `Value`) | Jackson | integral numbers below 10^15 as integers (`1`), others Java form | |
| GraphQL | graphql-java's result, written by Spring's `ObjectMapper` | Java form | |

Server logs and the `cursus` CLI are not client output and are not covered here.

## Input rules

- **The old shape still works.** Every mapped type also accepts its object form on input
  (`{"year":2026,"month":9,"day":22}`), so turning a type on never breaks a client that sends
  objects.
- **Query parameters and path variables** take the same text: `GET /v1/events?day=2026-09-22`
  or `/v1/events/{day}` binds straight onto a `google.type.Date` field. There is no need to
  split it into `day.year=…&day.month=…`.
- **Malformed values are rejected, not dropped.** `"day":"2026-02-30"` answers HTTP 400
  (`INVALID_ARGUMENT`) naming the type, the value and what was expected. On GraphQL it is a
  field error. The same holds for every well-known type: a Timestamp of `"yesterday"`, a
  Duration of `"ninety minutes"`, an `Int32Value` of `"abc"`. Timestamps are strict RFC 3339,
  so `2026-13-01T00:00:00Z` is rejected rather than rolled over into the next year. JSON `null`
  means the field is unset.
- **Plain fields are coerced unless you ask for strict input.** By default the gateway's fast
  parser turns a value that does not fit a plain field into the field's default: `"abc"` for an
  `int32` becomes `0`. Set `shiguchi.json.strict-input: true` to refuse it instead, as below.

### Strict input

```yaml
shiguchi:
  json:
    strict-input: true
```

A value that does not fit its field is then answered with HTTP 400 (`INVALID_ARGUMENT`) naming
the field, its type and the value, on REST bodies and GraphQL arguments alike:

```
Invalid int32 value 'abc': acme.Order.quantity takes a whole number from -2147483648 to 2147483647
```

The rules are proto3 JSON's:

| Field | Accepted | Refused |
| --- | --- | --- |
| integers | a number or a numeric string, whole and in range: `7`, `"7"`, `1e2` | `1.5`, `"abc"`, `2147483648` for an `int32`, `-1` for a `uint32`, `true` |
| `float`, `double` | a number, a numeric string, `"NaN"`, `"Infinity"`, `"-Infinity"` | `"one"`, `1e39` for a `float` |
| `bool` | `true`, `false`, `"true"`, `"false"` | `1`, `"yes"` |
| `string` | a JSON string | `12`, `{}` |
| `bytes` | base64, standard or URL-safe | anything else |
| enum | a value name or number; an unknown one is ignored | `true`, `1.5` |
| message, `repeated`, `map` | an object, an array, an object | anything else; `null` inside an array or as a map value |

Unknown fields and unknown enum names are ignored either way, and well-known and mapped types
follow the rules above this section in both modes. The fast compiled parser and the reflective
one read through the same rules, so which one serves a type makes no difference to what is
accepted. Types that reach `google.protobuf.Any` are parsed by `JsonFormat`, which applies its
own proto3 rules in both modes.

It is off by default because it can start refusing requests the gateway has been accepting:
turn it on, watch the 400s, and fix the clients that relied on the coercion.

## Your own scalar types

The same treatment is open to your own messages. A `ProtoScalarCodec` writes one message type as
a single JSON value and reads it back. Declare it as a bean and the gateway registers it at
startup:

```java
@Bean
ProtoScalarCodec skuCodec() {
    return ProtoScalarCodec.ofString(
            Sku.getDefaultInstance(),
            Sku::getCode,                                   // message -> text
            code -> Sku.newBuilder().setCode(code).build()); // text -> message
}
```

An `acme.Sku` field now travels as `"sku":"AB-1234"` instead of `{"code":"AB-1234"}`, wherever a
field of that type appears: request and response bodies, query parameters and path variables,
GraphQL, Avro.

- **Written against the generated class.** The gateway works from descriptors and holds
  `DynamicMessage`s. `ofString` and `of` convert, so the functions see your generated type. Implement
  the interface directly to work on the `DynamicMessage` itself.
- **Other JSON shapes.** `ProtoScalarCodec.of(prototype, JsonShape.NUMBER, ...)` writes a number,
  `INTEGER` a whole number, `BOOLEAN` a boolean and `ANY` an object or array of your design. The JSON side is a plain Java
  value (`String`, `Number`, `Boolean`, `Map`, `List`), not a parser, because the same value also
  arrives from a query string and a GraphQL argument.
- **Malformed input.** Throw `IllegalArgumentException` from the read function. The caller gets
  HTTP 400 (`INVALID_ARGUMENT`) naming the type, the value and your message.
- **The object form still works**, as it does for the built-in types: `{"code":"AB-1234"}` is
  accepted on input unless the codec's shape is `ANY`.
- **Schemas follow the shape**, and [the schema hints](#describing-the-value) when the codec has
  them. OpenAPI types the field `string`, `integer`, `number`, `boolean` or any value. GraphQL
  uses `String`, `Float` (`Int` for an int32 `INTEGER`), `Boolean` or `JSON`. Avro uses `string`,
  `long`, `double`, `boolean`, or a string holding JSON for `ANY`.
- **Overriding a built-in.** A codec for `google.type.Date` (or any type in the table above)
  replaces the gateway's. The schemas then describe it by its shape, not as a date.
- **Not for `google.protobuf` types.** Timestamp, Duration, the wrappers and the rest have a JSON
  form fixed by proto3. A codec naming one is rejected at startup, as are two codecs for the same
  message.

Like the settings below, codecs are read once at startup and apply to the whole process.

### Describing the value

The shape alone gives clients `"sku": string`. A `ScalarSchema` says more, and every published
schema picks up what it can use:

```java
@Bean
ProtoScalarCodec skuCodec() {
    return ProtoScalarCodec.ofString(Sku.getDefaultInstance(), Sku::getCode, Skus::parse)
            .withSchema(ScalarSchema.string()
                    .withPattern("[A-Z]{2}-\\d+")
                    .withDescription("Stock-keeping unit")
                    .withExample("AB-1234")
                    .withGraphQlName("Sku"));
}
```

| Hint | OpenAPI | GraphQL | Avro |
| --- | --- | --- | --- |
| shape | `type` | `String`, `Int` / `Float`, `Boolean`, `JSON` | `string`, `long`, `double`, `boolean` |
| `withFormat` | `format` (`uuid`, `email`, `int64`, or your own word) | `int32` makes an `INTEGER` an `Int` | - |
| `withPattern` | `pattern` | checked on input by the named scalar | - |
| `withDescription` | `description` | the named scalar's description | - |
| `withExample` | `example` | - | - |
| `withGraphQlName` | - | a custom scalar of that name: `sku: Sku` | - |

- **GraphQL `Int` is 32-bit.** An `INTEGER` codec is `Int` only with `withFormat("int32")`;
  otherwise it is `Float`, which carries a 64-bit value without an error. Name a scalar to keep
  whole numbers apart from fractions in the schema.
- **A named scalar** is declared in the SDL and wired by the gateway. It checks the shape and the
  pattern, then hands the value to the codec, which still has the last word. Two codecs may share
  a name only with equal schemas, and the name must not be a built-in or one the gateway declares
  (`String`, `JSON`, `DateTime`, `LocalDate`, `Decimal`, ...). It must not be the name of a message
  type either, or the schema does not load.
- **Hints describe, they do not convert.** The pattern is not enforced on REST input: the codec
  is. Keep the two consistent.
- `ScalarSchema` rejects contradictions when it is built: a pattern on a non-text shape, an example
  of the wrong shape or one that fails the pattern, a GraphQL name that is not one.

Implementing the interface directly? Override `schema()` instead of `jsonShape()`.

`ProtoScalarCodec` is `EXPERIMENTAL`: see [api-compatibility.md](api-compatibility.md).

## Converting in Java

`ProtoTypes` converts the same types to and from the Java types you would rather hold, for code
that builds or reads these messages itself, such as a gRPC service implementation or a codec:

```java
Date day          = ProtoTypes.date(LocalDate.of(2026, 9, 22));
LocalDate back    = ProtoTypes.toLocalDate(day);
Timestamp at      = ProtoTypes.timestamp(Instant.now());
Money price       = ProtoTypes.money(new MoneyValue("USD", new BigDecimal("12.34")));
BigDecimal amount = ProtoTypes.toMoneyValue(price).amount();
```

| Proto type | Java type |
| --- | --- |
| `google.protobuf.Timestamp` | `Instant`, `OffsetDateTime` (UTC) |
| `google.protobuf.Duration` | `java.time.Duration` |
| `google.type.Date` | `LocalDate`; `YearMonth`, `Year`, `MonthDay` for the partial forms (`toTemporal`) |
| `google.type.TimeOfDay` | `LocalTime` |
| `google.type.DateTime` | `LocalDateTime`, `OffsetDateTime`, `ZonedDateTime` |
| `google.type.Decimal` | `BigDecimal` |
| `google.type.Money` | `MoneyValue(currencyCode, amount)` |
| `google.type.DayOfWeek`, `Month` | `java.time.DayOfWeek`, `java.time.Month` |
| wrappers (`Int32Value`, ...) | boxed values, null for unset; unsigned as `Long` / `BigInteger` |
| `Struct`, `Value`, `ListValue` | `Map<String, Object>`, `Object`, `List<Object>` |
| `FieldMask` | `List<String>` |

Nothing is guessed. A value the Java type cannot hold throws `IllegalArgumentException` saying
what is missing: a `Date` with no day asked for as a `LocalDate`, a `TimeOfDay` of `24:00:00`, a
`DateTime` with no offset asked for as an `OffsetDateTime`, a `Money` amount with more than 9
fractional digits. `ProtoTypes.as(message, Date.getDefaultInstance())` turns a `DynamicMessage`
into the generated class first.

`ProtoTypes`, the codecs and the JSON dialect itself also work without a gateway or a Spring
context, for example inside the gRPC service behind it:
[proto-serde-standalone.md](proto-serde-standalone.md).

## Per surface

### REST

As in the tables above, in request bodies, responses, NDJSON streams and merged endpoints.

### GraphQL

The same text travels as custom scalars. These are declared in the SDL only while their type is
mapped, so a scalar that is not in use never clashes with a message of the same name.

| Proto type | GraphQL type |
| --- | --- |
| `google.protobuf.Timestamp` | `DateTime` (strict RFC 3339 with an offset on input) |
| `google.protobuf.Duration` | `String` |
| `google.type.Date` | `LocalDate` (accepts the partial forms) |
| `google.type.TimeOfDay` | `LocalTime` |
| `google.type.DateTime` | `String`: an offset, a zone or neither, so no single scalar fits |
| `google.type.Decimal` | `Decimal` (a string; number literals accepted on input) |
| `google.type.Money` | `JSON` (`{currency, amount}`) |

```graphql
query { event(id: "1") { day opens price } }
# { "data": { "event": { "day": "2026-09-22", "opens": "09:30:00", "price": "12.34" } } }

mutation { createEvent(input: { day: "2026-09-22", price: 12.34 }) { id } }
```

The scalars check the shape of a value, and the gateway's converter checks the calendar. That
is why `"2026-02-30"` passes the `LocalDate` scalar and then fails with `INVALID_ARGUMENT`.

When a type is switched off, it goes back to an object type (`Date { year month day }`) with a
matching `DateInput`.

GraphQL `String` fields cannot carry the object fallback. So a `DateTime` with no year, or with
a zone id the JVM does not know, does not serialize on GraphQL; REST prints it as an object.

### OpenAPI

Schemas describe what goes over the wire:

| Proto type | Schema |
| --- | --- |
| `google.protobuf.Timestamp` | `string`, `format: date-time` |
| `google.protobuf.Duration` | `string` (`format: duration` when the output is `ISO_8601`) |
| `Int32Value` / `UInt32Value` | `integer`, `int32` / `int64` |
| `Int64Value` / `UInt64Value` | `string`, `int64` / `uint64` (64-bit integers are JSON strings) |
| `FloatValue` / `DoubleValue` / `BoolValue` | `number` / `number` / `boolean` |
| `google.protobuf.Value` | any JSON value (no `type`) |
| `google.type.Date` | `string`, `format: date` |
| `google.type.TimeOfDay` | `string`, `format: time` |
| `google.type.DateTime` | `string`, with a description; no `format`, because the offset is optional |
| `google.type.Decimal` | `string`, `format: decimal` |
| `google.type.Money` | `object` with `currency` and `amount` |

A generated client therefore types `Timestamp` and `Date` fields as dates rather than plain
strings.

### Avro

On the gateway's own Avro surface, the mapped `google.type` messages are Avro `string`s holding
the same ISO text, and Money is a `{currency, amount}` record. Avro's `date` and `time-*` logical
types are not used, because they cannot hold partial dates, `24:00:00` or zone ids. See
[avro.md](avro.md#the-derived-schema).

### Console

The console edits `Date` fields with a date picker and `TimeOfDay` fields with a time picker. A
partial date stays in a text box. `DateTime` and `Decimal` fields are text inputs.

## Configuration

```yaml
shiguchi:
  json:
    scalar-types:
      date: true            # google.type.Date      <-> "2026-09-22"
      time-of-day: true     # google.type.TimeOfDay <-> "14:03:11.123"
      date-time: true       # google.type.DateTime  <-> "2026-09-22T14:03:11-04:00[America/New_York]"
      decimal: true         # google.type.Decimal   <-> "12.34"
      money: false          # google.type.Money     <-> {"currency":"USD","amount":"12.34"}
    duration:
      output: PROTO         # PROTO ("5400s") | ISO_8601 ("PT1H30M")
      accept-iso8601: true  # also read "PT1H30M" on input
    timestamp:
      accept-epoch-millis: false  # also read a JSON number of epoch millis on input
    int64-as: STRING        # STRING ("42", proto3) | NUMBER (42; exact only below 2^53 in JS)
    strict-input: false     # true: refuse "abc" for an int32 with a 400 instead of reading 0
```

Switching a type off restores the plain proto3 object form on every surface, which is what
`JsonFormat` produces.

The settings apply to the whole process: one gateway speaks one JSON dialect for every service.
They are read once at startup, before any serializer is compiled, and schema refreshes keep
them. The one exception is the response layout below, which a service can override.

## Response layout (print options)

Four switches change how REST responses are laid out. The defaults are the gateway's historical output.

```yaml
shiguchi:
  json:
    print:
      preserve-proto-field-names: false  # true: "display_name" instead of "displayName"
      enums-as-ints: false               # true: "kind": 1 instead of "kind": "BOOK"
      omit-default-values: false         # true: leave out 0, "", false, [] and {} fields
      pretty-print: false                # true: indented JSON
  services:
    - name: legacy-catalog
      json-print:                        # per-service overrides; unset inherits the above
        preserve-proto-field-names: true
```

```jsonc
// defaults
{"displayName":"Dune","kind":"BOOK","stock":0,"tags":[],"released":"2026-09-22"}
// preserve-proto-field-names + enums-as-ints + omit-default-values
{"display_name":"Dune","kind":1,"released":"2026-09-22"}
```

- **Output only.** Requests accept both field spellings and enum names or numbers either way.
- **Everything else still applies:** `google.type` as ISO text, `int64-as`, durations, and
  `response_body`, which sends the named field laid out the same way.
- `omit-default-values` only drops fields *without* presence. A message field, an `optional`
  field or a `oneof` member is printed whenever it is set, even to its default, exactly as
  `JsonFormat` does.
- **REST only.** GraphQL field names come from the schema, and the Avro and REST_DOWNSTREAM
  paths do not go through the proto JSON printers.
- **The OpenAPI document follows them.** A response message printed with proto field names or
  enum numbers gets its own component, suffixed `_proto_names`, `_enums_as_ints` or both
  (`Book_proto_names`), with the properties under the printed names and each enum an `integer`
  whose description lists the names (`0 = UNKNOWN, 1 = HARDCOVER`). Request bodies keep the
  default `Book` component, since input accepts either spelling, and so do services on the
  default options. `omit-default-values` changes no schema: no property is marked `required`.
- **Cost:** a service that changes any option is served by the wire-to-JSON transcoder (Tier 0),
  which applies the options itself — including services whose stubs are compiled, since the typed
  Tier 1 prints the default layout only. Types that reach `Any`, responses whose pagination
  headers need the decoded message, and the rare encoding Tier 0 cannot stream fall back to the
  reflective writer (`JsonFormat` for `Any`). Measured on a map- and WKT-heavy message, Tier 0 is
  about 1.4× the reflective writer; services left on the defaults are unaffected.
- `pretty-print` is different: it re-indents the finished body, so it works on whichever tier
  serves the body and combines with the other three. It applies to whole-body responses — unary calls,
  `response_body`, merged pages; NDJSON and SSE streams stay one message per line, and error
  bodies come from the error renderer unchanged. Numbers are copied exactly, never re-rounded.
  Every body is written twice, so keep it for services people read with curl.

## Performance and serde tiers

The mapping is applied in every [serde tier](transcoding.md#the-serde-tiers), so the output is
the same whichever tier serves a message:

- **Tier 0** (wire → JSON) and **Tier 2** (generated serializers) treat mapped types like
  well-known types, at no extra cost.
- **Tier 1** (the writer compiled for a stub class) applies it too: the writer is compiled
  under the installed mapping, so a response reaching a mapped type stays on the Tier 1 path.
- **Tier 3** (`JsonFormat`) cannot be taught it either. A message that fails to compile for
  Tier 2 is served by the reflective converter instead, with the same output. The exception is
  a message that also reaches `google.protobuf.Any`: only `JsonFormat` can print `Any`, so such
  a message keeps the object form for its `google.type` fields.
