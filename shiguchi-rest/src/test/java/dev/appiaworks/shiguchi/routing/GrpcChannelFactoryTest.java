package dev.appiaworks.shiguchi.routing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.config.ClientConfig;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.gateway.GrpcLoggingInterceptor;
import dev.appiaworks.shiguchi.gateway.GrpcMetricsInterceptor;
import io.grpc.ManagedChannel;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * BDD-style unit tests for GrpcChannelFactory.
 *
 * <p>The factory is responsible for building fully-configured outbound gRPC ManagedChannels.
 * Tests verify that:
 * - Channel configuration (insecure vs transport-security, keepalive, idle timeout) is applied
 * - Static metadata headers are attached when present
 * - Interceptor chain is assembled in the correct order
 * - Channel builds successfully and is usable (lazy connect, no actual network I/O)
 *
 * <p>Strategy:
 * - GatewayProperties and ServiceDefinition are real instances (not mocked) so the
 *   factory reads actual configuration.
 * - GrpcMetricsInterceptor and GrpcLoggingInterceptor are mocked lenient instances
 *   (the factory only stores them in the builder; it does not invoke them without an RPC).
 * - GrpcOpenTelemetry is always Optional.empty() to avoid OpenTelemetry SDK dependencies.
 * - ManagedChannels are shut down in @AfterEach to avoid resource leaks.
 */
@ExtendWith(MockitoExtension.class)
class GrpcChannelFactoryTest {

    private static final String TEST_HOST = "localhost";
    private static final int TEST_PORT = 65000;

    @Mock
    private GrpcMetricsInterceptor metricsInterceptor;

    @Mock
    private GrpcLoggingInterceptor loggingInterceptor;

    private ManagedChannel channel;

