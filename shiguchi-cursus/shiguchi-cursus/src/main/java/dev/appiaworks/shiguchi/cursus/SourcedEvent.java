package dev.appiaworks.shiguchi.cursus;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.util.Map;

/**
 * One event a {@link PushSource} mapped: its topic, the JSON payload every transport renders, and
 * for a typed event the protobuf type URL and bytes gRPC subscribers receive.
 *
 * @param topic the target topic
 * @param data the JSON payload
 * @param attributes the event's attributes ({@code from=source} and {@code source=<name>} are added)
 * @param key the event key, or null
 * @param typeUrl {@code type.googleapis.com/<message>} for a typed event, else null
 * @param proto the message bytes for a typed event, else null
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.12.0")
public record SourcedEvent(
        Topic topic, byte[] data, Map<String, String> attributes, String key, String typeUrl, byte[] proto) {

    public SourcedEvent {
        if (topic == null) throw new IllegalArgumentException("topic is required");
        if (data == null) throw new IllegalArgumentException("data is required");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /** A JSON event. */
    public static SourcedEvent json(Topic topic, byte[] data, Map<String, String> attributes, String key) {
        return new SourcedEvent(topic, data, attributes, key, null, null);
    }
}
