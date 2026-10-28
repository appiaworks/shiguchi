package com.acme.shop.kafka;

import com.google.protobuf.Message;
import dev.appiaworks.protobuf.extras.ProtoJson;
import java.util.Objects;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.Serializer;

/**
 * Writes proto messages to Kafka as proto binary or as JSON.
 *
 * <pre>{@code
 * new KafkaProducer<>(props, new StringSerializer(), new ProtoSerializer<Order>(json, WireFormat.JSON));
 * }</pre>
 *
 * Pass an instance as above rather than a class name in {@code value.serializer}: it needs its
 * ProtoJson, and that is application wiring, not producer configuration.
 */
public final class ProtoSerializer<M extends Message> implements Serializer<M> {

    private final ProtoJson json;
    private final WireFormat format;

    public ProtoSerializer(ProtoJson json, WireFormat format) {
        this.json = Objects.requireNonNull(json, "json");
        this.format = Objects.requireNonNull(format, "format");
    }

    @Override
    public byte[] serialize(String topic, M message) {
        if (message == null) return null; // a tombstone
        return switch (format) {
            case PROTOBUF -> message.toByteArray();
            case JSON -> json.printUtf8(message);
        };
    }

    @Override
    public byte[] serialize(String topic, Headers headers, M message) {
        if (message != null) format.stamp(headers);
        return serialize(topic, message);
    }
}
