package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Topic parsing and validation")
class TopicTest {

    @Test
    @DisplayName("parse orders:123 gives namespace orders and name 123")
    void given_simple_topic_when_parsed_should_extract_namespace_and_name() {
        Topic topic = Topic.parse("orders:123");
        assertThat(topic.namespace()).isEqualTo("orders");
        assertThat(topic.name()).isEqualTo("123");
    }

    @Test
    @DisplayName("parse tenant:acme:orders gives namespace tenant and name acme:orders")
    void given_topic_with_colon_in_name_when_parsed_should_split_on_first_colon() {
        Topic topic = Topic.parse("tenant:acme:orders");
        assertThat(topic.namespace()).isEqualTo("tenant");
        assertThat(topic.name()).isEqualTo("acme:orders");
    }

    @Test
    @DisplayName("toString round-trips parsed topic")
    void given_parsed_topic_when_to_string_should_match_original() {
        String original = "orders:events";
        Topic topic = Topic.parse(original);
        assertThat(topic.toString()).isEqualTo(original);
    }

    @Test
    @DisplayName("parse with no colon throws IllegalArgumentException")
    void given_topic_without_colon_when_parsed_should_throw() {
        assertThatThrownBy(() -> Topic.parse("orders"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be namespace:name");
    }

    @Test
    @DisplayName("parse with empty name throws IllegalArgumentException")
    void given_topic_with_empty_name_when_parsed_should_throw() {
        assertThatThrownBy(() -> Topic.parse("orders:"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be namespace:name");
    }

    @Test
    @DisplayName("parse with invalid namespace characters throws IllegalArgumentException")
    void given_topic_with_invalid_namespace_when_parsed_should_throw() {
        assertThatThrownBy(() -> Topic.parse("bad name:topic"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid topic namespace");
    }

    @Test
    @DisplayName("parse with invalid name characters throws IllegalArgumentException")
    void given_topic_with_invalid_name_when_parsed_should_throw() {
        assertThatThrownBy(() -> Topic.parse("orders:bad<>topic"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid topic name");
    }

    @Test
    @DisplayName("parse null throws IllegalArgumentException")
    void given_null_topic_when_parsed_should_throw() {
        assertThatThrownBy(() -> Topic.parse(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topic is required");
    }

    @Test
    @DisplayName("two parsed topics are equal when namespace and name match")
    void given_two_topics_with_same_values_when_compared_should_be_equal() {
        Topic topic1 = Topic.parse("orders:123");
        Topic topic2 = Topic.parse("orders:123");
        assertThat(topic1).isEqualTo(topic2);
        assertThat(topic1.hashCode()).isEqualTo(topic2.hashCode());
    }
}
