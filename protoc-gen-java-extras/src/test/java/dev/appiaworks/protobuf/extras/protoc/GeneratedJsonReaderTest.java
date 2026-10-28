package dev.appiaworks.protobuf.extras.protoc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.google.protobuf.Message;
import com.google.protobuf.Timestamp;
import com.google.type.Date;
import dev.appiaworks.protobuf.extras.JsonReadable;
import dev.appiaworks.protobuf.extras.ProtoJson;
import dev.appiaworks.protobuf.extras.ProtoJsonException;
import dev.appiaworks.protobuf.extras.ProtoScalarCodec;
import dev.appiaworks.protobuf.extras.ProtoTypes;
import dev.appiaworks.protobuf.extras.protoc.fixture.proto.Kind;
import dev.appiaworks.protobuf.extras.protoc.fixture.proto.Kinds;
import dev.appiaworks.protobuf.extras.protoc.fixture.proto.LegacyKindsOuterClass.LegacyKinds;
import dev.appiaworks.protobuf.extras.protoc.fixture.proto.LegacyKindsOuterClass.WithGroup;
import dev.appiaworks.protobuf.extras.protoc.fixture.proto.Part;
import dev.appiaworks.protobuf.extras.protoc.fixture.proto.ReachesAny;
import dev.appiaworks.protobuf.extras.protoc.fixture.proto.Trip;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code mergeJson} the plugin inserts into the Builders, as the generated classes in this
 * module's build carry it: every document the dynamic path reads, and every one it refuses, read
 * the same way through the setters.
 */
class GeneratedJsonReaderTest {

    private static final JsonFactory JSON = new JsonFactory();

    /** Documents the strict rules accept in the default dialect, from plain to awkward. */
    private static final List<String> ACCEPTED = List.of(
            "{}",
            "{\"aDouble\":1.5,\"aFloat\":\"2.5\",\"anInt32\":\"-7\",\"anInt64\":9007199254740993,\"aUint32\":4294967295,"
                    + "\"aUint64\":\"18446744073709551615\",\"aBool\":\"true\",\"aString\":\"x\",\"someBytes\":\"AAEC_w==\"}",
            "{\"a_double\":1e2,\"an_int32\":1e2,\"a_sint64\":\"-1\",\"a_fixed64\":\"18446744073709551600\",\"some_bytes\":\"AAEC/w==\"}",
            "{\"kind\":\"KIND_LARGE\",\"kinds\":[\"KIND_SMALL\",2,\"NOT_A_KIND\",99],\"kindByBool\":{\"true\":1,\"false\":\"KIND_LARGE\"}}",
            "{\"part\":{\"name\":\"gear\",\"weight\":\"12\",\"kind\":\"KIND_SMALL\"},\"parts\":[{\"name\":\"a\"},{}],"
                    + "\"inner\":{\"level\":\"LEVEL_HIGH\",\"parts\":[{\"weight\":1}]}}",
            "{\"at\":\"2026-09-22T14:03:11.500Z\",\"on\":\"2026-09-22\",\"weekday\":\"FRIDAY\",\"points\":\"42\","
                    + "\"attributes\":{\"k\":\"v\",\"n\":[1,null,{\"m\":true}]}}",
            "{\"labels\":{\"a\":\"1\",\"\":\"\"},\"intByInt\":{\"-1\":\"-9223372036854775808\"},\"partByUint32\":{\"4294967295\":{\"name\":\"u\"}},"
                    + "\"nameByUint64\":{\"18446744073709551615\":\"max\"},\"atByInt64\":{\"5\":\"1970-01-01T00:00:01Z\"},"
                    + "\"bytesByName\":{\"b\":\"Ynl0ZXM=\"},\"doubleBySint32\":{\"-9\":0.5},\"floatByFixed64\":{\"1\":\"NaN\"}}",
            "{\"choiceText\":\"t\",\"choiceNumber\":3,\"maybe\":\"\",\"maybeNumber\":null,\"class\":\"c\",\"serializedSize\":\"s\",\"line2xDate\":\"d\"}",
            "{\"unknown\":{\"deep\":[1,2,{\"x\":null}]},\"alsoUnknown\":null,\"aString\":\"kept\"}",
            "{\"aDouble\":null,\"part\":null,\"parts\":[],\"labels\":{},\"int32s\":[1,\"2\",3e0]}",
            "{\"doubles\":[\"NaN\",\"Infinity\",\"-Infinity\",1e308],\"aFloat\":\"-Infinity\"}",
            "{\"strings\":[\"a\",\"\"],\"byteStrings\":[\"eA==\"],\"bools\":[true,\"false\"],\"uint64s\":[\"1\",2]}",
            "{\"on\":{\"year\":2026},\"days\":[{\"year\":2026,\"month\":1}]}");

