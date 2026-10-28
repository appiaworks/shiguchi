package dev.appiaworks.shiguchi.avro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.appiaworks.shiguchi.config.ServiceDefinition.AvroWireFormat;
import java.io.InputStream;
import java.util.Optional;
import java.util.OptionalLong;
import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD-style tests for AvroFraming.
 *
 * <p>Tests verify that:
 *
 * <ul>
 *   <li>{@code BINARY} framing writes the raw datum with no single-object header
 *   <li>{@code SINGLE_OBJECT} framing writes the {@code C3 01} marker and the writer schema's
 *       little-endian fingerprint before the datum
 *   <li>Decoding resolves a writer schema through the supplied lookup only when the fingerprint
 *       does not match the reader, and fails clearly for an unknown fingerprint or garbage bytes
 * </ul>
 */
@DisplayName("AvroFraming encode/decode tests")
class AvroFramingTest {

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
    @DisplayName("GIVEN BINARY format WHEN encode called SHOULD produce bytes with no single-object header")
    void given_binaryFormat_when_encode_should_produceBytesWithoutHeader() {
        // Arrange
        GenericRecord record = minimalItem(itemSchema);

        // Act
        byte[] bytes = AvroFraming.encode(record, itemSchema, AvroWireFormat.BINARY);

        // Assert
        assertThat(AvroFraming.isSingleObject(bytes)).isFalse();
    }

    @Test
    @DisplayName("GIVEN BINARY-encoded bytes WHEN decoded with the same schema SHOULD return the equivalent datum")
    void given_binaryEncodedBytes_when_decoded_should_returnEquivalentDatum() {
        // Arrange
        GenericRecord record = minimalItem(itemSchema);
        byte[] bytes = AvroFraming.encode(record, itemSchema, AvroWireFormat.BINARY);

        // Act
        Object decoded = AvroFraming.decode(bytes, itemSchema, fp -> Optional.empty());

        // Assert
        assertThat(decoded).isInstanceOf(GenericRecord.class);
        assertThat(((GenericRecord) decoded).get("sku").toString()).isEqualTo("SKU-1");
    }

    @Test
    @DisplayName(
            "GIVEN SINGLE_OBJECT format WHEN encode called SHOULD start with C3 01 and carry the schema's little-endian fingerprint")
    void given_singleObjectFormat_when_encode_should_startWithMarkerAndFingerprint() {
        // Arrange
        GenericRecord record = minimalItem(itemSchema);

        // Act
        byte[] bytes = AvroFraming.encode(record, itemSchema, AvroWireFormat.SINGLE_OBJECT);

        // Assert
        assertThat(AvroFraming.isSingleObject(bytes)).isTrue();
        assertThat(bytes[0]).isEqualTo((byte) 0xC3);
        assertThat(bytes[1]).isEqualTo((byte) 0x01);
        OptionalLong fp = AvroFraming.fingerprintOf(bytes);
        assertThat(fp).isPresent();
        assertThat(fp.getAsLong()).isEqualTo(AvroSchemaRegistry.fingerprint(itemSchema));
    }

    @Test
    @DisplayName(
            "GIVEN single-object bytes whose fingerprint matches the reader WHEN decoded SHOULD not need the writer lookup")
    void given_matchingFingerprint_when_decoded_should_notNeedWriterLookup() {
        // Arrange
        GenericRecord record = minimalItem(itemSchema);
        byte[] bytes = AvroFraming.encode(record, itemSchema, AvroWireFormat.SINGLE_OBJECT);

        // Act
        Object decoded = AvroFraming.decode(bytes, itemSchema, fp -> {
            throw new AssertionError("writer lookup should not be needed when fingerprints match");
        });

        // Assert
        assertThat(((GenericRecord) decoded).get("sku").toString()).isEqualTo("SKU-1");
    }

    @Test
    @DisplayName(
            "GIVEN bytes written with an extra defaulted field WHEN decoded with an older reader schema SHOULD resolve through the lookup and drop the extra field")
    void given_writerWithExtraDefaultedField_when_decodedWithOlderReader_should_resolveAndDropExtraField() {
        // Arrange: writer schema has an extra "note" field with a default value.
        Schema writerSchema = SchemaBuilder.record("Item")
                .namespace("com.acme.inventory")
                .fields()
                .name("sku")
                .type()
                .stringType()
                .noDefault()
                .name("note")
                .type()
                .stringType()
                .stringDefault("n/a")
                .endRecord();
        Schema readerSchema = SchemaBuilder.record("Item")
                .namespace("com.acme.inventory")
                .fields()
                .name("sku")
                .type()
                .stringType()
                .noDefault()
                .endRecord();
        GenericData.Record writerRecord = new GenericData.Record(writerSchema);
        writerRecord.put("sku", "SKU-7");
        writerRecord.put("note", "hello");
        byte[] bytes = AvroFraming.encode(writerRecord, writerSchema, AvroWireFormat.SINGLE_OBJECT);

        // Act
        Object decoded = AvroFraming.decode(bytes, readerSchema, fp -> Optional.of(writerSchema));

        // Assert
        GenericRecord result = (GenericRecord) decoded;
        assertThat(result.get("sku").toString()).isEqualTo("SKU-7");
        assertThat(readerSchema.getField("note")).isNull();
    }

    @Test
    @DisplayName(
            "GIVEN single-object bytes with an unknown fingerprint WHEN decoded and lookup returns empty SHOULD throw IllegalArgumentException with the hex fingerprint")
    void given_unknownFingerprint_when_decoded_should_throwIllegalArgumentWithHexFingerprint() {
        // Arrange
        GenericRecord record = minimalItem(itemSchema);
        byte[] bytes = AvroFraming.encode(record, itemSchema, AvroWireFormat.SINGLE_OBJECT);
        // Corrupt the fingerprint bytes so it no longer matches the reader's own.
        bytes[2] = (byte) (bytes[2] ^ 0xFF);

        // Act & Assert
        assertThatThrownBy(() -> AvroFraming.decode(bytes, itemSchema, fp -> Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not a known schema")
                .hasMessageMatching(".*[0-9a-f]{16}.*");
    }

    @Test
    @DisplayName("GIVEN garbage bytes that are not valid Avro WHEN decoded SHOULD throw IllegalArgumentException")
    void given_garbageBytes_when_decoded_should_throwIllegalArgumentException() {
        // Arrange
        byte[] garbage = {0x00, (byte) 0xFF, 0x10, 0x20, 0x30};

        // Act & Assert
        assertThatThrownBy(() -> AvroFraming.decode(garbage, itemSchema, fp -> Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName(
            "GIVEN a datum that cannot be written by the given schema WHEN encode called SHOULD throw IllegalArgumentException")
    void given_datumNotMatchingSchema_when_encode_should_throwIllegalArgumentException() {
        // Arrange: a plain String is not a valid RECORD datum for itemSchema.
        Object notARecord = "not-a-record";

        // Act & Assert
        assertThatThrownBy(() -> AvroFraming.encode(notARecord, itemSchema, AvroWireFormat.BINARY))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
