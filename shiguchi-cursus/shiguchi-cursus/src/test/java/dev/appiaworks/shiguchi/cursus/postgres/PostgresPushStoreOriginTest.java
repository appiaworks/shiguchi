package dev.appiaworks.shiguchi.cursus.postgres;

import dev.appiaworks.shiguchi.cursus.PushOriginStoreContract;
import dev.appiaworks.shiguchi.cursus.PushProperties;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;

@DisplayName("PostgresPushStore: origin attribute and session skip")
class PostgresPushStoreOriginTest {

    private static final String TABLE = "push_event_origin_test";
    private static final String SESSION_TABLE = PostgresPushSchema.sessionTable(TABLE);

    private static DatabaseClient client;
    private static PostgresPushStore store;

    @BeforeAll
    static void start() {
        client = EmbeddedPg.client();
        PushProperties.Postgres config = new PushProperties.Postgres();
        config.setTable(TABLE);
        config.setTrimEvery(1);
        config.setSweepInterval(Duration.ZERO);
        store = new PostgresPushStore(client, config);
        store.afterPropertiesSet();
    }

    @AfterAll
    static void stop() {
        if (store != null) store.destroy();
    }

    @BeforeEach
    void clear() {
        client.sql("DELETE FROM " + TABLE).fetch().rowsUpdated().block();
        client.sql("DELETE FROM " + SESSION_TABLE).fetch().rowsUpdated().block();
    }

    @Test
    @DisplayName(
            "GIVEN events stamped with origins WHEN replayed and a session declaring one starts SHOULD keep push.origin through range and move the cursor over the leading own-origin run")
    void given_stamped_events_when_replayed_and_session_starts_should_keep_origin_and_skip_leading_run() {
        PushOriginStoreContract.verify(store);
    }
}
