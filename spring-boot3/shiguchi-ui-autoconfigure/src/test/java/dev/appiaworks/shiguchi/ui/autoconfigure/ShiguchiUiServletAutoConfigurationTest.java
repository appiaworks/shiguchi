package dev.appiaworks.shiguchi.ui.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.security.GatewaySecurityConfig;
import dev.appiaworks.shiguchi.ui.GrpcUiController;
import dev.appiaworks.shiguchi.ui.GrpcUiService;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * BDD tests for {@link ShiguchiUiServletAutoConfiguration} (Spring Boot 3 line). The servlet UI
 * itself is exercised end to end in the webmvc starter's tests, where Spring MVC is on the
 * classpath; here (WebFlux only, no Spring MVC) the point is what must NOT happen: the
 * configuration backs off outside a servlet application with Spring MVC, instead of scanning in
 * the gateway's reactive security chain and failing the boot, and its scan filter matches exactly
 * the classes it names.
 */
@DisplayName("ShiguchiUiServletAutoConfiguration (Boot 3)")
class ShiguchiUiServletAutoConfigurationTest {

    private static final AutoConfigurations UI_AUTO_CONFIGURATIONS =
            AutoConfigurations.of(ShiguchiUiAutoConfiguration.class, ShiguchiUiServletAutoConfiguration.class);

    @Test
    @DisplayName(
            "GIVEN a servlet application without Spring MVC WHEN the context starts SHOULD back off without the UI "
                    + "or the gateway's reactive security chain")
    void given_servletAppWithoutSpringMvc_when_contextStarts_should_backOff() {
        new WebApplicationContextRunner()
                .withConfiguration(UI_AUTO_CONFIGURATIONS)
                .withPropertyValues("shiguchi.ui.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ShiguchiUiServletAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean(GrpcUiService.class);
                    assertThat(context).doesNotHaveBean(GrpcUiController.class);
                    assertThat(context).doesNotHaveBean(GatewaySecurityConfig.class);
                    assertThat(context).doesNotHaveBean(SecurityWebFilterChain.class);
                });
    }

    @Test
    @DisplayName("GIVEN a reactive application WHEN the context starts SHOULD not apply the servlet configuration")
    void given_reactiveApp_when_contextStarts_should_notApplyServletConfiguration() {
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ShiguchiUiServletAutoConfiguration.class))
                .withPropertyValues("shiguchi.ui.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ShiguchiUiServletAutoConfiguration.class);
                    assertThat(context).doesNotHaveBean(GrpcUiService.class);
                });
    }

    @Test
    @DisplayName("GIVEN the scan filter WHEN matching class metadata SHOULD match only the listed class names")
    void given_scanFilter_when_matching_should_matchOnlyListedNames() throws IOException {
        var readers = new SimpleMetadataReaderFactory();
        var filter = new ServletScanExcludes();

        assertThat(filter.match(readers.getMetadataReader(GrpcUiController.class.getName()), readers))
                .isTrue();
        assertThat(filter.match(readers.getMetadataReader(GrpcUiService.class.getName()), readers))
                .isFalse();
    }

    @Test
    @DisplayName(
            "GIVEN the exclude list WHEN reading it SHOULD name the reactive-only gateway classes without duplicates")
    void given_excludeList_when_reading_should_nameReactiveOnlyClasses() {
        assertThat(ServletScanExcludes.CLASS_NAMES)
                .doesNotHaveDuplicates()
                .contains(
                        "dev.appiaworks.shiguchi.security.GatewaySecurityConfig",
                        "dev.appiaworks.shiguchi.gateway.GrpcMetadataPopulator",
                        "dev.appiaworks.shiguchi.ui.GrpcUiConfig",
                        "dev.appiaworks.shiguchi.ui.GrpcUiController");
    }
}
