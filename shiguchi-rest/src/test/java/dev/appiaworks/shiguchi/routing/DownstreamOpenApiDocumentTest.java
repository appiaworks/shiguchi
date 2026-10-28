package dev.appiaworks.shiguchi.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD-style tests for DownstreamOpenApiDocument.
 *
 * Tests verify that:
 * - JSON and YAML documents are parsed to the same operations
 * - HTTP verbs are upper-cased
 * - Operations without operationId are synthesised with format "VERB /path"
 * - Auth scopes can be specified as lists or space-separated strings
 * - Media type detection works for headers and content sniffing
 * - Malformed documents throw helpful errors
 * - Missing paths object is detected
 */
@DisplayName("DownstreamOpenApiDocument parser tests")
class DownstreamOpenApiDocumentTest {

    private byte[] loadFixture(String name) throws Exception {
        InputStream is = getClass().getResourceAsStream("/openapi/" + name);
        return is.readAllBytes();
    }

    @Test
    @DisplayName("GIVEN JSON fixture WHEN parsed SHOULD yield 4 operations with upper-case verbs")
    void given_jsonFixture_when_parsed_should_yield4Operations() throws Exception {
        // Arrange
        byte[] json = loadFixture("inventory.json");

        // Act
        DownstreamOpenApiDocument doc = DownstreamOpenApiDocument.parse(json, DownstreamOpenApiDocument.MEDIA_JSON);

        // Assert
        assertThat(doc.operations()).hasSize(4);
        List<DownstreamOpenApiDocument.Operation> ops = doc.operations();
        assertThat(ops)
                .anySatisfy(op -> assertThat(op.httpMethod()).isEqualTo("GET").isIn("GET", "POST", "DELETE"));
        assertThat(ops.stream().map(DownstreamOpenApiDocument.Operation::httpMethod))
                .allMatch(m -> m.equals(m.toUpperCase()));
    }

    @Test
    @DisplayName("GIVEN YAML fixture WHEN parsed SHOULD yield same operations as JSON")
    void given_yamlFixture_when_parsed_should_yieldSameAsJson() throws Exception {
        // Arrange
        byte[] json = loadFixture("inventory.json");
        byte[] yaml = loadFixture("inventory.yaml");

        // Act
        DownstreamOpenApiDocument jsonDoc = DownstreamOpenApiDocument.parse(json, DownstreamOpenApiDocument.MEDIA_JSON);
        DownstreamOpenApiDocument yamlDoc = DownstreamOpenApiDocument.parse(yaml, DownstreamOpenApiDocument.MEDIA_YAML);

        // Assert
        assertThat(yamlDoc.operations()).hasSameSizeAs(jsonDoc.operations());
        for (int i = 0; i < jsonDoc.operations().size(); i++) {
            DownstreamOpenApiDocument.Operation jsonOp = jsonDoc.operations().get(i);
            DownstreamOpenApiDocument.Operation yamlOp = yamlDoc.operations().get(i);
            assertThat(yamlOp.httpMethod()).isEqualTo(jsonOp.httpMethod());
            assertThat(yamlOp.path()).isEqualTo(jsonOp.path());
            assertThat(yamlOp.operationId()).isEqualTo(jsonOp.operationId());
            assertThat(yamlOp.hasOperationId()).isEqualTo(jsonOp.hasOperationId());
            assertThat(yamlOp.gatewayAuthScopes()).isEqualTo(jsonOp.gatewayAuthScopes());
        }
    }

    @Test
    @DisplayName("GIVEN operation without operationId WHEN parsed SHOULD synthesise id as DELETE /items/{sku}")
    void given_operationWithoutId_when_parsed_should_synthesiseId() throws Exception {
        // Arrange
        byte[] json = loadFixture("inventory.json");

        // Act
        DownstreamOpenApiDocument doc = DownstreamOpenApiDocument.parse(json, DownstreamOpenApiDocument.MEDIA_JSON);

        // Assert
        DownstreamOpenApiDocument.Operation deleteOp = doc.operations().stream()
                .filter(op -> op.httpMethod().equals("DELETE"))
                .findFirst()
                .orElseThrow();
        assertThat(deleteOp.operationId()).isEqualTo("DELETE /items/{sku}");
        assertThat(deleteOp.hasOperationId()).isFalse();
    }

