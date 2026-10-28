package dev.appiaworks.shiguchi.cursus;

import com.google.protobuf.Any;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import com.google.protobuf.util.JsonFormat;
import dev.appiaworks.shiguchi.proto.cursus.PublishRequest;
import dev.appiaworks.shiguchi.proto.cursus.PushProto;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import dev.appiaworks.shiguchi.serde.ProtoJsonText;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.nio.charset.StandardCharsets;

/**
 * Between the engine's {@link PushEvent} and the {@code shiguchi.cursus.v1} messages. Every event
 * holds JSON; a {@code json} payload is printed as it is, a {@code message} payload (an {@code
 * Any}) is rendered to its proto JSON, with {@code @type}, when one of the gateway's configured
 * services declares the type, and the message itself travels with the event for gRPC subscribers.
 */
final class PushEventProtos {

    private static final JsonFormat.Printer PRINTER = JsonFormat.printer().omittingInsignificantWhitespace();

    /** The push protos themselves, so a publisher may pack one of these too. */
    private static final JsonFormat.TypeRegistry OWN = JsonFormat.TypeRegistry.newBuilder()
            .add(PushProto.getDescriptor().getMessageTypes())
            .build();

    static final Metadata.Key<String> CLOSE_REASON =
            Metadata.Key.of("push-close-reason", Metadata.ASCII_STRING_MARSHALLER);

    private PushEventProtos() {}

    /** What a publish carries: the JSON every subscriber can read, and the typed message when there was one. */
    record Payload(byte[] json, String typeUrl, byte[] proto) {}

    /** The payload of a publish; refuses a missing payload and an unknown type. */
    static Payload payload(PublishRequest request, GrpcRouteRegistry registry) {
        switch (request.getPayloadCase()) {
            case JSON -> {
                return new Payload(PushJson.toJson(request.getJson()), null, null);
            }
            case MESSAGE -> {
                Any any = request.getMessage();
                return new Payload(
                        render(any, registry), any.getTypeUrl(), any.getValue().toByteArray());
            }
            default -> throw PushException.badRequest("a payload is required: json or message");
        }
    }

    /** The payload of a {@code Send}; refuses a missing payload and an unknown type. */
    static Payload payload(dev.appiaworks.shiguchi.proto.cursus.SendRequest request, GrpcRouteRegistry registry) {
        switch (request.getPayloadCase()) {
            case JSON -> {
                return new Payload(PushJson.toJson(request.getJson()), null, null);
            }
            case MESSAGE -> {
                Any any = request.getMessage();
                return new Payload(
                        render(any, registry), any.getTypeUrl(), any.getValue().toByteArray());
            }
            default -> throw PushException.badRequest("a payload is required: json or message");
        }
    }

    /** The payload of an event on the peer channel (what {@link #toProto} wrote). */
    static Payload payload(dev.appiaworks.shiguchi.proto.cursus.PushEvent event, GrpcRouteRegistry registry) {
        switch (event.getPayloadCase()) {
            case JSON -> {
                return new Payload(PushJson.toJson(event.getJson()), null, null);
            }
            case MESSAGE -> {
                Any any = event.getMessage();
                return new Payload(
                        render(any, registry), any.getTypeUrl(), any.getValue().toByteArray());
            }
            default -> throw PushException.badRequest("the event has no payload");
        }
    }

    private static byte[] render(Any any, GrpcRouteRegistry registry) {
        String typeUrl = any.getTypeUrl();
        String typeName = typeUrl.substring(typeUrl.lastIndexOf('/') + 1);
        if (typeName.isEmpty()) throw PushException.badRequest("message payload has no type URL");
        JsonFormat.TypeRegistry found = null;
        if (registry != null) {
            for (ServiceEndpointContext ctx : registry.allContexts()) {
                JsonFormat.TypeRegistry types = ctx.getTypeRegistry();
                if (types != null && types.find(typeName) != null) {
                    found = types;
                    break;
                }
            }
        }
        if (found == null && OWN.find(typeName) != null) found = OWN;
        if (found == null) {
            throw PushException.badRequest(
                    "message payload type '" + typeName + "' is not a type of any configured service");
        }
        Descriptor type = found.find(typeName);
        try {
            // Parse first, so a payload that is not a valid message of its type is refused here
            // rather than failing at every subscriber.
            DynamicMessage.parseFrom(type, any.getValue());
            return ProtoJsonText.printUtf8(PRINTER.usingTypeRegistry(found), any);
        } catch (InvalidProtocolBufferException e) {
            throw PushException.badRequest("message payload is not a valid " + typeName + ": " + e.getMessage());
        }
    }

    /**
     * The engine's event as the wire message: the typed message as it was published when there
     * was one, else its JSON as a {@code google.protobuf.Value}.
     */
    static dev.appiaworks.shiguchi.proto.cursus.PushEvent toProto(PushEvent e) {
        var out = dev.appiaworks.shiguchi.proto.cursus.PushEvent.newBuilder()
                .setTopic(e.topic().toString())
                .putAllAttributes(e.attributes())
                .setTime(Timestamp.newBuilder()
                        .setSeconds(e.time().getEpochSecond())
                        .setNanos(e.time().getNano()));
        if (e.typed()) {
            out.setMessage(Any.newBuilder()
                    .setTypeUrl(e.typeUrl())
                    .setValue(com.google.protobuf.ByteString.copyFrom(e.proto())));
        } else {
            Value json;
            try {
                json = PushJson.toValue(e.data());
            } catch (java.io.IOException ex) {
                // The publisher's JSON was validated when accepted; a stored payload that no longer
                // parses is delivered as a string rather than lost.
                json = Value.newBuilder()
                        .setStringValue(new String(e.data(), StandardCharsets.UTF_8))
                        .build();
            }
            out.setJson(json);
        }
        if (e.id() != null) out.setId(e.id());
        if (e.key() != null) out.setKey(e.key());
        return out.build();
    }

    /** The gRPC status of a refusal, by the HTTP status the REST surface would answer. */
    static StatusRuntimeException status(PushException e) {
        return code(e.status()).withDescription(e.getMessage()).asRuntimeException();
    }

    private static Status code(org.springframework.http.HttpStatus status) {
        return switch (status) {
            case UNAUTHORIZED -> Status.UNAUTHENTICATED;
            case FORBIDDEN -> Status.PERMISSION_DENIED;
            case NOT_FOUND -> Status.NOT_FOUND;
            case PAYLOAD_TOO_LARGE -> Status.RESOURCE_EXHAUSTED;
            default -> Status.INVALID_ARGUMENT;
        };
    }

    /** The stream's end when the gateway closes it: UNAVAILABLE, the reason in a trailer. */
    static StatusRuntimeException closed(String reason) {
        Metadata trailers = new Metadata();
        trailers.put(CLOSE_REASON, reason);
        return Status.UNAVAILABLE.withDescription("closed: " + reason).asRuntimeException(trailers);
    }

    static StatusRuntimeException overflow() {
        Metadata trailers = new Metadata();
        trailers.put(CLOSE_REASON, "queue_full");
        return Status.RESOURCE_EXHAUSTED
                .withDescription("queue_full: the subscriber stopped reading; reconnect and resume with after")
                .asRuntimeException(trailers);
    }
}
