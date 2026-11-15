package dev.appiaworks.shiguchi.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD-style unit tests for OpenApiConfig.mergeComponents.
 *
 * Tests the first-wins merge strategy where existing components in the target
 * take precedence over incoming components from the source.
 */
@DisplayName("OpenApiConfig.mergeComponents")
class OpenApiConfigTest {

    /** One OAuth2 scheme per entry; a null id means the scheme sets no client id. */
    private static dev.appiaworks.shiguchi.config.GatewayProperties oauthSchemes(String... ids) {
        var props = new dev.appiaworks.shiguchi.config.GatewayProperties();
        for (int i = 0; i < ids.length; i++) {
            var flow = new dev.appiaworks.shiguchi.config.OpenApiConfig.SecuritySchemeConfig.OAuthFlowConfig();
            flow.setClientId(ids[i]);
            var scheme = new dev.appiaworks.shiguchi.config.OpenApiConfig.SecuritySchemeConfig();
            scheme.setType("oauth2");
            if (i % 2 == 0) scheme.setAuthorizationCode(flow);
            else scheme.setClientCredentials(flow);
            props.getOpenapi().getSecuritySchemes().put("s" + i, scheme);
        }
        return props;
    }

    private static String swaggerClientId(dev.appiaworks.shiguchi.config.GatewayProperties props, String explicit) {
        var oauth = new org.springdoc.core.properties.SwaggerUiOAuthProperties();
        if (explicit != null) oauth.setClientId(explicit);
        new OpenApiConfig(null, null, props)
                .swaggerOAuthClientIdDefault(provider(oauth))
                .afterSingletonsInstantiated();
        return oauth.getClientId();
    }

    @Test
    @DisplayName(
            "GIVEN the only OAuth2 scheme sets a client id WHEN initialised SHOULD default Swagger's initOAuth to it")
    void given_soleOauthSchemeWithClientId_when_initialised_should_defaultSwaggerClientId() {
        assertThat(swaggerClientId(oauthSchemes("pub"), null)).isEqualTo("pub");
    }

    @Test
    @DisplayName("GIVEN a client-credentials-only scheme sets a client id WHEN initialised SHOULD default it too")
    void given_soleClientCredentialsScheme_when_initialised_should_defaultSwaggerClientId() {
        var props = oauthSchemes(null, "cc");
        props.getOpenapi().getSecuritySchemes().remove("s0");
        assertThat(swaggerClientId(props, null)).isEqualTo("cc");
    }

    @Test
    @DisplayName(
            "GIVEN several OAuth2 schemes and only one sets a client id WHEN initialised SHOULD leave Swagger alone, so the other schemes are not offered the wrong id")
    void given_severalOauthSchemesOneClientId_when_initialised_should_notTouchSwagger() {
        assertThat(swaggerClientId(oauthSchemes("evo-grpc-ui-dev", null, null), null))
                .isNull();
    }

    @Test
    @DisplayName("GIVEN several OAuth2 schemes with different client ids WHEN initialised SHOULD leave Swagger alone")
    void given_severalClientIds_when_initialised_should_notTouchSwagger() {
        assertThat(swaggerClientId(oauthSchemes("a", "b"), null)).isNull();
    }

    @Test
    @DisplayName("GIVEN no configured client id WHEN initialised SHOULD leave Swagger alone")
    void given_noClientId_when_initialised_should_notTouchSwagger() {
        assertThat(swaggerClientId(oauthSchemes((String) null), null)).isNull();
        assertThat(swaggerClientId(new dev.appiaworks.shiguchi.config.GatewayProperties(), null))
                .isNull();
    }

    @Test
    @DisplayName("GIVEN an explicit springdoc client id WHEN initialised SHOULD keep it")
    void given_explicitSpringdocValue_when_initialised_should_keepIt() {
        assertThat(swaggerClientId(oauthSchemes("pub"), "mine")).isEqualTo("mine");
    }