    @Test
    @DisplayName("GIVEN x-gateway-auth-scopes as list WHEN parsed SHOULD expose scopes; as string SHOULD split to list")
    void given_authScopesInDifferentFormats_when_parsed_should_parseCorrectly() throws Exception {
        // Arrange
        byte[] json = loadFixture("inventory.json");

        // Act
        DownstreamOpenApiDocument doc = DownstreamOpenApiDocument.parse(json, DownstreamOpenApiDocument.MEDIA_JSON);

        // Assert
        // getItem has scopes as a list
        DownstreamOpenApiDocument.Operation getItemOp = doc.operations().stream()
                .filter(op -> op.operationId().equals("getItem"))
                .findFirst()
                .orElseThrow();
        assertThat(getItemOp.gatewayAuthScopes()).containsExactly("inventory.read");

        // createItem has scopes as a space-separated string
        DownstreamOpenApiDocument.Operation createOp = doc.operations().stream()
                .filter(op -> op.operationId().equals("createItem"))
                .findFirst()
                .orElseThrow();
        assertThat(createOp.gatewayAuthScopes()).containsExactly("inventory.write", "inventory.admin");

        // listItems has no scopes
        DownstreamOpenApiDocument.Operation listOp = doc.operations().stream()
                .filter(op -> op.operationId().equals("listItems"))
                .findFirst()
                .orElseThrow();
        assertThat(listOp.gatewayAuthScopes()).isEmpty();
    }

    @Test
    @DisplayName("GIVEN malformed JSON WHEN parsed SHOULD throw IllegalArgumentException mentioning media type")
    void given_malformedJson_when_parsed_should_throwIllegalArgument() throws Exception {
        // Arrange
        byte[] malformed = loadFixture("malformed.json");

        // Act & Assert
        assertThatThrownBy(() -> DownstreamOpenApiDocument.parse(malformed, DownstreamOpenApiDocument.MEDIA_JSON))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("application/json");
    }

    @Test
    @DisplayName("GIVEN document without paths WHEN parsed SHOULD throw IllegalArgumentException mentioning paths")
    void given_noPathsDocument_when_parsed_should_throwIllegalArgument() throws Exception {
        // Arrange
        byte[] noPaths = loadFixture("no-paths.json");

        // Act & Assert
        assertThatThrownBy(() -> DownstreamOpenApiDocument.parse(noPaths, DownstreamOpenApiDocument.MEDIA_JSON))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("paths");
    }

    @Test
    @DisplayName("GIVEN various Content-Type headers WHEN detectMediaType called SHOULD return correct media type")
    void given_contentTypeHeaders_when_detectMediaType_should_returnCorrectType() throws Exception {
        // Arrange
        byte[] json = loadFixture("inventory.json");
        byte[] yaml = loadFixture("inventory.yaml");
        String jsonWithCharset = "application/json;charset=utf-8";
        String yamlHeader = "text/vnd.yaml";

        // Act & Assert
        assertThat(DownstreamOpenApiDocument.detectMediaType(json, jsonWithCharset))
                .isEqualTo(DownstreamOpenApiDocument.MEDIA_JSON);
        assertThat(DownstreamOpenApiDocument.detectMediaType(yaml, yamlHeader))
                .isEqualTo(DownstreamOpenApiDocument.MEDIA_YAML);

        // No header, body starts with { → JSON
        assertThat(DownstreamOpenApiDocument.detectMediaType(json, null))
                .isEqualTo(DownstreamOpenApiDocument.MEDIA_JSON);

        // No header, body starts with 'o' (openapi:) → YAML
        assertThat(DownstreamOpenApiDocument.detectMediaType(yaml, null))
                .isEqualTo(DownstreamOpenApiDocument.MEDIA_YAML);

        // Body with leading whitespace
        String withWhitespace = "  \n{\n  \"openapi\": \"3.0.3\"\n}";
        assertThat(DownstreamOpenApiDocument.detectMediaType(withWhitespace.getBytes(StandardCharsets.UTF_8), null))
                .isEqualTo(DownstreamOpenApiDocument.MEDIA_JSON);
    }

