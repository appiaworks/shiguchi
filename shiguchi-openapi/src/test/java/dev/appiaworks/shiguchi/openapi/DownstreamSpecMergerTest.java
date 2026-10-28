package dev.appiaworks.shiguchi.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import io.grpc.MethodDescriptor;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.Paths;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD-style unit tests for DownstreamSpecMerger.
 *
 * Tests the merging of REST_DOWNSTREAM service OpenAPI documents into the consolidated spec,
 * including path prefixing, component namespacing, ref rewriting, operationId/tag prefixing,
 * and security handling.
 */
@DisplayName("DownstreamSpecMerger")
class DownstreamSpecMergerTest {

    private static final String TEST_RESOURCES = "shiguchi-openapi/src/test/resources/openapi";

    private byte[] inventoryJsonBytes;
    private byte[] inventoryYamlBytes;
    private byte[] otherJsonBytes;
    private byte[] v31JsonBytes;

    @BeforeEach
    void setUp() throws IOException {
        inventoryJsonBytes = loadFixture("inventory.json");
        inventoryYamlBytes = loadFixture("inventory.yaml");
        otherJsonBytes = loadFixture("other.json");
        v31JsonBytes = loadFixture("v31.json");
    }

    @Test
    @DisplayName(
            "Given all four operations routed, when merged, should add paths /inv/items and /inv/items/{sku} with prefixed operationIds")
    void given_allOperationsRouted_when_merged_should_addPrefixedPaths() {
        // Arrange
        ServiceEndpointContext ctx = createInventoryContext("inventory", "/inv", false);
        addRoute(ctx, "GET", "/inv/items", "listItems");
        addRoute(ctx, "POST", "/inv/items", "createItem");
        addRoute(ctx, "GET", "/inv/items/{sku}", "getItem");
        addRoute(ctx, "DELETE", "/inv/items/{sku}", "deleteItem");

        Paths paths = new Paths();
        Components components = new Components();

        // Act
        DownstreamSpecMerger.merge(ctx, paths, components);

        // Assert
        assertThat(paths).containsKeys("/inv/items", "/inv/items/{sku}");
        assertThat(paths.get("/inv/items").readOperationsMap()).hasSize(2);
        assertThat(paths.get("/inv/items/{sku}").readOperationsMap()).hasSize(2);
    }

    @Test
    @DisplayName(
            "Given only listItems and getItem routed, when merged, should drop POST /items and DELETE from merged paths")
    void given_partialOperationsRouted_when_merged_should_excludeUnroutedOperations() {
        // Arrange
        ServiceEndpointContext ctx = createInventoryContext("inventory", "/inv", false);
        addRoute(ctx, "GET", "/inv/items", "listItems");
        addRoute(ctx, "GET", "/inv/items/{sku}", "getItem");

        Paths paths = new Paths();
        Components components = new Components();

        // Act
        DownstreamSpecMerger.merge(ctx, paths, components);

        // Assert
        assertThat(paths.get("/inv/items").readOperationsMap()).hasSize(1); // only GET
        assertThat(paths.get("/inv/items/{sku}").readOperationsMap()).hasSize(1); // only GET
    }

    @Test
    @DisplayName(
            "Given a routed path where none of its operations are routed, when merged, should not add that path at all")
    void given_routedPathWithNoRoutedOperations_when_merged_should_excludePath() {
        // Arrange
        ServiceEndpointContext ctx = createInventoryContext("inventory", "/inv", false);
        // Don't register any routes for /items — both GET and POST are excluded

        Paths paths = new Paths();
        Components components = new Components();

        // Act
        DownstreamSpecMerger.merge(ctx, paths, components);

        // Assert
        assertThat(paths).doesNotContainKey("/inv/items");
    }

    @Test
    @DisplayName(
            "Given components with schemas, parameters, and responses, when merged, should namespace all component names with service prefix")
    void given_componentsWithSchemas_when_merged_should_namespaceComponentNames() {
        // Arrange
        ServiceEndpointContext ctx = createInventoryContext("inventory", "/inv", false);
        addRoute(ctx, "GET", "/inv/items", "listItems");

        Paths paths = new Paths();
        Components components = new Components();

        // Act
        DownstreamSpecMerger.merge(ctx, paths, components);

        // Assert
        assertThat(components.getSchemas()).containsKeys("inventory.Item", "inventory.Tag", "inventory.Error");
        assertThat(components.getParameters()).containsKey("inventory.SkuParam");
        assertThat(components.getResponses()).containsKey("inventory.NotFound");
        assertThat(components.getSchemas()).doesNotContainKeys("Item", "Tag", "Error");
    }

