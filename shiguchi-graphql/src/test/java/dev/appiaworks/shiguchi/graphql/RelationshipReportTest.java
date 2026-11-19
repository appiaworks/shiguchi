package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.graphql.GraphQlSchemaBuilder.Relationship;
import dev.appiaworks.shiguchi.graphql.RelationshipSource.BatchKey;
import dev.appiaworks.shiguchi.graphql.RelationshipSource.Source;
import dev.appiaworks.shiguchi.routing.DownstreamOpenApiDocument;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RelationshipReport")
class RelationshipReportTest {

    private static final String DOC = """
            {"openapi":"3.0.3","info":{"title":"S","version":"1"},"paths":{},
             "components":{"schemas":{
               "Customer":{"type":"object","properties":{"id":{"type":"string"}}},
               "Order":{"type":"object","properties":{"id":{"type":"string"},"customerId":{"type":"string"}}}}}}
            """;

    private static final Relationship EXTENSION =
            new Relationship("ShopOrder", "customer", "customerId", "ShopCustomer", "", Source.EXTENSION);
    private static final Relationship YAML =
            new Relationship("ShopCustomer", "orders", "id", "ShopOrder", "customerId", Source.YAML);
    private static final Relationship BEAN =
            new Relationship("Author", "books", "id", "Book", "author_id", Source.BEAN);
    private static final Relationship NO_LOADER =
            new Relationship("Author", "editor", "editorId", "Editor", "", Source.ANNOTATION);

    private RelationshipReport report(List<Relationship> relationships) {
        return new RelationshipReport(
                () -> relationships,
                () -> List.of(
                        new BatchKey("ShopCustomer", "id", "id", Source.EXTENSION),
                        new BatchKey("Author", "id", "id", Source.ANNOTATION)),
                () -> Map.of("ShopCustomer", "rest"),
                () -> Map.of("ShopOrder#by:customerId", "rest", "Book#by:authorId", "bean"),
                () -> List.of(new RelationshipReport.RestDocument(
                        "shop",
                        "Shop",
                        DownstreamOpenApiDocument.parse(DOC.getBytes(StandardCharsets.UTF_8), "application/json"))));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> body, String key) {
        return (List<Map<String, Object>>) body.get(key);
    }

    @Test
    @DisplayName(
            "GIVEN relationships from several sources WHEN reporting SHOULD group by parent type with source and loader")
    void GIVEN_mixedSources_WHEN_reporting_SHOULD_groupByParentWithSourceAndLoader() {
        Map<String, Object> body =
                report(List.of(EXTENSION, YAML, BEAN, NO_LOADER)).report(null);

        List<Map<String, Object>> types = list(body, "types");
        assertThat(types).extracting(t -> t.get("type")).containsExactly("Author", "ShopCustomer", "ShopOrder");
        Map<String, Object> orders = list(types.get(1), "relationships").get(0);
        assertThat(orders)
                .containsEntry("field", "orders")
                .containsEntry("many", true)
                .containsEntry("groupBy", "customerId")
                .containsEntry("source", "yaml")
                .containsEntry("loaderRegistered", true);
        assertThat((Map<String, Object>) orders.get("loader"))
                .containsEntry("name", "ShopOrder#by:customerId")
                .containsEntry("kind", "rest")
                .containsEntry("grouped", true);
        List<Map<String, Object>> author = list(types.get(0), "relationships");
        assertThat(author).extracting(r -> r.get("source")).containsExactly("bean", "annotation");
        assertThat(author.get(0).get("loader"))
                .isEqualTo(Map.of("name", "Book#by:authorId", "kind", "bean", "grouped", true));
        assertThat(author.get(1)).containsEntry("loaderRegistered", false).containsEntry("loader", null);
    }

    @Test
    @DisplayName("GIVEN a type filter WHEN reporting SHOULD keep only that type's relationships, keys and suggestions")
    void GIVEN_typeFilter_WHEN_reporting_SHOULD_keepOnlyThatType() {
        Map<String, Object> body = report(List.of(EXTENSION, YAML, BEAN)).report("Author");

        assertThat(list(body, "types")).extracting(t -> t.get("type")).containsExactly("Author");
        assertThat(list(body, "batchKeys")).extracting(k -> k.get("type")).containsExactly("Author");
        assertThat(list(body, "suggestions")).isEmpty();
    }

    @Test
    @DisplayName("GIVEN loaders of every kind WHEN reporting SHOULD list each with its kind and whether it is grouped")
    void GIVEN_loaders_WHEN_reporting_SHOULD_listKindAndGrouped() {
        Map<String, Object> body = report(List.of()).report("");

        assertThat(list(body, "loaders"))
                .extracting(l -> l.get("name") + ":" + l.get("kind") + ":" + l.get("grouped"))
                .contains("ShopCustomer:rest:false", "ShopOrder#by:customerId:rest:true", "Book#by:authorId:bean:true");
        assertThat(list(body, "batchKeys"))
                .extracting(k -> k.get("source"))
                .containsExactlyInAnyOrder("extension", "annotation");
    }

    @Test
    @DisplayName(
            "GIVEN an unannotated document WHEN reporting SHOULD suggest, unwired, only what is not already effective")
    void GIVEN_unannotatedDocument_WHEN_reporting_SHOULD_suggestWhatIsNotEffective() {
        List<Map<String, Object>> all = list(report(List.of()).report(null), "suggestions");
        assertThat(all)
                .extracting(s -> s.get("parentType") + "." + s.get("field"))
                .containsExactlyInAnyOrder("ShopOrder.customer", "ShopCustomer.orders");
        assertThat(all)
                .allSatisfy(
                        s -> assertThat(s).containsEntry("source", "suggested").containsEntry("wired", false));
        Map<String, Object> one = all.stream()
                .filter(s -> "ShopOrder".equals(s.get("parentType")))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> hint = (Map<String, Object>) one.get("hint");
        assertThat(hint.get("extension")).asString().contains("x-shiguchi-references: Customer");
        assertThat(hint.get("yaml"))
                .asString()
                .contains("from: ShopOrder.customerId")
                .contains("to: ShopCustomer");

        List<Map<String, Object>> rest = list(report(List.of(EXTENSION)).report(null), "suggestions");
        assertThat(rest)
                .extracting(s -> s.get("parentType") + "." + s.get("field"))
                .containsExactly("ShopCustomer.orders");
    }
}
