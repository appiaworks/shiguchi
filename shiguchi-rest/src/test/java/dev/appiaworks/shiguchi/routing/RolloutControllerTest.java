package dev.appiaworks.shiguchi.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import io.grpc.Status;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The automated rollout's decisions, tick by tick on a fake clock: when it starts, how it steps,
 * when it rolls back, and what an operator can do to it.
 */
@DisplayName("Automated rollout")
class RolloutControllerTest {

    /** A clock the test moves. */
    private static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-01T12:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    private final TestClock clock = new TestClock();
    private TrafficControl control;
    private RolloutController rollouts;
    private ServiceDefinition.RolloutConfig config;

    @BeforeEach
    void setUp() {
        control = TrafficControl.create(
                "catalog", List.of(new VersionedLoadBalancerProvider.VersionWeight("1.4.0", 100)));
        ServiceDefinition def = new ServiceDefinition();
        def.setName("catalog");
        def.setDiscoveryName("catalog");
        config = def.getVersions().getRollout();
        config.setEnabled(true);
        config.setSteps(List.of(10, 50, 100));
        config.setInterval(Duration.ofMinutes(1));
        config.setMinCalls(20);
        config.setMaxErrorRate(0.05);
        GatewayProperties properties = new GatewayProperties();
        properties.getServices().add(def);
        GrpcChannelFactory factory = mock(GrpcChannelFactory.class);
        when(factory.trafficControl("catalog")).thenReturn(Optional.of(control));
        rollouts = new RolloutController(properties, factory, clock, false);
    }

    @AfterEach
    void release() {
        control.release();
    }

    private void running(String... versions) {
        Map<String, Integer> byVersion = new LinkedHashMap<>();
        for (String v : versions) byVersion.put(v, 1);
        control.setRunning(byVersion);
    }

    /** {@code n} calls on {@code version}, {@code failed} of them UNAVAILABLE, each taking {@code millis}. */
    private void calls(String version, int n, int failed, long millis) {
        TrafficControl.VersionStats stats = control.stats(version);
        for (int i = 0; i < n; i++) {
            stats.started();
            stats.finished(i < failed ? Status.Code.UNAVAILABLE : Status.Code.OK, millis * 1_000_000);
        }
    }

    private Object phase() {
        return rollouts.status("catalog").get("phase");
    }

    @Test
    @DisplayName(
            "GIVEN one version taking every call WHEN a newer one registers SHOULD start moving the first step's share to it")
    void given_stableVersion_when_newerRegisters_should_startFirstStep() {
        running("1.4.0");
        rollouts.tick();
        assertThat(phase()).isEqualTo("WATCHING");

        running("1.4.0", "1.10.0");
        rollouts.tick();

        assertThat(control.currentWeights()).containsEntry("1.4.0", 90.0).containsEntry("1.10.0", 10.0);
        assertThat(control.origin()).isEqualTo(TrafficControl.Origin.ROLLOUT);
        assertThat(rollouts.status("catalog"))
                .containsEntry("phase", "ROLLING")
                .containsEntry("stable", "1.4.0")
                .containsEntry("candidate", "1.10.0")
                .containsEntry("step", 10);
    }

    @Test
    @DisplayName(
            "GIVEN a healthy new version WHEN each step sees enough calls and its interval passes SHOULD step to 100 and complete")
    void given_healthyVersion_when_stepsPass_should_promote() {
        running("1.4.0", "1.10.0");
        rollouts.tick();

        for (int expected : new int[] {50, 100}) {
            calls("1.10.0", 30, 0, 5);
            clock.advance(Duration.ofSeconds(61));
            rollouts.tick();
            assertThat(control.weightOf("1.10.0")).isEqualTo(expected);
        }
        calls("1.10.0", 30, 1, 5);
        clock.advance(Duration.ofSeconds(61));
        rollouts.tick();

        assertThat(phase()).isEqualTo("WATCHING");
        assertThat(control.currentWeights()).containsEntry("1.4.0", 0.0).containsEntry("1.10.0", 100.0);
        assertThat((String) rollouts.status("catalog").get("lastDecision")).startsWith("promoted 1.10.0");
    }

    @Test
    @DisplayName(
            "GIVEN a step whose interval passed WHEN fewer than min-calls ended SHOULD hold the step rather than judge it")
    void given_fewCalls_when_intervalPassed_should_holdStep() {
        running("1.4.0", "1.10.0");
        rollouts.tick();
        calls("1.10.0", 5, 0, 5);
        clock.advance(Duration.ofMinutes(5));

        rollouts.tick();

        assertThat(control.weightOf("1.10.0")).isEqualTo(10);
        @SuppressWarnings("unchecked")
        Map<String, Object> window =
                (Map<String, Object>) rollouts.status("catalog").get("window");
        assertThat(window).containsEntry("calls", 5L).containsEntry("minCalls", 20L);
    }

