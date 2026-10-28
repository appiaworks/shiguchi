package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Push audit log")
class PushAuditTest {

    @Test
    @DisplayName("GIVEN two recorded writes WHEN listed SHOULD return the newest first")
    void given_two_writes_when_listed_should_return_newest_first() {
        PushAudit audit = new PushAudit();
        audit.record("alice", "tombstone", Map.of("id", "1"), "ok");
        audit.record("bob", "publish", Map.of(), "ok");

        assertThat(audit.list(10)).extracting(PushAudit.Entry::action).containsExactly("publish", "tombstone");
    }

    @Test
    @DisplayName("GIVEN three recorded writes WHEN listed with limit two SHOULD return the two newest")
    void given_three_writes_when_listed_with_limit_should_return_two_newest() {
        PushAudit audit = new PushAudit();
        audit.record("alice", "a", Map.of(), "ok");
        audit.record("alice", "b", Map.of(), "ok");
        audit.record("alice", "c", Map.of(), "ok");

        assertThat(audit.list(2)).extracting(PushAudit.Entry::action).containsExactly("c", "b");
    }

    @Test
    @DisplayName("GIVEN a capacity of two and three writes WHEN listed SHOULD keep only the newest two")
    void given_full_log_when_another_write_recorded_should_drop_oldest() {
        PushAudit audit = new PushAudit(2);
        audit.record("alice", "first", Map.of(), "ok");
        audit.record("alice", "second", Map.of(), "ok");
        audit.record("alice", "third", Map.of(), "ok");

        assertThat(audit.size()).isEqualTo(2);
        assertThat(audit.list(10)).extracting(PushAudit.Entry::action).containsExactly("third", "second");
    }

    @Test
    @DisplayName("GIVEN no principal WHEN recorded SHOULD name the caller anonymous")
    void given_no_principal_when_recorded_should_name_caller_anonymous() {
        PushAudit audit = new PushAudit();
        audit.record(null, "publish", null, "ok");

        assertThat(audit.list(1).get(0).principal()).isEqualTo("anonymous");
    }

    @Test
    @DisplayName("GIVEN arguments changed after recording WHEN listed SHOULD keep the arguments as recorded")
    void given_arguments_changed_after_recording_when_listed_should_keep_recorded_arguments() {
        PushAudit audit = new PushAudit();
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("id", "1");
        audit.record("alice", "tombstone", arguments, "ok");
        arguments.put("id", "2");

        assertThat(audit.list(1).get(0).arguments()).containsEntry("id", "1");
    }

    @Test
    @DisplayName("GIVEN null arguments WHEN recorded SHOULD list empty arguments")
    void given_null_arguments_when_recorded_should_list_empty_arguments() {
        PushAudit audit = new PushAudit();
        audit.record("alice", "publish", null, "ok");

        assertThat(audit.list(1).get(0).arguments()).isEmpty();
    }

    @Test
    @DisplayName("GIVEN a capacity of zero WHEN constructed SHOULD throw IllegalArgumentException")
    void given_zero_capacity_when_constructed_should_throw() {
        assertThatThrownBy(() -> new PushAudit(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
