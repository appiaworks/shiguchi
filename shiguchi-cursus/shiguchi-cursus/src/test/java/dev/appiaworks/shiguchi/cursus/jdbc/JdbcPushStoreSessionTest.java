package dev.appiaworks.shiguchi.cursus.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.appiaworks.shiguchi.cursus.PushEvent;
import dev.appiaworks.shiguchi.cursus.PushProperties;
import dev.appiaworks.shiguchi.cursus.PushStore.SessionCursor;
import dev.appiaworks.shiguchi.cursus.Topic;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class JdbcPushStoreSessionTest {

    private static final Duration HOUR = Duration.ofHours(1);

    private JdbcTestDatabase database;
    private String table;
    private String sessionTable;
    private JdbcTemplate jdbc;
    private JdbcPushStore store;

    private final Topic orders = Topic.parse("orders:123");
    private final Topic payments = Topic.parse("payments:9");

    abstract JdbcTestDatabase database();

    @BeforeAll
    void start() {
        database = database();
        table = database.table("push_event_session_test");
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

    private String append(Topic topic) {
        PushEvent event = new PushEvent(topic, null, "{}".getBytes(), null, null, null);
        return store.append(event, 10, HOUR).block().id();
    }

    @Test
    @DisplayName("GIVEN the JDBC store WHEN session support and table naming are checked "
            + "SHOULD keep sessions in push_event_session")
    void given_jdbcStore_when_sessionSupportAndNamingChecked_should_keepSessionsInSessionTable() {
        assertThat(store.persistentSessions()).isTrue();
        assertThat(JdbcPushSchema.sessionTable("push_event")).isEqualTo("push_event_session");
        assertThat(JdbcPushSchema.statements(store.dialect(), "push_event"))
                .anyMatch(sql -> sql.startsWith("CREATE TABLE IF NOT EXISTS push_event_session (")
                        || sql.startsWith("CREATE TABLE push_event_session ("));
    }

    @Test
    @DisplayName("GIVEN a topic with no events WHEN its head is read before and after three appends "
            + "SHOULD be empty, then the last id")
    void given_topicWithoutEvents_when_headIdRead_should_beEmptyThenLastAppendedId() {
        assertThat(store.headId(orders).blockOptional()).isEmpty();

        append(orders);
        append(orders);
        String last = append(orders);

        assertThat(store.headId(orders).block()).isEqualTo(last);
    }

    @Test
    @DisplayName("GIVEN an unknown session WHEN its cursor is read and then acked "
            + "SHOULD have no cursor, then the acked id")
    void given_unknownSession_when_cursorReadAndAcked_should_returnAckedId() {
        assertThat(store.cursor("alice", orders).blockOptional()).isEmpty();

        append(orders);
        String id2 = append(orders);
        append(orders);
        store.ack("alice", orders, id2, HOUR).block();

        assertThat(store.cursor("alice", orders).block()).isEqualTo(id2);
    }

    @Test
    @DisplayName("GIVEN a cursor at the third event WHEN an older id is acked SHOULD keep the cursor where it was")
    void given_cursorAtThirdEvent_when_olderAndSameIdAcked_should_never_moveBack() {
        String id1 = append(orders);
        append(orders);
        String id3 = append(orders);

        store.ack("alice", orders, id3, HOUR).block();
        store.ack("alice", orders, id1, HOUR).block();
        assertThat(store.cursor("alice", orders).block()).isEqualTo(id3);

        store.ack("alice", orders, id3, HOUR).block();
        assertThat(store.cursor("alice", orders).block()).isEqualTo(id3);
    }

    @Test
    @DisplayName("GIVEN a fresh session WHEN it is acked with the first id SHOULD replay every kept event "
            + "from the start")
    void given_freshSession_when_ackedWithFirstId_should_replayWholeHistory() {
        String id1 = append(orders);
        String id2 = append(orders);
        String id3 = append(orders);

        store.ack("alice", orders, store.firstId(), HOUR).block();

        assertThat(store.cursor("alice", orders).block()).isEqualTo(store.firstId());
        assertThat(store.range(orders, store.firstId(), 10).collectList().block())
                .extracting(PushEvent::id)
                .containsExactly(id1, id2, id3);
    }

    @Test
    @DisplayName("GIVEN two sessions on two topics WHEN listed SHOULD return both cursors with a future expiry, "
            + "narrowed by prefix and capped by limit")
    void given_twoSessions_when_listed_should_returnCursorsFilteredAndCapped() {
        String idA = append(orders);
        String idB = append(payments);
        store.ack("alice", orders, idA, HOUR).block();
        store.ack("dev1@-", payments, idB, HOUR).block();

        List<SessionCursor> all = store.sessions(null, 10).collectList().block();
        assertThat(all)
                .extracting(SessionCursor::session, SessionCursor::topic, SessionCursor::cursor)
                .containsExactlyInAnyOrder(tuple("alice", orders, idA), tuple("dev1@-", payments, idB));
        assertThat(all).allSatisfy(s -> assertThat(s.expiresAt()).isNotNull().isAfter(Instant.now()));

        assertThat(store.sessions("dev", 10).collectList().block())
                .extracting(SessionCursor::session)
                .containsExactly("dev1@-");
        assertThat(store.sessions(null, 1).collectList().block()).hasSize(1);
    }

    @Test
    @DisplayName("GIVEN a session with a cursor WHEN it is dropped SHOULD report one cursor gone, "
            + "and dropping it again SHOULD report none")
    void given_sessionWithCursor_when_dropped_should_removeIt() {
        String id = append(orders);
        store.ack("alice", orders, id, HOUR).block();

        assertThat(store.dropSession("alice").block()).isEqualTo(1L);
        assertThat(store.cursor("alice", orders).blockOptional()).isEmpty();
        assertThat(store.dropSession("alice").block()).isZero();
    }

    @Test
    @DisplayName("GIVEN a cursor acked with a 200 ms expiry WHEN 400 ms pass "
            + "SHOULD be swept, and no longer readable or listed")
    void given_cursorWithShortExpiry_when_expired_should_sweepAndHideIt() throws InterruptedException {
        String id = append(orders);
        store.ack("alice", orders, id, Duration.ofMillis(200)).block();

        Thread.sleep(400);

        assertThat(store.sweepSessions().block()).isEqualTo(1L);
        assertThat(store.cursor("alice", orders).blockOptional()).isEmpty();
        assertThat(store.sessions(null, 10).collectList().block()).isEmpty();
    }
}
