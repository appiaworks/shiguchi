package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.config.GatewayMode;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.RestBatchLoaderConfig;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import graphql.GraphQLContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import org.dataloader.BatchLoaderEnvironment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.graphql.execution.BatchLoaderRegistry;
import reactor.core.publisher.Mono;

/**
 * BDD-style unit tests for {@link RestBatchDataLoaderRegistrar}.
 *
 * <p>{@link BatchLoaderRegistry} is faked with an in-memory implementation (same pattern as
 * {@code GrpcDataLoaderRegistryFactoryTest}) that records the {@code registerMappedBatchLoader}
 * callback per type name, so the scan phase can be verified without a real Spring GraphQL
 * request cycle.
 */
@DisplayName("RestBatchDataLoaderRegistrar")
class RestBatchDataLoaderRegistrarTest {

    /** Minimal BatchLoaderRegistry fake that records the registered callback per type name. */
    private static class RecordingBatchLoaderRegistry implements BatchLoaderRegistry {
        final Map<String, BiFunction<Set<String>, BatchLoaderEnvironment, Mono<Map<String, Map<String, Object>>>>>
                registered = new java.util.LinkedHashMap<>();

        @Override
        public <K, V> RegistrationSpec<K, V> forTypePair(Class<K> keyType, Class<V> valueType) {
            throw new UnsupportedOperationException("not used by RestBatchDataLoaderRegistrar");
        }

        @Override
        @SuppressWarnings("unchecked")
        public <K, V> RegistrationSpec<K, V> forName(String name) {
            return new RegistrationSpec<>() {
                @Override
                public RegistrationSpec<K, V> withName(String name1) {
                    return this;
                }

                @Override
                public RegistrationSpec<K, V> withOptions(
                        java.util.function.Consumer<org.dataloader.DataLoaderOptions.Builder> optionsConsumer) {
                    return this;
                }

                @Override
                public RegistrationSpec<K, V> withOptions(org.dataloader.DataLoaderOptions options) {
                    return this;
                }

                @Override
                public void registerBatchLoader(
                        BiFunction<List<K>, BatchLoaderEnvironment, reactor.core.publisher.Flux<V>> loader) {
                    throw new UnsupportedOperationException("not used by RestBatchDataLoaderRegistrar");
                }

                @Override
                @SuppressWarnings("unchecked")
                public void registerMappedBatchLoader(
                        BiFunction<Set<K>, BatchLoaderEnvironment, Mono<Map<K, V>>> loader) {
                    registered.put(name, (BiFunction<
                                    Set<String>, BatchLoaderEnvironment, Mono<Map<String, Map<String, Object>>>>)
                            (BiFunction<?, ?, ?>) loader);
                }
            };
        }

        @Override
        public void registerDataLoaders(org.dataloader.DataLoaderRegistry registry, GraphQLContext graphQlContext) {
            // not exercised
        }
    }

    private static ServiceDefinition restDownstreamService(
            String name, String baseUrl, RestBatchLoaderConfig... loaders) {
        ServiceDefinition svcDef = new ServiceDefinition();
        svcDef.setName(name);
        svcDef.setMode(GatewayMode.REST_DOWNSTREAM);
        svcDef.setBaseUrl(baseUrl);
        svcDef.setRestBatchLoaders(List.of(loaders));
        return svcDef;
    }

    private static RestBatchLoaderConfig loaderConfig(String type, String endpoint) {
        RestBatchLoaderConfig cfg = new RestBatchLoaderConfig();
        cfg.setType(type);
        cfg.setEndpoint(endpoint);
        return cfg;
    }

    private static GatewayProperties propsWith(ServiceDefinition... services) {
        GatewayProperties props = new GatewayProperties();
        props.setServices(List.of(services));
        return props;
    }

    @Test
    @DisplayName("Given no services are REST_DOWNSTREAM, when scan runs, should register no loaders")
    void given_noRestDownstreamServices_when_scan_should_registerNoLoaders() {
        // Arrange
        ServiceDefinition grpcService = new ServiceDefinition();
        grpcService.setName("inv-grpc");
        grpcService.setMode(GatewayMode.DYNAMIC);
        GatewayProperties props = propsWith(grpcService);
        RecordingBatchLoaderRegistry registry = new RecordingBatchLoaderRegistry();
        RestBatchDataLoaderRegistrar registrar = new RestBatchDataLoaderRegistrar(props, registry);

        // Act
        registrar.scan();

        // Assert
        assertThat(registrar.getLoaders()).isEmpty();
        assertThat(registry.registered).isEmpty();
    }

