package dev.appiaworks.shiguchi.routing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.config.ServiceDefinition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;

/** Which registered instance a discovery backend's schema is read from. */
@DisplayName("Schema source selection")
class SchemaSourceSelectorTest {

    private static ServiceInstance instance(String id, String host, int port, Map<String, String> metadata) {
        return new DefaultServiceInstance(id, "catalog", host, port, false, metadata);
    }

    private static ServiceDefinition.VersionsConfig from(String schemaFrom) {
        ServiceDefinition.VersionsConfig config = new ServiceDefinition.VersionsConfig();
        config.setSchemaFrom(schemaFrom);
        return config;
    }

    private static final List<ServiceInstance> ROLLOUT = List.of(
            instance("new-b", "10.0.0.9", 9090, Map.of("version", "1.10.0")),
            instance("old-b", "10.0.0.7", 9090, Map.of("version", "1.4.0")),
            instance("old-a", "10.0.0.3", 9090, Map.of("version", "1.4.0")),
            instance("new-a", "10.0.0.5", 9090, Map.of("version", "1.10.0")));

    @Test
    @DisplayName(
            "GIVEN two versions WHEN schema-from is the default SHOULD pick the oldest version, lowest address first")
    void given_twoVersions_when_default_should_pickOldestLowestAddress() {
        var source = SchemaSourceSelector.select(ROLLOUT, new ServiceDefinition.VersionsConfig())
                .orElseThrow();

        assertThat(source.version()).isEqualTo("1.4.0");
        assertThat(source.address()).isEqualTo("10.0.0.3:9090");
        assertThat(source.instanceId()).isEqualTo("old-a");
    }

    @Test
    @DisplayName("GIVEN two versions WHEN schema-from is NEWEST SHOULD pick the newest version")
    void given_twoVersions_when_newest_should_pickNewest() {
        var source = SchemaSourceSelector.select(ROLLOUT, from("newest")).orElseThrow();

        assertThat(source.version()).isEqualTo("1.10.0");
        assertThat(source.address()).isEqualTo("10.0.0.5:9090");
    }

    @Test
    @DisplayName(
            "GIVEN an exact schema-from WHEN that version runs SHOULD pick it, and WHEN it does not SHOULD fall back to the oldest")
    void given_exactVersion_when_selected_should_pickItOrFallBack() {
        assertThat(SchemaSourceSelector.select(ROLLOUT, from("v1.10"))
                        .orElseThrow()
                        .version())
                .isEqualTo("1.10.0");
        assertThat(SchemaSourceSelector.select(ROLLOUT, from("2.0.0"))
                        .orElseThrow()
                        .version())
                .isEqualTo("1.4.0");
    }

    @Test
    @DisplayName("GIVEN a custom metadata key and a grpc-port override WHEN selected SHOULD read both")
    void given_customKeyAndGrpcPort_when_selected_should_readBoth() {
        var config = new ServiceDefinition.VersionsConfig();
        config.setMetadataKey("app.version");
        List<ServiceInstance> instances = List.of(
                instance("a", "10.0.0.1", 8080, Map.of("app.version", "3", "grpc-port", "9443")),
                instance("b", "10.0.0.2", 8080, Map.of("app.version", "2")));

        var source = SchemaSourceSelector.select(instances, config).orElseThrow();

        assertThat(source.version()).isEqualTo("2");
        assertThat(source.address()).isEqualTo("10.0.0.2:8080");
        config.setSchemaFrom("NEWEST");
        assertThat(SchemaSourceSelector.select(instances, config).orElseThrow().address())
                .isEqualTo("10.0.0.1:9443");
    }

    @Test
    @DisplayName(
            "GIVEN instances without version metadata WHEN selected SHOULD still pin one, with no version, the same each time")
    void given_unversionedInstances_when_selected_should_pinStably() {
        List<ServiceInstance> instances =
                List.of(instance("b", "10.0.0.8", 9090, Map.of()), instance("a", "10.0.0.2", 9090, Map.of()));

        var first = SchemaSourceSelector.select(instances, null).orElseThrow();
        var second = SchemaSourceSelector.select(List.of(instances.get(1), instances.get(0)), null)
                .orElseThrow();

        assertThat(first.version()).isNull();
        assertThat(first.address()).isEqualTo("10.0.0.2:9090").isEqualTo(second.address());
    }

    @Test
    @DisplayName(
            "GIVEN no instances WHEN selected SHOULD pick nothing, leaving the service channel to report the failure")
    void given_noInstances_when_selected_should_pickNothing() {
        assertThat(SchemaSourceSelector.select(List.of(), null)).isEmpty();
    }
}
