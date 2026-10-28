package dev.appiaworks.shiguchi.ui.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.protobuf.extras.serde.WireJsonTranscoder;
import dev.appiaworks.shiguchi.authz.GatewayAuthorizer;
import dev.appiaworks.shiguchi.authz.ScopeGatewayAuthorizer;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.gateway.GrpcMetadataPopulator;
import dev.appiaworks.shiguchi.grpc.GrpcDescriptorCache;
import dev.appiaworks.shiguchi.rest.GrpcRequestBuilder;
import dev.appiaworks.shiguchi.rest.ReactiveGrpcDispatcher;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.serde.DynamicProtoSerde;
import dev.appiaworks.shiguchi.serde.SerdeSelector;
import dev.appiaworks.shiguchi.serde.StaticSerdeDetector;
import dev.appiaworks.shiguchi.ui.GrpcUiConfig;
import dev.appiaworks.shiguchi.ui.GrpcUiController;
import dev.appiaworks.shiguchi.ui.GrpcUiSchemaBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.ReactiveUserDetailsServiceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.reactive.function.server.RouterFunction;

/**
 * BDD tests for {@link ShiguchiUiAutoConfiguration} (Spring Boot 4 line): whether the embedded gRPC
 * UI beans get registered, gated only by {@code shiguchi.ui.enabled}, and that its component
 * scan of {@code dev.appiaworks.shiguchi} — needed so the UI starter works without the gateway starter —
 * carries the same safeguard as the gateway's: {@code @SpringBootConfiguration} classes under that
 * package are not imported.
 *
 * <p>Identical to its counterpart in the other Boot line. {@code TestSupportConfig} supplies only
 * what a real standalone application would get from its own wiring ({@link GrpcRouteRegistry},
 * {@link ReactiveGrpcDispatcher}, {@link GrpcMetadataPopulator} — beans the scan itself provides in
 * production); the gateway defaults come from the imported {@code GatewayDefaults}, and the metrics
 * interceptor falls back to Micrometer's global registry, so no {@code MeterRegistry} bean is needed.
 */
@DisplayName("ShiguchiUiAutoConfiguration (Boot 4)")
class ShiguchiUiAutoConfigurationTest {

    @Configuration
    @EnableConfigurationProperties(GatewayProperties.class)
    static class TestSupportConfig {

        @Bean
        GrpcRouteRegistry grpcRouteRegistry() {
            return new GrpcRouteRegistry();
        }

        @Bean
        ReactiveGrpcDispatcher reactiveGrpcDispatcher(GatewayProperties props) {
            var serde = new SerdeSelector(new StaticSerdeDetector(), new DynamicProtoSerde(new GrpcDescriptorCache()));
            return new ReactiveGrpcDispatcher(
                    new GrpcRequestBuilder(), serde, new GrpcDescriptorCache(), new WireJsonTranscoder(), props);
        }

        @Bean
        GrpcMetadataPopulator grpcMetadataPopulator(GatewayProperties props) {
            return new GrpcMetadataPopulator(props);
        }
    }

    /**
     * A host application overriding one of the gateway defaults. Deliberately not a
     * {@code @Configuration}: this test package lives under {@code dev.appiaworks.shiguchi}, so the scan
     * under test would otherwise register it in every test; as a plain {@code @Bean} holder it only
     * takes part where it is passed to {@code withUserConfiguration}.
     */
    static class CustomAuthorizerConfig {
        @Bean
        GatewayAuthorizer customAuthorizer() {
            return new CustomAuthorizer();
        }
    }

    static class CustomAuthorizer extends ScopeGatewayAuthorizer {}

    /**
     * Lives under {@code dev.appiaworks.shiguchi}, so the scan sees it. A real application class here would
     * carry {@code @EnableAutoConfiguration} and re-enter auto-configuration mid-flight; the marker
     * bean proves the exclude filter kept it out.
     */
    @SpringBootConfiguration
    static class StrayApplicationClass {
        @Bean
        String strayMarker() {
            return "should never be registered by the UI scan";
        }
    }

    /**
     * Boot line 4's reactive security auto-configurations ride along: the scanned {@code
     * GatewaySecurityConfig} needs Boot's {@code ServerHttpSecurity} bean, exactly as in a real
     * application where security is on the classpath through shiguchi-rest.
     */
    private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
            .withUserConfiguration(TestSupportConfig.class)
            .withConfiguration(AutoConfigurations.of(
                    ShiguchiUiAutoConfiguration.class,
                    ReactiveWebSecurityAutoConfiguration.class,
                    ReactiveUserDetailsServiceAutoConfiguration.class));

