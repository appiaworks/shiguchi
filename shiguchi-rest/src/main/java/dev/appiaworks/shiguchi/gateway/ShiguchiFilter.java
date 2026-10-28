package dev.appiaworks.shiguchi.gateway;

import dev.appiaworks.shiguchi.rest.GrpcPathMatcher;
import dev.appiaworks.shiguchi.rest.GrpcRequestBuilder;
import dev.appiaworks.shiguchi.rest.GrpcResponsePaginationExtractor;
import dev.appiaworks.shiguchi.rest.GrpcStatusMapper;
import dev.appiaworks.shiguchi.rest.HttpBodySupport;
import dev.appiaworks.shiguchi.rest.MergeDispatcher;
import dev.appiaworks.shiguchi.rest.QueryParameterValidator;
import dev.appiaworks.shiguchi.rest.ReactiveGrpcDispatcher;
import dev.appiaworks.shiguchi.rest.RestBodyCodec;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import io.grpc.StatusRuntimeException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * GlobalFilter that intercepts all HTTP requests matched by the REST route index,
 * dispatches them to the appropriate gRPC backend, and writes the JSON response.
 *
 * Filter order 10150: runs after ReactiveLoadBalancerClientFilter (10100) —
 * so lb:// URIs are already resolved — but before NettyRoutingFilter (10200).
 * Writes its own response for matched routes and does NOT call chain.filter().
 */
@Component
@ConditionalOnClass(GlobalFilter.class)
public class ShiguchiFilter implements GlobalFilter, Ordered, org.springframework.context.EnvironmentAware {

    private static final Logger log = LoggerFactory.getLogger(ShiguchiFilter.class);

    private static final int ORDER = 10150;

    /** Header alias for the {@code ?fields=} field-projection parameter. */
    private static final String FIELDS_HEADER = "X-Fields";

    private final GrpcRouteRegistry registry;
    private final GrpcPathMatcher pathMatcher;
    private final ReactiveGrpcDispatcher dispatcher;
    private final GrpcMetadataPopulator metadataPopulator;
    private final GatewayAuthorizationEnforcer authEnforcer;
    private final GrpcStatusMapper statusMapper;
    private final GrpcResponsePaginationExtractor paginationExtractor;
    private final GatewayErrorRenderer errorRenderer;
    private final MergeDispatcher mergeDispatcher;
    private final GrpcRequestBuilder requestBuilder;
    private final dev.appiaworks.shiguchi.serde.SerdeSelector serdeSelector;
    private final String mergeNextPageTokenHeader;
    private final String mergeTotalCountHeader;

    /** Cap on a transcoded request body, in bytes ({@code shiguchi.rest.max-request-body}). */
    private final long maxRequestBody;

    /** Cap on a response body as written, in bytes ({@code shiguchi.rest.max-response-body}); MAX_VALUE = off. */
    private final long maxResponseBody;

    /** Non-JSON body codecs for callers (Avro, ...); empty means JSON only, the historical behaviour. */
    private final List<RestBodyCodec> codecs;

    /** Global {@code rest.request-validation.reject-unknown-query-parameters}; a service may override it. */
    private final boolean rejectUnknownQueryParameters;

    /** {@code rest.request-validation.ignored-query-parameters}: never reported as unknown. */
    private final java.util.Set<String> ignoredQueryParameters;
    /** {@code rest.streaming.default-format}: the framing when the caller's Accept names none. */
    private final dev.appiaworks.shiguchi.config.RestConfig.StreamFormat defaultStreamFormat;

    private final java.time.Duration sseKeepalive;

    /** {@code rest.forward-response-headers}; a service may override it. */
    private final List<String> forwardResponseHeaders;

    /** {@code rest.honor-grpc-timeout}. */
    private final boolean honorGrpcTimeout;

    /** {@code json.print.pretty-print}; a service's {@code json-print} may override it. */
    private final boolean prettyPrint;

    /** {@code rest.compression}; null when off. */
    private final dev.appiaworks.shiguchi.config.RestConfig.CompressionConfig compression;

    /** {@code rest.compression.min-size} in bytes. */
    private final long compressionMinSize;

    /** {@code rest.resource-etags}. */
    private final boolean resourceEtags;

    /**
     * {@code server.compression.enabled}: the server may re-encode a body after it was tagged, so
     * a hash tag can no longer promise byte identity and is sent weak.
     */
    private boolean serverCompression;

    /** The response cache and request coalescing; null when not wired (tests building the filter). */
    private ResponseCache responseCache;

    @Autowired
    public ShiguchiFilter(
            GrpcRouteRegistry registry,
            GrpcPathMatcher pathMatcher,
            ReactiveGrpcDispatcher dispatcher,
            GrpcMetadataPopulator metadataPopulator,
            GatewayAuthorizationEnforcer authEnforcer,
            GrpcStatusMapper statusMapper,
            GrpcResponsePaginationExtractor paginationExtractor,
            GatewayErrorRenderer errorRenderer,
            MergeDispatcher mergeDispatcher,
            GrpcRequestBuilder requestBuilder,
            dev.appiaworks.shiguchi.serde.SerdeSelector serdeSelector,
            dev.appiaworks.shiguchi.config.GatewayProperties properties,
            ObjectProvider<RestBodyCodec> codecs) {
        this(
                registry,
                pathMatcher,
                dispatcher,
                metadataPopulator,
                authEnforcer,
                statusMapper,
                paginationExtractor,
                errorRenderer,
                mergeDispatcher,
                requestBuilder,
                serdeSelector,
                properties,
                codecs.orderedStream().toList());
    }

    public ShiguchiFilter(
            GrpcRouteRegistry registry,
            GrpcPathMatcher pathMatcher,
            ReactiveGrpcDispatcher dispatcher,
            GrpcMetadataPopulator metadataPopulator,
            GatewayAuthorizationEnforcer authEnforcer,
            GrpcStatusMapper statusMapper,
            GrpcResponsePaginationExtractor paginationExtractor,
            GatewayErrorRenderer errorRenderer,
            MergeDispatcher mergeDispatcher,
            GrpcRequestBuilder requestBuilder,
            dev.appiaworks.shiguchi.serde.SerdeSelector serdeSelector,
            dev.appiaworks.shiguchi.config.GatewayProperties properties) {
        this(
                registry,
                pathMatcher,
                dispatcher,
                metadataPopulator,
                authEnforcer,
                statusMapper,
                paginationExtractor,
                errorRenderer,
                mergeDispatcher,
                requestBuilder,
                serdeSelector,
                properties,
                List.of());
    }

    public ShiguchiFilter(
            GrpcRouteRegistry registry,
            GrpcPathMatcher pathMatcher,
            ReactiveGrpcDispatcher dispatcher,
            GrpcMetadataPopulator metadataPopulator,
            GatewayAuthorizationEnforcer authEnforcer,
            GrpcStatusMapper statusMapper,
            GrpcResponsePaginationExtractor paginationExtractor,
            GatewayErrorRenderer errorRenderer,
            MergeDispatcher mergeDispatcher,
            GrpcRequestBuilder requestBuilder,
            dev.appiaworks.shiguchi.serde.SerdeSelector serdeSelector,
            dev.appiaworks.shiguchi.config.GatewayProperties properties,
            List<RestBodyCodec> codecs) {
        this.codecs = codecs != null ? List.copyOf(codecs) : List.of();
        this.registry = registry;
        this.pathMatcher = pathMatcher;
        this.dispatcher = dispatcher;
        this.metadataPopulator = metadataPopulator;
        this.authEnforcer = authEnforcer;
        this.statusMapper = statusMapper;
        this.paginationExtractor = paginationExtractor;
        this.errorRenderer = errorRenderer;
        this.mergeDispatcher = mergeDispatcher;
        this.requestBuilder = requestBuilder;
        this.serdeSelector = serdeSelector;
        // A merged page reuses the same pagination header names as any other paged response, so
        // clients do not need to know the endpoint fans out.
        var pagination = properties.getRest().getPagination();
        this.mergeNextPageTokenHeader = pagination.getNextPageTokenHeader();
        this.mergeTotalCountHeader = pagination.getTotalCountHeader();
        var streaming = properties.getRest().getStreaming();
        this.defaultStreamFormat = streaming == null || streaming.getDefaultFormat() == null
                ? dev.appiaworks.shiguchi.config.RestConfig.StreamFormat.NDJSON
                : streaming.getDefaultFormat();
        this.sseKeepalive = streaming == null ? null : streaming.getSseKeepalive();
        var cap = properties.getRest().getMaxRequestBody();
        this.maxRequestBody = cap == null || cap.toBytes() <= 0 ? Long.MAX_VALUE : cap.toBytes();
        var requestValidation = properties.getRest().getRequestValidation();
        this.rejectUnknownQueryParameters = requestValidation.isRejectUnknownQueryParameters();
        this.ignoredQueryParameters = requestValidation.getIgnoredQueryParameters() == null
                ? java.util.Set.of()
                : java.util.Set.copyOf(requestValidation.getIgnoredQueryParameters());
        var responseCap = properties.getRest().getMaxResponseBody();
        this.maxResponseBody =
                responseCap == null || responseCap.toBytes() <= 0 ? Long.MAX_VALUE : responseCap.toBytes();
        var forward = properties.getRest().getForwardResponseHeaders();
        this.forwardResponseHeaders = forward == null ? List.of() : List.copyOf(forward);
        this.honorGrpcTimeout = properties.getRest().isHonorGrpcTimeout();
        var print = properties.getJson() == null ? null : properties.getJson().getPrint();
        this.prettyPrint = print != null && print.isPrettyPrint();
        var compressionConfig = properties.getRest().getCompression();
        this.compression = compressionConfig != null && compressionConfig.isEnabled() ? compressionConfig : null;
        this.compressionMinSize = this.compression == null || this.compression.getMinSize() == null
                ? 0
                : this.compression.getMinSize().toBytes();
        this.resourceEtags = properties.getRest().isResourceEtags();
        this.endStreamsAtTokenExpiry = properties.getSecurity().isEndStreamsAtTokenExpiry();
    }

    /** {@code shiguchi.security.end-streams-at-token-expiry}. */
    private final boolean endStreamsAtTokenExpiry;

    @Override
    public void setEnvironment(org.springframework.core.env.Environment environment) {
        this.serverCompression = environment.getProperty("server.compression.enabled", Boolean.class, false);
    }

    /** The response cache ({@code rest.response-cache}) and request coalescing. */
    @Autowired(required = false)
    public void setResponseCache(ResponseCache responseCache) {
        this.responseCache = responseCache;
    }

    /**
     * Ends a server stream opened with the BFF session cookie when its session ends; null when the
     * filter is wired by hand without one, which binds nothing.
     */
    private dev.appiaworks.shiguchi.security.CookieSessionWatch sessionWatch;

