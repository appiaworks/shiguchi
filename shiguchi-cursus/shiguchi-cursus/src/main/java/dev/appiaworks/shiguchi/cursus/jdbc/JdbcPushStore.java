package dev.appiaworks.shiguchi.cursus.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.appiaworks.protobuf.extras.api.ApiStatus;
import dev.appiaworks.shiguchi.cursus.PushArchive;
import dev.appiaworks.shiguchi.cursus.PushDelivery;
import dev.appiaworks.shiguchi.cursus.PushEvent;
import dev.appiaworks.shiguchi.cursus.PushProperties;
import dev.appiaworks.shiguchi.cursus.PushStore;
import dev.appiaworks.shiguchi.cursus.Topic;
import dev.appiaworks.shiguchi.cursus.TopicPattern;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The store and archive on any SQL database ({@link JdbcPushSchema}): the same model as the
 * Postgres store, in portable SQL over a blocking {@link DataSource}, every call on
 * {@code boundedElastic} ({@link JdbcIo}).
 *
 * <p>An append mints its id under the row lock of a one-row counter table and inserts in the same
 * transaction, so ids commit in order across every instance (a poller that reads {@code id > last}
 * never skips a row). A topic's history is trimmed to {@code history-size} every
 * {@code trim-every} appends, and rows past {@code history-ttl} are swept every
 * {@code sweep-interval}. A topic with no history gets a local id of the same numeric shape but
 * negative, which nobody resumes from and which names no row.
 *
 * <p>With {@code archive.retention} above zero the same table is the archive: every event gets a
 * row (a topic without history too, marked out of the history from the start), trimming marks
 * rows out of the history instead of deleting them, and the sweep deletes a row only once it is
 * out of both the history and the archive window. A tombstone redacts the payload in place and
 * takes the row out of the history. Search is range, attribute and payload {@code LIKE}.
 *
 * <p>Consumer-group claims are optimistic updates, not {@code FOR UPDATE SKIP LOCKED}: a member
 * reads candidate rows, then takes each with an {@code UPDATE ... WHERE claimed_at = <what it
 * read>}; the row count says whether it won. Not every database has {@code SKIP LOCKED}, and the
 * optimistic form behaves the same everywhere.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.12.0")
