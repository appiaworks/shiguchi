package dev.appiaworks.shiguchi.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.appiaworks.shiguchi.config.GatewayMode;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.RestConfig;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * BDD-style tests for RestDownstreamRouteLoader.
 *
 * Tests verify that:
 * - OpenAPI documents are fetched and parsed correctly
 * - Routes are registered with correct HTTP bindings and auth scopes
 * - Path prefixes are applied correctly
 * - Include/exclude filters work correctly
 * - YAML configuration scopes override document scopes
 * - Failures are logged and retried after startup
 * - REST binding is conditional on rest-enabled configuration
 */
@DisplayName("RestDownstreamRouteLoader tests")
class RestDownstreamRouteLoaderTest {

    private byte[] loadFixture(String name) throws Exception {
        InputStream is = getClass().getResourceAsStream("/openapi/" + name);
        return is.readAllBytes();
    }

    private ServiceDefinition buildServiceDef(String name, String openapiUrl) {
        ServiceDefinition def = new ServiceDefinition();
        def.setName(name);
        def.setOpenapiUrl(openapiUrl);
        def.setBaseUrl("http://inventory:8080");
        def.setMode(GatewayMode.REST_DOWNSTREAM);
        return def;
    }

    private GatewayProperties buildGatewayProperties(ServiceDefinition... services) {
        GatewayProperties props = new GatewayProperties();
        RestConfig rest = new RestConfig();
        rest.setEnabled(true);
        props.setRest(rest);
        List<ServiceDefinition> svcList = List.of(services);
        props.setServices(svcList);
        return props;
    }

