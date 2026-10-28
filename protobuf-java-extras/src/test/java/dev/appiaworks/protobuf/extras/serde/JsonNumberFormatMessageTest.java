package dev.appiaworks.protobuf.extras.serde;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MessageOptions;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DoubleValue;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.FloatValue;
import com.google.protobuf.ListValue;
import com.google.protobuf.Struct;
import com.google.protobuf.StructProto;
import com.google.protobuf.Value;
import com.google.protobuf.WrappersProto;
import com.google.protobuf.util.JsonFormat;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The ECMAScript number layout through the option-aware writers: the wire transcoder and the
 * reflective writer must print the fixture {@code serde/ecmascript-message.json} byte for byte, and
 * the JAVA layout must stay what {@code JsonFormat} prints.
 */
class JsonNumberFormatMessageTest {

    private static final JsonFactory JSON = new JsonFactory();
    private static final JsonPrintOptions ECMASCRIPT =
            new JsonPrintOptions(false, false, false, JsonNumberFormat.ECMASCRIPT);

    private static Descriptor descriptor;
    private static DynamicMessage message;
    private static String expectedEcmascript;

    @BeforeAll
    static void build() throws Exception {
        DescriptorProto entry = DescriptorProto.newBuilder()
                .setName("MEntry")
                .setOptions(MessageOptions.newBuilder().setMapEntry(true))
                .addField(field("key", 1, FieldDescriptorProto.Type.TYPE_STRING, null, false))
                .addField(field("value", 2, FieldDescriptorProto.Type.TYPE_DOUBLE, null, false))
                .build();
        FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                .setName("numfmt.proto")
                .setPackage("numfmt")
                .setSyntax("proto3")
                .addDependency("google/protobuf/struct.proto")
                .addDependency("google/protobuf/wrappers.proto")
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Msg")
                        .addNestedType(entry)
                        .addField(field("d", 1, FieldDescriptorProto.Type.TYPE_DOUBLE, null, false))
                        .addField(field("f", 2, FieldDescriptorProto.Type.TYPE_FLOAT, null, false))
                        .addField(field("rd", 3, FieldDescriptorProto.Type.TYPE_DOUBLE, null, true))
                        .addField(field("m", 4, FieldDescriptorProto.Type.TYPE_MESSAGE, ".numfmt.Msg.MEntry", true))
                        .addField(field(
                                "dv", 5, FieldDescriptorProto.Type.TYPE_MESSAGE, ".google.protobuf.DoubleValue", false))
                        .addField(field(
                                "fv", 6, FieldDescriptorProto.Type.TYPE_MESSAGE, ".google.protobuf.FloatValue", false))
                        .addField(
                                field("v", 7, FieldDescriptorProto.Type.TYPE_MESSAGE, ".google.protobuf.Value", false))
                        .addField(
                                field("s", 8, FieldDescriptorProto.Type.TYPE_MESSAGE, ".google.protobuf.Struct", false))
                        .addField(field("zero", 9, FieldDescriptorProto.Type.TYPE_DOUBLE, null, false))
                        .addField(field("zf", 10, FieldDescriptorProto.Type.TYPE_FLOAT, null, false)))
                .build();
        FileDescriptor file = FileDescriptor.buildFrom(
                proto, new FileDescriptor[] {StructProto.getDescriptor(), WrappersProto.getDescriptor()});
        descriptor = file.findMessageTypeByName("Msg");

