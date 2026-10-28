package dev.appiaworks.shiguchi.refresh;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.gateway.ResponseCache;
import dev.appiaworks.shiguchi.routing.SchemaReloadListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Regression for the refresh scheduler failing to instantiate without the GraphQL, OpenAPI and
 * UI modules on the classpath. Those modules are {@code compileOnly} for the autoconfigure
 * module, so this test's runtime classpath has none of them: exactly the deployment shape that
 * used to blow up at startup with "Resolution of declared constructors ... failed".
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"shiguchi.refresh.enabled=true", "shiguchi.refresh.interval-seconds=3600"})
@DisplayName("GatewayRefreshScheduler wiring without optional surface modules")
class GatewayRefreshSchedulerWiringTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("Given refresh enabled and neither GraphQL nor OpenAPI nor UI on the classpath, "
            + "when the context starts, should create the scheduler and the refresh endpoint")
    void given_refreshEnabledWithoutSurfaceModules_when_contextStarts_should_createSchedulerAndEndpoint() {
        assertThat(context.getBean(GatewayRefreshScheduler.class)).isNotNull();
        assertThat(context.getBean(GatewayRefreshController.class)).isNotNull();
        // No surface module is present, so the only listener left is the REST response cache,
        // which lives in shiguchi-rest and empties itself on reload.
        assertThat(context.getBeansOfType(SchemaReloadListener.class).values())
                .allSatisfy(listener -> assertThat(listener).isInstanceOf(ResponseCache.class));
    }

    @Test
    @DisplayName("Given no DYNAMIC or REST_DOWNSTREAM services, when refreshAll runs, should complete with no changes")
    void given_noRefreshableServices_when_refreshAll_should_returnEmpty() {
        assertThat(context.getBean(GatewayRefreshScheduler.class).refreshAll()).isEmpty();
    }
}
