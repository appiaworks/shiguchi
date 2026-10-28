package dev.appiaworks.shiguchi.cursus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.appiaworks.shiguchi.gateway.SseKeepAlive;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.Exceptions;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * {@code GET <path>/ws}, subprotocol {@code push.v1.json}: one WebSocket carrying any number of
 * topic subscriptions, with JSON text frames both ways.
 *
 * <p>Client to server: {@code {"type":"connect","token":"…"}} (a JWT, for browsers, which cannot
 * set the handshake's {@code Authorization} header), {@code sub} ({@code topics}, optional
 * {@code after} as one id or {@code {topic: id}}, optional {@code qos}), {@code unsub}
 * ({@code topics}), {@code pub} ({@code topic}, {@code data}, {@code attributes}, {@code key}),
 * {@code ack} ({@code topic}, {@code id}: moves a persistent session's cursor or releases a
 * consumer-group claim, answered by {@code acked}, an error where sessions are not configured),
 * {@code refresh} ({@code token}), {@code ping}. Server to client: {@code connected},
 * {@code subscribed}, {@code unsubscribed}, {@code event}, {@code published}, {@code acked},
 * {@code error}, {@code overflow}, {@code reset} (the resume point is older than the retained
 * history), {@code pong}, {@code close}.
 *
 * <p>Each topic has a queue of its own ({@code client-queue-size}): an {@code AT_MOST_ONCE} topic
 * drops its oldest queued event for a client that is not reading, an {@code AT_LEAST_ONCE} topic
 * sends {@code overflow} and is unsubscribed, to be resubscribed with {@code after}. The connection
 * ends with a {@code close} frame and WebSocket status 1012 on the instance's shutdown or the
 * connection's lifetime, and 4401 when the token expires without a {@code refresh}.
 */
