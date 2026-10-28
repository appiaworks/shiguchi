package dev.appiaworks.shiguchi.cursus.postgres;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.appiaworks.shiguchi.cursus.PushArchive;
import dev.appiaworks.shiguchi.cursus.PushDelivery;
import dev.appiaworks.shiguchi.cursus.PushEvent;
import dev.appiaworks.shiguchi.cursus.PushProperties;
import dev.appiaworks.shiguchi.cursus.PushStore;
import dev.appiaworks.shiguchi.cursus.Topic;
import dev.appiaworks.shiguchi.cursus.TopicPattern;
import io.r2dbc.spi.Readable;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * History in one Postgres table ({@link PostgresPushSchema}): an append is one {@code INSERT ...
 * RETURNING id}, the {@code BIGSERIAL} id ordered across every instance; a topic's history is
 * trimmed to {@code history-size} every {@code trim-every} appends, and rows past
 * {@code history-ttl} are swept every {@code sweep-interval}. A topic with no history gets a local
 * id of the same numeric shape but negative, which nobody resumes from and which names no row.
 *
 * <p>With {@code archive.retention} above zero the same table is the archive: every event gets a
 * row (a topic without history too, marked out of the history from the start), trimming marks
 * rows out of the history instead of deleting them, and the sweep deletes a row only once it is
 * out of both the history and the archive window. Search is SQL over the archive indexes; a
 * tombstone redacts the payload in place and takes the row out of the history.
 */
