package dev.appiaworks.shiguchi.bff.session;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.bff.BffProperties;
import dev.appiaworks.shiguchi.bff.session.jdbc.JdbcBffSchema;
import dev.appiaworks.shiguchi.bff.session.jdbc.JdbcBffSessionStore;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** The contract on the JDBC store; a subclass picks the database. Every {@link #store()} gets its own tables. */
abstract class JdbcBffSessionStoreTestBase extends BffSessionStoreContract {

    private static final AtomicInteger TABLES = new AtomicInteger();

    final TestClock clock = new TestClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final String prefix = "t" + TABLES.incrementAndGet() + "_" + System.nanoTime() % 100_000 + "_";
    private JdbcBffSessionStore store;
    private DataSource dataSource;

    abstract DataSource newDataSource();

    abstract String name();

    @Override
    BffSessionStore store() {
        dataSource = newDataSource();
        store = new JdbcBffSessionStore(dataSource, config(), clock);
        store.afterPropertiesSet();
        return store;
    }

    BffProperties.Jdbc config() {
        BffProperties.Jdbc config = new BffProperties.Jdbc();
        config.setTablePrefix(name() + "_" + prefix);
        config.setSweepInterval(Duration.ZERO);
        return config;
    }

    @Override
    void advance(Duration d) {
        clock.advance(d);
    }

    @AfterEach
    void close() {
        if (store != null) store.destroy();
    }

    private int count(String table) {
        return new JdbcTemplate(dataSource).queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    @Test
    @DisplayName("GIVEN expired rows WHEN swept SHOULD delete them, logout-token records included, and keep live ones")
    void given_expired_rows_when_swept_should_keep_live_ones() {
        BffSessionStore s = store();
        s.save(session("old"), ttl()).block();
        s.save(session("new"), LONG).block();
        s.saveLogin(login("old"), ttl()).block();
        s.saveLogin(login("new"), LONG).block();
        s.markJtiSeen("google", "old", ttl()).block();
        s.markJtiSeen("google", "new", LONG).block();
        advance(ttl().plusSeconds(1));
        store.sweep().block();

        assertThat(count(name() + "_" + prefix + "session")).isEqualTo(1);
        assertThat(count(name() + "_" + prefix + "login")).isEqualTo(1);
        assertThat(count(name() + "_" + prefix + "jti")).isEqualTo(1);
        assertThat(s.find("new").blockOptional()).isPresent();
    }

    @Test
    @DisplayName("GIVEN existing tables WHEN the schema runs again SHOULD be idempotent")
    void given_existing_tables_when_schema_runs_again_should_not_fail() {
        BffSessionStore first = store();
        first.save(session("k"), LONG).block();
        new JdbcBffSessionStore(dataSource, config(), clock).afterPropertiesSet();

        assertThat(first.find("k").blockOptional()).isPresent();
    }

    @Test
    @DisplayName(
            "GIVEN tables made before the lease columns existed WHEN the schema runs SHOULD add them and keep the rows")
    void given_tables_without_lease_columns_when_schema_runs_should_add_them() {
        dataSource = newDataSource();
        JdbcBffSchema.Dialect dialect = JdbcBffSchema.Dialect.resolve(dataSource, null);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        JdbcBffSchema.statements(dialect, config().getTablePrefix())
                .subList(0, 4)
                .forEach(jdbc::execute);
        jdbc.update(
                "INSERT INTO " + name() + "_" + prefix + "session (session_key, subject, provider, application,"
                        + " created, last_seen, version, expires_at) VALUES ('old', 's', 'p', 'a', 1, 1, 1, ?)",
                clock.millis() + 600_000);

        store = new JdbcBffSessionStore(dataSource, config(), clock);
        store.afterPropertiesSet();

        assertThat(store.find("old").blockOptional()).isPresent();
        assertThat(store.find("old").block().acr()).isNull();
        assertThat(store.find("old").block().amr()).isEmpty();
        assertThat(store.find("old").block().authTime()).isNull();
        assertThat(store.find("old").block().claims()).isEmpty();
        assertThat(store.find("old").block().userAgent()).isNull();
        assertThat(store.tryLease("old", "a", LONG).block()).isTrue();
        assertThat(store.tryLease("old", "b", LONG).block()).isFalse();
    }
}
