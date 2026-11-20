package dev.appiaworks.shiguchi.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import io.grpc.BindableService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@DisplayName("GrpcReflectionAggregator registration")
class GrpcReflectionAggregatorConditionTest {

    @Configuration(proxyBeanMethods = false)
    @Import(GrpcReflectionAggregator.class)
    static class Config {
        @Bean
        GrpcRouteRegistry registry() {
            return new GrpcRouteRegistry();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    @DisplayName("GIVEN the proxy is not enabled WHEN the context starts SHOULD NOT register a gRPC service bean")
    void GIVEN_proxy_not_enabled_WHEN_context_starts_SHOULD_not_register_a_grpc_service_bean() {
        runner.run(context -> assertThat(context)
                .doesNotHaveBean(GrpcReflectionAggregator.class)
                .getBeans(BindableService.class)
                .isEmpty());
    }

    @Test
    @DisplayName("GIVEN shiguchi.grpc.enabled=true WHEN the context starts SHOULD register the aggregator")
    void GIVEN_proxy_enabled_WHEN_context_starts_SHOULD_register_the_aggregator() {
        runner.withPropertyValues("shiguchi.grpc.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(GrpcReflectionAggregator.class));
    }
}
