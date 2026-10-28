package dev.appiaworks.shiguchi.avro;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD-style tests for AvroSchemaRegistry.
 *
 * <p>Tests verify that:
 *
 * <ul>
 *   <li>A record registered under a component name is also reachable by its Avro full name,
 *       simple name, and parsing fingerprint
 *   <li>Re-registering a service replaces its previously bound names entirely
 *   <li>{@link AvroSchemaRegistry#remove} forgets a service's schemas
 * </ul>
 */
@DisplayName("AvroSchemaRegistry lookup tests")
class AvroSchemaRegistryTest {

    private static final String SERVICE = "inventory";

    private AvroSchemaRegistry registry;
    private Schema itemSchema;

    @BeforeEach
    void setUp() {
        registry = new AvroSchemaRegistry();
        itemSchema = SchemaBuilder.record("Item")
                .namespace("com.acme.inventory")
                .fields()
                .name("sku")
                .type()
                .stringType()
                .noDefault()
                .endRecord();
    }

    @Test
    @DisplayName("GIVEN a record registered under a component name WHEN found by that name SHOULD return the schema")
    void given_recordRegisteredUnderComponentName_when_foundByComponentName_should_returnSchema() {
        // Arrange
        registry.register(SERVICE, Map.of("ItemComponent", itemSchema));

        // Act
        var found = registry.find(SERVICE, "ItemComponent");

        // Assert
        assertThat(found).contains(itemSchema);
    }

    @Test
    @DisplayName(
            "GIVEN a record registered under a component name WHEN found by its Avro full name SHOULD return the same schema")
    void given_recordRegisteredUnderComponentName_when_foundByFullName_should_returnSameSchema() {
        // Arrange
        registry.register(SERVICE, Map.of("ItemComponent", itemSchema));

        // Act
        var found = registry.find(SERVICE, "com.acme.inventory.Item");

        // Assert
        assertThat(found).contains(itemSchema);
    }

    @Test
    @DisplayName(
            "GIVEN a record registered under a component name WHEN found by its simple name SHOULD return the same schema")
    void given_recordRegisteredUnderComponentName_when_foundBySimpleName_should_returnSameSchema() {
        // Arrange
        registry.register(SERVICE, Map.of("ItemComponent", itemSchema));

        // Act
        var found = registry.find(SERVICE, "Item");

        // Assert
        assertThat(found).contains(itemSchema);
    }

    @Test
    @DisplayName("GIVEN a registered record WHEN found by its fingerprint SHOULD return the same schema")
    void given_registeredRecord_when_foundByFingerprint_should_returnSameSchema() {
        // Arrange
        registry.register(SERVICE, Map.of("ItemComponent", itemSchema));
        long fp = AvroSchemaRegistry.fingerprint(itemSchema);

        // Act
        var found = registry.findByFingerprint(SERVICE, fp);

        // Assert
        assertThat(found).contains(itemSchema);
    }

    @Test
    @DisplayName("GIVEN a registered record WHEN names listed SHOULD include the component, full, and simple names")
    void given_registeredRecord_when_namesListed_should_includeAllThreeNames() {
        // Arrange
        registry.register(SERVICE, Map.of("ItemComponent", itemSchema));

        // Act
        var names = registry.names(SERVICE);

        // Assert
        assertThat(names).contains("ItemComponent", "com.acme.inventory.Item", "Item");
    }

    @Test
    @DisplayName(
            "GIVEN a service registered twice WHEN the second registration omits the first's component SHOULD no longer find it")
    void given_serviceReRegistered_when_secondRegistrationOmitsFirstComponent_should_noLongerFindOldComponent() {
        // Arrange
        registry.register(SERVICE, Map.of("ItemComponent", itemSchema));
        Schema otherSchema = SchemaBuilder.record("Order")
                .namespace("com.acme.orders")
                .fields()
                .name("orderId")
                .type()
                .stringType()
                .noDefault()
                .endRecord();

        // Act
        registry.register(SERVICE, Map.of("OrderComponent", otherSchema));

        // Assert
        assertThat(registry.find(SERVICE, "ItemComponent")).isEmpty();
        assertThat(registry.find(SERVICE, "OrderComponent")).contains(otherSchema);
    }

    @Test
    @DisplayName(
            "GIVEN a service registered twice with the same component name WHEN found SHOULD return the newer schema")
    void given_serviceReRegisteredWithSameComponentName_when_found_should_returnNewerSchema() {
        // Arrange
        registry.register(SERVICE, Map.of("Component", itemSchema));
        Schema replacement = SchemaBuilder.record("ItemV2")
                .namespace("com.acme.inventory")
                .fields()
                .name("sku")
                .type()
                .stringType()
                .noDefault()
                .name("qty")
                .type()
                .longType()
                .noDefault()
                .endRecord();

        // Act
        registry.register(SERVICE, Map.of("Component", replacement));

        // Assert
        assertThat(registry.find(SERVICE, "Component")).contains(replacement);
        assertThat(registry.find(SERVICE, "Item")).isEmpty();
        assertThat(registry.find(SERVICE, "ItemV2")).contains(replacement);
    }

    @Test
    @DisplayName("GIVEN a registered service WHEN removed SHOULD forget every name it bound")
    void given_registeredService_when_removed_should_forgetAllNames() {
        // Arrange
        registry.register(SERVICE, Map.of("ItemComponent", itemSchema));

        // Act
        registry.remove(SERVICE);

        // Assert
        assertThat(registry.find(SERVICE, "ItemComponent")).isEmpty();
        assertThat(registry.find(SERVICE, "Item")).isEmpty();
        assertThat(registry.names(SERVICE)).isEmpty();
    }

    @Test
    @DisplayName("GIVEN an unknown service WHEN find called SHOULD return empty rather than throwing")
    void given_unknownService_when_find_should_returnEmpty() {
        // Act & Assert
        assertThat(registry.find("unknown-service", "Item")).isEmpty();
        assertThat(registry.findByFingerprint("unknown-service", 123L)).isEmpty();
    }

    @Test
    @DisplayName("GIVEN two structurally different schemas WHEN fingerprint computed SHOULD produce different values")
    void given_twoDifferentSchemas_when_fingerprintComputed_should_produceDifferentValues() {
        // Arrange
        Schema other = SchemaBuilder.record("Different")
                .fields()
                .name("x")
                .type()
                .intType()
                .noDefault()
                .endRecord();
        Map<String, Schema> both = new LinkedHashMap<>();
        both.put("ItemComponent", itemSchema);
        both.put("DifferentComponent", other);
        registry.register(SERVICE, both);

        // Act
        long itemFp = AvroSchemaRegistry.fingerprint(itemSchema);
        long otherFp = AvroSchemaRegistry.fingerprint(other);

        // Assert
        assertThat(itemFp).isNotEqualTo(otherFp);
        assertThat(registry.findByFingerprint(SERVICE, itemFp)).contains(itemSchema);
        assertThat(registry.findByFingerprint(SERVICE, otherFp)).contains(other);
    }
}
