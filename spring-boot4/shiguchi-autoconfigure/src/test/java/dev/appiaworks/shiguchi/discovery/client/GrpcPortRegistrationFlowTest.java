package dev.appiaworks.shiguchi.discovery.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.HealthCheckHandler;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.discovery.EurekaClient;
import com.netflix.discovery.StatusChangeEvent;
import dev.appiaworks.shiguchi.discovery.client.GrpcPortRegistrationAutoConfiguration.GrpcPortReadyListener;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.netflix.eureka.EurekaInstanceConfigBean;
import org.springframework.cloud.netflix.eureka.serviceregistry.EurekaRegistration;
import org.springframework.cloud.netflix.eureka.serviceregistry.EurekaServiceRegistry;
import org.springframework.context.ApplicationContext;

/**
 * What a real Eureka server used to prove, without one. The server side is a fake: a recorder that takes
 * a (status, grpc-port) snapshot at every point where Eureka would replicate the instance to the registry
 * (each status change, and each on-demand replication request). The invariant under test is that no snapshot
 * is ever {@code UP} without {@code grpc-port}, except after the documented timeout.
 *
 * <p>The registration itself is the real Spring Cloud code: {@link EurekaServiceRegistry#register} runs
 * unmodified against a real {@link ApplicationInfoManager} and the real {@link EurekaInstanceConfigBean}
 * our post-processor configured; only the Eureka client (network) is a Mockito mock. Not covered, and
 * therefore a documented limitation: real replication latency, and what a real server returns to a real
 * {@code EurekaDiscoveryClient}.
 */
@DisplayName("gRPC port in the Eureka registration, without a Eureka server")
class GrpcPortRegistrationFlowTest {

