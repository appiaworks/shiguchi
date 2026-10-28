package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Topic template")
class TopicTemplateTest {

    @Test
    @DisplayName("GIVEN orders:{order_id} WHEN parsed SHOULD expose namespace orders and variable order_id")
    void given_ordersTemplate_when_parsed_should_expose_namespace_and_variables() {
        TopicTemplate template = TopicTemplate.parse("orders:{order_id}");

        assertThat(template.namespace()).isEqualTo("orders");
        assertThat(template.variables()).containsExactly("order_id");
    }

    @Test
    @DisplayName("GIVEN orders:{order_id} WHEN matching orders:42 SHOULD yield order_id 42")
    void given_ordersTemplate_when_matchingTopic_should_yield_variableValue() {
        Map<String, String> values = TopicTemplate.parse("orders:{order_id}").match(Topic.parse("orders:42"));

        assertThat(values).containsExactlyEntriesOf(Map.of("order_id", "42"));
    }

    @Test
    @DisplayName("GIVEN orders:{order_id} WHEN matching a topic of another namespace SHOULD not match")
    void given_ordersTemplate_when_matchingOtherNamespace_should_returnNull() {
        assertThat(TopicTemplate.parse("orders:{order_id}").match(Topic.parse("ticker:42")))
                .isNull();
    }

    @Test
    @DisplayName(
            "GIVEN orders:{order_id} WHEN matching a name with a slash SHOULD not match, a variable is one segment")
    void given_singleVariable_when_nameContainsSlash_should_returnNull() {
        assertThat(TopicTemplate.parse("orders:{order_id}").match(Topic.parse("orders:4/2")))
                .isNull();
    }

    @Test
    @DisplayName("GIVEN two variables WHEN matching tenant:acme/logs SHOULD yield both values in template order")
    void given_twoVariables_when_matchingTopic_should_yield_bothValuesInOrder() {
        TopicTemplate template = TopicTemplate.parse("tenant:{tenant}/{stream}");

        Map<String, String> values = template.match(Topic.parse("tenant:acme/logs"));

        assertThat(values).containsExactly(Map.entry("tenant", "acme"), Map.entry("stream", "logs"));
    }

    @Test
    @DisplayName("GIVEN a pattern without a colon or without a variable WHEN parsed SHOULD be rejected")
    void given_patternWithoutColonOrVariable_when_parsed_should_throwIllegalArgument() {
        assertThatThrownBy(() -> TopicTemplate.parse("orders"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("namespace:template");
        assertThatThrownBy(() -> TopicTemplate.parse("orders:fixed"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no {variable}");
    }

    @Test
    @DisplayName("GIVEN a duplicate variable or an unbalanced brace WHEN parsed SHOULD be rejected")
    void given_duplicateVariableOrUnbalancedBrace_when_parsed_should_throwIllegalArgument() {
        assertThatThrownBy(() -> TopicTemplate.parse("orders:{id}/{id}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("twice");
        assertThatThrownBy(() -> TopicTemplate.parse("orders:{id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unbalanced brace");
    }

    @Test
    @DisplayName("GIVEN a template WHEN converted to text SHOULD return the pattern as written")
    void given_template_when_toString_should_return_patternText() {
        assertThat(TopicTemplate.parse("orders:{order_id}")).hasToString("orders:{order_id}");
    }
}