    @Test
    @DisplayName("GIVEN inventory-schemas.json fixture WHEN parsed SHOULD expose 4 component schemas")
    void given_inventorySchemasFixture_when_parsed_should_expose4Schemas() throws Exception {
        // Arrange
        byte[] json = loadFixture("inventory-schemas.json");

        // Act
        DownstreamOpenApiDocument doc = DownstreamOpenApiDocument.parse(json, DownstreamOpenApiDocument.MEDIA_JSON);

        // Assert
        assertThat(doc.componentSchemas()).containsKeys("Item", "Category", "Page", "Error");
        assertThat(doc.componentSchemas()).hasSize(4);
    }

    @Test
    @DisplayName("GIVEN getItem operation WHEN parsed SHOULD have one path parameter sku resolved from $ref")
    void given_getItemOperation_when_parsed_should_haveSkuPathParam() throws Exception {
        // Arrange
        byte[] json = loadFixture("inventory-schemas.json");

        // Act
        DownstreamOpenApiDocument doc = DownstreamOpenApiDocument.parse(json, DownstreamOpenApiDocument.MEDIA_JSON);

        // Assert
        DownstreamOpenApiDocument.Operation getItem = doc.operations().stream()
                .filter(op -> op.operationId().equals("getItem"))
                .findFirst()
                .orElseThrow();
        assertThat(getItem.parameters()).hasSize(1);
        assertThat(getItem.parameters().get(0).name()).isEqualTo("sku");
        assertThat(getItem.parameters().get(0).in()).isEqualTo("path");
        assertThat(getItem.parameters().get(0).required()).isTrue();
    }

    @Test
    @DisplayName("GIVEN listItems operation WHEN parsed SHOULD have 3 query parameters in order")
    void given_listItemsOperation_when_parsed_should_have3QueryParams() throws Exception {
        // Arrange
        byte[] json = loadFixture("inventory-schemas.json");

        // Act
        DownstreamOpenApiDocument doc = DownstreamOpenApiDocument.parse(json, DownstreamOpenApiDocument.MEDIA_JSON);

        // Assert
        DownstreamOpenApiDocument.Operation listItems = doc.operations().stream()
                .filter(op -> op.operationId().equals("listItems"))
                .findFirst()
                .orElseThrow();
        assertThat(listItems.parameters())
                .extracting(DownstreamOpenApiDocument.Parameter::name)
                .containsExactly("limit", "status", "tag");
        assertThat(listItems.parameters()).allSatisfy(p -> assertThat(p.in()).isEqualTo("query"));
    }

    @Test
    @DisplayName("GIVEN createItem operation WHEN parsed SHOULD have requestBodySchema with Item $ref")
    void given_createItemOperation_when_parsed_should_haveItemRequestBody() throws Exception {
        // Arrange
        byte[] json = loadFixture("inventory-schemas.json");

        // Act
        DownstreamOpenApiDocument doc = DownstreamOpenApiDocument.parse(json, DownstreamOpenApiDocument.MEDIA_JSON);

        // Assert
        DownstreamOpenApiDocument.Operation createItem = doc.operations().stream()
                .filter(op -> op.operationId().equals("createItem"))
                .findFirst()
                .orElseThrow();
        assertThat(createItem.requestBodySchema()).isNotNull();
        assertThat(createItem.requestBodySchema().get("$ref")).isEqualTo("#/components/schemas/Item");
    }

