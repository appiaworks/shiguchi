package dev.appiaworks.shiguchi.cursus;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.time.Instant;
import java.util.Map;

/**
 * One event on a topic.
 *
 * <p>The payload is JSON ({@code data}) on every event; the JSON subscribers (SSE, WebSocket, the
 * transcoded stream) read that. An event a typed publisher sent as a protobuf message carries the
 * message too ({@code typeUrl} and {@code proto}, the fields of the {@code google.protobuf.Any}
 * it came in), so gRPC subscribers receive it as it was sent, without a conversion; {@code data}
 * is then its proto JSON rendering, {@code @type} included.
 *
 * @param topic where it was published
 * @param id the engine-assigned id, ordered within an instance; null until the store assigns one
 * @param data the payload, a single JSON value as UTF-8 bytes (the gateway validates it is JSON)
 * @param attributes publisher-set metadata, delivered with the event; never null
 * @param key the ordering key, or null
 * @param time when the gateway accepted it
 * @param typeUrl the type URL of the typed payload, or null for a JSON-only event
 * @param proto the typed payload's serialized bytes, or null for a JSON-only event
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.14.0")
public record PushEvent(
        Topic topic,
        String id,
        byte[] data,
        Map<String, String> attributes,
        String key,
        Instant time,
        String typeUrl,
        byte[] proto) {

    public PushEvent {
        if (topic == null) throw new IllegalArgumentException("topic is required");
        if (data == null) throw new IllegalArgumentException("data is required");
        if ((typeUrl == null) != (proto == null)) {
            throw new IllegalArgumentException("typeUrl and proto go together");
        }
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        if (time == null) time = Instant.now();
    }

    /** The attribute that marks a signal the gateway itself sends down a subscription (not a published event). */
    public static final String SIGNAL = "push.signal";

    /** True for a gateway signal ({@code overflow} or {@code reset}), which carries no published event. */
    public boolean signal() {
        return attributes.containsKey(SIGNAL);
    }

    /** The {@code overflow} signal: {@code dropped} events of {@code topic} were lost before this subscription resumed. */
    public static PushEvent overflowSignal(Topic topic, long dropped) {
        byte[] data = ("{\"reason\":\"session_overflow\",\"dropped\":" + dropped + "}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new PushEvent(
                topic, "", data, Map.of(SIGNAL, "overflow", "dropped", Long.toString(dropped)), null, null);
    }

    /**
     * The {@code reset} signal: the subscriber resumed from an id older than the history the store
     * still keeps ({@code reason} {@code history_gap}) or asked to resume where nothing is kept
     * ({@code no_history}), so it has missed events and should resynchronise. It carries no id, so
     * it never becomes a resume point, and no {@code push.origin}, so origin suppression never
     * drops it.
     */
    public static PushEvent resetSignal(Topic topic, String reason) {
        byte[] data = ("{\"topic\":\"" + topic + "\",\"reason\":\"" + reason + "\"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new PushEvent(topic, "", data, Map.of(SIGNAL, "reset", "reason", reason), null, null);
    }

    /** A JSON-only event. */
    public PushEvent(Topic topic, String id, byte[] data, Map<String, String> attributes, String key, Instant time) {
        this(topic, id, data, attributes, key, time, null, null);
    }

    /** True when a typed publisher sent this event as a protobuf message. */
    public boolean typed() {
        return proto != null;
    }

    public PushEvent withId(String newId) {
        return new PushEvent(topic, newId, data, attributes, key, time, typeUrl, proto);
    }
}