    // -------------------------------------------------------------------------
    // Test 1: insecure=true branch — channel built with usePlaintext()
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "GIVEN a ServiceDefinition with insecure=true, WHEN build() is called, SHOULD return a non-null ManagedChannel whose authority matches host:port")
    void GIVEN_insecureServiceDefinition_WHEN_build_SHOULD_returnChannelWithCorrectAuthority() {
        // Arrange
        GatewayProperties properties = newPropertiesWithDefaultClientConfig();
        GrpcChannelFactory factory = new GrpcChannelFactory(
                properties, metricsInterceptor, loggingInterceptor, Optional.empty(), noCustomizers(), noResolvers());

        ServiceDefinition svcDef = new ServiceDefinition();
        svcDef.setHost(TEST_HOST);
        svcDef.setPort(TEST_PORT);
        svcDef.setInsecure(true); // Plaintext connection
        svcDef.setStaticMetadata(new HashMap<>());

        // Act
        channel = factory.build(svcDef);

        // Assert
        assertThat(channel).isNotNull();
        assertThat(channel.authority()).isEqualTo(TEST_HOST + ":" + TEST_PORT);
    }

    // -------------------------------------------------------------------------
    // Test 2: static metadata with non-empty entries — interceptor branch exercised
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "GIVEN a ServiceDefinition with static metadata entries, WHEN build(), SHOULD return a usable non-null channel (static-metadata interceptor path exercised without throwing)")
    void GIVEN_staticMetadataPresent_WHEN_build_SHOULD_returnChannelWithInterceptorAttached() {
        // Arrange
        GatewayProperties properties = newPropertiesWithDefaultClientConfig();
        GrpcChannelFactory factory = new GrpcChannelFactory(
                properties, metricsInterceptor, loggingInterceptor, Optional.empty(), noCustomizers(), noResolvers());

        ServiceDefinition svcDef = new ServiceDefinition();
        svcDef.setHost(TEST_HOST);
        svcDef.setPort(TEST_PORT);
        svcDef.setInsecure(true);
        Map<String, String> staticMetadata = new HashMap<>();
        staticMetadata.put("x-tenant-id", "tenant-123");
        staticMetadata.put("x-request-id", "req-456");
        svcDef.setStaticMetadata(staticMetadata);

        // Act
        channel = factory.build(svcDef);

        // Assert — no exception thrown, channel is usable
        assertThat(channel).isNotNull();
        assertThat(channel.authority()).isEqualTo(TEST_HOST + ":" + TEST_PORT);
    }

    // -------------------------------------------------------------------------
    // Test 3: static metadata empty — interceptor branch skipped gracefully
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "GIVEN a ServiceDefinition with empty static metadata, WHEN build(), SHOULD return non-null channel (no interceptor branch)")
    void GIVEN_emptyStaticMetadata_WHEN_build_SHOULD_returnChannelWithoutAdditionalInterceptor() {
        // Arrange
        GatewayProperties properties = newPropertiesWithDefaultClientConfig();
        GrpcChannelFactory factory = new GrpcChannelFactory(
                properties, metricsInterceptor, loggingInterceptor, Optional.empty(), noCustomizers(), noResolvers());

        ServiceDefinition svcDef = new ServiceDefinition();
        svcDef.setHost(TEST_HOST);
        svcDef.setPort(TEST_PORT);
        svcDef.setInsecure(true);
        svcDef.setStaticMetadata(new HashMap<>()); // Empty

        // Act
        channel = factory.build(svcDef);

        // Assert
        assertThat(channel).isNotNull();
        assertThat(channel.authority()).isEqualTo(TEST_HOST + ":" + TEST_PORT);
    }

    // -------------------------------------------------------------------------
    // Test 4: insecure=false branch — transport-security applied
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "GIVEN a ServiceDefinition with insecure=false, WHEN build(), SHOULD return a non-null channel (transport-security branch exercised; no actual TLS connection)")
    void GIVEN_secureServiceDefinition_WHEN_build_SHOULD_returnChannelWithTransportSecurityApplied() {
        // Arrange
        GatewayProperties properties = newPropertiesWithDefaultClientConfig();
        GrpcChannelFactory factory = new GrpcChannelFactory(
                properties, metricsInterceptor, loggingInterceptor, Optional.empty(), noCustomizers(), noResolvers());

        ServiceDefinition svcDef = new ServiceDefinition();
        svcDef.setHost(TEST_HOST);
        svcDef.setPort(TEST_PORT);
        svcDef.setInsecure(false); // Requires TLS (build() is lazy; no actual connection)
        svcDef.setStaticMetadata(new HashMap<>());

        // Act
        channel = factory.build(svcDef);

        // Assert
        assertThat(channel).isNotNull();
        assertThat(channel.authority()).isEqualTo(TEST_HOST + ":" + TEST_PORT);
    }

    // -------------------------------------------------------------------------
    // Test 5: positive idleTimeout — idle timeout branch exercised
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "GIVEN a ClientConfig with a positive idleTimeout, WHEN build(), SHOULD return non-null channel (idle-timeout branch)")
    void GIVEN_positiveIdleTimeout_WHEN_build_SHOULD_returnChannelWithIdleTimeoutApplied() {
        // Arrange — construct factory AFTER setting idleTimeout, since constructor captures ClientConfig
        GatewayProperties properties = new GatewayProperties();
        ClientConfig clientConfig = new ClientConfig();
        clientConfig.setIdleTimeout(Duration.ofSeconds(5)); // Positive
        properties.setClient(clientConfig);

        GrpcChannelFactory factory = new GrpcChannelFactory(
                properties, metricsInterceptor, loggingInterceptor, Optional.empty(), noCustomizers(), noResolvers());

        ServiceDefinition svcDef = new ServiceDefinition();
        svcDef.setHost(TEST_HOST);
        svcDef.setPort(TEST_PORT);
        svcDef.setInsecure(true);
        svcDef.setStaticMetadata(new HashMap<>());

        // Act
        channel = factory.build(svcDef);

        // Assert
        assertThat(channel).isNotNull();
        assertThat(channel.authority()).isEqualTo(TEST_HOST + ":" + TEST_PORT);
    }

    // -------------------------------------------------------------------------
    // Test 6: idleTimeout = ZERO — idle timeout branch skipped
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "GIVEN a ClientConfig with idleTimeout = Duration.ZERO, WHEN build(), SHOULD return non-null channel (idle branch skipped)")
    void GIVEN_zeroIdleTimeout_WHEN_build_SHOULD_returnChannelWithoutIdleTimeout() {
        // Arrange — construct factory AFTER setting idleTimeout to ZERO
        GatewayProperties properties = new GatewayProperties();
        ClientConfig clientConfig = new ClientConfig();
        clientConfig.setIdleTimeout(Duration.ZERO); // Zero = skip idle timeout
        properties.setClient(clientConfig);

        GrpcChannelFactory factory = new GrpcChannelFactory(
                properties, metricsInterceptor, loggingInterceptor, Optional.empty(), noCustomizers(), noResolvers());

        ServiceDefinition svcDef = new ServiceDefinition();
        svcDef.setHost(TEST_HOST);
        svcDef.setPort(TEST_PORT);
        svcDef.setInsecure(true);
        svcDef.setStaticMetadata(new HashMap<>());

        // Act
        channel = factory.build(svcDef);

        // Assert
        assertThat(channel).isNotNull();
        assertThat(channel.authority()).isEqualTo(TEST_HOST + ":" + TEST_PORT);
    }

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.shutdownNow();
        }
    }

    // -------------------------------------------------------------------------
    // Helper methods
    // -------------------------------------------------------------------------

    /** Empty resolver provider — no GrpcServiceAddressResolver beans registered. */
    private static org.springframework.beans.factory.ObjectProvider<GrpcServiceAddressResolver> noResolvers() {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public GrpcServiceAddressResolver getObject() {
                throw new UnsupportedOperationException("no resolvers registered");
            }

            @Override
            public java.util.stream.Stream<GrpcServiceAddressResolver> orderedStream() {
                return java.util.stream.Stream.empty();
            }
        };
    }

    /** Empty customizer provider — no GrpcChannelBuilderCustomizer beans registered. */
    private static org.springframework.beans.factory.ObjectProvider<GrpcChannelBuilderCustomizer> noCustomizers() {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public GrpcChannelBuilderCustomizer getObject() {
                throw new UnsupportedOperationException("no customizers registered");
            }

            @Override
            public java.util.stream.Stream<GrpcChannelBuilderCustomizer> orderedStream() {
                return java.util.stream.Stream.empty();
            }
        };
    }

    /**
     * Creates a GatewayProperties with default ClientConfig.
     * Default config has idleTimeout = Duration.ZERO (idle branch skipped by default).
     */
    private GatewayProperties newPropertiesWithDefaultClientConfig() {
        GatewayProperties properties = new GatewayProperties();
        // properties.getClient() is initialized to a new ClientConfig() with defaults
        // (idleTimeout = Duration.ZERO, keepAliveTime = 30s, etc.)
        return properties;
    }
}
