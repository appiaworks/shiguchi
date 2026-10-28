package dev.appiaworks.shiguchi.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.DescriptorProtos.MethodOptions;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import io.grpc.MethodDescriptor.MethodType;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RetryServiceConfig")
class RetryServiceConfigTest {

    private static ServiceDefinition service(boolean enabled) {
        var svc = new ServiceDefinition();
        svc.setName("orders");
        svc.getRetry().setEnabled(enabled);
        return svc;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> methodConfigs(Map<String, Object> config) {
        return (List<Map<String, Object>>) config.get("methodConfig");
    }

    private static RouteDefinition route(String method, String httpMethod, RouteDefinition.IdempotencyLevel level) {
        var builder = RouteDefinition.builder("orders.OrderService", method, MethodType.UNARY, "")
                .idempotencyLevel(level);
        if (httpMethod != null) builder.httpBinding(httpMethod, "/v1/orders");
        return builder.build();
    }

    @Test
    @DisplayName(
            "GIVEN retry is off WHEN building the service config SHOULD produce none, leaving the channel on gRPC defaults")
    void given_retryDisabled_when_building_should_produceNoConfig() {
        assertThat(RetryServiceConfig.forService(service(false), Set.of())).isNull();
    }

    @Test
    @DisplayName(
            "GIVEN retry is on WHEN building the service config SHOULD emit a wildcard policy in the shape gRPC accepts")
    void given_retryEnabled_when_building_should_emitWildcardPolicy() {
        var svc = service(true);
        svc.getRetry().setMaxAttempts(4);
        svc.getRetry().setInitialBackoff(Duration.ofMillis(250));
        svc.getRetry().setMaxBackoff(Duration.ofSeconds(2));
        svc.getRetry().setBackoffMultiplier(1.5);

        List<Map<String, Object>> configs = methodConfigs(RetryServiceConfig.forService(svc, Set.of()));

        assertThat(configs).hasSize(1);
        assertThat(configs.get(0).get("name")).isEqualTo(List.of(Map.of()));
        @SuppressWarnings("unchecked")
        Map<String, Object> policy = (Map<String, Object>) configs.get(0).get("retryPolicy");
        // gRPC insists on a Double for maxAttempts and on "<seconds>s" strings for the backoffs.
        assertThat(policy.get("maxAttempts")).isEqualTo(4.0);
        assertThat(policy.get("initialBackoff")).isEqualTo("0.25s");
        assertThat(policy.get("maxBackoff")).isEqualTo("2s");
        assertThat(policy.get("backoffMultiplier")).isEqualTo(1.5);
        assertThat(policy.get("retryableStatusCodes")).isEqualTo(List.of("UNAVAILABLE", "RESOURCE_EXHAUSTED"));
    }

    @Test
    @DisplayName(
            "GIVEN excluded methods WHEN building the service config SHOULD list each before the wildcard with no policy of its own")
    void given_exclusions_when_building_should_listThemFirstWithoutPolicy() {
        List<Map<String, Object>> configs = methodConfigs(RetryServiceConfig.forService(
                service(true), Set.of("orders.OrderService/CreateOrder", "orders.OrderService/Cancel")));

        assertThat(configs).hasSize(3);
        // Sorted, specific entries first: a named entry only wins over the wildcard if gRPC sees it.
        assertThat(configs.get(0).get("name"))
                .isEqualTo(List.of(Map.of("service", "orders.OrderService", "method", "Cancel")));
        assertThat(configs.get(0)).doesNotContainKey("retryPolicy");
        assertThat(configs.get(1).get("name"))
                .isEqualTo(List.of(Map.of("service", "orders.OrderService", "method", "CreateOrder")));
        assertThat(configs.get(1)).doesNotContainKey("retryPolicy");
        assertThat(configs.get(2).get("name")).isEqualTo(List.of(Map.of()));
        assertThat(configs.get(2)).containsKey("retryPolicy");
    }

    @Test
    @DisplayName(
            "GIVEN routes WHEN collecting non-retryable methods SHOULD exclude everything not declared idempotent or bound to a safe verb")
    void given_routes_when_collecting_should_excludeUnsafeMethods() {
        var svc = service(true);
        List<RouteDefinition> routes = List.of(
                route("GetOrder", "GET", RouteDefinition.IdempotencyLevel.IDEMPOTENCY_UNKNOWN),
                route("ListOrders", null, RouteDefinition.IdempotencyLevel.NO_SIDE_EFFECTS),
                route("ReplaceOrder", "PUT", RouteDefinition.IdempotencyLevel.IDEMPOTENT),
                route("CreateOrder", "POST", RouteDefinition.IdempotencyLevel.IDEMPOTENCY_UNKNOWN),
                route("Charge", null, RouteDefinition.IdempotencyLevel.IDEMPOTENCY_UNKNOWN));

        assertThat(RetryServiceConfig.nonRetryableMethods(svc, routes))
                .containsExactly("orders.OrderService/Charge", "orders.OrderService/CreateOrder");
    }

    @Test
    @DisplayName(
            "GIVEN one method reached by a safe and an unsafe route WHEN collecting SHOULD exclude it, because retry is a property of the method")
    void given_mixedBindings_when_collecting_should_excludeTheMethod() {
        var svc = service(true);
        List<RouteDefinition> routes = List.of(
                route("Search", "GET", RouteDefinition.IdempotencyLevel.IDEMPOTENCY_UNKNOWN),
                route("Search", "POST", RouteDefinition.IdempotencyLevel.IDEMPOTENCY_UNKNOWN));

        assertThat(RetryServiceConfig.nonRetryableMethods(svc, routes)).containsExactly("orders.OrderService/Search");
    }

    @Test
    @DisplayName("GIVEN retry-non-idempotent is on WHEN collecting SHOULD exclude nothing, so every method is retried")
    void given_retryNonIdempotent_when_collecting_should_excludeNothing() {
        var svc = service(true);
        svc.getRetry().setRetryNonIdempotent(true);

        assertThat(RetryServiceConfig.nonRetryableMethods(
                        svc,
                        List.of(route("CreateOrder", "POST", RouteDefinition.IdempotencyLevel.IDEMPOTENCY_UNKNOWN))))
                .isEmpty();
    }

    @Test
    @DisplayName("GIVEN a proto method's options WHEN reading idempotency SHOULD map each level, defaulting to unknown")
    void given_methodOptions_when_reading_should_mapIdempotency() {
        assertThat(RetryServiceConfig.idempotencyOf(MethodOptions.newBuilder()
                        .setIdempotencyLevel(MethodOptions.IdempotencyLevel.NO_SIDE_EFFECTS)
                        .build()))
                .isEqualTo(RouteDefinition.IdempotencyLevel.NO_SIDE_EFFECTS);
        assertThat(RetryServiceConfig.idempotencyOf(MethodOptions.newBuilder()
                        .setIdempotencyLevel(MethodOptions.IdempotencyLevel.IDEMPOTENT)
                        .build()))
                .isEqualTo(RouteDefinition.IdempotencyLevel.IDEMPOTENT);
        assertThat(RetryServiceConfig.idempotencyOf(MethodOptions.getDefaultInstance()))
                .isEqualTo(RouteDefinition.IdempotencyLevel.IDEMPOTENCY_UNKNOWN);
        assertThat(RetryServiceConfig.idempotencyOf(null))
                .isEqualTo(RouteDefinition.IdempotencyLevel.IDEMPOTENCY_UNKNOWN);
    }

    @Test
    @DisplayName("GIVEN a status code name WHEN validating SHOULD accept real codes and name the offender otherwise")
    void given_statusCodeName_when_validating_should_rejectBadOnes() {
        assertThat(RetryServiceConfig.validate("unavailable")).isEqualTo(io.grpc.Status.Code.UNAVAILABLE);

        assertThatThrownBy(() -> RetryServiceConfig.validate("UNAVAILBLE"))
                .hasMessageContaining("'UNAVAILBLE' is not a gRPC status code");
        assertThatThrownBy(() -> RetryServiceConfig.validate("OK")).hasMessageContaining("OK is not a failure");
        assertThatThrownBy(() -> RetryServiceConfig.validate(" ")).hasMessageContaining("must not be blank");
    }

    @Test
    @DisplayName(
            "GIVEN retry enabled with default throttling WHEN building the service config SHOULD include retryThrottling with maxTokens 10.0 and tokenRatio 0.1 as Doubles")
    void given_retryEnabledWithDefaultThrottling_when_building_should_includeRetryThrottling() {
        var svc = service(true);
        var config = RetryServiceConfig.forService(svc, Set.of());

        assertThat(config).containsKey("retryThrottling");
        @SuppressWarnings("unchecked")
        Map<String, Object> throttling = (Map<String, Object>) config.get("retryThrottling");
        assertThat(throttling.get("maxTokens")).isEqualTo(10.0).isInstanceOf(Double.class);
        assertThat(throttling.get("tokenRatio")).isEqualTo(0.1).isInstanceOf(Double.class);
    }

    @Test
    @DisplayName(
            "GIVEN retry enabled with custom throttling values WHEN building the service config SHOULD reflect maxTokens and tokenRatio")
    void given_retryEnabledWithCustomThrottling_when_building_should_reflectValues() {
        var svc = service(true);
        svc.getRetry().getThrottling().setMaxTokens(50);
        svc.getRetry().getThrottling().setTokenRatio(0.5);
        var config = RetryServiceConfig.forService(svc, Set.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> throttling = (Map<String, Object>) config.get("retryThrottling");
        assertThat(throttling.get("maxTokens")).isEqualTo(50.0);
        assertThat(throttling.get("tokenRatio")).isEqualTo(0.5);
    }

    @Test
    @DisplayName(
            "GIVEN retry enabled but throttling disabled WHEN building the service config SHOULD not include retryThrottling key")
    void given_retryEnabledThrottlingDisabled_when_building_should_omitRetryThrottling() {
        var svc = service(true);
        svc.getRetry().getThrottling().setEnabled(false);
        var config = RetryServiceConfig.forService(svc, Set.of());

        assertThat(config).containsKey("methodConfig").doesNotContainKey("retryThrottling");
    }

    @Test
    @DisplayName(
            "GIVEN hedging is off WHEN building the service config SHOULD produce none, leaving the channel on gRPC defaults")
    void given_hedgingDisabled_when_building_should_produceNoConfig() {
        var svc = new ServiceDefinition();
        svc.setName("orders");
        svc.getHedging().setEnabled(false);
        assertThat(RetryServiceConfig.forService(svc, Set.of())).isNull();
    }

    @Test
    @DisplayName(
            "GIVEN hedging is on WHEN building the service config SHOULD emit a wildcard policy with hedgingPolicy key and the configured values")
    void given_hedgingEnabled_when_building_should_emitHedgingPolicy() {
        var svc = new ServiceDefinition();
        svc.setName("orders");
        svc.getRetry().setEnabled(false);
        svc.getHedging().setEnabled(true);
        svc.getHedging().setMaxAttempts(3);
        svc.getHedging().setHedgingDelay(java.time.Duration.ofMillis(100));

        List<Map<String, Object>> configs = methodConfigs(RetryServiceConfig.forService(svc, Set.of()));

        assertThat(configs).hasSize(1);
        assertThat(configs.get(0).get("name")).isEqualTo(List.of(Map.of()));
        @SuppressWarnings("unchecked")
        Map<String, Object> policy = (Map<String, Object>) configs.get(0).get("hedgingPolicy");
        assertThat(policy).isNotNull();
        assertThat(policy.get("maxAttempts")).isEqualTo(3.0);
        assertThat(policy.get("hedgingDelay")).isEqualTo("0.1s");
        assertThat(policy.get("nonFatalStatusCodes")).isEqualTo(List.of("UNAVAILABLE"));
    }

    @Test
    @DisplayName(
            "GIVEN hedging enabled with default throttling WHEN building the service config SHOULD include retryThrottling with maxTokens 10.0 and tokenRatio 0.1 as Doubles")
    void given_hedgingEnabledWithDefaultThrottling_when_building_should_includeRetryThrottling() {
        var svc = new ServiceDefinition();
        svc.setName("orders");
        svc.getRetry().setEnabled(false);
        svc.getHedging().setEnabled(true);
        var config = RetryServiceConfig.forService(svc, Set.of());

        assertThat(config).containsKey("retryThrottling");
        @SuppressWarnings("unchecked")
        Map<String, Object> throttling = (Map<String, Object>) config.get("retryThrottling");
        assertThat(throttling.get("maxTokens")).isEqualTo(10.0).isInstanceOf(Double.class);
        assertThat(throttling.get("tokenRatio")).isEqualTo(0.1).isInstanceOf(Double.class);
    }

    @Test
    @DisplayName(
            "GIVEN hedging enabled but throttling disabled WHEN building the service config SHOULD not include retryThrottling key")
    void given_hedgingEnabledThrottlingDisabled_when_building_should_omitRetryThrottling() {
        var svc = new ServiceDefinition();
        svc.setName("orders");
        svc.getRetry().setEnabled(false);
        svc.getHedging().setEnabled(true);
        svc.getHedging().getThrottling().setEnabled(false);
        var config = RetryServiceConfig.forService(svc, Set.of());

        assertThat(config).containsKey("methodConfig").doesNotContainKey("retryThrottling");
    }

    @Test
    @DisplayName(
            "GIVEN hedging enabled with excluded methods WHEN building the service config SHOULD list excluded methods first with no hedgingPolicy")
    void given_hedgingWithExclusions_when_building_should_listExcludedMethodsFirst() {
        var svc = new ServiceDefinition();
        svc.setName("orders");
        svc.getRetry().setEnabled(false);
        svc.getHedging().setEnabled(true);
        List<Map<String, Object>> configs = methodConfigs(RetryServiceConfig.forService(
                svc, Set.of("orders.OrderService/CreateOrder", "orders.OrderService/Cancel")));

        assertThat(configs).hasSize(3);
        assertThat(configs.get(0).get("name"))
                .isEqualTo(List.of(Map.of("service", "orders.OrderService", "method", "Cancel")));
        assertThat(configs.get(0)).doesNotContainKey("hedgingPolicy");
        assertThat(configs.get(1).get("name"))
                .isEqualTo(List.of(Map.of("service", "orders.OrderService", "method", "CreateOrder")));
        assertThat(configs.get(1)).doesNotContainKey("hedgingPolicy");
        assertThat(configs.get(2).get("name")).isEqualTo(List.of(Map.of()));
        assertThat(configs.get(2)).containsKey("hedgingPolicy");
    }

    @Test
    @DisplayName(
            "GIVEN routes with hedging enabled and default idempotency WHEN collecting non-hedgeable methods SHOULD exclude unsafe methods")
    void given_hedgingEnabled_when_collecting_should_excludeUnsafeMethods() {
        var svc = new ServiceDefinition();
        svc.setName("orders");
        svc.getRetry().setEnabled(false);
        svc.getHedging().setEnabled(true);
        List<RouteDefinition> routes = List.of(
                route("GetOrder", "GET", RouteDefinition.IdempotencyLevel.IDEMPOTENCY_UNKNOWN),
                route("ListOrders", null, RouteDefinition.IdempotencyLevel.NO_SIDE_EFFECTS),
                route("CreateOrder", "POST", RouteDefinition.IdempotencyLevel.IDEMPOTENCY_UNKNOWN),
                route("Charge", null, RouteDefinition.IdempotencyLevel.IDEMPOTENCY_UNKNOWN));

        assertThat(RetryServiceConfig.nonRetryableMethods(svc, routes))
                .containsExactly("orders.OrderService/Charge", "orders.OrderService/CreateOrder");
    }

    @Test
    @DisplayName(
            "GIVEN hedging with hedge-non-idempotent true WHEN collecting non-hedgeable methods SHOULD exclude nothing")
    void given_hedgingWithNonIdempotentTrue_when_collecting_should_excludeNothing() {
        var svc = new ServiceDefinition();
        svc.setName("orders");
        svc.getRetry().setEnabled(false);
        svc.getHedging().setEnabled(true);
        svc.getHedging().setHedgeNonIdempotent(true);

        assertThat(RetryServiceConfig.nonRetryableMethods(
                        svc,
                        List.of(route("CreateOrder", "POST", RouteDefinition.IdempotencyLevel.IDEMPOTENCY_UNKNOWN))))
                .isEmpty();
    }
}
