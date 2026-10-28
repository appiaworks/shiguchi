package dev.appiaworks.protobuf.extras.serde;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.FieldMask;
import com.google.protobuf.ListValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.protobuf.util.JsonFormat;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tier 0 reading {@code Struct}, {@code Value}, {@code ListValue} and {@code FieldMask} straight off
 * the wire: the same JSON as {@code JsonFormat} on well-formed input, a bail where a stream cannot
 * express what a parse would build.
 */
class WireJsonTranscoderStructTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final WireJsonTranscoder transcoder = new WireJsonTranscoder();
    private static Descriptor doc;

    @BeforeAll
    static void buildDescriptor() throws Exception {
        DescriptorProto message = DescriptorProto.newBuilder()
                .setName("Doc")
                .addField(field("meta", 1, ".google.protobuf.Struct"))
                .addField(field("tags", 2, ".google.protobuf.ListValue"))
                .addField(field("mask", 3, ".google.protobuf.FieldMask"))
                .addField(field("v", 4, ".google.protobuf.Value"))
                .build();
        FileDescriptor file = FileDescriptor.buildFrom(
                FileDescriptorProto.newBuilder()
                        .setName("struct_t0.proto")
                        .setSyntax("proto3")
                        .setPackage("t0")
                        .addDependency("google/protobuf/struct.proto")
                        .addDependency("google/protobuf/field_mask.proto")
                        .addMessageType(message)
                        .build(),
                new FileDescriptor[] {
                    Struct.getDescriptor().getFile(), FieldMask.getDescriptor().getFile()
                });
        doc = file.findMessageTypeByName("Doc");
    }

    private static FieldDescriptorProto field(String name, int number, String typeName) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                .setTypeName(typeName)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .build();
    }

    /** The message as the compiled WKTs serialise it, as a dynamic message of {@code Doc}. */
    private static DynamicMessage docOf(Struct meta, ListValue tags, FieldMask mask, Value v) throws Exception {
        DynamicMessage.Builder b = DynamicMessage.newBuilder(doc);
        if (meta != null) b.setField(doc.findFieldByName("meta"), dynamic(meta, "meta"));
        if (tags != null) b.setField(doc.findFieldByName("tags"), dynamic(tags, "tags"));
        if (mask != null) b.setField(doc.findFieldByName("mask"), dynamic(mask, "mask"));
        if (v != null) b.setField(doc.findFieldByName("v"), dynamic(v, "v"));
        return b.build();
    }

    private static DynamicMessage dynamic(com.google.protobuf.Message m, String fieldName) throws Exception {
        return DynamicMessage.parseFrom(doc.findFieldByName(fieldName).getMessageType(), m.toByteArray());
    }

    private static byte[] transcode(byte[] wire) {
        return transcoder.transcode(transcoder.programFor(doc), wire);
    }

    private static void assertSameAsJsonFormat(DynamicMessage msg) throws Exception {
        byte[] json = transcode(msg.toByteArray());
        assertThat(json).as("Tier 0 must take this message").isNotNull();
        String expected = JsonFormat.printer().includingDefaultValueFields().print(msg);
        assertThat(MAPPER.readTree(json)).isEqualTo(MAPPER.readTree(expected));
    }

    @Test
    @DisplayName("Given a Struct with scalars, a nested struct and a list, when transcoded, should match JsonFormat")
    void given_nestedStruct_when_transcoded_should_matchJsonFormat() throws Exception {
        Struct meta = Struct.newBuilder()
                .putFields("n", Value.newBuilder().setNumberValue(1.5).build())
                .putFields("s", Value.newBuilder().setStringValue("x").build())
                .putFields("b", Value.newBuilder().setBoolValue(false).build())
                .putFields("none", Value.newBuilder().setNullValueValue(0).build())
                .putFields(
                        "inner",
                        Value.newBuilder()
                                .setStructValue(Struct.newBuilder()
                                        .putFields(
                                                "k",
                                                Value.newBuilder()
                                                        .setNumberValue(2)
                                                        .build()))
                                .build())
                .putFields(
                        "list",
                        Value.newBuilder()
                                .setListValue(ListValue.newBuilder()
                                        .addValues(Value.newBuilder().setStringValue("a"))
                                        .addValues(Value.newBuilder().setNumberValue(3)))
                                .build())
                .build();
        assertSameAsJsonFormat(docOf(meta, null, null, null));
    }

    @Test
    @DisplayName(
            "Given a ListValue, a FieldMask with snake_case paths and a top-level Value, when transcoded, should match JsonFormat")
    void given_listMaskAndValue_when_transcoded_should_matchJsonFormat() throws Exception {
        ListValue tags = ListValue.newBuilder()
                .addValues(Value.newBuilder().setStringValue("alpha"))
                .addValues(Value.newBuilder().setBoolValue(true))
                .addValues(Value.newBuilder().setNullValueValue(0))
                .build();
        FieldMask mask = FieldMask.newBuilder()
                .addPaths("user.display_name")
                .addPaths("created_at")
                .build();
        Value v = Value.newBuilder().setStringValue("top").build();
        assertSameAsJsonFormat(docOf(null, tags, mask, v));
        String json = new String(transcode(docOf(null, tags, mask, v).toByteArray()), StandardCharsets.UTF_8);
        assertThat(json).contains("\"user.displayName,createdAt\"");
    }

    @Test
    @DisplayName(
            "Given an empty Struct, an empty ListValue and an unset Value, when transcoded, should print {}, [] and null")
    void given_emptyWkts_when_transcoded_should_printEmptyForms() throws Exception {
        DynamicMessage msg =
                docOf(Struct.getDefaultInstance(), ListValue.getDefaultInstance(), null, Value.getDefaultInstance());
        assertSameAsJsonFormat(msg);
        var tree = MAPPER.readTree(transcode(msg.toByteArray()));
        assertThat(tree.get("meta").isObject()).isTrue();
        assertThat(tree.get("meta").size()).isZero();
        assertThat(tree.get("tags").isArray()).isTrue();
        assertThat(tree.get("v").isNull()).isTrue();
    }

    @Test
    @DisplayName("Given a Value carrying two members on the wire, when transcoded, should bail with null")
    void given_valueWithTwoMembers_when_transcoded_should_bail() throws Exception {
        // string_value then number_value in one Value: a parse keeps the last, a stream cannot.
        byte[] twoMembers = concat(
                Value.newBuilder().setStringValue("first").build().toByteArray(),
                Value.newBuilder().setNumberValue(2).build().toByteArray());
        byte[] wire = lengthDelimited(4, twoMembers);
        assertThat(transcode(wire)).isNull();
    }

    @Test
    @DisplayName("Given a Struct repeating a key, when transcoded, should bail with null")
    void given_structWithDuplicateKey_when_transcoded_should_bail() throws Exception {
        byte[] entryA = Struct.newBuilder()
                .putFields("k", Value.newBuilder().setNumberValue(1).build())
                .build()
                .toByteArray();
        byte[] entryB = Struct.newBuilder()
                .putFields("k", Value.newBuilder().setNumberValue(2).build())
                .build()
                .toByteArray();
        byte[] wire = lengthDelimited(1, concat(entryA, entryB));
        assertThat(transcode(wire)).isNull();
    }

    private static byte[] lengthDelimited(int fieldNumber, byte[] content) {
        byte[] out = new byte[content.length + 2];
        out[0] = (byte) ((fieldNumber << 3) | 2);
        out[1] = (byte) content.length; // all fixtures are under 128 bytes
        System.arraycopy(content, 0, out, 2, content.length);
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
