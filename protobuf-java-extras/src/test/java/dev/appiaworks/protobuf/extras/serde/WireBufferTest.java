package dev.appiaworks.protobuf.extras.serde;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.CodedInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD tests for WireBuffer: a growable protobuf wire-format output with in-place length-delimited
 * field encoding. Tests verify the buffer correctly encodes nested messages, varint lengths,
 * strings with UTF-8 multi-byte sequences, and integer types.
 */
class WireBufferTest {

    @Test
    @DisplayName(
            "Given a nested message under 128 bytes, when begin/endLengthDelimited, should parse via CodedInputStream")
    void given_nestedMessageUnder128Bytes_when_lengthDelimitedRoundTrip_should_parseCorrectly() throws Exception {
        WireBuffer buf = new WireBuffer();

        buf.writeTag(1, 2);
        int mark = buf.beginLengthDelimited();
        buf.writeVarint32(42);
        buf.writeString("test");
        buf.endLengthDelimited(mark);

        byte[] bytes = buf.toByteArray();
        CodedInputStream in = CodedInputStream.newInstance(bytes);
        int tag = in.readTag();
        int length = in.readRawVarint32();
        assertThat(length).isEqualTo(bytes.length - 2); // tag byte + length byte, rest is content
    }

    @Test
    @DisplayName("Given 200-byte content in length-delimited, when ended, should shift and write 2-byte varint")
    void given_200ByteContent_when_lengthDelimitedEnded_should_write2ByteVarint() throws Exception {
        WireBuffer buf = new WireBuffer();

        buf.writeTag(1, 2);
        int mark = buf.beginLengthDelimited();
        byte[] content = new byte[200];
        buf.writeRawBytes(content, 0, 200);
        buf.endLengthDelimited(mark);

        byte[] bytes = buf.toByteArray();
        CodedInputStream in = CodedInputStream.newInstance(bytes);
        int tag = in.readTag();
        byte[] readBack = in.readByteArray();
        assertThat(readBack).isEqualTo(content);
    }

    @Test
    @DisplayName("Given 20000-byte content in length-delimited, when ended, should write 3-byte varint length")
    void given_20000ByteContent_when_lengthDelimitedEnded_should_write3ByteVarint() throws Exception {
        WireBuffer buf = new WireBuffer();

        buf.writeTag(1, 2);
        int mark = buf.beginLengthDelimited();
        byte[] content = new byte[20000];
        for (int i = 0; i < 20000; i++) {
            content[i] = (byte) (i & 0xFF);
        }
        buf.writeRawBytes(content, 0, 20000);
        buf.endLengthDelimited(mark);

        byte[] bytes = buf.toByteArray();
        CodedInputStream in = CodedInputStream.newInstance(bytes);
        int tag = in.readTag();
        byte[] readBack = in.readByteArray();
        assertThat(readBack).isEqualTo(content);
    }

    @Test
    @DisplayName("Given two nested levels both over 127 bytes, when ended inner then outer, should parse correctly")
    void given_twoNestedLevelsOver127Bytes_when_nestedEnded_should_parseCorrectly() throws Exception {
        WireBuffer buf = new WireBuffer();

        // Outer message
        buf.writeTag(1, 2);
        int outerMark = buf.beginLengthDelimited();

        // Inner message (over 127 bytes)
        buf.writeTag(2, 2);
        int innerMark = buf.beginLengthDelimited();
        byte[] innerContent = new byte[150];
        buf.writeRawBytes(innerContent, 0, 150);
        buf.endLengthDelimited(innerMark);

        // End outer
        buf.endLengthDelimited(outerMark);

        byte[] bytes = buf.toByteArray();
        CodedInputStream in = CodedInputStream.newInstance(bytes);
        int outerTag = in.readTag();
        int outerLength = in.readRawVarint32();
        assertThat(outerLength).isEqualTo(1 + 2 + 150); // inner tag + 2-byte inner length + inner content
        assertThat(in.readTag()).isEqualTo((2 << 3) | 2);
        assertThat(in.readByteArray()).isEqualTo(innerContent);
    }