    private static final String ON = "shiguchi.discovery.client.expose-grpc-port=true";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(GrpcPortRegistrationAutoConfiguration.class))
            .withUserConfiguration(GrpcPortRegistrationAutoConfigurationTest.EurekaBeans.class)
            .withPropertyValues(ON);

    /** Stands in for the registry: records "STATUS:grpc-port" each time the instance would be replicated. */
    static final class Registry {
        final List<String> snapshots = new ArrayList<>();
        final EurekaClient client = mock(EurekaClient.class);
        HealthCheckHandler handler;

        void attach(ApplicationInfoManager manager) {
            manager.registerStatusChangeListener(new ApplicationInfoManager.StatusChangeListener() {
                @Override
                public String getId() {
                    return "fake-registry";
                }

                @Override
                public void notify(StatusChangeEvent event) {
                    snapshots.add(snapshot(manager.getInfo()));
                }
            });
            // GrpcPortPublisher asks for an on-demand replication via registerHealthCheck: record that too.
            doAnswer(call -> snapshots.add("replicate " + snapshot(manager.getInfo())))
                    .when(client)
                    .registerHealthCheck(any());
            when(client.getHealthCheckHandler()).thenAnswer(call -> handler);
        }

        /**
         * One replicator tick, as {@code DiscoveryClient.refreshInstanceInfo} does it: ask the health check
         * handler for the status and apply it unless it returns null.
         */
        void tick(ApplicationInfoManager manager) {
            InstanceStatus status = handler.getStatus(manager.getInfo().getStatus());
            if (status != null) manager.setInstanceStatus(status);
        }

        void assertNeverUpWithoutPort() {
            assertThat(snapshots).noneMatch(s -> s.contains("UP:null"));
        }
    }

    private static String snapshot(InstanceInfo info) {
        return info.getStatus() + ":" + info.getMetadata().get("grpc-port");
    }

    /** Spring Cloud's own registration, run for real; only the Eureka client is a mock. */
    private static void register(ApplicationContext ctx, Registry registry) {
        EurekaRegistration reg = mock(EurekaRegistration.class);
        when(reg.getApplicationInfoManager()).thenReturn(ctx.getBean(ApplicationInfoManager.class));
        when(reg.getInstanceConfig()).thenReturn(ctx.getBean(EurekaInstanceConfigBean.class));
        when(reg.getEurekaClient()).thenReturn(mock(org.springframework.cloud.netflix.eureka.CloudEurekaClient.class));
        when(reg.getHealthCheckHandler())
                .thenReturn(new StaticListableBeanFactory(Map.of("h", registry.handler))
                        .getBeanProvider(HealthCheckHandler.class));
        new EurekaServiceRegistry().register(reg);
    }

    private static InstanceInfo info(ApplicationContext ctx) {
        return ctx.getBean(ApplicationInfoManager.class).getInfo();
    }

    private static Registry registryWith(HealthCheckHandler handler) {
        Registry registry = new Registry();
        registry.handler = handler;
        return registry;
    }

    @Test
    @DisplayName("GIVEN a late port WHEN EurekaServiceRegistry.register applies the initial status SHOULD be STARTING"
            + " without grpc-port")
    void GIVEN_late_port_WHEN_registered_SHOULD_be_starting_without_metadata() {
        runner.run(ctx -> {
            Registry registry = registryWith(current -> InstanceStatus.UP);
            ApplicationInfoManager manager = ctx.getBean(ApplicationInfoManager.class);
            // Anything but STARTING beforehand, so that register() visibly applies getInitialStatus().
            manager.setInstanceStatus(InstanceStatus.DOWN);

            register(ctx, registry);

            assertThat(ctx.getBean(EurekaInstanceConfigBean.class).getInitialStatus())
                    .isEqualTo(InstanceStatus.STARTING);
            assertThat(info(ctx).getStatus()).isEqualTo(InstanceStatus.STARTING);
            assertThat(info(ctx).getMetadata()).doesNotContainKey("grpc-port");
        });
    }

    @Test
    @DisplayName("GIVEN hold off WHEN EurekaServiceRegistry.register runs SHOULD register UP as before")
    void GIVEN_hold_off_WHEN_registered_SHOULD_be_up() {
        runner.withPropertyValues("shiguchi.discovery.client.hold-until-published=false")
                .run(ctx -> {
                    ctx.getBean(ApplicationInfoManager.class).setInstanceStatus(InstanceStatus.DOWN);

                    register(ctx, registryWith(current -> InstanceStatus.UP));

                    assertThat(info(ctx).getStatus()).isEqualTo(InstanceStatus.UP);
                });
    }

    @Test
    @DisplayName("GIVEN a registered, held instance and a healthy app WHEN the replicator ticks repeatedly SHOULD"
            + " never replicate UP, then replicate UP with grpc-port in one step when published")
    void GIVEN_held_and_healthy_WHEN_ticking_then_published_SHOULD_never_show_up_without_port() {
        Registry registry = new Registry();
        runner.withBean(EurekaClient.class, () -> registry.client).run(ctx -> {
            ApplicationInfoManager manager = ctx.getBean(ApplicationInfoManager.class);
            registry.attach(manager);
            // The real guard, installed by the post-processor on a handler bean, wraps a healthy handler.
            registry.handler = ctx.getBean(GrpcPortHold.class).guard(current -> InstanceStatus.UP);
            register(ctx, registry);

            for (int i = 0; i < 5; i++) registry.tick(manager);
            assertThat(info(ctx).getStatus()).isEqualTo(InstanceStatus.STARTING);
            assertThat(registry.snapshots).noneMatch(s -> s.contains("UP"));

            // The ready event: the late-bound port becomes known.
            ctx.getBean(GrpcPortPublisher.class).publish(51234);
            registry.tick(manager);

            // Metadata was already in place when UP became visible, and the on-demand replication that
            // follows carries both.
            assertThat(registry.snapshots).containsExactly("UP:51234", "replicate UP:51234");
            registry.assertNeverUpWithoutPort();
        });
    }

    @Test
    @DisplayName("GIVEN a held instance whose health is DOWN WHEN the port is published SHOULD report DOWN, with"
            + " grpc-port, and not be promoted to UP")
    void GIVEN_held_and_unhealthy_WHEN_published_SHOULD_stay_down() {
        Registry registry = new Registry();
        runner.withBean(EurekaClient.class, () -> registry.client).run(ctx -> {
            ApplicationInfoManager manager = ctx.getBean(ApplicationInfoManager.class);
            registry.attach(manager);
            registry.handler = ctx.getBean(GrpcPortHold.class).guard(current -> InstanceStatus.DOWN);
            register(ctx, registry);

            registry.tick(manager);
            assertThat(info(ctx).getStatus()).isEqualTo(InstanceStatus.DOWN);

            ctx.getBean(GrpcPortPublisher.class).publish(51234);

            // release() only moves STARTING to UP: a DOWN instance stays DOWN, now carrying the port.
            assertThat(info(ctx).getStatus()).isEqualTo(InstanceStatus.DOWN);
            assertThat(info(ctx).getMetadata()).containsEntry("grpc-port", "51234");
            assertThat(ctx.getBean(GrpcPortHold.class).isHolding()).isFalse();
            registry.assertNeverUpWithoutPort();
        });
    }

    @Test
    @DisplayName("GIVEN a held instance WHEN the timeout fires first SHOULD go UP without grpc-port (documented),"
            + " and a later publish then adds the port")
    void GIVEN_held_WHEN_timeout_then_publish_SHOULD_release_then_add_the_port() {
        GrpcPortHoldTest.ManualScheduler scheduler = new GrpcPortHoldTest.ManualScheduler();
        Registry registry = registryWith(current -> InstanceStatus.UP);
        runner.withBean(GrpcPortHold.Scheduler.class, () -> scheduler)
                .withBean(EurekaClient.class, () -> registry.client)
                .run(ctx -> {
                    ApplicationInfoManager manager = ctx.getBean(ApplicationInfoManager.class);
                    registry.attach(manager);
                    register(ctx, registry);
                    ctx.getBean(GrpcPortReadyListener.class).onReady(); // no port: starts the timeout
                    assertThat(scheduler.delay).isEqualTo(Duration.ofSeconds(60));

                    scheduler.task.run();

                    // The one sanctioned UP-without-port: a missing port must not keep the instance out for good.
                    assertThat(registry.snapshots).containsExactly("UP:null");

                    ctx.getBean(GrpcPortPublisher.class).publish(51234);

                    assertThat(info(ctx).getMetadata()).containsEntry("grpc-port", "51234");
                    assertThat(info(ctx).getStatus()).isEqualTo(InstanceStatus.UP);
                });
    }

    @Test
    @DisplayName("GIVEN a published port WHEN published again or changed SHOULD push a re-registration only when"
            + " the metadata changed")
    void GIVEN_published_WHEN_republished_SHOULD_request_replication_only_on_change() {
        Registry registry = registryWith(current -> InstanceStatus.UP);
        runner.withBean(EurekaClient.class, () -> registry.client).run(ctx -> {
            registry.attach(ctx.getBean(ApplicationInfoManager.class));
            register(ctx, registry);
            GrpcPortPublisher publisher = ctx.getBean(GrpcPortPublisher.class);

            assertThat(publisher.publish(51234)).isTrue();
            verify(registry.client, times(1)).registerHealthCheck(registry.handler);

            assertThat(publisher.publish(51234)).isTrue(); // same port: nothing to push
            verify(registry.client, times(1)).registerHealthCheck(registry.handler);

            assertThat(publisher.publish(51999)).isTrue(); // changed port: pushed again
            verify(registry.client, times(2)).registerHealthCheck(registry.handler);
            assertThat(info(ctx).getMetadata()).containsEntry("grpc-port", "51999");
            registry.assertNeverUpWithoutPort();
        });
    }
}