    @Test
    @DisplayName(
            "Given $refs in schema properties, parameters and responses, when merged, should all point to namespaced components")
    void given_refsInSchemaAndParameters_when_merged_should_rewriteRefsToNamespacedComponents() {
        // Arrange
        ServiceEndpointContext ctx = createInventoryContext("inventory", "/inv", false);
        addRoute(ctx, "GET", "/inv/items", "listItems");
        addRoute(ctx, "GET", "/inv/items/{sku}", "getItem");

        Paths paths = new Paths();
        Components components = new Components();

        // Act
        DownstreamSpecMerger.merge(ctx, paths, components);

        // Assert
        // Item schema should have tags array with $ref to inventory.Tag
        var itemSchema = components.getSchemas().get("inventory.Item");
        var tagsProperty = (io.swagger.v3.oas.models.media.ArraySchema)
                itemSchema.getProperties().get("tags");
        assertThat(tagsProperty.getItems().get$ref()).isEqualTo("#/components/schemas/inventory.Tag");

        // Parameter should reference inventory.SkuParam
        var getItemOp =
                paths.get("/inv/items/{sku}").readOperationsMap().get(io.swagger.v3.oas.models.PathItem.HttpMethod.GET);
        assertThat(getItemOp.getParameters().get(0).get$ref()).isEqualTo("#/components/parameters/inventory.SkuParam");

        // 404 response should reference inventory.Error
        var notFoundResponse = getItemOp.getResponses().get("404");
        assertThat(notFoundResponse.get$ref()).isEqualTo("#/components/responses/inventory.NotFound");
    }

    @Test
    @DisplayName(
            "Given servers at root, path and operation level, when merged, should remove all servers from merged operations")
    void given_serversAtMultipleLevels_when_merged_should_removeAllServers() {
        // Arrange
        ServiceEndpointContext ctx = createInventoryContext("inventory", "/inv", false);
        addRoute(ctx, "GET", "/inv/items/{sku}", "getItem");

        Paths paths = new Paths();
        Components components = new Components();

        // Act
        DownstreamSpecMerger.merge(ctx, paths, components);

        // Assert
        var pathItem = paths.get("/inv/items/{sku}");
        assertThat(pathItem.getServers()).isNull();
        var getOp = pathItem.readOperationsMap().get(io.swagger.v3.oas.models.PathItem.HttpMethod.GET);
        assertThat(getOp.getServers()).isNull();
    }

    @Test
    @DisplayName("Given inheritSecurity=false, when merged, should remove securitySchemes and operation-level security")
    void given_inheritSecurityFalse_when_merged_should_removeSecurity() {
        // Arrange
        ServiceEndpointContext ctx = createInventoryContext("inventory", "/inv", false);
        addRoute(ctx, "GET", "/inv/items/{sku}", "getItem");

        Paths paths = new Paths();
        Components components = new Components();

        // Act
        DownstreamSpecMerger.merge(ctx, paths, components);

        // Assert
        assertThat(components.getSecuritySchemes()).isNull();
        var getOp =
                paths.get("/inv/items/{sku}").readOperationsMap().get(io.swagger.v3.oas.models.PathItem.HttpMethod.GET);
        assertThat(getOp.getSecurity()).isNull();
    }

    @Test
    @DisplayName(
            "Given inheritSecurity=true, when merged, should preserve securitySchemes and operation-level security with service prefix")
    void given_inheritSecurityTrue_when_merged_should_preserveSecurity() {
        // Arrange
        ServiceEndpointContext ctx = createInventoryContext("inventory", "/inv", true);
        addRoute(ctx, "GET", "/inv/items/{sku}", "getItem");

        Paths paths = new Paths();
        Components components = new Components();

        // Act
        DownstreamSpecMerger.merge(ctx, paths, components);

        // Assert
        assertThat(components.getSecuritySchemes()).containsKey("inventory.bearer");
        var getOp =
                paths.get("/inv/items/{sku}").readOperationsMap().get(io.swagger.v3.oas.models.PathItem.HttpMethod.GET);
        assertThat(getOp.getSecurity()).isNotNull();
    }

