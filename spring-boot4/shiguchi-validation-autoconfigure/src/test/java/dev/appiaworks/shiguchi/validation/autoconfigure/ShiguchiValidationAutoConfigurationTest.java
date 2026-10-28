package dev.appiaworks.shiguchi.validation.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.validation.GatewayValidationFilter;
import dev.appiaworks.shiguchi.validation.ProtoValidator;
import dev.appiaworks.shiguchi.validation.ValidationServerInterceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * BDD tests for {@link ShiguchiValidationAutoConfiguration} (Spring Boot 4 line): the two
 * independent validation options — gateway-layer ({@code shiguchi.validation.gateway-enabled})
 * and downstream server-layer ({@code shiguchi.validation.server-enabled}) — and the shared
 * {@link ProtoValidator} bean gated by {@link OnValidationEnabledCondition}, which is satisfied
 * when either option is on.
 *
 * <p>Mirrors {@code ShiguchiValidationAutoConfigurationTest} in
 * spring-boot3/shiguchi-validation-autoconfigure. {@link ShiguchiValidationAutoConfiguration} and
 * {@link OnValidationEnabledCondition} are byte-identical between the two Boot lines, so no Boot
 * API divergence applies here.
 */
@DisplayName("ShiguchiValidationAutoConfiguration (Boot 4)")
class ShiguchiValidationAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ShiguchiValidationAutoConfiguration.class));

    @Test
    @DisplayName("GIVEN both validation options unset WHEN the context starts SHOULD register no validation beans")
    void given_bothOptionsUnset_when_contextStarts_should_registerNoValidationBeans() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(ProtoValidator.class);
            assertThat(context).doesNotHaveBean(GatewayValidationFilter.class);
            assertThat(context).doesNotHaveBean(ValidationServerInterceptor.class);
        });
    }

    @Test
    @DisplayName(
            "GIVEN gateway-enabled=true WHEN the context starts SHOULD register ProtoValidator and GatewayValidationFilter but not the server interceptor")
    void given_gatewayEnabled_when_contextStarts_should_registerGatewayFilterOnly() {
        runner.withPropertyValues("shiguchi.validation.gateway-enabled=true").run(context -> {
            assertThat(context).hasSingleBean(ProtoValidator.class);
            assertThat(context).hasSingleBean(GatewayValidationFilter.class);
            assertThat(context).doesNotHaveBean(ValidationServerInterceptor.class);
        });
    }

    @Test
    @DisplayName(
            "GIVEN server-enabled=true WHEN the context starts SHOULD register ProtoValidator and ValidationServerInterceptor but not the gateway filter")
    void given_serverEnabled_when_contextStarts_should_registerServerInterceptorOnly() {
        runner.withPropertyValues("shiguchi.validation.server-enabled=true").run(context -> {
            assertThat(context).hasSingleBean(ProtoValidator.class);
            assertThat(context).hasSingleBean(ValidationServerInterceptor.class);
            assertThat(context).doesNotHaveBean(GatewayValidationFilter.class);
        });
    }

    @Test
    @DisplayName(
            "GIVEN both gateway-enabled and server-enabled are true WHEN the context starts SHOULD register a single shared ProtoValidator plus both beans")
    void given_bothOptionsEnabled_when_contextStarts_should_registerBothBeansSharingOneValidator() {
        runner.withPropertyValues("shiguchi.validation.gateway-enabled=true", "shiguchi.validation.server-enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(ProtoValidator.class);
                    assertThat(context).hasSingleBean(GatewayValidationFilter.class);
                    assertThat(context).hasSingleBean(ValidationServerInterceptor.class);
                });
    }

    @Test
    @DisplayName(
            "GIVEN gateway-enabled=false explicitly WHEN the context starts SHOULD back off from every validation bean")
    void given_gatewayEnabledExplicitlyFalse_when_contextStarts_should_backOff() {
        runner.withPropertyValues("shiguchi.validation.gateway-enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(ProtoValidator.class);
            assertThat(context).doesNotHaveBean(GatewayValidationFilter.class);
        });
    }
}
