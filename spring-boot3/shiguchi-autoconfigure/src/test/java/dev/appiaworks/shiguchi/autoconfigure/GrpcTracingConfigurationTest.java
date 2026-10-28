package dev.appiaworks.shiguchi.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.opentelemetry.GrpcOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Behavioral tests for {@link GrpcTracingConfiguration}.
 *
 * <p>Tests verify that the gRPC OpenTelemetry plugin is registered only when an
 * {@link OpenTelemetry} bean is present in the context, honoring the
 * {@code @ConditionalOnBean} class-level guard.
 *
 * <p>Verified behaviour:
 * <ul>
 *   <li>GrpcTracingConfiguration is not instantiated when OpenTelemetry bean is absent;</li>
 *   <li>GrpcOpenTelemetry bean is not created when OpenTelemetry bean is absent;</li>
 *   <li>GrpcOpenTelemetry bean is created and available when OpenTelemetry bean is present;</li>
 *   <li>context refresh succeeds when OpenTelemetry bean is present.</li>
 * </ul>
 */
@DisplayName("GrpcTracingConfiguration")
class GrpcTracingConfigurationTest {

    @Test
    @DisplayName(
            "GIVEN no OpenTelemetry bean present WHEN context is refreshed SHOULD not create GrpcOpenTelemetry bean")
    void GIVEN_noOpenTelemetryBean_WHEN_contextRefreshes_SHOULD_notCreateGrpcOpenTelemetry() {
        // Arrange
        ApplicationContextRunner runner =
                new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(GrpcTracingConfiguration.class));

        // Act & Assert
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(GrpcOpenTelemetry.class);
        });
    }

    @Test
    @DisplayName(
            "GIVEN no OpenTelemetry bean present WHEN context is refreshed SHOULD not instantiate GrpcTracingConfiguration")
    void GIVEN_noOpenTelemetryBean_WHEN_contextRefreshes_SHOULD_notInstantiateConfiguration() {
        // Arrange
        ApplicationContextRunner runner =
                new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(GrpcTracingConfiguration.class));

        // Act & Assert
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(GrpcTracingConfiguration.class);
        });
    }

    @Test
    @DisplayName("GIVEN OpenTelemetry bean is present WHEN context is refreshed SHOULD create GrpcOpenTelemetry bean")
    void GIVEN_openTelemetryBeanPresent_WHEN_contextRefreshes_SHOULD_createGrpcOpenTelemetry() {
        // Arrange
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(OpenTelemetryPresentConfig.class)
                .withConfiguration(AutoConfigurations.of(GrpcTracingConfiguration.class));

        // Act & Assert
        runner.run(context -> {
            assertThat(context).hasSingleBean(GrpcOpenTelemetry.class);
        });
    }

    @Test
    @DisplayName(
            "GIVEN OpenTelemetry bean is present WHEN context is refreshed SHOULD complete successfully without errors")
    void GIVEN_openTelemetryBeanPresent_WHEN_contextRefreshes_SHOULD_completeSuccessfully() {
        // Arrange
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(OpenTelemetryPresentConfig.class)
                .withConfiguration(AutoConfigurations.of(GrpcTracingConfiguration.class));

        // Act & Assert
        runner.run(context -> {
            assertThat(context).hasNotFailed();
        });
    }

    // -------------------------------------------------------------------------
    // Test fixtures
    // -------------------------------------------------------------------------

    @Configuration
    static class OpenTelemetryPresentConfig {
        @Bean
        OpenTelemetry openTelemetry() {
            return OpenTelemetry.noop();
        }
    }
}
