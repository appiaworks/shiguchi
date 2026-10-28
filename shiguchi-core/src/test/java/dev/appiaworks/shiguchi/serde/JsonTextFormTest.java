package dev.appiaworks.shiguchi.serde;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.DescriptorValidationException;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.StructProto;
import com.google.protobuf.util.JsonFormat;
import dev.appiaworks.protobuf.extras.serde.AbstractDynamicSerializer;
import dev.appiaworks.protobuf.extras.serde.Codegen;
import dev.appiaworks.protobuf.extras.serde.ProtoMapConverter;
import dev.appiaworks.protobuf.extras.serde.WireJsonTranscoder;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The gateway's one JSON text form. Every path that prints a message must produce the fixture
 * {@code serde/json-text-form.json} byte for byte: JsonFormat output (which HTML-escapes
 * {@code <>&='} and U+2028 through Gson) is rewritten by {@link ProtoJsonText}, the Tier 3
 * JsonFormat path, the Tier 2 generated serializer, the Tier 0 wire transcoder and the reflective
 * writer must all agree.
 */
class JsonTextFormTest {

    private static final String FIXTURE_PATH = "/serde/json-text-form.json";
    private static final JsonFactory JSON_FACTORY = new JsonFactory();

    private static String expected;
    private static Descriptor sampleDescriptor;
    private static DynamicMessage sample;

    @BeforeAll
    static void buildDescriptorAndSample() throws IOException {
        expected = readFixture();

        FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                .setName("textform.proto")
                .setPackage("textform")
                .setSyntax("proto3")
                .addDependency("google/protobuf/struct.proto")
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Sample")
                        .addField(field("d", 1, FieldDescriptorProto.Type.TYPE_DOUBLE, null))
                        .addField(field("f", 2, FieldDescriptorProto.Type.TYPE_FLOAT, null))
                        .addField(field("big", 3, FieldDescriptorProto.Type.TYPE_INT64, null))
                        .addField(field("s", 4, FieldDescriptorProto.Type.TYPE_STRING, null))
                        .addField(field("v", 5, FieldDescriptorProto.Type.TYPE_MESSAGE, ".google.protobuf.Value"))
                        .addField(field("w", 6, FieldDescriptorProto.Type.TYPE_MESSAGE, ".google.protobuf.Value")))
                .build();
        FileDescriptor file;
        try {
            file = FileDescriptor.buildFrom(proto, new FileDescriptor[] {StructProto.getDescriptor()});
        } catch (DescriptorValidationException e) {
            throw new IllegalStateException("textform.proto must build", e);
        }
        sampleDescriptor = file.findMessageTypeByName("Sample");

        Descriptor valueDescriptor = StructProto.getDescriptor().findMessageTypeByName("Value");
        sample = DynamicMessage.newBuilder(sampleDescriptor)
                .setField(sampleDescriptor.findFieldByName("d"), 1.0d)
                .setField(sampleDescriptor.findFieldByName("f"), 0.1f)
                .setField(sampleDescriptor.findFieldByName("big"), 123456789012345678L)
                .setField(sampleDescriptor.findFieldByName("s"), "<a>&='" + "\u2028" + "\"" + "\n")
                .setField(sampleDescriptor.findFieldByName("v"), numberValue(valueDescriptor, 1e20))
                .setField(sampleDescriptor.findFieldByName("w"), numberValue(valueDescriptor, 123456789012345678d))
                .build();
    }

    @Test
    @DisplayName("Given a message with HTML-sensitive text, when JsonFormat prints it, should escape angle brackets")
    void given_htmlSensitiveText_when_jsonFormatPrints_should_escapeAngleBrackets() throws Exception {
        String printed = JsonFormat.printer().omittingInsignificantWhitespace().print(sample);

        assertThat(printed).contains("\\u003ca\\u003e");
    }

    @Test
    @DisplayName(
            "Given a message, when ProtoJsonText prints it with JsonFormat, should match the gateway JSON text form")
    void given_sampleMessage_when_protoJsonTextPrints_should_matchFixture() throws Exception {
        String printed = ProtoJsonText.print(
                JsonFormat.printer().includingDefaultValueFields().omittingInsignificantWhitespace(), sample);

        assertThat(printed).isEqualTo(expected);
    }

    @Test
    @DisplayName(
            "Given a message, when the Tier 3 JsonFormat response path serializes it, should match the gateway JSON text form")
    void given_sampleMessage_when_tier3ResponseToJson_should_matchFixture() throws Exception {
        String printed =
                new DynamicProtoSerde(null).responseToJson(sample, JsonFormat.TypeRegistry.getEmptyTypeRegistry());

        assertThat(printed).isEqualTo(expected);
    }

    @Test
    @DisplayName(
            "Given a message and the Java 25 code generator, when Tier 2 serializes it, should match the gateway JSON text form")
    void given_sampleMessage_when_tier2Serializes_should_matchFixture() throws Exception {
        Assumptions.assumeTrue(
                Codegen.available(),
                "needs the serializer code generator: Java 25+ with protobuf-java-extras-classfile");
        AbstractDynamicSerializer serializer = new DynamicSerializerFactory().compileDescriptor(sampleDescriptor);

        StringWriter out = new StringWriter();
        try (JsonGenerator generator = JSON_FACTORY.createGenerator(out)) {
            serializer.serialize(sample, generator);
        }

        assertThat(out.toString()).isEqualTo(expected);
    }

    @Test
    @DisplayName("Given a message, when Tier 0 transcodes its wire bytes, should match the gateway JSON text form")
    void given_sampleMessage_when_tier0Transcodes_should_matchFixture() throws Exception {
        WireJsonTranscoder transcoder = new WireJsonTranscoder();
        WireJsonTranscoder.Program program = transcoder.programFor(sampleDescriptor);
        assertThat(program).as("Sample must be Tier 0 eligible").isNotNull();

        byte[] json = transcoder.transcode(program, sample.toByteArray());

        assertThat(new String(json, StandardCharsets.UTF_8)).isEqualTo(expected);
    }

    @Test
    @DisplayName("Given a message, when the reflective writer writes it, should match the gateway JSON text form")
    void given_sampleMessage_when_reflectiveWriterWrites_should_matchFixture() throws Exception {
        StringWriter out = new StringWriter();
        try (JsonGenerator generator = JSON_FACTORY.createGenerator(out)) {
            ProtoMapConverter.writeMessageJson(generator, sample);
        }

        assertThat(out.toString()).isEqualTo(expected);
    }

    @Test
    @DisplayName("Given JSON text with no backslash, when normalized, should return the same instance unchanged")
    void given_textWithoutBackslash_when_normalized_should_returnSameInstance() {
        String json = "{\"n\":1.0,\"s\":\"plain\"}";

        String normalized = ProtoJsonText.normalize(json);

        assertThat(normalized).isSameAs(json);
    }

    @Test
    @DisplayName("Given JSON with escaped strings and exact numbers, when normalized, should rewrite only the escapes")
    void given_escapedStringsAndExactNumbers_when_normalized_should_rewriteEscapesOnly() {
        String json = "{\"n\":1.0E20,\"i\":12345678901234567890,\"s\":\"\\u003c\"}";

        String normalized = ProtoJsonText.normalize(json);

        assertThat(normalized).isEqualTo("{\"n\":1.0E20,\"i\":12345678901234567890,\"s\":\"<\"}");
    }

    private static String readFixture() throws IOException {
        try (InputStream in = JsonTextFormTest.class.getResourceAsStream(FIXTURE_PATH)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource " + FIXTURE_PATH);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
    }

    private static FieldDescriptorProto field(
            String name, int number, FieldDescriptorProto.Type type, String typeName) {
        FieldDescriptorProto.Builder builder = FieldDescriptorProto.newBuilder()
                .setName(name)
                .setJsonName(name)
                .setNumber(number)
                .setType(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
        if (typeName != null) {
            builder.setTypeName(typeName);
        }
        return builder.build();
    }

    private static DynamicMessage numberValue(Descriptor valueDescriptor, double number) {
        return DynamicMessage.newBuilder(valueDescriptor)
                .setField(valueDescriptor.findFieldByName("number_value"), number)
                .build();
    }
}
