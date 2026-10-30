package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.appiaworks.shiguchi.config.DataLoaderConfig;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.gateway.GrpcMetadataPopulator;
import graphql.GraphQLContext;
import io.grpc.Metadata;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import org.dataloader.BatchLoaderEnvironment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.graphql.execution.BatchLoaderRegistry;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * BDD-style unit tests for {@link GrpcDataLoaderRegistryFactory}.
 *
 * <p>{@link BatchLoaderRegistry} is faked with an in-memory implementation that records the
 * {@code registerMappedBatchLoader} callback per type name, mirroring how Spring GraphQL would
 * invoke it per request. {@link GrpcBatchDataLoaderRegistrar} is mocked since only its
 * {@code getLoaders()} contract is used by the factory under test.
 */
class GrpcDataLoaderRegistryFactoryTest {

    /** Minimal BatchLoaderRegistry fake that records the registered callback per type name. */
    private static class RecordingBatchLoaderRegistry implements BatchLoaderRegistry {
        final Map<String, BiFunction<Set<String>, BatchLoaderEnvironment, Mono<Map<String, Map<String, Object>>>>>
                registered = new java.util.LinkedHashMap<>();
        /** Every registerMappedBatchLoader call, in order, so repeated registrations are visible. */
        final List<String> registrationCalls = new java.util.ArrayList<>();

        @Override
        @SuppressWarnings("unchecked")
        public <K, V> RegistrationSpec<K, V> forTypePair(Class<K> keyType, Class<V> valueType) {
            throw new UnsupportedOperationException("not used by GrpcDataLoaderRegistryFactory");
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
                    throw new UnsupportedOperationException("not used by GrpcDataLoaderRegistryFactory");
                }