    @Test
    @DisplayName(
            "Given operationIds, when merged, should rename with service prefix; DELETE without operationId should keep null")
    void given_operationIds_when_merged_should_prefixOperationIdsExceptNull() {
        // Arrange
        ServiceEndpointContext ctx = createInventoryContext("inventory", "/inv", false);
        addRoute(ctx, "GET", "/inv/items", "listItems");
        addRoute(ctx, "DELETE", "/inv/items/{sku}", "deleteItem");

        Paths paths = new Paths();
        Components components = new Components();

        // Act
        DownstreamSpecMerger.merge(ctx, paths, components);

        // Assert
        var listOp = paths.get("/inv/items").readOperationsMap().get(io.swagger.v3.oas.models.PathItem.HttpMethod.GET);
        assertThat(listOp.getOperationId()).isEqualTo("inventory_listItems");

        var deleteOp = paths.get("/inv/items/{sku}")
                .readOperationsMap()
                .get(io.swagger.v3.oas.models.PathItem.HttpMethod.DELETE);
        assertThat(deleteOp.getOperationId()).isNull(); // DELETE has no operationId in fixture
    }

    @Test
    @DisplayName(
            "Given tags on operations, when merged, should prefix tags with service name in format 'service / Tag'")
    void given_tagsOnOperations_when_merged_should_prefixTags() {
        // Arrange
        ServiceEndpointContext ctx = createInventoryContext("inventory", "/inv", false);
        addRoute(ctx, "GET", "/inv/items", "listItems");
        addRoute(ctx, "POST", "/inv/items", "createItem");

        Paths paths = new Paths();
        Components components = new Components();

        // Act
        DownstreamSpecMerger.merge(ctx, paths, components);

        // Assert
        var listOp = paths.get("/inv/items").readOperationsMap().get(io.swagger.v3.oas.models.PathItem.HttpMethod.GET);
        assertThat(listOp.getTags()).containsExactly("inventory / Items");

        var createOp =
                paths.get("/inv/items").readOperationsMap().get(io.swagger.v3.oas.models.PathItem.HttpMethod.POST);
        assertThat(createOp.getTags()).containsExactly("inventory / Items", "inventory / Admin");
    }

    @Test
    @DisplayName(
            "Given x-audit vendor extension on listItems, when merged, should preserve extension in merged operation")
    void given_vendorExtensionOnOperation_when_merged_should_preserveExtension() {
        // Arrange
        ServiceEndpointContext ctx = createInventoryContext("inventory", "/inv", false);
        addRoute(ctx, "GET", "/inv/items", "listItems");

        Paths paths = new Paths();
        Components components = new Components();

        // Act
        DownstreamSpecMerger.merge(ctx, paths, components);

        // Assert
        var listOp = paths.get("/inv/items").readOperationsMap().get(io.swagger.v3.oas.models.PathItem.HttpMethod.GET);
        assertThat(listOp.getExtensions()).containsKey("x-audit");
        assertThat(listOp.getExtensions().get("x-audit")).isEqualTo(true);
    }

    @Test
    @DisplayName("Given YAML fixture with media type application/yaml, when merged, should produce same paths as JSON")
    void given_yamlFixture_when_merged_should_parseSamelyAsJson() {
        // Arrange
        ServiceEndpointContext ctxYaml =
                createContextWithSpec("inventory", "/inv", false, inventoryYamlBytes, "application/yaml");
        addRoute(ctxYaml, "GET", "/inv/items", "listItems");
        addRoute(ctxYaml, "GET", "/inv/items/{sku}", "getItem");

        Paths yamlPaths = new Paths();
        Components yamlComponents = new Components();

        // Act
        DownstreamSpecMerger.merge(ctxYaml, yamlPaths, yamlComponents);

        // Assert
        assertThat(yamlPaths).containsKeys("/inv/items", "/inv/items/{sku}");
        assertThat(yamlComponents.getSchemas()).containsKeys("inventory.Item", "inventory.Tag", "inventory.Error");
    }

