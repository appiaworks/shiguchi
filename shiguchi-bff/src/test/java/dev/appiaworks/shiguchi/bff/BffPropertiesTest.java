package dev.appiaworks.shiguchi.bff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

@DisplayName("BffProperties: binding and startup validation of shiguchi.bff")
class BffPropertiesTest {

    private static Binder binder(String fixture) throws IOException {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load(fixture, new ClassPathResource("bff/" + fixture));
        return new Binder(ConfigurationPropertySources.from(sources));
    }

    private static BffProperties bind(String fixture) throws IOException {
        return binder(fixture).bind("shiguchi.bff", BffProperties.class).get();
    }

    private static String startupProblem(String fixture) {
        Throwable thrown = catchThrowable(() -> BffConfiguration.validate(bind(fixture)));
        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        return thrown.getMessage();
    }

    @Test
    @DisplayName("GIVEN a fully specified YAML WHEN bound SHOULD populate every field")
    void given_fullyPopulatedYaml_when_bound_should_populateEveryField() throws IOException {
        BffProperties properties = bind("valid.yml");

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getSession().getStore()).isEqualTo(BffProperties.SessionStore.REDIS);
        assertThat(properties.getSession().getIdleTimeout()).isEqualTo(Duration.ofMinutes(20));
        assertThat(properties.getSession().getAbsoluteTimeout()).isEqualTo(Duration.ofHours(8));
        assertThat(properties.getCookie().getName()).isEqualTo("__Host-gw-session");
        assertThat(properties.getCookie().getSameSite()).isEqualTo(BffProperties.SameSite.STRICT);
        assertThat(properties.getCookie().isSecure()).isTrue();

        BffProperties.Provider idp = properties.getProviders().get("idp");
        assertThat(idp.getIssuerUri()).isEqualTo("https://id.example.com/realms/acme");
        assertThat(idp.getClientId()).isEqualTo("gateway");
        assertThat(idp.getClientSecret()).isEqualTo("s3cret");
        assertThat(idp.getScopes()).containsExactly("openid", "profile");
        assertThat(idp.getUpstreamTokenMode()).isEqualTo(BffProperties.UpstreamTokenMode.RELAY);

        BffProperties.Application eve = properties.getApplications().get("eve");
        assertThat(eve.getOrigins()).containsExactly("https://eve.example.com");
        assertThat(eve.getProviders()).containsExactly("idp");
        assertThat(eve.getReturnTo()).containsExactly("https://eve.example.com/");

