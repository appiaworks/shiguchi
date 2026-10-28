package dev.appiaworks.shiguchi.routing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ServiceLoadStatus")
class ServiceLoadStatusTest {

    private static GatewayProperties props(String... names) {
        var props = new GatewayProperties();
        for (String n : names) {
            var s = new ServiceDefinition();
            s.setName(n);
            s.setHost("h");
            s.setPort(1);
            props.getServices().add(s);
        }
        return props;
    }

    private static ServiceEndpointContext context(String name) {
        var def = new ServiceDefinition();
        def.setName(name);
        def.setHost("h");
        def.setPort(1);
        return new ServiceEndpointContext(def, null);
    }

    @Test
    @DisplayName(
            "GIVEN two configured services and one registered context WHEN asked SHOULD report the other as not loaded")
    void given_oneOfTwoRegistered_when_notLoaded_should_nameTheMissingOne() {
        var registry = new GrpcRouteRegistry();
        registry.registerContext(context("a"));
        var status = new ServiceLoadStatus();

        assertThat(status.notLoaded(props("a", "b"), registry)).containsExactly("b");
    }

    @Test
    @DisplayName(
            "GIVEN repeated failures WHEN recorded SHOULD count attempts and keep the last error, cleared by a load")
    void given_failuresThenLoad_when_recorded_should_countAndClear() {
        var status = new ServiceLoadStatus();
        status.recordFailure("a", "startup", new RuntimeException("first"));
        status.recordFailure("a", "retry", new RuntimeException("second"));

        assertThat(status.attempts("a")).isEqualTo(2);
        assertThat(status.failure("a")).get().satisfies(f -> {
            assertThat(f.phase()).isEqualTo("retry");
            assertThat(f.error()).isEqualTo("second");
            assertThat(f.attempts()).isEqualTo(2);
        });

        status.recordLoaded("a");
        assertThat(status.attempts("a")).isZero();
        assertThat(status.failure("a")).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN a snapshot WHEN a service is loaded and another failed SHOULD describe both with routes, target and failure")
    void given_mixedState_when_snapshot_should_describeEach() {
        var registry = new GrpcRouteRegistry();
        registry.registerContext(context("a"));
        var status = new ServiceLoadStatus();
        status.recordLoaded("a");
        status.recordFailure("b", "startup", new RuntimeException("UNAVAILABLE: connection refused"));

        List<ServiceLoadStatus.ServiceState> states = status.snapshot(props("a", "b"), registry);

        assertThat(states).hasSize(2);
        assertThat(states.get(0).name()).isEqualTo("a");
        assertThat(states.get(0).loaded()).isTrue();
        assertThat(states.get(0).loadedAt()).isNotNull();
        assertThat(states.get(0).target()).isEqualTo("h:1");
        assertThat(states.get(0).mode()).isEqualTo("DYNAMIC");
        assertThat(states.get(0).channelState()).isEqualTo("n/a");
        assertThat(states.get(0).lastFailure()).isNull();
        assertThat(states.get(1).loaded()).isFalse();
        assertThat(states.get(1).lastFailure().error()).contains("connection refused");
    }

    @Test
    @DisplayName(
            "GIVEN listeners WHEN outcomes are recorded SHOULD notify each, and a throwing listener must not stop the others")
    void given_listeners_when_recorded_should_notifyAllDespiteFailures() {
        var status = new ServiceLoadStatus();
        var calls = new AtomicInteger();
        var order = new ArrayList<String>();
        status.addListener(() -> {
            order.add("boom");
            throw new IllegalStateException("boom");
        });
        status.addListener(() -> {
            order.add("ok");
            calls.incrementAndGet();
        });

        status.recordFailure("a", "startup", new RuntimeException("x"));
        status.recordLoaded("a");

        assertThat(calls.get()).isEqualTo(2);
        assertThat(order).containsExactly("boom", "ok", "boom", "ok");
    }
}