    @Test
    @DisplayName(
            "GIVEN service with openapi-url and 4-op document WHEN buildContext SHOULD return context with null channel and isRestDownstream==true")
    void given_serviceWithOpenApiUrl_when_buildContext_should_returnRestContext() throws Exception {
        // Arrange
        byte[] fixture = loadFixture("inventory.json");
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(req -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, "application/json")
                        .body(new String(fixture))
                        .build()));

        ServiceDefinition svcDef = buildServiceDef("inventory", "http://localhost:8080/openapi.json");
        GatewayProperties props = buildGatewayProperties(svcDef);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        RestDownstreamRouteLoader loader =
                new RestDownstreamRouteLoader(props, registry, Optional.empty(), Optional.of(builder));

        // Act
        ServiceEndpointContext ctx = loader.buildContext(svcDef);

        // Assert
        assertThat(ctx).isNotNull();
        assertThat(ctx.getChannel()).isNull();
        assertThat(ctx.isRestDownstream()).isTrue();
        assertThat(ctx.getRawDownstreamSpec()).isNotNull();
        assertThat(ctx.getDescriptorHash()).hasSize(32); // SHA-256
        assertThat(ctx.getRoutes()).hasSize(4);
        String expectedFqn = OpenApiToDescriptor.serviceFqnFor("inventory");
        assertThat(ctx.getRoutes()).allSatisfy(route -> {
            assertThat(route.isPassthrough()).isTrue();
            assertThat(route.getUpstreamUri()).isEqualTo("http://inventory:8080");
            assertThat(route.getTransport()).isEqualTo(RouteDefinition.Transport.HTTP);
            assertThat(route.getFullServiceName()).isEqualTo(expectedFqn);
        });
    }

    @Test
    @DisplayName("GIVEN path-prefix: /inv WHEN buildContext SHOULD register routes at /inv/items, /inv/items/{sku}")
    void given_pathPrefix_when_buildContext_should_applyPrefix() throws Exception {
        // Arrange
        byte[] fixture = loadFixture("inventory.json");
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(req -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, "application/json")
                        .body(new String(fixture))
                        .build()));

        ServiceDefinition svcDef = buildServiceDef("inventory", "http://localhost:8080/openapi.json");
        svcDef.setPathPrefix("/inv");
        GatewayProperties props = buildGatewayProperties(svcDef);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        RestDownstreamRouteLoader loader =
                new RestDownstreamRouteLoader(props, registry, Optional.empty(), Optional.of(builder));

        // Act
        ServiceEndpointContext ctx = loader.buildContext(svcDef);

        // Assert
        assertThat(ctx.getRoutes()).hasSize(4);
        List<String> paths =
                ctx.getRoutes().stream().map(RouteDefinition::getHttpPath).toList();
        assertThat(paths).contains("/inv/items", "/inv/items/{sku}");
    }

    @Test
    @DisplayName("GIVEN exclude-methods: [createItem] WHEN buildContext SHOULD not register createItem")
    void given_excludeMethodsFilter_when_buildContext_should_filterOutMethods() throws Exception {
        // Arrange
        byte[] fixture = loadFixture("inventory.json");
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(req -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, "application/json")
                        .body(new String(fixture))
                        .build()));

        ServiceDefinition svcDef = buildServiceDef("inventory", "http://localhost:8080/openapi.json");
        List<String> excludeMethods = List.of("createItem");
        svcDef.setExcludeMethods(excludeMethods);
        GatewayProperties props = buildGatewayProperties(svcDef);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        RestDownstreamRouteLoader loader =
                new RestDownstreamRouteLoader(props, registry, Optional.empty(), Optional.of(builder));

        // Act
        ServiceEndpointContext ctx = loader.buildContext(svcDef);

        // Assert
        List<String> methodNames =
                ctx.getRoutes().stream().map(RouteDefinition::getMethodName).toList();
        assertThat(methodNames).doesNotContain("createItem");
        assertThat(methodNames).contains("listItems", "getItem");
    }

    @Test
    @DisplayName("GIVEN operation-scopes in config WHEN buildContext SHOULD use config scopes over document scopes")
    void given_operationScopesInConfig_when_buildContext_should_useConfigScopes() throws Exception {
        // Arrange
        byte[] fixture = loadFixture("inventory.json");
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(req -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, "application/json")
                        .body(new String(fixture))
                        .build()));

        ServiceDefinition svcDef = buildServiceDef("inventory", "http://localhost:8080/openapi.json");
        Map<String, List<String>> operationScopes = new HashMap<>();
        operationScopes.put("getItem", List.of("custom.scope"));
        svcDef.setOperationScopes(operationScopes);
        GatewayProperties props = buildGatewayProperties(svcDef);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        RestDownstreamRouteLoader loader =
                new RestDownstreamRouteLoader(props, registry, Optional.empty(), Optional.of(builder));

        // Act
        ServiceEndpointContext ctx = loader.buildContext(svcDef);

        // Assert
        RouteDefinition getItemRoute = ctx.getRoutes().stream()
                .filter(r -> r.getMethodName().equals("getItem"))
                .findFirst()
                .orElseThrow();
        assertThat(getItemRoute.getAuthScopes()).containsExactly("custom.scope");
    }

    @Test
    @DisplayName("GIVEN openapi-url blank WHEN buildContext SHOULD throw IllegalStateException naming the service")
    void given_blankOpenapiUrl_when_buildContext_should_throwIllegalState() {
        // Arrange
        ServiceDefinition svcDef = buildServiceDef("inventory", "");
        GatewayProperties props = buildGatewayProperties(svcDef);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        RestDownstreamRouteLoader loader =
                new RestDownstreamRouteLoader(props, registry, Optional.empty(), Optional.of(WebClient.builder()));

        // Act & Assert
        assertThatThrownBy(() -> loader.buildContext(svcDef))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inventory")
                .hasMessageContaining("openapi-url");
    }

    @Test
    @DisplayName("GIVEN stub returning 500 WHEN buildContext SHOULD throw exception")
    void given_stubReturning500_when_buildContext_should_throwException() {
        // Arrange
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(req -> Mono.just(
                        ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR).build()));

        ServiceDefinition svcDef = buildServiceDef("inventory", "http://localhost:8080/openapi.json");
        GatewayProperties props = buildGatewayProperties(svcDef);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        RestDownstreamRouteLoader loader =
                new RestDownstreamRouteLoader(props, registry, Optional.empty(), Optional.of(builder));

        // Act & Assert
        assertThatThrownBy(() -> loader.buildContext(svcDef)).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("GIVEN stub returning YAML with Content-Type WHEN buildContext SHOULD parse and store media type")
    void given_yamlWithContentType_when_buildContext_should_storeMediaType() throws Exception {
        // Arrange
        byte[] fixture = loadFixture("inventory.yaml");
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(req -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, "application/yaml")
                        .body(new String(fixture))
                        .build()));

        ServiceDefinition svcDef = buildServiceDef("inventory", "http://localhost:8080/openapi.yaml");
        GatewayProperties props = buildGatewayProperties(svcDef);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        RestDownstreamRouteLoader loader =
                new RestDownstreamRouteLoader(props, registry, Optional.empty(), Optional.of(builder));

        // Act
        ServiceEndpointContext ctx = loader.buildContext(svcDef);

        // Assert
        assertThat(ctx.getDownstreamSpecMediaType()).isEqualTo("application/yaml");
    }

    @Test
    @DisplayName("GIVEN rest-enabled: false WHEN buildContext SHOULD build routes without HTTP binding")
    void given_restDisabled_when_buildContext_should_notSetHttpBinding() throws Exception {
        // Arrange
        byte[] fixture = loadFixture("inventory.json");
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(req -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, "application/json")
                        .body(new String(fixture))
                        .build()));

        ServiceDefinition svcDef = buildServiceDef("inventory", "http://localhost:8080/openapi.json");
        svcDef.setRestEnabled(false);
        GatewayProperties props = buildGatewayProperties(svcDef);
        RestConfig rest = new RestConfig();
        rest.setEnabled(true);
        props.setRest(rest);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        RestDownstreamRouteLoader loader =
                new RestDownstreamRouteLoader(props, registry, Optional.empty(), Optional.of(builder));

        // Act
        ServiceEndpointContext ctx = loader.buildContext(svcDef);

        // Assert
        assertThat(ctx.getRoutes()).hasSize(4);
        assertThat(ctx.getRoutes()).allSatisfy(route -> {
            assertThat(route.hasRestBinding()).isFalse();
        });
    }

    @Test
    @DisplayName(
            "GIVEN load() fails WHEN called SHOULD not throw and register nothing; then retryFailedOnReady() should retry")
    void given_loadFailsButRetrySucceeds_when_retryFailedOnReady_should_registerContext() throws Exception {
        // Arrange
        byte[] fixture = loadFixture("inventory.json");
        AtomicBoolean shouldSucceed = new AtomicBoolean(false);
        WebClient.Builder builder = WebClient.builder().exchangeFunction(req -> {
            if (shouldSucceed.get()) {
                return Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, "application/json")
                        .body(new String(fixture))
                        .build());
            } else {
                return Mono.just(
                        ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR).build());
            }
        });

        ServiceDefinition svcDef = buildServiceDef("inventory", "http://localhost:8080/openapi.json");
        GatewayProperties props = buildGatewayProperties(svcDef);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        RestDownstreamRouteLoader loader =
                new RestDownstreamRouteLoader(props, registry, Optional.empty(), Optional.of(builder));

        // Act - first load fails silently
        loader.load();
        assertThat(registry.allContexts()).isEmpty();

        // Now enable success and retry
        shouldSucceed.set(true);
        loader.retryFailedOnReady();

        // Assert
        assertThat(registry.allContexts()).hasSize(1);
        assertThat(registry.contextByServiceName("inventory").get().getRoutes()).hasSize(4);
    }

    @Test
    @DisplayName("GIVEN a deferred load that succeeds on retry WHEN retryFailedOnReady runs "
            + "SHOULD notify every schema reload listener once, and not on a retry that still fails")
    void given_deferredLoadSucceedsOnRetry_when_retryFailedOnReady_should_notifyReloadListeners() throws Exception {
        // Arrange
        byte[] fixture = loadFixture("inventory.json");
        AtomicBoolean shouldSucceed = new AtomicBoolean(false);
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(req -> shouldSucceed.get()
                        ? Mono.just(ClientResponse.create(HttpStatus.OK)
                                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                                .body(new String(fixture))
                                .build())
                        : Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR)
                                .build()));
        java.util.concurrent.atomic.AtomicInteger reloads = new java.util.concurrent.atomic.AtomicInteger();
        dev.appiaworks.shiguchi.routing.SchemaReloadListener failing = () -> {
            throw new IllegalStateException("boom");
        };
        RestDownstreamRouteLoader loader = new RestDownstreamRouteLoader(
                buildGatewayProperties(buildServiceDef("inventory", "http://localhost:8080/openapi.json")),
                new GrpcRouteRegistry(),
                Optional.empty(),
                Optional.of(builder),
                () -> java.util.List.of(failing, reloads::incrementAndGet));

        // Act: startup fails, first retry still fails, second retry succeeds
        loader.load();
        loader.retryFailedOnReady();
        int afterFailedRetry = reloads.get();
        // A failed retry clears the pending list, so re-run load() to re-defer it, as a
        // scheduler-driven refresh would. Then let the fetch succeed.
        loader.load();
        shouldSucceed.set(true);
        loader.retryFailedOnReady();

        // Assert: no notification while nothing was registered; exactly one afterwards, and a
        // listener that throws does not stop the next one from being called
        assertThat(afterFailedRetry).isZero();
        assertThat(reloads.get()).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "GIVEN GraphQL enabled and inventory-schemas fixture WHEN buildContext SHOULD register synthetic service and GraphQL bindings")
    void given_graphqlEnabled_when_buildContext_should_registerGraphQlBindings() throws Exception {
        // Arrange
        byte[] fixture = loadFixture("inventory-schemas.json");
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(req -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, "application/json")
                        .body(new String(fixture))
                        .build()));

        ServiceDefinition svcDef = buildServiceDef("inventory", "http://localhost:8080/openapi.json");
        GatewayProperties props = buildGatewayProperties(svcDef);
        props.getGraphql().setEnabled(true);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        RestDownstreamRouteLoader loader =
                new RestDownstreamRouteLoader(props, registry, Optional.empty(), Optional.of(builder));

        // Act
        ServiceEndpointContext ctx = loader.buildContext(svcDef);

        // Assert
        String expectedFqn = OpenApiToDescriptor.serviceFqnFor("inventory");
        assertThat(ctx.getRoutes()).allSatisfy(route -> {
            assertThat(route.getFullServiceName()).isEqualTo(expectedFqn);
            assertThat(route.hasGraphQlBinding()).isTrue();
        });
        assertThat(ctx.getServiceByFqn()).containsKey(expectedFqn);
    }

    @Test
    @DisplayName("GIVEN GraphQL disabled WHEN buildContext SHOULD register routes without GraphQL bindings")
    void given_graphqlDisabled_when_buildContext_should_notHaveGraphQlBindings() throws Exception {
        // Arrange
        byte[] fixture = loadFixture("inventory-schemas.json");
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(req -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, "application/json")
                        .body(new String(fixture))
                        .build()));

        ServiceDefinition svcDef = buildServiceDef("inventory", "http://localhost:8080/openapi.json");
        GatewayProperties props = buildGatewayProperties(svcDef);
        props.getGraphql().setEnabled(false);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        RestDownstreamRouteLoader loader =
                new RestDownstreamRouteLoader(props, registry, Optional.empty(), Optional.of(builder));

        // Act
        ServiceEndpointContext ctx = loader.buildContext(svcDef);

        // Assert
        assertThat(ctx.getRoutes()).allSatisfy(route -> {
            assertThat(route.hasGraphQlBinding()).isFalse();
        });
    }

    @Test
    @DisplayName("GIVEN graphQlFieldName called with list_items SHOULD return listItems")
    void given_snakeCaseMethodName_when_graphQlFieldNameCalled_should_convertToCamelCase() {
        // Act
        String result = RestDownstreamRouteLoader.graphQlFieldName("list_items");

        // Assert
        assertThat(result).isEqualTo("listItems");
    }

    @Test
    @DisplayName("GIVEN graphQlFieldName called with GetItem SHOULD return getItem")
    void given_pascalCaseMethodName_when_graphQlFieldNameCalled_should_convertToLowerCamelCase() {
        // Act
        String result = RestDownstreamRouteLoader.graphQlFieldName("GetItem");

        // Assert
        assertThat(result).isEqualTo("getItem");
    }
}
