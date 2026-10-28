package dev.appiaworks.shiguchi.cursus.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.cursus.PushEvent;
import dev.appiaworks.shiguchi.cursus.PushProperties;
import dev.appiaworks.shiguchi.cursus.Topic;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class JdbcPushStoreExplicitIdTest {

    private static final Duration HOUR = Duration.ofHours(1);

    private JdbcTestDatabase database;
    private String table;
    private JdbcTemplate jdbc;
    private JdbcPushStore store;

    private final Topic topic = Topic.parse("ticks:a");

    abstract JdbcTestDatabase database();

    @BeforeAll
    void start() {
        database = database();
        table = database.table("push_event_explicit_id_test");
        jdbc = new JdbcTemplate(database.dataSource(getClass().getSimpleName()));
        PushProperties.Jdbc config = new PushProperties.Jdbc();
        config.setTable(table);
        config.setTrimEvery(1);
        config.setSweepInterval(Duration.ZERO);
        store = new JdbcPushStore(jdbc.getDataSource(), config);
        store.afterPropertiesSet();
    }

    @AfterAll
    void stop() {
        if (store != null) store.destroy();
    }

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM " + table);
    }

    @Test
    @DisplayName("GIVEN the table-wide id sequence WHEN asked SHOULD not keep caller-chosen ids")
    void given_tableWideSequence_when_asked_should_notKeepCallerIds() {
        assertThat(store.explicitIds()).isFalse();
    }

    @Test
    @DisplayName("GIVEN events carrying upstream.id WHEN reading the head event "
            + "SHOULD return the newest with its attributes")
    void given_eventsWithUpstreamId_when_headEventRead_should_returnNewestWithAttributes() {
        store.append(new PushEvent(topic, null, "{}".getBytes(), Map.of("upstream.id", "4"), null, null), 10, HOUR)
                .block();
        store.append(new PushEvent(topic, null, "{}".getBytes(), Map.of("upstream.id", "9"), null, null), 10, HOUR)
                .block();

        PushEvent head = store.headEvent(topic).block();

        assertThat(head.attributes()).containsEntry("upstream.id", "9");
        assertThat(store.headEvent(Topic.parse("ticks:none")).block()).isNull();
    }
}
