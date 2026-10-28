package dev.appiaworks.shiguchi.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.protobuf.extras.ProtoJson;
import dev.appiaworks.shiguchi.authz.GatewayAuthorizer;
import dev.appiaworks.shiguchi.authz.ScopeGatewayAuthorizer;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.gateway.DefaultGatewayErrorRenderer;
import dev.appiaworks.shiguchi.gateway.GatewayErrorRenderer;
import dev.appiaworks.shiguchi.rest.DefaultGrpcStatusMapper;
import dev.appiaworks.shiguchi.rest.GrpcStatusMapper;
import fixtures.CustomBeanOverrideFixtures.CustomAuthorizerConfig;
import fixtures.CustomBeanOverrideFixtures.CustomErrorRendererConfig;
import fixtures.CustomBeanOverrideFixtures.CustomProtoJsonConfig;
import fixtures.CustomBeanOverrideFixtures.CustomStatusMapperConfig;
import fixtures.CustomBeanOverrideFixtures.LatLngCodecConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.autoconfigure.ReactiveUserDetailsServiceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.cloud.gateway.route.InMemoryRouteDefinitionRepository;
import org.springframework.cloud.gateway.route.RouteDefinitionWriter;

/**
 * Behavioral tests for {@link ShiguchiAutoConfiguration}, exercised through a real Spring
 * {@link ReactiveWebApplicationContextRunner} so {@code @AutoConfiguration}, {@code
 * @EnableConfigurationProperties}, {@code @ComponentScan}, and every {@code
 * @ConditionalOnMissingBean} on this class are genuinely evaluated by the container — not
 * hand-simulated.
 *
 * <p>{@link ReactiveWebApplicationContextRunner} is used (not the plain {@code
 * ApplicationContextRunner}) because the production app is reactive/WebFlux and the broad
 * {@code @ComponentScan(basePackages = "dev.appiaworks.shiguchi")} pulls in {@code GatewaySecurityConfig}
 * (spring-boot4 autoconfigure), whose {@code securityFilterChain} bean needs a {@code
 * ServerHttpSecurity} — only supplied by Spring Security's <em>reactive</em> auto-configuration
 * ({@link ReactiveWebSecurityAutoConfiguration} / {@link ReactiveUserDetailsServiceAutoConfiguration}),
 * which in turn requires a reactive web application context to activate. The scan also reaches
 * {@code GrpcMetricsInterceptor} (needs a {@code MeterRegistry}) and {@code ScgRouteRegistrar}
 * (needs a Spring Cloud Gateway {@code RouteDefinitionWriter}) — both supplied below with real,
 * minimal implementations so the full production bean graph genuinely refreshes end to end,
 * exactly as it does in a host application with Actuator and Spring Cloud Gateway on the classpath.
 *
 * <p><b>Boot 3 / Boot 4 divergence:</b> Spring Security's reactive auto-configuration was both
 * repackaged and renamed between Boot 3 and Boot 4. Boot 3:
 * {@code org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration}
 * and {@code ...ReactiveUserDetailsServiceAutoConfiguration}. Boot 4 (imported here):
 * {@link ReactiveWebSecurityAutoConfiguration} at {@code
 * org.springframework.boot.security.autoconfigure.web.reactive} (renamed from
 * {@code ReactiveSecurityAutoConfiguration}) and {@link ReactiveUserDetailsServiceAutoConfiguration}
 * moved to {@code org.springframework.boot.security.autoconfigure} (no {@code .reactive}
 * subpackage). {@link ShiguchiAutoConfiguration}'s own {@code @AutoConfiguration(beforeName =
 * ...)} already lists every one of these spellings for exactly this reason. Everything else about
 * this test is identical to its Boot 3 twin.
 *
 * <p>Verified behaviour:
 * <ul>
 *   <li>default beans are registered: GrpcStatusMapper, GatewayErrorRenderer, GatewayAuthorizer;</li>
 *   <li>each bean respects {@code @ConditionalOnMissingBean} — a user-supplied bean of the same
 *       type back off the gateway's default, and the context keeps only the user's bean;</li>
 *   <li>{@code GatewayProperties} is bound and available with its documented defaults.</li>
 * </ul>
 */
