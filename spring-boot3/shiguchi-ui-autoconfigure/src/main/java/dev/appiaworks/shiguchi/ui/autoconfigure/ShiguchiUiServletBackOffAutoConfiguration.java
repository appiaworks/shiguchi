package dev.appiaworks.shiguchi.ui.autoconfigure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * Says why the embedded gRPC UI is missing from a servlet (Spring MVC) application.
 *
 * <p>The UI is served by WebFlux router functions and talks to the gateway's reactive
 * dispatcher, so {@link ShiguchiUiAutoConfiguration} only runs in a reactive web application. On
 * a servlet application it backs off entirely (its component scan would otherwise register the
 * gateway's reactive security chain and fail the boot). Without this warning, {@code
 * shiguchi.ui.enabled=true} would quietly do nothing.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "shiguchi.ui", name = "enabled", havingValue = "true")
public class ShiguchiUiServletBackOffAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ShiguchiUiServletBackOffAutoConfiguration.class);

    @Bean
    UiServletBackOffNotice shiguchiUiServletBackOffNotice() {
        log.warn("shiguchi.ui.enabled=true, but this is a servlet (Spring MVC) application: the embedded gRPC UI "
                + "needs a reactive (WebFlux) web application and is not served. Set "
                + "spring.main.web-application-type=reactive if the application has no Spring MVC endpoints, or run "
                + "the UI as a separate reactive application (see samples/sample-grpc-ui-standalone).");
        return new UiServletBackOffNotice();
    }

    /** Marker bean: the UI backed off because the application is a servlet one. */
    public static final class UiServletBackOffNotice {}
}
