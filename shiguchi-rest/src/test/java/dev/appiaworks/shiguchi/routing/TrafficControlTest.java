package dev.appiaworks.shiguchi.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.appiaworks.shiguchi.config.ServiceDefinition;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The live split of one version-routed service: changing it, putting it back, and its counters. */
@DisplayName("Traffic control")
class TrafficControlTest {

    private final TrafficControl control = TrafficControl.create(
            "catalog",
            List.of(
                    new VersionedLoadBalancerProvider.VersionWeight("1.4.0", 100),
                    new VersionedLoadBalancerProvider.VersionWeight("1.10.0", 0)));

    @AfterEach
    void release() {
        control.release();
    }

    private static Map<String, Number> split(Object... pairs) {
        Map<String, Number> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], (Number) pairs[i + 1]);
        return map;
    }

    @Test
    @DisplayName(
            "GIVEN a configured split WHEN new weights are set and then reset SHOULD report each, and notify every time")
    void given_configuredSplit_when_setAndReset_should_reportAndNotify() {
        AtomicInteger notified = new AtomicInteger();
        control.subscribe(notified::incrementAndGet);

        control.setWeights(split("1.4.0", 25, "v1.10", 75));

        assertThat(control.currentWeights()).containsExactly(Map.entry("1.4.0", 25.0), Map.entry("v1.10", 75.0));
        assertThat(control.weightOf("1.10.0"))
                .as("same version, other spelling")
                .isEqualTo(75.0);
        assertThat(control.overridden()).isTrue();
        assertThat(control.changedAt()).isNotNull();

        control.reset();

        assertThat(control.currentWeights()).isEqualTo(control.configuredWeights());
        assertThat(control.overridden()).isFalse();
        assertThat(notified).hasValue(2);
    }

    @Test
    @DisplayName("GIVEN an invalid split WHEN set SHOULD refuse it and keep the weights in effect")
    void given_invalidSplit_when_set_should_refuseAndKeepWeights() {
        assertThatThrownBy(() -> control.setWeights(Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> control.setWeights(split("1.4.0", -1))).hasMessageContaining("0 or more");
        assertThatThrownBy(() -> control.setWeights(split("1.4.0", 0, "1.10.0", 0)))
                .hasMessageContaining("above 0");
        assertThatThrownBy(() -> control.setWeights(split("1.10.0", 50, "v1.10", 50)))
                .hasMessageContaining("twice");
        assertThatThrownBy(() -> control.setWeights(split("1.4.0", Double.NaN)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(control.overridden()).isFalse();
        assertThat(control.weightOf("1.4.0")).isEqualTo(100.0);
    }

    @Test
    @DisplayName(
            "GIVEN a version's calls starting and ending WHEN its stats are read SHOULD count in flight, total and failed")
    void given_calls_when_statsRead_should_count() {
        TrafficControl.VersionStats stats = control.stats("1.4.0");
        stats.started();
        stats.started();
        stats.started();
        stats.finished(true);
        stats.finished(false);

        assertThat(stats.inFlight()).isEqualTo(1);
        assertThat(stats.calls()).isEqualTo(3);
        assertThat(stats.failures()).isEqualTo(1);
        assertThat(stats.lastCallAt()).isNotNull();
        assertThat(control.stats("v1.4"))
                .as("one entry per version, however spelled")
                .isSameAs(stats);
    }

    @Test
    @DisplayName("GIVEN a control WHEN looked up by id before and after release SHOULD find it, then not")
    void given_control_when_released_should_noLongerBeFound() {
        assertThat(TrafficControl.byId(control.id())).isSameAs(control);

        control.release();

        assertThat(TrafficControl.byId(control.id())).isNull();
    }

    @Test
    @DisplayName(
            "GIVEN live weights moving all calls to the new version WHEN the schema version is chosen SHOULD follow them, not the configuration")
    void given_liveWeights_when_schemaChosen_should_followThem() {
        var config = new ServiceDefinition.VersionsConfig();
        var old = new ServiceDefinition.TrafficSplit();
        old.setVersion("1.4.0");
        old.setWeight(100);
        config.setTraffic(List.of(old));
        var running = new LinkedHashSet<>(List.of("1.4.0", "1.10.0"));

        assertThat(SchemaSourceSelector.servedVersions(running, config)).containsExactly("1.4.0");
        assertThat(SchemaSourceSelector.servedVersions(running, Map.of("1.4.0", 0.0, "1.10.0", 100.0)))
                .containsExactly("1.10.0");
    }

    @Test
    @DisplayName("GIVEN a control id WHEN the policy config is built and parsed SHOULD carry the id to the policy")
    void given_controlId_when_configBuiltAndParsed_should_carryIt() {
        ServiceDefinition def = new ServiceDefinition();
        def.setName("catalog");
        def.setDiscoveryName("catalog");
        def.getVersions().setPreviewHeader("x-backend-version");

        @SuppressWarnings("unchecked")
        Map<String, Object> body =
                (Map<String, Object>) VersionedLoadBalancingConfig.forService(def, "round_robin", control.id())
                        .get(VersionedLoadBalancingConfig.POLICY);
        var parsed = (VersionedLoadBalancerProvider.Config) new VersionedLoadBalancerProvider()
                .parseLoadBalancingPolicyConfig(body)
                .getConfig();

        assertThat(parsed.control()).isEqualTo(control.id());
        assertThat(TrafficControl.byId(parsed.control())).isSameAs(control);
    }
}
