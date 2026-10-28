package dev.appiaworks.shiguchi.cursus.jdbc;

import dev.appiaworks.shiguchi.cursus.PushOriginStoreContract;
import dev.appiaworks.shiguchi.cursus.PushProperties;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class JdbcPushStoreOriginTest {

    private String table;
    private String sessionTable;
    private JdbcTemplate jdbc;
    private JdbcPushStore store;

    abstract JdbcTestDatabase database();

    @BeforeAll
    void start() {
        JdbcTestDatabase database = database();
        table = database.table("push_event_origin_test");
        sessionTable = JdbcPushSchema.sessionTable(table);
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
        jdbc.update("DELETE FROM " + sessionTable);
    }

    @Test
    @DisplayName(
            "GIVEN events stamped with origins WHEN replayed and a session declaring one starts SHOULD keep push.origin through range and move the cursor over the leading own-origin run")
    void given_stamped_events_when_replayed_and_session_starts_should_keep_origin_and_skip_leading_run() {
        PushOriginStoreContract.verify(store);
    }
}
