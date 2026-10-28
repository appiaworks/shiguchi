# sample-protobuf-java-extras

`protobuf-java-extras` in a plain Java program: no gateway, no Spring, no gRPC. What a service, a
batch job or a Kafka consumer next to a gateway would use to speak the same JSON.

It shows, one numbered step each in [`Main`](src/main/java/com/acme/shop/Main.java):

1. **Java types on generated classes.** `setPlacedOn(LocalDate)`, `setTotal(MoneyValue)`,
   `getDeliveryWindowAsDuration()`: added by `protoc-gen-java-extras` through the
   `io.github.appiaworks.protoc-java-extras` Gradle plugin, which is the only proto setup the build
   has.
2. **The gateway's default JSON**, `ProtoJson.defaults()`.
3. **A dialect of your own**, `ProtoJson.builder()`: Money as an amount, ISO 8601 durations, and
   `acme.shop.Sku` written as its code (`"AB-1"`) through a `ProtoScalarCodec`.
4. **Parsing** what a client sent, then reading it as Java types.
5. **Bytes to JSON** without building a message.
6. **Malformed input**, refused with the type and the value named.
7. **`ProtoTypes` on its own**: a `Date` with no day is a `YearMonth`, not a guess.
8. **A Kafka serializer and deserializer** for proto messages, in proto binary or JSON.
9. **A bridge** that republishes a binary topic as JSON through the transcoder.
10. **Which paths run**: an `Order` prints and parses through the `writeJson` and `mergeJson`
    the plugin generated into the class, on any Java. `usesCompiledSerializers()` says whether
    `DynamicMessage`s go through serializers generated at run time (Java 25+ with
    `protobuf-java-extras-classfile` on the classpath) or through the reflective converter.
    The JSON is the same either way.

## Run it

```bash
./gradlew publishToMavenLocal                         # once, from the repo root
./gradlew -p samples sample-protobuf-java-extras:run
./gradlew -p samples sample-protobuf-java-extras:run -PrunJava=25   # step 10 on the generated path
```

```
1. Built with Java types:  placed on 2026-09-22, total MoneyValue[currencyCode=EUR, amount=41.5]
2. Gateway defaults:       {"id":"o-1001","placedOn":"2026-09-22","paidAt":"2026-09-22T14:03:11Z","total":{"currencyCode":"EUR","units":"41","nanos":500000000},"deliveryWindow":"7200s","lines":[{"sku":{"code":"AB-1"},"quantity":2,"unitWeightKg":"0.25"}]}
3. Shop dialect:           {"id":"o-1001","placedOn":"2026-09-22","paidAt":"2026-09-22T14:03:11Z","total":{"currency":"EUR","amount":"41.5"},"deliveryWindow":"PT2H","lines":[{"sku":"AB-1","quantity":2,"unitWeightKg":"0.25"}]}
4. Parsed:                 PT30M window, sku CD-7, total 9.99
5. From bytes:             {"id":"o-1001", ... same as 3 ...}
6. Malformed:              Invalid google.type.Date value '22/09/2026': expected YYYY-MM-DD, YYYY-MM, YYYY or --MM-DD
                           Invalid acme.shop.Sku value 'ab1': expected a SKU like AB-1, got ab1
7. Partial date:           2026-09
8. Kafka:                  PROTOBUF: 65 bytes, content-type=application/x-protobuf, read back equal: true
                           JSON: 198 bytes, content-type=application/json, read back equal: true
9. Binary topic as JSON:   {"id":"o-1001", ... same as 3 ...}
10. Serializers:           Order's own writer and reader generated into the class; DynamicMessage serializers reflective (Java 21; generated ones need 25+ and protobuf-java-extras-classfile)
```

Against a local checkout, `./gradlew publishToMavenLocal`
puts the current build in `~/.m2`, which the sample resolves first.

## The build

```kotlin
plugins {
    application
    id("io.github.appiaworks.protoc-java-extras")
}

javaExtras {
    map("acme.shop.Sku", "com.acme.shop.SkuCode", "com.acme.shop.SkuCode.fromProto", "com.acme.shop.SkuCode.toProto")
}
```

The plugin applies `com.google.protobuf`, runs the accessor generator next to the Java one and
adds `protobuf-java-extras` to the classpath. One more line,
`runtimeOnly("io.github.appiaworks:protobuf-java-extras-classfile:$gatewayVersion")`, brings the
serializer generator: it needs Java 25 and is inert below. The samples run on a Java 21 toolchain,
so step 10 reports the reflective converter for `DynamicMessage`s; with `-PrunJava=25` the
program runs on a Java 25 JDK (provisioned if needed) and the line ends with
`generated at run time (Java 25)`. The `Order` itself prints and parses through the methods
the plugin generated either way. The `map` line gives the application's own
`acme.shop.Sku` message accessors in terms of the `SkuCode` record. With Maven instead, see
[`sample-protoc-java-extras-maven`](../sample-protoc-java-extras-maven).

## Kafka

[`com.acme.shop.kafka`](src/main/java/com/acme/shop/kafka) holds a serializer, a deserializer and
a bridge, small enough to copy into a project. The sample calls them directly, so no broker is
needed to run it.

```java
ProtoJson json = ProtoJson.builder().scalar(Codecs.SKU).build();

KafkaProducer<String, Order> producer = new KafkaProducer<>(props,
        new StringSerializer(), new ProtoSerializer<>(json, WireFormat.JSON));

KafkaConsumer<String, Order> consumer = new KafkaConsumer<>(props,
        new StringDeserializer(), new ProtoDeserializer<>(json, WireFormat.PROTOBUF, Order.getDefaultInstance()));
```

- **Two formats, one switch.** `WireFormat.PROTOBUF` writes `message.toByteArray()`: smallest and
  fastest, but every consumer needs the schema. `WireFormat.JSON` writes the message as
  `ProtoJson` prints it, so a consumer in any language, a log viewer, or a person can read it.
  With the same `ProtoJson` dialect as the gateway, a topic and the gateway's REST API carry the
  same JSON for the same message.
- **The serializer labels each record.** It sets a `content-type` header
  (`application/x-protobuf` or `application/json`). The deserializer reads by that header and
  uses its own format only for records without one, so a topic can hold both while producers
  move from one format to the other.
- **Bad records are Kafka's kind of error.** A value that does not read throws Kafka's
  `SerializationException` naming the type, the topic and the format, so the consumer's error
  handling (skip, dead-letter) applies.
- **The bridge does not parse.** [`JsonBridge`](src/main/java/com/acme/shop/kafka/JsonBridge.java)
  turns a binary record into JSON with `ProtoJson.transcode(bytes, descriptor)`. The bytes are
  walked once and written straight out as JSON; no `Order` is built. It needs only a
  `Descriptor`, so a bridge can run from a schema registry or a descriptor file with no generated
  code for the topic.

Pass the serializers as instances, as above. Named by class in `value.serializer`, Kafka would
create them with no `ProtoJson` and no message type.

## Further reading

- [docs/proto-serde-standalone.md](../../docs/proto-serde-standalone.md): `ProtoJson`, codecs,
  `ProtoTypes`, speed.
- [docs/protoc-java-extras.md](../../docs/protoc-java-extras.md): the accessors, every type they
  cover, and the Gradle and Maven setup.
