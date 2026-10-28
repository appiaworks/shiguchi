package dev.appiaworks.protobuf.extras.serde;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.OneofDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.util.JsonFormat;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD tests for the Tier 0 bail-out contract: wire encodings that need merge semantics
 * (duplicate singular fields, resumed repeated runs, dual oneof members) must return null
 * so the dispatcher falls back to the DynamicMessage path, unknown fields must be skipped,
 * and unsupported types must get no program at all.
 */
class WireJsonTranscoderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Descriptor msg;
    private static Descriptor superset;
    private static final WireJsonTranscoder transcoder = new WireJsonTranscoder();

    @BeforeAll
    static void buildDescriptors() throws Exception {
        DescriptorProto base = DescriptorProto.newBuilder()
                .setName("Msg")
                .addField(scalar("i", 1, FieldDescriptorProto.Type.TYPE_INT32))
                .addField(scalar("s", 2, FieldDescriptorProto.Type.TYPE_STRING))
                .addField(scalar("tags", 3, FieldDescriptorProto.Type.TYPE_STRING).toBuilder()
                        .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
                        .build())
                .addOneofDecl(OneofDescriptorProto.newBuilder().setName("c"))
                .addField(scalar("cs", 4, FieldDescriptorProto.Type.TYPE_STRING).toBuilder()
                        .setOneofIndex(0)
                        .build())
                .addField(scalar("cn", 5, FieldDescriptorProto.Type.TYPE_INT32).toBuilder()
                        .setOneofIndex(0)
                        .build())
                .build();
        DescriptorProto wide = base.toBuilder()
                .setName("MsgWide")
                .addField(scalar("extra", 99, FieldDescriptorProto.Type.TYPE_STRING))
                .build();

        FileDescriptor file = FileDescriptor.buildFrom(
                FileDescriptorProto.newBuilder()
                        .setName("t0.proto")
                        .setSyntax("proto3")
                        .setPackage("t0")
                        .addMessageType(base)
                        .addMessageType(wide)
                        .build(),
                new FileDescriptor[0]);
        msg = file.findMessageTypeByName("Msg");
        superset = file.findMessageTypeByName("MsgWide");
    }

    private static byte[] wire(Descriptor d, Object... nameValuePairs) {
        DynamicMessage.Builder b = DynamicMessage.newBuilder(d);
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            var fd = d.findFieldByName((String) nameValuePairs[i]);
            if (fd.isRepeated()) {
                b.addRepeatedField(fd, nameValuePairs[i + 1]);
            } else {
                b.setField(fd, nameValuePairs[i + 1]);
            }
        }
        return b.build().toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) len += p.length;
        byte[] out = new byte[len];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    @Test
    @DisplayName("Given an empty message, when transcoded, should emit the same defaults as JsonFormat")
    void given_emptyMessage_when_transcoded_should_emitDefaults() throws Exception {
        byte[] json = transcoder.transcode(transcoder.programFor(msg), new byte[0]);

        assertThat(json).isNotNull();
        String expected = JsonFormat.printer()
                .includingDefaultValueFields()
                .omittingInsignificantWhitespace()
                .print(DynamicMessage.newBuilder(msg).build());
        assertThat(MAPPER.readTree(json)).isEqualTo(MAPPER.readTree(expected));
    }

    @Test
    @DisplayName("Given a duplicated singular field on the wire, when transcoded, should bail with null")
    void given_duplicateSingularField_when_transcoded_should_bail() {
        byte[] merged = concat(wire(msg, "s", "first"), wire(msg, "s", "second"));

        assertThat(transcoder.transcode(transcoder.programFor(msg), merged)).isNull();
    }

    @Test
    @DisplayName("Given a repeated field resuming after another field, when transcoded, should bail with null")
    void given_interleavedRepeatedField_when_transcoded_should_bail() {
        byte[] interleaved = concat(wire(msg, "tags", "a"), wire(msg, "i", 7), wire(msg, "tags", "b"));

        assertThat(transcoder.transcode(transcoder.programFor(msg), interleaved))
                .isNull();
    }

    @Test
    @DisplayName("Given two members of one oneof on the wire, when transcoded, should bail with null")
    void given_bothOneofMembers_when_transcoded_should_bail() {
        byte[] both = concat(wire(msg, "cs", "x"), wire(msg, "cn", 3));

        assertThat(transcoder.transcode(transcoder.programFor(msg), both)).isNull();
    }

    @Test
    @DisplayName("Given unknown fields on the wire, when transcoded with the narrower schema, should skip them")
    void given_unknownFields_when_transcoded_should_skipThem() throws Exception {
        byte[] wide = wire(superset, "i", 42, "s", "keep", "extra", "dropped");

        byte[] json = transcoder.transcode(transcoder.programFor(msg), wide);

        assertThat(json).isNotNull();
        var tree = MAPPER.readTree(json);
        assertThat(tree.get("i").asInt()).isEqualTo(42);
        assertThat(tree.get("s").asText()).isEqualTo("keep");
        assertThat(tree.has("extra")).isFalse();
    }

    @Test
    @DisplayName("Given a type containing google.protobuf.Any, when programFor, should return null")
    void given_typeWithAnyField_when_programFor_should_returnNull() throws Exception {
        FileDescriptor file = FileDescriptor.buildFrom(
                FileDescriptorProto.newBuilder()
                        .setName("hasany0.proto")
                        .setSyntax("proto3")
                        .setPackage("t0")
                        .addDependency("google/protobuf/any.proto")
                        .addMessageType(DescriptorProto.newBuilder()
                                .setName("HasAny")
                                .addField(FieldDescriptorProto.newBuilder()
                                        .setName("payload")
                                        .setNumber(1)
                                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                                        .setTypeName(".google.protobuf.Any")
                                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)))
                        .build(),
                new FileDescriptor[] {com.google.protobuf.Any.getDescriptor().getFile()});

        assertThat(transcoder.programFor(file.findMessageTypeByName("HasAny"))).isNull();
    }

    @Test
    @DisplayName(
            "Given non-finite float/double values, when transcoded, should emit NaN/Infinity strings like JsonFormat")
    void given_nonFiniteDoubles_when_transcoded_should_matchJsonFormat() throws Exception {
        FileDescriptor file = FileDescriptor.buildFrom(
                FileDescriptorProto.newBuilder()
                        .setName("dbl0.proto")
                        .setSyntax("proto3")
                        .setPackage("t0")
                        .addMessageType(DescriptorProto.newBuilder()
                                .setName("Dbl")
                                .addField(scalar("d", 1, FieldDescriptorProto.Type.TYPE_DOUBLE)))
                        .build(),
                new FileDescriptor[0]);
        Descriptor dbl = file.findMessageTypeByName("Dbl");
        DynamicMessage m = DynamicMessage.newBuilder(dbl)
                .setField(dbl.findFieldByName("d"), Double.NaN)
                .build();

        byte[] json = transcoder.transcode(transcoder.programFor(dbl), m.toByteArray());

        String expected = JsonFormat.printer()
                .includingDefaultValueFields()
                .omittingInsignificantWhitespace()
                .print(m);
        assertThat(new String(json, StandardCharsets.UTF_8)).isEqualTo(expected);
    }

    private static FieldDescriptorProto scalar(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .build();
    }
}
