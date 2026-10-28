package dev.appiaworks.shiguchi.avro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.config.ServiceDefinition.AvroConfig;
import dev.appiaworks.shiguchi.config.ServiceDefinition.AvroSchemaSource;
import dev.appiaworks.shiguchi.config.ServiceDefinition.AvroWireFormat;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * BDD-style tests for AvroUpstreamCodec.
 *
 * <p>Tests verify that:
 *
 * <ul>
 *   <li>{@link AvroUpstreamCodec#supports} recognises {@code avro/binary} and its aliases only
 *   <li>{@link AvroUpstreamCodec#registerService} binds components carrying {@code x-avro-schema}
 *       (including cross-component record references) and, with {@code schema-files}, binds by
 *       component name or {@code x-avro-record}
 *   <li>{@link AvroUpstreamCodec#decode}/{@link AvroUpstreamCodec#encode} round-trip a value and
 *       respect the service's configured wire format
 *   <li>An unbound schema name fails clearly
 * </ul>
 */
@DisplayName("AvroUpstreamCodec service tests")
class AvroUpstreamCodecTest {

    private static final String SERVICE = "inventory";

    private AvroUpstreamCodec codec;
    private ObjectMapper json;

    @BeforeEach
    void setUp() {
        codec = new AvroUpstreamCodec(new AvroSchemaRegistry(), new DefaultResourceLoader());
        json = new ObjectMapper();
    }

    private Map<String, Map<String, Object>> componentsFixture() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/avro/components-with-x-avro-schema.json")) {
            return json.readValue(in, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        }
    }

    @Test
    @DisplayName(
            "GIVEN avro/binary and its vendor aliases WHEN supports called SHOULD return true, and false for application/json")
    void given_avroBinaryAndAliases_when_supports_should_returnTrueForAvroAndFalseForJson() {
        // Act & Assert
        assertThat(codec.supports("avro/binary")).isTrue();
        assertThat(codec.supports("application/vnd.apache.avro+binary")).isTrue();
        assertThat(codec.supports("application/avro")).isTrue();
        assertThat(codec.supports("application/json")).isFalse();
    }

    @Test
    @DisplayName("GIVEN a component with x-avro-schema WHEN registerService called SHOULD bind that component")
    void given_componentWithInlineSchema_when_registerService_should_bindComponent() throws Exception {
        // Arrange
        Map<String, Map<String, Object>> components = componentsFixture();
        ServiceDefinition def = new ServiceDefinition();

        // Act
        Set<String> bound = codec.registerService(SERVICE, def, components);

        // Assert
        assertThat(bound).contains("Item", "Order");
        assertThat(codec.canServe(SERVICE, "Item")).isTrue();
    }

    @Test
    @DisplayName(
            "GIVEN a component whose x-avro-schema references a record declared in another component WHEN registerService called SHOULD still bind it")
    void given_componentReferencingAnotherComponentsRecord_when_registerService_should_bindIt() throws Exception {
        // Arrange: the "Order" component's x-avro-schema array field references com.acme.inventory.Item,
        // which is declared by the "Item" component's own x-avro-schema.
        Map<String, Map<String, Object>> components = componentsFixture();
        ServiceDefinition def = new ServiceDefinition();

        // Act
        Set<String> bound = codec.registerService(SERVICE, def, components);

        // Assert
        assertThat(bound).contains("Order");
        assertThat(codec.canServe(SERVICE, "Order")).isTrue();
    }

    @Test
    @DisplayName(
            "GIVEN a component with x-avro-record naming a record from schema-files WHEN registerService with FILES source called SHOULD bind by the extension")
    void given_componentWithRecordExtension_when_registerServiceWithFilesSource_should_bindByExtension()
            throws Exception {
        // Arrange: "Product" carries x-avro-record: com.acme.inventory.Item, resolved against item.avsc.
        Map<String, Map<String, Object>> components = componentsFixture();
        AvroConfig avroConfig = new AvroConfig();
        avroConfig.setSchemaSource(AvroSchemaSource.FILES);
        avroConfig.setSchemaFiles(List.of("classpath:avro/item.avsc"));
        ServiceDefinition def = new ServiceDefinition();
        def.setAvro(avroConfig);

        // Act
        Set<String> bound = codec.registerService(SERVICE, def, components);

        // Assert
        assertThat(bound).contains("Product");
        assertThat(codec.canServe(SERVICE, "Product")).isTrue();
    }

    @Test
    @DisplayName(
            "GIVEN schema-files with FILES source WHEN a component's name matches the record's simple name SHOULD bind by name")
    void given_filesSource_when_componentNameMatchesRecordSimpleName_should_bindByName() throws Exception {
        // Arrange: components-with-x-avro-schema.json's "Item" component also carries an x-avro-schema,
        // but with FILES source only schema-files are consulted, and "Item" matches the file record's
        // simple name.
        AvroConfig avroConfig = new AvroConfig();
        avroConfig.setSchemaSource(AvroSchemaSource.FILES);
        avroConfig.setSchemaFiles(List.of("classpath:avro/item.avsc"));
        ServiceDefinition def = new ServiceDefinition();
        def.setAvro(avroConfig);
        Map<String, Map<String, Object>> components = Map.of("Item", Map.of());

        // Act
        Set<String> bound = codec.registerService(SERVICE, def, components);

        // Assert
        assertThat(bound).contains("Item");
        assertThat(codec.canServe(SERVICE, "Item")).isTrue();
    }

    @Test
    @DisplayName(
            "GIVEN a bound Item schema and matching bytes WHEN decode called with array=false SHOULD return a Map with the datum's values")
    void given_boundSchemaAndBytes_when_decodeCalled_should_returnMapWithValues() throws Exception {
        // Arrange
        Map<String, Map<String, Object>> components = componentsFixture();
        ServiceDefinition def = new ServiceDefinition();
        codec.registerService(SERVICE, def, components);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sku", "SKU-1");
        body.put("qty", 5L);
        byte[] encoded = codec.encode(SERVICE, "Item", body);

        // Act
        Object decoded = codec.decode(SERVICE, "Item", false, encoded);

        // Assert
        assertThat(decoded).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) decoded;
        assertThat(map.get("sku")).isEqualTo("SKU-1");
        assertThat(map.get("qty")).isEqualTo(5L);
    }

    @Test
    @DisplayName(
            "GIVEN a bound Item schema and an array of Item bytes WHEN decode called with array=true SHOULD return a List of Maps")
    void given_boundSchemaAndArrayBytes_when_decodeCalledWithArrayTrue_should_returnListOfMaps() throws Exception {
        // Arrange
        Map<String, Map<String, Object>> components = componentsFixture();
        ServiceDefinition def = new ServiceDefinition();
        codec.registerService(SERVICE, def, components);

        org.apache.avro.Schema itemSchema =
                codec.registry().find(SERVICE, "Item").orElseThrow();
        org.apache.avro.Schema arraySchema = org.apache.avro.Schema.createArray(itemSchema);
        Map<String, Object> item1 = Map.of("sku", "SKU-1", "qty", 1L);
        Map<String, Object> item2 = Map.of("sku", "SKU-2", "qty", 2L);
        Object arrayDatum = AvroMapConverter.fromJava(List.of(item1, item2), arraySchema);
        byte[] encoded = AvroFraming.encode(arrayDatum, arraySchema, AvroWireFormat.BINARY);

        // Act
        Object decoded = codec.decode(SERVICE, "Item", true, encoded);

        // Assert
        assertThat(decoded).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) decoded;
        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("sku")).isEqualTo("SKU-1");
        assertThat(list.get(1).get("sku")).isEqualTo("SKU-2");
    }

    @Test
    @DisplayName(
            "GIVEN a service configured with SINGLE_OBJECT wire format WHEN encode called SHOULD produce bytes starting with C3 01")
    void given_singleObjectWireFormat_when_encode_should_produceBytesStartingWithMarker() throws Exception {
        // Arrange
        Map<String, Map<String, Object>> components = componentsFixture();
        AvroConfig avroConfig = new AvroConfig();
        avroConfig.setWireFormat(AvroWireFormat.SINGLE_OBJECT);
        ServiceDefinition def = new ServiceDefinition();
        def.setAvro(avroConfig);
        codec.registerService(SERVICE, def, components);

        Map<String, Object> body = Map.of("sku", "SKU-1", "qty", 5L);

        // Act
        byte[] encoded = codec.encode(SERVICE, "Item", body);

        // Assert
        assertThat(encoded[0]).isEqualTo((byte) 0xC3);
        assertThat(encoded[1]).isEqualTo((byte) 0x01);
        // and it still decodes back through the same codec
        Object decoded = codec.decode(SERVICE, "Item", false, encoded);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) decoded;
        assertThat(map.get("sku")).isEqualTo("SKU-1");
    }

    @Test
    @DisplayName(
            "GIVEN a schema name with no bound schema WHEN decode called SHOULD throw IllegalArgumentException naming the schema and service")
    void given_unboundSchemaName_when_decode_should_throwIllegalArgumentException() {
        // Arrange
        ServiceDefinition def = new ServiceDefinition();
        codec.registerService(SERVICE, def, Map.of());

        // Act & Assert
        assertThatThrownBy(() -> codec.decode(SERVICE, "Nonexistent", false, new byte[] {0}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nonexistent")
                .hasMessageContaining(SERVICE);
    }

    @Test
    @DisplayName("GIVEN a bound schema WHEN canServe called for it SHOULD return true, and false for an unbound name")
    void given_boundAndUnboundSchemaNames_when_canServe_should_reflectBoundState() throws Exception {
        // Arrange
        Map<String, Map<String, Object>> components = componentsFixture();
        ServiceDefinition def = new ServiceDefinition();
        codec.registerService(SERVICE, def, components);

        // Act & Assert
        assertThat(codec.canServe(SERVICE, "Item")).isTrue();
        assertThat(codec.canServe(SERVICE, "NoSuchSchema")).isFalse();
    }
}