    @Autowired(required = false)
    public void setSessionWatch(dev.appiaworks.shiguchi.security.CookieSessionWatch sessionWatch) {
        this.sessionWatch = sessionWatch;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String httpMethod = exchange.getRequest().getMethod().name();
        String path = originalRequestPath(exchange);

        Optional<RouteDefinition> routeOpt = registry.findByHttpKey(httpMethod, path);
        if (routeOpt.isEmpty()) {
            return chain.filter(exchange); // not a gRPC route — let SCG handle it
        }

        RouteDefinition route = routeOpt.get();
        ServiceEndpointContext ctx = registry.contextFor(route);
        exchange.getAttributes()
                .put(GatewayAccessLogFilter.ROUTE_ATTR, route.getFullServiceName() + "/" + route.getMethodName());

        Map<String, String> pathVars = pathMatcher.extractVars(route, path);
        // First value per name, as everything but a repeated field reads it; the request builder
        // takes every value of a repeated name from the same map.
        Map<String, String> queryParams = dev.appiaworks.shiguchi.rest.QueryParameters.of(
                exchange.getRequest().getQueryParams());

        // X-Fields is an alias for ?fields= (some generated clients cannot add query params).
        // Folded in here so the request builder sees a single spelling; the query param wins.
        String fieldsHeader = exchange.getRequest().getHeaders().getFirst(FIELDS_HEADER);
        if (fieldsHeader != null && !fieldsHeader.isBlank()) {
            queryParams.putIfAbsent("fields", fieldsHeader);
        }

        // HTTP passthrough (REST_DOWNSTREAM): authorize on the route's scopes, then hand the
        // untouched request to SCG, whose routing filter proxies it to the backend. The body is
        // not read here — consuming it would leave nothing for the proxy to forward.
        if (route.isPassthrough()) {
            return authEnforcer
                    .enforce(route, authArguments(pathVars, queryParams), null)
                    .then(chain.filter(exchange))
                    .onErrorResume(AccessDeniedException.class, ex -> writeAccessDeniedResponse(exchange, ex));
        }

        Mono<Void> rejected = rejectUnknownQueryParameters(exchange, route, ctx);
        if (rejected != null) {
            return rejected;
        }

        Mono<Void> handled = handle(exchange, route, ctx, pathVars, queryParams);
        if (!route.isMerge()) handled = forwardResponseHeaders(exchange, route, ctx, handled);
        return withClientDeadline(exchange, handled);
    }

