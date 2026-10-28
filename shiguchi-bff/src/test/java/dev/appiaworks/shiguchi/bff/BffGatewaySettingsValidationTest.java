package dev.appiaworks.shiguchi.bff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("BffConfiguration: checks of the gateway's own CORS and header settings, and layout warnings")
class BffGatewaySettingsValidationTest {

    private static Throwable validateWith(GatewayProperties gateway) {
        return catchThrowable(() -> BffConfiguration.validate(BffTestFixtures.properties(), gateway));
    }

    @Test
    @DisplayName("GIVEN credentials with a wildcard CORS origin WHEN validated SHOULD fail")
    void given_credentialsWithWildcardOrigin_when_validated_should_fail() {
        GatewayProperties gateway = new GatewayProperties();
        gateway.getCors().setEnabled(true);
        gateway.getCors().setAllowCredentials(true);
        gateway.getCors().setAllowedOrigins(List.of("*"));

        assertThat(validateWith(gateway))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cors.allow-credentials is true with '*'");
    }

    @Test
    @DisplayName("GIVEN credentials with listed CORS origins WHEN validated SHOULD pass")
    void given_credentialsWithListedOrigins_when_validated_should_pass() {
        GatewayProperties gateway = new GatewayProperties();
        gateway.getCors().setEnabled(true);
        gateway.getCors().setAllowCredentials(true);
        gateway.getCors().setAllowedOrigins(List.of("https://eve.example.com"));

        assertThat(validateWith(gateway)).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"set-cookie", "Set-Cookie", "SET-COOKIE", " set-cookie ", "*"})
    @DisplayName("GIVEN set-cookie forwarded to clients, in any case or by wildcard WHEN validated SHOULD fail")
    void given_setCookieForwarded_when_validated_should_fail(String header) {
        GatewayProperties gateway = new GatewayProperties();
        gateway.getRest().setForwardResponseHeaders(List.of("x-trace", header));

        assertThat(validateWith(gateway))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rest.forward-response-headers");
    }

    @Test
    @DisplayName("GIVEN set-cookie forwarded for one service WHEN validated SHOULD fail naming the service")
    void given_setCookieForwardedPerService_when_validated_should_fail() {
        GatewayProperties gateway = new GatewayProperties();
        ServiceDefinition service = new ServiceDefinition();
        service.setName("echo");
        service.setForwardResponseHeaders(List.of("set-cookie"));
        gateway.getServices().add(service);

        assertThat(validateWith(gateway)).hasMessageContaining("services[echo].forward-response-headers");
    }

    @Test
    @DisplayName("GIVEN cookie in client.forward-headers WHEN validated SHOULD fail")
    void given_cookieForwardedToBackends_when_validated_should_fail() {
        GatewayProperties gateway = new GatewayProperties();
        gateway.getClient().setForwardHeaders(List.of("x-request-id", "Cookie"));

        assertThat(validateWith(gateway)).hasMessageContaining("client.forward-headers lists 'cookie'");
    }

    @Test
    @DisplayName("GIVEN other forwarded response headers WHEN validated SHOULD pass")
    void given_otherForwardedHeaders_when_validated_should_pass() {
        GatewayProperties gateway = new GatewayProperties();
        gateway.getRest().setForwardResponseHeaders(List.of("x-trace", "etag"));

        assertThat(validateWith(gateway)).isNull();
    }

    @Test
    @DisplayName(
            "GIVEN an application cross-site to external-base-url with SameSite Lax and no CORS WHEN checked SHOULD warn about the cookie and about CORS")
    void given_crossSiteApplication_when_checked_should_warnAboutCookieAndCors() {
        BffProperties properties = BffTestFixtures.properties();
        properties.setExternalBaseUrl("https://gw.other.net");

        assertThat(BffConfiguration.warnings(properties, new GatewayProperties()))
                .anyMatch(w -> w.contains("cross-site") && w.contains("'eve'"))
                .anyMatch(w -> w.contains("does not allow it with allow-credentials") && w.contains("'eve'"));
    }

    @Test
    @DisplayName("GIVEN sibling-host applications and CORS allowing them with credentials WHEN checked SHOULD not warn")
    void given_siblingHostWithCors_when_checked_should_notWarn() {
        BffProperties properties = BffTestFixtures.properties();
        properties.setExternalBaseUrl("https://api.example.com");
        GatewayProperties gateway = new GatewayProperties();
        gateway.getCors().setEnabled(true);
        gateway.getCors().setAllowCredentials(true);
        gateway.getCors().setAllowedOrigins(List.of("https://eve.example.com", "https://fin.example.com"));

        assertThat(BffConfiguration.warnings(properties, gateway)).isEmpty();
    }

    @Test
    @DisplayName("GIVEN an application on the gateway's own origin WHEN checked SHOULD not warn")
    void given_sameOrigin_when_checked_should_notWarn() {
        BffProperties properties = BffTestFixtures.properties();
        properties.setExternalBaseUrl("https://eve.example.com");
        properties.getApplications().remove("fin");

        assertThat(BffConfiguration.warnings(properties, new GatewayProperties()))
                .isEmpty();
    }

    @Test
    @DisplayName("GIVEN no external-base-url and an insecure cookie WHEN checked SHOULD not judge the layout")
    void given_noExternalBaseUrl_when_checked_should_notWarn() {
        BffProperties properties = BffTestFixtures.properties();
        properties.setExternalBaseUrl(null);
        properties.getCookie().setSecure(false);

        assertThat(BffConfiguration.warnings(properties, new GatewayProperties()))
                .isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN no external-base-url and a secure cookie WHEN checked SHOULD warn once that redirect_uri may become http://")
    void given_noExternalBaseUrlAndSecureCookie_when_checked_should_warnAboutRedirectUri() {
        BffProperties properties = BffTestFixtures.properties();
        properties.setExternalBaseUrl(null);
        properties.getCookie().setSecure(true);

        assertThat(BffConfiguration.warnings(properties, new GatewayProperties()))
                .singleElement()
                .satisfies(w -> assertThat(w)
                        .contains("external-base-url is not set while cookie.secure is true")
                        .contains("sign_in_failed"));
    }

    @Test
    @DisplayName("GIVEN external-base-url set and a secure cookie WHEN checked SHOULD not warn about redirect_uri")
    void given_externalBaseUrlAndSecureCookie_when_checked_should_notWarnAboutRedirectUri() {
        BffProperties properties = BffTestFixtures.properties();
        properties.setExternalBaseUrl("https://eve.example.com");
        properties.getCookie().setSecure(true);

        assertThat(BffConfiguration.warnings(properties, new GatewayProperties()))
                .noneMatch(w -> w.contains("external-base-url is not set"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"gateway-bff-sessions", "health, gateway-bff-sessions", "*"})
    @DisplayName("GIVEN the exposure list names the sessions endpoint or * WHEN checked SHOULD report it exposed")
    void given_exposureNamesSessionsEndpoint_when_checked_should_reportExposed(String include) {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("management.endpoints.web.exposure.include", include);

        assertThat(BffConfiguration.sessionsEndpointExposed(environment)).isTrue();
    }

    @Test
    @DisplayName(
            "GIVEN the exposure list in YAML list form names the sessions endpoint WHEN checked SHOULD report it exposed")
    void given_exposureListFormNamesSessionsEndpoint_when_checked_should_reportExposed() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("management.endpoints.web.exposure.include[0]", "health");
        environment.setProperty("management.endpoints.web.exposure.include[1]", "gateway-bff-sessions");

        assertThat(BffConfiguration.sessionsEndpointExposed(environment)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "health,info", "gateway-bff-sessions-x"})
    @DisplayName("GIVEN the exposure list omits the sessions endpoint WHEN checked SHOULD report it not exposed")
    void given_exposureOmitsSessionsEndpoint_when_checked_should_reportNotExposed(String include) {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("management.endpoints.web.exposure.include", include);

        assertThat(BffConfiguration.sessionsEndpointExposed(environment)).isFalse();
    }
}
