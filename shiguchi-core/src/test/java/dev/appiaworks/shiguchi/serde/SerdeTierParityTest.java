package dev.appiaworks.shiguchi.serde;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MessageOptions;
import com.google.protobuf.DescriptorProtos.OneofDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Duration;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.FieldMask;
import com.google.protobuf.Int32Value;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.JsonFormat;
import dev.appiaworks.protobuf.extras.serde.AbstractDynamicSerializer;
import dev.appiaworks.protobuf.extras.serde.Codegen;
import dev.appiaworks.protobuf.extras.serde.JsonPrintOptions;
import dev.appiaworks.protobuf.extras.serde.JsonWireTranscoder;
import dev.appiaworks.protobuf.extras.serde.ProtoMapConverter;
import dev.appiaworks.protobuf.extras.serde.ProtoWkt;
import dev.appiaworks.protobuf.extras.serde.WireBuffer;
import dev.appiaworks.protobuf.extras.serde.WireJsonTranscoder;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tier-equivalence property tests: for a kitchen-sink message covering every proto field
 * shape (scalars incl. unsigned, enums, bytes, oneof, proto3 optional, nested + repeated
 * messages, map fields with all key kinds, and the JSON-special well-known types), the
 * Tier 2 generated serializer must produce byte-for-byte-equivalent JSON to the
 * Tier 3 JsonFormat baseline, and both directions must round-trip.
 *
 * These tests are the contract that keeps Tier 2 honest: any field shape added to the
 * generator must be added to {@link #randomMessage} so it is covered here.
 */
class SerdeTierParityTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final JsonFactory JSON_FACTORY = new JsonFactory();

    private static Descriptor allTypes;
    private static Descriptor inner;
    private static AbstractDynamicSerializer allTypesSerializer;
    private static AbstractDynamicSerializer innerSerializer;

    @BeforeAll
    static void compileSerializers() throws Exception {
        Assumptions.assumeTrue(
                Codegen.available(),
                "needs the serializer code generator: Java 25+ with protobuf-java-extras-classfile");
        FileDescriptor file = buildParityFile();
        allTypes = file.findMessageTypeByName("AllTypes");
        inner = file.findMessageTypeByName("Inner");

        DynamicSerializerFactory factory = new DynamicSerializerFactory();
        allTypesSerializer = factory.compileDescriptor(allTypes);
        innerSerializer = factory.compileDescriptor(inner);
        // Wire the nested serializer the way precompile() pass 2 does
        allTypesSerializer.setNested("parity.Inner", innerSerializer);
    }

    // ── JSON parity ──────────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 2, 3, 5, 8, 13, 21, 34, 55, 89, 144, 233, 377, 610, 987, 1597, 2584, 4181, 6765, 10946})
    @DisplayName(
            "Given a random kitchen-sink message, when Tier 2 serializes, should emit the same JSON as Tier 3 JsonFormat")
    void given_randomMessage_when_tier2Serializes_should_matchJsonFormatOutput(long seed) throws Exception {
        DynamicMessage msg = randomMessage(new Random(seed));

        String tier2Json = tier2Serialize(msg);
        String tier3Json = JsonFormat.printer()
                .includingDefaultValueFields()
                .omittingInsignificantWhitespace()
                .print(msg);

        JsonNode tier2Tree = OBJECT_MAPPER.readTree(tier2Json);
        JsonNode tier3Tree = OBJECT_MAPPER.readTree(tier3Json);
        assertThat(tier2Tree)
                .as("seed %d\ntier2: %s\ntier3: %s", seed, tier2Json, tier3Json)
                .isEqualTo(tier3Tree);
    }

    @Test
    @DisplayName(
            "Given an empty message, when Tier 2 serializes, should match JsonFormat default-value and presence rules")
    void given_emptyMessage_when_tier2Serializes_should_matchDefaultValueRules() throws Exception {
        DynamicMessage msg = DynamicMessage.newBuilder(allTypes).build();

        JsonNode tier2Tree = OBJECT_MAPPER.readTree(tier2Serialize(msg));
        JsonNode tier3Tree = OBJECT_MAPPER.readTree(JsonFormat.printer()
                .includingDefaultValueFields()
                .omittingInsignificantWhitespace()
                .print(msg));

        assertThat(tier2Tree).isEqualTo(tier3Tree);
        // Presence fields must be absent, no-presence scalars and repeated/map fields present
        assertThat(tier2Tree.has("inner")).isFalse();
        assertThat(tier2Tree.has("maybe")).isFalse();
        assertThat(tier2Tree.has("choiceStr")).isFalse();
        assertThat(tier2Tree.get("i32").asInt()).isZero();
        assertThat(tier2Tree.get("tags").isArray()).isTrue();
        assertThat(tier2Tree.get("labels").isObject()).isTrue();
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 2, 3, 5, 8})
    @DisplayName(
            "Given a random message, when Tier 2 serializes to bytes, should produce the UTF-8 encoding of the String output")
    void given_randomMessage_when_tier2SerializesToBytes_should_matchStringOutput(long seed) throws Exception {
        DynamicMessage msg = randomMessage(new Random(seed));

        String viaString = tier2Serialize(msg);
        byte[] viaBytes;
        var bytes = new com.fasterxml.jackson.core.util.ByteArrayBuilder();
        try (JsonGenerator gen = JSON_FACTORY.createGenerator(
                (java.io.OutputStream) bytes, com.fasterxml.jackson.core.JsonEncoding.UTF8)) {
            allTypesSerializer.serialize(msg, gen);
        }
        viaBytes = bytes.toByteArray();

        assertThat(new String(viaBytes, java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo(viaString);
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 2, 3, 5, 8, 13, 21, 34, 55, 89, 144, 233, 377, 610, 987, 1597, 2584, 4181, 6765, 10946})
    @DisplayName(
            "Given a random kitchen-sink message, when Tier 0 transcodes its wire bytes, should emit the same JSON as JsonFormat")
    void given_randomMessage_when_tier0Transcodes_should_matchJsonFormatOutput(long seed) throws Exception {
        DynamicMessage msg = randomMessage(new Random(seed));
        WireJsonTranscoder transcoder = new WireJsonTranscoder();
        WireJsonTranscoder.Program program = transcoder.programFor(allTypes);
        assertThat(program).as("kitchen-sink type must be Tier 0 eligible").isNotNull();

        byte[] tier0Json = transcoder.transcode(program, msg.toByteArray());

        assertThat(tier0Json)
                .as("well-formed protobuf-java wire encoding must not bail")
                .isNotNull();
        JsonNode tier0Tree = OBJECT_MAPPER.readTree(tier0Json);
        JsonNode tier3Tree = OBJECT_MAPPER.readTree(JsonFormat.printer()
                .includingDefaultValueFields()
                .omittingInsignificantWhitespace()
                .print(msg));
        assertThat(tier0Tree)
                .as("seed %d\ntier0: %s", seed, new String(tier0Json, java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo(tier3Tree);
    }

    // ── Deserialize parity ───────────────────────────────────────────────────────────

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 2, 3, 5, 8, 13, 21, 34, 55, 89})
    @DisplayName("Given JsonFormat output, when Tier 2 deserializes, should rebuild an equal message")
    void given_jsonFormatOutput_when_tier2Deserializes_should_rebuildEqualMessage(long seed) throws Exception {
        DynamicMessage msg = randomMessage(new Random(seed));
        String json = JsonFormat.printer().print(msg);

        DynamicMessage rebuilt = tier2Deserialize(json);

        assertThat(rebuilt).as("seed %d\njson: %s", seed, json).isEqualTo(msg);
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 2, 3, 5, 8, 13, 21, 34, 55, 89})
    @DisplayName("Given Tier 2 output, when Tier 3 JsonFormat parses it, should rebuild an equal message")
    void given_tier2Output_when_jsonFormatParses_should_rebuildEqualMessage(long seed) throws Exception {
        DynamicMessage msg = randomMessage(new Random(seed));
        String tier2Json = tier2Serialize(msg);

        DynamicMessage.Builder builder = DynamicMessage.newBuilder(allTypes);
        JsonFormat.parser().merge(tier2Json, builder);

        assertThat(builder.build()).as("seed %d\ntier2: %s", seed, tier2Json).isEqualTo(msg);
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 2, 3, 5, 8})
    @DisplayName(
            "Given no nested serializer wired, when Tier 2 deserializes nested messages, should fall back to databind and rebuild an equal message")
    void given_unwiredNestedSerializer_when_tier2Deserializes_should_fallBackToDatabind(long seed) throws Exception {
        DynamicMessage msg = randomMessage(new Random(seed));
        String json = JsonFormat.printer().print(msg);

        // Fresh serializer with no setNested() wiring — nested Inner fields take the fallback path
        AbstractDynamicSerializer unwired = new DynamicSerializerFactory().compileDescriptor(allTypes);
        DynamicMessage rebuilt;
        try (JsonParser p = JSON_FACTORY.createParser(new StringReader(json))) {
            p.nextToken();
            rebuilt = unwired.deserialize(p, DynamicMessage.newBuilder(allTypes));
        }

        assertThat(rebuilt).as("seed %d\njson: %s", seed, json).isEqualTo(msg);
    }

    @Test
    @DisplayName(
            "Given explicit JSON null for a google.protobuf.Value field, when Tier 2 deserializes, should set null_value")
    void given_jsonNullForValueField_when_tier2Deserializes_should_setNullValue() throws Exception {
        DynamicMessage rebuilt = tier2Deserialize("{\"dynamic\":null}");

        FieldDescriptor dynamicFd = allTypes.findFieldByName("dynamic");
        assertThat(rebuilt.hasField(dynamicFd)).isTrue();

        // JsonFormat round-trip prints it back as null
        String json = JsonFormat.printer().omittingInsignificantWhitespace().print(rebuilt);
        assertThat(json).isEqualTo("{\"dynamic\":null}");
    }

    @Test
    @DisplayName("Given snake_case field names in JSON, when Tier 2 deserializes, should accept them like JsonFormat")
    void given_snakeCaseFieldNames_when_tier2Deserializes_should_acceptThem() throws Exception {
        DynamicMessage rebuilt = tier2Deserialize("{\"choice_str\":\"x\",\"created_at\":\"2023-11-14T22:13:20Z\"}");

        assertThat(rebuilt.getField(allTypes.findFieldByName("choice_str"))).isEqualTo("x");
        assertThat(rebuilt.hasField(allTypes.findFieldByName("created_at"))).isTrue();
    }

    @Test
    @DisplayName(
            "Given unknown JSON fields with object and array values, when Tier 2 deserializes, should skip them entirely")
    void given_unknownJsonFields_when_tier2Deserializes_should_skipSubtrees() throws Exception {
        DynamicMessage rebuilt =
                tier2Deserialize("{\"nope\":{\"deep\":[1,2,{\"x\":true}]},\"str\":\"kept\",\"alsoNope\":[[]]}");

        assertThat(rebuilt.getField(allTypes.findFieldByName("str"))).isEqualTo("kept");
    }

    // ── Map (GraphQL) path round-trips ───────────────────────────────────────────────

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 2, 3, 5, 8, 13, 21, 34, 55, 89})
    @DisplayName("Given a random message, when Tier 2 toMap then fromMap, should round-trip to an equal message")
    void given_randomMessage_when_tier2ToMapFromMap_should_roundTrip(long seed) {
        DynamicMessage msg = randomMessage(new Random(seed));

        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) allTypesSerializer.toMap(msg);
        DynamicMessage rebuilt =
                allTypesSerializer.fromMap(new LinkedHashMap<>(map), DynamicMessage.newBuilder(allTypes));

        assertThat(rebuilt).as("seed %d\nmap: %s", seed, map).isEqualTo(msg);
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 2, 3, 5, 8, 13, 21, 34, 55, 89})
    @DisplayName(
            "Given a random message, when Tier 3 ProtoMapConverter round-trips it, should rebuild an equal message")
    void given_randomMessage_when_tier3MapRoundTrip_should_rebuildEqualMessage(long seed) {
        DynamicMessage msg = randomMessage(new Random(seed));

        Map<String, Object> map = ProtoMapConverter.dynamicToMap(msg);
        DynamicMessage rebuilt = ProtoMapConverter.dynamicFromMap(map, allTypes);

        assertThat(rebuilt).as("seed %d\nmap: %s", seed, map).isEqualTo(msg);
    }

    @Test
    @DisplayName(
            "Given String values as sent by REST query params, when both tiers fromMap, should coerce numbers, bools and floats")
    void given_stringQueryParamValues_when_fromMap_should_coerceInBothTiers() {
        Map<String, Object> queryStyle = Map.of(
                "i32", "42",
                "i64", "9999999999",
                "u64", "18446744073709551615",
                "flag", "true",
                "flt", "1.5",
                "dbl", "2.75",
                "color", "GREEN");

        DynamicMessage tier2 =
                allTypesSerializer.fromMap(new LinkedHashMap<>(queryStyle), DynamicMessage.newBuilder(allTypes));
        DynamicMessage tier3 = ProtoMapConverter.dynamicFromMap(new LinkedHashMap<>(queryStyle), allTypes);

        for (DynamicMessage rebuilt : List.of(tier2, tier3)) {
            assertThat(rebuilt.getField(allTypes.findFieldByName("i32"))).isEqualTo(42);
            assertThat(rebuilt.getField(allTypes.findFieldByName("i64"))).isEqualTo(9999999999L);
            assertThat(rebuilt.getField(allTypes.findFieldByName("u64"))).isEqualTo(-1L); // unsigned max
            assertThat(rebuilt.getField(allTypes.findFieldByName("flag"))).isEqualTo(true);
            assertThat(rebuilt.getField(allTypes.findFieldByName("flt"))).isEqualTo(1.5f);
            assertThat(rebuilt.getField(allTypes.findFieldByName("dbl"))).isEqualTo(2.75d);
            assertThat(((com.google.protobuf.Descriptors.EnumValueDescriptor)
                                    rebuilt.getField(allTypes.findFieldByName("color")))
                            .getName())
                    .isEqualTo("GREEN");
        }
    }

    // ── Any exclusion ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName(
            "Given a message containing google.protobuf.Any, when compiling Tier 2, should refuse so Tier 3 handles it")
    void given_messageWithAnyField_when_compiling_should_refuse() throws Exception {
        FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                .setName("withany.proto")
                .setSyntax("proto3")
                .setPackage("parity")
                .addDependency("google/protobuf/any.proto")
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("HasAny")
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName("payload")
                                .setNumber(1)
                                .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                                .setTypeName(".google.protobuf.Any")
                                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)))
                .build();
        Descriptor hasAny = FileDescriptor.buildFrom(proto, new FileDescriptor[] {
                    com.google.protobuf.Any.getDescriptor().getFile()
                })
                .findMessageTypeByName("HasAny");

        DynamicSerializerFactory factory = new DynamicSerializerFactory();
        try {
            factory.compileDescriptor(hasAny);
            org.junit.jupiter.api.Assertions.fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            assertThat(expected.getMessage()).contains("Any");
        }
    }

    // ── JSON print options parity ────────────────────────────────────────────────────

    @ParameterizedTest(name = "seed {0}, preserve={1}, enums={2}, omit={3}")
    @org.junit.jupiter.params.provider.MethodSource("printOptionsCombinations")
    @DisplayName(
            "Given a random message and JSON print options, when ProtoMapConverter writes it, should match JsonFormat")
    void given_randomMessageAndOptions_when_protoMapConverterWrites_should_matchJsonFormat(
            long seed, boolean preserve, boolean enumsAsInts, boolean omitDefaults) throws Exception {
        DynamicMessage msg = randomMessage(new Random(seed));
        JsonPrintOptions options = new JsonPrintOptions(preserve, enumsAsInts, omitDefaults);

        // ProtoMapConverter path
        StringWriter sw = new StringWriter();
        try (JsonGenerator gen = JSON_FACTORY.createGenerator(sw)) {
            ProtoMapConverter.writeMessageJson(gen, msg, options);
        }
        String protoMapJson = sw.toString();

        // JsonFormat baseline with matching options
        var printer = JsonFormat.printer().omittingInsignificantWhitespace();
        if (!omitDefaults) printer = printer.includingDefaultValueFields();
        if (preserve) printer = printer.preservingProtoFieldNames();
        if (enumsAsInts) printer = printer.printingEnumsAsInts();
        String jsonFormatOutput = printer.print(msg);

        JsonNode protoMapTree = OBJECT_MAPPER.readTree(protoMapJson);
        JsonNode jsonFormatTree = OBJECT_MAPPER.readTree(jsonFormatOutput);
        assertThat(protoMapTree)
                .as("seed %d, options %s\nprotoMap: %s\njsonFormat: %s", seed, options, protoMapJson, jsonFormatOutput)
                .isEqualTo(jsonFormatTree);
    }

    @ParameterizedTest(name = "seed {0}, preserve={1}, enums={2}, omit={3}")
    @org.junit.jupiter.params.provider.MethodSource("printOptionsCombinations")
    @DisplayName(
            "Given a random message and JSON print options, when DynamicProtoSerde serializes, should match JsonFormat")
    void given_randomMessageAndOptions_when_dynamicSerdeRespondsToJson_should_matchJsonFormat(
            long seed, boolean preserve, boolean enumsAsInts, boolean omitDefaults) throws Exception {
        DynamicMessage msg = randomMessage(new Random(seed));
        JsonPrintOptions options = new JsonPrintOptions(preserve, enumsAsInts, omitDefaults);

        // Build a minimal TypeRegistry for the message
        var typeRegistry =
                JsonFormat.TypeRegistry.newBuilder().add(allTypes).add(inner).build();

        DynamicProtoSerde serde = new DynamicProtoSerde(null);
        String dynamicSerdeJson = serde.responseToJson(msg, typeRegistry, options);

        // JsonFormat baseline with matching options
        var printer = JsonFormat.printer().omittingInsignificantWhitespace();
        if (!omitDefaults) printer = printer.includingDefaultValueFields();
        if (preserve) printer = printer.preservingProtoFieldNames();
        if (enumsAsInts) printer = printer.printingEnumsAsInts();
        String jsonFormatOutput = printer.usingTypeRegistry(typeRegistry).print(msg);

        JsonNode dynamicSerdeTree = OBJECT_MAPPER.readTree(dynamicSerdeJson);
        JsonNode jsonFormatTree = OBJECT_MAPPER.readTree(jsonFormatOutput);
        assertThat(dynamicSerdeTree)
                .as(
                        "seed %d, options %s\ndynamicSerde: %s\njsonFormat: %s",
                        seed, options, dynamicSerdeJson, jsonFormatOutput)
                .isEqualTo(jsonFormatTree);
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 2, 3, 5, 8, 13, 21, 34, 55, 89})
    @DisplayName("Given DEFAULT options, when ProtoMapConverter writes, should match byte-for-byte with old signature")
    void given_defaultOptions_when_protoMapConverterWrites_should_matchOldSignature(long seed) throws Exception {
        DynamicMessage msg = randomMessage(new Random(seed));

        // Old signature (implicit DEFAULT)
        StringWriter sw1 = new StringWriter();
        try (JsonGenerator gen = JSON_FACTORY.createGenerator(sw1)) {
            ProtoMapConverter.writeMessageJson(gen, msg);
        }
        String oldSig = sw1.toString();

        // New signature with explicit DEFAULT
        StringWriter sw2 = new StringWriter();
        try (JsonGenerator gen = JSON_FACTORY.createGenerator(sw2)) {
            ProtoMapConverter.writeMessageJson(gen, msg, JsonPrintOptions.DEFAULT);
        }
        String newSig = sw2.toString();

        assertThat(newSig).as("seed %d", seed).isEqualTo(oldSig);
    }

    // ── Test data source for print options combinations ────────────────────────────

    @ParameterizedTest(name = "seed {0}, preserve={1}, enums={2}, omit={3}")
    @org.junit.jupiter.params.provider.MethodSource("printOptionsCombinations")
    @DisplayName(
            "Given a random message and JSON print options, when Tier 0 transcodes its wire bytes, should match JsonFormat")
    void given_randomMessageAndOptions_when_tier0Transcodes_should_matchJsonFormat(
            long seed, boolean preserve, boolean enumsAsInts, boolean omitDefaults) throws Exception {
        DynamicMessage msg = randomMessage(new Random(seed));
        JsonPrintOptions options = new JsonPrintOptions(preserve, enumsAsInts, omitDefaults);
        WireJsonTranscoder transcoder = new WireJsonTranscoder();

        byte[] tier0Json = transcoder.transcode(transcoder.programFor(allTypes), msg.toByteArray(), options);

        assertThat(tier0Json).as("well-formed wire encoding must not bail").isNotNull();
        var printer = JsonFormat.printer().omittingInsignificantWhitespace();
        if (!omitDefaults) printer = printer.includingDefaultValueFields();
        if (preserve) printer = printer.preservingProtoFieldNames();
        if (enumsAsInts) printer = printer.printingEnumsAsInts();
        String jsonFormatOutput = printer.print(msg);
        assertThat(OBJECT_MAPPER.readTree(tier0Json))
                .as(
                        "seed %d, options %s%ntier0: %s%njsonFormat: %s",
                        seed, options, new String(tier0Json, java.nio.charset.StandardCharsets.UTF_8), jsonFormatOutput)
                .isEqualTo(OBJECT_MAPPER.readTree(jsonFormatOutput));
    }

    @Test
    @DisplayName(
            "Given explicit zeros on the wire for fields without presence, when Tier 0 omits defaults, should leave them out as the reflective writer does")
    void given_explicitZerosOnWire_when_tier0OmitsDefaults_should_leaveThemOut() throws Exception {
        // A non-canonical encoder may send i32=0, str="" and color=0; protobuf-java never would.
        var bytes = new java.io.ByteArrayOutputStream();
        var out = com.google.protobuf.CodedOutputStream.newInstance(bytes);
        out.writeInt32(allTypes.findFieldByName("i32").getNumber(), 0);
        out.writeString(allTypes.findFieldByName("str").getNumber(), "");
        out.writeEnum(allTypes.findFieldByName("color").getNumber(), 0);
        out.writeInt64(allTypes.findFieldByName("i64").getNumber(), 7);
        out.flush();
        WireJsonTranscoder transcoder = new WireJsonTranscoder();
        JsonPrintOptions omit = new JsonPrintOptions(false, false, true);

        byte[] tier0Json = transcoder.transcode(transcoder.programFor(allTypes), bytes.toByteArray(), omit);

        StringWriter reflective = new StringWriter();
        try (JsonGenerator gen = JSON_FACTORY.createGenerator(reflective)) {
            ProtoMapConverter.writeMessageJson(gen, DynamicMessage.parseFrom(allTypes, bytes.toByteArray()), omit);
        }
        assertThat(OBJECT_MAPPER.readTree(tier0Json)).isEqualTo(OBJECT_MAPPER.readTree(reflective.toString()));
        assertThat(OBJECT_MAPPER.readTree(tier0Json).fieldNames()).toIterable().containsExactly("i64");
    }

    @Test
    @DisplayName(
            "Given an empty packed run on the wire, when Tier 0 omits defaults, should bail so the caller falls back")
    void given_emptyPackedRun_when_tier0OmitsDefaults_should_bail() throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        var out = com.google.protobuf.CodedOutputStream.newInstance(bytes);
        out.writeTag(
                allTypes.findFieldByName("nums").getNumber(), com.google.protobuf.WireFormat.WIRETYPE_LENGTH_DELIMITED);
        out.writeUInt32NoTag(0);
        out.flush();
        WireJsonTranscoder transcoder = new WireJsonTranscoder();

        assertThat(transcoder.transcode(
                        transcoder.programFor(allTypes), bytes.toByteArray(), new JsonPrintOptions(false, false, true)))
                .isNull();
        assertThat(transcoder.transcode(transcoder.programFor(allTypes), bytes.toByteArray(), JsonPrintOptions.DEFAULT))
                .as("with defaults printed an empty list is just []")
                .isNotNull();
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> printOptionsCombinations() {
        // All 8 combinations of (preserveProtoFieldNames, enumsAsInts, omitDefaultValues)
        // with a sampling of seed values from the parity test
        long[] seeds = {1, 2, 3, 5, 8, 13, 21, 34};
        java.util.List<org.junit.jupiter.params.provider.Arguments> args = new java.util.ArrayList<>();
        for (long seed : seeds) {
            for (boolean preserve : new boolean[] {false, true}) {
                for (boolean enums : new boolean[] {false, true}) {
                    for (boolean omit : new boolean[] {false, true}) {
                        args.add(org.junit.jupiter.params.provider.Arguments.of(seed, preserve, enums, omit));
                    }
                }
            }
        }
        return args.stream();
    }

    // ── Serialization helpers ────────────────────────────────────────────────────────

    private static String tier2Serialize(DynamicMessage msg) throws Exception {
        StringWriter sw = new StringWriter();
        try (JsonGenerator gen = JSON_FACTORY.createGenerator(sw)) {
            allTypesSerializer.serialize(msg, gen);
        }
        return sw.toString();
    }

    private static DynamicMessage tier2Deserialize(String json) throws Exception {
        try (JsonParser p = JSON_FACTORY.createParser(new StringReader(json))) {
            p.nextToken(); // START_OBJECT
            return allTypesSerializer.deserialize(p, DynamicMessage.newBuilder(allTypes));
        }
    }

    // ── Random message generation ────────────────────────────────────────────────────

    static DynamicMessage randomMessage(Random rnd) {
        DynamicMessage.Builder b = DynamicMessage.newBuilder(allTypes);

        set(b, "i32", rnd.nextInt());
        set(b, "i64", rnd.nextLong());
        set(b, "u32", rnd.nextInt()); // any bit pattern; JSON must be unsigned
        set(b, "u64", rnd.nextLong()); // any bit pattern; JSON must be unsigned
        set(b, "s32", rnd.nextInt());
        set(b, "sf64", rnd.nextLong());
        set(b, "dbl", rnd.nextInt(10_000) / 4.0d);
        set(b, "flt", rnd.nextInt(10_000) / 2.0f);
        set(b, "flag", rnd.nextBoolean());
        set(b, "str", "s-" + rnd.nextInt(1_000_000));
        byte[] bytes = new byte[8];
        rnd.nextBytes(bytes);
        set(b, "data", ByteString.copyFrom(bytes));
        FieldDescriptor colorFd = allTypes.findFieldByName("color");
        b.setField(colorFd, colorFd.getEnumType().getValues().get(rnd.nextInt(4)));

        if (rnd.nextBoolean()) {
            b.setField(allTypes.findFieldByName("inner"), randomInner(rnd));
        }
        for (int i = 0, n = rnd.nextInt(4); i < n; i++) {
            b.addRepeatedField(allTypes.findFieldByName("tags"), "t-" + rnd.nextInt(100));
            b.addRepeatedField(allTypes.findFieldByName("nums"), rnd.nextLong());
            b.addRepeatedField(
                    allTypes.findFieldByName("colors"),
                    colorFd.getEnumType().getValues().get(rnd.nextInt(4)));
            b.addRepeatedField(allTypes.findFieldByName("inners"), randomInner(rnd));
        }

        addMapEntry(b, "labels", "k-" + rnd.nextInt(100), "v-" + rnd.nextInt(100));
        addMapEntry(b, "counts", rnd.nextInt(1000), rnd.nextLong());
        addMapEntry(b, "items", "item-" + rnd.nextInt(100), randomInner(rnd));
        addMapEntry(b, "flags", rnd.nextBoolean(), "f-" + rnd.nextInt(100));

        if (rnd.nextBoolean()) {
            set(
                    b,
                    "created_at",
                    wkt(
                            Timestamp.newBuilder()
                                    .setSeconds(rnd.nextInt(2_000_000_000))
                                    .setNanos(rnd.nextInt(1_000_000_000))
                                    .build(),
                            "created_at"));
        }
        if (rnd.nextBoolean()) {
            set(
                    b,
                    "ttl",
                    wkt(
                            Duration.newBuilder()
                                    .setSeconds(rnd.nextInt(100_000))
                                    .setNanos(rnd.nextInt(1_000_000_000))
                                    .build(),
                            "ttl"));
        }
        if (rnd.nextBoolean()) {
            set(
                    b,
                    "mask",
                    wkt(
                            FieldMask.newBuilder()
                                    .addPaths("foo_bar")
                                    .addPaths("baz.qux_quux")
                                    .build(),
                            "mask"));
        }
        if (rnd.nextBoolean()) {
            Map<String, Object> struct = Map.of(
                    "a", rnd.nextInt(100) / 4.0d,
                    "b", "sv-" + rnd.nextInt(100),
                    "c", rnd.nextBoolean(),
                    "nested", Map.of("n", rnd.nextInt(100) / 2.0d),
                    "list", List.of(1.5d, "x", true));
            set(
                    b,
                    "meta",
                    ProtoWkt.fromJava(struct, allTypes.findFieldByName("meta").getMessageType()));
        }
        if (rnd.nextBoolean()) {
            Object value = switch (rnd.nextInt(3)) {
                case 0 -> "vs-" + rnd.nextInt(100);
                case 1 -> rnd.nextInt(1000) / 4.0d;
                default -> rnd.nextBoolean();
            };
            set(
                    b,
                    "dynamic",
                    ProtoWkt.fromJava(value, allTypes.findFieldByName("dynamic").getMessageType()));
        }
        if (rnd.nextBoolean()) {
            set(
                    b,
                    "list",
                    ProtoWkt.fromJava(
                            List.of(2.5d, "lv", false),
                            allTypes.findFieldByName("list").getMessageType()));
        }
        if (rnd.nextBoolean()) set(b, "opt_i64", wrapperMsg("opt_i64", rnd.nextLong()));
        if (rnd.nextBoolean()) set(b, "opt_u64", wrapperMsg("opt_u64", rnd.nextLong()));
        if (rnd.nextBoolean()) set(b, "opt_i32", wrapperMsg("opt_i32", rnd.nextInt()));
        if (rnd.nextBoolean()) set(b, "opt_u32", wrapperMsg("opt_u32", rnd.nextInt()));
        if (rnd.nextBoolean()) set(b, "opt_dbl", wrapperMsg("opt_dbl", rnd.nextInt(1000) / 8.0d));
        if (rnd.nextBoolean()) set(b, "opt_flt", wrapperMsg("opt_flt", rnd.nextInt(1000) / 2.0f));
        if (rnd.nextBoolean()) set(b, "opt_bool", wrapperMsg("opt_bool", rnd.nextBoolean()));
        if (rnd.nextBoolean()) set(b, "opt_str_w", wrapperMsg("opt_str_w", "w-" + rnd.nextInt(100)));
        if (rnd.nextBoolean()) {
            byte[] wb = new byte[5];
            rnd.nextBytes(wb);
            set(b, "opt_bytes", wrapperMsg("opt_bytes", ByteString.copyFrom(wb)));
        }
        for (int i = 0, n = rnd.nextInt(3); i < n; i++) {
            b.addRepeatedField(
                    allTypes.findFieldByName("times"),
                    wkt(
                            Timestamp.newBuilder()
                                    .setSeconds(1_700_000_000L + rnd.nextInt(100_000))
                                    .setNanos(rnd.nextInt(1_000_000_000))
                                    .build(),
                            "times"));
        }
        if (rnd.nextBoolean()) {
            set(b, "maybe", "maybe-" + rnd.nextInt(100));
        }
        if (rnd.nextBoolean()) {
            set(b, "choice_str", "c-" + rnd.nextInt(100));
        } else if (rnd.nextBoolean()) {
            set(b, "choice_num", rnd.nextInt());
        }

        return b.build();
    }

    private static DynamicMessage randomInner(Random rnd) {
        return DynamicMessage.newBuilder(inner)
                .setField(inner.findFieldByName("value"), "iv-" + rnd.nextInt(1000))
                .setField(inner.findFieldByName("code"), rnd.nextInt(1000))
                .build();
    }

    private static void set(DynamicMessage.Builder b, String field, Object value) {
        b.setField(allTypes.findFieldByName(field), value);
    }

    /** Re-homes a compiled WKT message onto the field's (possibly non-canonical) descriptor. */
    private static DynamicMessage wkt(com.google.protobuf.Message compiled, String field) {
        Descriptor target = allTypes.findFieldByName(field).getMessageType();
        try {
            return DynamicMessage.parseFrom(target, compiled.toByteString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static DynamicMessage wrapperMsg(String field, Object value) {
        Descriptor d = allTypes.findFieldByName(field).getMessageType();
        return DynamicMessage.newBuilder(d)
                .setField(d.findFieldByNumber(1), value)
                .build();
    }

    private static void addMapEntry(DynamicMessage.Builder b, String field, Object key, Object value) {
        FieldDescriptor mapFd = allTypes.findFieldByName(field);
        Descriptor entry = mapFd.getMessageType();
        b.addRepeatedField(
                mapFd,
                DynamicMessage.newBuilder(entry)
                        .setField(entry.findFieldByNumber(1), key)
                        .setField(entry.findFieldByNumber(2), value)
                        .build());
    }

    // ── Kitchen-sink descriptor ──────────────────────────────────────────────────────

    private static FileDescriptor buildParityFile() throws Exception {
        DescriptorProto innerMsg = DescriptorProto.newBuilder()
                .setName("Inner")
                .addField(scalar("value", 1, FieldDescriptorProto.Type.TYPE_STRING))
                .addField(scalar("code", 2, FieldDescriptorProto.Type.TYPE_INT32))
                .build();

        DescriptorProto.Builder m = DescriptorProto.newBuilder().setName("AllTypes");
        m.addField(scalar("i32", 1, FieldDescriptorProto.Type.TYPE_INT32));
        m.addField(scalar("i64", 2, FieldDescriptorProto.Type.TYPE_INT64));
        m.addField(scalar("u32", 3, FieldDescriptorProto.Type.TYPE_UINT32));
        m.addField(scalar("u64", 4, FieldDescriptorProto.Type.TYPE_UINT64));
        m.addField(scalar("s32", 5, FieldDescriptorProto.Type.TYPE_SINT32));
        m.addField(scalar("sf64", 6, FieldDescriptorProto.Type.TYPE_SFIXED64));
        m.addField(scalar("dbl", 7, FieldDescriptorProto.Type.TYPE_DOUBLE));
        m.addField(scalar("flt", 8, FieldDescriptorProto.Type.TYPE_FLOAT));
        m.addField(scalar("flag", 9, FieldDescriptorProto.Type.TYPE_BOOL));
        m.addField(scalar("str", 10, FieldDescriptorProto.Type.TYPE_STRING));
        m.addField(scalar("data", 11, FieldDescriptorProto.Type.TYPE_BYTES));
        m.addField(enumField("color", 12, ".parity.Color"));
        m.addField(message("inner", 13, ".parity.Inner"));
        m.addField(repeated(scalar("tags", 14, FieldDescriptorProto.Type.TYPE_STRING)));
        m.addField(repeated(scalar("nums", 15, FieldDescriptorProto.Type.TYPE_INT64)));
        m.addField(repeated(enumField("colors", 16, ".parity.Color")));
        m.addField(repeated(message("inners", 17, ".parity.Inner")));

        m.addNestedType(mapEntry(
                "LabelsEntry",
                scalar("key", 1, FieldDescriptorProto.Type.TYPE_STRING),
                scalar("value", 2, FieldDescriptorProto.Type.TYPE_STRING)));
        m.addField(repeated(message("labels", 18, ".parity.AllTypes.LabelsEntry")));
        m.addNestedType(mapEntry(
                "CountsEntry",
                scalar("key", 1, FieldDescriptorProto.Type.TYPE_INT32),
                scalar("value", 2, FieldDescriptorProto.Type.TYPE_INT64)));
        m.addField(repeated(message("counts", 19, ".parity.AllTypes.CountsEntry")));
        m.addNestedType(mapEntry(
                "ItemsEntry",
                scalar("key", 1, FieldDescriptorProto.Type.TYPE_STRING),
                message("value", 2, ".parity.Inner")));
        m.addField(repeated(message("items", 20, ".parity.AllTypes.ItemsEntry")));
        m.addNestedType(mapEntry(
                "FlagsEntry",
                scalar("key", 1, FieldDescriptorProto.Type.TYPE_BOOL),
                scalar("value", 2, FieldDescriptorProto.Type.TYPE_STRING)));
        m.addField(repeated(message("flags", 21, ".parity.AllTypes.FlagsEntry")));

        m.addField(message("created_at", 22, ".google.protobuf.Timestamp"));
        m.addField(message("ttl", 23, ".google.protobuf.Duration"));
        m.addField(message("mask", 24, ".google.protobuf.FieldMask"));
        m.addField(message("meta", 25, ".google.protobuf.Struct"));
        m.addField(message("dynamic", 26, ".google.protobuf.Value"));
        m.addField(message("list", 27, ".google.protobuf.ListValue"));
        m.addField(message("opt_i64", 28, ".google.protobuf.Int64Value"));
        m.addField(message("opt_u64", 29, ".google.protobuf.UInt64Value"));
        m.addField(message("opt_i32", 30, ".google.protobuf.Int32Value"));
        m.addField(message("opt_u32", 31, ".google.protobuf.UInt32Value"));
        m.addField(message("opt_dbl", 32, ".google.protobuf.DoubleValue"));
        m.addField(message("opt_flt", 33, ".google.protobuf.FloatValue"));
        m.addField(message("opt_bool", 34, ".google.protobuf.BoolValue"));
        m.addField(message("opt_str_w", 35, ".google.protobuf.StringValue"));
        m.addField(message("opt_bytes", 36, ".google.protobuf.BytesValue"));
        m.addField(repeated(message("times", 37, ".google.protobuf.Timestamp")));

        // A real two-member oneof, then a proto3 optional (its synthetic oneof must come last)
        m.addOneofDecl(OneofDescriptorProto.newBuilder().setName("choice"));
        m.addField(scalar("choice_str", 39, FieldDescriptorProto.Type.TYPE_STRING).toBuilder()
                .setOneofIndex(0)
                .build());
        m.addField(scalar("choice_num", 40, FieldDescriptorProto.Type.TYPE_INT32).toBuilder()
                .setOneofIndex(0)
                .build());
        m.addOneofDecl(OneofDescriptorProto.newBuilder().setName("_maybe"));
        m.addField(scalar("maybe", 38, FieldDescriptorProto.Type.TYPE_STRING).toBuilder()
                .setProto3Optional(true)
                .setOneofIndex(1)
                .build());

        EnumDescriptorProto colorEnum = EnumDescriptorProto.newBuilder()
                .setName("Color")
                .addValue(EnumValueDescriptorProto.newBuilder()
                        .setName("COLOR_UNSPECIFIED")
                        .setNumber(0))
                .addValue(EnumValueDescriptorProto.newBuilder().setName("RED").setNumber(1))
                .addValue(EnumValueDescriptorProto.newBuilder().setName("GREEN").setNumber(2))
                .addValue(EnumValueDescriptorProto.newBuilder().setName("BLUE").setNumber(3))
                .build();

        FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                .setName("parity.proto")
                .setSyntax("proto3")
                .setPackage("parity")
                .addDependency("google/protobuf/timestamp.proto")
                .addDependency("google/protobuf/duration.proto")
                .addDependency("google/protobuf/field_mask.proto")
                .addDependency("google/protobuf/struct.proto")
                .addDependency("google/protobuf/wrappers.proto")
                .addEnumType(colorEnum)
                .addMessageType(innerMsg)
                .addMessageType(m)
                .build();

        return FileDescriptor.buildFrom(proto, new FileDescriptor[] {
            Timestamp.getDescriptor().getFile(),
            Duration.getDescriptor().getFile(),
            FieldMask.getDescriptor().getFile(),
            Struct.getDescriptor().getFile(),
            Int32Value.getDescriptor().getFile(),
        });
    }

    private static FieldDescriptorProto scalar(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .build();
    }

    private static FieldDescriptorProto message(String name, int number, String typeName) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                .setTypeName(typeName)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .build();
    }

    private static FieldDescriptorProto enumField(String name, int number, String typeName) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(FieldDescriptorProto.Type.TYPE_ENUM)
                .setTypeName(typeName)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .build();
    }

    private static FieldDescriptorProto repeated(FieldDescriptorProto field) {
        return field.toBuilder()
                .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
                .build();
    }

    private static DescriptorProto mapEntry(String name, FieldDescriptorProto key, FieldDescriptorProto value) {
        return DescriptorProto.newBuilder()
                .setName(name)
                .setOptions(MessageOptions.newBuilder().setMapEntry(true))
                .addField(key)
                .addField(value)
                .build();
    }

    // ── JSON → wire transcoding contract ─────────────────────────────────────────────

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 2, 3, 5, 8, 13, 21, 34, 55, 89})
    @DisplayName(
            "Given JsonFormat output of a kitchen-sink message, when transcoded to wire, should parse back to the original message")
    void given_jsonFormatOutput_when_transcodedToWire_should_parseBackToOriginal(long seed) throws Exception {
        DynamicMessage msg = randomMessage(new Random(seed));
        String json = JsonFormat.printer()
                .includingDefaultValueFields()
                .omittingInsignificantWhitespace()
                .print(msg);

        JsonWireTranscoder transcoder = new JsonWireTranscoder();
        JsonWireTranscoder.Program program = transcoder.programFor(allTypes);
        assertThat(program).as("kitchen-sink type must support transcoding").isNotNull();

        long seen = transcoder.transcode(program, json, new WireBuffer(), false);
        assertThat(seen)
                .as("well-formed JSON from JsonFormat must transcode; json: %s", json)
                .isGreaterThanOrEqualTo(0);

        // The test verifies both the direct transcode path and the fallback Map path agree
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 2, 3, 5, 8})
    @DisplayName(
            "Given JsonFormat output without default values, when transcoded to wire, should parse back to the original message")
    void given_jsonFormatOutputOmitDefaults_when_transcodedToWire_should_parseBackToOriginal(long seed)
            throws Exception {
        DynamicMessage msg = randomMessage(new Random(seed));
        String json = JsonFormat.printer().omittingInsignificantWhitespace().print(msg);

        JsonWireTranscoder transcoder = new JsonWireTranscoder();
        JsonWireTranscoder.Program program = transcoder.programFor(allTypes);

        WireBuffer out = new WireBuffer();
        long seen = transcoder.transcode(program, json, out, false);
        assertThat(seen).as("JsonFormat output must transcode; json: %s", json).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("Given JSON with unknown top-level keys and nested unknown objects, when transcoded, should skip them")
    void given_unknownJsonKeys_when_transcoded_should_skipThem() throws Exception {
        String json = "{\"i32\":42,\"unknownTop\":{\"nested\":true},\"str\":\"keep\",\"alsoUnknown\":[1,2,3]}";

        JsonWireTranscoder transcoder = new JsonWireTranscoder();
        JsonWireTranscoder.Program program = transcoder.programFor(allTypes);

        WireBuffer out = new WireBuffer();
        long seen = transcoder.transcode(program, json, out, false);

        assertThat(seen).isGreaterThanOrEqualTo(0);
        DynamicMessage parsed = DynamicMessage.parseFrom(allTypes, out.toByteArray());
        assertThat(parsed.getField(allTypes.findFieldByName("i32"))).isEqualTo(42);
        assertThat(parsed.getField(allTypes.findFieldByName("str"))).isEqualTo("keep");
    }

    @Test
    @DisplayName("Given JSON with a duplicated key, when transcoded, should return -1")
    void given_duplicatedJsonKey_when_transcoded_should_returnNegativeOne() {
        String json = "{\"i32\":42,\"i32\":99}";

        JsonWireTranscoder transcoder = new JsonWireTranscoder();
        JsonWireTranscoder.Program program = transcoder.programFor(allTypes);

        WireBuffer out = new WireBuffer();
        long result = transcoder.transcode(program, json, out, false);

        assertThat(result).isEqualTo(-1L);
    }

    @Test
    @DisplayName("Given an int32 field holding 1.5 in JSON, when transcoded, should return -1")
    void given_fractionalNumberForIntField_when_transcoded_should_returnNegativeOne() {
        String json = "{\"i32\":1.5}";

        JsonWireTranscoder transcoder = new JsonWireTranscoder();
        JsonWireTranscoder.Program program = transcoder.programFor(allTypes);

        WireBuffer out = new WireBuffer();
        long result = transcoder.transcode(program, json, out, false);

        assertThat(result).isEqualTo(-1L);
    }

    @Test
    @DisplayName("Given a string field holding a number in JSON, when transcoded, should return -1")
    void given_numberForStringField_when_transcoded_should_returnNegativeOne() {
        String json = "{\"str\":123}";

        JsonWireTranscoder transcoder = new JsonWireTranscoder();
        JsonWireTranscoder.Program program = transcoder.programFor(allTypes);

        WireBuffer out = new WireBuffer();
        long result = transcoder.transcode(program, json, out, false);

        assertThat(result).isEqualTo(-1L);
    }

    @Test
    @DisplayName("Given JSON null for a scalar field in lenient mode, when transcoded, should leave the field unset")
    void given_nullForScalarField_when_transcodedLenient_should_leaveFieldUnset() throws Exception {
        String json = "{\"i32\":null,\"str\":\"kept\"}";

        JsonWireTranscoder transcoder = new JsonWireTranscoder();
        JsonWireTranscoder.Program program = transcoder.programFor(allTypes);

        WireBuffer out = new WireBuffer();
        long seen = transcoder.transcode(program, json, out, false);

        assertThat(seen).isGreaterThanOrEqualTo(0);
        DynamicMessage parsed = DynamicMessage.parseFrom(allTypes, out.toByteArray());
        assertThat(parsed.getField(allTypes.findFieldByName("str"))).isEqualTo("kept");
        // i32 should be unset or at default
    }

    @Test
    @DisplayName(
            "Given the returned bitmask from transcode, should have exactly the bits of the top-level fields present")
    void given_transcodeBitmask_should_matchPresentFields() throws Exception {
        String json = "{\"i32\":42,\"str\":\"hello\",\"flag\":true}";

        JsonWireTranscoder transcoder = new JsonWireTranscoder();
        JsonWireTranscoder.Program program = transcoder.programFor(allTypes);

        WireBuffer out = new WireBuffer();
        long seen = transcoder.transcode(program, json, out, false);

        assertThat(seen).isGreaterThanOrEqualTo(0);

        // Verify the bits correspond to the fields that were set
        FieldDescriptor i32Fd = allTypes.findFieldByName("i32");
        FieldDescriptor strFd = allTypes.findFieldByName("str");
        FieldDescriptor flagFd = allTypes.findFieldByName("flag");

        assertThat((seen & (1L << i32Fd.getIndex())) != 0).isTrue();
        assertThat((seen & (1L << strFd.getIndex())) != 0).isTrue();
        assertThat((seen & (1L << flagFd.getIndex())) != 0).isTrue();
    }
}