        DynamicMessage.Builder b = DynamicMessage.newBuilder(descriptor);
        b.setField(descriptor.findFieldByName("d"), 1.0d);
        b.setField(descriptor.findFieldByName("f"), 1.0E10f);
        for (double v : new double[] {1.0, 1.5E-7, 1.0E21}) b.addRepeatedField(descriptor.findFieldByName("rd"), v);
        Descriptor entryDesc = descriptor.findFieldByName("m").getMessageType();
        b.addRepeatedField(
                descriptor.findFieldByName("m"),
                DynamicMessage.newBuilder(entryDesc)
                        .setField(entryDesc.findFieldByName("key"), "a")
                        .setField(entryDesc.findFieldByName("value"), 2.0d)
                        .build());
        b.setField(
                descriptor.findFieldByName("dv"),
                dynamic(DoubleValue.newBuilder().setValue(3.0).build()));
        b.setField(
                descriptor.findFieldByName("fv"),
                dynamic(FloatValue.newBuilder().setValue(0.5f).build()));
        b.setField(
                descriptor.findFieldByName("v"),
                dynamic(Value.newBuilder().setNumberValue(1.0E20).build()));
        b.setField(
                descriptor.findFieldByName("s"),
                dynamic(Struct.newBuilder()
                        .putFields("k", Value.newBuilder().setNumberValue(4.0).build())
                        .build()));
        message = b.build();
        expectedEcmascript = fixture("/serde/ecmascript-message.json");
    }

    @Test
    @DisplayName(
            "GIVEN a message with doubles everywhere WHEN Tier 0 transcodes it in ECMASCRIPT SHOULD match the fixture")
    void given_message_when_wireTranscoderEcmascript_should_matchFixture() {
        WireJsonTranscoder transcoder = new WireJsonTranscoder();
        WireJsonTranscoder.Program program = transcoder.programFor(descriptor);
        assertThat(program).isNotNull();

        byte[] json = transcoder.transcode(program, message.toByteArray(), ECMASCRIPT);

        assertThat(new String(json, StandardCharsets.UTF_8)).isEqualTo(expectedEcmascript);
    }

    @Test
    @DisplayName(
            "GIVEN a message with doubles everywhere WHEN the reflective writer writes it in ECMASCRIPT SHOULD match the fixture")
    void given_message_when_reflectiveWriterEcmascript_should_matchFixture() throws Exception {
        StringWriter out = new StringWriter();
        try (JsonGenerator g = JSON.createGenerator(out)) {
            ProtoMapConverter.writeMessageJson(g, message, ECMASCRIPT);
        }

        assertThat(out.toString()).isEqualTo(expectedEcmascript);
    }

    @Test
    @DisplayName("GIVEN a message WHEN printed in JAVA format SHOULD stay what JsonFormat prints, on both writers")
    void given_message_when_javaFormat_should_matchJsonFormat() throws Exception {
        String reference = JsonFormat.printer()
                .includingDefaultValueFields()
                .omittingInsignificantWhitespace()
                .print(message);
        WireJsonTranscoder transcoder = new WireJsonTranscoder();
        byte[] wire = transcoder.transcode(
                transcoder.programFor(descriptor),
                message.toByteArray(),
                new JsonPrintOptions(true, false, false, JsonNumberFormat.JAVA));
        StringWriter out = new StringWriter();
        try (JsonGenerator g = JSON.createGenerator(out)) {
            ProtoMapConverter.writeMessageJson(g, message, JsonPrintOptions.DEFAULT);
        }

        assertThat(out.toString()).isEqualTo(reference);
        assertThat(new String(wire, StandardCharsets.UTF_8)).contains("\"d\":1.0", "\"rd\":[1.0,1.5E-7,1.0E21]");
    }

    @Test
    @DisplayName("GIVEN a ListValue of numbers WHEN written as a WKT in ECMASCRIPT SHOULD lay each number out")
    void given_listValue_when_wktEcmascript_should_layOutNumbers() throws Exception {
        DynamicMessage list = dynamic(ListValue.newBuilder()
                .addValues(Value.newBuilder().setNumberValue(1.0))
                .addValues(Value.newBuilder().setNumberValue(2.5))
                .build());
        StringWriter out = new StringWriter();
        try (JsonGenerator g = JSON.createGenerator(out)) {
            ProtoWkt.writeJson(g, list, JsonNumberFormat.ECMASCRIPT);
        }

        assertThat(out.toString()).isEqualTo("[1,2.5]");
    }

    private static DynamicMessage dynamic(com.google.protobuf.Message m) throws Exception {
        return DynamicMessage.parseFrom(m.getDescriptorForType(), m.toByteArray());
    }

    private static String fixture(String path) throws IOException {
        try (InputStream in = JsonNumberFormatMessageTest.class.getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
    }

    private static FieldDescriptorProto field(
            String name, int number, FieldDescriptorProto.Type type, String typeName, boolean repeated) {
        FieldDescriptorProto.Builder b = FieldDescriptorProto.newBuilder()
                .setName(name)
                .setJsonName(name)
                .setNumber(number)
                .setType(type)
                .setLabel(
                        repeated
                                ? FieldDescriptorProto.Label.LABEL_REPEATED
                                : FieldDescriptorProto.Label.LABEL_OPTIONAL);
        if (typeName != null) b.setTypeName(typeName);
        return b.build();
    }
}
