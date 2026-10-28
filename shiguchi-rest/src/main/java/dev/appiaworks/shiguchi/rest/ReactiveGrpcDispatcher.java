package dev.appiaworks.shiguchi.rest;

import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import com.google.protobuf.DynamicMessage;
import dev.appiaworks.shiguchi.grpc.GrpcCallContext;
import dev.appiaworks.shiguchi.grpc.GrpcDescriptorCache;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import dev.appiaworks.shiguchi.serde.SerdeSelector;
import dev.appiaworks.shiguchi.validation.RequestValidationHook;
import io.grpc.CallOptions;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.stub.StreamObserver;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

/**
 * Reactive gRPC dispatcher: {@link Mono}{@code <RestResponse>} (UTF-8 JSON bytes + decoded
 * message) for unary, {@link Flux}{@code <byte[]>} of NDJSON-ready items for server-streaming.
 *
 * <p>Unary calls use {@link ClientCalls#asyncUnaryCall} bridged to {@code Mono.create} so that
 * no thread is held during the network round-trip. Server-streaming calls use
 * {@link ClientCalls#asyncServerStreamingCall} with {@code Flux.create}.
 *
 * <p>gRPC method descriptors (with marshallers) are resolved via {@link GrpcDescriptorCache}
 * so they are built once per (context × method) instead of per request.
 */
