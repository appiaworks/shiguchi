package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.graphql.GraphQlSchemaBuilder.Relationship;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RelationshipSuggester")
class RelationshipSuggesterTest {

    private static Map<String, Object> props(String... names) {
        Map<String, Object> p = new LinkedHashMap<>();
        for (String n : names) p.put(n, Map.of("type", "string"));
        return p;
    }

    private static Map<String, Object> schema(Map<String, Object> properties) {
        return Map.of("type", "object", "properties", properties);
    }

    @Test
    @DisplayName("GIVEN Order.customerId and a Customer schema WHEN suggesting SHOULD propose both directions")
    void GIVEN_customerId_WHEN_suggest_SHOULD_proposeBothDirections() {
        Map<String, Map<String, Object>> schemas = new LinkedHashMap<>();
        schemas.put("Customer", schema(props("id", "name")));
        schemas.put("Order", schema(props("id", "customerId")));

        List<Relationship> out = RelationshipSuggester.suggest(schemas, "Shop");

        assertThat(out).extracting(Relationship::source).containsOnly(RelationshipSource.Source.SUGGESTED);
        assertThat(out)
                .anySatisfy(r -> {
                    assertThat(r.parentType()).isEqualTo("ShopOrder");
                    assertThat(r.fieldName()).isEqualTo("customer");
                    assertThat(r.resolvedType()).isEqualTo("ShopCustomer");
                    assertThat(r.groupBy()).isEmpty();
                })
                .anySatisfy(r -> {
                    assertThat(r.parentType()).isEqualTo("ShopCustomer");
                    assertThat(r.fieldName()).isEqualTo("orders");
                    assertThat(r.resolvedType()).isEqualTo("ShopOrder");
                    assertThat(r.groupBy()).isEqualTo("customerId");
                });
    }

    @Test
    @DisplayName("GIVEN a snake_case customer_id WHEN suggesting SHOULD match the Customer schema")
    void GIVEN_snakeCaseId_WHEN_suggest_SHOULD_match() {
        Map<String, Map<String, Object>> schemas = new LinkedHashMap<>();
        schemas.put("Customer", schema(props("id")));
        schemas.put("Order", schema(props("customer_id")));

        assertThat(RelationshipSuggester.suggest(schemas, "")).isNotEmpty();
    }

    @Test
    @DisplayName("GIVEN ownerId pointing at a User schema WHEN suggesting SHOULD wrongly suggest nothing")
    void GIVEN_ownerIdToUser_WHEN_suggest_SHOULD_suggestNothing() {
        Map<String, Map<String, Object>> schemas = new LinkedHashMap<>();
        schemas.put("User", schema(props("id")));
        schemas.put("Document", schema(props("id", "ownerId")));

        assertThat(RelationshipSuggester.suggest(schemas, "")).isEmpty();
    }

    @Test
    @DisplayName("GIVEN an already declared reference WHEN suggesting SHOULD leave it alone")
    void GIVEN_declaredReference_WHEN_suggest_SHOULD_skip() {
        Map<String, Object> declared = new LinkedHashMap<>();
        declared.put("customerId", Map.of("type", "string", "x-shiguchi-references", "Customer"));
        Map<String, Map<String, Object>> schemas = new LinkedHashMap<>();
        schemas.put("Customer", schema(props("id")));
        schemas.put("Order", schema(declared));

        assertThat(RelationshipSuggester.suggest(schemas, "")).isEmpty();
    }

    @Test
    @DisplayName("GIVEN a schema's own id property WHEN suggesting SHOULD not relate a schema to itself")
    void GIVEN_selfStem_WHEN_suggest_SHOULD_skip() {
        Map<String, Map<String, Object>> schemas = new LinkedHashMap<>();
        schemas.put("Order", schema(props("orderId")));

        assertThat(RelationshipSuggester.suggest(schemas, "")).isEmpty();
    }
}
