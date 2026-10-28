package com.acme.shop.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.shop.proto.Order;
import dev.appiaworks.protobuf.extras.ProtoJson;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for Kafka serialization and deserialization of protobuf messages in both binary and JSON
 * formats.
 */
class ProtoKafkaSerdeTest {

    private ProtoJson json;
    private Order testOrder;

    @BeforeEach
    void setUp() {
        json = ProtoJson.defaults();
        testOrder = Order.newBuilder()
                .setId("o-1")
                .setPlacedOn(LocalDate.of(2026, 9, 22))
                .build();
    }

    @Test
    @DisplayName("Given PROTOBUF format, when serializing an order, should write protobuf bytes and stamp content-type header")
    void given_protoFormat_when_serializingOrder_should_writeProtobufBytesAndStampHeader() {
        ProtoSerializer<Order> serializer = new ProtoSerializer<>(json, WireFormat.PROTOBUF);
        RecordHeaders headers = new RecordHeaders();

        byte[] bytes = serializer.serialize("orders", headers, testOrder);

        assertThat(bytes).isEqualTo(testOrder.toByteArray());
        assertThat(headers.lastHeader(WireFormat.HEADER)).isNotNull();
        assertThat(new String(headers.lastHeader(WireFormat.HEADER).value(), StandardCharsets.UTF_8))
                .isEqualTo("application/x-protobuf");
    }

    @Test
    @DisplayName("Given JSON format, when serializing an order, should write JSON UTF-8 bytes and stamp application/json header")
    void given_jsonFormat_when_serializingOrder_should_writeJsonUtf8AndStampHeader() {
        ProtoSerializer<Order> serializer = new ProtoSerializer<>(json, WireFormat.JSON);
        RecordHeaders headers = new RecordHeaders();

        byte[] bytes = serializer.serialize("orders", headers, testOrder);

        String jsonString = new String(bytes, StandardCharsets.UTF_8);
        assertThat(jsonString).contains("\"id\":\"o-1\"").contains("\"placedOn\":\"2026-09-22\"");
        assertThat(headers.lastHeader(WireFormat.HEADER)).isNotNull();
        assertThat(new String(headers.lastHeader(WireFormat.HEADER).value(), StandardCharsets.UTF_8))
                .isEqualTo("application/json");
    }

    @Test
    @DisplayName("Given proto format, when serializing twice to same headers, should have exactly one content-type header")
    void given_protoFormat_when_serializingTwice_should_haveExactlyOneContentTypeHeader() {
        ProtoSerializer<Order> serializer = new ProtoSerializer<>(json, WireFormat.PROTOBUF);
        RecordHeaders headers = new RecordHeaders();

        serializer.serialize("orders", headers, testOrder);
        serializer.serialize("orders", headers, testOrder);

        assertThat(headers.headers(WireFormat.HEADER)).hasSize(1);
    }

    @Test
    @DisplayName("Given proto format, when serializing null message, should return null and stamp no header")
    void given_protoFormat_when_serializingNull_should_returnNullAndNoHeader() {
        ProtoSerializer<Order> serializer = new ProtoSerializer<>(json, WireFormat.PROTOBUF);
        RecordHeaders headers = new RecordHeaders();

        byte[] bytes = serializer.serialize("orders", headers, null);

        assertThat(bytes).isNull();
        assertThat(headers.lastHeader(WireFormat.HEADER)).isNull();
    }

    @Test
    @DisplayName("Given JSON header and PROTOBUF fallback, when deserializing JSON bytes, should read as JSON and return equal order")
    void given_jsonHeaderAndFallbackProtobuf_when_deserializing_should_readJsonAndReturnEqualOrder() {
        // Serialize as JSON
        ProtoSerializer<Order> jsonSerializer = new ProtoSerializer<>(json, WireFormat.JSON);
        RecordHeaders headers = new RecordHeaders();
        byte[] jsonBytes = jsonSerializer.serialize("orders", headers, testOrder);

        // Deserialize with PROTOBUF fallback but JSON header should override
        ProtoDeserializer<Order> deserializer = new ProtoDeserializer<>(
                json, WireFormat.PROTOBUF, Order.getDefaultInstance());
        Order deserialized = deserializer.deserialize("orders", headers, jsonBytes);

        assertThat(deserialized).isEqualTo(testOrder);
    }