    /**
     * The caller's {@code grpc-timeout}, as a deadline counted from now, for every backend call the
     * request makes; the dispatcher keeps it only where it is sooner than the configured deadline.
     */
    private Mono<Void> withClientDeadline(ServerWebExchange exchange, Mono<Void> handled) {
        if (!honorGrpcTimeout) return handled;
        java.time.Duration timeout =
                GrpcWebFilter.parseTimeout(exchange.getRequest().getHeaders().getFirst("grpc-timeout"));
        if (timeout == null) return handled;
        io.grpc.Deadline deadline =
                io.grpc.Deadline.after(timeout.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
        return handled.contextWrite(c -> c.put(io.grpc.Deadline.class, deadline));
    }

    /** Whether this service's JSON bodies are indented: its {@code json-print} override, else global. */
    private boolean prettyPrint(ServiceEndpointContext ctx) {
        var override = ctx == null ? null : ctx.getConfig().getJsonPrint();
        return override != null && override.getPrettyPrint() != null ? override.getPrettyPrint() : prettyPrint;
    }

    /** {@code json} indented when the service prints pretty; unchanged otherwise. */
    private byte[] printed(ServiceEndpointContext ctx, byte[] json) {
        return prettyPrint(ctx) ? PrettyJson.indent(json) : json;
    }

    /**
     * Copies the backend's response headers and trailers named by {@code forward-response-headers}
     * onto the HTTP response. The dispatcher attaches the capture from the subscriber context to
     * every call it makes, and the copy runs as the response commits. A call answering with one
     * message has ended by then, so its trailers go too; a server stream commits at its first
     * message, before any trailer exists, so it sends headers only — always, rather than
     * trailers too whenever a short stream happens to finish first.
     */
    private Mono<Void> forwardResponseHeaders(
            ServerWebExchange exchange, RouteDefinition route, ServiceEndpointContext ctx, Mono<Void> handled) {
        ResponseHeaderForwarder forwarder =
                ResponseHeaderForwarder.of(ctx.getConfig().effectiveForwardResponseHeaders(forwardResponseHeaders));
        if (forwarder == null) return handled;
        var capture = new dev.appiaworks.shiguchi.grpc.ResponseMetadataCapture();
        boolean withTrailers = route.getMethodType().serverSendsOneMessage();
        exchange.getResponse().beforeCommit(() -> {
            forwarder.apply(capture, exchange.getResponse().getHeaders(), withTrailers);
            return Mono.empty();
        });
        return handled.contextWrite(c -> c.put(dev.appiaworks.shiguchi.grpc.ResponseMetadataCapture.class, capture));
    }

    private Mono<Void> handle(
            ServerWebExchange exchange,
            RouteDefinition route,
            ServiceEndpointContext ctx,
            Map<String, String> pathVars,
            Map<String, String> queryParams) {
        // google.api.HttpBody on either side: raw bytes, any content type. Decided before content
        // negotiation, which only knows JSON and the codecs and would refuse an image/png upload
        // or an Accept: text/csv that the RPC itself answers.
        var md = route.isMerge()
                ? null
                : ctx.lookupMethod(route.getFullServiceName(), route.getMethodName())
                        .orElse(null);
        {
            if (md != null) {
                boolean rawIn = HttpBodySupport.rawRequest(md.getInputType(), route.getHttpBody());
                switch (route.getMethodType()) {
                    case UNARY -> {
                        if (rawIn || HttpBodySupport.rawResponse(md.getOutputType(), route.getHttpResponseBody())) {
                            return handleHttpBody(exchange, route, ctx, md, pathVars, queryParams);
                        }
                    }
                    // A stream of HttpBody out is a download sent as it arrives; response_body does
                    // not apply to streams, so only the output type itself counts.
                    case SERVER_STREAMING -> {
                        if (HttpBodySupport.isHttpBody(md.getOutputType())) {
                            return handleHttpBodyDownload(exchange, route, ctx, md, pathVars, queryParams, rawIn);
                        }
                    }
                    // A stream of HttpBody in is an upload: every chunk of the HTTP body is a message.
                    case CLIENT_STREAMING -> {
                        if (rawIn) {
                            return handleHttpBodyUpload(exchange, route, ctx, md, pathVars, queryParams);
                        }
                    }
                    default -> {}
                }
            }
        }

        // A caller speaking a codec's media type (Avro, ...) takes the Map path: body decoded by the
        // codec, response encoded by it, pagination still read off the message. JSON callers stay on
        // the historical path below, untouched.
        Negotiated negotiated;
        try {
            negotiated = negotiate(exchange.getRequest().getHeaders());
        } catch (UnsupportedMediaType e) {
            return writeRenderedError(
                    exchange,
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    io.grpc.Status.INVALID_ARGUMENT.withDescription(e.getMessage()));
        } catch (NotAcceptable e) {
            return writeRenderedError(
                    exchange,
                    HttpStatus.NOT_ACCEPTABLE,
                    io.grpc.Status.INVALID_ARGUMENT.withDescription(e.getMessage()));
        }
        // response_body sends one field of the response, so it needs the decoded message, which the
        // JSON path below never builds (Tier 0 writes JSON straight off the wire). The codec path
        // with no codec is exactly that for a unary route: JSON in, message, one field out as JSON.
        boolean unaryResponseBody = route.getHttpResponseBody() != null
                && !route.isMerge()
                && route.getMethodType() == io.grpc.MethodDescriptor.MethodType.UNARY;
        // A resource etag is a field of the decoded message, and If-Match fills one in the request:
        // both need the message, like response_body.
        boolean resourceEtagRoute = resourceEtags
                && md != null
                && route.getMethodType() == io.grpc.MethodDescriptor.MethodType.UNARY
                && (ResourceEtags.hasEtag(md.getOutputType())
                        || ResourceEtags.requestField(md.getInputType(), route.getHttpBody()) != null);
        if (negotiated.requestCodec() != null
                || negotiated.responseCodec() != null
                || unaryResponseBody
                || resourceEtagRoute) {
            return handleWithCodecs(exchange, route, ctx, pathVars, queryParams, negotiated);
        }

        // A client-streaming route must not have its body aggregated first: the point of the
        // NDJSON request is that an upload larger than memory is sent message by message.
        if (route.getMethodType() == io.grpc.MethodDescriptor.MethodType.CLIENT_STREAMING) {
            return writeClientStreamingResponse(exchange, route, ctx, pathVars, queryParams);
        }

        return readBody(exchange)
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .flatMap(body -> authEnforcer
                        .enforce(route, authArguments(pathVars, queryParams), body)
                        .then(metadataPopulator.build(exchange.getRequest()))
                        .flatMap(metadata -> {
                            if (route.isMerge()) {
                                return writeMergeResponse(exchange, route, ctx, pathVars, queryParams, body, metadata);
                            }
                            if (route.getMethodType() == io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING) {
                                return endSignal()
                                        .flatMap(end -> writeStreamingResponse(
                                                exchange, route, ctx, pathVars, queryParams, body, metadata, end));
                            }
                            return sendUnary(
                                            exchange,
                                            route,
                                            ctx,
                                            metadata,
                                            "application/json",
                                            body,
                                            dispatcher
                                                    .dispatch(route, ctx, pathVars, queryParams, body, metadata)
                                                    .map(response -> renderJson(ctx, response)))
                                    .onErrorResume(
                                            StatusRuntimeException.class, ex -> writeErrorResponse(exchange, ctx, ex));
                        })
                        .onErrorResume(AccessDeniedException.class, ex -> writeAccessDeniedResponse(exchange, ex)))
                .onErrorResume(PayloadTooLargeException.class, ex -> writePayloadTooLarge(exchange, ex));
    }

    /**
     * Client-streaming routes: an {@code application/x-ndjson} request body, one JSON message per
     * line, answered with the backend's single JSON response.
     *
     * <p>The body is never aggregated. Lines are cut out of the incoming buffers as they arrive
     * and handed to the dispatcher, so an upload larger than memory streams through the gateway
     * — which is the whole reason a caller would use a client-streaming method. That also means
     * {@code rest.max-request-body} cannot apply to the body as a whole; it bounds a single line
     * instead, so one unterminated line cannot buffer without limit.
     *
     * <p>Authorization runs before the first line, on the route and its path/query arguments
     * alone. A body-aware authorizer sees no body here: there is no whole body to show it, and
     * waiting for one would defeat the streaming.
     */
    private Mono<Void> writeClientStreamingResponse(
            ServerWebExchange exchange,
            RouteDefinition route,
            ServiceEndpointContext ctx,
            Map<String, String> pathVars,
            Map<String, String> queryParams) {

        Flux<String> lines = ndjsonLines(exchange);

        return authEnforcer
                .enforce(route, authArguments(pathVars, queryParams), null)
                .then(metadataPopulator.build(exchange.getRequest()))
                .flatMap(metadata -> dispatcher
                        .dispatchClientStreaming(route, ctx, pathVars, queryParams, lines, metadata)
                        .flatMap(response -> send(exchange, route, ctx, renderJson(ctx, response), null)))
                .onErrorResume(StatusRuntimeException.class, ex -> writeErrorResponse(exchange, ctx, ex))
                .onErrorResume(AccessDeniedException.class, ex -> writeAccessDeniedResponse(exchange, ex))
                .onErrorResume(PayloadTooLargeException.class, ex -> writePayloadTooLarge(exchange, ex));
    }

    /**
     * Cuts the request body into newline-delimited strings without aggregating it.
     *
     * <p>A buffer boundary can fall anywhere, including mid-character in a multi-byte UTF-8
     * sequence, so bytes are carried over rather than decoded per buffer. Anything left after the
     * last newline is emitted as a final line, which makes a body with no trailing newline work
     * the way a caller would expect.
     */
    private Flux<String> ndjsonLines(ServerWebExchange exchange) {
        var pending = new java.io.ByteArrayOutputStream();
        return exchange.getRequest()
                .getBody()
                .concatMap(buffer -> {
                    byte[] chunk = new byte[buffer.readableByteCount()];
                    buffer.read(chunk);
                    DataBufferUtils.release(buffer);

                    List<String> complete = new java.util.ArrayList<>();
                    for (byte b : chunk) {
                        if (b == '\n') {
                            complete.add(takeLine(pending));
                        } else {
                            if (pending.size() >= maxRequestBody) {
                                return Flux.error(new PayloadTooLargeException(pending.size(), maxRequestBody));
                            }
                            pending.write(b);
                        }
                    }
                    return Flux.fromIterable(complete);
                })
                .concatWith(Flux.defer(() -> pending.size() == 0 ? Flux.empty() : Flux.just(takeLine(pending))));
    }

    /** Drains the pending bytes as one UTF-8 line, trimming a CR from a CRLF ending. */
    private static String takeLine(java.io.ByteArrayOutputStream pending) {
        byte[] bytes = pending.toByteArray();
        pending.reset();
        int length = (bytes.length > 0 && bytes[bytes.length - 1] == '\r') ? bytes.length - 1 : bytes.length;
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- request body

    /**
     * The whole request body, capped at {@code shiguchi.rest.max-request-body}: a body that
     * declares more via {@code Content-Length} is refused without reading it, one that turns out
     * larger while being read is refused at the byte it crosses the cap. Empty when there is none.
     */
    private Mono<byte[]> readBody(ServerWebExchange exchange) {
        long declared = exchange.getRequest().getHeaders().getContentLength();
        if (declared > maxRequestBody) {
            return Mono.error(new PayloadTooLargeException(declared, maxRequestBody));
        }
        return DataBufferUtils.join(exchange.getRequest().getBody(), (int) Math.min(maxRequestBody, Integer.MAX_VALUE))
                .map(buf -> {
                    byte[] bytes = new byte[buf.readableByteCount()];
                    buf.read(bytes);
                    DataBufferUtils.release(buf);
                    return bytes;
                })
                .defaultIfEmpty(new byte[0])
                .onErrorMap(
                        org.springframework.core.io.buffer.DataBufferLimitException.class,
                        e -> new PayloadTooLargeException(-1, maxRequestBody));
    }

    private Mono<Void> writePayloadTooLarge(ServerWebExchange exchange, PayloadTooLargeException ex) {
        return writeRenderedError(
                exchange,
                HttpStatus.PAYLOAD_TOO_LARGE,
                io.grpc.Status.RESOURCE_EXHAUSTED.withDescription(ex.getMessage()));
    }

    /** A request body over {@code shiguchi.rest.max-request-body}. */
    static final class PayloadTooLargeException extends RuntimeException {
        PayloadTooLargeException(long declared, long limit) {
            super(
                    declared >= 0
                            ? "Request body of " + declared + " bytes exceeds the limit of " + limit + " bytes"
                            : "Request body exceeds the limit of " + limit + " bytes");
        }
    }

    // ---------------------------------------------------------------- codec negotiation

    /** Outcome of content negotiation: null means JSON on that side. */
    record Negotiated(RestBodyCodec requestCodec, RestBodyCodec responseCodec) {
        static final Negotiated JSON = new Negotiated(null, null);
    }

    static final class UnsupportedMediaType extends RuntimeException {
        UnsupportedMediaType(String message) {
            super(message);
        }
    }

    static final class NotAcceptable extends RuntimeException {
        NotAcceptable(String message) {
            super(message);
        }
    }

    /**
     * Request codec from {@code Content-Type}, response codec from {@code Accept} (highest quality
     * first). JSON, {@code +json} and wildcards select JSON; a type no codec speaks is 415 for the
     * request and 406 for the response. Without codecs nothing is negotiated, so a JSON-only
     * gateway behaves exactly as before, whatever the headers say.
     */
    Negotiated negotiate(HttpHeaders headers) {
        if (codecs.isEmpty()) return Negotiated.JSON;

        RestBodyCodec requestCodec = null;
        MediaType contentType = headers.getContentType();
        if (contentType != null && !isJson(contentType) && !contentType.isWildcardType()) {
            requestCodec = codecFor(bare(contentType));
            if (requestCodec == null
                    && contentType.getType().equals("application")
                    && (contentType.getSubtype().contains("avro")
                            || contentType.getSubtype().contains("protobuf"))) {
                throw new UnsupportedMediaType("Unsupported request Content-Type " + bare(contentType));
            }
            if (requestCodec == null && contentType.getType().equals("avro")) {
                throw new UnsupportedMediaType("Unsupported request Content-Type " + bare(contentType));
            }
        }

        RestBodyCodec responseCodec = null;
        List<MediaType> accept = new java.util.ArrayList<>(headers.getAccept());
        if (!accept.isEmpty()) {
            // Spring 7 dropped MediaType.sortByQualityValue; a stable sort keeps declaration order for ties.
            accept.sort(java.util.Comparator.comparingDouble(MediaType::getQualityValue)
                    .reversed());
            boolean matched = false;
            for (MediaType mt : accept) {
                if (mt.getQualityValue() <= 0) continue;
                // The streaming framings (NDJSON, Server-Sent Events) carry JSON too; which one a
                // streaming route uses is settled in writeStreamingResponse, not by a codec.
                if (mt.isWildcardType()
                        || isJson(mt)
                        || isStreamingJson(mt)
                        || (mt.getType().equals("application") && mt.isWildcardSubtype())) {
                    matched = true;
                    break;
                }
                RestBodyCodec c = codecFor(bare(mt));
                if (c != null) {
                    responseCodec = c;
                    matched = true;
                    break;
                }
            }
            if (!matched)
                throw new NotAcceptable(
                        "None of the requested media types is supported: " + headers.getFirst(HttpHeaders.ACCEPT));
        }
        return new Negotiated(requestCodec, responseCodec);
    }

    private RestBodyCodec codecFor(String bareMediaType) {
        for (RestBodyCodec c : codecs) if (c.supports(bareMediaType)) return c;
        return null;
    }

    private static boolean isJson(MediaType mt) {
        return MediaType.APPLICATION_JSON.isCompatibleWith(mt)
                || mt.getSubtype().endsWith("+json");
    }

    /** {@code application/x-ndjson} or {@code text/event-stream}, named explicitly (not by wildcard). */
    private static boolean isStreamingJson(MediaType mt) {
        return !mt.isWildcardType()
                && !mt.isWildcardSubtype()
                && (mt.equalsTypeAndSubtype(MediaType.APPLICATION_NDJSON)
                        || mt.equalsTypeAndSubtype(MediaType.TEXT_EVENT_STREAM));
    }

    /**
     * Whether a server-streaming response goes out as Server-Sent Events: the caller's highest
     * ranked {@code Accept} entry decides — {@code text/event-stream} for SSE, NDJSON or JSON for
     * NDJSON — and a wildcard or no header falls back to {@code rest.streaming.default-format}.
     */
    boolean streamsAsSse(HttpHeaders headers) {
        List<MediaType> accept = new java.util.ArrayList<>(headers.getAccept());
        // Stable sort: equal quality keeps the caller's order.
        accept.sort(
                java.util.Comparator.comparingDouble(MediaType::getQualityValue).reversed());
        for (MediaType mt : accept) {
            if (mt.getQualityValue() <= 0) continue;
            if (mt.isWildcardType() || mt.isWildcardSubtype()) break;
            if (mt.equalsTypeAndSubtype(MediaType.TEXT_EVENT_STREAM)) return true;
            if (mt.equalsTypeAndSubtype(MediaType.APPLICATION_NDJSON) || isJson(mt)) return false;
        }
        return defaultStreamFormat == dev.appiaworks.shiguchi.config.RestConfig.StreamFormat.SSE;
    }

    private static String bare(MediaType mt) {
        return (mt.getType() + "/" + mt.getSubtype()).toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * The codec path for a unary route: body bytes → codec → request Map (path variables and
     * query parameters overlaid as for JSON) → gRPC → response message → codec bytes. Streaming
     * and merge routes are JSON / NDJSON only.
     *
     * <p>Also taken with no codec at all by a JSON caller of a unary route that declares
     * {@code response_body}: both codecs are then null, the body is parsed as JSON, and
     * {@link #writeCodecResponse} sends the named field.
     */
    private Mono<Void> handleWithCodecs(
            ServerWebExchange exchange,
            RouteDefinition route,
            ServiceEndpointContext ctx,
            Map<String, String> pathVars,
            Map<String, String> queryParams,
            Negotiated negotiated) {
        boolean unary =
                !route.isMerge() && route.getMethodType() != io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING;
        if (!unary) {
            if (negotiated.responseCodec() != null) {
                return writeRenderedError(
                        exchange,
                        HttpStatus.NOT_ACCEPTABLE,
                        io.grpc.Status.INVALID_ARGUMENT.withDescription(
                                "Streaming and merge routes answer application/json or application/x-ndjson only"));
            }
            return writeRenderedError(
                    exchange,
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    io.grpc.Status.INVALID_ARGUMENT.withDescription(
                            "Streaming and merge routes accept application/json request bodies only"));
        }
        com.google.protobuf.Descriptors.MethodDescriptor md = ctx.lookupMethod(
                        route.getFullServiceName(), route.getMethodName())
                .orElse(null);
        if (md == null) {
            return Mono.error(new IllegalStateException(
                    "Method not found: " + route.getFullServiceName() + "/" + route.getMethodName()));
        }

        ResourceEtags.IfMatch ifMatch =
                resourceEtags ? ResourceEtags.ifMatch(exchange.getRequest().getHeaders()) : null;
        if (ifMatch != null && ifMatch.multiple()) {
            return writeRenderedError(
                    exchange,
                    HttpStatus.BAD_REQUEST,
                    io.grpc.Status.INVALID_ARGUMENT.withDescription(
                            "If-Match names more than one entity tag; the request's etag field holds one"));
        }
        String etagField = ifMatch == null || ifMatch.tag() == null
                ? null
                : ResourceEtags.requestField(md.getInputType(), route.getHttpBody());

        return readBody(exchange)
                .flatMap(bytes -> {
                    Map<String, Object> requestMap;
                    String bodyForAuth;
                    try {
                        if (negotiated.requestCodec() != null) {
                            Map<String, Object> decoded = bytes.length == 0
                                    ? Map.of()
                                    : negotiated.requestCodec().decode(bytes, md.getInputType());
                            requestMap = requestBuilder.buildRequestMapFromDecoded(
                                    md.getInputType(), pathVars, queryParams, decoded, route.getHttpBody());
                            bodyForAuth = null; // the authorizer sees arguments, not opaque bytes
                        } else {
                            String body = new String(bytes, StandardCharsets.UTF_8);
                            requestMap = requestBuilder.buildRequestMap(
                                    md.getInputType(), pathVars, queryParams, body, route.getHttpBody());
                            bodyForAuth = body;
                        }
                        if (etagField != null) ResourceEtags.applyIfMatch(requestMap, etagField, ifMatch.tag());
                    } catch (StatusRuntimeException e) {
                        // A request-shape check (conflicting values) with its BadRequest details.
                        return writeErrorResponse(exchange, ctx, e);
                    } catch (Exception e) {
                        return writeRenderedError(
                                exchange,
                                HttpStatus.BAD_REQUEST,
                                io.grpc.Status.INVALID_ARGUMENT.withDescription("Request body: " + e.getMessage()));
                    }
                    RestBodyCodec responseCodec = negotiated.responseCodec();
                    return authEnforcer
                            .enforce(route, authArguments(pathVars, queryParams), bodyForAuth)
                            .then(metadataPopulator.build(exchange.getRequest()))
                            .flatMap(metadata -> sendUnary(
                                    exchange,
                                    route,
                                    ctx,
                                    metadata,
                                    responseCodec == null ? "application/json" : responseCodec.mediaType(),
                                    bodyForAuth != null ? bodyForAuth : bytes.length == 0 ? "" : null,
                                    dispatcher
                                            .dispatchMessage(route, ctx, requestMap, metadata)
                                            .handle((message, sink) -> {
                                                try {
                                                    sink.next(renderCodec(route, ctx, md, message, responseCodec));
                                                } catch (StatusRuntimeException e) {
                                                    sink.error(e);
                                                }
                                            })))
                            .onErrorResume(
                                    StatusRuntimeException.class,
                                    ex -> ifMatch != null && isPreconditionFailure(ex)
                                            ? writeRenderedError(
                                                    exchange,
                                                    HttpStatus.PRECONDITION_FAILED,
                                                    ex.getStatus(),
                                                    errorDetails(ctx, ex))
                                            : writeErrorResponse(exchange, ctx, ex))
                            .onErrorResume(AccessDeniedException.class, ex -> writeAccessDeniedResponse(exchange, ex));
                })
                .onErrorResume(PayloadTooLargeException.class, ex -> writePayloadTooLarge(exchange, ex));
    }

    /**
     * A unary route with {@code google.api.HttpBody} on either side (see {@link HttpBodySupport}).
     *
     * <p>Request: a raw body is kept as bytes — never decoded as UTF-8 text — and placed in the
     * {@code HttpBody} with the request's {@code Content-Type}; path variables and query parameters
     * bind the rest of the message. Otherwise the body is JSON as usual. The authorizer sees the
     * path and query arguments, not the raw bytes.
     *
     * <p>Response: an {@code HttpBody} answer is sent as its {@code data} with its
     * {@code content_type} ({@code application/octet-stream} when empty or unparsable); any other
     * answer is JSON, {@code response_body} included.
     */
    private Mono<Void> handleHttpBody(
            ServerWebExchange exchange,
            RouteDefinition route,
            ServiceEndpointContext ctx,
            com.google.protobuf.Descriptors.MethodDescriptor md,
            Map<String, String> pathVars,
            Map<String, String> queryParams) {
        boolean rawIn = HttpBodySupport.rawRequest(md.getInputType(), route.getHttpBody());
        boolean rawOut = HttpBodySupport.rawResponse(md.getOutputType(), route.getHttpResponseBody());

        return readBody(exchange)
                .flatMap(bytes -> {
                    com.google.protobuf.DynamicMessage request;
                    String bodyForAuth;
                    try {
                        if (rawIn) {
                            Map<String, Object> rest = requestBuilder.buildRequestMapFromDecoded(
                                    md.getInputType(), pathVars, queryParams, Map.of(), null);
                            MediaType contentType =
                                    exchange.getRequest().getHeaders().getContentType();
                            request = HttpBodySupport.withBody(
                                    serdeSelector.deserializeFromMap(rest, md.getInputType(), ctx),
                                    route.getHttpBody(),
                                    contentType == null ? "" : contentType.toString(),
                                    bytes);
                            bodyForAuth = null;
                        } else {
                            String body = new String(bytes, StandardCharsets.UTF_8);
                            request = serdeSelector.deserializeFromMap(
                                    requestBuilder.buildRequestMap(
                                            md.getInputType(), pathVars, queryParams, body, route.getHttpBody()),
                                    md.getInputType(),
                                    ctx);
                            bodyForAuth = body;
                        }
                    } catch (StatusRuntimeException e) {
                        return writeErrorResponse(exchange, ctx, e);
                    } catch (Exception e) {
                        return writeRenderedError(
                                exchange,
                                HttpStatus.BAD_REQUEST,
                                io.grpc.Status.INVALID_ARGUMENT.withDescription("Request body: " + e.getMessage()));
                    }
                    return authEnforcer
                            .enforce(route, authArguments(pathVars, queryParams), bodyForAuth)
                            .then(metadataPopulator.build(exchange.getRequest()))
                            .flatMap(metadata -> sendUnary(
                                    exchange,
                                    route,
                                    ctx,
                                    metadata,
                                    "http-body",
                                    // A raw upload is not a cache key; only a bodiless or JSON request is.
                                    rawIn ? (bytes.length == 0 ? "" : null) : bodyForAuth,
                                    dispatcher
                                            .dispatchRequest(route, ctx, request, metadata)
                                            .handle((message, sink) -> {
                                                try {
                                                    sink.next(
                                                            rawOut
                                                                    ? renderHttpBody(route, message)
                                                                    : renderCodec(route, ctx, md, message, null));
                                                } catch (StatusRuntimeException e) {
                                                    sink.error(e);
                                                }
                                            })))
                            .onErrorResume(StatusRuntimeException.class, ex -> writeErrorResponse(exchange, ctx, ex))
                            .onErrorResume(AccessDeniedException.class, ex -> writeAccessDeniedResponse(exchange, ex));
                })
                .onErrorResume(PayloadTooLargeException.class, ex -> writePayloadTooLarge(exchange, ex));
    }

    /**
     * A server-streaming method answering {@code stream google.api.HttpBody}: a download. The
     * request is built as for a unary {@code HttpBody} route (raw bytes when the input is an
     * {@code HttpBody}, JSON otherwise). The first message's {@code content_type} becomes the
     * response's {@code Content-Type}, and every message's {@code data} is written and flushed as
     * it arrives, at the pace the caller reads.
     *
     * <p>A failure before the first message is an ordinary HTTP error. After it the status and
     * part of the body are already out, and raw bytes have no in-band error form, so the
     * connection is aborted instead of ending cleanly: the caller sees a truncated transfer,
     * never a file that merely looks complete.
     */
    private Mono<Void> handleHttpBodyDownload(
            ServerWebExchange exchange,
            RouteDefinition route,
            ServiceEndpointContext ctx,
            com.google.protobuf.Descriptors.MethodDescriptor md,
            Map<String, String> pathVars,
            Map<String, String> queryParams,
            boolean rawIn) {
        return readBody(exchange)
                .flatMap(bytes -> {
                    com.google.protobuf.DynamicMessage request;
                    String bodyForAuth;
                    try {
                        if (rawIn) {
                            MediaType contentType =
                                    exchange.getRequest().getHeaders().getContentType();
                            request = HttpBodySupport.withBody(
                                    httpBodyBase(md, ctx, pathVars, queryParams),
                                    route.getHttpBody(),
                                    contentType == null ? "" : contentType.toString(),
                                    bytes);
                            bodyForAuth = null;
                        } else {
                            bodyForAuth = new String(bytes, StandardCharsets.UTF_8);
                            request = serdeSelector.deserializeFromMap(
                                    requestBuilder.buildRequestMap(
                                            md.getInputType(), pathVars, queryParams, bodyForAuth, route.getHttpBody()),
                                    md.getInputType(),
                                    ctx);
                        }
                    } catch (StatusRuntimeException e) {
                        return writeErrorResponse(exchange, ctx, e);
                    } catch (Exception e) {
                        return writeRenderedError(
                                exchange,
                                HttpStatus.BAD_REQUEST,
                                io.grpc.Status.INVALID_ARGUMENT.withDescription("Request body: " + e.getMessage()));
                    }
                    return authEnforcer
                            .enforce(route, authArguments(pathVars, queryParams), bodyForAuth)
                            .then(metadataPopulator.build(exchange.getRequest()))
                            .flatMap(metadata -> endSignal()
                                    .flatMap(end -> writeHttpBodyStream(
                                            exchange,
                                            sessionBound(
                                                    dispatcher.dispatchStreamingRequest(route, ctx, request, metadata),
                                                    end))))
                            .onErrorResume(
                                    StatusRuntimeException.class,
                                    ex -> exchange.getResponse().isCommitted()
                                            ? Mono.error(ex)
                                            : writeErrorResponse(exchange, ctx, ex))
                            .onErrorResume(AccessDeniedException.class, ex -> writeAccessDeniedResponse(exchange, ex));
                })
                .onErrorResume(PayloadTooLargeException.class, ex -> writePayloadTooLarge(exchange, ex));
    }

    private Mono<Void> writeHttpBodyStream(
            ServerWebExchange exchange, Flux<com.google.protobuf.DynamicMessage> messages) {
        var factory = exchange.getResponse().bufferFactory();
        var written = new java.util.concurrent.atomic.AtomicLong();
        Flux<DataBuffer> chunks = messages.switchOnFirst((first, all) -> {
                    if (first.hasValue()) {
                        // Headers go out with the first chunk, so they are set before it is emitted.
                        HttpBodySupport.Payload head = HttpBodySupport.payload(first.get(), null);
                        exchange.getResponse().getHeaders().setContentType(mediaTypeOrOctetStream(head.contentType()));
                        exchange.getResponse().setStatusCode(HttpStatus.OK);
                    }
                    return all;
                })
                .map(message -> HttpBodySupport.payload(message, null).data())
                // The cap counts the download as written: over it before the first chunk is a 502,
                // after it the transfer is aborted like any other mid-download failure.
                .handle((byte[] data, reactor.core.publisher.SynchronousSink<byte[]> sink) -> {
                    long total = written.addAndGet(data.length);
                    if (total > maxResponseBody) {
                        sink.error(responseTooLarge(-1));
                    } else {
                        sink.next(data);
                    }
                })
                .map(factory::wrap)
                .doOnComplete(() -> exchange.getAttributes().put(GatewayAccessLogFilter.GRPC_STATUS_ATTR, "OK"));
        // An empty stream is an empty 200 with the default type rather than no response at all.
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_OCTET_STREAM);
        exchange.getResponse().setStatusCode(HttpStatus.OK);
        return exchange.getResponse().writeAndFlushWith(chunks.map(Flux::just));
    }

    /**
     * A client-streaming method taking {@code stream google.api.HttpBody} (or whose {@code body}
     * names an {@code HttpBody} field): an upload. The HTTP body is never aggregated; each chunk
     * that arrives becomes one message, carrying the request's {@code Content-Type}, with path
     * and query arguments bound on every message as they are for an NDJSON upload. The backend's
     * answer is sent like a unary one: raw when it is an {@code HttpBody}, JSON otherwise.
     *
     * <p>{@code rest.max-request-body} does not bound the upload, which is the point of streaming
     * it; the backend's own inbound limit bounds each chunk, and chunks are small.
     */
    private Mono<Void> handleHttpBodyUpload(
            ServerWebExchange exchange,
            RouteDefinition route,
            ServiceEndpointContext ctx,
            com.google.protobuf.Descriptors.MethodDescriptor md,
            Map<String, String> pathVars,
            Map<String, String> queryParams) {
        com.google.protobuf.DynamicMessage base;
        try {
            base = httpBodyBase(md, ctx, pathVars, queryParams);
        } catch (StatusRuntimeException e) {
            return writeErrorResponse(exchange, ctx, e);
        } catch (Exception e) {
            return writeRenderedError(
                    exchange,
                    HttpStatus.BAD_REQUEST,
                    io.grpc.Status.INVALID_ARGUMENT.withDescription("Request: " + e.getMessage()));
        }
        MediaType contentType = exchange.getRequest().getHeaders().getContentType();
        String type = contentType == null ? "" : contentType.toString();
        // Resolved once per stream, not per chunk.
        var bodyField = HttpBodySupport.namedBodyField(base.getDescriptorForType(), route.getHttpBody());
        Flux<com.google.protobuf.DynamicMessage> messages = exchange.getRequest()
                .getBody()
                .map(buffer -> {
                    byte[] chunk = new byte[buffer.readableByteCount()];
                    buffer.read(chunk);
                    DataBufferUtils.release(buffer);
                    return chunk;
                })
                .filter(chunk -> chunk.length > 0)
                .map(chunk -> bodyField != null
                        ? HttpBodySupport.withBody(base, bodyField, type, chunk)
                        : HttpBodySupport.withBody(base, route.getHttpBody(), type, chunk));
        boolean rawOut = HttpBodySupport.rawResponse(md.getOutputType(), route.getHttpResponseBody());
        return authEnforcer
                .enforce(route, authArguments(pathVars, queryParams), null)
                .then(metadataPopulator.build(exchange.getRequest()))
                .flatMap(metadata -> dispatcher.dispatchClientStreamingRequest(route, ctx, messages, metadata))
                .flatMap(message -> {
                    RenderedResponse rendered;
                    try {
                        rendered = rawOut ? renderHttpBody(route, message) : renderCodec(route, ctx, md, message, null);
                    } catch (StatusRuntimeException e) {
                        return Mono.error(e);
                    }
                    return send(exchange, route, ctx, rendered, null);
                })
                .onErrorResume(StatusRuntimeException.class, ex -> writeErrorResponse(exchange, ctx, ex))
                .onErrorResume(AccessDeniedException.class, ex -> writeAccessDeniedResponse(exchange, ex));
    }

    /** The request with path and query arguments bound, before the raw body is placed in it. */
    private com.google.protobuf.DynamicMessage httpBodyBase(
            com.google.protobuf.Descriptors.MethodDescriptor md,
            ServiceEndpointContext ctx,
            Map<String, String> pathVars,
            Map<String, String> queryParams)
            throws Exception {
        Map<String, Object> rest =
                requestBuilder.buildRequestMapFromDecoded(md.getInputType(), pathVars, queryParams, Map.of(), null);
        return serdeSelector.deserializeFromMap(rest, md.getInputType(), ctx);
    }

    /** An {@code HttpBody} answer: its {@code data} with its {@code content_type}. */
    private RenderedResponse renderHttpBody(RouteDefinition route, com.google.protobuf.DynamicMessage message) {
        HttpBodySupport.Payload payload = HttpBodySupport.payload(message, route.getHttpResponseBody());
        return new RenderedResponse(mediaTypeOrOctetStream(payload.contentType()), null, payload.data(), null);
    }

    /** The backend's {@code content_type}, or {@code application/octet-stream} when it gave none usable. */
    private static MediaType mediaTypeOrOctetStream(String contentType) {
        if (contentType == null || contentType.isBlank()) return MediaType.APPLICATION_OCTET_STREAM;
        try {
            return MediaType.parseMediaType(contentType);
        } catch (org.springframework.http.InvalidMediaTypeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    /**
     * A decoded answer as JSON — its {@code response_body} field or the whole message — or in the
     * caller's codec. Throws {@code INTERNAL} when the message cannot be encoded.
     */
    private RenderedResponse renderCodec(
            RouteDefinition route,
            ServiceEndpointContext ctx,
            com.google.protobuf.Descriptors.MethodDescriptor md,
            com.google.protobuf.DynamicMessage message,
            RestBodyCodec responseCodec) {
        byte[] body;
        MediaType contentType;
        HttpHeaders headers = new HttpHeaders();
        try {
            // google.api.http's response_body: send one field instead of the whole message. The
            // gRPC reply is unchanged — this only decides what reaches the HTTP caller.
            String responseBodyField = route.getHttpResponseBody();
            if (responseBodyField != null) {
                var printOptions = ctx.getPrintOptions();
                body = responseBodyBytes(
                        message,
                        md.getOutputType(),
                        responseBodyField,
                        printOptions != null
                                ? printOptions
                                : dev.appiaworks.protobuf.extras.serde.JsonPrintOptions.DEFAULT);
                body = printed(ctx, body);
                contentType = MediaType.APPLICATION_JSON;
            } else if (responseCodec != null) {
                Map<String, Object> map = serdeSelector.serializeToMap(message, md.getOutputType(), ctx);
                body = responseCodec.encode(map, md.getOutputType());
                contentType = MediaType.parseMediaType(responseCodec.mediaType());
                responseCodec.responseHeaders(md.getOutputType()).forEach(headers::set);
            } else {
                body = printed(
                        ctx, serdeSelector.serializeToBytes(message, md.getOutputType(), ctx, ctx.getTypeRegistry()));
                contentType = MediaType.APPLICATION_JSON;
            }
        } catch (Exception e) {
            throw io.grpc.Status.INTERNAL
                    .withDescription("Response could not be encoded: " + e.getMessage())
                    .asRuntimeException();
        }
        paginationExtractor.applyHeaders(message, headers);
        return new RenderedResponse(
                contentType, headers, body, resourceEtags ? ResourceEtags.responseTag(message) : null);
    }

    /**
     * The path the client actually requested. Route-level SCG filters such as {@code StripPrefix}
     * (used by REST_DOWNSTREAM passthrough routes) run before this global filter and rewrite the
     * request path, recording the original URL in
     * {@code ServerWebExchangeUtils.GATEWAY_ORIGINAL_REQUEST_URL_ATTR}. Routes are registered
     * under the client-facing path, so that is the one to match on.
     */
    private static String originalRequestPath(ServerWebExchange exchange) {
        java.util.Set<java.net.URI> originals = exchange.getAttributeOrDefault(
                org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ORIGINAL_REQUEST_URL_ATTR,
                java.util.Collections.emptySet());
        for (java.net.URI original : originals) {
            if (original.getRawPath() != null) return original.getRawPath();
        }
        return exchange.getRequest().getPath().value();
    }

    /**
     * HTTP 400 naming the query parameters that bind to no request field, when the route's service
     * asks for it ({@code rest.request-validation.reject-unknown-query-parameters}); {@code null}
     * lets the request through. Runs before the body is read, so a rejected request never reaches
     * the authorizer or the backend. Parameters are read off the request itself, in the order sent.
     */
    private Mono<Void> rejectUnknownQueryParameters(
            ServerWebExchange exchange, RouteDefinition route, ServiceEndpointContext ctx) {
        var names = exchange.getRequest().getQueryParams().keySet();
        if (names.isEmpty() || ctx == null || ctx.getConfig() == null) return null;
        if (!ctx.getConfig().isRejectUnknownQueryParameters(rejectUnknownQueryParameters)) return null;
        var md = ctx.lookupMethod(route.getFullServiceName(), route.getMethodName())
                .orElse(null);
        if (md == null) return null;
        List<String> unknown =
                QueryParameterValidator.unknownParameters(md.getInputType(), names, ignoredQueryParameters);
        if (unknown.isEmpty()) return null;
        var failure = QueryParameterValidator.failure(md.getInputType(), unknown);
        return writeRenderedError(
                exchange, HttpStatus.BAD_REQUEST, failure.status(), GatewayErrorDetails.of(List.of(failure.detail())));
    }

    /**
     * Normalised request inputs handed to the {@code GatewayAuthorizer}: path variables merged
     * with query parameters. Path variables win on key collision — they are structural to the
     * route, whereas a query parameter is caller-supplied and must not be able to shadow the
     * object id a per-object authorizer is about to check.
     */
    private static Map<String, Object> authArguments(Map<String, String> pathVars, Map<String, String> queryParams) {
        Map<String, Object> args = new java.util.HashMap<>(queryParams);
        args.putAll(pathVars);
        return args;
    }

    /** A JSON answer from the dispatcher, indented when the service prints pretty. */
    private RenderedResponse renderJson(
            ServiceEndpointContext ctx, dev.appiaworks.shiguchi.rest.RestResponse response) {
        byte[] body = prettyPrint(ctx) ? PrettyJson.indent(response.body()) : response.body();
        HttpHeaders headers = new HttpHeaders();
        // Pagination headers are read off the decoded message, no JSON re-parse. Tier 0 responses
        // carry no message, but they are only chosen when the response type has no page_info.
        if (response.message() != null) {
            paginationExtractor.applyHeaders(response.message(), headers);
        }
        return new RenderedResponse(MediaType.APPLICATION_JSON, headers, body, null);
    }

    /**
     * The JSON of one response field, as {@code google.api.http}'s {@code response_body} asks for.
     *
     * <p>Lets an RPC keep a wrapper message — which proto style and forward compatibility both
     * want, since a bare repeated field cannot gain a sibling later — while REST callers get the
     * thing itself: a {@code ListItems} that answers {@code {"items":[...]}} over gRPC can serve
     * a bare JSON array over HTTP.
     *
     * <p>Rendered through {@code JsonFormat} on a synthetic wrapper rather than by hand, so the
     * field follows exactly the same proto3 JSON rules as it would inside the full message:
     * 64-bit integers as strings, enums by name, well-known types in their JSON forms.
     */
    private static byte[] responseBodyBytes(
            com.google.protobuf.DynamicMessage message,
            com.google.protobuf.Descriptors.Descriptor output,
            String fieldName)
            throws java.io.IOException {
        return responseBodyBytes(
                message, output, fieldName, dev.appiaworks.protobuf.extras.serde.JsonPrintOptions.DEFAULT);
    }

    /** {@link #responseBodyBytes(com.google.protobuf.DynamicMessage, com.google.protobuf.Descriptors.Descriptor, String)} with the service's {@code json.print} options. */
    private static byte[] responseBodyBytes(
            com.google.protobuf.DynamicMessage message,
            com.google.protobuf.Descriptors.Descriptor output,
            String fieldName,
            dev.appiaworks.protobuf.extras.serde.JsonPrintOptions options)
            throws java.io.IOException {
        com.google.protobuf.Descriptors.FieldDescriptor field = output.findFieldByName(fieldName);
        if (field == null) {
            // A response_body naming a field that does not exist is a proto bug, and silently
            // sending the whole message would hide it behind a response that merely looks wrong.
            throw new IllegalStateException(
                    "response_body names '" + fieldName + "', which " + output.getFullName() + " does not have");
        }
        if (field.getJavaType() == com.google.protobuf.Descriptors.FieldDescriptor.JavaType.MESSAGE
                && dev.appiaworks.protobuf.extras.serde.ProtoJsonMapping.needsGatewaySerde(field.getMessageType())
                && !dev.appiaworks.shiguchi.serde.DynamicSerializerFactory.containsAny(field.getMessageType())) {
            return mappedResponseBodyBytes(message, field, options);
        }

        // JsonFormat prints messages, not values, so the field is printed inside a wrapper holding
        // only it, and then unwrapped. That keeps every formatting rule identical to the full
        // response — 64-bit integers as strings, enums by name, well-known types in their JSON
        // forms — rather than reimplementing them for this path.
        //
        // Default values are deliberately NOT included here: with them, the wrapper prints every
        // other field of the message too and there is no longer a single value to unwrap. The
        // one field that matters is set explicitly below, so it is printed whatever its value.
        var printer = com.google.protobuf.util.JsonFormat.printer().omittingInsignificantWhitespace();
        if (options.preserveProtoFieldNames()) printer = printer.preservingProtoFieldNames();
        if (options.enumsAsInts()) printer = printer.printingEnumsAsInts();
        String printed = dev.appiaworks.shiguchi.serde.ProtoJsonText.print(
                printer,
                com.google.protobuf.DynamicMessage.newBuilder(output)
                        .setField(field, message.getField(field))
                        .build());

        // The wrapper is {"<jsonName>":<value>}, so the value is what sits between the first
        // colon after the key and the closing brace. Locating the key at the very start rather
        // than with indexOf keeps a string value that happens to contain the key from matching.
        String key = "{\"" + options.fieldName(field) + "\":";
        if (!printed.startsWith(key) || !printed.endsWith("}")) {
            // An empty repeated field or an unset message prints as {} with defaults omitted.
            if ("{}".equals(printed)) {
                return emptyValueFor(field, options).getBytes(StandardCharsets.UTF_8);
            }
            throw new IllegalStateException("could not extract '" + fieldName + "' from the response");
        }
        String value = printed.substring(key.length(), printed.length() - 1);
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * {@link #responseBodyBytes} for a field that reaches a {@code google.type} message the
     * gateway writes as text ({@code shiguchi.json.scalar-types}): {@code JsonFormat} cannot
     * apply that mapping, so the value is written by the gateway's own converter, as the full
     * response would write it.
     */
    private static byte[] mappedResponseBodyBytes(
            com.google.protobuf.DynamicMessage message,
            com.google.protobuf.Descriptors.FieldDescriptor field,
            dev.appiaworks.protobuf.extras.serde.JsonPrintOptions options)
            throws java.io.IOException {
        if (!field.isRepeated() && !message.hasField(field)) {
            return emptyValueFor(field, options).getBytes(StandardCharsets.UTF_8);
        }
        var out = new java.io.ByteArrayOutputStream();
        try (var gen = RESPONSE_BODY_JSON.createGenerator(out)) {
            Object value = message.getField(field);
            if (field.isMapField()) {
                dev.appiaworks.protobuf.extras.serde.ProtoMapConverter.writeMapField(
                        field, (java.util.List<?>) value, gen, options);
            } else if (field.isRepeated()) {
                gen.writeStartArray();
                for (Object item : (java.util.List<?>) value) {
                    writeMessageValue(gen, (com.google.protobuf.DynamicMessage) item, options);
                }
                gen.writeEndArray();
            } else {
                writeMessageValue(gen, (com.google.protobuf.DynamicMessage) value, options);
            }
        }
        return out.toByteArray();
    }

    private static void writeMessageValue(
            com.fasterxml.jackson.core.JsonGenerator gen,
            com.google.protobuf.DynamicMessage value,
            dev.appiaworks.protobuf.extras.serde.JsonPrintOptions options)
            throws java.io.IOException {
        if (dev.appiaworks.protobuf.extras.serde.ProtoWkt.isWkt(value.getDescriptorForType())) {
            dev.appiaworks.protobuf.extras.serde.ProtoWkt.writeJson(gen, value);
        } else {
            dev.appiaworks.protobuf.extras.serde.ProtoMapConverter.writeMessageJson(gen, value, options);
        }
    }

    private static final com.fasterxml.jackson.core.JsonFactory RESPONSE_BODY_JSON =
            new com.fasterxml.jackson.core.JsonFactory();

    /**
     * What an unset field renders as once defaults are omitted from the wrapper.
     *
     * <p>A caller asking for {@code items} on an empty page should get {@code []}, not {@code {}}
     * and not nothing: the field's absence is the point of the response, and a client parsing
     * JSON should not have to special-case it.
     */
    private static String emptyValueFor(
            com.google.protobuf.Descriptors.FieldDescriptor field,
            dev.appiaworks.protobuf.extras.serde.JsonPrintOptions options) {
        if (field.isRepeated()) return "[]";
        return switch (field.getJavaType()) {
            case MESSAGE -> "{}";
            case STRING -> "\"\"";
            case BOOLEAN -> "false";
            case ENUM ->
                options.enumsAsInts()
                        ? Integer.toString(
                                field.getEnumType().getValues().get(0).getNumber())
                        : "\"" + field.getEnumType().getValues().get(0).getName() + "\"";
            case LONG -> "\"0\""; // 64-bit integers are strings in proto3 JSON
            default -> "0";
        };
    }

    // ---------------------------------------------------------------- unary answers: caching and send

    /** The route's cache policy, from its service's {@code cache} rules or its proto TTL; null = not cacheable. */
    private CachePolicy cachePolicy(RouteDefinition route, ServiceEndpointContext ctx) {
        return CachePolicy.resolve(
                route, ctx == null ? null : ctx.getConfig(), responseCache != null && responseCache.isEnabled());
    }

    /**
     * Sends the answer {@code loader} produces — through the response cache and request coalescing
     * when the request is a {@code GET} (or {@code HEAD}) of a unary route they apply to, directly
     * otherwise.
     *
     * @param format what the answer is encoded as, part of the cache key
     * @param body the request body as text for the cache key; null when it cannot key an entry
     *     (a raw upload), which sends the request past the cache
     */
    private Mono<Void> sendUnary(
            ServerWebExchange exchange,
            RouteDefinition route,
            ServiceEndpointContext ctx,
            io.grpc.Metadata metadata,
            String format,
            String body,
            Mono<RenderedResponse> loader) {
        CachePolicy policy = cachePolicy(route, ctx);
        if (responseCache != null
                && responseCache.applies(policy)
                && body != null
                && body.length() <= MAX_KEYED_BODY
                && isSafe(exchange)
                && !route.isMerge()
                && route.getMethodType().serverSendsOneMessage()
                && route.getMethodType().clientSendsOneMessage()) {
            ResponseCache.Key key = cacheKey(exchange, route, ctx, policy, metadata, format, body);
            return responseCache
                    .get(key, policy, withForwardedHeaders(route, ctx, loader))
                    .flatMap(served -> send(exchange, route, ctx, policy, served.response(), served));
        }
        return loader.flatMap(rendered -> send(exchange, route, ctx, policy, rendered, null));
    }

    /** A request body longer than this does not key a cache entry; a GET rarely has one at all. */
    private static final int MAX_KEYED_BODY = 4096;

    /**
     * The loader, with the backend's forwarded headers copied onto the answer once the call ends,
     * so an answer handed to another caller (a cache hit, a coalesced request) carries them too.
     * The caller whose call it was gets them again as its response commits; the forwarder skips a
     * name already set, so they are not doubled.
     */
    private Mono<RenderedResponse> withForwardedHeaders(
            RouteDefinition route, ServiceEndpointContext ctx, Mono<RenderedResponse> loader) {
        ResponseHeaderForwarder forwarder =
                ResponseHeaderForwarder.of(ctx.getConfig().effectiveForwardResponseHeaders(forwardResponseHeaders));
        if (forwarder == null) return loader;
        boolean withTrailers = route.getMethodType().serverSendsOneMessage();
        return loader.flatMap(rendered -> Mono.deferContextual(view -> {
            view.getOrEmpty(dev.appiaworks.shiguchi.grpc.ResponseMetadataCapture.class)
                    .ifPresent(capture -> forwarder.apply(
                            (dev.appiaworks.shiguchi.grpc.ResponseMetadataCapture) capture,
                            rendered.headers(),
                            withTrailers));
            return Mono.just(rendered);
        }));
    }

    /**
     * Everything that can change the answer: the method, the raw path, every query parameter with
     * all its values in a stable order, the {@code X-Fields} header, the request body, the
     * response format and — unless the policy is {@code PUBLIC} — the forwarded metadata that
     * identifies the caller (principal, {@code Authorization}, {@code client.forward-headers};
     * tracing headers, which differ on every request, excluded). Each part is length-prefixed, so
     * no two different requests can spell the same key.
     */
    private ResponseCache.Key cacheKey(
            ServerWebExchange exchange,
            RouteDefinition route,
            ServiceEndpointContext ctx,
            CachePolicy policy,
            io.grpc.Metadata metadata,
            String format,
            String body) {
        StringBuilder key = new StringBuilder(128);
        part(key, route.getFullServiceName() + "/" + route.getMethodName());
        part(key, format);
        var query = new java.util.TreeMap<>(exchange.getRequest().getQueryParams());
        key.append(query.size()).append('q');
        query.forEach((name, values) -> {
            part(key, name);
            key.append(values.size()).append('v');
            values.forEach(v -> part(key, v == null ? "" : v));
        });
        String fields = exchange.getRequest().getHeaders().getFirst(FIELDS_HEADER);
        part(key, fields == null ? "" : fields);
        part(key, body);
        if (policy == null || policy.perCaller()) {
            var names = new java.util.TreeSet<>(metadata.keys());
            for (String name : names) {
                if (GrpcMetadataPopulator.isTracingHeader(name)) continue;
                part(key, name);
                if (name.endsWith(io.grpc.Metadata.BINARY_HEADER_SUFFIX)) {
                    var values =
                            metadata.getAll(io.grpc.Metadata.Key.of(name, io.grpc.Metadata.BINARY_BYTE_MARSHALLER));
                    if (values != null)
                        values.forEach(
                                v -> part(key, java.util.Base64.getEncoder().encodeToString(v)));
                } else {
                    var values =
                            metadata.getAll(io.grpc.Metadata.Key.of(name, io.grpc.Metadata.ASCII_STRING_MARSHALLER));
                    if (values != null) values.forEach(v -> part(key, v));
                }
            }
        } else {
            // A shared answer still differs by the version a preview header pins the call to.
            String preview = ctx.getConfig().getVersions().getPreviewHeader();
            if (preview != null && !preview.isBlank()) {
                String value = metadata.get(io.grpc.Metadata.Key.of(
                        preview.trim().toLowerCase(java.util.Locale.ROOT), io.grpc.Metadata.ASCII_STRING_MARSHALLER));
                part(key, value == null ? "" : value);
            }
        }
        return new ResponseCache.Key(ctx.getConfig().getName(), originalRequestPath(exchange), key.toString());
    }

    private static void part(StringBuilder key, String value) {
        key.append(value.length()).append(':').append(value);
    }

    private static boolean isSafe(ServerWebExchange exchange) {
        var method = exchange.getRequest().getMethod();
        return org.springframework.http.HttpMethod.GET.equals(method)
                || org.springframework.http.HttpMethod.HEAD.equals(method);
    }

    /** {@link #send(ServerWebExchange, RouteDefinition, ServiceEndpointContext, CachePolicy, RenderedResponse, ResponseCache.Served)} with the route's policy, outside the cache. */
    private Mono<Void> send(
            ServerWebExchange exchange,
            RouteDefinition route,
            ServiceEndpointContext ctx,
            RenderedResponse rendered,
            ResponseCache.Served served) {
        return send(exchange, route, ctx, cachePolicy(route, ctx), rendered, served);
    }

    /**
     * Writes a successful unary answer: the size cap, then the caching headers of the route's
     * policy ({@code Cache-Control}, {@code Vary}), the entity tag and a 304 when the caller's
     * {@code If-None-Match} already names it, where the answer came from ({@code Age},
     * {@code Cache-Status}) when the response cache or coalescing served it, and compression.
     *
     * <p>Only {@code getFirst}, {@code set}, {@code add}, {@code addAll} and {@code setVary} are used
     * on the response headers: this class is compiled once for Spring 6 and 7, and the Map methods
     * Spring 6's {@code HttpHeaders} inherited ({@code remove(Object)}, {@code containsKey}) are gone
     * in Spring 7. A 304 is therefore decided before anything a 304 must not carry is set.
     */
    private Mono<Void> send(
            ServerWebExchange exchange,
            RouteDefinition route,
            ServiceEndpointContext ctx,
            CachePolicy policy,
            RenderedResponse rendered,
            ResponseCache.Served served) {
        if (rendered.body().length > maxResponseBody) {
            return writeErrorResponse(exchange, ctx, responseTooLarge(rendered.body().length));
        }
        Mono<Boolean> identified =
                policy != null && policy.visibility() == dev.appiaworks.shiguchi.config.CacheVisibility.AUTO
                        ? identifiesCaller(exchange)
                        : Mono.just(false);
        String preview = ctx == null ? null : ctx.getConfig().getVersions().getPreviewHeader();
        return identified.flatMap(id -> write(exchange, policy, rendered, served, id, preview));
    }

    private Mono<Void> write(
            ServerWebExchange exchange,
            CachePolicy policy,
            RenderedResponse rendered,
            ResponseCache.Served served,
            boolean identified,
            String previewHeader) {
        HttpHeaders requestHeaders = exchange.getRequest().getHeaders();
        HttpHeaders headers = exchange.getResponse().getHeaders();
        boolean safe = isSafe(exchange);
        boolean compressible = compression != null
                && rendered.body().length >= compressionMinSize
                && compressible(rendered.contentType());
        boolean gzip = compressible && acceptsGzip(requestHeaders);

        if (policy != null) {
            headers.setCacheControl(policy.cacheControl(identified));
            List<String> vary = new java.util.ArrayList<>(4);
            // Each of these picks a different body for the same URL; a cache that ignored them would
            // hand an Avro body to a JSON client, or a projection to a caller who asked for all fields.
            if (!codecs.isEmpty()) vary.add(HttpHeaders.ACCEPT);
            vary.add(FIELDS_HEADER);
            if (compressible) vary.add(HttpHeaders.ACCEPT_ENCODING);
            // A preview header pins the call to one backend version, whose answer may differ.
            if (previewHeader != null && !previewHeader.isBlank()) vary.add(previewHeader.trim());
            headers.setVary(vary);
        } else if (compressible) {
            headers.setVary(List.of(HttpHeaders.ACCEPT_ENCODING));
        }
        if (served != null) {
            headers.set("Cache-Status", cacheStatus(served));
            if (served.outcome() == ResponseCache.Outcome.HIT || served.outcome() == ResponseCache.Outcome.STALE) {
                headers.set(HttpHeaders.AGE, Long.toString(served.ageSeconds()));
            }
        }

        // A resource's own version is the better validator; a hash tag only for a declared-cacheable route,
        // since on anything else it is a digest computed for nothing.
        String tag =
                rendered.resourceEtag() != null ? rendered.resourceEtag() : policy != null ? rendered.hashTag() : null;
        if (tag != null) {
            String sent = representationTag(tag, gzip, rendered.resourceEtag() == null);
            headers.set(HttpHeaders.ETAG, sent);
            if (safe && notModified(requestHeaders, tag, sent)) {
                exchange.getResponse().setStatusCode(HttpStatus.NOT_MODIFIED);
                exchange.getAttributes().put(GatewayAccessLogFilter.GRPC_STATUS_ATTR, "OK");
                return exchange.getResponse().setComplete();
            }
        }

        rendered.headers().forEach((name, values) -> {
            if (headers.getFirst(name) == null) headers.addAll(name, values);
        });
        headers.setContentType(rendered.contentType());
        byte[] body = rendered.body();
        if (gzip) {
            body = rendered.gzipped();
            headers.set(HttpHeaders.CONTENT_ENCODING, "gzip");
        }
        exchange.getResponse().setStatusCode(HttpStatus.OK);
        exchange.getAttributes().put(GatewayAccessLogFilter.GRPC_STATUS_ATTR, "OK");
        if (!safe && responseCache != null) {
            // A successful write to a resource makes what was read of it stale.
            responseCache.invalidatePath(originalRequestPath(exchange));
        }
        return exchange.getResponse()
                .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    /**
     * Whether the request names its caller: an {@code Authorization} or {@code Cookie} header, or a
     * principal other than an anonymous one. An {@code AUTO} policy turns {@code private} on it.
     */
    private static Mono<Boolean> identifiesCaller(ServerWebExchange exchange) {
        HttpHeaders headers = exchange.getRequest().getHeaders();
        if (headers.getFirst(HttpHeaders.AUTHORIZATION) != null || headers.getFirst(HttpHeaders.COOKIE) != null) {
            return Mono.just(true);
        }
        return exchange.getPrincipal()
                .map(p -> !(p instanceof org.springframework.security.authentication.AnonymousAuthenticationToken))
                .defaultIfEmpty(false);
    }

    /** RFC 9211 {@code Cache-Status}: whether the answer came from the cache, or a backend call and which. */
    private static String cacheStatus(ResponseCache.Served served) {
        return switch (served.outcome()) {
            case HIT -> "shiguchi; hit";
            case STALE -> "shiguchi; hit; detail=stale";
            case MISS -> served.stored() ? "shiguchi; fwd=miss; stored" : "shiguchi; fwd=miss";
            case COALESCED -> "shiguchi; fwd=miss; collapsed";
        };
    }

    /**
     * The tag as sent for this representation. The gzip form of a body is a different sequence of
     * bytes, so a strong tag must differ too: {@code "x"} becomes {@code "x-gzip"}. When the server
     * itself compresses ({@code server.compression}), which happens after this point, a hash tag
     * cannot promise byte identity any more and goes out weak.
     */
    private String representationTag(String tag, boolean gzip, boolean hashTag) {
        if (gzip && tag.endsWith("\"")) {
            return tag.substring(0, tag.length() - 1) + "-gzip\"";
        }
        if (hashTag && serverCompression && !tag.startsWith("W/")) {
            return "W/" + tag;
        }
        return tag;
    }

    /**
     * Whether {@code If-None-Match} names the answer: {@code *}, or a tag equal to it by weak
     * comparison (RFC 9110 uses weak comparison for this header), in either encoding — a caller
     * holding the identity form of a body still holds the same content.
     */
    static boolean notModified(HttpHeaders requestHeaders, String tag, String sent) {
        List<String> requested = requestHeaders.getIfNoneMatch();
        if (requested == null || requested.isEmpty()) return false;
        String base = opaque(tag);
        String representation = opaque(sent);
        for (String candidate : requested) {
            for (String t : candidate.split(",")) {
                String trimmed = t.trim();
                if (trimmed.isEmpty()) continue;
                if ("*".equals(trimmed)) return true;
                String o = opaque(trimmed);
                if (o.equals(base) || o.equals(representation)) return true;
            }
        }
        return false;
    }

    /** A tag without its {@code W/} prefix. */
    private static String opaque(String tag) {
        return tag.startsWith("W/") ? tag.substring(2) : tag;
    }

    /** Whether {@code rest.compression.mime-types} covers the content type. */
    private boolean compressible(MediaType type) {
        if (type == null) return false;
        for (String configured : compression.getMimeTypes()) {
            MediaType allowed;
            try {
                allowed = MediaType.parseMediaType(configured);
            } catch (org.springframework.http.InvalidMediaTypeException e) {
                continue;
            }
            if (allowed.includes(type)) return true;
            if (allowed.equalsTypeAndSubtype(MediaType.APPLICATION_JSON)
                    && type.getSubtype().endsWith("+json")) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code Accept-Encoding} allows gzip: named, or covered by {@code *}, with a non-zero q. */
    static boolean acceptsGzip(HttpHeaders headers) {
        List<String> values = headers.getValuesAsList(HttpHeaders.ACCEPT_ENCODING);
        if (values == null) return false;
        boolean wildcard = false;
        for (String value : values) {
            for (String item : value.split(",")) {
                String[] parts = item.trim().split(";");
                String coding = parts[0].trim().toLowerCase(java.util.Locale.ROOT);
                double q = 1.0;
                for (int i = 1; i < parts.length; i++) {
                    String param = parts[i].trim();
                    if (param.startsWith("q=")) {
                        try {
                            q = Double.parseDouble(param.substring(2));
                        } catch (NumberFormatException e) {
                            q = 0;
                        }
                    }
                }
                if (coding.equals("gzip") || coding.equals("x-gzip")) return q > 0;
                if (coding.equals("*")) wildcard = q > 0;
            }
        }
        return wildcard;
    }

    /**
     * A backend refusing a conditional write: AIP-154 answers a stale {@code etag} with
     * {@code ABORTED}, some services with {@code FAILED_PRECONDITION}. Either is HTTP 412 when the
     * caller sent {@code If-Match}.
     */
    private static boolean isPreconditionFailure(StatusRuntimeException ex) {
        var code = ex.getStatus().getCode();
        return code == io.grpc.Status.Code.ABORTED || code == io.grpc.Status.Code.FAILED_PRECONDITION;
    }

    /**
     * Writes a merged page: one JSON array of records, with the merged cursor and total on headers.
     *
     * <p>A merge answers with a page rather than an open stream even when its sources are
     * server-streaming, because the client contract is "one page per call" — the cursor is what
     * carries the caller forward, and NDJSON has nowhere to put it.
     */
    private Mono<Void> writeMergeResponse(
            ServerWebExchange exchange,
            RouteDefinition route,
            ServiceEndpointContext ctx,
            Map<String, String> pathVars,
            Map<String, String> queryParams,
            String body,
            io.grpc.Metadata metadata) {

        Map<String, Object> args;
        try {
            args = mergeRequestArgs(route, ctx, pathVars, queryParams, body);
        } catch (Exception e) {
            return Mono.error(e);
        }

        return mergeDispatcher
                .dispatch(route, args, metadata)
                .flatMap(page -> {
                    byte[] json;
                    try {
                        json = printed(ctx, renderMergedPage(page, ctx));
                    } catch (Exception e) {
                        return Mono.error(e);
                    }
                    if (json.length > maxResponseBody) {
                        return writeErrorResponse(exchange, ctx, responseTooLarge(json.length));
                    }

                    HttpHeaders headers = exchange.getResponse().getHeaders();
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    if (page.nextPageToken() != null) {
                        headers.set(mergeNextPageTokenHeader, page.nextPageToken());
                    }
                    if (page.totalCount() >= 0) {
                        headers.set(mergeTotalCountHeader, String.valueOf(page.totalCount()));
                    }
                    exchange.getResponse().setStatusCode(HttpStatus.OK);
                    exchange.getAttributes().put(GatewayAccessLogFilter.GRPC_STATUS_ATTR, "OK");
                    DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(json);
                    return exchange.getResponse().writeWith(Mono.just(buffer));
                })
                .onErrorResume(
                        dev.appiaworks.shiguchi.merge.MergeCursorCodec.InvalidCursorException.class,
                        ex -> writeRenderedError(
                                exchange,
                                HttpStatus.BAD_REQUEST,
                                io.grpc.Status.INVALID_ARGUMENT.withDescription(ex.getMessage())))
                .onErrorResume(StatusRuntimeException.class, ex -> writeErrorResponse(exchange, ctx, ex));
    }

    /** Builds the normalised request args for a merge, reusing the shared paging/sort conventions. */
    private Map<String, Object> mergeRequestArgs(
            RouteDefinition route,
            ServiceEndpointContext ctx,
            Map<String, String> pathVars,
            Map<String, String> queryParams,
            String body)
            throws Exception {

        var spec = route.getMergeSpec();
        var first = spec.sources().get(0);
        var sourceRoute = registry.findBySourceMethod(first.endpoint(), first.fullServiceName(), first.methodName())
                .orElseThrow(() -> new IllegalStateException("merge source not registered: " + first.label()));
        var sourceCtx = registry.contextFor(sourceRoute);
        var md = sourceCtx
                .lookupMethod(first.fullServiceName(), first.methodName())
                .orElseThrow(() -> new IllegalStateException("merge source method not found: " + first.label()));

        return requestBuilder.buildRequestMap(md.getInputType(), pathVars, queryParams, body);
    }

    private byte[] renderMergedPage(MergeDispatcher.MergePage page, ServiceEndpointContext ctx) throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        out.write('[');
        boolean first = true;
        for (com.google.protobuf.Message record : page.records()) {
            if (!first) out.write(',');
            first = false;
            out.write(
                    serdeSelector.serializeToBytes(record, record.getDescriptorForType(), ctx, ctx.getTypeRegistry()));
        }
        out.write(']');
        return out.toByteArray();
    }

    private Mono<Void> writeStreamingResponse(
            ServerWebExchange exchange,
            RouteDefinition route,
            ServiceEndpointContext ctx,
            Map<String, String> pathVars,
            Map<String, String> queryParams,
            String body,
            io.grpc.Metadata metadata,
            Optional<Mono<io.grpc.Status>> end) {

        boolean sse = streamsAsSse(exchange.getRequest().getHeaders());
        HttpHeaders headers = exchange.getResponse().getHeaders();
        if (sse) {
            headers.setContentType(MediaType.TEXT_EVENT_STREAM);
            // An event stream is live; a cache or proxy holding it back defeats the point.
            headers.setCacheControl("no-cache");
        } else {
            headers.setContentType(MediaType.APPLICATION_NDJSON);
        }
        exchange.getResponse().setStatusCode(HttpStatus.OK);

        // A failure before the first item is still a plain HTTP error (nothing is committed yet:
        // the caller's onErrorResume takes it). A failure after that travels in-band: a trailing
        // {"error": ...} line for NDJSON, an "error" event for SSE. The cap limits each message,
        // not the running total: a stream is not buffered and may run indefinitely, so a total
        // would end every long-lived stream. A message over it fails the stream like a backend
        // error would.
        // Each item is written straight into its response buffer, framed: an NDJSON line, or the
        // data line of an SSE event. The gateway's JSON is single-line (nothing is pretty-printed
        // on a stream), so one data: prefix frames the whole payload. The cap counts the JSON and
        // one newline per item.
        byte[] prefix = sse ? SSE_DATA : NO_BYTES;
        byte[] suffix = sse ? SSE_EVENT_END : NDJSON_LINE_END;
        int framing = prefix.length + suffix.length;
        Flux<DataBuffer> frames = dispatcher
                .dispatchStreamingFrames(
                        route,
                        ctx,
                        pathVars,
                        queryParams,
                        body,
                        metadata,
                        exchange.getResponse().bufferFactory(),
                        prefix,
                        suffix,
                        STREAM_ITEM_SIZE_HINT)
                .transform(source -> sessionBound(source, end))
                .handle((DataBuffer frame, reactor.core.publisher.SynchronousSink<DataBuffer> sink) -> {
                    // The cap is per message on a stream (see above): the JSON plus its newline.
                    long size = frame.readableByteCount() - framing + 1L;
                    if (size > maxResponseBody) {
                        DataBufferUtils.release(frame);
                        sink.error(responseTooLarge(size));
                    } else {
                        sink.next(frame);
                    }
                });
        if (sse) {
            // A keepalive counts as the first item below: once one is out the 200 is committed and
            // a later failure goes in-band as the error event.
            frames = SseKeepAlive.withKeepAlive(
                    frames,
                    sseKeepalive,
                    () -> exchange.getResponse().bufferFactory().wrap(SSE_KEEPALIVE.clone()),
                    reactor.core.scheduler.Schedulers.parallel());
        }
        return exchange.getResponse()
                .writeWith(frames.switchOnFirst((signal, all) -> signal.hasValue()
                        ? all.onErrorResume(
                                StatusRuntimeException.class, ex -> Mono.just(streamErrorFrame(exchange, ctx, ex, sse)))
                        : all))
                .onErrorResume(StatusRuntimeException.class, ex -> writeErrorResponse(exchange, ctx, ex));
    }

    /** What ends a server stream of the caller (session end, token expiry); empty for none. */
    private Mono<Optional<Mono<io.grpc.Status>>> endSignal() {
        return StreamEnd.of(sessionWatch, endStreamsAtTokenExpiry);
    }

    /**
     * A stream of a cookie caller lives and dies with the session, one of a bearer caller ends at
     * the token's expiry: when that happens the items stop, the backend call is cancelled and the
     * stream fails with UNAUTHENTICATED "session ended" or "token expired", which travels like any
     * other failure of a stream (the in-band error line or event, or the aborted transfer of a
     * download). Before the first item it is a plain 401 response. A caller with no end signal
     * gets the stream as it is.
     */
    private <T> Flux<T> sessionBound(Flux<T> source, Optional<Mono<io.grpc.Status>> end) {
        if (end.isEmpty()) return source;
        var ended = new java.util.concurrent.atomic.AtomicReference<io.grpc.Status>();
        return StreamEnd.until(source, end, ended)
                .concatWith(Flux.defer(
                        () -> ended.get() != null ? Flux.error(ended.get().asRuntimeException()) : Flux.empty()));
    }

    private static DataBuffer ndjsonFrame(ServerWebExchange exchange, byte[] json) {
        DataBuffer buf = exchange.getResponse().bufferFactory().allocateBuffer(json.length + 1);
        buf.write(json);
        buf.write((byte) '\n');
        return buf;
    }

    /**
     * One Server-Sent Event: an optional {@code event:} line, then the payload as {@code data:}
     * lines, then the blank line that ends the event. The gateway's JSON is single-line, but a
     * newline in the payload would end the event early, so each line gets its own {@code data:}
     * prefix — which the SSE spec rejoins with newlines on the client.
     */
    static DataBuffer sseFrame(ServerWebExchange exchange, String event, byte[] payload) {
        var out = new java.io.ByteArrayOutputStream(payload.length + 32);
        if (event != null) {
            out.writeBytes(("event: " + event + "\n").getBytes(StandardCharsets.UTF_8));
        }
        out.writeBytes(SSE_DATA);
        for (byte b : payload) {
            if (b == '\r') continue;
            if (b == '\n') {
                out.write('\n');
                out.writeBytes(SSE_DATA);
            } else {
                out.write(b);
            }
        }
        out.write('\n');
        out.write('\n');
        return exchange.getResponse().bufferFactory().wrap(out.toByteArray());
    }

    private static final byte[] SSE_DATA = "data: ".getBytes(StandardCharsets.UTF_8);

    private static final byte[] NO_BYTES = new byte[0];
    private static final byte[] NDJSON_LINE_END = {10};
    private static final byte[] SSE_EVENT_END = {10, 10};
    /** Bytes reserved per streamed item before growth, beyond three times its wire size. */
    private static final int STREAM_ITEM_SIZE_HINT = 512;

    /** An SSE comment: ignored by {@code EventSource}, enough to keep an idle connection busy. */
    private static final byte[] SSE_KEEPALIVE = ": keepalive\n\n".getBytes(StandardCharsets.UTF_8);

    private Mono<Void> writeErrorResponse(
            ServerWebExchange exchange, ServiceEndpointContext ctx, StatusRuntimeException ex) {
        if (isResponseTooLarge(ex)) {
            return writeRenderedError(
                    exchange, HttpStatus.BAD_GATEWAY, ex.getStatus(), responseTooLargeDetails(ctx, ex));
        }
        HttpStatus status = statusMapper.toHttpStatus(ex.getStatus());
        return writeRenderedError(exchange, status, ex.getStatus(), errorDetails(ctx, ex));
    }

    /** {@code ErrorInfo.reason} of a response the gateway refused to relay because of its size. */
    static final String RESPONSE_TOO_LARGE = "RESPONSE_TOO_LARGE";

    private static final String ERROR_DOMAIN = "shiguchi";

    /**
     * The failure for a response body over {@code rest.max-response-body}: {@code RESOURCE_EXHAUSTED}
     * with an {@code ErrorInfo}, carried as a status exception so the unary, merge and streaming
     * paths all report it through the same error writers.
     */
    private StatusRuntimeException responseTooLarge(long size) {
        return responseTooLarge(size, maxResponseBody);
    }

    /** {@link #responseTooLarge(long)} for a given limit; gRPC-Web reports its cap the same way. */
    static StatusRuntimeException responseTooLarge(long size, long limit) {
        String message = size >= 0
                ? "Response body of " + size + " bytes exceeds the limit of " + limit + " bytes"
                : "Response body exceeds the limit of " + limit + " bytes";
        com.google.rpc.ErrorInfo.Builder info = com.google.rpc.ErrorInfo.newBuilder()
                .setReason(RESPONSE_TOO_LARGE)
                .setDomain(ERROR_DOMAIN)
                .putMetadata("limit", Long.toString(limit));
        if (size >= 0) info.putMetadata("size", Long.toString(size));
        return io.grpc.protobuf.StatusProto.toStatusRuntimeException(com.google.rpc.Status.newBuilder()
                .setCode(io.grpc.Status.Code.RESOURCE_EXHAUSTED.value())
                .setMessage(message)
                .addDetails(com.google.protobuf.Any.pack(info.build()))
                .build());
    }

    /**
     * Whether a failure means a backend's response was too big to relay, rather than the backend
     * asking the caller to slow down: both arrive as {@code RESOURCE_EXHAUSTED}, but only the
     * latter is a 429. It is the gateway's own cap, or gRPC refusing a message over the channel's
     * {@code max-inbound-message-size} ("gRPC message exceeds maximum size").
     */
    static boolean isResponseTooLarge(StatusRuntimeException ex) {
        io.grpc.Status status = ex.getStatus();
        if (status.getCode() != io.grpc.Status.Code.RESOURCE_EXHAUSTED) return false;
        String description = status.getDescription();
        if (description != null && description.contains("message exceeds maximum size")) return true;
        return GatewayErrorDetails.from(ex, null)
                .errorInfo()
                .filter(i -> RESPONSE_TOO_LARGE.equals(i.getReason()) && ERROR_DOMAIN.equals(i.getDomain()))
                .isPresent();
    }

    /** The details for a too-large response; gRPC's own size failure carries none, so one is added. */
    private GatewayErrorDetails responseTooLargeDetails(ServiceEndpointContext ctx, StatusRuntimeException ex) {
        GatewayErrorDetails details = errorDetails(ctx, ex);
        if (!details.isEmpty()) return details;
        return GatewayErrorDetails.of(List.of(com.google.protobuf.Any.pack(com.google.rpc.ErrorInfo.newBuilder()
                .setReason(RESPONSE_TOO_LARGE)
                .setDomain(ERROR_DOMAIN)
                .build())));
    }

    private Mono<Void> writeAccessDeniedResponse(ServerWebExchange exchange, AccessDeniedException ex) {
        io.grpc.Status denied = io.grpc.Status.PERMISSION_DENIED.withDescription(ex.getMessage());
        return writeRenderedError(exchange, HttpStatus.FORBIDDEN, denied);
    }

    private Mono<Void> writeRenderedError(ServerWebExchange exchange, HttpStatus status, io.grpc.Status grpcStatus) {
        return writeRenderedError(exchange, status, grpcStatus, GatewayErrorDetails.none());
    }

    private Mono<Void> writeRenderedError(
            ServerWebExchange exchange, HttpStatus status, io.grpc.Status grpcStatus, GatewayErrorDetails details) {
        exchange.getAttributes()
                .put(
                        GatewayAccessLogFilter.GRPC_STATUS_ATTR,
                        grpcStatus.getCode().name());
        exchange.getResponse().setStatusCode(status);
        HttpHeaders headers = exchange.getResponse().getHeaders();
        headers.setContentType(errorRenderer.contentType());
        // A google.rpc.RetryInfo from the backend is the caller's Retry-After.
        details.retryDelay().ifPresent(delay -> headers.set(HttpHeaders.RETRY_AFTER, retryAfterSeconds(delay)));
        byte[] body = errorRenderer.render(status, grpcStatus, details, exchange);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    /** The {@code google.rpc.Status} details behind a failed call, printed with the service's own types. */
    private static GatewayErrorDetails errorDetails(ServiceEndpointContext ctx, StatusRuntimeException ex) {
        return GatewayErrorDetails.from(ex.getStatus(), ex.getTrailers(), ctx == null ? null : ctx.getTypeRegistry());
    }

    /** {@code Retry-After} is whole seconds; a sub-second delay still asks for one second. */
    private static String retryAfterSeconds(java.time.Duration delay) {
        long seconds = delay.getSeconds() + (delay.getNano() > 0 ? 1 : 0);
        return String.valueOf(Math.max(1, seconds));
    }

    /**
     * The in-band failure of a stream that failed after data was already sent. By then the 200
     * and the content type are committed, so the failure has to travel in the body:
     *
     * <ul>
     *   <li>NDJSON: a trailing line {@code {"error": <the same body a unary failure would get>}},
     *       the way grpc-gateway does it;
     *   <li>SSE: an event named {@code error} whose data is that body, so an {@code EventSource}
     *       listener for {@code error} events tells it apart from a message without inspecting it.
     * </ul>
     *
     * A renderer whose content type is not JSON cannot be embedded; the default shape is used then.
     */
    private DataBuffer streamErrorFrame(
            ServerWebExchange exchange, ServiceEndpointContext ctx, StatusRuntimeException ex, boolean sse) {
        boolean tooLarge = isResponseTooLarge(ex);
        HttpStatus status = tooLarge ? HttpStatus.BAD_GATEWAY : statusMapper.toHttpStatus(ex.getStatus());
        exchange.getAttributes()
                .put(
                        GatewayAccessLogFilter.GRPC_STATUS_ATTR,
                        ex.getStatus().getCode().name());
        GatewayErrorRenderer renderer = isJson(errorRenderer.contentType()) ? errorRenderer : STREAM_ERROR_RENDERER;
        byte[] body = renderer.render(
                status, ex.getStatus(), tooLarge ? responseTooLargeDetails(ctx, ex) : errorDetails(ctx, ex), exchange);
        if (sse) {
            return sseFrame(exchange, "error", body);
        }
        byte[] prefix = "{\"error\":".getBytes(StandardCharsets.UTF_8);
        byte[] line = new byte[prefix.length + body.length + 1];
        System.arraycopy(prefix, 0, line, 0, prefix.length);
        System.arraycopy(body, 0, line, prefix.length, body.length);
        line[line.length - 1] = '}';
        return ndjsonFrame(exchange, line);
    }

    private static final GatewayErrorRenderer STREAM_ERROR_RENDERER = new DefaultGatewayErrorRenderer();
}
