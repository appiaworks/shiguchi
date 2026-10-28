package dev.appiaworks.shiguchi.security;

/**
 * Boot's reactive security auto-configurations, by name, for {@code @AutoConfiguration(beforeName)}
 * on every gateway auto-configuration. {@link GatewaySecurityConfig}'s chain and default user are
 * {@code @ConditionalOnMissingBean}, and so are Boot's and Actuator's, so whichever registers
 * first wins — the gateway's must. Names, not classes, because the two Boot lines spell them
 * differently and the module compiles against one; a name that does not exist on the classpath
 * is ignored. Both spellings are listed so one constant set serves both lines.
 */
public final class SecurityAutoConfigurationNames {

    private SecurityAutoConfigurationNames() {}

    /** Boot 3: {@code spring-boot-autoconfigure}. */
    public static final String BOOT3_REACTIVE_SECURITY =
            "org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration";

    public static final String BOOT3_REACTIVE_USER_DETAILS =
            "org.springframework.boot.autoconfigure.security.reactive.ReactiveUserDetailsServiceAutoConfiguration";

    public static final String BOOT3_MANAGEMENT_SECURITY =
            "org.springframework.boot.actuate.autoconfigure.security.reactive.ReactiveManagementWebSecurityAutoConfiguration";

    /** Boot 4: {@code spring-boot-security}, repackaged and partly renamed. */
    public static final String BOOT4_REACTIVE_SECURITY =
            "org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration";

    public static final String BOOT4_REACTIVE_USER_DETAILS =
            "org.springframework.boot.security.autoconfigure.ReactiveUserDetailsServiceAutoConfiguration";

    public static final String BOOT4_MANAGEMENT_SECURITY =
            "org.springframework.boot.security.autoconfigure.actuate.web.reactive.ReactiveManagementWebSecurityAutoConfiguration";
}
