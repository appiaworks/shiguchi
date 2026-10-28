package dev.appiaworks.shiguchi.gateway;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.grpc.GrpcCallContext;
import dev.appiaworks.shiguchi.rest.ReactiveGrpcDispatcher;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.ProtoAccessRules;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * gRPC-Web on the gateway's HTTP port ({@code shiguchi.grpc-web.enabled}): a browser client
 * posts {@code <path-prefix>/<package.Service>/<Method>} with a gRPC-Web {@code Content-Type},
 * and the call goes to that service's backend over the same channel the REST surface uses —
 * load balancing, deadlines, retries, metrics, forwarded headers and all.
 *
 * <p>The message bytes are forwarded untouched in both directions; nothing is transcoded. What
 * this filter does is the framing ({@link GrpcWebFrames}): take the one request message out of
 * its frame (base64-decoding it first for {@code -text}), and send each response message as a
 * data frame followed by a trailers frame carrying {@code grpc-status}, {@code grpc-message} and
 * any rich details. The HTTP status is always 200; failures travel in the trailers, as gRPC-Web
 * clients expect.
 *
 * <p>Unary and server-streaming methods only — gRPC-Web cannot carry a client stream. A method
 * hidden by the service's include/exclude filters is not callable here either. Authorization
 * uses the method's route (its scopes and authorizer rules) exactly as the REST surface does;
 * the security chain treats these paths like the REST surface. The REST body caps apply too:
 * {@code rest.max-request-body} to the request, {@code rest.max-response-body} to the data frames
 * sent, the latter answered with {@code RESOURCE_EXHAUSTED} trailers.
 *
 * <p>A WebFlux filter, not a gateway route: a gRPC-Web request is answered here and never
 * reaches REST route matching, so SCG's global filters (the access log among them) do not see it.
 */
@Component
@ConditionalOnProperty(prefix = "shiguchi.grpc-web", name = "enabled", havingValue = "true")
@Order(GrpcWebFilter.ORDER)
public class GrpcWebFilter implements WebFilter {

    /**
     * After Spring Security ({@code -100}) and the inbound rate limiter ({@code -90}), before
     * WebFlux handler mapping: a gRPC-Web path is no SCG route, so a gateway {@code GlobalFilter}
     * would never see it.
     */
    public static final int ORDER = -80;

    private static final MediaType GRPC_WEB_PROTO = MediaType.parseMediaType("application/grpc-web+proto");
    private static final MediaType GRPC_WEB_TEXT_PROTO = MediaType.parseMediaType("application/grpc-web-text+proto");

    private final GrpcRouteRegistry registry;
    private final GrpcMetadataPopulator metadataPopulator;
    private final GatewayAuthorizationEnforcer authEnforcer;
    private final String pathPrefix;
    private final Duration defaultDeadline;
    private final long maxRequestBody;
    /** {@code rest.max-response-body}, counted over the data frames sent; MAX_VALUE = off. */
    private final long maxResponseBody;

    /** Present only when gateway-side validation is on ({@code validation.gateway-enabled}). */
    private dev.appiaworks.shiguchi.validation.RequestValidationHook validationHook;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setValidationHook(dev.appiaworks.shiguchi.validation.RequestValidationHook validationHook) {
        this.validationHook = validationHook;
    }

    /**
     * Ends a server stream opened with the BFF session cookie when its session ends; null when the
     * filter is wired by hand without one, which binds nothing.
     */
    private dev.appiaworks.shiguchi.security.CookieSessionWatch sessionWatch;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSessionWatch(dev.appiaworks.shiguchi.security.CookieSessionWatch sessionWatch) {
        this.sessionWatch = sessionWatch;
    }

    public GrpcWebFilter(
            GrpcRouteRegistry registry,
            GrpcMetadataPopulator metadataPopulator,
            GatewayAuthorizationEnforcer authEnforcer,
            GatewayProperties properties) {
        this.registry = registry;
        this.metadataPopulator = metadataPopulator;
        this.authEnforcer = authEnforcer;
        this.pathPrefix = normalizePrefix(properties.getGrpcWeb().getPathPrefix());
        this.defaultDeadline = properties.getClient().getDefaultDeadline();
        var cap = properties.getRest().getMaxRequestBody();
        this.maxRequestBody = cap == null || cap.toBytes() <= 0 ? Integer.MAX_VALUE : cap.toBytes();
        var responseCap = properties.getRest().getMaxResponseBody();
        this.maxResponseBody =
                responseCap == null || responseCap.toBytes() <= 0 ? Long.MAX_VALUE : responseCap.toBytes();
        this.endStreamsAtTokenExpiry = properties.getSecurity().isEndStreamsAtTokenExpiry();
    }

