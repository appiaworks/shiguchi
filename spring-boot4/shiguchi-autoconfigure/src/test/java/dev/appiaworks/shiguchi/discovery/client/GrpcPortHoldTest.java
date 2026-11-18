package dev.appiaworks.shiguchi.discovery.client;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.HealthCheckHandler;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import dev.appiaworks.shiguchi.discovery.client.GrpcPortRegistrationAutoConfiguration.GrpcPortReadyListener;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.protobuf.services.HealthStatusManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;

@DisplayName("GrpcPortHold: instance stays STARTING until the gRPC port is published")
class GrpcPortHoldTest {

    private static final String ON = "shiguchi.discovery.client.expose-grpc-port=true";

    /** Runs a scheduled task only when the test says so. */
    static class ManualScheduler implements GrpcPortHold.Scheduler {
        Runnable task;
        Duration delay;
        boolean cancelled;
        final java.util.concurrent.atomic.AtomicInteger cancels = new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public Runnable schedule(Runnable task, Duration delay) {
            this.task = task;
            this.delay = delay;
            return () -> {
                cancelled = true;
                cancels.incrementAndGet();
            };
        }
    }

    /** A health check handler that always reports UP, like EurekaHealthCheckHandler on a healthy app. */
    static class AlwaysUpHandler implements HealthCheckHandler {
        @Override
        public InstanceStatus getStatus(InstanceStatus currentStatus) {
            return InstanceStatus.UP;
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(GrpcPortRegistrationAutoConfiguration.class))
            .withUserConfiguration(GrpcPortRegistrationAutoConfigurationTest.EurekaBeans.class);

    /** Records "status:grpc-port" at the moment each status change becomes visible, as a consumer would see it. */
    private static ApplicationInfoManager.StatusChangeListener sampler(
            ApplicationInfoManager manager, List<String> seen) {
        return new ApplicationInfoManager.StatusChangeListener() {
            @Override
            public String getId() {
                return "grpc-port-hold-test";
            }

            @Override
            public void notify(com.netflix.discovery.StatusChangeEvent event) {
                seen.add(event.getStatus() + ":"
                        + manager.getInfo().getMetadata().get("grpc-port"));
            }
        };
    }

    /** The status the instance registers with: Spring Cloud's registry applies it on register. */
    private static InstanceStatus initial(ApplicationContext ctx) {
        return ctx.getBean(org.springframework.cloud.netflix.eureka.EurekaInstanceConfigBean.class)
                .getInitialStatus();
    }

    private static InstanceInfo info(ApplicationContext ctx) {
        return ctx.getBean(ApplicationInfoManager.class).getInfo();
    }

    @Test
    @DisplayName(
            "GIVEN a port unknown at registration WHEN the instance registers SHOULD be STARTING without grpc-port")
    void GIVEN_unknown_port_WHEN_registering_SHOULD_be_starting() {
        runner.withPropertyValues(ON).run(ctx -> {
            assertThat(info(ctx).getStatus()).isEqualTo(InstanceStatus.STARTING);
            assertThat(initial(ctx)).isEqualTo(InstanceStatus.STARTING);
            assertThat(info(ctx).getMetadata()).doesNotContainKey("grpc-port");
            assertThat(ctx.getBean(GrpcPortHold.class).isHolding()).isTrue();
        });
    }

    @Test
    @DisplayName("GIVEN a hold WHEN a health check handler says UP SHOULD keep STARTING while the port is pending")
    void GIVEN_hold_WHEN_health_says_up_SHOULD_stay_starting() {
        runner.withPropertyValues(ON).withBean(AlwaysUpHandler.class).run(ctx -> {
            HealthCheckHandler handler = ctx.getBean(HealthCheckHandler.class);

            assertThat(handler.getStatus(InstanceStatus.STARTING)).isNull();

            ctx.getBean(GrpcPortPublisher.class).publish(51234);

            assertThat(handler.getStatus(InstanceStatus.STARTING)).isEqualTo(InstanceStatus.UP);
        });
    }

    @Test
    @DisplayName("GIVEN a hold WHEN a health check handler says DOWN SHOULD pass DOWN through")
    void GIVEN_hold_WHEN_health_says_down_SHOULD_pass_through() {
        runner.withPropertyValues(ON)
                .withBean(HealthCheckHandler.class, () -> current -> InstanceStatus.DOWN)
                .run(ctx -> assertThat(ctx.getBean(HealthCheckHandler.class).getStatus(InstanceStatus.STARTING))
                        .isEqualTo(InstanceStatus.DOWN));
    }

    @Test
    @DisplayName("GIVEN a hold WHEN the port is published SHOULD set grpc-port and UP together")
    void GIVEN_hold_WHEN_published_SHOULD_carry_metadata_and_go_up() {
        runner.withPropertyValues(ON).run(ctx -> {
            ApplicationInfoManager manager = ctx.getBean(ApplicationInfoManager.class);
            List<String> seen = new ArrayList<>();
            manager.registerStatusChangeListener(sampler(manager, seen));

            ctx.getBean(GrpcPortPublisher.class).publish(51234);

            assertThat(info(ctx).getStatus()).isEqualTo(InstanceStatus.UP);
            assertThat(info(ctx).getMetadata()).containsEntry("grpc-port", "51234");
            // the metadata was in place at the moment UP became visible
            assertThat(seen).containsExactly("UP:51234");
            assertThat(ctx.getBean(GrpcPortHold.class).isHolding()).isFalse();
        });
    }

