package dev.appiaworks.shiguchi.cursus;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * What a transport needs to serve a subscription, in one call: the topics checked against the
 * declared namespaces and the caller's rights, the resume id checked, a connection registered for
 * the admin views (and ended by an operator's disconnect), the per-topic streams merged behind the
 * client queue, the metrics, and the end of the stream on the instance's shutdown or the
 * connection's lifetime. The GraphQL subscription fields are built on it; the SSE, WebSocket and
 * gRPC transports predate it and keep their own framing.
 *
 * <p>The returned stream completes when the connection must end (shutdown, lifetime, admin
 * disconnect) and errors with a {@link PushException} when the caller may not subscribe or an
 * {@code AT_LEAST_ONCE} subscriber stopped reading (status 429, code {@code overflow}).
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.12.0")
public final class PushSubscriptions {

    private final PushProperties properties;
    private final PushStore store;
    private final PushStreams streams;
    private final TopicAuthorizer authorizer;
    private final PushConnections connections;
    private final PushMetrics metrics;

    public PushSubscriptions(
            PushProperties properties,
            PushBroker broker,
            PushStore store,
            PushShutdown shutdown,
            TopicAuthorizer authorizer,
            PushConnections connections,
            PushMetrics metrics) {
        this(properties, broker, store, shutdown, authorizer, connections, metrics, null);
    }

    /** With the upstream-backed topics attached: a subscription to one opens its upstream stream. */
    public PushSubscriptions(
            PushProperties properties,
            PushBroker broker,
            PushStore store,
            PushShutdown shutdown,
            TopicAuthorizer authorizer,
            PushConnections connections,
            PushMetrics metrics,
            PushUpstreams upstreams) {
        this(properties, broker, store, shutdown, authorizer, connections, metrics, upstreams, null);
    }

    /** With the persistent sessions: a principal's session applies on a namespace that keeps them. */
    public PushSubscriptions(
            PushProperties properties,
            PushBroker broker,
            PushStore store,
            PushShutdown shutdown,
            TopicAuthorizer authorizer,
            PushConnections connections,
            PushMetrics metrics,
            PushUpstreams upstreams,
            PushSessions sessions) {
        this.properties = properties;
        this.store = store;
        this.streams = new PushStreams(broker, store, properties, shutdown, upstreams, sessions);
        this.authorizer = authorizer;
        this.connections = connections;
        this.metrics = metrics;
    }

    /**
     * Opens a subscription.
     *
     * @param transport the transport's name in the metrics and the admin views ({@code graphql})
     * @param auth the caller, or null when anonymous
     * @param topics topic names as the client wrote them ({@code orders:1}); each must belong to a
     *     declared namespace
     * @param qos the delivery the client asked for, or null for the namespaces' own
     * @param lastEventId the id to resume after, or null
     */
    public Flux<PushEvent> open(
            String transport, Authentication auth, List<String> topics, Qos qos, String lastEventId) {
        return open(transport, auth, topics, qos, lastEventId, null);
    }

    /**
     * {@link #open(String, Authentication, List, Qos, String)} naming a {@code CLIENT} session
     * ({@code session}, may be null). Gateway signals (an {@code overflow} on resume) are not in
     * the flux; the dropped count is in the metrics.
     */
    public Flux<PushEvent> open(
            String transport, Authentication auth, List<String> topics, Qos qos, String lastEventId, String session) {
        return open(transport, auth, topics, qos, lastEventId, session, null);
    }

    /**
     * {@link #open(String, Authentication, List, Qos, String, String)} declaring the subscriber's own
     * {@code origin} (may be null): events published with the same origin are not delivered to it.
     */
    @ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.13.0")
    public Flux<PushEvent> open(
            String transport,
            Authentication auth,
            List<String> topics,
            Qos qos,
            String lastEventId,
            String session,
            String origin) {
        return Flux.defer(() -> {
            List<Topic> parsed = PushStreams.topics(topics, properties);
            String after = PushStreams.after(lastEventId, store);
            String declared = PushOrigin.parse(origin);
            return Flux.fromIterable(parsed)
                    .concatMap(t -> authorizer.authorizeSubscribe(auth, t, properties.namespaceOf(t)))
                    .thenMany(Flux.defer(() -> stream(transport, auth, parsed, qos, after, session, declared)));
        });
    }

    private Flux<PushEvent> stream(
            String transport,
            Authentication auth,
            List<Topic> requested,
            Qos qos,
            String after,
            String session,
            String origin) {
        PushConnections.Live live = connections.open(transport, auth);
        live.origin(origin);
        return connections
                .admitted(live, requested)
                .flatMapMany(topics -> serve(transport, auth, topics, qos, after, session, origin, live));
    }

    private Flux<PushEvent> serve(
            String transport,
            Authentication auth,
            List<Topic> topics,
            Qos qos,
            String after,
            String session,
            String origin,
            PushConnections.Live live) {
        live.topics().addAll(topics);
        Flux<PushEvent> events = streams.merged(
                        topics, qos, after, metrics, live, new PushStreams.Caller(auth, session, null, null, origin))
                .events()
                .filter(e -> !e.signal());
        AtomicReference<String> reason = new AtomicReference<>("shutdown");
        Mono<Void> end = Mono.firstWithSignal(
                streams.end(reason), live.closed().doOnSuccess(v -> reason.set(live.closeReason())));
        int count = topics.size();
        return events.doOnNext(e -> {
                    metrics.delivered();
                    live.delivered(e);
                })
                .takeUntilOther(end)
                .onErrorMap(Exceptions::isOverflow, e -> overflow())
                .doOnSubscribe(s -> metrics.connectionOpened(transport, count))
                .doFinally(s -> {
                    metrics.connectionClosed(transport, count);
                    connections.close(live);
                });
    }

    static PushException overflow() {
        return new PushException(
                HttpStatus.TOO_MANY_REQUESTS,
                "overflow",
                "the client queue filled: the subscriber stopped reading; reconnect and resume from the last id");
    }
}
