package dev.appiaworks.shiguchi.autoconfigure;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.gateway.GatewayDefaults;
import dev.appiaworks.shiguchi.security.SecurityAutoConfigurationNames;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

/**
 * Spring Boot auto-configuration entry point for Shiguchi used as a library.
 *
 * When Shiguchi is added as a dependency to a host application, this class is
 * discovered via META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
 * and activates all gateway beans via @ComponentScan.
 *
 * The host application only needs to provide the shiguchi.* configuration in its
 * application.yml — no Java configuration code required.
 *
 * <p>Overridable defaults ({@link GatewayDefaults}) are imported, not component-scanned, so
 * {@code @ConditionalOnMissingBean} back-off works reliably: define your own bean of the same
 * type and the gateway default disappears. The UI auto-configuration imports the same class, so a
 * UI starter used on its own gets them too.
 */
// The gateway's SecurityWebFilterChain (GatewaySecurityConfig in shiguchi-rest, found by the
// component scan) is @ConditionalOnMissingBean, and so are Boot's and Actuator's default chains,
// so the gateway's must be registered first. Two things guarantee that. The ordering below
// (SecurityAutoConfigurationNames lists both lines' spellings; unknown names are ignored). And
// the scan filter: an
// application class annotated @SpringBootConfiguration / @SpringBootApplication that lives under
// dev.appiaworks.shiguchi (the samples do, and so do test fixtures) must not be picked up by this scan,
// because its @EnableAutoConfiguration would then be processed in the middle of this
// auto-configuration, importing Actuator's chain ahead of the gateway's: every request answered
// 401 with HTTP Basic.
@AutoConfiguration(
        beforeName = {
            SecurityAutoConfigurationNames.BOOT3_MANAGEMENT_SECURITY,
            SecurityAutoConfigurationNames.BOOT3_REACTIVE_SECURITY,
            SecurityAutoConfigurationNames.BOOT3_REACTIVE_USER_DETAILS,
            SecurityAutoConfigurationNames.BOOT4_MANAGEMENT_SECURITY,
            SecurityAutoConfigurationNames.BOOT4_REACTIVE_SECURITY,
            SecurityAutoConfigurationNames.BOOT4_REACTIVE_USER_DETAILS
        })
@EnableConfigurationProperties(GatewayProperties.class)
@Import(GatewayDefaults.class)
@ComponentScan(
        basePackages = "dev.appiaworks.shiguchi",
        excludeFilters =
                @ComponentScan.Filter(
                        type = org.springframework.context.annotation.FilterType.ANNOTATION,
                        classes = org.springframework.boot.SpringBootConfiguration.class))
public class ShiguchiAutoConfiguration {}