    @Test
    @DisplayName("GIVEN getItem operation WHEN parsed SHOULD have responseSchema with Item $ref")
    void given_getItemOperation_when_parsed_should_haveItemResponse() throws Exception {
        // Arrange
        byte[] json = loadFixture("inventory-schemas.json");

        // Act
        DownstreamOpenApiDocument doc = DownstreamOpenApiDocument.parse(json, DownstreamOpenApiDocument.MEDIA_JSON);

        // Assert
        DownstreamOpenApiDocument.Operation getItem = doc.operations().stream()
                .filter(op -> op.operationId().equals("getItem"))
                .findFirst()
                .orElseThrow();
        assertThat(getItem.responseSchema()).isNotNull();
        assertThat(getItem.responseSchema().get("$ref")).isEqualTo("#/components/schemas/Item");
    }

    @Test
    @DisplayName("GIVEN deleteItem operation WHEN parsed SHOULD have null responseSchema")
    void given_deleteItemOperation_when_parsed_should_haveNullResponse() throws Exception {
        // Arrange
        byte[] json = loadFixture("inventory-schemas.json");

        // Act
        DownstreamOpenApiDocument doc = DownstreamOpenApiDocument.parse(json, DownstreamOpenApiDocument.MEDIA_JSON);

        // Assert
        DownstreamOpenApiDocument.Operation deleteItem = doc.operations().stream()
                .filter(op -> op.operationId().equals("deleteItem"))
                .findFirst()
                .orElseThrow();
        assertThat(deleteItem.responseSchema()).isNull();
    }

    @Test
    @DisplayName("GIVEN ping operation WHEN parsed SHOULD have string responseSchema")
    void given_pingOperation_when_parsed_should_haveStringResponse() throws Exception {
        // Arrange
        byte[] json = loadFixture("inventory-schemas.json");

        // Act
        DownstreamOpenApiDocument doc = DownstreamOpenApiDocument.parse(json, DownstreamOpenApiDocument.MEDIA_JSON);

        // Assert
        DownstreamOpenApiDocument.Operation ping = doc.operations().stream()
                .filter(op -> op.operationId().equals("ping"))
                .findFirst()
                .orElseThrow();
        assertThat(ping.responseSchema()).isNotNull();
        assertThat(ping.responseSchema().get("type")).isEqualTo("string");
    }

    @Test
    @DisplayName("GIVEN operation with summary WHEN parsed SHOULD expose summary field")
    void given_operationWithSummary_when_parsed_should_exposeSummary() throws Exception {
        // Arrange
        byte[] json = loadFixture("inventory-schemas.json");

        // Act
        DownstreamOpenApiDocument doc = DownstreamOpenApiDocument.parse(json, DownstreamOpenApiDocument.MEDIA_JSON);

        // Assert
        DownstreamOpenApiDocument.Operation getItem = doc.operations().stream()
                .filter(op -> op.operationId().equals("getItem"))
                .findFirst()
                .orElseThrow();
        assertThat(getItem.summary()).isEqualTo("Get an item by SKU");
    }

    @Test
    @DisplayName("GIVEN operation with mixed case Avro media type WHEN parsed SHOULD normalize to lower case")
    void given_mixedCaseAvroMediaType_when_parsed_should_normalizeLowerCase() throws Exception {
        // Arrange
        byte[] json = loadFixture("inventory-avro.json");

        // Act
        DownstreamOpenApiDocument doc = DownstreamOpenApiDocument.parse(json, DownstreamOpenApiDocument.MEDIA_JSON);

        // Assert
        DownstreamOpenApiDocument.Operation getItem = doc.operations().stream()
                .filter(op -> op.operationId().equals("getItem"))
                .findFirst()
                .orElseThrow();
        assertThat(getItem.responseMediaTypes()).contains("avro/binary");
        assertThat(getItem.responseMediaTypes()).doesNotContain("Avro/Binary");
    }

    @Test
    @DisplayName("GIVEN operation with multiple response media types WHEN parsed SHOULD include all in order")
    void given_multipleResponseMediaTypes_when_parsed_should_includeAllInOrder() throws Exception {
        // Arrange
        byte[] json = loadFixture("inventory-avro.json");

        // Act
        DownstreamOpenApiDocument doc = DownstreamOpenApiDocument.parse(json, DownstreamOpenApiDocument.MEDIA_JSON);

        // Assert
        DownstreamOpenApiDocument.Operation getItem = doc.operations().stream()
                .filter(op -> op.operationId().equals("getItem"))
                .findFirst()
                .orElseThrow();
        assertThat(getItem.responseMediaTypes()).contains("avro/binary", "application/json");
        assertThat(getItem.responseMediaTypes().get(0)).isEqualTo("avro/binary");
    }

