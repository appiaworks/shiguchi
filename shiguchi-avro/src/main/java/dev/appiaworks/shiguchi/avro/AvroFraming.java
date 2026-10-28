package dev.appiaworks.shiguchi.avro;

import dev.appiaworks.shiguchi.config.ServiceDefinition.AvroWireFormat;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.IntFunction;
import java.util.function.LongFunction;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;

/**
 * Turns Avro datums into bytes and back in the three framings the gateway speaks.
 *
 * <ul>
 *   <li>{@link AvroWireFormat#BINARY}: the raw datum. Both sides must hold the schema already.
 *   <li>{@link AvroWireFormat#SINGLE_OBJECT}: the Avro single-object encoding, {@code C3 01} then
 *       the writer schema's 8-byte CRC-64-AVRO fingerprint (little-endian) then the datum. The
 *       reader looks the fingerprint up and resolves the writer schema against its own, so a
 *       backend that evolved its schema still decodes as long as the gateway knows that version.
 *   <li>{@link AvroWireFormat#CONFLUENT}: the Confluent wire format, {@code 00} then the 4-byte
 *       big-endian id the schema registry assigned the writer schema, then the datum. The reader
 *       looks the id up in what it fetched from the registry at load time.
 * </ul>
 *
 * <p>Decoding never needs to be told the framing: the markers are checked first, and bytes without
 * one are read as a raw datum with the expected schema. A raw datum can legitimately start with a
 * zero byte, so the Confluent check also requires the lookup to know the id.
 */
public final class AvroFraming {

    /** The single-object encoding marker. */
    public static final byte[] SINGLE_OBJECT_MARKER = {(byte) 0xC3, (byte) 0x01};

    private static final int HEADER_LENGTH = 10;

    /** The Confluent wire-format magic byte. */
    public static final byte CONFLUENT_MAGIC = 0;

    private static final int CONFLUENT_HEADER_LENGTH = 5;

    private AvroFraming() {}

    /** Whether {@code bytes} start with the single-object header. */
    public static boolean isSingleObject(byte[] bytes) {
        return bytes != null
                && bytes.length >= HEADER_LENGTH
                && bytes[0] == SINGLE_OBJECT_MARKER[0]
                && bytes[1] == SINGLE_OBJECT_MARKER[1];
    }

    /** The fingerprint carried in a single-object header, if the bytes have one. */
    public static OptionalLong fingerprintOf(byte[] bytes) {
        if (!isSingleObject(bytes)) return OptionalLong.empty();
        long fp = 0;
        for (int i = 7; i >= 0; i--) fp = (fp << 8) | (bytes[2 + i] & 0xFFL);
        return OptionalLong.of(fp);
    }

    /** The schema id carried in a Confluent header, if the bytes are long enough to have one. */
    public static OptionalInt confluentIdOf(byte[] bytes) {
        if (bytes == null || bytes.length < CONFLUENT_HEADER_LENGTH || bytes[0] != CONFLUENT_MAGIC) {
            return OptionalInt.empty();
        }
        int id = ((bytes[1] & 0xFF) << 24) | ((bytes[2] & 0xFF) << 16) | ((bytes[3] & 0xFF) << 8) | (bytes[4] & 0xFF);
        return OptionalInt.of(id);
    }

    /** {@link #decode(byte[], Schema, LongFunction, IntFunction)} without registry ids. */
    public static Object decode(byte[] bytes, Schema reader, LongFunction<Optional<Schema>> writerLookup) {
        return decode(bytes, reader, writerLookup, id -> Optional.empty());
    }

    /**
     * Decodes {@code bytes} into a generic datum of {@code reader}.
     *
     * @param writerLookup resolves a single-object fingerprint to the writer schema; may return
     *     empty, in which case the fingerprint must be the reader's own
     * @param registryLookup resolves a Confluent schema id to the writer schema; empty means the
     *     bytes are not Confluent-framed (a raw datum may start with a zero byte)
     * @throws IllegalArgumentException for a fingerprint nobody knows, or bytes that do not decode
     */
    public static Object decode(
            byte[] bytes,
            Schema reader,
            LongFunction<Optional<Schema>> writerLookup,
            IntFunction<Optional<Schema>> registryLookup) {
        Schema writer = reader;
        int offset = 0;
        OptionalInt registryId = confluentIdOf(bytes);
        if (registryId.isPresent()) {
            Optional<Schema> byId = registryLookup.apply(registryId.getAsInt());
            if (byId.isPresent()) {
                writer = byId.get();
                offset = CONFLUENT_HEADER_LENGTH;
            }
        }
        OptionalLong fp = offset == 0 ? fingerprintOf(bytes) : OptionalLong.empty();
        if (fp.isPresent()) {
            offset = HEADER_LENGTH;
            long readerFp = AvroSchemaRegistry.fingerprint(reader);
            if (fp.getAsLong() != readerFp) {
                writer = writerLookup
                        .apply(fp.getAsLong())
                        .orElseThrow(() -> new IllegalArgumentException(String.format(
                                "single-object fingerprint %016x is not a known schema for %s",
                                fp.getAsLong(), reader.getFullName())));
            }
        }
        try {
            BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(bytes, offset, bytes.length - offset, null);
            GenericDatumReader<Object> datumReader = new GenericDatumReader<>(writer, reader);
            return datumReader.read(null, decoder);
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException(
                    "Avro bytes do not decode as " + reader.getFullName() + ": " + e.getMessage(), e);
        }
    }

    /** Encodes a generic datum of {@code schema} in the Confluent wire format under registry id {@code id}. */
    public static byte[] encodeConfluent(Object datum, Schema schema, int id) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(CONFLUENT_MAGIC);
            out.write((id >>> 24) & 0xFF);
            out.write((id >>> 16) & 0xFF);
            out.write((id >>> 8) & 0xFF);
            out.write(id & 0xFF);
            BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
            new GenericDatumWriter<>(schema).write(datum, encoder);
            encoder.flush();
            return out.toByteArray();
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException(
                    "Value does not encode as " + schema.getFullName() + ": " + e.getMessage(), e);
        }
    }

    /** Encodes a generic datum of {@code schema} in the given framing ({@code CONFLUENT} needs {@link #encodeConfluent}). */
    public static byte[] encode(Object datum, Schema schema, AvroWireFormat format) {
        if (format == AvroWireFormat.CONFLUENT) {
            throw new IllegalArgumentException("CONFLUENT framing needs a registry id: use encodeConfluent");
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (format == AvroWireFormat.SINGLE_OBJECT) {
                out.write(SINGLE_OBJECT_MARKER);
                long fp = AvroSchemaRegistry.fingerprint(schema);
                for (int i = 0; i < 8; i++) out.write((int) (fp >>> (8 * i)) & 0xFF);
            }
            BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
            new GenericDatumWriter<>(schema).write(datum, encoder);
            encoder.flush();
            return out.toByteArray();
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException(
                    "Value does not encode as " + schema.getFullName() + ": " + e.getMessage(), e);
        }
    }
}
