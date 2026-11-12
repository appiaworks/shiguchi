package dev.appiaworks.shiguchi.gateway;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import com.google.rpc.BadRequest;
import com.google.rpc.ErrorDetailsProto;
import com.google.rpc.ErrorInfo;
import com.google.rpc.RetryInfo;
import dev.appiaworks.protobuf.extras.serde.JsonFactories;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.protobuf.StatusProto;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The rich error details a gRPC backend attached to a failed call: the {@code google.rpc.Status}
 * carried in the {@code grpc-status-details-bin} trailer, decoded once and offered both as typed
 * messages ({@link #badRequest()}, {@link #retryInfo()}, {@link #errorInfo()}, {@link #find}) and as
 * proto3 JSON ({@link #asJson()}) ready to embed in an error body.
 *
 * <p>Each detail is a {@code google.protobuf.Any}. The standard {@code google.rpc} types are always
 * resolvable; a backend-specific detail type resolves through the service's discovered type
 * registry when the gateway knows it, and otherwise degrades to
 * {@code {"@type": "...", "value": "<base64>"}} rather than being dropped.
 *
 * <p>Immutable. {@link #none()} is the empty instance used when a failure carries no trailers
 * (gateway-side rejections, access denied, HTTP backends).
 */
public final class GatewayErrorDetails {

    // Jackson 3 sorts properties alphabetically and rejects trailing tokens by default; Jackson 2 did
    // neither, so both stay off. The factory carries Jackson 2's read limits and number handling.
    private static final ObjectMapper MAPPER = JsonMapper.builder(JsonFactories.create())
            .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    /** All {@code google.rpc.*} error detail messages, so the standard details always print. */
    private static final JsonFormat.TypeRegistry STANDARD_TYPES = JsonFormat.TypeRegistry.newBuilder()
            .add(ErrorDetailsProto.getDescriptor().getMessageTypes())
            .add(com.google.rpc.StatusProto.getDescriptor().getMessageTypes())
            .build();

    private static final GatewayErrorDetails NONE = new GatewayErrorDetails(List.of(), null);

    private final List<Any> details;
    private final JsonFormat.TypeRegistry serviceTypes;
    private volatile List<Map<String, Object>> json;

    private GatewayErrorDetails(List<Any> details, JsonFormat.TypeRegistry serviceTypes) {
        this.details = details;
        this.serviceTypes = serviceTypes;
    }

    /** No details at all; {@link #asJson()} is empty. */
    public static GatewayErrorDetails none() {
        return NONE;
    }

    /**
     * Decodes the details a backend attached to {@code status}.
     *
     * @param status       the failed call's status
     * @param trailers     the call's trailers, or {@code null} when the failure had none
     * @param serviceTypes the discovered type registry of the service that failed, used to print
     *                     backend-specific detail types; {@code null} when unknown
     */
    public static GatewayErrorDetails from(Status status, Metadata trailers, JsonFormat.TypeRegistry serviceTypes) {
        if (trailers == null) return NONE;
        com.google.rpc.Status rich = StatusProto.fromStatusAndTrailers(status, trailers);
        if (rich == null || rich.getDetailsCount() == 0) return NONE;
        return new GatewayErrorDetails(List.copyOf(rich.getDetailsList()), serviceTypes);
    }

    /** Decodes the details attached to a failed call's exception ({@link Status#trailersFromThrowable}). */
    public static GatewayErrorDetails from(Throwable failure, JsonFormat.TypeRegistry serviceTypes) {
        return from(Status.fromThrowable(failure), Status.trailersFromThrowable(failure), serviceTypes);
    }

    /** Wraps already-decoded details, for callers that build a status themselves. */
    public static GatewayErrorDetails of(List<Any> details) {
        return details == null || details.isEmpty() ? NONE : new GatewayErrorDetails(List.copyOf(details), null);
    }

    public boolean isEmpty() {
        return details.isEmpty();
    }

    /** The raw {@code Any} details, in the order the backend attached them. */
    public List<Any> raw() {
        return details;
    }

    /** The first detail of the given type, unpacked. */
    public <T extends Message> Optional<T> find(Class<T> type) {
        for (Any any : details) {
            if (any.is(type)) {
                try {
                    return Optional.of(any.unpack(type));
                } catch (InvalidProtocolBufferException e) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    public Optional<BadRequest> badRequest() {
        return find(BadRequest.class);
    }

    public Optional<ErrorInfo> errorInfo() {
        return find(ErrorInfo.class);
    }

    public Optional<RetryInfo> retryInfo() {
        return find(RetryInfo.class);
    }

    /** The {@code RetryInfo.retry_delay}, when the backend sent one. */
    public Optional<Duration> retryDelay() {
        return retryInfo()
                .filter(RetryInfo::hasRetryDelay)
                .map(r -> Duration.ofSeconds(
                        r.getRetryDelay().getSeconds(), r.getRetryDelay().getNanos()));
    }

    /** One entry of a {@code google.rpc.BadRequest}: a proto field path, why it failed, and a machine reason. */
    public record FieldViolation(String field, String description, String reason) {}

    /** The {@code BadRequest} field violations of every such detail, in order; empty when there are none. */
    public List<FieldViolation> fieldViolations() {
        List<FieldViolation> out = new ArrayList<>();
        for (Any any : details) {
            if (!any.is(BadRequest.class)) continue;
            try {
                for (BadRequest.FieldViolation v : any.unpack(BadRequest.class).getFieldViolationsList()) {
                    out.add(new FieldViolation(v.getField(), v.getDescription(), v.getReason()));
                }
            } catch (InvalidProtocolBufferException ignored) {
                // an undecodable BadRequest stays visible through asJson()
            }
        }
        return out;
    }

    /** {@link #asJson()} without the {@code BadRequest} details (already surfaced by {@link #fieldViolations()}). */
    public List<Map<String, Object>> asJsonExceptBadRequest() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> d : asJson()) {
            if (!"type.googleapis.com/google.rpc.BadRequest".equals(d.get("@type"))) out.add(d);
        }
        return out;
    }

    /**
     * Every detail as proto3 JSON (an object with an {@code @type} member), in order. Unknown
     * detail types come back as {@code {"@type": url, "value": base64}}. Computed once.
     */
    public List<Map<String, Object>> asJson() {
        List<Map<String, Object>> cached = json;
        if (cached == null) {
            List<Map<String, Object>> out = new ArrayList<>(details.size());
            for (Any any : details) out.add(toJson(any));
            cached = Collections.unmodifiableList(out);
            json = cached;
        }
        return cached;
    }

    private Map<String, Object> toJson(Any any) {
        String printed = print(any, serviceTypes);
        if (printed == null) printed = print(any, STANDARD_TYPES);
        if (printed != null) {
            try {
                return MAPPER.readValue(printed, MAP_TYPE);
            } catch (Exception ignored) {
                // fall through to the opaque shape
            }
        }
        Map<String, Object> opaque = new LinkedHashMap<>(2);
        opaque.put("@type", any.getTypeUrl());
        opaque.put("value", Base64.getEncoder().encodeToString(any.getValue().toByteArray()));
        return opaque;
    }

    private static String print(Any any, JsonFormat.TypeRegistry registry) {
        if (registry == null) return null;
        try {
            return JsonFormat.printer()
                    .usingTypeRegistry(registry)
                    .omittingInsignificantWhitespace()
                    .print(any);
        } catch (InvalidProtocolBufferException | IllegalArgumentException e) {
            return null;
        }
    }
}