public final class JdbcPushStore implements PushStore, PushArchive, InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(JdbcPushStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, String>> ATTRIBUTES = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
    static final String COLUMNS =
            "id, topic, data, attributes, msg_key, event_time, type_url, proto, tombstoned_at, tombstoned_by";
    private static final String IN_HISTORY = " AND in_history = 1 AND tombstoned_at IS NULL";
    private static final String ESCAPE = " ESCAPE '!'";
    /** The claimed_by of a staged or released group row: nobody holds it. */
    private static final String UNCLAIMED = "~unclaimed~";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JdbcDialect dialect;
    private final PushProperties.Jdbc config;
    private final Duration retention;
    private final String table;
    private final String seqTable;
    private final String leaseTable;
    private final String deliveryTable;
    private final String sessionTable;
    private final String groupTable;
    private final String connectionTable;
    private final String trimTable;
    private final ConcurrentHashMap<Topic, AtomicInteger> appendsSinceTrim = new ConcurrentHashMap<>();
    private final AtomicLong localSequence = new AtomicLong();
    private volatile Disposable sweeper;
    private volatile boolean started;

    public JdbcPushStore(DataSource dataSource, PushProperties.Jdbc config) {
        this(dataSource, config, new PushProperties.Archive());
    }

    public JdbcPushStore(DataSource dataSource, PushProperties.Jdbc config, PushProperties.Archive archive) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.config = config;
        this.dialect = JdbcDialect.resolve(dataSource, config);
        Duration r = archive == null ? null : archive.getRetention();
        this.retention = r == null || r.isNegative() ? Duration.ZERO : r;
        this.table = JdbcPushSchema.table(config.getTable());
        this.seqTable = JdbcPushSchema.sequenceTable(table);
        this.leaseTable = JdbcPushSchema.leaseTable(table);
        this.deliveryTable = JdbcPushSchema.deliveryTable(table);
        this.sessionTable = JdbcPushSchema.sessionTable(table);
        this.groupTable = JdbcPushSchema.groupTable(table);
        this.connectionTable = JdbcPushSchema.connectionTable(table);
        this.trimTable = JdbcPushSchema.trimTable(table);
    }

    /** True when the table doubles as the archive ({@code archive.retention} above zero). */
    public boolean archiveOn() {
        return !retention.isZero();
    }

    /** The dialect in use, detected or forced. */
    public JdbcDialect dialect() {
        return dialect;
    }

    /**
     * Creates the tables when asked to, and starts the sweeper. Once: with the archive on this
     * object is also the {@code PushArchive} bean, and Spring runs the init callbacks per bean.
     */
    @Override
    public void afterPropertiesSet() {
        if (started) return;
        started = true;
        if (config.isCreateSchema()) {
            for (String sql : JdbcPushSchema.statements(dialect, table, archiveOn())) {
                try {
                    jdbc.execute(sql);
                } catch (DuplicateKeyException e) {
                    // two instances seeding the counter row at once
                } catch (DataAccessException e) {
                    if (!dialect.isAlreadyExists(e)) throw e;
                }
            }
            log.info("Push: JDBC table {} ready ({}{})", table, dialect, archiveOn() ? ", archive " + retention : "");
        }
        Duration every = config.getSweepInterval();
        if (every != null && !every.isZero() && !every.isNegative()) {
            sweeper = Flux.interval(every, every, Schedulers.parallel())
                    .concatMap(t -> sweep().onErrorResume(e -> {
                        log.warn("Push: JDBC history sweep failed: {}", e.toString());
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

    private static long now() {
        return System.currentTimeMillis();
    }

    private static String likeEscape(String s) {
        return s.replace("!", "!!").replace("%", "!%").replace("_", "!_").replace("[", "![");
    }

    // ---- history ----

    @Override
    public Mono<PushEvent> append(PushEvent event, int historySize, Duration historyTtl) {
        boolean history = historySize > 0;
        if (!history && !archiveOn()) return Mono.just(event.withId(Long.toString(-localId())));
        long time = event.time().toEpochMilli();
        Long expires = !history || historyTtl == null || historyTtl.isZero() || historyTtl.isNegative()
                ? null
                : Long.valueOf(time + historyTtl.toMillis());
        Long archivedUntil = archiveOn() ? Long.valueOf(time + retention.toMillis()) : null;
        String insert = "INSERT INTO " + table
                + " (id, topic, data, attributes, msg_key, event_time, expires_at, type_url, proto, in_history,"
                + " archived_until) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        return JdbcIo.mono(() -> {
                    Long id = tx.execute(status -> {
                        jdbc.update("UPDATE " + seqTable + " SET last_id = last_id + 1 WHERE id = 1");
                        Long next =
                                jdbc.queryForObject("SELECT last_id FROM " + seqTable + " WHERE id = 1", Long.class);
                        jdbc.update(insert, ps -> {
                            ps.setLong(1, next);
                            ps.setString(2, event.topic().toString());
                            ps.setString(3, new String(event.data(), StandardCharsets.UTF_8));
                            ps.setString(4, attributesJson(event.attributes()));
                            ps.setString(5, event.key());
                            ps.setLong(6, time);
                            ps.setObject(7, expires, Types.BIGINT);
                            ps.setString(8, event.typed() ? event.typeUrl() : null);
                            if (event.typed()) ps.setBytes(9, event.proto());
                            else ps.setNull(9, dialect.bytesSqlType());
                            ps.setInt(10, history ? 1 : 0);
                            ps.setObject(11, archivedUntil, Types.BIGINT);
                        });
                        return next;
                    });
                    return event.withId(Long.toString(id));
                })
                .doOnSuccess(stored -> {
                    if (history && stored != null) maybeTrim(stored.topic(), historySize);
                });
    }

    /** Every {@code trim-every} appends to a topic, drop from the history what is older than its newest {@code historySize} rows. */
    private void maybeTrim(Topic topic, int historySize) {
        int every = Math.max(1, config.getTrimEvery());
        AtomicInteger n = appendsSinceTrim.computeIfAbsent(topic, t -> new AtomicInteger());
        if (n.incrementAndGet() < every) return;
        n.set(0);
        JdbcIo.run(() -> trimNow(topic, historySize))
                .subscribe(v -> {}, e -> log.warn("Push: JDBC trim of {} failed: {}", topic, e.toString()));
    }

    private void trimNow(Topic topic, int historySize) {
        List<Long> boundary = jdbc.query(
                "SELECT id FROM " + table + " WHERE topic = ? AND in_history = 1 ORDER BY id DESC"
                        + dialect.page(Math.max(0, historySize - 1), 1),
                (rs, i) -> rs.getLong(1),
                topic.toString());
        if (boundary.isEmpty()) return;
        String older = " WHERE topic = ? AND in_history = 1 AND id < ?";
        if (archiveOn()) {
            // The rows stay, out of the history: trimmedThrough reads them.
            jdbc.update("UPDATE " + table + " SET in_history = 0" + older, topic.toString(), boundary.get(0));
            return;
        }
        tx.executeWithoutResult(status -> {
            recordTrimmed(older, topic.toString(), boundary.get(0));
            jdbc.update("DELETE FROM " + table + older, topic.toString(), boundary.get(0));
        });
    }

    /** A watermark nobody has touched this long, on a topic with no events left, is forgotten. */
    static final Duration WATERMARK_KEEP = Duration.ofDays(7);

    /**
     * Before rows are physically deleted: per topic, raises the watermark to the highest id among the
     * rows {@code where} selects that were not redacted (a tombstone is not a gap). Run in the
     * transaction of the delete, with the same predicate and arguments.
     */
    private void recordTrimmed(String where, Object... args) {
        List<Map.Entry<String, Long>> maxima = jdbc.query(
                "SELECT topic, max(id) FROM " + table + where + " AND tombstoned_at IS NULL GROUP BY topic",
                (rs, i) -> Map.entry(rs.getString(1), rs.getLong(2)),
                args);
        long at = now();
        String update = "UPDATE " + trimTable
                + " SET trimmed_through = CASE WHEN trimmed_through < ? THEN ? ELSE trimmed_through END,"
                + " updated_at = ? WHERE topic = ?";
        for (Map.Entry<String, Long> m : maxima) {
            if (jdbc.update(update, m.getValue(), m.getValue(), at, m.getKey()) > 0) continue;
            try {
                jdbc.update(
                        "INSERT INTO " + trimTable + " (topic, trimmed_through, updated_at) VALUES (?, ?, ?)",
                        m.getKey(),
                        m.getValue(),
                        at);
            } catch (DuplicateKeyException e) {
                jdbc.update(update, m.getValue(), m.getValue(), at, m.getKey());
            }
        }
    }

    @Override
    public Mono<String> trimmedThrough(Topic topic) {
        return JdbcIo.mono(() -> {
            Long marked = first(jdbc.query(
                    "SELECT trimmed_through FROM " + trimTable + " WHERE topic = ?",
                    (rs, i) -> rs.getLong(1),
                    topic.toString()));
            Long kept = jdbc.queryForObject(
                    "SELECT max(id) FROM " + table + " WHERE topic = ? AND tombstoned_at IS NULL"
                            + " AND (in_history = 0 OR (expires_at IS NOT NULL AND expires_at <= ?))",
                    Long.class,
                    topic.toString(),
                    now());
            long best = Math.max(marked == null ? 0 : marked, kept == null ? 0 : kept);
            return best <= 0 ? null : Long.toString(best);
        });
    }

    /** Deletes rows out of the history (and, with the archive on, out of its window too); the sweeper calls this on its interval. */
    public Mono<Long> sweep() {
        return JdbcIo.mono(() -> {
            long now = now();
            String outOfHistory =
                    "(in_history = 0 OR tombstoned_at IS NOT NULL" + " OR (expires_at IS NOT NULL AND expires_at < ?))";
            String where = archiveOn()
                    ? " WHERE (archived_until IS NULL OR archived_until < ?) AND " + outOfHistory
                    : " WHERE " + outOfHistory;
            Object[] args = archiveOn() ? new Object[] {now, now} : new Object[] {now};
            Long deleted = tx.execute(status -> {
                recordTrimmed(where, args);
                return (long) jdbc.update("DELETE FROM " + table + where, args);
            });
            long n = deleted == null ? 0 : deleted;
            jdbc.update(
                    "DELETE FROM " + trimTable + " WHERE updated_at < ? AND NOT EXISTS (SELECT 1 FROM " + table
                            + " e WHERE e.topic = " + trimTable + ".topic)",
                    now - WATERMARK_KEEP.toMillis());
            jdbc.update("DELETE FROM " + deliveryTable + " WHERE NOT EXISTS (SELECT 1 FROM " + table
                    + " e WHERE e.id = " + deliveryTable + ".event_id)");
            jdbc.update("DELETE FROM " + sessionTable + " WHERE expires_at < ?", now);
            jdbc.update("DELETE FROM " + connectionTable + " WHERE expires_at < ?", now);
            jdbc.update("DELETE FROM " + groupTable + " WHERE NOT EXISTS (SELECT 1 FROM " + table + " e WHERE e.id = "
                    + groupTable + ".event_id)");
            return n;
        });
    }

    @Override
    public Flux<PushEvent> range(Topic topic, String afterId, int limit) {
        long after = afterId == null ? 0 : parse(afterId);
        if (limit <= 0) return Flux.empty();
        return JdbcIo.flux(() -> jdbc.query(
                "SELECT " + COLUMNS + " FROM " + table
                        + " WHERE topic = ? AND id > ? AND (expires_at IS NULL OR expires_at > ?)" + IN_HISTORY
                        + " ORDER BY id" + dialect.page(0, limit),
                EVENT,
                topic.toString(),
                after,
                now()));
    }

    /** One kept event by id, for a notification that carried only the id. */
    public Mono<PushEvent> byId(String id) {
        long numeric = parse(id);
        return JdbcIo.mono(
                () -> first(jdbc.query("SELECT " + COLUMNS + " FROM " + table + " WHERE id = ?", EVENT, numeric)));
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
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE topic = ? AND (expires_at IS NULL OR expires_at > ?)"
                        + IN_HISTORY,
                Long.class,
                topic.toString(),
                now());
        return n == null ? 0 : n;
    }

    @Override
    public Mono<PushEvent> headEvent(Topic topic) {
        return JdbcIo.mono(() -> first(jdbc.query(
                "SELECT " + COLUMNS + " FROM " + table + " WHERE topic = ? AND in_history = 1"
                        + " AND tombstoned_at IS NULL ORDER BY id DESC" + dialect.page(0, 1),
                EVENT,
                topic.toString())));
    }

    @Override
    public Mono<String> headId(Topic topic) {
        return JdbcIo.mono(() -> headIdNow(topic));
    }

    private String headIdNow(Topic topic) {
        Long id = jdbc.queryForObject(
                "SELECT max(id) FROM " + table + " WHERE topic = ? AND in_history = 1", Long.class, topic.toString());
        return id == null ? null : Long.toString(id);
    }

    @Override
    public Mono<Boolean> redact(Topic topic, String id) {
        // The tombstone already took the row out of the history: one table, one row.
        return Mono.just(false);
    }

    // ---- sessions ----

    @Override
    public boolean persistentSessions() {
        return true;
    }

    @Override
    public Mono<String> cursor(String session, Topic topic) {
        return JdbcIo.mono(() -> cursorNow(session, topic));
    }

    private String cursorNow(String session, Topic topic) {
        Long id = first(jdbc.query(
                "SELECT last_ack_id FROM " + sessionTable + " WHERE session_key = ? AND topic = ? AND expires_at > ?",
                (rs, i) -> rs.getLong(1),
                session,
                topic.toString(),
                now()));
        return id == null ? null : Long.toString(id);
    }

    @Override
    public Mono<Void> ack(String session, Topic topic, String id, Duration expiry) {
        long numeric;
        try {
            numeric = parse(id);
        } catch (IllegalArgumentException e) {
            return Mono.error(e);
        }
        return JdbcIo.run(() -> ackNow(session, topic, numeric, expiry));
    }

    /** Moves the cursor forward only: a late ack of an older id never moves it back. */
    private void ackNow(String session, Topic topic, long id, Duration expiry) {
        Duration ttl = expiry == null ? Duration.ofDays(365) : expiry;
        long expires = now() + ttl.toMillis();
        String update = "UPDATE " + sessionTable
                + " SET last_ack_id = CASE WHEN last_ack_id < ? THEN ? ELSE last_ack_id END, expires_at = ?"
                + " WHERE session_key = ? AND topic = ?";
        if (jdbc.update(update, id, id, expires, session, topic.toString()) > 0) return;
        try {
            jdbc.update(
                    "INSERT INTO " + sessionTable
                            + " (session_key, topic, last_ack_id, expires_at) VALUES (?, ?, ?, ?)",
                    session,
                    topic.toString(),
                    id,
                    expires);
        } catch (DuplicateKeyException e) {
            jdbc.update(update, id, id, expires, session, topic.toString());
        }
    }

    @Override
    public Flux<SessionCursor> sessions(String prefix, int limit) {
        if (limit <= 0) return Flux.empty();
        String like = (prefix == null ? "" : likeEscape(prefix)) + "%";
        return JdbcIo.flux(() -> jdbc.query(
                "SELECT session_key, topic, last_ack_id, expires_at FROM " + sessionTable
                        + " WHERE session_key LIKE ?" + ESCAPE + " AND expires_at > ? ORDER BY session_key, topic"
                        + dialect.page(0, limit),
                (rs, i) -> new SessionCursor(
                        rs.getString("session_key"),
                        Topic.parse(rs.getString("topic")),
                        Long.toString(rs.getLong("last_ack_id")),
                        Instant.ofEpochMilli(rs.getLong("expires_at"))),
                like,
                now()));
    }

    @Override
    public Mono<Long> dropSession(String session) {
        return JdbcIo.mono(() -> (long) jdbc.update("DELETE FROM " + sessionTable + " WHERE session_key = ?", session));
    }

    @Override
    public Mono<Long> sweepSessions() {
        return JdbcIo.mono(() -> (long) jdbc.update("DELETE FROM " + sessionTable + " WHERE expires_at < ?", now()));
    }

    @Override
    public Mono<Boolean> seekSession(String session, Topic topic, String id) {
        long numeric;
        try {
            numeric = parse(id);
        } catch (IllegalArgumentException e) {
            return Mono.just(false);
        }
        return JdbcIo.mono(() -> seekNow(session, topic, numeric));
    }

    private boolean seekNow(String session, Topic topic, long id) {
        return jdbc.update(
                        "UPDATE " + sessionTable
                                + " SET last_ack_id = ? WHERE session_key = ? AND topic = ? AND expires_at > ?",
                        id,
                        session,
                        topic.toString(),
                        now())
                > 0;
    }

    // ---- consumer groups ----

    @Override
    public boolean consumerGroups() {
        return true;
    }

    @Override
    public boolean keyOrdering() {
        return true;
    }

    private static String groupSession(String group) {
        return "group:" + group;
    }

    @Override
    public Flux<Claimed> claim(
            String group, Topic topic, String member, int max, Duration ackDeadline, Duration expiry) {
        if (max <= 0) return Flux.empty();
        return JdbcIo.flux(() -> claimNow(group, topic, member, max, ackDeadline, expiry, false));
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
        return JdbcIo.flux(() -> claimNow(group, topic, member, max, ackDeadline, expiry, true));
    }

    private record Candidate(long eventId, int deliveries, long claimedAt, String key) {}

    private List<Claimed> claimNow(
            String group, Topic topic, String member, int max, Duration ackDeadline, Duration expiry, boolean keyed) {
        long now = now();
        // A claim older than this is up for grabs; no ack deadline means a claim never lapses.
        long threshold = ackDeadline == null ? Long.MIN_VALUE : now - Math.max(1, ackDeadline.toMillis());
        String session = groupSession(group);
        String c = cursorNow(session, topic);
        if (c == null) {
            c = headIdNow(topic);
            if (c == null) c = firstId();
        }
        long cursor = parse(c);
        List<long[]> got = new ArrayList<>();
        if (keyed) {
            // New events are staged as unclaimed rows so the cursor can move past them, then handed
            // out by the reclaim below, which skips a key another member holds live.
            long newest = cursor;
            for (Object[] e : eventsAfter(topic, cursor, 1000, now)) {
                long id = (Long) e[0];
                if (insertClaim(group, topic, id, UNCLAIMED, 0L, 0, (String) e[1])) newest = Math.max(newest, id);
            }
            ackNow(session, topic, newest, expiry);
            got.addAll(reclaim(group, topic, member, max, threshold, now, true));
        } else {
            got.addAll(reclaim(group, topic, member, max, threshold, now, false));
            int room = max - got.size();
            if (room > 0) {
                long newest = cursor;
                for (Object[] e : eventsAfter(topic, cursor, room, now)) {
                    long id = (Long) e[0];
                    if (insertClaim(group, topic, id, member, now, 1, null)) {
                        got.add(new long[] {id, 1});
                        newest = Math.max(newest, id);
                    }
                }
                ackNow(session, topic, newest, expiry);
            }
        }
        return withEvents(got);
    }

    /** Kept events after {@code cursor}, oldest first: each is {@code {id, key}}. */
    private List<Object[]> eventsAfter(Topic topic, long cursor, int limit, long now) {
        return jdbc.query(
                "SELECT id, msg_key FROM " + table
                        + " WHERE topic = ? AND id > ? AND (expires_at IS NULL OR expires_at > ?)" + IN_HISTORY
                        + " ORDER BY id" + dialect.page(0, limit),
                (rs, i) -> new Object[] {rs.getLong(1), rs.getString(2)},
                topic.toString(),
                cursor,
                now);
    }

    /** False when the row exists already: another member (or instance) staged or claimed that event. */
    private boolean insertClaim(
            String group, Topic topic, long id, String member, long at, int deliveries, String key) {
        try {
            jdbc.update(
                    "INSERT INTO " + groupTable
                            + " (group_name, topic, event_id, claimed_by, claimed_at, deliveries, msg_key)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    group,
                    topic.toString(),
                    id,
                    member,
                    at,
                    deliveries,
                    key);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * Takes up to {@code max} claims whose deadline lapsed, oldest event first. Each is an
     * optimistic update guarded by the {@code claimed_at} that was read, so of two members racing
     * for a row only one update matches. With {@code keyed}, a row whose key another member holds
     * live is not a candidate, and a claim is handed back when, after the update, another member
     * is found holding that key live (two members racing for siblings both back off and retry).
     */
    private List<long[]> reclaim(
            String group, Topic topic, String member, int max, long threshold, long now, boolean keyed) {
        int fetch = (int) Math.min(2L * max, 100_000);
        List<Candidate> candidates;
        RowMapper<Candidate> mapper = (rs, i) -> new Candidate(
                rs.getLong("event_id"), rs.getInt("deliveries"), rs.getLong("claimed_at"), rs.getString("msg_key"));
        if (keyed) {
            candidates = jdbc.query(
                    "SELECT x.event_id, x.deliveries, x.claimed_at, x.msg_key FROM " + groupTable
                            + " x WHERE x.group_name = ? AND x.topic = ? AND x.claimed_at < ?"
                            + " AND (x.msg_key IS NULL OR NOT EXISTS (SELECT 1 FROM " + groupTable
                            + " y WHERE y.group_name = x.group_name AND y.topic = x.topic AND y.msg_key = x.msg_key"
                            + " AND y.claimed_by <> ? AND y.claimed_at >= ?))"
                            + " ORDER BY x.event_id" + dialect.page(0, fetch),
                    mapper,
                    group,
                    topic.toString(),
                    threshold,
                    member,
                    threshold);
        } else {
            candidates = jdbc.query(
                    "SELECT event_id, deliveries, claimed_at, msg_key FROM " + groupTable
                            + " WHERE group_name = ? AND topic = ? AND claimed_at < ? ORDER BY event_id"
                            + dialect.page(0, fetch),
                    mapper,
                    group,
                    topic.toString(),
                    threshold);
        }
        List<long[]> won = new ArrayList<>();
        for (Candidate c : candidates) {
            if (won.size() >= max) break;
            int n = jdbc.update(
                    "UPDATE " + groupTable + " SET claimed_by = ?, claimed_at = ?, deliveries = deliveries + 1"
                            + " WHERE group_name = ? AND topic = ? AND event_id = ? AND claimed_at = ?",
                    member,
                    now,
                    group,
                    topic.toString(),
                    c.eventId(),
                    c.claimedAt());
            if (n == 0) continue;
            if (keyed && c.key() != null) {
                Integer rivals = jdbc.queryForObject(
                        "SELECT count(*) FROM " + groupTable
                                + " WHERE group_name = ? AND topic = ? AND msg_key = ? AND claimed_by <> ?"
                                + " AND claimed_at >= ?",
                        Integer.class,
                        group,
                        topic.toString(),
                        c.key(),
                        member,
                        threshold);
                if (rivals != null && rivals > 0) {
                    jdbc.update(
                            "UPDATE " + groupTable + " SET claimed_by = ?, claimed_at = 0, deliveries = deliveries - 1"
                                    + " WHERE group_name = ? AND topic = ? AND event_id = ? AND claimed_by = ?"
                                    + " AND claimed_at = ?",
                            UNCLAIMED,
                            group,
                            topic.toString(),
                            c.eventId(),
                            member,
                            now);
                    continue;
                }
            }
            won.add(new long[] {c.eventId(), c.deliveries() + 1});
        }
        return won;
    }

    /** The claimed rows with their events; a row whose event is gone is dropped. */
    private List<Claimed> withEvents(List<long[]> rows) {
        if (rows.isEmpty()) return List.of();
        Map<Long, Integer> deliveries = new HashMap<>();
        List<Long> ids = new ArrayList<>();
        for (long[] r : rows) {
            deliveries.put(r[0], (int) r[1]);
            ids.add(r[0]);
        }
        List<PushEvent> events = new ArrayList<>();
        for (int from = 0; from < ids.size(); from += 500) {
            List<Long> chunk = ids.subList(from, Math.min(ids.size(), from + 500));
            events.addAll(jdbc.query(
                    "SELECT " + COLUMNS + " FROM " + table + " WHERE id IN (" + placeholders(chunk.size()) + ")",
                    EVENT,
                    chunk.toArray()));
        }
        events.sort((a, b) -> Long.compare(parse(a.id()), parse(b.id())));
        List<Claimed> out = new ArrayList<>(events.size());
        for (PushEvent e : events) out.add(new Claimed(e, deliveries.getOrDefault(Long.parseLong(e.id()), 1)));
        return out;
    }

    private static String placeholders(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(i == 0 ? "?" : ", ?");
        return sb.toString();
    }

    @Override
    public Mono<Boolean> seekGroup(String group, Topic topic, String id) {
        long numeric;
        try {
            numeric = parse(id);
        } catch (IllegalArgumentException e) {
            return Mono.just(false);
        }
        return JdbcIo.mono(() -> {
            if (!seekNow(groupSession(group), topic, numeric)) return false;
            jdbc.update("DELETE FROM " + groupTable + " WHERE group_name = ? AND topic = ?", group, topic.toString());
            return true;
        });
    }

    @Override
    public Mono<Boolean> ackGroup(String group, Topic topic, String member, String id) {
        long numeric;
        try {
            numeric = parse(id);
        } catch (IllegalArgumentException e) {
            return Mono.just(false);
        }
        return JdbcIo.mono(() -> jdbc.update(
                        "DELETE FROM " + groupTable
                                + " WHERE group_name = ? AND topic = ? AND event_id = ? AND claimed_by = ?",
                        group,
                        topic.toString(),
                        numeric,
                        member)
                > 0);
    }

    @Override
    public Mono<Long> releaseClaims(String group, Topic topic, String member) {
        return JdbcIo.mono(() -> (long) jdbc.update(
                "UPDATE " + groupTable + " SET claimed_at = 0 WHERE group_name = ? AND topic = ? AND claimed_by = ?",
                group,
                topic.toString(),
                member));
    }

    @Override
    public Flux<GroupStatus> groups(String prefix, int limit) {
        if (limit <= 0) return Flux.empty();
        String like = "group:" + (prefix == null ? "" : likeEscape(prefix)) + "%";
        return JdbcIo.flux(() -> {
            List<String[]> sessions = jdbc.query(
                    "SELECT session_key, topic, last_ack_id FROM " + sessionTable + " WHERE session_key LIKE ?" + ESCAPE
                            + " AND expires_at > ? ORDER BY session_key, topic" + dialect.page(0, limit),
                    (rs, i) -> new String[] {rs.getString(1), rs.getString(2), Long.toString(rs.getLong(3))},
                    like,
                    now());
            List<GroupStatus> out = new ArrayList<>(sessions.size());
            for (String[] s : sessions) {
                String group = s[0].substring("group:".length());
                Long pending = jdbc.queryForObject(
                        "SELECT count(*) FROM " + groupTable + " WHERE group_name = ? AND topic = ?",
                        Long.class,
                        group,
                        s[1]);
                Long members = jdbc.queryForObject(
                        "SELECT count(DISTINCT claimed_by) FROM " + groupTable
                                + " WHERE group_name = ? AND topic = ? AND claimed_by <> ?",
                        Long.class,
                        group,
                        s[1],
                        UNCLAIMED);
                out.add(new GroupStatus(
                        group, Topic.parse(s[1]), pending == null ? 0 : pending, members == null ? 0 : members, s[2]));
            }
            return out;
        });
    }

    @Override
    public Mono<Long> dropGroup(String group) {
        return JdbcIo.mono(() -> {
            jdbc.update("DELETE FROM " + groupTable + " WHERE group_name = ?", group);
            return (long) jdbc.update("DELETE FROM " + sessionTable + " WHERE session_key = ?", groupSession(group));
        });
    }

    // ---- delivery log ----

    /** One batch insert per hundred records. */
    @Override
    public Mono<Void> recordDeliveries(List<PushDelivery> batch) {
        if (batch.isEmpty()) return Mono.empty();
        return JdbcIo.run(() -> {
            String sql = "INSERT INTO " + deliveryTable
                    + " (topic, event_id, connection_id, principal, transport, delivered_at, outcome)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?)";
            for (int from = 0; from < batch.size(); from += 100) {
                List<PushDelivery> chunk = batch.subList(from, Math.min(batch.size(), from + 100));
                jdbc.batchUpdate(sql, chunk, chunk.size(), (ps, d) -> {
                    long eventId;
                    try {
                        eventId = parse(d.eventId());
                    } catch (IllegalArgumentException e) {
                        eventId = 0;
                    }
                    ps.setString(1, d.topic().toString());
                    ps.setLong(2, eventId);
                    ps.setString(3, d.connectionId());
                    ps.setString(4, d.principal());
                    ps.setString(5, d.transport());
                    ps.setLong(6, d.time().toEpochMilli());
                    ps.setString(7, d.outcome());
                });
            }
        });
    }

    @Override
    public Flux<PushDelivery> deliveries(Topic topic, String eventId, int limit) {
        long numeric;
        try {
            numeric = parse(eventId);
        } catch (IllegalArgumentException e) {
            return Flux.empty();
        }
        if (limit <= 0) return Flux.empty();
        return JdbcIo.flux(() -> jdbc.query(
                "SELECT connection_id, principal, transport, delivered_at, outcome FROM " + deliveryTable
                        + " WHERE topic = ? AND event_id = ? ORDER BY delivered_at" + dialect.page(0, limit),
                (rs, i) -> new PushDelivery(
                        topic,
                        eventId,
                        rs.getString("connection_id"),
                        rs.getString("principal"),
                        rs.getString("transport"),
                        Instant.ofEpochMilli(rs.getLong("delivered_at")),
                        rs.getString("outcome")),
                topic.toString(),
                numeric));
    }

    @Override
    public Mono<Map<String, Long>> deliveryCounts(Topic topic, String eventId) {
        long numeric;
        try {
            numeric = parse(eventId);
        } catch (IllegalArgumentException e) {
            return Mono.just(Map.of());
        }
        return JdbcIo.mono(() -> {
            Map<String, Long> out = new LinkedHashMap<>();
            jdbc.query(
                    "SELECT outcome, count(*) FROM " + deliveryTable
                            + " WHERE topic = ? AND event_id = ? GROUP BY outcome",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> out.put(rs.getString(1), rs.getLong(2)),
                    topic.toString(),
                    numeric);
            return out;
        });
    }

    @Override
    public boolean deliveryLog() {
        return true;
    }

    // ---- leases ----

    /** The row is written when the key is free, expired, or already this holder's. */
    @Override
    public Mono<Boolean> lease(String key, String holder, Duration ttl) {
        return JdbcIo.mono(() -> {
            long now = now();
            long expires = now + ttl.toMillis();
            int n = jdbc.update(
                    "UPDATE " + leaseTable + " SET holder = ?, expires_at = ?"
                            + " WHERE lease_key = ? AND (holder = ? OR expires_at < ?)",
                    holder,
                    expires,
                    key,
                    holder,
                    now);
            if (n > 0) return true;
            try {
                jdbc.update(
                        "INSERT INTO " + leaseTable + " (lease_key, holder, expires_at) VALUES (?, ?, ?)",
                        key,
                        holder,
                        expires);
                return true;
            } catch (DuplicateKeyException e) {
                return false;
            }
        });
    }

    @Override
    public Mono<Void> release(String key, String holder) {
        return JdbcIo.run(
                () -> jdbc.update("DELETE FROM " + leaseTable + " WHERE lease_key = ? AND holder = ?", key, holder));
    }

    // ---- the connection registry ----

    private static final int REGISTER_CHUNK = 500;
    private static final String CONNECTION_COLUMNS =
            "id, instance_id, principal, transport, device_id, since_at, topics, presence_topics, peer_address";

    @Override
    public boolean connectionRegistry() {
        return true;
    }

    @Override
    public Mono<Void> registerConnections(List<ConnectionRecord> records, Duration ttl) {
        if (records.isEmpty()) return Mono.empty();
        // One row per id (the last wins).
        Map<String, ConnectionRecord> byId = new LinkedHashMap<>();
        for (ConnectionRecord r : records) byId.put(r.id(), r);
        List<ConnectionRecord> unique = new ArrayList<>(byId.values());
        long expires = now() + ttl.toMillis();
        return JdbcIo.run(() -> {
            for (int from = 0; from < unique.size(); from += REGISTER_CHUNK) {
                List<ConnectionRecord> chunk = unique.subList(from, Math.min(unique.size(), from + REGISTER_CHUNK));
                writeConnections(chunk, expires);
            }
        });
    }

    /**
     * Replaces the chunk's rows in one transaction. Two flushes of the same ids can both delete
     * nothing and both insert; the loser's insert violates the key, and a second round (which now
     * deletes the winner's rows) succeeds.
     */
    private void writeConnections(List<ConnectionRecord> chunk, long expires) {
        for (int attempt = 1; ; attempt++) {
            try {
                tx.executeWithoutResult(status -> {
                    jdbc.update(
                            "DELETE FROM " + connectionTable + " WHERE id IN (" + placeholders(chunk.size()) + ")",
                            chunk.stream().map(ConnectionRecord::id).toArray());
                    jdbc.batchUpdate(
                            "INSERT INTO " + connectionTable + " (" + CONNECTION_COLUMNS
                                    + ", expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            chunk,
                            chunk.size(),
                            (ps, r) -> {
                                ps.setString(1, r.id());
                                ps.setString(2, r.instance());
                                ps.setString(3, r.principal());
                                ps.setString(4, r.transport());
                                ps.setString(5, r.deviceId());
                                ps.setLong(6, r.since().toEpochMilli());
                                ps.setString(7, stringsJson(r.topics()));
                                ps.setString(8, stringsJson(r.presenceTopics()));
                                ps.setString(9, r.peerAddress());
                                ps.setLong(10, expires);
                            });
                });
                return;
            } catch (DuplicateKeyException e) {
                if (attempt >= 3) throw e;
            }
        }
    }

    @Override
    public Mono<Void> unregisterConnection(String id) {
        return JdbcIo.run(() -> jdbc.update("DELETE FROM " + connectionTable + " WHERE id = ?", id));
    }

    @Override
    public Flux<ConnectionRecord> connections(String principal, int limit) {
        if (limit <= 0) return Flux.empty();
        return JdbcIo.flux(() -> principal == null
                ? jdbc.query(
                        "SELECT " + CONNECTION_COLUMNS + " FROM " + connectionTable
                                + " WHERE expires_at > ? ORDER BY since_at, id" + dialect.page(0, limit),
                        CONNECTION,
                        now())
                : jdbc.query(
                        "SELECT " + CONNECTION_COLUMNS + " FROM " + connectionTable
                                + " WHERE expires_at > ? AND principal = ? ORDER BY since_at, id"
                                + dialect.page(0, limit),
                        CONNECTION,
                        now(),
                        principal));
    }

    @Override
    public Mono<ConnectionRecord> connection(String id) {
        return JdbcIo.mono(() -> first(jdbc.query(
                "SELECT " + CONNECTION_COLUMNS + " FROM " + connectionTable + " WHERE id = ? AND expires_at > ?",
                CONNECTION,
                id,
                now())));
    }

    @Override
    public Flux<ConnectionRecord> connectionsOn(Topic topic, int offset, int limit) {
        if (limit <= 0) return Flux.empty();
        return JdbcIo.flux(() -> jdbc.query(
                "SELECT " + CONNECTION_COLUMNS + " FROM " + connectionTable
                        + " WHERE expires_at > ? AND presence_topics LIKE ?" + ESCAPE
                        + " ORDER BY since_at, id" + dialect.page(Math.max(0, offset), limit),
                CONNECTION,
                now(),
                presenceLike(topic)));
    }

    @Override
    public Mono<Long> countConnectionsOn(Topic topic) {
        return JdbcIo.mono(() -> jdbc.queryForObject(
                "SELECT count(*) FROM " + connectionTable + " WHERE expires_at > ? AND presence_topics LIKE ?" + ESCAPE,
                Long.class,
                now(),
                presenceLike(topic)));
    }

    /** The stored list holds each topic as a JSON string, so its quoted form names exactly one element. */
    private static String presenceLike(Topic topic) {
        return "%" + likeEscape(jsonString(topic.toString())) + "%";
    }

    /** Deletes the registry rows whose lease lapsed; part of {@link #sweep()}. */
    public Mono<Long> sweepConnections() {
        return JdbcIo.mono(() -> (long) jdbc.update("DELETE FROM " + connectionTable + " WHERE expires_at < ?", now()));
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
        List<Object> args = new ArrayList<>();
        TopicPattern pattern = null;
        if (query.topic() != null) {
            if (query.exactTopic()) {
                where.add("topic = ?");
                args.add(query.topic());
            } else {
                pattern = query.topicPattern();
                where.add("topic LIKE ?" + ESCAPE);
                args.add(likePrefix(query.topic()));
            }
        }
        if (query.from() != null) {
            where.add("event_time >= ?");
            args.add(query.from().toEpochMilli());
        }
        if (query.to() != null) {
            where.add("event_time < ?");
            args.add(query.to().toEpochMilli());
        }
        if (query.afterId() != null) {
            where.add("id > ?");
            args.add(parse(query.afterId()));
        }
        if (query.beforeId() != null) {
            where.add("id < ?");
            args.add(parse(query.beforeId()));
        }
        for (Map.Entry<String, String> a : query.attributes().entrySet()) {
            where.add("attributes LIKE ?" + ESCAPE);
            args.add("%" + likeEscape(jsonString(a.getKey()) + ":" + jsonString(a.getValue())) + "%");
        }
        if (query.typeUrl() != null) {
            if ("json".equals(query.typeUrl())) {
                where.add("type_url IS NULL");
            } else {
                where.add("type_url = ?");
                args.add(query.typeUrl());
            }
        }
        if (query.text() != null) {
            where.add("LOWER(data) LIKE ?" + ESCAPE);
            args.add("%" + likeEscape(query.text().toLowerCase(Locale.ROOT)) + "%");
        }
        // A wildcard topic is narrowed by its literal prefix in SQL and matched exactly here, so the
        // SQL limit is widened to leave room for rows the prefix lets through and the pattern rejects.
        int sqlLimit = Math.max(1, pattern == null ? query.limit() : Math.max(1000, query.limit() * 10));
        String sql = "SELECT " + COLUMNS + " FROM " + table
                + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where))
                + " ORDER BY id DESC" + dialect.page(0, sqlLimit);
        Flux<Entry> rows = JdbcIo.flux(() -> jdbc.query(sql, ENTRY, args.toArray()));
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
        return JdbcIo.mono(() -> first(jdbc.query(
                "SELECT " + COLUMNS + " FROM " + table + " WHERE id = ? AND topic = ?",
                ENTRY,
                numeric,
                topic.toString())));
    }

    @Override
    public Mono<Boolean> tombstone(Topic topic, String id, String by) {
        long numeric;
        try {
            numeric = parse(id);
        } catch (IllegalArgumentException e) {
            return Mono.just(false);
        }
        return JdbcIo.mono(() -> jdbc.update(
                        "UPDATE " + table + " SET data = 'null', proto = NULL, type_url = NULL,"
                                + " tombstoned_at = ?, tombstoned_by = ? WHERE id = ? AND topic = ?"
                                + " AND tombstoned_at IS NULL",
                        now(),
                        by,
                        numeric,
                        topic.toString())
                > 0);
    }

    @Override
    public SearchCapabilities searchCapabilities() {
        return new SearchCapabilities(archiveOn(), true, true, retention);
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
        return likeEscape(t.substring(0, cut)) + "%";
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

    private static <T> T first(List<T> rows) {
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ---- rows ----

    private static final RowMapper<PushEvent> EVENT = (rs, i) -> event(rs);
    private static final RowMapper<Entry> ENTRY = (rs, i) -> {
        long tombstonedAt = rs.getLong("tombstoned_at");
        Instant at = rs.wasNull() ? null : Instant.ofEpochMilli(tombstonedAt);
        return new Entry(event(rs), at, rs.getString("tombstoned_by"));
    };
    private static final RowMapper<ConnectionRecord> CONNECTION = (rs, i) -> new ConnectionRecord(
            rs.getString("id"),
            rs.getString("instance_id"),
            rs.getString("principal"),
            rs.getString("transport"),
            rs.getString("device_id"),
            Instant.ofEpochMilli(rs.getLong("since_at")),
            strings(rs.getString("topics")),
            strings(rs.getString("presence_topics")),
            rs.getString("peer_address"));

    static PushEvent event(ResultSet rs) throws SQLException {
        byte[] proto = rs.getBytes("proto");
        return new PushEvent(
                Topic.parse(rs.getString("topic")),
                Long.toString(rs.getLong("id")),
                rs.getString("data").getBytes(StandardCharsets.UTF_8),
                attributes(rs.getString("attributes")),
                rs.getString("msg_key"),
                Instant.ofEpochMilli(rs.getLong("event_time")),
                proto == null ? null : rs.getString("type_url"),
                proto);
    }

    private static String jsonString(String value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
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
