package dev.appiaworks.protobuf.extras.serde;

import java.util.Arrays;

/**
 * A growable protobuf wire-format output, written front to back in one pass.
 *
 * <p>A length-delimited field (a submessage, a packed run, a string) is opened with {@link
 * #beginLengthDelimited}, which reserves one byte for the length, and closed with {@link
 * #endLengthDelimited} once its content is in. A content shorter than 128 bytes fits that byte;
 * a longer one has its tail moved right by the extra varint bytes. Nested messages so need no
 * buffer of their own and no second sizing pass, which is what lets a JSON document stream into
 * wire bytes as it is parsed.
 */
public final class WireBuffer {

    private byte[] buf;
    private int pos;

    public WireBuffer() {
        this(256);
    }

    public WireBuffer(int initialCapacity) {
        this.buf = new byte[Math.max(16, initialCapacity)];
    }

    public int size() {
        return pos;
    }

    public void reset() {
        pos = 0;
    }

    /** Drops everything written past {@code size}, undoing a partial write. */
    public void truncate(int size) {
        if (size < 0 || size > pos) throw new IllegalArgumentException("size " + size + " outside 0.." + pos);
        pos = size;
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(buf, pos);
    }

    private void ensure(int more) {
        if (pos + more > buf.length) {
            buf = Arrays.copyOf(buf, Math.max(buf.length << 1, pos + more));
        }
    }

    // ── Primitives ───────────────────────────────────────────────────────────────────

    public void writeTag(int fieldNumber, int wireType) {
        writeVarint32((fieldNumber << 3) | wireType);
    }

    public void writeVarint32(int value) {
        ensure(5);
        while ((value & ~0x7F) != 0) {
            buf[pos++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf[pos++] = (byte) value;
    }

    public void writeVarint64(long value) {
        ensure(10);
        while ((value & ~0x7FL) != 0) {
            buf[pos++] = (byte) (((int) value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf[pos++] = (byte) value;
    }

    public void writeSInt32(int value) {
        writeVarint32((value << 1) ^ (value >> 31));
    }

    public void writeSInt64(long value) {
        writeVarint64((value << 1) ^ (value >> 63));
    }

    public void writeFixed32(int value) {
        ensure(4);
        buf[pos++] = (byte) value;
        buf[pos++] = (byte) (value >> 8);
        buf[pos++] = (byte) (value >> 16);
        buf[pos++] = (byte) (value >> 24);
    }

    public void writeFixed64(long value) {
        ensure(8);
        for (int i = 0; i < 8; i++) {
            buf[pos++] = (byte) (value >> (8 * i));
        }
    }

    public void writeBool(boolean value) {
        ensure(1);
        buf[pos++] = value ? (byte) 1 : (byte) 0;
    }

    public void writeRawBytes(byte[] bytes, int offset, int length) {
        ensure(length);
        System.arraycopy(bytes, offset, buf, pos, length);
        pos += length;
    }

    /** A length-prefixed byte run: {@code bytes} and {@code BytesValue}. */
    public void writeBytes(byte[] bytes) {
        writeVarint32(bytes.length);
        writeRawBytes(bytes, 0, bytes.length);
    }

    // ── Length-delimited content written in place ────────────────────────────────────

    /** Reserves the length byte and returns its position, to pass to {@link #endLengthDelimited}. */
    public int beginLengthDelimited() {
        ensure(1);
        return pos++;
    }

    /** Fills in the length of everything written since {@code mark}, moving the content when it needs more than one byte. */
    public void endLengthDelimited(int mark) {
        int length = pos - mark - 1;
        if (length < 0x80) {
            buf[mark] = (byte) length;
            return;
        }
        int varintSize = varintSize(length);
        int extra = varintSize - 1;
        ensure(extra);
        System.arraycopy(buf, mark + 1, buf, mark + varintSize, length);
        int p = mark;
        int v = length;
        while ((v & ~0x7F) != 0) {
            buf[p++] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        buf[p] = (byte) v;
        pos += extra;
    }

    private static int varintSize(int value) {
        if ((value & (~0 << 7)) == 0) return 1;
        if ((value & (~0 << 14)) == 0) return 2;
        if ((value & (~0 << 21)) == 0) return 3;
        if ((value & (~0 << 28)) == 0) return 4;
        return 5;
    }

    /**
     * A length-prefixed UTF-8 string from a char range, encoded straight into the buffer.
     *
     * @return false when the range holds an unpaired surrogate, which has no UTF-8 form; nothing
     *     is written then and the caller takes another path
     */
    public boolean writeString(char[] chars, int offset, int length) {
        int mark = beginLengthDelimited();
        ensure(length * 3);
        int end = offset + length;
        int i = offset;
        // ASCII run first: the common case needs no branching per byte.
        while (i < end) {
            char c = chars[i];
            if (c >= 0x80) break;
            buf[pos++] = (byte) c;
            i++;
        }
        while (i < end) {
            char c = chars[i++];
            if (c < 0x80) {
                buf[pos++] = (byte) c;
            } else if (c < 0x800) {
                buf[pos++] = (byte) (0xC0 | (c >> 6));
                buf[pos++] = (byte) (0x80 | (c & 0x3F));
            } else if (!Character.isSurrogate(c)) {
                buf[pos++] = (byte) (0xE0 | (c >> 12));
                buf[pos++] = (byte) (0x80 | ((c >> 6) & 0x3F));
                buf[pos++] = (byte) (0x80 | (c & 0x3F));
            } else {
                if (!Character.isHighSurrogate(c) || i >= end || !Character.isLowSurrogate(chars[i])) {
                    pos = mark;
                    return false;
                }
                int cp = Character.toCodePoint(c, chars[i++]);
                ensure(4);
                buf[pos++] = (byte) (0xF0 | (cp >> 18));
                buf[pos++] = (byte) (0x80 | ((cp >> 12) & 0x3F));
                buf[pos++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
                buf[pos++] = (byte) (0x80 | (cp & 0x3F));
            }
        }
        endLengthDelimited(mark);
        return true;
    }

    /** {@link #writeString(char[], int, int)} for a String. */
    public boolean writeString(String s) {
        return writeString(s.toCharArray(), 0, s.length());
    }
}
