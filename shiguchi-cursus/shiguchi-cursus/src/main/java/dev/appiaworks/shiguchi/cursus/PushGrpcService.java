package dev.appiaworks.shiguchi.cursus;

import dev.appiaworks.shiguchi.grpc.GatewayHostedService;
import dev.appiaworks.shiguchi.proto.cursus.PublishRequest;
import dev.appiaworks.shiguchi.proto.cursus.PublishResponse;
import dev.appiaworks.shiguchi.proto.cursus.PublishedEvent;
import dev.appiaworks.shiguchi.proto.cursus.PushServiceGrpc;
import dev.appiaworks.shiguchi.proto.cursus.SubscribeRequest;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import io.grpc.MethodDescriptor;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.security.core.Authentication;
import reactor.core.Disposable;
import reactor.core.Exceptions;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * {@code shiguchi.cursus.v1.PushService}, the push surface as a gRPC service the gateway hosts:
 * {@code Publish} runs the same checks as {@code POST <path>/publish}, {@code Subscribe} is the
 * SSE subscription as a server stream, with resume, the shared queue policy and the connection
 * lifetime. A stream the gateway closes ends with {@code UNAVAILABLE} and the trailer {@code
 * push-close-reason} ({@code rebalance}, {@code shutdown}, {@code admin}); an {@code AT_LEAST_ONCE}
 * subscriber that stops reading gets {@code RESOURCE_EXHAUSTED} once its queue fills. Events are
 * written as the client asks for them: gRPC flow control carries the demand upstream.
 *
 * <p>Fan-out cost: the event's wire bytes are computed once ({@link PushGrpcFrames}) and {@code
 * Subscribe} is bound with a pass-through response marshaller, so every stream writes the same
 * {@code byte[]} and no stream encodes a message of its own; a typed event ({@code message}) is
 * forwarded as it was published, with no JSON on the way.
 */
public final class PushGrpcService implements PushServiceGrpc.AsyncService, GatewayHostedService {

