package dev.appiaworks.shiguchi.cursus;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.util.RawValue;

/**
 * The event as it travels between instances: one JSON document on a Redis channel or a Postgres
 * notification, and the same fields as a Redis Stream entry. The payload is embedded as it was
 * published, not re-serialised; a typed event carries its message too ({@code typeUrl},
 * {@code proto} as base64). Internal to the engines; not an API.
 */
public final class PushEventCodec {

    private static final ObjectMapper JSON = JsonMapper.builder(
                    dev.appiaworks.protobuf.extras.serde.JsonFactories.create())
            .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .disable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private PushEventCodec() {}

    public static String encode(PushEvent e) {
        ObjectNode node = JSON.createObjectNode();
        node.put("id", e.id());
        node.put("topic", e.topic().toString());
        node.putRawValue("data", new RawValue(new String(e.data(), StandardCharsets.UTF_8)));
        if (!e.attributes().isEmpty()) {
            ObjectNode attrs = node.putObject("attributes");
            e.attributes().forEach(attrs::put);
        }
        if (e.key() != null) node.put("key", e.key());
        node.put("time", e.time().toEpochMilli());
        if (e.typed()) {
            node.put("typeUrl", e.typeUrl());
            node.put("proto", e.proto());
        }
        return node.toString();
    }

    public static PushEvent decode(String json) {
        try {
            JsonNode node = JSON.readTree(json);
            return fromFields(
                    node.path("id").asString(null),
                    node.path("topic").asString(),
                    JSON.writeValueAsBytes(node.get("data")),
                    attributes(node.get("attributes")),
                    node.hasNonNull("key") ? node.get("key").asString() : null,
                    node.path("time").asLong(0),
                    node.hasNonNull("typeUrl") ? node.get("typeUrl").asString() : null,
                    node.hasNonNull("proto") ? node.get("proto").binaryValue() : null);
        } catch (tools.jackson.core.JacksonException ex) {
            throw new IllegalArgumentException("not a push event: " + ex.getMessage(), ex);
        }
    }

    /** The Stream entry's fields; the id is the entry's own. */
    public static Map<String, String> toFields(PushEvent e) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("data", new String(e.data(), StandardCharsets.UTF_8));
        if (!e.attributes().isEmpty()) {
            try {
                fields.put("attributes", JSON.writeValueAsString(e.attributes()));
            } catch (JacksonException ex) {
                throw new IllegalArgumentException(ex);
            }
        }
        if (e.key() != null) fields.put("key", e.key());
        fields.put("time", Long.toString(e.time().toEpochMilli()));
        if (e.typed()) {
            fields.put("typeUrl", e.typeUrl());
            fields.put("proto", java.util.Base64.getEncoder().encodeToString(e.proto()));
        }
        return fields;
    }

    public static PushEvent fromFields(String id, Topic topic, Map<String, String> fields) {
        Map<String, String> attrs = Map.of();
        String attrJson = fields.get("attributes");
        if (attrJson != null && !attrJson.isEmpty()) {
            try {
                attrs = attributes(JSON.readTree(attrJson));
            } catch (tools.jackson.core.JacksonException ex) {
                throw new IllegalArgumentException("bad attributes on " + id, ex);
            }
        }
        String time = fields.get("time");
        String proto = fields.get("proto");
        return fromFields(
                id,
                topic.toString(),
                fields.getOrDefault("data", "null").getBytes(StandardCharsets.UTF_8),
                attrs,
                fields.get("key"),
                time == null ? 0 : Long.parseLong(time),
                fields.get("typeUrl"),
                proto == null ? null : java.util.Base64.getDecoder().decode(proto));
    }

    private static PushEvent fromFields(
            String id,
            String topic,
            byte[] data,
            Map<String, String> attrs,
            String key,
            long timeMillis,
            String typeUrl,
            byte[] proto) {
        return new PushEvent(
                Topic.parse(topic),
                id,
                data,
                attrs,
                key,
                timeMillis == 0 ? null : Instant.ofEpochMilli(timeMillis),
                typeUrl,
                proto);
    }

    private static Map<String, String> attributes(JsonNode node) {
        if (node == null || !node.isObject()) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> f : node.properties())
            out.put(f.getKey(), f.getValue().asString());
        return out;
    }
}
