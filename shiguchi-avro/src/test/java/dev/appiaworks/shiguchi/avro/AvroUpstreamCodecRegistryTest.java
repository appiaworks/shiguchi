package dev.appiaworks.shiguchi.avro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.config.ServiceDefinition.AvroConfig;
import dev.appiaworks.shiguchi.config.ServiceDefinition.AvroSubjectStrategy;
import dev.appiaworks.shiguchi.config.ServiceDefinition.AvroWireFormat;
import java.io.InputStream;
import java.time.Duration;
import java.util.Map;
import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * BDD-style tests for AvroUpstreamCodec's schema-registry integration (CONFLUENT wire format).
 *
 * <p>Tests verify that:
 *
 * <ul>
 *   <li>{@code registerService} registers every bound schema under a subject named by full
 *       record name or component name, and that {@code avro.subjects} overrides the strategy
 *   <li>{@code encode} frames bytes with the id the registry assigned, and {@code decode} of an
 *       evolved (older) writer version resolves through the registry's cached versions
 *   <li>Misconfiguration fails loudly: {@code CONFLUENT} without a registry URL, or a registry
 *       that rejects the service, both throw {@link IllegalStateException}
 *   <li>{@code unregisterService} drops the binding and {@code subjectFor} is correct standalone
 * </ul>
 */
@DisplayName("AvroUpstreamCodec schema-registry integration tests")
class AvroUpstreamCodecRegistryTest {

    private static final String SERVICE = "inventory";

    private StubSchemaRegistry stub;
    private AvroUpstreamCodec codec;
    private ObjectMapper json;

    @BeforeEach
    void setUp() {
        stub = new StubSchemaRegistry();
        codec = new AvroUpstreamCodec(new AvroSchemaRegistry(), new DefaultResourceLoader());
        json = new ObjectMapper();
    }

    @AfterEach
    void tearDown() {
        stub.dispose();
    }

