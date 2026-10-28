package dev.appiaworks.shiguchi.cursus.postgres;

import dev.appiaworks.shiguchi.cursus.PushGapStoreContract;
import dev.appiaworks.shiguchi.cursus.PushProperties;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;

@DisplayName("PostgresPushStore: the highest id the history dropped")
class PostgresPushStoreGapTest {

    private static final String TABLE = "push_event_gap_test";
    private static final String ARCHIVE_TABLE = "push_event_gap_archive_test";
    private static final Duration WAIT = Duration.ofSeconds(15);

    private static DatabaseClient client;
    private static PostgresPushStore plain;
    private static PostgresPushStore archived;

    @BeforeAll
    static void start() {
        client = EmbeddedPg.client();
        plain = store(TABLE, Duration.ZERO);
        archived = store(ARCHIVE_TABLE, Duration.ofHours(1));
    }

    private static PostgresPushStore store(String table, Duration retention) {
        PushProperties.Postgres config = new PushProperties.Postgres();
        config.setTable(table);
        config.setTrimEvery(1);
        config.setSweepInterval(Duration.ZERO);
        PushProperties.Archive archive = new PushProperties.Archive();
        archive.setRetention(retention);
        PostgresPushStore store = new PostgresPushStore(client, config, archive);
        store.afterPropertiesSet();
        return store;
    }

    @AfterAll
    static void stop() {
        if (plain != null) plain.destroy();
        if (archived != null) archived.destroy();
    }

    @BeforeEach
    void clear() {
        for (String t : new String[] {TABLE, ARCHIVE_TABLE}) {
            client.sql("DELETE FROM " + t).fetch().rowsUpdated().block();
            client.sql("DELETE FROM " + PostgresPushSchema.trimTable(t))
                    .fetch()
                    .rowsUpdated()
                    .block();
        }
    }

    private static PushGapStoreContract.Engine engine(PostgresPushStore store) {
        return new PushGapStoreContract.Engine(
                store,
                () -> store.sweep().block(WAIT),
                (topic, id) -> store.tombstone(topic, id, "test").block(WAIT));
    }

    @Test
    @DisplayName("GIVEN a topic where nothing was removed WHEN asking SHOULD answer empty")
    void given_nothingRemoved_when_askingTrimmedThrough_should_beEmpty() {
        PushGapStoreContract.nothingTrimmed(engine(plain));
    }

    @Test
    @DisplayName(
            "GIVEN a size trim that deleted the rows WHEN asking SHOULD answer the last deleted id from the watermark")
    void given_sizeTrimDeletedRows_when_asking_should_answerWatermark() {
        PushGapStoreContract.trimmedBySize(engine(plain));
    }

    @Test
    @DisplayName(
            "GIVEN a size trim that only marked the rows out of the history (archive on) WHEN asking SHOULD answer the last marked id")
    void given_sizeTrimWithArchive_when_asking_should_answerLastMarkedId() {
        PushGapStoreContract.trimmedBySize(engine(archived));
    }

    @Test
    @DisplayName("GIVEN events past their ttl WHEN asking SHOULD answer the last expired id")
    void given_expiredEvents_when_asking_should_answerLastExpiredId() throws InterruptedException {
        PushGapStoreContract.expiredByTtl(engine(plain));
    }

    @Test
    @DisplayName("GIVEN expired rows the sweep deleted WHEN asking SHOULD still answer the last expired id")
    void given_sweptExpiredRows_when_asking_should_answerWatermark() throws InterruptedException {
        PushGapStoreContract.sweptAfterExpiry(engine(plain));
    }

    @Test
    @DisplayName(
            "GIVEN expired rows the sweep deleted with the archive on WHEN asking SHOULD still answer the last expired id")
    void given_sweptExpiredRowsWithArchive_when_asking_should_answerWatermark() throws InterruptedException {
        PushGapStoreContract.sweptAfterExpiry(engine(archived));
    }

    @Test
    @DisplayName("GIVEN a redacted event, swept or not WHEN asking SHOULD not count it as removed")
    void given_redactedEvent_when_asking_should_notCountIt() {
        PushGapStoreContract.redactedIsNotAGap(engine(archived));
    }
}
