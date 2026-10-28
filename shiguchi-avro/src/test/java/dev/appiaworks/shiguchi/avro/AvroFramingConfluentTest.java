package dev.appiaworks.shiguchi.avro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.appiaworks.shiguchi.config.ServiceDefinition.AvroWireFormat;
import java.io.InputStream;
import java.util.Optional;
import java.util.OptionalInt;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD-style tests for the Confluent wire format support in AvroFraming.
 *
 * <p>Tests verify that:
 *
 * <ul>
 *   <li>{@code encodeConfluent} writes the {@code 00} magic byte and a 4-byte big-endian id
 *   <li>{@code confluentIdOf} reads that id back, and is empty for bytes too short or without
 *       the magic byte
 *   <li>The 4-arg {@code decode} resolves Confluent-framed bytes through {@code registryLookup},
 *       falls back to a raw datum when the lookup is empty (a raw datum may legitimately start
 *       with a zero byte), and the plain {@code encode} refuses {@code CONFLUENT} directly
 * </ul>
 */
@DisplayName("AvroFraming Confluent wire format tests")
class AvroFramingConfluentTest {

    private Schema itemSchema;

    @BeforeEach
    void loadSchema() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/avro/item.avsc")) {
            itemSchema = new Schema.Parser().parse(in);
        }
    }

    private GenericRecord minimalItem(Schema schema) {
        GenericData.Record record = new GenericData.Record(schema);
        record.put("sku", "SKU-1");
        record.put("name", null);
        record.put("qty", 5L);
        record.put(
                "price",
                java.nio.ByteBuffer.wrap(
                        new java.math.BigDecimal("1.00").unscaledValue().toByteArray()));
        record.put("tags", java.util.List.of());
        record.put("attrs", java.util.Map.of());
        Schema statusSchema = schema.getField("status").schema();
        record.put("status", new GenericData.EnumSymbol(statusSchema, "ACTIVE"));
        record.put("updatedAt", 0L);
        record.put("created", 0);
        record.put("category", null);
        Schema fixedSchema = schema.getField("fixedId").schema();
        record.put("fixedId", new GenericData.Fixed(fixedSchema, new byte[] {0, 0, 0, 0}));
        return record;
    }

    @Test
    @DisplayName(
            "GIVEN a datum and registry id 42 WHEN encodeConfluent called SHOULD start with magic byte 00 then big-endian id 00 00 00 2A")
    void given_datumAndId_when_encodeConfluent_should_startWithMagicByteAndBigEndianId() {
        // Arrange
        GenericRecord record = minimalItem(itemSchema);

        // Act
        byte[] bytes = AvroFraming.encodeConfluent(record, itemSchema, 42);

        // Assert
        assertThat(bytes[0]).isEqualTo((byte) 0x00);
        assertThat(bytes[1]).isEqualTo((byte) 0x00);
        assertThat(bytes[2]).isEqualTo((byte) 0x00);
        assertThat(bytes[3]).isEqualTo((byte) 0x00);
        assertThat(bytes[4]).isEqualTo((byte) 0x2A);
    }

    @Test
    @DisplayName(
            "GIVEN Confluent-framed bytes WHEN confluentIdOf called SHOULD read the big-endian id back, and be empty for short or unmarked bytes")
    void given_confluentFramedBytes_when_confluentIdOf_should_readIdBackAndBeEmptyOtherwise() {
        // Arrange
        GenericRecord record = minimalItem(itemSchema);
        byte[] bytes = AvroFraming.encodeConfluent(record, itemSchema, 42);
        byte[] tooShort = {0x00, 0x00, 0x00};
        byte[] noMagicByte = {0x01, 0x00, 0x00, 0x00, 0x2A};

        // Act
        OptionalInt fromValidBytes = AvroFraming.confluentIdOf(bytes);
        OptionalInt fromTooShort = AvroFraming.confluentIdOf(tooShort);
        OptionalInt fromNoMagicByte = AvroFraming.confluentIdOf(noMagicByte);

        // Assert
        assertThat(fromValidBytes).hasValue(42);
        assertThat(fromTooShort).isEmpty();
        assertThat(fromNoMagicByte).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN Confluent-framed bytes whose id resolves through the registry lookup WHEN decode called SHOULD decode the datum")
    void given_confluentFramedBytesWithKnownId_when_decode_should_decodeDatum() {
        // Arrange
        GenericRecord record = minimalItem(itemSchema);
        byte[] bytes = AvroFraming.encodeConfluent(record, itemSchema, 7);

        // Act
        Object decoded = AvroFraming.decode(
                bytes, itemSchema, fp -> Optional.empty(), id -> id == 7 ? Optional.of(itemSchema) : Optional.empty());

        // Assert
        assertThat(decoded).isInstanceOf(GenericRecord.class);
        assertThat(((GenericRecord) decoded).get("sku").toString()).isEqualTo("SKU-1");
    }

    @Test
    @DisplayName(
            "GIVEN a raw BINARY-encoded datum that happens to start with a zero byte WHEN decode called with an empty idLookup SHOULD decode it as a raw datum")
    void given_rawDatumStartingWithZeroByte_when_decodeWithEmptyIdLookup_should_decodeAsRawDatum() {
        // Arrange: a record whose first field is a small non-negative long encodes (zig-zag varint)
        // starting with a low byte; force a schema/record combo whose raw binary starts with 0x00.
        Schema soleLongField = org.apache.avro.SchemaBuilder.record("ZeroFirstByte")
                .namespace("com.acme.test")
                .fields()
                .name("n")
                .type()
                .longType()
                .noDefault()
                .endRecord();
        GenericData.Record zeroRecord = new GenericData.Record(soleLongField);
        zeroRecord.put("n", 0L);
        byte[] rawBytes = AvroFraming.encode(zeroRecord, soleLongField, AvroWireFormat.BINARY);
        assertThat(rawBytes[0]).isEqualTo((byte) 0x00);

        // Act: registryLookup returns empty for every id, exactly like a codec with no registry bound.
        Object decoded = AvroFraming.decode(rawBytes, soleLongField, fp -> Optional.empty(), id -> Optional.empty());

        // Assert
        assertThat(((GenericRecord) decoded).get("n")).isEqualTo(0L);
    }

    @Test
    @DisplayName(
            "GIVEN CONFLUENT format WHEN the 3-arg encode is called SHOULD throw IllegalArgumentException mentioning encodeConfluent")
    void given_confluentFormat_when_encode_should_throwIllegalArgumentExceptionMentioningEncodeConfluent() {
        // Arrange
        GenericRecord record = minimalItem(itemSchema);

        // Act & Assert
        assertThatThrownBy(() -> AvroFraming.encode(record, itemSchema, AvroWireFormat.CONFLUENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("encodeConfluent");
    }

    @Test
    @DisplayName(
            "GIVEN SINGLE_OBJECT and BINARY encoded bytes WHEN decoded with the 3-arg decode SHOULD still work as before")
    void given_singleObjectAndBinaryBytes_when_decodedWithThreeArgDecode_should_stillWork() {
        // Arrange
        GenericRecord record = minimalItem(itemSchema);
        byte[] singleObjectBytes = AvroFraming.encode(record, itemSchema, AvroWireFormat.SINGLE_OBJECT);
        byte[] binaryBytes = AvroFraming.encode(record, itemSchema, AvroWireFormat.BINARY);

        // Act
        Object decodedSingleObject = AvroFraming.decode(singleObjectBytes, itemSchema, fp -> Optional.empty());
        Object decodedBinary = AvroFraming.decode(binaryBytes, itemSchema, fp -> Optional.empty());

        // Assert
        assertThat(((GenericRecord) decodedSingleObject).get("sku").toString()).isEqualTo("SKU-1");
        assertThat(((GenericRecord) decodedBinary).get("sku").toString()).isEqualTo("SKU-1");
    }
}