    private Map<String, Map<String, Object>> componentsFixture() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/avro/components-with-x-avro-schema.json")) {
            return json.readValue(in, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        }
    }

    private ServiceDefinition definitionWithConfluent(AvroSubjectStrategy strategy) {
        AvroConfig cfg = new AvroConfig();
        cfg.setSchemaRegistryUrl(stub.url());
        cfg.setWireFormat(AvroWireFormat.CONFLUENT);
        if (strategy != null) cfg.setSubjectStrategy(strategy);
        ServiceDefinition def = new ServiceDefinition();
        def.setAvro(cfg);
        return def;
    }

    @Test
    @DisplayName(
            "GIVEN default RECORD_NAME strategy WHEN registerService called SHOULD register each schema under its record's full name")
    void given_recordNameStrategy_when_registerService_should_registerUnderFullName() throws Exception {
        // Arrange
        Map<String, Map<String, Object>> components = componentsFixture();
        ServiceDefinition def = definitionWithConfluent(AvroSubjectStrategy.RECORD_NAME);

        // Act
        codec.registerService(SERVICE, def, components);

        // Assert
        assertThat(stub.receivedRequests())
                .anyMatch(r -> r.equals("POST /subjects/com.acme.inventory.Item/versions"))
                .anyMatch(r -> r.equals("POST /subjects/com.acme.orders.Order/versions"));
    }

    @Test
    @DisplayName(
            "GIVEN COMPONENT_NAME strategy WHEN registerService called SHOULD register each schema under its OpenAPI component name")
    void given_componentNameStrategy_when_registerService_should_registerUnderComponentName() throws Exception {
        // Arrange
        Map<String, Map<String, Object>> components = componentsFixture();
        ServiceDefinition def = definitionWithConfluent(AvroSubjectStrategy.COMPONENT_NAME);

        // Act
        codec.registerService(SERVICE, def, components);

        // Assert
        assertThat(stub.receivedRequests())
                .anyMatch(r -> r.equals("POST /subjects/Item/versions"))
                .anyMatch(r -> r.equals("POST /subjects/Order/versions"));
    }

    @Test
    @DisplayName(
            "GIVEN avro.subjects names an explicit subject for a component WHEN registerService called SHOULD register under that subject instead of the strategy's")
    void given_explicitSubjectOverride_when_registerService_should_registerUnderExplicitSubject() throws Exception {
        // Arrange
        Map<String, Map<String, Object>> components = componentsFixture();
        AvroConfig cfg = new AvroConfig();
        cfg.setSchemaRegistryUrl(stub.url());
        cfg.setWireFormat(AvroWireFormat.CONFLUENT);
        cfg.setSubjectStrategy(AvroSubjectStrategy.RECORD_NAME);
        cfg.setSubjects(Map.of("Item", "custom-item-subject"));
        ServiceDefinition def = new ServiceDefinition();
        def.setAvro(cfg);

        // Act
        codec.registerService(SERVICE, def, components);

        // Assert
        assertThat(stub.receivedRequests())
                .anyMatch(r -> r.equals("POST /subjects/custom-item-subject/versions"))
                .noneMatch(r -> r.equals("POST /subjects/com.acme.inventory.Item/versions"));
    }

    @Test
    @DisplayName(
            "GIVEN a CONFLUENT-configured service WHEN encode called SHOULD produce bytes starting with 00 and the id the stub assigned")
    void given_confluentConfiguredService_when_encode_should_startWithMagicByteAndAssignedId() throws Exception {
        // Arrange
        Map<String, Map<String, Object>> components = componentsFixture();
        ServiceDefinition def = definitionWithConfluent(AvroSubjectStrategy.RECORD_NAME);
        codec.registerService(SERVICE, def, components);
        int assignedId = stub.seedVersion(
                "com.acme.inventory.Item",
                codec.registry().find(SERVICE, "Item").orElseThrow().toString());
        // seedVersion is idempotent per schema text, so this returns the same id already registered.

        // Act
        byte[] encoded = codec.encode(SERVICE, "Item", Map.of("sku", "SKU-1", "qty", 5L));

        // Assert
        assertThat(encoded[0]).isEqualTo((byte) 0x00);
        int idFromBytes = ((encoded[1] & 0xFF) << 24)
                | ((encoded[2] & 0xFF) << 16)
                | ((encoded[3] & 0xFF) << 8)
                | (encoded[4] & 0xFF);
        assertThat(idFromBytes).isEqualTo(assignedId);
    }

    @Test
    @DisplayName(
            "GIVEN an evolved schema version registered directly on the stub before load WHEN a Confluent-framed message of that version is decoded SHOULD resolve it through the fetched versions")
    void given_evolvedVersionOnStub_when_decodeConfluentFramedBytes_should_resolveThroughFetchedVersions()
            throws Exception {
        // Arrange: seed an older version of Item directly on the registry (as if a prior deploy
        // registered it), then load the service so registerService fetches every version.
        Schema olderItem = SchemaBuilder.record("Item")
                .namespace("com.acme.inventory")
                .fields()
                .name("sku")
                .type()
                .stringType()
                .noDefault()
                .name("qty")
                .type()
                .longType()
                .longDefault(0L)
                .endRecord();
        int olderId = stub.seedVersion("com.acme.inventory.Item", olderItem.toString());

        Map<String, Map<String, Object>> components = componentsFixture();
        ServiceDefinition def = definitionWithConfluent(AvroSubjectStrategy.RECORD_NAME);
        codec.registerService(SERVICE, def, components);

        // A message written with the older schema, framed under the older id.
        org.apache.avro.generic.GenericData.Record olderRecord =
                new org.apache.avro.generic.GenericData.Record(olderItem);
        olderRecord.put("sku", "SKU-OLD");
        olderRecord.put("qty", 0L);
        byte[] confluentBytes = AvroFraming.encodeConfluent(olderRecord, olderItem, olderId);

        // Act
        Object decoded = codec.decode(SERVICE, "Item", false, confluentBytes);

        // Assert
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) decoded;
        assertThat(map.get("sku")).isEqualTo("SKU-OLD");
    }

    @Test
    @DisplayName(
            "GIVEN CONFLUENT wire format with no schemaRegistryUrl WHEN registerService called SHOULD throw IllegalStateException")
    void given_confluentWithoutRegistryUrl_when_registerService_should_throwIllegalStateException() {
        // Arrange
        AvroConfig cfg = new AvroConfig();
        cfg.setWireFormat(AvroWireFormat.CONFLUENT);
        ServiceDefinition def = new ServiceDefinition();
        def.setAvro(cfg);

        // Act & Assert
        assertThatThrownBy(() -> codec.registerService(SERVICE, def, Map.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName(
            "GIVEN a registry URL that is unreachable WHEN registerService called SHOULD throw IllegalStateException naming the subject")
    void given_unreachableRegistry_when_registerService_should_throwIllegalStateExceptionNamingSubject()
            throws Exception {
        // Arrange: bind to a closed local port so the connection fails fast, with a short timeout.
        int closedPort = stub.port();
        stub.dispose();
        Map<String, Map<String, Object>> components = componentsFixture();
        AvroConfig cfg = new AvroConfig();
        cfg.setSchemaRegistryUrl("http://localhost:" + closedPort);
        cfg.setWireFormat(AvroWireFormat.CONFLUENT);
        cfg.setRegistryTimeout(Duration.ofSeconds(2));
        ServiceDefinition def = new ServiceDefinition();
        def.setAvro(cfg);

        // Act & Assert
        assertThatThrownBy(() -> codec.registerService(SERVICE, def, components))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("subject");
    }

    @Test
    @DisplayName(
            "GIVEN a service registered with CONFLUENT WHEN unregisterService then encode called SHOULD fail with 'No Avro schema bound'")
    void given_confluentServiceUnregistered_when_encode_should_failWithNoSchemaBound() throws Exception {
        // Arrange
        Map<String, Map<String, Object>> components = componentsFixture();
        ServiceDefinition def = definitionWithConfluent(AvroSubjectStrategy.RECORD_NAME);
        codec.registerService(SERVICE, def, components);

        // Act
        codec.unregisterService(SERVICE);

        // Assert
        assertThatThrownBy(() -> codec.encode(SERVICE, "Item", Map.of("sku", "x", "qty", 1L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No Avro schema bound");
    }

    @Test
    @DisplayName(
            "GIVEN a component and schema WHEN subjectFor called directly SHOULD honor explicit subjects, then strategy")
    void given_componentAndSchema_when_subjectFor_should_honorExplicitSubjectsThenStrategy() {
        // Arrange
        Schema schema = SchemaBuilder.record("Widget")
                .namespace("com.acme.test")
                .fields()
                .name("id")
                .type()
                .stringType()
                .noDefault()
                .endRecord();
        AvroConfig recordNameCfg = new AvroConfig();
        recordNameCfg.setSubjectStrategy(AvroSubjectStrategy.RECORD_NAME);
        AvroConfig componentNameCfg = new AvroConfig();
        componentNameCfg.setSubjectStrategy(AvroSubjectStrategy.COMPONENT_NAME);
        AvroConfig explicitCfg = new AvroConfig();
        explicitCfg.setSubjectStrategy(AvroSubjectStrategy.RECORD_NAME);
        explicitCfg.setSubjects(Map.of("Widget", "explicit-subject"));

        // Act & Assert
        assertThat(AvroUpstreamCodec.subjectFor(recordNameCfg, "Widget", schema))
                .isEqualTo("com.acme.test.Widget");
        assertThat(AvroUpstreamCodec.subjectFor(componentNameCfg, "Widget", schema))
                .isEqualTo("Widget");
        assertThat(AvroUpstreamCodec.subjectFor(explicitCfg, "Widget", schema)).isEqualTo("explicit-subject");
    }
}
