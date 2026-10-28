package dev.appiaworks.protobuf.extras.protoc;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.google.protobuf.ByteString;
import com.google.protobuf.Int64Value;
import com.google.protobuf.Message;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import com.google.protobuf.util.JsonFormat;
import com.google.type.Date;
import com.google.type.DayOfWeek;
import dev.appiaworks.protobuf.extras.JsonWritable;
import dev.appiaworks.protobuf.extras.ProtoJson;
import dev.appiaworks.protobuf.extras.ProtoScalarCodec;
import dev.appiaworks.protobuf.extras.ProtoTypes;
import dev.appiaworks.protobuf.extras.protoc.fixture.proto.Kind;
import dev.appiaworks.protobuf.extras.protoc.fixture.proto.Kinds;
import dev.appiaworks.protobuf.extras.protoc.fixture.proto.LegacyKindsOuterClass.LegacyKinds;
import dev.appiaworks.protobuf.extras.protoc.fixture.proto.LegacyKindsOuterClass.WithGroup;
import dev.appiaworks.protobuf.extras.protoc.fixture.proto.Part;
import dev.appiaworks.protobuf.extras.protoc.fixture.proto.ReachesAny;
import dev.appiaworks.protobuf.extras.protoc.fixture.proto.Trip;
import dev.appiaworks.protobuf.extras.protoc.fixture.proto.WithAny;
import dev.appiaworks.protobuf.extras.serde.Codegen;
import dev.appiaworks.protobuf.extras.serde.JsonTrees;
import dev.appiaworks.protobuf.extras.serde.TypedMessageWriter;
import dev.appiaworks.protobuf.extras.serde.TypedWriterPlan;
import dev.appiaworks.protobuf.extras.serde.TypedWriters;
import java.io.IOException;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code writeJson} the plugin inserts, as the generated classes in this module's build
 * carry it: every kind of field, against the paths that do not read the getters. JSON is
 * compared as a tree, since the paths may order fields differently.
 */
class GeneratedJsonWriterTest {

    private static final JsonFactory JSON = new JsonFactory();

    private static final Kinds KINDS = Kinds.newBuilder()
            .setADouble(-1.5)
            .setAFloat(2.25f)
            .setAnInt32(-7)
            .setAnInt64(-9_007_199_254_740_993L)
            .setAUint32(-1)
            .setAUint64(-1L)
            .setASint32(Integer.MIN_VALUE)
            .setASint64(Long.MIN_VALUE)
            .setAFixed32(0xFFFF_FFF0)
            .setAFixed64(0xFFFF_FFFF_FFFF_FFF0L)
            .setASfixed32(-3)
            .setASfixed64(-4L)
            .setABool(true)
            .setAString("héllo \"quoted\"")
            .setSomeBytes(ByteString.copyFrom(new byte[] {0, 1, 2, (byte) 0xff}))
            .setKindValue(99)
            .setPart(part("gear", 12))
            .setInner(Kinds.Inner.newBuilder()
                    .setLevel(Kinds.Inner.Level.LEVEL_HIGH)
                    .addParts(part("bolt", 1))
                    .addParts(part("nut", 2)))
            .setAt(Timestamp.newBuilder().setSeconds(1_758_549_791L).setNanos(500_000_000))
            .setOn(Date.newBuilder().setYear(2026).setMonth(9).setDay(22))
            .setWeekday(DayOfWeek.FRIDAY)
            .setPoints(Int64Value.of(Long.MAX_VALUE))
            .setAttributes(Struct.newBuilder()
                    .putFields("k", Value.newBuilder().setStringValue("v").build())
                    .putFields("n", Value.newBuilder().setNumberValue(1.5).build()))
            .addInt32S(1)
            .addInt32S(-2)
            .addInt64S(Long.MAX_VALUE)
            .addUint64S(-1L)
            .addStrings("a")
            .addStrings("")
            .addByteStrings(ByteString.copyFromUtf8("x"))
            .addKinds(Kind.KIND_LARGE)
            .addKindsValue(42)
            .addParts(part("p1", 1))
            .addDays(Date.newBuilder().setYear(2026).setMonth(1).setDay(1))
            .addBools(false)
            .addDoubles(1e300)
            .putLabels("a", "1")
            .putLabels("", "")
            .putIntByInt(-1, Long.MIN_VALUE)
            .putPartByUint32(-1, part("u", 3))
            .putNameByUint64(-1L, "max")
            .putKindByBool(true, Kind.KIND_SMALL)
            .putKindByBoolValue(false, 77)
            .putAtByInt64(5L, Timestamp.newBuilder().setSeconds(1).build())
            .putBytesByName("b", ByteString.copyFromUtf8("bytes"))
            .putDoubleBySint32(-9, 0.5)
            .putFloatByFixed64(-1L, 1.5f)
            .setChoicePart(part("chosen", 9))
            .setMaybe("")
            .setClass_("reserved")
            .setSerializedSize_("altered")
            .setLine2XDate("digits")
            .build();

