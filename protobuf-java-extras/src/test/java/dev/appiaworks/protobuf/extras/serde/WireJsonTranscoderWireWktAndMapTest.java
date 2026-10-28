package dev.appiaworks.protobuf.extras.serde;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MessageOptions;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.util.JsonFormat;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD tests for Tier 0 wire transcoding of WKTs (Timestamp, Duration), wrappers, and map fields,
 * verifying that the streaming JSON emitter matches {@code JsonFormat.printer().includingDefaultValueFields()}.
 */
class WireJsonTranscoderWireWktAndMapTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final WireJsonTranscoder transcoder = new WireJsonTranscoder();

    private static Descriptor msgDesc;
    private static Descriptor innerDesc;

    @BeforeAll
    static void buildDescriptors() throws Exception {
        FileDescriptor timestampFile =
                com.google.protobuf.Timestamp.getDescriptor().getFile();
        FileDescriptor durationFile =
                com.google.protobuf.Duration.getDescriptor().getFile();
        FileDescriptor wrappersFile =
                com.google.protobuf.StringValue.getDescriptor().getFile();

        // Nested Inner message: int32 n = 1; string s = 2;
        DescriptorProto inner = DescriptorProto.newBuilder()
                .setName("Inner")
                .addField(scalar("n", 1, FieldDescriptorProto.Type.TYPE_INT32))
                .addField(scalar("s", 2, FieldDescriptorProto.Type.TYPE_STRING))
                .build();

        // Map entries for all map fields
        DescriptorProto labelsEntry = mapEntry(
                "LabelsEntry", 1, FieldDescriptorProto.Type.TYPE_STRING, 2, FieldDescriptorProto.Type.TYPE_STRING);
        DescriptorProto countsEntry = mapEntry(
                "CountsEntry", 1, FieldDescriptorProto.Type.TYPE_INT32, 2, FieldDescriptorProto.Type.TYPE_INT64);
        DescriptorProto flagsEntry = mapEntry(
                "FlagsEntry", 1, FieldDescriptorProto.Type.TYPE_BOOL, 2, FieldDescriptorProto.Type.TYPE_STRING);

        // Map<uint64, Inner> items = 10: message type value
        DescriptorProto itemsEntry = DescriptorProto.newBuilder()
                .setName("ItemsEntry")
                .setOptions(MessageOptions.newBuilder().setMapEntry(true).build())
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("key")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_UINT64)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("value")
                        .setNumber(2)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName(".pkg.Msg.Inner")
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .build();

        // Main Msg
        DescriptorProto msg = DescriptorProto.newBuilder()
                .setName("Msg")
                .addField(messageField("ts", 1, ".google.protobuf.Timestamp"))
                .addField(messageField("dur", 2, ".google.protobuf.Duration"))
                .addField(messageField("sv", 3, ".google.protobuf.StringValue"))
                .addField(messageField("iv", 4, ".google.protobuf.Int64Value"))
                .addField(messageField("bv", 5, ".google.protobuf.BoolValue"))
                .addField(messageField("byv", 6, ".google.protobuf.BytesValue"))
                .addField(repeatedMessageField("labels", 7, ".pkg.Msg.LabelsEntry"))
                .addField(repeatedMessageField("counts", 8, ".pkg.Msg.CountsEntry"))
                .addField(repeatedMessageField("flags", 9, ".pkg.Msg.FlagsEntry"))
                .addField(repeatedMessageField("items", 10, ".pkg.Msg.ItemsEntry"))
                .addField(repeatedMessageField("stamps", 11, ".google.protobuf.Timestamp"))
                .addNestedType(inner)
                .addNestedType(labelsEntry)
                .addNestedType(countsEntry)
                .addNestedType(flagsEntry)
                .addNestedType(itemsEntry)
                .build();

        FileDescriptor file = FileDescriptor.buildFrom(
                FileDescriptorProto.newBuilder()
                        .setName("wkt_and_map.proto")
                        .setSyntax("proto3")
                        .setPackage("pkg")
                        .addDependency("google/protobuf/timestamp.proto")
                        .addDependency("google/protobuf/duration.proto")
                        .addDependency("google/protobuf/wrappers.proto")
                        .addMessageType(msg)
                        .build(),
                new FileDescriptor[] {timestampFile, durationFile, wrappersFile});

        msgDesc = file.findMessageTypeByName("Msg");
        innerDesc = msgDesc.findNestedTypeByName("Inner");
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
    @DisplayName("Given a message with all WKT and wrapper fields set, when transcoded, should match JsonFormat tree")
    void given_allWktFieldsSet_when_transcoded_should_matchJsonFormat() throws Exception {
        DynamicMessage inner1 = DynamicMessage.newBuilder(innerDesc)
                .setField(innerDesc.findFieldByNumber(1), 42)
                .setField(innerDesc.findFieldByNumber(2), "inner1")
                .build();

        DynamicMessage ts = DynamicMessage.newBuilder(
                        msgDesc.findFieldByNumber(1).getMessageType())
                .setField(msgDesc.findFieldByNumber(1).getMessageType().findFieldByNumber(1), 1234567890L)
                .setField(msgDesc.findFieldByNumber(1).getMessageType().findFieldByNumber(2), 123456789)
                .build();

        DynamicMessage dur = DynamicMessage.newBuilder(
                        msgDesc.findFieldByNumber(2).getMessageType())
                .setField(msgDesc.findFieldByNumber(2).getMessageType().findFieldByNumber(1), 100L)
                .setField(msgDesc.findFieldByNumber(2).getMessageType().findFieldByNumber(2), 500000000)
                .build();

        DynamicMessage sv = DynamicMessage.newBuilder(
                        msgDesc.findFieldByNumber(3).getMessageType())
                .setField(msgDesc.findFieldByNumber(3).getMessageType().findFieldByNumber(1), "hello")
                .build();

        DynamicMessage iv = DynamicMessage.newBuilder(
                        msgDesc.findFieldByNumber(4).getMessageType())
                .setField(msgDesc.findFieldByNumber(4).getMessageType().findFieldByNumber(1), 9999L)
                .build();

        DynamicMessage bv = DynamicMessage.newBuilder(
                        msgDesc.findFieldByNumber(5).getMessageType())
                .setField(msgDesc.findFieldByNumber(5).getMessageType().findFieldByNumber(1), true)
                .build();

        DynamicMessage byv = DynamicMessage.newBuilder(
                        msgDesc.findFieldByNumber(6).getMessageType())
                .setField(
                        msgDesc.findFieldByNumber(6).getMessageType().findFieldByNumber(1),
                        com.google.protobuf.ByteString.copyFromUtf8("bytes"))
                .build();

        DynamicMessage expected = DynamicMessage.newBuilder(msgDesc)
                .setField(msgDesc.findFieldByNumber(1), ts)
                .setField(msgDesc.findFieldByNumber(2), dur)
                .setField(msgDesc.findFieldByNumber(3), sv)
                .setField(msgDesc.findFieldByNumber(4), iv)
                .setField(msgDesc.findFieldByNumber(5), bv)
                .setField(msgDesc.findFieldByNumber(6), byv)
                .build();

        byte[] json = transcoder.transcode(transcoder.programFor(msgDesc), expected.toByteArray());

        String expectedJson = JsonFormat.printer()
                .includingDefaultValueFields()
                .omittingInsignificantWhitespace()
                .print(expected);

        assertThat(json).isNotNull();
        assertThat(MAPPER.readTree(json)).isEqualTo(MAPPER.readTree(expectedJson));
    }

    @Test
    @DisplayName("Given wrappers with default values explicitly set, when transcoded, should print them (not omit)")
    void given_wrappersWithDefaultValuesSet_when_transcoded_should_printThem() throws Exception {
        DynamicMessage sv = DynamicMessage.newBuilder(
                        msgDesc.findFieldByNumber(3).getMessageType())
                .setField(msgDesc.findFieldByNumber(3).getMessageType().findFieldByNumber(1), "")
                .build();

        DynamicMessage iv = DynamicMessage.newBuilder(
                        msgDesc.findFieldByNumber(4).getMessageType())
                .setField(msgDesc.findFieldByNumber(4).getMessageType().findFieldByNumber(1), 0L)
                .build();

        DynamicMessage bv = DynamicMessage.newBuilder(
                        msgDesc.findFieldByNumber(5).getMessageType())
                .setField(msgDesc.findFieldByNumber(5).getMessageType().findFieldByNumber(1), false)
                .build();

        DynamicMessage expected = DynamicMessage.newBuilder(msgDesc)
                .setField(msgDesc.findFieldByNumber(3), sv)
                .setField(msgDesc.findFieldByNumber(4), iv)
                .setField(msgDesc.findFieldByNumber(5), bv)
                .build();

        byte[] json = transcoder.transcode(transcoder.programFor(msgDesc), expected.toByteArray());

        String expectedJson = JsonFormat.printer()
                .includingDefaultValueFields()
                .omittingInsignificantWhitespace()
                .print(expected);

        assertThat(json).isNotNull();
        var jsonTree = MAPPER.readTree(json);
        assertThat(jsonTree.has("sv")).isTrue();
        assertThat(jsonTree.has("iv")).isTrue();
        assertThat(jsonTree.has("bv")).isTrue();
        assertThat(jsonTree).isEqualTo(MAPPER.readTree(expectedJson));
    }

    @Test
    @DisplayName("Given every map field populated with entries, when transcoded, should match JsonFormat")
    void given_allMapFieldsPopulated_when_transcoded_should_matchJsonFormat() throws Exception {
        DynamicMessage inner1 = DynamicMessage.newBuilder(innerDesc)
                .setField(innerDesc.findFieldByNumber(1), 10)
                .setField(innerDesc.findFieldByNumber(2), "i1")
                .build();

        DynamicMessage inner2 = DynamicMessage.newBuilder(innerDesc)
                .setField(innerDesc.findFieldByNumber(1), 20)
                .setField(innerDesc.findFieldByNumber(2), "i2")
                .build();

        DynamicMessage expected = DynamicMessage.newBuilder(msgDesc).build();
        DynamicMessage.Builder b = DynamicMessage.newBuilder(msgDesc);

        // labels: string->string
        var labelsEntry = msgDesc.findFieldByNumber(7).getMessageType();
        b.addRepeatedField(
                msgDesc.findFieldByNumber(7),
                DynamicMessage.newBuilder(labelsEntry)
                        .setField(labelsEntry.findFieldByNumber(1), "k1")
                        .setField(labelsEntry.findFieldByNumber(2), "v1")
                        .build());
        b.addRepeatedField(
                msgDesc.findFieldByNumber(7),
                DynamicMessage.newBuilder(labelsEntry)
                        .setField(labelsEntry.findFieldByNumber(1), "k2")
                        .setField(labelsEntry.findFieldByNumber(2), "v2")
                        .build());
        b.addRepeatedField(
                msgDesc.findFieldByNumber(7),
                DynamicMessage.newBuilder(labelsEntry)
                        .setField(labelsEntry.findFieldByNumber(1), "k3")
                        .setField(labelsEntry.findFieldByNumber(2), "v3")
                        .build());

        // counts: int32->int64
        var countsEntry = msgDesc.findFieldByNumber(8).getMessageType();
        b.addRepeatedField(
                msgDesc.findFieldByNumber(8),
                DynamicMessage.newBuilder(countsEntry)
                        .setField(countsEntry.findFieldByNumber(1), 1)
                        .setField(countsEntry.findFieldByNumber(2), 100L)
                        .build());
        b.addRepeatedField(
                msgDesc.findFieldByNumber(8),
                DynamicMessage.newBuilder(countsEntry)
                        .setField(countsEntry.findFieldByNumber(1), 2)
                        .setField(countsEntry.findFieldByNumber(2), 200L)
                        .build());
        b.addRepeatedField(
                msgDesc.findFieldByNumber(8),
                DynamicMessage.newBuilder(countsEntry)
                        .setField(countsEntry.findFieldByNumber(1), 3)
                        .setField(countsEntry.findFieldByNumber(2), 300L)
                        .build());

        // flags: bool->string
        var flagsEntry = msgDesc.findFieldByNumber(9).getMessageType();
        b.addRepeatedField(
                msgDesc.findFieldByNumber(9),
                DynamicMessage.newBuilder(flagsEntry)
                        .setField(flagsEntry.findFieldByNumber(1), true)
                        .setField(flagsEntry.findFieldByNumber(2), "yes")
                        .build());
        b.addRepeatedField(
                msgDesc.findFieldByNumber(9),
                DynamicMessage.newBuilder(flagsEntry)
                        .setField(flagsEntry.findFieldByNumber(1), false)
                        .setField(flagsEntry.findFieldByNumber(2), "no")
                        .build());

        // items: uint64->Inner (with message values)
        var itemsEntry = msgDesc.findFieldByNumber(10).getMessageType();
        b.addRepeatedField(
                msgDesc.findFieldByNumber(10),
                DynamicMessage.newBuilder(itemsEntry)
                        .setField(itemsEntry.findFieldByNumber(1), 1000L)
                        .setField(itemsEntry.findFieldByNumber(2), inner1)
                        .build());
        b.addRepeatedField(
                msgDesc.findFieldByNumber(10),
                DynamicMessage.newBuilder(itemsEntry)
                        .setField(itemsEntry.findFieldByNumber(1), 2000L)
                        .setField(itemsEntry.findFieldByNumber(2), inner2)
                        .build());

        expected = b.build();
        byte[] json = transcoder.transcode(transcoder.programFor(msgDesc), expected.toByteArray());

        String expectedJson = JsonFormat.printer()
                .includingDefaultValueFields()
                .omittingInsignificantWhitespace()
                .print(expected);

        assertThat(json).isNotNull();
        assertThat(MAPPER.readTree(json)).isEqualTo(MAPPER.readTree(expectedJson));
    }

    @Test
    @DisplayName("Given a repeated Timestamp field with 3 entries, when transcoded, should match JsonFormat")
    void given_repeatedTimestampWith3Entries_when_transcoded_should_matchJsonFormat() throws Exception {
        DynamicMessage ts1 = DynamicMessage.newBuilder(
                        msgDesc.findFieldByNumber(11).getMessageType())
                .setField(msgDesc.findFieldByNumber(11).getMessageType().findFieldByNumber(1), 100L)
                .setField(msgDesc.findFieldByNumber(11).getMessageType().findFieldByNumber(2), 0)
                .build();

        DynamicMessage ts2 = DynamicMessage.newBuilder(
                        msgDesc.findFieldByNumber(11).getMessageType())
                .setField(msgDesc.findFieldByNumber(11).getMessageType().findFieldByNumber(1), 200L)
                .setField(msgDesc.findFieldByNumber(11).getMessageType().findFieldByNumber(2), 500000000)
                .build();

        DynamicMessage ts3 = DynamicMessage.newBuilder(
                        msgDesc.findFieldByNumber(11).getMessageType())
                .setField(msgDesc.findFieldByNumber(11).getMessageType().findFieldByNumber(1), 300L)
                .setField(msgDesc.findFieldByNumber(11).getMessageType().findFieldByNumber(2), 123456789)
                .build();

        DynamicMessage expected = DynamicMessage.newBuilder(msgDesc)
                .addRepeatedField(msgDesc.findFieldByNumber(11), ts1)
                .addRepeatedField(msgDesc.findFieldByNumber(11), ts2)
                .addRepeatedField(msgDesc.findFieldByNumber(11), ts3)
                .build();

        byte[] json = transcoder.transcode(transcoder.programFor(msgDesc), expected.toByteArray());

        String expectedJson = JsonFormat.printer()
                .includingDefaultValueFields()
                .omittingInsignificantWhitespace()
                .print(expected);

        assertThat(json).isNotNull();
        assertThat(MAPPER.readTree(json)).isEqualTo(MAPPER.readTree(expectedJson));
    }

    @Test
    @DisplayName("Given a map entry with value before key on the wire, when transcoded, should bail with null")
    void given_mapEntryWithValueBeforeKey_when_transcoded_should_bail() throws Exception {
        // Hand-build a map entry with field 2 (value) before field 1 (key), which is non-canonical
        var labelsEntry = msgDesc.findFieldByNumber(7).getMessageType();
        byte[] valueBytes = "value1".getBytes(StandardCharsets.UTF_8);
        byte[] keyBytes = "key1".getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CodedOutputStream cos = CodedOutputStream.newInstance(baos);
        // Tag for field 2 (value, string): (2 << 3) | 2 = 18
        cos.writeTag(2, 2);
        cos.writeRawVarint32(valueBytes.length);
        cos.writeRawBytes(valueBytes);
        // Tag for field 1 (key, string): (1 << 3) | 2 = 10
        cos.writeTag(1, 2);
        cos.writeRawVarint32(keyBytes.length);
        cos.writeRawBytes(keyBytes);
        cos.flush();
        byte[] entryBytes = baos.toByteArray();

        // Wrap in the map field tag and length
        byte[] mapFieldTag = new byte[] {0x3a}; // (7 << 3) | 2 = 58
        byte[] entryLength = varintEncode(entryBytes.length);
        byte[] mapBytes = concat(mapFieldTag, entryLength, entryBytes);

        byte[] json = transcoder.transcode(transcoder.programFor(msgDesc), mapBytes);

        assertThat(json).isNull();
    }

    @Test
    @DisplayName("Given a map entry carrying its key twice, when transcoded, should bail with null")
    void given_mapEntryWithKeyTwice_when_transcoded_should_bail() throws Exception {
        var labelsEntry = msgDesc.findFieldByNumber(7).getMessageType();
        byte[] keyBytes1 = "key1".getBytes(StandardCharsets.UTF_8);
        byte[] keyBytes2 = "key2".getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CodedOutputStream cos = CodedOutputStream.newInstance(baos);
        // First key field
        cos.writeTag(1, 2);
        cos.writeRawVarint32(keyBytes1.length);
        cos.writeRawBytes(keyBytes1);
        // Second key field (duplicate)
        cos.writeTag(1, 2);
        cos.writeRawVarint32(keyBytes2.length);
        cos.writeRawBytes(keyBytes2);
        cos.flush();
        byte[] entryBytes = baos.toByteArray();

        byte[] mapFieldTag = new byte[] {0x3a}; // (7 << 3) | 2 = 58
        byte[] entryLength = varintEncode(entryBytes.length);
        byte[] mapBytes = concat(mapFieldTag, entryLength, entryBytes);

        byte[] json = transcoder.transcode(transcoder.programFor(msgDesc), mapBytes);

        assertThat(json).isNull();
    }

    @Test
    @DisplayName("Given a map entry with key only (no value bytes), when transcoded, should use default value")
    void given_mapEntryWithKeyOnly_when_transcoded_should_useDefaultValue() throws Exception {
        byte[] keyBytes = "mykey".getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CodedOutputStream cos = CodedOutputStream.newInstance(baos);
        cos.writeTag(1, 2); // field 1 (key, string)
        cos.writeRawVarint32(keyBytes.length);
        cos.writeRawBytes(keyBytes);
        cos.flush();
        byte[] entryBytes = baos.toByteArray();

        byte[] mapFieldTag = new byte[] {0x3a}; // (7 << 3) | 2 = 58
        byte[] entryLength = varintEncode(entryBytes.length);
        byte[] mapBytes = concat(mapFieldTag, entryLength, entryBytes);

        byte[] json = transcoder.transcode(transcoder.programFor(msgDesc), mapBytes);

        // Build expected: labels entry with key="mykey", value="" (default for string)
        DynamicMessage expected = DynamicMessage.newBuilder(msgDesc).build();
        var labelsEntry = msgDesc.findFieldByNumber(7).getMessageType();
        DynamicMessage entry = DynamicMessage.newBuilder(labelsEntry)
                .setField(labelsEntry.findFieldByNumber(1), "mykey")
                .setField(labelsEntry.findFieldByNumber(2), "")
                .build();
        expected = DynamicMessage.newBuilder(msgDesc)
                .addRepeatedField(msgDesc.findFieldByNumber(7), entry)
                .build();

        String expectedJson = JsonFormat.printer()
                .includingDefaultValueFields()
                .omittingInsignificantWhitespace()
                .print(expected);

        assertThat(json).isNotNull();
        assertThat(MAPPER.readTree(json)).isEqualTo(MAPPER.readTree(expectedJson));
    }

    @Test
    @DisplayName(
            "Given a Timestamp with an unknown extra field inside it on the wire, when transcoded, should skip it and match JsonFormat")
    void given_timestampWithUnknownField_when_transcoded_should_skipAndMatch() throws Exception {
        // Timestamp with seconds=123, nanos=456, and an unknown field 5 (varint)
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CodedOutputStream cos = CodedOutputStream.newInstance(baos);
        cos.writeInt64(1, 123L); // seconds
        cos.writeInt32(2, 456); // nanos
        cos.writeInt32(5, 999); // unknown field
        cos.flush();
        byte[] tsBytes = baos.toByteArray();

        // Wrap in the ts field tag and length
        byte[] tsFieldTag = new byte[] {0x0a}; // (1 << 3) | 2 = 10
        byte[] tsLength = varintEncode(tsBytes.length);
        byte[] msgBytes = concat(tsFieldTag, tsLength, tsBytes);

        byte[] json = transcoder.transcode(transcoder.programFor(msgDesc), msgBytes);

        // Build expected: just the Timestamp without the unknown field
        DynamicMessage ts = DynamicMessage.newBuilder(
                        msgDesc.findFieldByNumber(1).getMessageType())
                .setField(msgDesc.findFieldByNumber(1).getMessageType().findFieldByNumber(1), 123L)
                .setField(msgDesc.findFieldByNumber(1).getMessageType().findFieldByNumber(2), 456)
                .build();

        DynamicMessage expected = DynamicMessage.newBuilder(msgDesc)
                .setField(msgDesc.findFieldByNumber(1), ts)
                .build();

        String expectedJson = JsonFormat.printer()
                .includingDefaultValueFields()
                .omittingInsignificantWhitespace()
                .print(expected);

        assertThat(json).isNotNull();
        assertThat(MAPPER.readTree(json)).isEqualTo(MAPPER.readTree(expectedJson));
    }

    @Test
    @DisplayName("Given JsonPrintOptions with omitDefaultValues and all fields unset, when transcoded, should emit {}")
    void given_omitDefaultValuesOptionAndAllUnset_when_transcoded_should_emitEmptyObject() throws Exception {
        byte[] json = transcoder.transcode(
                transcoder.programFor(msgDesc), new byte[0], new JsonPrintOptions(false, false, true));

        assertThat(json).isNotNull();
        assertThat(MAPPER.readTree(json)).isEqualTo(MAPPER.readTree("{}"));
    }

    // ── Helpers ──

    private static FieldDescriptorProto scalar(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .build();
    }

    private static FieldDescriptorProto messageField(String name, int number, String typeName) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                .setTypeName(typeName)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .build();
    }

    private static FieldDescriptorProto repeatedMessageField(String name, int number, String typeName) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                .setTypeName(typeName)
                .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
                .build();
    }

    private static DescriptorProto mapEntry(
            String name,
            int keyNumber,
            FieldDescriptorProto.Type keyType,
            int valueNumber,
            FieldDescriptorProto.Type valueType) {
        return DescriptorProto.newBuilder()
                .setName(name)
                .setOptions(MessageOptions.newBuilder().setMapEntry(true).build())
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("key")
                        .setNumber(keyNumber)
                        .setType(keyType)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("value")
                        .setNumber(valueNumber)
                        .setType(valueType)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .build();
    }

    private static byte[] varintEncode(int value) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            CodedOutputStream cos = CodedOutputStream.newInstance(baos);
            cos.writeRawVarint32(value);
            cos.flush();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return baos.toByteArray();
    }
}
