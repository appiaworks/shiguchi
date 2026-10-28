package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Topic template expansion: a source record becomes a concrete topic")
class TopicTemplateExpandTest {

    @Test
    @DisplayName("GIVEN a template orders:{key} WHEN expanded with key 42 SHOULD produce the topic orders:42")
    void given_singleVariableTemplate_when_expanded_should_produceConcreteTopic() {
        TopicTemplate template = TopicTemplate.parseTarget("orders:{key}");

        Topic topic = template.expand(Map.of("key", "42")::get);

        assertThat(topic).isEqualTo(Topic.parse("orders:42"));
    }

    @Test
    @DisplayName(
            "GIVEN a template variable with no value or a blank value WHEN expanded SHOULD fail naming the variable")
    void given_missingOrBlankValue_when_expanded_should_failNamingVariable() {
        TopicTemplate template = TopicTemplate.parseTarget("orders:{key}");

        assertThatThrownBy(() -> template.expand(Map.<String, String>of()::get))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("{key}");
        assertThatThrownBy(() -> template.expand(Map.of("key", "  ")::get))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("{key}");
    }

    @Test
    @DisplayName(
            "GIVEN a value containing a slash WHEN expanded SHOULD fail, since a variable matches one path segment only")
    void given_valueWithSlash_when_expanded_should_fail() {
        TopicTemplate template = TopicTemplate.parseTarget("orders:{key}");

        assertThatThrownBy(() -> template.expand(Map.of("key", "a/b")::get))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contains '/'");
    }

    @Test
    @DisplayName(
            "GIVEN a fixed target orders:all with no variable WHEN expanded SHOULD produce orders:all and name no variables")
    void given_fixedTarget_when_expanded_should_produceFixedTopic() {
        TopicTemplate template = TopicTemplate.parseTarget("orders:all");

        assertThat(template.variables()).isEmpty();
        assertThat(template.expand(name -> null)).isEqualTo(Topic.parse("orders:all"));
    }

    @Test
    @DisplayName(
            "GIVEN a subscription option pattern with no variable WHEN parsed SHOULD fail, since a topic option needs one")
    void given_fixedPatternInOption_when_parsed_should_fail() {
        assertThatThrownBy(() -> TopicTemplate.parse("orders:all"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("variable");
    }

    @Test
    @DisplayName(
            "GIVEN a two-variable template tenant:{t}/{s} WHEN expanded with both values SHOULD fill both into the topic")
    void given_twoVariableTemplate_when_expanded_should_fillBothVariables() {
        TopicTemplate template = TopicTemplate.parseTarget("tenant:{t}/{s}");

        Topic topic = template.expand(Map.of("t", "acme", "s", "alerts")::get);

        assertThat(topic).isEqualTo(Topic.parse("tenant:acme/alerts"));
    }
}
