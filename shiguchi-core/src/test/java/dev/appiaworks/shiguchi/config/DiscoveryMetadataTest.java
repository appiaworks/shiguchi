package dev.appiaworks.shiguchi.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DiscoveryMetadata.grpcPort")
class DiscoveryMetadataTest {

    @Test
    @DisplayName("GIVEN the canonical key WHEN read SHOULD return the port")
    void GIVEN_canonical_key_WHEN_read_SHOULD_return_port() {
        assertThat(DiscoveryMetadata.grpcPort(Map.of("grpc-port", "9090"))).hasValue(9090);
    }

    @Test
    @DisplayName("GIVEN each accepted alias WHEN read SHOULD return the port")
    void GIVEN_aliases_WHEN_read_SHOULD_return_port() {
        for (String key : new String[] {"grpc.port", "grpc_port", "gRPC_port", "GRPC-PORT", "gRPC.Port"}) {
            assertThat(DiscoveryMetadata.grpcPort(Map.of(key, "7000"))).as(key).hasValue(7000);
        }
    }

    @Test
    @DisplayName("GIVEN the canonical key and an alias WHEN read SHOULD prefer the canonical key")
    void GIVEN_canonical_and_alias_WHEN_read_SHOULD_prefer_canonical() {
        assertThat(DiscoveryMetadata.grpcPort(Map.of("grpc-port", "1111", "grpc.port", "2222")))
                .hasValue(1111);
    }

    @Test
    @DisplayName("GIVEN a custom key WHEN read SHOULD try it before the canonical key")
    void GIVEN_custom_key_WHEN_read_SHOULD_win() {
        Map<String, String> metadata = Map.of("my.rpc.port", "3333", "grpc-port", "1111");

        assertThat(DiscoveryMetadata.grpcPort(metadata, "my.rpc.port")).hasValue(3333);
        assertThat(DiscoveryMetadata.grpcPort(metadata, "absent")).hasValue(1111);
        assertThat(DiscoveryMetadata.grpcPort(metadata, " ")).hasValue(1111);
    }

    @Test
    @DisplayName("GIVEN a missing, blank, non-numeric or out-of-range value WHEN read SHOULD be empty")
    void GIVEN_invalid_values_WHEN_read_SHOULD_be_empty() {
        assertThat(DiscoveryMetadata.grpcPort(null)).isEmpty();
        assertThat(DiscoveryMetadata.grpcPort(Map.of())).isEmpty();
        assertThat(DiscoveryMetadata.grpcPort(Map.of("grpc-port", " "))).isEmpty();
        assertThat(DiscoveryMetadata.grpcPort(Map.of("grpc-port", "abc"))).isEmpty();
        assertThat(DiscoveryMetadata.grpcPort(Map.of("grpc-port", "0"))).isEmpty();
        assertThat(DiscoveryMetadata.grpcPort(Map.of("grpc-port", "65536"))).isEmpty();
    }

    @Test
    @DisplayName("GIVEN a value with spaces WHEN grpcPortValue is read SHOULD return it raw")
    void GIVEN_raw_value_WHEN_read_SHOULD_return_unparsed() {
        assertThat(DiscoveryMetadata.grpcPortValue(Map.of("grpc.port", "abc"))).isEqualTo("abc");
        assertThat(DiscoveryMetadata.grpcPortValue(Map.of("other", "1"))).isNull();
    }

    @Test
    @DisplayName("GIVEN two exact aliases WHEN read SHOULD prefer grpc.port over grpc_port")
    void GIVEN_two_exact_aliases_WHEN_read_SHOULD_prefer_dotted() {
        java.util.Map<String, String> metadata = new java.util.LinkedHashMap<>();
        metadata.put("grpc_port", "2222");
        metadata.put("grpc.port", "3333");

        assertThat(DiscoveryMetadata.grpcPort(metadata)).hasValue(3333);
    }

