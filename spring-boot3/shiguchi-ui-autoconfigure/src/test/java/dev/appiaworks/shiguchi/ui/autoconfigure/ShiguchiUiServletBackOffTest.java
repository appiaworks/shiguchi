package dev.appiaworks.shiguchi.ui.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.security.GatewaySecurityConfig;
import dev.appiaworks.shiguchi.ui.GrpcUiController;
import dev.appiaworks.shiguchi.ui.autoconfigure.ShiguchiUiServletBackOffAutoConfiguration.UiServletBackOffNotice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * BDD tests for the UI starter in a servlet (Spring MVC) application (Spring Boot 3 line): the
 * UI is WebFlux-only, so the starter must back off and say so rather than fail the boot on the
 * gateway's reactive security chain ({@code ServerHttpSecurity} not found).
 */
@DisplayName("ShiguchiUiAutoConfiguration on a servlet application (Boot 3)")
@ExtendWith(OutputCaptureExtension.class)
class ShiguchiUiServletBackOffTest {

    private static final AutoConfigurations UI_AUTO_CONFIGURATIONS =
            AutoConfigurations.of(ShiguchiUiAutoConfiguration.class, ShiguchiUiServletBackOffAutoConfiguration.class);

    private final WebApplicationContextRunner servlet =
            new WebApplicationContextRunner().withConfiguration(UI_AUTO_CONFIGURATIONS);

    @Test
    @DisplayName(
            "GIVEN a servlet application with shiguchi.ui.enabled=true WHEN the context starts SHOULD start without "
                    + "the UI or the gateway's reactive security chain")
    void given_servletAppWithUiEnabled_when_contextStarts_should_startWithoutReactiveBeans() {
        servlet.withPropertyValues("shiguchi.ui.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(GrpcUiController.class);
            assertThat(context).doesNotHaveBean(GatewaySecurityConfig.class);
            assertThat(context).doesNotHaveBean(SecurityWebFilterChain.class);
            assertThat(context).hasSingleBean(UiServletBackOffNotice.class);
        });
    }

    @Test
    @DisplayName(
            "GIVEN a servlet application with shiguchi.ui.enabled=true WHEN the context starts SHOULD warn that the UI "
                    + "needs a reactive application")
    void given_servletAppWithUiEnabled_when_contextStarts_should_warnUiNotServed(CapturedOutput output) {
        servlet.withPropertyValues("shiguchi.ui.enabled=true")
                .run(context -> assertThat(output)
                        .contains("needs a reactive (WebFlux) web application")
                        .contains("spring.main.web-application-type=reactive"));
    }

    @Test
    @DisplayName("GIVEN a servlet application with the UI disabled WHEN the context starts SHOULD stay silent")
    void given_servletAppWithUiDisabled_when_contextStarts_should_notWarn() {
        servlet.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(UiServletBackOffNotice.class);
        });
    }

    @Test
    @DisplayName("GIVEN a reactive application with shiguchi.ui.enabled=true WHEN the context starts SHOULD not warn")
    void given_reactiveAppWithUiEnabled_when_contextStarts_should_notRegisterNotice() {
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ShiguchiUiServletBackOffAutoConfiguration.class))
                .withPropertyValues("shiguchi.ui.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(UiServletBackOffNotice.class));
    }
}