    @Test
    @DisplayName(
            "Given a REST_DOWNSTREAM service with no restBatchLoaders declared, when scan runs, should register no loaders")
    void given_restDownstreamServiceWithNoLoaders_when_scan_should_registerNoLoaders() {
        // Arrange
        ServiceDefinition svcDef = restDownstreamService("inventory", "http://inventory:8080");
        GatewayProperties props = propsWith(svcDef);
        RecordingBatchLoaderRegistry registry = new RecordingBatchLoaderRegistry();
        RestBatchDataLoaderRegistrar registrar = new RestBatchDataLoaderRegistrar(props, registry);

        // Act
        registrar.scan();

        // Assert
        assertThat(registrar.getLoaders()).isEmpty();
    }

    @Test
    @DisplayName(
            "Given a REST_DOWNSTREAM service with one restBatchLoader, when scan runs, should register one loader keyed by its GraphQL type")
    void given_oneRestBatchLoaderDeclared_when_scan_should_registerOneLoaderByType() {
        // Arrange
        RestBatchLoaderConfig cfg = loaderConfig("Product", "/products");
        ServiceDefinition svcDef = restDownstreamService("catalog", "http://catalog:8080", cfg);
        GatewayProperties props = propsWith(svcDef);
        RecordingBatchLoaderRegistry registry = new RecordingBatchLoaderRegistry();
        RestBatchDataLoaderRegistrar registrar = new RestBatchDataLoaderRegistrar(props, registry);

        // Act
        registrar.scan();

        // Assert
        assertThat(registrar.getLoaders()).containsOnlyKeys("Product");
        assertThat(registry.registered).containsOnlyKeys("Product");
    }

    @Test
    @DisplayName("Given a restBatchLoader entry with a blank type, when scan runs, should skip it without throwing")
    void given_loaderWithBlankType_when_scan_should_skipWithoutThrowing() {
        // Arrange
        RestBatchLoaderConfig blank = loaderConfig("", "/whatever");
        RestBatchLoaderConfig valid = loaderConfig("Category", "/categories");
        ServiceDefinition svcDef = restDownstreamService("catalog", "http://catalog:8080", blank, valid);
        GatewayProperties props = propsWith(svcDef);
        RecordingBatchLoaderRegistry registry = new RecordingBatchLoaderRegistry();
        RestBatchDataLoaderRegistrar registrar = new RestBatchDataLoaderRegistrar(props, registry);

        // Act — must not throw
        registrar.scan();

        // Assert — only the valid entry registered
        assertThat(registrar.getLoaders()).containsOnlyKeys("Category");
    }

    @Test
    @DisplayName(
            "Given two REST_DOWNSTREAM services each with a loader, when scan runs, should register loaders for both, keyed by their own type")
    void given_twoServicesWithLoaders_when_scan_should_registerBoth() {
        // Arrange
        ServiceDefinition svc1 =
                restDownstreamService("catalog", "http://catalog:8080", loaderConfig("Product", "/products"));
        ServiceDefinition svc2 =
                restDownstreamService("accounts", "http://accounts:9090", loaderConfig("User", "/users"));
        GatewayProperties props = propsWith(svc1, svc2);
        RecordingBatchLoaderRegistry registry = new RecordingBatchLoaderRegistry();
        RestBatchDataLoaderRegistrar registrar = new RestBatchDataLoaderRegistrar(props, registry);

        // Act
        registrar.scan();

        // Assert
        assertThat(registrar.getLoaders()).containsOnlyKeys("Product", "User");
    }

    @Test
    @DisplayName(
            "Given getLoaders is called, when the returned map is modified, should throw UnsupportedOperationException (unmodifiable view)")
    void given_getLoadersCalled_when_returnedMapModified_should_throwUnsupportedOperationException() {
        // Arrange
        ServiceDefinition svcDef =
                restDownstreamService("catalog", "http://catalog:8080", loaderConfig("Product", "/products"));
        GatewayProperties props = propsWith(svcDef);
        RestBatchDataLoaderRegistrar registrar =
                new RestBatchDataLoaderRegistrar(props, new RecordingBatchLoaderRegistry());
        registrar.scan();

        // Act & Assert
        Map<String, BatchLoader> loaders = registrar.getLoaders();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> loaders.put("Other", null))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