public final class PushWebSocketHandler implements WebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(PushWebSocketHandler.class);
    static final String SUBPROTOCOL = "push.v1.json";
    static final int REBALANCE = 1012;
    static final int POLICY = 1008;
    static final int TOKEN_EXPIRED = 4401;
    /** The BFF session behind a cookie connection ended: sign in again, do not refresh. */
    static final int SESSION_ENDED = 4440;

    private final PushProperties properties;
    private final PushStreams streams;
    private final PushPublisher publisher;
    private final TopicAuthorizer authorizer;
    private final PushMetrics metrics;
    private final PushTokenAuthenticator tokens;
    private final PushConnections connections;
    private final PushSessions sessions;
    private volatile PushCookieSessions cookies;

    public PushWebSocketHandler(
            PushProperties properties,
            PushBroker broker,
            PushStore store,
            PushPublisher publisher,
            TopicAuthorizer authorizer,
            PushMetrics metrics,
            PushShutdown shutdown,
            PushTokenAuthenticator tokens,
            PushConnections connections) {
        this(properties, broker, store, publisher, authorizer, metrics, shutdown, tokens, connections, null);
    }

    /** With the upstream-backed topics attached: a subscription to one opens its upstream stream. */
    public PushWebSocketHandler(
            PushProperties properties,
            PushBroker broker,
            PushStore store,
            PushPublisher publisher,
            TopicAuthorizer authorizer,
            PushMetrics metrics,
            PushShutdown shutdown,
            PushTokenAuthenticator tokens,
            PushConnections connections,
            PushUpstreams upstreams) {
        this(properties, broker, store, publisher, authorizer, metrics, shutdown, tokens, connections, upstreams, null);
    }

    /** With the persistent sessions: {@code connect{session}} names a {@code CLIENT} session, {@code ack} moves the cursor. */
    public PushWebSocketHandler(
            PushProperties properties,
            PushBroker broker,
            PushStore store,
            PushPublisher publisher,
            TopicAuthorizer authorizer,
            PushMetrics metrics,
            PushShutdown shutdown,
            PushTokenAuthenticator tokens,
            PushConnections connections,
            PushUpstreams upstreams,
            PushSessions sessions) {
        this.sessions = sessions;
        this.properties = properties;
        this.streams = new PushStreams(broker, store, properties, shutdown, upstreams, sessions);
        this.publisher = publisher;
        this.authorizer = authorizer;
        this.metrics = metrics;
        this.tokens = tokens;
        this.connections = connections;
    }

    /** Binds connections opened with the BFF session cookie to their session; optional. */
    public void setCookieSessions(PushCookieSessions cookies) {
        this.cookies = cookies;
    }

    @Override
    public List<String> getSubProtocols() {
        return List.of(SUBPROTOCOL);
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        HandshakeInfo handshake = session.getHandshakeInfo();
        String origin = handshake.getHeaders().getFirst(HttpHeaders.ORIGIN);
        return handshake
                .getPrincipal()
                .map(p -> p instanceof Authentication a && TopicAuthorizer.isAuthenticated(a)
                        ? Optional.of(a)
                        : Optional.<Authentication>empty())
                .defaultIfEmpty(Optional.empty())
                .flatMap(auth -> {
                    // A cookie caller's Origin was matched exactly against its application's origins by
                    // the security chain before the upgrade; the lenient check below is for token callers.
                    PushCookieSessions bound = cookies;
                    boolean cookie = bound != null && bound.bound(auth.orElse(null));
                    if (!cookie && !originAllowed(origin, handshake.getHeaders().getFirst(HttpHeaders.HOST))) {
                        log.warn("Push: WebSocket from origin {} refused", origin);
                        return session.close(new CloseStatus(POLICY, "origin not allowed"));
                    }
                    return new Connection(session, auth.orElse(null)).run();
                });
    }

    /** No {@code Origin} (not a browser): allowed. Otherwise the gateway's own host, or the configured list. */
    boolean originAllowed(String origin, String host) {
        if (origin == null || origin.isBlank()) return true;
        List<String> allowed = properties.getWs().getAllowedOrigins();
        for (String a : allowed) {
            if ("*".equals(a) || a.equalsIgnoreCase(origin)) return true;
        }
        if (host == null) return false;
        try {
            URI o = URI.create(origin.trim());
            String originHost = o.getHost() == null ? "" : o.getHost();
            int originPort = o.getPort() != -1 ? o.getPort() : "https".equalsIgnoreCase(o.getScheme()) ? 443 : 80;
            String h = host.trim();
            int colon = h.lastIndexOf(':');
            String hostName = colon > 0 ? h.substring(0, colon) : h;
            int hostPort = colon > 0 ? Integer.parseInt(h.substring(colon + 1)) : originPort;
            return originHost.equalsIgnoreCase(hostName) && originPort == hostPort;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** One WebSocket: its principal, its topic subscriptions, and the single outbound stream they feed. */
    private final class Connection {
        private final WebSocketSession session;
        private final PushConnections.Live live;
        private final String id;
        private final AtomicReference<Authentication> auth = new AtomicReference<>();
        private final Sinks.Many<Instant> expiries = Sinks.many().unicast().onBackpressureBuffer();
        private final Sinks.Many<Flux<String>> outbound = Sinks.many().unicast().onBackpressureBuffer();
        private final Sinks.One<CloseStatus> closing = Sinks.one();
        private final AtomicReference<CloseStatus> closeStatus = new AtomicReference<>();
        private final ConcurrentHashMap<Topic, Sinks.Empty<Void>> subscriptions = new ConcurrentHashMap<>();
        private volatile String clientSession;
        /** The origin the connection declared in {@code connect}: the default of every {@code sub}, and what a {@code pub} is stamped with. */
        private volatile String origin;
        /** Per topic subscribed as a consumer-group member: {group, member}, for the acks. */
        private final ConcurrentHashMap<Topic, String[]> groupSubscriptions = new ConcurrentHashMap<>();
        /** The hooks' connect decision, taken once (true: admitted); a rejection has sent its error frame and begun the close. */
        private final Mono<Boolean> admission;

        private volatile reactor.core.Disposable sessionWatch;

        Connection(WebSocketSession session, Authentication initial) {
            this.live = connections.open("ws", initial);
            this.live.deviceId(session.getHandshakeInfo().getHeaders().getFirst("X-Push-Device"));
            this.id = live.id();
            this.session = session;
            this.auth.set(initial);
            this.admission = Mono.defer(() -> connections.admit(live))
                    .doOnNext(this::serverSubscribe)
                    .thenReturn(true)
                    .onErrorResume(PushException.class, e -> {
                        send(error(e.code(), e.getMessage(), null));
                        closeWith(new CloseStatus(POLICY, e.getMessage()));
                        return Mono.just(false);
                    })
                    .cache();
        }

        /** The topics a hook subscribes the connection to on the server side; its word is final, so they are not authorized again. */
        private void serverSubscribe(List<Topic> topics) {
            for (Topic topic : topics) {
                PushProperties.Namespace ns = properties.namespaceOf(topic);
                if (ns == null || subscriptions.containsKey(topic)) continue;
                if (PushGroups.grouped(ns)) {
                    log.warn("Push: a hook subscribed a connection to {}, a consumer-group topic; skipped", topic);
                    continue;
                }
                open(topic, ns, ns.getQos(), null, session(ns));
            }
        }

        /** The connect decision, taken at the first frame that needs it (a token may only arrive in {@code connect}). */
        private Mono<Boolean> admitted() {
            return admission;
        }

        Mono<Void> run() {
            PushProperties.Connection conn = properties.getConnection();
            metrics.connectionOpened("ws", 0);

            // The instance stopping or the lifetime elapsing → 1012; a token expiring → 4401.
            AtomicReference<String> reason = new AtomicReference<>("shutdown");
            streams.end(reason).subscribe(v -> {}, e -> {}, () -> closeWith(new CloseStatus(REBALANCE, reason.get())));
            live.closed().subscribe(v -> {}, e -> {}, () -> closeWith(new CloseStatus(REBALANCE, live.closeReason())));
            expiries.asFlux()
                    .switchMap(exp -> Mono.delay(
                            Duration.between(Instant.now(), exp).isNegative()
                                    ? Duration.ZERO
                                    : Duration.between(Instant.now(), exp),
                            Schedulers.parallel()))
                    .subscribe(t -> closeWith(new CloseStatus(TOKEN_EXPIRED, "token expired")));
            armExpiry();
            PushCookieSessions bound = cookies;
            if (bound != null && bound.bound(auth.get())) {
                // Lives as long as the session, not the access token; see armExpiry.
                sessionWatch = bound.ended(auth.get()).subscribe(v -> {}, e -> {}, () -> {
                    metrics.sessionEnded("ws");
                    closeWith(new CloseStatus(SESSION_ENDED, PushCookieSessions.REASON));
                });
            }
            if (TopicAuthorizer.isAuthenticated(auth.get())) admission.subscribe();

            send(connected());
            outbound.tryEmitNext(live.direct().map(e -> {
                metrics.delivered();
                live.delivered(e);
                return eventFrame(e);
            }));

            Flux<String> frames = outbound.asFlux()
                    // Prefetch 1 per topic, so the per-topic queue is the only buffer; a larger
                    // prefetch measured no faster at a thousand sockets.
                    .flatMap(Function.identity(), Integer.MAX_VALUE, 1)
                    .takeUntilOther(closing.asMono())
                    .concatWith(Mono.defer(() -> {
                        CloseStatus status = closeStatus.get();
                        return status == null ? Mono.empty() : Mono.just(closeFrame(status));
                    }));
            Flux<WebSocketMessage> messages = SseKeepAlive.withKeepAlive(
                    frames.map(session::textMessage),
                    conn.getKeepalive(),
                    () -> session.pingMessage(f -> f.wrap(new byte[0])),
                    Schedulers.parallel());

            Mono<Void> inbound = session.receive()
                    .filter(m -> m.getType() == WebSocketMessage.Type.TEXT)
                    .map(WebSocketMessage::getPayloadAsText)
                    .concatMap(this::handle)
                    .then();

            Mono<Void> outboundDone = session.send(messages).then(Mono.defer(() -> {
                CloseStatus status = closeStatus.get();
                return status == null ? session.close() : session.close(status);
            }));
            return Mono.firstWithSignal(outboundDone, inbound).doFinally(s -> {
                reactor.core.Disposable watch = sessionWatch;
                if (watch != null) watch.dispose();
                subscriptions.values().forEach(Sinks.Empty::tryEmitEmpty);
                metrics.connectionClosed("ws", subscriptions.size());
                connections.close(live);
                subscriptions.clear();
            });
        }

        private void closeWith(CloseStatus status) {
            if (closeStatus.compareAndSet(null, status)) closing.tryEmitValue(status);
        }

        private void armExpiry() {
            PushCookieSessions bound = cookies;
            if (bound != null && bound.bound(auth.get())) return;
            Instant exp = PushTokenAuthenticator.expiry(auth.get());
            if (exp != null) expiries.tryEmitNext(exp);
        }

        private void send(String frame) {
            outbound.tryEmitNext(Flux.just(frame));
        }

        // ---- inbound frames ----

        private Mono<Void> handle(String text) {
            JsonNode frame;
            try {
                frame = PushResponses.JSON.readTree(text);
            } catch (java.io.IOException e) {
                send(error("bad_request", "frame is not JSON: " + e.getMessage(), null));
                return Mono.empty();
            }
            if (!(frame instanceof ObjectNode obj) || !obj.hasNonNull("type")) {
                send(error("bad_request", "frame needs a type", null));
                return Mono.empty();
            }
            String type = obj.get("type").asText();
            return switch (type) {
                case "connect", "refresh" -> authenticate(obj);
                case "sub" -> subscribe(obj);
                case "unsub" -> unsubscribe(obj);
                case "pub" -> publish(obj);
                case "ack" -> ack(obj);
                case "ping" -> {
                    send("{\"type\":\"pong\"}");
                    yield Mono.empty();
                }
                default -> {
                    send(error("bad_request", "unknown frame type '" + type + "'", null));
                    yield Mono.empty();
                }
            };
        }

        private Mono<Void> ack(ObjectNode obj) {
            if (sessions == null) {
                send(error("bad_request", "sessions are not configured", null));
                return Mono.empty();
            }
            if (!obj.hasNonNull("topic") || !obj.hasNonNull("id")) {
                send(error("bad_request", "ack needs topic and id", null));
                return Mono.empty();
            }
            String topic = obj.get("topic").asText();
            String id = obj.get("id").asText();
            String[] grouped = null;
            try {
                grouped = groupSubscriptions.get(Topic.parse(topic));
            } catch (IllegalArgumentException ignored) {
                // reported by the session ack below
            }
            Mono<Void> acked;
            if (grouped != null || obj.hasNonNull("group")) {
                String group = obj.hasNonNull("group") ? obj.get("group").asText() : grouped[0];
                String member =
                        obj.hasNonNull("member") ? obj.get("member").asText() : grouped != null ? grouped[1] : id;
                acked = sessions.groups() == null
                        ? Mono.error(PushException.badRequest("consumer groups are not configured"))
                        : sessions.groups().ack(auth.get(), group, member, topic, id, authorizer);
            } else {
                acked = sessions.ack(auth.get(), clientSession, topic, id, authorizer);
            }
            return acked.doOnSuccess(v -> {
                        ObjectNode n = PushResponses.JSON.createObjectNode();
                        n.put("type", "acked");
                        n.put("topic", topic);
                        n.put("cursor", id);
                        send(n.toString());
                    })
                    .onErrorResume(PushException.class, e -> {
                        send(error(e.code(), e.getMessage(), null));
                        return Mono.empty();
                    });
        }

        private Mono<Void> authenticate(ObjectNode obj) {
            PushCookieSessions bound = cookies;
            if (obj.hasNonNull("token") && bound != null && bound.bound(auth.get())) {
                // One principal per connection: a page must not swap identities on a socket bound to a session.
                closeWith(new CloseStatus(POLICY, "token not accepted on a cookie connection"));
                return Mono.empty();
            }
            if (obj.hasNonNull("session")) clientSession = obj.get("session").asText();
            if (obj.hasNonNull("device")) live.deviceId(obj.get("device").asText());
            if (obj.hasNonNull("origin")) {
                try {
                    origin = PushOrigin.parse(obj.get("origin").asText());
                    live.origin(origin);
                } catch (PushException e) {
                    send(error(e.code(), e.getMessage(), null));
                    return Mono.empty();
                }
            }
            String token = obj.hasNonNull("token") ? obj.get("token").asText() : null;
            if (token == null) {
                // A bare connect: nothing to authenticate, answer what the handshake gave.
                send(connected());
                return admitted().then();
            }
            return tokens.authenticate(token)
                    .doOnNext(a -> {
                        auth.set(a);
                        live.authenticated(a);
                        armExpiry();
                        send(connected());
                    })
                    .onErrorResume(PushException.class, e -> {
                        send(error(e.code(), e.getMessage(), null));
                        return Mono.empty();
                    })
                    .then(Mono.defer(() -> admitted().then()));
        }

        private Mono<Void> subscribe(ObjectNode obj) {
            List<Topic> asked = topics(obj);
            if (asked == null) return Mono.empty();
            return admitted()
                    .filter(ok -> ok)
                    .flatMap(ok -> connections.subscribing(live, asked).onErrorResume(PushException.class, e -> {
                        send(error(e.code(), e.getMessage(), null));
                        return Mono.empty();
                    }))
                    .flatMap(topics -> subscribeTo(obj, topics));
        }

        private Mono<Void> subscribeTo(ObjectNode obj, List<Topic> topics) {
            Qos requested = null;
            if (obj.hasNonNull("qos")) {
                try {
                    requested = Qos.valueOf(obj.get("qos").asText().trim().toUpperCase());
                } catch (IllegalArgumentException e) {
                    send(error("bad_request", "unknown qos '" + obj.get("qos").asText() + "'", null));
                    return Mono.empty();
                }
            }
            Map<Topic, String> after = after(obj, topics);
            int max = properties.getConnection().getMaxSubscriptions();
            Qos qos = requested;
            String group = obj.hasNonNull("group") ? obj.get("group").asText() : null;
            String member = obj.hasNonNull("member") ? obj.get("member").asText() : id;
            String subOrigin;
            try {
                subOrigin = obj.hasNonNull("origin")
                        ? PushOrigin.parse(obj.get("origin").asText())
                        : origin;
            } catch (PushException e) {
                send(error(e.code(), e.getMessage(), null));
                return Mono.empty();
            }
            return Flux.fromIterable(topics)
                    .concatMap(topic -> {
                        PushProperties.Namespace ns = properties.namespaceOf(topic);
                        if (ns == null) {
                            send(error(
                                    "unknown_namespace",
                                    PushException.unknownNamespace(topic).getMessage(),
                                    topic));
                            return Mono.empty();
                        }
                        if (subscriptions.containsKey(topic)) {
                            send(subscribed(topic, after.get(topic), effective(ns, qos)));
                            return Mono.empty();
                        }
                        if (subscriptions.size() >= max) {
                            send(error("too_many_subscriptions", "at most " + max + " topics per connection", topic));
                            return Mono.empty();
                        }
                        return authorizer
                                .authorizeSubscribe(auth.get(), topic, ns)
                                .doOnSuccess(v -> {
                                    if (PushGroups.grouped(ns)) openGroup(topic, ns, group, member, qos);
                                    else if (group != null && !group.isBlank()) {
                                        send(error(
                                                "bad_request",
                                                topic.namespace()
                                                        + " is not a consumer-group namespace; drop the group",
                                                topic));
                                    } else
                                        open(topic, ns, effective(ns, qos), after.get(topic), session(ns), subOrigin);
                                })
                                .onErrorResume(PushException.class, e -> {
                                    send(error(e.code(), e.getMessage(), topic));
                                    return Mono.empty();
                                });
                    })
                    .then();
        }

        private static Qos effective(PushProperties.Namespace ns, Qos requested) {
            return requested == Qos.AT_MOST_ONCE ? Qos.AT_MOST_ONCE : ns.getQos();
        }

        /** The caller's session on {@code ns}, or null; a bad client session id is reported and treated as none. */
        private String session(PushProperties.Namespace ns) {
            if (sessions == null) return null;
            try {
                return sessions.key(ns, auth.get(), clientSession);
            } catch (PushException e) {
                send(error(e.code(), e.getMessage(), null));
                return null;
            }
        }

        /** A consumer-group subscription: the member's share of the topic, acknowledged with {@code ack} frames. */
        private void openGroup(Topic topic, PushProperties.Namespace ns, String group, String member, Qos requested) {
            if (requested == Qos.AT_MOST_ONCE) {
                send(error("bad_request", topic + " is a consumer-group topic: qos cannot be lowered", topic));
                return;
            }
            if (group == null || group.isBlank()) {
                send(error(
                        "bad_request",
                        topic.namespace() + " is a consumer-group namespace: name the group when subscribing",
                        topic));
                return;
            }
            Flux<PushEvent> events;
            try {
                events = streams.groupStream(topic, ns, group, member);
            } catch (PushException e) {
                send(error(e.code(), e.getMessage(), topic));
                return;
            }
            groupSubscriptions.put(topic, new String[] {group.trim(), member.trim()});
            open(topic, ns, Qos.AT_LEAST_ONCE, null, events);
        }

        private void open(Topic topic, PushProperties.Namespace ns, Qos qos, String afterId, String session) {
            open(topic, ns, qos, afterId, session, origin);
        }

        private void open(
                Topic topic, PushProperties.Namespace ns, Qos qos, String afterId, String session, String subOrigin) {
            open(topic, ns, qos, afterId, streams.topicStream(topic, ns, qos, afterId, session, subOrigin, metrics));
        }

        private void open(Topic topic, PushProperties.Namespace ns, Qos qos, String afterId, Flux<PushEvent> source) {
            Sinks.Empty<Void> stop = Sinks.empty();
            if (subscriptions.putIfAbsent(topic, stop) != null) return;
            live.topics().add(topic);
            metrics.subscribed();
            Flux<PushEvent> events = source;
            events = qos == Qos.AT_LEAST_ONCE
                    ? events.onBackpressureBuffer(ns.getClientQueueSize(), e -> {}, BufferOverflowStrategy.ERROR)
                    : events.onBackpressureBuffer(
                            ns.getClientQueueSize(),
                            e -> {
                                metrics.droppedQueueFull();
                                live.dropped(e, "queue_full");
                            },
                            BufferOverflowStrategy.DROP_OLDEST);
            Flux<String> frames = events.map(e -> {
                        if (e.signal()) return signalFrame(e);
                        metrics.delivered();
                        live.delivered(e);
                        return eventFrame(e);
                    })
                    .onErrorResume(Exceptions::isOverflow, e -> {
                        close(topic);
                        return Mono.just(overflow(topic));
                    })
                    .onErrorResume(e -> {
                        log.warn("Push: WebSocket subscription to {} failed: {}", topic, e.toString());
                        close(topic);
                        return Mono.just(error("stream_failed", e.getMessage(), topic));
                    })
                    .takeUntilOther(stop.asMono());
            send(subscribed(topic, afterId, qos));
            outbound.tryEmitNext(frames);
        }

        private boolean close(Topic topic) {
            Sinks.Empty<Void> stop = subscriptions.remove(topic);
            groupSubscriptions.remove(topic);
            live.topics().remove(topic);
            if (stop == null) return false;
            stop.tryEmitEmpty();
            metrics.unsubscribed();
            return true;
        }

        private Mono<Void> unsubscribe(ObjectNode obj) {
            List<Topic> topics = topics(obj);
            if (topics == null) return Mono.empty();
            for (Topic topic : topics) {
                if (close(topic)) send(control("unsubscribed", topic));
            }
            return Mono.empty();
        }

        private Mono<Void> publish(ObjectNode obj) {
            if (!obj.hasNonNull("topic")) {
                send(error("bad_request", "topic is required", null));
                return Mono.empty();
            }
            Topic topic;
            try {
                topic = Topic.parse(obj.get("topic").asText());
            } catch (IllegalArgumentException e) {
                send(error("bad_request", e.getMessage(), null));
                return Mono.empty();
            }
            PushProperties.Namespace ns = properties.namespaceOf(topic);
            if (ns == null) {
                send(error(
                        "unknown_namespace",
                        PushException.unknownNamespace(topic).getMessage(),
                        topic));
                return Mono.empty();
            }
            if (!ns.isClientPublish()) {
                send(error("forbidden", "client publishing is off for namespace '" + topic.namespace() + "'", topic));
                return Mono.empty();
            }
            JsonNode data = obj.get("data");
            if (data == null || data.isMissingNode()) {
                send(error("bad_request", "data is required", topic));
                return Mono.empty();
            }
            byte[] bytes = data.toString().getBytes(StandardCharsets.UTF_8);
            Map<String, String> attributes = new LinkedHashMap<>();
            JsonNode attrs = obj.get("attributes");
            if (attrs != null && attrs.isObject()) {
                for (Map.Entry<String, JsonNode> f : attrs.properties())
                    attributes.put(f.getKey(), f.getValue().asText());
            }
            Authentication a = auth.get();
            // The reserved push.* attributes are dropped and from / push.origin stamped by the publisher call.
            String from = TopicAuthorizer.isAuthenticated(a) ? a.getName() : null;
            String key = obj.hasNonNull("key") ? obj.get("key").asText() : null;
            return admitted()
                    .filter(ok -> ok)
                    .flatMap(ok -> authorizer
                            .authorizeSubscribe(a, topic, ns)
                            .then(Mono.defer(() -> connections.clientMessage(live, topic, bytes)))
                            .flatMap(decision -> switch (decision.getAction()) {
                                case DROP -> {
                                    ObjectNode n = PushResponses.JSON.createObjectNode();
                                    n.put("type", "published");
                                    n.put("topic", topic.toString());
                                    n.put("id", java.util.UUID.randomUUID().toString());
                                    send(n.toString());
                                    yield Mono.empty();
                                }
                                case REJECT -> {
                                    send(error(
                                            "forbidden",
                                            decision.getReason().isBlank()
                                                    ? "rejected by a hook"
                                                    : decision.getReason(),
                                            topic));
                                    yield Mono.empty();
                                }
                                default ->
                                    publisher
                                            .publishFromClient(
                                                    topic,
                                                    decision.getPayload().toByteArray(),
                                                    attributes,
                                                    key,
                                                    origin,
                                                    from)
                                            .doOnNext(e -> send(published(e)));
                            }))
                    .onErrorResume(PushException.class, e -> {
                        send(error(e.code(), e.getMessage(), topic));
                        return Mono.empty();
                    })
                    .then();
        }

        /** The frame's {@code topics} (or one {@code topic}), parsed; null after an error frame. */
        private List<Topic> topics(ObjectNode obj) {
            List<String> raw = new ArrayList<>();
            if (obj.get("topics") instanceof ArrayNode arr) {
                for (JsonNode n : arr) raw.add(n.asText());
            } else if (obj.hasNonNull("topic")) {
                raw.add(obj.get("topic").asText());
            }
            if (raw.isEmpty()) {
                send(error("bad_request", "topics is required", null));
                return null;
            }
            List<Topic> topics = new ArrayList<>(raw.size());
            for (String r : raw) {
                try {
                    Topic t = Topic.parse(r);
                    if (!topics.contains(t)) topics.add(t);
                } catch (IllegalArgumentException e) {
                    send(error("bad_request", e.getMessage(), null));
                    return null;
                }
            }
            return topics;
        }

        /** {@code after} as one id for every topic, or an object of topic to id. */
        private static Map<Topic, String> after(ObjectNode obj, List<Topic> topics) {
            Map<Topic, String> out = new LinkedHashMap<>();
            JsonNode after = obj.get("after");
            if (after == null || after.isNull()) return out;
            if (after.isObject()) {
                for (Map.Entry<String, JsonNode> f : after.properties()) {
                    try {
                        out.put(Topic.parse(f.getKey()), f.getValue().asText());
                    } catch (IllegalArgumentException ignored) {
                        // An id for a topic not in this frame is nothing to resume.
                    }
                }
            } else {
                for (Topic t : topics) out.put(t, after.asText());
            }
            return out;
        }

        // ---- outbound frames ----

        private String connected() {
            ObjectNode n = PushResponses.JSON.createObjectNode();
            n.put("type", "connected");
            n.put("connectionId", id);
            n.put("authenticated", TopicAuthorizer.isAuthenticated(auth.get()));
            ObjectNode limits = n.putObject("limits");
            limits.put("maxSubscriptions", properties.getConnection().getMaxSubscriptions());
            limits.put("maxMessageBytes", properties.getConnection().getMaxMessageBytes());
            Duration keepalive = properties.getConnection().getKeepalive();
            n.put("keepaliveMs", keepalive == null ? 0 : keepalive.toMillis());
            return n.toString();
        }
    }

    /**
     * Built by hand rather than through a Jackson tree: this runs once per subscriber per event,
     * and the topic and id need no escaping (their alphabets exclude quotes and backslashes).
     */
    static String eventFrame(PushEvent e) {
        String data = new String(e.data(), StandardCharsets.UTF_8);
        StringBuilder b = new StringBuilder(data.length() + 96);
        b.append("{\"type\":\"event\",\"topic\":\"")
                .append(e.topic())
                .append("\",\"id\":\"")
                .append(e.id())
                .append("\",\"data\":")
                .append(data);
        if (!e.attributes().isEmpty()) {
            b.append(",\"attributes\":{");
            boolean first = true;
            for (Map.Entry<String, String> a : e.attributes().entrySet()) {
                if (!first) b.append(',');
                first = false;
                quote(b, a.getKey()).append(':');
                quote(b, a.getValue());
            }
            b.append('}');
        }
        if (e.key() != null) quote(b.append(",\"key\":"), e.key());
        b.append(",\"time\":").append(e.time().toEpochMilli()).append('}');
        return b.toString();
    }

    private static StringBuilder quote(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"');
    }

    static String subscribed(Topic topic, String position, Qos qos) {
        ObjectNode n = PushResponses.JSON.createObjectNode();
        n.put("type", "subscribed");
        n.put("topic", topic.toString());
        if (position != null) n.put("position", position);
        n.put("qos", qos.name());
        return n.toString();
    }

    static String published(PushEvent e) {
        ObjectNode n = PushResponses.JSON.createObjectNode();
        n.put("type", "published");
        n.put("topic", e.topic().toString());
        n.put("id", e.id());
        return n.toString();
    }

    static String control(String type, Topic topic) {
        ObjectNode n = PushResponses.JSON.createObjectNode();
        n.put("type", type);
        n.put("topic", topic.toString());
        return n.toString();
    }

    /** The frame of a gateway signal: {@code reset} (the resume point is older than the history) or {@code overflow}. */
    static String signalFrame(PushEvent signal) {
        return "reset".equals(signal.attributes().get(PushEvent.SIGNAL)) ? resetSignal(signal) : overflowSignal(signal);
    }

    /** The {@code reset} frame: events between the resume point and the oldest kept one are gone. */
    static String resetSignal(PushEvent signal) {
        ObjectNode n = PushResponses.JSON.createObjectNode();
        n.put("type", "reset");
        n.put("topic", signal.topic().toString());
        n.put("reason", signal.attributes().getOrDefault("reason", "history_gap"));
        return n.toString();
    }

    /** The {@code overflow} frame of a persistent session resume: {@code dropped} events were lost. */
    static String overflowSignal(PushEvent signal) {
        ObjectNode n = PushResponses.JSON.createObjectNode();
        n.put("type", "overflow");
        n.put("topic", signal.topic().toString());
        n.put("reason", "session_overflow");
        n.put("dropped", Long.parseLong(signal.attributes().getOrDefault("dropped", "0")));
        return n.toString();
    }

    static String overflow(Topic topic) {
        ObjectNode n = PushResponses.JSON.createObjectNode();
        n.put("type", "overflow");
        n.put("topic", topic.toString());
        n.put("reason", "queue_full");
        return n.toString();
    }

    static String error(String code, String message, Topic topic) {
        ObjectNode n = PushResponses.JSON.createObjectNode();
        n.put("type", "error");
        n.put("code", code);
        n.put("message", message);
        if (topic != null) n.put("topic", topic.toString());
        return n.toString();
    }

    static String closeFrame(CloseStatus status) {
        ObjectNode n = PushResponses.JSON.createObjectNode();
        n.put("type", "close");
        n.put("code", status.getCode());
        n.put("reason", status.getReason());
        return n.toString();
    }
}
