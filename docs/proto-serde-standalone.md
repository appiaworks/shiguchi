# Using the proto serde outside the gateway

The gateway's JSON dialect and its type converters ship as a library of their own,
`protobuf-java-extras`. A gRPC service, a batch job or a test can use it without running a
gateway and without Spring or gRPC on the classpath:

- [`ProtoJson`](#json) reads and writes messages as JSON in the gateway's dialect, so a service
  and the gateway in front of it agree on every byte.
- [`ProtoScalarCodec`](#your-own-scalar-types) writes a message type of yours as one JSON value.
- [`ProtoTypes`](#converting-types) converts well-known proto types to and from `java.time`,
  `BigDecimal` and friends.

Everything here is `EXPERIMENTAL` ([api-compatibility.md](api-compatibility.md)).

[`samples/sample-protobuf-java-extras`](../samples/sample-protobuf-java-extras) runs all of it in
one plain Java program, plus a Kafka serializer and deserializer built on it.

## Dependency

```kotlin
dependencies {
    implementation("io.github.appiaworks:protobuf-java-extras:<version>")
}
```

It depends on `protobuf-java`, `protobuf-java-util`, the `com.google.type` classes
(`proto-google-common-protos`) and `jackson-core`. No Spring, no gRPC, no `jackson-databind`. It
is what `shiguchi-core` itself uses, so an application that already has the gateway on its
classpath has it too.

One optional addition, for [compiled serializers](#speed):

```kotlin
    // Java 25+: the generated serializers; inert on an older JVM
    runtimeOnly("io.github.appiaworks:protobuf-java-extras-classfile:<version>")
```

## JSON

```java
import dev.appiaworks.protobuf.extras.ProtoJson;

ProtoJson json = ProtoJson.defaults();

Interval interval = Interval.newBuilder()
        .setStartTime(ProtoTypes.timestamp(Instant.parse("2026-09-22T14:03:11Z")))
        .setEndTime(ProtoTypes.timestamp(Instant.parse("2026-09-22T15:00:00Z")))
        .build();

String text = json.print(interval);
// {"startTime":"2026-09-22T14:03:11Z","endTime":"2026-09-22T15:00:00Z"}

Interval back = json.parse(text, Interval.getDefaultInstance());
```

A `ProtoJson` is immutable and thread-safe. Build it once and share it.

| Method | What it does |
| --- | --- |
| `print(message)` | The message as JSON text |
| `printUtf8(message)` | The same as UTF-8 bytes |
| `print(message, writer)` | The same, written to a `Writer` (flushed, not closed) |
| `write(message, jsonGenerator)` | One JSON value into a Jackson generator you own |
| `print(bytes, descriptor)` | Serialized proto bytes as JSON text, without building the message |
| `transcode(bytes, descriptor)` | Proto bytes in, UTF-8 JSON bytes out |
| `parse(text, prototype)` | JSON text into the generated class |
| `parse(text, descriptor)` | JSON text into a `DynamicMessage` |
| `toMap(message)` | The tree the JSON would parse to: maps, lists, strings, numbers, booleans |
| `fromMap(map, prototype)` | The reverse |

### Choosing the dialect

| Start from | What it speaks |
| --- | --- |
| `ProtoJson.defaults()` | The gateway's defaults: `google.type` Date, TimeOfDay, DateTime and Decimal as ISO text, Money as its proto object, 64-bit integers as strings |
| `ProtoJson.proto3()` | Plain proto3 JSON, as `JsonFormat` prints it |
| `ProtoJson.builder()` | The defaults, changed one switch at a time |

```java
ProtoJson json = ProtoJson.builder()
        .moneyAsAmount(true)                                   // {"currency":"USD","amount":"12.34"}
        .durationFormat(ProtoJson.DurationFormat.ISO_8601)     // "PT1H30M" instead of "5400s"
        .int64AsNumber(true)                                   // 42 instead of "42"
        .omitDefaultValues(true)                               // leave out 0, "", false, []
        .build();
```

The builder's switches are the ones [`shiguchi.json.*`](json-types.md#configuration)
exposes: `dateAsText`, `timeOfDayAsText`, `dateTimeAsText`, `decimalAsText`, `moneyAsAmount`,
`durationFormat`, `acceptIso8601Duration`, `acceptEpochMillisTimestamp`, `int64AsNumber`, and for
layout `preserveProtoFieldNames`, `enumsAsInts`, `omitDefaultValues` and `numberFormat(JsonNumberFormat.ECMASCRIPT)`. To match a gateway, set
them the way its YAML does.

### What to expect

- **Output** follows proto3 JSON: lowerCamelCase names, 64-bit integers as strings, zero-valued
  scalars and empty lists written, unset message fields left out.
- **Input** accepts lowerCamelCase and the original proto field names. Unknown fields are
  ignored. JSON `null` leaves a field unset.
- **Malformed values throw `ProtoJsonException`** (an `IllegalArgumentException`): text that is
  not a JSON object, a Timestamp of `"yesterday"`, `"abc"` or `1.5` for an `int32`, a number for
  a `string`. Nothing is coerced: `ProtoJson` reads under the gateway's
  [strict input](json-types.md#strict-input) rules. The message names the type, the value, the
  field and what was expected; `typeName()` and `value()` give the first two on their own.
- **Field order is not fixed.** A generated message and the bytes methods print fields in the
  order they have on the wire, with defaults last; a `DynamicMessage` prints them in the order
  the proto declares. The JSON value is the same. Compare JSON as JSON, not as text.
- **Single-value messages stay single values.** A message whose JSON form is one value prints and
  parses as that value on its own too: `print(timestamp)` is `"2026-09-22T14:03:11Z"` with the
  quotes, `print(Int64Value.of(42))` is `"42"`, and a `google.type.Date` is `"2026-09-22"` under
  the defaults and an object under `proto3()`. `toMap` refuses such a message, since it has no
  object to return.
- **`toMap` ignores the layout switches.** Its keys are always lowerCamelCase and it leaves
  nothing out; `preserveProtoFieldNames`, `enumsAsInts` and `omitDefaultValues` shape `print`
  and `write`.
- **`google.protobuf.Any` is not unpacked.** It comes out as its raw `typeUrl` and `value`
  fields, not as proto3's `@type` form. Use `JsonFormat` with a `TypeRegistry` for messages that
  carry one.

## Your own scalar types

Give the builder a codec and every field of that message type, in any message, at any depth,
reads and writes as one value:

```java
import dev.appiaworks.protobuf.extras.ProtoScalarCodec;

ProtoScalarCodec latLng = ProtoScalarCodec.ofString(
        LatLng.getDefaultInstance(),
        ll -> ll.getLatitude() + "," + ll.getLongitude(),
        text -> {
            String[] parts = text.split(",");
            if (parts.length != 2) throw new IllegalArgumentException("expected lat,lng");
            return LatLng.newBuilder()
                    .setLatitude(Double.parseDouble(parts[0]))
                    .setLongitude(Double.parseDouble(parts[1]))
                    .build();
        });

ProtoJson json = ProtoJson.builder().scalar(latLng).build();

Viewport viewport = Viewport.newBuilder()
        .setLow(LatLng.newBuilder().setLatitude(40.5).setLongitude(-74.25))
        .setHigh(LatLng.newBuilder().setLatitude(41.0).setLongitude(-73.5))
        .build();

json.print(viewport);
// {"low":"40.5,-74.25","high":"41.0,-73.5"}

json.parse("{\"low\":\"nowhere\"}", Viewport.getDefaultInstance());
// ProtoJsonException: Invalid google.type.LatLng value 'nowhere': expected lat,lng
```

The rules are the ones the gateway applies, described in
[json-types.md](json-types.md#your-own-scalar-types): other JSON shapes through
`ProtoScalarCodec.of(..)`, the object form still accepted on input, a codec for a built-in
`google.type` message replacing the built-in form, and no codecs for `google.protobuf` types.
Two codecs for one message fail at `build()`.

`codec.withSchema(ScalarSchema.string().withPattern(..).withExample(..))` describes the value for
the OpenAPI, GraphQL and Avro schemas a gateway publishes
([json-types.md](json-types.md#describing-the-value)). `ProtoJson` reads and writes the same
either way.

## Converting types

`ProtoTypes` is static and needs no setup.

```java
import dev.appiaworks.protobuf.extras.MoneyValue;
import dev.appiaworks.protobuf.extras.ProtoTypes;

Timestamp at        = ProtoTypes.timestamp(Instant.now());
Instant instant     = ProtoTypes.toInstant(at);

Date day            = ProtoTypes.date(LocalDate.of(2026, 9, 22));
LocalDate localDate = ProtoTypes.toLocalDate(day);

Money price         = ProtoTypes.money(new MoneyValue("USD", new BigDecimal("12.34")));
BigDecimal amount   = ProtoTypes.toMoneyValue(price).amount();

DateTime meeting    = ProtoTypes.dateTime(ZonedDateTime.now(ZoneId.of("America/New_York")));
```

The method named after the proto type builds the message; `toXxx` returns the Java value. The
full table is in [json-types.md](json-types.md#converting-in-java).

Three rules cover the edges:

- **Nothing is guessed.** A value the Java type cannot hold throws `IllegalArgumentException`
  saying what is missing. A `Date` with no day is not a `LocalDate`; ask `toTemporal` and you get
  a `YearMonth`, a `Year` or a `MonthDay`. A `DateTime` with no offset is not an
  `OffsetDateTime`; ask `toLocalDateTime`.
- **Nothing is rounded.** `money(..)` rejects an amount with more than 9 fractional digits.
- **Wrappers map unset to null.** `ProtoTypes.unwrap(msg.hasCount() ? msg.getCount() : null)`
  returns null, and `wrap(null)` returns null for you to leave the field unset.

To get these conversions as methods on your generated classes
(`order.getPlacedOnAsLocalDate()`, `builder.setPlacedOn(LocalDate)`), see
[protoc-java-extras.md](protoc-java-extras.md).

Working from descriptors rather than generated classes? `ProtoTypes.as(dynamicMessage,
Date.getDefaultInstance())` gives the generated class, and `ProtoTypes.dynamic(message,
descriptor)` goes the other way.

## Alongside a gateway

In an application that runs the gateway, inject the `ProtoJson` bean it registers. It speaks the
gateway's configured dialect: `shiguchi.json.*`, the global print options and every
`ProtoScalarCodec` bean. What it prints is what the gateway's REST surface would write for that
message (per-service print overrides aside):

```java
@Component
class OrderEvents {
    private final ProtoJson json;

    OrderEvents(ProtoJson json) {
        this.json = json;
    }

    void publish(Order order) {
        kafka.send("orders", json.printUtf8(order));
    }
}
```

Define a `ProtoJson` bean of your own and the gateway's backs off.

Otherwise, a `ProtoJson` instance is independent of everything else in the JVM:

- Two instances with different dialects work side by side, on any number of threads.
- An application that also runs the gateway can use a `ProtoJson` of another dialect. The
  gateway's configured dialect is not changed by it and does not leak into it. Codec beans
  registered with the gateway are not picked up by a `ProtoJson`; pass them to its builder.
- `ProtoJson` throws `ProtoJsonException` even with the gateway on the classpath. Only the
  gateway's own request handling reports the same failure as gRPC `INVALID_ARGUMENT`.

## From bytes

A consumer that already holds serialized messages, such as a Kafka record or a proxied gRPC
response, does not need to parse them to print them:

```java
byte[] json = protoJson.transcode(record.value(), Order.getDescriptor());   // UTF-8 JSON
String text = protoJson.print(record.value(), Order.getDescriptor());
```

The bytes are walked once and written straight out as JSON. No message object is built. Bytes
that are not a message of that type throw `IllegalArgumentException`.

The sample's [Kafka section](../samples/sample-protobuf-java-extras/README.md#kafka) builds on
this: a serializer and deserializer that write proto binary or JSON and label each record with a
`content-type` header, and a bridge that republishes a binary topic as JSON this way.

## Speed

`ProtoJson` has four ways to print and picks one per call. All four produce the same JSON.
Parsing has three, which accept the same documents and build the same messages.

| Path | Used for | Needs |
| --- | --- | --- |
| Generated writer | A generated message, with the default layout, on every print method | Classes built with [`protoc-gen-java-extras`](protoc-java-extras.md#json) (any Java), or `protobuf-java-extras-classfile` on the classpath, Java 25+ |
| Wire transcoder | A generated message otherwise; the bytes methods | Nothing extra |
| Generated reader | `parse(text, prototype)` into a generated class, straight through its Builder | Classes built with [`protoc-gen-java-extras`](protoc-java-extras.md#json) (any Java) |
| Compiled serializer | A `DynamicMessage`, with the default layout; `parse` otherwise | `protobuf-java-extras-classfile` on the classpath, Java 25+ |
| Reflective converter | A `DynamicMessage` otherwise, `parse` without the generator, `fromMap`, and whatever the others decline | Nothing extra |

- **Generated writers** read a generated message through its own getters and write the JSON
  as they go: nothing is serialized or copied first. A class built with
  `protoc-gen-java-extras` carries its writer (`writeJson`), which is used as is. Otherwise one
  is generated per class on first use, with the writers of every class its fields reach, and
  cached in the `ProtoJson` instance.
- **The wire transcoder** serializes a generated message (which protobuf does quickly) and turns
  those bytes into JSON without building a second message. It applies every builder switch,
  layout included, so a non-default layout prints through it, and so does every generated
  message when the generator is absent.
- **The generated reader** (`mergeJson` on the Builder) streams the text into a generated
  class through its setters: no `DynamicMessage` and no re-read from bytes in between, under
  the same strict rules as the other two.
- **Compiled serializers** print `DynamicMessage`s, for code that works from descriptors, and
  parse every message: the text streams straight into the message, with no tree in between. They are generated per message type on first use, with the Class-File API, and cached
  in the `ProtoJson` instance that compiled them. Add `protobuf-java-extras-classfile` and run on
  Java 25+ to use them;
  without it `ProtoJson` works the same on the reflective converter.
  `ProtoJson.builder().compiledSerializers(false)` switches them off where generating classes at
  run time is unwelcome, and `usesCompiledSerializers()` tells you which you have.
- **What declines.** A message that reaches `google.protobuf.Any`, proto2 groups, and wire
  encodings that need merging (a non-repeated field sent twice) go to the reflective converter.
  You do not have to handle this.

Measured with JMH (`ProtoJsonBenchmark`) on one developer machine, one fork: an order with 6
scalars, 8 nested items, a 3-entry map and a Timestamp, in a class built with
`protoc-gen-java-extras`. Treat the ratios as indicative; the two columns are one run each on the
same jar, and the JDK 21 one has no generator.

| Operation | JDK 25 | JDK 21 |
| --- | --- | --- |
| `JsonFormat.printer().print(message)` | 8.8 µs | 7.9 µs |
| `ProtoJson.print(message)`, the class's own `writeJson` | 2.5 µs | 2.9 µs |
| `ProtoJson.print(message, writer)`, the same | 2.6 µs | 3.1 µs |
| `ProtoJson.transcode(bytes, descriptor)`, wire transcoder | 4.3 µs | 4.8 µs |
| `ProtoJson.print(dynamicMessage, writer)`, compiled serializer | 3.0 µs | — |
| `ProtoJson.print(dynamicMessage, writer)`, reflective | 3.4 µs | 3.9 µs |
| `JsonFormat.parser().merge(text, builder)` | 9.9 µs | 10.0 µs |
| `ProtoJson.parse(text, prototype)`, the Builder's own `mergeJson` | 5.0 µs | 5.1 µs |
| `ProtoJson.parse(text, descriptor)` + `ProtoTypes.as`, compiled serializer | 9.6 µs | — |
| `ProtoJson.parse(text, descriptor)` + `ProtoTypes.as`, reflective | 11.5 µs | 11.7 µs |

So: a class built with the plugin prints in well under a third of the time `JsonFormat` takes
and parses in half, on either JDK, with nothing generated at run time. Without the plugin a
generated message prints through the transcoder (serialize, then the `transcode` row) and
parses through the descriptor path. The generated serializers buy about 13% on
`DynamicMessage` printing and bring descriptor parsing level with `JsonFormat`, which the
reflective parser trails by about 15%.

## Limits today

- **`fromMap` is not accelerated.** `parse` streams through the compiled parser when the generator is
  there; `fromMap` starts from a tree already, and uses the reflective converter.
- **`parse` converts.** It builds a `DynamicMessage` and re-reads it as the generated class,
  one extra encode and decode per call. Printing does not.
- **No `Any`**, as noted above.
- **`dev.appiaworks.protobuf.extras.serde` is not for you.** The package holds the gateway's machinery
  (`ProtoJsonMapping`, `ProtoMapConverter`) and may change in any release. Use
  `dev.appiaworks.protobuf.extras`.
