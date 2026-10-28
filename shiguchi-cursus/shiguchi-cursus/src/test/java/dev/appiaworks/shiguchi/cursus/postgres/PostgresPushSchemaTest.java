package dev.appiaworks.shiguchi.cursus.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PostgresPushSchema")
class PostgresPushSchemaTest {

    @Test
    @DisplayName("GIVEN plain and schema-qualified names WHEN validating the table SHOULD return them unchanged")
    void given_plainAndQualifiedNames_when_validatingTable_should_returnUnchanged() {
        assertThat(PostgresPushSchema.table("push_event")).isEqualTo("push_event");
        assertThat(PostgresPushSchema.table("push.events")).isEqualTo("push.events");
    }

    @Test
    @DisplayName("GIVEN unsafe or malformed names WHEN validating the table SHOULD reject them naming the property")
    void given_unsafeNames_when_validatingTable_should_reject() {
        for (String bad : new String[] {"push-event", "1abc", null, "x; drop table y"}) {
            assertThatThrownBy(() -> PostgresPushSchema.table(bad))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cursus.postgres.table");
        }
    }

    @Test
    @DisplayName("GIVEN a plain identifier WHEN validating the channel SHOULD accept it")
    void given_plainIdentifier_when_validatingChannel_should_accept() {
        assertThat(PostgresPushSchema.channel("shiguchi_cursus")).isEqualTo("shiguchi_cursus");
    }

    @Test
    @DisplayName("GIVEN a dotted or spaced name WHEN validating the channel SHOULD reject it")
    void given_dottedOrSpacedName_when_validatingChannel_should_reject() {
        assertThatThrownBy(() -> PostgresPushSchema.channel("a.b")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PostgresPushSchema.channel("a b")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName(
            "GIVEN a table WHEN building the statements SHOULD create the table, add the newer columns, then two idempotent indexes")
    void given_table_when_buildingStatements_should_createTableAndIndexes() {
        List<String> statements = PostgresPushSchema.statements("push_event");

        assertThat(statements).hasSize(18);
        assertThat(statements.get(0))
                .startsWith("CREATE TABLE IF NOT EXISTS push_event (")
                .contains("id BIGSERIAL PRIMARY KEY")
                .contains("history BOOLEAN NOT NULL DEFAULT TRUE");
        assertThat(statements.subList(1, 5))
                .allMatch(s -> s.startsWith("ALTER TABLE push_event ADD COLUMN IF NOT EXISTS"));
        assertThat(statements.get(5)).contains("IF NOT EXISTS push_event_topic_id");
        assertThat(statements.get(6)).contains("IF NOT EXISTS push_event_expires");
    }

    @Test
    @DisplayName("GIVEN the archive on WHEN building the statements SHOULD add three search indexes, one a GIN index")
    void given_archiveOn_when_buildingStatements_should_addThreeSearchIndexes() {
        List<String> plain = PostgresPushSchema.statements("push_event", false);
        List<String> archive = PostgresPushSchema.statements("push_event", true);

        assertThat(archive).hasSize(plain.size() + 3);
        assertThat(archive).anySatisfy(s -> assertThat(s).contains("USING GIN"));
    }

    @Test
    @DisplayName(
            "GIVEN the push_event table WHEN naming its lease table SHOULD be push_event_lease and create it idempotently")
    void given_eventTable_when_buildingLeaseTable_should_createIdempotentLeaseTable() {
        assertThat(PostgresPushSchema.leaseTable("push_event")).isEqualTo("push_event_lease");
        assertThat(PostgresPushSchema.statements("push_event"))
                .anySatisfy(s -> assertThat(s)
                        .startsWith("CREATE TABLE IF NOT EXISTS push_event_lease (")
                        .contains("key TEXT PRIMARY KEY")
                        .contains("expires_at TIMESTAMPTZ NOT NULL"));
    }

    @Test
    @DisplayName("GIVEN the archive off WHEN building the statements SHOULD bring the history column up to date")
    void given_archiveOff_when_buildingStatements_should_addHistoryColumn() {
        assertThat(PostgresPushSchema.statements("push_event", false))
                .anySatisfy(s -> assertThat(s).contains("ADD COLUMN IF NOT EXISTS history"));
    }
}