    private static final LegacyKinds LEGACY = LegacyKinds.newBuilder()
            .setId("legacy-1")
            .setCount(0)
            .setStatus(LegacyKinds.Status.RETIRED)
            .addHistory(LegacyKinds.Status.ACTIVE)
            .addHistory(LegacyKinds.Status.RETIRED)
            .putStatusByName("x", LegacyKinds.Status.ACTIVE)
            .setPayload(ByteString.copyFromUtf8("p"))
            .setBig(-1L)
            .setNext(LegacyKinds.newBuilder().setId("legacy-2"))
            .build();

    private static final List<Message> MESSAGES = List.of(
            KINDS,
            Kinds.getDefaultInstance(),
            Kinds.newBuilder().setChoiceNumber(0).setMaybeNumber(0).build(),
            LEGACY,
            Trip.newBuilder()
                    .setDepartsOn(Date.newBuilder().setYear(2026).setMonth(9).setDay(22))
                    .setClass_(Timestamp.newBuilder().setSeconds(7))
                    .putCheckpoints(
                            "start", Timestamp.newBuilder().setSeconds(1).build())
                    .putDaysByLeg(
                            2, Date.newBuilder().setYear(2026).setMonth(10).build())
                    .addStops(Trip.Stop.newBuilder()
                            .setPlatform(Trip.Stop.Platform.newBuilder()
                                    .setClearedAt(Timestamp.newBuilder().setSeconds(2))))
                    .build());

    /** The fixtures, for the reader test. */
    static Kinds kinds() {
        return KINDS;
    }

    static LegacyKinds legacy() {
        return LEGACY;
    }

    private static Part part(String name, long weight) {
        return Part.newBuilder()
                .setName(name)
                .setWeight(weight)
                .setKind(Kind.KIND_SMALL)
                .build();
    }

    // ── Who carries a writer ─────────────────────────────────────────────────────────

    @Test
    @DisplayName(
            "GIVEN the generated classes WHEN inspected SHOULD implement JsonWritable unless they reach Any or a group")
    void given_generatedClasses_when_inspected_should_implementJsonWritableUnlessAnyOrGroup() {
        assertThat(Kinds.getDefaultInstance()).isInstanceOf(JsonWritable.class);
        assertThat(Kinds.Inner.getDefaultInstance()).isInstanceOf(JsonWritable.class);
        assertThat(Part.getDefaultInstance()).isInstanceOf(JsonWritable.class);
        assertThat(LegacyKinds.getDefaultInstance()).isInstanceOf(JsonWritable.class);
        assertThat(Trip.getDefaultInstance()).isInstanceOf(JsonWritable.class);
        assertThat(WithAny.getDefaultInstance()).isNotInstanceOf(JsonWritable.class);
        assertThat(ReachesAny.getDefaultInstance()).isNotInstanceOf(JsonWritable.class);
        assertThat(WithGroup.getDefaultInstance()).isNotInstanceOf(JsonWritable.class);
    }

