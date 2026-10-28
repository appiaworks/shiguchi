package com.acme.shop.kafka;

import com.google.protobuf.Descriptors.Descriptor;
import dev.appiaworks.protobuf.extras.ProtoJson;
import java.util.Objects;
import org.apache.kafka.common.header.Headers;

/**
 * Republishes a proto-binary topic as JSON, for consumers that cannot read protobuf (a dashboard,
 * a JavaScript service, a data lake).
 *
 * <p>It never builds a message. The transcoder walks the record's bytes once and writes JSON
 * straight out, so a bridge forwarding thousands of records a second spends no time or garbage
 * on objects nobody looks at. It needs only a {@link Descriptor}: from a generated class
 * ({@code Order.getDescriptor()}), or from a schema registry or a descriptor file when the
 * bridge has no generated code for the topic at all.
 *
 * <pre>{@code
 * for (ConsumerRecord<String, byte[]> record : consumer.poll(timeout)) {  // ByteArrayDeserializer
 *     producer.send(new ProducerRecord<>("orders.json", null, record.key(),
 *             bridge.toJson(record.value(), record.headers()), record.headers()));
 * }
 * }</pre>
 */
public final class JsonBridge {

    private final ProtoJson json;
    private final Descriptor descriptor;

    public JsonBridge(ProtoJson json, Descriptor descriptor) {
        this.json = Objects.requireNonNull(json, "json");
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    }

    /**
     * The record value as UTF-8 JSON, with {@code headers} restamped as JSON. Throws
     * {@code IllegalArgumentException} for bytes that are not a message of the descriptor's type.
     */
    public byte[] toJson(byte[] protobuf, Headers headers) {
        if (protobuf == null) return null;
        byte[] out = json.transcode(protobuf, descriptor);
        if (headers != null) WireFormat.JSON.stamp(headers);
        return out;
    }
}