@DisplayName("ShiguchiAutoConfiguration")
class ShiguchiAutoConfigurationTest {

    private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(RouteDefinitionWriter.class, InMemoryRouteDefinitionRepository::new)
            .withConfiguration(AutoConfigurations.of(
                    ShiguchiAutoConfiguration.class,
                    ReactiveWebSecurityAutoConfiguration.class,
                    ReactiveUserDetailsServiceAutoConfiguration.class));

    @Test
    @DisplayName("GIVEN no user beans WHEN the context refreshes SHOULD register a DefaultGrpcStatusMapper")
    void GIVEN_noUserBeans_WHEN_contextRefreshes_SHOULD_registerDefaultGrpcStatusMapper() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(GrpcStatusMapper.class);
            assertThat(context.getBean(GrpcStatusMapper.class)).isInstanceOf(DefaultGrpcStatusMapper.class);
        });
    }

    @Test
    @DisplayName("GIVEN no user beans WHEN the context refreshes SHOULD register a DefaultGatewayErrorRenderer")
    void GIVEN_noUserBeans_WHEN_contextRefreshes_SHOULD_registerDefaultGatewayErrorRenderer() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(GatewayErrorRenderer.class);
            assertThat(context.getBean(GatewayErrorRenderer.class)).isInstanceOf(DefaultGatewayErrorRenderer.class);
        });
    }

    @Test
    @DisplayName("GIVEN no user beans WHEN the context refreshes SHOULD register a ScopeGatewayAuthorizer")
    void GIVEN_noUserBeans_WHEN_contextRefreshes_SHOULD_registerScopeGatewayAuthorizer() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(GatewayAuthorizer.class);
            assertThat(context.getBean(GatewayAuthorizer.class)).isInstanceOf(ScopeGatewayAuthorizer.class);
        });
    }

    @Test
    @DisplayName(
            "GIVEN no user beans WHEN the context refreshes SHOULD bind GatewayProperties with its documented defaults")
    void GIVEN_noUserBeans_WHEN_contextRefreshes_SHOULD_bindGatewayPropertiesDefaults() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(GatewayProperties.class);
            GatewayProperties props = context.getBean(GatewayProperties.class);
            assertThat(props.getGrpc().isEnabled()).isFalse();
            assertThat(props.getGrpc().getPort()).isEqualTo(9090);
        });
    }

    @Test
    @DisplayName(
            "GIVEN a user-supplied GrpcStatusMapper bean WHEN the context refreshes SHOULD back off the default and keep only the user's bean")
    void GIVEN_userSuppliedGrpcStatusMapper_WHEN_contextRefreshes_SHOULD_backOffDefault() {
        runner.withUserConfiguration(CustomStatusMapperConfig.class).run(context -> {
            assertThat(context).hasSingleBean(GrpcStatusMapper.class);
            assertThat(context.getBean(GrpcStatusMapper.class)).isSameAs(CustomStatusMapperConfig.INSTANCE);
            assertThat(context.getBean(GrpcStatusMapper.class)).isNotInstanceOf(DefaultGrpcStatusMapper.class);
        });
    }

    @Test
    @DisplayName(
            "GIVEN a user-supplied GatewayAuthorizer bean WHEN the context refreshes SHOULD back off ScopeGatewayAuthorizer and keep only the user's bean")
    void GIVEN_userSuppliedGatewayAuthorizer_WHEN_contextRefreshes_SHOULD_backOffDefault() {
        runner.withUserConfiguration(CustomAuthorizerConfig.class).run(context -> {
            assertThat(context).hasSingleBean(GatewayAuthorizer.class);
            assertThat(context.getBean(GatewayAuthorizer.class)).isSameAs(CustomAuthorizerConfig.INSTANCE);
            assertThat(context.getBean(GatewayAuthorizer.class)).isNotInstanceOf(ScopeGatewayAuthorizer.class);
        });
    }

    @Test
    @DisplayName(
            "GIVEN a user-supplied GatewayErrorRenderer bean WHEN the context refreshes SHOULD back off DefaultGatewayErrorRenderer and keep only the user's bean")
    void GIVEN_userSuppliedGatewayErrorRenderer_WHEN_contextRefreshes_SHOULD_backOffDefault() {
        runner.withUserConfiguration(CustomErrorRendererConfig.class).run(context -> {
            assertThat(context).hasSingleBean(GatewayErrorRenderer.class);
            assertThat(context.getBean(GatewayErrorRenderer.class)).isSameAs(CustomErrorRendererConfig.INSTANCE);
            assertThat(context.getBean(GatewayErrorRenderer.class)).isNotInstanceOf(DefaultGatewayErrorRenderer.class);
        });
    }

    // User-override fixtures live in fixtures.CustomBeanOverrideFixtures, deliberately outside
    // the dev.appiaworks.shiguchi package tree — see that class's javadoc for why.

    // ── ProtoJson in the gateway's dialect ───────────────────────────────────────────

    private static final com.google.type.Date DAY = com.google.type.Date.newBuilder()
            .setYear(2026)
            .setMonth(9)
            .setDay(22)
            .build();

    @Test
    @DisplayName(
            "GIVEN no user beans WHEN the context refreshes SHOULD register a ProtoJson speaking the gateway's default dialect")
    void GIVEN_noUserBeans_WHEN_contextRefreshes_SHOULD_registerProtoJsonInGatewayDialect() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ProtoJson.class);
            assertThat(context.getBean(ProtoJson.class).print(DAY)).isEqualTo("\"2026-09-22\"");
        });
    }

    @Test
    @DisplayName(
            "GIVEN shiguchi.json settings WHEN the context refreshes SHOULD give the ProtoJson bean the same dialect")
    void GIVEN_jsonSettings_WHEN_contextRefreshes_SHOULD_applyThemToProtoJson() {
        runner.withPropertyValues(
                        "shiguchi.json.scalar-types.date=false",
                        "shiguchi.json.int64-as=NUMBER",
                        "shiguchi.json.print.preserve-proto-field-names=true")
                .run(context -> {
                    ProtoJson json = context.getBean(ProtoJson.class);
                    assertThat(json.print(DAY)).isEqualTo("{\"year\":2026,\"month\":9,\"day\":22}");
                    assertThat(json.print(com.google.protobuf.Int64Value.of(42)))
                            .isEqualTo("42");
                    assertThat(json.print(com.google.type.Money.newBuilder()
                                    .setCurrencyCode("USD")
                                    .build()))
                            .contains("\"currency_code\"");
                });
    }

    @Test
    @DisplayName("GIVEN a ProtoScalarCodec bean WHEN the context refreshes SHOULD give the ProtoJson bean that codec")
    void GIVEN_scalarCodecBean_WHEN_contextRefreshes_SHOULD_registerItWithProtoJson() {
        runner.withUserConfiguration(LatLngCodecConfig.class).run(context -> {
            com.google.type.LatLng point = com.google.type.LatLng.newBuilder()
                    .setLatitude(1.5)
                    .setLongitude(2.5)
                    .build();
            assertThat(context.getBean(ProtoJson.class).print(point)).isEqualTo("\"1.5,2.5\"");
        });
    }

    @Test
    @DisplayName(
            "GIVEN a user-supplied ProtoJson bean WHEN the context refreshes SHOULD back off the gateway's and keep only the user's")
    void GIVEN_userSuppliedProtoJson_WHEN_contextRefreshes_SHOULD_backOffDefault() {
        runner.withUserConfiguration(CustomProtoJsonConfig.class).run(context -> {
            assertThat(context).hasSingleBean(ProtoJson.class);
            assertThat(context.getBean(ProtoJson.class)).isSameAs(CustomProtoJsonConfig.INSTANCE);
        });
    }
}