    @Test
    @DisplayName("Given a JSON record with no content-type header, when deserializing with a JSON fallback, should read it as JSON")
    void given_noHeaderAndJsonFallback_when_deserializing_should_readAsJson() {
        byte[] jsonBytes = json.printUtf8(testOrder);
        ProtoDeserializer<Order> deserializer =
                new ProtoDeserializer<>(json, WireFormat.JSON, Order.getDefaultInstance());

        assertThat(deserializer.deserialize("orders", new RecordHeaders(), jsonBytes)).isEqualTo(testOrder);
        assertThat(deserializer.deserialize("orders", jsonBytes)).isEqualTo(testOrder);
    }

    @Test
    @DisplayName("Given unknown content-type header value, when deserializing, should fall back to fallback format")
    void given_unknownContentTypeHeader_when_deserializing_should_useFallback() {
        // Serialize as PROTOBUF (the fallback)
        ProtoSerializer<Order> serializer = new ProtoSerializer<>(json, WireFormat.PROTOBUF);
        byte[] protoBytes = serializer.serialize("orders", testOrder);

        // Create headers with unknown content-type
        RecordHeaders headers = new RecordHeaders();
        headers.add(WireFormat.HEADER, "text/plain".getBytes(StandardCharsets.UTF_8));

        ProtoDeserializer<Order> deserializer = new ProtoDeserializer<>(
                json, WireFormat.PROTOBUF, Order.getDefaultInstance());
        Order deserialized = deserializer.deserialize("orders", headers, protoBytes);

        assertThat(deserialized).isEqualTo(testOrder);
    }

    @Test
    @DisplayName("Given malformed JSON bytes, when deserializing, should throw SerializationException with message containing type name and topic")
    void given_malformedJsonBytes_when_deserializing_should_throwSerializationExceptionWithContext() {
        RecordHeaders headers = new RecordHeaders();
        headers.add(WireFormat.HEADER, "application/json".getBytes(StandardCharsets.UTF_8));

        byte[] malformedJson = "{\"placedOn\":\"22/09/2026\"}".getBytes(StandardCharsets.UTF_8);

        ProtoDeserializer<Order> deserializer = new ProtoDeserializer<>(
                json, WireFormat.JSON, Order.getDefaultInstance());

        assertThatThrownBy(() -> deserializer.deserialize("orders", headers, malformedJson))
                .isInstanceOf(SerializationException.class)
                .hasMessageContaining("acme.shop.Order")
                .hasMessageContaining("orders")
                .hasMessageContaining("JSON");
    }

    @Test
    @DisplayName("Given malformed protobuf bytes, when deserializing, should throw SerializationException")
    void given_malformedProtobufBytes_when_deserializing_should_throwSerializationException() {
        byte[] malformedProto = new byte[]{(byte) 0xFF, (byte) 0xFF};

        ProtoDeserializer<Order> deserializer = new ProtoDeserializer<>(
                json, WireFormat.PROTOBUF, Order.getDefaultInstance());

        assertThatThrownBy(() -> deserializer.deserialize("orders", malformedProto))
                .isInstanceOf(SerializationException.class);
    }

    @Test
    @DisplayName("Given null data, when deserializing, should return null")
    void given_null_when_deserializing_should_returnNull() {
        ProtoDeserializer<Order> deserializer = new ProtoDeserializer<>(
                json, WireFormat.PROTOBUF, Order.getDefaultInstance());

        Order deserialized = deserializer.deserialize("orders", null);

        assertThat(deserialized).isNull();
    }

    @Test
    @DisplayName("Given protobuf record, when bridging to JSON, should return same JSON and stamp application/json header")
    void given_protobufRecord_when_bridgingToJson_should_returnSameJsonAndStampHeader() {
        // Get the protobuf bytes
        byte[] protoBytes = testOrder.toByteArray();

        // Bridge to JSON
        JsonBridge bridge = new JsonBridge(json, Order.getDescriptor());
        RecordHeaders headers = new RecordHeaders();
        headers.add(WireFormat.HEADER, "application/x-protobuf".getBytes(StandardCharsets.UTF_8));

        byte[] jsonBytes = bridge.toJson(protoBytes, headers);

        // Verify the JSON is the same as directly printing with ProtoJson
        String bridgedJson = new String(jsonBytes, StandardCharsets.UTF_8);
        String directJson = new String(json.printUtf8(testOrder), StandardCharsets.UTF_8);

        assertThat(bridgedJson).isEqualTo(directJson);
        assertThat(new String(headers.lastHeader(WireFormat.HEADER).value(), StandardCharsets.UTF_8))
                .isEqualTo("application/json");
    }
}