    /** Pre-serialized responses: the bytes are the message. */
    static final MethodDescriptor.Marshaller<byte[]> BYTES = new MethodDescriptor.Marshaller<>() {
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
                        .withDescription("unreadable frame")
                        .withCause(e)
                        .asRuntimeException();
            }
        }
    };

    /** {@code Subscribe} with the response left as bytes. */
    static final MethodDescriptor<SubscribeRequest, byte[]> SUBSCRIBE_RAW =
            PushServiceGrpc.getSubscribeMethod().toBuilder(
                            PushServiceGrpc.getSubscribeMethod().getRequestMarshaller(), BYTES)
                    .build();

    private final PushProperties properties;
    private final PushStore store;
    private final PushPublisher publisher;
    private final TopicAuthorizer authorizer;
    private final PushMetrics metrics;
    private final PushStreams streams;
    private final PushTokenAuthenticator tokens;
    private final PushConnections connections;
    private final GrpcRouteRegistry registry;
    private final PushGrpcFrames frames = new PushGrpcFrames();
    private final PushSessions sessions;

    public PushGrpcService(
            PushProperties properties,
            PushBroker broker,
            PushStore store,
            PushPublisher publisher,
            TopicAuthorizer authorizer,
            PushMetrics metrics,
            PushShutdown shutdown,
            PushTokenAuthenticator tokens,
            PushConnections connections,
            GrpcRouteRegistry registry) {
        this(properties, broker, store, publisher, authorizer, metrics, shutdown, tokens, connections, registry, null);
    }

    /** With the upstream-backed topics attached: a subscription to one opens its upstream stream. */
    public PushGrpcService(
            PushProperties properties,
            PushBroker broker,
            PushStore store,
            PushPublisher publisher,
            TopicAuthorizer authorizer,
            PushMetrics metrics,
            PushShutdown shutdown,
            PushTokenAuthenticator tokens,
            PushConnections connections,
            GrpcRouteRegistry registry,
            PushUpstreams upstreams) {
        this(
                properties,
                broker,
                store,
                publisher,
                authorizer,
                metrics,
                shutdown,
                tokens,
                connections,
                registry,
                upstreams,
                null);
    }

    /** With the persistent sessions: {@code SubscribeRequest.session} and {@code Ack}. */
    public PushGrpcService(
            PushProperties properties,
            PushBroker broker,
            PushStore store,
            PushPublisher publisher,
            TopicAuthorizer authorizer,
            PushMetrics metrics,
            PushShutdown shutdown,
            PushTokenAuthenticator tokens,
            PushConnections connections,
            GrpcRouteRegistry registry,
            PushUpstreams upstreams,
            PushSessions sessions) {
        this.sessions = sessions;
        this.properties = properties;
        this.store = store;
        this.publisher = publisher;
        this.authorizer = authorizer;
        this.metrics = metrics;
        this.streams = new PushStreams(broker, store, properties, shutdown, upstreams, sessions);
        this.tokens = tokens;
        this.connections = connections;
        this.registry = registry;
    }

    /**
     * The generated binding, with {@code Subscribe} rebound to write pre-serialized frames. Served
     * twice: on the in-process server the gateway's own surfaces dial, and, as a {@link
     * GatewayHostedService}, in place on the native gRPC port, where a stream then costs one hop
     * rather than the proxy's bridge into the in-process channel.
     */
    @Override
    public ServerServiceDefinition bindService() {
        ServerServiceDefinition generated = PushServiceGrpc.bindService(this);
        ServerServiceDefinition.Builder service = ServerServiceDefinition.builder(PushServiceGrpc.SERVICE_NAME);
        for (ServerMethodDefinition<?, ?> method : generated.getMethods()) {
            if (!method.getMethodDescriptor().getFullMethodName().equals(SUBSCRIBE_RAW.getFullMethodName())) {
                service.addMethod(method);
            }
        }
        service.addMethod(SUBSCRIBE_RAW, ServerCalls.asyncServerStreamingCall(this::subscribeRaw));
        // The metadata interceptor rides with the definition, so every server this is added to
        // (the in-process one, the gateway's native gRPC port) authenticates the same way.
        return io.grpc.ServerInterceptors.intercept(service.build(), new PushGrpcHeaders());
    }

    @Override
    public void publish(PublishRequest request, StreamObserver<PublishResponse> response) {
        Mono<Optional<Authentication>> caller = PushGrpcHeaders.caller(tokens);
        Mono.defer(() -> {
                    List<Topic> topics = PushStreams.topics(request.getTopicsList(), properties);
                    PushEventProtos.Payload payload = PushEventProtos.payload(request, registry);
                    String key = request.getKey().isEmpty() ? null : request.getKey();
                    String origin = PushOrigin.parse(request.getOrigin());
                    return caller.flatMap(auth -> Flux.fromIterable(topics)
                            .concatMap(
                                    t -> authorizer.authorizePublish(auth.orElse(null), t, properties.namespaceOf(t)))
                            .thenMany(Flux.fromIterable(topics)
                                    .concatMap(t -> publisher.publishAsOperator(
                                            t,
                                            payload.json(),
                                            request.getAttributesMap(),
                                            key,
                                            payload.typeUrl(),
                                            payload.proto(),
                                            origin,
                                            TopicAuthorizer.isAuthenticated(auth.orElse(null))
                                                    ? auth.get().getName()
                                                    : null)))
                            .map(e -> PublishedEvent.newBuilder()
                                    .setTopic(e.topic().toString())
                                    .setId(e.id())
                                    .build())
                            .collectList());
                })
                .map(events -> PublishResponse.newBuilder().addAllEvents(events).build())
                .onErrorMap(PushException.class, PushEventProtos::status)
                .subscribe(
                        r -> {
                            response.onNext(r);
                            response.onCompleted();
                        },
                        response::onError);
    }

    private volatile PushRegistry presence;
    private volatile PushSender sender;

    /** Targeted delivery: {@code Send}. */
    public void setSender(PushSender sender) {
        this.sender = sender;
    }

    @Override
    public void send(
            dev.appiaworks.shiguchi.proto.cursus.SendRequest request,
            StreamObserver<dev.appiaworks.shiguchi.proto.cursus.SendResponse> response) {
        PushSender s = sender;
        if (s == null) {
            response.onError(PushEventProtos.status(PushException.badRequest("targeted Send is not configured")));
            return;
        }
        PushGrpcHeaders.caller(tokens)
                .flatMap(auth -> {
                    PushSender.Target target = switch (request.getTargetCase()) {
                        case PRINCIPAL -> PushSender.Target.ofPrincipal(request.getPrincipal());
                        case CONNECTION_ID -> PushSender.Target.ofConnection(request.getConnectionId());
                        case DEVICE ->
                            PushSender.Target.ofDevice(
                                    request.getDevice().getPrincipal(),
                                    request.getDevice().getDeviceId());
                        default ->
                            throw PushException.badRequest("a target is required: principal, connection_id or device");
                    };
                    if ((target.principal() != null && target.principal().isBlank())
                            || (target.connectionId() != null
                                    && target.connectionId().isBlank())
                            || (target.deviceId() != null && target.deviceId().isBlank())) {
                        throw PushException.badRequest("the target names an empty value");
                    }
                    PushEventProtos.Payload payload = PushEventProtos.payload(request, registry);
                    return s.send(
                            auth.orElse(null),
                            target,
                            payload,
                            PushOrigin.forOperator(request.getAttributesMap(), request.getOrigin(), null),
                            request.getRequireAck(),
                            request.getTimeoutMs() > 0 ? java.time.Duration.ofMillis(request.getTimeoutMs()) : null);
                })
                .map(result -> {
                    dev.appiaworks.shiguchi.proto.cursus.SendResponse.Builder out =
                            dev.appiaworks.shiguchi.proto.cursus.SendResponse.newBuilder()
                                    .setId(result.id());
                    for (PushSender.Delivery d : result.deliveries()) {
                        dev.appiaworks.shiguchi.proto.cursus.DeliveryStatus.Builder b =
                                dev.appiaworks.shiguchi.proto.cursus.DeliveryStatus.newBuilder()
                                        .setConnectionId(d.connectionId())
                                        .setStatus(PushSender.toProto(d.status()));
                        if (d.instance() != null) b.setInstance(d.instance());
                        out.addDeliveries(b);
                    }
                    return out.build();
                })
                .onErrorMap(PushException.class, PushEventProtos::status)
                .subscribe(
                        out -> {
                            response.onNext(out);
                            response.onCompleted();
                        },
                        response::onError);
    }

    /** The connection registry that answers {@code HasSubscribers} and {@code GetSubscribers}. */
    public void setRegistry(PushRegistry registry) {
        this.presence = registry;
    }

    /** Subscribe permission is the permission to ask: a backend that may subscribe may ask who listens. */
    private Mono<List<Topic>> authorizedTopics(List<String> names) {
        return PushGrpcHeaders.caller(tokens).flatMap(auth -> {
            List<Topic> topics = PushStreams.topics(names, properties);
            return Flux.fromIterable(topics)
                    .concatMap(t -> authorizer.authorizeSubscribe(auth.orElse(null), t, properties.namespaceOf(t)))
                    .then(Mono.just(topics));
        });
    }

    @Override
    public void hasSubscribers(
            dev.appiaworks.shiguchi.proto.cursus.HasSubscribersRequest request,
            StreamObserver<dev.appiaworks.shiguchi.proto.cursus.HasSubscribersResponse> response) {
        PushRegistry r = presence;
        if (r == null) {
            response.onError(PushEventProtos.status(PushException.badRequest("push registry is not configured")));
            return;
        }
        Mono.defer(() -> authorizedTopics(request.getTopicsList()))
                .flatMapMany(Flux::fromIterable)
                .concatMap(t -> r.hasSubscribers(t).map(b -> java.util.Map.entry(t.toString(), b)))
                .collectList()
                .map(entries -> {
                    dev.appiaworks.shiguchi.proto.cursus.HasSubscribersResponse.Builder out =
                            dev.appiaworks.shiguchi.proto.cursus.HasSubscribersResponse.newBuilder();
                    for (var e : entries) out.putSubscribers(e.getKey(), e.getValue());
                    return out.build();
                })
                .onErrorMap(PushException.class, PushEventProtos::status)
                .subscribe(
                        out -> {
                            response.onNext(out);
                            response.onCompleted();
                        },
                        response::onError);
    }

    @Override
    public void getSubscribers(
            dev.appiaworks.shiguchi.proto.cursus.GetSubscribersRequest request,
            StreamObserver<dev.appiaworks.shiguchi.proto.cursus.GetSubscribersResponse> response) {
        PushRegistry r = presence;
        if (r == null) {
            response.onError(PushEventProtos.status(PushException.badRequest("push registry is not configured")));
            return;
        }
        int size = request.getPageSize() <= 0 ? 100 : Math.min(request.getPageSize(), 1000);
        Mono.defer(() -> {
                    int offset;
                    try {
                        offset = request.getPageToken().isEmpty() ? 0 : Integer.parseInt(request.getPageToken());
                    } catch (NumberFormatException e) {
                        return Mono.error(PushException.badRequest("invalid page_token"));
                    }
                    return authorizedTopics(List.of(request.getTopic())).flatMap(topics -> {
                        Topic topic = topics.get(0);
                        return Mono.zip(r.count(topic), r.subscribers(topic, offset, size + 1))
                                .map(t -> {
                                    List<PushConnections.Info> page = t.getT2();
                                    boolean more = page.size() > size;
                                    dev.appiaworks.shiguchi.proto.cursus.GetSubscribersResponse.Builder out =
                                            dev.appiaworks.shiguchi.proto.cursus.GetSubscribersResponse.newBuilder()
                                                    .setCount(t.getT1());
                                    for (PushConnections.Info i : page.subList(0, Math.min(size, page.size()))) {
                                        dev.appiaworks.shiguchi.proto.cursus.Subscriber.Builder b =
                                                dev.appiaworks.shiguchi.proto.cursus.Subscriber.newBuilder()
                                                        .setConnectionId(i.id())
                                                        .setTransport(i.transport())
                                                        .setSince(com.google.protobuf.Timestamp.newBuilder()
                                                                .setSeconds(i.since()
                                                                        .getEpochSecond())
                                                                .setNanos(i.since()
                                                                        .getNano()));
                                        if (i.principal() != null) b.setPrincipal(i.principal());
                                        if (i.deviceId() != null) b.setDeviceId(i.deviceId());
                                        if (i.instance() != null) b.setInstance(i.instance());
                                        out.addSubscribers(b);
                                    }
                                    if (more) out.setNextPageToken(Integer.toString(offset + size));
                                    return out.build();
                                });
                    });
                })
                .onErrorMap(PushException.class, PushEventProtos::status)
                .subscribe(
                        out -> {
                            response.onNext(out);
                            response.onCompleted();
                        },
                        response::onError);
    }

    /** {@code Ack}: moves the caller's session cursor on a topic forward. */
    @Override
    public void ack(
            dev.appiaworks.shiguchi.proto.cursus.AckRequest request,
            StreamObserver<dev.appiaworks.shiguchi.proto.cursus.AckResponse> response) {
        if (sessions == null) {
            response.onError(PushEventProtos.status(PushException.badRequest("sessions are not configured")));
            return;
        }
        PushGrpcHeaders.caller(tokens)
                .flatMap(auth -> !request.getGroup().isEmpty()
                        ? (sessions.groups() == null
                                ? Mono.<Void>error(PushException.badRequest("consumer groups are not configured"))
                                : sessions.groups()
                                        .ack(
                                                auth.orElse(null),
                                                request.getGroup(),
                                                request.getMember(),
                                                request.getTopic(),
                                                request.getId(),
                                                authorizer))
                        : sessions.ack(
                                auth.orElse(null),
                                request.getSession().isEmpty() ? null : request.getSession(),
                                request.getTopic(),
                                request.getId(),
                                authorizer))
                .onErrorMap(PushException.class, PushEventProtos::status)
                .subscribe(v -> {}, response::onError, () -> {
                    response.onNext(dev.appiaworks.shiguchi.proto.cursus.AckResponse.newBuilder()
                            .setTopic(request.getTopic())
                            .setCursor(request.getId())
                            .build());
                    response.onCompleted();
                });
    }

    /** {@code Subscribe}, writing each event's shared wire bytes. */
    void subscribeRaw(SubscribeRequest request, StreamObserver<byte[]> response) {
        ServerCallStreamObserver<byte[]> call = (ServerCallStreamObserver<byte[]>) response;
        String device = PushGrpcHeaders.device();
        Mono<Optional<Authentication>> caller = PushGrpcHeaders.caller(tokens);
        // gRPC allows the ready and cancel handlers only before this method returns, and the caller
        // is decoded asynchronously (a JWKS fetch): the writer registers them now, writes later.
        Writer<byte[]> writer = new Writer<>(call);
        Disposable setup = Mono.defer(() -> {
                    List<Topic> topics = PushStreams.topics(request.getTopicsList(), properties);
                    String after = PushStreams.after(request.getAfter(), store);
                    String origin = PushOrigin.parse(request.getOrigin());
                    Qos qos = switch (request.getQos()) {
                        case AT_MOST_ONCE -> Qos.AT_MOST_ONCE;
                        case AT_LEAST_ONCE -> Qos.AT_LEAST_ONCE;
                        default -> null;
                    };
                    return caller.flatMap(auth -> Flux.fromIterable(topics)
                            .concatMap(
                                    t -> authorizer.authorizeSubscribe(auth.orElse(null), t, properties.namespaceOf(t)))
                            .then(Mono.defer(() -> stream(
                                    topics,
                                    qos,
                                    after,
                                    new PushStreams.Caller(
                                            auth.orElse(null),
                                            request.getSession().isEmpty() ? null : request.getSession(),
                                            request.getGroup().isEmpty() ? null : request.getGroup(),
                                            request.getMember().isEmpty() ? null : request.getMember(),
                                            origin),
                                    device))));
                })
                .onErrorMap(PushException.class, PushEventProtos::status)
                .subscribe(
                        events -> {
                            writer.onComplete(events.closed());
                            events.flux().subscribe(writer);
                        },
                        call::onError);
        writer.setup(setup);
    }

    /**
     * A subscription's frames, and the status that ends the call when they complete: completion
     * means the gateway closed the stream (lifetime, shutdown, admin), never that the topics ran
     * dry. Carried beside the flux, not as an error signal inside it, so a client that cancels at
     * the same instant gets no dropped-error noise.
     */
    record Stream(Flux<byte[]> flux, java.util.function.Supplier<StatusRuntimeException> closed) {}

    private Mono<Stream> stream(
            List<Topic> requested, Qos qos, String after, PushStreams.Caller caller, String device) {
        PushConnections.Live live = connections.open("grpc", caller.auth());
        live.deviceId(device);
        return connections.admitted(live, requested).map(topics -> serve(topics, qos, after, caller, live));
    }

    private Stream serve(
            List<Topic> topics, Qos qos, String after, PushStreams.Caller caller, PushConnections.Live live) {
        live.topics().addAll(topics);
        live.origin(caller.origin());
        Flux<PushEvent> events = streams.merged(topics, qos, after, metrics, live, caller)
                .events()
                .mergeWith(live.direct());
        AtomicReference<String> reason = new AtomicReference<>("shutdown");
        Mono<Void> end = Mono.firstWithSignal(
                streams.end(reason), live.closed().doOnSuccess(v -> reason.set(live.closeReason())));
        int count = topics.size();
        Flux<byte[]> flux = events.map(e -> {
                    if (e.signal()) return PushEventProtos.toProto(e).toByteArray();
                    metrics.delivered();
                    live.delivered(e);
                    return frames.frame(e);
                })
                .takeUntilOther(end)
                .onErrorMap(Exceptions::isOverflow, e -> PushEventProtos.overflow())
                .doOnSubscribe(s -> metrics.connectionOpened("grpc", count))
                .doFinally(s -> {
                    metrics.connectionClosed("grpc", count);
                    connections.close(live);
                });
        return new Stream(flux, () -> PushEventProtos.closed(reason.get()));
    }

    /**
     * Writes events as the call is ready for them: one request outstanding at a time, renewed from
     * {@code onNext} while the transport accepts writes and from the ready handler once it does
     * again, so a slow reader's events queue upstream (and overflow there) instead of in the
     * transport.
     */
    static final class Writer<T> extends BaseSubscriber<T> {

        private final ServerCallStreamObserver<T> call;
        private volatile java.util.function.Supplier<StatusRuntimeException> onComplete;
        private final AtomicBoolean outstanding = new AtomicBoolean();
        private volatile boolean subscribed;
        private volatile boolean callCancelled;
        private volatile Disposable setup;

        /**
         * Registers the call's ready and cancel handlers, which gRPC only accepts before the service
         * method returns; nothing is written until the writer is subscribed to the events.
         */
        Writer(ServerCallStreamObserver<T> call) {
            this.call = call;
            call.setOnReadyHandler(this::drain);
            call.setOnCancelHandler(this::callCancelled);
        }

        /** {@code onComplete}: the status a completed stream ends the call with; null completes it cleanly. */
        void onComplete(java.util.function.Supplier<StatusRuntimeException> onComplete) {
            this.onComplete = onComplete;
        }

        /** The work that is still deciding whether the call may stream (the caller, the authorization). */
        void setup(Disposable setup) {
            this.setup = setup;
            if (callCancelled) setup.dispose();
        }

        private void callCancelled() {
            callCancelled = true;
            Disposable pending = setup;
            if (pending != null) pending.dispose();
            cancel();
        }

        @Override
        protected void hookOnSubscribe(org.reactivestreams.Subscription subscription) {
            subscribed = true;
            drain();
        }

        private void drain() {
            if (subscribed && !isDisposed() && call.isReady() && outstanding.compareAndSet(false, true)) request(1);
        }

        @Override
        protected void hookOnNext(T value) {
            call.onNext(value);
            outstanding.set(false);
            drain();
        }

        @Override
        protected void hookOnComplete() {
            if (call.isCancelled()) return;
            var done = onComplete;
            if (done == null) call.onCompleted();
            else call.onError(done.get());
        }

        @Override
        protected void hookOnError(Throwable t) {
            if (call.isCancelled()) return;
            call.onError(
                    t instanceof StatusRuntimeException
                            ? t
                            : Status.INTERNAL
                                    .withDescription(t.getMessage())
                                    .withCause(t)
                                    .asRuntimeException());
        }
    }
}
