package dev.appiaworks.shiguchi.security;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.appiaworks.shiguchi.config.GatewayMode;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.SecurityConfig;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The warning that a caller's bearer token would travel to a backend in plaintext. What matters is
 * when it speaks and when it stays quiet: a false alarm on every startup is how a real warning
 * gets ignored.
 */
@DisplayName("InsecureBackendCredentialWarning")
class InsecureBackendCredentialWarningTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger(InsecureBackendCredentialWarning.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(appender);
    }

    private static GatewayProperties authenticatedGateway() {
        var properties = new GatewayProperties();
        properties.getSecurity().setMode(SecurityConfig.Mode.AUTHENTICATED);
        return properties;
    }

    private static ServiceDefinition service(String name, boolean insecure) {
        var svc = new ServiceDefinition();
        svc.setName(name);
        svc.setHost("localhost");
        svc.setPort(9090);
        svc.setInsecure(insecure);
        return svc;
    }

    private List<String> warningsFor(GatewayProperties properties) {
        new InsecureBackendCredentialWarning(properties).warnOnPlaintextCredentialForwarding();
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    @DisplayName("GIVEN authentication is on WHEN a backend is plaintext SHOULD warn and name it")
    void given_authenticated_when_plaintextBackend_should_warn() {
        var properties = authenticatedGateway();
        properties.getServices().add(service("orders", true));
        properties.getServices().add(service("billing", false));

        assertThat(warningsFor(properties))
                .singleElement()
                .satisfies(message -> assertThat(message)
                        .contains("PLAINTEXT")
                        .contains("orders")
                        .doesNotContain("billing")
                        .contains("insecure: false"));
    }

    @Test
    @DisplayName("GIVEN every backend uses TLS WHEN starting SHOULD stay quiet")
    void given_allBackendsSecure_when_starting_should_sayNothing() {
        var properties = authenticatedGateway();
        properties.getServices().add(service("orders", false));

        assertThat(warningsFor(properties)).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN a backend has a tls block WHEN insecure is still true SHOULD stay quiet, because the tls block wins")
    void given_tlsBlock_when_insecureTrue_should_sayNothing() {
        var properties = authenticatedGateway();
        var svc = service("orders", true);
        var tls = new ServiceDefinition.TlsConfig();
        tls.setTrustCertCollection("/etc/ca.pem");
        svc.setTls(tls);
        properties.getServices().add(svc);

        assertThat(warningsFor(properties)).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN no surface authenticates WHEN backends are plaintext SHOULD stay quiet, since there is no token to leak")
    void given_noAuthentication_when_plaintextBackend_should_sayNothing() {
        var properties = new GatewayProperties(); // PERMIT_ALL, the default
        properties.getServices().add(service("orders", true));

        assertThat(warningsFor(properties)).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN only one surface is gated WHEN a backend is plaintext SHOULD still warn, because that surface issues tokens")
    void given_oneGatedSurface_when_plaintextBackend_should_warn() {
        var properties = new GatewayProperties();
        properties.getSecurity().getSurfaces().getRest().setRequireAuth(true);
        properties.getServices().add(service("orders", true));

        assertThat(warningsFor(properties))
                .singleElement()
                .satisfies(message -> assertThat(message).contains("orders"));
    }

    @Test
    @DisplayName(
            "GIVEN a REST_DOWNSTREAM service WHEN it is listed SHOULD be ignored, since it has no gRPC channel to secure")
    void given_restDownstream_when_starting_should_ignoreIt() {
        var properties = authenticatedGateway();
        var svc = service("legacy", true);
        svc.setMode(GatewayMode.REST_DOWNSTREAM);
        svc.setOpenapiUrl("http://legacy:8080/v3/api-docs");
        properties.getServices().add(svc);

        assertThat(warningsFor(properties)).isEmpty();
    }

    @Test
    @DisplayName("GIVEN several plaintext backends WHEN warning SHOULD count them and list every name")
    void given_severalPlaintextBackends_when_warning_should_listThemAll() {
        var properties = authenticatedGateway();
        properties.getServices().add(service("orders", true));
        properties.getServices().add(service("inventory", true));

        assertThat(warningsFor(properties))
                .singleElement()
                .satisfies(message -> assertThat(message)
                        .contains("2 backend(s)")
                        .contains("orders")
                        .contains("inventory"));
    }
}