    /** Documents the strict rules refuse, each with the field that trips them. */
    private static final List<String> REFUSED = List.of(
            "{\"anInt32\":1.5}",
            "{\"anInt32\":\"abc\"}",
            "{\"anInt32\":2147483648}",
            "{\"aUint32\":-1}",
            "{\"aUint64\":\"18446744073709551616\"}",
            "{\"aFloat\":1e39}",
            "{\"aBool\":1}",
            "{\"aString\":1}",
            "{\"aString\":{}}",
            "{\"someBytes\":\"not base64!\"}",
            "{\"kind\":true}",
            "{\"kind\":{}}",
            "{\"part\":\"gear\"}",
            "{\"part\":[]}",
            "{\"parts\":{}}",
            "{\"parts\":[null]}",
            "{\"parts\":[\"x\"]}",
            "{\"int32s\":[1,null]}",
            "{\"int32s\":5}",
            "{\"labels\":[]}",
            "{\"labels\":{\"a\":null}}",
            "{\"labels\":{\"a\":1}}",
            "{\"intByInt\":{\"x\":1}}",
            "{\"partByUint32\":{\"-1\":{}}}",
            "{\"partByUint32\":{\"1\":null}}",
            "{\"partByUint32\":{\"1\":\"p\"}}",
            "{\"kindByBool\":{\"maybe\":1}}",
            "{\"at\":\"yesterday\"}",
            "{\"on\":\"2026-02-30\"}",
            "{\"points\":\"x\"}",
            "{\"inner\":{\"parts\":[{\"weight\":\"heavy\"}]}}",
            "[]",
            "\"text\"",
            "42",
            "{\"aString\":\"a\"} trailing",
            "{\"aString\":",
            "");

    // ── Who carries a reader ─────────────────────────────────────────────────────────

    @Test
    @DisplayName(
            "GIVEN the generated Builders WHEN inspected SHOULD implement JsonReadable unless they reach Any or a group")
    void given_generatedBuilders_when_inspected_should_implementJsonReadableUnlessAnyOrGroup() {
        assertThat(Kinds.newBuilder()).isInstanceOf(JsonReadable.class);
        assertThat(Kinds.Inner.newBuilder()).isInstanceOf(JsonReadable.class);
        assertThat(Part.newBuilder()).isInstanceOf(JsonReadable.class);
        assertThat(LegacyKinds.newBuilder()).isInstanceOf(JsonReadable.class);
        assertThat(Trip.newBuilder()).isInstanceOf(JsonReadable.class);
        assertThat(ReachesAny.newBuilder()).isNotInstanceOf(JsonReadable.class);
        assertThat(WithGroup.newBuilder()).isNotInstanceOf(JsonReadable.class);
    }

    // ── Parity with the dynamic path ─────────────────────────────────────────────────

    @Test
    @DisplayName("GIVEN the accepted documents WHEN parsed in the default dialect SHOULD be accepted by both paths")
    void given_acceptedDocuments_when_parsedInDefaultDialect_should_beAccepted() {
        for (String document : ACCEPTED) {
            assertThat(catchParse(() -> ProtoJson.defaults().parse(document, Kinds.getDescriptor())))
                    .as("the dynamic path accepts %s", document)
                    .isNull();
            assertThat(catchParse(() -> ProtoJson.defaults().parse(document, Kinds.getDefaultInstance())))
                    .as("the Builder accepts %s", document)
                    .isNull();
        }
    }

