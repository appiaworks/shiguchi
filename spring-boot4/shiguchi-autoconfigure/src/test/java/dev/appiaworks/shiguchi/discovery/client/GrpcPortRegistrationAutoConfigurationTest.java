package dev.appiaworks.shiguchi.discovery.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.InstanceInfo;
import dev.appiaworks.shiguchi.config.DiscoveryMetadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.protobuf.services.HealthStatusManager;
import java.util.OptionalInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.commons.util.InetUtils;
import org.springframework.cloud.commons.util.InetUtilsProperties;
import org.springframework.cloud.netflix.eureka.EurekaInstanceConfigBean;
import org.springframework.cloud.netflix.eureka.InstanceInfoFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@DisplayName("GrpcPortRegistrationAutoConfiguration")
class GrpcPortRegistrationAutoConfigurationTest {

    @Configuration(proxyBeanMethods = false)
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

    /** The app's own metadata, written before the auto-configuration's post-processor runs. */
    static class UserMetadata
            implements org.springframework.beans.factory.config.BeanPostProcessor,
                    org.springframework.core.PriorityOrdered {
        @Override
        public Object postProcessBeforeInitialization(Object bean, String name) {
            if (bean instanceof EurekaInstanceConfigBean c) {
                c.getMetadataMap().put("grpc-port", "6060");
            }
            return bean;
        }

        @Override
        public int getOrder() {
            return org.springframework.core.Ordered.HIGHEST_PRECEDENCE;
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(GrpcPortRegistrationAutoConfiguration.class))
            .withUserConfiguration(EurekaBeans.class);

    private static InstanceInfo info(org.springframework.context.ApplicationContext ctx) {
        return ctx.getBean(ApplicationInfoManager.class).getInfo();
    }

    @Test
    @DisplayName("GIVEN expose-grpc-port is not set WHEN the context starts SHOULD not register anything")
    void GIVEN_default_WHEN_started_SHOULD_be_off() {
        runner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(GrpcPortPublisher.class);
            assertThat(ctx).doesNotHaveBean(DiscoveryClientProperties.class);
            assertThat(info(ctx).getMetadata()).doesNotContainKey("grpc-port");
        });
    }

    @Test
    @DisplayName("GIVEN the properties WHEN bound SHOULD expose the documented defaults and overrides")
    void GIVEN_properties_WHEN_bound_SHOULD_bind() {
        runner.withPropertyValues(
                        "shiguchi.discovery.client.expose-grpc-port=true",
                        "shiguchi.discovery.client.grpc-port=9090",
                        "shiguchi.discovery.client.metadata-key=rpc.port",
                        "shiguchi.discovery.client.type-metadata=false",
                        "shiguchi.discovery.client.update-on-bind=false")
                .run(ctx -> {
                    DiscoveryClientProperties p = ctx.getBean(DiscoveryClientProperties.class);
                    assertThat(p.isExposeGrpcPort()).isTrue();
                    assertThat(p.getGrpcPort()).isEqualTo(9090);
                    assertThat(p.getMetadataKey()).isEqualTo("rpc.port");
                    assertThat(p.isTypeMetadata()).isFalse();
                    assertThat(p.isUpdateOnBind()).isFalse();
                });
        runner.withPropertyValues("shiguchi.discovery.client.expose-grpc-port=true")
                .run(ctx -> {
                    DiscoveryClientProperties p = ctx.getBean(DiscoveryClientProperties.class);
                    assertThat(p.getGrpcPort()).isNull();
                    assertThat(p.getMetadataKey()).isEqualTo("grpc-port");
                    assertThat(p.isTypeMetadata()).isTrue();
                    assertThat(p.isUpdateOnBind()).isTrue();
                });
    }

    @Test
    @DisplayName("GIVEN an explicit port WHEN the instance registers SHOULD carry grpc-port and shiguchi.type=grpc")
    void GIVEN_explicit_port_WHEN_registering_SHOULD_publish_key_and_type() {
        runner.withPropertyValues(
                        "shiguchi.discovery.client.expose-grpc-port=true", "shiguchi.discovery.client.grpc-port=9090")
                .run(ctx -> {
                    assertThat(info(ctx).getMetadata())
                            .containsEntry("grpc-port", "9090")
                            .containsEntry(DiscoveryMetadata.DEFAULT_PREFIX + DiscoveryMetadata.TYPE, "grpc");
                    assertThat(DiscoveryMetadata.grpcPort(info(ctx).getMetadata()))
                            .hasValue(9090);
                });
    }

    @Test
    @DisplayName("GIVEN type-metadata off and a custom key WHEN registering SHOULD publish only that key")
    void GIVEN_custom_key_and_no_type_WHEN_registering_SHOULD_publish_only_the_port() {
        runner.withPropertyValues(
                        "shiguchi.discovery.client.expose-grpc-port=true",
                        "shiguchi.discovery.client.grpc-port=9090",
                        "shiguchi.discovery.client.metadata-key=my.rpc.port",
                        "shiguchi.discovery.client.type-metadata=false")
                .run(ctx -> assertThat(info(ctx).getMetadata())
                        .containsEntry("my.rpc.port", "9090")
                        .doesNotContainKey("grpc-port")
                        .doesNotContainKey("shiguchi.type"));
    }

    @Test
    @DisplayName("GIVEN a supplier bean WHEN registering SHOULD publish its port")
    void GIVEN_supplier_bean_WHEN_registering_SHOULD_use_it() {
        runner.withPropertyValues("shiguchi.discovery.client.expose-grpc-port=true")
                .withBean(GrpcServerPortSupplier.class, () -> () -> OptionalInt.of(7070))
                .run(ctx -> assertThat(info(ctx).getMetadata()).containsEntry("grpc-port", "7070"));
    }

    @Test
    @DisplayName("GIVEN the app already set grpc-port WHEN a detected port differs SHOULD leave the app's value")
    void GIVEN_user_key_WHEN_detected_port_differs_SHOULD_not_overwrite() {
        runner.withPropertyValues("shiguchi.discovery.client.expose-grpc-port=true")
                .withBean(GrpcServerPortSupplier.class, () -> () -> OptionalInt.of(7070))
                .withBean(UserMetadata.class)
                .run(ctx -> {
                    ctx.getBean(GrpcPortPublisher.class).publish(7070);
                    assertThat(info(ctx).getMetadata()).containsEntry("grpc-port", "6060");
                });
    }

    @Test
    @DisplayName("GIVEN the app already set grpc-port WHEN the explicit property is set SHOULD overwrite it")
    void GIVEN_user_key_WHEN_explicit_property_SHOULD_overwrite() {
        runner.withPropertyValues(
                        "shiguchi.discovery.client.expose-grpc-port=true", "shiguchi.discovery.client.grpc-port=9090")
                .withBean(UserMetadata.class)
                .run(ctx -> assertThat(info(ctx).getMetadata()).containsEntry("grpc-port", "9090"));
    }

    @Test
    @DisplayName("GIVEN a port known only after startup WHEN published SHOULD update the live registration")
    void GIVEN_late_port_WHEN_published_SHOULD_update_application_info_metadata() {
        runner.withPropertyValues("shiguchi.discovery.client.expose-grpc-port=true")
                .run(ctx -> {
                    ApplicationInfoManager manager = ctx.getBean(ApplicationInfoManager.class);
                    assertThat(manager.getInfo().getMetadata()).doesNotContainKey("grpc-port");

                    boolean published = ctx.getBean(GrpcPortPublisher.class).publish(51234);

                    assertThat(published).isTrue();
                    assertThat(manager.getInfo().getMetadata())
                            .containsEntry("grpc-port", "51234")
                            .containsEntry("shiguchi.type", "grpc");
                    assertThat(manager.getInfo().isDirty()).isTrue();
                    // a later rebind (the port changed) replaces what this publisher wrote
                    ctx.getBean(GrpcPortPublisher.class).publish(51235);
                    assertThat(manager.getInfo().getMetadata()).containsEntry("grpc-port", "51235");
                });
    }

    @Test
    @DisplayName("GIVEN a started io.grpc.Server on port 0 WHEN the app is ready SHOULD publish its bound port")
    void GIVEN_grpc_server_bean_WHEN_ready_SHOULD_publish_bound_port() throws Exception {
        Server server = ServerBuilder.forPort(0)
                .addService(new HealthStatusManager().getHealthService())
                .build()
                .start();
        try {
            runner.withPropertyValues("shiguchi.discovery.client.expose-grpc-port=true")
                    .withBean(Server.class, () -> server)
                    .run(ctx -> {
                        ctx.getBean(GrpcPortRegistrationAutoConfiguration.GrpcPortReadyListener.class)
                                .onReady();
                        assertThat(info(ctx).getMetadata())
                                .containsEntry("grpc-port", String.valueOf(server.getPort()));
                    });
        } finally {
            server.shutdownNow();
        }
    }
}
