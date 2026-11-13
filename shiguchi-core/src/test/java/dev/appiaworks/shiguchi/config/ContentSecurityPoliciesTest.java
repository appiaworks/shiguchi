package dev.appiaworks.shiguchi.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ContentSecurityPolicies")
class ContentSecurityPoliciesTest {

    private final GatewayProperties properties = new GatewayProperties();

    private void scheme(String id, String tokenUrl) {
        var flow = new OpenApiConfig.SecuritySchemeConfig.OAuthFlowConfig();
        flow.setTokenUrl(tokenUrl);
        var scheme = new OpenApiConfig.SecuritySchemeConfig();
        scheme.setType("oauth2");
        scheme.setAuthorizationCode(flow);
        properties.getOpenapi().getSecuritySchemes().put(id, scheme);
    }

    @Test
    @DisplayName("GIVEN no schemes WHEN built SHOULD be the unchanged strict default")
    void given_noSchemes_when_built_should_beDefault() {
        assertThat(ContentSecurityPolicies.effective(properties))
                .isEqualTo(HeadersConfig.DEFAULT_CONTENT_SECURITY_POLICY)
                .contains("connect-src 'self';");
    }

    @Test
    @DisplayName("GIVEN a keycloak scheme WHEN built SHOULD add only the token origin to connect-src")
    void given_scheme_when_built_should_addTokenOrigin() {
        scheme("keycloak-sso", "https://keycloak.example.org/realms/main/protocol/openid-connect/token");

        String csp = ContentSecurityPolicies.effective(properties);

        assertThat(csp).contains("connect-src 'self' https://keycloak.example.org;");
        // Everything outside connect-src is as shipped: no unsafe-inline for scripts, no wildcards.
        assertThat(csp.replace(" https://keycloak.example.org", ""))
                .isEqualTo(HeadersConfig.DEFAULT_CONTENT_SECURITY_POLICY);
    }

    @Test
    @DisplayName("GIVEN schemes sharing an origin and one with a port WHEN built SHOULD dedupe and keep the port")
    void given_sharedOrigins_when_built_should_dedupe() {
        scheme("a", "https://idp.example.org/realms/a/token");
        scheme("b", "https://IDP.example.org/realms/b/token");
        scheme("c", "http://localhost:8180/token");

        assertThat(ContentSecurityPolicies.effective(properties))
                .contains("connect-src 'self' https://idp.example.org http://localhost:8180;");
    }

    @Test
    @DisplayName("GIVEN unusable token urls WHEN built SHOULD leave the default alone")
    void given_badUrls_when_built_should_ignoreThem() {
        scheme("blank", " ");
        scheme("relative", "/token");
        scheme("nonsense", "not a url");
        scheme("odd", "javascript:alert(1)");

        assertThat(ContentSecurityPolicies.effective(properties))
                .isEqualTo(HeadersConfig.DEFAULT_CONTENT_SECURITY_POLICY);
    }

    @Test
    @DisplayName("GIVEN a user-supplied policy WHEN built SHOULD return it untouched even with schemes configured")
    void given_override_when_built_should_notRewrite() {
        scheme("keycloak-sso", "https://keycloak.example.org/token");
        properties.getHeaders().setContentSecurityPolicy("default-src 'none'; connect-src 'self'");

        assertThat(ContentSecurityPolicies.effective(properties)).isEqualTo("default-src 'none'; connect-src 'self'");
    }

    @Test
    @DisplayName("GIVEN an empty policy WHEN built SHOULD stay empty so the header stays off")
    void given_empty_when_built_should_stayEmpty() {
        scheme("keycloak-sso", "https://keycloak.example.org/token");
        properties.getHeaders().setContentSecurityPolicy("");

        assertThat(ContentSecurityPolicies.effective(properties)).isEmpty();
    }
}