    // ── Parity ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName(
            "GIVEN plain proto3 JSON WHEN a message prints through writeJson SHOULD give the value JsonFormat gives")
    void given_proto3Dialect_when_writeJson_should_matchJsonFormat() throws IOException {
        ProtoJson proto3 = ProtoJson.proto3();
        JsonFormat.Printer printer = JsonFormat.printer().alwaysPrintFieldsWithNoPresence();
        for (Message message : MESSAGES) {
            String label = message.getDescriptorForType().getFullName();
            Object expected = tree(printer.print(message));
            assertThat(tree(proto3.print(message))).as("print: %s", label).isEqualTo(expected);
            if (message instanceof Kinds || message instanceof Trip) {
                // The default mapping writes google.type.Date as text; proto3 JSON as an object. Only
                // messages without one compare under the default mapping too.
                continue;
            }
            assertThat(tree(direct(message))).as("writeJson: %s", label).isEqualTo(expected);
            assertThat(tree(written(proto3, message)))
                    .as("write to generator: %s", label)
                    .isEqualTo(expected);
        }
    }

    @Test
    @DisplayName(
            "GIVEN every dialect WHEN a message prints through writeJson and through its dynamic form SHOULD agree")
    void given_everyDialect_when_printedTypedAndDynamic_should_agree() throws IOException {
        for (ProtoJson json : dialects()) {
            for (Message message : MESSAGES) {
                String label = message.getDescriptorForType().getFullName() + " in " + json;
                Object expected = tree(json.print(ProtoTypes.dynamic(message, message.getDescriptorForType())));
                assertThat(tree(json.print(message))).as("print: %s", label).isEqualTo(expected);
                StringWriter out = new StringWriter();
                json.print(message, out);
                assertThat(tree(out.toString()))
                        .as("print to writer: %s", label)
                        .isEqualTo(expected);
                assertThat(tree(written(json, message)))
                        .as("write to generator: %s", label)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    @DisplayName("GIVEN the writer generated at run time WHEN both write the same message SHOULD give the same JSON")
    void given_runtimeWriter_when_bothWrite_should_agree() throws Exception {
        Assumptions.assumeTrue(Codegen.available(), "needs the serializer generator (Java 25+)");
        for (Message message : MESSAGES) {
            @SuppressWarnings("unchecked")
            Class<? extends Message> type = (Class<? extends Message>) message.getClass();
            TypedMessageWriter runtime =
                    Codegen.provider().typedWriter(TypedWriterPlan.of(type, message.getDescriptorForType()));
            StringWriter out = new StringWriter();
            try (JsonGenerator gen = JSON.createGenerator(out)) {
                runtime.write(message, gen);
            }
            assertThat(tree(direct(message)))
                    .as(message.getDescriptorForType().getFullName())
                    .isEqualTo(tree(out.toString()));
        }
    }

    @Test
    @DisplayName(
            "GIVEN a class that carries its writer WHEN TypedWriters compiles it SHOULD use that writer, generator or not")
    void given_jsonWritableClass_when_typedWritersCompile_should_useTheCarriedWriter() throws Exception {
        TypedMessageWriter writer = TypedWriters.compile(Kinds.getDefaultInstance(), new HashMap<>());
        StringWriter out = new StringWriter();
        try (JsonGenerator gen = JSON.createGenerator(out)) {
            writer.write(KINDS, gen);
        }
        assertThat(out.toString()).isEqualTo(direct(KINDS));
    }

    // ── Details the dynamic paths could mask ────────────────────────────────────────

    @Test
    @DisplayName("GIVEN unsigned and altered names WHEN written SHOULD use the unsigned values and protoc's JSON names")
    void given_unsignedAndAlteredNames_when_written_should_useUnsignedValuesAndJsonNames() throws IOException {
        String json = direct(KINDS);
        assertThat(json)
                .contains("\"aUint32\":4294967295")
                .contains("\"aUint64\":\"18446744073709551615\"")
                .contains("\"aFixed64\":\"18446744073709551600\"")
                .contains("\"partByUint32\":{\"4294967295\":")
                .contains("\"nameByUint64\":{\"18446744073709551615\":\"max\"}")
                .contains("\"intByInt\":{\"-1\":\"-9223372036854775808\"}")
                .contains("\"kind\":99")
                .contains("\"kinds\":[\"KIND_LARGE\",42]")
                .contains("\"kindByBool\":{")
                .contains("\"false\":77")
                .contains("\"class\":\"reserved\"")
                .contains("\"serializedSize\":\"altered\"")
                .contains("\"line2xDate\":\"digits\"")
                .contains("\"choicePart\":{")
                .doesNotContain("choiceText")
                .contains("\"maybe\":\"\"")
                .doesNotContain("maybeNumber");
    }

    @Test
    @DisplayName("GIVEN a proto2 message WHEN written SHOULD name closed enum values and omit unset optionals")
    void given_proto2Message_when_written_should_nameClosedEnumsAndOmitUnset() throws IOException {
        String json = direct(LegacyKinds.newBuilder().setId("x").build());
        assertThat(json).isEqualTo("{\"id\":\"x\",\"history\":[],\"statusByName\":{}}");
        assertThat(direct(LEGACY))
                .contains("\"status\":\"RETIRED\"")
                .contains("\"history\":[\"ACTIVE\",\"RETIRED\"]")
                .contains("\"statusByName\":{\"x\":\"ACTIVE\"}")
                .contains("\"big\":\"18446744073709551615\"")
                .contains("\"next\":{\"id\":\"legacy-2\"");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────

    private static List<ProtoJson> dialects() {
        return List.of(
                ProtoJson.defaults(),
                ProtoJson.builder().compiledSerializers(false).build(),
                ProtoJson.builder()
                        .scalar(ProtoScalarCodec.ofString(
                                Part.getDefaultInstance(),
                                p -> p.getName() + "#" + p.getWeight(),
                                text -> part(text.substring(0, text.indexOf('#')), 0)))
                        .int64AsNumber(true)
                        .dateAsText(false)
                        .build(),
                ProtoJson.builder()
                        .preserveProtoFieldNames(true)
                        .omitDefaultValues(true)
                        .enumsAsInts(true)
                        .build());
    }

    /** {@code ProtoJson.write}: the inserted method inside the dialect, for a default layout. */
    private static String written(ProtoJson json, Message message) throws IOException {
        StringWriter out = new StringWriter();
        try (JsonGenerator gen = JSON.createGenerator(out)) {
            json.write(message, gen);
        }
        return out.toString();
    }

    /** The inserted method itself, called as generated code would, under the default mapping. */
    private static String direct(Message message) throws IOException {
        StringWriter out = new StringWriter();
        try (JsonGenerator gen = JSON.createGenerator(out)) {
            ((JsonWritable) message).writeJson(gen);
        }
        return out.toString();
    }

    private static Object tree(String json) throws IOException {
        try (JsonParser parser = JSON.createParser(json)) {
            parser.nextToken();
            return JsonTrees.read(parser);
        }
    }
}
