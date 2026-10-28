package dev.appiaworks.shiguchi.cursus.jdbc;

import dev.appiaworks.shiguchi.cursus.PushGapStoreContract;
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
abstract class JdbcPushStoreGapTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    private JdbcTemplate jdbc;
    private String table;
    private String archiveTable;
    private JdbcPushStore plain;
    private JdbcPushStore archived;

    abstract JdbcTestDatabase database();

    @BeforeAll
    void start() {
        JdbcTestDatabase database = database();
        jdbc = new JdbcTemplate(database.dataSource(getClass().getSimpleName()));
        table = database.table("push_event_gap_test");
        archiveTable = database.table("push_event_gap_archive_test");
        plain = store(table, Duration.ZERO);
        archived = store(archiveTable, Duration.ofHours(1));
    }

    private JdbcPushStore store(String name, Duration retention) {
        PushProperties.Jdbc config = new PushProperties.Jdbc();
        config.setTable(name);
        config.setTrimEvery(1);
        config.setSweepInterval(Duration.ZERO);
        PushProperties.Archive archive = new PushProperties.Archive();
        archive.setRetention(retention);
        JdbcPushStore store = new JdbcPushStore(jdbc.getDataSource(), config, archive);
        store.afterPropertiesSet();
        return store;
    }

    @AfterAll
    void stop() {
        if (plain != null) plain.destroy();
        if (archived != null) archived.destroy();
    }

    @BeforeEach
    void clear() {
        for (String t : new String[] {table, archiveTable}) {
            jdbc.update("DELETE FROM " + t);
            jdbc.update("DELETE FROM " + JdbcPushSchema.trimTable(t));
        }
    }

    private static PushGapStoreContract.Engine engine(JdbcPushStore store) {
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
