package dev.appiaworks.shiguchi.graphql.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import dev.appiaworks.shiguchi.cursus.PushEvent;
import dev.appiaworks.shiguchi.cursus.Topic;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import dev.appiaworks.shiguchi.serde.SerdeSelector;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * BDD-style unit tests for PushGraphQlDataFetcher's envelope: what a subscriber sees for one push
 * event, for an untyped namespace (JSON payload) and for a typed one (the generated message).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PushGraphQlDataFetcher")
class PushGraphQlDataFetcherTest {

    private static final Instant TIME = Instant.parse("2026-10-09T10:00:00Z");
    private static final String FULL_NAME = "ev.OrderEvent";

    @Mock
    private SerdeSelector serde;

    @Mock
    private ServiceEndpointContext context;

    /** A proto3 file with {@code ev.OrderEvent { string order_id = 1; string status = 2; }}. */
    private static FileDescriptor orderEventFile() throws Exception {
        FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                .setName("ev.proto")
                .setPackage("ev")
                .setSyntax("proto3")
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("OrderEvent")
                        .addField(stringField("order_id", 1))
                        .addField(stringField("status", 2)))
                .build();
        return FileDescriptor.buildFrom(proto, new FileDescriptor[0]);
    }

    private static FieldDescriptorProto.Builder stringField(String name, int number) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(FieldDescriptorProto.Type.TYPE_STRING)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
    }

    private static PushGraphQlContributor.Field untypedField() {
        return new PushGraphQlContributor.Field("audit", null, "auditEvents", "AuditEventsEnvelope", null, null);
    }

    private PushGraphQlContributor.Field typedField(Descriptor payload) {
        return new PushGraphQlContributor.Field(
                "orders", null, "ordersEvents", "OrdersEventsEnvelope", payload, context);
    }

    private static PushEvent jsonEvent(String json, Map<String, String> attributes, String key) {
        return new PushEvent(
                Topic.parse("audit:created"), "e-1", json.getBytes(StandardCharsets.UTF_8), attributes, key, TIME);
    }

    @Test
    @DisplayName(
            "GIVEN an untyped namespace and a JSON event WHEN the envelope is built SHOULD parse the payload as JSON")
    void given_untypedNamespaceAndJsonEvent_when_envelopeBuilt_should_parsePayloadAsJson() throws Exception {
        PushGraphQlDataFetcher fetcher = new PushGraphQlDataFetcher(untypedField(), null, serde);

        Map<String, Object> envelope = fetcher.envelope(jsonEvent("{\"price\":1}", Map.of(), null));

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) envelope.get("payload");
        assertThat(payload).containsEntry("price", 1);
    }

    @Test
    @DisplayName(
            "GIVEN an event with attributes WHEN the envelope is built SHOULD list each attribute as a key and value map")
    void given_eventWithAttributes_when_envelopeBuilt_should_listKeyValueMaps() throws Exception {
        PushGraphQlDataFetcher fetcher = new PushGraphQlDataFetcher(untypedField(), null, serde);

        // PushEvent copies its attributes into an unordered map, so the order is not asserted.
        Map<String, Object> envelope = fetcher.envelope(jsonEvent("{}", Map.of("region", "eu", "tier", "gold"), null));

        @SuppressWarnings("unchecked")
        List<Object> attributes = (List<Object>) envelope.get("attributes");
        assertThat(attributes)
                .containsExactlyInAnyOrder(
                        Map.of("key", "region", "value", "eu"), Map.of("key", "tier", "value", "gold"));
    }

    @Test
    @DisplayName(
            "GIVEN an event without a key WHEN the envelope is built SHOULD render the time as ISO text and the key as null")
    void given_eventWithoutKey_when_envelopeBuilt_should_renderIsoTimeAndNullKey() throws Exception {
        PushGraphQlDataFetcher fetcher = new PushGraphQlDataFetcher(untypedField(), null, serde);

        Map<String, Object> envelope = fetcher.envelope(jsonEvent("{}", Map.of(), null));

        assertThat(envelope.get("time")).isEqualTo("2026-10-09T10:00:00Z");
        assertThat(envelope.get("key")).isNull();
        assertThat(envelope.get("id")).isEqualTo("e-1");
        assertThat(envelope.get("topic")).isEqualTo("audit:created");
    }

    @Test
    @DisplayName(
            "GIVEN a typed namespace and a typed event WHEN the envelope is built SHOULD serialize the message as sent")
    void given_typedNamespaceAndTypedEvent_when_envelopeBuilt_should_serializeMessageAsSent() throws Exception {
        Descriptor descriptor = orderEventFile().findMessageTypeByName("OrderEvent");
        DynamicMessage sent = DynamicMessage.newBuilder(descriptor)
                .setField(descriptor.findFieldByName("order_id"), "42")
                .build();
        PushEvent event = new PushEvent(
                Topic.parse("orders:created"),
                "e-2",
                "{}".getBytes(StandardCharsets.UTF_8),
                Map.of(),
                "k",
                TIME,
                "type.googleapis.com/" + FULL_NAME,
                sent.toByteArray());
        when(serde.serializeToMapProjected(any(DynamicMessage.class), eq(descriptor), eq(context), any()))
                .thenReturn(Map.of("orderId", "42"));
        PushGraphQlDataFetcher fetcher = new PushGraphQlDataFetcher(typedField(descriptor), null, serde);

        Map<String, Object> envelope = fetcher.envelope(event);

        ArgumentCaptor<DynamicMessage> captor = ArgumentCaptor.forClass(DynamicMessage.class);
        verify(serde).serializeToMapProjected(captor.capture(), eq(descriptor), eq(context), any());
        assertThat(captor.getValue().getField(descriptor.findFieldByName("order_id")))
                .isEqualTo("42");
        assertThat(envelope.get("payload")).isEqualTo(Map.of("orderId", "42"));
    }

    @Test
    @DisplayName(
            "GIVEN a typed namespace and a JSON-only event with unknown fields WHEN the envelope is built SHOULD parse the message and ignore the unknown fields")
    void given_typedNamespaceAndJsonOnlyEvent_when_envelopeBuilt_should_parseMessageIgnoringUnknownFields()
            throws Exception {
        Descriptor descriptor = orderEventFile().findMessageTypeByName("OrderEvent");
        FieldDescriptor orderId = descriptor.findFieldByName("order_id");
        FieldDescriptor status = descriptor.findFieldByName("status");
        when(serde.serializeToMapProjected(any(DynamicMessage.class), eq(descriptor), eq(context), any()))
                .thenReturn(Map.of("orderId", "7"));
        PushGraphQlDataFetcher fetcher = new PushGraphQlDataFetcher(typedField(descriptor), null, serde);

        Map<String, Object> envelope =
                fetcher.envelope(jsonEvent("{\"orderId\":\"7\",\"status\":\"NEW\",\"unknown\":1}", Map.of(), null));

        ArgumentCaptor<DynamicMessage> captor = ArgumentCaptor.forClass(DynamicMessage.class);
        verify(serde).serializeToMapProjected(captor.capture(), eq(descriptor), eq(context), any());
        assertThat(captor.getValue().getField(orderId)).isEqualTo("7");
        assertThat(captor.getValue().getField(status)).isEqualTo("NEW");
        assertThat(envelope.get("payload")).isEqualTo(Map.of("orderId", "7"));
    }

    @Test
    @DisplayName("GIVEN an untyped namespace and a malformed JSON event WHEN the envelope is built SHOULD fail")
    void given_untypedNamespaceAndMalformedJson_when_envelopeBuilt_should_fail() {
        PushGraphQlDataFetcher fetcher = new PushGraphQlDataFetcher(untypedField(), null, serde);
        PushEvent event = jsonEvent("not json", Map.of(), null);

        assertThatThrownBy(() -> fetcher.envelope(event)).isInstanceOf(JsonProcessingException.class);
    }
}
