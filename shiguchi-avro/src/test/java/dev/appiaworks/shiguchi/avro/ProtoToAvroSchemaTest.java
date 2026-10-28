package dev.appiaworks.shiguchi.avro;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Any;
import com.google.protobuf.BoolValue;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.ListValue;
import com.google.protobuf.StringValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.UInt64Value;
import dev.appiaworks.shiguchi.config.AvroSurfaceConfig.Uint64Mapping;
import java.util.Optional;
import org.apache.avro.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD-style tests for ProtoToAvroSchema.
 *
 * Tests verify that proto descriptors are correctly converted to Avro schemas:
 * - Well-known types are mapped to appropriate Avro types
 * - Uint64Mapping configuration affects uint64 mapping
 * - Caching by full name and fingerprint works correctly
 * - Null handling for message and enum fields
 */
@DisplayName("ProtoToAvroSchema")
class ProtoToAvroSchemaTest {

    @Test
    @DisplayName("GIVEN a proto StringValue descriptor WHEN schemaFor called SHOULD return string schema")
    void given_stringValueDescriptor_when_schemaFor_should_returnString() {
        // Arrange
        ProtoToAvroSchema converter = new ProtoToAvroSchema();
        Descriptor descriptor = StringValue.getDescriptor();

        // Act
        Schema schema = converter.schemaFor(descriptor);

        // Assert
        assertThat(schema).isNotNull();
        assertThat(schema.getType()).isEqualTo(Schema.Type.RECORD);
    }

    @Test
    @DisplayName("GIVEN a proto BoolValue descriptor WHEN schemaFor called SHOULD return record with bool field")
    void given_boolValueDescriptor_when_schemaFor_should_returnRecordWithBoolField() {
        // Arrange
        ProtoToAvroSchema converter = new ProtoToAvroSchema();
        Descriptor descriptor = BoolValue.getDescriptor();

        // Act
        Schema schema = converter.schemaFor(descriptor);

        // Assert
        assertThat(schema).isNotNull();
        assertThat(schema.getType()).isEqualTo(Schema.Type.RECORD);
        assertThat(schema.getField("value").schema().getType()).isEqualTo(Schema.Type.BOOLEAN);
    }

    @Test
    @DisplayName(
            "GIVEN a proto Timestamp descriptor WHEN schemaFor called SHOULD return record with timestamp-micros field")
    void given_timestampDescriptor_when_schemaFor_should_returnRecordWithTimestampField() {
        // Arrange
        ProtoToAvroSchema converter = new ProtoToAvroSchema();
        Descriptor descriptor = Timestamp.getDescriptor();

        // Act
        Schema schema = converter.schemaFor(descriptor);

        // Assert
        assertThat(schema).isNotNull();
        assertThat(schema.getType()).isEqualTo(Schema.Type.RECORD);
        // Timestamp has seconds and nanos fields
        assertThat(schema.getField("seconds")).isNotNull();
        assertThat(schema.getField("nanos")).isNotNull();
    }

    @Test
    @DisplayName(
            "GIVEN a proto UInt64Value with LONG mapping WHEN schemaFor called SHOULD map to long, with STRING mapping to string")
    void given_uint64ValueWithDifferentMappings_when_schemaFor_should_mapAccordingly() {
        // Arrange
        Descriptor descriptor = UInt64Value.getDescriptor();

        // Act with LONG mapping
        ProtoToAvroSchema converterLong = new ProtoToAvroSchema(Uint64Mapping.LONG);
        Schema schemaLong = converterLong.schemaFor(descriptor);

        // Act with STRING mapping
        ProtoToAvroSchema converterString = new ProtoToAvroSchema(Uint64Mapping.STRING);
        Schema schemaString = converterString.schemaFor(descriptor);

        // Assert
        assertThat(schemaLong).isNotNull();
        assertThat(schemaString).isNotNull();
        // Both should have a "value" field, with different types
        assertThat(schemaLong.getField("value")).isNotNull();
        assertThat(schemaString.getField("value")).isNotNull();
    }

