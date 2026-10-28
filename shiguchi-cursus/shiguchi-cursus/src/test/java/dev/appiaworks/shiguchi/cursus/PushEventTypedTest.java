package dev.appiaworks.shiguchi.cursus;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.appiaworks.shiguchi.proto.cursus.PublishedEvent;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PushEvent typed payload")
class PushEventTypedTest {

    private static final Topic TOPIC = Topic.parse("a:b");
    private static final String TYPE_URL = "type.googleapis.com/shiguchi.cursus.v1.PublishedEvent";
    private static final byte[] PROTO =
            PublishedEvent.newBuilder().setTopic("a:b").setId("1").build().toByteArray();

    @Test
    @DisplayName("an event built with the six-argument constructor is JSON-only")
    void given_six_argument_constructor_when_inspected_should_be_json_only() {
        PushEvent event = new PushEvent(TOPIC, "1", "{}".getBytes(UTF_8), Map.of(), null, Instant.EPOCH);

        assertThat(event.typed()).isFalse();
        assertThat(event.typeUrl()).isNull();
        assertThat(event.proto()).isNull();
    }

    @Test
    @DisplayName("an event with a type URL and proto bytes is typed, and withId keeps both")
    void given_type_url_and_proto_when_renamed_with_id_should_keep_typed_payload() {
        PushEvent event =
                new PushEvent(TOPIC, null, "{}".getBytes(UTF_8), Map.of(), null, Instant.EPOCH, TYPE_URL, PROTO);

        PushEvent renamed = event.withId("5");

        assertThat(event.typed()).isTrue();
        assertThat(renamed.id()).isEqualTo("5");
        assertThat(renamed.typed()).isTrue();
        assertThat(renamed.typeUrl()).isEqualTo(TYPE_URL);
        assertThat(renamed.proto()).isEqualTo(PROTO);
    }

    @Test
    @DisplayName("a type URL without proto bytes is refused")
    void given_type_url_without_proto_when_constructed_should_throw() {
        assertThatThrownBy(() ->
                        new PushEvent(TOPIC, "1", "{}".getBytes(UTF_8), Map.of(), null, Instant.EPOCH, TYPE_URL, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("go together");
    }

    @Test
    @DisplayName("proto bytes without a type URL are refused")
    void given_proto_without_type_url_when_constructed_should_throw() {
        assertThatThrownBy(() ->
                        new PushEvent(TOPIC, "1", "{}".getBytes(UTF_8), Map.of(), null, Instant.EPOCH, null, PROTO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("go together");
    }
}
