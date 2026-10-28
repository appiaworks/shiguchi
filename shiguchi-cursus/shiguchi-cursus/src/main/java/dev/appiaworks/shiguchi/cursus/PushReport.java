package dev.appiaworks.shiguchi.cursus;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import reactor.core.publisher.Mono;

/**
 * What the {@code gateway-push} actuator endpoint shows and does: an overview of this instance
 * (connections, subscriptions, rates), the namespaces with their policy and load, the topics
 * with live subscribers, the open connections, the archived messages with search; and the
 * writes: disconnect a connection, publish a test event, republish an archived event (one or a
 * query's worth, as a job), tombstone one. Per instance, except the archive, which is as shared
 * as the store behind it.
 */
public final class PushReport {

    private static final Duration BLOCK = Duration.ofSeconds(30);

    private final PushProperties properties;
    private final PushBroker broker;
    private final PushStore store;
    private final PushMetrics metrics;
    private final PushConnections connections;
    private final PushPublisher publisher;
    private final PushArchive archive;
    private final PushRepublishJobs jobs;
    private final PushAudit audit;
    private volatile PushUpstreams upstreams;
    private volatile PushDeliveries deliveries;
    private volatile PushHooks hooks;
    private volatile PushSources sources;
    private volatile PushDlq dlq;
    private volatile PushSessions sessions;
    private volatile PushGroups groups;

    public void setGroups(PushGroups groups) {
        this.groups = groups;
    }

    /** The consumer groups whose name starts with {@code prefix}, at most {@code limit} rows. */
    public List<Map<String, Object>> groups(String prefix, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<PushStore.GroupStatus> rows =
                store.groups(prefix, limit).collectList().block(Duration.ofSeconds(10));
        if (rows == null) return out;
        for (PushStore.GroupStatus g : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("group", g.group());
            m.put("topic", g.topic().toString());
            m.put("pending", g.pending());
            m.put("members", g.members());
            m.put("cursor", g.cursor());
            out.add(m);
        }
        return out;
    }

    /** Forgets a group on every topic; how many topics it was on. */
    public long dropGroup(String group) {
        Long n = store.dropGroup(group).block(Duration.ofSeconds(10));
        return n == null ? 0 : n;
    }

    /**
     * Moves a group's position on {@code topicName} to {@code to}: an id, {@code first} or
     * {@code head}. Its claims are dropped, so the group redelivers from there. False when the
     * group is unknown.
     */
    public Mono<Boolean> seekGroup(String group, String topicName, String to) {
        return seek(topicName, to, (topic, id) -> store.seekGroup(PushGroups.name("group", group), topic, id), "group");
    }

    /** Moves a persistent session's cursor on {@code topicName} to {@code to} (an id, {@code first} or {@code head}). */
    public Mono<Boolean> seekSession(String session, String topicName, String to) {
        if (session == null || session.isBlank()) return Mono.error(PushException.badRequest("session is required"));
        return seek(topicName, to, (topic, id) -> store.seekSession(session, topic, id), "session");
    }

    private Mono<Boolean> seek(
            String topicName,
            String to,
            java.util.function.BiFunction<Topic, String, Mono<Boolean>> move,
            String kind) {
        Topic topic;
        try {
            topic = Topic.parse(topicName);
        } catch (IllegalArgumentException | NullPointerException e) {
            return Mono.error(PushException.badRequest("topic is required, as namespace:name"));
        }
        if (to == null || to.isBlank())
            return Mono.error(PushException.badRequest("to is required: an id, first or head"));
        Mono<String> id;
        switch (to.trim()) {
            case "first" -> id = Mono.just(store.firstId());
            case "head" -> id = store.headId(topic).defaultIfEmpty(store.firstId());
            default -> {
                String given = to.trim();
                try {
                    store.compareIds(given, given);
                } catch (RuntimeException e) {
                    return Mono.error(PushException.badRequest("to is not an event id: '" + to + "'"));
                }
                id = Mono.just(given);
            }
        }
        return id.flatMap(resolved -> move.apply(topic, resolved)).doOnNext(done -> {
            if (done) metrics.seek(kind);
        });
    }