    /** {@code shiguchi.security.end-streams-at-token-expiry}. */
    private final boolean endStreamsAtTokenExpiry;

    /** Whether a {@code Content-Type} is one of the gRPC-Web ones. */
    static boolean isGrpcWeb(MediaType contentType) {
        return contentType != null
                && "application".equals(contentType.getType())
                && contentType.getSubtype().startsWith("grpc-web");
    }

    /** The configured prefix without trailing slashes; empty when unset. */
    public static String normalizePrefix(String prefix) {
        return prefix == null ? "" : prefix.replaceAll("/+$", "");
    }

    /**
     * {@code <prefix>/package.Service/Method} split into service and method, or null when the path
     * is not exactly that shape.
     */
    static String[] methodPath(String path, String prefix) {
        if (!prefix.isEmpty()) {
            if (!path.startsWith(prefix + "/")) return null;
            path = path.substring(prefix.length());
        }
        String[] parts = path.startsWith("/") ? path.substring(1).split("/", -1) : new String[0];
        return parts.length == 2 && !parts[0].isEmpty() && !parts[1].isEmpty() ? parts : null;
    }

    static boolean isText(MediaType contentType) {
        return contentType.getSubtype().startsWith("grpc-web-text");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        MediaType contentType = exchange.getRequest().getHeaders().getContentType();
        if (!isGrpcWeb(contentType) || exchange.getRequest().getMethod() != HttpMethod.POST) {
            return chain.filter(exchange);
        }
        String path = exchange.getRequest().getPath().value();
        if (!pathPrefix.isEmpty() && !path.startsWith(pathPrefix + "/")) return chain.filter(exchange);
        boolean text = isText(contentType);
        exchange.getResponse().setStatusCode(HttpStatus.OK);
        exchange.getResponse().getHeaders().setContentType(text ? GRPC_WEB_TEXT_PROTO : GRPC_WEB_PROTO);

        String[] parts = methodPath(path, pathPrefix);
        if (parts == null) {
            return trailersOnly(
                    exchange, text, Status.UNIMPLEMENTED.withDescription("Not a gRPC method path: " + path));
        }
        String service = parts[0];
        String methodName = parts[1];
        exchange.getAttributes().put(GatewayAccessLogFilter.ROUTE_ATTR, service + "/" + methodName);
        exchange.getAttributes().put(GatewayAccessLogFilter.PROTOCOL_ATTR, "grpc-web");

        ServiceEndpointContext ctx = registry.contextByServiceFqn(service).orElse(null);
        var md = ctx == null ? null : ctx.lookupMethod(service, methodName).orElse(null);
        if (md == null || !ctx.getConfig().isMethodExposed(service, methodName)) {
            return trailersOnly(
                    exchange,
                    text,
                    Status.UNIMPLEMENTED.withDescription("Unknown method " + service + "/" + methodName));
        }
        if (md.isClientStreaming()) {
            return trailersOnly(
                    exchange,
                    text,
                    Status.UNIMPLEMENTED.withDescription("gRPC-Web cannot carry a client stream; " + service + "/"
                            + methodName + " is not callable"));
        }
        boolean serverStreaming = md.isServerStreaming();
        // A method with neither a REST nor a GraphQL route still carries its annotation's access rules.
        RouteDefinition route = registry.findBySourceMethod(ctx.getConfig().getName(), service, methodName)
                .orElseGet(() -> ProtoAccessRules.apply(
                                RouteDefinition.builder(
                                        service,
                                        methodName,
                                        serverStreaming
                                                ? MethodDescriptor.MethodType.SERVER_STREAMING
                                                : MethodDescriptor.MethodType.UNARY,
                                        ctx.getConfig().getTarget()),
                                md)
                        .build());

        MethodDescriptor<byte[], byte[]> rawMethod = MethodDescriptor.<byte[], byte[]>newBuilder()
                .setType(
                        serverStreaming
                                ? MethodDescriptor.MethodType.SERVER_STREAMING
                                : MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(MethodDescriptor.generateFullMethodName(service, methodName))
                .setRequestMarshaller(RAW)
                .setResponseMarshaller(RAW)
                .build();

        Mono<byte[]> requestMessage = DataBufferUtils.join(
                        exchange.getRequest().getBody(), (int) Math.min(maxRequestBody, Integer.MAX_VALUE))
                .map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    return bytes;
                })
                .defaultIfEmpty(new byte[0])
                .map(body -> GrpcWebFrames.requestMessage(body, text));

