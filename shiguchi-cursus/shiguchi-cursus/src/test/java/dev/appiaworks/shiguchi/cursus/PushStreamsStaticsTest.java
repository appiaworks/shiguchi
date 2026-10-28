package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;

@DisplayName("Push stream request helpers")
class PushStreamsStaticsTest {

    private final PushProperties properties = propertiesWithNamespacesAndMaxSubscriptions(2);

    // ---- topics() tests ----

    @Test
    @DisplayName("a list of declared topics yields those topics in the order they were named")
    void given_declared_topics_when_parsing_should_return_them_in_order() {
        List<Topic> topics = PushStreams.topics(List.of("a:x", "b:y"), properties);

        assertThat(topics).containsExactly(Topic.parse("a:x"), Topic.parse("b:y"));
    }

    @Test
    @DisplayName("a comma-separated value is split into one topic per name")
    void given_comma_separated_value_when_parsing_should_split_on_comma() {
        List<Topic> topics = PushStreams.topics(List.of("a:x,b:y"), properties);

        assertThat(topics).containsExactly(Topic.parse("a:x"), Topic.parse("b:y"));
    }

    @Test
    @DisplayName("the same topic named twice, once padded with spaces, is subscribed to once")
    void given_duplicate_topic_with_padding_when_parsing_should_collapse_to_one() {
        List<Topic> topics = PushStreams.topics(List.of("a:x", " a:x "), properties);

        assertThat(topics).containsExactly(Topic.parse("a:x"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("noUsableTopics")
    @DisplayName("a request naming no usable topic is refused as a bad request")
    void given_no_usable_topic_when_parsing_should_refuse_bad_request(String scenario, List<String> raw) {
        assertThatThrownBy(() -> PushStreams.topics(raw, properties)).isInstanceOfSatisfying(PushException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.getMessage()).contains("at least one topic");
        });
    }

    static Stream<Arguments> noUsableTopics() {
        return Stream.of(Arguments.of("no values", List.of()), Arguments.of("only blank values", List.of("", " ")));
    }

    @Test
    @DisplayName("more distinct topics than the connection allows are refused as a bad request")
    void given_more_topics_than_max_subscriptions_when_parsing_should_refuse_bad_request() {
        assertThatThrownBy(() -> PushStreams.topics(List.of("a:x", "a:y", "b:z"), properties))
                .isInstanceOfSatisfying(PushException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).contains("at most 2");
                });
    }

    @Test
    @DisplayName("a topic in an undeclared namespace is refused as not found")
    void given_undeclared_namespace_when_parsing_should_refuse_not_found() {
        assertThatThrownBy(() -> PushStreams.topics(List.of("nope:x"), properties))
                .isInstanceOfSatisfying(PushException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.code()).isEqualTo("unknown_namespace");
                });
    }

    @Test
    @DisplayName("a topic that is not namespace:name is refused as a bad request")
    void given_malformed_topic_when_parsing_should_refuse_bad_request() {
        assertThatThrownBy(() -> PushStreams.topics(List.of("bad topic"), properties))
                .isInstanceOfSatisfying(
                        PushException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ---- after() tests ----

    @ParameterizedTest(name = "[{0}] means no resume point")
    @MethodSource("absentResumePoints")
    @DisplayName("a missing or blank resume id means there is no resume point")
    void given_absent_resume_id_when_checking_should_return_null(String scenario, String last) {
        assertThat(PushStreams.after(last, new MemoryPushStore())).isNull();
    }

    static Stream<Arguments> absentResumePoints() {
        return Stream.of(Arguments.of("null", null), Arguments.of("blank", "  "));
    }

    @Test
    @DisplayName("a numeric resume id that the store understands is returned as the resume point")
    void given_numeric_resume_id_when_checking_should_return_it() {
        assertThat(PushStreams.after("123", new MemoryPushStore())).isEqualTo("123");
    }

    @Test
    @DisplayName("a resume id the store cannot read is refused as a bad request")
    void given_non_numeric_resume_id_when_checking_should_refuse_bad_request() {
        assertThatThrownBy(() -> PushStreams.after("not-an-id", new MemoryPushStore()))
                .isInstanceOfSatisfying(
                        PushException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ---- helpers ----

    private static PushProperties propertiesWithNamespacesAndMaxSubscriptions(int maxSubscriptions) {
        PushProperties props = new PushProperties();
        props.getNamespaces().put("a", new PushProperties.Namespace());
        props.getNamespaces().put("b", new PushProperties.Namespace());
        props.getConnection().setMaxSubscriptions(maxSubscriptions);
        return props;
    }
}
