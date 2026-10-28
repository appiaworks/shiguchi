package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Topic pattern matching")
class TopicPatternTest {

    @Test
    @DisplayName("pattern orders:* matches orders:123 but not orders:a/b")
    void given_single_segment_pattern_when_matched_should_not_cross_segment_boundary() {
        TopicPattern pattern = TopicPattern.parse("orders:*");
        assertThat(pattern.matches(Topic.parse("orders:123"))).isTrue();
        assertThat(pattern.matches(Topic.parse("orders:a/b"))).isFalse();
    }

    @Test
    @DisplayName("pattern orders:** matches orders:a/b")
    void given_double_segment_pattern_when_matched_should_cross_segment_boundaries() {
        TopicPattern pattern = TopicPattern.parse("orders:**");
        assertThat(pattern.matches(Topic.parse("orders:a/b"))).isTrue();
        assertThat(pattern.matches(Topic.parse("orders:a/b/c"))).isTrue();
    }

    @Test
    @DisplayName("pattern tenant:acme/* matches tenant:acme/alerts but not tenant:other/alerts")
    void given_prefix_pattern_when_matched_should_check_prefix() {
        TopicPattern pattern = TopicPattern.parse("tenant:acme/*");
        assertThat(pattern.matches(Topic.parse("tenant:acme/alerts"))).isTrue();
        assertThat(pattern.matches(Topic.parse("tenant:other/alerts"))).isFalse();
    }

    @Test
    @DisplayName("pattern tenant:acme/* does not match nested segments tenant:acme/a/b")
    void given_prefix_pattern_when_matched_should_not_match_nested() {
        TopicPattern pattern = TopicPattern.parse("tenant:acme/*");
        assertThat(pattern.matches(Topic.parse("tenant:acme/a/b"))).isFalse();
    }

    @Test
    @DisplayName("pattern user:{sub} behaves like user:* for matching")
    void given_variable_pattern_when_matched_should_treat_as_wildcard() {
        TopicPattern pattern = TopicPattern.parse("user:{sub}");
        assertThat(pattern.matches(Topic.parse("user:alice"))).isTrue();
        assertThat(pattern.matches(Topic.parse("user:alice/inbox"))).isFalse();
    }

    @Test
    @DisplayName("pattern with no colon matches every topic in that namespace")
    void given_namespace_only_pattern_when_matched_should_match_all_in_namespace() {
        TopicPattern pattern = TopicPattern.parse("orders");
        assertThat(pattern.matches(Topic.parse("orders:123"))).isTrue();
        assertThat(pattern.matches(Topic.parse("orders:a/b/c"))).isTrue();
        assertThat(pattern.matches(Topic.parse("other:123"))).isFalse();
    }

    @Test
    @DisplayName("pattern with unclosed brace throws IllegalArgumentException")
    void given_pattern_with_unclosed_brace_when_parsed_should_throw() {
        assertThatThrownBy(() -> TopicPattern.parse("user:{sub"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unclosed {");
    }

    @Test
    @DisplayName("blank pattern throws IllegalArgumentException")
    void given_blank_pattern_when_parsed_should_throw() {
        assertThatThrownBy(() -> TopicPattern.parse(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topic pattern is required");
    }

    @Test
    @DisplayName("null pattern throws IllegalArgumentException")
    void given_null_pattern_when_parsed_should_throw() {
        assertThatThrownBy(() -> TopicPattern.parse(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topic pattern is required");
    }

    @Test
    @DisplayName("pattern toString returns original text")
    void given_parsed_pattern_when_to_string_should_return_original() {
        String original = "tenant:acme/*";
        TopicPattern pattern = TopicPattern.parse(original);
        assertThat(pattern.toString()).isEqualTo(original);
    }
}
