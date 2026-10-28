package com.acme.shop.kafka;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import dev.appiaworks.protobuf.extras.ProtoJson;
import dev.appiaworks.protobuf.extras.ProtoJsonException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.Deserializer;

/**
 * Reads proto messages from Kafka, written as proto binary or as JSON. The record's
 * {@code content-type} header decides; a record without one is read as {@code fallback}, so a topic
 * written by producers that set no header still works.
 */
public final class ProtoDeserializer<M extends Message> implements Deserializer<M> {

    private final ProtoJson json;
    private final WireFormat fallback;
    private final M prototype;

    /** @param prototype any instance of the generated class, typically {@code Order.getDefaultInstance()} */
    public ProtoDeserializer(ProtoJson json, WireFormat fallback, M prototype) {
        this.json = Objects.requireNonNull(json, "json");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        this.prototype = Objects.requireNonNull(prototype, "prototype");
    }

    @Override
    public M deserialize(String topic, byte[] data) {
        return read(fallback, topic, data);
    }

    @Override
    public M deserialize(String topic, Headers headers, byte[] data) {
        return read(WireFormat.of(headers, fallback), topic, data);
    }

    @SuppressWarnings("unchecked")
    private M read(WireFormat format, String topic, byte[] data) {
        if (data == null) return null; // a tombstone
        try {
            return switch (format) {
                case PROTOBUF -> (M) prototype.getParserForType().parseFrom(data);
                case JSON -> json.parse(new String(data, StandardCharsets.UTF_8), prototype);
            };
        } catch (InvalidProtocolBufferException | ProtoJsonException e) {
            // Kafka's own type for a record that cannot be read: the consumer reports the
            // partition and offset, and an error handler can skip or dead-letter the record.
            throw new SerializationException("Cannot read a " + prototype.getDescriptorForType().getFullName()
                    + " from " + topic + " as " + format + ": " + e.getMessage(), e);
        }
    }
}
