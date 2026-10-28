package dev.appiaworks.shiguchi.cursus;

import dev.appiaworks.shiguchi.gateway.SseKeepAlive;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * {@code GET <path>/subscribe?topic=a&topic=b}: one Server-Sent Events stream over every topic
 * named. Each event is {@code id:}, {@code event: <topic>}, {@code data: <json>}. A
 * {@code Last-Event-ID} header (or {@code lastEventId} query parameter) resumes an
 * {@code AT_LEAST_ONCE} topic from the history; {@code qos=AT_MOST_ONCE} asks for less than the
 * namespace offers. The stream ends with {@code event: close} when the connection reaches its
 * lifetime, or {@code event: overflow} when an {@code AT_LEAST_ONCE} subscriber stops reading and
 * its queue fills, so the client reconnects and resumes.
 */
public final class PushSubscribeHandler {

    private final PushProperties properties;
    private final PushBroker broker;
    private final PushStore store;
    private final TopicAuthorizer authorizer;
    private final PushMetrics metrics;
    private final PushStreams streams;
    private final PushConnections connections;
    private volatile PushCookieSessions cookies;

    public PushSubscribeHandler(
            PushProperties properties,
            PushBroker broker,
            PushStore store,
            TopicAuthorizer authorizer,
            PushMetrics metrics,
            PushShutdown shutdown,
            PushConnections connections) {
        this(properties, broker, store, authorizer, metrics, shutdown, connections, null);
    }

    /** With the upstream-backed topics attached: a subscription to one opens its upstream stream. */
    public PushSubscribeHandler(
            PushProperties properties,
            PushBroker broker,
            PushStore store,
            TopicAuthorizer authorizer,
            PushMetrics metrics,
            PushShutdown shutdown,
            PushConnections connections,
            PushUpstreams upstreams) {
        this(properties, broker, store, authorizer, metrics, shutdown, connections, upstreams, null);
    }

    /** With the persistent sessions ({@code ?session=} names a {@code CLIENT} session; a principal's is implicit). */
    public PushSubscribeHandler(
            PushProperties properties,
            PushBroker broker,
            PushStore store,
            TopicAuthorizer authorizer,
            PushMetrics metrics,
            PushShutdown shutdown,
            PushConnections connections,
            PushUpstreams upstreams,
            PushSessions sessions) {
        this.properties = properties;
        this.broker = broker;
        this.store = store;
        this.authorizer = authorizer;
        this.metrics = metrics;
        this.streams = new PushStreams(broker, store, properties, shutdown, upstreams, sessions);
        this.connections = connections;
    }

    /** Binds streams opened with the BFF session cookie to their session; optional. */
    public void setCookieSessions(PushCookieSessions cookies) {
        this.cookies = cookies;
    }

    public Mono<ServerResponse> subscribe(ServerRequest request) {
        return Mono.defer(() -> {
                    Subscription sub = parse(request);
                    return principal(request)
                            .flatMap(auth -> Flux.fromIterable(sub.topics)
                                    .concatMap(t -> authorizer.authorizeSubscribe(
                                            auth.orElse(null), t, properties.namespaceOf(t)))
                                    .then(Mono.defer(() -> respond(sub, auth.orElse(null), device(request)))));
                })
                .onErrorResume(PushException.class, PushResponses::error);
    }

    static Mono<Optional<Authentication>> principal(ServerRequest request) {
        return request.principal()
                .map(p -> p instanceof Authentication a ? Optional.of(a) : Optional.<Authentication>empty())
                .defaultIfEmpty(Optional.empty());
    }

    private record Subscription(
            List<Topic> topics,
            Qos qos,
            String lastEventId,
            String session,
            String group,
            String member,
            String origin) {}

