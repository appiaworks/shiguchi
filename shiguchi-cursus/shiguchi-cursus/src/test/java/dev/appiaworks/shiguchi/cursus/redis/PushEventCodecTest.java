package dev.appiaworks.shiguchi.cursus.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.appiaworks.shiguchi.cursus.PushEvent;
import dev.appiaworks.shiguchi.cursus.PushEventCodec;
import dev.appiaworks.shiguchi.cursus.Topic;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PushEventCodec")
class PushEventCodecTest {

    private final Topic topic = Topic.parse("orders:123");
    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("GIVEN an event WHEN encoded and decoded SHOULD round-trip topic, id, data, attributes, key, and time")
    void given_event_when_encodedAndDecoded_should_roundTrip() {
        Instant time = Instant.parse("2024-01-15T10:30:45.123Z");
        PushEvent original = new PushEvent(
                topic,
                "100-1",
                "{\"amount\":123.45}".getBytes(StandardCharsets.UTF_8),
                Map.of("user", "alice", "source", "mobile"),
                "order:xyz",
                time);

        String encoded = PushEventCodec.encode(original);
        PushEvent decoded = PushEventCodec.decode(encoded);

        assertThat(decoded.topic()).isEqualTo(original.topic());
        assertThat(decoded.id()).isEqualTo(original.id());
        assertThat(decoded.data()).isEqualTo(original.data());
        assertThat(decoded.attributes()).isEqualTo(original.attributes());
        assertThat(decoded.key()).isEqualTo(original.key());
        assertThat(decoded.time().toEpochMilli()).isEqualTo(original.time().toEpochMilli());
    }

    @Test
    @DisplayName(
            "GIVEN an event without attributes WHEN encoded SHOULD omit the attributes field and decode with empty map")
    void given_eventWithoutAttributes_when_encoded_should_decodeWithEmptyAttributes() {
        PushEvent event = new PushEvent(topic, "1", "{}".getBytes(), null, null, null);
        String encoded = PushEventCodec.encode(event);
        PushEvent decoded = PushEventCodec.decode(encoded);

        assertThat(decoded.attributes()).isEmpty();
        // Verify the encoded JSON does not contain "attributes"
        assertThat(encoded).doesNotContain("\"attributes\"");
    }

    @Test
    @DisplayName("GIVEN an event without a key WHEN encoded SHOULD omit the key field and decode with null key")
    void given_eventWithoutKey_when_encoded_should_decodeWithNullKey() {
        PushEvent event = new PushEvent(topic, "1", "{}".getBytes(), Map.of(), null, null);
        String encoded = PushEventCodec.encode(event);
        PushEvent decoded = PushEventCodec.decode(encoded);

        assertThat(decoded.key()).isNull();
        // Verify the encoded JSON does not contain "key"
        assertThat(encoded).doesNotContain("\"key\"");
    }

    @Test
    @DisplayName("GIVEN data that is a nested JSON object WHEN encoded and decoded SHOULD preserve byte for byte")
    void given_nestedJsonObject_when_encodedAndDecoded_should_preserveExact() {
        String originalData = "{\"nested\":{\"deep\":{\"value\":42}},\"array\":[1,2,3]}";
        PushEvent event = new PushEvent(topic, "1", originalData.getBytes(StandardCharsets.UTF_8), null, null, null);

        String encoded = PushEventCodec.encode(event);
        PushEvent decoded = PushEventCodec.decode(encoded);

        String decodedData = new String(decoded.data(), StandardCharsets.UTF_8);
        assertThat(decodedData).isEqualTo(originalData);
        // Verify the encoded string contains the raw data substring
        assertThat(encoded).contains(originalData);
    }

    @Test
    @DisplayName("GIVEN data that is a JSON array WHEN encoded and decoded SHOULD preserve byte for byte")
    void given_jsonArray_when_encodedAndDecoded_should_preserveExact() {
        String originalData = "[{\"id\":1,\"name\":\"first\"},{\"id\":2,\"name\":\"second\"}]";
        PushEvent event = new PushEvent(topic, "1", originalData.getBytes(StandardCharsets.UTF_8), null, null, null);

        String encoded = PushEventCodec.encode(event);
        PushEvent decoded = PushEventCodec.decode(encoded);

        String decodedData = new String(decoded.data(), StandardCharsets.UTF_8);
        assertThat(decodedData).isEqualTo(originalData);
    }

    @Test
    @DisplayName("GIVEN an event WHEN toFields and fromFields SHOULD round-trip the data")
    void given_event_when_toFieldsAndFromFields_should_roundTrip() {
        Instant time = Instant.parse("2024-01-15T10:30:45.500Z");
        PushEvent original =
                new PushEvent(topic, "100-1", "{\"x\":1}".getBytes(), Map.of("attr1", "value1"), "key123", time);

        Map<String, String> fields = PushEventCodec.toFields(original);
        PushEvent reconstructed = PushEventCodec.fromFields("100-1", topic, fields);

        assertThat(reconstructed.id()).isEqualTo(original.id());
        assertThat(reconstructed.topic()).isEqualTo(original.topic());
        assertThat(reconstructed.data()).isEqualTo(original.data());
        assertThat(reconstructed.attributes()).isEqualTo(original.attributes());
        assertThat(reconstructed.key()).isEqualTo(original.key());
        assertThat(reconstructed.time().toEpochMilli())
                .isEqualTo(original.time().toEpochMilli());
    }

    @Test
    @DisplayName("GIVEN a decoded event from fields with no id provided WHEN constructed SHOULD have null id")
    void given_fieldsWithoutId_when_decoded_should_haveNullId() {
        Map<String, String> fields = Map.of("data", "{}", "time", "1705319445500");
        PushEvent event = PushEventCodec.fromFields(null, topic, fields);

        assertThat(event.id()).isNull();
    }

    @Test
    @DisplayName("GIVEN a garbage JSON string WHEN decoded SHOULD throw IllegalArgumentException")
    void given_garbajeJson_when_decoded_should_throw() {
        assertThatThrownBy(() -> PushEventCodec.decode("not a json string"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a push event");
    }

    @Test
    @DisplayName("GIVEN a garbage event id WHEN compareIds SHOULD throw IllegalArgumentException")
    void given_garbajeId_when_compared_should_throw() {
        assertThatThrownBy(() -> RedisPushStore.parse("not-a-valid-id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not an event id");
    }

    @Test
    @DisplayName("GIVEN encoded event fields WHEN decoded from fields SHOULD preserve attributes as a JSON string")
    void given_attributesInFields_when_decoded_should_parseCorrectly() {
        Map<String, String> fields = Map.of(
                "data", "{}",
                "attributes", "{\"key1\":\"value1\",\"key2\":\"value2\"}",
                "time", "1705319445500");

        PushEvent event = PushEventCodec.fromFields("100-1", topic, fields);

        assertThat(event.attributes()).containsEntry("key1", "value1").containsEntry("key2", "value2");
    }
}