    @Test
    @DisplayName("GIVEN a hold and no port by the timeout WHEN it fires SHOULD WARN naming the property and go UP")
    void GIVEN_hold_WHEN_timeout_fires_SHOULD_warn_and_release() {
        Logger logger = (Logger) LoggerFactory.getLogger(GrpcPortHold.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        ManualScheduler scheduler = new ManualScheduler();
        try {
            runner.withPropertyValues(ON, "shiguchi.discovery.client.publish-timeout=5s")
                    .withBean(GrpcPortHold.Scheduler.class, () -> scheduler)
                    .run(ctx -> {
                        ctx.getBean(GrpcPortReadyListener.class).onReady();
                        assertThat(scheduler.delay).isEqualTo(Duration.ofSeconds(5));
                        assertThat(info(ctx).getStatus()).isEqualTo(InstanceStatus.STARTING);

                        scheduler.task.run();

                        assertThat(info(ctx).getStatus()).isEqualTo(InstanceStatus.UP);
                        assertThat(info(ctx).getMetadata()).doesNotContainKey("grpc-port");
                        assertThat(appender.list).anySatisfy(e -> {
                            assertThat(e.getLevel()).isEqualTo(Level.WARN);
                            assertThat(e.getFormattedMessage())
                                    .contains("shiguchi.discovery.client.grpc-port")
                                    .contains("hold-until-published");
                        });
                    });
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("GIVEN a pending timeout WHEN the port is published first SHOULD cancel the timeout")
    void GIVEN_pending_timeout_WHEN_published_SHOULD_cancel_it() {
        ManualScheduler scheduler = new ManualScheduler();
        runner.withPropertyValues(ON)
                .withBean(GrpcPortHold.Scheduler.class, () -> scheduler)
                .run(ctx -> {
                    ctx.getBean(GrpcPortReadyListener.class).onReady();

                    ctx.getBean(GrpcPortPublisher.class).publish(51234);

                    assertThat(scheduler.cancelled).isTrue();
                    scheduler.task.run(); // a late firing is a no-op
                    assertThat(info(ctx).getStatus()).isEqualTo(InstanceStatus.UP);
                    assertThat(info(ctx).getMetadata()).containsEntry("grpc-port", "51234");
                });
    }

    @Test
    @DisplayName("GIVEN hold-until-published off WHEN the instance registers SHOULD be UP at once")
    void GIVEN_hold_disabled_WHEN_registering_SHOULD_be_up() {
        runner.withPropertyValues(ON, "shiguchi.discovery.client.hold-until-published=false")
                .withBean(AlwaysUpHandler.class)
                .run(ctx -> {
                    assertThat(initial(ctx)).isEqualTo(InstanceStatus.UP);
                    assertThat(ctx.getBean(GrpcPortHold.class).isHolding()).isFalse();
                    assertThat(ctx.getBean(HealthCheckHandler.class).getStatus(InstanceStatus.STARTING))
                            .isEqualTo(InstanceStatus.UP);
                });
    }

    @Test
    @DisplayName("GIVEN a port known at registration WHEN the instance registers SHOULD never be STARTING")
    void GIVEN_static_port_WHEN_registering_SHOULD_not_hold() {
        runner.withPropertyValues(ON, "shiguchi.discovery.client.grpc-port=9090")
                .run(ctx -> {
                    assertThat(initial(ctx)).isEqualTo(InstanceStatus.UP);
                    assertThat(info(ctx).getMetadata()).containsEntry("grpc-port", "9090");
                    assertThat(ctx.getBean(GrpcPortHold.class).isHolding()).isFalse();
                });
        runner.withPropertyValues(ON)
                .withBean(GrpcServerPortSupplier.class, () -> () -> OptionalInt.of(7070))
                .run(ctx -> {
                    assertThat(initial(ctx)).isEqualTo(InstanceStatus.UP);
                    assertThat(ctx.getBean(GrpcPortHold.class).isHolding()).isFalse();
                });
    }

    @Test
    @DisplayName("GIVEN update-on-bind off WHEN the port is unknown SHOULD not hold, nothing would release it")
    void GIVEN_update_on_bind_off_WHEN_registering_SHOULD_not_hold() {
        runner.withPropertyValues(ON, "shiguchi.discovery.client.update-on-bind=false")
                .run(ctx -> assertThat(initial(ctx)).isEqualTo(InstanceStatus.UP));
    }

    @Test
    @DisplayName("GIVEN a real server on port 0 WHEN the app is ready SHOULD never expose UP without grpc-port")
    void GIVEN_server_on_port_zero_WHEN_ready_SHOULD_never_show_up_without_the_port() throws Exception {
        Server server = ServerBuilder.forPort(0)
                .addService(new HealthStatusManager().getHealthService())
                .build()
                .start();
        try {
            AtomicReference<List<String>> seen = new AtomicReference<>(new ArrayList<>());
            runner.withPropertyValues(ON)
                    .withBean(AlwaysUpHandler.class)
                    .withBean(Server.class, () -> server)
                    .run(ctx -> {
                        ApplicationInfoManager manager = ctx.getBean(ApplicationInfoManager.class);
                        manager.registerStatusChangeListener(sampler(manager, seen.get()));
                        // before the ready event a consumer reading the registry sees STARTING, no port
                        assertThat(info(ctx).getStatus()).isEqualTo(InstanceStatus.STARTING);

                        ctx.getBean(GrpcPortReadyListener.class).onReady();

                        assertThat(info(ctx).getStatus()).isEqualTo(InstanceStatus.UP);
                        assertThat(seen.get()).containsExactly("UP:" + server.getPort());
                    });
        } finally {
            server.shutdownNow();
        }
    }
}