    @Test
    @DisplayName("GIVEN an exact alias and a case-variant WHEN read SHOULD prefer the exact spelling")
    void GIVEN_exact_alias_and_case_variant_WHEN_read_SHOULD_prefer_exact() {
        java.util.Map<String, String> metadata = new java.util.LinkedHashMap<>();
        metadata.put("GRPC-PORT", "1111");
        metadata.put("grpc_port", "2222");

        assertThat(DiscoveryMetadata.grpcPort(metadata)).hasValue(2222);
    }

    @Test
    @DisplayName("GIVEN conflicting case-variants only WHEN read SHOULD take the first in map order, deterministically")
    void GIVEN_conflicting_case_variants_WHEN_read_SHOULD_take_first_in_order() {
        java.util.Map<String, String> metadata = new java.util.LinkedHashMap<>();
        metadata.put("GRPC-PORT", "1111");
        metadata.put("gRPC.Port", "2222");

        assertThat(DiscoveryMetadata.grpcPort(metadata)).hasValue(1111);
    }

    @Test
    @DisplayName("GIVEN a blank canonical key and a valid alias WHEN read SHOULD fall through to the alias")
    void GIVEN_blank_canonical_WHEN_read_SHOULD_use_alias() {
        assertThat(DiscoveryMetadata.grpcPort(Map.of("grpc-port", " ", "GRPC_PORT", "4444")))
                .hasValue(4444);
    }

    @Test
    @DisplayName("GIVEN only the Kubernetes named-port key WHEN read SHOULD return that port, in any case")
    void GIVEN_kubernetes_port_key_WHEN_read_SHOULD_return_port() {
        assertThat(DiscoveryMetadata.grpcPort(Map.of("port.grpc", "9090"))).hasValue(9090);
        assertThat(DiscoveryMetadata.grpcPort(Map.of("Port.GRPC", "9091"))).hasValue(9091);
    }

    @Test
    @DisplayName("GIVEN the Kubernetes key and any grpc-port spelling WHEN read SHOULD prefer the grpc-port spelling")
    void GIVEN_kubernetes_key_and_alias_WHEN_read_SHOULD_prefer_alias() {
        assertThat(DiscoveryMetadata.grpcPort(Map.of("port.grpc", "9090", "grpc-port", "1111")))
                .hasValue(1111);
        assertThat(DiscoveryMetadata.grpcPort(Map.of("port.grpc", "9090", "GRPC_PORT", "2222")))
                .hasValue(2222);
        assertThat(DiscoveryMetadata.grpcPort(Map.of("port.grpc", "9090", "Port.GRPC", "3333")))
                .hasValue(9090);
    }

    @Test
    @DisplayName("GIVEN an invalid or blank Kubernetes key WHEN read SHOULD be empty, and a custom key still wins")
    void GIVEN_invalid_kubernetes_key_WHEN_read_SHOULD_be_empty() {
        assertThat(DiscoveryMetadata.grpcPort(Map.of("port.grpc", "nope"))).isEmpty();
        assertThat(DiscoveryMetadata.grpcPort(Map.of("port.grpc", " "))).isEmpty();
        assertThat(DiscoveryMetadata.grpcPort(Map.of("port.http", "8080"))).isEmpty();
        assertThat(DiscoveryMetadata.grpcPort(Map.of("port.grpc", "9090", "my.key", "7777"), "my.key"))
                .hasValue(7777);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("GIVEN a Consul-spelled key WHEN reading the dotted key SHOULD find it")
    void GIVEN_consul_spelling_WHEN_reading_dotted_key_SHOULD_find_it() {
        java.util.Map<String, String> meta = java.util.Map.of("shiguchi_expose", "true", "shiguchi.type", "rest");

        org.assertj.core.api.Assertions.assertThat(DiscoveryMetadata.value(meta, "shiguchi.expose"))
                .isEqualTo("true");
        org.assertj.core.api.Assertions.assertThat(DiscoveryMetadata.value(meta, "shiguchi.type"))
                .isEqualTo("rest");
        org.assertj.core.api.Assertions.assertThat(DiscoveryMetadata.consulKey("shiguchi.path-prefix"))
                .isEqualTo("shiguchi_path-prefix");
    }
}