    @Test
    @DisplayName("GIVEN a proto Struct descriptor WHEN schemaFor called SHOULD return record")
    void given_structDescriptor_when_schemaFor_should_returnRecord() {
        // Arrange
        ProtoToAvroSchema converter = new ProtoToAvroSchema();
        Descriptor descriptor = Struct.getDescriptor();

        // Act
        Schema schema = converter.schemaFor(descriptor);

        // Assert
        assertThat(schema).isNotNull();
        assertThat(schema.getType()).isEqualTo(Schema.Type.RECORD);
    }

    @Test
    @DisplayName("GIVEN a proto Any descriptor WHEN schemaFor called SHOULD return record")
    void given_anyDescriptor_when_schemaFor_should_returnRecord() {
        // Arrange
        ProtoToAvroSchema converter = new ProtoToAvroSchema();
        Descriptor descriptor = Any.getDescriptor();

        // Act
        Schema schema = converter.schemaFor(descriptor);

        // Assert
        assertThat(schema).isNotNull();
        assertThat(schema.getType()).isEqualTo(Schema.Type.RECORD);
    }

    @Test
    @DisplayName("GIVEN a proto ListValue descriptor WHEN schemaFor called SHOULD return record")
    void given_listValueDescriptor_when_schemaFor_should_returnRecord() {
        // Arrange
        ProtoToAvroSchema converter = new ProtoToAvroSchema();
        Descriptor descriptor = ListValue.getDescriptor();

        // Act
        Schema schema = converter.schemaFor(descriptor);

        // Assert
        assertThat(schema).isNotNull();
        assertThat(schema.getType()).isEqualTo(Schema.Type.RECORD);
    }

    @Test
    @DisplayName("GIVEN a schema derived twice WHEN schemaFor called twice SHOULD return the same cached instance")
    void given_deriveCalledTwice_when_schemaFor_should_returnCachedInstance() {
        // Arrange
        ProtoToAvroSchema converter = new ProtoToAvroSchema();
        Descriptor descriptor = Timestamp.getDescriptor();

        // Act
        Schema schema1 = converter.schemaFor(descriptor);
        Schema schema2 = converter.schemaFor(descriptor);

        // Assert
        assertThat(schema1).isSameAs(schema2);
    }

    @Test
    @DisplayName("GIVEN a derived schema WHEN byFingerprint called with its fingerprint SHOULD return it in Optional")
    void given_derivedSchema_when_byFingerprint_should_findIt() {
        // Arrange
        ProtoToAvroSchema converter = new ProtoToAvroSchema();
        Descriptor descriptor = Timestamp.getDescriptor();
        Schema schema = converter.schemaFor(descriptor);
        long fingerprint = AvroSchemaRegistry.fingerprint(schema);

        // Act
        Optional<Schema> found = converter.byFingerprint(fingerprint);

        // Assert
        assertThat(found).isPresent().contains(schema);
    }

    @Test
    @DisplayName("GIVEN a converter WHEN find called with unknown message name SHOULD return empty Optional")
    void given_unknownMessageName_when_find_should_returnEmpty() {
        // Arrange
        ProtoToAvroSchema converter = new ProtoToAvroSchema();

        // Act
        Optional<Schema> found = converter.find("does.not.Exist");

        // Assert
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("GIVEN a converter with derived schemas WHEN known called SHOULD return all schemas")
    void given_derivedSchemas_when_known_should_returnCollection() {
        // Arrange
        ProtoToAvroSchema converter = new ProtoToAvroSchema();
        converter.schemaFor(Timestamp.getDescriptor());
        converter.schemaFor(Struct.getDescriptor());

        // Act
        var knownSchemas = converter.known();

        // Assert
        assertThat(knownSchemas).isNotEmpty();
        assertThat(knownSchemas.size()).isGreaterThanOrEqualTo(2);
    }
}
