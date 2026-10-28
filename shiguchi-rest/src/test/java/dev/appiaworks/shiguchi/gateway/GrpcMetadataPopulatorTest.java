package dev.appiaworks.shiguchi.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import io.grpc.Metadata;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;

/**
 * BDD-style tests for GrpcMetadataPopulator.
 *
 * Tests verify that:
 * - Forward headers from the HTTP request are copied to gRPC metadata
 * - Authorization and x-principal headers are always propagated
 * - Blank headers are skipped
 * - ForwardHeaders configuration is lowercased for case-insensitive matching
 * - x-principal defaults to "anonymous" when no security context is available
 */
class GrpcMetadataPopulatorTest {

    private ServerHttpRequest createMockRequest(String... headerPairs) {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        HttpHeaders headers = new HttpHeaders();

        // Add headers in pairs: key1, value1, key2, value2, etc.
        for (int i = 0; i < headerPairs.length; i += 2) {
            String key = headerPairs[i];
            String value = headerPairs[i + 1];
            headers.set(key, value);
        }

        when(request.getHeaders()).thenReturn(headers);
        return request;
    }

    @Test
    @DisplayName("Given forward header present in HTTP request, when build is called, should copy header to metadata")
    void given_forwardHeaderPresent_when_build_should_copyToMetadata() {
        // Arrange
        GatewayProperties properties = new GatewayProperties();
        properties.getClient().setForwardHeaders(List.of("x-request-id"));
        GrpcMetadataPopulator populator = new GrpcMetadataPopulator(properties);

        ServerHttpRequest request = createMockRequest("X-Request-Id", "req-123");

        // Act
        Metadata metadata = populator.build(request).block();

        // Assert
        assertThat(metadata).isNotNull();
        Metadata.Key<String> key = Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER);
        assertThat(metadata.get(key)).isEqualTo("req-123");
    }

    @Test
    @DisplayName(
            "Given forward header absent from HTTP request, when build is called, should not set the key in metadata")
    void given_forwardHeaderAbsent_when_build_should_notSetInMetadata() {
        // Arrange
        GatewayProperties properties = new GatewayProperties();
        properties.getClient().setForwardHeaders(List.of("x-request-id"));
        GrpcMetadataPopulator populator = new GrpcMetadataPopulator(properties);

        ServerHttpRequest request = createMockRequest();

        // Act
        Metadata metadata = populator.build(request).block();

        // Assert
        assertThat(metadata).isNotNull();
        Metadata.Key<String> key = Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER);
        assertThat(metadata.get(key)).isNull();
    }

    @Test
    @DisplayName("Given forward header with blank value, when build is called, should skip the blank header")
    void given_blankHeaderValue_when_build_should_skipBlankHeader() {
        // Arrange
        GatewayProperties properties = new GatewayProperties();
        properties.getClient().setForwardHeaders(List.of("x-correlation-id"));
        GrpcMetadataPopulator populator = new GrpcMetadataPopulator(properties);

        ServerHttpRequest request = createMockRequest("X-Correlation-Id", "  ");

        // Act
        Metadata metadata = populator.build(request).block();

        // Assert
        assertThat(metadata).isNotNull();
        Metadata.Key<String> key = Metadata.Key.of("x-correlation-id", Metadata.ASCII_STRING_MARSHALLER);
        assertThat(metadata.get(key)).isNull();
    }

    @Test
    @DisplayName(
            "Given Authorization header present in HTTP request, when build is called, should always propagate the header")
    void given_authorizationHeaderPresent_when_build_should_alwaysPropagate() {
        // Arrange
        GatewayProperties properties = new GatewayProperties();
        properties.getClient().setForwardHeaders(new ArrayList<>()); // Empty forward headers
        GrpcMetadataPopulator populator = new GrpcMetadataPopulator(properties);

        ServerHttpRequest request = createMockRequest("Authorization", "Bearer token-abc123");

        // Act
        Metadata metadata = populator.build(request).block();

        // Assert
        assertThat(metadata).isNotNull();
        Metadata.Key<String> authKey = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
        assertThat(metadata.get(authKey)).isEqualTo("Bearer token-abc123");
    }

    @Test
    @DisplayName("Given no security context, when build is called, should set x-principal to 'anonymous'")
    void given_noSecurityContext_when_build_should_setAnonymousPrincipal() {
        // Arrange
        GatewayProperties properties = new GatewayProperties();
        properties.getClient().setForwardHeaders(new ArrayList<>());
        GrpcMetadataPopulator populator = new GrpcMetadataPopulator(properties);

        ServerHttpRequest request = createMockRequest();

        // Act
        Metadata metadata = populator.build(request).block();

        // Assert
        assertThat(metadata).isNotNull();
        Metadata.Key<String> principalKey = Metadata.Key.of("x-principal", Metadata.ASCII_STRING_MARSHALLER);
        assertThat(metadata.get(principalKey)).isEqualTo("anonymous");
    }

    @Test
    @DisplayName(
            "Given multiple custom forward headers in config, when build is called, should copy all of them to metadata")
    void given_multipleForwardHeaders_when_build_should_copyAllHeaders() {
        // Arrange
        GatewayProperties properties = new GatewayProperties();
        properties.getClient().setForwardHeaders(List.of("x-request-id", "x-tenant-id", "x-correlation-id"));
        GrpcMetadataPopulator populator = new GrpcMetadataPopulator(properties);

        ServerHttpRequest request = createMockRequest(
                "X-Request-Id", "req-456",
                "X-Tenant-Id", "tenant-789",
                "X-Correlation-Id", "corr-101112");

        // Act
        Metadata metadata = populator.build(request).block();

        // Assert
        assertThat(metadata).isNotNull();
        Metadata.Key<String> reqIdKey = Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER);
        Metadata.Key<String> tenantKey = Metadata.Key.of("x-tenant-id", Metadata.ASCII_STRING_MARSHALLER);
        Metadata.Key<String> corrKey = Metadata.Key.of("x-correlation-id", Metadata.ASCII_STRING_MARSHALLER);

        assertThat(metadata.get(reqIdKey)).isEqualTo("req-456");
        assertThat(metadata.get(tenantKey)).isEqualTo("tenant-789");
        assertThat(metadata.get(corrKey)).isEqualTo("corr-101112");
    }

    @Test
    @DisplayName(
            "Given forward header with mixed case in config, when build is called, should match case-insensitively")
    void given_mixedCaseForwardHeader_when_build_should_matchCaseInsensitively() {
        // Arrange
        GatewayProperties properties = new GatewayProperties();
        properties.getClient().setForwardHeaders(List.of("X-Request-ID")); // Mixed case in config
        GrpcMetadataPopulator populator = new GrpcMetadataPopulator(properties);

        ServerHttpRequest request = createMockRequest("x-request-id", "req-999");

        // Act
        Metadata metadata = populator.build(request).block();

        // Assert
        assertThat(metadata).isNotNull();
        Metadata.Key<String> key = Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER);
        assertThat(metadata.get(key)).isEqualTo("req-999");
    }

    // -------------------------------------------------------------------------
    // Tracing headers tests (forward-tracing-headers feature)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GIVEN forward-tracing-headers=true (default) WHEN traceparent header present SHOULD forward it")
    void GIVEN_forwardTracingHeadersEnabled_WHEN_traceparentPresent_SHOULD_forwardTraceparent() {
        // Arrange
        GatewayProperties properties = new GatewayProperties();
        properties.getClient().setForwardTracingHeaders(true); // Default is true
        properties.getClient().setForwardHeaders(new ArrayList<>()); // No custom headers
        GrpcMetadataPopulator populator = new GrpcMetadataPopulator(properties);

        ServerHttpRequest request =
                createMockRequest("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");

        // Act
        Metadata metadata = populator.build(request).block();

        // Assert
        assertThat(metadata).isNotNull();
        Metadata.Key<String> key = Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER);
        assertThat(metadata.get(key)).isEqualTo("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
    }

    @Test
    @DisplayName("GIVEN forward-tracing-headers=true WHEN x-b3-traceid header present SHOULD forward it")
    void GIVEN_forwardTracingHeadersEnabled_WHEN_b3TraceIdPresent_SHOULD_forwardB3TraceId() {
        // Arrange
        GatewayProperties properties = new GatewayProperties();
        properties.getClient().setForwardTracingHeaders(true);
        properties.getClient().setForwardHeaders(new ArrayList<>());
        GrpcMetadataPopulator populator = new GrpcMetadataPopulator(properties);

        ServerHttpRequest request = createMockRequest("x-b3-traceid", "80f198ee56343ba8");

        // Act
        Metadata metadata = populator.build(request).block();

        // Assert
        assertThat(metadata).isNotNull();
        Metadata.Key<String> key = Metadata.Key.of("x-b3-traceid", Metadata.ASCII_STRING_MARSHALLER);
        assertThat(metadata.get(key)).isEqualTo("80f198ee56343ba8");
    }

    @Test
    @DisplayName("GIVEN forward-tracing-headers=false WHEN traceparent header present SHOULD NOT forward it")
    void GIVEN_forwardTracingHeadersDisabled_WHEN_traceparentPresent_SHOULD_notForward() {
        // Arrange
        GatewayProperties properties = new GatewayProperties();
        properties.getClient().setForwardTracingHeaders(false); // Disabled
        properties.getClient().setForwardHeaders(new ArrayList<>());
        GrpcMetadataPopulator populator = new GrpcMetadataPopulator(properties);

        ServerHttpRequest request =
                createMockRequest("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");

        // Act
        Metadata metadata = populator.build(request).block();

        // Assert
        assertThat(metadata).isNotNull();
        Metadata.Key<String> key = Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER);
        assertThat(metadata.get(key)).isNull(); // Should NOT be forwarded
    }

    @Test
    @DisplayName(
            "GIVEN forward-tracing-headers=false but custom forward headers set WHEN custom header present SHOULD still forward the custom header")
    void GIVEN_tracingDisabledButCustomHeadersSet_WHEN_customHeaderPresent_SHOULD_forwardCustom() {
        // Arrange
        GatewayProperties properties = new GatewayProperties();
        properties.getClient().setForwardTracingHeaders(false); // Tracing disabled
        properties.getClient().setForwardHeaders(List.of("x-request-id")); // But custom header set
        GrpcMetadataPopulator populator = new GrpcMetadataPopulator(properties);

        ServerHttpRequest request = createMockRequest("x-request-id", "req-custom-123");

        // Act
        Metadata metadata = populator.build(request).block();

        // Assert
        assertThat(metadata).isNotNull();
        Metadata.Key<String> key = Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER);
        assertThat(metadata.get(key)).isEqualTo("req-custom-123"); // Custom header still forwarded
    }

    @Test
    @DisplayName(
            "GIVEN a service routing by version on x-principal and a preview header WHEN a caller sends both SHOULD forward the preview header but never the caller's own x-principal")
    void given_versionRoutingHeaders_when_build_should_forwardPreviewButNotSpoofedPrincipal() {
        GatewayProperties properties = new GatewayProperties();
        var service = new dev.appiaworks.shiguchi.config.ServiceDefinition();
        service.setName("catalog");
        service.getVersions().setStickyHeader("x-principal");
        service.getVersions().setPreviewHeader("X-Backend-Version");
        properties.getServices().add(service);
        GrpcMetadataPopulator populator = new GrpcMetadataPopulator(properties);

        ServerHttpRequest request = createMockRequest("x-principal", "admin", "X-Backend-Version", "1.10.0");

        Metadata metadata = populator.build(request).block();

        Metadata.Key<String> principal = Metadata.Key.of("x-principal", Metadata.ASCII_STRING_MARSHALLER);
        Metadata.Key<String> preview = Metadata.Key.of("x-backend-version", Metadata.ASCII_STRING_MARSHALLER);
        assertThat(metadata.getAll(principal)).containsExactly("anonymous");
        assertThat(metadata.get(preview)).isEqualTo("1.10.0");
    }

    @Test
    @DisplayName(
            "Given x-principal listed in forward-headers, when the caller sends its own X-Principal, should send only the gateway's principal")
    void given_principalInForwardHeaders_when_callerSendsPrincipal_should_sendOnlyGatewayPrincipal() {
        GatewayProperties properties = new GatewayProperties();
        properties.getClient().setForwardHeaders(List.of("x-principal", "x-tenant-id"));
        GrpcMetadataPopulator populator = new GrpcMetadataPopulator(properties);

        ServerHttpRequest request = createMockRequest("X-Principal", "admin", "X-Tenant-Id", "t1");

        Metadata metadata = populator.build(request).block();

        Metadata.Key<String> principal = Metadata.Key.of("x-principal", Metadata.ASCII_STRING_MARSHALLER);
        Metadata.Key<String> tenant = Metadata.Key.of("x-tenant-id", Metadata.ASCII_STRING_MARSHALLER);
        assertThat(metadata.getAll(principal)).containsExactly("anonymous");
        assertThat(metadata.get(principal)).isEqualTo("anonymous");
        assertThat(metadata.get(tenant)).isEqualTo("t1");
    }

    @Test
    @DisplayName(
            "Given authorization listed in forward-headers, when the caller sends a token, should send the token once")
    void given_authorizationInForwardHeaders_when_build_should_sendTokenOnce() {
        GatewayProperties properties = new GatewayProperties();
        properties.getClient().setForwardHeaders(List.of("Authorization"));
        GrpcMetadataPopulator populator = new GrpcMetadataPopulator(properties);

        ServerHttpRequest request = createMockRequest("Authorization", "Bearer token-abc123");

        Metadata metadata = populator.build(request).block();

        Metadata.Key<String> auth = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
        assertThat(metadata.getAll(auth)).containsExactly("Bearer token-abc123");
    }

    @Test
    @DisplayName(
            "Given a padded, mixed-case X-Principal in forward-headers, when the caller sends one, should still send only the gateway's principal")
    void given_paddedPrincipalInForwardHeaders_when_callerSendsPrincipal_should_sendOnlyGatewayPrincipal() {
        GatewayProperties properties = new GatewayProperties();
        properties.getClient().setForwardHeaders(List.of(" X-Principal "));
        GrpcMetadataPopulator populator = new GrpcMetadataPopulator(properties);

        ServerHttpRequest request = createMockRequest("X-Principal", "admin");

        Metadata metadata = populator.build(request).block();

        Metadata.Key<String> principal = Metadata.Key.of("x-principal", Metadata.ASCII_STRING_MARSHALLER);
        assertThat(metadata.getAll(principal)).containsExactly("anonymous");
    }
}
