package dev.appiaworks.shiguchi.discovery.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Server;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.commons.util.InetUtils;
import org.springframework.cloud.commons.util.InetUtilsProperties;
import org.springframework.cloud.consul.ConsulClient;
import org.springframework.cloud.consul.discovery.ConsulDiscoveryProperties;
import org.springframework.cloud.consul.discovery.HeartbeatProperties;
import org.springframework.cloud.consul.model.http.agent.NewService;
import org.springframework.cloud.consul.serviceregistry.ConsulAutoServiceRegistration;
import org.springframework.cloud.consul.serviceregistry.ConsulRegistration;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.grpc.server.lifecycle.GrpcServerStartedEvent;

@DisplayName("ConsulGrpcPortAutoConfiguration")
class ConsulGrpcPortAutoConfigurationTest {

    /** Collects the scheduled tasks instead of waiting for them. */
    static class FakeScheduler implements GrpcPortHold.Scheduler {
        final List<Runnable> tasks = new ArrayList<>();
        final List<Duration> delays = new ArrayList<>();

        @Override
        public Runnable schedule(Runnable task, Duration delay) {
            tasks.add(task);
            delays.add(delay);
            return () -> tasks.remove(task);
        }
    }

    private final FakeScheduler scheduler = new FakeScheduler();
    private final ConsulClient consul = mock(ConsulClient.class);
    private final ConsulAutoServiceRegistration auto = mock(ConsulAutoServiceRegistration.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConsulGrpcPortAutoConfiguration.class))
            .withBean(ConsulClient.class, () -> consul)
            .withBean(ConsulAutoServiceRegistration.class, () -> auto)
            .withBean(GrpcPortHold.Scheduler.class, () -> scheduler)
            .withPropertyValues("shiguchi.discovery.client.expose-grpc-port=true");

    private static ConsulRegistration registration(NewService.Check check, Map<String, String> meta) {
        NewService service = new NewService();
        service.setId("orders-8080");
        service.setName("orders");
        service.setPort(8080);
        service.setMeta(meta == null ? null : new HashMap<>(meta));
        service.setCheck(check);
        return new ConsulRegistration(service, new ConsulDiscoveryProperties(new InetUtils(new InetUtilsProperties())));
    }

    private static NewService.Check httpCheck() {
        NewService.Check check = new NewService.Check();
        check.setHttp("http://localhost:8080/actuator/health");
        check.setInterval("10s");
        return check;
    }

    private static Map<String, String> meta(ConsulRegistration registration) {
        return registration.getService().getMeta();
    }

    private static GrpcServerStartedEvent started(int port) {
        return new GrpcServerStartedEvent(mock(GrpcServerLifecycle.class), mock(Server.class), "0.0.0.0", port);
    }

    private void withPublisher(java.util.function.Consumer<AssertableApplicationContext> test, String... properties) {
        runner.withPropertyValues(properties).run(ctx -> test.accept(ctx));
    }

    @Test
    @DisplayName("GIVEN the property is off WHEN the context starts SHOULD not create the publisher")
    void GIVEN_property_off_WHEN_starting_SHOULD_be_inert() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConsulGrpcPortAutoConfiguration.class))
                .run(ctx -> assertThat(ctx).doesNotHaveBean(ConsulGrpcPortPublisher.class));
    }

    @Test
    @DisplayName("GIVEN a fixed gRPC port WHEN Consul customizes the registration SHOULD carry grpc-port and the type")
    void GIVEN_static_port_WHEN_customizing_SHOULD_write_meta() {
        withPublisher(
                ctx -> {
                    ConsulRegistration reg = registration(httpCheck(), null);

                    ctx.getBean(ConsulGrpcPortPublisher.class).customize(reg);

                    assertThat(meta(reg)).containsEntry("grpc-port", "9595").containsEntry("shiguchi.type", "grpc");
                    assertThat(reg.getService().getCheck().getStatus()).isNull();
                    assertThat(ctx.getBean(ConsulGrpcPortPublisher.class).isHolding())
                            .isFalse();
                },
                "shiguchi.discovery.client.grpc-port=9595");
    }

    @Test
    @DisplayName("GIVEN a meta key the app set itself WHEN a port is known SHOULD leave it alone")
    void GIVEN_app_set_key_WHEN_customizing_SHOULD_not_overwrite() {
        runner.withPropertyValues("spring.grpc.server.port=9595").run(ctx -> {
            ConsulRegistration reg = registration(httpCheck(), Map.of("grpc-port", "1234"));

            ctx.getBean(ConsulGrpcPortPublisher.class).customize(reg);

            assertThat(meta(reg)).containsEntry("grpc-port", "1234").containsEntry("shiguchi.type", "grpc");
        });
    }

    @Test
    @DisplayName("GIVEN an explicit grpc-port property WHEN the app set another value SHOULD overwrite it")
    void GIVEN_explicit_property_WHEN_customizing_SHOULD_overwrite() {
        withPublisher(
                ctx -> {
                    ConsulRegistration reg = registration(httpCheck(), Map.of("grpc-port", "1234"));

                    ctx.getBean(ConsulGrpcPortPublisher.class).customize(reg);

                    assertThat(meta(reg)).containsEntry("grpc-port", "9595");
                },
                "shiguchi.discovery.client.grpc-port=9595");
    }

    @Test
    @DisplayName("GIVEN a late port WHEN customized SHOULD register with a critical check and hold")
    void GIVEN_late_port_WHEN_customizing_SHOULD_hold_with_critical_check() {
        runner.run(ctx -> {
            ConsulRegistration reg = registration(httpCheck(), null);

            ConsulGrpcPortPublisher publisher = ctx.getBean(ConsulGrpcPortPublisher.class);
            publisher.customize(reg);

            assertThat(reg.getService().getCheck().getStatus()).isEqualTo("critical");
            assertThat(publisher.isHolding()).isTrue();
            assertThat(meta(reg)).isNull();
        });
    }

    @Test
    @DisplayName("GIVEN a held service WHEN the port is published SHOULD set the meta, pass the check and re-register")
    void GIVEN_held_service_WHEN_publishing_SHOULD_release_and_reregister() {
        when(auto.isRunning()).thenReturn(true);
        runner.run(ctx -> {
            ConsulRegistration reg = registration(httpCheck(), null);
            ConsulGrpcPortPublisher publisher = ctx.getBean(ConsulGrpcPortPublisher.class);
            publisher.customize(reg);

            boolean published = publisher.publish(45123);

            assertThat(published).isTrue();
            assertThat(meta(reg)).containsEntry("grpc-port", "45123").containsEntry("shiguchi.type", "grpc");
            assertThat(reg.getService().getCheck().getStatus()).isEqualTo("passing");
            assertThat(publisher.isHolding()).isFalse();
            verify(consul).agentServiceRegister(isNull(), same(reg.getService()));
        });
    }

    @Test
    @DisplayName("GIVEN the service has not registered yet WHEN the port is published SHOULD not call Consul")
    void GIVEN_unregistered_service_WHEN_publishing_SHOULD_only_update_the_local_registration() {
        when(auto.isRunning()).thenReturn(false);
        runner.run(ctx -> {
            ConsulRegistration reg = registration(httpCheck(), null);
            ConsulGrpcPortPublisher publisher = ctx.getBean(ConsulGrpcPortPublisher.class);
            publisher.customize(reg);

            publisher.publish(45123);

            assertThat(meta(reg)).containsEntry("grpc-port", "45123");
            verify(consul, never()).agentServiceRegister(any(), any());
        });
    }

    @Test
    @DisplayName("GIVEN the port is already registered WHEN published again SHOULD not re-register")
    void GIVEN_port_already_registered_WHEN_publishing_SHOULD_be_a_no_op() {
        when(auto.isRunning()).thenReturn(true);
        runner.withPropertyValues("shiguchi.discovery.client.grpc-port=9595").run(ctx -> {
            ConsulRegistration reg = registration(httpCheck(), null);
            ConsulGrpcPortPublisher publisher = ctx.getBean(ConsulGrpcPortPublisher.class);
            publisher.customize(reg);

            assertThat(publisher.publish(9595)).isTrue();

            verify(consul, never()).agentServiceRegister(any(), any());
        });
    }

    @Test
    @DisplayName("GIVEN no port in time WHEN the publish timeout fires SHOULD release the check without grpc-port")
    void GIVEN_no_port_WHEN_timeout_fires_SHOULD_release_without_port() {
        when(auto.isRunning()).thenReturn(true);
        runner.withPropertyValues("shiguchi.discovery.client.publish-timeout=7s")
                .run(ctx -> {
                    ConsulRegistration reg = registration(httpCheck(), null);
                    ConsulGrpcPortPublisher publisher = ctx.getBean(ConsulGrpcPortPublisher.class);
                    publisher.customize(reg);

                    ctx.getBean(ConsulGrpcPortAutoConfiguration.ConsulGrpcPortReadyListener.class)
                            .onReady();

                    assertThat(scheduler.delays).containsExactly(Duration.ofSeconds(7));
                    assertThat(publisher.isHolding()).isTrue();

                    scheduler.tasks.get(0).run();

                    assertThat(publisher.isHolding()).isFalse();
                    assertThat(reg.getService().getCheck().getStatus()).isEqualTo("passing");
                    assertThat(meta(reg)).isNull();
                    verify(consul).agentServiceRegister(isNull(), same(reg.getService()));
                });
    }

    @Test
    @DisplayName("GIVEN a port published in time WHEN the timeout would fire SHOULD have nothing scheduled")
    void GIVEN_port_published_WHEN_checking_timeout_SHOULD_cancel_it() {
        runner.run(ctx -> {
            ConsulRegistration reg = registration(httpCheck(), null);
            ConsulGrpcPortPublisher publisher = ctx.getBean(ConsulGrpcPortPublisher.class);
            publisher.customize(reg);
            ctx.getBean(ConsulGrpcPortAutoConfiguration.ConsulGrpcPortReadyListener.class)
                    .onReady();
            assertThat(scheduler.tasks).hasSize(1);

            publisher.publish(45123);

            assertThat(scheduler.tasks).isEmpty();
        });
    }

    @Test
    @DisplayName("GIVEN heartbeat is on WHEN the port is late SHOULD not hold, and still publish by re-registering")
    void GIVEN_heartbeat_WHEN_late_port_SHOULD_not_hold() {
        when(auto.isRunning()).thenReturn(true);
        HeartbeatProperties beat = new HeartbeatProperties();
        beat.setEnabled(true);
        runner.withBean(HeartbeatProperties.class, () -> beat).run(ctx -> {
            NewService.Check ttl = new NewService.Check();
            ttl.setTtl("30s");
            ConsulRegistration reg = registration(ttl, null);
            ConsulGrpcPortPublisher publisher = ctx.getBean(ConsulGrpcPortPublisher.class);

            publisher.customize(reg);

            assertThat(publisher.isHolding()).isFalse();
            assertThat(ttl.getStatus()).isNull();

            publisher.publish(45123);

            assertThat(meta(reg)).containsEntry("grpc-port", "45123");
            assertThat(ttl.getStatus()).isNull();
            verify(consul).agentServiceRegister(isNull(), same(reg.getService()));
        });
    }

    @Test
    @DisplayName("GIVEN no health check WHEN the port is late SHOULD not hold")
    void GIVEN_no_check_WHEN_late_port_SHOULD_not_hold() {
        runner.run(ctx -> {
            ConsulRegistration reg = registration(null, null);
            ConsulGrpcPortPublisher publisher = ctx.getBean(ConsulGrpcPortPublisher.class);

            publisher.customize(reg);

            assertThat(publisher.isHolding()).isFalse();
        });
    }

    @Test
    @DisplayName("GIVEN hold-until-published is off WHEN the port is late SHOULD not hold")
    void GIVEN_hold_off_WHEN_late_port_SHOULD_not_hold() {
        runner.withPropertyValues("shiguchi.discovery.client.hold-until-published=false")
                .run(ctx -> {
                    ConsulRegistration reg = registration(httpCheck(), null);
                    ConsulGrpcPortPublisher publisher = ctx.getBean(ConsulGrpcPortPublisher.class);

                    publisher.customize(reg);

                    assertThat(publisher.isHolding()).isFalse();
                    assertThat(reg.getService().getCheck().getStatus()).isNull();
                });
    }

    @Test
    @DisplayName("GIVEN no registration yet WHEN a port is published SHOULD report that nothing was published")
    void GIVEN_no_registration_WHEN_publishing_SHOULD_return_false() {
        runner.run(ctx -> {
            assertThat(ctx.getBean(ConsulGrpcPortPublisher.class).publish(45123))
                    .isFalse();
            verify(consul, never()).agentServiceRegister(any(), any());
        });
    }

    @Test
    @DisplayName("GIVEN a Spring gRPC server starting after the app is ready WHEN it binds SHOULD publish to Consul")
    void GIVEN_spring_grpc_server_WHEN_started_late_SHOULD_publish_to_consul() {
        when(auto.isRunning()).thenReturn(true);
        runner.withPropertyValues("spring.grpc.server.port=0").run(ctx -> {
            ConsulRegistration reg = registration(httpCheck(), null);
            ConsulGrpcPortPublisher publisher = ctx.getBean(ConsulGrpcPortPublisher.class);
            publisher.customize(reg);
            ctx.getBean(SpringGrpcServerPortSupplier.class).onReady();

            ctx.publishEvent(started(45124));

            assertThat(meta(reg)).containsEntry("grpc-port", "45124");
            assertThat(reg.getService().getCheck().getStatus()).isEqualTo("passing");
            verify(consul, times(1)).agentServiceRegister(isNull(), same(reg.getService()));
        });
    }
}
