package dev.appiaworks.shiguchi.ui.autoconfigure;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.gateway.GatewayDefaults;
import dev.appiaworks.shiguchi.security.SecurityAutoConfigurationNames;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;

/**
 * Spring Boot auto-configuration entry point for the embedded gRPC UI, identical on both Boot
 * lines.
 *
 * <p>The UI starter must work on its own — {@code samples/sample-grpc-ui-standalone} is exactly a
 * UI starter with no gateway starter — and {@link dev.appiaworks.shiguchi.ui.GrpcUiController} needs the
 * gateway's core beans ({@code GrpcRouteRegistry}, {@code ReactiveGrpcDispatcher}, the route
 * loaders, {@code GatewayProperties}). So this scans {@code dev.appiaworks.shiguchi} the way {@code
 * ShiguchiAutoConfiguration} does, with the same two safeguards: {@code @SpringBootConfiguration}
 * classes are excluded (an application class under {@code dev.appiaworks.shiguchi} would otherwise have its
 * {@code @EnableAutoConfiguration} processed mid-auto-configuration and register Actuator's security
 * chain first — every request 401), and the ordering runs after the gateway auto-configuration,
 * when present, and before Boot's reactive security ones. Beans the gateway auto-configuration
 * already scanned are not registered twice. The gateway's overridable defaults ({@link
 * GatewayDefaults}: authorizer, status mapper, error renderer) are imported the same way the
 * gateway auto-configuration imports them, so the scanned components find them in a standalone
 * UI too. The Spring Cloud Gateway-dependent beans in {@code dev.appiaworks.shiguchi.gateway} are guarded
 * by {@code @ConditionalOnClass}, so the scan is safe without SCG on the classpath, and the metrics
 * interceptor falls back to Micrometer's global registry when Actuator is absent. The scan also
 * finds {@code GatewaySecurityConfig}, so a standalone UI gets the gateway's security posture
 * ({@code PERMIT_ALL} by default) instead of Boot's HTTP Basic chain.
 *
 * <p>Reactive web applications only: the UI is a set of WebFlux router functions, and the scan
 * brings in WebFlux-only beans (the gateway's {@code SecurityWebFilterChain} needs {@code
 * ServerHttpSecurity}). In a servlet application the whole configuration backs off and {@link
 * ShiguchiUiServletBackOffAutoConfiguration} warns instead of failing the boot.
 *
 * <p>UI beans themselves are only enabled when {@code shiguchi.ui.enabled=true} (each is
 * guarded by {@code @ConditionalOnProperty}).
 */
@AutoConfiguration(
        afterName = "dev.appiaworks.shiguchi.autoconfigure.ShiguchiAutoConfiguration",
        beforeName = {
            SecurityAutoConfigurationNames.BOOT3_MANAGEMENT_SECURITY,
            SecurityAutoConfigurationNames.BOOT3_REACTIVE_SECURITY,
            SecurityAutoConfigurationNames.BOOT3_REACTIVE_USER_DETAILS,
            SecurityAutoConfigurationNames.BOOT4_MANAGEMENT_SECURITY,
            SecurityAutoConfigurationNames.BOOT4_REACTIVE_SECURITY,
            SecurityAutoConfigurationNames.BOOT4_REACTIVE_USER_DETAILS
        })
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@EnableConfigurationProperties(GatewayProperties.class)
@Import(GatewayDefaults.class)
@ComponentScan(
        basePackages = "dev.appiaworks.shiguchi",
        excludeFilters = @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = SpringBootConfiguration.class))
public class ShiguchiUiAutoConfiguration {}
