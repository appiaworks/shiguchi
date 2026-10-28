package dev.appiaworks.shiguchi.cursus;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.appiaworks.shiguchi.proto.cursus.PublishedEvent;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PushGrpcFrames")
class PushGrpcFramesTest {

    private static final Topic TOPIC = Topic.parse("a:b");
    private static final String TYPE_URL = "type.googleapis.com/shiguchi.cursus.v1.PublishedEvent";

    @Test
    @DisplayName("framing the same event twice returns the same bytes instance and keeps one entry")
    void given_same_event_twice_when_framed_should_return_same_bytes_instance() {
        PushGrpcFrames frames = new PushGrpcFrames();
        PushEvent event = jsonEvent("1", "{\"n\":1}");

        byte[] first = frames.frame(event);
        byte[] second = frames.frame(event);

        assertThat(second).isSameAs(first);
        assertThat(frames.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("two distinct events with identical content are cached separately by identity")
    void given_two_distinct_events_with_same_content_when_framed_should_keep_two_entries() {
        PushGrpcFrames frames = new PushGrpcFrames();

        frames.frame(jsonEvent("1", "{\"n\":1}"));
        frames.frame(jsonEvent("1", "{\"n\":1}"));

        assertThat(frames.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("a JSON event is framed as a wire PushEvent with its topic, id and JSON payload")
    void given_json_event_when_framed_should_parse_as_wire_event_with_topic_id_and_payload() throws Exception {
        byte[] bytes = new PushGrpcFrames().frame(jsonEvent("42", "{\"n\":1}"));

        dev.appiaworks.shiguchi.proto.cursus.PushEvent wire =
                dev.appiaworks.shiguchi.proto.cursus.PushEvent.parseFrom(bytes);

        assertThat(wire.getTopic()).isEqualTo("a:b");
        assertThat(wire.getId()).isEqualTo("42");
        assertThat(wire.getPayloadCase()).isEqualTo(dev.appiaworks.shiguchi.proto.cursus.PushEvent.PayloadCase.JSON);
        assertThat(wire.getJson().getStructValue().getFieldsMap().get("n").getNumberValue())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("a typed event is framed with its message and type URL, unpackable to the published type")
    void given_typed_event_when_framed_should_carry_message_that_unpacks_to_its_type() throws Exception {
        byte[] proto =
                PublishedEvent.newBuilder().setTopic("a:b").setId("9").build().toByteArray();
        PushEvent event =
                new PushEvent(TOPIC, "1", "{\"@type\":\"x\"}".getBytes(UTF_8), Map.of(), null, null, TYPE_URL, proto);

        byte[] bytes = new PushGrpcFrames().frame(event);
        dev.appiaworks.shiguchi.proto.cursus.PushEvent wire =
                dev.appiaworks.shiguchi.proto.cursus.PushEvent.parseFrom(bytes);

        assertThat(wire.getPayloadCase()).isEqualTo(dev.appiaworks.shiguchi.proto.cursus.PushEvent.PayloadCase.MESSAGE);
        assertThat(wire.getMessage().getTypeUrl()).isEqualTo(TYPE_URL);
        assertThat(wire.getMessage().unpack(PublishedEvent.class).getTopic()).isEqualTo("a:b");
    }

    @Test
    @DisplayName("after more events than the ring holds, the oldest are evicted and framing them again recomputes")
    void given_ring_of_four_when_six_events_framed_should_evict_oldest_two() {
        PushGrpcFrames frames = new PushGrpcFrames(4);
        PushEvent[] events = new PushEvent[6];
        for (int i = 0; i < events.length; i++) {
            events[i] = jsonEvent(String.valueOf(i), "{}");
        }
        byte[] firstFrame = frames.frame(events[0]);
        for (int i = 1; i < events.length; i++) {
            frames.frame(events[i]);
        }

        assertThat(frames.size()).isEqualTo(4);
        byte[] recomputed = frames.frame(events[0]);
        assertThat(recomputed).isNotSameAs(firstFrame).isEqualTo(firstFrame);
    }

    @Test
    @DisplayName("a capacity that is not a power of two is refused")
    void given_capacity_not_power_of_two_when_constructed_should_throw() {
        assertThatThrownBy(() -> new PushGrpcFrames(3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("power of two");
    }

    @Test
    @DisplayName("a reset signal is framed as a wire event with the push.signal attribute and no id")
    void given_reset_signal_when_converted_should_carry_attribute_and_no_id() {
        dev.appiaworks.shiguchi.proto.cursus.PushEvent wire =
                PushEventProtos.toProto(PushEvent.resetSignal(TOPIC, "history_gap"));

        assertThat(wire.getTopic()).isEqualTo("a:b");
        assertThat(wire.getId()).isEmpty();
        assertThat(wire.getAttributesMap())
                .containsEntry(PushEvent.SIGNAL, "reset")
                .containsEntry("reason", "history_gap");
    }

    private static PushEvent jsonEvent(String id, String json) {
        return new PushEvent(TOPIC, id, json.getBytes(UTF_8), Map.of(), null, Instant.EPOCH);
    }
}