        BffProperties.Application fin = properties.getApplications().get("fin");
        assertThat(fin.getOrigins()).containsExactly("https://fin.example.com:8443");
        assertThat(fin.getReturnTo()).containsExactly("https://fin.example.com:8443/home");
    }

    @Test
    @DisplayName("GIVEN a fully specified YAML WHEN validated SHOULD pass without exception")
    void given_fullyVersionedYaml_when_validated_should_passWithoutException() throws IOException {
        BffProperties properties = bind("valid.yml");

        assertThatCode(() -> BffConfiguration.validate(properties)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("GIVEN new properties WHEN read SHOULD have the documented defaults")
    void given_newProperties_when_read_should_haveDocumentedDefaults() {
        BffProperties properties = new BffProperties();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getSession().getStore()).isEqualTo(BffProperties.SessionStore.MEMORY);
        assertThat(properties.getSession().getIdleTimeout()).isEqualTo(Duration.ofMinutes(30));
        assertThat(properties.getSession().getAbsoluteTimeout()).isEqualTo(Duration.ofHours(12));
        assertThat(properties.getCookie().getName()).isEqualTo("__Host-gw-session");
        assertThat(properties.getCookie().getSameSite()).isEqualTo(BffProperties.SameSite.LAX);
        assertThat(properties.getCookie().isSecure()).isTrue();
    }

    @Test
    @DisplayName("GIVEN enabled with no applications and no providers WHEN validated SHOULD report both")
    void given_noApplicationsAndNoProviders_when_validated_should_reportBoth() throws IOException {
        Throwable thrown = catchThrowable(() -> BffConfiguration.validate(bind("empty.yml")));

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(thrown.getMessage())
                .contains("no applications declared under shiguchi.bff.applications")
                .contains("no providers declared under shiguchi.bff.providers");
    }

    @Test
    @DisplayName("GIVEN an application naming an unknown provider WHEN validated SHOULD fail naming it")
    void given_unknownProvider_when_validated_should_failNamingIt() {
        assertThat(startupProblem("unknown-provider.yml")).contains("application 'eve': unknown provider 'nope'");
    }

    @Test
    @DisplayName("GIVEN an origin with a path WHEN validated SHOULD fail as not exactly scheme://host[:port]")
    void given_originWithPath_when_validated_should_failAsNotExactOrigin() {
        assertThat(startupProblem("origin-with-path.yml"))
                .contains("origin 'https://eve.example.com/app' must be exactly scheme://host[:port]");
    }

    @Test
    @DisplayName("GIVEN an origin with a wildcard WHEN validated SHOULD fail as not exactly scheme://host[:port]")
    void given_originWithWildcard_when_validated_should_failAsNotExactOrigin() {
        assertThat(startupProblem("origin-wildcard.yml"))
                .contains("origin 'https://*.example.com' must be exactly scheme://host[:port]");
    }

    @Test
    @DisplayName("GIVEN two applications sharing an origin after normalization WHEN validated SHOULD fail")
    void given_sharedOriginAfterNormalization_when_validated_should_fail() {
        assertThat(startupProblem("shared-origin.yml"))
                .contains("applications 'eve' and 'fin' share origin https://eve.example.com");
    }

    @Test
    @DisplayName("GIVEN a relative return-to WHEN validated SHOULD fail as not an absolute URL")
    void given_relativeReturnTo_when_validated_should_failAsNotAbsolute() {
        assertThat(startupProblem("return-to-relative.yml")).contains("return-to '/home' must be an absolute URL");
    }

    @Test
    @DisplayName("GIVEN a return-to under another origin WHEN validated SHOULD fail as outside the origins")
    void given_returnToUnderForeignOrigin_when_validated_should_failAsOutsideOrigins() {
        assertThat(startupProblem("return-to-foreign.yml"))
                .contains("return-to 'https://other.example.com/' is not under one of the application's origins");
    }

    @Test
    @DisplayName("GIVEN a __Host- cookie that is not secure WHEN validated SHOULD fail naming the cookie")
    void given_hostCookieNotSecure_when_validated_should_failNamingCookie() {
        assertThat(startupProblem("cookie-host-insecure.yml"))
                .contains("cookie.name '__Host-x' has the __Host- prefix, which needs cookie.secure true");
    }

    @Test
    @DisplayName("GIVEN a SameSite=None cookie that is not secure WHEN validated SHOULD fail naming same-site")
    void given_sameSiteNoneNotSecure_when_validated_should_failNamingSameSite() {
        assertThat(startupProblem("cookie-samesite-none-insecure.yml"))
                .contains("cookie.same-site NONE needs cookie.secure true");
    }

    @Test
    @DisplayName("GIVEN a login-timeout of 16 minutes WHEN validated SHOULD fail naming the 15 minute cap")
    void given_loginTimeoutOver15Minutes_when_validated_should_fail() {
        BffProperties properties = BffTestFixtures.properties();
        properties.setLoginTimeout(Duration.ofMinutes(16));

        assertThat(catchThrowable(() -> BffConfiguration.validate(properties)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("login-timeout")
                .hasMessageContaining("at most 15 minutes");
    }

    @Test
    @DisplayName("GIVEN a login-timeout of exactly 15 minutes WHEN validated SHOULD pass")
    void given_loginTimeout15Minutes_when_validated_should_pass() {
        BffProperties properties = BffTestFixtures.properties();
        properties.setLoginTimeout(Duration.ofMinutes(15));

        assertThatCode(() -> BffConfiguration.validate(properties)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("GIVEN a negative login rate or burst WHEN validated SHOULD fail naming each")
    void given_negativeLoginRateLimit_when_validated_should_fail() {
        BffProperties properties = BffTestFixtures.properties();
        properties.getLoginRateLimit().setRequestsPerSecond(-1);
        properties.getLoginRateLimit().setBurst(-5);

        assertThat(catchThrowable(() -> BffConfiguration.validate(properties)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("login-rate-limit.requests-per-second")
                .hasMessageContaining("login-rate-limit.burst");
    }

    @Test
    @DisplayName("GIVEN the limit on and external-base-url set WHEN no forward-headers-strategy is set SHOULD warn")
    void given_limitOnBehindProxyWithoutForwardStrategy_when_checked_should_warn() {
        BffProperties properties = BffTestFixtures.properties();
        properties.getLoginRateLimit().setRequestsPerSecond(5);

        assertThat(BffConfiguration.loginLimitWarning(properties, null)).contains("forward-headers-strategy");
        assertThat(BffConfiguration.loginLimitWarning(properties, "framework")).isNull();
        properties.setExternalBaseUrl(null);
        assertThat(BffConfiguration.loginLimitWarning(properties, null)).isNull();
        properties.setExternalBaseUrl("https://gw.example.com");
        properties.getLoginRateLimit().setRequestsPerSecond(0);
        assertThat(BffConfiguration.loginLimitWarning(properties, null)).isNull();
    }

    @Test
    @DisplayName("GIVEN a SameSite=None cookie that is secure WHEN validated SHOULD pass")
    void given_sameSiteNoneSecure_when_validated_should_pass() {
        BffProperties properties = BffTestFixtures.properties();
        properties.getCookie().setSameSite(BffProperties.SameSite.NONE);
        properties.getCookie().setSecure(true);

        assertThatCode(() -> BffConfiguration.validate(properties)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("GIVEN a provider without issuer-uri WHEN validated SHOULD fail naming the provider")
    void given_providerWithoutIssuer_when_validated_should_failNamingProvider() {
        assertThat(startupProblem("provider-no-issuer.yml")).contains("provider 'idp': issuer-uri is required");
    }

    @Test
    @DisplayName("GIVEN a provider without client-id WHEN validated SHOULD fail naming the provider")
    void given_providerWithoutClientId_when_validated_should_failNamingProvider() {
        assertThat(startupProblem("provider-no-client-id.yml")).contains("provider 'idp': client-id is required");
    }

    @Test
    @DisplayName("GIVEN a provider id outside the allowed pattern WHEN validated SHOULD fail naming the id")
    void given_providerIdOutsidePattern_when_validated_should_failNamingId() {
        assertThatThrownBy(() -> BffConfiguration.validate(bind("bad-provider-id.yml")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("provider 'Bad_Id': id must match [a-z0-9-]{1,32}");
    }

    @Test
    @DisplayName("GIVEN jws-algorithms with an HMAC value WHEN validated SHOULD fail naming the provider and value")
    void given_hmacJwsAlgorithm_when_validated_should_failNamingProviderAndValue() {
        assertThat(startupProblem("jws-algorithms-hmac.yml"))
                .contains("provider 'idp': jws-algorithms value 'HS256' is not supported: HMAC needs a shared secret");
    }

    @Test
    @DisplayName(
            "GIVEN authorize-parameters naming protocol parameters WHEN validated SHOULD fail naming each, not the harmless one")
    void given_authorizeParametersNamingProtocolParameters_when_validated_should_failNamingEach() {
        assertThat(startupProblem("authorize-parameters-protocol.yml"))
                .contains("provider 'idp': authorize-parameters must not set 'client_id': the gateway sends it")
                .contains("provider 'idp': authorize-parameters must not set 'state': the gateway sends it")
                .doesNotContain("'provider'");
    }

    @Test
    @DisplayName(
            "GIVEN pass-through-parameters naming a protocol parameter or a malformed name WHEN validated SHOULD fail naming each")
    void given_passThroughParametersNamingProtocolParameters_when_validated_should_failNamingEach() {
        assertThat(startupProblem("pass-through-protocol.yml"))
                .contains("provider 'idp': pass-through-parameters must not name 'redirect_uri': the gateway sends it")
                .contains("provider 'idp': pass-through-parameters names must match [A-Za-z0-9_.-]{1,64}")
                .doesNotContain("'prompt'");
    }

    @Test
    @DisplayName(
            "GIVEN a provider without pass-through-parameters WHEN bound SHOULD default to the five OIDC parameters")
    void given_noPassThroughParameters_when_bound_should_defaultToFiveOidcParameters() throws IOException {
        assertThat(bind("valid.yml").getProviders().get("idp").getPassThroughParameters())
                .containsExactly("prompt", "max_age", "acr_values", "login_hint", "ui_locales");
    }

    @Test
    @DisplayName("GIVEN a provider that still sets the removed type key WHEN validated SHOULD fail naming the key")
    void given_removedProviderTypeKey_when_validated_should_failNamingKey() {
        Throwable thrown = catchThrowable(() -> BffConfiguration.validate(
                bind("provider-type-removed.yml"), new GatewayProperties(), binder("provider-type-removed.yml")));

        assertThat(thrown)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("provider 'idp': type was removed (OIDC was its only value): delete the key");
    }

    @Test
    @DisplayName("GIVEN a provider without the removed type key WHEN validated with the binder SHOULD pass")
    void given_noProviderTypeKey_when_validatedWithBinder_should_pass() {
        assertThatCode(() -> BffConfiguration.validate(bind("valid.yml"), new GatewayProperties(), binder("valid.yml")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("GIVEN jws-algorithms none WHEN validated SHOULD fail naming the provider and value")
    void given_noneJwsAlgorithm_when_validated_should_failNamingProviderAndValue() {
        assertThat(startupProblem("jws-algorithms-none.yml"))
                .contains("provider 'idp': jws-algorithms value 'none' is not allowed");
    }

    @Test
    @DisplayName("GIVEN an unknown jws-algorithms value WHEN validated SHOULD fail naming the provider and value")
    void given_unknownJwsAlgorithm_when_validated_should_failNamingProviderAndValue() {
        assertThat(startupProblem("jws-algorithms-garbage.yml"))
                .contains("provider 'idp': jws-algorithms value 'ES257' is not a known JWS algorithm");
    }

    @Test
    @DisplayName("GIVEN a lower-case jws-algorithms value WHEN validated SHOULD fail, names are exact")
    void given_lowerCaseJwsAlgorithm_when_validated_should_fail() {
        assertThat(startupProblem("jws-algorithms-lowercase.yml"))
                .contains("provider 'idp': jws-algorithms value 'rs256' is not a known JWS algorithm");
    }

    @Test
    @DisplayName("GIVEN an empty jws-algorithms list WHEN validated SHOULD fail naming the provider")
    void given_emptyJwsAlgorithms_when_validated_should_failNamingProvider() {
        assertThat(startupProblem("jws-algorithms-empty.yml"))
                .contains("provider 'idp': jws-algorithms must list at least one algorithm");
    }

    @Test
    @DisplayName("GIVEN a provider without jws-algorithms WHEN read SHOULD default to RS256 only")
    void given_noJwsAlgorithms_when_read_should_defaultToRs256() {
        assertThat(new BffProperties.Provider().getJwsAlgorithms()).containsExactly("RS256");
    }

    @Test
    @DisplayName("GIVEN several problems at once WHEN validated SHOULD report every one in a single message")
    void given_severalProblems_when_validated_should_reportEveryOneInOneMessage() {
        String message = startupProblem("multiple-problems.yml");

        assertThat(message)
                .contains("provider 'idp': issuer-uri is required")
                .contains("application 'eve': unknown provider 'nope'")
                .contains("origin 'https://eve.example.com/app' must be exactly scheme://host[:port]")
                .contains("return-to '/home' must be an absolute URL")
                .contains("cookie.name '__Host-gw-session' has the __Host- prefix, which needs cookie.secure true");
    }

    @Test
    @DisplayName("GIVEN new properties WHEN read SHOULD have the sign-in defaults")
    void given_newProperties_when_read_should_haveSignInDefaults() {
        BffProperties properties = new BffProperties();

        assertThat(properties.getPath()).isEqualTo("/bff");
        assertThat(properties.getExternalBaseUrl()).isNull();
        assertThat(properties.getLoginTimeout()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.getCookie().isPersistent()).isFalse();
        BffProperties.Provider provider = new BffProperties.Provider();
        assertThat(provider.getDisplayName()).isNull();
        assertThat(provider.getHintParameter()).isEqualTo("provider");
        assertThat(provider.getAudiences()).isEmpty();
        assertThat(provider.getClientAuthenticationMethod()).isEqualTo(BffProperties.ClientAuthenticationMethod.BASIC);
        assertThat(new BffProperties.Application().getPostLogoutRedirect()).isNull();
    }

    @Test
    @DisplayName("GIVEN a YAML with every sign-in property WHEN bound SHOULD populate them and validate")
    void given_signInYaml_when_bound_should_populateAndValidate() throws IOException {
        BffProperties properties = bind("full-sign-in.yml");

        assertThat(properties.getPath()).isEqualTo("/auth");
        assertThat(properties.getExternalBaseUrl()).isEqualTo("https://gw.example.com");
        assertThat(properties.getLoginTimeout()).isEqualTo(Duration.ofMinutes(2));
        assertThat(properties.getCookie().isPersistent()).isTrue();
        BffProperties.Provider idp = properties.getProviders().get("idp");
        assertThat(idp.getDisplayName()).isEqualTo("Company SSO");
        assertThat(idp.getHintParameter()).isEqualTo("idp_hint");
        assertThat(idp.getAuthorizeParameters())
                .containsExactly(Map.entry("provider", "google"), Map.entry("resource_id", "api"));
        assertThat(idp.getAuthorizationUri()).isEqualTo("https://id.example.com/authorize");
        assertThat(idp.getTokenUri()).isEqualTo("https://id.example.com/token");
        assertThat(idp.getJwkSetUri()).isEqualTo("https://id.example.com/jwks");
        assertThat(idp.getEndSessionUri()).isEqualTo("https://id.example.com/logout");
        assertThat(idp.getAudiences()).containsExactly("acme-sso");
        assertThat(idp.getClientAuthenticationMethod()).isEqualTo(BffProperties.ClientAuthenticationMethod.POST);
        assertThat(properties.getApplications().get("eve").getPostLogoutRedirect())
                .isEqualTo("https://eve.example.com/bye");
        assertThatCode(() -> BffConfiguration.validate(properties)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("GIVEN a path without a leading slash WHEN validated SHOULD fail naming the path")
    void given_pathWithoutLeadingSlash_when_validated_should_fail() {
        assertThat(startupProblem("path-no-slash.yml")).contains("path 'bff' must start with /");
    }

    @Test
    @DisplayName("GIVEN the path / WHEN validated SHOULD fail")
    void given_rootPath_when_validated_should_fail() {
        assertThat(startupProblem("path-root.yml")).contains("path '/' must start with /");
    }

    @Test
    @DisplayName("GIVEN a path with a trailing slash WHEN validated SHOULD fail")
    void given_pathWithTrailingSlash_when_validated_should_fail() {
        assertThat(startupProblem("path-trailing-slash.yml")).contains("path '/bff/' must start with /");
    }

    @Test
    @DisplayName("GIVEN a provider without client-secret WHEN validated SHOULD fail naming the provider")
    void given_providerWithoutSecret_when_validated_should_fail() {
        assertThat(startupProblem("provider-no-secret.yml")).contains("provider 'idp': client-secret is required");
    }

    @Test
    @DisplayName("GIVEN a post-logout-redirect under another origin WHEN validated SHOULD fail")
    void given_postLogoutRedirectUnderForeignOrigin_when_validated_should_fail() {
        assertThat(startupProblem("post-logout-foreign.yml"))
                .contains("post-logout-redirect 'https://other.example.com/bye' is not under one of");
    }

    @Test
    @DisplayName("GIVEN a relative post-logout-redirect WHEN validated SHOULD fail as not absolute")
    void given_relativePostLogoutRedirect_when_validated_should_fail() {
        assertThat(startupProblem("post-logout-relative.yml"))
                .contains("post-logout-redirect '/bye' must be an absolute URL");
    }

    @Test
    @DisplayName("GIVEN a malformed session-claims name WHEN validated SHOULD fail naming the application")
    void given_malformedSessionClaim_when_validated_should_fail() {
        assertThat(startupProblem("session-claims-bad.yml"))
                .contains("application 'eve': session-claims names must match [A-Za-z0-9_.:/-]{1,64}");
    }

    @Test
    @DisplayName("GIVEN an application without session-claims WHEN bound SHOULD default to name, email and picture")
    void given_noSessionClaims_when_bound_should_defaultToTheFixedThree() throws IOException {
        assertThat(bind("valid.yml").getApplications().values())
                .allSatisfy(a -> assertThat(a.getSessionClaims()).containsExactly("name", "email", "picture"));
    }
}