    @Test
    @DisplayName("GIVEN operation with request body media types WHEN parsed SHOULD expose requestMediaTypes")
    void given_operationWithRequestMediaTypes_when_parsed_should_exposeRequestMediaTypes() throws Exception {
        // Arrange
        byte[] json = loadFixture("inventory-avro.json");

        // Act
        DownstreamOpenApiDocument doc = DownstreamOpenApiDocument.parse(json, DownstreamOpenApiDocument.MEDIA_JSON);

        // Assert
        DownstreamOpenApiDocument.Operation createItem = doc.operations().stream()
                .filter(op -> op.operationId().equals("createItem"))
                .findFirst()
                .orElseThrow();
        assertThat(createItem.requestMediaTypes()).contains("avro/binary", "application/json");
    }

    @Test
    @DisplayName("GIVEN operation with schema ref WHEN parsed SHOULD extract schema name")
    void given_operationWithSchemaRef_when_parsed_should_extractSchemaName() throws Exception {
        // Arrange
        byte[] json = loadFixture("inventory-avro.json");

        // Act
        DownstreamOpenApiDocument doc = DownstreamOpenApiDocument.parse(json, DownstreamOpenApiDocument.MEDIA_JSON);

        // Assert
        DownstreamOpenApiDocument.Operation getItem = doc.operations().stream()
                .filter(op -> op.operationId().equals("getItem"))
                .findFirst()
                .orElseThrow();
        assertThat(getItem.responseSchemaName()).isEqualTo("Item");
    }

    @Test
    @DisplayName("GIVEN operation with array response WHEN parsed SHOULD extract items schema name")
    void given_operationWithArrayResponse_when_parsed_should_extractItemsSchemaName() throws Exception {
        // Arrange
        byte[] json = loadFixture("inventory-avro.json");

        // Act
        DownstreamOpenApiDocument doc = DownstreamOpenApiDocument.parse(json, DownstreamOpenApiDocument.MEDIA_JSON);

        // Assert
        DownstreamOpenApiDocument.Operation listItems = doc.operations().stream()
                .filter(op -> op.operationId().equals("listItems"))
                .findFirst()
                .orElseThrow();
        assertThat(listItems.responseSchemaName()).isEqualTo("Item");
    }

    @Test
    @DisplayName("GIVEN operation with inline schema WHEN parsed SHOULD have null schema name")
    void given_operationWithInlineSchema_when_parsed_should_haveNullSchemaName() throws Exception {
        // Arrange
        byte[] json = loadFixture("inventory-avro.json");

        // Act
        DownstreamOpenApiDocument doc = DownstreamOpenApiDocument.parse(json, DownstreamOpenApiDocument.MEDIA_JSON);

        // Assert
        DownstreamOpenApiDocument.Operation getStatus = doc.operations().stream()
                .filter(op -> op.operationId().equals("getStatus"))
                .findFirst()
                .orElseThrow();
        assertThat(getStatus.responseSchemaName()).isNull();
    }

    @Test
    @DisplayName("GIVEN operation with request body schema ref WHEN parsed SHOULD extract request schema name")
    void given_operationWithRequestBodySchemaRef_when_parsed_should_extractRequestSchemaName() throws Exception {
        // Arrange
        byte[] json = loadFixture("inventory-avro.json");

        // Act
        DownstreamOpenApiDocument doc = DownstreamOpenApiDocument.parse(json, DownstreamOpenApiDocument.MEDIA_JSON);

        // Assert
        DownstreamOpenApiDocument.Operation createItem = doc.operations().stream()
                .filter(op -> op.operationId().equals("createItem"))
                .findFirst()
                .orElseThrow();
        assertThat(createItem.requestSchemaName()).isEqualTo("Item");
    }
}
