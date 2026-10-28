package dev.appiaworks.shiguchi.bff.session.jdbc;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import dev.appiaworks.shiguchi.bff.BffProperties;
import dev.appiaworks.shiguchi.bff.session.BffLogin;
import dev.appiaworks.shiguchi.bff.session.BffSession;
import dev.appiaworks.shiguchi.bff.session.BffSessionClaims;
import dev.appiaworks.shiguchi.bff.session.BffSessionStore;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Sessions and logins in two SQL tables ({@link JdbcBffSchema}), over a blocking
 * {@link DataSource}: every call runs on {@code boundedElastic}, never on an event-loop thread.
 * Portable SQL only (single-row {@code UPDATE}/{@code INSERT}/{@code DELETE}, no {@code MERGE}, no
 * {@code RETURNING}), so a dialect that misbehaves is fixed in {@link JdbcBffSchema.Dialect}.
 *
 * <p>Expiry is a column, {@code expires_at} (epoch milliseconds from the injected {@link Clock}),
 * and every read and {@code touch} filters on it, so an expired row is invisible at once. Expired
 * rows are deleted by a sweep every {@code sweep-interval} (default one minute), not on write.
 * Instants keep millisecond precision.
 *
 * <p>{@code save} is update-then-insert (retrying the update if a concurrent insert wins).
 * {@code takeLogin} reads the row, then deletes it by key and returns it only if its own
 * {@code DELETE} removed a row: two instances racing for one {@code state} read the same row but
 * the database lets exactly one delete succeed, with no row lock or transaction needed.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.13.0")