    @Test
    @DisplayName("GIVEN the refused documents WHEN parsed in the default dialect SHOULD be refused by both paths")
    void given_refusedDocuments_when_parsedInDefaultDialect_should_beRefused() {
        for (String document : REFUSED) {
            assertThat(catchParse(() -> ProtoJson.defaults().parse(document, Kinds.getDescriptor())))
                    .as("the dynamic path refuses %s", document)
                    .isNotNull();
            assertThat(catchParse(() -> ProtoJson.defaults().parse(document, Kinds.getDefaultInstance())))
                    .as("the Builder refuses %s", document)
                    .isNotNull();
        }
    }

    @Test
    @DisplayName(
            "GIVEN every document and dialect WHEN parsed through the Builder and through the dynamic path SHOULD build the same message or refuse with the same words")
    void given_everyDocumentAndDialect_when_parsedTypedAndDynamic_should_agree() {
        List<String> corpus = new ArrayList<>(ACCEPTED);
        corpus.addAll(REFUSED);
        for (ProtoJson json : dialects()) {
            for (String document : corpus) {
                String label = document + " in " + json;
                Kinds[] dynamic = new Kinds[1];
                ProtoJsonException refusedDynamic = catchParse(() -> dynamic[0] =
                        ProtoTypes.as(json.parse(document, Kinds.getDescriptor()), Kinds.getDefaultInstance()));
                Kinds[] typed = new Kinds[1];
                ProtoJsonException refusedTyped =
                        catchParse(() -> typed[0] = json.parse(document, Kinds.getDefaultInstance()));
                if (refusedDynamic != null) {
                    assertThat(refusedTyped).as("the Builder refuses %s", label).isNotNull();
                    assertThat(refusedTyped.getMessage()).as(label).isEqualTo(refusedDynamic.getMessage());
                } else {
                    assertThat(refusedTyped).as("the Builder accepts %s", label).isNull();
                    assertThat(typed[0]).as(label).isEqualTo(dynamic[0]);
                }
            }
        }
    }

    @Test
    @DisplayName("GIVEN a message printed in each dialect WHEN parsed back through the Builder SHOULD be the message")
    void given_printedMessage_when_parsedBack_should_roundTrip() {
        List<Message> messages = List.of(
                GeneratedJsonWriterTest.kinds(),
                Kinds.getDefaultInstance(),
                GeneratedJsonWriterTest.legacy(),
                Trip.newBuilder()
                        .setDepartsOn(
                                Date.newBuilder().setYear(2026).setMonth(9).setDay(22))
                        .setClass_(Timestamp.newBuilder().setSeconds(7))
                        .putCheckpoints(
                                "start", Timestamp.newBuilder().setSeconds(1).build())
                        .addStops(Trip.Stop.newBuilder()
                                .setPlatform(Trip.Stop.Platform.newBuilder()
                                        .setClearedAt(Timestamp.newBuilder().setSeconds(2))))
                        .build());
        for (ProtoJson json : dialects()) {
            for (Message message : messages) {
                Message back = json.parse(json.print(message), message.getDefaultInstanceForType());
                // An unknown enum number is dropped on the way in, as every path drops it.
                Message expected = message instanceof Kinds k ? knownEnumsOnly(k) : message;
                assertThat(back)
                        .as("%s in %s", message.getDescriptorForType().getFullName(), json)
                        .isEqualTo(expected);
            }
        }
    }

    // ── The method itself ────────────────────────────────────────────────────────────

    @Test
    @DisplayName(
            "GIVEN a parser on an object WHEN mergeJson runs SHOULD fill the builder, leave the parser on END_OBJECT and return the builder")
    void given_parserOnObject_when_mergeJson_should_fillAndStopAtEndObject() throws IOException {
        String text = "{\"aString\":\"x\",\"part\":{\"name\":\"p\"},\"int32s\":[1,2]} 7";
        try (JsonParser p = JSON.createParser(text)) {
            p.nextToken();
            Kinds.Builder builder = Kinds.newBuilder();
            Message.Builder returned = builder.mergeJson(p);

            assertThat(returned).isSameAs(builder);
            assertThat(p.currentToken()).isEqualTo(JsonToken.END_OBJECT);
            assertThat(p.nextToken()).isEqualTo(JsonToken.VALUE_NUMBER_INT);
            Kinds kinds = builder.build();
            assertThat(kinds.getAString()).isEqualTo("x");
            assertThat(kinds.getPart().getName()).isEqualTo("p");
            assertThat(kinds.getInt32SList()).containsExactly(1, 2);
        }
    }

