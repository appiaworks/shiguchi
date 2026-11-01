package dev.appiaworks.shiguchi.health;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.ReadinessPolicy;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import dev.appiaworks.shiguchi.routing.ServiceLoadStatus;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

@DisplayName("GatewayReadinessPublisher")
class GatewayReadinessPublisherTest {

    private final GrpcRouteRegistry registry = new GrpcRouteRegistry();
    private final ServiceLoadStatus status = new ServiceLoadStatus();
    private final List<Object> published = new ArrayList<>();
    private final ApplicationEventPublisher publisher = new ApplicationEventPublisher() {
        @Override
        public void publishEvent(ApplicationEvent event) {
            published.add(event);
        }

        @Override
        public void publishEvent(Object event) {
            published.add(event);
        }
    };

    private static GatewayProperties props(ReadinessPolicy policy, String... names) {
        var props = new GatewayProperties();
        props.getHealth().setReadiness(policy);
        for (String n : names) {
            var s = new ServiceDefinition();
            s.setName(n);
            s.setHost("h");
            s.setPort(1);
            props.getServices().add(s);
        }
        return props;
    }

    private void register(GatewayProperties props, String name) {
        props.getServices().stream()
                .filter(s -> name.equals(s.getName()))
                .findFirst()
                .ifPresent(s -> registry.registerContext(new ServiceEndpointContext(s, null)));
    }

    private static AvailabilityChangeEvent<ReadinessState> boot(ReadinessState state) {
        return new AvailabilityChangeEvent<>("boot", state);
    }

    private List<ReadinessState> readinessPublished() {
        List<ReadinessState> out = new ArrayList<>();
        for (Object o : published) {
            if (o instanceof AvailabilityChangeEvent<?> e && e.getState() instanceof ReadinessState s) out.add(s);
        }
        return out;
    }

    @Test
    @DisplayName(
            "GIVEN ALL_LOADED and a service missing WHEN Boot declares ACCEPTING_TRAFFIC SHOULD answer REFUSING_TRAFFIC, then ACCEPTING once it loads")
    void given_allLoadedPolicyWithMissingService_when_bootAccepts_should_refuseThenAcceptOnLoad() {
        var props = props(ReadinessPolicy.ALL_LOADED, "a", "b");
        register(props, "a");
        var p = new GatewayReadinessPublisher(props, registry, status, publisher);

        assertThat(p.ready()).isFalse();
        p.onAvailabilityChange(boot(ReadinessState.ACCEPTING_TRAFFIC));
        assertThat(readinessPublished()).containsExactly(ReadinessState.REFUSING_TRAFFIC);

        register(props, "b");
        status.recordLoaded("b");
        assertThat(readinessPublished())
                .containsExactly(ReadinessState.REFUSING_TRAFFIC, ReadinessState.ACCEPTING_TRAFFIC);
        assertThat(p.ready()).isTrue();
    }

    @Test
    @DisplayName("GIVEN every service loaded WHEN Boot declares ACCEPTING_TRAFFIC SHOULD publish nothing")
    void given_allLoaded_when_bootAccepts_should_stayQuiet() {
        var props = props(ReadinessPolicy.ALL_LOADED, "a");
        register(props, "a");
        var p = new GatewayReadinessPublisher(props, registry, status, publisher);

        p.onAvailabilityChange(boot(ReadinessState.ACCEPTING_TRAFFIC));
        status.recordLoaded("a");

        assertThat(published).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN ANY_LOADED with one of two services loaded WHEN evaluated SHOULD be ready; with none SHOULD not")
    void given_anyLoadedPolicy_when_evaluated_should_needOneService() {
        var props = props(ReadinessPolicy.ANY_LOADED, "a", "b");
        var p = new GatewayReadinessPublisher(props, registry, status, publisher);
        assertThat(p.ready()).isFalse();

        register(props, "a");
        assertThat(p.ready()).isTrue();
    }

    @Test
    @DisplayName("GIVEN IGNORE WHEN nothing is loaded SHOULD be ready and never publish")
    void given_ignorePolicy_when_nothingLoaded_should_beReadyAndQuiet() {
        var props = props(ReadinessPolicy.IGNORE, "a");
        var p = new GatewayReadinessPublisher(props, registry, status, publisher);

        assertThat(p.ready()).isTrue();
        p.onAvailabilityChange(boot(ReadinessState.ACCEPTING_TRAFFIC));
        status.recordFailure("a", "retry", new RuntimeException("down"));

        assertThat(published).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN a load outcome before Boot declared the application ready WHEN recorded SHOULD publish nothing yet")
    void given_outcomeBeforeReady_when_recorded_should_notPublish() {
        var props = props(ReadinessPolicy.ALL_LOADED, "a");
        new GatewayReadinessPublisher(props, registry, status, publisher);

        status.recordFailure("a", "startup", new RuntimeException("down"));

        assertThat(published).isEmpty();
    }

    @Test
    @DisplayName("GIVEN a liveness event WHEN received SHOULD be ignored")
    void given_livenessEvent_when_received_should_ignore() {
        var props = props(ReadinessPolicy.ALL_LOADED, "a");
        var p = new GatewayReadinessPublisher(props, registry, status, publisher);

        p.onAvailabilityChange(new AvailabilityChangeEvent<>("boot", LivenessState.CORRECT));

        assertThat(published).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = ReadinessPolicy.class,
            names = {"ALL_LOADED", "ANY_LOADED"})
    @DisplayName(
            "GIVEN a loaded service and Boot accepted traffic WHEN a later refresh fails SHOULD stay ready and never publish REFUSING_TRAFFIC")
    void given_policySatisfiedAndTrafficAccepted_when_laterRefreshFails_should_staySatisfiedAndNotRefuse(
            ReadinessPolicy policy) {
        var props = props(policy, "a");
        register(props, "a");
        var p = new GatewayReadinessPublisher(props, registry, status, publisher);
        p.onAvailabilityChange(boot(ReadinessState.ACCEPTING_TRAFFIC));

        status.recordFailure("a", "refresh", new RuntimeException("boom"));

        assertThat(p.ready()).isTrue();
        assertThat(readinessPublished()).doesNotContain(ReadinessState.REFUSING_TRAFFIC);
    }
}