    @Test
    @DisplayName(
            "GIVEN shiguchi.ui.enabled=true WHEN the context starts SHOULD register the UI controller, schema builder and router")
    void given_uiEnabled_when_contextStarts_should_registerUiBeans() {
        runner.withPropertyValues("shiguchi.ui.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(GrpcUiController.class);
            assertThat(context).hasSingleBean(GrpcUiSchemaBuilder.class);
            assertThat(context).hasSingleBean(GrpcUiConfig.class);
            assertThat(context).hasBean("grpcUiRouterFunction");
            assertThat(context.getBean("grpcUiRouterFunction")).isInstanceOf(RouterFunction.class);
        });
    }

    @Test
    @DisplayName("GIVEN shiguchi.ui.enabled=false WHEN the context starts SHOULD back off and register no UI beans")
    void given_uiDisabled_when_contextStarts_should_backOff() {
        runner.withPropertyValues("shiguchi.ui.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(GrpcUiController.class);
            assertThat(context).doesNotHaveBean(GrpcUiSchemaBuilder.class);
            assertThat(context).doesNotHaveBean(GrpcUiConfig.class);
            assertThat(context).doesNotHaveBean("grpcUiRouterFunction");
        });
    }

    @Test
    @DisplayName("GIVEN shiguchi.ui.enabled is unset WHEN the context starts SHOULD back off (default is disabled)")
    void given_uiPropertyUnset_when_contextStarts_should_backOffByDefault() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(GrpcUiController.class);
            assertThat(context).doesNotHaveBean(GrpcUiConfig.class);
        });
    }

    @Test
    @DisplayName(
            "GIVEN no gateway auto-configuration WHEN the UI auto-configuration runs alone SHOULD still find the route "
                    + "registry and loaders the console needs")
    void given_standaloneUiStarter_when_contextStarts_should_scanGatewayCoreBeans() {
        runner.withPropertyValues("shiguchi.ui.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(GrpcRouteRegistry.class);
            assertThat(context).hasBean("dynamicRouteLoader");
            assertThat(context).hasSingleBean(GatewayProperties.class);
        });
    }

    @Test
    @DisplayName(
            "GIVEN no gateway auto-configuration WHEN the UI auto-configuration runs alone SHOULD supply the gateway "
                    + "defaults the scanned components need")
    void given_standaloneUiStarter_when_contextStarts_should_importGatewayDefaults() {
        runner.withPropertyValues("shiguchi.ui.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(GatewayAuthorizer.class);
            assertThat(context.getBean(GatewayAuthorizer.class)).isExactlyInstanceOf(ScopeGatewayAuthorizer.class);
            assertThat(context).hasSingleBean(dev.appiaworks.shiguchi.rest.GrpcStatusMapper.class);
            assertThat(context).hasSingleBean(dev.appiaworks.shiguchi.gateway.GatewayErrorRenderer.class);
        });
    }

    @Test
    @DisplayName("GIVEN a host application's own GatewayAuthorizer WHEN the context starts SHOULD back the default off")
    void given_customAuthorizer_when_contextStarts_should_backOffDefault() {
        runner.withUserConfiguration(CustomAuthorizerConfig.class)
                .withPropertyValues("shiguchi.ui.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(GatewayAuthorizer.class);
                    assertThat(context.getBean(GatewayAuthorizer.class)).isInstanceOf(CustomAuthorizer.class);
                });
    }

    @Test
    @DisplayName(
            "GIVEN a standalone UI with Boot's reactive security auto-configuration WHEN the context starts SHOULD "
                    + "register the gateway's PERMIT_ALL chain, not Boot's HTTP Basic default")
    void given_standaloneUiWithBootSecurity_when_contextStarts_should_registerGatewayChain() {
        // The gateway's chain and default user are @ConditionalOnMissingBean and so are Boot's, so
        // auto-configuration ordering decides who wins.
        runner.withPropertyValues("shiguchi.ui.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(SecurityWebFilterChain.class);
            assertThat(context).hasBean("securityFilterChain");
            assertThat(context.getBean(ReactiveUserDetailsService.class))
                    .as("the generated-password default user is suppressed")
                    .isInstanceOf(MapReactiveUserDetailsService.class);
        });
    }

    @Test
    @DisplayName(
            "GIVEN a @SpringBootConfiguration class under dev.appiaworks.shiguchi WHEN the UI scan runs SHOULD not import it")
    void given_strayApplicationClassInPackage_when_scanRuns_should_excludeIt() {
        runner.withPropertyValues("shiguchi.ui.enabled=true").run(context -> {
            assertThat(context).doesNotHaveBean("strayMarker");
            assertThat(context).doesNotHaveBean(StrayApplicationClass.class);
        });
    }
}