    @Test
    @DisplayName("GIVEN a parser on a value that is not an object WHEN mergeJson runs SHOULD refuse it naming the type")
    void given_parserNotOnObject_when_mergeJson_should_refuse() throws IOException {
        try (JsonParser p = JSON.createParser("[1]")) {
            p.nextToken();
            assertThatThrownBy(() -> Kinds.newBuilder().mergeJson(p))
                    .isInstanceOf(ProtoJsonException.class)
                    .hasMessageContaining("accessors.Kinds")
                    .hasMessageContaining("expected a JSON object");
        }
    }

    @Test
    @DisplayName(
            "GIVEN a proto2 message WHEN parsed SHOULD read closed enums by name and refuse a missing required field")
    void given_proto2Message_when_parsed_should_readClosedEnumsAndRequireRequired() {
        LegacyKinds legacy = ProtoJson.defaults()
                .parse(
                        "{\"id\":\"x\",\"status\":\"RETIRED\",\"history\":[\"ACTIVE\",2],\"statusByName\":{\"a\":\"ACTIVE\"},\"big\":\"18446744073709551615\"}",
                        LegacyKinds.getDefaultInstance());
        assertThat(legacy.getStatus()).isEqualTo(LegacyKinds.Status.RETIRED);
        assertThat(legacy.getHistoryList()).containsExactly(LegacyKinds.Status.ACTIVE, LegacyKinds.Status.RETIRED);
        assertThat(legacy.getStatusByNameMap()).containsEntry("a", LegacyKinds.Status.ACTIVE);
        assertThat(legacy.getBig()).isEqualTo(-1L);

        assertThatThrownBy(() -> ProtoJson.defaults().parse("{\"count\":1}", LegacyKinds.getDefaultInstance()))
                .isInstanceOf(ProtoJsonException.class)
                .hasMessageContaining("id");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────

    /** The default dialect, the same without run-time generation, plain proto3, and one with a codec and other switches. */
    private static List<ProtoJson> dialects() {
        return List.of(
                ProtoJson.defaults(),
                ProtoJson.builder().compiledSerializers(false).build(),
                ProtoJson.proto3(),
                ProtoJson.builder()
                        .scalar(ProtoScalarCodec.ofString(
                                Part.getDefaultInstance(),
                                p -> p.getName() + "#" + p.getWeight() + "#" + p.getKindValue(),
                                text -> {
                                    String[] parts = text.split("#", -1);
                                    if (parts.length != 3)
                                        throw new IllegalArgumentException("expected name#weight#kind");
                                    return Part.newBuilder()
                                            .setName(parts[0])
                                            .setWeight(Long.parseLong(parts[1]))
                                            .setKindValue(Integer.parseInt(parts[2]))
                                            .build();
                                }))
                        .int64AsNumber(true)
                        .dateAsText(false)
                        .build());
    }

    private static ProtoJsonException catchParse(Runnable parse) {
        try {
            parse.run();
            return null;
        } catch (ProtoJsonException e) {
            return e;
        }
    }

    /** The message with its unknown enum numbers cleared, which no parse path keeps. */
    private static Kinds knownEnumsOnly(Kinds kinds) {
        Kinds.Builder b = kinds.toBuilder();
        if (kinds.getKind() == Kind.UNRECOGNIZED) b.clearKind();
        b.clearKinds();
        for (int i = 0; i < kinds.getKindsCount(); i++) {
            if (kinds.getKinds(i) != Kind.UNRECOGNIZED) b.addKinds(kinds.getKinds(i));
        }
        b.clearKindByBool();
        kinds.getKindByBoolMap().forEach((k, v) -> {
            if (v != Kind.UNRECOGNIZED) b.putKindByBool(k, v);
        });
        return b.build();
    }
}
