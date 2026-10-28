package dev.appiaworks.shiguchi.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import dev.appiaworks.protobuf.extras.serde.ProjectionNode;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.routing.HttpOperationBinding;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import dev.appiaworks.shiguchi.serde.SerdeSelector;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The {@link RouteDefinition.Transport#HTTP} counterpart of {@link ReactiveGrpcDispatcher} for the
 * GraphQL surface: turns the resolved field arguments into one HTTP call on a {@code
 * REST_DOWNSTREAM} backend and returns the response as the same projected {@code Map} the gRPC
 * path returns, so nothing above it knows which transport served the field.
 *
 * <p>Request: arguments arrive keyed by JSON name (what the SDL exposes) and are re-keyed to the
 * synthetic request message's proto field names, which are the backend's own property names.
 * Fields named in the {@link HttpOperationBinding} go into the path or the query string; when the
 * operation has a body, every remaining field is sent as JSON.
 *
 * <p>Response: the JSON body is parsed to a {@code Map}, wrapped into {@code items} / {@code value}
 * for array and scalar responses, bound to the synthetic response message (so numbers, enums and
 * nesting are normalised exactly as for a proto), and converted back with the selection
 * projection applied. A non-2xx status becomes a {@link StatusRuntimeException} with the gRPC
 * status the REST surface would have mapped it from, so error rendering stays uniform.
 *
 * <p>Media types: JSON by default. When the service's {@code upstream-content.preferred} names a
 * type an {@link UpstreamBodyCodec} bean supports, the operation declares that type in its OpenAPI
 * {@code content}, and the codec has a schema for the operation's component, the request is sent
 * and/or asked for in that encoding ({@code Accept} lists it first, JSON as fallback). The response
 * is always decoded by the {@code Content-Type} the backend actually returned, so a backend that
 * answers JSON to an Avro request still works.
 */
@Component
public class HttpUpstreamDispatcher {

    private static final Logger log = LoggerFactory.getLogger(HttpUpstreamDispatcher.class);
    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final SerdeSelector serdeSelector;
    private final GatewayProperties properties;
    private final WebClient webClient;
    private final List<UpstreamBodyCodec> codecs;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    public HttpUpstreamDispatcher(
            SerdeSelector serdeSelector,
            GatewayProperties properties,
            Optional<WebClient.Builder> webClientBuilder,
            ObjectProvider<UpstreamBodyCodec> codecs) {
        this(serdeSelector, properties, webClientBuilder, codecs.orderedStream().toList());
    }

    public HttpUpstreamDispatcher(
            SerdeSelector serdeSelector,
            GatewayProperties properties,
            Optional<WebClient.Builder> webClientBuilder,
            List<UpstreamBodyCodec> codecs) {
        this.serdeSelector = serdeSelector;
        this.properties = properties;
        this.webClient = webClientBuilder.orElseGet(WebClient::builder).build();
        this.codecs = codecs != null ? List.copyOf(codecs) : List.of();
    }

    /** JSON-only dispatcher, for callers that wire by hand. */
    public HttpUpstreamDispatcher(
            SerdeSelector serdeSelector, GatewayProperties properties, Optional<WebClient.Builder> webClientBuilder) {
        this(serdeSelector, properties, webClientBuilder, List.of());
    }

    public Mono<Map<String, Object>> dispatchToMap(
            RouteDefinition route,
            ServiceEndpointContext ctx,
            Map<String, Object> args,
            Metadata metadata,
            ProjectionNode projection) {
        return exchange(route, ctx, args, metadata)
                .map(ex -> bind(ex.body(), ex.method().getOutputType(), ctx, projection));
    }

    /**
     * One HTTP call, the response bound to the operation's synthetic response message. The gRPC
     * counterpart is {@link ReactiveGrpcDispatcher#dispatchMessage}; both give the merge and the
     * GraphQL surface the same {@link Message} whichever transport served it.
     */
    public Mono<Message> dispatchMessage(
            RouteDefinition route, ServiceEndpointContext ctx, Map<String, Object> args, Metadata metadata) {
        return exchange(route, ctx, args, metadata)
                .map(ex -> toMessage(ex.body(), ex.method().getOutputType(), ctx));
    }

    /**
     * The rows of a list operation as messages, for merge sources: an array response is split into
     * its {@code items}, so each element arrives as the gRPC path's server-streaming records do;
     * an object response is one message. A backend that ignores paging and sorting still works —
     * the merge reads such a source in full — and a non-2xx answer fails the stream with the same
     * {@link StatusRuntimeException} the GraphQL path would see.
     */
    public Flux<Message> dispatchMessages(
            RouteDefinition route, ServiceEndpointContext ctx, Map<String, Object> args, Metadata metadata) {
        return exchange(route, ctx, args, metadata).flatMapMany(ex -> {
            Descriptor output = ex.method().getOutputType();
            Message message = toMessage(ex.body(), output, ctx);
            FieldDescriptor items =
                    ex.binding().responseIsArray() ? output.findFieldByName(HttpOperationBinding.ARRAY_FIELD) : null;
            if (items == null || !items.isRepeated() || items.getType() != FieldDescriptor.Type.MESSAGE) {
                return Flux.just(message);
            }
            @SuppressWarnings("unchecked")
            List<Message> rows = (List<Message>) message.getField(items);
            return Flux.fromIterable(rows);
        });
    }

    /**
     * The message type one row of {@code method}'s response has: the element type of {@code items}
     * for an array response, else the response message itself. What a merge sorts and pages by.
     */
    public static Descriptor rowTypeOf(MethodDescriptor method, HttpOperationBinding binding) {
        Descriptor output = method.getOutputType();
        if (binding != null && binding.responseIsArray()) {
            FieldDescriptor items = output.findFieldByName(HttpOperationBinding.ARRAY_FIELD);
            if (items != null && items.isRepeated() && items.getType() == FieldDescriptor.Type.MESSAGE) {
                return items.getMessageType();
            }
        }
        return output;
    }

    /** The decoded, enum-translated response of one call, with what it was decoded against. */
    record Exchange(Map<String, Object> body, MethodDescriptor method, HttpOperationBinding binding) {}

    private Mono<Exchange> exchange(
            RouteDefinition route, ServiceEndpointContext ctx, Map<String, Object> args, Metadata metadata) {

        MethodDescriptor md;
        HttpOperationBinding binding;
        URI uri;
        Object body;
        Negotiation negotiation;
        try {
            md = ctx.lookupMethod(route.getFullServiceName(), route.getMethodName())
                    .orElseThrow(() -> new IllegalStateException(
                            "Method not found: " + route.getFullServiceName() + "/" + route.getMethodName()));
            binding = ctx.httpBindingFor(route.getMethodName())
                    .orElseThrow(() -> new IllegalStateException(
                            "No HTTP binding for " + route.getFullServiceName() + "/" + route.getMethodName()));
            // Arguments carry proto enum value names (what the SDL exposes); the backend wants its own.
            Map<String, Object> byProtoName = EnumValueTranslator.toBackend(
                    keyByProtoName(args, md.getInputType()), md.getInputType(), ctx.getEnumValueOriginals());
            uri = buildUri(route.getUpstreamUri(), binding, byProtoName);
            body = binding.hasBody() ? bodyOf(binding, byProtoName) : null;
            negotiation = negotiate(ctx.getConfig(), binding);
        } catch (Exception e) {
            return Mono.error(e);
        }

        final Duration timeout = route.getUpstreamTimeout() != null
                ? route.getUpstreamTimeout()
                : ctx.getConfig().effectiveHttpTimeout(null, properties.getClient());

        log.debug(
                "[HTTP dispatch→] {} {} body={} accept={} contentType={}",
                binding.httpMethod(),
                uri,
                body != null,
                negotiation.accept(),
                body != null ? negotiation.requestContentType() : "-");

        final Negotiation finalNegotiation = negotiation;
        WebClient.RequestBodySpec spec = webClient
                .method(HttpMethod.valueOf(binding.httpMethod()))
                .uri(uri)
                .headers(h -> {
                    h.set(HttpHeaders.ACCEPT, finalNegotiation.accept());
                    String auth = metadata != null ? metadata.get(AUTHORIZATION) : null;
                    if (auth != null && !auth.isBlank()) h.set(HttpHeaders.AUTHORIZATION, auth);
                });
        WebClient.RequestHeadersSpec<?> request;
        if (body == null) {
            request = spec;
        } else if (negotiation.requestCodec() != null) {
            byte[] encoded =
                    encodeBody(negotiation.requestCodec(), ctx.getConfig().getName(), binding, body);
            request = spec.contentType(MediaType.parseMediaType(negotiation.requestContentType()))
                    .bodyValue(encoded);
        } else {
            request = spec.contentType(MediaType.APPLICATION_JSON).bodyValue(toJson(body));
        }

        final MethodDescriptor finalMd = md;
        final HttpOperationBinding finalBinding = binding;
        final String serviceName = ctx.getConfig().getName();
        return request.exchangeToMono(response -> readResponse(response, finalBinding, serviceName))
                .timeout(timeout)
                .onErrorMap(
                        TimeoutException.class,
                        e -> Status.DEADLINE_EXCEEDED
                                .withDescription("HTTP backend timed out after " + timeout)
                                .asRuntimeException())
                .map(map -> new Exchange(
                        EnumValueTranslator.toProto(
                                toJsonNames(map, finalMd.getOutputType()),
                                finalMd.getOutputType(),
                                ctx.getEnumValueOriginals()),
                        finalMd,
                        finalBinding));
    }

    /**
     * Re-keys a decoded response from the backend's property names (the synthetic message's proto
     * field names, e.g. {@code placed_at}) to the JSON names the serde tiers bind by ({@code
     * placedAt}), recursively through nested objects and arrays. The compiled Tier 2 deserializer
     * only knows JSON names, so without this a snake_case property silently reads as its default.
     * Keys matching no field are kept as they are.
     */
    static Map<String, Object> toJsonNames(Map<String, Object> map, Descriptor type) {
        if (map == null) return null;
        Map<String, Object> out = new LinkedHashMap<>(map);
        for (FieldDescriptor fd : type.getFields()) {
            String proto = fd.getName();
            String json = fd.getJsonName();
            Object value;
            if (map.containsKey(json)) {
                value = map.get(json);
            } else if (map.containsKey(proto)) {
                value = out.remove(proto);
            } else {
                continue;
            }
            out.put(json, nestedToJsonNames(fd, value));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object nestedToJsonNames(FieldDescriptor fd, Object value) {
        if (fd.getType() != FieldDescriptor.Type.MESSAGE || value == null) return value;
        if (fd.isMapField()) {
            FieldDescriptor valueFd = fd.getMessageType().findFieldByNumber(2);
            if (!(value instanceof Map<?, ?> m)
                    || valueFd == null
                    || valueFd.getType() != FieldDescriptor.Type.MESSAGE) {
                return value;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), nestedToJsonNames(valueFd, e.getValue()));
            }
            return out;
        }
        if (fd.isRepeated()) {
            if (!(value instanceof List<?> list)) return value;
            List<Object> out = new java.util.ArrayList<>(list.size());
            for (Object item : list) {
                out.add(item instanceof Map<?, ?> m ? toJsonNames((Map<String, Object>) m, fd.getMessageType()) : item);
            }
            return out;
        }
        return value instanceof Map<?, ?> m ? toJsonNames((Map<String, Object>) m, fd.getMessageType()) : value;
    }

    // -------------------------------------------------------------------------

    /**
     * What to ask the backend for and how to send the body: the service's preferred media type
     * when a codec supports it, the operation declares it, and the codec holds the schema; else
     * JSON. Request and response sides are decided independently, since an operation may declare
     * different media types for each.
     */
    Negotiation negotiate(ServiceDefinition def, HttpOperationBinding binding) {
        ServiceDefinition.UpstreamContent pref = def != null && def.getUpstreamContent() != null
                ? def.getUpstreamContent()
                : new ServiceDefinition.UpstreamContent();
        if (pref.prefersJson() || codecs.isEmpty()) return Negotiation.JSON;

        String preferred = HttpOperationBinding.bare(pref.getPreferred());
        UpstreamBodyCodec codec = null;
        for (UpstreamBodyCodec c : codecs) {
            if (c.supports(preferred)) {
                codec = c;
                break;
            }
        }
        if (codec == null) {
            log.debug("No UpstreamBodyCodec supports preferred media type {}; using JSON", preferred);
            return Negotiation.JSON;
        }
        String service = def.getName();

        UpstreamBodyCodec responseCodec = binding.producesResponse(preferred)
                        && binding.responseSchemaName() != null
                        && codec.canServe(service, binding.responseSchemaName())
                ? codec
                : null;
        UpstreamBodyCodec requestCodec = binding.hasBody()
                        && binding.acceptsRequest(preferred)
                        && binding.requestSchemaName() != null
                        && codec.canServe(service, binding.requestSchemaName())
                ? codec
                : null;
        if (responseCodec == null && requestCodec == null) return Negotiation.JSON;

        String accept = responseCodec != null
                ? codec.mediaType() + ", " + MediaType.APPLICATION_JSON_VALUE + ";q=0.5"
                : MediaType.APPLICATION_JSON_VALUE;
        String contentType = requestCodec != null ? codec.mediaType() : MediaType.APPLICATION_JSON_VALUE;
        return new Negotiation(accept, contentType, requestCodec);
    }

    /** Outcome of {@link #negotiate}. {@code requestCodec} is null when the body goes as JSON. */
    record Negotiation(String accept, String requestContentType, UpstreamBodyCodec requestCodec) {
        static final Negotiation JSON =
                new Negotiation(MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_JSON_VALUE, null);
    }

    @SuppressWarnings("unchecked")
    private byte[] encodeBody(UpstreamBodyCodec codec, String service, HttpOperationBinding binding, Object body) {
        if (!(body instanceof Map<?, ?> map)) {
            throw Status.INVALID_ARGUMENT
                    .withDescription("Request body for " + binding.requestSchemaName()
                            + " must be an object to encode as " + codec.mediaType())
                    .asRuntimeException();
        }
        try {
            return codec.encode(service, binding.requestSchemaName(), (Map<String, Object>) map);
        } catch (IllegalArgumentException e) {
            throw Status.INVALID_ARGUMENT
                    .withDescription("Request body does not fit the " + binding.requestSchemaName() + " schema: "
                            + e.getMessage())
                    .asRuntimeException();
        }
    }

    private Mono<Map<String, Object>> readResponse(
            ClientResponse response, HttpOperationBinding binding, String serviceName) {
        int status = response.statusCode().value();
        String contentType =
                response.headers().contentType().map(MediaType::toString).orElse(null);
        return response.bodyToMono(byte[].class).defaultIfEmpty(new byte[0]).flatMap(bytes -> {
            if (status >= 200 && status < 300) {
                try {
                    UpstreamBodyCodec codec = codecFor(contentType);
                    return Mono.just(
                            codec != null ? decodeBody(codec, serviceName, bytes, binding) : parseBody(bytes, binding));
                } catch (Exception e) {
                    return Mono.error(Status.INTERNAL
                            .withDescription("Unparseable " + (contentType != null ? contentType : "JSON")
                                    + " from HTTP backend: " + e.getMessage())
                            .asRuntimeException());
                }
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            return Mono.error(toGrpcStatus(status)
                    .withDescription(text.isBlank() ? "HTTP " + status : "HTTP " + status + ": " + trim(text))
                    .asRuntimeException());
        });
    }

    /** The codec for a returned {@code Content-Type}, or null for JSON / absent (parsed as JSON). */
    private UpstreamBodyCodec codecFor(String contentType) {
        if (contentType == null || codecs.isEmpty()) return null;
        String bare = HttpOperationBinding.bare(contentType);
        if (bare.equals(MediaType.APPLICATION_JSON_VALUE) || bare.endsWith("+json")) return null;
        for (UpstreamBodyCodec c : codecs) if (c.supports(bare)) return c;
        return null;
    }

    private static Map<String, Object> decodeBody(
            UpstreamBodyCodec codec, String serviceName, byte[] bytes, HttpOperationBinding binding) {
        if (bytes.length == 0) return new LinkedHashMap<>();
        if (binding.responseSchemaName() == null) {
            throw new IllegalArgumentException("backend answered " + codec.mediaType()
                    + " but the operation's response schema is inline, so no schema is bound for it");
        }
        Object decoded = codec.decode(serviceName, binding.responseSchemaName(), binding.responseIsArray(), bytes);
        return wrap(decoded, binding);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseBody(byte[] bytes, HttpOperationBinding binding) throws Exception {
        if (bytes.length == 0 || new String(bytes, StandardCharsets.UTF_8).isBlank()) return new LinkedHashMap<>();
        return wrap(json.readValue(bytes, Object.class), binding);
    }

    /** Wraps arrays and scalars into the synthetic response message's single field. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> wrap(Object parsed, HttpOperationBinding binding) {
        if (binding.responseIsArray()) {
            Map<String, Object> wrapped = new LinkedHashMap<>();
            wrapped.put(HttpOperationBinding.ARRAY_FIELD, parsed instanceof List<?> l ? l : List.of(parsed));
            return wrapped;
        }
        if (binding.responseIsScalar() || !(parsed instanceof Map)) {
            Map<String, Object> wrapped = new LinkedHashMap<>();
            wrapped.put(HttpOperationBinding.SCALAR_FIELD, parsed);
            return wrapped;
        }
        return (Map<String, Object>) parsed;
    }

    /** Normalises through the synthetic message so the Map shape matches the gRPC path exactly. */
    private Map<String, Object> bind(
            Map<String, Object> raw, Descriptor outputType, ServiceEndpointContext ctx, ProjectionNode projection) {
        try {
            DynamicMessage message = serdeSelector.deserializeFromMap(raw, outputType, ctx);
            return serdeSelector.serializeToMapProjected(message, outputType, ctx, projection);
        } catch (Exception e) {
            throw Status.INTERNAL
                    .withDescription("HTTP backend response does not match its OpenAPI schema: " + e.getMessage())
                    .asRuntimeException();
        }
    }

    private DynamicMessage toMessage(Map<String, Object> raw, Descriptor outputType, ServiceEndpointContext ctx) {
        try {
            return serdeSelector.deserializeFromMap(raw, outputType, ctx);
        } catch (Exception e) {
            throw Status.INTERNAL
                    .withDescription("HTTP backend response does not match its OpenAPI schema: " + e.getMessage())
                    .asRuntimeException();
        }
    }

    /** GraphQL hands arguments by JSON name; the binding names proto fields. Accept either spelling. */
    private static Map<String, Object> keyByProtoName(Map<String, Object> args, Descriptor inputType) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (args == null) return out;
        for (FieldDescriptor fd : inputType.getFields()) {
            Object v = args.containsKey(fd.getJsonName()) ? args.get(fd.getJsonName()) : args.get(fd.getName());
            if (v != null) out.put(fd.getName(), v);
        }
        return out;
    }

    private static URI buildUri(String baseUri, HttpOperationBinding binding, Map<String, Object> args) {
        String path = binding.pathTemplate();
        for (String p : binding.pathParams()) {
            Object v = args.get(p);
            if (v == null) {
                throw Status.INVALID_ARGUMENT
                        .withDescription("Missing path parameter '" + p + "'")
                        .asRuntimeException();
            }
            path = path.replace(
                    "{" + p + "}",
                    URLEncoder.encode(String.valueOf(v), StandardCharsets.UTF_8).replace("+", "%20"));
        }
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(baseUri).path(path);
        for (String q : binding.queryParams()) {
            Object v = args.get(q);
            if (v == null) continue;
            if (v instanceof List<?> list) {
                for (Object item : list) if (item != null) b.queryParam(q, String.valueOf(item));
            } else {
                b.queryParam(q, String.valueOf(v));
            }
        }
        return b.build().encode().toUri();
    }

    private static Object bodyOf(HttpOperationBinding binding, Map<String, Object> args) {
        // A single "body" field means the document declared a non-object body: send it as-is.
        if (args.containsKey("body")
                && args.size()
                        == binding.pathParams().size() + binding.queryParams().size() + 1) {
            return args.get("body");
        }
        Map<String, Object> body = new LinkedHashMap<>(args);
        binding.pathParams().forEach(body::remove);
        binding.queryParams().forEach(body::remove);
        return body;
    }

    private String toJson(Object body) {
        try {
            return json.writeValueAsString(body);
        } catch (Exception e) {
            throw Status.INVALID_ARGUMENT
                    .withDescription("Request body is not serialisable: " + e.getMessage())
                    .asRuntimeException();
        }
    }

    /** Inverse of the REST surface's status mapping, so HTTP backend errors render like gRPC ones. */
    static Status toGrpcStatus(int http) {
        return switch (http) {
            case 400, 422 -> Status.INVALID_ARGUMENT;
            case 401 -> Status.UNAUTHENTICATED;
            case 403 -> Status.PERMISSION_DENIED;
            case 404 -> Status.NOT_FOUND;
            case 408, 504 -> Status.DEADLINE_EXCEEDED;
            case 409 -> Status.ALREADY_EXISTS;
            case 412 -> Status.FAILED_PRECONDITION;
            case 429 -> Status.RESOURCE_EXHAUSTED;
            case 501 -> Status.UNIMPLEMENTED;
            case 502, 503 -> Status.UNAVAILABLE;
            default -> http >= 500 ? Status.INTERNAL : Status.UNKNOWN;
        };
    }

    private static String trim(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
