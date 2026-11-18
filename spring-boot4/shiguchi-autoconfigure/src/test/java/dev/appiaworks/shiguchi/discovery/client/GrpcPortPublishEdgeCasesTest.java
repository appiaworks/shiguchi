package dev.appiaworks.shiguchi.discovery.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.HealthCheckHandler;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.discovery.StatusChangeEvent;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.Lifecycle;
import org.springframework.core.Ordered;

@DisplayName("GrpcPortPublisher and GrpcPortHold: edge cases around publishing the gRPC port")
class GrpcPortPublishEdgeCasesTest {

    private static final String ON = "shiguchi.discovery.client.expose-grpc-port=true";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(GrpcPortRegistrationAutoConfiguration.class))
            .withUserConfiguration(GrpcPortRegistrationAutoConfigurationTest.EurekaBeans.class);

    @Test
    @DisplayName("GIVEN a timeout and a publish at the same instant WHEN both race SHOULD release exactly once")
    void GIVEN_timeout_and_publish_racing_WHEN_both_fire_SHOULD_release_exactly_once() throws Exception {
        GrpcPortHoldTest.ManualScheduler scheduler = new GrpcPortHoldTest.ManualScheduler();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            runner.withPropertyValues(ON)
                    .withBean(GrpcPortHold.Scheduler.class, () -> scheduler)
                    .run(ctx -> {
                        ApplicationInfoManager manager = ctx.getBean(ApplicationInfoManager.class);
                        GrpcPortHold hold = ctx.getBean(GrpcPortHold.class);
                        GrpcPortPublisher publisher = ctx.getBean(GrpcPortPublisher.class);
                        AtomicInteger ups = new AtomicInteger();
                        manager.registerStatusChangeListener(new ApplicationInfoManager.StatusChangeListener() {
                            @Override
                            public String getId() {
                                return "race";
                            }

                            @Override
                            public void notify(StatusChangeEvent event) {
                                if (event.getStatus() == InstanceStatus.UP) ups.incrementAndGet();
                            }
                        });
                        // A non-atomic release (check, then act) lets both threads see "holding" and release
                        // twice. Each round is a fresh barrier-aligned collision; 300 rounds caught that
                        // mutation (check, yield, set) in 6 of 6 trial runs; 50 caught it in 1 of 5.
                        for (int i = 0; i < 300; i++) {
                            manager.setInstanceStatus(InstanceStatus.STARTING);
                            hold.engage();
                            hold.startTimeout();
                            Runnable timeout = scheduler.task;
                            int port = 20000 + i;
                            int before = ups.get();
                            int cancelsBefore = scheduler.cancels.get();
                            CyclicBarrier barrier = new CyclicBarrier(2);
                            Future<?> a = pool.submit(() -> {
                                barrier.await(5, TimeUnit.SECONDS);
                                publisher.publish(port);
                                return null;
                            });
                            Future<?> b = pool.submit(() -> {
                                barrier.await(5, TimeUnit.SECONDS);
                                timeout.run();
                                return null;
                            });
                            a.get(10, TimeUnit.SECONDS);
                            b.get(10, TimeUnit.SECONDS);

                            assertThat(ups.get() - before)
                                    .as("UP notifications in round %d", i)
                                    .isEqualTo(1);
                            // Eureka's own status change is idempotent, so a double release shows only in
                            // the side effect that runs once per release: cancelling the timeout.
                            assertThat(scheduler.cancels.get() - cancelsBefore)
                                    .as("releases in round %d", i)
                                    .isEqualTo(1);
                            assertThat(hold.isHolding()).isFalse();
                            assertThat(manager.getInfo().getStatus()).isEqualTo(InstanceStatus.UP);
                        }
                    });
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("GIVEN a published port WHEN the same port is published again SHOULD change nothing and report true")
    void GIVEN_published_WHEN_published_again_SHOULD_be_idempotent() {
        runner.withPropertyValues(ON).run(ctx -> {
            GrpcPortPublisher publisher = ctx.getBean(GrpcPortPublisher.class);
            ApplicationInfoManager manager = ctx.getBean(ApplicationInfoManager.class);
            assertThat(publisher.publish(51234)).isTrue();
            AtomicInteger changes = new AtomicInteger();
            manager.registerStatusChangeListener(new ApplicationInfoManager.StatusChangeListener() {
                @Override
                public String getId() {
                    return "idem";
                }

                @Override
                public void notify(StatusChangeEvent event) {
                    changes.incrementAndGet();
                }
            });

            assertThat(publisher.publish(51234)).isTrue();

            assertThat(changes).hasValue(0);
            assertThat(manager.getInfo().getMetadata()).containsEntry("grpc-port", "51234");
            assertThat(manager.getInfo().getStatus()).isEqualTo(InstanceStatus.UP);
        });
    }

    @Test
    @DisplayName("GIVEN a published port WHEN the server restarts on another port SHOULD update the metadata")
    void GIVEN_published_WHEN_port_changes_SHOULD_update_metadata() {
        runner.withPropertyValues(ON).run(ctx -> {
            GrpcPortPublisher publisher = ctx.getBean(GrpcPortPublisher.class);
            ApplicationInfoManager manager = ctx.getBean(ApplicationInfoManager.class);
            publisher.publish(51234);

            // Not automatic: nothing watches the server. The app republishes by calling publish again.
            assertThat(publisher.publish(51999)).isTrue();

            assertThat(manager.getInfo().getMetadata()).containsEntry("grpc-port", "51999");
            assertThat(manager.getInfo().getStatus()).isEqualTo(InstanceStatus.UP);
        });
    }

    @Test
    @DisplayName("GIVEN ports out of range WHEN resolved or published SHOULD be rejected, never put in the registry")
    void GIVEN_invalid_ports_WHEN_resolved_SHOULD_be_rejected() {
        for (String bad : List.of("0", "-1", "65536", "70000")) {
            runner.withPropertyValues(ON, "shiguchi.discovery.client.grpc-port=" + bad)
                    .run(ctx -> assertThat(ctx.getBean(GrpcPortPublisher.class).resolvePort())
                            .as(bad)
                            .isEqualTo(OptionalInt.empty()));
        }
        runner.withPropertyValues(ON)
                .withBean(GrpcServerPortSupplier.class, () -> () -> OptionalInt.of(0))
                .run(ctx -> assertThat(ctx.getBean(GrpcPortPublisher.class).resolvePort())
                        .isEqualTo(OptionalInt.empty()));
        runner.withPropertyValues(ON)
                .withBean(GrpcServerPortSupplier.class, () -> () -> OptionalInt.of(65536))
                .run(ctx -> assertThat(ctx.getBean(GrpcPortPublisher.class).resolvePort())
                        .isEqualTo(OptionalInt.empty()));
        runner.withPropertyValues(ON, "shiguchi.discovery.client.grpc-port=65535")
                .run(ctx -> assertThat(ctx.getBean(GrpcPortPublisher.class).resolvePort())
                        .isEqualTo(OptionalInt.of(65535)));
    }

    @Test
    @DisplayName("GIVEN a non-numeric grpc-port property WHEN the context starts SHOULD fail on binding")
    void GIVEN_non_numeric_port_WHEN_starting_SHOULD_fail_binding() {
        runner.withPropertyValues(ON, "shiguchi.discovery.client.grpc-port=abc")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("GIVEN a handler with Lifecycle and Ordered WHEN guarded SHOULD keep both interfaces on the proxy")
    void GIVEN_lifecycle_ordered_handler_WHEN_guarded_SHOULD_keep_interfaces() {
        class Handler implements HealthCheckHandler, Lifecycle, Ordered {
            boolean running = true;

            @Override
            public InstanceStatus getStatus(InstanceStatus current) {
                return InstanceStatus.UP;
            }

            @Override
            public void start() {
                running = true;
            }

            @Override
            public void stop() {
                running = false;
            }

            @Override
            public boolean isRunning() {
                return running;
            }

            @Override
            public int getOrder() {
                return 42;
            }
        }
        @SuppressWarnings("unchecked")
        ObjectProvider<ApplicationInfoManager> provider = mock(ObjectProvider.class);
        GrpcPortHold hold = new GrpcPortHold(new DiscoveryClientProperties(), provider, (t, d) -> () -> {});
        Handler target = new Handler();
        hold.engage();

        HealthCheckHandler proxy = hold.guard(target);

        assertThat(proxy).isInstanceOf(Lifecycle.class).isInstanceOf(Ordered.class);
        assertThat(((Ordered) proxy).getOrder()).isEqualTo(42);
        ((Lifecycle) proxy).stop();
        assertThat(target.running).isFalse();
        assertThat(((Lifecycle) proxy).isRunning()).isFalse();
        assertThat(proxy.getStatus(InstanceStatus.STARTING)).isNull();
        hold.release(null);
        assertThat(proxy.getStatus(InstanceStatus.STARTING)).isEqualTo(InstanceStatus.UP);
    }
}
