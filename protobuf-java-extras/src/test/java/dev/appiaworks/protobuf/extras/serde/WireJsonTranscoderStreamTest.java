package dev.appiaworks.protobuf.extras.serde;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD tests for the stream-based transcode overload: writing JSON directly to an OutputStream
 * without buffering the entire JSON in memory. Tests the contract that false on bail allows the
 * caller to rewind and fall back, and that output is functionally identical to the byte[] overload.
 */
class WireJsonTranscoderStreamTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Descriptor simpleMsg;
    private static Descriptor largeMsg;
    private static final WireJsonTranscoder transcoder = new WireJsonTranscoder();

    @BeforeAll
    static void buildDescriptors() throws Exception {
        DescriptorProto simple = DescriptorProto.newBuilder()
                .setName("Simple")
                .addField(scalar("id", 1, FieldDescriptorProto.Type.TYPE_STRING))
                .addField(scalar("n", 2, FieldDescriptorProto.Type.TYPE_INT32))
                .build();
        DescriptorProto large = DescriptorProto.newBuilder()
                .setName("Large")
                .addField(scalar("items", 1, FieldDescriptorProto.Type.TYPE_STRING).toBuilder()
                        .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
                        .build())
                .build();

        FileDescriptor file = FileDescriptor.buildFrom(
                FileDescriptorProto.newBuilder()
                        .setName("stream.proto")
                        .setSyntax("proto3")
                        .setPackage("stream")
                        .addMessageType(simple)
                        .addMessageType(large)
                        .build(),
                new FileDescriptor[0]);
        simpleMsg = file.findMessageTypeByName("Simple");
        largeMsg = file.findMessageTypeByName("Large");
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
    @DisplayName("Given a well-formed message, when transcoded to a ByteArrayOutputStream, "
            + "should return true and output should equal the byte[] overload")
    void given_wellFormedMessage_when_transcodedToStream_should_returnTrueAndEqualByteArray() throws Exception {
        byte[] wireBytes = wire(simpleMsg, "id", "test-id", "n", 42);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        boolean success =
                transcoder.transcode(transcoder.programFor(simpleMsg), wireBytes, JsonPrintOptions.DEFAULT, out);

        assertThat(success).isTrue();
        byte[] streamJson = out.toByteArray();
        byte[] arrayJson = transcoder.transcode(transcoder.programFor(simpleMsg), wireBytes);
        assertThat(streamJson).isEqualTo(arrayJson);
    }

    @Test
    @DisplayName("Given print options with preserveProtoFieldNames, when transcoded to stream, "
            + "should equal the byte[] overload with the same options")
    void given_preserveProtoFieldNameOption_when_transcodedToStream_should_respectOption() throws Exception {
        byte[] wireBytes = wire(simpleMsg, "id", "preserve-test", "n", 99);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JsonPrintOptions opts = new JsonPrintOptions(true, false, false); // preserveProtoFieldNames

        boolean success = transcoder.transcode(transcoder.programFor(simpleMsg), wireBytes, opts, out);

        assertThat(success).isTrue();
        byte[] streamJson = out.toByteArray();
        byte[] arrayJson = transcoder.transcode(transcoder.programFor(simpleMsg), wireBytes, opts);
        assertThat(streamJson).isEqualTo(arrayJson);
        // Verify the option was applied: should contain "id" not "Id"
        assertThat(new String(streamJson, StandardCharsets.UTF_8)).contains("\"id\"");
    }

    @Test
    @DisplayName("Given wire with a duplicated singular field (bail case), when transcoded to stream, "
            + "should return false")
    void given_duplicateSingularFieldBailCase_when_transcodedToStream_should_returnFalse() throws Exception {
        byte[] merged = concat(wire(simpleMsg, "id", "first"), wire(simpleMsg, "id", "second"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        boolean success = transcoder.transcode(transcoder.programFor(simpleMsg), merged, JsonPrintOptions.DEFAULT, out);

        assertThat(success).isFalse();
    }

    @Test
    @DisplayName("Given the bail case, when the caller rewinds its buffer and writes new bytes, "
            + "the stream should contain only those new bytes")
    void given_bailCase_when_bufferRewound_should_containOnlyNewBytes() throws Exception {
        byte[] merged = concat(wire(simpleMsg, "id", "first"), wire(simpleMsg, "id", "second"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // First transcode attempt should bail
        boolean success = transcoder.transcode(transcoder.programFor(simpleMsg), merged, JsonPrintOptions.DEFAULT, out);
        assertThat(success).isFalse();

        // Caller rewinds the buffer
        int sizeBeforeReset = out.size();
        out.reset();
        assertThat(out.size()).isEqualTo(0);

        // Now write new bytes
        byte[] newWireBytes = wire(simpleMsg, "id", "valid", "n", 7);
        transcoder.transcode(transcoder.programFor(simpleMsg), newWireBytes, JsonPrintOptions.DEFAULT, out);

        byte[] result = out.toByteArray();
        // Only the second document: exactly what the byte[] overload produces for it.
        assertThat(sizeBeforeReset).isGreaterThan(0); // the bail left partial output the caller discarded
        assertThat(result).isEqualTo(transcoder.transcode(transcoder.programFor(simpleMsg), newWireBytes));
    }

    @Test
    @DisplayName("Given a large message with 2000 repeated string entries (~40 chars each), "
            + "when transcoded to stream, should return true and equal the byte[] overload "
            + "(exercises generator flushing mid-way)")
    void given_largeMessageWithManyRepeatedEntries_when_transcodedToStream_should_returnTrueAndBeComplete()
            throws Exception {
        // Build a message with 2000 repeated entries, each ~40 chars
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(largeMsg);
        String template = "this-is-a-moderately-sized-string-";
        for (int i = 0; i < 2000; i++) {
            builder.addRepeatedField(largeMsg.findFieldByName("items"), template + i);
        }
        byte[] wireBytes = builder.build().toByteArray();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean success =
                transcoder.transcode(transcoder.programFor(largeMsg), wireBytes, JsonPrintOptions.DEFAULT, out);

        assertThat(success).isTrue();
        byte[] streamJson = out.toByteArray();
        byte[] arrayJson = transcoder.transcode(transcoder.programFor(largeMsg), wireBytes);
        assertThat(streamJson).isEqualTo(arrayJson);

        // Verify the output is valid JSON with all items
        var tree = MAPPER.readTree(streamJson);
        var itemsNode = tree.get("items");
        assertThat(itemsNode.isArray()).isTrue();
        assertThat(itemsNode).hasSize(2000);
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
