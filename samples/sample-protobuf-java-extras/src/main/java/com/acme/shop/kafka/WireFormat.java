package com.acme.shop.kafka;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

/**
 * How a record's value is encoded. The serializer stamps it on every record as a
 * {@code content-type} header, so a consumer can tell the two apart on one topic, for instance
 * while producers move from one to the other.
 */
public enum WireFormat {
    /** Proto binary: smallest and fastest; consumers need the schema. */
    PROTOBUF("application/x-protobuf"),
    /** JSON in the ProtoJson dialect: readable by anything, and by people. */
    JSON("application/json");

    public static final String HEADER = "content-type";

    private final String contentType;

    WireFormat(String contentType) {
        this.contentType = contentType;
    }

    public String contentType() {
        return contentType;
    }

    void stamp(Headers headers) {
        headers.remove(HEADER).add(HEADER, contentType.getBytes(StandardCharsets.UTF_8));
    }

    /** The format a record's header names, or {@code fallback} when it names none we know. */
    static WireFormat of(Headers headers, WireFormat fallback) {
        Header header = headers == null ? null : headers.lastHeader(HEADER);
        if (header == null) return fallback;
        String value = new String(header.value(), StandardCharsets.UTF_8);
        for (WireFormat format : values()) {
            if (format.contentType.equals(value)) return format;
        }
        return fallback;
    }
}