    public void setSessions(PushSessions sessions) {
        this.sessions = sessions;
    }

    /** The live sessions whose name starts with {@code prefix}, at most {@code limit} cursors. */
    public List<Map<String, Object>> sessions(String prefix, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<PushStore.SessionCursor> cursors =
                store.sessions(prefix, limit).collectList().block(Duration.ofSeconds(10));
        if (cursors == null) return out;
        for (PushStore.SessionCursor c : cursors) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("session", c.session());
            m.put("topic", c.topic().toString());
            m.put("cursor", c.cursor());
            m.put(
                    "expiresAt",
                    c.expiresAt() == null || c.expiresAt().equals(java.time.Instant.MAX)
                            ? null
                            : c.expiresAt().toString());
            out.add(m);
        }
        return out;
    }

    /** Forgets a session on every topic; how many cursors went. */
    public long dropSession(String session) {
        Long n = store.dropSession(session).block(Duration.ofSeconds(10));
        return n == null ? 0 : n;
    }

    public void setHooks(PushHooks hooks) {
        this.hooks = hooks;
    }

    public void setSources(PushSources sources) {
        this.sources = sources;
    }

    public void setDlq(PushDlq dlq) {
        this.dlq = dlq;
    }

    /** The sources as the overview and the {@code sources} section show them. */
    public List<Map<String, Object>> sources() {
        PushSources s = sources;
        return s == null ? List.of() : s.status();
    }

    /** The dead-letter queue, newest first. */
    public List<Map<String, Object>> dlq(String source, int limit) {
        PushDlq d = dlq;
        if (d == null) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (PushDlq.Entry e : d.list(source, limit)) {
            out.add(PushDlq.render(e, properties.getAdmin().getPayloadVisibility()));
        }
        return out;
    }

    public Map<String, Object> dlqEntry(String id) {
        PushDlq d = dlq;
        PushDlq.Entry e = d == null ? null : d.get(id);
        return e == null ? null : PushDlq.render(e, properties.getAdmin().getPayloadVisibility());
    }

    public boolean deleteDlq(String id) {
        PushDlq d = dlq;
        return d != null && d.remove(id);
    }

    /**
     * Publishes a dead-lettered record again, to {@code target} or the topic it was meant for,
     * stamped {@code retried_by}; the entry leaves the queue once published. Not found: null.
     */
    public Mono<PushEvent> retryDlq(String id, String target, String by) {
        PushDlq d = dlq;
        PushDlq.Entry e = d == null ? null : d.get(id);
        if (e == null) return Mono.empty();
        String topicText = target != null && !target.isBlank() ? target : e.topic();
        if (topicText == null || topicText.isBlank()) {
            return Mono.error(PushException.badRequest("entry " + id + " has no topic; give one"));
        }
        Topic topic;
        try {
            topic = Topic.parse(topicText);
        } catch (IllegalArgumentException ex) {
            return Mono.error(PushException.badRequest(ex.getMessage()));
        }
        Map<String, String> attributes = new LinkedHashMap<>(e.attributes());
        attributes.put("retried_by", by == null ? "" : by);
        attributes.put("dlq_id", e.id());
        attributes.putIfAbsent("from", "source");
        attributes.putIfAbsent("source", e.source());
        return publisher
                .publish(topic, e.data(), attributes, e.key(), e.typeUrl(), e.proto())
                .doOnNext(ev -> d.remove(id));
    }

    private final Instant started = Instant.now();
    private final String instance;

    /** The last counter sample, for the per-second rates. */
    private volatile Sample last;

    private volatile double[] rates = new double[3];

    private record Sample(long nanos, double published, double delivered, double dropped) {}

    public PushReport(
            PushProperties properties,
            PushBroker broker,
            PushStore store,
            PushMetrics metrics,
            PushConnections connections,
            PushPublisher publisher) {
        this(
                properties,
                broker,
                store,
                metrics,
                connections,
                publisher,
                PushArchive.none(),
                new PushRepublishJobs(),
                new PushAudit());
    }

    public PushReport(
            PushProperties properties,
            PushBroker broker,
            PushStore store,
            PushMetrics metrics,
            PushConnections connections,
            PushPublisher publisher,
            PushArchive archive,
            PushRepublishJobs jobs,
            PushAudit audit) {
        this.properties = properties;
        this.broker = broker;
        this.store = store;
        this.metrics = metrics;
        this.connections = connections;
        this.publisher = publisher;
        this.archive = archive == null ? PushArchive.none() : archive;
        this.jobs = jobs == null ? new PushRepublishJobs() : jobs;
        this.audit = audit == null ? new PushAudit() : audit;
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (java.io.IOException e) {
            host = "unknown";
        }
        this.instance = host;
        this.last = new Sample(System.nanoTime(), 0, 0, 0);
    }

    public PushAudit audit() {
        return audit;
    }

    /** Where the deliveries are counted and logged, for {@code messages/{id}/deliveries}. */
    public void setDeliveries(PushDeliveries deliveries) {
        this.deliveries = deliveries;
    }

    /** The deliveries of one archived (or recent) event; see {@link PushDeliveries#report}. */
    public Mono<Map<String, Object>> deliveries(String topic, String id, int limit) {
        Topic t;
        try {
            t = Topic.parse(topic);
        } catch (IllegalArgumentException e) {
            return Mono.error(PushException.badRequest(e.getMessage()));
        }
        PushDeliveries d = deliveries;
        if (d == null) {
            Map<String, Object> none = new LinkedHashMap<>();
            none.put("topic", t.toString());
            none.put("id", id);
            none.put("logged", false);
            none.put("scope", "instance");
            none.put("delivered", 0L);
            none.put("dropped", 0L);
            none.put("log", List.of());
            return Mono.just(none);
        }
        return d.report(t, id, limit);
    }

    /** The upstream-backed topics, for the topics view's {@code upstream} column and the overview. */
    public void setUpstreams(PushUpstreams upstreams) {
        this.upstreams = upstreams;
    }

    public PushArchive archive() {
        return archive;
    }

    public Map<String, Object> overview() {
        sample();
        Map<Topic, Long> subs = broker.subscriptions();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instance", instance);
        out.put("uptimeSeconds", Duration.between(started, Instant.now()).toSeconds());
        Map<String, Object> conns = new LinkedHashMap<>();
        conns.put("total", metrics.connections());
        for (String t : PushMetrics.TRANSPORTS) conns.put(t, metrics.connections(t));
        out.put("connections", conns);
        out.put("subscriptions", metrics.subscriptions());
        out.put("activeTopics", subs.size());
        out.put("namespaces", properties.getNamespaces().size());
        Map<String, Object> engine = new LinkedHashMap<>();
        engine.put("broker", properties.getBroker().name());
        engine.put("store", properties.getStore().name());
        engine.put("history", store.capabilities().history());
        PushArchive.SearchCapabilities search = archive.searchCapabilities();
        Map<String, Object> archiveInfo = new LinkedHashMap<>();
        archiveInfo.put("enabled", search.enabled());
        archiveInfo.put(
                "retention",
                search.retention() == null ? null : search.retention().toString());
        archiveInfo.put("attributes", search.attributes());
        archiveInfo.put("fullText", search.fullText());
        archiveInfo.put(
                "payloadVisibility",
                properties.getAdmin().getPayloadVisibility().name());
        engine.put("archive", archiveInfo);
        PushUpstreams ups = upstreams;
        Map<String, Object> upstreamInfo = new LinkedHashMap<>();
        upstreamInfo.put("patterns", ups == null ? 0 : ups.upstreams().size());
        upstreamInfo.put("owned", ups == null ? List.of() : ups.owned());
        engine.put("upstreams", upstreamInfo);
        PushHooks hk = hooks;
        Map<String, Object> hookInfo = new LinkedHashMap<>();
        hookInfo.put("count", hk == null ? 0 : hk.size());
        hookInfo.put("names", hk == null ? List.of() : hk.names());
        hookInfo.put("activeTopics", hk == null ? 0 : hk.activeTopics());
        engine.put("hooks", hookInfo);
        engine.put("sources", sources());
        PushDlq d = dlq;
        Map<String, Object> dlqInfo = new LinkedHashMap<>();
        dlqInfo.put("size", d == null ? 0 : d.size());
        dlqInfo.put("max", d == null ? 0 : d.max());
        dlqInfo.put("added", d == null ? 0L : d.added());
        engine.put("dlq", dlqInfo);
        PushSessions ps = sessions;
        Map<String, Object> sessionInfo = new LinkedHashMap<>();
        sessionInfo.put("supported", store.persistentSessions());
        sessionInfo.put("namespaces", ps == null ? List.of() : ps.namespaces());
        sessionInfo.put("acks", (long) metrics.acksCount());
        engine.put("sessions", sessionInfo);
        PushGroups pg = groups;
        Map<String, Object> groupInfo = new LinkedHashMap<>();
        groupInfo.put("supported", store.consumerGroups());
        groupInfo.put("namespaces", pg == null ? List.of() : pg.namespaces());
        groupInfo.put("members", pg == null ? 0 : pg.members());
        groupInfo.put("acks", (long) metrics.groupAcksCount());
        engine.put("groups", groupInfo);
        out.put("engine", engine);
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("published", (long) metrics.publishedCount());
        totals.put("delivered", (long) metrics.deliveredCount());
        totals.put("dropped", (long) metrics.droppedCount());
        out.put("totals", totals);
        Map<String, Object> perSecond = new LinkedHashMap<>();
        perSecond.put("published", round(rates[0]));
        perSecond.put("delivered", round(rates[1]));
        perSecond.put("dropped", round(rates[2]));
        out.put("perSecond", perSecond);
        Map<String, Object> ws = new LinkedHashMap<>();
        ws.put("enabled", properties.getWs().isEnabled());
        ws.put("path", PushConfiguration.normalizePath(properties.getPath()) + "/ws");
        out.put("ws", ws);
        out.put("path", PushConfiguration.normalizePath(properties.getPath()));
        return out;
    }

    public List<Map<String, Object>> namespaces() {
        Map<Topic, Long> subs = broker.subscriptions();
        List<Map<String, Object>> out = new ArrayList<>();
        properties.getNamespaces().forEach((name, ns) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("qos", ns.getQos().name());
            m.put("historySize", ns.getHistorySize());
            m.put(
                    "historyTtl",
                    ns.getHistoryTtl() == null ? null : ns.getHistoryTtl().toString());
            m.put("clientQueueSize", ns.getClientQueueSize());
            m.put("auth", ns.getAuth().name());
            m.put("publishAuth", ns.getPublishAuth().name());
            m.put("clientPublish", ns.isClientPublish());
            long topics = 0;
            long subscribers = 0;
            for (Map.Entry<Topic, Long> e : subs.entrySet()) {
                if (e.getKey().namespace().equals(name)) {
                    topics++;
                    subscribers += e.getValue();
                }
            }
            m.put("activeTopics", topics);
            m.put("subscribers", subscribers);
            out.add(m);
        });
        return out;
    }

    /** Topics with a live subscriber on this instance, most subscribed first, optionally filtered by a substring. */
    public List<Map<String, Object>> topics(String query, int limit) {
        Map<Topic, Long> subs = broker.subscriptions();
        List<Map.Entry<Topic, Long>> entries = new ArrayList<>(subs.entrySet());
        entries.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<Topic, Long> e : entries) {
            String name = e.getKey().toString();
            if (query != null && !query.isBlank() && !name.contains(query.trim())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("topic", name);
            m.put("subscribers", e.getValue());
            PushProperties.Namespace ns = properties.namespaceOf(e.getKey());
            m.put("history", ns != null && ns.getHistorySize() > 0 ? store.historyLength(e.getKey()) : 0L);
            PushUpstreams ups = upstreams;
            String upstream = ups == null ? null : ups.status(e.getKey());
            if (upstream != null) m.put("upstream", upstream);
            out.add(m);
            if (out.size() >= limit) break;
        }
        return out;
    }

    public List<PushConnections.Info> connections(String principal, String topic, String transport) {
        Predicate<PushConnections.Info> filter = i -> true;
        if (principal != null && !principal.isBlank())
            filter = filter.and(i -> principal.trim().equals(i.principal()));
        if (topic != null && !topic.isBlank())
            filter = filter.and(i -> i.topics().contains(topic.trim()));
        if (transport != null && !transport.isBlank())
            filter = filter.and(i -> transport.trim().equals(i.transport()));
        PushRegistry r = registry;
        if (r != null && r.enabled()) {
            List<PushConnections.Info> all = r.list(null, 5000).block(java.time.Duration.ofSeconds(10));
            if (all == null) return List.of();
            List<PushConnections.Info> out = new ArrayList<>();
            for (PushConnections.Info i : all) if (filter.test(i)) out.add(i);
            return out;
        }
        return connections.list(filter);
    }

    private volatile PushRegistry registry;

    /** The cluster-wide connection registry, when the store keeps one. */
    public void setRegistry(PushRegistry registry) {
        this.registry = registry;
    }

    private volatile PushSender sender;

    /** Reaches the connections other instances hold, over the peer channel. */
    public void setSender(PushSender sender) {
        this.sender = sender;
    }

    /** Sends {@code data} (JSON text) to connection {@code id}, here or on another instance, as the operator. */
    public Mono<Map<String, Object>> sendToConnection(String id, String data, boolean requireAck, Long timeoutMs) {
        PushSender s = sender;
        if (s == null) return Mono.error(PushException.badRequest("targeted Send is not configured"));
        PushEventProtos.Payload payload;
        try {
            payload = new PushEventProtos.Payload(
                    PushJson.toJson(PushJson.toValue(data.getBytes(StandardCharsets.UTF_8))), null, null);
        } catch (java.io.IOException e) {
            return Mono.error(PushException.badRequest("data is not JSON: " + e.getMessage()));
        }
        return s.sendAsOperator(
                        PushSender.Target.ofConnection(id),
                        payload,
                        Map.of("from", "admin"),
                        requireAck,
                        timeoutMs == null ? null : java.time.Duration.ofMillis(timeoutMs))
                .map(r -> {
                    List<Map<String, Object>> deliveries = new ArrayList<>();
                    for (PushSender.Delivery d : r.deliveries()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("connectionId", d.connectionId());
                        row.put("instance", d.instance());
                        row.put("status", d.status().name());
                        deliveries.add(row);
                    }
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("id", r.id());
                    out.put("deliveries", deliveries);
                    return out;
                });
    }

    /** Ends connection {@code id}: on this instance, or on the one holding it when a peer channel reaches it. */
    public boolean disconnect(String id) {
        if (connections.disconnect(id, "admin")) return true;
        PushSender s = sender;
        if (s == null) return false;
        Boolean done = s.disconnect(id, "admin").block(java.time.Duration.ofSeconds(10));
        return done != null && done;
    }

    /** The instance holding connection {@code id} when it is not this one; null if unknown or local. */
    public String remoteInstance(String id) {
        PushRegistry r = registry;
        if (r == null || !r.enabled()) return null;
        List<PushConnections.Info> all = r.list(null, 5000).block(java.time.Duration.ofSeconds(10));
        if (all == null) return null;
        for (PushConnections.Info i : all) {
            if (i.id().equals(id) && i.instance() != null && !i.instance().equals(r.instance())) return i.instance();
        }
        return null;
    }

    /** Publishes as the operator, bypassing the namespace's publish check (the endpoint's own gate applies). */
    public Mono<PushEvent> publish(String topic, byte[] data, Map<String, String> attributes) {
        return publish(topic, data, attributes, null);
    }

    /** {@link #publish(String, byte[], Map)} stamping {@code origin} (may be null) as the event's {@code push.origin}. */
    public Mono<PushEvent> publish(String topic, byte[] data, Map<String, String> attributes, String origin) {
        Topic t;
        try {
            t = Topic.parse(topic);
        } catch (IllegalArgumentException e) {
            return Mono.error(PushException.badRequest(e.getMessage()));
        }
        Map<String, String> attrs = new LinkedHashMap<>(attributes == null ? Map.of() : attributes);
        attrs.putIfAbsent("from", "admin");
        return Mono.defer(() -> {
            String declared = PushOrigin.parse(origin);
            if (declared != null) attrs.put(PushOrigin.ATTRIBUTE, declared);
            return publisher.publish(t, data, attrs, null);
        });
    }

    // ---- the archive ----

    /** Archived events matching {@code query}, newest first, rendered per {@code admin.payload-visibility}. */
    public List<Map<String, Object>> messages(PushArchive.Query query) {
        List<PushArchive.Entry> found = archive.search(query).collectList().block(BLOCK);
        List<Map<String, Object>> out = new ArrayList<>();
        if (found != null) for (PushArchive.Entry e : found) out.add(render(e));
        return out;
    }

    /** One archived event, or null. */
    public Map<String, Object> message(String topic, String id) {
        Topic t = Topic.parse(topic);
        PushArchive.Entry e = archive.get(t, id).block(BLOCK);
        return e == null ? null : render(e);
    }

    /**
     * Republishes one archived event as a new event on {@code target} (the same topic when null),
     * linked by {@code origin_id}; a dry run answers what would be sent without sending it.
     */
    public Mono<Map<String, Object>> republish(
            String topic, String id, String target, String reason, String by, boolean dryRun) {
        Topic origin;
        Topic to;
        try {
            origin = Topic.parse(topic);
            to = target == null || target.isBlank() ? origin : Topic.parse(target);
        } catch (IllegalArgumentException e) {
            return Mono.error(PushException.badRequest(e.getMessage()));
        }
        PushException refused = republishRefused(to);
        if (refused != null) return Mono.error(refused);
        return archive.get(origin, id)
                .switchIfEmpty(Mono.error(PushException.notFound("no archived event " + id + " on " + origin)))
                .flatMap(entry -> {
                    if (entry.tombstoned()) {
                        return Mono.error(PushException.conflict("event " + id + " on " + origin + " is a tombstone"));
                    }
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("dryRun", dryRun);
                    out.put("origin", Map.of("topic", origin.toString(), "id", id));
                    out.put("target", to.toString());
                    if (dryRun) return Mono.just(out);
                    return republishOne(entry, to, reason, by).map(sent -> {
                        out.put("id", sent.id());
                        return out;
                    });
                });
    }

    /**
     * Republishes every archived event a query matches (at most {@code admin.republish.max-batch})
     * as a job, or, as a dry run, answers how many it would send.
     */
    public Map<String, Object> republishAll(
            PushArchive.Query query, String target, String reason, String by, boolean dryRun) {
        Topic to = null;
        if (target != null && !target.isBlank()) {
            try {
                to = Topic.parse(target);
            } catch (IllegalArgumentException e) {
                throw PushException.badRequest(e.getMessage());
            }
            PushException refused = republishRefused(to);
            if (refused != null) throw refused;
        }
        int max = properties.getAdmin().getRepublish().getMaxBatch();
        List<PushArchive.Entry> matched = archive.search(query.withLimit(max + 1))
                .filter(e -> !e.tombstoned())
                .collectList()
                .block(BLOCK);
        if (matched == null) matched = List.of();
        boolean capped = matched.size() > max;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dryRun", dryRun);
        out.put("matched", Math.min(matched.size(), max));
        out.put("capped", capped);
        out.put("maxBatch", max);
        out.put("target", to == null ? null : to.toString());
        if (dryRun) return out;
        if (capped) {
            throw PushException.badRequest(
                    "the query matches more than admin.republish.max-batch (" + max + ") events; narrow it");
        }
        if (to == null) {
            // Republishing in place: every origin namespace must be allowed.
            for (PushArchive.Entry e : matched) {
                PushException refused = republishRefused(e.event().topic());
                if (refused != null) throw refused;
            }
        }
        Topic fixed = to;
        PushRepublishJobs.Job job = jobs.start(
                by,
                fixed == null ? null : fixed.toString(),
                reason,
                matched,
                e -> republishOne(e, fixed == null ? e.event().topic() : fixed, reason, by),
                properties.getAdmin().getRepublish().getRatePerSecond());
        out.put("job", job.toMap());
        return out;
    }

    /** Redacts one archived event and takes it out of the history. True when something was redacted. */
    public Mono<Boolean> tombstone(String topic, String id, String by) {
        Topic t;
        try {
            t = Topic.parse(topic);
        } catch (IllegalArgumentException e) {
            return Mono.error(PushException.badRequest(e.getMessage()));
        }
        return archive.tombstone(t, id, by).flatMap(done -> {
            if (!done) return Mono.just(false);
            if (archive == store) return Mono.just(true);
            return store.redact(t, id).thenReturn(true);
        });
    }

    public List<Map<String, Object>> jobs() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PushRepublishJobs.Job j : jobs.list()) out.add(j.toMap());
        return out;
    }

    public Map<String, Object> job(String id) {
        PushRepublishJobs.Job j = jobs.get(id);
        return j == null ? null : j.toMap();
    }

    public boolean cancelJob(String id) {
        return jobs.cancel(id);
    }

    public List<Map<String, Object>> auditLog(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PushAudit.Entry e : audit.list(limit)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("time", e.time().toString());
            m.put("principal", e.principal());
            m.put("action", e.action());
            m.put("arguments", e.arguments());
            m.put("result", e.result());
            out.add(m);
        }
        return out;
    }

    private Mono<PushEvent> republishOne(PushArchive.Entry entry, Topic to, String reason, String by) {
        PushEvent e = entry.event();
        Map<String, String> attrs = new LinkedHashMap<>(e.attributes());
        attrs.put("origin_id", e.id());
        attrs.put("origin_topic", e.topic().toString());
        attrs.put("republished_by", by == null ? "anonymous" : by);
        if (reason != null && !reason.isBlank()) attrs.put("reason", reason.trim());
        return publisher.publish(to, e.data(), attrs, e.key(), e.typeUrl(), e.proto());
    }

    /** Null when a republish may target {@code topic}; else why not (allow-list, undeclared namespace). */
    private PushException republishRefused(Topic topic) {
        if (properties.namespaceOf(topic) == null) return PushException.unknownNamespace(topic);
        List<String> allowed = properties.getAdmin().getRepublish().getNamespaces();
        if (!allowed.isEmpty() && !allowed.contains(topic.namespace())) {
            return PushException.forbidden(
                    "namespace '" + topic.namespace() + "' is not in shiguchi.cursus.admin.republish.namespaces");
        }
        return null;
    }

    /** An archived event for the endpoint, as much of it as {@code admin.payload-visibility} allows. */
    Map<String, Object> render(PushArchive.Entry entry) {
        PushEvent e = entry.event();
        PushProperties.PayloadVisibility visibility = properties.getAdmin().getPayloadVisibility();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("topic", e.topic().toString());
        m.put("id", e.id());
        m.put("time", e.time().toString());
        m.put("bytes", e.data().length);
        m.put("typeUrl", e.typeUrl());
        m.put("tombstoned", entry.tombstoned());
        if (entry.tombstoned()) {
            m.put("tombstonedAt", entry.tombstonedAt().toString());
            m.put("tombstonedBy", entry.tombstonedBy());
        }
        if (visibility != PushProperties.PayloadVisibility.NONE) {
            m.put("key", e.key());
            m.put("attributes", e.attributes());
        }
        if (visibility == PushProperties.PayloadVisibility.FULL) {
            m.put("data", new String(e.data(), StandardCharsets.UTF_8));
        }
        return m;
    }

    private void sample() {
        long now = System.nanoTime();
        Sample prev = last;
        double elapsed = (now - prev.nanos) / 1e9;
        if (elapsed < 1.0) return;
        Sample next = new Sample(now, metrics.publishedCount(), metrics.deliveredCount(), metrics.droppedCount());
        rates = new double[] {
            (next.published - prev.published) / elapsed,
            (next.delivered - prev.delivered) / elapsed,
            (next.dropped - prev.dropped) / elapsed
        };
        last = next;
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
