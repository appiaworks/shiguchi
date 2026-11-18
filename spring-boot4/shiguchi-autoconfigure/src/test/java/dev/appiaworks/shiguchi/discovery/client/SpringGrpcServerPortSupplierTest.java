package dev.appiaworks.shiguchi.discovery.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.InstanceInfo;
import io.grpc.Server;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.commons.util.InetUtils;
import org.springframework.cloud.commons.util.InetUtilsProperties;
import org.springframework.cloud.netflix.eureka.EurekaInstanceConfigBean;
import org.springframework.cloud.netflix.eureka.InstanceInfoFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.grpc.server.lifecycle.GrpcServerStartedEvent;

@DisplayName("SpringGrpcServerPortSupplier")
class SpringGrpcServerPortSupplierTest {

    /** Not annotated: a {@code @Configuration} here would be picked up by the end-to-end tests' component scan. */
    static class EurekaBeans {

        @Bean
        EurekaInstanceConfigBean eurekaInstanceConfigBean() {
            EurekaInstanceConfigBean config = new EurekaInstanceConfigBean(new InetUtils(new InetUtilsProperties()));
            config.setAppname("orders");
            config.setInstanceId("orders:1");
            config.setNonSecurePort(8080);
            return config;
        }

        @Bean
        ApplicationInfoManager applicationInfoManager(EurekaInstanceConfigBean config) {
            return new ApplicationInfoManager(config, new InstanceInfoFactory().create(config));
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(GrpcPortRegistrationAutoConfiguration.class))
            .withUserConfiguration(EurekaBeans.class)
            .withPropertyValues("shiguchi.discovery.client.expose-grpc-port=true");

    private static GrpcServerStartedEvent started(int port) {
        return new GrpcServerStartedEvent(mock(GrpcServerLifecycle.class), mock(Server.class), "0.0.0.0", port);
    }

    private static InstanceInfo info(ApplicationContext ctx) {
        return ctx.getBean(ApplicationInfoManager.class).getInfo();
    }

    @Test
    @DisplayName("GIVEN a fixed spring.grpc.server.port WHEN the instance registers SHOULD carry it as grpc-port")
    void GIVEN_fixed_property_WHEN_registering_SHOULD_publish() {
        runner.withPropertyValues("spring.grpc.server.port=9595").run(ctx -> {
            assertThat(info(ctx).getMetadata()).containsEntry("grpc-port", "9595");
        });
    }

    @Test
    @DisplayName("GIVEN spring.grpc.server.port=0 and no start event WHEN resolved SHOULD find no port")
    void GIVEN_dynamic_port_before_start_WHEN_resolved_SHOULD_be_empty() {
        runner.withPropertyValues("spring.grpc.server.port=0").run(ctx -> {
            assertThat(ctx.getBean(SpringGrpcServerPortSupplier.class).port()).isEmpty();
            assertThat(info(ctx).getMetadata()).doesNotContainKey("grpc-port");
        });
    }

    @Test
    @DisplayName("GIVEN port 0 and a start event WHEN the app is ready SHOULD publish the bound port")
    void GIVEN_dynamic_port_and_start_event_WHEN_ready_SHOULD_publish_bound_port() {
        runner.withPropertyValues("spring.grpc.server.port=0").run(ctx -> {
            ctx.publishEvent(started(45123));
            assertThat(ctx.getBean(SpringGrpcServerPortSupplier.class).port()).hasValue(45123);

            ctx.getBean(GrpcPortRegistrationAutoConfiguration.GrpcPortReadyListener.class)
                    .onReady();

            assertThat(info(ctx).getMetadata()).containsEntry("grpc-port", "45123");
        });
    }

    @Test
    @DisplayName("GIVEN the app is already ready WHEN the server starts SHOULD publish from the event")
    void GIVEN_ready_app_WHEN_server_starts_late_SHOULD_publish() {
        runner.withPropertyValues("spring.grpc.server.port=0").run(ctx -> {
            ctx.getBean(SpringGrpcServerPortSupplier.class).onReady();

            ctx.publishEvent(started(45124));

            assertThat(info(ctx).getMetadata()).containsEntry("grpc-port", "45124");
        });
    }

    @Test
    @DisplayName("GIVEN a start event for an unbound server WHEN received SHOULD be ignored")
    void GIVEN_unbound_server_WHEN_started_SHOULD_be_ignored() {
        runner.withPropertyValues("spring.grpc.server.port=0").run(ctx -> {
            ctx.getBean(SpringGrpcServerPortSupplier.class).onReady();

            ctx.publishEvent(started(-1));

            assertThat(ctx.getBean(SpringGrpcServerPortSupplier.class).port()).isEmpty();
            assertThat(info(ctx).getMetadata()).doesNotContainKey("grpc-port");
        });
    }

    @Test
    @DisplayName("GIVEN a bound port and a configured one WHEN resolved SHOULD prefer the bound port")
    void GIVEN_bound_and_configured_WHEN_resolved_SHOULD_prefer_bound() {
        runner.withPropertyValues("spring.grpc.server.port=9595").run(ctx -> {
            ctx.publishEvent(started(9596));

            assertThat(ctx.getBean(SpringGrpcServerPortSupplier.class).port()).hasValue(9596);
        });
    }

    @Test
    @DisplayName("GIVEN a user-declared supplier WHEN resolved SHOULD win over the Spring gRPC one")
    void GIVEN_user_supplier_WHEN_resolved_SHOULD_win() {
        runner.withPropertyValues("spring.grpc.server.port=9595")
                .withBean(GrpcServerPortSupplier.class, () -> () -> java.util.OptionalInt.of(7001))
                .run(ctx -> {
                    assertThat(ctx.getBean(GrpcPortPublisher.class).resolvePort())
                            .hasValue(7001);
                });
    }
}
