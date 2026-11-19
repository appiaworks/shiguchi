package dev.appiaworks.shiguchi.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OpenApiRelationshipExtensions")
class OpenApiRelationshipExtensionsTest {

    private static OpenApiRelationshipExtensions.Result read(String prefix, String schemas, String paths) {
        String json =
                "{\"openapi\":\"3.0.3\",\"paths\":{" + paths + "},\"components\":{\"schemas\":{" + schemas + "}}}";
        return OpenApiRelationshipExtensions.read(
                DownstreamOpenApiDocument.parse(json.getBytes(StandardCharsets.UTF_8), "application/json"), prefix);
    }

    private static final String CUSTOMER =
            "\"Customer\":{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}";

    @Test
    @DisplayName("GIVEN x-shiguchi-references WHEN read SHOULD return a many-to-one with the prefixed type names")
    void GIVEN_references_WHEN_read_SHOULD_returnPrefixedManyToOne() {
        var result = read(
                "Shop",
                CUSTOMER + ",\"Order\":{\"properties\":{\"customerId\":{\"x-shiguchi-references\":\"Customer\","
                        + "\"x-shiguchi-field\":\"buyer\"}}}",
                "");

        assertThat(result.problems()).isEmpty();
        assertThat(result.references()).singleElement().satisfies(r -> {
            assertThat(r.parentType()).isEqualTo("ShopOrder");
            assertThat(r.property()).isEqualTo("customerId");
            assertThat(r.targetType()).isEqualTo("ShopCustomer");
            assertThat(r.field()).isEqualTo("buyer");
            assertThat(r.location()).isEqualTo("OpenAPI x-shiguchi-references on Order.customerId");
        });
    }

    @Test
    @DisplayName("GIVEN x-shiguchi-has-many WHEN read SHOULD return a one-to-many with group-by and field")
    void GIVEN_hasMany_WHEN_read_SHOULD_returnOneToMany() {
        var result = read(
                "",
                "\"Customer\":{\"properties\":{\"id\":{\"x-shiguchi-has-many\":{\"type\":\"Order\","
                        + "\"group-by\":\"customerId\",\"field\":\"orders\"}}}},"
                        + "\"Order\":{\"properties\":{\"customerId\":{\"type\":\"string\"}}}",
                "");

        assertThat(result.problems()).isEmpty();
        assertThat(result.hasMany()).singleElement().satisfies(m -> {
            assertThat(m.parentType()).isEqualTo("Customer");
            assertThat(m.targetType()).isEqualTo("Order");
            assertThat(m.groupBy()).isEqualTo("customerId");
            assertThat(m.field()).isEqualTo("orders");
        });
    }

    @Test
    @DisplayName("GIVEN x-shiguchi-batch-key on a schema WHEN read SHOULD return its key property")
    void GIVEN_batchKey_WHEN_read_SHOULD_returnKey() {
        var result = read(
                "P", "\"Item\":{\"x-shiguchi-batch-key\":\"sku\",\"properties\":{\"sku\":{\"type\":\"string\"}}}", "");

        assertThat(result.keys()).singleElement().satisfies(k -> {
            assertThat(k.type()).isEqualTo("PItem");
            assertThat(k.property()).isEqualTo("sku");
        });
    }

    @Test
    @DisplayName("GIVEN x-shiguchi-loader on an operation WHEN read SHOULD map it to a rest-batch-loaders entry")
    void GIVEN_loader_WHEN_read_SHOULD_mapToLoaderConfig() {
        var result = read(
                "Shop",
                "\"Item\":{\"x-shiguchi-batch-key\":\"sku\",\"properties\":{\"sku\":{\"type\":\"string\"}}}",
                "\"/items\":{\"get\":{\"operationId\":\"items\",\"x-shiguchi-loader\":{\"type\":\"Item\","
                        + "\"ids-param\":\"skus\",\"result-field\":\"data\",\"max-batch-size\":50}}}");

        assertThat(result.problems()).isEmpty();
        assertThat(result.loaders()).singleElement().satisfies(l -> {
            assertThat(l.config().getType()).isEqualTo("ShopItem");
            assertThat(l.config().getEndpoint()).isEqualTo("/items");
            assertThat(l.config().getIdsParam()).isEqualTo("skus");
            assertThat(l.config().getKeyField()).isEqualTo("sku");
            assertThat(l.config().getResultField()).isEqualTo("data");
            assertThat(l.config().getMaxBatchSize()).isEqualTo(50);
            assertThat(l.config().getGroupBy()).isNull();
        });
    }