public final class JdbcBffSessionStore implements BffSessionStore, InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(JdbcBffSessionStore.class);

    private static final String SESSION_COLUMNS = "session_key, subject, provider, application, sid, name, email,"
            + " picture, access_token, access_token_expires_at, refresh_token, id_token, created, last_seen, version,"
            + " acr, amr, auth_time, claims, user_agent, expires_at";
    private static final String LOGIN_COLUMNS =
            "state, code_verifier, nonce, provider, application, return_to, redirect_uri, created, expires_at, max_age";

    private final JdbcTemplate jdbc;
    private final JdbcBffSchema.Dialect dialect;
    private final BffProperties.Jdbc config;
    private final Clock clock;
    private final String sessions;
    private final String logins;
    private final String jtis;
    private volatile Disposable sweeper;
    private volatile boolean started;

    public JdbcBffSessionStore(DataSource dataSource, BffProperties.Jdbc config) {
        this(dataSource, config, Clock.systemUTC());
    }

    /** @param clock the time source for {@code expires_at}, for tests */
    public JdbcBffSessionStore(DataSource dataSource, BffProperties.Jdbc config, Clock clock) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.config = config;
        this.clock = clock;
        this.dialect = JdbcBffSchema.Dialect.resolve(dataSource, config.getDialect());
        this.sessions = JdbcBffSchema.sessionTable(config.getTablePrefix());
        this.logins = JdbcBffSchema.loginTable(config.getTablePrefix());
        this.jtis = JdbcBffSchema.jtiTable(config.getTablePrefix());
    }

    /** Creates the tables when asked to, and starts the sweep. Once. */
    @Override
    public void afterPropertiesSet() {
        if (started) return;
        started = true;
        if (config.isCreateSchema()) {
            for (String sql : JdbcBffSchema.statements(dialect, config.getTablePrefix())) {
                try {
                    jdbc.execute(sql);
                } catch (DataAccessException e) {
                    if (!dialect.isAlreadyExists(e)) throw e;
                }
            }
            log.info("BFF: JDBC tables {}, {}, {} ready ({})", sessions, logins, jtis, dialect);
        }
        Duration every = config.getSweepInterval();
        if (every != null && !every.isZero() && !every.isNegative()) {
            sweeper = Flux.interval(every, every, Schedulers.parallel())
                    .concatMap(t -> sweep().onErrorResume(e -> {
                        log.warn("BFF: JDBC session sweep failed: {}", e.toString());
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

    /** Deletes every expired session, login and logout-token record. Runs periodically; public so tests need not wait. */
    public Mono<Void> sweep() {
        return io(() -> {
            long now = clock.millis();
            jdbc.update("DELETE FROM " + sessions + " WHERE expires_at <= ?", now);
            jdbc.update("DELETE FROM " + logins + " WHERE expires_at <= ?", now);
            jdbc.update("DELETE FROM " + jtis + " WHERE expires_at <= ?", now);
            return null;
        });
    }

    @Override
    public Mono<Void> save(BffSession s, Duration ttl) {
        return io(() -> {
            long expires = expiresAt(ttl);
            if (updateSession(s, expires) == 0) {
                try {
                    jdbc.update(
                            "INSERT INTO " + sessions + " (" + SESSION_COLUMNS
                                    + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            ps -> {
                                ps.setString(1, s.key());
                                bindSession(ps, 2, s, expires);
                            });
                } catch (DuplicateKeyException e) {
                    updateSession(s, expires);
                }
            }
            return null;
        });
    }

    private int updateSession(BffSession s, long expires) {
        return jdbc.update(
                "UPDATE " + sessions + " SET subject = ?, provider = ?, application = ?, sid = ?, name = ?,"
                        + " email = ?, picture = ?, access_token = ?, access_token_expires_at = ?,"
                        + " refresh_token = ?, id_token = ?, created = ?, last_seen = ?, version = ?,"
                        + " acr = ?, amr = ?, auth_time = ?, claims = ?, user_agent = ?, expires_at = ? WHERE session_key = ?",
                ps -> {
                    bindSession(ps, 1, s, expires);
                    ps.setString(21, s.key());
                });
    }

    /** Binds the twenty columns after the key, starting at {@code i}. */
    private static void bindSession(PreparedStatement ps, int i, BffSession s, long expires) throws SQLException {
        ps.setLong(bindFields(ps, i, s), expires);
    }

    /** Binds the nineteen columns subject..user_agent, starting at {@code i}; returns the next index. */
    private static int bindFields(PreparedStatement ps, int i, BffSession s) throws SQLException {
        ps.setString(i++, s.subject());
        ps.setString(i++, s.provider());
        ps.setString(i++, s.application());
        ps.setString(i++, s.sid());
        ps.setString(i++, s.name());
        ps.setString(i++, s.email());
        ps.setString(i++, s.picture());
        ps.setString(i++, s.accessToken());
        setMillis(ps, i++, s.accessTokenExpiresAt());
        ps.setString(i++, s.refreshToken());
        ps.setString(i++, s.idToken());
        setMillis(ps, i++, s.created());
        setMillis(ps, i++, s.lastSeen());
        ps.setLong(i++, s.version());
        ps.setString(i++, s.acr());
        ps.setString(i++, s.amr().isEmpty() ? null : String.join(" ", s.amr()));
        setMillis(ps, i++, s.authTime());
        ps.setString(i++, BffSessionClaims.toJson(s.claims()));
        ps.setString(i++, s.userAgent());
        return i;
    }

    @Override
    public Mono<BffSession> find(String key) {
        return io(() -> jdbc
                .query(
                        "SELECT " + SESSION_COLUMNS + " FROM " + sessions + " WHERE session_key = ? AND expires_at > ?",
                        (rs, n) -> session(rs),
                        key,
                        clock.millis())
                .stream()
                .findFirst()
                .orElse(null));
    }

    @Override
    public Mono<Void> touch(String key, Instant lastSeen, Duration ttl) {
        return io(() -> {
            jdbc.update(
                    "UPDATE " + sessions
                            + " SET last_seen = ?, expires_at = ? WHERE session_key = ? AND expires_at > ?",
                    lastSeen.toEpochMilli(),
                    expiresAt(ttl),
                    key,
                    clock.millis());
            return null;
        });
    }

    @Override
    public Mono<Boolean> tryLease(String key, String owner, Duration ttl) {
        return io(() -> {
            long now = clock.millis();
            return jdbc.update(
                            "UPDATE " + sessions + " SET lease_owner = ?, lease_until = ? WHERE session_key = ?"
                                    + " AND expires_at > ? AND (lease_owner IS NULL OR lease_until <= ?)",
                            owner,
                            now + Math.max(1, ttl.toMillis()),
                            key,
                            now,
                            now)
                    == 1;
        });
    }

    @Override
    public Mono<Void> releaseLease(String key, String owner) {
        return io(() -> {
            jdbc.update(
                    "UPDATE " + sessions
                            + " SET lease_owner = NULL, lease_until = NULL WHERE session_key = ? AND lease_owner = ?",
                    key,
                    owner);
            return null;
        });
    }

    /** Compare-and-set on {@code version}; leaves {@code expires_at} and the lease columns alone. */
    @Override
    public Mono<Boolean> replaceIfVersion(BffSession s, long expectedVersion) {
        return io(() -> jdbc.update(
                        "UPDATE " + sessions + " SET subject = ?, provider = ?, application = ?, sid = ?, name = ?,"
                                + " email = ?, picture = ?, access_token = ?, access_token_expires_at = ?,"
                                + " refresh_token = ?, id_token = ?, created = ?, last_seen = ?, version = ?,"
                                + " acr = ?, amr = ?, auth_time = ?, claims = ?, user_agent = ?"
                                + " WHERE session_key = ? AND version = ? AND expires_at > ?",
                        ps -> {
                            ps.setString(bindFields(ps, 1, s), s.key());
                            ps.setLong(21, expectedVersion);
                            ps.setLong(22, clock.millis());
                        })
                == 1);
    }

    @Override
    public Mono<Void> delete(String key) {
        return io(() -> {
            jdbc.update("DELETE FROM " + sessions + " WHERE session_key = ?", key);
            return null;
        });
    }

    @Override
    public Flux<BffSession> findBySubject(String provider, String subject) {
        return Mono.fromCallable(() -> jdbc.query(
                        "SELECT " + SESSION_COLUMNS + " FROM " + sessions
                                + " WHERE provider = ? AND subject = ? AND expires_at > ?",
                        (rs, n) -> session(rs),
                        provider,
                        subject,
                        clock.millis()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable);
    }

    @Override
    public Mono<List<String>> deleteBySid(String provider, String sid) {
        return deleteWhere("provider = ? AND sid = ?", provider, sid);
    }

    @Override
    public Mono<List<String>> deleteBySubject(String provider, String subject) {
        return deleteWhere("provider = ? AND subject = ?", provider, subject);
    }

    /**
     * Reads the matching keys, then deletes each by key and reports only those whose own
     * {@code DELETE} removed a row: two instances racing for one session see disjoint answers.
     */
    private Mono<List<String>> deleteWhere(String where, String provider, String value) {
        return io(() -> {
            long now = clock.millis();
            record Row(String key, long expiresAt) {}
            List<Row> rows = jdbc.query(
                    "SELECT session_key, expires_at FROM " + sessions + " WHERE " + where,
                    (rs, n) -> new Row(rs.getString("session_key"), rs.getLong("expires_at")),
                    provider,
                    value);
            List<String> deleted = new ArrayList<>();
            for (Row row : rows) {
                int removed = jdbc.update("DELETE FROM " + sessions + " WHERE session_key = ?", row.key());
                if (removed == 1 && row.expiresAt() > now) deleted.add(row.key());
            }
            return deleted;
        });
    }

    @Override
    public Mono<Boolean> markJtiSeen(String provider, String jti, Duration ttl) {
        return io(() -> {
            String key = provider + ":" + jti;
            long now = clock.millis();
            // An expired record of the same jti does not count; the sweep may not have run yet.
            jdbc.update("DELETE FROM " + jtis + " WHERE jti_key = ? AND expires_at <= ?", key, now);
            try {
                return jdbc.update(
                                "INSERT INTO " + jtis + " (jti_key, expires_at) VALUES (?, ?)",
                                key,
                                now + Math.max(1, ttl.toMillis()))
                        == 1;
            } catch (DuplicateKeyException e) {
                return false;
            }
        });
    }

    @Override
    public Mono<Void> forgetJti(String provider, String jti) {
        return io(() -> {
            jdbc.update("DELETE FROM " + jtis + " WHERE jti_key = ?", provider + ":" + jti);
            return null;
        });
    }

    @Override
    public Mono<Void> saveLogin(BffLogin l, Duration ttl) {
        return io(() -> {
            long expires = expiresAt(ttl);
            if (updateLogin(l, expires) == 0) {
                try {
                    jdbc.update(
                            "INSERT INTO " + logins + " (" + LOGIN_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            ps -> {
                                ps.setString(1, l.state());
                                bindLogin(ps, 2, l, expires);
                            });
                } catch (DuplicateKeyException e) {
                    updateLogin(l, expires);
                }
            }
            return null;
        });
    }

    private int updateLogin(BffLogin l, long expires) {
        return jdbc.update(
                "UPDATE " + logins + " SET code_verifier = ?, nonce = ?, provider = ?, application = ?,"
                        + " return_to = ?, redirect_uri = ?, created = ?, expires_at = ?, max_age = ? WHERE state = ?",
                ps -> {
                    bindLogin(ps, 1, l, expires);
                    ps.setString(10, l.state());
                });
    }

    /** Binds the nine columns after the state, starting at {@code i}. */
    private static void bindLogin(PreparedStatement ps, int i, BffLogin l, long expires) throws SQLException {
        ps.setString(i++, l.codeVerifier());
        ps.setString(i++, l.nonce());
        ps.setString(i++, l.provider());
        ps.setString(i++, l.application());
        ps.setString(i++, l.returnTo());
        ps.setString(i++, l.redirectUri());
        setMillis(ps, i++, l.created());
        ps.setLong(i++, expires);
        if (l.maxAge() == null) ps.setNull(i, Types.BIGINT);
        else ps.setLong(i, l.maxAge());
    }

    @Override
    public Mono<BffLogin> takeLogin(String state) {
        return io(() -> {
            record Row(BffLogin login, long expiresAt) {}
            Row row = jdbc
                    .query(
                            "SELECT " + LOGIN_COLUMNS + " FROM " + logins + " WHERE state = ?",
                            (rs, n) -> new Row(login(rs), rs.getLong("expires_at")),
                            state)
                    .stream()
                    .findFirst()
                    .orElse(null);
            if (row == null) return null;
            // Only the caller whose DELETE removed the row owns the login.
            int deleted = jdbc.update("DELETE FROM " + logins + " WHERE state = ?", state);
            return deleted == 1 && row.expiresAt() > clock.millis() ? row.login() : null;
        });
    }

    private long expiresAt(Duration ttl) {
        return clock.millis() + Math.max(1, ttl.toMillis());
    }

    private static void setMillis(PreparedStatement ps, int i, Instant t) throws SQLException {
        if (t == null) ps.setNull(i, Types.BIGINT);
        else ps.setLong(i, t.toEpochMilli());
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : Instant.ofEpochMilli(v);
    }

    private static BffSession session(ResultSet rs) throws SQLException {
        return new BffSession(
                rs.getString("session_key"),
                rs.getString("subject"),
                rs.getString("provider"),
                rs.getString("application"),
                rs.getString("sid"),
                rs.getString("name"),
                rs.getString("email"),
                rs.getString("picture"),
                rs.getString("access_token"),
                instant(rs, "access_token_expires_at"),
                rs.getString("refresh_token"),
                rs.getString("id_token"),
                instant(rs, "created"),
                instant(rs, "last_seen"),
                rs.getLong("version"),
                rs.getString("acr"),
                amr(rs.getString("amr")),
                instant(rs, "auth_time"),
                BffSessionClaims.fromJson(rs.getString("claims")),
                rs.getString("user_agent"));
    }

    /** Space-separated in the column; an absent column value is no methods. */
    private static List<String> amr(String joined) {
        if (joined == null || joined.isBlank()) return List.of();
        return List.of(joined.trim().split("\s+"));
    }

    private static BffLogin login(ResultSet rs) throws SQLException {
        return new BffLogin(
                rs.getString("state"),
                rs.getString("code_verifier"),
                rs.getString("nonce"),
                rs.getString("provider"),
                rs.getString("application"),
                rs.getString("return_to"),
                rs.getString("redirect_uri"),
                instant(rs, "created"),
                maxAge(rs));
    }

    private static Long maxAge(ResultSet rs) throws SQLException {
        long v = rs.getLong("max_age");
        return rs.wasNull() ? null : v;
    }

    /** Blocking work on {@code boundedElastic}; a null result is an empty Mono. */
    private static <T> Mono<T> io(Callable<T> work) {
        return Mono.fromCallable(work).subscribeOn(Schedulers.boundedElastic());
    }
}
