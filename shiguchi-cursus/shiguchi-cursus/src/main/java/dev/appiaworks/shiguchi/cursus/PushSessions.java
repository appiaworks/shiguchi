package dev.appiaworks.shiguchi.cursus;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Mono;

/**
 * Persistent sessions: a namespace with {@code session-expiry} above zero remembers, per session
 * and topic, the last event the subscriber acknowledged (its cursor), so a subscriber that
 * reconnects without saying where it was resumes from there and receives what was published
 * while it was away. The session is named by {@code session-key}: the principal, or a client-chosen
 * {@code session} id scoped to the principal. A subscription with an explicit position
 * ({@code Last-Event-ID}, {@code after}) acknowledges it first; a {@code POST <path>/ack}, a
 * WebSocket {@code ack} frame or {@code PushService.Ack} moves the cursor forward. A new session
 * starts at the topic's head: nothing published before it is replayed. More than
 * {@code max-queued} events pending at resume drop the oldest, counted and announced to the
 * subscriber with an {@code overflow} signal. The store sweeps sessions idle past the expiry.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.14.0")
public final class PushSessions {

    private static final Logger log = LoggerFactory.getLogger(PushSessions.class);
    private static final Pattern CLIENT_SESSION = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    /** Where a session's subscription starts, and how many pending events were dropped to get there. */
    public record Start(String afterId, long dropped, boolean gap) {
        public Start(String afterId, long dropped) {
            this(afterId, dropped, false);
        }

        Start withGap(boolean gap) {
            return gap ? new Start(afterId, dropped, true) : this;
        }
    }

    private final PushProperties properties;
    private final PushStore store;
    private final PushMetrics metrics;
    private volatile PushGroups groups;
    private volatile PushConnections connections;
    private volatile String directNamespace;

    /** Where an {@code ack} of a directly sent event ({@code Send}, in {@code directNamespace}) is routed. */
    public void setDirect(PushConnections connections, String directNamespace) {
        this.connections = connections;
        this.directNamespace = directNamespace;
    }

    /** The consumer groups, reached through the sessions by the streams; set by the configuration. */
    public void setGroups(PushGroups groups) {
        this.groups = groups;
    }

    public PushGroups groups() {
        return groups;
    }

    public PushSessions(PushProperties properties, PushStore store, PushMetrics metrics) {
        this.properties = properties;
        this.store = store;
        this.metrics = metrics;
        List<String> persistent = properties.getNamespaces().entrySet().stream()
                .filter(e -> persistent(e.getValue()))
                .map(java.util.Map.Entry::getKey)
                .toList();
        if (!persistent.isEmpty() && !store.persistentSessions()) {
            throw new IllegalStateException("shiguchi.cursus: namespace(s) " + persistent
                    + " set session-expiry above zero but the " + properties.getStore()
                    + " store keeps no sessions");
        }
        if (!persistent.isEmpty() && properties.getStore() == PushProperties.Engine.MEMORY) {
            log.warn(
                    "Push: persistent sessions of {} live in the MEMORY store: this instance only, lost on restart",
                    persistent);
        }
    }

    /** True when {@code ns} keeps sessions. */
    public static boolean persistent(PushProperties.Namespace ns) {
        Duration expiry = ns == null ? null : ns.getSessionExpiry();
        return expiry != null && !expiry.isZero() && !expiry.isNegative() && !PushGroups.grouped(ns);
    }

    /** The namespaces that keep sessions, for the overview. */
    public List<String> namespaces() {
        return properties.getNamespaces().entrySet().stream()
                .filter(e -> persistent(e.getValue()))
                .map(java.util.Map.Entry::getKey)
                .toList();
    }

    /**
     * The session a subscriber of {@code ns} belongs to: the principal's name under
     * {@code PRINCIPAL}, {@code <session>@<principal or ->} under {@code CLIENT}; null when the
     * namespace keeps no sessions or the subscriber named none (a transient subscription).
     */
    public String key(PushProperties.Namespace ns, Authentication auth, String clientSession) {
        if (!persistent(ns)) return null;
        boolean authenticated = TopicAuthorizer.isAuthenticated(auth);
        return switch (ns.getSessionKey()) {
            case GROUP -> null;
            case PRINCIPAL -> authenticated ? auth.getName() : null;
            case CLIENT -> {
                if (clientSession == null || clientSession.isBlank()) yield null;
                String s = clientSession.trim();
                if (!CLIENT_SESSION.matcher(s).matches()) {
                    throw PushException.badRequest(
                            "session must be 1-128 letters, digits, '.', '_' or '-', got '" + clientSession + "'");
                }
                yield s + "@" + (authenticated ? auth.getName() : "-");
            }
        };
    }

    /**
     * Where {@code session}'s subscription to {@code topic} starts: {@code after} is acknowledged
     * first when given; a session without a cursor starts at the topic's head; more than
     * {@code max-queued} pending events drop the oldest.
     */
    public Mono<Start> start(PushProperties.Namespace ns, String session, Topic topic, String after) {
        return start(ns, session, topic, after, null, null);
    }

    /**
     * {@link #start(PushProperties.Namespace, String, Topic, String)} for a subscriber that declared
     * {@code origin} (null: none). Once the cursor is known and before the pending events are
     * counted, the cursor moves over the leading run of events whose {@code push.origin} is
     * {@code origin} and stops at the first event that is not: nothing unacknowledged precedes
     * them, so acknowledging them cannot skip an event the subscriber was owed. An own-origin
     * event behind a foreign one is left alone (the stream filters it, but it still counts toward
     * {@code max-queued}, and the cursor will not pass it until the foreign event is acknowledged).
     */
    public Mono<Start> start(
            PushProperties.Namespace ns,
            String session,
            Topic topic,
            String after,
            String origin,
            PushMetrics suppressedMetrics) {
        Duration expiry = ns.getSessionExpiry();
        Mono<Void> implicit = after == null ? Mono.empty() : store.ack(session, topic, after, expiry);
        // A stored cursor (after the implicit ack) older than what the history dropped has missed
        // events; a fresh session starts at the head and cannot have.
        return implicit.then(store.cursor(session, topic))
                .flatMap(cursor -> gapOf(topic, cursor).map(gap -> new Cursor(cursor, gap)))
                .switchIfEmpty(Mono.defer(() -> store.headId(topic)
                        .defaultIfEmpty(store.firstId())
                        .flatMap(head -> store.ack(session, topic, head, expiry).thenReturn(new Cursor(head, false)))))
                .flatMap(c -> (origin == null
                                ? Mono.just(c.id())
                                : skipOwn(ns, session, topic, c.id(), origin, expiry, suppressedMetrics))
                        .flatMap(cursor -> bound(ns, session, topic, cursor, expiry))
                        .map(start -> start.withGap(c.gap())));
    }

    private record Cursor(String id, boolean gap) {}

    private Mono<Boolean> gapOf(Topic topic, String cursor) {
        return store.trimmedThrough(topic)
                .map(trimmed -> store.compareIds(cursor, trimmed) < 0)
                .defaultIfEmpty(false);
    }

    private Mono<String> skipOwn(
            PushProperties.Namespace ns,
            String session,
            Topic topic,
            String cursor,
            String origin,
            Duration expiry,
            PushMetrics suppressedMetrics) {
        return store.range(topic, cursor, Math.max(1, ns.getHistorySize()))
                .takeWhile(e -> PushOrigin.matches(e, origin))
                .collectList()
                .flatMap(own -> {
                    if (own.isEmpty()) return Mono.just(cursor);
                    String last = own.get(own.size() - 1).id();
                    if (suppressedMetrics != null) suppressedMetrics.suppressed(topic.namespace(), own.size());
                    return store.ack(session, topic, last, expiry).thenReturn(last);
                });
    }

    private Mono<Start> bound(
            PushProperties.Namespace ns, String session, Topic topic, String cursor, Duration expiry) {
        int max = maxQueued(ns);
        return store.range(topic, cursor, max + 1).count().flatMap(pending -> {
            if (pending <= max) return Mono.just(new Start(cursor, 0));
            return store.range(topic, cursor, Math.max(ns.getHistorySize(), max + 1))
                    .collectList()
                    .flatMap(all -> {
                        int drop = all.size() - max;
                        if (drop <= 0) return Mono.just(new Start(cursor, 0));
                        String newCursor = all.get(drop - 1).id();
                        metrics.droppedSessionOverflow(drop);
                        log.info(
                                "Push: session {} on {} had {} events pending, {} oldest dropped (max-queued {})",
                                session,
                                topic,
                                all.size(),
                                drop,
                                max);
                        return store.ack(session, topic, newCursor, expiry).thenReturn(new Start(newCursor, drop));
                    });
        });
    }

    /** {@code max-queued}, bounded by the history. */
    static int maxQueued(PushProperties.Namespace ns) {
        int history = Math.max(1, ns.getHistorySize());
        int configured = ns.getMaxQueued();
        return configured <= 0 ? history : Math.min(configured, history);
    }

    /**
     * Acknowledges {@code id} on {@code topic} for the caller's session: the cursor moves forward
     * to it (never back). Refused when the namespace keeps no sessions, the caller has no session,
     * or may not subscribe to the topic.
     */
    public Mono<Void> ack(
            Authentication auth, String clientSession, String topicName, String id, TopicAuthorizer authorizer) {
        Topic topic;
        try {
            topic = Topic.parse(topicName);
        } catch (IllegalArgumentException e) {
            return Mono.error(PushException.badRequest(e.getMessage()));
        }
        PushConnections direct = connections;
        if (direct != null && topic.namespace().equals(directNamespace)) {
            // _direct:<connection id>: the answer to a Send with require_ack, not a session cursor.
            return direct.acknowledgeDirect(topic.name(), id, auth)
                    ? Mono.empty()
                    : Mono.error(PushException.notFound("no direct delivery " + id + " is waiting on " + topic));
        }
        PushProperties.Namespace ns = properties.namespaceOf(topic);
        if (ns == null) return Mono.error(PushException.unknownNamespace(topic));
        if (!persistent(ns)) {
            return Mono.error(PushException.badRequest(
                    "namespace '" + topic.namespace() + "' keeps no sessions (session-expiry is 0); nothing to ack"));
        }
        String session;
        try {
            session = key(ns, auth, clientSession);
        } catch (PushException e) {
            return Mono.error(e);
        }
        if (session == null) {
            return Mono.error(
                    ns.getSessionKey() == PushProperties.SessionKey.CLIENT
                            ? PushException.badRequest("a session id is required to ack on " + topic.namespace())
                            : PushException.unauthenticated(
                                    "an ack on " + topic.namespace() + " needs an authenticated caller"));
        }
        String checked;
        try {
            checked = PushStreams.after(id, store);
        } catch (PushException e) {
            return Mono.error(e);
        }
        if (checked == null) return Mono.error(PushException.badRequest("id is required"));
        return authorizer
                .authorizeSubscribe(auth, topic, ns)
                .then(store.ack(session, topic, checked, ns.getSessionExpiry()))
                .doOnSuccess(v -> metrics.acked());
    }
}
