package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("GraphQlCostCalculator")
class GraphQlCostCalculatorTest {

    private static final GraphQLSchema SCHEMA = new SchemaGenerator()
            .makeExecutableSchema(
                    new SchemaParser().parse("""
                                    type Query {
                                      item(id: ID): Item
                                      items(first: Int): [Item!]!
                                    }
                                    type Item {
                                      id: ID
                                      name: String
                                      tags: [String!]
                                      owner: Owner
                                    }
                                    type Owner { name: String }
                                    """),
                    RuntimeWiring.newRuntimeWiring().build());

    private static Integer cost(Map<String, Integer> weights, int defaultListSize, String query) {
        return cost(weights, defaultListSize, query, Map.of());
    }

    private static Integer cost(
            Map<String, Integer> weights, int defaultListSize, String query, Map<String, Object> vars) {
        return new GraphQlCostCalculator(() -> weights, defaultListSize).calculate(SCHEMA, query, null, vars);
    }

    @Test
    @DisplayName("GIVEN no weights WHEN a query selects three fields SHOULD cost one point each")
    void given_noWeights_when_threeFields_should_costThree() {
        assertThat(cost(Map.of(), 10, "{ item(id: \"1\") { id name } }")).isEqualTo(3);
    }

    @Test
    @DisplayName("GIVEN a weight on a field WHEN it is selected SHOULD cost that weight")
    void given_weight_when_selected_should_costWeight() {
        assertThat(cost(Map.of("Item.owner", 5), 10, "{ item(id: \"1\") { id owner { name } } }"))
                .isEqualTo(1 + 1 + 5 + 1);
    }

    @Test
    @DisplayName("GIVEN a list field with first WHEN selected SHOULD multiply by first")
    void given_listWithFirst_when_selected_should_multiplyByFirst() {
        // items (1 * 7) + id (1)
        assertThat(cost(Map.of(), 10, "{ items(first: 7) { id } }")).isEqualTo(8);
    }

    @Test
    @DisplayName("GIVEN a list field without first WHEN selected SHOULD multiply by default-list-size")
    void given_listWithoutFirst_when_selected_should_useDefaultListSize() {
        assertThat(cost(Map.of(), 4, "{ items { id } }")).isEqualTo(5);
    }

    @Test
    @DisplayName("GIVEN first sent as a variable WHEN selected SHOULD multiply by the variable")
    void given_firstAsVariable_when_selected_should_useVariable() {
        assertThat(cost(Map.of(), 10, "query($n: Int) { items(first: $n) { id } }", Map.of("n", 3)))
                .isEqualTo(4);
    }

    @Test
    @DisplayName("GIVEN a nested list WHEN it has no first SHOULD inherit the enclosing field's first")
    void given_nestedList_when_noFirst_should_inheritParentFirst() {
        // items 1*5, tags inherits 5 -> 5, plus nothing under tags
        assertThat(cost(Map.of(), 10, "{ items(first: 5) { tags } }")).isEqualTo(5 + 5);
    }

    @Test
    @DisplayName("GIVEN a document that does not parse WHEN costed SHOULD answer null")
    void given_brokenDocument_when_costed_should_answerNull() {
        assertThat(cost(Map.of(), 10, "{ item(")).isNull();
    }
}
