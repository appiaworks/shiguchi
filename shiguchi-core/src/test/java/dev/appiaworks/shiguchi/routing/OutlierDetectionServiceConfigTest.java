package dev.appiaworks.shiguchi.routing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.config.ServiceDefinition;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code loadBalancingConfig} emitted for {@code services[].outlier-detection}, in the parsed
 * JSON shape gRPC's outlier-detection policy reads (gRFC A50).
 */
@DisplayName("Outlier detection service config")
class OutlierDetectionServiceConfigTest {

    private static ServiceDefinition service() {
        ServiceDefinition def = new ServiceDefinition();
        def.setName("orders");
        def.getOutlierDetection().setEnabled(true);
        return def;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> policy(List<Map<String, Object>> lb) {
        assertThat(lb).hasSize(1);
        return (Map<String, Object>) lb.get(0).get(OutlierDetectionServiceConfig.POLICY);
    }

    @Test
    @DisplayName(
            "GIVEN outlier detection off WHEN the config is built SHOULD emit nothing, leaving the channel as it was")
    void given_disabled_when_built_should_emitNothing() {
        assertThat(OutlierDetectionServiceConfig.loadBalancingConfig(new ServiceDefinition(), "round_robin"))
                .isNull();
    }

    @Test
    @DisplayName(
            "GIVEN the defaults WHEN the config is built SHOULD emit durations as seconds, numbers as doubles, failure-percentage only, around the child policy")
    void given_defaults_when_built_should_emitGrpcShape() {
        Map<String, Object> config =
                policy(OutlierDetectionServiceConfig.loadBalancingConfig(service(), "round_robin"));

        assertThat(config)
                .containsEntry("interval", "10s")
                .containsEntry("baseEjectionTime", "30s")
                .containsEntry("maxEjectionTime", "300s")
                .containsEntry("maxEjectionPercent", 50.0)
                .doesNotContainKey("successRateEjection");
        assertThat(config.get("failurePercentageEjection"))
                .isEqualTo(Map.of(
                        "threshold", 50.0, "enforcementPercentage", 100.0, "minimumHosts", 2.0, "requestVolume", 20.0));
        assertThat(config.get("childPolicy")).isEqualTo(List.of(Map.of("round_robin", Map.of())));
    }

    @Test
    @DisplayName("GIVEN success-rate ejection WHEN the config is built SHOULD express the stdev factor in thousandths")
    void given_successRate_when_built_should_scaleStdevFactor() {
        ServiceDefinition def = service();
        def.getOutlierDetection().getSuccessRate().setEnabled(true);
        def.getOutlierDetection().getSuccessRate().setStdevFactor(1.9);
        def.getOutlierDetection().getFailurePercentage().setEnabled(false);
        def.getOutlierDetection().setInterval(Duration.ofMillis(1500));

        Map<String, Object> config = policy(OutlierDetectionServiceConfig.loadBalancingConfig(def, "pick_first"));

        assertThat(config).containsEntry("interval", "1.5s").doesNotContainKey("failurePercentageEjection");
        assertThat(config.get("successRateEjection"))
                .isEqualTo(Map.of(
                        "stdevFactor",
                        1900.0,
                        "enforcementPercentage",
                        100.0,
                        "minimumHosts",
                        5.0,
                        "requestVolume",
                        100.0));
        assertThat(config.get("childPolicy")).isEqualTo(List.of(Map.of("pick_first", Map.of())));
    }

    @Test
    @DisplayName("GIVEN no child policy WHEN the config is built SHOULD wrap round_robin")
    void given_noChildPolicy_when_built_should_wrapRoundRobin() {
        Map<String, Object> config =
                policy(OutlierDetectionServiceConfig.loadBalancingConfig(service(), (String) null));

        assertThat(config.get("childPolicy")).isEqualTo(List.of(Map.of("round_robin", Map.of())));
    }
}