public final class PostgresPushStore implements PushStore, PushArchive, InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(PostgresPushStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, String>> ATTRIBUTES = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
    private static final String COLUMNS =
            "id, topic, data, attributes::text AS attributes, key, time, type_url, proto, tombstoned_at, tombstoned_by";
    private static final String IN_HISTORY = " AND history AND tombstoned_at IS NULL";

    private final DatabaseClient db;
    private final PushProperties.Postgres config;
    private final Duration retention;
    private final String table;
    private final String insert;
    private final String range;
    private final String byId;
    private final String get;
    private final String count;
    private final String trim;
    private final String sweep;
    private final String tombstone;
    private final String lease;
    private final String releaseLease;
    private final String deliveryTable;
    private final String sessionTable;
    private final String groupTable;
    private final String reclaim;
    private final String claimNew;
    private final String stageKeyed;
    private final String reclaimKeyed;
    private final String seekCursor;
    private final String releaseAllClaims;
    private final String eventsByIds;
    private final String ackGroup;
    private final String releaseClaims;
    private final String groupsLike;
    private final String dropGroupClaims;
    private final String sweepGroups;
    private final String headId;
    private final String headEvent;
    private final String cursorOf;
    private final String ackCursor;
    private final String sessionsLike;
    private final String dropSession;
    private final String sweepSessions;
    private final String deliveriesOf;
    private final String deliveryCounts;
    private final String sweepDeliveries;
    private final String connectionTable;
    private final String sweepConnections;
    private final String trimmedThrough;
    private final String sweepTrimmed;
    private final ConcurrentHashMap<Topic, AtomicInteger> appendsSinceTrim = new ConcurrentHashMap<>();
    private final AtomicLong localSequence = new AtomicLong();
    private volatile Disposable sweeper;
    private volatile boolean started;

    public PostgresPushStore(DatabaseClient db, PushProperties.Postgres config) {
        this(db, config, new PushProperties.Archive());
    }

    public PostgresPushStore(DatabaseClient db, PushProperties.Postgres config, PushProperties.Archive archive) {
        this.db = db;
        this.config = config;
        Duration r = archive == null ? null : archive.getRetention();
        this.retention = r == null || r.isNegative() ? Duration.ZERO : r;
        this.table = PostgresPushSchema.table(config.getTable());
        this.insert = "INSERT INTO " + table
                + " (topic, data, attributes, key, time, expires_at, type_url, proto, history, archived_until)"
                + " VALUES ($1, $2, $3::jsonb, $4, $5, $6, $7, $8, $9, $10) RETURNING id";
        this.range = "SELECT " + COLUMNS + " FROM " + table
                + " WHERE topic = $1 AND id > $2 AND (expires_at IS NULL OR expires_at > now())" + IN_HISTORY
                + " ORDER BY id LIMIT $3";
        this.byId = "SELECT " + COLUMNS + " FROM " + table + " WHERE id = $1";
        this.get = "SELECT " + COLUMNS + " FROM " + table + " WHERE id = $1 AND topic = $2";
        this.count = "SELECT count(*) AS n FROM " + table
                + " WHERE topic = $1 AND (expires_at IS NULL OR expires_at > now())" + IN_HISTORY;
        String older = " WHERE topic = $1 AND history AND id < (SELECT id FROM " + table
                + " WHERE topic = $1 AND history ORDER BY id DESC OFFSET $2 LIMIT 1)";
        String trims = PostgresPushSchema.trimTable(table);
        // One statement: the rows go and the watermark moves with them (a redacted row is not a gap).
        Function<String, String> deleting = where -> "WITH gone AS (DELETE FROM " + table + where
                + " RETURNING topic, id, tombstoned_at), mark AS (INSERT INTO " + trims
                + " (topic, trimmed_through, updated_at) SELECT topic, max(id), now() FROM gone"
                + " WHERE tombstoned_at IS NULL GROUP BY topic"
                + " ON CONFLICT (topic) DO UPDATE SET trimmed_through ="
                + " GREATEST(" + trims + ".trimmed_through, EXCLUDED.trimmed_through), updated_at = now())"
                + " SELECT count(*) AS n FROM gone";
        this.trim = archiveOn() ? "UPDATE " + table + " SET history = FALSE" + older : deleting.apply(older);
        String outOfHistory =
                "(NOT history OR tombstoned_at IS NOT NULL" + " OR (expires_at IS NOT NULL AND expires_at < now()))";
        this.sweep = deleting.apply(
                archiveOn()
                        ? " WHERE (archived_until IS NULL OR archived_until < now()) AND " + outOfHistory
                        : " WHERE " + outOfHistory);
        this.trimmedThrough = "SELECT COALESCE(GREATEST((SELECT max(trimmed_through) FROM " + trims
                + " WHERE topic = $1), (SELECT max(id) FROM " + table + " WHERE topic = $1"
                + " AND tombstoned_at IS NULL AND (NOT history OR (expires_at IS NOT NULL AND expires_at <= now())))), 0)"
                + " AS id";
        this.sweepTrimmed = "DELETE FROM " + trims + " WHERE updated_at < now() - interval '7 days'"
                + " AND NOT EXISTS (SELECT 1 FROM " + table + " e WHERE e.topic = " + trims + ".topic)";
        String leases = PostgresPushSchema.leaseTable(table);
        this.lease = "INSERT INTO " + leases + " (key, holder, expires_at) VALUES ($1, $2, $3)"
                + " ON CONFLICT (key) DO UPDATE SET holder = EXCLUDED.holder, expires_at = EXCLUDED.expires_at"
                + " WHERE " + leases + ".holder = EXCLUDED.holder OR " + leases + ".expires_at < now()"
                + " RETURNING holder";
        this.releaseLease = "DELETE FROM " + leases + " WHERE key = $1 AND holder = $2";
        this.deliveryTable = PostgresPushSchema.deliveryTable(table);
        this.sessionTable = PostgresPushSchema.sessionTable(table);
        this.headId = "SELECT max(id) AS id FROM " + table + " WHERE topic = $1 AND history";
        this.headEvent = "SELECT " + COLUMNS + " FROM " + table
                + " WHERE topic = $1 AND history AND tombstoned_at IS NULL ORDER BY id DESC LIMIT 1";
        this.cursorOf =
                "SELECT last_ack_id FROM " + sessionTable + " WHERE session = $1 AND topic = $2 AND expires_at > now()";
        this.ackCursor = "INSERT INTO " + sessionTable + " (session, topic, last_ack_id, expires_at)"
                + " VALUES ($1, $2, $3, $4) ON CONFLICT (session, topic) DO UPDATE SET"
                + " last_ack_id = GREATEST(" + sessionTable + ".last_ack_id, EXCLUDED.last_ack_id),"
                + " expires_at = EXCLUDED.expires_at";
        this.sessionsLike = "SELECT session, topic, last_ack_id, expires_at FROM " + sessionTable
                + " WHERE session LIKE $1 AND expires_at > now() ORDER BY session, topic LIMIT $2";
        this.dropSession = "DELETE FROM " + sessionTable + " WHERE session = $1";
        this.sweepSessions = "DELETE FROM " + sessionTable + " WHERE expires_at < now()";
        this.groupTable = PostgresPushSchema.groupTable(table);
        this.reclaim = "UPDATE " + groupTable + " SET member = $3, claimed_at = now(), deliveries = deliveries + 1"
                + " WHERE (group_name, topic, event_id) IN (SELECT group_name, topic, event_id FROM " + groupTable
                + " WHERE group_name = $1 AND topic = $2 AND claimed_at < now() - make_interval(secs => $4)"
                + " ORDER BY event_id LIMIT $5 FOR UPDATE SKIP LOCKED) RETURNING event_id, deliveries";
        this.claimNew = "INSERT INTO " + groupTable + " (group_name, topic, event_id, member, claimed_at, deliveries)"
                + " SELECT $1, topic, id, $3, now(), 1 FROM " + table
                + " WHERE topic = $2 AND id > $4 AND (expires_at IS NULL OR expires_at > now())" + IN_HISTORY
                + " ORDER BY id LIMIT $5 ON CONFLICT DO NOTHING RETURNING event_id";
        // KEY ordering: new events are staged as already-expired claims (member '', deliveries 0) so
        // the cursor can move past them, then handed out by the reclaim below, which skips a key
        // another member holds live; a skipped event stays staged and its later siblings stay behind it.
        this.stageKeyed = "INSERT INTO " + groupTable
                + " (group_name, topic, event_id, member, claimed_at, deliveries, key)"
                + " SELECT $1, topic, id, '', to_timestamp(0), 0, key FROM " + table
                + " WHERE topic = $2 AND id > $3 AND (expires_at IS NULL OR expires_at > now())" + IN_HISTORY
                + " ORDER BY id LIMIT $4 ON CONFLICT DO NOTHING RETURNING event_id";
        this.reclaimKeyed = "UPDATE " + groupTable + " SET member = $3, claimed_at = now(), deliveries = deliveries + 1"
                + " WHERE (group_name, topic, event_id) IN (SELECT x.group_name, x.topic, x.event_id FROM "
                + groupTable + " x WHERE x.group_name = $1 AND x.topic = $2"
                + " AND x.claimed_at < now() - make_interval(secs => $4)"
                + " AND (x.key IS NULL OR NOT EXISTS (SELECT 1 FROM " + groupTable
                + " y WHERE y.group_name = x.group_name AND y.topic = x.topic AND y.key = x.key"
                + " AND y.member <> $3 AND y.claimed_at >= now() - make_interval(secs => $4)))"
                + " AND pg_try_advisory_xact_lock(hashtext($1 || '|' || $2))"
                + " ORDER BY x.event_id LIMIT $5 FOR UPDATE SKIP LOCKED) RETURNING event_id, deliveries";
        this.seekCursor = "UPDATE " + sessionTable
                + " SET last_ack_id = $3 WHERE session = $1 AND topic = $2 AND expires_at > now()";
        this.eventsByIds = "SELECT " + COLUMNS + " FROM " + table + " WHERE id = ANY($1) ORDER BY id";
        this.ackGroup =
                "DELETE FROM " + groupTable + " WHERE group_name = $1 AND topic = $2 AND event_id = $3 AND member = $4";
        this.releaseClaims = "UPDATE " + groupTable + " SET claimed_at = to_timestamp(0)"
                + " WHERE group_name = $1 AND topic = $2 AND member = $3";
        this.groupsLike = "SELECT s.session, s.topic, s.last_ack_id,"
                + " (SELECT count(*) FROM " + groupTable
                + " g WHERE g.group_name = substr(s.session, 7) AND g.topic = s.topic) AS pending,"
                + " (SELECT count(DISTINCT member) FILTER (WHERE member <> '') FROM " + groupTable
                + " g WHERE g.group_name = substr(s.session, 7) AND g.topic = s.topic) AS members"
                + " FROM " + sessionTable
                + " s WHERE s.session LIKE $1 AND s.expires_at > now() ORDER BY s.session, s.topic LIMIT $2";
        this.dropGroupClaims = "DELETE FROM " + groupTable + " WHERE group_name = $1";
        this.releaseAllClaims = "DELETE FROM " + groupTable + " WHERE group_name = $1 AND topic = $2";
        this.sweepGroups = "DELETE FROM " + groupTable + " g WHERE NOT EXISTS (SELECT 1 FROM " + table
                + " e WHERE e.id = g.event_id)";
        this.deliveriesOf = "SELECT connection_id, principal, transport, time, outcome FROM " + deliveryTable
                + " WHERE topic = $1 AND event_id = $2 ORDER BY time LIMIT $3";
        this.deliveryCounts = "SELECT outcome, count(*) AS n FROM " + deliveryTable
                + " WHERE topic = $1 AND event_id = $2 GROUP BY outcome";
        this.sweepDeliveries = "DELETE FROM " + deliveryTable + " d WHERE NOT EXISTS (SELECT 1 FROM " + table
                + " e WHERE e.id = d.event_id)";
        this.connectionTable = PostgresPushSchema.connectionTable(table);
        this.sweepConnections = "DELETE FROM " + connectionTable + " WHERE expires_at < now()";
        this.tombstone = "UPDATE " + table + " SET data = 'null', proto = NULL, type_url = NULL,"
                + " tombstoned_at = now(), tombstoned_by = $3 WHERE id = $1 AND topic = $2 AND tombstoned_at IS NULL";
    }

    /** True when the table doubles as the archive ({@code archive.retention} above zero). */
    public boolean archiveOn() {
        return !retention.isZero();
    }

    /**
     * Creates the table when asked to, and starts the sweeper. Once: with the archive on this
     * object is also the {@code PushArchive} bean, and Spring runs the init callbacks per bean.
     */
    @Override
    public void afterPropertiesSet() {
        if (started) return;
        started = true;
        if (config.isCreateSchema()) {
            Flux.fromIterable(PostgresPushSchema.statements(table, archiveOn()))
                    .concatMap(sql -> db.sql(sql).fetch().rowsUpdated())
                    .blockLast(Duration.ofSeconds(30));
            log.info("Push: Postgres table {} ready{}", table, archiveOn() ? " (archive " + retention + ")" : "");
        }
        Duration every = config.getSweepInterval();
        if (every != null && !every.isZero() && !every.isNegative()) {
            sweeper = Flux.interval(every, every, Schedulers.parallel())
                    .concatMap(t -> sweep().onErrorResume(e -> {
                        log.warn("Push: Postgres history sweep failed: {}", e.toString());
                        return Mono.empty();
                    }))
                    .subscribe();
        }
    }

    @Override
    public void destroy() {
        Disposable s = sweeper;
        if (s != null) s.dispose();
    }

    @Override
    public Mono<PushEvent> append(PushEvent event, int historySize, Duration historyTtl) {
        boolean history = historySize > 0;
        if (!history && !archiveOn()) return Mono.just(event.withId(Long.toString(-localId())));
        OffsetDateTime time = event.time().atOffset(ZoneOffset.UTC);
        OffsetDateTime expires = !history || historyTtl == null || historyTtl.isZero() || historyTtl.isNegative()
                ? null
                : time.plus(historyTtl);
        OffsetDateTime archivedUntil = archiveOn() ? time.plus(retention) : null;
        DatabaseClient.GenericExecuteSpec spec = db.sql(insert)
                .bind("$1", event.topic().toString())
                .bind("$2", new String(event.data(), StandardCharsets.UTF_8))
                .bind("$3", attributesJson(event.attributes()))
                .bind("$5", time)
                .bind("$9", history);
        spec = event.key() == null ? spec.bindNull("$4", String.class) : spec.bind("$4", event.key());
        spec = expires == null ? spec.bindNull("$6", OffsetDateTime.class) : spec.bind("$6", expires);
        spec = event.typed()
                ? spec.bind("$7", event.typeUrl()).bind("$8", event.proto())
                : spec.bindNull("$7", String.class).bindNull("$8", byte[].class);
        spec = archivedUntil == null ? spec.bindNull("$10", OffsetDateTime.class) : spec.bind("$10", archivedUntil);
        return spec.map(row -> row.get("id", Long.class))
                .one()
                .map(id -> event.withId(Long.toString(id)))
                .doOnSuccess(stored -> {
                    if (history) maybeTrim(stored.topic(), historySize);
                });
    }

    /** Every {@code trim-every} appends to a topic, drop from the history what is older than its newest {@code historySize} rows. */
    private void maybeTrim(Topic topic, int historySize) {
        int every = Math.max(1, config.getTrimEvery());
        AtomicInteger n = appendsSinceTrim.computeIfAbsent(topic, t -> new AtomicInteger());
        if (n.incrementAndGet() < every) return;
        n.set(0);
        db.sql(trim)
                .bind("$1", topic.toString())
                .bind("$2", (long) Math.max(0, historySize - 1))
                .fetch()
                .rowsUpdated()
                .subscribe(deleted -> {}, e -> log.warn("Push: Postgres trim of {} failed: {}", topic, e.toString()));
    }

    /** Deletes rows out of the history (and, with the archive on, out of its window too); the sweeper calls this on its interval. */
    public Mono<Long> sweep() {
        return db.sql(sweep)
                .map(row -> row.get("n", Long.class))
                .one()
                .defaultIfEmpty(0L)
                .flatMap(n -> db.sql(sweepTrimmed).fetch().rowsUpdated().thenReturn(n))
                .flatMap(n -> db.sql(sweepDeliveries).fetch().rowsUpdated().thenReturn(n))
                .flatMap(n -> sweepSessions().thenReturn(n))
                .flatMap(n -> sweepConnections().thenReturn(n))
                .flatMap(n -> db.sql(sweepGroups).fetch().rowsUpdated().thenReturn(n));
    }

    @Override
    public boolean consumerGroups() {
        return true;
    }

    private static String groupSession(String group) {
        return "group:" + group;
    }

    @Override
    public Flux<Claimed> claim(
            String group, Topic topic, String member, int max, Duration ackDeadline, Duration expiry) {
        if (max <= 0) return Flux.empty();
        double idleSeconds = ackDeadline == null ? 1e9 : Math.max(0.001, ackDeadline.toMillis() / 1000.0);
        Flux<Claimed> redelivered = db.sql(reclaim)
                .bind("$1", group)
                .bind("$2", topic.toString())
                .bind("$3", member)
                .bind("$4", idleSeconds)
                .bind("$5", max)
                .map(row -> Map.entry(row.get("event_id", Long.class), row.get("deliveries", Integer.class)))
                .all()
                .collectList()
                .flatMapMany(rows -> rows.isEmpty() ? Flux.empty() : withEvents(rows));
        return redelivered.collectList().flatMapMany(first -> {
            int room = max - first.size();
            if (room <= 0) return Flux.fromIterable(first);
            String session = groupSession(group);
            Mono<String> cursor = cursor(session, topic)
                    .switchIfEmpty(Mono.defer(() -> headId(topic).defaultIfEmpty(firstId())));
            Flux<Claimed> fresh = cursor.flatMapMany(c -> db.sql(claimNew)
                    .bind("$1", group)
                    .bind("$2", topic.toString())
                    .bind("$3", member)
                    .bind("$4", parse(c))
                    .bind("$5", room)
                    .map(row -> Map.entry(row.get("event_id", Long.class), 1))
                    .all()
                    .collectList()
                    .flatMapMany(rows -> {
                        long newest =
                                rows.stream().mapToLong(Map.Entry::getKey).max().orElse(parse(c));
                        Mono<Void> moved = ack(session, topic, Long.toString(newest), expiry);
                        return moved.thenMany(rows.isEmpty() ? Flux.empty() : withEvents(rows));
                    }));
            return Flux.concat(Flux.fromIterable(first), fresh);
        });
    }

    @Override
    public boolean keyOrdering() {
        return true;
    }

    @Override
    public Flux<Claimed> claim(
            String group,
            Topic topic,
            String member,
            int max,
            Duration ackDeadline,
            Duration expiry,
            PushProperties.Ordering ordering) {
        if (ordering != PushProperties.Ordering.KEY) return claim(group, topic, member, max, ackDeadline, expiry);
        if (max <= 0) return Flux.empty();
        double idleSeconds = ackDeadline == null ? 1e9 : Math.max(0.001, ackDeadline.toMillis() / 1000.0);
        String session = groupSession(group);
        Mono<String> cursor = cursor(session, topic)
                .switchIfEmpty(Mono.defer(() -> headId(topic).defaultIfEmpty(firstId())));
        Mono<Void> staged = cursor.flatMap(c -> db.sql(stageKeyed)
                .bind("$1", group)
                .bind("$2", topic.toString())
                .bind("$3", parse(c))
                .bind("$4", 1000)
                .map(row -> row.get("event_id", Long.class))
                .all()
                .collectList()
                .flatMap(ids -> ack(
                        session,
                        topic,
                        Long.toString(
                                ids.stream().mapToLong(Long::longValue).max().orElse(parse(c))),
                        expiry)));
        return staged.then(Mono.defer(() -> db.sql(reclaimKeyed)
                        .bind("$1", group)
                        .bind("$2", topic.toString())
                        .bind("$3", member)
                        .bind("$4", idleSeconds)
                        .bind("$5", max)
                        .map(row -> Map.entry(row.get("event_id", Long.class), row.get("deliveries", Integer.class)))
                        .all()
                        .collectList()))
                .flatMapMany(rows -> rows.isEmpty() ? Flux.empty() : withEvents(rows));
    }

    @Override
    public Mono<Boolean> seekGroup(String group, Topic topic, String id) {
        return seekSession(groupSession(group), topic, id)
                .flatMap(found -> !found
                        ? Mono.just(false)
                        : db.sql(releaseAllClaims)
                                .bind("$1", group)
                                .bind("$2", topic.toString())
                                .fetch()
                                .rowsUpdated()
                                .thenReturn(true));
    }

    @Override
    public Mono<Boolean> seekSession(String session, Topic topic, String id) {
        long numeric;
        try {
            numeric = parse(id);
        } catch (IllegalArgumentException e) {
            return Mono.just(false);
        }
        return db.sql(seekCursor)
                .bind("$1", session)
                .bind("$2", topic.toString())
                .bind("$3", numeric)
                .fetch()
                .rowsUpdated()
                .map(n -> n > 0);
    }

    /** The claimed rows with their events; a row whose event is gone is dropped. */
    private Flux<Claimed> withEvents(List<Map.Entry<Long, Integer>> rows) {
        Long[] ids = rows.stream().map(Map.Entry::getKey).toArray(Long[]::new);
        Map<Long, Integer> deliveries = new java.util.HashMap<>();
        for (Map.Entry<Long, Integer> r : rows) deliveries.put(r.getKey(), r.getValue());
        return db.sql(eventsByIds)
                .bind("$1", ids)
                .map(PostgresPushStore::event)
                .all()
                .map(e -> new Claimed(e, deliveries.getOrDefault(Long.parseLong(e.id()), 1)));
    }

    @Override
    public Mono<Boolean> ackGroup(String group, Topic topic, String member, String id) {
        long numeric;
        try {
            numeric = parse(id);
        } catch (IllegalArgumentException e) {
            return Mono.just(false);
        }
        return db.sql(ackGroup)
                .bind("$1", group)
                .bind("$2", topic.toString())
                .bind("$3", numeric)
                .bind("$4", member)
                .fetch()
                .rowsUpdated()
                .map(n -> n > 0);
    }

    @Override
    public Mono<Long> releaseClaims(String group, Topic topic, String member) {
        return db.sql(releaseClaims)
                .bind("$1", group)
                .bind("$2", topic.toString())
                .bind("$3", member)
                .fetch()
                .rowsUpdated();
    }

    @Override
    public Flux<GroupStatus> groups(String prefix, int limit) {
        String like = "group:"
                + (prefix == null
                        ? ""
                        : prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")) + "%";
        return db.sql(groupsLike)
                .bind("$1", like)
                .bind("$2", limit)
                .map(row -> new GroupStatus(
                        row.get("session", String.class).substring("group:".length()),
                        Topic.parse(row.get("topic", String.class)),
                        row.get("pending", Long.class),
                        row.get("members", Long.class),
                        Long.toString(row.get("last_ack_id", Long.class))))
                .all();
    }

    @Override
    public Mono<Long> dropGroup(String group) {
        return db.sql(dropGroupClaims).bind("$1", group).fetch().rowsUpdated().then(dropSession(groupSession(group)));
    }

    @Override
    public boolean persistentSessions() {
        return true;
    }

    @Override
    public Mono<PushEvent> headEvent(Topic topic) {
        return db.sql(headEvent)
                .bind("$1", topic.toString())
                .map(PostgresPushStore::event)
                .one();
    }

    @Override
    public Mono<String> headId(Topic topic) {
        return db.sql(headId)
                .bind("$1", topic.toString())
                .map(row -> java.util.Optional.ofNullable(row.get("id", Long.class)))
                .one()
                .flatMap(id -> id.map(v -> Mono.just(Long.toString(v))).orElse(Mono.empty()));
    }

    @Override
    public Mono<String> cursor(String session, Topic topic) {
        return db.sql(cursorOf)
                .bind("$1", session)
                .bind("$2", topic.toString())
                .map(row -> Long.toString(row.get("last_ack_id", Long.class)))
                .one();
    }

    @Override
    public Mono<Void> ack(String session, Topic topic, String id, Duration expiry) {
        long numeric;
        try {
            numeric = parse(id);
        } catch (IllegalArgumentException e) {
            return Mono.error(e);
        }
        Duration ttl = expiry == null ? Duration.ofDays(365) : expiry;
        return db.sql(ackCursor)
                .bind("$1", session)
                .bind("$2", topic.toString())
                .bind("$3", numeric)
                .bind("$4", OffsetDateTime.now(ZoneOffset.UTC).plus(ttl))
                .fetch()
                .rowsUpdated()
                .then();
    }

    @Override
    public Flux<SessionCursor> sessions(String prefix, int limit) {
        String like = (prefix == null
                        ? ""
                        : prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")) + "%";
        return db.sql(sessionsLike)
                .bind("$1", like)
                .bind("$2", limit)
                .map(row -> {
                    OffsetDateTime at = row.get("expires_at", OffsetDateTime.class);
                    return new SessionCursor(
                            row.get("session", String.class),
                            Topic.parse(row.get("topic", String.class)),
                            Long.toString(row.get("last_ack_id", Long.class)),
                            at == null ? null : at.toInstant());
                })
                .all();
    }

    @Override
    public Mono<Long> dropSession(String session) {
        return db.sql(dropSession).bind("$1", session).fetch().rowsUpdated();
    }

    @Override
    public Mono<Long> sweepSessions() {
        return db.sql(sweepSessions).fetch().rowsUpdated();
    }

    @Override
    public Flux<PushEvent> range(Topic topic, String afterId, int limit) {
        long after = afterId == null ? 0 : parse(afterId);
        return db.sql(range)
                .bind("$1", topic.toString())
                .bind("$2", after)
                .bind("$3", limit)
                .map(PostgresPushStore::event)
                .all();
    }

    @Override
    public Mono<String> trimmedThrough(Topic topic) {
        return db.sql(trimmedThrough)
                .bind("$1", topic.toString())
                .map(row -> row.get("id", Long.class))
                .one()
                .filter(id -> id > 0)
                .map(id -> Long.toString(id));
    }

    /** One kept event by id, for a notification that carried only the id. */
    public Mono<PushEvent> byId(String id) {
        return db.sql(byId).bind("$1", parse(id)).map(PostgresPushStore::event).one();
    }

    /** True when the id names a row of the table; false for the local id of a topic without history. */
    public static boolean isStored(String id) {
        return id != null && !id.isEmpty() && id.charAt(0) != '-';
    }

    @Override
    public int compareIds(String a, String b) {
        return Long.compare(parse(a), parse(b));
    }

    @Override
    public long historyLength(Topic topic) {
        Long n = db.sql(count)
                .bind("$1", topic.toString())
                .map(row -> row.get("n", Long.class))
                .one()
                .block(Duration.ofSeconds(10));
        return n == null ? 0 : n;
    }

    @Override
    public Mono<Boolean> redact(Topic topic, String id) {
        // The tombstone already took the row out of the history: one table, one row.
        return Mono.just(false);
    }

    /** One multi-row INSERT per hundred records. */
    @Override
    public Mono<Void> recordDeliveries(java.util.List<PushDelivery> batch) {
        if (batch.isEmpty()) return Mono.empty();
        return Flux.fromIterable(chunks(batch, 100))
                .concatMap(this::insertDeliveries)
                .then();
    }

    private Mono<Void> insertDeliveries(java.util.List<PushDelivery> rows) {
        StringBuilder sql = new StringBuilder("INSERT INTO ")
                .append(deliveryTable)
                .append(" (topic, event_id, connection_id, principal, transport, time, outcome) VALUES ");
        for (int i = 0; i < rows.size(); i++) {
            int b = i * 7;
            if (i > 0) sql.append(", ");
            sql.append("($")
                    .append(b + 1)
                    .append(", $")
                    .append(b + 2)
                    .append(", $")
                    .append(b + 3)
                    .append(", $")
                    .append(b + 4)
                    .append(", $")
                    .append(b + 5)
                    .append(", $")
                    .append(b + 6)
                    .append(", $")
                    .append(b + 7)
                    .append(')');
        }
        DatabaseClient.GenericExecuteSpec spec = db.sql(sql.toString());
        for (int i = 0; i < rows.size(); i++) {
            PushDelivery d = rows.get(i);
            int b = i * 7;
            long eventId;
            try {
                eventId = parse(d.eventId());
            } catch (IllegalArgumentException e) {
                eventId = 0;
            }
            spec = spec.bind("$" + (b + 1), d.topic().toString())
                    .bind("$" + (b + 2), eventId)
                    .bind("$" + (b + 3), d.connectionId())
                    .bind("$" + (b + 5), d.transport())
                    .bind("$" + (b + 6), d.time().atOffset(ZoneOffset.UTC))
                    .bind("$" + (b + 7), d.outcome());
            spec = d.principal() == null
                    ? spec.bindNull("$" + (b + 4), String.class)
                    : spec.bind("$" + (b + 4), d.principal());
        }
        return spec.fetch().rowsUpdated().then();
    }

    private static <T> java.util.List<java.util.List<T>> chunks(java.util.List<T> all, int size) {
        java.util.List<java.util.List<T>> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i += size) out.add(all.subList(i, Math.min(all.size(), i + size)));
        return out;
    }

    @Override
    public Flux<PushDelivery> deliveries(Topic topic, String eventId, int limit) {
        long numeric;
        try {
            numeric = parse(eventId);
        } catch (IllegalArgumentException e) {
            return Flux.empty();
        }
        return db.sql(deliveriesOf)
                .bind("$1", topic.toString())
                .bind("$2", numeric)
                .bind("$3", limit)
                .map(row -> {
                    OffsetDateTime time = row.get("time", OffsetDateTime.class);
                    return new PushDelivery(
                            topic,
                            eventId,
                            row.get("connection_id", String.class),
                            row.get("principal", String.class),
                            row.get("transport", String.class),
                            time == null ? Instant.EPOCH : time.toInstant(),
                            row.get("outcome", String.class));
                })
                .all();
    }

    @Override
    public Mono<Map<String, Long>> deliveryCounts(Topic topic, String eventId) {
        long numeric;
        try {
            numeric = parse(eventId);
        } catch (IllegalArgumentException e) {
            return Mono.just(Map.of());
        }
        return db.sql(deliveryCounts)
                .bind("$1", topic.toString())
                .bind("$2", numeric)
                .map(row -> Map.entry(row.get("outcome", String.class), row.get("n", Long.class)))
                .all()
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    @Override
    public boolean deliveryLog() {
        return true;
    }

    /** One upsert: the row is written when the key is free, expired, or already this holder's. */
    @Override
    public Mono<Boolean> lease(String key, String holder, Duration ttl) {
        return db.sql(lease)
                .bind("$1", key)
                .bind("$2", holder)
                .bind("$3", OffsetDateTime.now(ZoneOffset.UTC).plus(ttl))
                .fetch()
                .rowsUpdated()
                .map(n -> n > 0);
    }

    @Override
    public Mono<Void> release(String key, String holder) {
        return db.sql(releaseLease)
                .bind("$1", key)
                .bind("$2", holder)
                .fetch()
                .rowsUpdated()
                .then();
    }

    // ---- the connection registry ----

    private static final int REGISTER_CHUNK = 500;

    @Override
    public boolean connectionRegistry() {
        return true;
    }

    @Override
    public Mono<Void> registerConnections(List<ConnectionRecord> records, Duration ttl) {
        if (records.isEmpty()) return Mono.empty();
        // One row per id (the last wins): a multi-row upsert cannot touch the same row twice.
        Map<String, ConnectionRecord> byId = new java.util.LinkedHashMap<>();
        for (ConnectionRecord r : records) byId.put(r.id(), r);
        List<ConnectionRecord> unique = new ArrayList<>(byId.values());
        OffsetDateTime expires = OffsetDateTime.now(ZoneOffset.UTC).plus(ttl);
        return Flux.range(0, (unique.size() + REGISTER_CHUNK - 1) / REGISTER_CHUNK)
                .concatMap(i -> upsertConnections(
                        unique.subList(i * REGISTER_CHUNK, Math.min(unique.size(), (i + 1) * REGISTER_CHUNK)), expires))
                .then();
    }

    private Mono<Long> upsertConnections(List<ConnectionRecord> chunk, OffsetDateTime expires) {
        StringBuilder sql = new StringBuilder(
                "INSERT INTO " + connectionTable
                        + " (id, instance, principal, transport, device_id, since, topics, presence_topics, expires_at, peer_address) VALUES ");
        for (int i = 0; i < chunk.size(); i++) {
            int b = i * 10;
            if (i > 0) sql.append(", ");
            sql.append("($").append(b + 1);
            for (int c = 2; c <= 10; c++) {
                sql.append(", $").append(b + c);
                if (c == 7 || c == 8) sql.append("::jsonb");
            }
            sql.append(")");
        }
        sql.append(" ON CONFLICT (id) DO UPDATE SET instance = EXCLUDED.instance, principal = EXCLUDED.principal,"
                + " transport = EXCLUDED.transport, device_id = EXCLUDED.device_id, since = EXCLUDED.since,"
                + " topics = EXCLUDED.topics, presence_topics = EXCLUDED.presence_topics,"
                + " expires_at = EXCLUDED.expires_at, peer_address = EXCLUDED.peer_address");
        DatabaseClient.GenericExecuteSpec spec = db.sql(sql.toString());
        for (int i = 0; i < chunk.size(); i++) {
            ConnectionRecord r = chunk.get(i);
            int b = i * 10;
            spec = spec.bind("$" + (b + 1), r.id());
            spec = bindText(spec, "$" + (b + 2), r.instance());
            spec = bindText(spec, "$" + (b + 3), r.principal());
            spec = bindText(spec, "$" + (b + 4), r.transport());
            spec = bindText(spec, "$" + (b + 5), r.deviceId());
            spec = spec.bind("$" + (b + 6), r.since().atOffset(ZoneOffset.UTC));
            spec = spec.bind("$" + (b + 7), stringsJson(r.topics()));
            spec = spec.bind("$" + (b + 8), stringsJson(r.presenceTopics()));
            spec = spec.bind("$" + (b + 9), expires);
            spec = bindText(spec, "$" + (b + 10), r.peerAddress());
        }
        return spec.fetch().rowsUpdated();
    }

    private static DatabaseClient.GenericExecuteSpec bindText(
            DatabaseClient.GenericExecuteSpec spec, String name, String value) {
        return value == null ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }

    @Override
    public Mono<Void> unregisterConnection(String id) {
        return db.sql("DELETE FROM " + connectionTable + " WHERE id = $1")
                .bind("$1", id)
                .fetch()
                .rowsUpdated()
                .then();
    }

    private static final String CONNECTION_COLUMNS =
            "id, instance, principal, transport, device_id, since, topics::text AS topics,"
                    + " presence_topics::text AS presence_topics, peer_address";

    @Override
    public Flux<ConnectionRecord> connections(String principal, int limit) {
        String sql = "SELECT " + CONNECTION_COLUMNS + " FROM " + connectionTable + " WHERE expires_at > now()"
                + (principal == null ? "" : " AND principal = $2")
                + " ORDER BY since, id LIMIT $1";
        DatabaseClient.GenericExecuteSpec spec = db.sql(sql).bind("$1", Math.max(0, limit));
        if (principal != null) spec = spec.bind("$2", principal);
        return spec.map(PostgresPushStore::connectionRow).all();
    }

    @Override
    public Mono<ConnectionRecord> connection(String id) {
        return db.sql("SELECT " + CONNECTION_COLUMNS + " FROM " + connectionTable
                        + " WHERE id = $1 AND expires_at > now()")
                .bind("$1", id)
                .map(PostgresPushStore::connectionRow)
                .one();
    }

    @Override
    public Flux<ConnectionRecord> connectionsOn(Topic topic, int offset, int limit) {
        return db.sql("SELECT " + CONNECTION_COLUMNS + " FROM " + connectionTable
                        + " WHERE expires_at > now() AND presence_topics @> $1::jsonb"
                        + " ORDER BY since, id LIMIT $2 OFFSET $3")
                .bind("$1", stringsJson(List.of(topic.toString())))
                .bind("$2", Math.max(0, limit))
                .bind("$3", Math.max(0, offset))
                .map(PostgresPushStore::connectionRow)
                .all();
    }

    @Override
    public Mono<Long> countConnectionsOn(Topic topic) {
        return db.sql("SELECT count(*) AS n FROM " + connectionTable
                        + " WHERE expires_at > now() AND presence_topics @> $1::jsonb")
                .bind("$1", stringsJson(List.of(topic.toString())))
                .map(row -> row.get("n", Long.class))
                .one();
    }

    /** Deletes the registry rows whose lease lapsed; part of {@link #sweep()}. */
    public Mono<Long> sweepConnections() {
        return db.sql(sweepConnections).fetch().rowsUpdated();
    }

    private static ConnectionRecord connectionRow(Readable row) {
        OffsetDateTime since = row.get("since", OffsetDateTime.class);
        return new ConnectionRecord(
                row.get("id", String.class),
                row.get("instance", String.class),
                row.get("principal", String.class),
                row.get("transport", String.class),
                row.get("device_id", String.class),
                since == null ? Instant.now() : since.toInstant(),
                strings(row.get("topics", String.class)),
                strings(row.get("presence_topics", String.class)),
                row.get("peer_address", String.class));
    }

    private static String stringsJson(List<String> values) {
        try {
            return JSON.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static List<String> strings(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return JSON.readValue(json, STRINGS);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("bad connection column: " + json, e);
        }
    }

    @Override
    public Capabilities capabilities() {
        return new Capabilities(true, false);
    }

    // ---- the archive ----

    /** Never called by the gateway: the history row written by {@link #append(PushEvent, int, Duration)} is the archive row. */
    @Override
    public Mono<Void> append(PushEvent event) {
        return Mono.empty();
    }

    @Override
    public Flux<Entry> search(Query query) {
        if (!archiveOn()) return Flux.empty();
        List<String> where = new ArrayList<>();
        List<Function<DatabaseClient.GenericExecuteSpec, DatabaseClient.GenericExecuteSpec>> binds = new ArrayList<>();
        TopicPattern pattern = null;
        if (query.topic() != null) {
            if (query.exactTopic()) {
                where.add("topic = " + param(binds, query.topic()));
            } else {
                pattern = query.topicPattern();
                where.add("topic LIKE " + param(binds, likePrefix(query.topic())));
            }
        }
        if (query.from() != null)
            where.add("time >= " + param(binds, query.from().atOffset(ZoneOffset.UTC)));
        if (query.to() != null) where.add("time < " + param(binds, query.to().atOffset(ZoneOffset.UTC)));
        if (query.afterId() != null) where.add("id > " + param(binds, parse(query.afterId())));
        if (query.beforeId() != null) where.add("id < " + param(binds, parse(query.beforeId())));
        if (!query.attributes().isEmpty()) {
            where.add("attributes @> " + param(binds, attributesJson(query.attributes())) + "::jsonb");
        }
        if (query.typeUrl() != null) {
            where.add(
                    "json".equals(query.typeUrl())
                            ? "type_url IS NULL"
                            : "type_url = " + param(binds, query.typeUrl()));
        }
        if (query.text() != null) {
            where.add("to_tsvector('simple', data) @@ plainto_tsquery('simple', " + param(binds, query.text()) + ")");
        }
        // A wildcard topic is narrowed by its literal prefix in SQL and matched exactly here, so the
        // SQL limit is widened to leave room for rows the prefix lets through and the pattern rejects.
        int sqlLimit = pattern == null ? query.limit() : Math.max(1000, query.limit() * 10);
        String sql = "SELECT " + COLUMNS + " FROM " + table
                + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where))
                + " ORDER BY id DESC LIMIT " + param(binds, sqlLimit);
        DatabaseClient.GenericExecuteSpec spec = db.sql(sql);
        for (var bind : binds) spec = bind.apply(spec);
        Flux<Entry> rows = spec.map(PostgresPushStore::entry).all();
        if (pattern == null) return rows;
        TopicPattern p = pattern;
        return rows.filter(e -> p.matches(e.event().topic())).take(query.limit());
    }

    @Override
    public Mono<Entry> get(Topic topic, String id) {
        long numeric;
        try {
            numeric = parse(id);
        } catch (IllegalArgumentException e) {
            return Mono.empty();
        }
        return db.sql(get)
                .bind("$1", numeric)
                .bind("$2", topic.toString())
                .map(PostgresPushStore::entry)
                .one();
    }

    @Override
    public Mono<Boolean> tombstone(Topic topic, String id, String by) {
        long numeric;
        try {
            numeric = parse(id);
        } catch (IllegalArgumentException e) {
            return Mono.just(false);
        }
        DatabaseClient.GenericExecuteSpec spec =
                db.sql(tombstone).bind("$1", numeric).bind("$2", topic.toString());
        spec = by == null ? spec.bindNull("$3", String.class) : spec.bind("$3", by);
        return spec.fetch().rowsUpdated().map(n -> n > 0);
    }

    @Override
    public SearchCapabilities searchCapabilities() {
        return new SearchCapabilities(archiveOn(), true, true, retention);
    }

    /** Registers one positional parameter and returns its placeholder. */
    private static String param(
            List<Function<DatabaseClient.GenericExecuteSpec, DatabaseClient.GenericExecuteSpec>> binds, Object value) {
        String name = "$" + (binds.size() + 1);
        binds.add(spec -> spec.bind(name, value));
        return name;
    }

    /** The literal prefix of a topic glob as a LIKE pattern: {@code orders:eu/*} gives {@code orders:eu/%}. */
    static String likePrefix(String glob) {
        String t = glob.indexOf(':') < 0 ? glob + ":" : glob;
        int cut = t.length();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '*' || c == '{') {
                cut = i;
                break;
            }
        }
        String literal =
                t.substring(0, cut).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return literal + "%";
    }

    static long parse(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not an event id: '" + id + "'");
        }
    }

    /**
     * Milliseconds times a thousand plus a counter, negated by the caller: numeric like a table id,
     * never one of them, and told apart by its sign ({@link #isStored}).
     */
    private long localId() {
        return System.currentTimeMillis() * 1000 + (localSequence.incrementAndGet() % 1000);
    }

    static PushEvent event(Readable row) {
        Long id = row.get("id", Long.class);
        String attributes = row.get("attributes", String.class);
        OffsetDateTime time = row.get("time", OffsetDateTime.class);
        byte[] proto = bytes(row.get("proto"));
        return new PushEvent(
                Topic.parse(row.get("topic", String.class)),
                id == null ? null : Long.toString(id),
                row.get("data", String.class).getBytes(StandardCharsets.UTF_8),
                attributes(attributes),
                row.get("key", String.class),
                time == null ? Instant.now() : time.toInstant(),
                proto == null ? null : row.get("type_url", String.class),
                proto);
    }

    static Entry entry(Readable row) {
        OffsetDateTime tombstonedAt = row.get("tombstoned_at", OffsetDateTime.class);
        return new Entry(
                event(row),
                tombstonedAt == null ? null : tombstonedAt.toInstant(),
                row.get("tombstoned_by", String.class));
    }

    private static byte[] bytes(Object value) {
        if (value == null) return null;
        if (value instanceof byte[] b) return b;
        if (value instanceof ByteBuffer buf) {
            byte[] out = new byte[buf.remaining()];
            buf.duplicate().get(out);
            return out;
        }
        throw new IllegalStateException("unexpected bytea value " + value.getClass());
    }

    private static String attributesJson(Map<String, String> attributes) {
        try {
            return JSON.writeValueAsString(attributes == null ? Map.of() : attributes);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static Map<String, String> attributes(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return JSON.readValue(json, ATTRIBUTES);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("bad attributes column: " + json, e);
        }
    }
}
