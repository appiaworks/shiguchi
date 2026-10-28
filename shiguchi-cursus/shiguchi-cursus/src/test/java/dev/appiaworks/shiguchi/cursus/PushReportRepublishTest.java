package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@DisplayName("Push report republish and tombstone")
class PushReportRepublishTest {

    private final PushProperties props = new PushProperties();
    private final MemoryPushBroker broker = new MemoryPushBroker();
    private final MemoryPushStore store = new MemoryPushStore();
    private final PushMetrics metrics = new PushMetrics(new SimpleMeterRegistry());
    private final MemoryPushArchive archive = new MemoryPushArchive(100, Duration.ofHours(1));
    private final PushReport report;

    PushReportRepublishTest() {
        PushProperties.Namespace orders = new PushProperties.Namespace();
        orders.setQos(Qos.AT_LEAST_ONCE);
        orders.setHistorySize(10);
        props.getNamespaces().put("orders", orders);
        props.getNamespaces().put("ticker", new PushProperties.Namespace());
        PushPublisher publisher = new PushPublisher(props, store, broker, metrics, archive);
        report = new PushReport(
                props,
                broker,
                store,
                metrics,
                new PushConnections(),
                publisher,
                archive,
                new PushRepublishJobs(),
                new PushAudit());
    }

    @Test
    @DisplayName("GIVEN an archived event WHEN republished as a dry run SHOULD describe the send and publish nothing")
    void given_archived_event_when_dry_run_republished_should_describe_send_and_publish_nothing() {
        PushEvent original = publish("orders:1", "{}", Map.of());
        long before = archive.size();

        Map<String, Object> out = report.republish("orders:1", original.id(), null, "fix", "alice", true)
                .block();

        assertThat(out).containsEntry("dryRun", true).containsEntry("target", "orders:1");
        assertThat(out).containsKey("origin");
        assertThat(archive.size()).isEqualTo(before);
    }

    @Test
    @DisplayName("GIVEN an archived event WHEN republished with a reason SHOULD link the new event to its origin")
    void given_archived_event_when_republished_should_link_new_event_to_origin() {
        PushEvent original = publish("orders:1", "{}", Map.of("region", "eu"));

        report.republish("orders:1", original.id(), null, "fix", "alice", false).block();

        Map<String, Object> newest = report.messages(all("orders:1")).get(0);
        assertThat(newest.get("id")).isNotEqualTo(original.id());
        assertThat(newest.get("attributes"))
                .asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("region", "eu")
                .containsEntry("origin_id", original.id())
                .containsEntry("origin_topic", "orders:1")
                .containsEntry("republished_by", "alice")
                .containsEntry("reason", "fix");
    }

    @Test
    @DisplayName("GIVEN a target in an undeclared namespace WHEN republished SHOULD refuse with unknown_namespace")
    void given_undeclared_target_namespace_when_republished_should_refuse_unknown_namespace() {
        PushEvent original = publish("orders:1", "{}", Map.of());

        assertRefused(
                report.republish("orders:1", original.id(), "other:1", null, "alice", false), "unknown_namespace");
    }

    @Test
    @DisplayName("GIVEN an allow-list without the origin namespace WHEN republished SHOULD refuse with forbidden")
    void given_allow_list_without_origin_namespace_when_republished_should_refuse_forbidden() {
        PushEvent original = publish("orders:1", "{}", Map.of());
        props.getAdmin().getRepublish().setNamespaces(List.of("ticker"));

        assertRefused(report.republish("orders:1", original.id(), null, null, "alice", false), "forbidden");
    }

    @Test
    @DisplayName("GIVEN a tombstoned event WHEN republished SHOULD refuse with conflict")
    void given_tombstoned_event_when_republished_should_refuse_conflict() {
        PushEvent original = publish("orders:1", "{}", Map.of());
        report.tombstone("orders:1", original.id(), "alice").block();

        assertRefused(report.republish("orders:1", original.id(), null, null, "alice", false), "conflict");
    }