    private Subscription parse(ServerRequest request) {
        List<String> raw = request.queryParams().getOrDefault("topic", List.of());
        if (raw.isEmpty()) throw PushException.badRequest("at least one topic query parameter is required");
        List<Topic> topics = PushStreams.topics(raw, properties);
        Qos qos = null;
        String qosParam = request.queryParam("qos").orElse(null);
        if (qosParam != null) {
            try {
                qos = Qos.valueOf(qosParam.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw PushException.badRequest("unknown qos '" + qosParam + "'");
            }
        }
        String last = request.headers().firstHeader("Last-Event-ID");
        if (last == null) last = request.queryParam("lastEventId").orElse(null);
        return new Subscription(
                topics,
                qos,
                PushStreams.after(last, store),
                request.queryParam("session").orElse(null),
                request.queryParam("group").orElse(null),
                request.queryParam("member").orElse(null),
                PushOrigin.parse(request.queryParam("origin").orElse(null)));
    }

    private static String device(ServerRequest request) {
        return request.headers().firstHeader("X-Push-Device");
    }

    private Mono<ServerResponse> respond(Subscription sub, Authentication auth, String device) {
        PushConnections.Live live = connections.open("sse", auth);
        live.deviceId(device);
        return connections.admitted(live, sub.topics).flatMap(topics -> serve(sub, topics, live, auth));
    }

    private Mono<ServerResponse> serve(
            Subscription sub, List<Topic> served, PushConnections.Live live, Authentication auth) {
        live.topics().addAll(served);
        live.origin(sub.origin);
        PushProperties.Connection conn = properties.getConnection();
        Flux<PushEvent> events = streams.merged(
                        served,
                        sub.qos,
                        sub.lastEventId,
                        metrics,
                        live,
                        new PushStreams.Caller(auth, sub.session, sub.group, sub.member, sub.origin))
                .events()
                .mergeWith(live.direct());

        Flux<DataBuffer> frames = events.map(e -> {
            if (e.signal()) {
                String name = "reset".equals(e.attributes().get(PushEvent.SIGNAL)) ? "reset" : "overflow";
                return controlFrame(name, new String(e.data(), StandardCharsets.UTF_8));
            }
            metrics.delivered();
            live.delivered(e);
            return eventFrame(e);
        });
        // Ends with a close frame on the instance's shutdown, and on the lifetime when there is one;
        // whichever comes first names the reason.
        AtomicReference<String> reason = new AtomicReference<>("shutdown");
        PushCookieSessions bound = cookies;
        // A cookie stream lives as long as the session; it has no token expiry to end it.
        Mono<Void> sessionEnd = bound == null
                ? Mono.never()
                : bound.ended(auth).doOnSuccess(v -> {
                    reason.set(PushCookieSessions.REASON);
                    metrics.sessionEnded("sse");
                });
        Mono<Void> end = Mono.firstWithSignal(
                streams.end(reason), live.closed().doOnSuccess(v -> reason.set(live.closeReason())), sessionEnd);
        frames = frames.takeUntilOther(end)
                .concatWith(Mono.fromSupplier(() -> controlFrame("close", "{\"reason\":\"" + reason.get() + "\"}")));
        frames = frames.onErrorResume(
                Exceptions::isOverflow,
                e -> Mono.fromSupplier(() -> controlFrame("overflow", "{\"reason\":\"queue_full\"}")));
        frames = SseKeepAlive.withKeepAlive(frames, conn.getKeepalive(), () -> wrap(KEEPALIVE), Schedulers.parallel());
        int topics = served.size();
        frames = frames.doOnSubscribe(s -> metrics.connectionOpened("sse", topics))
                .doFinally(s -> {
                    metrics.connectionClosed("sse", topics);
                    connections.close(live);
                });

        return ServerResponse.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(BodyInserters.fromDataBuffers(frames));
    }

    private static final byte[] KEEPALIVE = ": keepalive\n\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DATA = "data: ".getBytes(StandardCharsets.UTF_8);

    /** {@code id:}, {@code event: <topic>}, then the JSON as {@code data:} lines (one per payload line). */
    static DataBuffer eventFrame(PushEvent e) {
        byte[] payload = e.data();
        var out = new java.io.ByteArrayOutputStream(payload.length + 64);
        // A directly sent event has no place in the resume order: an id line would become the
        // browser's Last-Event-ID and be offered back as a cursor the stores do not know.
        String id = e.attributes().containsKey(PushConnections.DIRECT) ? "" : "id: " + e.id() + "\n";
        out.writeBytes((id + "event: " + e.topic() + "\n").getBytes(StandardCharsets.UTF_8));
        writeData(out, payload);
        return wrap(out.toByteArray());
    }

    static DataBuffer controlFrame(String event, String json) {
        var out = new java.io.ByteArrayOutputStream(json.length() + 32);
        out.writeBytes(("event: " + event + "\n").getBytes(StandardCharsets.UTF_8));
        writeData(out, json.getBytes(StandardCharsets.UTF_8));
        return wrap(out.toByteArray());
    }

    private static void writeData(java.io.ByteArrayOutputStream out, byte[] payload) {
        out.writeBytes(DATA);
        for (byte b : payload) {
            if (b == '\r') continue;
            if (b == '\n') {
                out.write('\n');
                out.writeBytes(DATA);
            } else {
                out.write(b);
            }
        }
        out.write('\n');
        out.write('\n');
    }

    private static DataBuffer wrap(byte[] bytes) {
        return DefaultDataBufferFactory.sharedInstance.wrap(bytes);
    }
}