    @Test
    @DisplayName(
            "GIVEN a step WHEN the new version's error rate passes the limit SHOULD roll back at once, and not retry that version, only a newer one")
    void given_errorRateBreached_when_judged_should_rollBackAndNotRetry() {
        running("1.4.0", "1.10.0");
        rollouts.tick();
        calls("1.10.0", 20, 2, 5); // 10 % against a 5 % limit, before the interval is over

        rollouts.tick();

        assertThat(phase()).isEqualTo("ROLLED_BACK");
        assertThat(control.currentWeights()).containsEntry("1.4.0", 100.0).containsEntry("1.10.0", 0.0);
        assertThat((String) rollouts.status("catalog").get("lastDecision")).contains("error rate 10.00 %");
        assertThat(rollouts.status("catalog").get("failedVersions")).isEqualTo(List.of("1.10.0"));

        clock.advance(Duration.ofMinutes(10));
        rollouts.tick();
        assertThat(control.weightOf("1.10.0"))
                .as("a failed version is not retried")
                .isZero();

        running("1.4.0", "1.10.0", "1.11.0");
        rollouts.tick();
        assertThat(rollouts.status("catalog")).containsEntry("phase", "ROLLING").containsEntry("candidate", "1.11.0");
    }

    @Test
    @DisplayName("GIVEN a p99 limit WHEN the new version's unary calls are slower SHOULD roll back")
    void given_p99Breached_when_judged_should_rollBack() {
        config.setMaxP99(Duration.ofMillis(100));
        running("1.4.0", "1.10.0");
        rollouts.tick();
        calls("1.10.0", 18, 0, 20);
        calls("1.10.0", 2, 0, 900);

        rollouts.tick();

        assertThat(phase()).isEqualTo("ROLLED_BACK");
        assertThat((String) rollouts.status("catalog").get("lastDecision")).contains("p99");
    }

    @Test
    @DisplayName(
            "GIVEN a running rollout WHEN an operator changes the weights through the endpoint SHOULD pause, and resume SHOULD reapply the step")
    void given_rolling_when_weightsChangedByHand_should_pauseUntilResumed() {
        running("1.4.0", "1.10.0");
        rollouts.tick();

        control.setWeights(Map.of("1.4.0", 70, "1.10.0", 30));
        calls("1.10.0", 30, 0, 5);
        clock.advance(Duration.ofMinutes(2));
        rollouts.tick();

        assertThat(phase()).isEqualTo("PAUSED");
        assertThat(control.weightOf("1.10.0"))
                .as("the operator's weights stand")
                .isEqualTo(30);

        rollouts.act("catalog", "resume");

        assertThat(phase()).isEqualTo("ROLLING");
        assertThat(control.weightOf("1.10.0")).isEqualTo(10);
        // A new window: the calls made while paused do not count for the step.
        clock.advance(Duration.ofMinutes(2));
        rollouts.tick();
        assertThat(control.weightOf("1.10.0")).isEqualTo(10);
    }

    @Test
    @DisplayName(
            "GIVEN a running rollout WHEN an operator promotes or aborts SHOULD move every call to the new or the old version")
    void given_rolling_when_promotedOrAborted_should_moveEverything() {
        running("1.4.0", "1.10.0");
        rollouts.tick();

        rollouts.act("catalog", "abort");
        assertThat(phase()).isEqualTo("ROLLED_BACK");
        assertThat(control.weightOf("1.10.0")).isZero();

        rollouts.act("catalog", "resume");
        assertThat(phase()).as("resume retries a rolled-back version").isEqualTo("ROLLING");

        rollouts.act("catalog", "promote");
        assertThat(phase()).isEqualTo("WATCHING");
        assertThat(control.weightOf("1.10.0")).isEqualTo(100);
        assertThat(control.weightOf("1.4.0")).isZero();
    }

    @Test
    @DisplayName("GIVEN a rollout WHEN an action does not apply or is unknown SHOULD refuse it")
    void given_rollout_when_badAction_should_refuse() {
        running("1.4.0");
        rollouts.tick();

        assertThatThrownBy(() -> rollouts.act("catalog", "pause")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> rollouts.act("catalog", "abort")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> rollouts.act("catalog", "dance")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rollouts.act("other", "pause")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName(
            "GIVEN a running rollout WHEN the new version disappears from the registry SHOULD put every call back on the old one")
    void given_rolling_when_candidateGone_should_returnToStable() {
        running("1.4.0", "1.10.0");
        rollouts.tick();

        running("1.4.0");
        rollouts.tick();

        assertThat(phase()).isEqualTo("WATCHING");
        assertThat(control.weightOf("1.4.0")).isEqualTo(100);
        assertThat(control.weightOf("1.10.0")).isZero();
    }

    @Test
    @DisplayName("GIVEN a shared split set by hand WHEN ticked SHOULD not start a rollout over it")
    void given_sharedSplit_when_ticked_should_notStart() {
        control.setWeights(Map.of("1.4.0", 50, "1.10.0", 50));
        running("1.4.0", "1.10.0", "1.11.0");

        rollouts.tick();

        assertThat(phase()).isEqualTo("WATCHING");
        assertThat(control.weightOf("1.11.0")).isZero();
    }

    @Test
    @DisplayName("GIVEN latencies WHEN the p99 is read SHOULD be within a bucket of the true value")
    void given_latencies_when_p99Read_should_beClose() {
        LatencyHistogram histogram = new LatencyHistogram();
        for (int i = 1; i <= 1000; i++) histogram.record(i * 1_000_000L); // 1..1000 ms

        double p99 = LatencyHistogram.quantileMillis(histogram.snapshot(), 0.99);

        assertThat(p99).isBetween(990.0, 990.0 * 1.25);
        assertThat(LatencyHistogram.quantileMillis(new long[72], 0.99)).isEqualTo(-1);
    }
}
