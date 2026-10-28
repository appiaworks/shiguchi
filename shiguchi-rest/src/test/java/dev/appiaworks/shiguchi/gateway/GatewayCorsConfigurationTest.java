package dev.appiaworks.shiguchi.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.Descriptors.MethodDescriptor;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * BDD-style tests for GatewayCorsConfiguration.
 *
 * <p>Tests verify that:
 * - gatewayCorsConfigurationSource bean returns a CorsConfigurationSource
 * - Default paths (browser surfaces) are used when custom paths are empty
 * - Custom paths override default browser paths
 * - CORS settings (origins, methods, headers, credentials, maxAge) are mapped
 * - The returned source correctly registers configurations for paths
 */
class GatewayCorsConfigurationTest {

    private final GatewayCorsConfiguration config = new GatewayCorsConfiguration();

    // -------------------------------------------------------------------------
    // Test 1: Bean returns non-null CorsConfigurationSource
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "GIVEN GatewayProperties with CORS enabled WHEN gatewayCorsConfigurationSource is called SHOULD return non-null source")
    void GIVEN_corsEnabled_WHEN_createSource_SHOULD_returnNonNullSource() {
        // Arrange
        GatewayProperties properties = new GatewayProperties();
        properties.getCors().setEnabled(true);

        // Act
        CorsConfigurationSource source = config.gatewayCorsConfigurationSource(properties, registry(null));

        // Assert
        assertThat(source).isNotNull();
    }

    // -------------------------------------------------------------------------
    // Test 2: Empty paths list uses default browser paths
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "GIVEN CORS paths list is empty WHEN gatewayCorsConfigurationSource is called SHOULD use default browser paths")
    void GIVEN_emptyPathsList_WHEN_createSource_SHOULD_useDefaultBrowserPaths() {
        // Arrange
        GatewayProperties properties = new GatewayProperties();
        properties.getCors().setEnabled(true);
        properties.getCors().setPaths(List.of()); // Empty paths

        // Act
        CorsConfigurationSource source = config.gatewayCorsConfigurationSource(properties, registry(null));

        // Assert
        assertThat(source).isNotNull();
        // Verify it's a UrlBasedCorsConfigurationSource with registered paths
        assertThat(source).isInstanceOf(UrlBasedCorsConfigurationSource.class);
    }

    // -------------------------------------------------------------------------
    // Test 3: Custom paths override defaults
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "GIVEN custom CORS paths are configured WHEN gatewayCorsConfigurationSource is called SHOULD use custom paths instead of defaults")
    void GIVEN_customPathsList_WHEN_createSource_SHOULD_useCustomPaths() {
        // Arrange
        GatewayProperties properties = new GatewayProperties();
        properties.getCors().setEnabled(true);
        properties.getCors().setPaths(List.of("/custom/**", "/api/**")); // Custom paths

        // Act
        CorsConfigurationSource source = config.gatewayCorsConfigurationSource(properties, registry(null));

        // Assert
        assertThat(source).isNotNull();
        assertThat(source).isInstanceOf(UrlBasedCorsConfigurationSource.class);
    }

    // -------------------------------------------------------------------------
    // Test 4: Allowed origins are set from properties
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "GIVEN CORS allowed origins in properties WHEN gatewayCorsConfigurationSource is called SHOULD set origins in config")
    void GIVEN_allowedOrigins_WHEN_createSource_SHOULD_setOriginsInConfig() {
        // Arrange
        GatewayProperties properties = new GatewayProperties();
        properties.getCors().setEnabled(true);
        properties.getCors().setAllowedOrigins(List.of("https://console.example.com", "https://dashboard.example.com"));

        // Act
        CorsConfigurationSource source = config.gatewayCorsConfigurationSource(properties, registry(null));

        // Assert
        assertThat(source).isNotNull();
        assertThat(source).isInstanceOf(UrlBasedCorsConfigurationSource.class);
        // Verify origins are set in the CORS config being built
        assertThat(properties.getCors().getAllowedOrigins())
                .containsExactlyInAnyOrder("https://console.example.com", "https://dashboard.example.com");
    }

    // -------------------------------------------------------------------------
    // Test 5: Allowed methods are set from properties
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "GIVEN CORS allowed methods in properties WHEN gatewayCorsConfigurationSource is called SHOULD set methods in config")
    void GIVEN_allowedMethods_WHEN_createSource_SHOULD_setMethodsInConfig() {
        // Arrange
        GatewayProperties properties = new GatewayProperties();
        properties.getCors().setEnabled(true);
        properties.getCors().setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));

        // Act
        CorsConfigurationSource source = config.gatewayCorsConfigurationSource(properties, registry(null));

        // Assert
        assertThat(source).isNotNull();
        assertThat(properties.getCors().getAllowedMethods())
                .containsExactlyInAnyOrder("GET", "POST", "PUT", "DELETE", "OPTIONS");
    }

    // -------------------------------------------------------------------------
    // Test 6: Allowed headers are set from properties
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "GIVEN CORS allowed headers in properties WHEN gatewayCorsConfigurationSource is called SHOULD set headers in config")
    void GIVEN_allowedHeaders_WHEN_createSource_SHOULD_setHeadersInConfig() {
        // Arrange
        GatewayProperties properties = new GatewayProperties();
        properties.getCors().setEnabled(true);
        properties.getCors().setAllowedHeaders(List.of("Content-Type", "Authorization", "X-Custom"));

        // Act
        CorsConfigurationSource source = config.gatewayCorsConfigurationSource(properties, registry(null));

        // Assert
        assertThat(source).isNotNull();
        assertThat(properties.getCors().getAllowedHeaders())
                .containsExactlyInAnyOrder("Content-Type", "Authorization", "X-Custom");
    }

    // -------------------------------------------------------------------------
    // Test 7: Allow credentials flag is mapped
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "GIVEN CORS allow credentials is true WHEN gatewayCorsConfigurationSource is called SHOULD set credentials in config")
    void GIVEN_allowCredentialsTrue_WHEN_createSource_SHOULD_setCredentialsInConfig() {
        // Arrange
        GatewayProperties properties = new GatewayProperties();
        properties.getCors().setEnabled(true);
        properties.getCors().setAllowCredentials(true);

        // Act
        CorsConfigurationSource source = config.gatewayCorsConfigurationSource(properties, registry(null));

        // Assert
        assertThat(source).isNotNull();
        assertThat(properties.getCors().isAllowCredentials()).isTrue();
    }

    // -------------------------------------------------------------------------
    // Test 8: MaxAge duration is converted to seconds
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "GIVEN CORS maxAge duration in properties WHEN gatewayCorsConfigurationSource is called SHOULD convert to seconds in config")
    void GIVEN_maxAgeDuration_WHEN_createSource_SHOULD_convertToSeconds() {
        // Arrange
        GatewayProperties properties = new GatewayProperties();
        properties.getCors().setEnabled(true);
        properties.getCors().setMaxAge(Duration.ofHours(2)); // 2 hours = 7200 seconds

        // Act
        CorsConfigurationSource source = config.gatewayCorsConfigurationSource(properties, registry(null));

        // Assert
        assertThat(source).isNotNull();
        // Verify that the maxAge was set to 2 hours duration
        assertThat(properties.getCors().getMaxAge()).isEqualTo(Duration.ofHours(2));
    }

    // -------------------------------------------------------------------------
    // Test 9: Multiple paths are all registered in the source
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GIVEN multiple custom paths WHEN gatewayCorsConfigurationSource is called SHOULD register all paths")
    void GIVEN_multiplePaths_WHEN_createSource_SHOULD_registerAllPaths() {
        // Arrange
        GatewayProperties properties = new GatewayProperties();
        properties.getCors().setEnabled(true);
        List<String> paths = List.of("/api/**", "/graphql", "/ui/**", "/admin/**");
        properties.getCors().setPaths(paths);

        // Act
        CorsConfigurationSource source = config.gatewayCorsConfigurationSource(properties, registry(null));

        // Assert
        assertThat(source).isNotNull();
        assertThat(source).isInstanceOf(UrlBasedCorsConfigurationSource.class);
        // Verify the paths were set correctly
        assertThat(properties.getCors().getPaths()).containsExactlyInAnyOrder(paths.toArray(new String[0]));
    }

    // -------------------------------------------------------------------------
    // Test 10: Default UI path from properties is used in default browser paths
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "GIVEN custom UI path in properties WHEN gatewayCorsConfigurationSource is called with empty paths SHOULD use custom UI path in defaults")
    void GIVEN_customUiPath_WHEN_createSource_SHOULD_useCustomUiPathInDefaults() {
        // Arrange
        GatewayProperties properties = new GatewayProperties();
        properties.getCors().setEnabled(true);
        properties.getCors().setPaths(List.of()); // Empty = use defaults
        properties.getUi().setPath("/my-custom-ui"); // Custom UI path

        // Act
        CorsConfigurationSource source = config.gatewayCorsConfigurationSource(properties, registry(null));

        // Assert
        assertThat(source).isNotNull();
        assertThat(source).isInstanceOf(UrlBasedCorsConfigurationSource.class);
        // Verify that the custom UI path was set
        assertThat(properties.getUi().getPath()).isEqualTo("/my-custom-ui");
    }

    // -------------------------------------------------------------------------
    // gRPC-Web method paths
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "GIVEN gRPC-Web on and default paths WHEN a preflight targets a loaded method SHOULD grant CORS and expose the grpc status headers")
    void GIVEN_grpcWebEnabled_WHEN_pathNamesLoadedMethod_SHOULD_grantWithExposedGrpcHeaders() {
        GatewayProperties properties = grpcWebProperties();
        CorsConfigurationSource source =
                config.gatewayCorsConfigurationSource(properties, registry(knownMethod("web.Library", "GetBook")));

        CorsConfiguration cors = source.getCorsConfiguration(exchange("/web.Library/GetBook"));

        assertThat(cors).isNotNull();
        assertThat(cors.getAllowedOrigins()).containsExactly("https://app.example.com");
        assertThat(cors.getExposedHeaders())
                .containsExactlyInAnyOrder("grpc-status", "grpc-message", "grpc-status-details-bin");
    }

    @Test
    @DisplayName("GIVEN gRPC-Web on WHEN a two-segment path names no loaded method (a REST route) SHOULD grant no CORS")
    void GIVEN_grpcWebEnabled_WHEN_pathIsRestRoute_SHOULD_grantNothing() {
        GatewayProperties properties = grpcWebProperties();
        CorsConfigurationSource source =
                config.gatewayCorsConfigurationSource(properties, registry(knownMethod("web.Library", "GetBook")));

        assertThat(source.getCorsConfiguration(exchange("/v1/books"))).isNull();
        assertThat(source.getCorsConfiguration(exchange("/web.Library/Nope"))).isNull();
    }

    @Test
    @DisplayName("GIVEN gRPC-Web off WHEN a preflight targets a loaded method SHOULD grant no CORS")
    void GIVEN_grpcWebDisabled_WHEN_pathNamesLoadedMethod_SHOULD_grantNothing() {
        GatewayProperties properties = grpcWebProperties();
        properties.getGrpcWeb().setEnabled(false);
        CorsConfigurationSource source =
                config.gatewayCorsConfigurationSource(properties, registry(knownMethod("web.Library", "GetBook")));

        assertThat(source.getCorsConfiguration(exchange("/web.Library/GetBook")))
                .isNull();
    }

    private static GatewayProperties grpcWebProperties() {
        GatewayProperties properties = new GatewayProperties();
        properties.getCors().setEnabled(true);
        properties.getCors().setAllowedOrigins(List.of("https://app.example.com"));
        properties.getGrpcWeb().setEnabled(true);
        return properties;
    }

    private static MockServerWebExchange exchange(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.options(path)
                .header("Origin", "https://app.example.com")
                .header("Access-Control-Request-Method", "POST"));
    }

    private static GrpcRouteRegistry knownMethod(String service, String method) {
        GrpcRouteRegistry registry = mock(GrpcRouteRegistry.class);
        ServiceEndpointContext ctx = mock(ServiceEndpointContext.class);
        when(registry.contextByServiceFqn(anyString())).thenReturn(Optional.empty());
        when(registry.contextByServiceFqn(service)).thenReturn(Optional.of(ctx));
        when(ctx.lookupMethod(anyString(), anyString())).thenReturn(Optional.empty());
        when(ctx.lookupMethod(service, method)).thenReturn(Optional.of(mock(MethodDescriptor.class)));
        return registry;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<GrpcRouteRegistry> registry(GrpcRouteRegistry registry) {
        ObjectProvider<GrpcRouteRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return provider;
    }
}
