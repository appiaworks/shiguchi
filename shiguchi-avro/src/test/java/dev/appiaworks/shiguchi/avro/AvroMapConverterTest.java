package dev.appiaworks.shiguchi.avro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD-style tests for AvroMapConverter.
 *
 * <p>Tests verify that:
 *
 * <ul>
 *   <li>Decoded records become ordered {@code Map}s with the gateway's plain-Java shapes: bytes
 *       decimals and temporal logical types as text, nested records and collections as {@code
 *       Map}/{@code List}, null fields omitted
 *   <li>Encoding accepts the lenient inputs the REST/GraphQL surfaces hand it (numeric strings,
 *       ISO-8601 or epoch temporals, base64) and round-trips through decode
 *   <li>Missing required fields, unknown enum symbols and union branch selection fail or resolve
 *       the way the REST surface's error handling expects
 * </ul>
 */
@DisplayName("AvroMapConverter conversion tests")
class AvroMapConverterTest {

    private Schema itemSchema;

    @BeforeEach
    void loadSchema() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/avro/item.avsc")) {
            itemSchema = new Schema.Parser().parse(in);
        }
    }

    /** A fully-populated Item record built directly through the Avro API (decode side fixture). */
    private GenericRecord fullItemRecord() {
        GenericData.Record record = new GenericData.Record(itemSchema);
        record.put("sku", "SKU-1");
        record.put("name", "Widget");
        record.put("qty", 7L);
        BigDecimal price = new BigDecimal("12.50");
        record.put("price", ByteBuffer.wrap(price.unscaledValue().toByteArray()));
        record.put("tags", List.of("a", "b"));
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("color", "red");
        record.put("attrs", attrs);
        Schema statusSchema = itemSchema.getField("status").schema();
        record.put("status", new GenericData.EnumSymbol(statusSchema, "ACTIVE"));
        record.put("updatedAt", 1_700_000_000_000L);
        record.put("created", 19_723); // epoch day
        Schema categorySchema = unwrapNullable(itemSchema.getField("category").schema());
        GenericData.Record category = new GenericData.Record(categorySchema);
        category.put("id", 1);
        category.put("name", "Widgets");
        record.put("category", category);
        Schema fixedSchema = itemSchema.getField("fixedId").schema();
        record.put("fixedId", new GenericData.Fixed(fixedSchema, new byte[] {1, 2, 3, 4}));
        return record;
    }

    private static Schema unwrapNullable(Schema schema) {
        if (schema.getType() != Schema.Type.UNION) return schema;
        for (Schema b : schema.getTypes()) if (b.getType() != Schema.Type.NULL) return b;
        throw new IllegalStateException("no non-null branch");
    }

    @Test
    @DisplayName(
            "GIVEN a fully-populated Item record WHEN toJava called SHOULD produce an ordered Map with converted scalars")
    void given_fullItemRecord_when_toJava_should_produceOrderedMapWithConvertedScalars() {
        // Arrange
        GenericRecord record = fullItemRecord();

        // Act
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) AvroMapConverter.toJava(record, itemSchema);

        // Assert
        assertThat(result).isInstanceOf(LinkedHashMap.class);
        assertThat(result.keySet())
                .containsExactly(
                        "sku",
                        "name",
                        "qty",
                        "price",
                        "tags",
                        "attrs",
                        "status",
                        "updatedAt",
                        "created",
                        "category",
                        "fixedId");
        assertThat(result.get("sku")).isEqualTo("SKU-1");
        assertThat(result.get("qty")).isEqualTo(7L);
        assertThat(result.get("price")).isEqualTo("12.50");
        assertThat(result.get("status")).isEqualTo("ACTIVE");
        assertThat(result.get("updatedAt"))
                .isEqualTo(Instant.ofEpochMilli(1_700_000_000_000L).toString());
        assertThat(result.get("created")).isEqualTo("2024-01-01");
        assertThat(result.get("fixedId")).isEqualTo(Base64.getEncoder().encodeToString(new byte[] {1, 2, 3, 4}));
    }

    @Test
    @DisplayName(
            "GIVEN a record with nested category, array tags and map attrs WHEN toJava called SHOULD convert them to Map/List/Map")
    void given_nestedArrayAndMapFields_when_toJava_should_convertToJavaCollections() {
        // Arrange
        GenericRecord record = fullItemRecord();

        // Act
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) AvroMapConverter.toJava(record, itemSchema);

        // Assert
        assertThat(result.get("tags")).isInstanceOf(List.class).isEqualTo(List.of("a", "b"));
        assertThat(result.get("attrs")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) result.get("attrs");
        assertThat(attrs).containsEntry("color", "red");
        assertThat(result.get("category")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> category = (Map<String, Object>) result.get("category");
        assertThat(category).containsEntry("id", 1).containsEntry("name", "Widgets");
    }

    @Test
    @DisplayName("GIVEN a record with a null nullable field WHEN toJava called SHOULD omit that key from the Map")
    void given_nullNullableField_when_toJava_should_omitKey() {
        // Arrange
        GenericRecord record = fullItemRecord();
        record.put("name", null);
        record.put("category", null);

        // Act
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) AvroMapConverter.toJava(record, itemSchema);

        // Assert
        assertThat(result).doesNotContainKey("name");
        assertThat(result).doesNotContainKey("category");
    }

    @Test
    @DisplayName(
            "GIVEN a Map with lenient string inputs WHEN fromJava called SHOULD build a record that decodes back to the same values")
    void given_lenientMapInputs_when_fromJava_should_roundTripThroughToJava() {
        // Arrange
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("sku", "SKU-9");
        input.put("qty", "7"); // numeric string
        input.put("price", "12.50"); // decimal as text
        input.put("tags", List.of("x"));
        input.put("attrs", Map.of("k", "v"));
        input.put("status", "RETIRED"); // enum as string
        input.put("updatedAt", "2023-11-14T22:13:20Z"); // ISO instant
        input.put("created", "2024-01-01"); // ISO date
        input.put("fixedId", Base64.getEncoder().encodeToString(new byte[] {9, 9, 9, 9}));

        // Act
        Object datum = AvroMapConverter.fromJava(input, itemSchema);
        @SuppressWarnings("unchecked")
        Map<String, Object> roundTripped = (Map<String, Object>) AvroMapConverter.toJava(datum, itemSchema);

        // Assert
        assertThat(roundTripped.get("sku")).isEqualTo("SKU-9");
        assertThat(roundTripped.get("qty")).isEqualTo(7L);
        assertThat(roundTripped.get("price")).isEqualTo("12.50");
        assertThat(roundTripped.get("status")).isEqualTo("RETIRED");
        assertThat(roundTripped.get("updatedAt")).isEqualTo("2023-11-14T22:13:20Z");
        assertThat(roundTripped.get("created")).isEqualTo("2024-01-01");
        assertThat(roundTripped.get("fixedId")).isEqualTo(Base64.getEncoder().encodeToString(new byte[] {9, 9, 9, 9}));
    }

    @Test
    @DisplayName(
            "GIVEN a Map with epoch-millis updatedAt and Double price WHEN fromJava called SHOULD accept the alternate numeric shapes")
    void given_epochMillisAndNumericPrice_when_fromJava_should_acceptAlternateShapes() {
        // Arrange
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("sku", "SKU-2");
        input.put("qty", 3L);
        input.put("price", new BigDecimal("5.00"));
        input.put("tags", List.of());
        input.put("attrs", Map.of());
        input.put("status", "ACTIVE");
        input.put("updatedAt", 1_700_000_000_000L); // raw epoch millis
        input.put("created", 19_723);
        input.put("fixedId", Base64.getEncoder().encodeToString(new byte[] {0, 0, 0, 1}));

        // Act
        Object datum = AvroMapConverter.fromJava(input, itemSchema);
        @SuppressWarnings("unchecked")
        Map<String, Object> roundTripped = (Map<String, Object>) AvroMapConverter.toJava(datum, itemSchema);

        // Assert
        assertThat(roundTripped.get("updatedAt"))
                .isEqualTo(Instant.ofEpochMilli(1_700_000_000_000L).toString());
        assertThat(roundTripped.get("price")).isEqualTo("5.00");
    }

    @Test
    @DisplayName(
            "GIVEN a Map missing the required sku field WHEN fromJava called SHOULD throw IllegalArgumentException naming the field path")
    void given_missingRequiredField_when_fromJava_should_throwIllegalArgumentWithFieldPath() {
        // Arrange
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("qty", 1L);
        input.put("price", "1.00");
        input.put("tags", List.of());
        input.put("attrs", Map.of());
        input.put("status", "ACTIVE");
        input.put("updatedAt", "2023-11-14T22:13:20Z");
        input.put("created", "2024-01-01");
        input.put("fixedId", Base64.getEncoder().encodeToString(new byte[] {0, 0, 0, 0}));

        // Act & Assert
        assertThatThrownBy(() -> AvroMapConverter.fromJava(input, itemSchema))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sku")
                .hasMessageContaining("required");
    }

    @Test
    @DisplayName("GIVEN a Map with an unknown enum symbol WHEN fromJava called SHOULD throw IllegalArgumentException")
    void given_unknownEnumSymbol_when_fromJava_should_throwIllegalArgumentException() {
        // Arrange
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("sku", "SKU-3");
        input.put("qty", 1L);
        input.put("price", "1.00");
        input.put("tags", List.of());
        input.put("attrs", Map.of());
        input.put("status", "DISCONTINUED"); // not a symbol of ItemStatus
        input.put("updatedAt", "2023-11-14T22:13:20Z");
        input.put("created", "2024-01-01");
        input.put("fixedId", Base64.getEncoder().encodeToString(new byte[] {0, 0, 0, 0}));

        // Act & Assert
        assertThatThrownBy(() -> AvroMapConverter.fromJava(input, itemSchema))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DISCONTINUED");
    }

    @Test
    @DisplayName("GIVEN a Map absent the nullable name field WHEN fromJava called SHOULD apply the schema default")
    void given_absentNullableFieldWithDefault_when_fromJava_should_applySchemaDefault() {
        // Arrange
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("sku", "SKU-4");
        // "name" intentionally absent; schema default is null
        input.put("qty", 1L);
        input.put("price", "1.00");
        input.put("tags", List.of());
        input.put("attrs", Map.of());
        input.put("status", "ACTIVE");
        input.put("updatedAt", "2023-11-14T22:13:20Z");
        input.put("created", "2024-01-01");
        input.put("fixedId", Base64.getEncoder().encodeToString(new byte[] {0, 0, 0, 0}));

        // Act
        Object datum = AvroMapConverter.fromJava(input, itemSchema);
        @SuppressWarnings("unchecked")
        Map<String, Object> roundTripped = (Map<String, Object>) AvroMapConverter.toJava(datum, itemSchema);

        // Assert: default value null is omitted from the decoded Map, just like an unset field
        assertThat(roundTripped).doesNotContainKey("name");
    }

    @Test
    @DisplayName(
            "GIVEN a union of null, long and string WHEN fromJava called SHOULD pick the long branch for a Long and the string branch for text")
    void given_unionOfLongAndString_when_fromJava_should_pickBranchByJavaType() {
        // Arrange: long listed before string, so a Number (plausible for both) resolves to long first.
        Schema union = Schema.createUnion(
                Schema.create(Schema.Type.NULL), Schema.create(Schema.Type.LONG), Schema.create(Schema.Type.STRING));

        // Act
        Object fromLong = AvroMapConverter.fromJava(42L, union);
        Object fromString = AvroMapConverter.fromJava("hello", union);

        // Assert
        assertThat(fromLong).isInstanceOf(Long.class).isEqualTo(42L);
        assertThat(fromString).isInstanceOf(String.class).isEqualTo("hello");
        assertThat(AvroMapConverter.toJava(fromLong, union)).isEqualTo(42L);
        assertThat(AvroMapConverter.toJava(fromString, union)).isEqualTo("hello");
    }
}