    @Test
    @DisplayName(
            "GIVEN several OAuth2 schemes and only some set a client id WHEN mapped SHOULD list only those schemes")
    void given_severalSchemesSomeIds_when_mapped_should_listOnlySchemesWithIds() {
        assertThat(OpenApiConfig.clientIdsByScheme(oauthSchemes("kc-sso", null, "eve")))
                .containsOnlyKeys("s0", "s2")
                .containsEntry("s0", "kc-sso")
                .containsEntry("s2", "eve");
    }

    @Test
    @DisplayName("GIVEN a scheme whose flows disagree on the client id WHEN mapped SHOULD leave it out")
    void given_flowsDisagree_when_mapped_should_leaveSchemeOut() {
        var props = oauthSchemes("a");
        var other = new dev.appiaworks.shiguchi.config.OpenApiConfig.SecuritySchemeConfig.OAuthFlowConfig();
        other.setClientId("b");
        props.getOpenapi().getSecuritySchemes().get("s0").setClientCredentials(other);
        assertThat(OpenApiConfig.clientIdsByScheme(props)).isEmpty();
    }

    @Test
    @DisplayName("GIVEN ids WHEN the plugin is injected SHOULD define it and register it first in plugins")
    void given_ids_when_injected_should_registerPlugin() {
        var js =
                "window.ui = SwaggerUIBundle({\n    plugins: [\n      SwaggerUIBundle.plugins.DownloadUrl\n    ],\n});";
        var out = OpenApiConfig.SwaggerClientIds.inject(js, Map.of("kc", "pub"));
        assertThat(out)
                .contains("const ids = {\"kc\":\"pub\"};")
                .contains("plugins: [shiguchiOAuthClientIds, \n      SwaggerUIBundle.plugins.DownloadUrl")
                .endsWith(js.substring(js.indexOf("window.ui"))
                        .replace("plugins: [", "plugins: [shiguchiOAuthClientIds, "));
    }

    @Test
    @DisplayName("GIVEN a hostile client id WHEN the plugin is built SHOULD escape it so the script cannot be closed")
    void given_hostileId_when_built_should_escape() {
        var out = OpenApiConfig.SwaggerClientIds.pluginScript(Map.of("x", "</script><script>alert(1)//\" "));
        assertThat(out).doesNotContain("</script>").doesNotContain(" ").contains("<\\/script>");
    }

    @Test
    @DisplayName("GIVEN no client ids WHEN the plugin is injected SHOULD leave the initializer untouched")
    void given_noIds_when_injected_should_leaveInitializer() {
        var js = "plugins: [ a ]";
        assertThat(OpenApiConfig.SwaggerClientIds.inject(js, Map.of())).isEqualTo(js);
    }

    @Test
    @DisplayName("GIVEN an initializer without a plugins entry WHEN injected SHOULD leave it untouched")
    void given_noPluginsEntry_when_injected_should_leaveInitializer() {
        var js = "window.ui = SwaggerUIBundle({});";
        assertThat(OpenApiConfig.SwaggerClientIds.inject(js, Map.of("kc", "pub")))
                .isEqualTo(js);
    }

    private static Boolean swaggerPkce(dev.appiaworks.shiguchi.config.GatewayProperties props, Boolean explicit) {
        var oauth = new org.springdoc.core.properties.SwaggerUiOAuthProperties();
        oauth.setUsePkceWithAuthorizationCodeGrant(explicit);
        new OpenApiConfig(null, null, props)
                .swaggerOAuthClientIdDefault(provider(oauth))
                .afterSingletonsInstantiated();
        return oauth.getUsePkceWithAuthorizationCodeGrant();
    }

    @Test
    @DisplayName("GIVEN an authorization-code flow with a client id WHEN initialised SHOULD turn PKCE on")
    void given_publicAuthCodeClient_when_initialised_should_enablePkce() {
        assertThat(swaggerPkce(oauthSchemes("pub", "cc"), null)).isTrue();
    }