@Component
public class ReactiveGrpcDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ReactiveGrpcDispatcher.class);

    private final GrpcRequestBuilder requestBuilder;
    private final SerdeSelector serdeSelector;
    private final GrpcDescriptorCache descriptorCache;

    // Optional: present only when shiguchi.validation.gateway-enabled=true
    @Nullable
    private RequestValidationHook validationHook;

    @Autowired(required = false)
    public void setValidationHook(RequestValidationHook validationHook) {
        this.validationHook = validationHook;
    }

    private final dev.appiaworks.protobuf.extras.serde.WireJsonTranscoder wireTranscoder;

    private final java.time.Duration defaultDeadline;

    public ReactiveGrpcDispatcher(
            GrpcRequestBuilder requestBuilder,
            SerdeSelector serdeSelector,
            GrpcDescriptorCache descriptorCache,
            dev.appiaworks.protobuf.extras.serde.WireJsonTranscoder wireTranscoder,
            dev.appiaworks.shiguchi.config.GatewayProperties properties) {
        this.requestBuilder = requestBuilder;
        this.serdeSelector = serdeSelector;
        this.descriptorCache = descriptorCache;
        this.wireTranscoder = wireTranscoder;
        this.defaultDeadline = properties.getClient().getDefaultDeadline();
        // Conflict rejection compares against the body as a Map, which the direct encoder never builds.
        this.directRequests = requestBuilder.rejectsConflictingValues()
                ? null
                : new DirectRequestEncoder(requestBuilder.caseInsensitiveEnums());
    }

    /** Request side of Tier 0: HTTP request straight to wire bytes; null when the gateway's options need the Map. */
    @Nullable
    private final DirectRequestEncoder directRequests;

    /**
     * The request's wire bytes when both sides can skip the message object: a Tier 0 response
     * program, no validation hook (it inspects the decoded request), and a request the encoder
     * takes. Null otherwise, and the caller builds the request Map as before.
     */
    @Nullable
    private byte[] directRequestWire(
            MethodDescriptor md,
            RouteDefinition route,
            Map<String, String> pathVars,
            Map<String, String> queryParams,
            String body,
            dev.appiaworks.protobuf.extras.serde.WireJsonTranscoder.Program tier0) {
        if (tier0 == null || directRequests == null || validationHook != null) return null;
        return directRequests.encode(md.getInputType(), pathVars, queryParams, body, route.getHttpBody());
    }

    /**
     * Tier 0 eligibility: a supported program and — for unary — no page_info field, since
     * pagination headers need the decoded message. Tier 0 is the fastest response path whether
     * or not the compiled class is on the classpath (it reads the wire bytes straight into JSON,
     * where Tier 1 parses a message first and then writes it), so it is taken first; Tier 1
     * serves what it cannot: a type carrying {@code google.protobuf.Any} or a group, and the
     * paginated unary reply. Tier 0 also applies json.print options itself, which Tier 1 cannot.
     */
    private dev.appiaworks.protobuf.extras.serde.WireJsonTranscoder.Program tier0Program(
            MethodDescriptor md, ServiceEndpointContext ctx, boolean requiresPageInfoAccess) {
        if (requiresPageInfoAccess && md.getOutputType().findFieldByName("page_info") != null) return null;
        return wireTranscoder.programFor(md.getOutputType());
    }

    /** A unary call with request and response both as wire bytes; the response transcoded on Tier 0. */
    private Mono<RestResponse> dispatchRaw(
            RouteDefinition route,
            ServiceEndpointContext ctx,
            ServiceDescriptor sd,
            MethodDescriptor md,
            dev.appiaworks.protobuf.extras.serde.WireJsonTranscoder.Program tier0,
            byte[] requestWire,
            Metadata grpcMetadata) {
        var callOpts = unaryCallOptions(ctx, grpcMetadata);
        var rawMethod = descriptorCache.resolveRaw(ctx, sd, md, io.grpc.MethodDescriptor.MethodType.UNARY);
        if (log.isDebugEnabled()) {
            log.debug(
                    "[gRPC dispatch→] {}/{} request={} wire bytes",
                    route.getFullServiceName(),
                    route.getMethodName(),
                    requestWire.length);
        }
        return Mono.create(sink -> {
            var call = ctx.getChannel().newCall(rawMethod, callScoped(callOpts, sink.contextView()));
            ClientCalls.asyncUnaryCall(call, requestWire, new StreamObserver<>() {
                @Override
                public void onNext(byte[] wire) {
                    try {
                        sink.success(new RestResponse(transcodeOrFallback(tier0, wire, md, ctx), null));
                    } catch (Exception e) {
                        sink.error(e);
                    }
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

    /** Transcodes wire→JSON, falling back to the DynamicMessage path on unsupported encodings. */
    private byte[] transcodeOrFallback(
            dev.appiaworks.protobuf.extras.serde.WireJsonTranscoder.Program program,
            byte[] wire,
            MethodDescriptor md,
            ServiceEndpointContext ctx)
            throws Exception {
        byte[] json = wireTranscoder.transcode(program, wire, ctx.getPrintOptions());
        if (json != null) return json;
        DynamicMessage parsed = DynamicMessage.parseFrom(md.getOutputType(), wire);
        return serdeSelector.serializeToBytes(parsed, md.getOutputType(), ctx, ctx.getTypeRegistry());
    }

    public Mono<RestResponse> dispatch(
            RouteDefinition route,
            ServiceEndpointContext ctx,
            Map<String, String> pathVars,
            Map<String, String> queryParams,
            String body,
            Metadata grpcMetadata) {

        MethodDescriptor md;
        ServiceDescriptor sd;
        DynamicMessage request;
        Map<String, Object> requestMap = null;

        try {
            md = ctx.lookupMethod(route.getFullServiceName(), route.getMethodName())
                    .orElseThrow(() -> new IllegalStateException(
                            "Method not found: " + route.getFullServiceName() + "/" + route.getMethodName()));
            sd = ctx.getServiceByFqn().get(route.getFullServiceName());

            // Both sides on the wire: the HTTP request encoded straight to request bytes, the
            // response bytes transcoded straight to JSON — no message object either way.
            var direct = tier0Program(md, ctx, true);
            byte[] requestWire = directRequestWire(md, route, pathVars, queryParams, body, direct);
            if (requestWire != null) {
                return dispatchRaw(route, ctx, sd, md, direct, requestWire, grpcMetadata);
            }

            // Body is parsed once into a Map; path/query params overlay it, and the serde
            // consumes the Map directly — no JSON re-serialization + re-parse round trip.
            requestMap =
                    requestBuilder.buildRequestMap(md.getInputType(), pathVars, queryParams, body, route.getHttpBody());
            request = serdeSelector.deserializeFromMap(requestMap, md.getInputType(), ctx);
            if (validationHook != null) {
                validationHook.validate(request, md);
            }
        } catch (Exception e) {
            return Mono.error(e);
        }

        var callOpts = unaryCallOptions(ctx, grpcMetadata);
        log.debug("[gRPC dispatch→] {}/{} body={}", route.getFullServiceName(), route.getMethodName(), requestMap);
        final MethodDescriptor finalMd = md;

        // Tier 0: dynamic-only service — transcode the raw wire bytes straight to JSON,
        // no message object materialised (unless the encoding forces a fallback parse).
        var tier0 = tier0Program(md, ctx, true);
        if (tier0 != null) {
            var rawMethod = descriptorCache.resolveRawResponse(ctx, sd, md, io.grpc.MethodDescriptor.MethodType.UNARY);
            final DynamicMessage finalRequest = request;
            return Mono.create(sink -> {
                var call = ctx.getChannel().newCall(rawMethod, callScoped(callOpts, sink.contextView()));
                ClientCalls.asyncUnaryCall(call, finalRequest, new StreamObserver<>() {
                    @Override
                    public void onNext(byte[] wire) {
                        try {
                            sink.success(new RestResponse(transcodeOrFallback(tier0, wire, finalMd, ctx), null));
                        } catch (Exception e) {
                            sink.error(e);
                        }
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

        // Tier 1: when the compiled response class is on the classpath, parse straight into
        // the generated type (fast stub parser) and let the Tier 1 typed writer emit the JSON.
        var grpcMethod = descriptorCache.resolveResponseAsMessage(
                ctx,
                sd,
                md,
                io.grpc.MethodDescriptor.MethodType.UNARY,
                serdeSelector.tier1ResponseInstance(md.getOutputType(), ctx));
        return Mono.create(sink -> {
            var call = ctx.getChannel().newCall(grpcMethod, callScoped(callOpts, sink.contextView()));
            ClientCalls.asyncUnaryCall(call, request, new StreamObserver<>() {
                @Override
                public void onNext(com.google.protobuf.Message value) {
                    try {
                        byte[] json = serdeSelector.serializeToBytes(
                                value, finalMd.getOutputType(), ctx, ctx.getTypeRegistry());
                        if (log.isDebugEnabled()) {
                            log.debug(
                                    "[gRPC dispatch←] {}/{} response={}",
                                    route.getFullServiceName(),
                                    route.getMethodName(),
                                    new String(json, java.nio.charset.StandardCharsets.UTF_8));
                        }
                        sink.success(new RestResponse(json, value));
                    } catch (Exception e) {
                        sink.error(e);
                    }
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

    /** A server-streaming call served on Tier 0: the raw wire items and the program that transcodes them. */
    private record StreamingTier0(
            Flux<byte[]> wire,
            dev.appiaworks.protobuf.extras.serde.WireJsonTranscoder.Program program,
            MethodDescriptor md) {}

    /**
     * The Tier 0 form of a server-streaming route, or null when the route needs the message path
     * (a type Tier 0 cannot transcode). The request takes the wire-direct encoder when it can, the
     * request Map otherwise, exactly as {@link #dispatch} does.
     */
    @Nullable
    private StreamingTier0 streamingTier0(
            RouteDefinition route,
            ServiceEndpointContext ctx,
            Map<String, String> pathVars,
            Map<String, String> queryParams,
            String body,
            Metadata grpcMetadata)
            throws Exception {
        MethodDescriptor md = ctx.lookupMethod(route.getFullServiceName(), route.getMethodName())
                .orElseThrow();
        ServiceDescriptor sd = ctx.getServiceByFqn().get(route.getFullServiceName());
        var tier0 = tier0Program(md, ctx, false);
        if (tier0 == null) return null;

        // Both sides on the wire, as in dispatch(): each item transcoded from its bytes.
        byte[] requestWire = directRequestWire(md, route, pathVars, queryParams, body, tier0);
        if (requestWire != null) {
            var rawMethod =
                    descriptorCache.resolveRaw(ctx, sd, md, io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING);
            return new StreamingTier0(
                    streamingResponses(ctx.getChannel(), rawMethod, callOptions(grpcMetadata), requestWire), tier0, md);
        }

        Map<String, Object> requestMap =
                requestBuilder.buildRequestMap(md.getInputType(), pathVars, queryParams, body, route.getHttpBody());
        DynamicMessage request = serdeSelector.deserializeFromMap(requestMap, md.getInputType(), ctx);
        if (validationHook != null) {
            validationHook.validate(request, md);
        }
        log.debug("[gRPC stream→] {}/{} body={}", route.getFullServiceName(), route.getMethodName(), requestMap);
        var rawMethod =
                descriptorCache.resolveRawResponse(ctx, sd, md, io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING);
        return new StreamingTier0(
                streamingResponses(ctx.getChannel(), rawMethod, callOptions(grpcMetadata), request), tier0, md);
    }

    public Flux<byte[]> dispatchStreaming(
            RouteDefinition route,
            ServiceEndpointContext ctx,
            Map<String, String> pathVars,
            Map<String, String> queryParams,
            String body,
            Metadata grpcMetadata) {

        MethodDescriptor md;
        DynamicMessage request;
        ServiceDescriptor sd;

        try {
            StreamingTier0 tier0 = streamingTier0(route, ctx, pathVars, queryParams, body, grpcMetadata);
            if (tier0 != null) {
                return tier0.wire().handle((wire, sink) -> {
                    try {
                        sink.next(transcodeOrFallback(tier0.program(), wire, tier0.md(), ctx));
                    } catch (Exception e) {
                        sink.error(e);
                    }
                });
            }

            md = ctx.lookupMethod(route.getFullServiceName(), route.getMethodName())
                    .orElseThrow();
            sd = ctx.getServiceByFqn().get(route.getFullServiceName());
            Map<String, Object> requestMap =
                    requestBuilder.buildRequestMap(md.getInputType(), pathVars, queryParams, body, route.getHttpBody());
            request = serdeSelector.deserializeFromMap(requestMap, md.getInputType(), ctx);
            if (validationHook != null) {
                validationHook.validate(request, md);
            }
            log.debug("[gRPC stream→] {}/{} body={}", route.getFullServiceName(), route.getMethodName(), requestMap);
        } catch (Exception e) {
            return Flux.error(e);
        }

        final MethodDescriptor finalMd = md;
        var callOpts = callOptions(grpcMetadata);

        // Tier 1 typed response when the compiled class is available (see dispatch()).
        var grpcMethod = descriptorCache.resolveResponseAsMessage(
                ctx,
                sd,
                md,
                io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING,
                serdeSelector.tier1ResponseInstance(md.getOutputType(), ctx));
        return streamingResponses(ctx.getChannel(), grpcMethod, callOpts, request)
                .handle((value, sink) -> {
                    try {
                        sink.next(serdeSelector.serializeToBytes(
                                value, finalMd.getOutputType(), ctx, ctx.getTypeRegistry()));
                    } catch (Exception e) {
                        sink.error(e);
                    }
                });
    }

    /**
     * {@link #dispatchStreaming} with every item written straight into a response buffer, framed:
     * {@code prefix}, the JSON, {@code suffix} (a newline for NDJSON, the data line of an SSE
     * event). On Tier 0 the JSON goes from the wire bytes into the buffer with no byte array in
     * between; a transcode that bails rewinds the buffer and writes the DynamicMessage path output
     * instead. The caller owns each buffer and releases it once written or dropped.
     *
     * @param sizeHint bytes to allocate per item beyond the framing, before growth; the JSON of a
     *     message is typically two to three times its wire size
     */
    public Flux<DataBuffer> dispatchStreamingFrames(
            RouteDefinition route,
            ServiceEndpointContext ctx,
            Map<String, String> pathVars,
            Map<String, String> queryParams,
            String body,
            Metadata grpcMetadata,
            DataBufferFactory buffers,
            byte[] prefix,
            byte[] suffix,
            int sizeHint) {
        StreamingTier0 tier0;
        try {
            tier0 = streamingTier0(route, ctx, pathVars, queryParams, body, grpcMetadata);
        } catch (Exception e) {
            return Flux.error(e);
        }
        if (tier0 == null) {
            return dispatchStreaming(route, ctx, pathVars, queryParams, body, grpcMetadata)
                    .map(json -> {
                        DataBuffer buffer = buffers.allocateBuffer(prefix.length + json.length + suffix.length);
                        buffer.write(prefix);
                        buffer.write(json);
                        buffer.write(suffix);
                        return buffer;
                    });
        }
        var printOptions = ctx.getPrintOptions();
        return tier0.wire().handle((wire, sink) -> {
            DataBuffer buffer =
                    buffers.allocateBuffer(prefix.length + suffix.length + Math.max(sizeHint, wire.length * 3));
            try {
                buffer.write(prefix);
                int mark = buffer.writePosition();
                if (!wireTranscoder.transcode(tier0.program(), wire, printOptions, buffer.asOutputStream())) {
                    buffer.writePosition(mark);
                    DynamicMessage parsed = DynamicMessage.parseFrom(tier0.md().getOutputType(), wire);
                    buffer.write(serdeSelector.serializeToBytes(
                            parsed, tier0.md().getOutputType(), ctx, ctx.getTypeRegistry()));
                }
                buffer.write(suffix);
                sink.next(buffer);
            } catch (Exception e) {
                org.springframework.core.io.buffer.DataBufferUtils.release(buffer);
                sink.error(e);
            }
        });
    }

    /**
     * Server-streaming dispatch yielding decoded messages rather than serialised output.
     *
     * <p>Exists for the merge path, which has to read sort-key fields off every record to order it
     * against the other sources. That rules out the Tier 0 wire→JSON shortcut — there is no way to
     * compare records that were never decoded — so a merge always pays for materialised messages.
     * The same trade-off {@code tier0Program} already makes when a response carries {@code
     * page_info}, just unconditional here.
     *
     * @param requestArgs request fields as a Map, the spelling both HTTP surfaces normalise to
     */
    public Flux<com.google.protobuf.Message> dispatchStreamingMessages(
            RouteDefinition route, ServiceEndpointContext ctx, Map<String, Object> requestArgs, Metadata grpcMetadata) {

        MethodDescriptor md;
        DynamicMessage request;
        io.grpc.MethodDescriptor<DynamicMessage, com.google.protobuf.Message> grpcMethod;

        try {
            md = ctx.lookupMethod(route.getFullServiceName(), route.getMethodName())
                    .orElseThrow(() -> new IllegalStateException(
                            "Method not found: " + route.getFullServiceName() + "/" + route.getMethodName()));
            ServiceDescriptor sd = ctx.getServiceByFqn().get(route.getFullServiceName());
            request = serdeSelector.deserializeFromMap(requestArgs, md.getInputType(), ctx);
            if (validationHook != null) {
                validationHook.validate(request, md);
            }
            grpcMethod = descriptorCache.resolveResponseAsMessage(
                    ctx,
                    sd,
                    md,
                    route.getMethodType() == io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING
                            ? io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING
                            : io.grpc.MethodDescriptor.MethodType.UNARY,
                    serdeSelector.tier1ResponseInstance(md.getOutputType()));
            log.debug(
                    "[gRPC merge-source→] {}/{} args={}",
                    route.getFullServiceName(),
                    route.getMethodName(),
                    requestArgs);
        } catch (Exception e) {
            return Flux.error(e);
        }

        if (route.getMethodType() == io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING) {
            return streamingResponses(ctx.getChannel(), grpcMethod, callOptions(grpcMetadata), request);
        }

        // A unary source contributes its whole result as one message. Merging still works — it is
        // simply a source of length one from the heap's point of view — but it must go through
        // asyncUnaryCall: driving a unary method with the server-streaming call would break the
        // client-side contract for how many responses the call expects.
        final DynamicMessage unaryRequest = request;
        final io.grpc.MethodDescriptor<DynamicMessage, com.google.protobuf.Message> unaryMethod = grpcMethod;
        var unaryOpts = unaryCallOptions(ctx, grpcMetadata);
        return Mono.<com.google.protobuf.Message>create(sink -> {
                    var call = ctx.getChannel().newCall(unaryMethod, callScoped(unaryOpts, sink.contextView()));
                    ClientCalls.asyncUnaryCall(call, unaryRequest, new StreamObserver<>() {
                        @Override
                        public void onNext(com.google.protobuf.Message value) {
                            sink.success(value);
                        }

                        @Override
                        public void onError(Throwable t) {
                            sink.error(t);
                        }

                        @Override
                        public void onCompleted() {}
                    });
                })
                .flux();
    }

    /**
     * Bridges a server-streaming gRPC call to a Flux with real flow control: gRPC auto-request
     * is disabled and {@code request(n)} is driven by downstream reactive demand, so a slow
     * HTTP/WebSocket consumer backpressures the backend instead of buffering unbounded
     * messages in the gateway. Downstream cancellation cancels the gRPC call.
     *
     * <p>Public for the gRPC-Web filter, which streams raw message bytes through it.
     */
    public static <Q, T> Flux<T> streamingResponses(
            io.grpc.Channel channel, io.grpc.MethodDescriptor<Q, T> grpcMethod, CallOptions callOpts, Q request) {

        return Flux.create(sink -> {
            // beforeStart runs before the underlying call is started, and sink.onRequest replays
            // pending demand immediately on registration — so only capture the stream there and
            // wire the demand/cancel hooks after asyncServerStreamingCall has started the call.
            var streamRef = new java.util.concurrent.atomic.AtomicReference<ClientCallStreamObserver<Q>>();
            var observer = new ClientResponseObserver<Q, T>() {
                @Override
                public void beforeStart(ClientCallStreamObserver<Q> requestStream) {
                    requestStream.disableAutoRequestWithInitial(0);
                    streamRef.set(requestStream);
                }

                @Override
                public void onNext(T value) {
                    sink.next(value);
                }

                @Override
                public void onError(Throwable t) {
                    sink.error(t);
                }

                @Override
                public void onCompleted() {
                    sink.complete();
                }
            };
            ClientCalls.asyncServerStreamingCall(
                    channel.newCall(grpcMethod, callScoped(callOpts, sink.contextView())), request, observer);
            ClientCallStreamObserver<Q> requestStream = streamRef.get();
            sink.onRequest(n -> requestStream.request((int) Math.min(n, Integer.MAX_VALUE)));
            sink.onCancel(() -> requestStream.cancel("downstream cancelled", null));
        });
    }

    /**
     * Client-streaming dispatch: every line of an NDJSON request body becomes one message on the
     * call, and the backend's single response comes back as JSON.
     *
     * <p>The request is a {@link Flux}, not a buffered list, so a caller can upload more than fits
     * in memory: messages are sent as they arrive and the backend's flow control decides the pace
     * ({@link ClientCallStreamObserver#isReady()} gates each send, and the call's
     * {@code onReadyHandler} asks for the next line). Path and query parameters are applied to
     * every message, the way they are for a unary call.
     *
     * <p>A line that is not valid JSON for the request type fails the call with
     * {@code INVALID_ARGUMENT} and cancels it, so a bad upload stops at the bad line instead of
     * sending the rest.
     */
    public Mono<RestResponse> dispatchClientStreaming(
            RouteDefinition route,
            ServiceEndpointContext ctx,
            Map<String, String> pathVars,
            Map<String, String> queryParams,
            Flux<String> body,
            Metadata grpcMetadata) {

        MethodDescriptor md;
        try {
            md = lookup(route, ctx);
        } catch (Exception e) {
            return Mono.error(e);
        }
        final MethodDescriptor finalMd = md;

        // One message per line, parsed as it arrives.
        Flux<DynamicMessage> messages = body.filter(line -> !line.isBlank())
                .concatMap(line -> Mono.fromCallable(() -> {
                            Map<String, Object> requestMap = requestBuilder.buildRequestMap(
                                    finalMd.getInputType(), pathVars, queryParams, line, route.getHttpBody());
                            return serdeSelector.deserializeFromMap(requestMap, finalMd.getInputType(), ctx);
                        })
                        // A line the gateway could not read is the caller's mistake, not the
                        // backend's: report it as INVALID_ARGUMENT so it maps to 400 like any
                        // other unreadable request body, rather than surfacing as a 500.
                        .onErrorMap(
                                e -> !(e instanceof StatusRuntimeException),
                                e -> io.grpc.Status.INVALID_ARGUMENT
                                        .withDescription("Request line: " + e.getMessage())
                                        .withCause(e)
                                        .asRuntimeException()));

        return dispatchClientStreamingRequest(route, ctx, messages, grpcMetadata)
                .flatMap(value -> Mono.fromCallable(() -> new RestResponse(
                        serdeSelector.serializeToBytes(value, finalMd.getOutputType(), ctx, ctx.getTypeRegistry()),
                        value)));
    }

    /**
     * Client-streaming dispatch of messages the caller has already built: each one is sent on
     * the call as it arrives and the backend's single response comes back decoded.
     *
     * <p>The request is a {@link Flux}, not a buffered list, so a caller can upload more than fits
     * in memory: messages are sent as they arrive and the backend's flow control decides the pace
     * ({@link ClientCallStreamObserver#isReady()} gates each send, and the call's
     * {@code onReadyHandler} asks for the next one). The validation hook sees every message.
     *
     * <p>An error from {@code messages} (a malformed line, a validation failure) cancels the call
     * and fails the result with that error, so a bad upload stops there instead of sending the
     * rest.
     */
    public Mono<DynamicMessage> dispatchClientStreamingRequest(
            RouteDefinition route, ServiceEndpointContext ctx, Flux<DynamicMessage> messages, Metadata grpcMetadata) {

        MethodDescriptor md;
        ServiceDescriptor sd;
        try {
            md = lookup(route, ctx);
            sd = ctx.getServiceByFqn().get(route.getFullServiceName());
        } catch (Exception e) {
            return Mono.error(e);
        }

        boolean bidi = route.getMethodType() == io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING;
        var grpcMethod = descriptorCache.resolve(
                ctx,
                sd,
                md,
                bidi
                        ? io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING
                        : io.grpc.MethodDescriptor.MethodType.CLIENT_STREAMING);
        var callOpts = unaryCallOptions(ctx, grpcMetadata);
        final MethodDescriptor finalMd = md;

        log.debug("[gRPC client-stream→] {}/{}", route.getFullServiceName(), route.getMethodName());

        return Mono.create(sink -> {
            var responses = new java.util.concurrent.atomic.AtomicReference<DynamicMessage>();
            // The upload subscription, so a failed or cancelled call stops the caller's body.
            var upload = new java.util.concurrent.atomic.AtomicReference<reactor.core.Disposable>();
            // Set when the upload itself failed; the call's own onError is then just the echo of
            // the cancel that follows, and must not replace the caller's error.
            var uploadFailed = new java.util.concurrent.atomic.AtomicBoolean();

            // gRPC refuses setOnReadyHandler once the call has started, so the handler is
            // registered in beforeStart — the same reason the server-streaming path uses a
            // ClientResponseObserver rather than a plain StreamObserver.
            var readyWaiter = new java.util.concurrent.atomic.AtomicReference<MonoSink<Void>>();
            var streamRef = new java.util.concurrent.atomic.AtomicReference<ClientCallStreamObserver<DynamicMessage>>();

            var observer = new ClientResponseObserver<DynamicMessage, DynamicMessage>() {
                @Override
                public void beforeStart(ClientCallStreamObserver<DynamicMessage> requestStream) {
                    streamRef.set(requestStream);
                    requestStream.setOnReadyHandler(() -> {
                        MonoSink<Void> waiting = readyWaiter.getAndSet(null);
                        if (waiting != null) waiting.success();
                    });
                }

                @Override
                public void onNext(DynamicMessage value) {
                    // A client-streaming method answers once. A bidi method may answer many times
                    // over one HTTP request, which this shape cannot express, so the last message
                    // wins and the route loader keeps bidi off the REST surface.
                    responses.set(value);
                }

                @Override
                public void onError(Throwable t) {
                    if (uploadFailed.get()) return;
                    reactor.core.Disposable d = upload.get();
                    if (d != null) d.dispose();
                    sink.error(t);
                }

                @Override
                public void onCompleted() {
                    DynamicMessage value = responses.get();
                    if (value == null) {
                        sink.error(io.grpc.Status.INTERNAL
                                .withDescription("Backend completed the stream without sending a response")
                                .asRuntimeException());
                        return;
                    }
                    sink.success(value);
                }
            };

            ClientCalls.asyncBidiStreamingCall(
                    ctx.getChannel().newCall(grpcMethod, callScoped(callOpts, sink.contextView())), observer);
            ClientCallStreamObserver<DynamicMessage> requestStream = streamRef.get();

            // One message at a time, paced by the call's readiness rather than by the caller.
            reactor.core.Disposable subscription = messages.concatMap(message -> Mono.fromRunnable(() -> {
                                if (validationHook != null) {
                                    validationHook.validate(message, finalMd);
                                }
                            })
                            .then(awaitReady(requestStream, readyWaiter))
                            .then(Mono.fromRunnable(() -> requestStream.onNext(message))))
                    .subscribe(
                            ignored -> {},
                            error -> {
                                // A malformed message (or a validation failure) must not look like a
                                // backend error: answer on the caller's terms, then cancel the call.
                                // In that order: gRPC reports the cancel on another thread, and if
                                // it reached the sink first the caller would get CANCELLED (a 500)
                                // instead of the INVALID_ARGUMENT (a 400) this line deserves.
                                uploadFailed.set(true);
                                sink.error(error);
                                requestStream.cancel("request stream failed", error);
                            },
                            requestStream::onCompleted);
            upload.set(subscription);

            sink.onCancel(() -> {
                subscription.dispose();
                requestStream.cancel("client cancelled", null);
            });
        });
    }

    /**
     * Server-streaming dispatch of a request the caller has already built, answering decoded
     * messages with the same flow control as {@link #streamingResponses}: the backend is read only
     * as fast as the subscriber asks, and cancelling cancels the call.
     */
    public Flux<DynamicMessage> dispatchStreamingRequest(
            RouteDefinition route, ServiceEndpointContext ctx, DynamicMessage request, Metadata grpcMetadata) {
        MethodDescriptor md;
        ServiceDescriptor sd;
        try {
            md = lookup(route, ctx);
            sd = ctx.getServiceByFqn().get(route.getFullServiceName());
            if (validationHook != null) {
                validationHook.validate(request, md);
            }
        } catch (Exception e) {
            return Flux.error(e);
        }
        var grpcMethod = descriptorCache.resolve(ctx, sd, md, io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING);
        log.debug("[gRPC stream→] {}/{}", route.getFullServiceName(), route.getMethodName());
        return streamingResponses(ctx.getChannel(), grpcMethod, callOptions(grpcMetadata), request);
    }

    /**
     * The same server-streaming dispatch for a method named directly rather than through a route:
     * what the push module's upstream-backed topics use, since a method carrying only
     * {@code (shiguchi.api.topic)} has no route of its own.
     */
    public Flux<DynamicMessage> dispatchStreamingRequest(
            ServiceEndpointContext ctx,
            String serviceFqn,
            String methodName,
            DynamicMessage request,
            Metadata grpcMetadata) {
        MethodDescriptor md;
        ServiceDescriptor sd;
        try {
            md = ctx.lookupMethod(serviceFqn, methodName)
                    .orElseThrow(() -> new IllegalStateException("Method not found: " + serviceFqn + "/" + methodName));
            sd = ctx.getServiceByFqn().get(serviceFqn);
            if (validationHook != null) {
                validationHook.validate(request, md);
            }
        } catch (Exception e) {
            return Flux.error(e);
        }
        var grpcMethod = descriptorCache.resolve(ctx, sd, md, io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING);
        log.debug("[gRPC stream→] {}/{} (upstream topic)", serviceFqn, methodName);
        return streamingResponses(ctx.getChannel(), grpcMethod, callOptions(grpcMetadata), request);
    }

    /**
     * Unary dispatch of a request the caller built, for a method named directly rather than
     * through a route: what the push module's hooks use to call a backend's
     * {@code shiguchi.cursus.v1.PushHooks} implementation, which has no route of its own.
     */
    public Mono<DynamicMessage> dispatchRequest(
            ServiceEndpointContext ctx,
            String serviceFqn,
            String methodName,
            DynamicMessage request,
            Metadata grpcMetadata) {
        MethodDescriptor md;
        ServiceDescriptor sd;
        try {
            md = ctx.lookupMethod(serviceFqn, methodName)
                    .orElseThrow(() -> new IllegalStateException("Method not found: " + serviceFqn + "/" + methodName));
            sd = ctx.getServiceByFqn().get(serviceFqn);
            if (validationHook != null) {
                validationHook.validate(request, md);
            }
        } catch (Exception e) {
            return Mono.error(e);
        }
        var grpcMethod = descriptorCache.resolve(ctx, sd, md, io.grpc.MethodDescriptor.MethodType.UNARY);
        var callOpts = unaryCallOptions(ctx, grpcMetadata);
        log.debug("[gRPC →] {}/{} (direct)", serviceFqn, methodName);
        return Mono.create(sink -> {
            var call = ctx.getChannel().newCall(grpcMethod, callScoped(callOpts, sink.contextView()));
            ClientCalls.asyncUnaryCall(call, request, new StreamObserver<>() {
                @Override
                public void onNext(DynamicMessage value) {
                    sink.success(value);
                }

                @Override
                public void onError(Throwable t) {
                    sink.error(t);
                }

                @Override
                public void onCompleted() {
                    sink.success();
                }
            });
        });
    }

    private static MethodDescriptor lookup(RouteDefinition route, ServiceEndpointContext ctx) {
        return ctx.lookupMethod(route.getFullServiceName(), route.getMethodName())
                .orElseThrow(() -> new IllegalStateException(
                        "Method not found: " + route.getFullServiceName() + "/" + route.getMethodName()));
    }

    /**
     * Completes once the call can take another message, so a fast uploader cannot outrun a slow
     * backend and buffer the whole upload in the gateway.
     *
     * <p>gRPC invokes the ready handler when the send window reopens. The handler is registered
     * once per call (registering it per message would drop earlier waiters), and re-checks
     * {@code isReady()} after registering: the window can open between the first check and the
     * registration, and a waiter that missed that edge would otherwise never be woken.
     */
    private static Mono<Void> awaitReady(
            ClientCallStreamObserver<DynamicMessage> requestStream,
            java.util.concurrent.atomic.AtomicReference<MonoSink<Void>> waiter) {
        if (requestStream.isReady()) return Mono.empty();
        return Mono.create(ready -> {
            waiter.set(ready);
            // Covers the window opening between the isReady() above and this line.
            if (requestStream.isReady() && waiter.getAndSet(null) != null) ready.success();
        });
    }

    /**
     * Direct unary dispatch for the gRPC UI — bypasses HTTP path/query merging.
     * The caller supplies the full JSON payload; it is deserialized and sent as-is.
     */
    public Mono<String> dispatchDirect(
            ServiceEndpointContext ctx, String serviceFqn, String methodName, String bodyJson, Metadata grpcMetadata) {

        MethodDescriptor md;
        ServiceDescriptor sd;
        DynamicMessage request;

        try {
            md = ctx.lookupMethod(serviceFqn, methodName)
                    .orElseThrow(() -> new IllegalStateException("Method not found: " + serviceFqn + "/" + methodName));
            sd = ctx.getServiceByFqn().get(serviceFqn);
            String json = (bodyJson != null && !bodyJson.isBlank()) ? bodyJson : "{}";
            request = serdeSelector.deserialize(json, md.getInputType(), ctx, ctx.getTypeRegistry());
        } catch (Exception e) {
            return Mono.error(e);
        }

        var grpcMethod = descriptorCache.resolve(ctx, sd, md, io.grpc.MethodDescriptor.MethodType.UNARY);
        var callOpts = unaryCallOptions(ctx, grpcMetadata);

        log.debug("[gRPC dispatch→] {}/{} body={}", serviceFqn, methodName, bodyJson);

        final MethodDescriptor finalMd = md;
        return Mono.create(sink -> {
            var call = ctx.getChannel().newCall(grpcMethod, callScoped(callOpts, sink.contextView()));
            ClientCalls.asyncUnaryCall(call, request, new StreamObserver<>() {
                @Override
                public void onNext(DynamicMessage value) {
                    try {
                        String json =
                                serdeSelector.serialize(value, finalMd.getOutputType(), ctx, ctx.getTypeRegistry());
                        log.debug("[gRPC dispatch←] {}/{} response={}", serviceFqn, methodName, json);
                        sink.success(json);
                    } catch (Exception e) {
                        sink.error(e);
                    }
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
     * Client-streaming dispatch for the gRPC UI: the console supplies the request messages as
     * whole JSON payloads, and the backend's single response comes back as a JSON string.
     *
     * <p>The console holds its messages in memory already (someone typed them), so this takes a
     * list rather than a {@link Flux} — the streaming that matters here is on the wire, not in
     * the browser.
     */
    public Mono<String> dispatchClientStreamingDirect(
            ServiceEndpointContext ctx,
            String serviceFqn,
            String methodName,
            java.util.List<String> payloads,
            Metadata grpcMetadata) {

        MethodDescriptor md;
        ServiceDescriptor sd;
        try {
            md = ctx.lookupMethod(serviceFqn, methodName)
                    .orElseThrow(() -> new IllegalStateException("Method not found: " + serviceFqn + "/" + methodName));
            sd = ctx.getServiceByFqn().get(serviceFqn);
        } catch (Exception e) {
            return Mono.error(e);
        }

        var grpcMethod = descriptorCache.resolve(ctx, sd, md, io.grpc.MethodDescriptor.MethodType.CLIENT_STREAMING);
        var callOpts = unaryCallOptions(ctx, grpcMetadata);
        final MethodDescriptor finalMd = md;

        log.debug("[gRPC client-stream→] {}/{} messages={}", serviceFqn, methodName, payloads.size());

        return Mono.create(sink -> {
            var answer = new java.util.concurrent.atomic.AtomicReference<DynamicMessage>();
            var streamRef = new java.util.concurrent.atomic.AtomicReference<ClientCallStreamObserver<DynamicMessage>>();

            var observer = new ClientResponseObserver<DynamicMessage, DynamicMessage>() {
                @Override
                public void beforeStart(ClientCallStreamObserver<DynamicMessage> requestStream) {
                    streamRef.set(requestStream);
                }

                @Override
                public void onNext(DynamicMessage value) {
                    answer.set(value);
                }

                @Override
                public void onError(Throwable t) {
                    sink.error(t);
                }

                @Override
                public void onCompleted() {
                    DynamicMessage value = answer.get();
                    if (value == null) {
                        sink.error(io.grpc.Status.INTERNAL
                                .withDescription("Backend completed the stream without sending a response")
                                .asRuntimeException());
                        return;
                    }
                    try {
                        sink.success(
                                serdeSelector.serialize(value, finalMd.getOutputType(), ctx, ctx.getTypeRegistry()));
                    } catch (Exception e) {
                        sink.error(e);
                    }
                }
            };

            ClientCalls.asyncBidiStreamingCall(
                    ctx.getChannel().newCall(grpcMethod, callScoped(callOpts, sink.contextView())), observer);
            ClientCallStreamObserver<DynamicMessage> requestStream = streamRef.get();

            try {
                for (String payload : payloads) {
                    String json = (payload != null && !payload.isBlank()) ? payload : "{}";
                    requestStream.onNext(
                            serdeSelector.deserialize(json, finalMd.getInputType(), ctx, ctx.getTypeRegistry()));
                }
                requestStream.onCompleted();
            } catch (Exception e) {
                requestStream.cancel("request payload rejected", e);
                sink.error(io.grpc.Status.INVALID_ARGUMENT
                        .withDescription("Request message: " + e.getMessage())
                        .withCause(e)
                        .asRuntimeException());
            }

            sink.onCancel(() -> requestStream.cancel("console cancelled", null));
        });
    }

    /**
     * Direct server-streaming dispatch for the gRPC UI — bypasses HTTP path/query merging.
     */
    public Flux<String> dispatchStreamingDirect(
            ServiceEndpointContext ctx, String serviceFqn, String methodName, String bodyJson, Metadata grpcMetadata) {

        MethodDescriptor md;
        DynamicMessage request;
        io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> grpcMethod;

        try {
            md = ctx.lookupMethod(serviceFqn, methodName)
                    .orElseThrow(() -> new IllegalStateException("Method not found: " + serviceFqn + "/" + methodName));
            ServiceDescriptor sd = ctx.getServiceByFqn().get(serviceFqn);
            String json = (bodyJson != null && !bodyJson.isBlank()) ? bodyJson : "{}";
            request = serdeSelector.deserialize(json, md.getInputType(), ctx, ctx.getTypeRegistry());
            grpcMethod = descriptorCache.resolve(ctx, sd, md, io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING);
            log.debug("[gRPC stream→] {}/{} body={}", serviceFqn, methodName, json);
        } catch (Exception e) {
            return Flux.error(e);
        }

        final MethodDescriptor finalMd = md;
        return streamingResponses(ctx.getChannel(), grpcMethod, callOptions(grpcMetadata), request)
                .handle((value, sink) -> {
                    try {
                        sink.next(serdeSelector.serialize(value, finalMd.getOutputType(), ctx, ctx.getTypeRegistry()));
                    } catch (Exception e) {
                        sink.error(e);
                    }
                });
    }

    /**
     * Unary dispatch for the GraphQL path: accepts pre-built request args as Map and returns
     * response as Map — no JSON serialization at any point.
     */
    public Mono<Map<String, Object>> dispatchToMap(
            RouteDefinition route, ServiceEndpointContext ctx, Map<String, Object> requestArgs, Metadata grpcMetadata) {
        return dispatchToMap(route, ctx, requestArgs, grpcMetadata, null);
    }

    /**
     * Unary GraphQL dispatch with optional selection projection: when non-null, only the
     * composite subtrees the query selected are converted to Map.
     */
    public Mono<Map<String, Object>> dispatchToMap(
            RouteDefinition route,
            ServiceEndpointContext ctx,
            Map<String, Object> requestArgs,
            Metadata grpcMetadata,
            dev.appiaworks.protobuf.extras.serde.ProjectionNode projection) {
        MethodDescriptor md = ctx.lookupMethod(route.getFullServiceName(), route.getMethodName())
                .orElse(null);
        if (md == null) {
            return Mono.error(new IllegalStateException(
                    "Method not found: " + route.getFullServiceName() + "/" + route.getMethodName()));
        }
        return dispatchMessage(route, ctx, requestArgs, grpcMetadata).handle((value, sink) -> {
            try {
                Map<String, Object> responseMap =
                        serdeSelector.serializeToMapProjected(value, md.getOutputType(), ctx, projection);
                log.debug(
                        "[gRPC dispatch←] {}/{} response={}",
                        route.getFullServiceName(),
                        route.getMethodName(),
                        responseMap);
                sink.next(responseMap);
            } catch (Exception e) {
                sink.error(e);
            }
        });
    }

    /**
     * Unary dispatch from a request Map to the decoded response message, no JSON anywhere: the
     * Map and GraphQL paths build on it, and a {@link RestBodyCodec} on the REST surface uses it
     * to encode the response in its own format while pagination headers still come off the message.
     */
    public Mono<DynamicMessage> dispatchMessage(
            RouteDefinition route, ServiceEndpointContext ctx, Map<String, Object> requestArgs, Metadata grpcMetadata) {

        DynamicMessage request;
        try {
            MethodDescriptor md = ctx.lookupMethod(route.getFullServiceName(), route.getMethodName())
                    .orElseThrow(() -> new IllegalStateException(
                            "Method not found: " + route.getFullServiceName() + "/" + route.getMethodName()));
            request = serdeSelector.deserializeFromMap(requestArgs, md.getInputType(), ctx);
        } catch (Exception e) {
            return Mono.error(e);
        }
        log.debug("[gRPC dispatch→] {}/{} args={}", route.getFullServiceName(), route.getMethodName(), requestArgs);
        return dispatchRequest(route, ctx, request, grpcMetadata);
    }

    /**
     * {@link #dispatchMessage} for a request message the caller built itself — the
     * {@code google.api.HttpBody} path, whose raw bytes go into the message directly rather than
     * through a request map. Validation applies as on every other path.
     */
    public Mono<DynamicMessage> dispatchRequest(
            RouteDefinition route, ServiceEndpointContext ctx, DynamicMessage request, Metadata grpcMetadata) {

        MethodDescriptor md;
        ServiceDescriptor sd;
        try {
            md = ctx.lookupMethod(route.getFullServiceName(), route.getMethodName())
                    .orElseThrow(() -> new IllegalStateException(
                            "Method not found: " + route.getFullServiceName() + "/" + route.getMethodName()));
            sd = ctx.getServiceByFqn().get(route.getFullServiceName());
            if (validationHook != null) {
                validationHook.validate(request, md);
            }
        } catch (Exception e) {
            return Mono.error(e);
        }

        var grpcMethod = descriptorCache.resolve(ctx, sd, md, io.grpc.MethodDescriptor.MethodType.UNARY);
        var callOpts = unaryCallOptions(ctx, grpcMetadata);

        return Mono.create(sink -> {
            var call = ctx.getChannel().newCall(grpcMethod, callScoped(callOpts, sink.contextView()));
            ClientCalls.asyncUnaryCall(call, request, new StreamObserver<>() {
                @Override
                public void onNext(DynamicMessage value) {
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
     * Server-streaming dispatch for the GraphQL path: each item emitted as Map — no JSON.
     */
    public Flux<Map<String, Object>> dispatchStreamingToMap(
            RouteDefinition route, ServiceEndpointContext ctx, Map<String, Object> requestArgs, Metadata grpcMetadata) {
        return dispatchStreamingToMap(route, ctx, requestArgs, grpcMetadata, null);
    }

    /**
     * Server-streaming GraphQL dispatch with optional selection projection (see
     * {@link #dispatchToMap(RouteDefinition, ServiceEndpointContext, Map, Metadata,
     * dev.appiaworks.protobuf.extras.serde.ProjectionNode)}).
     */
    public Flux<Map<String, Object>> dispatchStreamingToMap(
            RouteDefinition route,
            ServiceEndpointContext ctx,
            Map<String, Object> requestArgs,
            Metadata grpcMetadata,
            dev.appiaworks.protobuf.extras.serde.ProjectionNode projection) {

        MethodDescriptor md;
        DynamicMessage request;
        io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> grpcMethod;

        try {
            md = ctx.lookupMethod(route.getFullServiceName(), route.getMethodName())
                    .orElseThrow();
            ServiceDescriptor sd = ctx.getServiceByFqn().get(route.getFullServiceName());
            request = serdeSelector.deserializeFromMap(requestArgs, md.getInputType(), ctx);
            grpcMethod = descriptorCache.resolve(ctx, sd, md, io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING);
            log.debug("[gRPC stream→] {}/{} args={}", route.getFullServiceName(), route.getMethodName(), requestArgs);
        } catch (Exception e) {
            return Flux.error(e);
        }

        final MethodDescriptor finalMd = md;
        return streamingResponses(ctx.getChannel(), grpcMethod, callOptions(grpcMetadata), request)
                .handle((value, sink) -> {
                    try {
                        sink.next(
                                serdeSelector.serializeToMapProjected(value, finalMd.getOutputType(), ctx, projection));
                    } catch (Exception e) {
                        sink.error(e);
                    }
                });
    }

    /**
     * The call options with what the gateway filter put in the subscriber context for this request:
     * a {@link dev.appiaworks.shiguchi.grpc.ResponseMetadataCapture} when response headers are forwarded, so
     * the channel interceptor records what the backend sends back, and the caller's
     * {@code grpc-timeout} as an {@link io.grpc.Deadline}, which wins only when it is sooner than the
     * deadline already set.
     */
    private static CallOptions callScoped(CallOptions options, reactor.util.context.ContextView context) {
        CallOptions scoped = context.<dev.appiaworks.shiguchi.grpc.ResponseMetadataCapture>getOrEmpty(
                        dev.appiaworks.shiguchi.grpc.ResponseMetadataCapture.class)
                .map(capture -> options.withOption(dev.appiaworks.shiguchi.grpc.ResponseMetadataCapture.KEY, capture))
                .orElse(options);
        io.grpc.Deadline client =
                context.<io.grpc.Deadline>getOrEmpty(io.grpc.Deadline.class).orElse(null);
        if (client != null && (scoped.getDeadline() == null || client.isBefore(scoped.getDeadline()))) {
            scoped = scoped.withDeadline(client);
        }
        return scoped;
    }

    private static CallOptions callOptions(Metadata metadata) {
        if (metadata == null || metadata.keys().isEmpty()) return CallOptions.DEFAULT;
        return CallOptions.DEFAULT.withOption(GrpcCallContext.METADATA_KEY, metadata);
    }

    /**
     * Call options for unary dispatch: base options plus the effective deadline
     * (per-service {@code deadline}, else {@code client.default-deadline}; zero disables).
     * Server-streaming calls deliberately never get a deadline — it would cap the lifetime
     * of intentionally long-lived streams.
     */
    private CallOptions unaryCallOptions(ServiceEndpointContext ctx, Metadata metadata) {
        CallOptions opts = callOptions(metadata);
        java.time.Duration deadline = ctx.getConfig().effectiveDeadline(defaultDeadline);
        if (deadline != null && !deadline.isZero() && !deadline.isNegative()) {
            opts = opts.withDeadlineAfter(deadline.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        }
        return opts;
    }
}