    @Test
    @DisplayName("Given an ASCII string, when writeString, should equal string's UTF-8 bytes after length varint")
    void given_asciiString_when_writeString_should_matchUtf8Bytes() {
        WireBuffer buf = new WireBuffer();

        String ascii = "hello";
        buf.writeString(ascii);

        byte[] result = buf.toByteArray();
        byte[] expected = ascii.getBytes(StandardCharsets.UTF_8);

        // First byte is the length (5), followed by the ASCII bytes
        assertThat(result[0]).isEqualTo((byte) expected.length);
        for (int i = 0; i < expected.length; i++) {
            assertThat(result[i + 1]).isEqualTo(expected[i]);
        }
    }

    @Test
    @DisplayName(
            "Given a string with 2-byte and 3-byte UTF-8 chars (é, 日本) and 4-byte emoji, when writeString, should match UTF-8")
    void given_multiBytUtf8String_when_writeString_should_encodeCorrectly() {
        WireBuffer buf = new WireBuffer();

        String text = "café日本😀"; // 2-byte, 3-byte, 3-byte, 4-byte chars
        buf.writeString(text);

        byte[] result = buf.toByteArray();
        byte[] expected = text.getBytes(StandardCharsets.UTF_8);

        // Skip the length varint (1 byte for short strings)
        byte[] encoded = new byte[result.length - 1];
        System.arraycopy(result, 1, encoded, 0, encoded.length);
        assertThat(encoded).isEqualTo(expected);
    }

    @Test
    @DisplayName(
            "Given a string with unpaired surrogate, when writeString, should return false and leave size unchanged")
    void given_unpairedSurrogate_when_writeString_should_returnFalseAndNotWrite() {
        WireBuffer buf = new WireBuffer();
        int initialSize = buf.size();

        // Unpaired high surrogate
        String invalid = "a\uD800b";
        boolean result = buf.writeString(invalid);

        assertThat(result).isFalse();
        assertThat(buf.size()).isEqualTo(initialSize);
    }

    @Test
    @DisplayName("Given writeVarint64 of -1 (int32 negative), when read with CodedInputStream.readInt32, should be -1")
    void given_negativeInt32AsVarint64_when_readAsInt32_should_roundTrip() throws Exception {
        WireBuffer buf = new WireBuffer();
        buf.writeVarint64(-1L);

        byte[] bytes = buf.toByteArray();
        CodedInputStream in = CodedInputStream.newInstance(bytes);
        int value = in.readInt32();

        assertThat(value).isEqualTo(-1);
    }

    @Test
    @DisplayName("Given writeSInt32(-5), when read with readSInt32, should equal -5 (zigzag roundtrip)")
    void given_negativeSInt32_when_readSInt32_should_roundTrip() throws Exception {
        WireBuffer buf = new WireBuffer();
        buf.writeSInt32(-5);

        byte[] bytes = buf.toByteArray();
        CodedInputStream in = CodedInputStream.newInstance(bytes);
        int value = in.readSInt32();

        assertThat(value).isEqualTo(-5);
    }

    @Test
    @DisplayName("Given writeFixed64/readFixed64, should roundtrip correctly")
    void given_fixed64Value_when_roundTrip_should_matchOriginal() throws Exception {
        WireBuffer buf = new WireBuffer();
        long originalValue = 9007199254740992L;

        buf.writeFixed64(originalValue);

        byte[] bytes = buf.toByteArray();
        CodedInputStream in = CodedInputStream.newInstance(bytes);
        long value = in.readFixed64();

        assertThat(value).isEqualTo(originalValue);
    }

    @Test
    @DisplayName("Given truncate(n), should drop bytes after n and reject n > size")
    void given_truncate_should_dropBytesAndValidateSize() {
        WireBuffer buf = new WireBuffer();
        buf.writeVarint32(42);
        buf.writeVarint32(99);
        int initialSize = buf.size();

        buf.truncate(1);
        assertThat(buf.size()).isEqualTo(1);

        // Reject truncate with size > current
        assertThatThrownBy(() -> buf.truncate(initialSize))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside");
    }

    @Test
    @DisplayName("Given truncate with negative size, should throw IllegalArgumentException")
    void given_negativeTruncateSize_should_throwIllegalArgumentException() {
        WireBuffer buf = new WireBuffer();
        buf.writeVarint32(42);

        assertThatThrownBy(() -> buf.truncate(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside");
    }
}
