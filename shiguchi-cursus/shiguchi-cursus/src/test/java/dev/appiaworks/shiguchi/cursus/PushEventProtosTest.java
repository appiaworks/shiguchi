package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import com.google.protobuf.util.JsonFormat;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.proto.cursus.PublishRequest;
import dev.appiaworks.shiguchi.proto.cursus.PublishedEvent;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;

@DisplayName("Push protobuf mapping")
class PushEventProtosTest {

    // ---- payload() tests ----

    @Test
    @DisplayName("publish with a JSON object payload renders it as compact JSON bytes")
    void given_json_object_payload_when_rendering_should_return_compact_json() throws InvalidProtocolBufferException {
        PublishRequest request =
                PublishRequest.newBuilder().setJson(jsonValue("{\"n\":1}")).build();

        byte[] bytes = PushEventProtos.payload(request, null).json();

        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("{\"n\":1}");
    }

    @Test
    @DisplayName("publish with a JSON string payload renders it as a quoted JSON string")
    void given_json_string_payload_when_rendering_should_return_quoted_string() {
        PublishRequest request = PublishRequest.newBuilder()
                .setJson(Value.newBuilder().setStringValue("hi"))
                .build();

        byte[] bytes = PushEventProtos.payload(request, null).json();

        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("\"hi\"");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("undeliverablePayloads")
    @DisplayName("publish with a payload that cannot be delivered is refused as a bad request")
    void given_undeliverable_payload_when_rendering_should_refuse_bad_request(
            String scenario, PublishRequest request, String messageFragment) {
        assertThatThrownBy(() -> PushEventProtos.payload(request, null).json())
                .isInstanceOfSatisfying(PushException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).contains(messageFragment);
                });
    }

    static Stream<Arguments> undeliverablePayloads() {
        PublishRequest missingPayload =
                PublishRequest.newBuilder().addTopics("a:b").build();
        PublishRequest unknownType = PublishRequest.newBuilder()
                .setMessage(Any.newBuilder()
                        .setTypeUrl("type.googleapis.com/foo.Bar")
                        .build())
                .build();
        PublishRequest invalidBytes = PublishRequest.newBuilder()
                .setMessage(Any.newBuilder()
                        .setTypeUrl("type.googleapis.com/shiguchi.cursus.v1.PublishedEvent")
                        .setValue(ByteString.copyFrom(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF}))
                        .build())
                .build();
        return Stream.of(
                Arguments.of("missing payload", missingPayload, "payload is required"),
                Arguments.of("unknown message type", unknownType, "not a type of any configured service"),
                Arguments.of("bytes that are not a valid message", invalidBytes, "not a valid"));
    }

    @Test
    @DisplayName("publish with a push message payload renders it with its @type using the push protos")
    void given_push_message_payload_when_rendering_without_registry_should_include_type_url() {
        PublishedEvent event =
                PublishedEvent.newBuilder().setTopic("a:b").setId("1").build();
        PublishRequest request =
                PublishRequest.newBuilder().setMessage(Any.pack(event)).build();

        String json = new String(PushEventProtos.payload(request, null).json(), StandardCharsets.UTF_8);

        assertThat(json)
                .contains("\"@type\":\"type.googleapis.com/shiguchi.cursus.v1.PublishedEvent\"")
                .contains("\"topic\":\"a:b\"");
    }

    @Test
    @DisplayName("publish with a message payload of a configured service type renders it through that service")
    void given_message_of_configured_service_type_when_rendering_should_render_well_known_type() {
        ServiceDefinition definition = new ServiceDefinition();
        definition.setName("clock");
        ServiceEndpointContext context = new ServiceEndpointContext(definition, null);
        context.setTypeRegistry(JsonFormat.TypeRegistry.newBuilder()
                .add(Timestamp.getDescriptor())
                .build());
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        registry.registerContext(context);
        PublishRequest request = PublishRequest.newBuilder()
                .setMessage(Any.pack(Timestamp.newBuilder().setSeconds(5).build()))
                .build();

        String json = new String(PushEventProtos.payload(request, registry).json(), StandardCharsets.UTF_8);

        assertThat(json)
                .contains("\"@type\":\"type.googleapis.com/google.protobuf.Timestamp\"")
                .contains("1970-01-01T00:00:05Z");
    }

    // ---- toProto() tests ----

    @Test
    @DisplayName("an engine event maps topic, id, JSON, attributes, key and time onto the wire message")
    void given_engine_event_when_converting_should_map_every_field() {
        PushEvent event = new PushEvent(
                Topic.parse("a:b"),
                "7",
                "{\"x\":[1,2]}".getBytes(StandardCharsets.UTF_8),
                Map.of("k", "v"),
                "key1",
                Instant.ofEpochSecond(10, 500));

        dev.appiaworks.shiguchi.proto.cursus.PushEvent wire = PushEventProtos.toProto(event);

        assertThat(wire.getTopic()).isEqualTo("a:b");
        assertThat(wire.getId()).isEqualTo("7");
        assertThat(wire.getPayloadCase()).isEqualTo(dev.appiaworks.shiguchi.proto.cursus.PushEvent.PayloadCase.JSON);
        var numbers = wire.getJson()
                .getStructValue()
                .getFieldsMap()
                .get("x")
                .getListValue()
                .getValuesList();
        assertThat(numbers).hasSize(2);
        assertThat(numbers.get(0).getNumberValue()).isEqualTo(1.0);
        assertThat(numbers.get(1).getNumberValue()).isEqualTo(2.0);
        assertThat(wire.getAttributesMap()).containsEntry("k", "v");
        assertThat(wire.getKey()).isEqualTo("key1");
        assertThat(wire.getTime().getSeconds()).isEqualTo(10);
        assertThat(wire.getTime().getNanos()).isEqualTo(500);
    }

    @Test
    @DisplayName("an engine event whose stored data is not valid JSON is delivered as a string value")
    void given_event_with_data_that_is_not_json_when_converting_should_deliver_string_value() {
        PushEvent event = new PushEvent(
                Topic.parse("a:b"), "1", "not json".getBytes(StandardCharsets.UTF_8), null, null, Instant.EPOCH);

        dev.appiaworks.shiguchi.proto.cursus.PushEvent wire = PushEventProtos.toProto(event);

        assertThat(wire.getJson().getStringValue()).isEqualTo("not json");
    }

    @Test
    @DisplayName("an engine event with no id or key is converted to empty id and key strings")
    void given_event_without_id_or_key_when_converting_should_leave_them_empty() {
        PushEvent event = new PushEvent(
                Topic.parse("a:b"), null, "1".getBytes(StandardCharsets.UTF_8), null, null, Instant.EPOCH);

        dev.appiaworks.shiguchi.proto.cursus.PushEvent wire = PushEventProtos.toProto(event);

        assertThat(wire.getId()).isEmpty();
        assertThat(wire.getKey()).isEmpty();
    }

    // ---- status() and stream closure tests ----

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusalStatuses")
    @DisplayName("a push refusal maps to the gRPC status of its HTTP status and keeps the message")
    void given_push_refusal_when_mapping_should_use_grpc_status_and_keep_message(
            String scenario, PushException refusal, Status.Code expected) {
        StatusRuntimeException mapped = PushEventProtos.status(refusal);

        assertThat(mapped.getStatus().getCode()).isEqualTo(expected);
        assertThat(mapped.getStatus().getDescription()).isEqualTo(refusal.getMessage());
    }

    static Stream<Arguments> refusalStatuses() {
        return Stream.of(
                Arguments.of("unauthenticated", PushException.unauthenticated("x"), Status.Code.UNAUTHENTICATED),
                Arguments.of("forbidden", PushException.forbidden("x"), Status.Code.PERMISSION_DENIED),
                Arguments.of(
                        "unknown namespace", PushException.unknownNamespace(Topic.parse("n:x")), Status.Code.NOT_FOUND),
                Arguments.of("too large", PushException.tooLarge(10, 5), Status.Code.RESOURCE_EXHAUSTED),
                Arguments.of("bad request", PushException.badRequest("x"), Status.Code.INVALID_ARGUMENT));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("closures")
    @DisplayName("a stream closed by the gateway ends with a status whose trailer names the reason")
    void given_gateway_closure_when_building_status_should_carry_reason_trailer(
            String scenario, Supplier<StatusRuntimeException> closure, Status.Code code, String reason) {
        StatusRuntimeException ended = closure.get();

        assertThat(ended.getStatus().getCode()).isEqualTo(code);
        assertThat(ended.getTrailers().get(PushEventProtos.CLOSE_REASON)).isEqualTo(reason);
    }

    static Stream<Arguments> closures() {
        return Stream.of(
                Arguments.of(
                        "admin close",
                        (Supplier<StatusRuntimeException>) () -> PushEventProtos.closed("admin"),
                        Status.Code.UNAVAILABLE,
                        "admin"),
                Arguments.of(
                        "overflow",
                        (Supplier<StatusRuntimeException>) PushEventProtos::overflow,
                        Status.Code.RESOURCE_EXHAUSTED,
                        "queue_full"));
    }

    // ---- typed payload tests ----

    @Test
    @DisplayName("publish with a typed message payload keeps its type URL and serialized bytes beside the JSON")
    void given_typed_message_payload_when_rendering_should_keep_type_url_and_bytes() {
        PublishedEvent event =
                PublishedEvent.newBuilder().setTopic("a:b").setId("1").build();
        PublishRequest request =
                PublishRequest.newBuilder().setMessage(Any.pack(event)).build();

        PushEventProtos.Payload payload = PushEventProtos.payload(request, null);

        assertThat(payload.typeUrl()).isEqualTo("type.googleapis.com/shiguchi.cursus.v1.PublishedEvent");
        assertThat(payload.proto()).isEqualTo(event.toByteArray());
        assertThat(new String(payload.json(), StandardCharsets.UTF_8)).contains("\"@type\"");
    }

    @Test
    @DisplayName("publish with a JSON payload carries no type URL and no typed bytes")
    void given_json_payload_when_rendering_should_have_no_type_url_or_bytes() throws InvalidProtocolBufferException {
        PublishRequest request =
                PublishRequest.newBuilder().setJson(jsonValue("{\"n\":1}")).build();

        PushEventProtos.Payload payload = PushEventProtos.payload(request, null);

        assertThat(payload.typeUrl()).isNull();
        assertThat(payload.proto()).isNull();
    }

    @Test
    @DisplayName("a typed engine event converts to a wire message with its type URL and no JSON")
    void given_typed_engine_event_when_converting_should_carry_message_and_no_json() {
        byte[] proto =
                PublishedEvent.newBuilder().setTopic("a:b").setId("3").build().toByteArray();
        PushEvent event = new PushEvent(
                Topic.parse("a:b"),
                "3",
                "{}".getBytes(StandardCharsets.UTF_8),
                null,
                null,
                Instant.EPOCH,
                "type.googleapis.com/shiguchi.cursus.v1.PublishedEvent",
                proto);

        dev.appiaworks.shiguchi.proto.cursus.PushEvent wire = PushEventProtos.toProto(event);

        assertThat(wire.getPayloadCase()).isEqualTo(dev.appiaworks.shiguchi.proto.cursus.PushEvent.PayloadCase.MESSAGE);
        assertThat(wire.getMessage().getTypeUrl()).isEqualTo("type.googleapis.com/shiguchi.cursus.v1.PublishedEvent");
        assertThat(wire.hasJson()).isFalse();
    }

    @Test
    @DisplayName("a JSON-only engine event converts to a wire message with a JSON payload")
    void given_json_only_engine_event_when_converting_should_carry_json_payload() {
        PushEvent event = new PushEvent(
                Topic.parse("a:b"), "4", "{}".getBytes(StandardCharsets.UTF_8), null, null, Instant.EPOCH);

        dev.appiaworks.shiguchi.proto.cursus.PushEvent wire = PushEventProtos.toProto(event);

        assertThat(wire.getPayloadCase()).isEqualTo(dev.appiaworks.shiguchi.proto.cursus.PushEvent.PayloadCase.JSON);
    }

    private static Value jsonValue(String json) throws InvalidProtocolBufferException {
        Value.Builder value = Value.newBuilder();
        JsonFormat.parser().merge(json, value);
        return value.build();
    }
}