    @Test
    @DisplayName("GIVEN an unknown event id WHEN republished SHOULD refuse with not_found")
    void given_unknown_event_id_when_republished_should_refuse_not_found() {
        publish("orders:1", "{}", Map.of());

        assertRefused(report.republish("orders:1", "0000000000000000999", null, null, "alice", false), "not_found");
    }

    @Test
    @DisplayName("GIVEN two matching events WHEN a bulk republish is a dry run SHOULD report the count and not capped")
    void given_two_matching_events_when_bulk_dry_run_should_report_count_not_capped() {
        publish("orders:1", "{}", Map.of());
        publish("orders:1", "{}", Map.of());

        Map<String, Object> out = report.republishAll(all("orders"), null, null, "alice", true);

        assertThat(out)
                .containsEntry("dryRun", true)
                .containsEntry("matched", 2)
                .containsEntry("capped", false);
    }

    @Test
    @DisplayName(
            "GIVEN two matching events and a batch limit of one WHEN bulk republished SHOULD cap the dry run and refuse the real run")
    void given_batch_limit_of_one_when_bulk_republished_should_cap_dry_run_and_refuse_real_run() {
        props.getAdmin().getRepublish().setMaxBatch(1);
        publish("orders:1", "{}", Map.of());
        publish("orders:1", "{}", Map.of());

        Map<String, Object> dry = report.republishAll(all("orders"), null, null, "alice", true);
        assertThat(dry).containsEntry("capped", true).containsEntry("matched", 1);

        assertThatThrownBy(() -> report.republishAll(all("orders"), null, null, "alice", false))
                .isInstanceOfSatisfying(
                        PushException.class, e -> assertThat(e.code()).isEqualTo("bad_request"));
    }

    @Test
    @DisplayName("GIVEN two matching events WHEN bulk republished for real SHOULD run a job that publishes both")
    @SuppressWarnings("unchecked")
    void given_two_matching_events_when_bulk_republished_should_run_job_to_completion() throws InterruptedException {
        publish("orders:1", "{}", Map.of());
        publish("orders:1", "{}", Map.of());
        long before = archive.size();

        Map<String, Object> out = report.republishAll(all("orders"), null, "ops", "alice", false);
        Map<String, Object> started = (Map<String, Object>) out.get("job");
        String id = (String) started.get("id");

        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        Map<String, Object> job = report.job(id);
        while (!"DONE".equals(job.get("state")) && System.nanoTime() < deadline) {
            Thread.sleep(10);
            job = report.job(id);
        }
        assertThat(job).containsEntry("state", "DONE").containsEntry("published", 2L);
        assertThat(archive.size()).isEqualTo(before + 2);
    }

    @Test
    @DisplayName("GIVEN an archived event WHEN tombstoned SHOULD leave the history and show as tombstoned")
    void given_archived_event_when_tombstoned_should_leave_history_and_show_tombstoned() {
        PushEvent original = publish("orders:1", "{}", Map.of());

        assertThat(report.tombstone("orders:1", original.id(), "alice").block()).isTrue();

        StepVerifier.create(store.range(Topic.parse("orders:1"), null, 10).map(PushEvent::id))
                .expectNextCount(0)
                .verifyComplete();
        assertThat(report.message("orders:1", original.id())).containsEntry("tombstoned", true);
    }

    private void assertRefused(Mono<Map<String, Object>> republish, String code) {
        StepVerifier.create(republish)
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(PushException.class);
                    assertThat(((PushException) e).code()).isEqualTo(code);
                })
                .verify();
    }

    private PushEvent publish(String topic, String json, Map<String, String> attributes) {
        return report.publish(topic, json.getBytes(StandardCharsets.UTF_8), attributes)
                .block();
    }

    private static PushArchive.Query all(String topic) {
        return new PushArchive.Query(topic, null, null, null, null, null, null, null, 100);
    }
}