    @Test
    @DisplayName(
            "Given two contexts (inventory and other), when both merged into same Paths/Components, should contain both service.Error variants")
    void given_twoServiceContexts_when_mergedIntoSame_should_namespaceEachService() {
        // Arrange
        ServiceEndpointContext ctx1 = createInventoryContext("inventory", "/inv", false);
        addRoute(ctx1, "GET", "/inv/items", "listItems");

        ServiceEndpointContext ctx2 =
                createContextWithSpec("other", "/other", false, otherJsonBytes, "application/json");
        addRoute(ctx2, "GET", "/other/resources", "listResources");

        Paths paths = new Paths();
        Components components = new Components();

        // Act
        DownstreamSpecMerger.merge(ctx1, paths, components);
        DownstreamSpecMerger.merge(ctx2, paths, components);

        // Assert
        assertThat(components.getSchemas()).containsKeys("inventory.Error", "other.Error");
        assertThat(components.getSchemas()).doesNotContainKeys("Error");
    }

    @Test
    @DisplayName(
            "Given a target that already contains /inv/items, when merged, should skip the duplicate and leave existing entry untouched")
    void given_targetContainsDuplicatePath_when_merged_should_skipAndKeepExisting() {
        // Arrange
        Paths paths = new Paths();
        var existingItem = new io.swagger.v3.oas.models.PathItem();
        paths.addPathItem("/inv/items", existingItem);

        ServiceEndpointContext ctx = createInventoryContext("inventory", "/inv", false);
        addRoute(ctx, "GET", "/inv/items", "listItems");

        Components components = new Components();

        // Act
        DownstreamSpecMerger.merge(ctx, paths, components);

        // Assert — existing entry should not be replaced
        assertThat(paths.get("/inv/items")).isSameAs(existingItem);
    }

    @Test
    @DisplayName("Given malformed spec bytes, when merged, should not throw and should leave targets empty")
    void given_malformedSpecBytes_when_merged_should_notThrowAndLeaveEmpty() {
        // Arrange
        ServiceEndpointContext ctx =
                createContextWithSpec("bad", "/bad", false, "not valid json".getBytes(), "application/json");
        Paths paths = new Paths();
        Components components = new Components();

        // Act & Assert — should not throw
        DownstreamSpecMerger.merge(ctx, paths, components);
        assertThat(paths).isEmpty();
        assertThat(components.getSchemas()).isNullOrEmpty();
    }

    @Test
    @DisplayName(
            "Given the 3.1 fixture with type union schema, when parsed, should bind without exception and expose /ping")
    void given_openapi31Fixture_when_parsed_should_bindAndExposePath() {
        // Arrange
        ServiceEndpointContext ctx = createContextWithSpec("v31", "", false, v31JsonBytes, "application/json");
        addRoute(ctx, "GET", "/ping", "ping");

        Paths paths = new Paths();
        Components components = new Components();

        // Act
        DownstreamSpecMerger.merge(ctx, paths, components);

        // Assert
        assertThat(paths).containsKey("/ping");
        var pingOp = paths.get("/ping").readOperationsMap().get(io.swagger.v3.oas.models.PathItem.HttpMethod.GET);
        assertThat(pingOp.getOperationId()).isEqualTo("v31_ping");
    }

    // -------------------------------------------------------------------------
    // Helper methods
    // -------------------------------------------------------------------------

    private ServiceEndpointContext createInventoryContext(String name, String pathPrefix, boolean inheritSecurity) {
        return createContextWithSpec(name, pathPrefix, inheritSecurity, inventoryJsonBytes, "application/json");
    }

    private ServiceEndpointContext createContextWithSpec(
            String name, String pathPrefix, boolean inheritSecurity, byte[] specBytes, String mediaType) {
        var config = new ServiceDefinition();
        config.setName(name);
        config.setPathPrefix(pathPrefix);
        config.setInheritSecurity(inheritSecurity);

        ServiceEndpointContext ctx = new ServiceEndpointContext(config, null);
        ctx.setRawDownstreamSpec(specBytes, mediaType);
        return ctx;
    }

    private void addRoute(ServiceEndpointContext ctx, String method, String path, String operationId) {
        RouteDefinition route = RouteDefinition.builder(
                        "service.Service", operationId, MethodDescriptor.MethodType.UNARY, "localhost:50051")
                .httpBinding(method, path)
                .build();
        ctx.addRoute(route);
    }

    private byte[] loadFixture(String filename) throws IOException {
        try (var in = getClass().getResourceAsStream("/openapi/" + filename)) {
            if (in == null) throw new IOException("missing test fixture /openapi/" + filename);
            return in.readAllBytes();
        }
    }
}
