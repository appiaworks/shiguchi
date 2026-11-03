package dev.appiaworks.shiguchi.ui.autoconfigure;

import org.slf4j.LoggerFactory;

/**
 * Logs, once per JVM, that the Spring Boot 3 starters are deprecated. Temporary: the Boot 3 line is
 * removed after 0.18.x. The gateway and UI auto-configurations each carry a copy (neither module
 * depends on the other), so the once-guard is a system property both copies share.
 */
final class Boot3DeprecationNotice {

    static final String LOGGED_PROPERTY = "shiguchi.boot3-deprecation-notice.logged";

    static final String MESSAGE = "Shiguchi's Spring Boot 3 starters are deprecated: 0.18.x is the last"
            + " release line that ships them, with security fixes on a best-effort basis until May 2027."
            + " Move to the shiguchi-spring-boot4-* starters; shiguchi.* configuration is unchanged."
            + " See https://github.com/appiaworks/shiguchi#compatibility";

    private Boot3DeprecationNotice() {}

    static void logOnce() {
        if (System.getProperties().putIfAbsent(LOGGED_PROPERTY, "true") == null) {
            LoggerFactory.getLogger("dev.appiaworks.shiguchi.Boot3Deprecation").warn(MESSAGE);
        }
    }
}
