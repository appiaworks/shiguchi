# Java-typed accessors on generated proto classes

`protoc-gen-javaextras` is a protoc plugin. It adds methods to the classes protoc's Java
generator writes, so a field of a well-known type reads and writes as the Java type you would
rather hold:

```java
// Without it
Trip trip = Trip.newBuilder()
        .setDepartsOn(Date.newBuilder().setYear(2026).setMonth(9).setDay(22))
        .setBookedAt(Timestamp.newBuilder().setSeconds(now.getEpochSecond()).setNanos(now.getNano()))
        .build();
LocalDate day = LocalDate.of(
        trip.getDepartsOn().getYear(), trip.getDepartsOn().getMonth(), trip.getDepartsOn().getDay());

// With it
Trip trip = Trip.newBuilder()
        .setDepartsOn(LocalDate.of(2026, 9, 22))
        .setBookedAt(Instant.now())
        .build();
LocalDate day = trip.getDepartsOnAsLocalDate();
```

The generated accessors stay exactly as they were. The new ones sit beside them, on the message
and on its `Builder`. Every message also gets a [`writeJson`](#json) method and its Builder a
`mergeJson`, so printing and parsing it as JSON need no reflection and no code generation at run
time.

It is independent of the gateway: use it in any project that compiles protos to Java. The
conversions are those of [`ProtoTypes`](proto-serde-standalone.md#converting-types), which the
added methods call, so the one runtime dependency is `protobuf-java-extras`.

Everything here is experimental and may change in a minor release.

## Gradle

```kotlin
plugins {
    `java-library`
    id("io.github.appiaworks.protoc-java-extras") version "<version>"
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:<protobuf version>" }
}
```

That is all. The plugin:

- applies `com.google.protobuf` if the build has not already;
- resolves the protoc plugin and runs it in every `generateProto` task, for every source set;
- adds `io.github.appiaworks:protobuf-java-extras` to `api` (to `implementation` when the project is
  not a `java-library`).

It needs the build to run on Java 21 or later, and resolves from wherever your build resolves
`io.github.appiaworks` artifacts.

Options go in a `javaExtras` block:

```kotlin
javaExtras {
    // No accessors for fields of this type.
    disable("google.type.Decimal")

    // Accessors for a message type of your own.
    map("acme.Sku", "com.acme.SkuCode", "com.acme.Skus.toCode", "com.acme.Skus.toProto")

    // No writeJson methods.
    json.set(false)

    // Manage the protobuf-java-extras dependency yourself.
    addRuntimeDependency.set(false)
}
```

To pin the protoc plugin to another version than the Gradle plugin's own:

```kotlin
dependencies {
    protocGenJavaExtras("io.github.appiaworks:protoc-gen-java-extras:<version>")
}
```

## Maven

No extra Maven plugin is needed. `io.github.ascopes:protobuf-maven-plugin` runs a protoc plugin
that is a Java program straight from its Maven coordinates, in the same protoc run as the Java
generator, which is what the accessors require:

```xml
<dependencies>
  <dependency>
    <groupId>io.github.appiaworks</groupId>
    <artifactId>protobuf-java-extras</artifactId>
    <version>${shiguchi.version}</version>
  </dependency>
</dependencies>

<build>
  <plugins>
    <plugin>
      <groupId>io.github.ascopes</groupId>
      <artifactId>protobuf-maven-plugin</artifactId>
      <version>5.1.11</version>
      <configuration>
        <protoc>${protobuf.version}</protoc>
        <plugins>
          <plugin kind="jvm-maven">
            <groupId>io.github.appiaworks</groupId>
            <artifactId>protoc-gen-java-extras</artifactId>
            <version>${shiguchi.version}</version>
            <!-- Optional:
            <options>disable=google.type.Decimal,map=acme.Sku;com.acme.SkuCode;com.acme.Skus.toCode;com.acme.Skus.toProto</options>
            -->
          </plugin>
        </plugins>
      </configuration>
      <executions>
        <execution>
          <goals>
            <goal>generate</goal>
          </goals>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

A complete project is in
[`samples/sample-protoc-java-extras-maven`](../samples/sample-protoc-java-extras-maven).
For Gradle, [`samples/sample-protobuf-java-extras`](../samples/sample-protobuf-java-extras)
applies the plugin and uses the accessors.

`org.xolstice.maven.plugins:protobuf-maven-plugin` does not work for this: it runs custom plugins
in a protoc invocation of their own, and a plugin that inserts into the Java generator's output
has to share its invocation.

## What you get

For a field `departs_on` of a mapped type, with `Xxx` standing for the Java type's name:

| Field | On the message and the Builder | On the Builder |
| --- | --- | --- |
| Singular | `getDepartsOnAsXxx()` | `setDepartsOn(Xxx)` |
| `repeated` | `getDepartsOnAsXxxList()` | `addDepartsOn(Xxx)`, `addAllDepartsOnAsXxx(Iterable)` |
| `map<K, V>` with a mapped value | `getDepartsOnAsXxxMap()` | `putDepartsOn(K, Xxx)` |

| Proto type | Java type | Getter suffix |
| --- | --- | --- |
| `google.protobuf.Timestamp` | `Instant` and `OffsetDateTime` (UTC) | `AsInstant`, `AsOffsetDateTime` |
| `google.protobuf.Duration` | `java.time.Duration` | `AsDuration` |
| `google.type.Date` | `LocalDate` | `AsLocalDate` |
| `google.type.TimeOfDay` | `LocalTime` | `AsLocalTime` |
| `google.type.DateTime` | `LocalDateTime`, `OffsetDateTime`, `ZonedDateTime` | `AsLocalDateTime`, ... |
| `google.type.Decimal` | `BigDecimal` | `AsBigDecimal` |
| `google.type.Money` | `MoneyValue` | `AsMoneyValue` |
| `google.type.DayOfWeek`, `Month` (enums) | `java.time.DayOfWeek`, `java.time.Month` | `AsJavaDayOfWeek`, `AsJavaMonth` |
| `Int32Value`, `Int64Value`, `FloatValue`, `DoubleValue`, `BoolValue`, `StringValue`, `BytesValue` | `Integer`, `Long`, `Float`, `Double`, `Boolean`, `String`, `ByteString` | `AsInteger`, ... |
| `UInt32Value`, `UInt64Value` | `Long`, `BigInteger` (the unsigned value) | `AsLong`, `AsBigInteger` |
| `google.protobuf.Struct` | `Map<String, Object>` | `AsMap` |
| `google.protobuf.FieldMask` | `List<String>` | `AsPaths` |

The rules:

- **A getter returns null when the field is not set.** That is the point of asking for a
  `LocalDate` or an `Integer` rather than a message: there is no zero value to mistake for one.
  An enum getter returns null for the `UNSPECIFIED` value.
- **A setter rejects null** with `NullPointerException`, as the generated setters do. Use the
  generated `clearDepartsOn()` to unset. The enum setters are the exception: null sets
  `UNSPECIFIED`.
- **Nothing is guessed.** The accessors call `ProtoTypes`, so a value the Java type cannot hold
  throws `IllegalArgumentException`: a `Date` with no day read as a `LocalDate`, a `DateTime`
  with no offset read as an `OffsetDateTime`, a `MoneyValue` with more than 9 fractional digits.
  The generated getter is still there for those values.
- **Lists and maps are unmodifiable copies**, converted when you call the getter.
- **Types with more than one Java form get one getter per form** and one overloaded setter.

## JSON

Every generated message implements `dev.appiaworks.protobuf.extras.JsonWritable`:

```java
public void writeJson(com.fasterxml.jackson.core.JsonGenerator gen) throws IOException
```

It writes the message as proto JSON through its own getters: every field, lowerCamelCase
names, 64-bit integers as strings, `google.type` messages and your codecs as the dialect in force
says. [`ProtoJson`](proto-serde-standalone.md#speed) prints such a message through it, and so does
the gateway when the class is on its classpath (Tier 1), on any Java the library runs on: the
writer that would otherwise be generated at run time, which needs Java 25, is already there.
The JSON is the same either way, and the same as every other path writes.

The Builder implements `dev.appiaworks.protobuf.extras.JsonReadable`, the other direction:

```java
public Builder mergeJson(com.fasterxml.jackson.core.JsonParser parser) throws IOException
```

It reads one JSON object into the builder through the setters, under the strict rules every
`ProtoJson` parse follows: a value that does not fit its field is refused with a
`ProtoJsonException`, unknown fields are ignored, JSON null leaves a field unset, fields match by
their JSON or their proto name. `ProtoJson.parse(text, prototype)` streams the text through it,
with no `DynamicMessage` and no re-read from bytes in between; the gateway's request parsing
keeps its own, configurable, rules.

You rarely call either yourself; `ProtoJson.print(message)` and `ProtoJson.parse(text, prototype)`
are the entry points, and apply the dialect. Called directly they use the default dialect, or
whatever `ProtoJsonMapping` is installed.

A message that reaches `google.protobuf.Any` or a proto2 group gets neither method; those go
through the other paths. `json=off` (`javaExtras { json.set(false) }` in Gradle) leaves both out
everywhere.

## Your own types

Map a message type of your own to a Java type with two static methods:

```java
public record SkuCode(String value) {
    public static SkuCode fromProto(Sku sku) { return new SkuCode(sku.getCode()); }
    public static Sku toProto(SkuCode code) { return Sku.newBuilder().setCode(code.value()).build(); }
}
```

```kotlin
javaExtras {
    map("acme.Sku", "com.acme.SkuCode", "com.acme.SkuCode.fromProto", "com.acme.SkuCode.toProto")
}
```

Every `acme.Sku` field now has `getSkuAsSkuCode()` and `setSku(SkuCode)`. The converters are
named as `package.Class.method`; expressions are rejected. Mapping a type from the table above
replaces its built-in accessors.

## Running protoc yourself

The plugin is an ordinary protoc plugin in a jar with a `Main-Class`. protoc starts plugins as
programs, so give it a two-line launcher:

```sh
#!/bin/sh
exec java -cp "protoc-gen-java-extras.jar:protobuf-java.jar" dev.appiaworks.protobuf.extras.protoc.JavaExtrasPlugin
```

```sh
protoc --plugin=protoc-gen-javaextras=./launcher.sh \
       --java_out=gen --javaextras_out=gen \
       --javaextras_opt=disable=google.type.Decimal \
       acme/order.proto
```

Three things to get right:

- **Same invocation, same output directory, after `--java_out`.** The plugin writes into the
  files the Java generator has just produced, through protoc's insertion points.
- **Options** are comma-separated: `disable=<proto type>`,
  `map=<proto type>;<java type>;<toJava method>;<fromJava method>` and `json=off`. The parts of a mapping are
  separated by semicolons because protoc itself splits `--javaextras_out=<options>:<dir>` at a
  colon.
- **protobuf's own classes are not regenerated.** Accessors are added to the messages in the
  files you compile, for fields *of* the mapped types.

## Limits

- **Java only.** Kotlin code can call the accessors; no Kotlin DSL extensions are generated.
- **Editions up to 2023.** Proto2, proto3 and edition 2023 files work. A later edition is
  refused by protoc until the plugin declares support for it.
- **Proto2 groups and `google.protobuf.Value` / `ListValue` fields** get no accessors. A message
  reaching a group or `google.protobuf.Any` gets no `writeJson` or `mergeJson`.
- **Java 21** to run the plugin and to use `protobuf-java-extras`.
