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

    private static dev.appiaworks.shiguchi.config.GatewayProperties propsWithClientIds(String... ids) {
        var props = new dev.appiaworks.shiguchi.config.GatewayProperties();
        for (int i = 0; i < ids.length; i++) {
            var flow = new dev.appiaworks.shiguchi.config.OpenApiConfig.SecuritySchemeConfig.OAuthFlowConfig();
            flow.setClientId(ids[i]);
            var scheme = new dev.appiaworks.shiguchi.config.OpenApiConfig.SecuritySchemeConfig();
            scheme.setType("oauth2");
            scheme.setAuthorizationCode(flow);
            props.getOpenapi().getSecuritySchemes().put("s" + i, scheme);
        }
        return props;
    }

    @Test
    @DisplayName("GIVEN exactly one configured client id WHEN initialised SHOULD default Swagger's initOAuth to it")
    void given_oneClientId_when_initialised_should_defaultSwaggerClientId() {
        var oauth = new org.springdoc.core.properties.SwaggerUiOAuthProperties();
        new OpenApiConfig(null, null, propsWithClientIds("pub"))
                .swaggerOAuthClientIdDefault(provider(oauth))
                .afterSingletonsInstantiated();
        assertThat(oauth.getClientId()).isEqualTo("pub");
    }

    @Test
    @DisplayName(
            "GIVEN two different client ids or an explicit springdoc value WHEN initialised SHOULD leave Swagger alone")
    void given_ambiguousOrExplicit_when_initialised_should_notTouchSwagger() {
        var two = new org.springdoc.core.properties.SwaggerUiOAuthProperties();
        new OpenApiConfig(null, null, propsWithClientIds("a", "b"))
                .swaggerOAuthClientIdDefault(provider(two))
                .afterSingletonsInstantiated();
        assertThat(two.getClientId()).isNull();

        var explicit = new org.springdoc.core.properties.SwaggerUiOAuthProperties();
        explicit.setClientId("mine");
        new OpenApiConfig(null, null, propsWithClientIds("pub"))
                .swaggerOAuthClientIdDefault(provider(explicit))
                .afterSingletonsInstantiated();
        assertThat(explicit.getClientId()).isEqualTo("mine");
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
}