    @Test
    @DisplayName("GIVEN an explicit PKCE setting WHEN initialised SHOULD keep it")
    void given_explicitPkce_when_initialised_should_keepIt() {
        assertThat(swaggerPkce(oauthSchemes("pub"), false)).isFalse();
    }

    @Test
    @DisplayName("GIVEN only a client-credentials client id WHEN initialised SHOULD NOT touch PKCE")
    void given_onlyClientCredentials_when_initialised_should_notEnablePkce() {
        var props = oauthSchemes(null, "cc");
        props.getOpenapi().getSecuritySchemes().remove("s0");
        assertThat(swaggerPkce(props, null)).isNull();
    }

    @Test
    @DisplayName(
            "GIVEN schemes but no global security WHEN checked SHOULD warn; with global security or no schemes not")
    void given_schemesWithoutGlobalSecurity_when_checked_should_warn() {
        var props = oauthSchemes("pub");
        assertThat(OpenApiConfig.warnsAboutMissingGlobalSecurity(props)).isTrue();
        props.getOpenapi().getGlobalSecurity().add("s0");
        assertThat(OpenApiConfig.warnsAboutMissingGlobalSecurity(props)).isFalse();
        assertThat(OpenApiConfig.warnsAboutMissingGlobalSecurity(
                        new dev.appiaworks.shiguchi.config.GatewayProperties()))
                .isFalse();
    }