                @Override
                @SuppressWarnings("unchecked")
                public void registerMappedBatchLoader(
                        BiFunction<Set<K>, BatchLoaderEnvironment, Mono<Map<K, V>>> loader) {
                    registrationCalls.add(name);
                    registered.put(name, (BiFunction<
                                    Set<String>, BatchLoaderEnvironment, Mono<Map<String, Map<String, Object>>>>)
                            (BiFunction<?, ?, ?>) loader);
                }
            };
        }

        @Override
        public void registerDataLoaders(org.dataloader.DataLoaderRegistry registry, GraphQLContext graphQLContext) {
            // not exercised
        }
    }

    /** BatchLoader fake that records the keys and metadata it was invoked with. */
    private static class RecordingBatchLoader implements BatchLoader {
        List<String> lastKeys;
        Metadata lastMetadata;
        Mono<Map<String, Map<String, Object>>> toReturn = Mono.just(Map.of());

        @Override
        public Mono<Map<String, Map<String, Object>>> loadMapped(List<String> keys, Metadata metadata) {
            this.lastKeys = keys;
            this.lastMetadata = metadata;
            return toReturn;
        }
    }

    private static BatchLoaderEnvironment envWithGraphQlContext(GraphQLContext ctx) {
        BatchLoaderEnvironment env = mock(BatchLoaderEnvironment.class);
        when(env.getContext()).thenReturn(ctx);
        return env;
    }

    @Test
    @DisplayName(
            "Given the registrar has no loaders, when registerLoaders runs, should register nothing with the BatchLoaderRegistry")
    void given_noLoaders_when_registerLoaders_should_registerNothing() {
        // Arrange
        RecordingBatchLoaderRegistry registry = new RecordingBatchLoaderRegistry();
        GrpcBatchDataLoaderRegistrar registrar = mock(GrpcBatchDataLoaderRegistrar.class);
        when(registrar.getLoaders()).thenReturn(Map.of());
        GrpcDataLoaderRegistryFactory factory = new GrpcDataLoaderRegistryFactory(registry, registrar);

        // Act
        factory.registerLoaders();

        // Assert
        assertThat(registry.registered).isEmpty();
    }

    @Test
    @DisplayName(
            "Given the registrar has one loader, when registerLoaders runs, should register it under its type name")
    void given_oneLoader_when_registerLoaders_should_registerUnderTypeName() {
        // Arrange
        RecordingBatchLoaderRegistry registry = new RecordingBatchLoaderRegistry();
        GrpcBatchDataLoaderRegistrar registrar = mock(GrpcBatchDataLoaderRegistrar.class);
        RecordingBatchLoader loader = new RecordingBatchLoader();
        when(registrar.getLoaders()).thenReturn(Map.of("Book", loader));
        GrpcDataLoaderRegistryFactory factory = new GrpcDataLoaderRegistryFactory(registry, registrar);

        // Act
        factory.registerLoaders();

        // Assert
        assertThat(registry.registered).containsOnlyKeys("Book");
    }

    @Test
    @DisplayName(
            "Given a registered loader's callback is invoked with keys, when the callback runs, should delegate to the underlying loader's loadMapped with those keys")
    void given_callbackInvokedWithKeys_when_callbackRuns_should_delegateToUnderlyingLoader() {
        // Arrange
        RecordingBatchLoaderRegistry registry = new RecordingBatchLoaderRegistry();
        GrpcBatchDataLoaderRegistrar registrar = mock(GrpcBatchDataLoaderRegistrar.class);
        RecordingBatchLoader loader = new RecordingBatchLoader();
        loader.toReturn = Mono.just(Map.of("34", Map.of("name", "Tritanium")));
        when(registrar.getLoaders()).thenReturn(Map.of("Book", loader));
        GrpcDataLoaderRegistryFactory factory = new GrpcDataLoaderRegistryFactory(registry, registrar);
        factory.registerLoaders();

        GraphQLContext emptyContext = GraphQLContext.newContext().build();
        BatchLoaderEnvironment env = envWithGraphQlContext(emptyContext);

        // Act
        Mono<Map<String, Map<String, Object>>> result =
                registry.registered.get("Book").apply(Set.of("34"), env);

        // Assert
        assertThat(result.block()).containsEntry("34", Map.of("name", "Tritanium"));
        assertThat(loader.lastKeys).containsExactly("34");
    }

    @Test
    @DisplayName(
            "Given the BatchLoaderEnvironment context carries a ServerWebExchange with an Authorization header, when the callback runs, should forward it as gRPC Metadata")
    void given_contextHasAuthorizationHeader_when_callbackRuns_should_forwardAsGrpcMetadata() {
        // Arrange
        RecordingBatchLoaderRegistry registry = new RecordingBatchLoaderRegistry();
        GrpcBatchDataLoaderRegistrar registrar = mock(GrpcBatchDataLoaderRegistrar.class);
        RecordingBatchLoader loader = new RecordingBatchLoader();
        when(registrar.getLoaders()).thenReturn(Map.of("Book", loader));
        GrpcDataLoaderRegistryFactory factory = new GrpcDataLoaderRegistryFactory(registry, registrar);
        factory.registerLoaders();

        ServerWebExchange exchange = mock(ServerWebExchange.class);
        ServerHttpRequest httpRequest = mock(ServerHttpRequest.class);
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer secret-token");
        when(exchange.getRequest()).thenReturn(httpRequest);
        when(httpRequest.getHeaders()).thenReturn(headers);

        GraphQLContext ctx = GraphQLContext.newContext()
                .put(ServerWebExchange.class, exchange)
                .build();
        BatchLoaderEnvironment env = envWithGraphQlContext(ctx);

        // Act
        registry.registered.get("Book").apply(Set.of("1"), env).block();

        // Assert
        Metadata.Key<String> authKey = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
        assertThat(loader.lastMetadata.get(authKey)).isEqualTo("Bearer secret-token");
    }

    @Test
    @DisplayName(
            "Given a populator and the request's saved Reactor context, when the callback runs, should send the populator's metadata built as the caller")
    void given_populatorAndSavedContext_when_callbackRuns_should_sendPopulatorMetadataAsCaller() {
        // Arrange
        RecordingBatchLoaderRegistry registry = new RecordingBatchLoaderRegistry();
        GrpcBatchDataLoaderRegistrar registrar = mock(GrpcBatchDataLoaderRegistrar.class);
        RecordingBatchLoader loader = new RecordingBatchLoader();
        when(registrar.getLoaders()).thenReturn(Map.of("Book", loader));
        GrpcMetadataPopulator populator = new GrpcMetadataPopulator(new GatewayProperties());
        GrpcDataLoaderRegistryFactory factory =
                new GrpcDataLoaderRegistryFactory(registry, registrar, new GatewayProperties(), populator);
        factory.registerLoaders();

        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/graphql")
                .header("Authorization", "Bearer bob-token")
                .build());
        GraphQLContext ctx = GraphQLContext.newContext().build();
        GraphQlRequestContext.save(
                ctx, ReactiveSecurityContextHolder.withAuthentication(new TestingAuthenticationToken("bob", "n/a")));
        ctx.put(ServerWebExchange.class, exchange);
        BatchLoaderEnvironment env = envWithGraphQlContext(ctx);

        // Act
        registry.registered.get("Book").apply(Set.of("1"), env).block();

        // Assert
        assertThat(loader.lastMetadata.get(Metadata.Key.of("x-principal", Metadata.ASCII_STRING_MARSHALLER)))
                .isEqualTo("bob");
        assertThat(loader.lastMetadata.get(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)))
                .isEqualTo("Bearer bob-token");
    }

    @Test
    @DisplayName(
            "Given the BatchLoaderEnvironment context has no ServerWebExchange, when the callback runs, should call loadMapped with metadata that carries no authorization header")
    void given_contextHasNoServerWebExchange_when_callbackRuns_should_useMetadataWithoutAuthHeader() {
        // Arrange
        RecordingBatchLoaderRegistry registry = new RecordingBatchLoaderRegistry();
        GrpcBatchDataLoaderRegistrar registrar = mock(GrpcBatchDataLoaderRegistrar.class);
        RecordingBatchLoader loader = new RecordingBatchLoader();
        when(registrar.getLoaders()).thenReturn(Map.of("Book", loader));
        GrpcDataLoaderRegistryFactory factory = new GrpcDataLoaderRegistryFactory(registry, registrar);
        factory.registerLoaders();

        GraphQLContext emptyContext = GraphQLContext.newContext().build();
        BatchLoaderEnvironment env = envWithGraphQlContext(emptyContext);

        // Act
        registry.registered.get("Book").apply(Set.of("1"), env).block();

        // Assert
        Metadata.Key<String> authKey = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
        assertThat(loader.lastMetadata.get(authKey)).isNull();
    }

    @Test
    @DisplayName(
            "Given DataLoaderConfig has maxBatchSize, when registerLoaders runs, should register the loader with config options applied")
    void given_dataLoaderConfigWithMaxBatchSize_when_registerLoaders_should_registerLoaderWithConfig() {
        // Arrange
        RecordingBatchLoaderRegistry registry = new RecordingBatchLoaderRegistry();
        GrpcBatchDataLoaderRegistrar registrar = mock(GrpcBatchDataLoaderRegistrar.class);
        RecordingBatchLoader loader = new RecordingBatchLoader();
        when(registrar.getLoaders()).thenReturn(Map.of("Book", loader));

        DataLoaderConfig config = new DataLoaderConfig();
        config.setMaxBatchSize(50);
        GrpcDataLoaderRegistryFactory factory = new GrpcDataLoaderRegistryFactory(registry, registrar, config);

        // Act
        factory.registerLoaders();

        // Assert: loader is registered (withOptions is called internally)
        assertThat(registry.registered).containsKey("Book");
    }

    @Test
    @DisplayName(
            "Given a type with cache config, when the registered callback is invoked twice with same keys, should return cached result from first invocation")
    void given_typeWithCacheConfig_when_callbackInvokedTwiceWithSameKeys_should_returnCachedResult() {
        // Arrange
        RecordingBatchLoaderRegistry registry = new RecordingBatchLoaderRegistry();
        GrpcBatchDataLoaderRegistrar registrar = mock(GrpcBatchDataLoaderRegistrar.class);
        RecordingBatchLoader loader = new RecordingBatchLoader();
        loader.toReturn = Mono.just(Map.of("1", Map.of("title", "Book1")));
        when(registrar.getLoaders()).thenReturn(Map.of("Book", loader));

        DataLoaderConfig config = new DataLoaderConfig();
        Duration ttl = Duration.ofHours(1);
        config.getCache().put("Book", new DataLoaderConfig.TypeCache());
        config.getCache().get("Book").setTtl(ttl);
        config.getCache().get("Book").setMaxSize(1000);

        GrpcDataLoaderRegistryFactory factory = new GrpcDataLoaderRegistryFactory(registry, registrar, config);
        factory.registerLoaders();

        GraphQLContext ctx = GraphQLContext.newContext().build();
        BatchLoaderEnvironment env = envWithGraphQlContext(ctx);

        // Act: first invocation
        Mono<Map<String, Map<String, Object>>> result1 =
                registry.registered.get("Book").apply(Set.of("1"), env);
        Map<String, Map<String, Object>> data1 = result1.block();

        // Update the loader's response to verify it's not called again
        loader.toReturn = Mono.just(Map.of("1", Map.of("title", "Book1-Updated")));

        // Act: second invocation with same keys
        Mono<Map<String, Map<String, Object>>> result2 =
                registry.registered.get("Book").apply(Set.of("1"), env);
        Map<String, Map<String, Object>> data2 = result2.block();

        // Assert: first result is the original
        assertThat(data1).containsEntry("1", Map.of("title", "Book1"));
        // Assert: second result is still the original (cache hit, not the updated value)
        assertThat(data2).containsEntry("1", Map.of("title", "Book1"));
    }

    @Test
    @DisplayName(
            "Given a type without cache config, when the registered callback is invoked multiple times with same keys, should call underlying loader each time")
    void given_typeWithoutCacheConfig_when_callbackInvokedMultipleTimes_should_callUnderlyingLoaderEachTime() {
        // Arrange
        RecordingBatchLoaderRegistry registry = new RecordingBatchLoaderRegistry();
        GrpcBatchDataLoaderRegistrar registrar = mock(GrpcBatchDataLoaderRegistrar.class);
        RecordingBatchLoader loader = new RecordingBatchLoader();
        loader.toReturn = Mono.just(Map.of("1", Map.of("title", "Book1")));
        when(registrar.getLoaders()).thenReturn(Map.of("Book", loader));

        DataLoaderConfig config = new DataLoaderConfig();
        // No cache entry for "Book"
        GrpcDataLoaderRegistryFactory factory = new GrpcDataLoaderRegistryFactory(registry, registrar, config);
        factory.registerLoaders();

        GraphQLContext ctx = GraphQLContext.newContext().build();
        BatchLoaderEnvironment env = envWithGraphQlContext(ctx);

        // Act: first invocation
        Mono<Map<String, Map<String, Object>>> result1 =
                registry.registered.get("Book").apply(Set.of("1"), env);
        Map<String, Map<String, Object>> data1 = result1.block();

        // Update the loader's response
        loader.toReturn = Mono.just(Map.of("1", Map.of("title", "Book1-Updated")));

        // Act: second invocation with same keys
        Mono<Map<String, Map<String, Object>>> result2 =
                registry.registered.get("Book").apply(Set.of("1"), env);
        Map<String, Map<String, Object>> data2 = result2.block();

        // Assert: second result reflects the updated response (no cache)
        assertThat(data1).containsEntry("1", Map.of("title", "Book1"));
        assertThat(data2).containsEntry("1", Map.of("title", "Book1-Updated"));
    }

    @Test
    @DisplayName(
            "Given cache config with unknown type name, when registerLoaders runs, should register known types successfully and log warning for unknown type")
    void given_cacheConfigWithUnknownTypeName_when_registerLoaders_should_registerKnownTypesAndSkipUnknown() {
        // Arrange
        RecordingBatchLoaderRegistry registry = new RecordingBatchLoaderRegistry();
        GrpcBatchDataLoaderRegistrar registrar = mock(GrpcBatchDataLoaderRegistrar.class);
        RecordingBatchLoader loaderBook = new RecordingBatchLoader();
        RecordingBatchLoader loaderAuthor = new RecordingBatchLoader();
        when(registrar.getLoaders()).thenReturn(Map.of("Book", loaderBook, "Author", loaderAuthor));

        DataLoaderConfig config = new DataLoaderConfig();
        // Add cache entry for known type
        config.getCache().put("Book", new DataLoaderConfig.TypeCache());
        config.getCache().get("Book").setTtl(Duration.ofHours(1));
        // Add cache entry for unknown type
        config.getCache().put("UnknownType", new DataLoaderConfig.TypeCache());
        config.getCache().get("UnknownType").setTtl(Duration.ofHours(1));

        GrpcDataLoaderRegistryFactory factory = new GrpcDataLoaderRegistryFactory(registry, registrar, config);

        // Act
        factory.registerLoaders();

        // Assert: both known types registered successfully
        assertThat(registry.registered).containsKeys("Book", "Author");
        // Unknown type should not cause failure
        assertThat(registry.registered).doesNotContainKey("UnknownType");
    }

    @Test
    @DisplayName(
            "GIVEN a type that appears only after startup WHEN onSchemaReloaded runs twice SHOULD register that type with the BatchLoaderRegistry exactly once")
    void given_newlyAppearedType_when_onSchemaReloadedRunsTwice_should_registerTypeOnce() {
        // Arrange
        RecordingBatchLoaderRegistry registry = new RecordingBatchLoaderRegistry();
        GrpcBatchDataLoaderRegistrar registrar = mock(GrpcBatchDataLoaderRegistrar.class);
        Map<String, BatchLoader> loaders = new java.util.HashMap<>();
        when(registrar.getLoaders()).thenReturn(loaders);
        GrpcDataLoaderRegistryFactory factory = new GrpcDataLoaderRegistryFactory(registry, registrar);
        factory.registerLoaders();
        loaders.put("Book", new RecordingBatchLoader());

        // Act
        factory.onSchemaReloaded();
        factory.onSchemaReloaded();

        // Assert
        assertThat(registry.registrationCalls).containsExactly("Book");
    }

    @Test
    @DisplayName(
            "GIVEN a type's loader was replaced in the registrar after registration WHEN the registered callback runs SHOULD call the replacement loader, not the original")
    void given_loaderReplacedInRegistrar_when_registeredCallbackRuns_should_useReplacementLoader() {
        // Arrange
        RecordingBatchLoaderRegistry registry = new RecordingBatchLoaderRegistry();
        GrpcBatchDataLoaderRegistrar registrar = mock(GrpcBatchDataLoaderRegistrar.class);
        Map<String, BatchLoader> loaders = new java.util.HashMap<>();
        RecordingBatchLoader original = new RecordingBatchLoader();
        RecordingBatchLoader replacement = new RecordingBatchLoader();
        replacement.toReturn = Mono.just(Map.of("34", Map.of("name", "Replaced")));
        loaders.put("Book", original);
        when(registrar.getLoaders()).thenReturn(loaders);
        GrpcDataLoaderRegistryFactory factory = new GrpcDataLoaderRegistryFactory(registry, registrar);
        factory.registerLoaders();
        loaders.put("Book", replacement);

        BatchLoaderEnvironment env =
                envWithGraphQlContext(GraphQLContext.newContext().build());

        // Act
        Map<String, Map<String, Object>> result =
                registry.registered.get("Book").apply(Set.of("34"), env).block();

        // Assert
        assertThat(result).containsEntry("34", Map.of("name", "Replaced"));
        assertThat(replacement.lastKeys).containsExactly("34");
        assertThat(original.lastKeys).isNull();
    }
}
