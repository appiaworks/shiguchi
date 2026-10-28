package dev.appiaworks.shiguchi.cursus.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PostgresPushSchema delivery log table")
class PostgresPushSchemaDeliveryTest {

    @Test
    @DisplayName("GIVEN an event table name WHEN the delivery table is named SHOULD append _delivery")
    void given_eventTable_when_deliveryTableNamed_should_appendSuffix() {
        assertThat(PostgresPushSchema.deliveryTable("push_event")).isEqualTo("push_event_delivery");
    }

    @Test
    @DisplayName("GIVEN an event table WHEN the statements are built SHOULD include the delivery table creation")
    void given_eventTable_when_statementsBuilt_should_createDeliveryTable() {
        List<String> statements = PostgresPushSchema.statements("push_event");

        assertThat(statements).anyMatch(s -> s.startsWith("CREATE TABLE IF NOT EXISTS push_event_delivery ("));
        assertThat(statements)
                .anyMatch(s -> s.startsWith("CREATE INDEX IF NOT EXISTS") && s.contains("push_event_delivery"));
    }
}