    private static org.springframework.beans.factory.ObjectProvider<
                    org.springdoc.core.properties.SwaggerUiOAuthProperties>
            provider(org.springdoc.core.properties.SwaggerUiOAuthProperties value) {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public org.springdoc.core.properties.SwaggerUiOAuthProperties getObject() {
                return value;
            }

            @Override
            public org.springdoc.core.properties.SwaggerUiOAuthProperties getIfAvailable() {
                return value;
            }
        };
    }

    @Test
    @DisplayName(
            "Given 'to' empty and 'from' with schemas, parameters, responses and securitySchemes, when merged, should copy all kinds")
    void given_toEmptyFromFull_when_merged_should_copyAllComponentKinds() {
        // Arrange
        Components to = new Components();
        Components from = createFullComponents();

        // Act
        OpenApiConfig.mergeComponents(from, to);

        // Assert
        assertThat(to.getSchemas()).containsKeys("Item", "Error");
        assertThat(to.getParameters()).containsKey("PageParam");
        assertThat(to.getResponses()).containsKey("NotFound");
        assertThat(to.getSecuritySchemes()).containsKey("bearer");
    }

    @Test
    @DisplayName(
            "Given 'to' already has schema 'X' and 'from' also has 'X', when merged, should keep 'to' instance (first-wins)")
    void given_toAndFromBothHaveSchema_when_merged_should_keepToSchema() {
        // Arrange
        Components to = new Components();
        Schema<?> existingSchema = new Schema<>().type("string");
        Map<String, Schema<?>> toSchemas = new LinkedHashMap<>();
        toSchemas.put("Item", existingSchema);
        to.setSchemas((Map) toSchemas);

        Components from = new Components();
        Schema<?> newSchema = new Schema<>().type("object");
        Map<String, Schema<?>> fromSchemas = new LinkedHashMap<>();
        fromSchemas.put("Item", newSchema);
        from.setSchemas((Map) fromSchemas);

        // Act
        OpenApiConfig.mergeComponents(from, to);

        // Assert — existing schema should be preserved
        assertThat(to.getSchemas().get("Item")).isSameAs(existingSchema);
    }

    @Test
    @DisplayName("Given 'from' null maps, when merged, should leave 'to' unchanged and not replace with empty")
    void given_fromNullMaps_when_merged_should_leaveToUnchanged() {
        // Arrange
        Components to = new Components();
        Map<String, Schema<?>> existingSchemas = new LinkedHashMap<>();
        existingSchemas.put("Item", new Schema<>().type("object"));
        to.setSchemas((Map) existingSchemas);

        Components from = new Components();
        // All maps in 'from' are null

        // Act
        OpenApiConfig.mergeComponents(from, to);

        // Assert
        assertThat(to.getSchemas()).containsKey("Item");
        assertThat(to.getSchemas()).hasSize(1);
    }

    @Test
    @DisplayName("Given 'from' with new schema 'Widget' and 'to' has existing 'Item', when merged, should contain both")
    void given_fromHasNewSchemaToHasExisting_when_merged_should_combineBoth() {
        // Arrange
        Components to = new Components();
        Map<String, Schema<?>> toSchemas = new LinkedHashMap<>();
        toSchemas.put("Item", new Schema<>().type("object"));
        to.setSchemas((Map) toSchemas);

        Components from = new Components();
        Map<String, Schema<?>> fromSchemas = new LinkedHashMap<>();
        fromSchemas.put("Widget", new Schema<>().type("object"));
        from.setSchemas((Map) fromSchemas);

        // Act
        OpenApiConfig.mergeComponents(from, to);

        // Assert
        assertThat(to.getSchemas()).containsKeys("Item", "Widget");
    }

    // -------------------------------------------------------------------------
    // Helper methods
    // -------------------------------------------------------------------------

    private Components createFullComponents() {
        Components components = new Components();

        // Add schemas
        Map<String, Schema<?>> schemas = new LinkedHashMap<>();
        schemas.put("Item", new Schema<>().type("object"));
        schemas.put("Error", new Schema<>().type("object"));
        components.setSchemas((Map) schemas);

        // Add parameters
        Map<String, Parameter> parameters = new LinkedHashMap<>();
        parameters.put("PageParam", new Parameter().name("page").in("query"));
        components.setParameters(parameters);

        // Add responses
        Map<String, io.swagger.v3.oas.models.responses.ApiResponse> responses = new LinkedHashMap<>();
        responses.put("NotFound", new ApiResponse().description("Not Found"));
        components.setResponses(responses);

        // Add security schemes
        Map<String, SecurityScheme> securitySchemes = new LinkedHashMap<>();
        securitySchemes.put(
                "bearer", new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer"));
        components.setSecuritySchemes(securitySchemes);

        return components;
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName(
            "GIVEN two backends WHEN the UI urls are filled SHOULD list All services plus one entry per backend, unless disabled or already set")
    void given_twoBackends_when_urlsFilled_should_listEachBackend() {
        var builder = org.mockito.Mockito.mock(GatewayOpenApiBuilder.class);
        org.mockito.Mockito.when(builder.backendNames())
                .thenReturn(new java.util.TreeSet<>(java.util.List.of("auth", "market")));
        var gp = new dev.appiaworks.shiguchi.config.GatewayProperties();

        var ui = new org.springdoc.core.properties.SwaggerUiConfigProperties();
        OpenApiConfig.addBackendUrls(ui, gp, builder);
        org.assertj.core.api.Assertions.assertThat(ui.getUrls())
                .extracting(org.springdoc.core.properties.AbstractSwaggerUiConfigProperties.SwaggerUrl::getUrl)
                .containsExactly("/v3/api-docs", "/v3/api-docs/backends/auth", "/v3/api-docs/backends/market");
        org.assertj.core.api.Assertions.assertThat(ui.getUrlsPrimaryName()).isEqualTo("All services");

        var disabledProps = new dev.appiaworks.shiguchi.config.GatewayProperties();
        disabledProps.getOpenapi().setGroupByBackend(false);
        var untouched = new org.springdoc.core.properties.SwaggerUiConfigProperties();
        OpenApiConfig.addBackendUrls(untouched, disabledProps, builder);
        org.assertj.core.api.Assertions.assertThat(untouched.getUrls()).isNullOrEmpty();

        org.mockito.Mockito.when(builder.backendNames()).thenReturn(new java.util.TreeSet<>(java.util.List.of("only")));
        var single = new org.springdoc.core.properties.SwaggerUiConfigProperties();
        OpenApiConfig.addBackendUrls(single, gp, builder);
        org.assertj.core.api.Assertions.assertThat(single.getUrls()).isNullOrEmpty();
    }
}
