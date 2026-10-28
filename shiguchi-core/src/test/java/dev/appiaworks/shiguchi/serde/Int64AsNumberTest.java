package dev.appiaworks.shiguchi.serde;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Int64Value;
import com.google.protobuf.UInt64Value;
import dev.appiaworks.protobuf.extras.serde.AbstractDynamicSerializer;
import dev.appiaworks.protobuf.extras.serde.Codegen;
import dev.appiaworks.protobuf.extras.serde.ProtoJsonMapping;
import dev.appiaworks.protobuf.extras.serde.ProtoJsonMapping.DurationFormat;
import dev.appiaworks.protobuf.extras.serde.ProtoMapConverter;
import dev.appiaworks.protobuf.extras.serde.WireJsonTranscoder;
import java.io.StringWriter;
import java.math.BigInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code shiguchi.json.int64-as: NUMBER}: every tier writes 64-bit integers as JSON numbers,
 * unsigned values above {@code Long.MAX_VALUE} included, and STRING (the default) keeps the
 * proto3 string form.
 */
@DisplayName("int64-as NUMBER")
class Int64AsNumberTest {

    @BeforeAll
    static void requiresCodegen() {
        Assumptions.assumeTrue(
                Codegen.available(),
                "needs the serializer code generator: Java 25+ with protobuf-java-extras-classfile");
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonFactory JSON = new JsonFactory();
    private static final Descriptor COUNTERS = buildCounters();

    @AfterEach
    void restoreDefaults() {
        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS);
    }

    private static void numbers() {
        ProtoJsonMapping.install(
                new ProtoJsonMapping(true, true, true, true, false, DurationFormat.PROTO, true, false, true));
    }

    private static DynamicMessage counters() {
        Descriptor d = COUNTERS;
        return DynamicMessage.newBuilder(d)
                .setField(d.findFieldByName("total"), 9_007_199_254_740_993L)
                .setField(d.findFieldByName("big"), -1L) // uint64 max: 18446744073709551615
                .setField(d.findFieldByName("delta"), -42L)
                .addRepeatedField(d.findFieldByName("history"), 1L)
                .addRepeatedField(d.findFieldByName("history"), 2L)
                .setField(
                        d.findFieldByName("maybe"),
                        DynamicMessage.newBuilder(Int64Value.of(7)).build())
                .setField(
                        d.findFieldByName("umaybe"),
                        DynamicMessage.newBuilder(UInt64Value.of(-1L)).build())
                .build();
    }

    private static JsonNode tier2(DynamicMessage msg) throws Exception {
        AbstractDynamicSerializer serializer = new DynamicSerializerFactory().compileDescriptor(COUNTERS);
        StringWriter out = new StringWriter();
        try (JsonGenerator gen = JSON.createGenerator(out)) {
            serializer.serialize(msg, gen);
        }
        return MAPPER.readTree(out.toString());
    }

    private static JsonNode tier0(DynamicMessage msg) throws Exception {
        WireJsonTranscoder transcoder = new WireJsonTranscoder();
        return MAPPER.readTree(transcoder.transcode(transcoder.programFor(COUNTERS), msg.toByteArray()));
    }

    private static JsonNode reflective(DynamicMessage msg) throws Exception {
        StringWriter out = new StringWriter();
        try (JsonGenerator gen = JSON.createGenerator(out)) {
            ProtoMapConverter.writeMessageJson(gen, msg);
        }
        return MAPPER.readTree(out.toString());
    }

    @Test
    @DisplayName(
            "GIVEN int64-as NUMBER WHEN a message with 64-bit fields is written by Tier 0, Tier 2 and the reflective converter SHOULD emit identical JSON numbers")
    void given_numbers_when_writtenByEveryTier_should_emitIdenticalNumbers() throws Exception {
        numbers();
        DynamicMessage msg = counters();

        JsonNode json = tier2(msg);

        assertThat(tier0(msg)).isEqualTo(json);
        assertThat(reflective(msg)).isEqualTo(json);
        assertThat(json.get("total").isNumber()).isTrue();
        assertThat(json.get("total").longValue()).isEqualTo(9_007_199_254_740_993L);
        assertThat(json.get("big").bigIntegerValue()).isEqualTo(new BigInteger("18446744073709551615"));
        assertThat(json.get("delta").longValue()).isEqualTo(-42L);
        assertThat(json.get("history").get(1).isNumber()).isTrue();
        assertThat(json.get("maybe").isNumber()).isTrue();
        assertThat(json.get("umaybe").bigIntegerValue()).isEqualTo(new BigInteger("18446744073709551615"));
    }

    @Test
    @DisplayName(
            "GIVEN int64-as NUMBER WHEN a 64-bit field is absent from the wire SHOULD write its default as the number 0")
    void given_numbers_when_fieldAbsent_should_writeZeroNumber() throws Exception {
        numbers();
        DynamicMessage empty = DynamicMessage.newBuilder(COUNTERS).build();

        JsonNode json = tier0(empty);

        assertThat(json.get("total").isNumber()).isTrue();
        assertThat(json.get("total").intValue()).isZero();
        assertThat(tier2(empty)).isEqualTo(json);
    }

    @Test
    @DisplayName(
            "GIVEN the default STRING form WHEN a message with 64-bit fields is written SHOULD keep proto3 strings")
    void given_defaults_when_written_should_keepStrings() throws Exception {
        JsonNode json = tier2(counters());

        assertThat(json.get("total").isTextual()).isTrue();
        assertThat(json.get("total").asText()).isEqualTo("9007199254740993");
        assertThat(json.get("big").asText()).isEqualTo("18446744073709551615");
        assertThat(json.get("maybe").isTextual()).isTrue();
        assertThat(tier0(counters())).isEqualTo(json);
    }

    @Test
    @DisplayName(
            "GIVEN int64-as NUMBER WHEN asked whether proto3-only serializers can serve a message with int64 fields SHOULD say no, and yes under STRING")
    void given_numbers_when_checkingGatewaySerde_should_requireItOnlyWhenOn() {
        assertThat(ProtoJsonMapping.needsGatewaySerde(COUNTERS)).isFalse();

        numbers();

        assertThat(ProtoJsonMapping.needsGatewaySerde(COUNTERS)).isTrue();
    }

    private static Descriptor buildCounters() {
        FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                .setName("t/counters.proto")
                .setPackage("t")
                .setSyntax("proto3")
                .addDependency("google/protobuf/wrappers.proto")
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Counters")
                        .addField(field("total", 1, Type.TYPE_INT64))
                        .addField(field("big", 2, Type.TYPE_UINT64))
                        .addField(field("delta", 3, Type.TYPE_SINT64))
                        .addField(field("history", 4, Type.TYPE_FIXED64).setLabel(Label.LABEL_REPEATED))
                        .addField(field("maybe", 5, Type.TYPE_MESSAGE).setTypeName(".google.protobuf.Int64Value"))
                        .addField(field("umaybe", 6, Type.TYPE_MESSAGE).setTypeName(".google.protobuf.UInt64Value")))
                .build();
        try {
            return FileDescriptor.buildFrom(
                            proto, new FileDescriptor[] {com.google.protobuf.WrappersProto.getDescriptor()})
                    .findMessageTypeByName("Counters");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static FieldDescriptorProto.Builder field(String name, int number, Type type) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(type)
                .setLabel(Label.LABEL_OPTIONAL);
    }
}
