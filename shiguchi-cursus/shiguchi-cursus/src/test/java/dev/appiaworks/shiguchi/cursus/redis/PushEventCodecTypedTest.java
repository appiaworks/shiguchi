package dev.appiaworks.shiguchi.cursus.redis;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.cursus.PushEvent;
import dev.appiaworks.shiguchi.cursus.PushEventCodec;
import dev.appiaworks.shiguchi.cursus.Topic;
import dev.appiaworks.shiguchi.proto.cursus.PublishedEvent;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PushEventCodec typed payload")
class PushEventCodecTypedTest {

    private static final Topic TOPIC = Topic.parse("orders:123");
    private static final String TYPE_URL = "type.googleapis.com/shiguchi.cursus.v1.PublishedEvent";
    private static final byte[] PROTO = PublishedEvent.newBuilder()
            .setTopic("orders:123")
            .setId("7")
            .build()
            .toByteArray();
    private static final Instant TIME = Instant.ofEpochMilli(1_705_319_445_500L);

    @Test
    @DisplayName("a typed event encoded and decoded keeps its message, type URL and every other field")
    void given_typed_event_when_encodedAndDecoded_should_keep_message_and_fields() {
        PushEvent original = typedEvent();

        PushEvent decoded = PushEventCodec.decode(PushEventCodec.encode(original));

        assertThat(decoded.typed()).isTrue();
        assertThat(decoded.typeUrl()).isEqualTo(TYPE_URL);
        assertThat(decoded.proto()).isEqualTo(PROTO);
        assertThat(decoded.data()).isEqualTo(original.data());
        assertThat(decoded.id()).isEqualTo("100-1");
        assertThat(decoded.topic()).isEqualTo(TOPIC);
        assertThat(decoded.attributes()).isEqualTo(Map.of("user", "alice"));
        assertThat(decoded.key()).isEqualTo("order:xyz");
        assertThat(decoded.time().toEpochMilli()).isEqualTo(TIME.toEpochMilli());
    }

    @Test
    @DisplayName("a typed event's fields include the type URL and the message as base64")
    void given_typed_event_when_toFields_should_include_type_url_and_base64_proto() {
        Map<String, String> fields = PushEventCodec.toFields(typedEvent());

        assertThat(fields)
                .containsEntry("typeUrl", TYPE_URL)
                .containsEntry("proto", Base64.getEncoder().encodeToString(PROTO));
    }

    @Test
    @DisplayName("a typed event's stream fields restore its type URL and message when read back")
    void given_typed_fields_when_fromFields_should_restore_type_url_and_proto() {
        Map<String, String> fields = PushEventCodec.toFields(typedEvent());

        PushEvent restored = PushEventCodec.fromFields("100-1", TOPIC, fields);

        assertThat(restored.typed()).isTrue();
        assertThat(restored.typeUrl()).isEqualTo(TYPE_URL);
        assertThat(restored.proto()).isEqualTo(PROTO);
    }

    @Test
    @DisplayName("a JSON-only event encodes without typeUrl or proto fields")
    void given_json_only_event_when_encodedAndFieldsBuilt_should_omit_typed_keys() {
        PushEvent event = new PushEvent(TOPIC, "1", "{\"n\":1}".getBytes(UTF_8), Map.of(), null, TIME);

        String encoded = PushEventCodec.encode(event);

        assertThat(encoded).doesNotContain("\"typeUrl\"").doesNotContain("\"proto\"");
        assertThat(PushEventCodec.toFields(event)).doesNotContainKeys("typeUrl", "proto");
    }

    @Test
    @DisplayName("a JSON-only event decoded from the wire is not typed")
    void given_json_only_event_when_decoded_should_not_be_typed() {
        PushEvent event = new PushEvent(TOPIC, "1", "{\"n\":1}".getBytes(UTF_8), Map.of(), null, TIME);

        PushEvent decoded = PushEventCodec.decode(PushEventCodec.encode(event));

        assertThat(decoded.typed()).isFalse();
        assertThat(decoded.typeUrl()).isNull();
        assertThat(decoded.proto()).isNull();
    }

    private static PushEvent typedEvent() {
        return new PushEvent(
                TOPIC,
                "100-1",
                "{\"topic\":\"orders:123\"}".getBytes(UTF_8),
                Map.of("user", "alice"),
                "order:xyz",
                TIME,
                TYPE_URL,
                PROTO);
    }
}
