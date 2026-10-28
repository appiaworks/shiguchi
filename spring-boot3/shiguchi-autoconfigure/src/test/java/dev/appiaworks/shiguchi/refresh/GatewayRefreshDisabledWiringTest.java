package dev.appiaworks.shiguchi.refresh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Complements {@link GatewayRefreshSchedulerWiringTest} (refresh enabled): with
 * {@code shiguchi.refresh.enabled=false} (the default), {@link GatewayRefreshScheduler}'s
 * {@code @ConditionalOnProperty} must keep it — and therefore its {@code @Scheduled} polling loop
 * and the actuator endpoint, which is {@code @ConditionalOnBean(GatewayRefreshScheduler.class)} —
 * out of the context entirely, so nothing schedules a background refresh.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("GatewayRefreshScheduler wiring with refresh disabled")
class GatewayRefreshDisabledWiringTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName(
            "GIVEN shiguchi.refresh.enabled is left at its default (false) WHEN the context starts SHOULD register neither the scheduler nor the refresh endpoint")
    void given_refreshEnabledDefaultsFalse_when_contextStarts_should_registerNeitherSchedulerNorEndpoint() {
        assertThatThrownBy(() -> context.getBean(GatewayRefreshScheduler.class))
                .isInstanceOf(NoSuchBeanDefinitionException.class);
        assertThatThrownBy(() -> context.getBean(GatewayRefreshController.class))
                .isInstanceOf(NoSuchBeanDefinitionException.class);
    }

    @Test
    @DisplayName("GIVEN refresh is disabled WHEN the context starts SHOULD still start successfully "
            + "(the gateway works fine with schema refresh entirely off)")
    void given_refreshDisabled_when_contextStarts_should_stillStartSuccessfully() {
        assertThat(context.getStartupDate()).isPositive();
    }
}