        return requestMessage
                .flatMap(message -> authEnforcer
                        .enforce(route, Map.of(), null)
                        .then(metadataPopulator.build(exchange.getRequest()).defaultIfEmpty(new Metadata()))
                        .flatMap(metadata -> {
                            // After authorization, as on the REST surface; a violation throws
                            // INVALID_ARGUMENT, answered below in the trailers-only form.
                            validate(md, message);
                            CallOptions options = callOptions(exchange, ctx, metadata, serverStreaming);
                            return endSignal(serverStreaming).flatMap(end -> {
                                Flux<byte[]> responses = serverStreaming
                                        ? ReactiveGrpcDispatcher.streamingResponses(
                                                ctx.getChannel(), rawMethod, options, message)
                                        : unary(ctx, rawMethod, options, message)
                                                .flux();
                                // A stream of a cookie caller lives and dies with the session, one of a
                                // bearer caller ends at the token's expiry: the frames stop, the backend
                                // call is cancelled and the stream finishes with UNAUTHENTICATED
                                // trailers, not the OK ones.
                                var endStatus = new java.util.concurrent.atomic.AtomicReference<Status>();
                                responses = StreamEnd.until(responses, end, endStatus);
                                var written = new java.util.concurrent.atomic.AtomicLong();
                                Flux<byte[]> frames = responses
                                        .map(GrpcWebFrames::dataFrame)
                                        // Over the response cap the call ends with RESOURCE_EXHAUSTED
                                        // trailers (ErrorInfo RESPONSE_TOO_LARGE), as the REST surface does.
                                        // A unary response is one body, so the cap is on its size. A server
                                        // stream is not buffered and may run indefinitely, so the cap is on
                                        // each message: a running total would end every long-lived stream.
                                        .handle((byte[] frame, reactor.core.publisher.SynchronousSink<byte[]> sink) -> {
                                            long size = serverStreaming
                                                    ? frame.length - 5L
                                                    : written.addAndGet(frame.length);
                                            if (size > maxResponseBody) {
                                                sink.error(ShiguchiFilter.responseTooLarge(size, maxResponseBody));
                                            } else {
                                                sink.next(frame);
                                            }
                                        })
                                        .concatWith(Mono.fromSupplier(() -> trailers(
                                                exchange, endStatus.get() != null ? endStatus.get() : Status.OK, null)))
                                        .onErrorResume(ex -> Mono.just(trailers(
                                                exchange, Status.fromThrowable(ex), Status.trailersFromThrowable(ex))));
                                return write(exchange, text, frames);
                            });
                        }))
                .onErrorResume(
                        AccessDeniedException.class,
                        ex -> trailersOnly(exchange, text, Status.PERMISSION_DENIED.withDescription(ex.getMessage())))
                .onErrorResume(
                        io.grpc.StatusRuntimeException.class,
                        ex -> trailersOnly(exchange, text, ex.getStatus(), ex.getTrailers()))
                .onErrorResume(
                        GrpcWebFrames.MalformedFrameException.class,
                        ex -> trailersOnly(exchange, text, Status.INVALID_ARGUMENT.withDescription(ex.getMessage())))
                .onErrorResume(
                        org.springframework.core.io.buffer.DataBufferLimitException.class,
                        ex -> trailersOnly(
                                exchange,
                                text,
                                Status.RESOURCE_EXHAUSTED.withDescription(
                                        "Request body exceeds the limit of " + maxRequestBody + " bytes")));
    }

    /** What ends a server stream of the caller (session end, token expiry); empty for none. */
    private Mono<java.util.Optional<Mono<Status>>> endSignal(boolean serverStreaming) {
        if (!serverStreaming) return Mono.just(java.util.Optional.empty());
        return StreamEnd.of(sessionWatch, endStreamsAtTokenExpiry);
    }

    private static Mono<byte[]> unary(
            ServiceEndpointContext ctx, MethodDescriptor<byte[], byte[]> method, CallOptions options, byte[] message) {
        return Mono.create(sink -> {
            ClientCall<byte[], byte[]> call = ctx.getChannel().newCall(method, options);
            // A browser that goes away cancels the backend call rather than leaving it to run.
            sink.onCancel(() -> call.cancel("gRPC-Web caller went away", null));
            ClientCalls.asyncUnaryCall(call, message, new StreamObserver<>() {
                @Override
                public void onNext(byte[] value) {
                    sink.success(value);
                }

                @Override
                public void onError(Throwable t) {
                    sink.error(t);
                }

                @Override
                public void onCompleted() {}
            });
        });
    }

    /**
     * Forwarded headers as the REST surface forwards them, and the deadline: the client's
     * {@code grpc-timeout} when it sends one, capped for a unary call by the service's deadline
     * ({@code deadline}, else {@code client.default-deadline}). Streams get only the client's.
     */
    private CallOptions callOptions(
            ServerWebExchange exchange, ServiceEndpointContext ctx, Metadata metadata, boolean serverStreaming) {
        CallOptions options = metadata == null || metadata.keys().isEmpty()
                ? CallOptions.DEFAULT
                : CallOptions.DEFAULT.withOption(GrpcCallContext.METADATA_KEY, metadata);
        Duration deadline = parseTimeout(exchange.getRequest().getHeaders().getFirst("grpc-timeout"));
        if (!serverStreaming) {
            Duration service = ctx.getConfig().effectiveDeadline(defaultDeadline);
            if (service != null && !service.isZero() && !service.isNegative()) {
                deadline = deadline == null || service.compareTo(deadline) < 0 ? service : deadline;
            }
        }
        return deadline == null ? options : options.withDeadlineAfter(deadline.toNanos(), TimeUnit.NANOSECONDS);
    }

    /** A {@code grpc-timeout} value: digits and a unit ({@code H M S m u n}); null when absent or malformed. */
    static Duration parseTimeout(String value) {
        if (value == null || value.length() < 2 || value.length() > 9) return null;
        long amount;
        try {
            amount = Long.parseLong(value.substring(0, value.length() - 1));
        } catch (NumberFormatException e) {
            return null;
        }
        if (amount < 0) return null;
        return switch (value.charAt(value.length() - 1)) {
            case 'H' -> Duration.ofHours(amount);
            case 'M' -> Duration.ofMinutes(amount);
            case 'S' -> Duration.ofSeconds(amount);
            case 'm' -> Duration.ofMillis(amount);
            case 'u' -> Duration.ofNanos(amount * 1_000);
            case 'n' -> Duration.ofNanos(amount);
            default -> null;
        };
    }

    private static byte[] trailers(ServerWebExchange exchange, Status status, Metadata trailers) {
        exchange.getAttributes()
                .put(GatewayAccessLogFilter.GRPC_STATUS_ATTR, status.getCode().name());
        return GrpcWebFrames.trailerFrame(status, trailers);
    }

    /**
     * A call that fails before any message: the status is sent both as response headers (the
     * "trailers-only" form some clients read first) and as the trailers frame.
     */
    private static Mono<Void> trailersOnly(ServerWebExchange exchange, boolean text, Status status) {
        return trailersOnly(exchange, text, status, null);
    }

    /** Trailers-only with metadata: a validation failure's {@code google.rpc.BadRequest} goes along. */
    private static Mono<Void> trailersOnly(ServerWebExchange exchange, boolean text, Status status, Metadata trailers) {
        var headers = exchange.getResponse().getHeaders();
        headers.set("grpc-status", Integer.toString(status.getCode().value()));
        if (status.getDescription() != null) {
            headers.set("grpc-message", GrpcWebFrames.percentEncode(status.getDescription()));
        }
        return write(exchange, text, Flux.just(trailers(exchange, status, trailers)));
    }

    /**
     * Runs the gateway-side validation hook, when one is installed, on the request message. The
     * message is parsed only for that — the backend still receives the caller's bytes unchanged —
     * so with validation off nothing is decoded, as before.
     */
    private void validate(com.google.protobuf.Descriptors.MethodDescriptor md, byte[] message) {
        var hook = validationHook;
        if (hook == null) return;
        com.google.protobuf.DynamicMessage request;
        try {
            request = com.google.protobuf.DynamicMessage.parseFrom(md.getInputType(), message);
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
            throw Status.INVALID_ARGUMENT
                    .withDescription("Request message is not a valid "
                            + md.getInputType().getFullName())
                    .asRuntimeException();
        }
        hook.validate(request, md);
    }

    private static Mono<Void> write(ServerWebExchange exchange, boolean text, Flux<byte[]> frames) {
        var factory = exchange.getResponse().bufferFactory();
        Flux<DataBuffer> buffers = frames.map(frame -> factory.wrap(text ? GrpcWebFrames.text(frame) : frame));
        // Each frame is flushed as it is produced, so a server stream reaches the browser live.
        return exchange.getResponse().writeAndFlushWith(buffers.map(Flux::just));
    }

    /** Message bytes passed through untouched. */
    private static final MethodDescriptor.Marshaller<byte[]> RAW = new MethodDescriptor.Marshaller<>() {
        @Override
        public InputStream stream(byte[] value) {
            return new ByteArrayInputStream(value);
        }

        @Override
        public byte[] parse(InputStream stream) {
            try {
                return stream.readAllBytes();
            } catch (IOException e) {
                throw Status.INTERNAL
                        .withDescription("could not read a response message")
                        .withCause(e)
                        .asRuntimeException();
            }
        }
    };
}