    @Test
    @DisplayName("GIVEN references to an unknown schema WHEN read SHOULD report it with its location")
    void GIVEN_unknownSchema_WHEN_read_SHOULD_reportProblem() {
        var result = read("", "\"Order\":{\"properties\":{\"customerId\":{\"x-shiguchi-references\":\"Nobody\"}}}", "");

        assertThat(result.references()).isEmpty();
        assertThat(result.problems())
                .singleElement()
                .asString()
                .startsWith("OpenAPI x-shiguchi-references on Order.customerId")
                .contains("no schema 'Nobody'");
    }

    @Test
    @DisplayName("GIVEN a has-many group-by that is not a property WHEN read SHOULD report it")
    void GIVEN_unknownGroupByProperty_WHEN_read_SHOULD_reportProblem() {
        var result = read(
                "",
                CUSTOMER.replace(
                                "\"id\":{\"type\":\"string\"}",
                                "\"id\":{\"x-shiguchi-has-many\":{\"type\":\"Order\",\"group-by\":\"nope\"}}")
                        + ",\"Order\":{\"properties\":{\"customerId\":{\"type\":\"string\"}}}",
                "");

        assertThat(result.hasMany()).isEmpty();
        assertThat(result.problems()).singleElement().asString().contains("Order has no property 'nope'");
    }

    @Test
    @DisplayName("GIVEN a batch key that is not a property WHEN read SHOULD report it")
    void GIVEN_unknownBatchKey_WHEN_read_SHOULD_reportProblem() {
        var result = read("", "\"Item\":{\"x-shiguchi-batch-key\":\"isbn\",\"properties\":{\"sku\":{}}}", "");

        assertThat(result.keys()).isEmpty();
        assertThat(result.problems()).singleElement().asString().contains("x-shiguchi-batch-key on Item");
    }

    @Test
    @DisplayName("GIVEN a loader on a POST operation WHEN read SHOULD report that it must be a GET")
    void GIVEN_loaderOnPost_WHEN_read_SHOULD_reportProblem() {
        var result = read(
                "",
                CUSTOMER,
                "\"/c\":{\"post\":{\"operationId\":\"c\",\"x-shiguchi-loader\":{\"type\":\"Customer\"}}}");

        assertThat(result.loaders()).isEmpty();
        assertThat(result.problems()).singleElement().asString().contains("x-shiguchi-loader on POST /c");
    }

    @Test
    @DisplayName("GIVEN two loaders for the same type WHEN read SHOULD keep the first and report the second")
    void GIVEN_duplicateLoader_WHEN_read_SHOULD_reportSecond() {
        var result = read(
                "",
                CUSTOMER,
                "\"/a\":{\"get\":{\"operationId\":\"a\",\"x-shiguchi-loader\":{\"type\":\"Customer\"}}},"
                        + "\"/b\":{\"get\":{\"operationId\":\"b\",\"x-shiguchi-loader\":{\"type\":\"Customer\"}}}");

        assertThat(result.loaders()).hasSize(1);
        assertThat(result.problems()).singleElement().asString().contains("second loader for Customer");
    }

    @Test
    @DisplayName("GIVEN a document without extensions WHEN read SHOULD be empty")
    void GIVEN_noExtensions_WHEN_read_SHOULD_beEmpty() {
        var result = read("", CUSTOMER, "");

        assertThat(result.isEmpty()).isTrue();
        assertThat(result.problems()).isEmpty();
    }
}
