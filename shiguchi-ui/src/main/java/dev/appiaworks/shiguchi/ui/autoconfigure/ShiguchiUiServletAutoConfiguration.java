package dev.appiaworks.shiguchi.ui.autoconfigure;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.gateway.GatewayDefaults;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;

/**
 * Spring Boot auto-configuration entry point for the embedded gRPC UI in a servlet (Spring MVC)
 * application, identical on both Boot lines. The reactive counterpart is {@link
 * ShiguchiUiAutoConfiguration}; the two never both apply, one per web application type.
 *
 * <p>Like the reactive one it scans {@code dev.appiaworks.shiguchi}: the UI needs the gateway's
 * core beans ({@code GrpcRouteRegistry}, {@code ReactiveGrpcDispatcher}, the route loaders, {@code
 * GatewayProperties}), and the starter must work on its own. The same safeguard applies:
 * {@code @SpringBootConfiguration} classes are excluded (an application class under that package
 * would re-enter auto-configuration mid-flight). The gateway's overridable defaults ({@link
 * GatewayDefaults}: authorizer, status mapper, error renderer) are imported, as in the reactive
 * configuration; they carry no web-stack types.
 *
 * <p>The difference is the scan's exclude list, {@link ServletScanExcludes}: the gateway modules
 * also hold WebFlux-only components (the reactive security chain, the web filters, the Spring
 * Cloud Gateway route registrar, the rate limiter, the WebClient-based REST_DOWNSTREAM
 * dispatcher, the reactive metadata populator and the WebFlux flavour of the UI). They are
 * excluded by class name, from the scan's class metadata, so none is loaded and the servlet
 * classpath needs neither WebFlux nor Reactor Netty. The UI's servlet adapter ({@code
 * dev.appiaworks.shiguchi.ui.servlet}) is picked up by the same scan; all of its classes are guarded
 * for the servlet stack and are enabled by {@code shiguchi.ui.enabled=true}.
 *
 * <p>Security is the host application's in servlet mode. The gateway's security posture
 * ({@code PERMIT_ALL} by default, {@code requireAuth}) is reactive-only and is not applied: the
 * UI's routes are protected exactly like the host's own endpoints, by its {@code SecurityFilterChain}
 * (Spring Boot's default one, secured with a generated password, when it defines none). The call
 * metadata carries the host's authenticated principal and {@code Authorization} header. Features
 * that depend on the reactive stack (REST_DOWNSTREAM, HTTP merge sources, rate limiting, gRPC-Web,
 * the BFF relay, Cursus) are not part of servlet mode.
 */
@AutoConfiguration(afterName = "dev.appiaworks.shiguchi.autoconfigure.ShiguchiAutoConfiguration")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "org.springframework.web.servlet.function.RouterFunction")
@EnableConfigurationProperties(GatewayProperties.class)
@Import(GatewayDefaults.class)
@ComponentScan(
        basePackages = "dev.appiaworks.shiguchi",
        excludeFilters = {
            @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = SpringBootConfiguration.class),
            @ComponentScan.Filter(type = FilterType.CUSTOM, classes = ServletScanExcludes.class)
        })
public class ShiguchiUiServletAutoConfiguration {}
