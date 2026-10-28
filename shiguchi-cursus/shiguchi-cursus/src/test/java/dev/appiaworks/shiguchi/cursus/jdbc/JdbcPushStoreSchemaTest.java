package dev.appiaworks.shiguchi.cursus.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.appiaworks.shiguchi.cursus.PushEvent;
import dev.appiaworks.shiguchi.cursus.PushProperties;
import dev.appiaworks.shiguchi.cursus.Topic;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class JdbcPushStoreSchemaTest {

    private JdbcTestDatabase database;
    private DataSource dataSource;

    abstract JdbcTestDatabase database();

    @BeforeAll
    void start() {
        database = database();
        dataSource = database.dataSource(getClass().getSimpleName());
    }

    private PushProperties.Jdbc config(String base) {
        PushProperties.Jdbc config = new PushProperties.Jdbc();
        config.setTable(database.table(base));
        config.setSweepInterval(Duration.ZERO);
        return config;
    }

    @Test
    @DisplayName("GIVEN a created schema WHEN a second store starts SHOULD not fail and keep the counter row single")
    void given_createdSchema_when_secondStoreStarts_should_beIdempotent() {
        PushProperties.Jdbc config = config("push_event_schema_a");
        JdbcPushStore first = new JdbcPushStore(dataSource, config);
        first.afterPropertiesSet();
        PushEvent stored = first.append(
                        new PushEvent(Topic.parse("orders:1"), null, "{}".getBytes(), null, null, Instant.now()),
                        10,
                        null)
                .block();

        JdbcPushStore second = new JdbcPushStore(dataSource, config);
        second.afterPropertiesSet();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + config.getTable() + "_seq", Integer.class))
                .isEqualTo(1);
        assertThat(second.historyLength(Topic.parse("orders:1"))).isEqualTo(1);
        assertThat(second.byId(stored.id()).block()).isNotNull();
    }

    @Test
    @DisplayName("GIVEN create-schema off and no tables WHEN the store is used SHOULD fail instead of creating them")
    void given_createSchemaOff_when_used_should_failWithoutCreating() {
        PushProperties.Jdbc config = config("push_event_schema_off");
        config.setCreateSchema(false);
        JdbcPushStore store = new JdbcPushStore(dataSource, config);
        store.afterPropertiesSet();

        assertThatThrownBy(() -> store.headId(Topic.parse("orders:1")).block()).isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("GIVEN many threads appending at once WHEN they finish SHOULD hold every event under a distinct id")
    void given_concurrentAppends_when_finished_should_haveDistinctIds() throws Exception {
        PushProperties.Jdbc config = config("push_event_schema_ids");
        JdbcPushStore store = new JdbcPushStore(dataSource, config);
        store.afterPropertiesSet();
        Topic topic = Topic.parse("orders:ids");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Set<Long>>> futures = new java.util.ArrayList<>();
            for (int t = 0; t < 8; t++) {
                futures.add(pool.submit(() -> {
                    Set<Long> ids = new TreeSet<>();
                    for (int i = 0; i < 15; i++) {
                        PushEvent e = store.append(
                                        new PushEvent(topic, null, "{}".getBytes(), null, null, Instant.now()),
                                        1000,
                                        null)
                                .block();
                        ids.add(Long.parseLong(e.id()));
                    }
                    return ids;
                }));
            }
            Set<Long> all = new TreeSet<>();
            for (Future<Set<Long>> f : futures) all.addAll(f.get(60, TimeUnit.SECONDS));

            assertThat(all).hasSize(120);
            assertThat(store.historyLength(topic)).isEqualTo(120);
        } finally {
            pool.shutdownNow();
        }
    }
}
