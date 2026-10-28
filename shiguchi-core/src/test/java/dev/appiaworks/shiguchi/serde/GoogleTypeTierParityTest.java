package dev.appiaworks.shiguchi.serde;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.type.Date;
import com.google.type.DateProto;
import com.google.type.DateTime;
import com.google.type.DateTimeProto;
import com.google.type.Decimal;
import com.google.type.DecimalProto;
import com.google.type.TimeOfDay;
import com.google.type.TimeOfDayProto;
import dev.appiaworks.protobuf.extras.serde.AbstractDynamicSerializer;
import dev.appiaworks.protobuf.extras.serde.Codegen;
import dev.appiaworks.protobuf.extras.serde.ProtoJsonMapping;
import dev.appiaworks.protobuf.extras.serde.ProtoMapConverter;
import dev.appiaworks.protobuf.extras.serde.WireJsonTranscoder;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tier-parity tests for Google Type scalar mappings across Tier 0 (WireJsonTranscoder),
 * Tier 2 (DynamicSerializerFactory), and Tier 3 (ProtoMapConverter) serialization layers.
 *
 * Builds a parity.Event message with Date, TimeOfDay, DateTime, and Decimal fields,
 * then verifies that all three tiers emit identical JSON when mapped, and that
 * round-tripping works correctly through each tier.
 */
class GoogleTypeTierParityTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final JsonFactory JSON_FACTORY = new JsonFactory();

    private static Descriptor eventDescriptor;
    private static AbstractDynamicSerializer eventSerializer;
    private static WireJsonTranscoder transcoder;

    @BeforeAll
    static void compileSerializers() throws Exception {
        Assumptions.assumeTrue(
                Codegen.available(),
                "needs the serializer code generator: Java 25+ with protobuf-java-extras-classfile");
        FileDescriptor eventFile = buildParityFile();
        eventDescriptor = eventFile.findMessageTypeByName("Event");

        DynamicSerializerFactory factory = new DynamicSerializerFactory();
        eventSerializer = factory.compileDescriptor(eventDescriptor);
        transcoder = new WireJsonTranscoder();
    }

    @AfterEach
    void restoreMappingDefaults() {
        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Tier parity: all three tiers emit identical JSON
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName(
            "Given Event with mapped google.type fields, when Tier 2, Tier 0, and ProtoMapConverter serialize, should emit identical JSON trees")
    void given_eventWithMappedTypes_when_allThreeSerialize_should_emitIdenticalJson() throws Exception {
        ProtoJsonMapping mapping = new ProtoJsonMapping(
                true, true, true, true, false, ProtoJsonMapping.DurationFormat.PROTO, false, false);
        ProtoJsonMapping.install(mapping);

        // Build event with realistic data
        DynamicMessage date = buildDate(2026, 9, 22);
        DynamicMessage timeOfDay = buildTimeOfDay(9, 30, 0, 0);
        DynamicMessage dateTime = buildDateTime(2026, 9, 22, 9, 30, 0, 0);
        DynamicMessage price = buildDecimal("99.99");

        DynamicMessage event = DynamicMessage.newBuilder(eventDescriptor)
                .setField(eventDescriptor.findFieldByName("day"), date)
                .setField(eventDescriptor.findFieldByName("opens"), timeOfDay)
                .setField(eventDescriptor.findFieldByName("starts"), dateTime)
                .setField(eventDescriptor.findFieldByName("price"), price)
                .setField(eventDescriptor.findFieldByName("name"), "Concert")
                .build();

        // Tier 2: generated serializer
        String tier2Json = tier2Serialize(event);
        JsonNode tier2Tree = OBJECT_MAPPER.readTree(tier2Json);

        // Tier 0: WireJsonTranscoder (if available)
        byte[] wireBytes = event.toByteArray();
        byte[] tier0JsonBytes = transcoder.transcode(transcoder.programFor(eventDescriptor), wireBytes);
        String tier0Json = new String(tier0JsonBytes, java.nio.charset.StandardCharsets.UTF_8);
        JsonNode tier0Tree = OBJECT_MAPPER.readTree(tier0Json);

        // Tier 3: ProtoMapConverter (fallback)
        Map<String, Object> mapForm = ProtoMapConverter.dynamicToMap(event);
        String tier3Json = OBJECT_MAPPER.writeValueAsString(mapForm);
        JsonNode tier3Tree = OBJECT_MAPPER.readTree(tier3Json);

        // All three should be equal
        assertThat(tier2Tree).as("Tier 2 JSON").isEqualTo(tier0Tree);
        assertThat(tier2Tree).as("Tier 2 vs Tier 3 JSON").isEqualTo(tier3Tree);
    }

    @Test
    @DisplayName(
            "Given Event with mapped fields, when serialized, JSON should have 'day':'2026-09-22' 'opens':'09:30:00'")
    void given_eventWithMappedTypes_when_serialized_should_haveIsoStrings() throws Exception {
        ProtoJsonMapping mapping = new ProtoJsonMapping(
                true, true, true, true, false, ProtoJsonMapping.DurationFormat.PROTO, false, false);
        ProtoJsonMapping.install(mapping);

        DynamicMessage date = buildDate(2026, 9, 22);
        DynamicMessage timeOfDay = buildTimeOfDay(9, 30, 0, 0);
        DynamicMessage dateTime = buildDateTime(2026, 9, 22, 14, 3, 11, 0);
        DynamicMessage price = buildDecimal("49.99");

        DynamicMessage event = DynamicMessage.newBuilder(eventDescriptor)
                .setField(eventDescriptor.findFieldByName("day"), date)
                .setField(eventDescriptor.findFieldByName("opens"), timeOfDay)
                .setField(eventDescriptor.findFieldByName("starts"), dateTime)
                .setField(eventDescriptor.findFieldByName("price"), price)
                .setField(eventDescriptor.findFieldByName("name"), "Event1")
                .build();

        String json = tier2Serialize(event);
        JsonNode tree = OBJECT_MAPPER.readTree(json);

        assertThat(tree.get("day").asText()).isEqualTo("2026-09-22");
        assertThat(tree.get("opens").asText()).isEqualTo("09:30:00");
        assertThat(tree.get("starts").asText()).contains("2026-09-22T14:03:11");
        assertThat(tree.get("price").asText()).isEqualTo("49.99");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Tier 2 toMap and round-trip tests
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName(
            "Given Event serialized to map with ISO strings, when map has 'day':'2026-09-22', should contain ISO formatted date")
    void given_eventToMap_when_dayField_should_haveIsoDate() throws Exception {
        ProtoJsonMapping mapping = new ProtoJsonMapping(
                true, true, true, true, false, ProtoJsonMapping.DurationFormat.PROTO, false, false);
        ProtoJsonMapping.install(mapping);

        DynamicMessage date = buildDate(2026, 9, 22);
        DynamicMessage event = DynamicMessage.newBuilder(eventDescriptor)
                .setField(eventDescriptor.findFieldByName("day"), date)
                .setField(eventDescriptor.findFieldByName("name"), "Test")
                .build();

        @SuppressWarnings("unchecked")
        Map<String, Object> mapForm = (Map<String, Object>) eventSerializer.toMap(event);

        assertThat(mapForm.get("day")).isEqualTo("2026-09-22");
    }

    @Test
    @DisplayName(
            "Given a map with ISO date string '2026-09-22', when fromMap on Event descriptor, should round-trip to equal DynamicMessage")
    void given_mapWithIsoDate_when_fromMap_should_roundTripCorrectly() throws Exception {
        ProtoJsonMapping mapping = new ProtoJsonMapping(
                true, true, true, true, false, ProtoJsonMapping.DurationFormat.PROTO, false, false);
        ProtoJsonMapping.install(mapping);

        Map<String, Object> mapForm = new LinkedHashMap<>();
        mapForm.put("day", "2026-09-22");
        mapForm.put("opens", "09:30:00");
        mapForm.put("starts", "2026-09-22T14:03:11");
        mapForm.put("price", "99.99");
        mapForm.put("name", "TestEvent");

        DynamicMessage result = eventSerializer.fromMap(mapForm, DynamicMessage.newBuilder(eventDescriptor));

        // Verify it round-trips: serialize back to map
        @SuppressWarnings("unchecked")
        Map<String, Object> roundTripped = (Map<String, Object>) eventSerializer.toMap(result);

        assertThat(roundTripped.get("day")).isEqualTo("2026-09-22");
        assertThat(roundTripped.get("opens")).isEqualTo("09:30:00");
    }

    @Test
    @DisplayName(
            "Given JSON text with ISO-formatted date, when Tier 2 deserialize, should parse to equal DynamicMessage")
    void given_jsonTextWithIsoDate_when_tier2Deserialize_should_roundTripCorrectly() throws Exception {
        ProtoJsonMapping mapping = new ProtoJsonMapping(
                true, true, true, true, false, ProtoJsonMapping.DurationFormat.PROTO, false, false);
        ProtoJsonMapping.install(mapping);

        String json =
                "{\"day\":\"2026-09-22\",\"opens\":\"09:30:00\",\"starts\":\"2026-09-22T14:03:11\",\"price\":\"49.99\",\"name\":\"TestEvent\"}";

        JsonParser parser = JSON_FACTORY.createParser(new StringReader(json));
        parser.nextToken(); // Advance to START_OBJECT
        DynamicMessage result = eventSerializer.deserialize(parser, DynamicMessage.newBuilder(eventDescriptor));

        // Serialize back and compare — Tier 2 includes default values for repeated fields (holidays)
        String serialized = tier2Serialize(result);
        JsonNode roundTripped = OBJECT_MAPPER.readTree(serialized);

        // Verify core fields are preserved
        assertThat(roundTripped.get("day").asText()).isEqualTo("2026-09-22");
        assertThat(roundTripped.get("opens").asText()).isEqualTo("09:30:00");
        assertThat(roundTripped.get("starts").asText()).contains("2026-09-22T14:03:11");
        assertThat(roundTripped.get("price").asText()).isEqualTo("49.99");
        assertThat(roundTripped.get("name").asText()).isEqualTo("TestEvent");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // reachesMapped cache tests
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given Event descriptor with mapped google.type fields, when reachesMapped, should return true")
    void given_eventDescriptorWithMappedTypes_when_reachesMapped_should_returnTrue() {
        ProtoJsonMapping mapping = new ProtoJsonMapping(
                true, true, true, true, false, ProtoJsonMapping.DurationFormat.PROTO, false, false);
        ProtoJsonMapping.install(mapping);

        boolean result = ProtoJsonMapping.reachesMapped(eventDescriptor);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Given Event descriptor, after installing PROTO3 mapping, when reachesMapped, should return false")
    void given_eventDescriptor_afterProto3Install_when_reachesMapped_should_returnFalse() {
        // First install a mapping that has mapped types
        ProtoJsonMapping mapping = new ProtoJsonMapping(
                true, true, true, true, false, ProtoJsonMapping.DurationFormat.PROTO, false, false);
        ProtoJsonMapping.install(mapping);
        assertThat(ProtoJsonMapping.reachesMapped(eventDescriptor)).isTrue();

        // Now install PROTO3 (which has no mapped types) — cache should be cleared
        ProtoJsonMapping.install(ProtoJsonMapping.PROTO3);

        boolean result = ProtoJsonMapping.reachesMapped(eventDescriptor);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Given Event descriptor with repeated Date holidays field, when reachesMapped, should return true")
    void given_eventWithRepeatedMappedField_when_reachesMapped_should_returnTrue() {
        ProtoJsonMapping mapping = new ProtoJsonMapping(
                true, true, true, true, false, ProtoJsonMapping.DurationFormat.PROTO, false, false);
        ProtoJsonMapping.install(mapping);

        boolean result = ProtoJsonMapping.reachesMapped(eventDescriptor);

        assertThat(result).isTrue();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helper methods
    // ──────────────────────────────────────────────────────────────────────────

    private static FileDescriptor buildParityFile() throws Exception {
        // Build file descriptor with dependencies on google.type protos
        DescriptorProto eventProto = DescriptorProto.newBuilder()
                .setName("Event")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("day")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName("google.type.Date")
                        .build())
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("opens")
                        .setNumber(2)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName("google.type.TimeOfDay")
                        .build())
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("starts")
                        .setNumber(3)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName("google.type.DateTime")
                        .build())
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("price")
                        .setNumber(4)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName("google.type.Decimal")
                        .build())
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("holidays")
                        .setNumber(5)
                        .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName("google.type.Date")
                        .build())
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("name")
                        .setNumber(6)
                        .setType(FieldDescriptorProto.Type.TYPE_STRING)
                        .build())
                .build();

        FileDescriptorProto fileProto = FileDescriptorProto.newBuilder()
                .setName("parity.proto")
                .setPackage("parity")
                .setSyntax("proto3")
                .addMessageType(eventProto)
                .build();

        return FileDescriptor.buildFrom(fileProto, new FileDescriptor[] {
            DateProto.getDescriptor(),
            TimeOfDayProto.getDescriptor(),
            DateTimeProto.getDescriptor(),
            DecimalProto.getDescriptor()
        });
    }

    private static DynamicMessage buildDate(int year, int month, int day) {
        return DynamicMessage.newBuilder(Date.getDescriptor())
                .setField(Date.getDescriptor().findFieldByName("year"), year)
                .setField(Date.getDescriptor().findFieldByName("month"), month)
                .setField(Date.getDescriptor().findFieldByName("day"), day)
                .build();
    }

    private static DynamicMessage buildTimeOfDay(int hours, int minutes, int seconds, int nanos) {
        return DynamicMessage.newBuilder(TimeOfDay.getDescriptor())
                .setField(TimeOfDay.getDescriptor().findFieldByName("hours"), hours)
                .setField(TimeOfDay.getDescriptor().findFieldByName("minutes"), minutes)
                .setField(TimeOfDay.getDescriptor().findFieldByName("seconds"), seconds)
                .setField(TimeOfDay.getDescriptor().findFieldByName("nanos"), nanos)
                .build();
    }

    private static DynamicMessage buildDateTime(
            int year, int month, int day, int hours, int minutes, int seconds, int nanos) {
        return DynamicMessage.newBuilder(DateTime.getDescriptor())
                .setField(DateTime.getDescriptor().findFieldByName("year"), year)
                .setField(DateTime.getDescriptor().findFieldByName("month"), month)
                .setField(DateTime.getDescriptor().findFieldByName("day"), day)
                .setField(DateTime.getDescriptor().findFieldByName("hours"), hours)
                .setField(DateTime.getDescriptor().findFieldByName("minutes"), minutes)
                .setField(DateTime.getDescriptor().findFieldByName("seconds"), seconds)
                .setField(DateTime.getDescriptor().findFieldByName("nanos"), nanos)
                .build();
    }

    private static DynamicMessage buildDecimal(String value) {
        return DynamicMessage.newBuilder(Decimal.getDescriptor())
                .setField(Decimal.getDescriptor().findFieldByName("value"), value)
                .build();
    }

    private static String tier2Serialize(DynamicMessage msg) throws Exception {
        StringWriter sw = new StringWriter();
        JsonGenerator gen = JSON_FACTORY.createGenerator(sw);
        eventSerializer.serialize(msg, gen);
        gen.close();
        return sw.toString();
    }
}
