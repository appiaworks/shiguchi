package dev.appiaworks.shiguchi.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.GatewayPropertiesValidator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.cloud.gateway.route.InMemoryRouteDefinitionRepository;
import org.springframework.cloud.gateway.route.RouteDefinitionWriter;
import org.springframework.validation.Validator;

/**
 * The gateway's configuration is validated while Spring Boot binds it (Boot 4): a service
 * without an address, a port out of range or a {@code PB_FILE} service without a descriptor path
 * fails the context with the property path in the message, and a host application's own
 * {@code configurationPropertiesValidator} takes precedence.
 */
@DisplayName("Configuration validation at bind time (Boot 4)")
class GatewayConfigValidationTest {

    private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(RouteDefinitionWriter.class, InMemoryRouteDefinitionRepository::new)
            .withConfiguration(AutoConfigurations.of(
                    ShiguchiAutoConfiguration.class,
                    org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration
                            .class,
                    org.springframework.boot.security.autoconfigure.ReactiveUserDetailsServiceAutoConfiguration.class));

    @Test
    @DisplayName(
            "GIVEN a service with a name but no host, discovery-name or target WHEN the context starts SHOULD fail naming shiguchi.services[0].host")
    void given_serviceWithoutAddress_when_contextStarts_should_failNamingTheProperty() {
        runner.withPropertyValues("shiguchi.services[0].name=orders").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining("services[0].host")
                    .hasMessageContaining("host + port, discovery-name or target");
        });
    }

    @Test
    @DisplayName(
            "GIVEN a PB_FILE service without a descriptor path WHEN the context starts SHOULD fail naming pb-descriptor-path")
    void given_pbFileWithoutPath_when_contextStarts_should_failNamingTheProperty() {
        runner.withPropertyValues(
                        "shiguchi.services[0].name=orders",
                        "shiguchi.services[0].mode=PB_FILE",
                        "shiguchi.services[0].host=localhost",
                        "shiguchi.services[0].port=9090")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("pbDescriptorPath");
                });
    }

    @Test
    @DisplayName("GIVEN a well-formed service WHEN the context starts SHOULD bind and register the gateway's validator")
    void given_validService_when_contextStarts_should_bindAndRegisterValidator() {
        runner.withPropertyValues(
                        "shiguchi.services[0].name=orders",
                        "shiguchi.services[0].mode=PB_FILE",
                        "shiguchi.services[0].pb-descriptor-path=/tmp/orders.pb",
                        "shiguchi.services[0].discovery-name=orders")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("configurationPropertiesValidator", Validator.class))
                            .isInstanceOf(GatewayPropertiesValidator.class);
                    assertThat(context.getBean(GatewayProperties.class)
                                    .getServices()
                                    .get(0)
                                    .getName())
                            .isEqualTo("orders");
                });
    }

    @Test
    @DisplayName(
            "GIVEN the host application defines its own configurationPropertiesValidator WHEN the context starts SHOULD keep the application's bean")
    void given_userValidator_when_contextStarts_should_backOff() {
        Validator mine = new Validator() {
            @Override
            public boolean supports(Class<?> clazz) {
                return false;
            }

            @Override
            public void validate(Object target, org.springframework.validation.Errors errors) {}
        };
        runner.withBean("configurationPropertiesValidator", Validator.class, () -> mine)
                .withPropertyValues("shiguchi.services[0].name=orders")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("configurationPropertiesValidator"))
                            .isSameAs(mine);
                });
    }
}
